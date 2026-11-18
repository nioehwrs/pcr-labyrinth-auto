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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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

    /** 写盘队列上限 + 背压（2026-11-17）：原 single-thread 无界队列，日志风暴时生产快于落盘会无限堆积
     *  （每行要 flush 两份 sink），是助手 rss 膨胀的可疑源。改为有界 + CallerRunsPolicy：
     *  队列满时由 addLog 的调用线程同步落盘 ⇒ 不丢日志、队列不无限涨、生产自然降速。 */
    private const val QUEUE_CAP = 4096
    private val io = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(QUEUE_CAP),
        { r -> Thread(r, "lmj-logfile").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy(),
    )
    private val lock = Any()

    /**
     * ★批量 flush 阈值（2026-11-17）：原来**每行都 `flush()` 两份文件** ⇒ 战斗中日志几十行/秒时
     *   每秒上百次 write 系统调用，在 CPU/IO 紧张时会把系统拖到 lmkd 判定 "device is not responding"。
     *   现在攒够 [FLUSH_BYTES] 或距上次满 [FLUSH_MS] 才 flush。
     *   ⚠代价：进程被 SIGKILL（LMK/用户强杀）时最多丢最后这 ~0.5s/8KB 的日志。
     */
    private const val FLUSH_BYTES = 8 * 1024
    private const val FLUSH_MS = 500L

    /** 一份正在写的文件（每个目录一份，内容相同） */
    private class Sink(val f: File, val w: BufferedWriter, var bytes: Long = 0L) {
        var pending = 0L                       // 自上次 flush 以来写入的字节
        var lastFlush = System.currentTimeMillis()
    }

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
                openSessionLocked()
                ready = true
                pruneAll()
            }
            AppState.addLog(
                "日志落盘：${sessionPath.ifEmpty { "（没有可用目录，只能看 logcat）" }}" +
                    "（完整跑完→ done/ *__DONE_*.txt；中途退出→ abort/ *__ABORT_*；根目录无后缀=被杀）",
            )
        }
    }

    /** 新建一份 `session_*.txt`（调用方必须已持有 [lock]；同秒内连切两次会追加到同名文件，可忽略） */
    private fun openSessionLocked() {
        session = openAll("session_" + nameFmt.format(Date()))
        sessionPath = pickReportPath(session)
    }

    /**
     * ★截断会话（2026-11-02 用户："按每次结束一轮运行，就应该截断一个 session"）。
     *
     * 以前一个 session = **一次 app 启动**，同一次启动里连着跑「1 局」和「3 局」两个任务会被并成一条，
     * 日志页就出现「成功 4/3 局 · 有未完成」这种自相矛盾的统计（真实案例：00:18 单局 + 00:43 三局）。
     * 现在每轮运行开始时另起一份，一条 session 只对应一次运行，成功数/计划数天然对齐。
     */
    fun rotateSession() {
        if (!ready) return
        io.execute {
            var old = ""
            var fresh = ""
            synchronized(lock) {
                if (session.isNotEmpty()) {
                    old = sessionPath
                    for (s in session) {
                        runCatching {
                            s.w.write(
                                "\n===== 会话结束 ${nameFmt.format(Date())}：" +
                                    "本轮运行到此为止，下一轮另起一份 =====\n",
                            )
                            s.w.flush()
                            s.w.close()
                        }
                    }
                    session = emptyList()
                    sessionPath = ""
                }
                openSessionLocked()
                fresh = sessionPath
            }
            AppState.addLog(
                "会话日志切分：新一轮开始 → ${fresh.ifEmpty { "（没有可用目录）" }}" +
                    if (old.isEmpty()) "" else "（上一份：$old）",
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
                // 防御：会话被截断后（rotateSession 与 append 之间有竞态）也要有地方写
                if (session.isEmpty()) openSessionLocked()
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

    // ─────────────── 日志页的两级列表（2026-10-27 用户方案） ───────────────

    /** 徽章语义色；core 层不碰 Compose，UI 侧映射到 `StatusColors` */
    enum class Tone { GOOD, WARN, BAD, IDLE }

    /**
     * **一级条目 = 一次运行**（一份 `session_*.txt`，跨多局）；二级 = 这次运行里的单局。
     *
     * 2026-10-27 用户口径（原话的形状）：
     * ```
     * ▸ 本次运行 10-27 21:47   成功 5/5 局 · 1小时50分        [生成诊断包]
     *     ├ 第 1 局 21:49 成功 26分钟 51000分                [选这一局]
     *     └ 第 5 局 23:40 成功 27分钟 51600分
     * ```
     * * 点顶层 ⇒ 诊断包 = **session + 该次运行的全部单局**；
     * * 点某一局 ⇒ 诊断包 = **session + 这一局**；
     * * 打包只按用户选择生成，**不包含全部历史**。
     */
    data class SessionEntry(
        val key: String,
        val title: String,          // 本次运行 · 10-27 21:47
        val badge: String,          // 全部成功 / 有未完成 / 进行中
        val tone: Tone,
        val timeText: String,       // 成功 5/5 局 · 1 小时 50 分
        val detail: String,         // 共 6 份日志 · 2.3 MB
        val sortMs: Long,
        /** 是不是**本次进程**正在写的那一条（只有它叫"本次运行"，且永远不折叠） */
        val current: Boolean,
        val sessionFile: LogEntry?, // 可能已被清理（每目录只留 60 份）
        val files: List<LogEntry>,  // 诊断包要带的全部文件（session + 各单局 + 该时段的崩溃记录）
        val runs: List<RunEntry>,
    )

    /** 二级条目 = **单局**（`run_*.txt`） */
    data class RunEntry(
        val key: String,
        val title: String,          // 第 1 局 · 21:49
        val badge: String,          // 成功 / 失败：手动停止 / 被中断
        val tone: Tone,
        val timeText: String,       // 26 分钟 · 总分 51000
        val detail: String,         // 510 KB
        val kind: String,           // MAZE / REROLL / SELFTEST
        val index: Int?,            // 第几局（日志里「本局：第 N/M 局」；老日志按时间顺序补）
        val planned: Int?,          // 这一轮计划几局
        val score: Long?,           // 总分（只认「迷宫结算…总分=…」，不要刷开局路线那个总分）
        val startMs: Long,
        val endMs: Long?,           // 被杀的局没有结束时间
        val sortMs: Long,
        val files: List<LogEntry>,
    )

    /** `run_开始_KIND__结论_结束`（**不含扩展名**：分组时已经去掉，旧版遗留的 .log 也认） */
    private val runNameRe = Regex(
        "^run_(\\d{8}_\\d{6})_([A-Za-z0-9]+)(?:__([A-Za-z0-9_]+?)_(\\d{8}_\\d{6}))?$",
    )

    /** 解析文件名里的时间戳（**不复用 [nameFmt]**：那个在 io 线程写文件名，SimpleDateFormat 不是线程安全的） */
    private val stampFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val hmFmt = SimpleDateFormat("HH:mm", Locale.US)
    private val mdHmFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    /**
     * 局号：`── 本局：第 2/5 局 ──`（2026-10-27 起由 `MazeLoop` 在 beginRun 之后写进本局文件）。
     * 老日志没有这一行 ⇒ 用"本次运行里的第几份单局日志"兜底。
     */
    private val runIndexRe = Regex("第 (\\d+)/(\\d+) 局")

    /**
     * 总分：**只认**「迷宫结算…总分=…」。⚠ 不能用宽松的 `总分=(\d+)` ——
     * 刷开局路线日志里也有 `（刷开局记住的路线，总分=1700）`，那是**路线分**不是本局结算分。
     */
    private val scoreRe = Regex("迷宫结算[^\\n]*?总分=(\\d+)")

    /**
     * 一级列表（按最近活动倒序）：**先按 session 归堆，再把单局挂到"开始时间不晚于它"的最近一次 session**。
     *
     * 为什么不看目录（done/abort/根目录）：那是**结论**，不是层级；用户要的是"我发哪一次"。
     */
    fun listSessions(max: Int = 20): List<SessionEntry> {
        val entries = listRecent(300)

        val runs = entries.asSequence()
            .filter { it.name.startsWith("run_") }
            .groupBy { groupKey(it.name) }
            .mapNotNull { (key, files) -> buildRun(key, files) }
            .sortedBy { it.startMs }
            .toList()

        // session 按开始时间升序（后面用"最近一个开始时间 ≤ X"来归属）
        val sessions = entries.asSequence()
            .filter { it.name.startsWith("session_") }
            .mapNotNull { e ->
                val stamp = e.name.substringBeforeLast('.').removePrefix("session_")
                parseStamp(stamp)?.let { Triple(it, e, ArrayList<RunEntry>()) }
            }
            .sortedBy { it.first }
            .toList()

        // 崩溃记录 / 认不出的杂项：按时间挂到所属 session（这样诊断包会**自动带上崩溃记录**）
        val loose = HashMap<Long, MutableList<LogEntry>>()
        val unowned = ArrayList<LogEntry>()
        for (e in entries) {
            if (e.name.startsWith("run_") || e.name.startsWith("session_")) continue
            val t = e.f.lastModified()
            val owner = sessions.lastOrNull { it.first <= t }
            if (owner == null) unowned.add(e) else loose.getOrPut(owner.first) { ArrayList() }.add(e)
        }

        val orphanRuns = ArrayList<RunEntry>()
        for (r in runs) {
            val owner = sessions.lastOrNull { it.first <= r.startMs }
            if (owner == null) orphanRuns.add(r) else owner.third.add(r)
        }

        val out = ArrayList<SessionEntry>()
        for (i in sessions.indices) {
            val (start, sessFile, rs) = sessions[i]
            val nextStart = sessions.getOrNull(i + 1)?.first
            val own = rs.filter { nextStart == null || it.startMs < nextStart }
            val extra = loose[start].orEmpty()
            // 归属下一条 session 开始之前的崩溃记录也算这一轮（两端都按时间切）
            val extra2 = extra.filter { nextStart == null || it.f.lastModified() < nextStart }
            out.add(
                sessionEntry(
                    start, sessFile, own, extra2,
                    running = i == sessions.lastIndex,
                    // 只有**最新那条**才是"本次运行"（本次进程还在写它），其余一律按时间叫"运行"
                    current = i == sessions.lastIndex,
                ),
            )
        }

        if (orphanRuns.isNotEmpty() || unowned.isNotEmpty()) {
            val files = ArrayList<LogEntry>()
            for (r in orphanRuns) files.addAll(r.files)
            files.addAll(unowned)
            out.add(
                SessionEntry(
                    key = "loose",
                    title = "历史日志（没有会话日志）",
                    badge = "未归属",
                    tone = Tone.IDLE,
                    timeText = "共 ${orphanRuns.size} 局 + ${unowned.size} 份其它日志",
                    detail = sizeText(files.sumOf { it.sizeKb }),
                    sortMs = (files.maxOfOrNull { it.f.lastModified() } ?: 0L),
                    current = false,
                    sessionFile = null,
                    files = files.sortedByDescending { it.f.lastModified() },
                    runs = orphanRuns.sortedByDescending { it.startMs },
                ),
            )
        }

        return out.sortedByDescending { it.sortMs }.take(max)
    }

    /** 分组键：`run_x_KIND__TAG_end.txt` → `run_x_KIND` */
    private fun groupKey(name: String): String {
        val base = name.substringBeforeLast('.')
        val i = base.indexOf("__")
        return if (i > 0) base.substring(0, i) else base
    }

    private fun sessionEntry(
        start: Long,
        sessFile: LogEntry,
        runs: List<RunEntry>,
        extra: List<LogEntry>,
        running: Boolean,
        current: Boolean,
    ): SessionEntry {
        // ★局号：日志里有就用日志里的（撤退重跑会重复同一个号，那是真的）；老日志没有 → 按时间顺序补
        val plannedHint = runs.maxOfOrNull { it.planned ?: 0 }?.takeIf { it > 0 }
        var seq = 0
        val sortedRuns = runs.sortedBy { it.startMs }.map { r ->
            if (r.kind == "MAZE") seq++
            val idx = r.index ?: seq
            r.copy(
                index = idx,
                planned = r.planned ?: plannedHint,
                title = runTitle(r.kind, idx, r.startMs),
            )
        }
        val planned = plannedHint ?: sortedRuns.count { it.kind == "MAZE" }
        val ok = sortedRuns.count { it.tone == Tone.GOOD }
        val end = sortedRuns.maxOfOrNull { it.endMs ?: it.sortMs } ?: start
        val files = ArrayList<LogEntry>()
        files.add(sessFile)
        for (r in sortedRuns) files.addAll(r.files)
        files.addAll(extra)
        val crashN = extra.count { it.name.startsWith("crash_") }
        val (badge, tone) = when {
            running && runPath.isNotEmpty() -> "进行中" to Tone.WARN
            // 只有会话日志（打完开局就被关掉 / 只刷了开局）⇒ 中性，不算失败
            sortedRuns.isEmpty() -> "仅会话" to Tone.IDLE
            // ★`>=` 而不是 `==`：按运行截断会话后两者本就相等；旧数据里若有"两个任务并进一条"
            //   （成功 4 / 计划 3）也不该判成"有未完成"（2026-11-02）
            ok >= planned && sortedRuns.none { it.tone == Tone.BAD } -> "全部成功" to Tone.GOOD
            sortedRuns.any { it.tone == Tone.BAD } -> "有中断" to Tone.BAD
            else -> "有未完成" to Tone.WARN
        }
        return SessionEntry(
            key = "session_${start}",
            title = (if (current) "本次运行" else "运行") + " · ${mdHmFmt.format(Date(start))}",
            badge = badge,
            tone = tone,
            timeText = if (sortedRuns.isEmpty()) "没有单局日志" + (if (crashN > 0) "（含崩溃记录）" else "")
            // 分母不比分子小：旧数据出现过"成功 4/3 局"（两个任务并进一条 session），显示上也别露馅
            else "成功 $ok/${maxOf(planned, ok)} 局 · 用时 ${duration(end - start)}",

            detail = "共 ${files.size} 份日志 · ${sizeText(files.sumOf { it.sizeKb })}" +
                (if (crashN > 0) " · 含 $crashN 份崩溃记录" else ""),
            sortMs = files.maxOfOrNull { it.f.lastModified() } ?: start,
            current = current,
            sessionFile = sessFile,
            files = files.sortedByDescending { it.f.lastModified() },
            runs = sortedRuns,
        )
    }

    private fun buildRun(key: String, files: List<LogEntry>): RunEntry? {
        val newest = files.maxByOrNull { it.f.lastModified() } ?: return null
        val m = runNameRe.find(key) ?: return null
        val startMs = parseStamp(m.groupValues[1]) ?: newest.f.lastModified()
        val kind = m.groupValues[2].uppercase(Locale.US)
        // ★结论与结束时间要回到**归档后的原文件名**上找：分组键已经把 `__结论_结束时间` 剥掉了
        val archived = files
            .mapNotNull { runNameRe.find(it.f.name.substringBeforeLast('.')) }
            .firstOrNull { it.groupValues[3].isNotEmpty() }
        val tag = archived?.groupValues?.get(3)?.takeIf { it.isNotEmpty() }
        val endMs = archived?.groupValues?.get(4)?.takeIf { it.isNotEmpty() }?.let { parseStamp(it) }
        val running = runPath.isNotEmpty() && files.any { it.f.absolutePath == runPath }
        val (badge, tone) = when {
            running -> "进行中" to Tone.WARN
            tag == "DONE" || newest.dir == "done" -> "成功" to Tone.GOOD
            newest.dir == "abort" -> "失败 · ${abortLabel(tag)}" to Tone.WARN
            // 根目录、没有结论后缀 = 进程被当场干掉（没来得及收尾）
            else -> "被中断" to Tone.BAD
        }
        // 「第 N/M 局」写在**文件头**（开局就写），「总分=」在**文件尾**（结算时写）⇒ 头尾各读一段
        val head = readSlice(newest.f, 8 * 1024, fromEnd = false)
        val tail = readSlice(newest.f, 64 * 1024, fromEnd = true)
        val mi = runIndexRe.find(head)
        val index = mi?.groupValues?.get(1)?.toIntOrNull()
        val planned = mi?.groupValues?.get(2)?.toIntOrNull()
        val score = scoreRe.findAll(tail).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()

        val durText = endMs?.let { duration(it - startMs) }
        val scoreText = score?.let { "总分 $it" }
        return RunEntry(
            key = key,
            title = runTitle(kind, index, startMs),
            badge = badge,
            tone = tone,
            timeText = listOfNotNull(durText, scoreText).joinToString(" · ").ifEmpty { "—" },
            detail = sizeText(files.sumOf { it.sizeKb }),
            kind = kind,
            index = index,
            planned = planned,
            score = score,
            startMs = startMs,
            endMs = endMs,
            sortMs = newest.f.lastModified(),
            files = files.sortedBy { it.f.name },
        )
    }

    /** 读文件头/尾一小段（日志可能有几 MB，不能整个读进内存） */
    private fun readSlice(f: File, maxBytes: Int, fromEnd: Boolean): String = runCatching {
        java.io.RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val n = minOf(len, maxBytes.toLong()).toInt()
            if (n <= 0) return@use ""
            if (fromEnd) raf.seek(len - n)
            val buf = ByteArray(n)
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
    }.getOrDefault("")

    private fun sizeText(kb: Long): String = if (kb >= 1024) "%.1f MB".format(kb / 1024.0) else "$kb KB"

    /** 单局标题：`第 1 局 · 21:49`（刷开局/自检没有局号，用类型 + 日期） */
    private fun runTitle(kind: String, index: Int?, startMs: Long): String =
        if (kind == "MAZE" && index != null) "第 $index 局 · ${hmFmt.format(Date(startMs))}"
        else "${kindLabel(kind)} · ${mdHmFmt.format(Date(startMs))}"

    private fun parseStamp(s: String): Long? = runCatching { stampFmt.parse(s)?.time }.getOrNull()

    private fun kindLabel(kind: String): String = when (kind) {
        "MAZE" -> "跑迷宫"
        "REROLL" -> "刷开局"
        "SELFTEST" -> "自检"
        else -> kind
    }

    /** `abort/` 里的结论标签 → 人话；未知标签原样附在括号里（对日志时还能认） */
    private fun abortLabel(tag: String?): String = when (tag) {
        null -> "中途退出"
        "ABORT_STOP" -> "手动停止"
        "ABORT_EXIT" -> "中途退出"
        "ABORT_RETREAT" -> "按策略撤退"
        // ★细分到"哪一条策略"（2026-11-02 用户）：老文件仍是上面那条笼统的说法
        "ABORT_RETREAT_SCORE" -> "凹分不足撤退"
        "ABORT_RETREAT_EVENT" -> "凹分：事件条件撤退"
        "ABORT_RETREAT_NOPLAN" -> "编组算不出队伍，撤退"
        "ABORT_RETREAT_FAIL3" -> "连败 3 次撤退"
        "ABORT_RETREAT_OTHER" -> "按策略撤退（原因未知）"
        "ABORT_REPLACED" -> "被新一局替换"
        "ABORT_CRASH" -> "异常中断"
        else -> "中途退出（$tag）"
    }

    private fun duration(ms: Long): String {
        val min = (ms / 60000).toInt()
        return when {
            min >= 60 -> "${min / 60} 小时 ${min % 60} 分"
            min >= 1 -> "$min 分钟"
            else -> "${ms / 1000} 秒"
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
        val now = System.currentTimeMillis()
        for (s in sinks) {
            if (s.bytes >= MAX_BYTES) continue
            runCatching {
                s.w.write(line)
                s.bytes += line.length
                s.pending += line.length
                // ★攒够字节数或时间才落盘（见 FLUSH_BYTES/FLUSH_MS 注释）
                if (s.pending >= FLUSH_BYTES || now - s.lastFlush >= FLUSH_MS) {
                    s.w.flush()
                    s.pending = 0
                    s.lastFlush = now
                }
            }
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
