package com.lmj.labyrinthauto.logic

import android.content.Context
import android.os.SystemClock
import com.lmj.labyrinthauto.core.AppState
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * **流程观测探针（P0-观察，2026-10-31）** —— 只记录，**不控制任何动作**。
 *
 * 目的：为"把单体主循环拆成多条链"攒事实依据。当前流程是"按页面独立判断推进"，
 * 但又靠 37 个按 tag 去重的缓存维持前后关联 ⇒ 真实流程藏在日志里、看不出结构。
 * 这里把**节点 → 页面序列**结构化落盘，之后用 `tools/seq_report.py` 离线归纳出"链序列模板"，
 * 再决定哪几条链值得建、哪些页永远是中断页。
 *
 * ★三条铁律（照这个写，别越界）：
 *   1. **不改行为**：不 tap、不改状态、不参与任何判定；失败就自禁用，绝不影响跑图；
 *   2. **不控频**：主循环每轮调一次 [tick]（100ms 一轮），但**同一页只在离开时写一条**；
 *   3. **不引入额外 RPC**：只用调用方已经读到的 `st`/`tag`，一个 agent 调用都不加。
 *
 * 产出：`<外部files>/logs/probe/maze_seq_<yyyyMMdd_HHmmss>.jsonl`，一局一个文件，
 * 每行一条"这一页发生了什么"（含期间的动作序列），外加 `ev=run_end` 收尾行。
 *
 * 字段（也是 `seq_report.py` 依赖的契约，改字段记得同步）：
 * ```
 * ev      "page" | "stall" | "run_end"
 * run     局标识；i 本局第几个页面
 * node/ptype/area/block   节点快照（ptype = NORMAL/BOSS/SHOP/EVENT/RELIC/TICKET/HARD）
 * stt/sttName/top/view/inBattle   页面快照
 * t0/ms/rounds/idleMax    进入时刻、停留时长、经历轮数、期间最大 idle
 * acts    期间点过的按钮（"点了 xxx (x,y)" 的 what，最多保留 [MAX_ACTS] 条）
 * prev    上一页的 top（看"从哪来"，判断回退/绕圈用）
 * ```
 */
object PlanProbe {

    /** 总开关：出现任何异常/奇怪现象，置 false 即整体停写（不影响主流程） */
    @Volatile
    var enabled: Boolean = true

    /** 同一页停留超过这个时长就补一条 `ev=stall`（只报一次/页） */
    private const val STALL_MS = 30_000L

    /** 每页最多留几条动作（够看出"这页点了什么"，又不会把文件撑大） */
    private const val MAX_ACTS = 12

