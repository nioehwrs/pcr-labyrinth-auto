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

    /**
     * ★探测间隔挡位（2026-11-17 用户）：点完探"页面变没变"的间隔，默认 150ms。
     *  取值范围见 [com.lmj.labyrinthauto.reroll.RerollSettings.TAP_PROBE_STEPS]（150/200/250/300/350）。
     *  · 由 [com.lmj.labyrinthauto.LmjApp] 启动时 + 策略页改动时从设置注入（这里不直接读 Context，
     *    避免逻辑层依赖 Android）；所有**用默认 probeMs 的调用点**自动跟随。
     *  · 屏幕中心慢页 = 本值 ×2（见 Navigator）；各 settleMs 不受影响（那些是逐个坑调出来的）。
     */
    @Volatile
    private var probeMsValue = 150L

    /**
     * ★★2026-11-20（用户）：**直调档**的探测间隔（60ms）。
     *   直调不点穿、不怕"探太快"（那是坐标点击的风险），而 `labExEquipSig` 这类签名读只要 10~20ms，
     *   150ms 一探等于每次"页面变了没"最多晚 150ms 才发现。配合 [run] 里直调档把探针次数放大
     *   （≥10 次），总窗口仍是 600ms+，但检测粒度细一倍多。坐标点击路径不受影响。
     */
    private const val DIRECT_PROBE_MS = 60L

    fun setProbeMs(ms: Long) {
        probeMsValue = ms.coerceIn(150L, 350L)
    }

    fun currentProbeMs(): Long = probeMsValue

    /**
     * 一次 probe 的结果。
     *
     * ★`top` 和 `left` **必须来自同一次内存快照**：先读 labTop、再另读一次关闭态，
     *   两次之间状态可能已经变化（top 说"还在"、closing 说"已关"）⇒ 判据不自洽。
     *   调用方通过 [run] 的 `probeState` 一次返回，阶段 1 就只做一次 RPC。
     *
     * @param top 当前窗口规范名（仅用于日志）
     * @param left 这一下是否已生效（已离开 from / 关闭已启动）
     * @param diag **顺便**带回的诊断串（如退出 tween 的 `started/factor`），只进 probe 轨迹日志；
     *        它和 `top`/`left` 来自同一次读取，不额外发 RPC、也不拖慢 probe 节奏。
     */
    data class Probe(val top: String, val left: Boolean, val diag: String = "")

    suspend fun run(
        tapFn: suspend (Int, Int, String) -> Boolean,
        x: Int,
        y: Int,
        what: String,
        from: Set<String>,
        /** 点完**第一次**探测前等多久。0（默认）= 自动取 [probeMs]（2026-11-17 用户：效率优先，
         *  连点由 [probe] 次数兜住——探到离开就停，不会形成点击洪水）。 */
        settleMs: Long = 0L,
        retries: Int = 1,
        expect: Set<String> = emptySet(),
        expectTimeoutMs: Long = 6_000L,
        readTop: (suspend () -> String)? = null,
        unknownTop: String = "",
        xyAt: (suspend () -> Pair<Int, Int>?)? = null,
        probe: Int = 0,
        probeMs: Long = currentProbeMs(),
        reached: (suspend (String) -> Boolean)? = null,
        /**
         * ★阶段2**自定义到达判据**（2026-11-20，Boss 两轮选人）：每拍用当前 top 调一次，
         * 返回 true 就算"到达下一阶段"，不必等 `t in expect` 或超时。
         * 典型用法：top 还是同一个 Selection、但卡片签名已变 ⇒ Boss 第一轮完成，立即进入第二轮。
         */
        arrived: (suspend (String) -> Boolean)? = null,
        /** ★给了它就**只调它一次**（同一次快照同时给 top + 判据），不再读 readTop / reached。 */
        probeState: (suspend () -> Probe)? = null,
        skipMax: Int = 6,
        /** 跳拍 / xyAt 取不到时的间隔。默认 = [probeMs]（2026-11-17 用户：跟挡位联动，不再写死 60）。 */
        skipDelayMs: Long = probeMs,
        /** ★首点前先「只读不点」几拍（新页入场动画期间按钮坐标还在动，第一下必空）；每拍 [skipDelayMs]。
         *  2026-11-17 用户：默认 1 拍（= 1×probeMs）先试试，抑制"第一下点空"。 */
        skipFirstHits: Int = 1,
        /**
         * ★★**直调执行器**（2026-11-19）：给了它就**代替** [tapFn]（[x] / [y] / [xyAt] 全部忽略）。
         *
         * 复用本模型而不是另写一套：重试、探针节奏、到达目标页的判据全都一样，
         * 直调只是**换一个触发方式** —— 不投屏幕坐标 ⇒ 不受前台/遮挡/叠层影响，也省掉 `input tap` 的 ~110ms。
         *
         * @return `true` = 已投递到游戏线程；`false` = 投递失败（直接收工）；
         *         `null` = 这一刻不该点（页面在切 / 按钮没定位到）⇒ 跳过并重试，**不消耗** [retries]
         */
        pressFn: (suspend () -> Boolean?)? = null,
        /**
         * ★★**直调最多真正投递几次**（默认 1，**不可放开**）。
         *
         * 2026-11-19 三选一卡片动画慢（约 1s），按 retries 连投 8 次 ⇒ 游戏真收到 8 次「选中」、
         * 错误码 205 回标题。★2026-11-20 实测复现：放开到 4 + 加速档（30ms 首探）后，
         * `战后选人` 一次连投 4 轮 ⇒ 205（日志 `第 8 次点击，直调 4 轮`）。
         *
         * ⚠**点穿/点错**（坐标点击的风险）和**重复执行同一 onClick**（直调的风险）是两回事：
         *   直调确实不点穿，但同一按钮投多次 = 游戏执行多次 ⇒ 选人/购买等有副作用的页必须只投一次。
         *   ⇒ 保持 1：只投一次、之后只探测（把 [retries] 当纯探测窗口用）。日志仍打出 `直调N轮` 供观察。
         */
        pressMax: Int = 1,
        /** ★直调是否走加速档（默认 true）。结算页（pressFn 内部直调失败会退坐标）传 false 保留
         *   慢节奏——否则 settle 30ms + probe 密会重现 2026-11-13 的「1.4s 连点 7 下 → lmkd 杀进程」。
         *   纯直调页（选人/编组/EX/商店/门票）无需管。 */
        directFast: Boolean = true,
        verbose: Boolean = true,
    ): Boolean {
        // ══ 2026-11-20 直调加速档（用户：直调无点穿风险，坐标时代的等待对它是纯浪费）══
        //   · skipFirstHits（首点前只读一拍等坐标落位）、effSettle（首探前等页面稳定）、
        //     probeMs×2（EX 防"点穿到动画中按钮"）——这些都是坐标点击的护栏，直调一概不需要。
        //   · 阶段2 expect/arrived（等游戏切页）、pressMax（防重复 onClick）保留。
        val isDirect = pressFn != null
        val fast = isDirect && directFast
        val effProbeMs = if (fast) DIRECT_PROBE_MS else probeMs   // 直调档细探 60ms（忽略调用点的 ×2）
        val effSkipFirstHits = if (fast) 0 else skipFirstHits
        val effSkipDelayMs = if (fast) 30L else skipDelayMs   // 直调跳拍留 30ms 窗口（0 会让 6 次跳拍几毫秒跑完）
        // ── 阶段 1：反复点，直到**离开 from**（证明这一下生效了）──
        // ★settleMs 未显式给（0）⇒ 取 probeMs（直调压到 30ms 让首探尽快返回）。
        val effSettle = if (settleMs > 0L) settleMs else if (fast) 30L else probeMs
        var attempt = 0
        var skip = 0                        // "这一刻不该点"的次数（不消耗 retries，但要有上限防死循环）
        var firstSkip = 0                   // 首点前已经跳过的拍数
        var directFired = 0                 // ★直调已经真正投递过几次（受 [pressMax] 限制）
        var left = false
        var nowTop = ""
        // ★直调档细探（60ms）⇒ 探针次数放大到 ≥10，保证总窗口仍 ≥600ms（原来 4×150）。
        //   窗口不缩是**必须**的：窗口太短会判"没生效"⇒ 外层重进 ⇒ pressMax 额度重置 ⇒ 重复 onClick。
        val maxProbe = if (fast) maxOf(probe.coerceAtLeast(1), 10) else probe.coerceAtLeast(1)
        while (attempt < retries.coerceAtLeast(1)) {
            // ★2026-11-12（用户）：新页刚出现时按钮还在入场动画（RectTransform 未落位）⇒
            //   首次点击前先**只读不点**跳几拍，下一拍 `xyAt` 会重新取落位后的坐标再点。
            //   不做长固定延迟；跳拍不消耗 retries，但计入 skip 上限防死循环。
            if (attempt == 0 && firstSkip < effSkipFirstHits) {
                firstSkip++
                skip++
                if (skip > skipMax) return false
                delay(effSkipDelayMs)
                continue
            }
            // 坐标：给了 xyAt 就**每次点击前现取**（切页动画期间坐标在动 ⇒ 旧坐标会点空）。
            var px = x
            var py = y
            if (pressFn != null) {
                if (directFired >= pressMax.coerceAtLeast(1)) {
                    // ★★已经直调过了 ⇒ **只探测、绝不再投**（直调多点会真的重复执行游戏逻辑）
                    attempt++
                } else {
                    // ★直调：null = 这一刻不该点（页面在切 / 按钮没定位到）⇒ 跳过并重试，不消耗 retries
                    val fired = runCatching { pressFn() }.getOrNull()
                    if (fired == null) {
                        skip++
                        if (skip > skipMax) return false
                        delay(effSkipDelayMs)
                        continue
                    }
                    attempt++
                    if (!fired) return false
                    directFired++
                }
            } else {
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
            }
            // ★2026-11-19（日志精简）：删掉「⏱ probe … 轨迹」输出（每次探测一行、纯诊断噪音）；
            //   命中与否由下面的 `⤷ …` 一行体现。
            // ── 点完**探**一下"页面变没变" ──
            var probed = 0
            while (probed < maxProbe) {
                delay(if (probed == 0) effSettle else effProbeMs)
                probed++
                val hit: Boolean
                if (probeState != null) {
                    // ★一次快照：top / 判据 / 诊断同源；读失败 ⇒ top 空、left=false（不算离开）
                    val ps = runCatching { probeState() }.getOrNull()
                    nowTop = ps?.top ?: ""
                    hit = ps?.left == true
                } else {
                    nowTop = if (readTop != null) {
                        runCatching { readTop() }.getOrDefault(unknownTop)
                    } else {
                        runCatching { AgentApi.labTop() }.getOrNull() ?: ""
                    }
                    // 探针读失败 ⇒ **不算"离开"**
                    if (readTop != null && nowTop == unknownTop) continue
                    // 判据优先用 reached（"到达目标页了没"）；没传就退回老行为（离开 from 就算生效）
                    hit = if (reached != null) reached(nowTop) else (nowTop !in from)
                }
                if (hit) {
                    left = true
                    if (verbose) {
                        val fireTag = if (isDirect) "，直调 $directFired 轮" else ""
                        AppState.addLog(
                            "⤷ [${from.joinToString("/")}] --$what--> [$nowTop] " +
                                "（第 $attempt 次点击${fireTag}，探 $probed 次已离开）",
                        )
                    }
                    break
                }
                // ★"**进战斗了**"也要算"离开"：进战斗时编组页那个对话框可能还留在场景里
                //   ⇒ 只看窗口名永远判不出"点生效了" ⇒ 一直重试（实测连点 6 下全是白点）。
                if ("@battle" in from && runCatching { AgentApi.labInBattle() }.getOrDefault(false)) {
                    left = true
                    if (verbose) {
                        val fireTag = if (isDirect) "，直调 $directFired 轮" else ""
                        AppState.addLog(
                            "⤷ [${from.joinToString("/")}] --$what--> [已进入战斗] " +
                                "（第 $attempt 次点击${fireTag}，探 $probed 次）",
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
            // ① 目标页（便宜、准）。★2026-11-09：调用方给了 `readTop` 就用它 ——
            //   刷开局那套用的是 **viewId**（`259/263/264`），和窗口名是两套口径；
            //   没给就还是原来的窗口名。
            val t = if (readTop != null) {
                runCatching { readTop() }.getOrDefault(unknownTop)
            } else {
                runCatching { AgentApi.labTop() }.getOrNull() ?: ""
            }
            // ★自定义到达判据优先（如"卡片签名变了"）：top 没变也能立即算到达（Boss 两轮选人）。
            if (arrived != null && runCatching { arrived(t) }.getOrDefault(false)) {
                if (verbose) {
                    AppState.addLog(
                        "⤷ 阶段2 [${from.joinToString("/")}] --$what--> 自定义到达（卡片刷新，top=$t）" +
                            "用时 ${android.os.SystemClock.elapsedRealtime() - t0}ms",
                    )
                }
                return true
            }
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
