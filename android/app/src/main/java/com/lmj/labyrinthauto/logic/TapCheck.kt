package com.lmj.labyrinthauto.logic

import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.frida.AgentApi
import kotlinx.coroutines.delay

/**
 * **点一下 → 探一下"页面变了没"** —— 全项目唯一的点击-确认模型。
 *
 * 原来这个逻辑只是 [MazeLoop] 里的私有 `tapAndCheck`（"选人页专用"），后来 EX 三连页、
 * 结算页、编组页都用上了。2026-11-08 用户要求把**进入迷宫之前**的线性导航
 * （首页 → 冒险 → 黎明界 → 出发，见 [Navigator.goToLabyrinthTop]）也改成这个模型
 * —— 那一段不在主循环里，所以把它抽成独立的顶层实现：
 *   · [MazeLoop.tapAndCheck] 变成它的**薄包装**（调用点零改动）；
 *   · [Navigator] 直接用它。
 *
 * ★为什么不能"点了就固定 sleep 700/900ms"：
 *   固定等待是"赌页面在那么久内会切完"。切完得早 ⇒ 白等；切完得晚 ⇒ 误判"没生效" ⇒ 再点
 *   （而再点那下可能正好点在动画里的另一个目标上）。探针是**更快知道"变没变"**，
 *   变了立刻往下走，没变才继续等 —— 不是"加等待换精准"。
 *
 * @param tapFn 真正点击（返回 false = 这一下没打出去，直接收工）。抽成参数是为了让
 *        [MazeLoop] 与 [Navigator] 各自用它自己的 `tap`（带各自的前台闸门/日志）。
 * @param x @param y 点击坐标（给了 [xyAt] 时只在首次/兜底用）
 * @param what 日志里这一下的名字（`战斗开始` / `ButtonQuest` …）
 * @param from **当前所在位置**；读到不在 [from] 里 ⇒ 判定"这一下生效了"
 * @param settleMs 点完第一次探之前停多久（后面几次用 [probeMs]）
 * @param retries 同一个动作最多点几次（`1` = 不重试，交给调用方）
 * @param expect **阶段 2** 要等的目标；空集 = "离开 [from] 就算完成"，不等后续页面。
 *        ⚠[from]/[expect] 必须与 [readTop] 的**口径一致**（都用窗口名，或都用 viewId 字符串）。
 * @param readTop "当前在哪"的探针；null = 用 [AgentApi.labTop]（最上层对话框名）
 * @param unknownTop 探针**读失败**时的返回值。给了 [readTop] 时它会被当成"**还没离开**"
 *        （否则会把"读不到"误判成"点生效了" ⇒ 状态机直接跳到下一步 ⇒ 点错按钮）
 * @param xyAt 每次点击前**现取坐标**（null 返回值 = 这一刻不该点，**不消耗** [retries]）
 * @param probe 点完连探几次（探到"变了"就停）；0/1 = 只探一次
 * @param reached 阶段 1 的目标判据（可选）。传了它就**只认它**：到目标 = 完成；
 *        "离开了 from 但没到目标" = 这次没生效 ⇒ 重试同一个动作。不传 = 离开 from 就算生效
 * @param skipMax [xyAt] 返回 null 的最多次数（防"永远不该点"的死循环）
 * @param skipDelayMs [xyAt] 返回 null 时的重试间隔
 */
object TapCheck {

