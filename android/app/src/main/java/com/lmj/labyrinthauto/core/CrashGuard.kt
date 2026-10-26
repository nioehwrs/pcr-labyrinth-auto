package com.lmj.labyrinthauto.core

import android.content.Context
import android.os.Build
import android.util.Log
import com.lmj.labyrinthauto.frida.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ★崩溃 / 非正常退出的"自证"（2026-10-26 用户：**Android 15 上有用户反馈"跑着跑着游戏和工具都没了"**）。
 *
 * 为什么需要它：现象是"进程直接消失"，而这种死法恰恰**什么日志都留不下** ——
 * `LogFile` 逐行 flush 只保证"最后一行写完"，但**死因**（谁动的手）是空白，
 * 光看日志只能停在"点了 xxx"那一行，抓不到根因。
 *
 * 三种死法、三条证据链，正好区分得开：
 * 1. **Java 未捕获异常**（Kotlin 代码里抛到顶）⇒ 本类兜底，**同步**写 `logs/crash_<时间>.txt`
 *    （不走 LogFile 的写盘队列，写完再交给系统默认 handler）；
 * 2. **native 崩溃**（`libfrida_bridge.so` / 游戏里的 agent）⇒ Java 抓不到，只能靠系统
 *    tombstone（`/data/tombstones`，root 可读）—— [tombstoneHint] 会把最近几条的 **Cmdline**
 *    读出来，一眼看出崩的是 `com.lmj.labyrinthauto` 还是游戏；
 * 3. **被 SIGKILL / LMK / 整机（模拟器）重启** ⇒ 任何代码都来不及跑，只能靠"上次没正常收尾"
 *    反推：[marker] 每 [HEARTBEAT_MS] 刷一次时间戳，下次启动发现它还在 ⇒ 上次是**突然死亡**，
 *    并把"最后存活时刻"写进日志 —— 拿它去和 session 日志的最后一行对齐，就能定位死在第几步。
 *
 * 用法：`LmjApp.onCreate` 里 [install]（要最早，越早越不漏），`LogFile.init` 之后 [report]。
 */
object CrashGuard {
    private const val TAG = "LabAuto"
    private const val MARKER_NAME = "last_alive.txt"

    /** 心跳间隔：15s 足够定位"死在哪一步"，又不至于频繁写盘 */
    private const val HEARTBEAT_MS = 15_000L

    /** 旧 marker 的心跳在这个时间内 ⇒ 判定为"刚刚突然死掉"（更久以前多半是用户主动清了后台） */
    private const val FRESH_MS = 30 * 60_000L

    private val stampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val nameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private var dirs: List<File> = emptyList()
    private var marker: File? = null
    private var installed = false

    /**
     * 上次进程的非正常退出（null = 首次启动 / 上次走完了 / 记录太旧）。
     *
     * ★2026-10-26 用真实反馈日志复盘出来的形状：Android 15 那台死在"进入战斗后 1 秒"，
     * 日志直接断在 `页面 → ?(29)`，连战斗中每 30s 的心跳都没有 ⇒ 进程是**当场消失**的。
     * 所以这里除了"死了"，还要带上"死的时候在干什么"（[view] / [maze]），用户一眼能对上。
     */
    data class PrevExit(
        val pid: String,
        val at: String,       // "yyyy-MM-dd HH:mm:ss"
        val agoSec: Long,
        val why: String,
        val view: String,
        val maze: String,
    )

    @Volatile
    var previousExit: PrevExit? = null
        private set

