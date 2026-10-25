package com.lmj.labyrinthauto.logic.team

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.lmj.labyrinthauto.core.AppState
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * **组队记录**（2026-10-24 用户要求）—— 每次编队决策写一行 JSON，字段对齐 PC 版
 * `team_build.log_run()`（那样 PC 的组队记录/模拟页能直接读）。
 *
 * ## 为什么不复用 `LogFile`
 * `LogFile` 写的是"人读的 .txt 运行日志"，一个回合几十行中文；这里是**机器读的结构化记录**
 * （一行一个 JSON，便于 `adb pull` 回来跟 PC 的 `team_runs.jsonl` 合并/统计）。
 * 两者的清理策略也不同：`LogFile` 按局归档进 `done/`、`abort/`；组队记录只按大小轮转。
 *
 * ## 文件位置（与 .txt 同目录，两份内容相同）
 * ```
 *   /sdcard/Android/data/<pkg>/files/logs/team_runs.jsonl     ← adb pull / 一键导出都取这份
 *   /data/data/<pkg>/files/logs/team_runs.jsonl               ← 外部不可写时的备份
 *   team_runs_<yyyyMMdd_HHmmss>.jsonl                          ← 超过 32MB 轮转出来的历史份（留 5 份）
 * ```
 *
 * ## run_id：**尽量全球唯一**
 * 格式 `<stage>|<yyyyMMdd-HHmmss>-<3位毫秒>|<12 位随机 hex>`
 * * 前两段 = PC `_new_run_id` 的可读部分（一眼能看出"哪关、什么时候"）；
 * * 第三段 = `UUID.randomUUID()` 的 12 位十六进制（48 bit 随机）⇒ 多台设备各自跑完再合并，
 *   **撞号概率可忽略**；PC 侧的格式没有第三段，所以连"和 PC 同秒同关"也不会重复；
 * * 进程内另有 `used` 集合兜底（同毫秒连发也不会重号）。
 *
 * ⚠写入是**逐行 flush**，进程被杀只可能丢"正在写的那一行"。
 */
object TeamRunLog {
    private const val TAG = "LabAuto"

    /** 文件名（与 PC 同名，方便直接把行粘进 PC 的 `team_runs.jsonl`） */
    const val FILE_NAME = "team_runs.jsonl"

    /** 单份上限：一行约 0.6~1.5KB（含池/遗物明细）⇒ 32MB ≈ 2~4 万次组队，够跑很久 */
    private const val MAX_BYTES = 32L * 1024 * 1024

    /** 轮转出来的旧份最多留几个（再多就删最旧的） */
    private const val KEEP_ROTATED = 5