    suspend fun run(
        tapFn: suspend (Int, Int, String) -> Boolean,
        x: Int,
        y: Int,
        what: String,
        from: Set<String>,
        settleMs: Long = 150L,
        retries: Int = 1,
        expect: Set<String> = emptySet(),
        expectTimeoutMs: Long = 6_000L,
        readTop: (suspend () -> String)? = null,
        unknownTop: String = "",
        xyAt: (suspend () -> Pair<Int, Int>?)? = null,
        probe: Int = 0,
        probeMs: Long = 150L,
        reached: (suspend (String) -> Boolean)? = null,
        skipMax: Int = 6,
        skipDelayMs: Long = 60L,
        verbose: Boolean = true,
    ): Boolean {
        // ── 阶段 1：反复点，直到**离开 from**（证明这一下生效了）──
        var attempt = 0
        var skip = 0                        // "这一刻不该点"的次数（不消耗 retries，但要有上限防死循环）
        var left = false
        var nowTop = ""
        val maxProbe = probe.coerceAtLeast(1)
        while (attempt < retries.coerceAtLeast(1)) {
            // 坐标：给了 xyAt 就**每次点击前现取**（切页动画期间坐标在动 ⇒ 旧坐标会点空）。
            var px = x
            var py = y
            if (xyAt != null) {
                val p = runCatching { xyAt() }.getOrNull()
                if (p == null) {
                    skip++
                    if (skip > skipMax) return false
                    delay(skipDelayMs)
                    continue
                }
                px = p.first
                py = p.second
            }
            attempt++
            if (!tapFn(px, py, what)) return false
            // ── 点完**探**一下"页面变没变" ──
            var probed = 0
            while (probed < maxProbe) {
                delay(if (probed == 0) settleMs else probeMs)
                probed++
                nowTop = if (readTop != null) {
                    runCatching { readTop() }.getOrDefault(unknownTop)
                } else {
                    runCatching { AgentApi.labTop() }.getOrNull() ?: ""
                }
                // 探针读失败 ⇒ **不算"离开"**
                if (readTop != null && nowTop == unknownTop) continue
                // 判据优先用 reached（"到达目标页了没"）；没传就退回老行为（离开 from 就算生效）
                val hit = if (reached != null) reached(nowTop) else (nowTop !in from)
                if (hit) {
                    left = true
                    if (verbose) {
                        AppState.addLog(
                            "⤷ [${from.joinToString("/")}] --$what--> [$nowTop] " +
                                "（第 $attempt 次点击，探 $probed 次已离开）",
                        )
                    }
                    break
                }
                // ★"**进战斗了**"也要算"离开"：进战斗时编组页那个对话框可能还留在场景里
                //   ⇒ 只看窗口名永远判不出"点生效了" ⇒ 一直重试（实测连点 6 下全是白点）。
                if ("@battle" in from && runCatching { AgentApi.labInBattle() }.getOrDefault(false)) {
                    left = true
                    if (verbose) {
                        AppState.addLog(
                            "⤷ [${from.joinToString("/")}] --$what--> [已进入战斗] " +
                                "（第 $attempt 次点击，探 $probed 次）",
                        )
                    }
                    break
                }
            }
            if (left) break
        }
        if (!left) return false
        if (expect.isEmpty()) return true

        // ── 阶段 2：**停手**，只等目标页面出现 ──
        //   "离开前一个页面就结束点击，但要等到后一个页面出现，才算状态机完成"（2026-11-07 用户）
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - t0 < expectTimeoutMs) {
            // ① 窗口名（便宜、准）
            val t = runCatching { AgentApi.labTop() }.getOrNull() ?: ""
            if (t in expect) {
                if (verbose) {
                    AppState.addLog(
                        "⤷ 阶段2 [${from.joinToString("/")}] --$what--> 到达 [$t] " +
                            "用时 ${android.os.SystemClock.elapsedRealtime() - t0}ms",
                    )
                }
                return true
            }
            // ② 特殊目标 `@battle`：进战斗后**没有对话框**（labTop 是空串），只能用 inBattle 判
            if ("@battle" in expect && runCatching { AgentApi.labInBattle() }.getOrDefault(false)) {
                if (verbose) {
                    AppState.addLog(
                        "⤷ 阶段2 [${from.joinToString("/")}] --$what--> 已进入战斗 " +
                            "用时 ${android.os.SystemClock.elapsedRealtime() - t0}ms",
                    )
                }
                return true
            }
            delay(100)
        }
        AppState.addLog("⤷ 阶段2 [${from.joinToString("/")}] --$what--> 等 $expectTimeoutMs ms 仍未到达（期望 $expect）")
        return false
    }
}