    /** 进程入口尽早调一次（重复调无害） */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        dirs = runCatching { buildDirs(ctx) }.getOrDefault(emptyList())
        val m = File(ctx.filesDir, MARKER_NAME)
        marker = m
        previousExit = runCatching { readPrevious(m) }.getOrNull()
        runCatching { writeMarker(m, "启动") }
        runCatching { startHeartbeat(m) }
        runCatching { installHandler(ctx) }
    }

    /**
     * 把"上次是不是突然死的"打进日志（**必须在 `LogFile.init` 之后调**，否则只会进 logcat）。
     * 若确认上次非正常结束，再顺手看一眼系统 tombstone（只在**已经拿过 root** 时才查，
     * 避免平白弹授权框）。
     */
    fun report() {
        val p = previousExit ?: return
        val where = whereText(p)
        val hhmm = p.at.takeIf { it.length >= 16 }?.substring(11, 16) ?: "?"
        // 日志：完整信息（排查用）
        AppState.addLog("⚠上次进程（pid=${p.pid}）在 $hhmm 之后突然没了，最后停在：$where（why=${p.why}）")
        AppState.addLog("　没有正常收尾 ⇒ 被系统杀 / native 崩溃 / 整机重启，三者之一；崩溃栈看 logs/crash_*.txt")
        // 界面：一句话（首页横幅，可关掉）
        AppState.setAbnormalExit("上次运行在 $hhmm（$where）突然中断，可能是被系统关闭或崩溃")
        if (RootShell.form == null) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { tombstoneHint() }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { AppState.addLog(it) }
        }
    }

    /**
     * 把"最后停在哪"翻成人话。
     *
     * ★`?(29)` 就是**战斗页**：真实日志里每次 `进入战斗` 之后紧跟的就是 `页面 → ?(29)`
     *   （2026-10-26 复盘，那份日志里出现 59 次），而 agent 现场报文里同一时刻是 `view=ViewBattle`。
     */
    private fun whereText(p: PrevExit): String = when {
        p.view.contains("Battle", ignoreCase = true) || p.view.contains("?(29)") -> "战斗中"
        p.maze.isNotBlank() && p.maze != "-" && p.maze != "?" -> "迷宫里 ${p.maze}"
        else -> "没在跑图"
    }

    /**
     * 最近几条系统 tombstone 的"崩溃进程名"。
     *
     * 输出形如：`tombstone_07: Cmdline: com.lmj.labyrinthauto` —— 一眼分清
     * 是**我们**崩了（native 库问题）还是**游戏**崩了（跟我们无关）。
     * 读不到（没 root / 路径不同）就返回空串，不当作错误。
     */
    suspend fun tombstoneHint(): String {
        val cmd = "for f in \$(ls -t /data/tombstones 2>/dev/null | grep -a '^tombstone' | head -3); do " +
            "printf '%s: ' \"\$f\"; grep -m1 -a 'Cmdline' /data/tombstones/\$f 2>/dev/null || echo '(无 Cmdline)'; done"
        val r = RootShell.exec(cmd, 8_000L)
        val lines = r.out.lines().map { it.trim() }.filter { it.isNotEmpty() && it.contains("tombstone") }
        if (lines.isEmpty()) return ""
        return "系统 tombstone（最近的 native 崩溃进程）：" + lines.joinToString("｜")
    }

    // ────────────────────────── 内部 ──────────────────────────

    private fun buildDirs(ctx: Context): List<File> {
        val out = ArrayList<File>()
        runCatching { File(ctx.filesDir, "logs").also { it.mkdirs() } }
            .getOrNull()?.takeIf { it.isDirectory }?.let { out.add(it) }
        runCatching {
            ctx.getExternalFilesDir(null)?.let { ext ->
                File(ext, "logs").also { it.mkdirs() }
            }
        }.getOrNull()?.takeIf { it.isDirectory }?.let { out.add(it) }
        return out
    }

    /** 读上次的心跳：文件还在 ⇒ 上次进程**没走正常收尾**（被 SIGKILL / native 崩溃 / 整机重启） */
    private fun readPrevious(m: File): PrevExit? {
        if (!m.exists()) return null
        val txt = runCatching { m.readText() }.getOrNull()?.trim().orEmpty()
        runCatching { m.delete() }                    // 读完立刻删 ⇒ 只报一次
        if (txt.isEmpty()) return null
        val beat = Regex("beat=(\\d{13})").find(txt)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val agoSec = (System.currentTimeMillis() - beat) / 1000
        // 太久以前的不报：多半是用户自己清了后台、或隔天才打开，属于正常
        if (agoSec > FRESH_MS / 1000) return null
        return PrevExit(
            pid = Regex("pid=(\\d+)").find(txt)?.groupValues?.get(1) ?: "?",
            at = Regex("at=([^|]+)").find(txt)?.groupValues?.get(1)?.trim() ?: "?",
            agoSec = agoSec,
            why = Regex("why=([^|]*)").find(txt)?.groupValues?.get(1).orEmpty(),
            view = Regex("view=(\\S+)").find(txt)?.groupValues?.get(1) ?: "-",
            maze = Regex("maze=(\\S+)").find(txt)?.groupValues?.get(1) ?: "-",
        )
    }

    /** 写/刷心跳（pid + 时刻 + 当前状态），供下次启动判断"是不是突然死的" */
    private fun writeMarker(m: File, why: String) {
        val now = System.currentTimeMillis()
        runCatching {
            m.writeText(
                "pid=${android.os.Process.myPid()}|beat=$now|at=${stampFmt.format(Date(now))}" +
                    "|why=$why|${stateSnapshot()}",
            )
        }
    }

    private fun startHeartbeat(m: File) {
        Thread({
            while (true) {
                runCatching { Thread.sleep(HEARTBEAT_MS) }
                runCatching { writeMarker(m, "心跳") }
            }
        }, "lmj-alive").apply { isDaemon = true }.start()
    }

    private fun installHandler(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeCrash(t, e) }
            // 把 marker 标成"崩了"，下次启动的结论就从"被系统杀"变成"Java 崩溃"
            runCatching { marker?.let { writeMarker(it, "CRASH:${e.javaClass.simpleName}") } }
            // 证据已落盘 → 交回系统默认 handler（进程照样会被结束，但终于有据可查）
            if (prev != null) prev.uncaughtException(t, e)
        }
        runCatching { Log.i(TAG, "崩溃兜底已装：${dirs.size} 个日志目录") }
    }

    private fun writeCrash(t: Thread, e: Throwable) {
        val sw = StringWriter()
        runCatching { PrintWriter(sw).use { e.printStackTrace(it) } }
        val body = buildString {
            appendLine("########## 崩溃（Java 未捕获异常）")
            appendLine("########## 时间 ${stampFmt.format(Date())}")
            appendLine(
                "########## 机型 ${Build.MANUFACTURER} ${Build.MODEL}｜" +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            appendLine("########## 进程 pid=${android.os.Process.myPid()}｜线程 ${t.name}")
            appendLine("########## 状态 ${stateSnapshot()}")
            appendLine("##########")
            appendLine(sw.toString())
            appendLine("########## 提示：若这条是 native 崩溃（栈里没有 Java 帧），请看下次启动日志里的 tombstone 一行")
        }
        for (d in dirs) {
            runCatching {
                d.mkdirs()
                File(d, "crash_${nameFmt.format(Date())}.txt").writeText(body)
            }
        }
        // logcat 也来一份（万一写文件失败——比如磁盘满）
        runCatching { Log.e(TAG, body) }
    }

    private fun stateSnapshot(): String = runCatching {
        "running=${AppState.running.value} page=${AppState.pageName.value ?: "-"}" +
            " view=${AppState.viewLabel.value ?: "-"} frida=${AppState.fridaStatus.value ?: "-"}" +
            " maze=${AppState.mazeProgress.value ?: "-"}"
    }.getOrDefault("state=?")
}
