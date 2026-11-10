package com.lmj.labyrinthauto.frida

import com.lmj.labyrinthauto.core.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 申请 root 并跑命令。
 *
 * 为什么要有这一层：MuMu 上 su 是**厂商实现**，参数写法跟 AOSP 不完全一样
 * （实测 `/system/xbin/su` 只放行 uid 0/1000/2000，普通 app 被文件权限挡死；
 *  而 `/system/bin/su` 会弹「SuperUser 授权框」，授权后才是 root）。
 * 所以这里**逐个试**几种常见写法，用 `id` 有没有输出 uid=0 来判定哪种可用，然后缓存。
 *
 * 第一次调用会弹出 MuMu 的授权框（选「永久记住选择」后就不弹了）。
 */
object RootShell {

    /** 可用的调用形态：prefix + [单条命令字符串] */
    data class Form(val label: String, val prefix: List<String>)

    data class Exec(val exit: Int, val out: String, val err: String) {
        val text: String get() = (out + err).trim()
    }

    /** MuMu 12 的 su 客户端（世界可执行，虽然不是 setuid，但会走授权框 → root 守护进程） */
    private const val SU_BIN = "/system/bin/su"

    /**
     * ★su 的候选路径（2026-10-18 用户反馈："MuMu 15 有这个问题，MuMu 12 正常"）。
     *
     * 查证结果：**MuMu 15 = MuMu 模拟器 6.0 / Android 15，root 改成了 KernelSU 方案**
     * （官方测评："首创模拟器级 KernelSU 原生 Root"）。KernelSU 的 su **不在磁盘上放文件**：
     * 内核 sucompat 截获 `execve("/system/bin/su")` 后改道到 `/data/adb/ksud`，
     * **且只对已授权的 uid 生效**；未授权时 execve 返回 `ENOENT`（= 用户截图里的 `error=2`）。
     * ⇒ 这里的候选路径只是"磁盘上真的放了 su 的镜像"（MuMu 12 / Magisk / AOSP）才需要 stat；
     * `/system/bin/su` 与 PATH 里的裸 `su` 必须**无条件试**（见 [detect]）。
     */
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/debug_ramdisk/su",
        "/data/local/tmp/su",
    )

    /** 同一条 su 的几种调用形态（MuMu 的 su 参数跟 AOSP 不完全一样，逐个试）。 */
    private fun formsFor(bin: String) = listOf(
        Form("$bin -c", listOf(bin, "-c")),
        Form("$bin 0 -c", listOf(bin, "0", "-c")),
        Form("$bin 0 sh -c", listOf(bin, "0", "sh", "-c")),
        Form("$bin root -c", listOf(bin, "root", "-c")),
    )

    /**
     * ★诊断一行：`路径: 不存在` / `路径: 存在(368656B, 64位/x86-64, 可执行=true)`。
     * 用来区分"真没有 su"与"有 su 但 exec 报 ENOENT（位数/解释器不对）"。
     */
    private fun diagOf(path: String): String {
        return try {
            val f = File(path)
            if (!f.exists()) return "$path: 不存在"
            val buf = ByteArray(20)
            runCatching { java.io.FileInputStream(f).use { it.read(buf) } }
            val isElf = buf[0] == 0x7F.toByte() && buf[1] == 'E'.code.toByte()
            val cls = if (buf[4].toInt() == 2) "64位" else "32位"
            // ELF 头 +0x12 起 2 字节 = e_machine（小端）
            val mach = ((buf[19].toInt() and 0xFF) shl 8) or (buf[18].toInt() and 0xFF)
            val m = when (mach) {
                0x3E -> "x86-64"; 0x03 -> "x86"; 0xB7 -> "aarch64"; 0x28 -> "arm"
                else -> String.format("0x%04x", mach)
            }
            val kind = if (isElf) "$cls/$m" else if (buf[0] == '#'.code.toByte()) "脚本" else "非 ELF"
            "$path: 存在(${f.length()}B, $kind, 可执行=${f.canExecute()})"
        } catch (t: Throwable) {
            "$path: 读不了(${t.message})"
        }
    }

    @Volatile
    private var cached: Form? = null

    val form: Form? get() = cached

    /**
     * 防并发重复探测（2026-10-26）：运行组件预热改成"启动即做"后，它和**首页的 root 自检**
     * 会几乎同时调 [detect] —— 没有锁的话会各弹一次授权框（MuMu 首次很烦），
     * 还会把 4 种写法 × N 个路径的 `su -c id` 白跑两遍。
     */
    private val detectLock = Mutex()

    /** 探测哪种写法真能拿到 root（第一次会弹授权框）。并发调用会合并成一次。 */
    suspend fun detect(): Form? {
        cached?.let { return it }
        return detectLock.withLock { cached ?: detectInner() }
    }

    /** [detect] 的实际探测（调用前已持锁；成功时会把结果写进 [cached]） */
    private suspend fun detectInner(): Form? {
        // ① 先看哪些路径**磁盘上**有 su（只 stat，不执行）
        val found = SU_PATHS.filter { runCatching { File(it).exists() }.getOrDefault(false) }
        // ★★关键：不能因为 exists=false 就跳过 exec（2026-10-18 查证）——
        //   MuMu 6.0（Android 15 镜像，"MuMu 15"）的 root 是 **KernelSU 式**：`/system/bin/su`
        //   **磁盘上本来就没有这个文件**，内核 sucompat 会拦 execve("/system/bin/su") 并改道到 ksud；
        //   而这条改道**只对已授权的 UID 生效**。未授权时 execve 返回 ENOENT（= 截图里的 error=2）。
        //   ⇒ 所以 /system/bin/su 与 PATH 里的 `su` **必须无条件试一次**，不能靠 File.exists() 预判。
        val bins = (listOf(SU_BIN) + found + "su").distinct()
        if (found.isNotEmpty() && found != listOf(SU_BIN)) {
            AppState.addLog("root 探测：su 位于 ${found.joinToString("、")}")
        }
        // ② 逐个路径 × 每种写法试
        var lastErr = ""
        var sawEnoent = false
        for (bin in bins) {
            for (f in formsFor(bin)) {
                val r = runWith(f, "id", 20_000L)
                if (r.text.contains("uid=0")) {
                    cached = f
                    AppState.addLog("root 已就绪（写法：${f.label}）")
                    return f
                }
                lastErr = r.text.take(120)
                if (lastErr.contains("error=2") || lastErr.contains("No such file")) sawEnoent = true
                AppState.addLog("root 探测失败（${f.label}）：$lastErr")
            }
        }
        AppState.addLog("root 探测：${bins.size} 个路径 × 4 种写法都没拿到 uid=0（最后一次：$lastErr）")
        // ③ 诊断：把"磁盘上有没有 / 位数"打出来，配合 ENOENT 判断是哪一类
        SU_PATHS.forEach { AppState.addLog("  · ${diagOf(it)}") }
        if (sawEnoent && !File(SU_BIN).exists()) {
            AppState.addLog(
                "root 诊断：/system/bin/su 在磁盘上不存在且 exec 报 ENOENT —— " +
                    "若这是 MuMu 6.0 / Android 15（KernelSU 式 root），su 只在**已授权**时由内核改道，" +
                    "⇒ 请到模拟器的「Root 权限 / 超级用户」里允许本应用（或先打开 Root 开关并重启实例），再点「检查 / 申请 root」",
            )
        }
        return null
    }

    /** 跑一条短命令（id / pidof / pkill 这类）。 */
    suspend fun exec(cmd: String, timeoutMs: Long = 20_000L): Exec {
        val f = cached ?: detect() ?: return Exec(-1, "", "没有可用的 su 写法（授权被拒或超时）")
        // ★2026-11-10：优先走**常驻 root shell**（见 [ShellSession]）。
        //   `pm install` 那种几十秒的长命令不占 shell 通道，仍走独立 su。
        if (shellBroken || cmd.startsWith("pm install")) return runWith(f, cmd, timeoutMs)
        return withContext(Dispatchers.IO) {
            shellLock.withLock {
                val s = ensureShell(f)
                if (s != null) {
                    val r = runInShell(s, cmd, timeoutMs)
                    if (r != null) return@withLock r
                    // 超时 / 通道被污染：杀掉，下次重建（会再 su 一次），本条命令回退独立进程
                    s.kill()
                    shell = null
                    AppState.addLog("常驻 root shell 没回应（${cmd.take(40)}…）→ 本条改走独立 su")
                }
                runWith(f, cmd, timeoutMs)
            }
        }
    }

    /**
     * ★2026-11-10 雷电 9：常驻 root shell。
     *
     * 雷电的 su 是 **Koush Superuser**（`com.koushikdutta.superuser`）：**每执行一次 `su`**
     * 就 broadcast 给 `SuReceiver` 并弹一条「已授予"…"超级用户权限」toast（logcat 实测
     * `SuReceiver.onReceive → Toast.show`，运行中每 2s 一条）。MuMu 的 su 只在首次授权时
     * 提示，所以那边正常。
     *
     * 这里第一次 su 就起一个**常驻 sh**，之后所有短命令写它的 stdin（用结束标记 + `$?`
     * 拿退出码）⇒ 授权提示只弹一次；顺带省掉每条命令 fork su 的 30~80ms。
     * 起不来（有的 su 不支持交互 sh）就**永久回退**到每次 su 的 [runWith]，功能优先。
     * `spawn()`（frida-inject）仍走独立 su —— 它要独占 stdout 做长驻管道。
     */
    private class ShellSession(val p: Process) {
        val writer = p.outputStream.bufferedWriter()
        val lines = LinkedBlockingQueue<String>()
        @Volatile var dead = false

        init {
            Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { lines.put(it) } }
                dead = true
            }.apply { isDaemon = true }.start()
            // shell 自己的 stderr（每条命令的 stderr 已在命令里 `2>&1` 并进 stdout）
            Thread {
                runCatching { p.errorStream.bufferedReader().forEachLine { lines.put(it) } }
            }.apply { isDaemon = true }.start()
        }

        fun kill() {
            dead = true
            runCatching { p.destroy() }
        }
    }

    private val shellLock = Mutex()
    @Volatile private var shell: ShellSession? = null

    /** 交互 sh 起不来/握手失败后永久置位，别再每条命令都弹一次授权框。 */
    @Volatile private var shellBroken = false
    private var shellSeq = 0

    /** 起（或复用）常驻 sh；握手失败 ⇒ [shellBroken] 永久回退。调用前须持 [shellLock]、在 IO 线程。 */
    private fun ensureShell(f: Form): ShellSession? {
        shell?.takeIf { !it.dead }?.let { return it }
        if (shellBroken) return null
        val s = try {
            ShellSession(ProcessBuilder(*(f.prefix + listOf("sh")).toTypedArray()).start())
        } catch (t: Throwable) {
            shellBroken = true
            AppState.addLog("常驻 root shell 起不来（${t.message}）→ 回退到每条命令单独 su")
            return null
        }
        return try {
            s.writer.write("echo __LMJ_READY__\n")
            s.writer.flush()
            val first = s.lines.poll(3, TimeUnit.SECONDS)
            if (first != null && first.contains("__LMJ_READY__")) {
                shell = s
                AppState.addLog("常驻 root shell 已建立（root 授权只提示这一次）")
                s
            } else {
                s.kill()
                shellBroken = true
                AppState.addLog("常驻 root shell 握手失败 → 回退到每条命令单独 su（会重新弹 root 提示）")
                null
            }
        } catch (t: Throwable) {
            s.kill()
            shellBroken = true
            null
        }
    }

    /** 在常驻 shell 里跑一条命令；超时/通道异常返回 null（调用方回退）。 */
    private fun runInShell(s: ShellSession, cmd: String, timeoutMs: Long): Exec? {
        if (s.dead) return null
        val seq = ++shellSeq
        val marker = "__LMJ_END_${seq}__"
        val out = StringBuilder()
        return try {
            s.writer.write("{ $cmd ; } 2>&1; echo \"$marker \$?\"\n")
            s.writer.flush()
            val deadline = System.currentTimeMillis() + timeoutMs
            var done: Exec? = null
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                val line = s.lines.poll(left, TimeUnit.MILLISECONDS) ?: break
                if (line.startsWith("$marker ")) {
                    val code = line.removePrefix("$marker ").trim().toIntOrNull() ?: -1
                    done = Exec(code, out.toString(), "")
                    break
                }
                out.appendLine(line)
            }
            done
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 起一个**长驻**的 root 进程（frida-inject 就是这种）：
     * 不等待结束，stdout/stderr 逐行回调，调用方拿到的 Process 可以用来 kill。
     */
    suspend fun spawn(cmd: String, onLine: (String) -> Unit): Process? {
        val f = cached ?: detect() ?: run {
            AppState.addLog("起注入器失败：没有可用的 su 写法")
            return null
        }
        return withContext(Dispatchers.IO) {
            try {
                val p = ProcessBuilder(*(f.prefix + cmd).toTypedArray()).start()
                Thread {
                    runCatching { p.inputStream.bufferedReader().forEachLine { onLine(it) } }
                }.apply { isDaemon = true }.start()
                Thread {
                    runCatching { p.errorStream.bufferedReader().forEachLine { onLine("[err] $it") } }
                }.apply { isDaemon = true }.start()
                p
            } catch (t: Throwable) {
                AppState.addLog("起注入器失败：${t.message}")
                null
            }
        }
    }

    private suspend fun runWith(f: Form, cmd: String, timeoutMs: Long): Exec = withContext(Dispatchers.IO) {
        val p = try {
            ProcessBuilder(*(f.prefix + cmd).toTypedArray()).start()
        } catch (t: Throwable) {
            return@withContext Exec(-1, "", "启动 su 失败：${t.message}")
        }
        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
        val tErr = Thread { runCatching { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } }
        tOut.isDaemon = true
        tErr.isDaemon = true
        tOut.start()
        tErr.start()
        val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) {
            runCatching { p.destroy() }
            tOut.join(500)
            tErr.join(500)
            return@withContext Exec(-1, out.toString(), err.toString() + "\n(等待超时 ${timeoutMs}ms)")
        }
        tOut.join(1000)
        tErr.join(1000)
        Exec(p.exitValue(), out.toString(), err.toString())
    }
}
