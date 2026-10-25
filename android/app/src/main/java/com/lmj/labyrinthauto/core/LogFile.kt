package com.lmj.labyrinthauto.core

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 日志落盘（2026-10-17 用户要求："把完整跑完的日志保存下来方便查看，以后就知道完整跑完日志在哪里，
 * 中途退出日志在哪里"）。
 *
 * ## 关键语义：**运行时逐行写盘**，不是退出时才写
 * 每行 `AppState.addLog` 都立刻入队 → 单线程写 → **每行 flush**。所以：
 * * 正常收尾：写尾行 + **改名并归档**到 `done/` 或 `abort/`；
 * * **中途被杀**（进程被系统/用户干掉）：文件已经在盘上，内容是**被杀前最后一行**为止 ✓
 *   （能丢的只有"正在写的那一行"）。这正是"中途退出没机会转移日志"的答案 ✓。
 *
 * ## 目录与命名
 * ```
 *   logs/session_20261017_105638.txt                      整个 app 进程的日志（从启动起，含进迷宫之前）
 *   logs/run_20261017_110203_MAZE.txt                     一局**正在跑**
 *   logs/run_20261017_110203_MAZE.txt（无后缀，进程死后留在根目录）= 这局**被杀**、没来得及收尾 ✗
 *   logs/done/run_20261017_110203_MAZE__DONE_20261017_113045.txt     一局**完整跑完**（含结束时间）
 *   logs/abort/run_20261017_110203_MAZE__ABORT_STOP_20261017_111530.txt  一局**中途退出**（含原因+时间）
 *
 * ★后缀用 **.txt**（2026-10-19 用户）：普通文件管理器/默认文本查看器直接认，双击就能看；
 *   `.log` 在很多手机上会提示"无法识别的文件类型"。
 * ```
 * 文件头统一带**环境信息**（应用版本 / 机型 / 系统 / ABI / 屏幕 / 是否模拟器 / pid），
 * 这样把日志发给别人（或几周后自己看）时不用再问"什么机器、什么版本"。
 *
 * 物理路径两份（内容相同）：
 *   * `/sdcard/Android/data/<pkg>/files/logs/`  ← `adb pull` 直接取（无需权限）✓
 *   * `/data/data/<pkg>/files/logs/`            ← 外部不可写时的备份
 */
object LogFile {
    private const val TAG = "LabAuto"

    /** 每个目录最多留几份（按修改时间淘汰最旧的） */
    private const val KEEP_PER_DIR = 60

    /** 单份上限：正常一局（几十分钟、几千行）≈ 几 MB；32MB 只是防"进程开一整天"把盘写满 */
    private const val MAX_BYTES = 32L * 1024 * 1024