    private val tFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val runFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lmj-planprobe").apply { isDaemon = true }
    }
    private val lock = Any()

    private var w: BufferedWriter? = null
    private var runId = ""
    private var pageSeq = 0
    private var startAt = 0L            // elapsedRealtime（算总时长用）
    private var startWall = 0L          // 墙上时间（落盘用）
    private var saidDisabled = false

    /** 当前页（离开时才落盘） */
    private class Cur(
        val tag: String, val node: String, val ptype: String, val area: Int, val block: String,
        val stt: Int, val sttName: String, val top: String, val view: String, val inBattle: Boolean,
        val prevTop: String, val t0: Long,
        /** 墙上时间（毫秒 epoch）—— `t0` 是 [SystemClock.elapsedRealtime]（开机毫秒），
         *  不能当时间戳格式化，否则会写成"22:52"这种假时间（2026-11-01 修）。 */
        val t0Wall: Long = System.currentTimeMillis(),
    ) {
        var rounds = 0
        var idleMax = 0
        var stallSaid = false
        val acts = ArrayList<String>(8)
    }

    private var cur: Cur? = null

    /** 一局开始：开新文件。失败即整体禁用（只提示一次） */
    fun startRun(ctx: Context?) {
        if (!enabled || ctx == null) return
        try {
            stopRunLocked()
            val dir = runCatching {
                val ext = ctx.getExternalFilesDir(null)
                val d = if (ext != null) File(ext, "logs/probe") else File(ctx.filesDir, "logs/probe")
                d.mkdirs()
                if (d.isDirectory) d else File(ctx.filesDir, "logs/probe").also { it.mkdirs() }
            }.getOrNull() ?: return
            // 只留最近 40 局（一局一个文件、每局才几十行，但别无限长）
            runCatching {
                dir.listFiles { f -> f.name.startsWith("maze_seq_") && f.name.endsWith(".jsonl") }
                    ?.sortedBy { it.lastModified() }
                    ?.dropLast(40)
                    ?.forEach { it.delete() }
            }
            runId = runFmt.format(Date())
            pageSeq = 0
            cur = null
            startAt = SystemClock.elapsedRealtime()
            startWall = System.currentTimeMillis()
            val f = File(dir, "maze_seq_$runId.jsonl")
            synchronized(lock) { w = f.bufferedWriter() }
            AppState.addLog("〔观测〕页面序列探针开启 → ${f.absolutePath}")
        } catch (e: Throwable) {
            disable("startRun: ${e.message}")
        }
    }

    /** 一局结束：补一条收尾行并关文件（[reason] 与 LogFile 的归档后缀同口径） */
    fun endRun(reason: String) {
        if (!enabled) return
        try {
            flushCur("page")        // 最后一页也要按 page 落盘（否则 seq_report 会漏这一页）
            val obj = JSONObject().put("ev", "run_end").put("run", runId)
                .put("reason", reason).put("pages", pageSeq)
                .put("ms", SystemClock.elapsedRealtime() - startAt)
                .put("t0", tFmt.format(Date(startWall))).put("t1", tFmt.format(Date()))
            writeLine(obj)
            io.execute { synchronized(lock) { runCatching { w?.flush(); w?.close() }; w = null } }
        } catch (e: Throwable) {
            disable("endRun: ${e.message}")
        }
    }

    /**
     * 主循环每轮调用一次（**位置：tag 算完之后、任何 `continue` 之前**）。
     * 同页只累加计数，页面变了才把上一页写出去。
     *
     * @param st       本轮 labStatus 的原始 JSON（只读）
     * @param tag      主循环算好的 tag（同一页 tag 相同 —— 这是"一页"的判定依据）
     * @param stt      statusType
     * @param sttName  statusName(stt)
     * @param viewPart view 的可读名（MazeLoop 已算好，避免这里重复映射）
     * @param idle     本轮 idle 计数
     */
    fun tick(st: JSONObject, tag: String, stt: Int, sttName: String, viewPart: String, idle: Int) {
        if (!enabled) return
        try {
            val now = SystemClock.elapsedRealtime()
            val c = cur
            if (c == null || c.tag != tag) {
                if (c != null) flushCur("page")
                cur = Cur(
                    tag = tag,
                    node = s(st, "blockId"),
                    ptype = s(st, "panelTypeName"),
                    area = n(st, "area"),
                    block = s(st, "blockId"),
                    stt = stt,
                    sttName = sttName,
                    top = s(st, "pageTop"),
                    view = viewPart,
                    inBattle = st.optBoolean("inBattle", false),
                    prevTop = c?.top ?: "",
                    t0 = now,
                )
                pageSeq++
            } else {
                c.rounds++
                if (idle > c.idleMax) c.idleMax = idle
                if (!c.stallSaid && now - c.t0 >= STALL_MS) {
                    c.stallSaid = true
                    writeLine(
                        JSONObject().put("ev", "stall").put("run", runId).put("i", pageSeq)
                            .put("node", c.node).put("ptype", c.ptype).put("area", c.area)
                            .put("stt", c.stt).put("sttName", c.sttName).put("top", c.top)
                            .put("view", c.view).put("ms", now - c.t0).put("rounds", c.rounds)
                            .put("t0", tFmt.format(Date(c.t0Wall))).put("t", tFmt.format(Date())),
                    )
                }
            }
        } catch (e: Throwable) {
            disable("tick: ${e.message}")
        }
    }

    /**
     * 记录一次点击（**在 [MazeLoop.tap] 真正点下去之后调**）。
     * 只挂到当前页的动作列表里，不单独落盘。
     */
    fun act(what: String) {
        if (!enabled) return
        try {
            val c = cur ?: return
            if (c.acts.size >= MAX_ACTS) return
            c.acts.add(if (what.length > 80) what.take(80) else what)
        } catch (_: Throwable) {
        }
    }

    /**
     * 供影子链（[BossChain]）写事件用：追加一行 `ev=chain` 到同一份 jsonl。
     * 调用方只负责填 `chain/step/verdict/...`，这里补 `ev` 与 `run`。
     */
    fun chainEvent(o: JSONObject) {
        if (!enabled) return
        try {
            o.put("ev", "chain").put("run", runId)
            writeLine(o)
        } catch (_: Throwable) {
        }
    }

    // ───────────────────────── 内部 ─────────────────────────

    private fun flushCur(ev: String) {
        val c = cur ?: return
        cur = null
        val now = SystemClock.elapsedRealtime()
        val obj = JSONObject()
            .put("ev", ev).put("run", runId).put("i", pageSeq)
            .put("node", c.node).put("ptype", c.ptype).put("area", c.area).put("block", c.block)
            .put("stt", c.stt).put("sttName", c.sttName).put("top", c.top)
            .put("view", c.view).put("inBattle", c.inBattle)
            .put("t0", tFmt.format(Date(c.t0Wall))).put("ms", now - c.t0)
            .put("rounds", c.rounds).put("idleMax", c.idleMax)
            .put("prev", c.prevTop)
        if (c.acts.isNotEmpty()) {
            val a = JSONArray()
            c.acts.forEach { a.put(it) }
            obj.put("acts", a)
        }
        writeLine(obj)
    }

    private fun writeLine(o: JSONObject) {
        io.execute {
            try {
                synchronized(lock) { w?.let { it.write(o.toString()); it.newLine(); it.flush() } }
            } catch (_: Throwable) {
            }
        }
    }

    private fun stopRunLocked() {
        io.execute { synchronized(lock) { runCatching { w?.flush(); w?.close() }; w = null } }
    }

    private fun disable(why: String) {
        enabled = false
        if (!saidDisabled) {
            saidDisabled = true
            AppState.addLog("[!] 观测探针已自禁用（$why）——不影响跑图")
        }
        runCatching { stopRunLocked() }
    }

    /** 安全取字符串：agent 对缺失字段可能回 null，`optString` 会得到字符串 "null" */
    private fun s(o: JSONObject, k: String): String {
        val v = o.opt(k) ?: return ""
        if (v === JSONObject.NULL) return ""
        val t = v.toString()
        return if (t == "null") "" else t
    }

    private fun n(o: JSONObject, k: String): Int = (o.opt(k) as? Number)?.toInt() ?: -1
}