    private val idFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lmj-teamrunlog").apply { isDaemon = true }
    }
    private val lock = Any()

    /** 一份正在追加的文件 + 写手 + 已写字节 */
    private class Sink(val f: File, val w: BufferedWriter, var bytes: Long)

    private var dirs: List<File> = emptyList()
    private var sinks: List<Sink> = emptyList()
    @Volatile private var ready = false

    /** 设备信息（每行都带一份：合并多台设备的记录后仍能分清"这条是哪台机器跑的"） */
    private var device: JSONObject = JSONObject()

    /** 进程内已发过的 run_id（同毫秒也不会重号） */
    private val used = HashSet<String>()

    /** 已写入的条数（初始化时数一次当前文件，之后每写一行 +1）——给 UI 显示用 */
    @Volatile private var writtenLines = 0

    /** 幂等；`LmjApp.onCreate` 调一次（和 [com.lmj.labyrinthauto.core.LogFile] 一起） */
    fun init(ctx: Context) {
        if (ready) return
        val ds = ArrayList<File>()
        runCatching { File(ctx.filesDir, "logs").also { it.mkdirs() }.let { if (it.isDirectory) ds.add(it) } }
        runCatching {
            ctx.getExternalFilesDir(null)?.let { ext ->
                File(ext, "logs").also { it.mkdirs() }.let { if (it.isDirectory) ds.add(it) }
            }
        }
        val dev = runCatching { buildDevice(ctx) }.getOrElse { JSONObject() }
        // ★目录/设备信息**同步**落好就置 ready：否则"init 还没跑完就来了一条 append"会被丢掉。
        //   文件句柄仍在线程里开（[append] 发现 sinks 空会自己补开）。
        synchronized(lock) {
            if (ready) return
            dirs = ds
            device = dev
            ready = true
        }
        io.execute {
            synchronized(lock) { if (sinks.isEmpty()) openAll() }
            // 已有文件的条数（两份内容相同，取外部那份数一次；之后每写一行 +1）
            val base = ds.lastOrNull { !it.absolutePath.contains("/data/data/") } ?: ds.firstOrNull()
            if (base != null) writtenLines = countLines(File(base, FILE_NAME))
            val p = currentPath()
            if (p.isNotEmpty()) AppState.addLog("组队记录：$p（每次编队一行 JSON；导出见「分享日志」页）")
        }
    }

    /**
     * 记一次组队。`rec` 由 [com.lmj.labyrinthauto.logic.party.PartyPlan] 组装（字段见那边的 `buildRunRecord`），
     * 这里只补 `type/run_id/ts/source/device`。返回 run_id（没初始化时返回 null）。
     */
    fun append(rec: JSONObject): String? {
        if (!ready) return null
        val rid = newRunId(rec.optString("stage"))
        val line = composeLine(rec, rid, dayFmt.format(Date()), device)
        io.execute {
            synchronized(lock) {
                if (sinks.isEmpty()) openAll()        // init 的异步任务还没跑到（或上次全失败）→ 这里补开
                rotateIfNeeded()
                for (s in sinks) {
                    runCatching {
                        s.w.write(line)
                        s.w.write("\n")
                        s.w.flush()
                        s.bytes += line.length + 1
                    }.onFailure { Log.w(TAG, "组队记录写入失败：${s.f}", it) }
                }
                if (sinks.isNotEmpty()) writtenLines++
            }
        }
        return rid
    }

    /**
     * 拼出**要写进文件的那一行**（纯函数，单测直接验；不碰设备 API）。
     * 只补元信息（`type/run_id/ts/source/app/device`）——业务字段一律由调用方给足，
     * 这样 PC 侧 `team_lab` 读到的键名与 `team_build.log_run` 完全对应。
     */
    internal fun composeLine(rec: JSONObject, rid: String, ts: String, dev: JSONObject): String {
        val out = JSONObject(rec.toString())          // 深拷一份，不改调用方的对象
        out.put("type", "run")
        out.put("run_id", rid)
        out.put("ts", ts)
        out.put("source", "android")
        out.put("app", dev.optString("ver"))
        out.put("device", dev)
        return out.toString()
    }

    /** `<stage>|yyyyMMdd-HHmmss-mmm|12位随机hex` —— 见类注释里"尽量全球唯一"的说明 */
    fun newRunId(stage: String): String {
        val st = stage.ifEmpty { "unknown" }
        synchronized(used) {
            while (true) {
                val ts = idFmt.format(Date())
                val ms = String.format(Locale.US, "%03d", System.currentTimeMillis() % 1000)
                val rnd = UUID.randomUUID().toString().replace("-", "").take(12)
                val rid = "$st|$ts-$ms|$rnd"
                if (used.add(rid)) return rid
            }
        }
    }

    /** 组队记录概况（给"分享日志"页显示） */
    data class Stats(val runs: Int, val bytes: Long, val path: String, val files: Int)

    fun stats(): Stats {
        val cur = currentFile()
        if (cur == null) return Stats(0, 0, "", 0)
        val all = allFiles()
        val bytes = all.sumOf { it.length() }         // 只读文件属性，不扫内容（UI 上不能卡）
        return Stats(writtenLines, bytes, cur.absolutePath, all.size)
    }

    /** 当前这份（外部存储那份优先，`adb pull` 方便） */
    fun currentFile(): File? {
        val ds = dirs.ifEmpty { return null }
        val ext = ds.lastOrNull { !it.absolutePath.contains("/data/data/") }
        val base = ext ?: ds.first()
        return File(base, FILE_NAME)
    }

    fun currentPath(): String = currentFile()?.absolutePath ?: ""

    /** 当前份 + 轮转出来的历史份（新的在前） */
    fun allFiles(): List<File> {
        val cur = currentFile() ?: return emptyList()
        val d = cur.parentFile ?: return listOf(cur)
        val hist = d.listFiles { f ->
            f.isFile && f.name.startsWith("team_runs_") && f.name.endsWith(".jsonl")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
        return if (cur.exists()) listOf(cur) + hist else hist
    }

    // ────────────────────────── 内部 ──────────────────────────

    private fun openAll() {
        val out = ArrayList<Sink>()
        for (d in dirs) {
            runCatching {
                val f = File(d, FILE_NAME)
                f.parentFile?.mkdirs()
                val w = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), "UTF-8"), 1 shl 13)
                out.add(Sink(f, w, f.length()))
            }.onFailure { Log.w(TAG, "组队记录打不开：$d", it) }
        }
        sinks = out
    }

    /** 超过 [MAX_BYTES] → 关掉、改名成 `team_runs_<时间>.jsonl`、重开；旧的只留 [KEEP_ROTATED] 份 */
    private fun rotateIfNeeded() {
        if (sinks.none { it.bytes >= MAX_BYTES }) return
        val tags = idFmt.format(Date())
        for (s in sinks) runCatching { s.w.flush(); s.w.close() }
        for (s in sinks) {
            runCatching {
                val d = s.f.parentFile ?: return@runCatching
                val t = File(d, "team_runs_$tags.jsonl")
                if (t.exists()) t.delete()
                if (!s.f.renameTo(t)) runCatching { s.f.copyTo(t, overwrite = true); s.f.delete() }
                val olds = d.listFiles { f ->
                    f.isFile && f.name.startsWith("team_runs_") && f.name.endsWith(".jsonl")
                }?.sortedByDescending { it.lastModified() } ?: return@runCatching
                olds.drop(KEEP_ROTATED).forEach { runCatching { it.delete() } }
            }
        }
        sinks = emptyList()
        openAll()
    }

    private fun countLines(f: File): Int = runCatching {
        var n = 0
        f.bufferedReader(Charsets.UTF_8).use { r ->
            while (r.readLine() != null) n++
        }
        n
    }.getOrDefault(0)

    private fun buildDevice(ctx: Context): JSONObject {
        val ver = runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            "${pi.versionName ?: "?"}($vc)"
        }.getOrDefault("?")
        // 设备短码（ANDROID_ID 的哈希前 6 位）：只用于"人眼分辨是哪台机器"，不落原值
        val dev6 = runCatching {
            val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
            Integer.toHexString(id.hashCode())
        }.getOrDefault("?")
        return JSONObject()
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("device", Build.DEVICE)
            .put("dev", dev6)
            .put("android", "${Build.VERSION.RELEASE}(${Build.VERSION.SDK_INT})")
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
            .put("emu", isEmulator())
            .put("ver", ver)
    }

    /** 与 `LogFile` 同一套判据（x86 ABI 最可靠；机型/指纹兜底） */
    private fun isEmulator(): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase(Locale.US) ?: ""
        if (abi.startsWith("x86")) return true
        val fp = Build.FINGERPRINT.lowercase(Locale.US)
        val model = Build.MODEL.lowercase(Locale.US)
        val hw = Build.HARDWARE.lowercase(Locale.US)
        val prod = Build.PRODUCT.lowercase(Locale.US)
        return fp.contains("generic") || fp.contains("unknown") ||
            model.contains("sdk") || model.contains("emulator") || model.contains("mumu") ||
            hw.contains("goldfish") || hw.contains("ranchu") || hw.contains("qemu") ||
            prod.contains("sdk") || prod.contains("emulator") || prod.contains("nemu")
    }
}