    private val nameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lmj-logfile").apply { isDaemon = true }
    }
    private val lock = Any()

    /** 一份正在写的文件（每个目录一份，内容相同） */
    private class Sink(val f: File, val w: BufferedWriter, var bytes: Long = 0L)

    private var dirs: List<File> = emptyList()
    @Volatile private var ready = false

    /** 环境信息行（写进每个文件头） */
    private var env: List<String> = emptyList()

    private var session: List<Sink> = emptyList()
    private var runSinks: List<Sink> = emptyList()
    private var runBase = ""                       // run_20261017_110203_MAZE
    @Volatile private var runPath = ""
    @Volatile private var sessionPath = ""

    /** 幂等；在 `Application.onCreate` 调一次（进程一启动的日志就不会丢） */
    fun init(ctx: Context) {
        if (ready) return
        val ds = ArrayList<File>()
        runCatching { File(ctx.filesDir, "logs").also { it.mkdirs() }.let { if (it.isDirectory) ds.add(it) } }
        runCatching {
            ctx.getExternalFilesDir(null)?.let { ext ->
                File(ext, "logs").also { it.mkdirs() }.let { if (it.isDirectory) ds.add(it) }
            }
        }
        val envLines = runCatching { buildEnv(ctx) }.getOrDefault(emptyList())
        io.execute {
            synchronized(lock) {
                if (ready) return@synchronized
                dirs = ds
                env = envLines
                // 归档目录先建好（用户一看就知道"跑完的去哪了"）
                for (d in ds) {
                    runCatching { File(d, "done").mkdirs(); File(d, "abort").mkdirs() }
                }
                val nm = "session_" + nameFmt.format(Date())
                session = openAll(nm)
                sessionPath = pickReportPath(session)
                ready = true
                pruneAll()
            }
            AppState.addLog(
                "日志落盘：${sessionPath.ifEmpty { "（没有可用目录，只能看 logcat）" }}" +
                    "（完整跑完→ done/ *__DONE_*.txt；中途退出→ abort/ *__ABORT_*；根目录无后缀=被杀）",
            )
        }
    }

    /** 一局开始：新开 `run_<时间>_<kind>.txt`（上一局若还没收尾，先按 ABORT_REPLACED 归档） */
    fun beginRun(kind: String) {
        if (!ready) return
        io.execute {
            synchronized(lock) {
                if (runSinks.isNotEmpty()) {
                    val p = finishLocked("ABORT_REPLACED", "上一局没走完就开了新的一局")
                    if (p.isNotEmpty()) AppState.addLog("上一局日志已归档（未跑完）：$p")
                }
                runBase = "run_" + nameFmt.format(Date()) + "_" + ascii(kind)
                runSinks = openAll(runBase)
                runPath = pickReportPath(runSinks)
            }
            if (runPath.isNotEmpty()) AppState.addLog("本局日志文件：$runPath（结束时归档到 done/ 或 abort/）")
        }
    }

    /**
     * 一局结束：写尾行 → **改名并归档**到 `done/`（完整跑完）或 `abort/`（中途退出）。
     * [tag] 只用 ASCII：`DONE` / `ABORT_STOP` / `ABORT_EXIT` / `ABORT_REPLACED` …
     */
    fun endRun(done: Boolean, tag: String, reason: String) {
        if (!ready) return
        io.execute {
            var report = ""
            val t = if (done) "DONE" else ascii(tag).ifEmpty { "ABORT" }
            synchronized(lock) {
                if (runSinks.isNotEmpty()) {
                    report = finishLocked(t, reason)
                    runPath = ""
                }
            }
            if (report.isNotEmpty()) {
                AppState.addLog(
                    "本局日志已归档（${if (done) "完整跑完" else "中途退出"}）：$report ｜ $reason",
                )
            }
        }
    }

    /** 一行日志（[AppState.addLog] 已带时间戳，这里原样落盘） */
    fun append(stamped: String) {
        if (!ready) return
        io.execute {
            synchronized(lock) {
                val line = stamped + "\n"
                writeAll(session, line)
                writeAll(runSinks, line)
            }
        }
    }

    /** 当前这局的日志路径（没在跑时为空） */
    fun runFilePath(): String = runPath

    /** 当前 session 日志路径 */
    fun sessionFilePath(): String = sessionPath

    /** 一份日志文件（给"一键分享"用）；[dir] = 所在子目录（"" 根目录 / "done" / "abort"） */
    data class LogEntry(val f: File, val name: String, val dir: String, val sizeKb: Long, val time: String)

    /**
     * 最近的日志文件（2026-10-19 用户："让其他用户提供某一次的日志，能不能一键"）。
     * 优先外部目录（`/sdcard/Android/data/<pkg>/files/logs/`，含 done/、abort/），
     * 按修改时间倒序，同名的两份（内部/外部）只留一份。
     */
    fun listRecent(max: Int = 40): List<LogEntry> {
        val root = externalDir() ?: dirs.firstOrNull() ?: return emptyList()
        val files = ArrayList<Pair<File, String>>()      // 文件 → 所在子目录（"" / done / abort）
        for (d in listOf(root, File(root, "done"), File(root, "abort"))) {
            val sub = if (d == root) "" else d.name
            d.listFiles { f -> f.isFile && isLogFile(f.name) }?.forEach { files.add(it to sub) }
        }
        return files.distinctBy { it.first.name }
            .sortedByDescending { it.first.lastModified() }
            .take(max)
            .map { (f, sub) ->
                LogEntry(f, f.name, sub, (f.length() + 1023) / 1024, dayFmt.format(Date(f.lastModified())))
            }
    }

    /** 把某份日志用系统分享面板发出去（微信 / QQ / 邮件都能收文件）。 */
    fun share(ctx: Context, f: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(
                android.content.Intent.EXTRA_SUBJECT,
                "黎明界自动化日志 ${f.name}",
            )
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(android.content.Intent.createChooser(i, "分享日志：${f.name}"))
    }

    /** 用系统"查看"打开这份日志（文件管理器 / 文本查看器）。返回 false = 没有能打开它的应用。 */
    fun openWithViewer(ctx: Context, f: File): Boolean {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { ctx.startActivity(i) }.isSuccess
    }

    /**
     * 复制到**公共下载目录**（2026-10-19 用户：模拟器上没微信/QQ，分享面板没用，
     * 复制过去之后用文件管理器/adb 都能拿）。返回给用户看的落点描述。
     */
    fun copyToDownloads(ctx: Context, f: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = ctx.contentResolver
                .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("系统拒绝了写入下载目录")
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                f.inputStream().use { it.copyTo(out) }
            } ?: error("打不开输出流")
            return "Download/${f.name}"
        }
        // Android 9 及以下没有 MediaStore.Downloads：直接写公共 Download（需要老的写权限，失败就报）
        @Suppress("DEPRECATION")
        val dir = android.os.Environment
            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val t = File(dir, f.name)
        runCatching { f.copyTo(t, overwrite = true) }.onFailure { error("写入失败：${it.message}") }
        return t.absolutePath
    }

    // ────────────────────────── 内部 ──────────────────────────

    /** 写尾行、关闭、改名归档；返回"外部可见"那份的最终路径（给日志展示） */
    private fun finishLocked(tag: String, reason: String): String {
        if (runSinks.isEmpty()) return ""
        val end = Date()
        val footer = "\n===== 结束：${if (tag == "DONE") "完整跑完" else "中途退出（$reason）"}" +
            " ｜ ${dayFmt.format(end)} =====\n"
        for (s in runSinks) {
            runCatching { s.w.write(footer); s.w.flush() }
            runCatching { s.w.close() }
        }
        val sub = if (tag == "DONE") "done" else "abort"
        val endTs = nameFmt.format(end)
        val ext = externalDir()
        var report = ""
        for (s in runSinks) {
            runCatching {
                val parent = s.f.parentFile ?: return@runCatching
                val subDir = File(parent, sub).also { it.mkdirs() }
                // 名字里同时带**开始**（runBase）与**结束**时间（endTs）+ 结论（tag）
                val target = File(subDir, "${runBase}__${tag}_${endTs}.txt")
                if (target.exists()) target.delete()
                val okMove = s.f.renameTo(target)
                if (!okMove) {            // 跨分区/被占用时退化为"复制+删"
                    runCatching { s.f.copyTo(target, overwrite = true); s.f.delete() }
                }
                if (ext != null && parent.absolutePath == ext.absolutePath) report = target.absolutePath
            }
        }
        runSinks = emptyList()
        pruneAll()
        return report
    }

    private fun openAll(baseName: String): List<Sink> {
        val out = ArrayList<Sink>()
        for (d in dirs) {
            runCatching {
                val f = File(d, "$baseName.txt")
                f.parentFile?.mkdirs()
                val w = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), "UTF-8"), 1 shl 13)
                // ★文件头：时间 + 环境（机型/系统/屏幕/版本…）—— 免得几周后看日志还要猜"什么机器、什么版本"
                w.write("########## 文件 $baseName\n")
                w.write("########## 开始 ${dayFmt.format(Date())}\n")
                for (e in env) w.write("########## $e\n")
                w.write("##########\n")
                w.flush()
                out.add(Sink(f, w, f.length()))
            }.onFailure { Log.w(TAG, "日志文件打不开：$d/$baseName", it) }
        }
        return out
    }

    private fun writeAll(sinks: List<Sink>, line: String) {
        for (s in sinks) {
            if (s.bytes >= MAX_BYTES) continue
            runCatching { s.w.write(line); s.w.flush(); s.bytes += line.length }
        }
    }

    /** 环境信息（写进每个文件头）：应用/机型/系统/ABI/屏幕/是否模拟器/pid */
    private fun buildEnv(ctx: Context): List<String> {
        val out = ArrayList<String>()
        val ver = runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            // minSdk=26：longVersionCode 是 API 28+，低版本退回 versionCode
            val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            "${pi.versionName ?: "?"}($vc)"
        }.getOrDefault("?")
        out.add("应用 $ver ｜ 包名 ${ctx.packageName}")
        val emu = runCatching { isEmulator() }.getOrDefault(false)
        out.add(
            "机型 ${Build.MANUFACTURER} ${Build.MODEL} ｜ 设备名 ${Build.DEVICE} ｜ " +
                (if (emu) "**模拟器**" else "真机"),
        )
        out.add(
            "系统 Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ｜ " +
                "ABI ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} ｜ 指纹 ${Build.FINGERPRINT.take(60)}",
        )
        runCatching {
            val dm = ctx.resources.displayMetrics
            out.add(
                "屏幕 ${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi ｜ " +
                    "density=${"%.2f".format(dm.density)} ｜ 语言 ${Locale.getDefault()}",
            )
        }
        out.add("进程 pid=${android.os.Process.myPid()} ｜ 时区 ${java.util.TimeZone.getDefault().id}")
        return out
    }

    /** 常见模拟器特征（MuMu / 雷电 / 蓝叠 / AOSP 镜像）。只用于日志标注，不影响逻辑 */
    private fun isEmulator(): Boolean {
        // ★最可靠的判据：**x86 ABI**。MuMu 会把指纹/机型伪装成三星真机（实测
        //   `SM-S9080 / b0q / 真机` ✗），但手机不可能是 x86 ⇒ 这一条就够了 ✓
        val abi = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase(Locale.US) ?: ""
        if (abi.startsWith("x86")) return true
        val fp = Build.FINGERPRINT.lowercase(Locale.US)
        val model = Build.MODEL.lowercase(Locale.US)
        val hw = Build.HARDWARE.lowercase(Locale.US)
        val prod = Build.PRODUCT.lowercase(Locale.US)
        val board = Build.BOARD.lowercase(Locale.US)
        return fp.contains("generic") || fp.contains("unknown") ||
            model.contains("sdk") || model.contains("emulator") || model.contains("mumu") ||
            hw.contains("goldfish") || hw.contains("ranchu") || hw.contains("qemu") ||
            prod.contains("sdk") || prod.contains("emulator") || prod.contains("nemu") ||
            board.contains("goldfish") || board.contains("qemu")
    }

    /** 优先报"外部可见"那份（adb pull 方便），否则私有目录那份 */
    private fun pickReportPath(sinks: List<Sink>): String {
        val ext = externalDir()
        val s = if (ext != null) sinks.firstOrNull { it.f.parentFile?.absolutePath == ext.absolutePath } else null
        return (s ?: sinks.firstOrNull())?.f?.absolutePath ?: ""
    }

    private fun externalDir(): File? {
        val ds = dirs
        return ds.lastOrNull { !it.absolutePath.contains("/data/data/") }
    }

    /** 老文件清理：logs/、logs/done、logs/abort 各留 [KEEP_PER_DIR] 份（按 mtime） */
    private fun pruneAll() {
        val all = ArrayList<File>()
        for (d in dirs) {
            all.add(d)
            all.add(File(d, "done"))
            all.add(File(d, "abort"))
        }
        for (d in all) {
            runCatching {
                // 兼容清理：旧版 .log 与现在的 .txt 都算（升级后旧文件会被逐步淘汰）
                val files = d.listFiles { f -> f.isFile && isLogFile(f.name) }
                    ?.sortedByDescending { it.lastModified() } ?: return@runCatching
                files.drop(KEEP_PER_DIR).forEach { runCatching { it.delete() } }
            }
        }
    }

    /** 是日志文件吗：现在的 .txt + 升级前遗留的 .log（列表/清理都认，逐步淘汰） */
    private fun isLogFile(name: String): Boolean =
        name.endsWith(".txt") || name.endsWith(".log")

    /** 文件名里只用 ASCII（中文文件名在 adb/zip 里容易乱码） */
    private fun ascii(s: String): String = s.mapNotNull {
        if (it.isLetterOrDigit() && it.code < 128) it else if (it == '_' || it == '-') it else null
    }.joinToString("").uppercase().take(24)
}
