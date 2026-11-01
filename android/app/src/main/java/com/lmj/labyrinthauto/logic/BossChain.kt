package com.lmj.labyrinthauto.logic

import org.json.JSONObject

/**
 * **Boss 链影子比对（2026-10-31）** —— 只判断"走到第几步"，**不接管任何动作**。
 *
 * 为什么先做影子：观测到的 Boss 节点真实序列（5 局里 4 局完全一致，19 页）是
 *
 * ```
 * 地图 → QuestDetail → PartyEdit → CommonFullSize → CommonBig → CommonFullSize
 *      → PartyEdit → QuestDetail → 地图(战斗) → LabExResult
 *      → BackKeyCapture → FlatoutPlayer → 地图
 * ```
 *
 * 这条链现在靠 `_EXEQ_SESS` / `_PARTY_OP` / `_FAIL_TAP` 三个缓存 + 页面派发拼出来，
 * 直接改成"链驱动"风险高（中途任一步判错就卡住）。所以第一步只做**影子状态机**：
 * 用上面的表去解释"当前这页属于第几步"，把偏差写进日志（跟 PlanProbe 同一个文件），
 * 跑几局看吻合率。吻合率够高再让它接管动作。
 *
 * ★三条铁律（和 [PlanProbe] 一样）：
 *   1. **不 tap、不改状态、不参与分派**，只在 step 变化时写一行日志；
 *   2. 认不出来就记 `unknown` 并**原地不动**，绝不猜；
 *   3. 任何异常自禁用，不影响跑图。
 *
 * 关键设计（对着观测数据来的，别随手改）：
 *   · **同一页会出现在多个 step**（地图 0/6/10、详情 1/5、编队 2/4）⇒ 用"就近往后找"匹配，
 *     不是简单查表；
 *   · **特别装备是可选步**（有的局不出装备页，直接开打）⇒ 允许跨过 [SKIP_AHEAD] 步；
 *   · **节点边界不等于链边界**：Boss 打完后还会回到同一 block 做层结算，
 *     所以链在"回到地图"或出现层结算页时结束，而不是看到 block 变了就断。
 */
object BossChain {

    @Volatile
    var enabled: Boolean = true

    /** 允许"往后跳"的步数（覆盖"跳过特别装备"这种可选步） */
    private const val SKIP_AHEAD = 4

    /** 断言层最多连挂几轮就放行（≈3s，POLL_MS=100ms；防止链表不全把流程卡死） */
    private const val DRIFT_MAX = 30

    /** 每步的期望停留上限（超了写一条 warn，纯观测） */
    private const val STEP_TIMEOUT_MS = 60_000L
    private const val BATTLE_TIMEOUT_MS = 180_000L

    /** 一步 = 名字 + 该步允许出现的页面（pageTop 前缀，"" 表示无顶框/地图页） */
    private class Step(val name: String, val tops: Set<String>, val timeout: Long)

    private val STEPS = listOf(
        Step("地图待命", setOf(""), 30_000),
        Step("详情·挑战", setOf("DialogQuestDetail"), STEP_TIMEOUT_MS),
        Step("编队", setOf("DialogCommonPartyEdit"), 90_000),
        Step("特别装备", setOf("DialogCommonFullSize", "DialogCommonBig"), 60_000),
        Step("编队·开战", setOf("DialogCommonPartyEdit"), 60_000),
        Step("详情·开打", setOf("DialogQuestDetail"), STEP_TIMEOUT_MS),
        Step("战斗", setOf("", "ViewBattle"), BATTLE_TIMEOUT_MS),
        Step("EX 结算", setOf("DialogLabyrinthExResult"), STEP_TIMEOUT_MS),
        Step("过场", setOf("DialogBackKeyCapture"), 60_000),
        Step("自动战斗提示", setOf("DialogFlatoutPlayer"), 30_000),
        Step("回地图", setOf(""), 30_000),
    )

    /** 出现这些页面说明 Boss 战已结束、进入层收尾 ⇒ 链结束（不算链内的步） */
    private val CHAIN_END_TOPS = listOf("DialogLabyrinthResult", "DialogAnimationViewer")

    private var active = false
    private var step = 0
    private var stepAt = 0L
    private var pages = 0
    private var backs = 0
    private var unknowns = 0
    private var driftRounds = 0          // 断言层连挂轮数（对得上就清零）
    private var block = ""
    private var doneBlock = ""          // 已跑完的节点（Boss 打完还会落在同一 block 做层结算，别重开）
    private var t0 = 0L
    private var lastTop = ""
    private var saidDisabled = false

    /**
     * **实装第一刀：派发前的断言**（2026-11-01 用户："就这样做实装，运行中测试"）。
     *
     * 只在 Boss 节点生效：拿"当前页"去核对链的期望步——
     *   · 对得上（本步/下一步/上一步）⇒ 放行，照常走页面派发；
     *   · 对不上 ⇒ **本轮先不派发**（宁可不点，也不要点在错误的页上），最多连挂 [DRIFT_MAX] 轮；
     *   · 连续挂满 ⇒ 自动放行（防止链表不全把流程卡死），并记一条 `guard` 日志。
     *
     * 为什么是"拦"而不是"点"：现在已知的事故几乎都是**两页叠在一起时点错坐标**
     * （实测把「挑战」点成了「战斗开始」）。链唯一比页面派发多知道的事就是"我现在应该在第几步"，
     * 所以先用它做**防误点**，接管动作留到后面。
     */
    fun guard(top: String): Boolean {
        if (!enabled || !active) return true
        return try {
            if (match(step, top) != null) {
                driftRounds = 0
                true
            } else {
                driftRounds++
                if (driftRounds == 1) emit("guard", "页面与链期望不符 → 本轮不派发：$top", top)
                if (driftRounds > DRIFT_MAX) {
                    driftRounds = 0
                    emit("guard", "连续 $DRIFT_MAX 轮对不上 → 放行（链表可能缺这一步）：$top", top)
                    true
                } else false
            }
        } catch (_: Throwable) {
            true
        }
    }

    /** 是否已过"编队·开战"（step≥5）—— 此后的详情页只是战斗过渡，别再去点「挑战」 */
    fun isAfterBattleStart(): Boolean = enabled && active && step >= 5

    /** 新一局：复位（由 MazeLoop 在 run 开始时调） */
    fun reset() {
        active = false
        step = 0
        pages = 0
        backs = 0
        unknowns = 0
        driftRounds = 0
        block = ""
        doneBlock = ""
        lastTop = ""
    }

    /**
     * 每轮调用一次（和 [PlanProbe.tick] 同一位置、同一条数据）。
     * 只在 `ptype == "BOSS"` 的节点上工作；**step 变化/异常时才写日志**。
     */
    fun tick(ptype: String, blockId: String, top: String, stt: Int) {
        if (!enabled) return
        try {
            if (!ptype.equals("BOSS", ignoreCase = true)) {
                if (active) end("离开 Boss 节点（当前 $ptype）")
                return
            }
            // 层收尾页出现 ⇒ 链结束（Boss 打完后还会落在同一 block 上）。**无论 active 与否都 return**，
            // 否则会在收尾页上重新开一条链。
            if (CHAIN_END_TOPS.any { top.startsWith(it) }) {
                if (active) end("层收尾页 $top")
                return
            }
            if (!active) {
                if (blockId.isNotEmpty() && blockId == doneBlock) return   // 这个节点已经跑完了
                active = true
                step = 0
                stepAt = System.currentTimeMillis()
                t0 = stepAt
                pages = 0
                backs = 0
                unknowns = 0
                this.block = blockId
                lastTop = ""
                emit("start", "开始 Boss 链【$blockId】", top)
                return
            }
            if (top != lastTop) pages++          // 只数"页"，不是轮（同页会 tick 几百次）
            lastTop = top

            val hit = match(step, top)
            when {
                hit == null -> {
                    unknowns++
                    if (unknowns <= 5) emit("unknown", "认不出：$top（停在 ${STEPS[step].name}）", top)
                }
                hit > step -> {
                    // 跨步（可能跳过了可选步）：逐步记一行，方便对账
                    for (s in step + 1..hit) emit("advance", "→ ${STEPS[s].name}", top)
                    step = hit
                    stepAt = System.currentTimeMillis()
                }
                hit < step -> {
                    backs++
                    emit("back", "回退 ${STEPS[step].name} → ${STEPS[hit].name}", top)
                    step = hit
                    stepAt = System.currentTimeMillis()
                }
                else -> {
                    val now = System.currentTimeMillis()
                    if (now - stepAt > STEPS[step].timeout) {
                        emit("timeout", "停在 ${STEPS[step].name} 超过 ${(now - stepAt) / 1000}s", top)
                        stepAt = now          // 只报一次
                    }
                }
            }
            // 到达最后一步（回地图）⇒ 正常收工
            if (step == STEPS.size - 1) end("回到地图，链完成")
        } catch (e: Throwable) {
            if (!saidDisabled) {
                saidDisabled = true
                enabled = false
                logDisabled("[!] Boss 链影子已自禁用（${e.message}）——不影响跑图")
            }
        }
    }

    // ───────────────────────── 内部 ─────────────────────────

    /** 就近匹配：先看当前步，再往后找 ≤ [SKIP_AHEAD] 步，最后看前一步（短回退）。 */
    private fun match(cur: Int, top: String): Int? {
        if (fits(cur, top)) return cur
        for (s in cur + 1..minOf(cur + SKIP_AHEAD, STEPS.size - 1)) {
            if (fits(s, top)) return s
        }
        if (cur > 0 && fits(cur - 1, top)) return cur - 1
        return null
    }

    private fun fits(s: Int, top: String): Boolean {
        val st = STEPS[s]
        if (top.isEmpty()) return st.tops.contains("")
        return st.tops.any { it.isNotEmpty() && top.startsWith(it) }
    }

    private fun end(reason: String) {
        doneBlock = block
        val cost = System.currentTimeMillis() - t0
        emit("end", "$reason（${STEPS.getOrNull(step)?.name ?: "?"}，$pages 页 / ${cost / 1000}s" +
            " / 回退 $backs / 认不出 $unknowns）", lastTop)
        active = false
        step = 0
    }

    /** 写进 PlanProbe 的同一份 jsonl（`ev=chain`），不额外开文件 */
    private fun emit(verdict: String, note: String, top: String) {
        PlanProbe.chainEvent(
            JSONObject()
                .put("chain", "BOSS")
                .put("step", step)
                .put("stepName", STEPS.getOrNull(step)?.name ?: "?")
                .put("verdict", verdict)
                .put("note", note)
                .put("top", top)
                .put("block", block)
                .put("pages", pages)
        )
    }

    private fun logDisabled(msg: String) = com.lmj.labyrinthauto.core.AppState.addLog(msg)
}
