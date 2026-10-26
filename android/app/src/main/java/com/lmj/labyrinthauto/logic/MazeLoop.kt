package com.lmj.labyrinthauto.logic

import android.content.Context
import android.os.SystemClock
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.core.CenterTapGate
import com.lmj.labyrinthauto.core.LabBtnStore
import com.lmj.labyrinthauto.core.LogFile
import com.lmj.labyrinthauto.core.OverlayStatus
import com.lmj.labyrinthauto.data.GameData
import com.lmj.labyrinthauto.reroll.RerollState
import com.lmj.labyrinthauto.reroll.RerollSettings
import com.lmj.labyrinthauto.logic.party.PartyPlan
import com.lmj.labyrinthauto.logic.relic.RelicPick
import com.lmj.labyrinthauto.logic.shop.ShopPlan
import com.lmj.labyrinthauto.logic.team.Rules
import com.lmj.labyrinthauto.logic.team.TeamRunLog
import com.lmj.labyrinthauto.frida.AgentApi
import com.lmj.labyrinthauto.frida.AgentBridge
import com.lmj.labyrinthauto.frida.FridaInjector
import com.lmj.labyrinthauto.frida.GameProcess
import com.lmj.labyrinthauto.input.Foreground
import com.lmj.labyrinthauto.input.InputBackend
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * **迷宫内状态机（P5 骨架）** —— 形状逐条对应 PC 版 `pcr_auto.py` 的 `maze_loop`(5900)：
 *
 * ```
 * 读一次地图 → 循环：
 *   labStatus() ──null──→ "不在迷宫/换区途中"（第 1 次 + 每 10 次报一次）；若已过结算 → 收工
 *      │
 *      └─ tag = 节点/节点类型/状态/视图/顶框 —— 换页才重置各计数（PC 6004~6029）
 *           ├─ statusType != 0 → STATUS_HANDLERS[状态]（1 召唤 2 门票 4 事件 5/6 战斗 7 商店 8 演出）
 *           └─ statusType == 0 → PAGE_HANDLERS（选人页 / 结算页）
 * ```
 *
 * ★骨架阶段**一个 handler 都还没搬**：分派结果统一走 [unknownState] —— 把现场
 * （status/dialog/choices/nodes/buttons）一次性打进日志（同一 `状态/节点类型` 只打一次，
 * PC `unknown_state_diag`(1061) 同款去重），照着这段就能补对应 handler。
 *
 * ★与 PC 的两处**有意偏差**（都标在这里，等 handler 搬进来就删）：
 *   1. PC 换页不再打"状态变了"整行（动作由 handler 用 say() 说）；骨架没有动作，
 *      所以**换页打一行**（标了「现场」），否则日志全黑没法判断它在不在干活；
 *   2. PC 同页 30 轮无有效动作就退出；骨架永远不会有"有效动作"，所以**不设这个上限**，
 *      靠 前台切换 / agent 断开 / 手动停止 退出。
 */
object MazeLoop {

    /** PC 版 `MAZE_STATUS_NAMES`(502)：statusType → 名字（仅日志可读性） */
    private val STATUS_NAMES = mapOf(
        0 to "NONE", 1 to "SUMMON_UNIT", 2 to "SUMMON_TICKET_CHOICE", 3 to "USE_RELIC",
        4 to "EVENT_CHOICE", 5 to "QUEST", 6 to "BOSS", 7 to "SHOP", 8 to "EVENT_EFFECT",
        9 to "RELIC_CHOICE", -1 to "INVALID_VALUE",
    )

    fun statusName(t: Int): String = STATUS_NAMES[t] ?: "?($t)"

    /** 遗物决策层（PC `RelicPick`）—— 每局 run 开始时按当前 assets 重建 */
    private lateinit var relicPick: RelicPick

    /** 本局已遇 Boss 的 unitId（按区记）：PC 从 `labyrinth_state.json` 的 boss 列表取 3 区 Boss，
     *  这里改为"遇到 BOSS 节点时记下来"，让遗物特殊条件（毒液沙罗曼蛇）能对上（见 [handleRelicChoice]） */
    private val bossByArea = mutableMapOf<Int, Int>()

    /** 同一组候选卡已点次数（按 tag；组变了重置）—— PC `REL_TAP_MAX`(471) 防重复砸同一坐标把游戏点卡 */
    private val relicChoiceTries = mutableMapOf<String, Pair<String, Int>>()

    /** PC 版 `REL_TAP_MAX`(471)：同一组候选卡最多点几次（点不动就不再点，等 idle 上限停手） */
    private const val REL_TAP_MAX = 2

    /** PC 版 `MAZE_DIALOG_CONFIRM`(324)：通用弹窗"确定/关闭"白名单（按顺序找，命中即点；认不出不猜） */
    private val DIALOG_CONFIRM = listOf(
        "OneButtonWhite", "OneButtonBlue", "TwoButtonRight",
        "ThreeButtonRight", "TwoButtonCenter", "ThreeButtonCenter",
    )

    /** ★允许走[宽名单兜底][tapDialogUnknown]的**通用弹窗壳**（2026-10-17 收窄）：
     *  `DialogCommonBig/Middle/FullSize` = 获得道具/宝箱开封/提示这类**没有自己 handler** 的壳。
     *  ⚠**不含 `DialogCommonPartyEdit`**（编队页）：它是"我们自己 handler 驱动的页"，有自己完整流程；
     *  实测在这页上做宽名单兜底，会把 `UIButton_Unit / common_btn_info / MenuButtonPosition /
     *  labyrinth_btn_menu…`（12 颗页内+HUD 按钮）当弹窗按钮 ✗ ⇒ 白烧一次 `findTextIn`（实测 1.3s）
     *  并在日志刷"有 12 颗按钮、都不在白名单"的噪音。 */
    private val DLG_GENERIC_SHELLS = listOf("DialogCommonBig", "DialogCommonMiddle", "DialogCommonFullSize")

    private fun hasXY(b: JSONObject): Boolean =
        b.opt("x") != null && b.opt("x") !== JSONObject.NULL &&
            b.opt("y") != null && b.opt("y") !== JSONObject.NULL

    /**
     * 每轮之间的间隔。★2026-10-16 改：原来 1s，理由是"labStatus 含全堆扫描（贵）"——但那份堆扫描是
     * **兜底路径**：agent 的 `labLtdFast()` 先走**零扫描**（`ViewManager.Instance → currentView+0xF8`），
     * 取不到才回退堆扫描；PC 用的是**同一份 agent**（JS_BUILD 一致）。
     * ⇒ 对齐 PC `MAZE_LOOP_INTERVAL=0.1` 的口径，取 **100ms**（与 PC 完全一致）。
     * ★本机是**模拟器**（跑在 PC 上）：没有手机那套"发热/系统占用/耗电"顾虑，不必为此保守；
     *   而且我们省掉了 PC 那次"frida 信息跨 Android→PC"的网络往返，理论上只会比 PC 更快。
     *   唯一剩下的权衡是模拟器内的 CPU 争用（游戏 + agent + 本 app 抢 CPU，可能拖慢游戏帧率），
     *   表现是"模拟器窗口里游戏变卡"，而不是发热 —— 真出现再往上调即可。
     */
    private const val POLL_MS = 100L

    /** PC `DLG_OWN_PAGE_EVERY`(340)："我们自己 handler 驱动的页"每隔这么多轮才查一次通用弹窗 */
    private const val DLG_OWN_PAGE_EVERY = 5

    /** PC `DLG_FORCE_AFTER_IDLE`(336)：连续这么多轮无动作就**强制**查一次弹窗（兜底） */
    private const val DLG_FORCE_AFTER_IDLE = 3

    /** 同一现场的"还在等"心跳间隔（PC：30s 一档） */
    private const val HEARTBEAT_MS = 30_000L

    /** PC `SHOP_DRAG_MAX`(4839)：商店列表被裁时最多上滑几次 */
    private const val SHOP_DRAG_MAX = 3
    /** PC `SHOP_DRAG_MS`(4844)：拖动时长（ms），越长惯性越小 */
    private const val SHOP_DRAG_MS = 700L
    /** PC `SHOP_AUTO`(4821)：true = 按计划真买/真刷新；false = 只读不点（安全排查用） */
    private const val SHOP_AUTO = true

    /**
     * ★商店金币读取重试（2026-10-18 用户："不会是进了商店、没读取到金币数，误以为没有操作直接退出吧"）。
     * coin 读到 null/0 时**先别决策**：按 0 走会"一件都买不起、也刷不起" ⇒ 直接点「关闭」退出，
     * 正好就是"5 层攒了 2w 金币、什么都没买就退出"。窗口内每 [SHOP_COIN_GAP_MS] 重读（读到 >0 立刻继续），
     * 超时仍为 0/空才按 0 继续（真一分钱没有时不会卡死）。
     */
    private const val SHOP_COIN_WAIT_MS = 10_000L

    /** 金币重读间隔（配合 [SHOP_COIN_WAIT_MS]） */
    private const val SHOP_COIN_GAP_MS = 800L

    /**
     * ★一局的**外层壳**（2026-10-17 用户："把完整跑完的日志保存下来，以后就知道完整跑完日志在哪里，
     * 中途退出日志在哪里"）：只负责"本局日志文件"的开与收，实际状态机在 [runBody]。
     *
     * 收尾判据 = [runFinished]（主循环见到结算页、且"本局结束"那条 return 走到了）。
     * 文件名后缀即结论：`__DONE`（完整跑完）/ `__ABORT_STOP`（handler 判定异常收工）/
     * `__ABORT_EXIT`（取消、掉线、异常退出）/ 无后缀（**进程被杀**，没来得及收尾）。
     *
     * @return true = 这一局**完整跑完**（连跑迷宫据此决定要不要开下一局）
     */
    suspend fun run(input: InputBackend, ctx: Context): Boolean {
        appCtx = ctx.applicationContext          // ★给 [LabBtnStore]（按钮路径跨局回喂）用
        // ★「结算后点中心」是**本局**语义：每局开始先关（2026-10-22 用户：第二局开局选角色被误点）
        labResultSeen = false
        CenterTapGate.reset("新的一局开始")
        LogFile.init(ctx.applicationContext)
        LogFile.beginRun("maze")
        runFinished = false
        lastScore = emptyMap()
        lastRewards = emptyList()
        failStrategy = RerollSettings.loadFailStrategy(ctx.applicationContext)
        retreatRequested = false
        manualTeamNodes.clear()
        manualSaid.clear()
        // ★战斗设定只读自检（2026-10-22）：每局开头打一行，验证 SaveDataManager 三项能否读到
        //   （连结爆发立即发动 / 发动时重置 / 自动战斗速度）。**只读、不写、失败不影响流程**；
        //   超时压到 1.5s（读不到就本局不打，别为了诊断拖慢开局）。验证够了可以删掉这段。
        runCatching {
            val bs = AgentApi.battleSettings(timeoutMs = 1_500L)
            AppState.addLog("战斗设定（只读）=" + (bs?.toString() ?: "agent 没回（超时/未注入）"))
        }
        try {
            runBody(input, ctx)
        } finally {
            // ★本局结束（含取消/异常）一律关掉，绝不带到下一局/刷初始
            CenterTapGate.reset("本局结束")
            LogFile.endRun(
                done = runFinished,
                tag = when {
                    runFinished -> "DONE"
                    retreatRequested -> "ABORT_RETREAT"
                    stopRequested -> "ABORT_STOP"
                    else -> "ABORT_EXIT"
                },
                reason = when {
                    runFinished -> "已过结算页、本局完整跑完"
                    retreatRequested -> "按策略「撤退重跑」：本局作废，继续下一局"
                    stopRequested -> "handler 判定异常/卡死 → 收工"
                    else -> "未到结算页就退出（取消/掉线/异常）"
                },
            )
        }
        // ★一局收尾：把悬浮窗的"层数/状态"清掉（"第几局"由连跑循环管，别在这儿动）
        OverlayStatus.setState(null)
        OverlayStatus.setFloor(null)
        return runFinished
    }

    private suspend fun runBody(input: InputBackend, ctx: Context) {
        AppState.addLog("── 迷宫内状态机启动 ──")
        // ★本局状态清零（2026-10-19 修）：这两个是单例字段，跨局残留 ⇒ "撤退重跑"开的新局
        //   在同一失败页从 4/3 起步、一失败就再触发撤退（实测 19:51/19:52 连续 4/3、5/3 死循环）
        nodeFailed.clear()
        manualTeamNodes.clear()
        if (!input.isAvailable()) {
            AppState.addLog("停手：点击后端不可用")
            return
        }
        // 选人要用的名册（花名册）+ 用户自选名单（PC: _pick_tables() / _guild_picks() 的替代）
        val charaPick = CharaPick(
            GameData.tables(ctx),
            GameData.guildPool(ctx),
            { labId -> RerollSettings.loadGuildPicks(ctx)[labId] ?: emptyList() },
            Rules(GameData.tables(ctx)),
            GameData.relicTables(ctx),
        )
        charaPickRef = charaPick            // ★悬浮窗"编队：五个名字"要查名册（见 handlePartyEdit）
        // ★结算奖励的名字表（中文）：道具 / EX装备 id → 中文名
        run {
            val rn = GameData.rewardNames(ctx)
            rewardItemNames = rn.items
            rewardExNames = rn.exEquip
        }
        // 商店决策层（PC shop_plan / refreshDecision）；5 层刷新/3★ 决策接 MDP 最优停止算法
        val shopPlan = ShopPlan(
            GameData.relicTables(ctx),
            RerollSettings.loadShopBuy2Star(ctx),
            useMdp = RerollSettings.loadShopUseMdp(ctx),
        )
        // 遗物三选一决策层（PC RelicPick）
        relicPick = RelicPick(GameData.relicTables(ctx))
        // 编队决策层要用：关卡判定表（boss/ex）+ 加速遗物技能值口径（PC: party_ctx_cached / _ex_resolve）
        PartyPlan.loadTables(ctx)
        // 本局遗物决策用的"已遇 Boss / 已点次数"状态
        bossByArea.clear()
        shopRelicCtx = null; shopRelicCtxKey = null   // ★商店遗物序的上下文（按店缓存，跨局必须清）
        relicChoiceTries.clear()
        partyScroll.clear()                     // 编队页"滚动找缺员"的每页状态（本局重来）
        partyTeams.clear()                      // 多分队：本进程已确认过的各队
        partyFillSaid.clear()                   // 多分队：编队策略日志去重
        partyCleaned.clear()                    // Boss 页"腾空继承队"标记
        // ★★特别装备的两个"跨局残留"缓存（2026-10-22 用户："选完人之后有时候不选 ex 装备"）：
        //   ① `exeqSessions` 按节点缓存，`exDone=true` 会让**下一局同一节点静默跳过装备**（连日志都没有）；
        //   ② `exequipLastTeam1`（同队 ⇒ 认为装备没被动过）只在 `GameProcess.kill()` 时清过，
        //      局内刷开局/不杀进程连跑时，上一局的会留到本局 ⇒ 首战同队就直接跳过。
        //   新一局的装备是重置的，这两条假设只在本局内成立 ⇒ 每局开头必须清。
        exeqSessions.clear()
        exequipLastTeam1 = null
        selNoCardHeals.clear()                  // 选人页"读不到卡片"的自愈计数
        relicBadHeals.clear()                   // 遗物候选"读数全是垃圾"的自愈计数
        battleDumps = 0                         // 战斗结算页对象字段抓取（每场一次、每局上限）
        labResultDumps = 0                      // 迷宫结算页对象字段抓取（单独预算，每局 ≤2 次）
        dlgLearnSaid.clear()                    // 通用弹窗"学到的路径"命中/失效日志去重
        dlgTapSig = null                        // 弹窗点击熔断（本局重新计数）
        dlgTapN = 0
        labResultSig = ""                       // 结算页"落位闸门"（连续两轮同坐标才点）
        seenKills = GameProcess.kills           // 本局开始时的"游戏被杀次数"基线（见主循环 0.35）
        // 刷开局产物（reroll）注入：开局前就已知 3/5 区 Boss 与每区最优路线，
        // 消除"开局读不到 3 区 Boss / 完整地图"的差异（PLAN §0.6 原定不碰，本次已实现）。
        val reroll = RerollState.result
        val rerollRoute = reroll?.routePerArea
        rerollRoutes = rerollRoute                    // ★给移动分支用（见 [handleMove]）
        if (reroll != null) {
            reroll.bossByArea[3]?.let { bossByArea[3] = it }
            reroll.bossByArea[5]?.let { bossByArea[5] = it }
            val b3 = bossByArea[3]
            val b5 = bossByArea[5]
            val routeAreas = rerollRoute?.keys?.sorted()?.joinToString(",") ?: "无"
            AppState.addLog(
                "已接入刷开局产物 ▶ 3区Boss=${b3}${PartyPlan.bossName(b3)?.let { "($it)" } ?: ""} " +
                    "5区Boss=${b5}${PartyPlan.bossName(b5)?.let { "($it)" } ?: ""} " +
                    "预计算路线区=[$routeAreas]",
            )
            AppState.addLog("（局内将沿预计算路线推进，不再现算 DP；路线来源会在每次 [路线] 日志里标注）")
        } else {
            AppState.addLog("未检测到刷开局产物（RerollState 为空）→ 局内按 DP 现算路线走（PC 同款兜底）")
        }
        // 事件选项表（PC load_event_choice 5923）：event_id → 第几项（缺省 1）
        EventPick.load { name ->
            ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        // ★叠加用户在策略页「事件」里改过的选择（相对表内默认值的覆盖）
        EventPick.applyPicks(EventSettings.loadPicks(ctx))
        // 每个新局重置编队缓存（角色池/加速技能值可能不同）
        partyCtxCache = null
        partyPlanSig = null
        partyDoneAt = 0L

        // ① 屏幕尺寸（PC 5903~5914：游戏相机优先，adb wm size 兜底）
        var screenW = 1920
        var screenH = 1080
        AgentApi.screen()?.let { scr ->
            screenW = scr.optInt("w", 1920)
            screenH = scr.optInt("h", 1080)
            scrW = screenW
            scrH = screenH
            AppState.addLog("屏幕 = ${screenW}x${screenH}（游戏相机；点击坐标就是这套坐标系）")
        }

        // ② 读一次地图（PC 5926~5942：读图 → 算最优路线并记下）
        val mp = AgentApi.labMap()
        val panelsArr = mp?.optJSONArray("panels")
        val panels = if (panelsArr == null) emptyList()
        else (0 until panelsArr.length()).mapNotNull { panelsArr.optJSONObject(it) }
        val curArea = (mp?.opt("curArea") as? Number)?.toInt() ?: -1
        val curBlock = (mp?.opt("curBlockId") as? Number)?.toInt() ?: -1
        if (panels.isNotEmpty()) {
            val byArea = sortedMapOf<Int, Int>()
            for (p in panels) {
                val a = (p.opt("area") as? Number)?.toInt() ?: continue
                byArea[a] = (byArea[a] ?: 0) + 1
            }
            AppState.addLog("地图：curArea=$curArea curBlockId=$curBlock 总节点=${panels.size} 各区=$byArea")
            // ★PC 5933~5940 还会接"刷开局"的产物（统一事件价值口径 + 与验过的图对账）；
            //   我们已接：开局注入的 `rerollRoute`（= 刷开局算好的 routePerArea）作为 savedRoute 传下去。
            // ★PC 的判定（价值偏差/Boss 白名单）属于"刷开局"那一步，**局内不做** —— 这里只算路线。
            if (curArea > 0 && curBlock > 0) {
                Route.refreshRoute(
                    curArea,
                    curBlock,
                    force = true,
                    panels = panels,
                    savedRoute = rerollRoute?.get(curArea),
                )
            }
        } else {
            AppState.addLog("未读到地图（labMap 为空）→ 局内回退按类型优先级走（PC 同款兜底）")
        }

        // ③ 主循环
        var lastTag: String? = null
        var tagAt = SystemClock.elapsedRealtime()
        var nullReads = 0
        var hbAt = 0L
        var resultDone = false          // 过了结算页（P6 接上后由结算 handler 置位）
        var stuckRestarts = 0           // ★卡死自愈：本局已重启游戏几次（只计数、不限次）
        val dumped = HashSet<String>()  // 未知状态诊断去重：按 (状态, 节点类型)，**整局不清**（PC 同款）
        val tries = HashMap<String, Int>()      // PC: _TRIES(1124)：同一界面的尝试计数（换页清零）
        val pageRounds = HashMap<String, Int>() // PC: disp_rounds：本页已过的轮数（换页清零，弹窗节流用）
        val waitSaid = HashSet<String>()        // PC: _WAIT_SAID(491)："只等不点"每个状态只说一次
        val stallDiag = HashSet<String>()       // ★"页面停但帧在涨"的取证去重（换页清空；只打日志、不动作）
        var idle = 0                            // PC: idle：连续"无有效动作"的轮数（弹窗兜底闸门用）
        stopRequested = false                   // handler 判定"卡死了"→ 请求收工（字段见类底部）
        lastPickedSig = null
        resetFrameWatch()                       // 帧看门狗：本局从零建立基线

        while (true) {                  // 取消靠 delay() 抛 CancellationException（服务停止时 scope.cancel）
            if (!AgentBridge.connected.value) {
                // ★自动黎明界窗口内（autoRecover）：agent 断开多半就是"游戏崩了 / 被重启"——
                //   交给注入器自愈（它会调起+重注），等回连后接着跑本局，**不当作收工**。
                //   窗口外仍照旧停手（不自动碰游戏）。
                if (!FridaInjector.autoRecover) {
                    AppState.addLog("停手：agent 断开（frida 会话没了）")
                    return
                }
                AppState.addLog("agent 断开（自动黎明界窗口内，多半是游戏崩了/被重启）→ 等自愈拉起并重连…")
                if (!recoverAfterRestart(input)) {
                    AppState.addLog("[✗] agent 断开后没能回到迷宫 → 停手")
                    return
                }
                lastTag = null
                tagAt = SystemClock.elapsedRealtime()
                hbAt = tagAt
                idle = 0
                tries.clear()
                pageRounds.clear()
                resetBattleTimers()
                AppState.addLog("[✓] 已重连并回到迷宫 → 继续跑本局")
                continue
            }
            // ★安全闸门（同 Navigator）：只在游戏前台时跑；读不到就停，不猜
            if (!Foreground.isGameForeground(GameProcess.GAME_PKG)) {
                AppState.addLog("停手：前台已切到 ${Foreground.packageName() ?: "读不到"}（不再碰游戏）")
                return
            }

            // ── ★0.35) 游戏被重启过 ⇒ 清掉"建立在游戏侧状态还在"之上的缓存 ──
            //   2026-10-16 用户："为什么卡死重启之后组队没有选特别装备，是因为本场阵容和上一场一样吗？"
            //   —— **是**：`特别装备` 的跳过判据是"`sig == exequipLastTeam1`（与上一场同一套队）⇒ 装备没被动过"，
            //   而**游戏被重启**时游戏侧是重建的、这条假设不成立 ⇒ 会跳过本该做的特别装备。
            //   收口在 `GameProcess.kill()`（所有重启路径都经它，含注入器自愈）⇒ 这里只比对它的计数。
            if (GameProcess.kills != seenKills) {
                seenKills = GameProcess.kills
                exequipLastTeam1 = null                 // ⇒ 本场强制重设一次特别装备
                exeqSessions.clear()                    // ★同节点不能沿用"已配好装备"（游戏侧重建了，2026-10-22）
                partyCleaned.clear()                    // Boss 页"腾空继承队"标记（游戏侧重置了）
                partyTeams.clear()                      // 多分队：已确认过的各队（重新确认一遍）
                AppState.addLog(
                    "（检测到游戏被重启 → 清掉\"同队跳过装备\"与编队缓存 ⇒ 本场会重新设特别装备）",
                )
            }

            // ── ★0.4) 游戏「卡死」看门狗（PC 口径，用户 2026-10-16 指定）──
            //   判据 = agent `gameTick` 的 **`UnityEngine.Time.frameCount` 是否推进**：
            //     帧在涨 ⇒ 游戏主循环活着，页面再慢也**不是**卡死（别误杀正在等网络/动画的一局）；
            //     连续 [GAME_FRAME_STUCK_MS]=30s 不涨 ⇒ 游戏主循环被冻住 ⇒ 判定卡死 → 自愈重启。
            //   ★为什么不用"现场 tag 不变"：那分不清"页面慢"和"游戏冻住"（实测遗物页静置 363s 也可能只是
            //     我们 handler 没动作，而游戏帧一直在涨）。
            //   ★放在读 labStatus **之前**：游戏冻住时 labStatus 自己也会超时/返回 null，不能等它先返回。
            //   ★只在「自动黎明界」窗口（autoRecover）生效；助手没在跑迷宫时绝不动游戏。
            if (FridaInjector.autoRecover) {
                // ★除了"帧冻住"，**handler 自己也能请求自愈**（2026-10-16：选人页读不到卡片时，
                //   这页多半是半建状态 ⇒ 重启一次通常能被重新建出来；见 handleLabSelection）。
                val why = pollGameFrame(SystemClock.elapsedRealtime()) ?: selfHealReason
                if (why != null) {
                    // ★★让路（2026-10-16 用户："扫描可能会导致卡死吧"）：handler 主动请求的自愈**不受此限**
                    //   （那是"确定性失败"，该重启就得重启）；但**"帧不涨"**若是**我们自己刚做过重活**造成的
                    //   （重扫描 / 取证 dump），杀游戏就是自伤 ✗ —— 历史上 19:58 / 21:22 / 22:24 / 22:33
                    //   那几次"卡死重启"全是这一种 ✗。现在：把看门狗基线往前推，宽限 [HEAVY_GRACE_MS] 再看。
                    val nap = SystemClock.elapsedRealtime()
                    if (selfHealReason == null && nap < heavyUntil) {
                        frameAdvanceAt = nap                        // 重置基线 ⇒ 重新计时
                        if (nap - heavyGraceAt >= 10_000L) {
                            heavyGraceAt = nap
                            AppState.addLog(
                                "… 帧不涨，但我们刚做过重活（${heavyWhat}）→ **不判卡死**，" +
                                    "宽限 ${HEAVY_GRACE_MS / 1000}s 再看（别把自己的慢当成游戏卡 ✗）",
                            )
                        }
                        continue
                    }
                    selfHealReason = null
                    // ★不设次数上限（用户 2026-10-16）：只要还判定为"游戏卡死"就一直自愈重启，
                    //   直到这一局跑完；退出靠 前台切走 / 手动停止 / agent 彻底不回来（见 recoverAfterRestart）。
                    stuckRestarts++
                    AppState.addLog("⚠ $why → 判定游戏卡死，自愈重启游戏（本局第 $stuckRestarts 次）")
                    dumpStuckDiag(null)                 // 先取证（杀掉就取不到了）
                    GameProcess.kill()                  // 注入循环会按 autoRecover 重新调起+重注
                    lastTag = null                      // 回来当"新现场"重新起算
                    tagAt = SystemClock.elapsedRealtime()
                    hbAt = tagAt
                    idle = 0
                    tries.clear()
                    pageRounds.clear()
                    resetBattleTimers()
                    if (!recoverAfterRestart(input)) {
                        AppState.addLog("[✗] 自愈重启后没能回到迷宫 → 停手")
                        return
                    }
                    AppState.addLog("[✓] 自愈重启完成 → 继续跑本局")
                    continue
                }
            }

            val st = AgentApi.labStatus()
            if (st == null) {
                nullReads++
                // ★"读不到"（不在迷宫 / 换区途中 / 游戏重启中）不算"同一现场的延续"——把心跳计时往前推，
                //   免得自愈重启后、页面刚回来就显示"同一现场已 xxxs"这种假象。
                tagAt = SystemClock.elapsedRealtime()
                if (resultDone) {
                    AppState.addLog("[✓] 已过结算页、当前不在迷宫 → 本局结束")
                    runFinished = true                 // ★"完整跑完"（[run] 据此把日志存成 __DONE.log）
                    return
                }
                // 最常见成因：换区途中的一瞬间，游戏自己会切完，等就是了（PC 5993~5998）
                if (nullReads == 1 || nullReads % 10 == 0) {
                    AppState.addLog("[!] labStatus 为空（不在迷宫/换区途中/读取失败），等待…（第 $nullReads 次）")
                }
                delay(POLL_MS)
                continue
            }
            nullReads = 0
            lastStatusAt = SystemClock.elapsedRealtime()   // ★让 pollLoop 知道"主循环刚读过"

            val stt = st.optInt("statusType", -1)
            val top = jStr(st, "pageTop")
            // ★悬浮窗状态（2026-10-17 用户）：每轮先刷"第几层 + 在干什么"，放在这里（tag 之后、
            //   所有 `continue` 之前）⇒ 连迷宫结算页那种提前 continue 的分支也不会停在旧文字上 ✓
            overlayTick(st, stt)
            // tag（PC 6004）：带 view —— statusType 在换页时可能不变，只看状态会漏判"页面已换"
            val tag = "节点${jStr(st, "blockId")} [${jStr(st, "panelTypeName")}] " +
                "状态=${statusName(stt)} view=${jStr(st, "viewClass").ifEmpty { jStr(st, "viewId") }} " +
                "顶框=${jStr(st, "pageTop").ifEmpty { "无" }}"

            val now = SystemClock.elapsedRealtime()
            // ★场景切换避让（2026-10-17）：`viewClass` 一变（进/出战斗、换区）说明**场景正在加载**，
            //   这时做大范围遍历会把游戏主线程拖死（00:42 / 02:01 / 08:58 三次卡死都在切换后 ~2s 内）
            //   ⇒ 记一个"解除时刻"，其间只读状态、探测与弹窗遍历都先停（点按钮不受影响 ✓）。
            val vseen = jStr(st, "viewClass")
            if (vseen != lastViewSeen) {
                lastViewSeen = vseen
                sceneHoldUntil = now + SCENE_HOLD_MS
            }
            if (tag != lastTag) {
                lastTag = tag
                tagAt = now
                hbAt = now
                idle = 0
                dragCount = 0                  // PC 6013：换节点/状态 → 拖动次数清零
                tries.clear()                  // PC 6015：换节点/状态 → 各 handler 的尝试次数清零
                pageRounds.clear()             // 换页 → 本页轮数清零（弹窗节流从头数）
                stallDiag.clear()              // 换页 → "停顿取证"去重也清零
                noPlanTries.clear()            // 换页 → "编组页算不出目标"计数也清零
                dlgOpenMiss = null             // 换页 → dialog 空查缓存清零（新页顶框不同）
                // ★有意偏差 1（见类注释）：没搬的 handler 没有动作，换页打一行"现场"
                AppState.addLog("现场 → $tag")
            } else if (now - hbAt >= HEARTBEAT_MS) {
                hbAt = now
                AppState.addLog(
                    "… 同一现场已 ${(now - tagAt) / 1000}s：$tag" +
                        if (FridaInjector.autoRecover) frameHint() else "",
                )
                // ★"帧在涨、页面却不动" = **不是卡死**，是我们 handler 没动作（PC 也这么分）。
                //   按用户口径这种情况**不重启**，但打一次现场取证，方便事后补 handler
                //   （2026-10-16 那次遗物页停 363s 就是这一类，当时没有取证手段）。
                // ★**战斗视图要排除**（2026-10-16）：一场战斗本来就会静置几分钟，取证是给"非战斗页"用的。
                //   战斗里取证纯属白烧 ~10s（`choices` 堆扫描 + 全按钮遍历），还正好耽误"认战果"那几轮；
                //   战斗真卡死另有 `pollGameFrame()` 兜着（帧计数，**对战斗不设例外**），不会漏。
                if (FridaInjector.autoRecover && !st.optBoolean("inBattle", false) &&
                    now - tagAt >= STALL_DIAG_MS && stallDiag.add(tag)
                ) {
                    AppState.addLog("ⓘ 页面已停 ${(now - tagAt) / 1000}s，但游戏帧还在涨 ⇒ 非卡死（handler 没动作），取证一次：")
                    dumpStuckDiag(st)
                }
            }

            // 1) **战斗中**（ViewBattle）：用内存字段判（比 statusType 可靠 —— 战斗中 statusType 会
            //    先从 QUEST(5) 变成 SUMMON_UNIT(1)，靠状态会误判/误点）。这一层**放在通用弹窗之前**：
            //    战斗中我们只等、只在结算时点「下一步」/「重新挑战」，从不点通用弹窗。
            //    ★战斗视图会**滞后**于实际页面切换（实测战斗后遗物页已出现而 inBattle 仍 true）
            //      ⇒ 战斗之上若已压着"我们认识的选取页/失败页"，就不走进战斗分支（PC 6113）。
            if (st.optBoolean("inBattle", false) && BATTLE_INTERRUPT_PAGES.none { it in top }) {
                if (battleTick(input, st)) {
                    if (retreatRequested) {
                        AppState.addLog("[✗] 按策略「撤退重跑」→ 结束本局（服务层会调撤退 API 并继续下一局）")
                        return
                    }
                    idle = 0
                    delay(POLL_MS)
                    continue
                }
            }
            // 能走到这里 ⇒ 已不在战斗视图（或失败结算已落回）→ 计时/档位复位（PC 6196~6201）
            resetBattleTimers()

            // 1.5) ★特别装备三连页（叠在编队页之上、独立 DialogCommonBig/FullSize）：交给专属 handler，
            //     **绝不走下面的通用弹窗白名单**——白名单命中 ThreeButton*/TwoButton*，会误点"装备确定/确认"
            //     打乱三步顺序（PC 同款规避：exequip 由自己驱动，不进白名单）。只在战斗节点(5/6)出现。
            // ★照 PC `EXEQ_CONTAINERS`(2906)：**只有 pageTop 是这两个容器**时才可能出这三页，否则**别每轮扫**
            //   —— `labExEquipTop` 要遍历 DialogArea 子节点 + 逐个 GetComponentInChildren，很贵；
            //   在预览页/编组页上每轮空扫会把"预览页出现→点挑战"拖到 7~8s（实测 2026-10-16）。
            if ((stt == 5 || stt == 6) && top in EXEQ_CONTAINERS) {
                val et = AgentApi.labExEquipTop()
                // ★必须用 jStr：agent 非特别装备页返回 {top:null}，optString 会得到字符串 "null"（非空！），
                //   误判成"特别装备页在上层"→ 静默 continue → 永不分派到 handleBattleNode（预览页点不了挑战）。
                val exTop = et?.let { jStr(it, "top") } ?: ""
                if (exTop.isNotEmpty()) {
                    handleExequip(input, st, exTop)
                    idle = 0
                    delay(POLL_MS)
                    continue
                }
            }

            // 0) 通用弹窗（PC 6228~6280 的核心）：
            //    · 闸门：pageTop 说有对话框才查（省一次全树遍历）；连续 3 轮无动作兜底强制查一次
            //      （PC DLG_FORCE_AFTER_IDLE=3：实测 1.8s/次的检测，没意义时不查）
            //    · ★白名单（PC MAZE_DIALOG_CONFIRM 324）命中才点 —— 认不出来的弹窗**不猜**，
            //      避免误点"取消/放弃"之类
            //    · 选角色页（statusType=1 且有顶框）是"我们 handler 自己驱动的页"（PC `_own_skip`）：
            //      那个"对话框"就是页面本身，别每轮全树去查弹窗
            //    ★还没搬的（PC 同位置有）：战斗失败页的"两颗按钮"识别（2026-10-12 那套）、
            //      特别装备三连页已搬：在上方 1.5) 处、白名单之前拦截，交给 handleExequip（不进白名单，
            //        否则 ThreeButton*/TwoButton* 会被误点"装备确定/确认"打乱三步顺序）。
            // ★照 PC `_own_page/_own_skip`(6211~6235)：按**页面**认"我们自己 handler 驱动的页"，不再按 statusType。
            //   旧写法 `stt == 1` 两头错：
            //     ① 编组页(5/6 + DialogCommonPartyEdit) 不算 ownPage ⇒ **每轮白烧一次全树遍历(≈900ms)**，
            //        而 PC 是每 5 轮才查一次；
            //     ② 门票结果页(状态=1 但顶框是 DialogCommonBig) 反被当成"自己的页" ⇒ 白名单被跳过 ⇒ 卡死。
            //   改完后 ①节流省掉 4/5 的扫描，②这类"结果/确认页"会正常走白名单点掉。
            val rounds = bump(pageRounds, tag)
            // ★预览页(DialogQuestDetail) / 失败页(DialogQuestFailed) 也是 `handleBattleNode` 自己驱动的页：
            //   不纳入 ownPage 的话，预览页**每轮都白做一次** `dialog()`（全树遍历，注释另处实测 ≈900ms），
            //   而它发生在分派之前 ⇒ "预览页出现 → 点挑战"被硬拖慢近 1s（2026-10-16 用户反馈"点挑战还是有点慢"）。
            //   纳入后走 [DLG_OWN_PAGE_EVERY] 节流；真卡住时仍有 `idle >= DLG_FORCE_AFTER_IDLE` 兜底强制扫描。
            val ownPage = PAGE_LAB_SELECTION in top || PAGE_PARTY_EDIT in top ||
                PAGE_QUEST_DETAIL in top || PAGE_QUEST_FAILED in top ||
                PAGE_LAB_RESULT in top          // ★迷宫结算页也算（2026-10-17：它上面跑一次 dialog() 实测 3.2s ✗）
            // ★own page 上只有"**真的弹窗壳**压上来"才查通用弹窗（2026-10-17 用户："弹窗检查在编队页
            //   1.5~1.9s，这个是必要的还是多余的？"——**答：多余**）：编队/选人/预览/失败页本身就是
            //   一个对话框，`dialog()` 走一遍 DialogArea 子树实测 1.5~1.9s（别处才 500ms），
            //   而这页的弹窗各有自己的 handler（特别装备、结果页）⇒ 平时查它是白烧。
            //   判据：顶框是 `DialogCommon*`（但**不是** `DialogCommonPartyEdit` 这个 own page 自己）
            //   或 `DialogBackKeyCapture`（通用弹窗的外壳）⇒ 才查；否则本轮跳过（仍有 `idle` 兜底）。
            val topIsPopupShell = (top.startsWith("DialogCommon") && PAGE_PARTY_EDIT !in top) ||
                top.startsWith("DialogBackKeyCapture")
            val ownSkip = ownPage && !topIsPopupShell
            // ★★战斗中**整段跳过**通用弹窗（2026-10-17 事故，PC 6413 的规矩"战斗视图在上一步就分流掉了，
            //   这里只处理地图/页面上的弹窗"——我们漏了这条"分流后不落回"）：
            //   `battleTick` 返回 false 时（战斗刚开始、结果未定）原来会**落到这里**，于是每轮都做一次
            //   `dialog()`（DialogArea 子树遍历）。实测 00:42:11 进战斗 → 00:42:13（战斗场景正在加载、
            //   顶框=DialogFlatoutPlayer）那次遍历之后**整个游戏进程僵住**：viewId 连续超时、frida 连
            //   `create_script` 都超时（00:43:12），只能杀游戏重来 ⇒ 这一局白打。
            //   战斗中我们本来也"只等、只在结算时点下一步/重新挑战"，所以跳过它语义不变 ✓
            //   （真卡死另有帧看门狗兜着）。
            val inBattleNow = st.optBoolean("inBattle", false)
            // ★★「过了结算分数页」→ 打开中心加速（2026-10-22 用户方案）。
            //   ⚠必须放在通用弹窗段**之前**：奖励页第一屏（AnimationViewer）如果要先跑一次
            //   `dialog()`（实测 3~7s）才打开，就白白错过第一屏（11:34 那局正是：先烧 3.2s 才开）。
            //   判据："看到**新的非结算页面**"——分数页本身还挂着时不点；过渡期读空也不点。
            if (labResultSeen && !CenterTapGate.isActive() &&
                top.isNotEmpty() && PAGE_LAB_RESULT !in top && !inBattleNow
            ) {
                CenterTapGate.activate("已过结算分数页（$top）")
            }
            // ★同一条避让：场景刚切换时不查通用弹窗（`dialog()` 要走 DialogArea 子树 ✗ 也属重活）。
            if (!inBattleNow && now >= sceneHoldUntil &&
                ((top.isNotEmpty() && !ownSkip) || idle >= DLG_FORCE_AFTER_IDLE)
            ) {
                // ★★结算后加速（2026-10-22 用户改版）：**不挑页面**。只要 [CenterTapGate] 已开
                //   （= 本局过了结算分数页），每轮先点一次屏幕中心；**每 3 轮**才做一次"真检测"
                //   （奖励页的 dialog 检测一次 3~4s，每轮都查反而更慢）。检测轮继续往下走白名单/宽名单。
                //   关闭点见 [CenterTapGate.reset]：刷初始前 / 杀进程 / 本局开始与结束。
                if (CenterTapGate.isActive() && top.isNotEmpty()) {
                    val n2 = tries.getOrPut("$tag|ctap", { 0 })
                    tries["$tag|ctap"] = n2 + 1
                    if (n2 % 3 != 2) {
                        tap(input, scrW / 2, scrH / 2, "结算后加速·点中心（第 ${n2 + 1} 次）")
                        idle = 0
                        delay(900)
                        continue
                    }
                }
                if (top.isNotEmpty() && dlgOpenMiss == top) {
                    // ★同一顶框上一轮已 dialog(top) open=false ⇒ 顶框不变结果不会变，
                    //   不再重复走那 0.5~2.2s 的遍历（2026-10-19 优化，低配机器一局 86 次 slow）
                } else if (tapDialogConfirm(input, top)) {
                    idle = 0
                    delay(900)
                    continue
                }
                // ★白名单**没命中**时的兜底（2026-10-16 用户截图：打完 5 层 Boss 卡在「宝箱开封结果」，
                //   顶部是 `DialogCommonFullSize(Clone)`、那颗「确认」的名字不在白名单里 ⇒ 以前**静默
                //   return false**，日志里只剩"未找到屏内可前往节点"，完全看不出是弹窗没认出来）。
                //   兜底规则见 [tapDialogUnknown]：**只有一颗按钮**才点（并先空点中心跳动画），多颗不猜。
                //   ★★只对**真正的通用弹窗壳**才做（2026-10-17，两轮收窄）：这兜底是"在 DialogArea
                //   子树按**宽名单**找按钮"，而"我们自己 handler 驱动的页"（选人/遗物/编队/预览/失败页）
                //   上，DialogArea 里往往没有真对话框 ⇒ 会把**页内/HUD 的按钮**当成弹窗按钮 ✗
                //   （实机见过 12 颗：`MapButton/UIButton_Unit/common_btn_info/labyrinth_btn_menu…`）
                //   ⇒ 白烧一次遍历、还刷噪音。判据用 [DLG_GENERIC_SHELLS] + `ownPage`（两道）。
                if (!ownPage && DLG_GENERIC_SHELLS.any { it in top } &&
                    // ★"点屏幕中心"已上移到本段开头（[CenterTapGate]，不挑页面）；这里只负责找按钮。
                    tapDialogUnknown(input, tag, tries, top)
                ) {
                    idle = 0
                    delay(900)
                    continue
                }
                // ★★**定向兜底**：迷宫结算「关闭」之后的**奖励页**读不到按钮时，点一次屏幕中央
                //   （2026-10-17 用户："分数页之后的奖励页，如果读不到，可以点一下屏幕中央。
                //    这次专门定向到这一个页面上，不要影响其他地方"）。
                //   为什么只有这页敢点中心：① 它**只可能**是"获得道具/迷宫币"一类的结果展示，
                //     上面没有"取消/放弃"这类会丢东西的决策（点了最坏是多看一屏）；② 实机这页的
                //     `dialog()` 要 3.1~3.7s（UI 树大），读不到时与其每轮白烧遍历，不如点一下推进。
                //   护栏三道（缺一不可）：① **必须已经走过迷宫结算页**（[labResultSeen]）；
                //   ② 顶框必须是 `DialogCommonBig`（奖励弹窗的壳）；③ 同一现场最多 [REWARD_CENTER_MAX] 次。
                if (labResultSeen && top.startsWith("DialogCommonBig")) {
                    // ★结算奖励（奖励页这次一定已填好；结算页没读到就靠这里补）
                    if (tries.getOrPut("$tag|rw", { 0 }) == 0) {
                        tries["$tag|rw"] = 1
                        maybeLogRewards("奖励页")
                        // ★奖励页 = 最后一站：分数仍空就用 LTD 内存补一次（2026-10-23，见 [fillScoreFromMemory]）
                        maybeFillScoreFromLtd("奖励页")
                    }
                    val n = rewardCenterTaps.getOrPut(tag, { 0 })
                    if (n < REWARD_CENTER_MAX) {
                        rewardCenterTaps[tag] = n + 1
                        AppState.addLog(
                            "… 奖励页（$top）读不到按钮 → 点一次屏幕中央" +
                                "（仅「已过结算页」的奖励页允许；第 ${n + 1}/$REWARD_CENTER_MAX 次）",
                        )
                        tap(input, scrW / 2, scrH / 2, "奖励页·中央")
                        idle = 0
                        delay(900)
                        continue
                    }
                }
            }

            // 分派（PC 6372~6478）：
            //   · statusType==0 → **移动**：移动确认框已在上面第 0 步处理掉，这里点目标节点前进
            //   · 其它 → PC 是"**先按页面（pageTop）分派，再按状态**"（6460~6475）：
            //       ★同一个 statusType 可能对应完全不同的页（SUMMON_UNIT 既是"战斗中"又是"选取奖励角色"页，
            //         靠 pageTop 唯一区分）；★statusType==2（门票）有专属覆盖，**必须先于页面分派**（实测被抢过）。
            //     页面 handler（选人/结算）还没搬 ⇒ 现在只有状态分派，顺序注释留在这里免得将来写反。
            // ★页面级 handler（**先于状态分派**，PC 6460~6475）：结算页的 statusType 是 NONE(0)，
            //   只能靠 pageTop 认 —— 不在这里拦下来，就会落进下面的"移动"分支干等
            //   （2026-10-16 实测：卡在 DialogLabyrinthResult 186s，日志只有"未找到屏内可前往节点"）。
            if (PAGE_LAB_RESULT in jStr(st, "pageTop")) {
                resultDone = true                  // 见过结算页 ⇒ 本局结束（labStatus 空掉即收工）
                labResultSeen = true               // ★其后弹出的奖励页才允许"点屏幕中央"兜底（见 0) 段）
                handleLabResult(input, tag, tries)
                idle = 0
                delay(POLL_MS)
                continue
            }
            // ★「过了结算分数页 → 开中心加速」已上移到通用弹窗段之前（那里能抢在 dialog() 之前生效）
            var acted = true
            if (stt != 0) {
                when (stt) {
                    1 -> {
                        // ★SUMMON_UNIT 两种脸：①初始编成（DialogArea/无 DialogLabyrinthSelection）
                        //   ②战后"选取奖励角色"页（pageTop=DialogLabyrinthSelection，3 张卡选 1）
                        val top = jStr(st, "pageTop")
                        if (top.contains("DialogLabyrinthSelection")) handleLabSelection(input, st, tag, charaPick, tries, "战后选人")
                        else handleSummonUnit(input, st, tag, charaPick, tries, waitSaid)
                    }   // PC 2120 / 战后选人
                    2 -> handleLabSelection(input, st, tag, charaPick, tries, "门票")   // PC 门票页（同 DialogLabyrinthSelection）
                    3 -> { /* USE_RELIC 演出，仅等待、不做决策（PC MAZE_WAIT_ONLY={3}） */ }
                    4 -> handleEventChoice(input, st, tag, tries)        // PC EVENT_CHOICE(1088)
                    7 -> handleShop(input, st, tag, tries, screenW, screenH, shopPlan)   // PC SHOP(4728)
                    8 -> handleEventEffect(input, st, tag, tries, screenW, screenH)  // PC EVENT_EFFECT(1190)
                    // 遗物三选一：读候选卡 rewardId+按钮坐标，按 RelicPick.pickCard 选一张（PC RELIC_CHOICE 6297）
                    9 -> acted = handleRelicChoice(input, st, tag, tries)
                    // PC 3837/3838：5=QUEST、6=BOSS 都是 handle_battle_node（再按 pageTop 分派）
                    5, 6 -> handleBattleNode(input, st, tag, tries, dumped)
                    else -> {
                        unknownState(st, dumped)   // 还没搬的 → 现场诊断
                        acted = false
                    }
                }
            } else {
                acted = handleMove(input, st, screenW, screenH)   // PC 6373~6458
            }
            if (acted) idle = 0 else idle++
            if (stopRequested) {
                AppState.addLog("[✗] 状态机判定无法推进 → 收工")
                return
            }
            if (retreatRequested) {
                AppState.addLog("[✗] 按策略「撤退重跑」→ 结束本局（服务层会调撤退 API 并继续下一局）")
                return
            }

            delay(POLL_MS)
        }
    }

    // ────────────────────────── handler：SUMMON_UNIT(1) ──────────────────────────

    /** PC 版 `MAZE_BATTLE_WAIT`(373)：战斗/演出状态的等待轮数上限（≈几~十分钟，防真卡死） */
    private const val MAZE_BATTLE_WAIT = 1200

    /** ★卡死判据（PC 口径）：游戏帧计数（`UnityEngine.Time.frameCount`）连续这么久**不推进** ⇒ 判定卡死。
     *  用户 2026-10-16 指定：跑迷宫期间游戏"暂停"30s 即可认为卡死。 */
    private const val GAME_FRAME_STUCK_MS = 30_000L

    /** ★帧计数**读不到**（`gameTick` 超时 / `Time` 类取不到）连续这么久 ⇒ 也判卡死（游戏冻死时 RPC 会超时）。
     *  阈值放长些：避开"刚注入、il2cpp 还没热起来"的启动窗口。 */
    private const val GAME_FRAME_UNREADABLE_MS = 60_000L

    /** 帧计数轮询间隔（`gameTick` 是 T_FAST 导出，1s 一次足够；别每 100ms 去问） */
    private const val GAME_FRAME_POLL_MS = 1_000L

    /** 角色一览一次滚**几行**（用户 2026-10-16 实测：2 行会把某些角色卡**滚出可见区**、反而要多滚回来）。
     *  ★**1.7 → 1.5**（同日二次实测）：按 1.7 求距离时实际会**滑过头**（惯性多走一点），
     *   收敛到 1.5 行后落点更接近"正好 1.7 行"。与 PC `PARTY_SCROLL_ROWS` 同一个数（两侧同步改）。 */
    private const val SCROLL_ROWS = 1.5

    /** 页面停了这么久、但游戏帧还在涨 ⇒ 打一次现场取证（**不重启**：只说明 handler 没动作） */
    private const val STALL_DIAG_MS = 60_000L

    /** ★卡死自愈：杀掉后等"游戏回到前台 + agent 回连"的上限 */
    private const val STUCK_BACK_MS = 90_000L

    /** PC `MAZE_NODE_TAP_GAP`(5418) / `MAZE_NODE_TAP_BACKOFF`(5419)：同一地图节点的点击间隔（1s 起、逐步退避到 3s） */
    private const val NODE_TAP_GAP_MS = 1_000L
    private const val NODE_TAP_BACKOFF_MS = 3_000L

    /** PC `MAZE_DRAG_LIMIT`(339) / `MAZE_DRAG_MS_MIN,MAX`(340) / `MAZE_DRAG_SETTLE`(341)：屏外节点 → 横向拖动地图 */
    private const val DRAG_LIMIT = 6
    private const val DRAG_MS_MIN = 700
    private const val DRAG_MS_MAX = 1600
    private const val DRAG_SETTLE_MS = 450L

    // ── 战斗（QUEST/BOSS）：页面判据与按钮名单，全部照抄 PC ──────────────────────

    /** PC `BATTLE_START_BTN`(480)：战斗格子预览页底部右侧的「挑战」（另一侧是查看地图，别点错） */
    private const val BATTLE_START_BTN = "UIButton_Unit"

    /** PC `PAGE_QUEST_DETAIL`(488) / `PAGE_PARTY_EDIT`(489) / `PAGE_SMALL`(492)：靠 pageTop 分派页面 */
    private const val PAGE_QUEST_DETAIL = "DialogQuestDetail"
    private const val PAGE_PARTY_EDIT = "DialogCommonPartyEdit"

    /**
     * ★悬浮窗用的"这一轮在干什么"（2026-10-17 用户：`第x/x局` / `第几层` / 当前状态，一行）。
     *
     * 只做**默认描述**（按 statusType + pageTop 猜个大概），细的那半句由各 handler 自己覆盖：
     * 编队页写五个成员名、选人页写选中的角色、商店写"购买 2★ xxx"…。
     * 判据全部来自**已经在手上**的 `st`（不额外 RPC），所以每轮调它零成本。
     */
    /** 悬浮窗第三格「不显示」的页面自动映射态：命中即跳过 setState，保留上一句（不更新=沿用） */
    private val HIDDEN_OVERLAY_STATES = setOf(
        "选择角色", "编队", "战斗准备", "召唤角色", "门票选人",
        "使用遗物（演出）", "事件选择", "商店", "事件演出", "遗物三选一", "地图",
    )

    /**
     * 节点类型(panelTypeName) → 悬浮窗中文短名，与 frida agent `BLOCK_TYPE` 一一对应
     * （agent_base.js：`{1:"NONE",2:"NORMAL",3:"HARD",4:"TICKET",5:"EVENT",6:"RELIC",7:"SHOP",8:"BOSS",[-1]:"INVALID"}`）。
     * agent 返回的未知类型（`?数字`）原样显示，并打一次日志方便补全。
     */
    private val PANEL_CN = mapOf(
        "NONE" to "起始", "NORMAL" to "普通战", "HARD" to "精英战",
        "TICKET" to "门票", "EVENT" to "事件", "RELIC" to "遗物",
        "SHOP" to "商店", "BOSS" to "首领", "INVALID" to "异常",
    )
    private val unknownPanels = mutableSetOf<String>()

    private fun overlayTick(st: JSONObject, stt: Int) {
        // ★第二项：当前节点编号 + 类型（中文短名；替换原「第 x 层」）
        val area = (st.opt("area") as? Number)?.toInt()
        if (area != null && area > 0) {
            val bid = jStr(st, "blockId")
            val raw = jStr(st, "panelTypeName")
            val cn = PANEL_CN[raw] ?: raw
            if (raw.isNotEmpty() && raw !in PANEL_CN && unknownPanels.add(raw)) {
                AppState.addLog("〔未知节点类型〕$raw（悬浮窗原样显示，可补全 PANEL_CN）")
            }
            OverlayStatus.setFloor("节点$bid $cn")
        }
        val top = jStr(st, "pageTop")
        // ★第三格：先算出自动映射态，命中「不显示」清单则跳过（保留上一句），否则推送
        val candidate = when {
            PAGE_LAB_RESULT in top -> "迷宫结算"
            PAGE_LAB_SELECTION in top -> "选择角色"
            PAGE_PARTY_EDIT in top -> "编队"
            PAGE_QUEST_DETAIL in top -> "战斗准备"
            PAGE_QUEST_FAILED in top -> "战斗失败"
            stt == 1 -> "召唤角色"
            stt == 2 -> "门票选人"
            stt == 3 -> "使用遗物（演出）"
            stt == 4 -> "事件选择"
            stt == 7 -> "商店"
            stt == 8 -> "事件演出"
            stt == 9 -> "遗物三选一"
            st.optBoolean("inBattle", false) -> "战斗中"
            // ★战斗节点(5/6)上压着 EX 装备三连页时，top 是 EXEQ_CONTAINERS，
            //   不能误显示成"前往节点"（实际在选特别装备）。与主循环 466 行判据一致。
            (stt == 5 || stt == 6) && top in EXEQ_CONTAINERS -> "特别装备"
            stt == 5 || stt == 6 -> "前往节点${jStr(st, "blockId")}"
            else -> "地图"
        }
        if (candidate !in HIDDEN_OVERLAY_STATES) OverlayStatus.setState(candidate)
    }

    /** PC `PAGE_LAB_SELECTION`：战后选人 / 门票选人的三选一页（与 PAGE_PARTY_EDIT 同属"我们自己驱动的页"） */
    private const val PAGE_LAB_SELECTION = "DialogLabyrinthSelection"

    /** PC `EXEQ_CONTAINERS`(2906)：**只有**这两个容器页才可能是"特别装备三连页"→ 才去探测（省掉每轮空扫） */
    private val EXEQ_CONTAINERS = listOf("DialogCommonBig(Clone)", "DialogCommonFullSize(Clone)")

    /** PC `PAGE_SMALL`(492)：游戏自己的小弹窗壳 = "进战斗/领奖"那一瞬的过渡页 → 只等不点 */
    private const val PAGE_SMALL = "DialogCommonSmall"

    /** PC `PAGE_QUEST_FAILED`(465)：战斗失败页（底部 左「结束」右「重新挑战」） */
    private const val PAGE_QUEST_FAILED = "DialogQuestFailed"

    /** PC `BATTLE_INTERRUPT_PAGES`(468)：战斗视图**滞后**时压在上面的页面 —— 命中就不走进战斗分支 */
    private val BATTLE_INTERRUPT_PAGES = listOf(
        "DialogLabyrinthSelection", PAGE_PARTY_EDIT, "DialogLabyrinthResult", PAGE_QUEST_FAILED,
    )

    /** PC `BATTLE_TAP_MAX`(477)：挑战**只点一次**（点完没进展 ⇒ 已被上层对话框盖住，同一坐标那层是「战斗开始」） */
    private const val BATTLE_TAP_MAX = 1

    /** ★「挑战」**带证据重试**的上限与冷却（2026-10-16 加）：那一下可能是空点（坐标略偏 / 按钮未就绪），
     *  而 `BATTLE_TAP_MAX=1` 是"永不重试" ⇒ 会永远停在预览页刷心跳（实测卡了 2 分钟、第 450 轮）。
     *  重试前提（见 [handleQuestDetail]）：最上层仍是预览页 + **编组页零件没在屏**（说明上一次确实没打开
     *  编组页）+ 距上次点击够久 + 总次数未超上限。 */
    private const val BATTLE_TAP_MAX_TOTAL = 3
    private const val BATTLE_RETAP_GAP_MS = 6_000L

    /** PC `BATTLE_IDLE_WARN`(478)：点过挑战后停在本页这么多轮才提示"可能被盖住" */
    private const val BATTLE_IDLE_WARN = 20

    /** ★预览页上"一直拿不到「挑战」可点坐标"的轮数上限（按钮始终没落位 / 页面结构变了 ⇒ 停手） */
    private const val QD_NOT_FOUND_MAX = 30

    /** PC `PAGE_LAB_RESULT`(416)：迷宫结算页（打完最终 Boss 的 RESULT ①②屏）——靠 pageTop 认，
     *  它的 `statusType` 是 **NONE(0)**，不认它就会落进"移动"分支一直干等（2026-10-16 实测卡 186s） */
    private const val PAGE_LAB_RESULT = "DialogLabyrinthResult"

    /** PC `LAB_RESULT_TAP_MAX`(417)：结算页最多点几次（①②两屏各一次即可；点不动就不再砸）。
     *  ⚠数的是**真点下去的次数**（2026-10-16 修：原来数的是轮数，读不到按钮时 6 轮就把额度耗光）。 */
    private const val LAB_RESULT_TAP_MAX = 6

    /** PC `LAB_RESULT_NEXT_RES`(5359) / `LAB_RESULT_CLOSE_RES`(5360)：结算页按钮的**节点名**候选
     *  —— `cands`（对象字段）拿不到时，按名字在结算子树里找（PC 的第 2 档兜底）。
     *  ⚠**不含**「撤退（无报酬）」「返回（有报酬）」那类名字 ⇒ 依然点不到。 */
    private const val LAB_RESULT_NEXT_RE = "NextButton|ButtonNext|ResultNext"
    private const val LAB_RESULT_CLOSE_RE = "CloseButton|ButtonClose|ResultClose"

    /** ★结算页"停手后又等多久"就收工（秒）：避免点不动之后**永久空转**（2026-10-16 实测空了 3 分多钟）。 */
    private const val LAB_RESULT_GIVEUP_S = 90

    /** PC `_RESULT_BTN_RE`(5034) / `_RESULT_BTN_RE_WIDE`(5035)：结算页/失败页按钮的窄、宽名单 */
    private const val RESULT_BTN_RE = "Next|Retry|End|Challenge|Resume|Decide"
    private const val RESULT_BTN_RE_WIDE = "Button|Btn"

    /** PC `_WIN_LABELS`(5036) / `_FAIL_LABELS`(5037)：胜/负**同一处看**，靠标签区分 */
    private val WIN_LABELS = listOf("下一步", "次へ", "Next")
    private val FAIL_LABELS = listOf("挑战", "Retry")

    /** PC `MAZE_BATTLE_RESULT_GRACE`(5183)：结算就绪但没读到按钮 → 先等这么久再提醒 */
    private const val BATTLE_RESULT_GRACE_MS = 8_000L

    /** ★结算按钮"近失"取证的间隔与次数上限（2026-10-16，用户反馈"检测到比出现在屏幕上还晚"）：
     *  结算就绪却两样都没读到时，每隔这么久把"下半屏所有按钮/标签"打一行，并报**本次探测耗时**——
     *  用来分清 ① 不在 y≥85% 带内 ② 标签读不到/不是「下一步/挑战」 ③ 名字不在名单里
     *  ④ 树里压根没这节点（游戏还没建好 ⇒ 慢的是游戏侧） ⑤ 探测本身慢（每秒只能采一次样）。 */
    // ★降频（2026-10-16 哨兵实锤）：这条取证自己会走**全树**（实测 `[slow] findTextIn 3309ms`），
    //   一局最多 5 次太贵了 ⇒ 改成每 15s 一次、最多 2 次（探测本身已经修好，"近失"只在真异常时才需要）。
    private const val RESULT_DIAG_GAP_MS = 15_000L
    private const val RESULT_DIAG_MAX = 2

    /** ★探测**占空比**闸门（2026-10-16 二次改，事故复盘）：单次探测 ≥ 这个耗时(ms) 时，下一次探测推迟到
     *  "探测耗时之后"再开始（保证 扫描时间 ≤ 空闲时间，最坏 ≈50% 占空比）。
     *  ⚠为什么必须加：全树宽模式实测**一次 6s**（19:46 日志 `本次探测 6087ms`）。若在战斗中不受限地
     *  每秒扫一遍 ⇒ 把游戏进程 CPU 榨干 ⇒ RPC 连续超时 ⇒ agent 被误判"掉线" ⇒ 自愈把游戏杀了
     *  （19:58 那场战斗中掉线就是这么来的）。 */
    private const val PROBE_DUTY_MS = 1_500L

    /**
     * ★★2026-10-23 **两层化**（替代原来的 `BATTLE_IDLE_MS = 10s` 兜底扫描）。
     *
     * 旧设计：战斗中每 10s 做一次窄扫，用来在廉价信号失效时"兜底认出结算页"。
     * 问题有两头：
     *   · 结算页出现后**最多要等 10s 才发现**（这正是"检测比出现在屏幕上还晚"的根源）；
     *   · 那 10s 一次的扫在战斗中仍是白烧 —— 按钮此刻**绝不可能**存在。
     *
     * 新设计（用户 2026-10-23："win/lose 是可以认出来的，认出来之前战斗中可以什么都不做"）：
     *   ① 战斗中每 [BATTLE_PAGE_POLL_MS] 只调一次 agent 的 `resPageKind`（几毫秒，**不做任何扫描**）；
     *   ② 认出来（win/fail/ex）后先静置 [RESULT_SETTLE_MS]，再按 [BATTLE_READY_GAP_MS] 找按钮。
     */
    /** ★"只认页"的探测间隔：一次只要几毫秒 ⇒ 可以勤问；结算页出现后 ≤ 这个间隔就能发现 */
    private const val BATTLE_PAGE_POLL_MS = 300L

    /**
     * ★认出 win/lose（或拿到兜底信号）后**静置**这么久才开始找按钮（2026-10-23）。
     *
     * 结算页 prefab 刚 Instantiate 的那几秒里，按钮还在播动画、`active` 还没置真 ⇒ 此刻去找必然
     * 全失败；更糟的是它正好撞上"战斗 view 销毁"的窗口 —— 10-22 22:58 那次卡死就是在这个时刻
     * 连续重扫，把游戏主线程压到 0 帧的。
     *
     * ★实测（2026-10-23 六场）：`_resPageKind` 在**对话框实例刚 Instantiate** 时就能认出
     *   （`GetComponentInChildren(includeInactive=true)`），而按钮真正 active 还要 **4~4.5s**。
     *   静置 1s 时那次探测**全落空**（每次白烧 ~775ms）⇒ 先提到 3s（命中要 2 次轻量轮询），
     *   再按用户口径提到 **3.5s**（让第一次轻量轮询就尽量落在按钮 active 之后）。
     *   动画时长是 prefab 自带的、与设备性能无关，所以这个值是稳定的（用户："1s 确实不会出现"）。
     */
    private const val RESULT_SETTLE_MS = 3_500L

    /**
     * ★静置之后"**轻量轮询**"的次数上限（2026-10-23 用户："3s 等待 + 6 次，间隔 400ms"）。
     *
     * 每次只调一次 `battleResultBtn`（缓存校验 / 学习，实测 ~100ms）—— **不重试、不读字段、不扫树**。
     * `(400 + 100)ms × 6 ≈ 3s` ⇒ 3s 静置 + 这段正好覆盖到 ~6s（按钮实测在"认出"后
     * 3.5~4.5s 才 active）。
     * **每次命中就清零**（胜利结算有两页，第二页的「下一步」同样享受轻量轮询）；
     * 用完仍未命中 ⇒ 升级到**全套**探测（[BATTLE_READY_GAP_MS] 1.2s 一次）继续找，绝不停。
     */
    private const val RESULT_PROBE_FAST_MAX = 6

    /** ★轻量轮询的间隔（用户 2026-10-23 指定 400ms；配合"单次 ~100ms"正好 500ms 一轮） */
    private const val RESULT_LIGHT_GAP_MS = 400L

    /**
     * ★结算页的**顶框类名**（2026-10-23）：`resPageKind` 认不出来时（EX / 结构变化）的兜底信号。
     *
     * ⚠**绝不能**再用"顶框非空"当判据 —— 战斗中的 `DialogFlatoutPlayer`（自动战斗提示）、
     * `DialogBackKeyCapture`（返回键外壳）都会被误当成结算页（10-22 22:58 那场就是从这儿开始的：
     * 开打 3 秒就被判"结果已定"，探测节奏立刻从 10s 提到 1.2s，一路烧到结算页）。
     */
    private val RESULT_TOP_RE = Regex("DialogQuestResultWin|DialogQuestFailed|DialogLabyrinthExResult")

    /**
     * ★久战兜底（2026-10-23）：战斗打了这么久还"没认出"结算页 ⇒ 退而允许用"顶框非空"当信号。
     *
     * 防的是"某个结算页 `resPageKind` 认不出、顶框类名也对不上"（EX / 新结构 / prefab 改名）时，
     * 一路干等到 [MAZE_BATTLE_WAIT]（≈120s）超时停手。久战里冒出来的对话框基本就是结算页；
     * 即便不是，找不到按钮也只是继续等（[BATTLE_RESULT_GRACE_MS] 会报警），不会误点。
     */
    private const val RESULT_TOP_FALLBACK_MS = 90_000L

    /** ★结果页出现后的探测节奏（2026-10-16）：窄档即可命中（实测 172ms），所以可以勤一点；
     *  ⚠它**必须**尊重 `battleProbeAt`（胜利页点完会把它 +2.5s，用来防同一页连点），
     *  否则会像 20:22 那样 2 秒内点 4 下。 */
    private const val BATTLE_READY_GAP_MS = 1_200L

    /** ★窄档连续这么多次都没找到，才允许扫一次**全树**（全树一次 6.6s ⇒ 只能是罕见兜底） */
    private const val BATTLE_WIDE_AFTER_MISS = 3

    /** ★**每场战斗的全树扫描配额**（2026-10-16 用户："扫描可能会导致卡死吧"）：
     *  场次级别的**总量上限** —— 单次门槛（[BATTLE_WIDE_AFTER_MISS]）管"多频繁"，这条管"总共几次"×
     *  "每次多贵"。用超了就**彻底不再扫**，只保留字段读 + 窄档 + 停手/自愈 ⇒ 最坏情况有上界 ✓。 */
    private const val BATTLE_WIDE_MAX = 3

    /** ★重活的"让路时长"：探到一次 ≥ 这么贵的操作 ⇒ 帧看门狗额外宽限这么久（见 0.4 段） */
    private const val HEAVY_GRACE_MS = 30_000L

    /** 游戏相机尺寸（PC `MAZE_SCREEN_W/H` 338）：进迷宫时读一次；战斗结算按钮的"底部带"（y≥85%）要用 */
    @Volatile
    private var scrW = 1920

    @Volatile
    private var scrH = 1080

    // 战斗中状态（PC maze_loop 5995~6001 的那几个 locals）
    @Volatile
    private var inBattlePrev = false

    @Volatile
    private var waitBattle = 0

    /** 上次"找按钮"探测的时刻（贵动作，[BATTLE_READY_GAP_MS] 节流） */
    private var battleProbeAt = 0L

    /** ★上次"只认页"探测的时刻（便宜动作，[BATTLE_PAGE_POLL_MS] 节流；2026-10-23 两层化） */
    private var pageProbeAt = 0L

    /** ★静置之后已连续"快速探测"几次（未命中才累加；命中清零，见 [RESULT_PROBE_FAST_MAX]） */
    private var resultProbeN = 0

    /** 占空比闸门：这个时刻之前**不探测**（上一次探测太贵时把它往后推，见 [PROBE_DUTY_MS]） */
    private var battleHoldUntil = 0L

    // ── ★场景切换避让（2026-10-17 三次卡死复盘）────────────────────────────────
    /** 上次看到的 `viewClass`（"ViewLabyrinthMap"/"ViewBattle"/? ），一变 ⇒ 场景在加载 */
    private var lastViewSeen = ""

    /** 场景刚切换时，到这个时刻之前**不做重活**（探测 / 弹窗遍历） */
    private var sceneHoldUntil = 0L

    /** 场景切换后的避让时长。★三次卡死（00:42 / 02:01 / 08:58）都发生在 `view` 切换后 ~2s 内，
     *  元凶都是"大范围遍历（全树 / 战斗画布 / DialogArea）+ 游戏正在加载场景"撞在一起 ⇒ 进程冻住。
     *  切换后等这一会儿再干活，把"我们自己把游戏搞死"这条路掐掉 ✓（点按钮不在此限 ✓）。 */
    private const val SCENE_HOLD_MS = 2_000L

    /** 结果已定后"窄档连续未命中"的次数（到 [BATTLE_WIDE_AFTER_MISS] 才给一次全树） */
    private var battleWideMiss = 0

    /** ★本场战斗已用过几次**全树扫描**（配额，见 [BATTLE_WIDE_MAX]） */
    private var battleWideUsed = 0

    /** ★"我们刚做过重活"的截止时刻（帧看门狗据此**让路**，见 0.4 那段注释） */
    private var heavyUntil = 0L

    /** 上面那次重活是什么（日志用） */
    private var heavyWhat = ""

    /** 让路提示上次打印时刻（每 10s 最多一条） */
    private var heavyGraceAt = 0L
    private var battleRes0 = 0L
    private var battleT0 = 0L
    private var battleSaid = 0
    private var battleWinSaid = false
    private var battleFailSaid = false

    /** ★本**局**已抓过几次战斗结算页的对象字段（每场一次、每局最多 [BATTLE_DUMP_MAX] 次，见 [dumpBattleResult]） */
    private var battleDumps = 0

    /** 每局最多抓几次结算页（普通战 1 次 + Boss 战 1 次即可；留一点余量给"失败页"） */
    private const val BATTLE_DUMP_MAX = 3

    /** ★★**自动抓取 dump 总开关 = 关**（2026-10-17 用户："结算页没问题的话，就把 dump 关掉吧"）。
     *  为什么关：偏移/指针两条路都已验证可用（战斗结算「字段读@DialogLabyrinthExResult+0x108」、
     *  迷宫结算「DialogArea 优先 3ms」）⇒ 自动 dump 只是往日志里灌 7~8 千字符（1.7s/次），
     *  对决策没有任何帮助 ✗。代码与预算计数都留着，改这一个常量就能再打开（排新偏移时用）。 */
    private const val AUTO_RESULT_DUMP = false

    /** ★**迷宫结算页**（`DialogLabyrinthResult`）单独一份抓取预算（2026-10-16 用户："你抓到迷宫结算的字段了？"）。
     *  ⚠为什么必须**单独**：原来它和战斗结算共用 [battleDumps]/[BATTLE_DUMP_MAX]（每局 3 次），而一局里
     *  普通战 + Boss 战 + 失败页就能把 3 次用完 ⇒ **走到最后的迷宫结算页时额度已空、一次都抓不到** ✗。
     *  这页每局最多出现 2 屏（①「加入的角色」②「CLEAR+总分」），各抓一次足够。 */
    private var labResultDumps = 0

    /** 迷宫结算页每局最多抓 2 次（两屏各一次） */
    private const val LAB_RESULT_DUMP_MAX = 2

    /** ★结算页"连续几轮定位不到按钮"才允许走树兜底（2026-10-17）：入场动画期间前几轮空是正常的，
     *  立刻遍历既慢又是卡死风险的来源。 */
    private const val LAB_RESULT_TREE_AFTER_N = 4

    /** ★同上前提下才**补抓**对象字段（原来在函数开头抓 ⇒ 每局白花 2~7s，还把第一次点击拖后 ✗） */
    private const val LAB_RESULT_DUMP_AFTER_N = 6

    /** ★结算页"落位闸门"用：上一次看到的 `tag|label@x,y`（连续两次一致才点） */
    private var labResultSig = ""

    /** ★本局是否**完整跑完**（见到结算页并走到"本局结束"那条 return）—— 决定日志存成 `__DONE` 还是 `__ABORT_*` */
    private var runFinished = false

    /** ★悬浮窗"编队：五个名字"用：本局的名册（[runBody] 建好就放这里，供 [handlePartyEdit] 查名字） */
    private var charaPickRef: CharaPick? = null

    /** ★编队页"还没建好"只报一次（正常过渡，别每轮刷） */
    private var partyNotReadySaid = false

    /** ★是否**已经走过迷宫结算页**（`DialogLabyrinthResult`）—— 只有它之后的 `DialogCommonBig`
     *  才允许"读不到按钮就点屏幕中央"这个定向兜底（2026-10-17 用户要求，只此一页） */
    private var labResultSeen = false

    /** ★本局是否已经打过"结算奖励"日志（只打一次；结算页/奖励页谁先读到算谁） */
    private var rewardsLogged = false

    /** 结算奖励 id → 中文名（runBody 里从 [GameData.rewardNames] 填） */
    private var rewardItemNames: Map<String, String> = emptyMap()
    private var rewardExNames: Map<String, String> = emptyMap()

    /** ★本局结算成绩（供整轮汇总；每局开头清空） */
    var lastScore: Map<String, String> = emptyMap()
        private set

    /** ★本局结算奖励（中文名+数量，已按 id 汇总；供整轮汇总） */
    var lastRewards: List<RewardEntry> = emptyList()
        private set

    /** ★奖励页"点屏幕中央"的次数（按现场 tag 计，最多 [REWARD_CENTER_MAX] 次） */
    private val rewardCenterTaps = HashMap<String, Int>()

    /** ★奖励页定向兜底：同一现场最多点几次屏幕中央（一般 1~2 屏奖励，够了） */
    private const val REWARD_CENTER_MAX = 2

    /** ★刷开局算好的**每区最优路线**（`RerollState` 的 `routePerArea`）：移动分支要用它，
     *  不能再只在开局那一次传（见 [handleMove] 里的说明）。 */
    private var rerollRoutes: Map<Int, List<Int>>? = null

    /** ★最多等几轮让按钮落位（超过 ⇒ 不再等，直接点；防"坐标每轮抖 1px"死等） */
    private const val LAB_RESULT_SETTLE_N = 8

    /** ★通用弹窗"学到的路径"日志去重（2026-10-17）：`"<顶框>|hit"` / `"<顶框>|miss"` 各报一次/局 */
    private val dlgLearnSaid = HashSet<String>()

    /** ★弹窗点击熔断（2026-10-17）：同一颗按钮最多连点 [DLG_TAP_MAX] 次（防"幽灵弹窗"无限连点） */
    private const val DLG_TAP_MAX = 3
    private var dlgTapSig: String? = null
    private var dlgTapN = 0

    /** 上次见到的"游戏被杀次数"（[GameProcess.kills]）；一变 ⇒ 说明游戏被重建过，清编队/装备缓存 */
    private var seenKills = 0

    /** 上次已经取证过的顶框名（同一页面不重复 dump —— 见 [dumpBattleResult]） */
    private var lastDumpTop: String? = null

    /** PC `_NODE_FAILED`(3787) / `_FAIL_TAP`：本局"这个节点失败过"（≤3 次重挑战，之后停手） */
    private val nodeFailed = HashMap<String, Int>()

    /** ★战斗失败策略（0=中断运行 1=撤退重跑 2=等待接手），每局开头从 RerollSettings 读 */
    private var failStrategy = 0

    /** ★策略=撤退重跑：本局请求撤退（服务层看到后调 retire 并继续下一局） */

    /** ★编组页"算不出目标"的轮数（按节点）：连续 5 轮按失败策略处理，防无限死等（2026-10-19） */
    private val noPlanTries = HashMap<String, Int>()

    /** ★同一顶框 `dialog(topCls)` 已查过一次 open=false 的缓存：顶框不变就不再重复查
     *  （低配机器上回退遍历 DialogArea 要 0.5~2.2s/次，2026-10-19 用户日志：一局 86 次 slow） */
    private var dlgOpenMiss: String? = null
    @Volatile
    var retreatRequested = false

    /** ★策略=等待接手：这些节点关闭自动组队/自动开战，等用户自己操作 */
    private val manualTeamNodes = HashSet<String>()

    /** 手动节点提示只打一次 */
    private val manualSaid = HashSet<String>()

    /** PC `_LAST_FIGHT_TEAM`(6123) 的简化：我们**点过「挑战」**的节点（用来区分"这局不是脚本开的"） */
    private val weStartedFight = HashSet<String>()

    /**
     * 编组页「滚动角色一览找缺员」的每页状态（对应 PC `_PARTY_OP[tag]` 的 scroll/last_vis/scroll_still
     * 与 `_PARTY_NOSCROLL`）。★按页 tag 记 —— 换页/换队自然从头数。
     */
    private class PartyScroll {
        var count = 0                                  // 本页已滚几次（只用于日志；**不设上限**）
        var lastVis: List<Int>? = null                 // 上次滚完"屏上图标集合"（用来判滚没滚动）
        var still = 0                                  // 连续几次滚完屏上没变化
        val noScroll = HashSet<Int>()                  // 滚多少次都翻不出来的缺员（不再白滚）
    }

    private val partyScroll = HashMap<String, PartyScroll>()

    /** PC `_PARTY_TEAMS`(2509)：本节点"**本进程已确认过的各队**"（(area/blockId) → 各队 unitId）——
     *  既用于"这一队还要不要编"的判据（`already`），也是 `partyDic` 读不到时的缩池兜底。 */
    private val partyTeams = HashMap<String, MutableList<List<Int>>>()

    /** PC `_PARTY_FILL_SAID`(2517)：编队策略那行按"节点 + 编不编"只打一次（一节点最多两行） */
    private val partyFillSaid = HashSet<String>()

    /** ★Boss 页"腾空继承来的 2/3 队"是否已做过（(area/blockId) → 已做，每节点一次） */
    private val partyCleaned = HashSet<String>()

    /** ★编组页"首次看到它的时刻"用的键与时间（页面静置闸门，见 handlePartyEdit 顶部） */
    private var partyPageKey: String? = null
    private var partyPageAt = 0L

    /** 上次点「挑战」的时刻（[BATTLE_RETAP_GAP_MS] 冷却用） */
    private var qdTapAt = 0L

    // ── 特别装备（EX 装备）三连页：决策-free 纯操作（PC `_exeq_step` + `handle_exequip`）──
    //   ① 特别装备设定   PartsDialogPartyExtraEquip（ThreeButton Left/Center/Right = 取消/自动装备/装备确定）
    //   ② 自动特别装备设定 PartsDialogRecommendSettingExtraEquip（labels 4 行 + checkBtn + TwoButton）
    //   ③ 优先属性值     PartsDialogExtraEquipSort（toggles + TwoButton）
    //   只读由 agent 的 labExEquip / labExEquipTop 提供（字段与 PC 探针一致）。
    private const val LAB_EXEQUIP_AUTO = true        // 是否自动走这套流程（PC LAB_EXEQUIP_AUTO）
    private const val LAB_EXEQUIP_SKIP_SAME = true   // 与上场同一套队才跳过（PC LAB_EXEQUIP_SKIP_SAME）
    private const val EXEQ_SETTLE_MIN_MS = 250L      // 点完至少等这么久（PC EXEQ_SETTLE_MIN=0.25s）
    private const val EXEQ_POLL_MS = 150L            // 本轮内连推多步的间隔（PC EXEQ_POLL=0.15s）
    private const val EXEQ_FAST_BUDGET_MS = 3000L    // 本轮内最多花多少秒推进（PC EXEQ_FAST_BUDGET=3s）
    private const val EXEQ_WAIT_MAX_MS = 3000L       // 等页面变样的上限（PC EXEQ_WAIT_MAX=3s）
    private const val EXEQ_MAX_STEPS = 8             // 一次流程最多几步（PC EXEQ_MAX_STEPS=8）
    private const val EXEQ_OPEN_WAIT_MS = 30000L     // 就绪却读不到「特别装备」按钮的等待上限（PC EXEQ_OPEN_WAIT=30s）

    /** 单个节点（按 nodeKey）的特别装备流程会话（PC `_EXEQ_SESS` 按 (节点,状态,视图) 记） */
    private data class ExeqSess(
        var n: Int = 0,
        var opened: Boolean = false,
        var openedAt: Long = 0L,
        var auto: Boolean = false,
        var exDone: Boolean = false,
        var exShare: Boolean = true,
        var team1Sig: List<Int>? = null,
        var pendSig: String? = null,
        var pendAt: Long = 0L,
    )
    private val exeqSessions = HashMap<String, ExeqSess>()
    /** 最近配好的队伍1 签名（sorted unitId，只记一套）—— 用于 SKIP_SAME（PC `_EXEQUIP_LAST`，Boss 只看队伍1） */
    private var exequipLastTeam1: List<Int>? = null

    private fun exeqSess(key: String): ExeqSess = exeqSessions.getOrPut(key) { ExeqSess() }

    private fun _exeqBtn(buttons: org.json.JSONArray?, name: String): JSONObject? {
        if (buttons == null) return null
        for (i in 0 until buttons.length()) {
            val b = buttons.optJSONObject(i) ?: continue
            if (b.optString("name") == name) return b
        }
        return null
    }

    /** 这一刻"这一页长什么样"的签名 —— 点完要等它变了才允许下一步（状态变化驱动，PC `_exeq_sig`） */
    private fun _exeqSig(top: String, p: JSONObject): String {
        val arr = { k: String -> p.optJSONArray(k)?.toString() ?: "" }
        return when (top) {
            "party" -> "party|${p.opt("change")}|${arr("unitIds")}"
            "setting" -> "setting|${arr("labels")}|${p.opt("checked")}|${arr("terms")}"
            "sort" -> "sort|${p.opt("cur")}|${arr("toggles")}"
            else -> top
        }
    }

    // 编队页缓存（PC `party_ctx_cached`：本局持有角色池 + 加速遗物技能值，进迷宫读一次）
    @Volatile
    private var partyCtxCache: PartyPlan.PartyCtx? = null

    /** 编队方案是否已就绪（集合一致）→ 点过「战斗开始」后免得再算 */
    private var partyDoneAt = 0L

    /** 编队方案"换目标/换就绪"时才打一次日志（PC 同款：避免每轮刷屏） */
    private var partyPlanSig: String? = null

    /** 战后选人页：已点过的那一页（按卡片集合签名去重，避免重复点） */
    private var lastSelSig: String? = null

    /** ★同一组卡已点几次 / 上次点的时刻（PC `_LAB_SEL_TAPS` + `LAB_SELECT_TAP_MAX` 同款）：
     *  点一下没反应时允许再点一次；超过上限就停手（怕把游戏点卡）。 */
    private var lastSelTaps = 0
    private var lastSelTapAt = 0L

    /** 结算"近失"取证：上次打印时刻 / 本次战斗已打印次数（进战斗时复位，见 [battleTick]） */
    private var resultDiagAt = 0L
    private var resultDiagN = 0

    /** 上一颗结算按钮是**从哪找到的**（root/模式/标签），由 [battleResultBtns] 写、日志用 */
    private var lastBtnSrc: String? = null

    /** 本场是否已"找到并点过"结算按钮（找到后就不再打"近失"取证，免得页面翻走后误报） */
    private var battleBtnSaid = false

    /** 战后选人页：本页算好的决策上下文（own 数据），按卡片签名缓存，避免每轮重打慢 RPC */
    private var lastCtxSig: String? = null
    private var lastCtx: CharaPick.LabSelCtx? = null

    /** ★app 上下文（[LabBtnStore] 按钮路径落盘/回喂用；[run] 开头赋值） */
    private var appCtx: android.content.Context? = null

    // ── 跨轮的"点击节流"记录（PC `_NODE_TAP_AT/_NODE_TAP_N/_NODE_SAID_AT`，按节点记、**不清**）──
    private val nodeTapAt = HashMap<Int, Long>()
    private val nodeTapN = HashMap<Int, Int>()
    private val nodeSaidAt = HashMap<Int, Long>()

    /** PC `drag_count`(5966)：本节点已拖动次数（**换页清零**，见 maze_loop 6013） */
    @Volatile
    private var dragCount = 0

    @Volatile
    private var stopRequested = false           // handler 判定"卡死了"→ 请求收工

    /** ★handler 请求"自愈重启"的原因（主循环会在帧看门狗那一步一起处理）；null = 无请求 */
    @Volatile
    private var selfHealReason: String? = null

    /** ★选人页"读不到卡片"已请求过几次自愈重启（按页面 tag）。**跨重启保留**（主循环重启时会
     *  `tries.clear()`，用它计数才不会无限重启），每局开始清空。 */
    private val selNoCardHeals = HashMap<String, Int>()

    /** 选人页读不到卡片：等这么久（秒）还读不到 ⇒ 请求自愈重启一次 */
    private const val SEL_NOCARD_HEAL_S = 40L

    /** 同上面那件事最多重启几次（用完 ⇒ 停手留人工） */
    private const val SEL_NOCARD_HEAL_MAX = 2

    /** ★遗物候选"读数全是垃圾"已请求过几次自愈重启（按页面 tag）。**跨重启保留**、每局清空。
     *  2026-10-17 用户："我想全自动，所以……有激进的方法，如果兜底扫描扫出来是垃圾数据，那就直接重启" */
    private val relicBadHeals = HashMap<String, Int>()

    /** 遗物候选读数垃圾：最多重启几次（用完 ⇒ 停手留人工） */
    private const val RELIC_BAD_HEAL_MAX = 2

    /** ★事件页"没配置选项"时：不点、等人工；等这么久仍在这页 ⇒ 停手留人工（2026-10-16） */
    private const val EVENT_NOPICK_GIVEUP_S = 90L

    /** ★未知状态多久不出现在"只观察、不扫树"阶段内 ⇒ 才打完整现场（2026-10-17 用户：
     *  "未知状态的时候不要立刻扫描，晚一点再看一遍状态"）。2s 足够跨过换页/加载的瞬间。 */
    private const val UNKNOWN_DIAG_AFTER_MS = 2_000L

    @Volatile
    private var lastPickedSig: List<Int>? = null   // PC: _CS_PICKED_SIG：选角色页"已选"变化检测

    /**
     * 最近一次**成功**读到 `labStatus` 的时刻（elapsedRealtime）。
     * 用途：`FridaInjector.pollLoop` 也在读同一份 `labStatus`（给仪表盘显示"最上层对话框"），
     * 两条并发循环重复读很浪费（PC 只有一个循环没有这事）。它看到这里"刚读过"就跳过自己那次；
     * 主循环一停时间戳自然过期，仪表盘读取自动恢复 ⇒ **不用 try/finally 维护开关**。
     */
    @Volatile
    private var lastStatusAt = 0L

    /** 主循环是否在活跃读状态（给 [com.lmj.labyrinthauto.frida.FridaInjector] 判"要不要跳过自己那次"） */
    fun statusFresh(): Boolean = SystemClock.elapsedRealtime() - lastStatusAt < STATUS_FRESH_MS

    private const val STATUS_FRESH_MS = 2_000L

    // ── 游戏帧计数看门狗状态（[pollGameFrame]；每次 run 开始时 [resetFrameWatch]）─────────────
    /** 上次轮询 `gameTick` 的时刻 */
    private var framePollAt = 0L
    /** 上次"帧计数发生变化"的时刻（0 = 还没建立基线） */
    private var frameAdvanceAt = 0L
    /** 上次看到的 frame 值（-1 = 还没读到） */
    private var frameLast = -1L
    /** "帧不涨"的提示是否已打过（避免刷屏；帧恢复后重置） */
    private var frameSaidStale = false

    /** PC 版 `_bump`(1124)：记一次"同一界面"的尝试，返回累计次数 */
    private fun bump(tries: MutableMap<String, Int>, tag: String): Int {
        val n = (tries[tag] ?: 0) + 1
        tries[tag] = n
        return n
    }

    /**
     * ★同一轮内**多读几次**再决定放弃（2026-10-17 用户："门票/遗物首读空同样多读几次，不要立刻放弃。
     * 切换状态可能有个过程"）。
     *
     * 为什么：页面刚建立的那一瞬，结构化列表常常读到 0 张（实测门票 4 次、遗物 3 行），下一轮就好。
     * 以前只能"本轮不点、下一轮再看" ⇒ 告警刷屏，还会被 [selNoDecide] 当成"读不到卡片"计入自愈升级 ✗。
     * 现在同一轮里隔 [gapMs] 重读 [times] 次，任一次满足 [ok] 就返回；都不满足才返回 null（调用方照旧处理）。
     */
    private suspend fun <T : Any> readRetry(
        times: Int = 3,
        gapMs: Long = 250L,
        ok: (T) -> Boolean = { true },
        fn: suspend () -> T?,
    ): T? {
        repeat(times) { i ->
            val r = fn()
            if (r != null && ok(r)) return r
            if (i < times - 1) delay(gapMs)
        }
        return null
    }

    /**
     * PC 版 `handle_summon_unit`(1819)：SUMMON_UNIT 有**两副面孔**（同一 statusType，靠 pageTop 分）：
     *
     *   ① `pageTop` 是对话框 → **选角色页**（初始编成）：精准读取 → 识别公会 → 自动点选三人 → 「去邀请」
     *   ② 没对话框 / 在战斗 → 战斗中/演出：**只等不点**（判据用内存 inBattle/viewClass，比 pageTop 稳：
     *      战斗中也可能压着对话框；"没有对话框"也可能是别的演出 —— PC 注释 1825）
     *
     * ★2026-10-10 PC 实机"在 10101 起点干等 1200 轮"就是因为它被当成 ② 了 —— 判据顺序不能反。
     */
    private suspend fun handleSummonUnit(
        input: InputBackend,
        st: JSONObject,
        tag: String,
        pick: CharaPick,
        tries: MutableMap<String, Int>,
        waitSaid: MutableSet<String>,
    ) {
        val top = jStr(st, "pageTop")
        val vb = jStr(st, "viewClass") + "|" + jStr(st, "battleView")
        val inBattle = st.optBoolean("inBattle", false) || vb.contains("ViewBattle")
        if (inBattle || top.isEmpty()) {                   // ② 战斗中 / 演出（含"召唤出场"动画）
            val nm = jStr(st, "statusTypeName")
            val n = bump(tries, "WAIT|$tag")
            if (nm !in waitSaid) {
                waitSaid.add(nm)
                AppState.addLog("（$nm：战斗中/演出 → 只等不点）")
            }
            if (n >= MAZE_BATTLE_WAIT) {
                AppState.addLog("[✗] $nm 等待 $MAZE_BATTLE_WAIT 轮仍未推进，判定异常")
                stopRequested = true
            }
            return
        }
        // ① 选角色页：**精准读取**（不再整树遍历/不 hook/不差分 —— PC 1847 定案）
        val n = bump(tries, tag)
        val q = AgentApi.labCharaPageQuick()
        if (q == null) {
            if (n == 1) AppState.addLog("[!] 选角色页读不到（没连上/超时）（只报一次，不做任何扫描）")
            return
        }
        // PC 1852：`if q.get("err")` —— ★读**成功**时 err 就是 JSON null，不算错
        //   （我第一版在这里吃了 org.json 的亏：optString 把 JSON null 变成字符串 "null"，
        //     结果把成功读当成"读不到"→ 永远不动手。判据一律以 ok 为准 + err 只认非空真字符串。）
        val err = q.optString("err")
        val failed = !q.optBoolean("ok", false) || (err.isNotEmpty() && err != "null")
        if (failed) {
            if (n == 1) {
                AppState.addLog("[!] 选角色页读不到：${err.take(120).ifEmpty { "（没说原因）" }}（只报一次，不做任何扫描）")
                // ★诊断：这一页其实**不是**选角色页（比如门票给完角色的"结果页"），打出按钮清单好补 handler
                dumpDialogButtons("顶框=$top 状态=SUMMON_UNIT")
            }
            // ★降级：读不到又一直停在这 → 退化为通用弹窗白名单点一下确认，避免永久卡死
            //（ownPage 让主循环的白名单在这被跳过，所以这里必须自己兜；按钮是白名单里的"确定/下一步"类）
            // 阈值取 10 轮（≈15s）：先给页面切换留足时间（换页瞬间读失败是正常的），确认真卡住才降级
            if (n >= 10) {
                if (tapDialogConfirm(input, top)) {
                    AppState.addLog("选角色页读不到且已停 $n 轮 → 退化为通用弹窗确认（顶框=$top）")
                    delay(900)
                    return
                }
                if (n == 10) {
                    AppState.addLog("[!] 读不到选角色页、弹窗白名单也没命中 → 这页需要单独补 handler（顶框=$top）")
                }
            }
            return
        }
        val pk = intListOf(q, "picked")
        val changed = n == 1 || pk != lastPickedSig
        if (changed) {
            lastPickedSig = pk
            AppState.addLog(
                "⏸ 选角色页：已选 ${pk.size}/3：" +
                    (if (pk.isEmpty()) "（空）" else pk.joinToString("、") { "$it ${pick.uname(it)}" }),
            )
        }
        val rule = pick.ruleLine(q, n == 1)
        if (CharaPick.AUTO && rule != null) {
            pick.autoPick(input, q, rule.second)           // ★自动点选三人 / 点「去邀请」
        }
    }

    /** PC `EVENT_SKIP_RE`(462)：事件演出页的「跳过」按钮名（找不到才退点屏幕中心） */
    private const val EVENT_SKIP_RE = "skip|Skip|SKIP|スキップ|早送|跳过"

    /**
     * PC `EVENT_BTN_RE`(321)：事件选项卡上每张卡的「选择」按钮（按 x 升序 = choice_id_1/2/3）。
     * ★PC 是**从 UI 树**按这个节点名找的（`find_list`），不是类读取。
     *   我们原来用类读取 `labEvent()`，实测会返回空卡 → 事件页卡死（2026-10-16）。
     *   名字可能带 "(Clone)" 后缀 → 只锚开头、不锚结尾。
     */
    private const val EVENT_CHOICE_BTN_RE = "^labyrinth_btn_main_square"

    /**
     * 事件选择页（EVENT_CHOICE=4）：按 eventId 选第 N 张选项卡（PC `handle_event_choice` 1088）。
     * 读取走 agent 的 `labEvent`（返回每张卡的按钮屏幕坐标）；选项序号由 P4 `EventPick.pick` 给出（默认第 1 项）。
     * ★卡片按屏幕 x 左→右排序（与 PC `find_list` 按 x 排序后取第 N 张一致）。
     */
    private suspend fun handleEventChoice(
        input: InputBackend,
        st: JSONObject,
        tag: String,
        tries: MutableMap<String, Int>,
    ) {
        val n = bump(tries, tag)
        val eid = (st.opt("eventId") as? Number)?.toInt()
        // ★照 PC `handle_event_choice`：选项卡按钮**从 UI 树**取（`^labyrinth_btn_main_square` × 卡片数，
        //   按 x 升序 ↔ choice_id_1/2/3）。原实现用类读取 `labEvent()`，实测返回空卡 → 事件页卡死。
        //   `findList` 只返回**屏内**节点（自带 onscreen 过滤），与 PC/我们的屏外口径一致。
        // ★根顺序（2026-10-17 三次改）：`dialog`（DialogArea 子树）→ `layer`（DialogArea 的父层）。
        //   去掉的两条：① **`view`（当前视图子树）** —— 事件页顶框还是"无"时它 = 整张地图页，
        //      实测一次 2.2s ✗（12:07:30/40/50 三次都是它）；② **全树**（更贵，从不用）。
        //   事件选项卡就在对话框里（或它的父层），这两个根都只要几十 ms ✓
        var arr = AgentApi.findList(EVENT_CHOICE_BTN_RE, "dialog")
        if (arr == null || arr.length() == 0) {
            arr = AgentApi.findList(EVENT_CHOICE_BTN_RE, "layer")     // DialogArea 的父层（同层兄弟）
        }
        val btns = (0 until (arr?.length() ?: 0))
            .mapNotNull { arr?.optJSONObject(it) }
            .filter { (it.opt("x") as? Number) != null && (it.opt("y") as? Number) != null }
            .sortedBy { (it.opt("x") as? Number)?.toInt() ?: 0 }
        if (btns.isEmpty()) {
            if (n <= 2) AppState.addLog("[!] 事件页没有可点选项卡（树里没找到 ${EVENT_CHOICE_BTN_RE}）→ 本轮不点")
            return
        }
        val want = EventPick.pick(eid, btns.size)
        if (want == null) {
            // ★这个事件**没配置选项** ⇒ **不猜**（用户 2026-10-16："事件也有二选一/三选一，都不要随便选"）。
            //   ① 把 event id + 屏上选项卡（序号/名字/坐标）打出来，方便往 `event_choice.json` 补一行；
            //   ② 不点，等人工点一次（页面推进后本函数自然不再被调用）；
            //   ③ 一直停在这页 [EVENT_NOPICK_GIVEUP_S] 秒 ⇒ 停手留人工（不无声空转）。
            if (n == 1) {
                AppState.addLog(
                    "[!] 事件(${eid ?: "?"}) **没配置选项**（event_choice.json 里没有它）→ **不猜、不点**；" +
                        "请人工选一次，或补一行配置。候选=" +
                        btns.mapIndexed { i, b -> "#${i + 1}${b.optString("name")}@(${b.optInt("x")},${b.optInt("y")})" }
                            .joinToString(" "),
                )
            }
            val ks = "$tag|evnp"
            val first = tries.getOrPut(ks, { 0 })
            if (first == 0) {
                tries[ks] = (SystemClock.elapsedRealtime() / 1000L).toInt().coerceAtLeast(1)
            } else if (SystemClock.elapsedRealtime() / 1000L - first >= EVENT_NOPICK_GIVEUP_S) {
                AppState.addLog("[✗] 事件(${eid ?: "?"}) 未配置且已等 ${EVENT_NOPICK_GIVEUP_S}s → 停手（留人工/补配置）")
                stopRequested = true
            }
            return
        }
        if (n == 1) AppState.addLog("事件(${eid ?: "?"})：选第 $want 项（共 ${btns.size} 项）")
        // ★悬浮窗（2026-10-17 用户："事件选择：xxx（选项内容）"）：选项卡坐标走 UI 树（快），
        //   但**选项正文**只有类读取 `labEvent` 才给（`commentLabel`）⇒ 每页第一次调一次（几十 ms，
        //   而且是 DialogArea 优先，不是扫堆）拿到正文；拿不到就退回"第 N 项"。
        if (n == 1) {
            val cm = AgentApi.labEvent()?.optJSONArray("cards")
                ?.optJSONObject((want - 1).coerceAtLeast(0))?.optString("comment").orEmpty()
            OverlayStatus.setState("事件选择：" + cm.ifBlank { "第 $want 项" }.take(28))
        }
        val chosen = btns[(want - 1).coerceIn(0, btns.size - 1)]
        tap(input, chosen.optInt("x"), chosen.optInt("y"), "事件选项卡#$want")
    }

    /**
     * 事件演出页（EVENT_EFFECT=8）：**只点真正的「跳过」按钮**，找不到就不点（PC `handle_event_effect` 1190，
     * 2026-10-17 用户改口径："事件跳过不要点屏幕中心，查一下，没有跳过就不点"）。
     * 演出结束会自动切回地图（statusType→0），不会无限循环；找不到时把候选名打出来（供补名字）。
     */
    private suspend fun handleEventEffect(
        input: InputBackend,
        st: JSONObject,
        tag: String,
        tries: MutableMap<String, Int>,
        screenW: Int,
        screenH: Int,
    ) {
        val n = bump(tries, tag)
        // ★根顺序（2026-10-17 四次改）：`dialog`（DialogArea 子树）→ `layer`（DialogArea 的父层）。
        //   去掉 `view`（2026-10-17 实机 13:28:25：一次 **2156ms** ✗）：演出页的 `view` 根 = 整张地图页，
        //   而「跳过」按钮在对话框里（实测命中 (1752,82)）⇒ 这个根又贵又没必要。
        var skip = AgentApi.findTextList(EVENT_SKIP_RE, max = 40, root = "dialog")
        if (skip == null || skip.length() == 0) skip = AgentApi.findTextList(EVENT_SKIP_RE, max = 40, root = "layer")
        var hit: JSONObject? = null
        if (skip != null) for (i in 0 until skip.length()) {
            val b = skip.optJSONObject(i) ?: continue
            if (hasXY(b)) { hit = b; break }
        }
        if (hit != null) {
            tap(input, hit.optInt("x"), hit.optInt("y"), "事件演出·跳过")
            if (n == 1) AppState.addLog("事件演出：跳过")
        } else {
            // ★没有「跳过」就**不点**（2026-10-17 用户："事件跳过不要点屏幕中心，没有跳过就不点"）：
            //   原来退回点屏幕中心，那既不是跳过、又可能误触；演出会自己结束（statusType→0），等就行 ✓
            if (n == 1) {
                AppState.addLog("[!] 事件演出没找到「跳过」按钮 → **不点**（等演出自己结束）")
            }
        }
    }

    // ── 商店页跨轮状态（模拟 PC 模块级 _shop_drag / _shop_rr）──
    private var shopDragCount = 0
    private var shopDragTarget: String? = null
    private var shopRefreshCount = 0
    private var shopRefreshKey: String? = null

    /** ★「金币读到 0/空」的起始时刻 + 所属门店（换店或读到 >0 就清）；见 [SHOP_COIN_WAIT_MS] */
    private var shopCoinBadKey: String? = null
    private var shopCoinBadAt = 0L

    /** ★本店是否已经买过东西（买过之后金币 0 是正常值，不再当"读失败"重读；换店清零） */
    private var shopBought = false

    /**
     * ★商店"同级遗物序"的上下文缓存（2026-10-22）：敌人情况 + 当前遗物，交给
     * [RelicPick.sortSameStar]。按店缓存（每轮读 `labOwnRelics` 太浪费）；**买走一件后置空** ⇒
     * 下一轮按新的持有状态重算（dot/吸血/守备的动态序会变）。每局开头也要清（同 blockId 会跨局重复）。
     */
    private var shopRelicCtx: RelicPick.Ctx? = null
    private var shopRelicCtxKey: String? = null

    /** PC `_shop_slot_index`(4566)：商品全局序号 gi → 屏上槽位数组下标（滚动后按底部对齐换算） */
    private fun shopSlotIndex(gi: Int, total: Int, n: Int): Int? {
        if (n <= 0) return null
        val vi = if (n >= total) gi else gi - (total - n)
        return if (vi in 0 until n) vi else null
    }

    /**
     * 商店页（SHOP=7）：读全部商品 + 金币 + 刷新费，按 [ShopPlan] 给出购买/刷新计划，再**真正点买/刷新/关闭**
     * （PC `handle_shop`(4728) → `shop_step`(4644) → `shop_try_buy`(4579)）。一轮只动一次：
     * ① 计划里第一个「未买 && 可买(availability==1) && 钱够」→ 可见直接点、被裁先上滑拖列表；
     * ② 没可买的 → 按 `refreshDecision` 点「用NNN更新」；③ 刷不动/不划算 → 点「关闭」退出。
     * [SHOP_AUTO]=false 时只打印计划不点（安全排查）。
     */
    private suspend fun handleShop(
        input: InputBackend,
        st: JSONObject,
        tag: String,
        tries: MutableMap<String, Int>,
        screenW: Int,
        screenH: Int,
        shopPlan: ShopPlan,
    ) {
        val n = bump(tries, tag)
        if (!SHOP_AUTO && n == 1) AppState.addLog("⏸ 商店：只读不点（SHOP_AUTO=false）")
        val sh = AgentApi.labShop()
        if (sh == null) {
            if (n == 1) AppState.addLog("[!] 商店页读不到（没连上/超时）（只报一次）")
            return
        }
        val err = sh.optString("err")
        if (err.isNotEmpty() && err != "null") {
            // ★"还没建好"是**正常过渡**（刚走进商店节点那几百 ms），不是错误：
            //   agent 现在会立刻返回（不再扫堆 8s ⇒ 不再把 RPC 通道占住、不再被误判掉线），
            //   这里只轻声说一次，下一轮就正常了。
            if (n == 1) {
                if (err.contains("还没建好")) AppState.addLog("… 商店页还没建好（${err.take(80)}）→ 下一轮再看")
                else AppState.addLog("[!] 商店页读不到：${err.take(120)}（只报一次）")
            }
            // ★安全阀（2026-10-17）：agent 不再扫堆兜底 ⇒ 万一 DialogArea 里**始终**找不到商店
            //   （名字/结构变了），这里会一直"等下轮"而不推进 ✗。连续 10 轮就喊出来并带上
            //   DialogArea 子节点名（`dlgMiss`），别让它静默卡住整局。
            if (n == 10) {
                AppState.addLog(
                    "[!] 商店页连续 10 轮读不到 → 可能 DialogArea 里没有该对话框；" +
                        "子节点现场：${sh.optString("dlgMiss").take(200)}",
                )
            }
            return
        }
        // ★读取来源/耗时（2026-10-17 用户："商店还是在扫描"）：`src` = cache / dlg / scan。
        //   `scan` = 全堆扫描（实测数秒 ✗）—— 出现就说明"DialogArea 优先"没命中，日志里同时给出
        //   DialogArea 的子节点名 + 有没有目标组件（agent 的 `dlgMiss`），不用再靠猜。
        //   每种来源每页只报一次（正常应只出现 `cache` 或 `dlg` 一行）。
        val src = sh.optString("src").ifEmpty { "?" }
        if (tries.getOrPut("$tag|rsrc_$src", { 0 }) == 0) {
            tries["$tag|rsrc_$src"] = 1
            AppState.addLog(
                "商店读取：来源=$src 耗时=${sh.optLong("ms")}ms" +
                    "（walk=${sh.optLong("msWalk")}ms）" +
                    if (src == "scan") {
                        "（⚠全堆扫描，本不该出现；DialogArea 未命中：${sh.optString("dlgMiss").take(200)}）"
                    } else "",
            )
        }
        val itemsArr = sh.optJSONArray("items")
        val items = if (itemsArr == null) emptyList<JSONObject>()
        else (0 until itemsArr.length()).mapNotNull { itemsArr.optJSONObject(it) }
        if (items.isEmpty()) {
            if (n <= 2) AppState.addLog("[!] 商店没读到商品（items=0${if (err.isNotEmpty()) " err=$err" else ""}）→ 不动作（避免误关商店）")
            return
        }
        val area = (st.opt("area") as? Number)?.toInt()
        // 换店 → 刷新计数清零（**必须放在 shopPlan 之前**：3★兜底要靠 done 判断本店刷新是否已用尽，
        //   否则会拿到上一间店的旧计数，出现"刷新都没动就先买 3★"的浪费）
        val blockId = st.opt("blockId")?.toString() ?: ""
        val rkey = "$blockId|$area"
        if (rkey != shopRefreshKey) {
            shopRefreshCount = 0; shopRefreshKey = rkey; shopBought = false   // 换店 ⇒ 买过标记也清
        }
        // ★金币读不到/读到 0 → **先别决策**（2026-10-18 用户："不会是没读取到金币数，误以为没操作直接退出吧"）：
        //   coin=0 时商品全被判"买不起"、刷新也"刷不起" ⇒ 立刻点「关闭」退出 —— 正好是"5 层 2w 什么都没买"。
        //   进店那几百 ms 常读不到 currentCoin 标签 ⇒ 窗口内每 [SHOP_COIN_GAP_MS] 重读，读到 >0 立刻照常决策。
        //   ★但**本店买过之后 0 是正常值**（刚花光；用户 2026-10-18："一旦购买了商品，这条规则就该失效"）：
        //     此时只有"空（根本没读到）"才继续重读，读到 0 就照常决策（没钱了就刷/关）。
        val coinRead = (sh.opt("coin") as? Number)?.toInt()
        val coinBad = coinRead == null || (coinRead <= 0 && !shopBought)
        if (coinBad) {
            val nowMs = SystemClock.elapsedRealtime()
            if (shopCoinBadKey != rkey) { shopCoinBadKey = rkey; shopCoinBadAt = nowMs }
            val waited = nowMs - shopCoinBadAt
            if (waited < SHOP_COIN_WAIT_MS) {
                if (n <= 2) {
                    AppState.addLog(
                        "[!] 商店金币读到 ${coinRead ?: "空"}（可能还没建好）→ ${SHOP_COIN_WAIT_MS / 1000}s 内每 " +
                            "${SHOP_COIN_GAP_MS}ms 重读，读到就继续（不按 0 决策、不关店）",
                    )
                }
                delay(SHOP_COIN_GAP_MS)
                return
            }
            AppState.addLog(
                "[!] 商店金币 ${SHOP_COIN_WAIT_MS / 1000}s 内始终读到 ${coinRead ?: "空"} → 按 0 继续" +
                    "（不买不刷、将关闭商店；若画面上确有金币，请手动处理）",
            )
        } else {
            shopCoinBadKey = null
            shopCoinBadAt = 0L
        }
        // ★同级遗物序的上下文（2026-10-22）：敌人情况（3 区 Boss 是否毒液）+ 当前遗物 + 待选遗物，
        //   统一交给 [RelicPick.sortSameStar]（三选一那边用同一个函数）。
        if (shopRelicCtxKey != rkey) { shopRelicCtxKey = rkey; shopRelicCtx = null }
        val relicCtx = shopRelicCtx ?: run {
            val own = AgentApi.labOwnRelics()
            // ★读不到持有遗物 ⇒ 传 null（只用静态系列序），别拿"空持有"当真：
            //   那会让 dot 判成"本局还没有 → -2 最优先"，排序会跑偏。下次轮询再试。
            if (own == null) null
            else relicPick.relicCtx(own, area, bossByArea[3]).also { shopRelicCtx = it }
        }
        val (picked, desc) = shopPlan.shopPlan(area, sh, done = shopRefreshCount, relicCtx = relicCtx)
        if (n == 1 || n % 20 == 0) {
            // ★`desc` 与 PC 逐字一致（对拍测试守着），末尾这段"能买几件"是给**人**看的可读口径
            //   （2026-10-17 用户："买完余=-2620 —— 日志修一下"：原来那个数会算上买不起的件）
            AppState.addLog("商店：$desc${shopPlan.buyPlanBrief(picked, (sh.opt("coin") as? Number)?.toInt() ?: 0)}")
        }
        if (!SHOP_AUTO) return
        val coin = (sh.opt("coin") as? Number)?.toInt() ?: 0
        // ① 先买：计划里第一个「未买 && 可买(availability==1) && 钱够」
        val tgt = picked.firstOrNull {
            !Py.truthy(it.opt("isSold")) &&
                (it.opt("availability") as? Number)?.toInt() == 1 &&
                ((it.opt("price") as? Number)?.toInt() ?: 0) <= coin
        }
        if (tgt != null) {
            val gi = shopPlan.shopSlotOf(tgt, items)             // 商品全局序号（静态，不随滚动变）
            val slotsArr = sh.optJSONArray("slots")
            val slots = if (slotsArr == null) emptyList<JSONObject>()
            else (0 until slotsArr.length()).mapNotNull { slotsArr.optJSONObject(it) }
            val vi = shopSlotIndex(gi, items.size, slots.size)   // → 屏上槽位数组下标（滚动后按底部对齐）
            val visOK = vi != null && vi < slots.size && slots[vi].optBoolean("vis", false)
            val tgtTag = "${tgt.opt("rewardId")}@${tgt.opt("price")}"
            if (!visOK) {
                // PC：目标在「可视窗口上方」(gi < 商品数-屏上槽数) 时下拉只会越跑越远 → 停手
                if (vi == null && slots.size < items.size && gi < items.size - slots.size) {
                    if (n <= 2 || n % 20 == 0) AppState.addLog("[!] 商店目标（全局槽#$gi）在可视窗口上方 → 不该下拉，停手（留人工）")
                    return
                }
                if (shopDragTarget != tgtTag) { shopDragCount = 0; shopDragTarget = tgtTag }   // 换目标 → 计数清零
                if (shopDragCount >= SHOP_DRAG_MAX) {
                    if (n <= 2 || n % 20 == 0) AppState.addLog("[!] 商店目标（全局槽#$gi）上滑 $shopDragCount 次后仍不可见 → 停手（留人工）")
                    return
                }
                val x = screenW / 2
                val y1 = screenH * 800 / 1080                     // 固定一条横线拖动（PC 800→300 按比例缩放）
                val y2 = screenH * 300 / 1080
                shopDragCount++
                AppState.addLog("上滑商店列表（第 $shopDragCount 次，目标 $tgtTag）")
                input.swipe(x, y1, x, y2, SHOP_DRAG_MS)
                return
            }
            // 可见 → 直接点（目标可见后计数复位）
            shopDragCount = 0; shopDragTarget = null
            val idx = vi ?: return
            val s = slots[idx]
            // ★悬浮窗（2026-10-17 用户："购买 2星（五角星标志）xxx（遗物名字）"；印记不读出具体名 → "购买随机印记"）
            val buyRid = tgt.opt("rewardId")
            val buyKind = shopPlan.shopItemKind(buyRid).first
            OverlayStatus.setState(
                if (buyKind.endsWith("印记")) "购买随机印记"
                else "购买 " + relicPick.relicLabel(buyRid),
            )
            // ★本店买过 ⇒ 之后"金币=0"是正常值（刚花光），[SHOP_COIN_WAIT_MS] 的重读规则对它失效
            shopBought = true
            shopRelicCtx = null      // ★持有遗物变了 ⇒ 下一轮重算"同级遗物序"（dot/吸血/守备的动态项）
            tap(input, s.optInt("x"), s.optInt("y"), "商店·买#$vi($tgtTag)")
            return
        }
        // ② 没可买的了 → 刷新或关闭（换店归零已提到 shopPlan 之前，这里不再重复）
        // q1（刷出≥1个1★概率）Kotlin 暂无 shop_pool_stats 探针 → 传 null（=不拦，与 PC q1 未知同款）
        val rd = shopPlan.refreshDecision(area, sh, shopRefreshCount, null)
        if (n == 1 || n % 20 == 0) AppState.addLog("商店：${if (rd.refresh) "决定刷新" else "不刷新：${rd.why}"}")
        if (rd.refresh) {
            val rb = sh.optJSONObject("rerollBtn")
            if (rb != null && hasXY(rb)) {
                tap(input, rb.optInt("x"), rb.optInt("y"), "商店·更新")
                shopRefreshCount++
                return
            }
            if (n == 1) AppState.addLog("[!] 想刷新但没读到「更新」按钮坐标 → 不点（留人工）")
            return
        }
        // ③ 刷不动/不划算 → 关闭退出
        val cb = sh.optJSONObject("closeBtn")
        if (cb != null && hasXY(cb)) {
            tap(input, cb.optInt("x"), cb.optInt("y"), "商店·关闭")
            return
        }
        if (n == 1) AppState.addLog("[!] 没可买的、也读不到「关闭」按钮坐标 → 停手（留人工）")
    }

    /**
     * 选人/选取页**做不了正确选择**时的统一处置（用户 2026-10-16：**"盲选太不靠谱，宁可重启游戏"**）。
     *
     * 触发场景（原先是"退化为点第 1 张 / 按坐标赌一把"这类瞎猜）：
     *   · 读不到卡片（agent 返回空，页面多半半建）；· P4 规则判不出该选哪张；
     *   · 选中的那张没有「决定」坐标（**换一张就是换选择**，不能干）；· 卡片读不出发给谁（unitId=0）。
     *
     * 处置：**绝不盲点**（一张都不点）→ 等 [SEL_NOCARD_HEAL_S] 秒 → **请求自愈重启游戏**（走"帧冻住"
     * 同一条路；重开客户端后这页通常能被重建出来）→ 每页最多 [SEL_NOCARD_HEAL_MAX] 次（计数器**跨重启
     * 保留**）→ 用完则 `stopRequested`（停手留人工）。
     *
     * @return 恒 true（调用方直接 `return`，别往下点）
     */
    private suspend fun selNoDecide(
        tag: String,
        kind: String,
        tries: MutableMap<String, Int>,
        n: Int,
        why: String,
    ): Boolean {
        val ky = "$tag|noc"
        val first = tries.getOrPut(ky, { 0 })
        if (first == 0) {
            tries[ky] = (SystemClock.elapsedRealtime() / 1000L).toInt().coerceAtLeast(1)
            AppState.addLog("[!] $kind 页$why → **不猜**（盲选不靠谱），先等页面/数据就绪")
            return true
        }
        val waited = SystemClock.elapsedRealtime() / 1000L - first
        if (waited < SEL_NOCARD_HEAL_S) {
            if (n % 10 == 0) AppState.addLog("… $kind 页仍未就绪（$why，已 ${waited}s）→ 继续等，不点")
            return true
        }
        val heal = selNoCardHeals.getOrPut(tag, { 0 })
        // ★中间档：**丢缓存重解析**（2026-10-16，用户："重启重新挂载是不是理论上有可能解决"）
        //   回答是"可能，但先试更便宜的"：清掉 agent 侧所有读取缓存（实例缓存 / TTL 结果缓存 / LTD 指针）
        //   再重读一遍。它能治好两类——① 缓存里的实例已被复用/销毁（浅校验仍通过）；② LTD 指着旧实例
        //   ——而"页面自己没建好（字段就是空）"这类只有**重启重建**才行。清完读到了 ⇒ 日志留证：是缓存问题。
        if (tries.getOrPut("$tag|clr", { 0 }) == 0) {
            val r = AgentApi.labResetCaches()
            tries["$tag|clr"] = 1
            tries.remove(ky)                                      // 重新计时，再给一次机会
            AppState.addLog(
                "… $kind 页 ${waited}s 仍未就绪 → **丢缓存重解析**" +
                    "（清 ${r?.opt("cleared") ?: "?"} 项实例缓存，LTD 指针也丢掉重取）→ 下一轮重读",
            )
            return true
        }
        if (heal < SEL_NOCARD_HEAL_MAX) {
            selNoCardHeals[tag] = heal + 1
            tries.remove(ky)                                     // 重启回来重新计时
            selfHealReason = "$kind 页 ${waited}s 仍无法正确选择（$why；第 ${heal + 1}/$SEL_NOCARD_HEAL_MAX 次）"
            return true
        }
        AppState.addLog("[✗] $kind 页重启 $SEL_NOCARD_HEAL_MAX 次仍无法正确选择（$why）→ 停手（留人工）")
        stopRequested = true
        return true
    }

    /**
     * "选取页" picker（pageTop=`DialogLabyrinthSelection`）—— 战后选人(SUMMON_UNIT·同为 1) 与
     * 门票(SUMMON_TICKET_CHOICE·2) **共用同一页类**，只是卡面内容不同（角色 vs 属性印记）。
     * 3 张卡里挑 1 张最好的点「决定」。
     *
     * 读取走 agent 的 `labSelection`（堆扫描 DialogLabyrinthSelection 实例，返回每张卡的 unitId + 决定按钮坐标）。
     * **架构**：handler 不做选择——决策全在 P4（`CharaPick`），本函数只负责取 PLAN 并点「决定」：
     *   · 战后选人 → `pickLabReward(cards, ctx)`（PC `lab_select_rank`：底座 + L5~L1 特殊优先，完整）；
     *   · 门票     → `pickTicket(cards)`（PC `handle_ticket_choice`：按属性次序 暗>火>光>风>水 取首个匹配）。
     * 决策所需的 own 数据（本局持有角色/遗物 + 层数）由 `CharaPick.buildCtx` 从 agent 的
     * labUnits/labOwnRelics/labStatus.area 折算；handler 按卡片签名缓存，避免每轮重打慢 RPC。
     */
    private suspend fun handleLabSelection(
        input: InputBackend,
        st: JSONObject,
        tag: String,
        pick: CharaPick,
        tries: MutableMap<String, Int>,
        kind: String = "战后选人",
    ) {
        val n = bump(tries, tag)
        // n>=2 说明上一轮没点成（多为卡片滑入动画 → 坐标在屏外）→ 强制刷新绕过 1s 读缓存，
        // 否则重读在 1s 内一直拿到旧的屏外坐标，白等一秒。
        // ★同一轮内多读几次（2026-10-17 用户："首读空同样多读几次"）：页面刚建立时 `cards` 常是空
        //   （实测选人/门票都是下一轮才好）⇒ 先在同轮里隔 250ms 重读 3 次，别急着走 selNoDecide ✗
        // ★同一轮内多读几次（2026-10-17 用户："首读空同样多读几次"）：页面刚建立时 `cards` 常是空
        //   （实测选人/门票都是下一轮才好）⇒ 先在同轮里重读几次，别急着走 selNoDecide ✗
        // ★★"战后选人"的合格判据（2026-10-17 用户："选人页不可能出现 unitid=0…如果查到不中角色池中的
        //   结果，就认为这次读取是失败的。必然能读到三张正常的卡才对"）：
        //     ① 必须正好 **3 张**；② 每张 unitId ≠ 0；③ 每张 unitId 都**落在角色池里**
        //        （`units.json` 查得到 ⇒ `uname() != "?"`）。
        //   任一不满足 ⇒ 这次读取算失败，继续重读；重读都失败才走原来的"读不到卡片"处置 ✓
        //   ⚠门票页豁免：印记卡本来就可能没有 unitId（只按张数 + 卡面文字判属性）。
        // ★重读 8 次 × 400ms ≈ 3.2s（2026-10-17 用户："5*400 和 8*400 在能取到数据的情况下速度是一样的，
        //   所以改大一点也没事"）：确实——第一次就读到就立刻返回，重读只在失败时才付代价；
        //   而实机这页填数据要 2s 以上（14 次 `unitId=0` 都是 5 次没覆盖到）⇒ 放到 3.2s 更稳。
        val q = readRetry(times = 8, gapMs = 400L, ok = { s ->
            val arr = s.optJSONArray("cards") ?: return@readRetry false
            if (kind == "门票") return@readRetry arr.length() > 0
            if (arr.length() != 3) return@readRetry false
            (0 until arr.length()).all { i ->
                val uid = (arr.optJSONObject(i)?.opt("unitId") as? Number)?.toInt() ?: 0
                uid != 0 && pick.uname(uid) != "?"
            }
        }) { AgentApi.labSelection(force = true) } ?: AgentApi.labSelection(force = n >= 2)
        if (q == null) {
            if (n == 1) AppState.addLog("[!] $kind 页读不到（没连上/超时）（只报一次）")
            return
        }
        // ★agent 的 labSelection 成功时不设 ok 字段（只看 cards 是否非空），判据只认 err（非空真字符串）
        val err = q.optString("err")
        val failed = err.isNotEmpty() && err != "null"
        if (failed) {
            if (n == 1) AppState.addLog("[!] $kind 页读不到：${err.take(120).ifEmpty { "（没说原因）" }}（只报一次）")
            return
        }
        val cards = (0 until (q.optJSONArray("cards")?.length() ?: 0))
            .mapNotNull { q.optJSONArray("cards").opt(it) as? JSONObject }
        if (cards.isEmpty()) {
            // ★读不到卡片（2026-10-16 实机：`SUMMON_UNIT` + `DialogLabyrinthSelection` 停在节点 20302
            //   4 分钟以上，脚本只在第 1 轮打了句"没有卡片"就再也不动、也永远不自愈 ⇒ 用户："怎么好久没选人"）：
            //   ① 先等：页面可能还在建（正常几轮内就该读到）；
            //   ② 等够 [SEL_NOCARD_HEAL_S] 秒仍读不到 ⇒ **请求自愈重启**（走"帧冻住"同一条路；这页多半是
            //      游戏冻死时留下的**半建状态**，重启后能被重新建出来）；
            //   ③ 每页最多 [SEL_NOCARD_HEAL_MAX] 次（计数器**跨重启保留**，否则会无限重启）⇒
            //      用完就**停手收工**（不再无声空转，留人工点掉这一页）。
            // ★门票页的**第二条读法**（2026-10-16 实机）：门票页（`SUMMON_TICKET_CHOICE`）的
            //   `DialogLabyrinthSelection` 结构与选人页不同 ⇒ `labSelection` **永远是空** ⇒ 我们只能
            //   "留人工"，而且每轮重读还会把游戏读卡（22:04 那次卡死就是）。
            //   改问 `labChoices`（另一条读法，实测在这页有卡、且带 `decideX/decideY`）：
            //     · 只有 **1 张**卡 ⇒ **唯一选项**，点它（不是猜 —— 与"事件只有一个选项"同一原则）；
            //     · 多张 ⇒ 还需要**卡面文案**才知道该选哪张（`labChoices` 目前不返回文案）⇒ 仍不猜，
            //       日志里说清"补文案字段后即可自动选"。
            if (kind == "门票") {
                // ★同轮重读 3 次、要读到 **3 张**才算（2026-10-17 用户："门票/遗物首读空同样多读几次"）
                //   —— 实测门票页首读 `n=0` 出现过 4 次，下一轮就好；别一空就走 selNoDecide 的自愈升级。
                val ch = readRetry(times = 5, gapMs = 400L, ok = { it.optJSONArray("cards")?.length() ?: 0 >= 3 }) {
                    AgentApi.labChoices(force = true)
                }
                val all = (0 until (ch?.optJSONArray("cards")?.length() ?: 0)).mapNotNull { i ->
                    ch?.optJSONArray("cards")?.optJSONObject(i)
                }
                val cds = all.filter { it.opt("decideX") != null && it.opt("decideY") != null }
                // ★门票页**固定三选一**（用户 2026-10-16："没有例外"）⇒ 读到的张数必须 == 3。
                //   ⚠少于 3 张 **不是"唯一选项"**，而是**我们没读全** ⇒ **绝不能点**（点下去就是替玩家
                //   在没看到其余选项的情况下瞎选 ✗）。上一版我按"唯一选项 ⇒ 点它"处理，是错的，已撤。
                if (cds.size != 3) {
                    if (tries.getOrPut("$tag|tk3", { 0 }) == 0) {
                        tries["$tag|tk3"] = 1
                        AppState.addLog(
                            "[!] 门票 页：labChoices 只读到 ${cds.size} 张（该页**固定 3 张**）→ " +
                                "**没读全，不点**；等下轮重读（或补 labChoices 的读法）",
                        )
                    }
                }
                // ★取证：**不管读到几张都 dump 一次**（2026-10-16）——`pickTicket` 要"卡面文案"判属性
                //   （暗>火>光>风>水），而 `labChoices` 不返回文案；而且现在"只读到 N 张"也需要证据
                //   （卡是哪些、`hasUnit` 是什么）。有 `ptr` 就能 dump，一张也够看结构。
                if (all.isNotEmpty() && tries.getOrPut("$tag|tkd", { 0 }) == 0) {
                    tries["$tag|tkd"] = 1
                    AppState.addLog("（门票页取证：dump ${all.size} 张卡的对象字段，找文案字段…）")
                    for (c in all) {
                        val dd = AgentApi.objSlots(c.optString("ptr"), 0x10, 0x1c0)
                        AppState.addLog(
                            "【卡取证】ptr=${c.optString("ptr")} cls=${dd?.opt("cls")} → " +
                                (dd?.optJSONArray("slots")?.toString()?.take(240) ?: "null"),
                        )
                    }
                }
            }
            // ★读卡自证（2026-10-16）：`labSelection` 本身不带诊断 ⇒ 这里主动问一次 `labSelectionDiag`
            //   （它返回"扫了几个实例 / 选中哪个 / 每个实例有几张卡有 `+0xB8 unitInfo` / err"）。
            val dg = AgentApi.labSelectionDiag(force = true)
            val dd = dg?.optJSONObject("diag")
            val probes = (0 until (dd?.optJSONArray("probe")?.length() ?: 0))
                .mapNotNull { dd?.optJSONArray("probe")?.optJSONObject(it) }
                .take(3).joinToString(" ") { it.toString() }
            val whyTxt = ("cards=${dg?.opt("cards")} 扫描=${dd?.opt("scanned")} 选中=${dd?.opt("picked")}" +
                " err=${dd?.optString("err")?.take(50)} ｜ 探针=$probes").take(260)
            // ★读不到卡片 ⇒ **不猜**（原来在这里等 15 轮后"按坐标赌一把"）→ 交给统一处置（[selNoDecide]）
            selNoDecide(tag, kind, tries, n, "读不到卡片：$whyTxt")
            return
        }
        // 已点过这一页（按卡片集合签名去重）→ 只等游戏推进，不重复点（防连点）
        // ★签名带上本页 tag：印记页的卡片可能**都没有 unitId**（签名退化成空串），
        //   不带 tag 就会和别的页撞车 → 静默 return → 卡死。
        val sig = tag + "|" +
            cards.mapNotNull { (it.opt("unitId") as? Number)?.toInt() }.sorted().joinToString(",")
        // ★这一组卡**还没推进**时的处理（2026-10-16 用户截图「角色加入奖励!」那页：点了一下没点到，
        //   就再也没点 ⇒ 页面卡死 40s+）。按 PC `LAB_SELECT_TAP_MAX=2` 的口径：
        //     ① "真点下去"的次数还没到上限 ⇒ **允许再点一次**（隔 [LAB_SELECT_RETAP_MS]，别在动画里连点）；
        //     ② 到上限了 ⇒ 不再砸同一坐标（怕把游戏点卡），改用**节点树里文字=「选择」的按钮**再试一次
        //        （`decideX/Y` 取自卡片对象的按钮字段，和树里那颗文字节点算出来可能差几十像素）。
        var viaTree = false
        if (sig == lastSelSig) {
            val mx = PartyPlan.LAB_SELECT_TAP_MAX
            if (lastSelTaps >= mx) {
                if (lastSelTaps == mx) {
                    lastSelTaps = mx + 1                                  // 只报一次，之后闭嘴
                    viaTree = true
                    AppState.addLog("… $kind 页已点 $mx 次仍未推进 → 改用「节点树里的选择按钮」再试一次")
                } else {
                    // ★两次（或树兜底）都没让页面推进 ⇒ 别纯静默（2026-10-16：这一页静默了 4 分钟，
                    //   用户只看到"怎么好久没选人"）。每 ~15 轮说一句状态。
                    if (n % 15 == 0) {
                        AppState.addLog(
                            "… $kind 页仍在（本组卡已点 $mx 次 + 树兜底 1 次，都没推进）→ 等游戏/人工" +
                                "（选了哪张：$sig）",
                        )
                    }
                    return
                }
            } else {
                val since = SystemClock.elapsedRealtime() - lastSelTapAt
                if (since < PartyPlan.LAB_SELECT_RETAP_MS) return         // 给游戏反应时间，别连点
                AppState.addLog(
                    "… $kind 页第 ${lastSelTaps + 1}/$mx 次点：上次点了没推进（隔 ${since / 1000}s）→ 再点一次",
                )
            }
        } else {
            lastSelTaps = 0                                               // 换了一组卡 = 新页面
        }
        // ★决策交给 P4；own 数据（labUnits/labOwnRelics）按本页签名缓存，避免每轮重打慢 RPC
        val res = if (kind == "门票") {
            pick.pickTicket(cards)
        } else {
            val ctx = if (sig == lastCtxSig && lastCtx != null) lastCtx!! else {
                val area = (st.opt("area") as? Number)?.toInt()
                val u = AgentApi.labUnits()
                val r = AgentApi.labOwnRelics()
                val unitIds = intList(u, "unitIds")
                val relicArr = r?.optJSONArray("relics")
                val relicIds = (0 until (relicArr?.length() ?: 0))
                    .mapNotNull { relicArr?.optJSONObject(it)?.opt("relicId") }
                pick.buildCtx(area, unitIds, relicIds, relicArr?.length() ?: 0)
                    .also { lastCtx = it; lastCtxSig = sig }
            }
            pick.pickLabReward(cards, ctx)
        }
        // ★P4 判不出 / 选中的那张没坐标 ⇒ **不猜**（2026-10-16 用户："盲选太不靠谱，宁可重启游戏"）：
        //   走 [selNoDecide]（等 → 有界自愈重启 → 停手），**不再"退化为点第 1 张"**。
        //   （印记页本身的规则在 P4 `CharaPick.pickTicket`：按卡面文字属性顺序 暗>火>光>风>水 选，
        //     全不匹配才取第 1 张 —— 那是 PC 同款的**规则缺省**，不是这里的瞎猜。）
        var chosen = res?.card
        val why = res?.why.orEmpty()
        val level = res?.level ?: 0
        // ★判不出来 / 选中的那张没坐标 ⇒ **不猜**（原来是"退化为点第 1 张"，那等于替用户瞎选一张）
        if (res == null) {
            selNoDecide(tag, kind, tries, n, "P4 规则判不出该选哪张（返回 null）")
            return
        }
        if (chosen == null || !hasDecideXY(chosen)) {
            selNoDecide(
                tag, kind, tries, n,
                if (chosen == null) "选不出目标卡" else "选中的那张没有「决定」坐标（换一张＝换选择，不干）",
            )
            return
        }
        val cid = (chosen.opt("unitId") as? Number)?.toInt() ?: 0
        // ★卡片"还没读全"就别点（2026-10-16 实机：日志打出 `3 选 1 → 0 ?（评级 0 / 属性 ）` 却照样点了——
        //   那会儿卡片还在入场动画、id 读成 0）。战斗奖励的 3 张卡是**真角色**，读不出 id 时选谁都是瞎猜。
        //   ⚠只对"战后选人"生效：**门票页**那些印记卡本来就可能没有 unitId（见上面签名那段注释）。
        //   最多等 15 轮（≈20s，必须跨过 agent 的 1s 读缓存），仍读不出才带告警退化为按坐标点。
        if (kind != "门票" && cid == 0) {
            if (n == 1) AppState.addLog("[!] $kind 页卡片还没读全（unitId=0）→ 先不点，等下一轮重读")
            if (n <= 15) return                                     // 先给页面/读缓存时间
            // ★15 轮还读不出 ⇒ **不猜**（原来是"退化为按坐标点这张（赌一把）"）
            selNoDecide(tag, kind, tries, n, "卡片读不出发给谁（unitId=0，已 $n 轮）")
            return
        }
        val lvlTxt = if (level == 0) "评分" else "L$level"
        val dx = chosen.optInt("decideX")
        val dy = chosen.optInt("decideY")
        val onScreen = dx in 0 until scrW && dy in 0 until scrH
        // ★坐标在屏外：卡片/按钮常带"从屏外滑入"的动画，某时刻读到还在屏外。
        //   处理策略 = **先别点、也别标记已选**，等下一轮重读（动画停稳坐标就回屏内）；
        //   停太久（本例 8 轮 ≈ 1~2s）才退化为节点树找「选择」按钮兜底。
        if (!onScreen) {
            if (n == 1) {
                AppState.addLog("[!] $kind 页「决定」坐标 ($dx,$dy) 在屏外（大概在滑动动画中）→ 等下一轮重读")
            }
            // 阈值 15 轮：必须超过 agent 的 SEL_TTL_MS=1s 读缓存，否则重读拿到的还是旧的屏外坐标
            if (n <= 15) return
            AppState.addLog("[!] $kind 页坐标 $n 轮仍在屏外 → 改用节点树找「选择」按钮")
            val btns = findSelButtons(12)
            val onscr = (0 until (btns?.length() ?: 0))
                .mapNotNull { btns?.optJSONObject(it) }
                .filter { b ->
                    val x = (b.opt("x") as? Number)?.toInt() ?: -1
                    val y = (b.opt("y") as? Number)?.toInt() ?: -1
                    x in 0 until scrW && y in 0 until scrH
                }
            val pickBtn = onscr.minByOrNull { kotlin.math.abs(((it.opt("x") as? Number)?.toInt() ?: 0) - dx) }
            if (pickBtn == null) {
                if (n == 16) AppState.addLog("[!] $kind 页：节点树也没找到屏内的「选择」按钮 → 这页需要补 handler")
                return
            }
            tap(input, pickBtn.optInt("x"), pickBtn.optInt("y"), "$kind：选 $cid（节点树兜底）")
            lastSelSig = sig
            lastSelTaps++
            lastSelTapAt = SystemClock.elapsedRealtime()
            return
        }
        // ★日志收拾（2026-10-16）：纯评分时 `why` 与 `lvlTxt` 都是「评分」⇒ 之前打成 `｜ 评分（评分）`；
        //   读不出 id 时打 `?` 而不是 `0 ?`；评级 0 / 属性空 都当"未读到"显示 `?`。
        val idTxt = if (cid == 0) "?" else cid.toString()
        val tail = why.ifEmpty { if (lvlTxt == "评分") "" else lvlTxt }
        // ★悬浮窗（2026-10-17 用户："选择角色 xxx（名字）"）：选中谁就显示谁；
        //   门票页印记卡没有 unitId ⇒ uname(0) 返回 "?"（非空，ifBlank 兜不住）→ 这里显式判断：
        //   有真名(非"?")才用名字，否则退到卡面属性（如"门票：暗"），属性也空才 "?"。
        val dispName = pick.uname(cid)
        val disp = if (dispName != "?") dispName else pick.cardAttr(chosen).ifBlank { "?" }
        OverlayStatus.setState("$kind：$disp")
        AppState.addLog(
            "⏸ $kind：3 选 1 → $idTxt ${pick.uname(cid)}" +
                "（评级 ${pick.tierOf(cid).takeIf { it > 0 } ?: "?"} / 属性 ${pick.cardAttr(chosen).ifBlank { "?" }}）" +
                if (tail.isEmpty()) "" else "｜ $tail",
        )
        // ★三张候选全打（2026-10-17 用户："有些选人看上去不是很正常，比如水兔，是不是默认选1了"）：
        //   只打"选中哪张"看不出"是不是被缺省规则带走的" ✗ ⇒ 三张的 名字/id/评级/属性 全列出来、
        //   选中的打 ★。配合 `CharaPick.pickLabReward` 写进 `why` 的「同分 ⇒ 按序号取第 N 张」
        //   「某张查不到评级」，一眼就能分清"评过之后选的"与"缺省选第 1 张"。
        if (tries.getOrPut("$tag|rcands", { 0 }) == 0) {
            tries["$tag|rcands"] = 1
            val rows = cards.mapIndexed { i, c ->
                val u = (c.opt("unitId") as? Number)?.toInt() ?: 0
                (if (i == res.index) "★" else "·") + "${i + 1})" +
                    (if (u == 0) "?" else pick.uname(u)) + "(" +
                    (if (u == 0) "id?" else u.toString()) + "/" +
                    (pick.tierOf(u).takeIf { it > 0 }?.let { "L$it" } ?: "评级?") + " " +
                    pick.cardAttr(c).ifBlank { "属性?" } + ")"
            }
            AppState.addLog("   $kind 候选：${rows.joinToString("  ")}")
        }
        if (CharaPick.AUTO) {
            // ★★等"决定"按钮出来（2026-10-16 用户："选角色的卡片现在通过字段读，太快了，导致按钮还没出来"）
            //   现在卡片是**字段读**（几十 ms ✓）⇒ 常常"卡面已就绪、但下方「决定」按钮的入场动画还没结束"
            //   ⇒ 按坐标点下去就是**空点**（点不到按钮，还会白耗点击额度 ✗）。
            //   本页**第一次点**前等 [SEL_TAP_LEAD_MS]=700ms；重试不再等（`lastSelSig` 已标记 ⇒ 每页只付一次 ✓）。
            if (lastSelSig != sig) {
                if (n <= 2) AppState.addLog("… $kind 页：字段读已就绪 → 等 ${SEL_TAP_LEAD_MS}ms 让「决定」按钮出来再点")
                delay(SEL_TAP_LEAD_MS)
            }
            if (viaTree) {
                val btns = findSelButtons(12)
                val cands = (0 until (btns?.length() ?: 0)).mapNotNull { btns?.optJSONObject(it) }
                    .filter { b ->
                        val x = (b.opt("x") as? Number)?.toInt() ?: -1
                        val y = (b.opt("y") as? Number)?.toInt() ?: -1
                        x in 0 until scrW && y in 0 until scrH
                    }
                val pb = cands.minByOrNull {
                    kotlin.math.abs(((it.opt("x") as? Number)?.toInt() ?: 0) - dx)
                }
                if (pb == null) {
                    AppState.addLog("[!] $kind 页：节点树里也没有「选择」按钮 → 这页需要人工（不再点）")
                    return
                }
                tap(input, pb.optInt("x"), pb.optInt("y"), "$kind：选 $cid（树·选择）")
            } else {
                tap(input, dx, dy, "$kind：选 $cid")
            }
            lastSelSig = sig   // ★点下去后才标记"这页已点"（否则屏外那次会把它误标成已完成 → 卡死）
            lastSelTaps++      // ★计数 = "真点了几次"（PC 同口径），到 LAB_SELECT_TAP_MAX 才停手
            lastSelTapAt = SystemClock.elapsedRealtime()
        }
    }

    /** ★选人/门票页：本页**第一次点**「决定」前的等待（让按钮的入场动画走完，见调用处注释）。
     *  2026-10-17 用户："选人的 0.7 改成 0.5" ⇒ 700 → 500。 */
    private const val SEL_TAP_LEAD_MS = 500L

    /** ★遗物页：**第一次点**「决定」前的等待（2026-10-17 用户："选遗物加上 0.5（仅第一次点击）"）。
     *  同理选人页：卡片是**字段读**（几十 ms ✓）⇒ 卡面就绪 ≠ 按钮就绪（刚见过"决定坐标还在屏外
     *  (1515,1228)、下一轮才落位到 (1515,935)"）⇒ 第一次点前等一下，免得点空。 */
    private const val REL_TAP_LEAD_MS = 500L

    /** ★「选择」按钮（选人/门票/遗物页都在 DialogArea 里）：**先限根 `dialog`**（几十 ms ✓）、
     *  空了再全树兜底（2026-10-17：全树一次实测 ~3s ✗，这些页每轮都要找它 ⇒ 单通道常被占住 ⇒
     *  便宜调用（viewId/gameTick）被挤超时）。 */
    private suspend fun findSelButtons(max: Int): org.json.JSONArray? {
        val a = AgentApi.findTextList("选择", max, null, "dialog")
        if (a != null && a.length() > 0) return a
        return AgentApi.findTextList("选择", max)
    }

    /** 这张卡有没有可用的「决定」按钮坐标（`decideX/decideY`；JSON null 不算） */
    private fun hasDecideXY(c: JSONObject): Boolean {
        val dx = c.opt("decideX"); val dy = c.opt("decideY")
        return dx != null && dx !== JSONObject.NULL && dy != null && dy !== JSONObject.NULL
    }

    /** 从 JSONObject 里取 int 列表（单元 id 等） */
    private fun intList(o: JSONObject?, key: String): List<Int> {
        val a = o?.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<Int>(a.length())
        for (i in 0 until a.length()) (a.opt(i) as? Number)?.toInt()?.let { out.add(it) }
        return out
    }

    /**
     * PC 版 `maze_loop` 的**"移动"分支**（6373~6458）：`statusType==0` 时点地图上的目标节点前进。
     *
     *   ① 优先按**预计算路线**走：取路线里"当前节点的下一站"（这就是"局内按路线前进"）；
     *   ② 不可点/没路线 → [Route.pickNode] 兜底（按类型优先级），并标 `（偏离路线）`；
     *   ③ 同一节点 [NODE_TAP_GAP_MS] 内不重复点，间隔按 **1→2→3s 退避**（用户 2026-10-13：防"走图时无意义的点击"）；
     *   ④ 目标在屏外 → **横向拖动地图**把它拉进视野（最多 [DRAG_LIMIT] 次；时长越长=抬手越慢≈削弱惯性）。
     *
     * @return true = 本轮点了或拖了（有效动作）；false = 什么也没找到（调用方据此加 `idle`）
     */
    private suspend fun handleMove(input: InputBackend, st: JSONObject, screenW: Int, screenH: Int): Boolean {
        val nodesArr = AgentApi.labNodes()
        // ★别用 optJSONObject(Int)：各 org.json 实现里这个重载不一样（Android 运行时 / 单测的 json-java
        //   甚至会被解析成 "optJSONObject(String)"）→ 统一走 opt(i) as? JSONObject
        val nodes = if (nodesArr == null) emptyList()
        else (0 until nodesArr.length()).mapNotNull { nodesArr.opt(it) as? JSONObject }
        val nxt = intListOf(st, "next")
        val sel = nodes.filter {
            (it.opt("panelState") as? Number)?.toInt() == Route.NODE_SELECTABLE &&
                (nxt.isEmpty() || (it.opt("blockId") as? Number)?.toInt() in nxt) &&
                (it.opt("x") as? Number) != null
        }
        // ① 优先按路线走：路线里当前节点的下一站（那颗必须是"可前往"且在屏内）
        var t: JSONObject? = null
        var rt = ""
        val area = (st.opt("area") as? Number)?.toInt() ?: -1
        val cur = (st.opt("blockId") as? Number)?.toInt() ?: -1
        // ★把刷开局算好的**本区路线**一起传下去（2026-10-17 复盘发现）：移动分支原来不传
        //   `savedRoute` ⇒ 只有区 1（开局那次调用了带 savedRoute 的版本）用上预计算路线，
        //   区 2~5 全部打「现算 DP」（日志实证：area2/3/4 都是"现算 DP" ✗，与开局那句
        //   "预计算路线区=[1,2,3,4,5]"自相矛盾）。两者口径本就一致（PC 已验证），
        //   所以这不会改路线选择，**但会让日志/行为与预期一致**，也省一次 DP。
        val rp = Route.refreshRoute(area, cur, savedRoute = rerollRoutes?.get(area))
        if (rp.isNotEmpty() && cur in rp) {
            val i = rp.indexOf(cur)
            if (i + 1 < rp.size) {
                val want = rp[i + 1]
                t = nodes.firstOrNull {
                    (it.opt("blockId") as? Number)?.toInt() == want &&
                        (it.opt("panelState") as? Number)?.toInt() == Route.NODE_SELECTABLE &&
                        (it.opt("x") as? Number) != null
                }
                if (t == null) {
                    AppState.addLog("[!] 路线下一站 B$want 当前不可点（panelState/屏内不符）→ 回退按类型优先级挑")
                }
            }
        }
        // ② 兜底：无路线/偏离路线 → 按类型优先级挑
        if (t == null) {
            t = Route.pickNode(nodes, nxt, screenW, screenH)
            if (t != null) rt = "（偏离路线）"
        }
        if (t != null) {
            dragCount = 0
            val nk = (t.opt("blockId") as? Number)?.toInt() ?: -1
            val now = System.currentTimeMillis()
            val last = nodeTapAt[nk] ?: 0L
            if (now - last > 30_000) nodeTapN[nk] = 0                    // 很久没碰过 → 计数从头来
            val gap = minOf(NODE_TAP_GAP_MS * (1 + (nodeTapN[nk] ?: 0)), NODE_TAP_BACKOFF_MS)
            if (now - last < gap) {
                delay(150)                                               // 刚点过 → 等游戏把它打开，别重复点
                return true
            }
            nodeTapN[nk] = (nodeTapN[nk] ?: 0) + 1
            nodeTapAt[nk] = now
            val bt = (t.opt("blockType") as? Number)?.toInt()
            val what = "前进到节点$nk[${Route.LAB_BLOCK_TYPE[bt] ?: Py.str(bt)}]$rt"
            OverlayStatus.setState("前往节点$nk")      // ★悬浮窗（细于 overlayTick 的默认描述）
            tap(input, t.optInt("x"), t.optInt("y"), what)
            nodeSaidAt[nk] = now                                         // 同一节点 30s 内只"重点"一次（PC 6425）
            delay(800)
            return true
        }
        // ③ 目标在屏外 → 横向拖动地图，把它拉进视野
        val off = sel.filter { (it.opt("x") as? Number)?.toInt()?.let { x -> x > screenW || x < 0 } == true }
        if (off.isNotEmpty() && dragCount < DRAG_LIMIT) {
            val half = screenW / 2
            val n0 = off.minByOrNull { kotlin.math.abs((it.opt("x") as? Number)!!.toInt() - half) }!!
            val n0x = n0.optInt("x")
            val cy = screenH / 2
            val toward = if (n0x > screenW) -1 else 1                    // 目标在右 → 向左拖
            val step = maxOf(200, minOf((screenW * 0.55).toInt(), kotlin.math.abs(n0x - half)))
            val x1 = if (toward < 0) screenW - 150 else 150
            val x2 = maxOf(80, minOf(screenW - 80, x1 + toward * step))
            val dist = kotlin.math.abs(x2 - x1)
            val ms = maxOf(DRAG_MS_MIN, minOf(DRAG_MS_MAX, (dist * 1.4).toInt()))
            val r = input.swipe(x1, cy, x2, cy, ms.toLong())
            dragCount++
            AppState.addLog(
                "拖动地图（找节点${n0.optInt("blockId")}，第 $dragCount 次）" +
                    if (r is com.lmj.labyrinthauto.input.InputResult.Ok) "" else "：${r}",
            )
            delay(DRAG_SETTLE_MS)                                        // 静置：等滑动惯性结束、地图停稳
            return true
        }
        AppState.addLog(
            // ★x/y 现在只有"可前往(4)/当前所在(0)"这两类才有（agent 侧省掉了其余状态的坐标计算 ✓）
            "→ 未找到屏内可前往节点（panelState==4 且在屏内）；可前往=$nxt；全部节点=" +
                nodes.take(12).joinToString("、") {
                    "${it.opt("blockId")}/${it.opt("panelState")}" +
                        ((it.opt("x") as? Number)?.let { v -> "/$v" } ?: "")
                } +
                if (nodes.size > 12) "…（共 ${nodes.size} 个）" else "",
        )
        return false
    }

    // ────────────────────────── handler：QUEST(5)/BOSS(6) 战斗 ──────────────────────────

    /** PC `PARTY_SLOT_RE`(502) / `PARTY_BTN["decide"]`(501)：编组页独有零件（用来交叉验证"谁在最上层"） */
    private const val PARTY_SLOT_RE = "^UnitIcon(L2|L1|C|R1|R2)$"
    private const val PARTY_DECIDE_BTN = "ButtonDecide"

    /** PC `_party_page_on_top`(1219)：编组页零件在不在屏上（pageTop 有时漏报）。
     *  ★限根 `dialog`（2026-10-17 实机：这里原来是 2 次**全树** `find`，一次 2.5~2.9s ✗，
     *   本局被走了 3 次）：编组页自己就是 `DialogCommonPartyEdit`，零件一定在 DialogArea 里 ✓ */
    private suspend fun partyPageOnTop(): Boolean {
        val slot = AgentApi.findList(PARTY_SLOT_RE, "dialog")?.length() ?: 0
        if (slot > 0) return true
        val dec = AgentApi.findList(PARTY_DECIDE_BTN, "dialog")?.length() ?: 0
        return dec > 0
    }

    /**
     * PC `handle_battle_node`(3750)：QUEST/BOSS 的**总入口** —— 按"最上层对话框"再分派。
     *
     * 为什么必须按页面分派（PC 3751）：换页时 `statusType`（仍 QUEST(5)）和 `viewClass`
     * （仍 ViewLabyrinthMap）**都可能完全不变**，只有 `pageTop` 能区分"预览页"和"队伍编组页"；
     * 两个对话框会同时 active，按坐标点会落到**上层**那个的同位置按钮上（实测把「挑战」点成了「战斗开始」）。
     */
    private suspend fun handleBattleNode(
        input: InputBackend,
        st: JSONObject,
        tag: String,
        tries: MutableMap<String, Int>,
        dumped: MutableSet<String>,
    ) {
        // 遇到 BOSS 节点：记下本区 Boss unitId（遗物特殊条件要用；PC 靠 labyrinth_state.json 的 boss 列表）
        if (st.optInt("statusType") == 6) {
            val q = st.optString("questId")
            val a = (st.opt("area") as? Number)?.toInt()
            if (q.isNotBlank() && a != null) PartyPlan.bossOf(q)?.let { bossByArea[a] = it }
        }
        val top = jStr(st, "pageTop")
        when {
            PAGE_QUEST_DETAIL in top -> handleQuestDetail(input, st, tag, tries)
            PAGE_PARTY_EDIT in top -> handlePartyEdit(input, st, tag)   // PC 3228：读人 → 决策编队 → 战斗开始
            PAGE_QUEST_FAILED in top -> handleQuestFailed(input, st)
            else -> {
                // PC 3810：小弹窗壳是"进战斗/领奖"的过渡页 → 只等不点（也不告警、不 dump）
                if (PAGE_SMALL in top) return
                unknownState(st, dumped)
            }
        }
    }

    /**
     * 遗物三选一（RELIC_CHOICE=9）—— 逐行对应 PC maze_loop 6297~6362：
     * 读每张候选卡自己的 rewardId + DecideButton 坐标，按 `RelicPick.pickCard` 选一张点下去。
     * 同一组候选最多点 [REL_TAP_MAX] 次（点不动就不再砸同一坐标，防把游戏点卡）；返回是否点了（没点则让 idle 累加）。
     *
     * ★与 PC 的唯一有意差异：`special`（3 区 Boss=毒液沙罗曼蛇）的判定来源。
     * PC 从 `labyrinth_state.json`（刷开局预写）一次性拿到**所有区** Boss；这里改为"遇到 BOSS 节点时记进 [bossByArea]"，
     * 故在**打到 3 区 Boss 之前**就领遗物、且 3 区恰是毒液时，special 会判 false（仅影响守备·持续回复的优先级）。
     */
    private suspend fun handleRelicChoice(input: InputBackend, st: JSONObject, tag: String, tries: MutableMap<String, Int>): Boolean {
        val rn = bump(tries, tag)   // 本页轮数（换页清零）：rn>=2 = 上一轮没点成（多为卡片未落位/动画中）
        // ★★同一轮内**多读几次**（2026-10-17 用户："门票/遗物首读空同样多读几次，不要立刻放弃。
        //   切换状态可能有个过程"）：实测遗物首读 `cards=0` 连打 3 行（11:58:44~45）、门票 4 次 ——
        //   都是**页面刚建立**那一瞬，下一轮就好了。以前只能"本轮不点、下一轮再看"，告警刷屏还会计入
        //   自愈升级；现在同轮里重读 5 次（还带 force 绕过缓存）✓
        // ★★合格判据（2026-10-17 用户："遗物呢，有这个检查吗，遗物也固定有 id 的"）——**和选人页同一把尺**：
        //   ① 正好 **3 张**；② 每张都有 `decideX`（有点击坐标）；③ 每张 rewardId 都**在遗物表里**
        //      （`isKnown`）。任一不满足 = 这次读取失败，继续重读 ✓
        //   （实测垃圾读数是 60 个 8~20 亿的假 id ⇒ 三条全不过 ⇒ 自动重读，不会拖到后面去"盲选"）
        val cs = readRetry(times = 5, gapMs = 400L, ok = { s ->
            val arr = s.optJSONArray("cards") ?: return@readRetry false
            if (arr.length() != 3) return@readRetry false
            (0 until arr.length()).all { i ->
                val c = arr.optJSONObject(i) ?: return@readRetry false
                c.opt("decideX") != null && relicPick.isKnown(c)
            }
        }) { AgentApi.labChoices(force = true) } ?: AgentApi.labChoices(force = rn >= 2) ?: return false
        val cards = (0 until (cs.optJSONArray("cards")?.length() ?: 0))
            .mapNotNull { cs.optJSONArray("cards")?.optJSONObject(it) }
            .filter { it.opt("decideX") != null }   // PC: c.get("decideX") is not None
        if (cards.isEmpty()) {
            if (rn <= 2) {
                AppState.addLog(
                    "[遗物] 未读到可用候选卡（cards=${cs.optJSONArray("cards")?.length() ?: 0}，" +
                        "decides=${cs.optJSONArray("decides")?.length() ?: 0}）→ 本轮不点 ｜why=${cs.optString("why")}",
                )
            }
            return false
        }
        // ★★"读到的候选不干净就不点"闸门（2026-10-17 用户："遗物也固定有 id 的"）：
        //   判据与选人页同一把尺 —— **三张必须全是真候选**：张数对、有 `decideX`、rewardId 都在遗物表里。
        //   · 上面 [readRetry] 已按这条重读 5 次，能走到这里说明"重读也没读干净"；
        //   · `pickCard` 的兜底是 PC 同款的"全无数据 ⇒ 等价于取第一张卡"，而实测垃圾读数是
        //     `[1179648, 2084821607, …]`（60 个 8~20 亿的假 id）⇒ **照选就是盲选** ✗；
        //   · 所以：只要**还有一张不在表里**就当读数异常 ⇒ 不点，走"丢缓存 → 自愈重启 → 停手"。
        val known = cards.count { relicPick.isKnown(it) && it.opt("decideX") != null }
        if (known < cards.size || cards.size != 3) {
            val idsBrief = cards.take(6).map { it.opt("rewardId") }.toString()
            // ① 便宜档：**丢缓存重解析**（最可能的根因 = `g_instCache["labSel"]` 指着旧/别的
            //    `DialogLabyrinthSelection` ⇒ 结构化路径读到空、兜底扫堆又吐垃圾）。清完重读一次。
            if (tries.getOrPut("$tag|rclr", { 0 }) == 0) {
                tries["$tag|rclr"] = 1
                val r = AgentApi.labResetCaches()
                AppState.addLog(
                    "[遗物] 候选不干净（认表 $known/${cards.size} 张，应有 3 张：$idsBrief）→ 疑似读数异常 ⇒ " +
                        "**丢缓存重解析**（清 ${r?.opt("cleared") ?: "?"} 项）后重读 ｜LTD候选=${ltdChoicesBrief(st)}",
                )
                return false
            }
            // ② 激进档（2026-10-17 用户："我想全自动……如果兜底扫描扫出来是垃圾数据，那就直接重启"）：
            //    请求自愈重启（主循环下一步就 `GameProcess.kill()` → 重新调起 + 重注 → 回迷宫继续本局），
            //    按页面 tag 计数 ≤ [RELIC_BAD_HEAL_MAX]，用完才停手留人工。
            val heal = relicBadHeals.getOrPut(tag, { 0 })
            if (heal < RELIC_BAD_HEAL_MAX) {
                relicBadHeals[tag] = heal + 1
                tries.remove("$tag|rclr")                        // 重启回来允许再走一次"丢缓存"
                selfHealReason =
                    "遗物候选读数全不可信（${cards.size} 张全不在遗物表：$idsBrief；第 ${heal + 1}/$RELIC_BAD_HEAL_MAX 次）"
                AppState.addLog(
                    "[遗物] 候选读数仍是垃圾（$idsBrief）→ **请求自愈重启**" +
                        "（第 ${heal + 1}/$RELIC_BAD_HEAL_MAX 次；重启后重读这一页）",
                )
                return false
            }
            AppState.addLog("[✗] 遗物候选重启 $RELIC_BAD_HEAL_MAX 次后读数仍是垃圾（$idsBrief）→ 停手（留人工）")
            stopRequested = true
            return false
        }
        val area = (st.opt("area") as? Number)?.toInt()
        val own = AgentApi.labOwnRelics()
        val ctx = relicPick.relicCtx(own, area, bossByArea[3])
        val k = relicPick.pickCard(cards, ctx)
        val c = cards[k]
        // ★照 PC：卡片**还没落位**（决定坐标在屏外，入场动画中）→ 本轮**不点、也不计入已点次数**，
        //   等它落位（force 重读拿到屏内坐标）再点。否则会空点 → 计数 → 到 REL_TAP_MAX 就停手卡死。
        val dx = c.optInt("decideX")
        val dy = c.optInt("decideY")
        if (dx !in 0..scrW || dy !in 0..scrH) {
            if (rn <= 2) AppState.addLog("[遗物] 选中卡的「决定」坐标 ($dx,$dy) 在屏外（大概未落位/动画中）→ 等落位再点")
            return false
        }
        // 同一组候选的重复点击防护（组变了 → 重置）
        val sig = cards.joinToString(",") { it.opt("rewardId").toString() } + "|" + c.opt("rewardId")
        val prev = relicChoiceTries[tag]
        val n = if (prev != null && prev.first == sig) prev.second + 1 else 1
        if (n > REL_TAP_MAX) {
            if (n <= 2 || n % 30 == 0) {
                AppState.addLog("[遗物] 这一组候选已点过 $n 次仍没进展（选中 ${c.opt("rewardId")}）→ 不再点击，等待中")
            }
            return false
        }
        // ★真点下去才算一次尝试（PC `tap_xy` 同款语义）
        // ★★第一次点前等 [REL_TAP_LEAD_MS]（2026-10-17 用户："选遗物加上 0.5（仅第一次点击）"）
        //   判据用"这一组候选的点击次数 `n`"（`n == 1` = 第一次；重试不再等 ✓，同选人页口径）。
        if (n == 1) {
            if (rn <= 2) AppState.addLog("… [遗物] 第一次点前等 ${REL_TAP_LEAD_MS}ms 让「决定」按钮落位")
            delay(REL_TAP_LEAD_MS)
        }
        if (tap(input, dx, dy, "选择遗物：${c.opt("rewardId")}${if (n > 1) "（第 $n 次）" else ""}")) {
            relicChoiceTries[tag] = sig to n
            // ★悬浮窗：遗物三选一选中了谁（`2★ xxx`；查不到表就显示 rewardId）
            OverlayStatus.setState(
                "遗物：" + relicPick.relicLabel(c.opt("rewardId")).ifBlank { c.opt("rewardId").toString() },
            )
            AppState.addLog(
                "[遗物] 选 ${c.opt("rewardId")}（${cards.size} 张里认表 $known 张）：候选=[${cards.map { it.opt("rewardId") }}] " +
                    "理由=${relicPick.relicPickNote(c, ctx)} ｜LTD候选=${ltdChoicesBrief(st)}",
            )
            return true
        }
        return false
    }

    /**
     * PC `handle_party_edit`(3228)：编组页 = **读人 → 用 P4 决策层编队 → 点「战斗开始」**。
     *
     * 这里只做**核心**（PC 同款思想，部分简化，文档已记）：
     *   · 目标队用 P4 的 `TeamBuild.buildBest(stage, pool, relicsSkill, floor, size)`（已对拍 500/500）；
     *   · 一次算好的操作**一把点完**（撤人=点下排槽位、加入=点上方角色一览；两者都是"同一张快照的
     *     坐标一路点下去都有效"——槽位右对齐、一览是静态网格，见下方 ①a/①b 注释），
     *     点完等游戏生效、下轮**按实际队伍重算**；
     *   · 只能点"当前可见"的：不在可见区的缺员由滚动分支滚进屏，下一轮再点一批（**滚→点→滚→点**）；
     *   · 集合一致（`plan.done`）且 `LAB_PARTY_AUTO` 且到下限 → 点「战斗开始」（`ButtonDecide`）；
     *   · **特别装备三连页**由 `handleExequip` 驱动（在白名单之前拦截，不进弹窗白名单，免得误点按钮坏队）；
     *     编队就绪后自动点开「特别装备」，走完才点「战斗开始」（顺序见下方 ②）。
     *   · 多分队走 [PartyPlan.partyFillOk]：**5 层 Boss 默认编满 3 队**；3 层/其它层只编第 1 队
     *     （本节点失败过才补后备队）；**不该编的那一队空队放行**（点「决定」走过去，游戏允许不编满就挑战）。
     */
    private suspend fun handlePartyEdit(input: InputBackend, st: JSONObject, tag: String) {
        // ★策略=等待接手：本节点失败 3 次后由用户自己组队 → 这里什么都不做（也不点「决定」）
        val nk0 = nodeKey(st)
        if (nk0 in manualTeamNodes) {
            if (manualSaid.add(nk0)) {
                AppState.addLog("[手动] 节点 ${jStr(st, "blockId")}：等待你组队/开战（本节点自动组队已关闭）")
                OverlayStatus.setState("手动组队：等待你操作")
            }
            return
        }
        val pt = AgentApi.labParty()
        if (pt == null) {
            AppState.addLog("[!] 编队页读不到（超时/没连上）→ 本轮不点")
            return
        }
        // PC 1852 同款坑：`labParty` 成功时 `err` 就是 JSON null，不算错；只有非空真字符串才算。
        //   （之前用 optString 把 JSON null 变成字符串 "null" → 把成功读当成"读不到"，于是永远不动手。）
        val err = pt.optString("err")
        val partyFailed = err.isNotEmpty() && err != "null"
        if (partyFailed) {
            // ★"还没建好"是正常过渡（页面刚弹出那几百 ms）——agent 现在会快速失败、不再扫堆（数秒 ✗），
            //   这里静默等下一轮即可，别每轮刷一行（2026-10-17）。
            if (err.contains("还没建好")) {
                if (!partyNotReadySaid) {
                    partyNotReadySaid = true
                    AppState.addLog("… 编队页还没建好（DialogArea 里没有）→ 下一轮再读")
                }
            } else {
                AppState.addLog("[!] 编队页读不到：$err → 本轮不点")
            }
            return
        }
        partyNotReadySaid = false
        // ★[labResultSeen] 的复位**不在这里**（2026-10-22）：它原来放在编队页，而第二局"开局选角色"
        //   发生在第一个编队页之前 ⇒ 带着上一局的 true 在选人页点了屏幕中央（实机踩过）。
        //   现在统一在 [run] 开头复位；本处只管本节点的日志/计数。
        rewardsLogged = false                    // 结算奖励日志（本局重新来）
        rewardCenterTaps.clear()
        unknownFirstSeen.clear()                 // 未知状态"先观察"计时（本局重新来）
        // ★交叉验证 LTD（2026-10-16 查"池读少"用）：`pt.ltdOk` = 编组页零件引用的 LTD 是否**就是**
        //   agent 缓存 `g_lab` 那个（`_partyLtdOk` 比对 `PartsPartyEditLabyrinth+0x1A0`）。
        //   false ⇒ 我们读状态/持有池用的 LTD 可能是**上一局残留的对象**（`_findLTD` 的 alive() 只做
        //   结构校验、不校验"是不是当前局"）⇒ `池(持有N)` 会读少（实测：同一局页面"可编入"从 7 涨到 15，
        //   而"持有"一直是 7）。null = 取不到，不做判定。
        if (pt.opt("ltdOk") == false) {
            AppState.addLog("[!] 编组页引用的 LTD 与 agent 缓存的不一致 → 持有池/状态可能来自旧局（目标会偏）")
        }
        // ── ★页面静置闸门（PC `MAZE_PAGE_SETTLE`(364) 的等价物，2026-10-16 补）────────────────
        //   编组页刚出现时 UI 还没铺好、**旧页面的对话框实例仍在树上** ⇒ 这时读到的快照是**上一个节点的**
        //   （连 `plan.done` 也是假的"已完成"）→ 会直接点「战斗开始」开打、根本没编队。
        //   实测（用户 2026-10-16）：`挑战` → 1.0s → `战斗开始`，打的是上一节点的 3 人队，
        //   而本节点手上 7 人、按规则必须补满 5 人 ⇒ 判据被"旧快照 + 旧结论"骗了。
        //   所以这一页**先静置 [PartyPlan.LAB_PARTY_SETTLE_MS] 再动手**（只在页面刚出现那一次等）。
        if (partyPageKey != tag) {
            partyPageKey = tag
            partyPageAt = SystemClock.elapsedRealtime()
            delay(PartyPlan.LAB_PARTY_SETTLE_MS)
            return
        }
        if (SystemClock.elapsedRealtime() - partyPageAt < PartyPlan.LAB_PARTY_SETTLE_MS) {
            delay(200)
            return
        }
        // ★不按 nowMember 是否为空判断"是否进页"：空正好代表还没选人、正该开始填（PC 版同款，没有这条守卫）。
        if (partyCtxCache == null) partyCtxCache = PartyPlan.partyCtx()
        val ctx = partyCtxCache!!
        // ★把"本节点失败过"传给规划：个别关卡档会据此补 1 个坦克/兼职坦克（`tank_on_fail`，用户 2026-10-16）
        val plan = PartyPlan.partyPlan(st, pt, ctx, failed = (nodeFailed[nodeKey(st)] ?: 0) > 0)
        // 换目标/换就绪时才打日志（避免每轮刷屏，PC 同款）
        // ★去重键带上**节点**（2026-10-16）：原来只有"done|目标"，于是**不同节点只要结论相同就一行都不打**
        //   —— 实机就这样：进了编组页、按"目标已一致"直接开打，日志里却完全看不出来，
        //   用户看到的是"进战斗却没编组"（13:57 那次 questId=770110901 就是）。
        val sig = "${nodeKey(st)}|${plan.done}|${plan.target}"
        if (sig != partyPlanSig) {
            partyPlanSig = sig
            plan.lines.forEach { AppState.addLog(it) }
            // ★组队记录（2026-10-24 用户）：每次**结论变化**写一行 JSON（同一节点同一结论不重复写，
            //   换局/换节点会再写）。run_id 全球唯一（stage|时间|随机），多台设备合并不会撞号。
            //   空队/池空时 plan.runRec 为 null ⇒ 不写（与 PC log_run 一致）。
            plan.runRec?.let { rec ->
                val rid = TeamRunLog.append(rec)
                if (rid != null) {
                    AppState.addLog("组队记录 → ${TeamRunLog.FILE_NAME}（run_id=$rid）")
                }
            }
        }
        // ★算不出目标（放宽属性限定后仍空）→ 计数，连续 5 轮按**失败策略**处理
        //   （2026-10-19 用户："和战斗失败一样处理，如果写的重开就重开"）——不再无限停在编组页
        if (!plan.ok && plan.target.isEmpty()) {
            val nk = nodeKey(st)
            val n = (noPlanTries[nk] ?: 0) + 1
            noPlanTries[nk] = n
            if (n >= 5) {
                noPlanTries.remove(nk)
                when (failStrategy) {
                    RerollSettings.FAIL_RETREAT -> {
                        AppState.addLog("[✗] 编组页连续 $n 轮算不出目标 → 按策略「撤退重跑」：本局作废，自动开下一局")
                        retreatRequested = true
                        return
                    }
                    RerollSettings.FAIL_WAIT -> {
                        AppState.addLog("[!] 编组页连续 $n 轮算不出目标 → 等待接手（本节点转人工）")
                        manualTeamNodes.add(nk)
                        return
                    }
                    else -> {
                        AppState.addLog("[✗] 编组页连续 $n 轮算不出目标 → 停手")
                        stopRequested = true
                        return
                    }
                }
            }
        }
        // ★悬浮窗：编队页显示**目标队五个成员名**（2026-10-17 用户："编队：xxxxx（显示五个成员名字）"）。
            //   名字取自名册（`uname`），查不到的人显示 id —— 比空白更能说明"编了谁"。
            if (plan.target.isNotEmpty()) {
                val names = plan.target.joinToString(" ") { u ->
                    val n = charaPickRef?.uname(u)
                    if (n.isNullOrBlank() || n == "?") "#$u" else n
                }
                OverlayStatus.setState("编队：$names")
            }
            // ★编组页顶部"队伍1/2/3"页签候选（名字还没定）——一页报一次，用来接"直接切队撤人"
            pt.optJSONArray("tabCand")?.let { tc ->
                if (tc.length() > 0) AppState.addLog("编组页页签候选 = $tc")
            }
        // ── ★"眼前这一队还编不编"（PC `party_fill_ok`(2579) 同款；用户 2026-10-16 定的分层策略）──
        //   5 层 Boss 默认编满 3 队；3 层/其它层按初始策略只编第 1 队（失败过才补后备队）。
        //   ★不编的那一队 ⇒ **放行**：空队也点「决定」走过去（游戏允许"有未编组的队伍也能挑战"），
        //     否则会卡在第 2 队等一个永远等不到的"编好"。
        //   ★分层名单在 [PartyPlan.LAB_BOSS_FILL_ALL_AREAS]；与 PC `LAB_BOSS_FILL_ALL_AREAS` 同步。
        val isBoss = st.optInt("panelType") == 8 || st.optInt("statusType") == 6
        val area = (st.opt("area") as? Number)?.toInt() ?: -1
        val fillAll = isBoss && area in PartyPlan.LAB_BOSS_FILL_ALL_AREAS
        val nk = nodeKey(st)
        val dic = pt.optJSONObject("partyDic")
        // ── ★Boss 页：先把**继承来的 2/3 队残队腾空**（用户 2026-10-16）────────────────────────
        //   为什么：Boss 页的队伍是**上一关继承来的**（5 层继承 3 层），而 3 层"失败过才补后备队"留下的
        //   2/3 队会一直占着角色 —— 缩池会把 `partyDic` 里"别队的人"从本队池里剔掉（非 Boss 关只影响
        //   显示，Boss 关就会**阻碍第 1 队重算选人**，用户原话"可能会阻碍 5 层的编队选人"）。
        //   做法：点顶部页签 `PartsPartySelectButton{N}` **直接切队**（右下角那颗只是"下一页"，走到
        //   最后一队会变成「挑战」，撤不了别队）→ 把 2/3 队的人全撤下 → 切回队伍1。
        //   每节点只做一次；做完 `return`，下一轮拿干净状态重新算计划/编队。
        if (isBoss && partyCleaned.add(nk)) {
            val tabs = pt.optJSONObject("partyTabs")
            val nspNow = (pt.opt("nowSelectParty") as? Number)?.toInt() ?: 0
            val others = (1..2).filter { PartyPlan.partyDicN(dic, it) > 0 }   // 队伍2=idx1、队伍3=idx2
            if (tabs != null && (others.isNotEmpty() || nspNow > 0)) {
                for (i in others) {
                    val tc = tabs.optJSONObject("${i + 1}") ?: continue
                    if (tc.opt("x") == null) continue
                    AppState.addLog(
                        "编队：Boss 页「队伍${i + 1}」是继承来的、还有 ${PartyPlan.partyDicN(dic, i)} 人 → " +
                            "切过去腾空（免得占着角色）",
                    )
                    tap(input, tc.optInt("x"), tc.optInt("y"), "切到队伍${i + 1}")
                    delay(PartyPlan.LAB_PARTY_SWITCH_MS)
                    clearPartyTeam(input, i + 1)
                }
                val t1 = tabs.optJSONObject("1")
                if (t1 != null && t1.opt("x") != null) {
                    tap(input, t1.optInt("x"), t1.optInt("y"), "切回队伍1")
                    delay(PartyPlan.LAB_PARTY_SWITCH_MS)
                }
                AppState.addLog("编队：腾空完成 → 回队伍1 按算法重算")
                return
            }
        }
        val (fillOk, fillWhy) = PartyPlan.partyFillOk(
            isBoss = isBoss,
            curN = plan.cur.size,
            dic = dic,
            nsp = pt.opt("nowSelectParty"),
            failed = (nodeFailed[nk] ?: 0) > 0,
            already = !(partyTeams[nk]?.isEmpty() ?: true),
            size = PartyPlan.LAB_PARTY_SIZE,
            maxParties = PartyPlan.LAB_BOSS_FILL_PARTIES,
            planDone = plan.done,
            fillAll = fillAll,
        )
        // 不编的那一队 = 允许空队直接"点过去"（PC `_boss_adv`(3635)）
        val bossAdv = isBoss && !fillOk
        if (isBoss && partyFillSaid.add("$nk|$fillOk")) {
            val nsp = (pt.opt("nowSelectParty") as? Number)?.toInt()
            AppState.addLog(
                "编队：" + (if (fillOk) "编" else "跳过") +
                    (if (nsp != null) "队伍${nsp + 1}" else "本队") +
                    (if (fillOk) "" else "（$fillWhy）") +
                    " ｜ 游戏里 队伍1 已 ${PartyPlan.partyDicN(dic, 0)} 人、" +
                    "编满 ${PartyPlan.partyFullN(dic, PartyPlan.LAB_PARTY_SIZE)} 队 ｜ " +
                    (
                        if (fillAll) "本层 $area 层 Boss：默认编满 ${PartyPlan.LAB_BOSS_FILL_PARTIES} 队"
                        else "初始策略：只编第 1 队（失败过才补后备队）"
                        ),
            )
        }
        // ① 自动组队：按批点完差异
        //   ★`fillOk=false`（= 这一队不该编）时**不编**，直接往下走「放行」，见上面与 [bossAdv]。
        if (PartyPlan.LAB_PARTY_TEAM_AUTO && fillOk && plan.ops.isNotEmpty()) {
            // ★这里以前是 `plan.ops.take(LAB_PARTY_BATCH_MAX)`（= 3）—— 那是 PC「最多连做几批」的常量，
            //   被当成"本轮最多点几个"用 ⇒ 一次计划 6 步时会被拆成两轮，"一次性点完"其实只点了 3 下
            //   （用户 2026-10-16 追问"加入可能一次性做不到？"时暴露）。现在**整批全点**：
            //   · 计划本身只含"当前可见"的差异（看不见的在 `plan.offscreen` 里、走滚动分支）；
            //   · 每轮都从**实际队伍**重新规划 ⇒ 不会多点、也不会漏点。
            val ops = plan.ops
            // ── ①a 撤人：**一次性算好位置、一把点完**（2026-10-16 用户：PC 就是这个做法）──────────
            //   ★顺序（用户 2026-10-16 实测，口径要对齐）：**站位的号从右往左数** —— 右边 = 1 号位、
            //   左边 = 5 号位；撤人时**其余人一律往右补位**，空位出现在**左侧（高号位）**。
            //   因此必须**从左到右撤**：先撤左边（高号位）的人，右边（低号位）那些还没撤的人坐标**不动**；
            //   反过来先右后左，同一张快照算出的坐标就被"补位"挪走了（越撤越偏）。
            //   ⚠**加入不是"填左侧空位"**（用户 2026-10-16 纠正）：新人站哪儿由**该角色自己的攻击范围**
            //   决定（游戏按攻击范围重排队伍）⇒ 所以加人的坐标**只能从"上面的选择区（一览）"取**
            //   —— 那是一块按角色排的静态网格，**位置不随队伍变化**（见 ①b）。
            //   PC 注释里的"最左先移"同此（见 `_party_clear_team`）。
            //   ⚠原来"每撤一个重读一次快照 + 批内自检"会把一批 5 下拖成 5 次 RPC + 5 次等待
            //   （用户："移除角色时是一个个的"）。撤完只做**一次**核对。
            val removes = ops.filter { it.action == "remove" }
            if (removes.isNotEmpty()) {
                val snap = AgentApi.labParty()
                if (snap == null) {
                    AppState.addLog("[!] 撤人：读不到编队页快照 → 本轮不撤，下轮再试")
                    delay(PartyPlan.LAB_PARTY_BATCH_SETTLE)
                    return
                }
                val nowIds = partyNowIds(snap)
                // ★顺序**照计划来**（PartyPlan 已按「攻击范围从大到小」排好 = 站位最左 → 最右）。
                //   原来这里又按 x 升序重排了一遍：一旦坐标取自"角色一览"（x 是网格位置、与站位无关），
                //   就会把顺序打乱 ⇒ 先撤了右边、左边的人被补位右移，后面的坐标全偏（2026-10-18 回归）。
                val todo = removes.filter { it.unit in nowIds }
                    .mapNotNull { op -> findUnitCoord(snap, op.unit, op.via)?.let { op to it } }
                if (todo.isEmpty()) {
                    AppState.addLog("[!] 撤人：快照里找不到槽位坐标（${removes.size} 个目标）→ 下轮再试")
                } else {
                    AppState.addLog("编队：撤人 ${todo.size} 个（一次性点完，不再一个个重读快照）")
                    for ((op, c) in todo) {
                        tap(input, c.first, c.second, "编队：remove ${op.unit}")
                        delay(PartyPlan.LAB_PARTY_TAP_MS)
                    }
                    delay(PartyPlan.LAB_PARTY_BATCH_SETTLE)
                }
            }
            // ── ①b 加入：**也一次性点完**（用户 2026-10-16：加人不改变"选择区"里其他卡的位置 ——
            //   选择区是按角色排的静态网格、与队伍无关；PC 注释实测"空队与满队两次坐标一致"）
            //   ★注意：**不是**"填左侧空位"——新人站队伍里哪儿由它的**攻击范围**决定（游戏自己重排），
            //   所以我们只点"选择区"里的卡，队伍那侧的位置与我们无关 ✓────────────────────────────────
            //   ★找不到坐标的（一览里滚出可见区）**不当错误**：留给下一轮的滚动分支去滚出来。
            val adds = ops.filter { it.action == "add" }
            if (adds.isEmpty()) {
                delay(PartyPlan.LAB_PARTY_BATCH_SETTLE)
                return
            }
            val snap2 = AgentApi.labParty()
            if (snap2 == null) {
                AppState.addLog("[!] 加入：读不到编队页快照 → 本轮不加，下轮再试")
                delay(PartyPlan.LAB_PARTY_BATCH_SETTLE)
                return
            }
            val nowIds2 = partyNowIds(snap2)
            // ★顺手用这张快照核对**撤人**生效没有（省掉单独一次读）：还留在队里就别再加人
            val stuckRm = removes.map { it.unit }.filter { it in nowIds2 }
            if (stuckRm.isNotEmpty()) {
                AppState.addLog(
                    "[!] 撤人核对：${stuckRm.joinToString(",")} 还在队里（点歪了？）→ 本轮不加人，下轮按实际队伍重算",
                )
                delay(PartyPlan.LAB_PARTY_BATCH_SETTLE)
                return
            }
            val addTodo = adds.filter { it.unit !in nowIds2 }                 // 已在队里的不必再点
                .mapNotNull { op -> findUnitCoord(snap2, op.unit, op.via)?.let { op to it } }
            val addSkipped = adds.filter { it.unit in nowIds2 }.map { it.unit }
            if (addSkipped.isNotEmpty()) {
                AppState.addLog("〔跳过〕加入 ${addSkipped.joinToString(",")}：快照里已在队里")
            }
            val noCoord = adds.map { it.unit }.filter { u ->
                u !in nowIds2 && addTodo.none { it.first.unit == u }
            }
            if (noCoord.isNotEmpty()) {
                AppState.addLog(
                    "… 加入：${noCoord.joinToString(",")} 本页没有可点坐标（不在可见区？）→ 交给滚动分支",
                )
            }
            if (addTodo.isNotEmpty()) {
                AppState.addLog("编队：加入 ${addTodo.size} 个（一次性点完，不再一个个重读快照）")
                for ((op, c) in addTodo) {
                    tap(input, c.first, c.second, "编队：add ${op.unit}")
                    delay(PartyPlan.LAB_PARTY_TAP_MS)
                }
            }
            delay(PartyPlan.LAB_PARTY_BATCH_SETTLE)                  // 等游戏把队伍真正更新
            // ★**整批只做一次核对**（撤的人是否真出队、加的人是否真入队）—— 取代原来"每个 op 一次读"的
            //   批内自检；哪一下没生效会明确列出来，下一轮按实际队伍重算。
            val after = AgentApi.labParty()?.let { partyNowIds(it) }
            if (after != null) {
                val badRm = ops.filter { it.action == "remove" && it.unit in after }.map { it.unit }
                val badAdd = addTodo.map { it.first.unit }.filter { it !in after }
                if (badRm.isNotEmpty() || badAdd.isNotEmpty()) {
                    AppState.addLog(
                        "[!] 编队核对：撤了还在=[${badRm.joinToString(",")}]｜加了没成=[${badAdd.joinToString(",")}] " +
                            "→ 下轮按实际队伍重算",
                    )
                }
            }
            return
        }
        // ①.5 ★滚动角色一览，把"在页内全量、但不在可见区"的缺员滚进屏（PC `_do_party_scroll`(3217) 同款）。
        //   ★只在"没有可见可点项"时滚 —— 每轮先把裁剪带内能点的点完，再滚两行找屏外的人。
        //   ★这是 Android 侧**一直缺的一步**：`PartyPlan` 把 `offscreen` 算出来了却没人消费
        //     ⇒ 缺员永远加不进来（实机 2026-10-16：Boss_306604 那场 134901/129001 卡在"需滚动"，
        //     队伍只加到 3 人，用户反馈"滑动不好使"）。
        if (PartyPlan.LAB_PARTY_TEAM_AUTO && fillOk && plan.ops.isEmpty() && plan.offscreen.isNotEmpty()) {
            val ps = partyScroll.getOrPut(tag) { PartyScroll() }
            val vis = scrollVisIds(pt)
            if (ps.lastVis != null && vis == ps.lastVis) ps.still++ else ps.still = 0
            ps.lastVis = vis
            val off = plan.offscreen.filter { it.unit !in ps.noScroll }
            when {
                // ★唯一停机判据：连续两次滚完"屏上图标集合"没变 ⇒ 这些缺员在本页翻不到（PC 同款）。
                //   **不设滚动次数上限**（用户 2026-10-16）：次数到顶并不代表"翻不出来"，只有"滚了屏上没变化"
                //   才是真的到头了；次数上限只会把还能滚的情况提前掐掉。
                ps.still >= 2 -> {
                    ps.noScroll.addAll(plan.offscreen.map { it.unit })
                    AppState.addLog(
                        "编队：连滚两次屏上没变化 ⇒ 这些缺员在本页翻不到（${plan.offscreen.map { it.unit }}）" +
                            "→ 不再滚，交给算法（可能缩队）",
                    )
                }
                off.isNotEmpty() -> {
                    val dir = if (off.any { it.dir == "down" }) "down" else "up"
                    if (scrollPartyList(input, pt, dir)) {
                        ps.count++
                        if (ps.count <= 2 || ps.count % 10 == 0) {
                            AppState.addLog(
                                "编队：滚动角色一览找缺员（第 ${ps.count} 次，方向=${if (dir == "down") "下" else "上"}，" +
                                    "当前可见 ${vis.size} 张）",
                            )
                        }
                    }
                    delay(PartyPlan.LAB_PARTY_SCROLL_SETTLE)   // ★等滚动动画停住再点，否则会点到旧位置那张卡
                }
            }
            return
        }
        // ② 已就绪（或"这一队不编 ⇒ 放行"）→ 决定「特别装备」+ 点「决定/战斗开始」（防抖：gap 内不重复点）
        if ((plan.done || bossAdv) && PartyPlan.LAB_PARTY_AUTO) {
            val key = nk                               // nodeKey(st)，上面已算过
            val sess = exeqSess(key)
            // ★同一节点"重挑战但第 1 队换人" ⇒ 上次的 exDone 不能复用（2026-10-22）：
            //   装备是跟着人走的。失败重试时编队会按 `tank_on_fail` 补坦克/换人，若不重置就会
            //   静默沿用"已配好"直接开打。只判第 1 队（team1Sig 只记第 1 队；2/3 队本来就不覆盖基线）。
            val nspNow = (pt.opt("nowSelectParty") as? Number)?.toInt() ?: 0
            if (sess.exDone && nspNow <= 0 && plan.done && sess.team1Sig != null) {
                val sigNow = plan.cur.sorted()
                val sigOld = sess.team1Sig
                if (sigNow != sigOld) {
                    AppState.addLog("特别装备：第1队换人 → 重设一次（$sigOld → $sigNow）")
                    sess.exDone = false
                    sess.opened = false
                }
            }
            // ── ★特别装备：编队就绪后自动点开（LAB_EXEQUIP_AUTO）→ 之后由 handleExequip 单步接管 ──
            //   每页只点开一次（手动关掉后不再自动重开，避免来回拉扯）；
            //   优化：当前队 == 上一场同一套人 → 直接放行（装备没被动过，省掉这步）。
            //   ⚠ 只能比"相邻上一场"：中途换过队就可能把装备拆走了（PC 2026-10-13 纠正）。
            //   ★只有"真按目标编好的这一队"才走特别装备；放行的空队直接点过去（PC 同款）。
            if (plan.done && LAB_EXEQUIP_AUTO && !sess.opened && !sess.exDone) {
                val sig = plan.cur.sorted()
                if (LAB_EXEQUIP_SKIP_SAME && exequipLastTeam1 != null && sig == exequipLastTeam1) {
                    sess.exDone = true
                    AppState.addLog("特别装备：跳过（与上一场同一套队，装备没被动过）")
                } else {
                    val bt = pt.optJSONObject("buttons") ?: JSONObject()
                    val eq = bt.optJSONObject("exequip")
                    if (eq != null && eq.opt("x") != null) {
                        // 第 1 队（nowSelectParty<=0）才允许「使用其他角色已装备」；第 2/3 队取消勾选防抢装备
                        val nsp = pt.opt("nowSelectParty")
                        sess.exShare = !(nsp is Int) || nsp <= 0
                        if (sess.exShare) sess.team1Sig = sig
                        sess.opened = true
                        sess.openedAt = System.currentTimeMillis()
                        tap(input, eq.optInt("x"), eq.optInt("y"),
                            "打开特别装备${if (sess.exShare) "" else "（第2/3队：不共用其他角色装备）"}")
                        delay(PartyPlan.LAB_PARTY_TAP_MS)
                        // ★★点开「特别装备」后**本轮到此为止**（2026-10-17 用户指出）：
                        //   原来同一轮继续往下，又点了「战斗开始/决定」——而此刻已经在特别装备页上，
                        //   那个坐标处**没有按钮**（空点）；万一碰巧压在装备页某颗按钮上还会误操作 ✗。
                        //   下一轮由 handleExequip 接管，走完装备再回来开打（顺序本来就是这么设计的）。
                        return
                    } else {
                        // 编队已就绪却读不到「特别装备」按钮 → 等 EXEQ_OPEN_WAIT 后放行（别为装备卡住流程）
                        val nel = System.currentTimeMillis() - sess.openedAt
                        if (nel > EXEQ_OPEN_WAIT_MS) {
                            sess.exDone = true
                            AppState.addLog("    [!] 编队已就绪但读不到「特别装备」按钮，等了 ${nel / 1000}s → 跳过装备，直接准备开打")
                        }
                    }
                }
            }
            // ★顺序：组队就绪 → 特别装备走完（exDone）→ 才开打。
            //   ★"放行"（[bossAdv]：这一队不编、空队点过去）**不等特别装备** —— 上面那块被 `plan.done`
            //     挡着不会点开装备，`sess.exDone` 永远是假，再等它就**互相死锁**
            //     （2026-10-16 实测：5 层 Boss 游戏里已编满 3 队 ⇒ fillOk=false ⇒ 卡在编组页不动）。
            if (bossAdv || !LAB_EXEQUIP_AUTO || sess.exDone) {
                val curN = plan.cur.size
                // ★放行（Boss 超出要编的分队数）时**空队也允许点过去**（PC `_boss_adv`(3635)）：
                //   第 2/3 队是故意不编的，而游戏本身就允许"有未编组的队伍也能挑战"。
                if (!bossAdv && curN < PartyPlan.LAB_PARTY_MIN_SLOTS) {
                    AppState.addLog("战斗开始：当前只有 $curN 人 < 下限 ${PartyPlan.LAB_PARTY_MIN_SLOTS} → 不点")
                    return
                }
                val now = System.currentTimeMillis()
                if (now - partyDoneAt < PartyPlan.LAB_PARTY_TAP_GAP) {
                    delay(150)
                    return
                }
                partyDoneAt = now
                val bt = pt.optJSONObject("buttons") ?: JSONObject()
                val dec = bt.optJSONObject("decide")
                if (dec != null && dec.opt("x") != null) {
                    // ★这一队算确认了 → 记下来（下一队缩池的兜底；`already` 判据也用它）
                    if (plan.done && plan.target.isNotEmpty()) {
                        val lst = partyTeams.getOrPut(key) { mutableListOf() }
                        val t = plan.target.sorted()
                        if (t !in lst) lst.add(t)
                    }
                    weStartedFight.add(key)                  // 记"这局是我们点的战斗开始"
                    tap(
                        input, dec.optInt("x"), dec.optInt("y"),
                        if (bossAdv) "决定·跳过本队（$curN 人，允许空）" else "战斗开始",
                    )
                    delay(800)
                } else {
                    AppState.addLog("[!] 编队页找不到「战斗开始」按钮（ButtonDecide）")
                }
            }
        }
    }

    /**
     * 特别装备三连页：读出来 + 按 `LAB_EXEQUIP_AUTO` 走单步动作（PC `handle_exequip`）。
     * 调用方已在白名单之前拦截并 `continue`，这里直接驱动、不用担心被通用弹窗误点。
     * 多步在本轮内连推（`EXEQ_FAST_BUDGET_MS`），某步在"等页面变样"或流程结束即交回主循环。
     */
    private suspend fun handleExequip(input: InputBackend, st: JSONObject, top: String) {
        if (!LAB_EXEQUIP_AUTO) {
            AppState.addLog("    ⏸ 特别装备页：$top（LAB_EXEQUIP_AUTO=false → 不自动，等手动）")
            return
        }
        val key = nodeKey(st)
        val t0 = System.currentTimeMillis()
        val sess = exeqSess(key)
        while (System.currentTimeMillis() - t0 < EXEQ_FAST_BUDGET_MS) {
            val before = sess.n
            _exeqStep(input, st, top, key)
            if (sess.n == before || sess.exDone) break   // 这步没点（在等页面变）或流程结束 ⇒ 交回主循环
            delay(EXEQ_POLL_MS)
        }
    }

    /**
     * 特别装备流程的**单步**动作（PC `_exeq_step`）：一轮最多点一下；点完**等"页面变样"**再走下一步。
     * 顺序：① 点「自动装备」→ ② 若「全部」≠稀有度 点那一行 →（③ 选「稀有度」+ 确认）
     *       ② 若未勾 点复选框 → 点「确认」→ ① 按 isChangeExtraEquip() 点「装备确定」；
     *       若点过自动装备后**仍然没有改动** → 点「取消」退出。
     */
    private suspend fun _exeqStep(input: InputBackend, st: JSONObject, top: String, key: String) {
        val sess = exeqSess(key)
        if (sess.n >= EXEQ_MAX_STEPS) {
            if (sess.n == EXEQ_MAX_STEPS) {
                sess.n += 1
                AppState.addLog("    [!] 特别装备流程走了 $EXEQ_MAX_STEPS 步仍未结束 → 停手")
            }
            return
        }
        val x = AgentApi.labExEquip(true) ?: return        // 要点了才取"带按钮坐标"的读数
        if (jStr(x, "top") != top) return                  // 页面已经变了 → 本轮不点（jStr 防 top:null 变 "null"）
        val p = x.optJSONObject("page") ?: JSONObject()
        val sig = _exeqSig(top, p)
        sess.pendSig?.let { ps ->
            val el = System.currentTimeMillis() - sess.pendAt
            if (ps == sig && el < EXEQ_WAIT_MAX_MS) return  // ★上一步点完页面还没变 → 继续等，不抢着点
            if (ps == sig) AppState.addLog("    [!] 特别装备上一步 ${el / 1000}s 后页面没变 → 重判")
            sess.pendSig = null
        }
        var what: String? = null
        var tapBtn: JSONObject? = null
        var markAuto = false
        var isFinal = false
        when (top) {
            "party" -> {
                val ch = p.opt("change")
                when {
                    ch == true -> { what = "装备确定"; tapBtn = _exeqBtn(p.optJSONArray("buttons"), "ThreeButtonRight"); isFinal = true }
                    ch == false -> {
                        if (sess.auto) { what = "取消（自动装备后无改动）"; tapBtn = _exeqBtn(p.optJSONArray("buttons"), "ThreeButtonLeft"); isFinal = true }
                        else { what = "自动装备"; tapBtn = _exeqBtn(p.optJSONArray("buttons"), "ThreeButtonCenter"); markAuto = true }
                    }
                    else -> { if (sess.n < 2) AppState.addLog("    [!] ① 读不到 isChangeExtraEquip → 本轮不点"); return }
                }
            }
            "setting" -> {
                val labels = p.optJSONArray("labels")
                if (labels == null || labels.length() == 0) return
                val share = sess.exShare
                when {
                    labels.optString(0, "") != "稀有度" -> {
                        what = "「全部」行(当前=${labels.optString(0, "?")}) 进「优先属性值」"
                        tapBtn = p.optJSONArray("prioBtn")?.optJSONObject(0)
                    }
                    p.opt("checked") == false && share -> { what = "复选框(补勾)"; tapBtn = p.optJSONObject("checkBtn") }
                    p.opt("checked") == true && !share -> { what = "复选框(取消勾选·不抢别队装备)"; tapBtn = p.optJSONObject("checkBtn") }
                    p.opt("checked") != null -> { what = "确认"; tapBtn = _exeqBtn(p.optJSONArray("buttons"), "TwoButtonRight") }
                    else -> { if (sess.n < 2) AppState.addLog("    [!] ② 读不到复选框状态 → 本轮不点"); return }
                }
            }
            "sort" -> {
                val tg = p.optJSONArray("toggles") ?: org.json.JSONArray()
                var hit: JSONObject? = null
                for (i in 0 until tg.length()) {
                    val t = tg.optJSONObject(i)
                    if ((t?.optString("label") ?: "") == "稀有度") { hit = t; break }
                }
                if (hit == null) { AppState.addLog("    [!] ③ 页里找不到「稀有度」选项 → 不点"); return }
                var cur: JSONObject? = null
                for (i in 0 until tg.length()) {
                    val t = tg.optJSONObject(i)
                    if (t?.optBoolean("sel") == true) { cur = t; break }
                }
                if (cur != null && (cur.optString("label") ?: "") == "稀有度") {
                    what = "确认"; tapBtn = _exeqBtn(p.optJSONArray("buttons"), "TwoButtonRight")
                } else {
                    what = "「稀有度」"; tapBtn = hit
                }
            }
        }
        if (what == null || tapBtn == null || tapBtn.opt("x") == null) return
        sess.n += 1
        if (markAuto) sess.auto = true
        sess.pendSig = sig
        sess.pendAt = System.currentTimeMillis()
        if (isFinal) {
            sess.exDone = true
            // 只有队伍1（exShare）配完才更新基线（PC：Boss 只看队伍1，队伍2/3 不覆盖）
            if (sess.exShare) {
                val sig1 = sess.team1Sig
                if (!sig1.isNullOrEmpty()) exequipLastTeam1 = sig1
            }
        }
        tap(input, tapBtn.optInt("x"), tapBtn.optInt("y"), "特别装备·${tapBtn.optString("name")}")
        AppState.addLog("特别装备：$what")
        delay(EXEQ_SETTLE_MIN_MS)
    }

    /** 角色一览里"当前屏上"的 unitId（升序）—— 只用来判"滚完到底动没动"（PC `_vis` 同款：不过滤 vis） */
    private fun scrollVisIds(pt: JSONObject): List<Int> {
        val a = pt.optJSONArray("scrollIcons") ?: return emptyList()
        val out = ArrayList<Int>(a.length())
        for (i in 0 until a.length()) {
            val u = (a.optJSONObject(i)?.opt("unitId") as? Number)?.toInt() ?: continue
            if (u != 0) out.add(u)
        }
        out.sort()
        return out
    }

    /**
     * PC `_do_party_scroll`(3217)：把「角色一览」拖两行，把缺员滚进可见区。
     *
     * ★三个关键点：
     *   · **行高不写死**：从 `scrollIcons` 的真实屏幕坐标反推（按列量化 60px 分组、取相邻行 y 差的
     *     **最小高频间距**并量化到 8px 桶，只收 40~400 的合理区间）⇒ 分辨率/布局变了也能重算；
     *     图标不足时退回"裁剪带高 ÷ 可见行数"。
     *   · **起点在带内、终点不夹**（2026-10-16）：起点取行进侧边缘（保证手指落在列表上），
     *     终点只留 60px 屏幕边距 —— 用户实测"终点出框没有影响"，这样"两行"的距离永远给足。
     *     以前终点夹在带内 ⇒ 带高 587px、行距 217px 时最多只走 1.2 行（"说两行、实际一行"）。
     *   · 每次滑动的"行距/想要几行/实际几行"都打进日志，方便对账。
     * @return true = 滑动手势已真下发（调用方据此计数）
     */
    private suspend fun scrollPartyList(input: InputBackend, pt: JSONObject, dir: String): Boolean {
        val icons = ArrayList<Pair<Int, Int>>()
        val a = pt.optJSONArray("scrollIcons")
        for (i in 0 until (a?.length() ?: 0)) {
            val c = a!!.optJSONObject(i) ?: continue
            if (c.opt("vis") === JSONObject.NULL || c.opt("vis") == false) continue
            val x = (c.opt("x") as? Number)?.toInt() ?: continue
            val y = (c.opt("y") as? Number)?.toInt() ?: continue
            icons.add(x to y)
        }
        if (icons.isEmpty()) {
            AppState.addLog("编队：滚动时读不到角色一览图标（scrollIcons 空/全被裁）→ 不滚")
            return false
        }
        // ── 行高反推（PC `_party_row_height`(3185)）──
        var rh = 0.0
        if (icons.size >= 2) {
            val cols = HashMap<Int, MutableList<Int>>()
            for ((x, y) in icons) cols.getOrPut(Math.round(x / 60f) * 60) { mutableListOf() }.add(y)
            val gaps = ArrayList<Int>()
            for (ys in cols.values) {
                val s = ys.toSortedSet().toList()
                for (i in 0 until s.size - 1) {
                    val g = kotlin.math.abs(s[i + 1] - s[i])
                    if (g in 40..400) gaps.add(g)                     // 合理行高区间，过滤瞬态/异常
                }
            }
            if (gaps.isNotEmpty()) {
                val buckets = HashMap<Int, Int>()
                for (g in gaps) {
                    val k = Math.round(g / 8f) * 8                    // 量化到 8px 桶，容忍几像素噪声
                    buckets[k] = (buckets[k] ?: 0) + 1
                }
                // ★真行距 = **反复出现的间距里"最小"的那个**（2026-10-16 修）：
                //   同一列相邻两张卡的行距必然是行距的整数倍（相邻是 1×；中间缺一行就是 2×），
                //   所以"最小的高频间距"恒等于真行距 —— 偶尔缺一行也不会把行距算成 2×。
                //   ⚠原来是"取众数 + >200px 就折半"的安全网：本游戏卡片大、行距实测 ≈217px，
                //   217 > 200 ⇒ 被折半成 108 ⇒ 下面 `2*rh` 恰好只走**一行**
                //   （用户 2026-10-16："说是一次两行，实际只有一行"）。
                val maxCnt = buckets.values.maxOrNull() ?: 0
                val cand = buckets.entries
                    .filter { it.value >= maxOf(2, maxCnt / 3) }      // 高频：出现 ≥2 次且不少于最多的 1/3
                    .minByOrNull { it.key }
                rh = (cand?.key ?: buckets.maxByOrNull { it.value }?.key ?: 0).toDouble()
            }
        }
        val band = pt.optJSONObject("band")
        if (rh <= 0.0 && band != null) {                               // 兜底：裁剪带高 ÷ 可见行数（同样来自 UI）
            val rows = icons.map { Math.round(it.second / 60f) }.toSet().size
            val bh = (band.optInt("bottom") - band.optInt("top")).toDouble()
            if (rows >= 1 && bh > 0) rh = bh / rows
        }
        if (rh <= 0.0) rh = 140.0                                      // 极端兜底（正常走不到：至少该有图标）
        rh = minOf(400.0, maxOf(50.0, rh))                             // 只夹异常值（上限放到 400：本游戏行距 ≈217）

        // ── 拖动几何：全在裁剪带内（PC 3250~3259）──
        var lo: Double
        var hi: Double
        val cx: Double
        if (band != null) {
            cx = (band.optInt("left") + band.optInt("right")) / 2.0
            lo = band.optInt("top") + 40.0
            hi = band.optInt("bottom") - 40.0
        } else {
            cx = scrW / 2.0
            lo = 60.0
            hi = scrH - 60.0
        }
        if (hi <= lo) {                                                // 裁剪带异常窄 → 退回整屏安全区
            lo = scrH * 0.08
            hi = scrH * 0.92
        }
        // ★起点取**行进方向那一侧**的边缘（2026-10-16 修）：PC 3250~3259 取的是带**中点**，
        //   而本游戏带高只有 ≈587px、行距 ≈217px ⇒ 中点起手最多只能走约 1.2 行，
        //   "两行"的距离根本装不下（这是"说两行、实际一行"的**第二个**原因，与上面行距被折半叠加）。
        //   从边缘起手，两行的距离才放得进带内；起点仍在带内（留 40px 边）⇒ 手指一定落在列表上。
        // ★终点**不再夹在裁剪带内**（用户 2026-10-16 实测：终点出带没有任何影响）——
        //   只保留"别滑出屏幕"这一道（留 60px），保证"两行"的距离**永远给足**。
        val want = SCROLL_ROWS * rh                                    // ★一次滚几行（见 [SCROLL_ROWS]）
        val sy: Double
        val ey: Double
        if (dir == "down") {                                           // 手指由下往上滑 → 内容下移、露出列表后面
            sy = hi
            ey = maxOf(60.0, sy - want)
        } else {                                                       // 手指由上往下滑 → 露出列表前面
            sy = lo
            ey = minOf(scrH - 60.0, sy + want)
        }
        val got = kotlin.math.abs(ey - sy)
        // ★时长按距离等比缩放（2026-10-16）：`LAB_PARTY_SCROLL_MS=350` 是照"两行≈240px"校准的
        //   （≈0.69 px/ms，PC 实测值）。距离放开后可能翻倍，若仍用 350ms，速度也翻倍
        //   ⇒ 会被识别成"甩动"、惯性滑过头。保持速度不变 ⇒ 拖到哪就是哪。夹在 250~800ms。
        val ms = (got / 0.69).toLong().coerceIn(250L, 800L)
        AppState.addLog(
            "编队：滚动（${if (dir == "down") "下" else "上"}）行距=${rh.toInt()}px ｜ " +
                "swipe(${cx.toInt()},${sy.toInt()})→(${cx.toInt()},${ey.toInt()})" +
                "＝${got.toInt()}px（约 ${"%.1f".format(got / rh)} 行；目标 ${"%.1f".format(SCROLL_ROWS)} 行＝${want.toInt()}px）" +
                "，时长 ${ms}ms" +
                if (got < want - 1.0) "　⚠ 屏幕边距拦住，没给够" else "",
        )
        return when (
            val r = input.swipe(cx.toInt(), sy.toInt(), cx.toInt(), ey.toInt(), ms)
        ) {
            is com.lmj.labyrinthauto.input.InputResult.Ok -> true
            else -> {
                AppState.addLog("编队：滚动失败（$r）")
                false
            }
        }
    }

    /**
     * 「清队」：把**当前所在队伍**的人全部撤下（整队腾空），不看算法目标。
     *
     * ★做法（用户 2026-10-16 简化）：**对着 1 号位连点 5 次**，点完读一次确认，而不是"每点一次
     *   重读快照看撤了谁/还剩谁" —— 撤掉一个人，后面的人会**左移补位**，所以同一个坐标连点就是
     *   从前往后清空；少了一堆 RPC，也不会因为"读到的名字没变就以为没撤掉"而犹豫。
     *   点完若还剩人（动画/漏点）就再补一轮，最多 3 轮。
     */
    private suspend fun clearPartyTeam(input: InputBackend, teamNo: Int) {
        repeat(3) { round ->
            val pt = AgentApi.labParty()
            if (pt == null) {
                AppState.addLog("[!] 清队伍$teamNo：读不到编队页快照 → 停止清这队")
                return
            }
            val left = partyNowIds(pt).size
            if (left == 0) return
            val c = partySlot1XY(pt)
            if (c == null) {
                AppState.addLog("[!] 清队伍$teamNo：读不到 1 号位坐标 → 停止清这队")
                return
            }
            val n = left.coerceAtMost(5)
            if (round == 0) AppState.addLog("清队伍$teamNo：对着 1 号位（最右）连点 $n 次（撤下后后面的人往右补位）")
            for (i in 1..n) {
                tap(input, c.first, c.second, "清队伍$teamNo·1 号位（$i/$n）")
                delay(PartyPlan.LAB_PARTY_TAP_MS)
            }
            delay(PartyPlan.LAB_PARTY_BATCH_SETTLE)
            val after = AgentApi.labParty()?.let { partyNowIds(it).size } ?: -1
            if (after == 0) return
            AppState.addLog("清队伍$teamNo：点完还剩 $after 人 → 再补一轮（第 ${round + 2}/3 轮）")
        }
    }

    /** "1 号位"坐标 = 底部成员里**最靠右**那张卡。
     *  ★1 号位在**最右**（用户 2026-10-16 纠正；与 PC 实测一致：`130801@最右` 就是 1 号位）。
     *   撤掉 1 号位后，后面的人会**往右补位**，所以同一个坐标连点就是按站位从 1 号位往后清空。 */
    private fun partySlot1XY(pt: JSONObject): Pair<Int, Int>? {
        val a = pt.optJSONArray("icons") ?: return null
        var best: Pair<Int, Int>? = null
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            if (((c.opt("unitId") as? Number)?.toInt() ?: 0) == 0) continue
            val x = (c.opt("x") as? Number)?.toInt() ?: continue
            val y = (c.opt("y") as? Number)?.toInt() ?: continue
            val b = best
            if (b == null || x > b.first) best = x to y
        }
        return best
    }

    /** 编队页"当前成员"的 unitId 集合（`nowMember` 为准，空则退 `icons`）——批内自检用 */
    private fun partyNowIds(pt: JSONObject): Set<Int> {
        val out = mutableSetOf<Int>()
        val nm = pt.optJSONArray("nowMember")
        if (nm != null) {
            for (i in 0 until nm.length()) {
                (nm.opt(i) as? Number)?.toInt()?.let { if (it != 0) out.add(it) }
            }
        }
        if (out.isEmpty()) {
            val ic = pt.optJSONArray("icons")
            if (ic != null) {
                for (i in 0 until ic.length()) {
                    val u = (ic.optJSONObject(i)?.opt("unitId") as? Number)?.toInt()
                    if (u != null && u != 0) out.add(u)
                }
            }
        }
        return out
    }

    /**
     * 在编队快照里找某角色的**可点坐标**。
     *
     * ★`via` 决定先找哪个区域（**PC 3489 同款，两边不能混用**）：
     *   · `remove` → **下排槽位（`icons`，即"当前的成员"）优先** —— 撤人点的是底排那张卡，
     *     点角色一览里的同 id 卡**不会撤人**；
     *   · `add` → 角色一览（`scrollIcons`）优先，找不到才退槽位。
     *   ⚠2026-10-16 实机 bug（用户："已经满 5 人了还在尝试选其他人"）：这里原来**一律先看
     *   `scrollIcons`**，而撤人计划挂的是槽位坐标 (630,908)；执行时却把一览里同 id 卡的
     *   (643,782) 找出来点下去 —— 点在一览某张卡上，队伍纹丝不动 ⇒ 6 轮死循环。
     * ★一览里**滚出窗口**的卡，坐标是"列表内位置"、不是屏上位置（PC 也踩过"点 130801 落到
     *   y=731"）—— 只认 `vis` 不为 false 的卡；都没有就返回 null（交给下一轮的滚动/重算）。
     */
    private fun findUnitCoord(pt: JSONObject, unit: Int, via: String? = null): Pair<Int, Int>? {
        val order = if (via == "槽位") listOf("icons", "scrollIcons") else listOf("scrollIcons", "icons")
        for (arrName in order) {
            val arr = pt.optJSONArray(arrName) ?: continue
            for (i in 0 until arr.length()) {
                val c = arr.opt(i) as? JSONObject ?: continue
                if ((c.opt("unitId") as? Number)?.toInt() != unit) continue
                if (arrName == "scrollIcons") {
                    val vis = c.opt("vis")
                    if (vis === JSONObject.NULL || vis == false) continue   // 不在可见区 → 坐标不可信
                }
                val x = c.opt("x"); val y = c.opt("y")
                if (x != null && x !== JSONObject.NULL && y != null && y !== JSONObject.NULL) {
                    return c.optInt("x") to c.optInt("y")
                }
            }
        }
        return null
    }

    /**
     * PC `handle_quest_detail`(1233)：战斗格子预览页 → 点「挑战」。
     * ★「挑战」**只点一次**（`BATTLE_TAP_MAX`）：点完游戏会弹出"队伍编组"把预览页盖住，但 statusType/view
     * 都不变、连「查看地图」也还能被 find 到 ⇒ 判据全失效、会对着同一坐标反复点（而那坐标在上一层恰好是
     * 「战斗开始」）。所以点一次没进展就停手。
     */
    private suspend fun handleQuestDetail(
        input: InputBackend,
        st: JSONObject,
        tag: String,
        tries: MutableMap<String, Int>,
    ) {
        // ★策略=等待接手：本节点交给用户 → 预览页也不点「挑战」，等你开战
        val nk = nodeKey(st)
        if (nk in manualTeamNodes) {
            if (manualSaid.add("$nk#qd")) {
                AppState.addLog("[手动] 节点 ${jStr(st, "blockId")}：预览页不点「挑战」，等你点开战")
                OverlayStatus.setState("手动组队：等你开战")
            }
            return
        }
        val n = bump(tries, tag)                        // 本页轮数（换页清零）
        // ★「挑战」**只真点一次**（PC `BATTLE_TAP_MAX`）：点完游戏会弹"队伍编组"把预览页盖住，而
        //   statusType/view 都不变、连「查看地图」也还能被 find 到 ⇒ 判据全失效、会对着同一坐标反复点
        //   （那坐标在上一层恰好是「战斗开始」）。
        // ★但**"没找到按钮"不算一次尝试**（按钮/卡片还在入场动画里、坐标还在屏外）——否则动画期间的
        //   一次落空就把唯一机会用掉、之后永远不再点 = 卡死在预览页（rel 的 [handleRelicChoice] 同款取舍）。
        val taps = tries["QD_TAPS"] ?: 0
        if (taps >= BATTLE_TAP_MAX) {
            // ★带证据地重试（2026-10-16）：只在"确认编组页没出现"时才再点一次 —— 那说明上一次是空点。
            val nowMs = SystemClock.elapsedRealtime()
            val tooMany = taps >= BATTLE_TAP_MAX_TOTAL
            val tooSoon = nowMs - qdTapAt < BATTLE_RETAP_GAP_MS
            var partyOn = false
            if (!tooMany && !tooSoon) {
                partyOn = AgentApi.labQuestDetail(BATTLE_START_BTN)?.optBoolean("party", false) == true
            }
            if (tooMany || tooSoon || partyOn) {
                // 点完「挑战」这页本来还要停好几秒才切走（实测 6.5s）⇒ 短等待不输出
                if (n == BATTLE_IDLE_WARN || n % 50 == 0) {
                    AppState.addLog("… 已点过「$BATTLE_START_BTN」、本页仍在上层（第 $n 轮；已点 $taps 次）")
                }
                return
            }
            AppState.addLog(
                "[!] 预览页仍在、编组页**没**出现 ⇒ 上一次「$BATTLE_START_BTN」多半是空点 → " +
                    "隔 ${BATTLE_RETAP_GAP_MS / 1000}s 重试（第 ${taps + 1}/$BATTLE_TAP_MAX_TOTAL 次）",
            )
        }
        // ★一次调用拿到"最上层对话框名 + 挑战坐标 + 编组页零件在不在"：agent 只走**最上层对话框子树**
        //   （几十个节点）。原来这里是 partyPageOnTop()(2 次整树 find) + findNode()(1 次整树 find)，
        //   整树 661 节点 ≈2s/次 ⇒ 一轮 6s+，就是"看七八秒敌人"的元凶。
        //   ★2026-10-20：对话框子树里没有「挑战」按钮（它常挂 **View 层**）时，agent 退到
        //   `labBtn` 路径缓存——上局学的路径从 [LabBtnStore] 回喂（免学习），新学的落盘。
        val qKey = "QDStart|$BATTLE_START_BTN"
        val qKnown = appCtx?.let { LabBtnStore.get(it, qKey) }
        val qd = AgentApi.labQuestDetail(BATTLE_START_BTN, qKnown?.first, qKnown?.second)
        if (qd?.has("learnedRoot") == true) {
            val c = appCtx
            val pa = qd.optJSONArray("learnedPath")
            if (c != null && pa != null) {
                val list = ArrayList<Int>()
                for (i in 0 until pa.length()) list.add(pa.optInt(i))
                LabBtnStore.put(c, qKey, qd.optString("learnedRoot"), list)
                AppState.addLog("预览页「挑战」按钮路径已学成落盘（下局起直达，不再全树 find）")
            }
        }
        val qTop = qd?.let { jStr(it, "top") } ?: ""
        if (n == 1) {
            AppState.addLog(
                "预览页：一次读 ${qd?.optInt("_ms") ?: -1}ms（最上层=$qTop，子树节点=${qd?.optInt("nodes") ?: -1}）",
            )
        }
        // 编组页零件在屏 ⇒ 预览页其实被编组页盖住（PC 1247：这时按预览页坐标点会落到「战斗开始」）→ 不点
        if (qd != null && qd.optBoolean("party", false)) {
            if (tries.getOrPut("QD_PARTY_SAID", { 0 }) == 0) {
                tries["QD_PARTY_SAID"] = 1
                AppState.addLog("… 预览页被「队伍编组页」盖住了（最上层=$qTop）→ 不点挑战，等编组页 handler")
            }
            return
        }
        val startObj = qd?.optJSONObject("start")
        var p: Pair<Int, Int>? = startObj?.let { s ->
            if (s.has("x") && s.has("y")) s.optInt("x") to s.optInt("y") else null
        }
        // ★路径命中的可观测性（2026-10-20 用户："每个学过的按钮都有日志吗"）：每局第一次打一条
        if (p != null && startObj != null && tries.getOrPut("QD_HIT_SAID", { 0 }) == 0) {
            tries["QD_HIT_SAID"] = 1
            val hit = startObj.optString("hit")
            // ★**临时验证日志**（2026-10-20 用户：验证一个版本后摘除）：统一带 `[路径验证]` 前缀，
            //   将来 grep 这个前缀就能找全一次摘干净
            AppState.addLog(
                "[路径验证] " + when (hit) {
                    "学习" -> "预览页「挑战」：路径本次学成（已落盘，下局回喂直达）"
                    "路径" -> "预览页「挑战」：走学到的路径直达（零遍历，几十 ms）"
                    else -> "预览页「挑战」：对话框子树内命中（不走路径缓存）"
                },
            )
        }
        if (p == null) {
            // ★兜底（正常走不到，只有页面结构变了才走这里）：子树里没找到 → 退回整树 find（准但慢），
            //   并补上旧版"编组页盖住"的交叉验证（同 `findTextIn` 的"先子树、找不到再整树"策略）。
            if (partyPageOnTop()) {
                if (tries.getOrPut("QD_PARTY_SAID", { 0 }) == 0) {
                    tries["QD_PARTY_SAID"] = 1
                    AppState.addLog("… 预览页被「队伍编组页」盖住了（pageTop 没认出来）→ 不点挑战，等编组页 handler")
                }
                return
            }
            // ★坐标缓存（2026-10-20）：这个按钮不在任何可 descend 的根里（qdDiag=no-canvas-root），
            //   但坐标实测不动（BtnProbe Δmax=0px）⇒ 存"坐标+屏幕尺寸"，命中直接点；
            //   分辨率变了缓存自动失效（getXY 返回 null），点错由"点了没反应重试"兜底。
            val dm = appCtx?.resources?.displayMetrics
            if (dm != null) {
                val xk = "xy|qd|$BATTLE_START_BTN"
                LabBtnStore.getXY(appCtx!!, xk, dm.widthPixels, dm.heightPixels)?.let { xy ->
                    p = xy
                    if (tries.getOrPut("QD_XY_SAID", { 0 }) == 0) {
                        tries["QD_XY_SAID"] = 1
                        AppState.addLog("[路径验证] 预览页「挑战」：用缓存坐标直接点（${xy.first},${xy.second}）")
                    }
                }
            }
            if (p == null) {
                // ★先限根 `dialog` 找一次（几十 ms ✓），没有再全树兜底（2.5~2.9s ✗，本局走了 3 次）
                val fp = AgentApi.findText(BATTLE_START_BTN, "dialog")?.let { d ->
                    if (d.has("x") && d.has("y")) d.optInt("x") to d.optInt("y") else null
                } ?: findNode(BATTLE_START_BTN)
                // 全树找到 → 存坐标（下次直达）
                val c = appCtx
                if (fp != null && dm != null && c != null) {
                    LabBtnStore.putXY(c, "xy|qd|$BATTLE_START_BTN", fp.first, fp.second, dm.widthPixels, dm.heightPixels)
                    AppState.addLog("[路径验证] 预览页「挑战」：坐标已学成落盘（下局直接点）")
                }
                p = fp
            }
        }
        val pFinal = p
        if (pFinal != null && tries.getOrPut("QD_TAPS", { 0 }) < BATTLE_TAP_MAX_TOTAL) {
            tries["QD_TAPS"] = taps + 1
            qdTapAt = SystemClock.elapsedRealtime()
            weStartedFight.add(nodeKey(st))            // 记下"这场是我们开的"（PC `_LAST_FIGHT_TEAM` 的简化）
            tap(input, pFinal.first, pFinal.second, "开打（questId=${jStr(st, "questId")}）")
            return
        }
        // 没点到（按钮还没落位 / 真找不到）：只报进度，**不计尝试**，下轮再来
        if (n == 1 || n == 5 || n % 30 == 0) {
            // ★qdDiag（2026-10-20）：agent 端路径缓存学习失败的透传原因
            //   （not-found = view/dlg/canvas 三个根的子树里都没有这个名字 ⇒ 要看按钮挂在哪）
            val diag = qd?.optString("qdDiag").orEmpty()
            val errS = qd?.optString("err")?.takeIf { it.isNotEmpty() }?.let { "，err=$it" } ?: ""
            val diagS = diag.takeIf { it.isNotEmpty() }?.let { "，qdDiag=$it" } ?: ""
            AppState.addLog(
                "[!] 预览页上还没拿到「$BATTLE_START_BTN」可点坐标（第 $n 轮，最上层=$qTop，" +
                    "子树节点=${qd?.optInt("nodes") ?: -1}$errS$diagS）",
            )
        }
        if (n >= QD_NOT_FOUND_MAX) {
            AppState.addLog("[✗] 预览页在、$n 轮都拿不到「$BATTLE_START_BTN」→ 判定异常，停手")
            stopRequested = true
        }
    }

    /** 本节点钥匙（PC `(blockId, area)`） */
    private fun nodeKey(st: JSONObject): String {
        val b = (st.opt("blockId") as? Number)?.toInt() ?: -1
        val a = (st.opt("area") as? Number)?.toInt() ?: -1
        return "$a/$b"
    }

    /** ★结算按钮"直接读"在同一轮内重试几次（2026-10-17 用户："多读几次，避免一次没读到直接降级扫描"） */
    private const val RESULT_READ_TRIES = 3
    private const val RESULT_READ_GAP_MS = 200L
    /**
     * ★"直接读"重试的**时间预算**（2026-10-22 卡死修复）：次数上限之外再加一道时间闸。
     *
     * 结算页刚 Instantiate、结算动画还在播的那一刻，按钮节点还没 active ⇒ 每一次"直接读"都必然
     * 全失败，而其中 `battleResultBtn` 一次就要遍历一遍 DialogArea（实测 1~2s）。以前 3 次重试
     * ⇒ 一轮 3~6s，且 `resultReady` 之后节奏是 1.2s 一轮 ⇒ 连续几轮把游戏主线程读死
     * （10-22 22:58 日志：23:03:02 结算页出现 → 13s 无日志 → 帧冻 10s → 游戏进程消失）。
     * 预算用完就**不再重试**，直接走下面的 `findTextIn(top/dialog)` —— 它复用 agent 侧同一份遍历缓存
     * （`ROOT_WALK_TTL_MS` 1500ms 内），几乎免费，而且 23:06:34 那次认出按钮正是这条路 ⇒ 功能不变。
     */
    private const val RESULT_READ_BUDGET_MS = 500L

    /** PC `handle_battle_node` 的失败页分支（3771~3801）：点底部右那颗「重新挑战」并记账（≤3 次）。 */
    private suspend fun handleQuestFailed(input: InputBackend, st: JSONObject) {
        val (_, fail) = battleResultBtns(wide = true, deep = true)
        // ★失败页也抓一次（与胜利页共用"一局一次"的标志）：这是同一段链路的另一条分支，
        //   而且失败页的按钮名/位置与胜利页不同，正需要对上偏移。
        //   ★只在 [AUTO_RESULT_DUMP] 打开时才抓（默认关，见那里的说明）。
        if (AUTO_RESULT_DUMP && fail != null && battleDumps < BATTLE_DUMP_MAX) {
            battleDumps++
            dumpBattleResult(jStr(st, "pageTop"))
        }
        if (fail == null) {
            AppState.addLog("… 战斗失败页：还没读到「重新挑战」按钮 → 等它出现（不点别的）")
            return
        }
        val k = nodeKey(st)
        val n = (nodeFailed[k] ?: 0) + 1
        nodeFailed[k] = n
        AppState.addLog("⚠ 战斗失败 $n/3（节点 ${jStr(st, "blockId")}；「${fail.optString("text")}」）")
        if (n >= 3) {
            when (failStrategy) {
                RerollSettings.FAIL_RETREAT -> {
                    AppState.addLog("[✗] 同一节点连续失败 3 次 → 按策略「撤退重跑」：本局作废，自动开下一局")
                    retreatRequested = true
                    return
                }
                RerollSettings.FAIL_WAIT -> {
                    manualTeamNodes.add(k)
                    AppState.addLog(
                        "[手动] 同一节点已失败 $n 次 → 回编队页后**不自动组队/不自动开战**，等你操作" +
                            "（节点 ${jStr(st, "blockId")}）",
                    )
                    tap(input, fail.optInt("x"), fail.optInt("y"), "重新挑战（回编队页）")
                    delay(1_500)
                    return
                }
                else -> {
                    AppState.addLog("[✗] 同一节点连续失败 3 次 → 停手（PC note_battle_failed 同款）")
                    stopRequested = true
                    return
                }
            }
        }
        tap(input, fail.optInt("x"), fail.optInt("y"), "重新挑战")
        delay(1_500)
    }

    // ────────────────────────── 结算奖励（LabyrinthTempData）──────────────────────────

    private fun invTypeName(t: Int): String = when (t) {
        0 -> "宝箱"; 1 -> "角色"; 2 -> "道具"; 3 -> "装备强化"; 4 -> "装备"
        5 -> "队伍经验"; 6 -> "体力"; 7 -> "家具"; 8 -> "宝石"; 10 -> "设计图"
        11 -> "碎片"; 12 -> "金币"; 13 -> "竞技场券"; 14 -> "公主竞技场券"; 15 -> "纹章"
        18 -> "EX装备"
        else -> "类型$t"
    }

    /** InventoryInfo 列表 → 按 (type,id) 汇总的条目（中文名优先） */
    private fun invEntries(a: JSONArray?): List<RewardEntry> {
        if (a == null || a.length() == 0) return emptyList()
        val order = ArrayList<String>()                 // 首见顺序
        val sums = LinkedHashMap<String, Int>()
        val names = HashMap<String, String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val t = o.optInt("type", -1)
            val id = (o.opt("id") as? Number)?.toInt() ?: continue
            val cnt = (o.opt("count") as? Number)?.toInt() ?: 0
            val key = "$t:$id"
            if (!sums.containsKey(key)) {
                order.add(key)
                val nm = when (t) {
                    1 -> charaPickRef?.uname(id)
                    2 -> rewardItemNames[id.toString()]
                    18 -> rewardExNames[id.toString()]
                    else -> null
                }
                names[key] = if (!nm.isNullOrBlank() && nm != "?") nm else "${invTypeName(t)}#$id"
            }
            sums[key] = (sums[key] ?: 0) + cnt
        }
        return order.map { k -> RewardEntry(k, names[k] ?: k, sums[k] ?: 0) }
    }

    /** List<List<InventoryInfo>>（宝箱）→ 摊平后按 (type,id) 汇总 */
    private fun boxEntries(a: JSONArray?): List<RewardEntry> {
        if (a == null || a.length() == 0) return emptyList()
        val flat = JSONArray()
        for (i in 0 until a.length()) {
            val box = a.optJSONArray(i) ?: continue
            for (j in 0 until box.length()) box.optJSONObject(j)?.let { flat.put(it) }
        }
        return invEntries(flat)
    }

    /** 多组条目合并（同 key 数量相加） */
    private fun mergeEntries(groups: List<List<RewardEntry>>): List<RewardEntry> {
        val order = ArrayList<String>()
        val names = HashMap<String, String>()
        val sums = LinkedHashMap<String, Int>()
        for (g in groups) for (e in g) {
            if (!sums.containsKey(e.key)) {
                order.add(e.key)
                names[e.key] = e.name
            }
            sums[e.key] = (sums[e.key] ?: 0) + e.count
        }
        return order.map { RewardEntry(it, names[it] ?: it, sums[it] ?: 0) }
    }

    private fun fmtEntries(l: List<RewardEntry>): String =
        l.joinToString("、") { "${it.name}×${it.count}" }

    /**
     * ★结算奖励：从 `LabyrinthTempData` 读（agent `labRewardsSnapshot`）。只打一次：
     * 结算页读到就用；还没填好则留到奖励页再读（[rewardsLogged] 控制）。
     */
    private suspend fun maybeLogRewards(where: String) {
        if (rewardsLogged) return
        val rw = AgentApi.labRewards()
        if (rw == null || !rw.optBoolean("ok")) {
            val e = rw?.optString("err").orEmpty()
            if (e.isNotEmpty() && e != "null") AppState.addLog("[!] 结算奖励读不到：$e")
            return
        }
        // ★总分兜底（2026-10-23 用户："结算奖励都出来了，结算奖励的前一个页面就是分数，但它没有统计到"）：
        //   奖励读的是 LTD **内存**字段，而分数原来只读结算②屏的 **UI 文本** ⇒ 两者不同源。
        //   同一份 LTD 里就有 `ResultInfo.ResultScore` ⇒ 这里顺手把分补上（奖励空不空都补）。
        fillScoreFromMemory(rw.optJSONObject("result"), "奖励读取·$where")
        val ch = invEntries(rw.optJSONArray("challenge"))
        val info = invEntries(rw.optJSONArray("rewardInfo"))
        val nb = boxEntries(rw.optJSONArray("normalBox"))
        val rb = boxEntries(rw.optJSONArray("rareBox"))
        // ★`challenge` = 普通宝箱 + 稀有宝箱 的平铺（同一批，别重复打）；它为空才退回按箱展开
        val main = when {
            info.isNotEmpty() -> info
            ch.isNotEmpty() -> ch
            else -> mergeEntries(listOf(nb, rb))
        }
        if (main.isEmpty()) return                     // 还没填好 → 留到奖励页再读
        rewardsLogged = true
        lastRewards = main
        AppState.addLog("🎁 结算奖励（$where）：${fmtEntries(main)}")
    }

    /** 文本数字归一化（结算②屏的 CountUp 标签可能带千分位 `12,345`） */
    private fun scoreNum(s: String?): Int? =
        s?.trim()?.replace(",", "")?.replace("，", "")?.toIntOrNull()

    /**
     * ★用 `LabyrinthTempData.ResultInfo`（**内存**）补结算数值（2026-10-23）。
     *
     * 背景：用户实测「结算奖励打出来了，但奖励页前一屏的分数没统计到」。原因两处不同源——
     * 奖励走 `labRewards`（LTD `+0xB8/+0x158/+0x168/...` 内存字段，boss 胜利后就有），
     * 分数走 `labResult`（结算②屏 `ScoreView+0x30` 的 UI 文本）⇒ ②屏没赶上/CountUp 还在滚/
     * 被点掉了，就只剩奖励、没有分数。而同一份 LTD 的 `ResultInfo`（+0x140）里
     * `ResultScore` 就是**总分**，`+0x2C/0x34` 是累计点/强化点，`+0x40` 是迷宫币 ⇒ 可直接补齐。
     *
     * 口径：① 界面总分与内存**相等** ⇒ 不动；
     *      ② 界面没读到 / 读到 `0`（CountUp 还在起点）/ 数值不等 ⇒ **以内存为准**覆盖，
     *        并补累计点/强化点/迷宫币（界面已有值的项优先保留）；
     *      ③ `resultScore <= 0` 视为"还没填好"→ 不填，留给后面的时机再试。
     *      「难度 / 得分率」内存里没有（UI 专属）⇒ 保留原值或 "-"。
     */
    private fun fillScoreFromMemory(res: JSONObject?, where: String) {
        if (res == null) return
        val total = (res.opt("resultScore") as? Number)?.toInt() ?: return
        if (total <= 0) return
        fun keep(k: String): String =
            lastScore[k]?.takeIf { it.isNotEmpty() && it != "-" && it != "null" } ?: "-"
        val ui = keep("总分")
        if (ui != "-" && scoreNum(ui) == total) return      // 已经一致 ⇒ 什么都不用做
        val point = (res.opt("point") as? Number)?.toInt()
        val enh = (res.opt("enhance") as? Number)?.toInt()
        val coin = (res.optJSONObject("coin")?.opt("count") as? Number)?.toInt()
        val oldPoint = keep("累计点")
        val oldEnh = keep("强化点")
        val oldCoin = keep("迷宫币")
        lastScore = linkedMapOf(
            "总分" to total.toString(),
            "难度" to keep("难度"),
            "得分率" to keep("得分率"),
            "累计点" to (if (oldPoint == "-") point?.toString() ?: "-" else oldPoint),
            "强化点" to (if (oldEnh == "-") enh?.toString() ?: "-" else oldEnh),
            "迷宫币" to (if (oldCoin == "-") coin?.toString() ?: "-" else oldCoin),
        )
        AppState.addLog(
            "迷宫结算（内存口径·$where）：总分=$total" +
                " ｜ 累计点=${point ?: "-"} ｜ 强化点=${enh ?: "-"} ｜ 迷宫币=${coin ?: "-"}" +
                "（界面②屏" + (if (ui == "-") "没读到" else "显示 $ui") + "，取自 LTD.ResultInfo）",
        )
    }

    /** 总分还没拿到（或拿到 0）时的兜底读：单独拉一次 LTD（奖励已 logged 不再重打，只补分数） */
    private suspend fun maybeFillScoreFromLtd(where: String) {
        val n = scoreNum(lastScore["总分"])
        if (n != null && n > 0) return                    // 已有非零总分 ⇒ 不必再读
        val rw = AgentApi.labRewards() ?: return
        if (!rw.optBoolean("ok")) return
        fillScoreFromMemory(rw.optJSONObject("result"), where)
    }

    /**
     * PC `handle_lab_result`(5448)：迷宫结算页（pageTop=[PAGE_LAB_RESULT]）——打完最终 Boss 的两屏：
     *   ①「加入的角色」→ 底部「下一步」  ②「CLEAR + 总分 + 明细」→ 底部「关闭」。
     *
     * ★按钮坐标**不写死**：agent `labResult` 从对象字段现算（`ItemView+0x48`=下一步 /
     *   `ScoreView+0x68`=关闭）⇒ 右侧「撤退（无报酬）/返回（有报酬）」天然点不到（PC 同款规矩）。
     * ★这一页在 PC 上一直偏慢（页面建好前 labResult 会给 ok=false / 按钮坐标为空）——这里"读不到就
     *   下一轮再看"，不硬点；点满 [LAB_RESULT_TAP_MAX] 次才停手。
     * ★走完这两屏，掉落/获得道具两屏由通用弹窗分支点掉，之后 `labStatus` 变空、由收工判据结束本局。
     */
    private suspend fun handleLabResult(
        input: InputBackend,
        tag: String,
        tries: MutableMap<String, Int>,
    ) {
        // ★★不再在开头抓 dump（2026-10-17，用户："按钮可能不会第一时间弹出来…结算页会不会有这个问题"）：
        //   原来这里每次都先抓一份对象字段（**2~7s** ✗）⇒ 第一次点击被硬拖后几秒；而偏移早已定出
        //   （`+0x248/+0x250` 是活的那份 ✓，见 [labResultSnapshot]）⇒ 这份料只有"异常时"才需要 ✓。
        //   现在改成：**连续多轮都定位不到按钮**时才补抓一次（见下面 `p == null` 分支）。
        val r = AgentApi.labResult()
        if (r == null || !r.optBoolean("ok")) {
            if (tries.getOrPut("$tag|rs0", { 0 }) == 0) {
                AppState.addLog("[!] 结算页读不到（labResult：${r?.optString("err")?.take(60) ?: "null"}）→ 下一轮再看")
            }
            return
        }
        // ★来源/耗时（2026-10-17）：这页**每局只出现一次** ⇒ 实例缓存永远命不中，原来每次都
        //   直接**全堆扫描**（数秒 ✗）。现在 DialogArea 优先 ⇒ 期望 `src=dlg`、耗时几十 ms。
        //   看到 `src=scan` 就是那条优先路没命中（顺带说明"结算页慢"的来源）。
        val rsrc = r.optString("src").ifEmpty { "?" }
        if (tries.getOrPut("$tag|rsrc_$rsrc", { 0 }) == 0) {
            tries["$tag|rsrc_$rsrc"] = 1
            AppState.addLog(
                "结算页读取：来源=$rsrc 耗时=${r.optLong("ms")}ms" +
                    if (rsrc == "scan") "（⚠全堆扫描，本不该出现）" else "",
            )
        }
        // ②屏才有数值（①屏这些标签还没激活）—— 只报一次
        val t = r.optJSONObject("text")
        val score = t?.optString("score")?.trim().orEmpty()
        if (score.isNotEmpty() && score != "null" && tries.getOrPut("$tag|rs1", { 0 }) == 0) {
            tries["$tag|rs1"] = 1
            val diff = t?.opt("diffNum")?.toString() ?: "-"
            val rate = t?.opt("scoreRate")?.toString() ?: "-"
            val point = t?.opt("point")?.toString() ?: "-"
            val enh = t?.opt("enhance")?.toString() ?: "-"
            val coin = t?.opt("coin")?.toString() ?: "-"
            AppState.addLog(
                "迷宫结算：总分=$score ｜ 难度=$diff ｜ 得分率=$rate" +
                    " ｜ 累计点=$point ｜ 强化点=$enh ｜ 迷宫币=$coin",
            )
            lastScore = linkedMapOf(
                "总分" to score, "难度" to diff, "得分率" to rate,
                "累计点" to point, "强化点" to enh, "迷宫币" to coin,
            )
        }
        // ★结算奖励（同一份 LTD）：结算页先试一次；还没填好就等奖励页再读
        if (tries.getOrPut("$tag|rw", { 0 }) == 0) {
            tries["$tag|rw"] = 1
            maybeLogRewards("结算页")
        }
        val n = bump(tries, tag)
        // ★"点了几次"要数**真点下去的按钮**（2026-10-16）：上一版数的是**轮数** ⇒ 读不到按钮时
        //   6 轮就把额度耗光（日志里"结算页点了 6 次"其实是 6 次空点）。轮数只用来打进度。
        val taps0 = tries.getOrPut("$tag|rtaps", { 0 })
        if (taps0 >= LAB_RESULT_TAP_MAX) {
            if (taps0 == LAB_RESULT_TAP_MAX) {
                tries["$tag|rtaps"] = taps0 + 1
                AppState.addLog("[!] 结算页真点了 $LAB_RESULT_TAP_MAX 次仍没离开 → 停手（留人工）")
            }
            // ★停手之后**别永久空转**（2026-10-16 实测：这页空转了 3 分多钟，一路"同一现场已 193s"
            //   —— 因为"游戏帧还在涨"⇒ 卡死自愈不触发 ⇒ 永远出不来）。再等 [LAB_RESULT_GIVEUP_S] 秒
            //   仍在这页就**收工结束本局**（游戏停在这页、请人工点掉）：比无声卡住好得多。
            val gv = tries.getOrPut("$tag|rgv", { 0 })
            val nowS = (SystemClock.elapsedRealtime() / 1000L).toInt()
            if (gv == 0) tries["$tag|rgv"] = nowS
            else if (nowS - gv >= LAB_RESULT_GIVEUP_S) {
                AppState.addLog("[✗] 结算页停手后又等 ${LAB_RESULT_GIVEUP_S}s 仍没推进 → 收工（这一页请人工点掉）")
                stopRequested = true
            }
            return
        }
        // ★按钮定位：**对象字段为主、节点树兜底**（2026-10-16 修：上一版照搬 PC 第 1 档的
        //   "`on`=子视图激活"却**没做兜底** ⇒ 实测这页 `next`/`close` 的 `on` 都是假 ⇒ 永远点不了，
        //   只能空点中间，最后"点了 6 次仍没离开"。PC 那边 `on` 只是**第 1 档快路径**，
        //   后面还有"按结算子树名字找"⇒ 这里补上同款链条）：
        //     ① `cands` 里 `which` 那颗、`on=true`、且在屏内（最准：也避免点到**上一屏**那颗）；
        //     ② `cands` 里任何一颗、只要在屏内（**`on` 只作优选、不作闸**）——「下一步/关闭」的字段
        //        坐标本来就只属于这两颗，点错的风险极低；
        //     ③ 节点树里按名字找 `NextButton|ButtonNext` / `CloseButton|ButtonClose`（屏内、下半屏）。
        //   ⚠「撤退（无报酬）」「返回（有报酬）」都不在这三个来源里 ⇒ 依然点不到。
        val cands = r.optJSONObject("cands")
        val want = jStr(r, "which")
        val order = listOf(want, "next", "close").filter { it == "next" || it == "close" }.distinct()
        val inBox = { x: Int, y: Int -> x in 0 until scrW && y in 0 until scrH }
        var label = ""
        var xy: Pair<Int, Int>? = null
        var bad = ""
        for (k in order) {                                   // ① 首选：激活 + 屏内
            val c = cands?.optJSONObject(k) ?: continue
            val x = (c.opt("x") as? Number)?.toInt()
            val y = (c.opt("y") as? Number)?.toInt()
            if (c.opt("on") != true) { bad = "$k 未激活"; continue }
            if (x == null || y == null || !inBox(x, y)) { bad = "$k 坐标 ($x,$y) 不在屏内"; continue }
            label = if (k == "next") "下一步" else "关闭"
            xy = x to y
            break
        }
        if (xy == null) for (k in order) {                   // ② 退一步：只要有坐标且在屏内
            val c = cands?.optJSONObject(k) ?: continue
            val x = (c.opt("x") as? Number)?.toInt() ?: continue
            val y = (c.opt("y") as? Number)?.toInt() ?: continue
            if (!inBox(x, y)) continue
            label = if (k == "next") "下一步" else "关闭"
            xy = x to y
            if (tries.getOrPut("$tag|rb1", { 0 }) == 0) {
                AppState.addLog("… 结算页按钮「$label」的 on 是假（$bad）→ 坐标在屏内，照点（不再等）")
            }
            break
        }
        // ★★树兜底加"等一下"闸门（2026-10-17）：按钮有入场动画，字段坐标可能**先空/先屏外** ⇒
        //   前几轮只是"还没就绪"，不该立刻去遍历 ✗（这页遍历是重活、也是卡死风险的来源）。
        //   连续 [LAB_RESULT_TREE_AFTER_N] 轮都拿不到才允许走树。
        if (xy == null && n >= LAB_RESULT_TREE_AFTER_N) {     // ③ 兜底：节点树按名字找
            val half = (scrH * 0.5).toInt()
            for ((re, lab) in listOf(LAB_RESULT_NEXT_RE to "下一步", LAB_RESULT_CLOSE_RE to "关闭")) {
                val lst = AgentApi.findTextIn(re, "top", 6, half) ?: continue
                var hit: Pair<Int, Int>? = null
                for (i in 0 until lst.length()) {
                    val b = lst.opt(i) as? JSONObject ?: continue
                    val x = (b.opt("x") as? Number)?.toInt() ?: continue
                    val y = (b.opt("y") as? Number)?.toInt() ?: continue
                    if (!inBox(x, y)) continue
                    hit = x to y
                    break
                }
                if (hit != null) {
                    label = lab
                    xy = hit
                    if (tries.getOrPut("$tag|rb2", { 0 }) == 0) {
                        AppState.addLog(
                            "… 结算页对象字段拿不到按钮（$bad）→ 改用节点树按名字找到「$label」@${hit.first},${hit.second}",
                        )
                    }
                    break
                }
            }
        }
        val p = xy
        if (p == null) {
            // ★按需补抓（见函数开头）：只有**连续多轮**定位不到按钮时才抓一次对象字段
            //   ★默认不抓（[AUTO_RESULT_DUMP]=false）：结算页字段读已打通（3ms），dump 只是噪音。
            if (AUTO_RESULT_DUMP && n >= LAB_RESULT_DUMP_AFTER_N && labResultDumps < LAB_RESULT_DUMP_MAX) {
                labResultDumps++
                AppState.addLog("（结算页连续 $n 轮定位不到按钮 → 抓一次对象字段，约 2~7s）")
                dumpBattleResult()
            }
            // ★读不到坐标 ⇒ **不空点屏幕中心**（用户 2026-10-16：那是"过渡操作"，不该顶替「下一步」；
            //   实测空点对结算页毫无作用，只会把"点了几次"的额度耗光）。只报进度，等按钮就绪。
            if (n <= 3 || n % 20 == 0) {
                AppState.addLog("… 结算页（第 $n 轮）还没读到可点按钮（which=$want${if (bad.isEmpty()) "" else "，$bad"}）→ 下一轮再看")
            }
            return
        }
        // ★★落位闸门（2026-10-17 用户："按钮可能不会第一时间弹出来"）：**连续两轮读到同一坐标**才点。
        //   这页按钮带 `TweenScale/TweenAlpha` 入场动画 —— 字段坐标可能"早就有、但按钮还没可点" ⇒
        //   立刻点就是空点（和选人页那次同一形状）。只多等一轮（≈ 一次 labResult，几十 ms）✓；
        //   ①→②换屏时 label/坐标会变 ⇒ 自动重新等一轮 ✓（sig 里带 tag，换页也不会误判）。
        val sig = "$tag|$label@${p.first},${p.second}"
        if (labResultSig != sig && n < LAB_RESULT_SETTLE_N) {
            labResultSig = sig
            if (n <= 2) {
                AppState.addLog("… 结算页「$label」@${p.first},${p.second} 先记一轮（等按钮落位）→ 下轮一致就点")
            }
            return
        }
        // ★兜底安全阀：万一坐标每轮抖 1px（永远"不一致"），等满 [LAB_RESULT_SETTLE_N] 轮就不再等、直接点 ✓
        if (n == LAB_RESULT_SETTLE_N && labResultSig != sig) {
            AppState.addLog("… 结算页坐标 $LAB_RESULT_SETTLE_N 轮仍未稳定（每次都在变）→ 不再等，照点")
            labResultSig = sig
        }
        // ★★「关闭」= ②屏（总分屏）点完就没了 ⇒ 分数还没读到就**先用内存补**（2026-10-23）。
        //   实测问题：奖励页出来了、分数却是空的 —— 因为 ②屏的 UI 文本没赶上（CountUp 滚动/换屏太快），
        //   而奖励走的是 LTD 内存。这里在离开这页之前把 LTD.ResultInfo 的总分落下来，避免整局白跑。
        if (label == "关闭") maybeFillScoreFromLtd("关闭前")
        tries["$tag|rtaps"] = taps0 + 1
        AppState.addLog("迷宫结算：点了「$label」(${p.first},${p.second})")
        tap(input, p.first, p.second, "结算·$label")
        delay(1_200)
    }

    /**
     * ★战斗结算页"对象字段"抓取（2026-10-16 用户："战斗结束：做一个抓取试试"）。
     *
     * 目的：把结算按钮**从内存字段读**（像迷宫结算页 `DialogLabyrinthResult+0x108 →ItemView+0x48` 那样
     * 现算坐标），从而战斗中**彻底不扫树**（现在靠全树遍历，一次最坏 6s，是 19:58 事故的起点）。
     *
     * 抓的是：最上层对话框的名字/GameObject 指针/字段槽里挂的类名/身上的组件，以及屏内"像按钮"节点的
     * GameObject 指针 + 候选组件（`类名@实例指针`）+ 槽位。拿到"类名 + 实例指针 + 偏移"之后，
     * 下一版就能按类读字段算坐标，不再遍历树。
     *
     * 日志分段打印（每段 ≤280 字符，便于整段复制发我）。
     */
    private suspend fun dumpBattleResult(top: String = "") {
        // ★按**页面**去重（2026-10-16 三次修）：原来只要"找到按钮"就 dump ⇒ 同一/相邻页面反复触发
        //   （实测一场 3 次、每次 5~6s ⇒ 帧读冻 ⇒ 被判"卡死"并重启 ✗）。现在同一个顶框只抓一次。
        if (top.isNotEmpty()) {
            if (top == lastDumpTop) return
            lastDumpTop = top
        }
        val d = AgentApi.battleResultDump()
        if (d == null) {
            AppState.addLog("【抓取】结算页 dump 失败：agent 没回（超时/掉线）")
            return
        }
        val s = d.toString()
        AppState.addLog("【抓取】结算页 dump ${s.length} 字符（耗时 ${d.opt("t2")}ms，ok=${d.opt("ok")}）↓ 请整段发我")
        var i = 0
        var k = 1
        while (i < s.length) {
            val end = minOf(i + 280, s.length)
            AppState.addLog("【抓取#$k】${s.substring(i, end)}")
            i = end
            k++
        }
    }

    /**
     * ★★结算按钮"**学一次、之后只校验**"（2026-10-16，用户："比偏移更稳？试一下"）。
     *
     * 为什么比偏移稳：胜利页那颗「下一步」**不在结算对话框字段里**（`PartsQuestResultFormer` 里只有
     * `CustomUILabel` 文字 ✗）——它在**结算 prefab 的骨架**下（`TopParts/BottomParts/ButtonAnimator/
     * Button/NextButton`），而这些节点**同一 session 内指针稳定**（实测两场战斗 `TopParts@0x7b9f7f8f7280`
     * 完全一样 ✓）。所以"记住指针 + 每次校验（节点名 + 屏内）"既**零遍历**（约 3 次调用 ✓）
     * 又天然**跟着版本走**（结构变了 ⇒ 校验失败 ⇒ agent 自动重学 ✓，不像偏移会"悄悄读歪" ✗）。
     *
     * 胜负判据仍是**同一份证据**：按钮的配对标签文字（「下一步」= 胜、「挑战/Retry」= 负 ✓）。
     * @return null = 不可用（调用方继续走字段读/树遍历）
     */
    private suspend fun battleResultBtnPick(bot: Int): Pair<JSONObject?, JSONObject?>? {
        val r = AgentApi.battleResultBtn(bot) ?: return null
        if (r.opt("ok") != true) return null
        val lb = r.optString("label").trim()
        if (lb.isEmpty()) return null                          // 无标签 ⇒ 判不了胜负 ⇒ 不认（不猜 ✓）
        val x = r.optInt("x")
        val y = r.optInt("y")
        if (x !in 0 until scrW || y !in 0 until scrH) return null
        val o = JSONObject().put("x", x).put("y", y).put("text", lb)
        lastBtnSrc = "结果按钮缓存(${r.optString("hit")})@${r.optString("name")}"
        if (!battleBtnSaid) {
            AppState.addLog(
                "结算按钮来源：结果按钮缓存 「$lb」@($x,$y) ｜ ${r.optString("name")}" +
                    "（${r.optString("hit")}${if (r.opt("cands") != null) "，候选 ${r.opt("cands")}" else ""}）",
            )
        }
        return if (FAIL_LABELS.any { it in lb }) null to o else o to null
    }

    /**
     * ★战斗结算按钮的**字段读**（2026-10-16 定案，取代"全树遍历"的快路径）。
     *
     * 来源：agent `battleResultMem()` = 从 DialogArea 拿结算对话框组件（零扫描 ✓）→ 在它的字段
     * （找不到再下探一层 `Parts*` 零件）里找 `CustomUIButton` → 算坐标 + 读**配对标签文字**。
     * 实测证据：`DialogQuestFailed+0x118 = CustomUIButton(1619,989)` = 「重新挑战」✓（与树遍历一致 ✓）。
     *
     * **胜负判据与树遍历完全同源**：配对 `CustomUILabel` 的文字 —— 「下一步/次へ/Next」= 胜、
     * 「挑战/Retry」= 负 ✓（所以字段读同样能分辨成功/失败，且是"文字+坐标同源"）。
     * 同一标签有多个候选时取**最右**那颗（实测两页的可用按钮都在右下 ✓，如 (1619,989)/(1664,984)）。
     *
     * @return null = 字段读不可用（调用方回退树遍历）
     */
    private suspend fun battleResultMemPick(bot: Int): Pair<JSONObject?, JSONObject?>? {
        val m = AgentApi.battleResultMem() ?: return null
        if (m.opt("ok") != true) return null
        val arr = m.optJSONArray("cands") ?: return null
        var win: JSONObject? = null
        var fail: JSONObject? = null
        var srcTag = ""
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val y = c.optInt("y")
            if (y < bot) continue                                   // 与树遍历同口径：只看底部带
            val x = c.optInt("x")
            val lb = c.optString("label").trim()
            if (lb.isEmpty()) continue                              // ★标签读不到 ⇒ 不认这颗（不猜 ✓）
            val o = JSONObject().put("x", x).put("y", y).put("text", lb)
            val isFail = FAIL_LABELS.any { it in lb }
            val isWin = !isFail && WIN_LABELS.any { it in lb }
            if (isFail && (fail == null || x > fail!!.optInt("x"))) { fail = o; srcTag = c.optString("src") }
            if (isWin && (win == null || x > win!!.optInt("x"))) { win = o; srcTag = c.optString("src") }
        }
        if (win == null && fail == null) return null
        lastBtnSrc = "字段读@$srcTag"
        if (!battleBtnSaid) {
            AppState.addLog(
                "结算按钮来源：字段读 「${(win ?: fail)!!.optString("text")}」" +
                    "@(${(win ?: fail)!!.optInt("x")},${(win ?: fail)!!.optInt("y")}) ｜ ${srcTag}",
            )
        }
        return win to fail
    }

    /**
     * 结算按钮"近失"取证（只读，2026-10-16，用户反馈"检测到比出现在屏幕上还晚"）。
     *
     * ★与正式探测的**唯一区别**：这里**不设 y 带、不要求标签匹配**，把下半屏名字含 `Button|Btn`
     *   的节点连 **名字/坐标/标签文字** 一起列出来。用途是一眼分清四种可能：
     *     ① 节点在、但 `y < 85%`（出现"带太窄"）；
     *     ② 名字不在 `RESULT_BTN_RE` 名单里；
     *     ③ 标签读不到、或不是「下一步/挑战」（`«无标签»` = 读不到）；
     *     ④ 下半屏连按钮节点都没有 ⇒ **树里还没建好**，慢的是游戏侧，不是我们。
     */
    private suspend fun battleResultProbeDiag(): String {
        val half = (scrH * 0.5).toInt()
        val sb = StringBuilder()
        // ★`null`（全树 ✗ 一次 ~5s）换成 `dialog`（DialogArea 子树 ✓）—— 2026-10-17：取证本身也是
        //   在"战斗结算页"上做的重活，别再给全树（02:01 那次卡死就是同一类重活撞上结算页瞬间）。
        //   ★`battle`（战斗画布、几百节点 5~8s ✗）同样去掉：08:58 那次卡死就是它 ⇒ 只留 `dialog` ✓。
        for (root in listOf("dialog")) {
            val list = AgentApi.findTextIn(RESULT_BTN_RE_WIDE, root, 20, half) ?: continue
            val hits = ArrayList<String>()
            for (i in 0 until list.length()) {
                val t = list.opt(i) as? JSONObject ?: continue
                val x = (t.opt("x") as? Number)?.toInt() ?: continue
                val y = (t.opt("y") as? Number)?.toInt() ?: continue
                // ★`optString` 遇 JSON null 会返回**字符串 "null"**（不是空串）⇒ "读不到标签"的判断会失效、
                //   日志打成 `「null」`（看着像读到了内容）。这里显式把 JSON null 当空。（2026-10-16 实机见）
                val lbRaw = t.opt("text")
                val lb = (if (lbRaw == null || lbRaw === JSONObject.NULL) "" else lbRaw.toString()).trim().take(8)
                hits.add("${t.optString("name")}($x,$y)" + if (lb.isEmpty()) "«无标签»" else "「$lb」")
                if (hits.size >= 8) break
            }
            if (hits.isNotEmpty()) {
                sb.append(if (root == null) "全树" else root).append('=').append(hits.joinToString(" "))
                break                                    // 找到就走：不必两个根都扫
            }
        }
        return if (sb.isEmpty()) "「下半屏没有名字含 Button/Btn 的节点」" else sb.toString()
    }

    /**
     * PC `_battle_result_btns`(5040)：结算页**屏幕最底部那两颗** → (win, fail)，都没有 = (null, null)。
     * ★胜/负**同一处看**（用户 2026-10-12）：一次查询把两颗都读回来，靠**标签**区分：
     * 「挑战/Retry」= 失败页「重新挑战」；「下一步/次へ」= 胜利页「下一步」。
     * 查询顺序 **最上层对话框 → 战斗画布 → 整棵树**（整棵树只在 deep 时查），只看 y ≥ 85% 屏高的"底部带"。
     */
    private suspend fun battleResultBtns(wide: Boolean, deep: Boolean): Pair<JSONObject?, JSONObject?> {
        val bot = (scrH * 0.85).toInt()
        // ★★**第一优先：结算按钮"学一次、之后只校验"**（2026-10-16）：首次走一次只限 DialogArea 的遍历
        //   学习并记住节点指针；之后每场只做"读指针 + 校验 + 算坐标"⇒ **零遍历**（胜利页那颗「下一步」
        //   不在对话框字段里 ✗，只能这样记住它）。校验不过（换版本/页面结构变了）⇒ agent 自动重学 ✓。
        // ★★直接读（学习缓存 / 字段读）**多重试几次**再降级到树遍历（2026-10-17 用户："直接读开关要
        //   多读几次，避免一次没读到直接降级开始扫描"）：结算页刚出现的那一瞬，缓存/字段可能还没就绪
        //   （一次 `ok=false`），以前当场退到树遍历（147~180ms/次；而"换页瞬间做遍历"正是卡死过的形状）。
        //   现在同一轮里隔 200ms 重读 3 次（≈0.4s），任一次成功就用它 ✓（都失败才走下面的树）。
        // ★用**时间预算**兜住次数上限（见 [RESULT_READ_BUDGET_MS]）：第一次之后就按已用时间决定
        //   还要不要重试，避免"结算动画期连续 3 次遍历 DialogArea"把游戏主线程读死。
        val readT0 = SystemClock.elapsedRealtime()
        for (i in 0 until RESULT_READ_TRIES) {
            battleResultBtnPick(bot)?.let { return it }
            battleResultMemPick(bot)?.let { return it }
            if (i == RESULT_READ_TRIES - 1) break
            if (SystemClock.elapsedRealtime() - readT0 >= RESULT_READ_BUDGET_MS) break
            delay(RESULT_READ_GAP_MS)
        }
        // ★名单**顺序很重要**（2026-10-16 修正回归）：上一版为了省遍历把宽档改成"只用宽名单"，
        //   结果宽名单把 `Button(959,984)`（名字就叫 `Button`）排在 `NextButton(1664,979)` **前面**
        //   ⇒ 我们点了那颗**不推进**的 ✗（22:27 实测：每 4s 重复点 (959,984)、页面纹丝不动）。
        //   恢复成**窄名单优先**（`Next|Retry|…` 先命中 `NextButton` ✓）、宽名单只作兜底：
        //   顺序即优先级 —— 循环遇到第一个"标签匹配"的就返回，所以窄的命中会先被采纳 ✓。
        val pats = if (wide) listOf(RESULT_BTN_RE, RESULT_BTN_RE_WIDE) else listOf(RESULT_BTN_RE)
        // ★根的顺序（2026-10-16 改）：`top` → **`dialog`** → `battle` →（deep 时）全树。
        //   ⚠为什么多加 `dialog` 这一档：`top` 只取"DialogArea 里**最后一个** `Dialog*` 子节点"，
        //   而实测结算那一刻它是 **`DialogBackKeyCapture(Clone)`**（返回键拦截外壳，从 19:07 的日志到
        //   19:46 都是它）⇒ 按钮根本不在那棵子树里，只好一路退到**全树**（一次最坏 6s）。
        //   `dialog` = 整个 DialogArea 子树（几十个节点，便宜）⇒ 把"对话框外壳里包着的真按钮"捞回来，
        //   大概率不必再退全树。
        // ★根的顺序与层级（2026-10-16 二次修）：**结果页出现时先只查便宜的两个根**（`top` 小、
        //   `dialog` 实测 147~180ms 就命中 ✓），`battle`（战斗画布、几百节点、一次数秒 ✗）与全树
        //   只在窄档连续未命中后（`deep`）才给 —— 全部历史命中都来自 `dialog`/全树，**从未**来自 `battle`。
        // ★★2026-10-17 **二次事故**：`deep` 档里的 `null` = **整棵树遍历**（实测一次 ~5s ✗）。02:01 那次
        //   正是在"战斗胜利页刚出现"的瞬间连做 3 次（`[slow] findTextIn 4982/4979/4945ms`）⇒ **游戏进程冻住**
        //   （frame 卡在 15345 ⇒ 30s 后判卡死 ⇒ 自愈重启 ✗）。与 00:42 那次（战斗中做 DialogArea 遍历）同型：
        //   **在页面/战斗切换的瞬间做大范围 invoke** 会把游戏卡住。
        //   ⇒ 全树**彻底不给了**：19:46 那次"只有全树找得到"的成因是 `top` 取到 `DialogBackKeyCapture` 外壳，
        //     而那条早已被 `dialog`（整个 DialogArea 子树，实测 147~180ms 命中 ✓）覆盖 ⇒ 全树已无必要 ✓。
        //   `battle`（几百节点、一次数秒）也只在非结果页时给。
        //   ⇒ 只剩两个根：`top`（几十个节点）+ `dialog`（DialogArea 子树，实测 147~180ms 命中 ✓）。
        //   ★★2026-10-17 **三次事故**：`battle`（战斗画布、几百节点 ⇒ 一次 5~8s ✗）也要去掉 ——
        //   08:58 那次就是它：进战斗 2s（顶框=DialogFlatoutPlayer、场景正在加载）时一个 `findTextIn`
        //   挂在 8s 没回来 ⇒ 游戏进程被拖住 ⇒ viewId 连续超时 ⇒ 判掉线 ⇒ 自愈 ✗。
        //   本文件自己的注释早就写着"**全部历史命中都来自 `dialog`/全树，从未来自 `battle`**" ⇒ 白烧。
        val roots: List<String?> = listOf("top", "dialog")       // （`deep`/`wide` 不再影响根集合）
        for (root in roots) {
            for (re in pats) {
                // ★上限 40 → 12（2026-10-16）：探测"没命中"时最贵（要扫完全部根），而每个命中都要
                //   算一次坐标（≈3 次 invoke）+ 读一次标签 ⇒ 命中越多越慢。结算页就那两颗按钮，12 够。
                val list = AgentApi.findTextIn(re, root, 12, bot) ?: continue
                for (i in 0 until list.length()) {
                    val t = list.opt(i) as? JSONObject ?: continue
                    if (!hasXY(t)) continue
                    // ★同 [battleResultProbeDiag]：`optString` 遇 JSON null 返回字符串 "null" ⇒ 显式当空
                    val lbRaw = t.opt("text")
                    val lb = if (lbRaw == null || lbRaw === JSONObject.NULL) "" else lbRaw.toString().trim()
                    if (lb.isEmpty()) continue
                    // ★记下"从哪找到的"（root × 模式 × 标签）：出问题时一眼看出我们在哪些区域是瞎的
                    // ★把**命中的节点名 + 坐标**也记进去（2026-10-16）：这次"点了不推进的那颗"就是因为
                    //   日志只写"模式=宽「下一步」"，看不出到底点的是 `NextButton` 还是 `Button` ✗。
                    val src = "root=${root ?: "全树"} 模式=${if (re == RESULT_BTN_RE) "窄" else "宽"}「$lb」" +
                        "@${t.optString("name")}(${t.optInt("x")},${t.optInt("y")})"
                    if (FAIL_LABELS.any { it in lb }) {
                        lastBtnSrc = src
                        return null to t
                    }
                    if (WIN_LABELS.any { it in lb }) {
                        lastBtnSrc = src
                        return t to null
                    }
                }
            }
        }
        return null to null
    }

    /**
     * PC `maze_loop` 的**战斗中分支**（6103~6195）：只等；结算时点「下一步」（胜）或「重新挑战」（负）。
     * @return true = 仍在战斗里（调用方 continue 下一轮）；false = 失败结算（落回通用弹窗分支处理）
     */
    private suspend fun battleTick(input: InputBackend, st: JSONObject): Boolean {
        val bs = jStr(st, "battleState").ifEmpty { "?" }
        val resultEnable = st.opt("battleResultEnable") == true
        val stType = st.optInt("statusType")
        val top = jStr(st, "pageTop")
        if (!inBattlePrev) {
            inBattlePrev = true
            resultDiagN = 0                            // 本场"近失"取证计数复位
            resultDiagAt = 0L
            battleBtnSaid = false                      // 本场"来源/找到"日志只打一次
            lastBtnSrc = null
            battleProbeAt = SystemClock.elapsedRealtime()
            pageProbeAt = 0L                           // 认页探测立刻可做（不等 [BATTLE_PAGE_POLL_MS]）
            battleHoldUntil = 0L
            battleWideMiss = 0
            battleWideUsed = 0                          // 全树扫描配额（每场重置）
            battleRes0 = 0L                             // ★本场"认出结算页"的起点（静置计时基准）
            resultProbeN = 0                            // 快速探测配额（每场重置）
            AppState.addLog("进入战斗")
            if (nodeKey(st) !in weStartedFight) {
                AppState.addLog("[!] 已进入战斗，但本脚本没点过「挑战」（游戏自己开的 / 上一场没结束）→ 本场不编队，只等结算")
            }
        }
        val now = SystemClock.elapsedRealtime()
        // ══ 层 1：**只认页**（2026-10-23 两层化，见 [BATTLE_PAGE_POLL_MS]）══════════════════════
        //   `resPageKind` = 在 DialogArea 子节点上做几次 `GetComponentInChildren`（几毫秒），
        //   ⇒ 可以勤问；答 null 就**什么都不做**（不找按钮、不扫树、不读字段）。
        //   ⚠场景切换避让期内不问（[SCENE_HOLD_MS]）：那会儿 DialogArea 正在重建。
        if (battleRes0 == 0L && now >= sceneHoldUntil && now - pageProbeAt >= BATTLE_PAGE_POLL_MS) {
            pageProbeAt = now
            val kind = AgentApi.resPageKind()
            if (kind != null) {
                battleRes0 = now
                AppState.addLog(
                    "… 认出结算页（$kind）→ 静置 ${RESULT_SETTLE_MS}ms 等按钮落位，再开始找「下一步/重新挑战」",
                )
            }
        }
        // ★便宜兜底：`IsResultEnable` 或 顶框**就是**结算页类名 ⇒ 也算认出来（EX / 结构变化时仍能进流程）。
        //   ⚠绝不用"顶框非空"：`DialogFlatoutPlayer`/`DialogBackKeyCapture` 会误触发（见 [RESULT_TOP_RE]）。
        //   ★打一行日志（2026-10-23）：否则从日志上看不出这一场是"resPageKind 认出的"还是"兜底认出的"
        //     （10-23 那轮第 3 场就是这样，看着像没认出、其实走了兜底）。
        if (battleRes0 == 0L && (resultEnable || RESULT_TOP_RE.containsMatchIn(top))) {
            battleRes0 = now
            AppState.addLog(
                "… 认出结算页（兜底：${if (resultEnable) "IsResultEnable" else "顶框类名"}｜${top.ifEmpty { "无" }}" +
                    "）→ 静置 ${RESULT_SETTLE_MS}ms 等按钮落位，再开始找",
            )
        }
        // ★久战兜底（见 [RESULT_TOP_FALLBACK_MS]）：认不出类名也不能干等到超时停手。
        if (battleRes0 == 0L && top.isNotEmpty() && battleT0 > 0 &&
            now - battleT0 >= RESULT_TOP_FALLBACK_MS
        ) {
            battleRes0 = now
            AppState.addLog(
                "… 战斗已 ${(now - battleT0) / 1000}s、顶框=${top}（没认出结算页类名）→ 按结算处理，静置后再试",
            )
        }
        // ══ 层 2：认出来 + 静置够了，才去找按钮 ══════════════════════════════════════════════
        var win: JSONObject? = null
        var fail: JSONObject? = null
        val settled = battleRes0 > 0L && now - battleRes0 >= RESULT_SETTLE_MS
        val bot = (scrH * 0.85).toInt()
        // ⚠必须尊重 `battleProbeAt`：胜利页点完会把它 +2.5s（防"同一页连点"；
        //   20:22 那场 2 秒点了 4 下就是这个没生效）。
        // ★场景切换避让（2026-10-17）：刚进/出战斗的前 [SCENE_HOLD_MS] 内**不探测**——那时战斗场景
        //   还在加载，探测也可能撞上游戏自己的加载 ⇒ 三次卡死的共同形状。
        val gated = now >= sceneHoldUntil && now >= battleHoldUntil

        // ── 2a：**轻量轮询**（2026-10-23 用户："6 次，间隔 400ms"）────────────────────────────
        //   只做一次 `battleResultBtn`（缓存校验 / 学习，实测 100ms 级）—— **不重试、不读字段、不扫树**。
        //   这样 400ms 的间隔才有意义（全套探测一次 ~780ms，间隔再密也只是被耗时撑开）。
        //   实测按钮在"认出"后 3.5~4.5s 才 active ⇒ 3s 静置 + (400+100)ms × 6 ≈ 覆盖到 6s。
        if (settled && resultProbeN < RESULT_PROBE_FAST_MAX && gated &&
            now - battleProbeAt >= RESULT_LIGHT_GAP_MS
        ) {
            battleProbeAt = now
            val p = battleResultBtnPick(bot)
            if (p == null) {
                resultProbeN++
            } else {
                win = p.first
                fail = p.second
                resultProbeN = 0                   // 命中即清零：下一张结算页重新有快速配额
                // ★轻量命中也算"本场找到了"（2026-10-23 修）：不置这个标志的话，
                //   [BATTLE_RESULT_GRACE_MS] 那条"8s 仍没读到"会在按钮**已经点掉之后**误报
                //   （实测 00:19:45：顶框已经是 `DialogBackKeyCapture`，按钮早点击过了）。
                battleBtnSaid = true
            }
        }

        // ── 2b：轻量 6 次都没命中 ⇒ 升级到**全套**（缓存 + 字段读 + 窄名单树遍历），1.2s 一次 ──
        //   到这里说明按钮不在 DialogArea 的常规位置（失败页/结构变化），或者轻量那条路失效了。
        if (settled && win == null && fail == null && resultProbeN >= RESULT_PROBE_FAST_MAX &&
            gated && now - battleProbeAt >= BATTLE_READY_GAP_MS
        ) {
            battleProbeAt = now
            // ★窄档连续这么多次没命中才给宽名单（`deep` 现在只影响名单，不再影响根集合；
            //   全树已被禁，见 [battleResultBtns] 注释）。
            if (!battleBtnSaid) battleWideMiss++
            else battleWideMiss = 0
            // ★配额（[BATTLE_WIDE_MAX]）：单次门槛管"多频繁"，配额管"总共几次" ⇒ 最坏有上界 ✓
            val deep = battleWideMiss >= BATTLE_WIDE_AFTER_MISS &&
                battleWideUsed < BATTLE_WIDE_MAX
            if (deep) battleWideUsed++
            val t1 = SystemClock.elapsedRealtime()
            val r = battleResultBtns(wide = true, deep = deep)
            val probeMs = SystemClock.elapsedRealtime() - t1
            // ★占空比闸门：这次探测太贵（≥ [PROBE_DUTY_MS]）⇒ 下一次推到"耗时之后"再开始（见该常量注释）。
            //   ⚠这里**只等 1.5s**（结算窗口很短、要尽快认出按钮点掉），不能让闸门把"看见按钮"推后几秒。
            if (probeMs >= PROBE_DUTY_MS) {
                battleHoldUntil = SystemClock.elapsedRealtime() + minOf(probeMs, 1_500L)
                // ★登记"重活"：帧看门狗会据此**让路**（否则"我们把它读慢了"会被当成"游戏卡死"⇒杀游戏 ✗，
            //   历史上 19:58 / 21:50 / 22:33 / 22:24 那几次都是这一种 ✗）。
                heavyUntil = SystemClock.elapsedRealtime() + maxOf(probeMs * 4, HEAVY_GRACE_MS)
                heavyWhat = "战斗结算探测 ${probeMs}ms"
            }
            win = r.first
            fail = r.second
            if (win != null || fail != null) {
                // ★抓取（2026-10-16）挪到这里：**真的认出结算按钮**这一刻，页面才是"结算页"
                //   （之前用 `resultReady` 触发，结果战斗中弹出的「自动战斗」对话框也算 ⇒ 抓错了页，
                //   20:16 那份 dump 顶框是 `DialogFlatoutPlayer(Clone)`，跟结算无关）。
                //   ★默认不抓（[AUTO_RESULT_DUMP]=false，2026-10-17 用户："结算页没问题的话，就把 dump 关掉吧"）。
                if (AUTO_RESULT_DUMP && battleDumps < BATTLE_DUMP_MAX) {
                    battleDumps++
                    dumpBattleResult(jStr(st, "pageTop"))
                }
                if (!battleBtnSaid) AppState.addLog("结算按钮来源：${lastBtnSrc ?: "?"}（探测 ${probeMs}ms）")
                battleBtnSaid = true
                resultProbeN = 0                    // ★命中就清零：下一张结算页（奖励页/第二页）重新有快速配额
            } else if (!battleBtnSaid) {
                // ★全套阶段**不再累加** [resultProbeN]（那个计数只属于"静置后的轻量轮询"）；
                //   这里按 [BATTLE_READY_GAP_MS] 一直找，直到认出按钮（[BATTLE_RESULT_GRACE_MS] 会报警）。
                // ★"按钮明明在屏上、我们却晚好几秒才认出来"的取证（2026-10-16 用户反馈）：
                //   结果已定却两样都没读到 ⇒ 每 [RESULT_DIAG_GAP_MS] 打一行"近失" + **本次探测耗时**
                //   （探测本身就慢 ⇒ 我们每秒只能采一次样；近失为空 ⇒ 树里真没这节点/名字标签还没就绪）。
                //   ★找到过按钮就不再打：页面翻走时必然"未命中"，那是误报（15:33:23 那次就是）。
                val tnow = SystemClock.elapsedRealtime()
                if (tnow - resultDiagAt >= RESULT_DIAG_GAP_MS && resultDiagN < RESULT_DIAG_MAX) {
                    resultDiagAt = tnow
                    resultDiagN++
                    // ★取证也是"重活"（一次 DialogArea 遍历），同样要登记 [heavyUntil]（2026-10-22）：
                    //   以前只登记主探测，取证那次把游戏读慢时帧看门狗会把它当成"游戏卡死"⇒ 自愈重启 ✗。
                    val diag0 = SystemClock.elapsedRealtime()
                    val near = battleResultProbeDiag()
                    val diagMs = SystemClock.elapsedRealtime() - diag0
                    if (diagMs >= PROBE_DUTY_MS) {
                        heavyUntil = SystemClock.elapsedRealtime() + maxOf(diagMs * 4, HEAVY_GRACE_MS)
                        heavyWhat = "结算近失取证 ${diagMs}ms"
                    }
                    AppState.addLog(
                        "结算探测 #$resultDiagN：未命中（本次探测 ${probeMs}ms，取证 ${diagMs}ms，状态=$stType/$bs，" +
                            "带= y≥${(scrH * 0.85).toInt()}）｜ 近失=$near",
                    )
                }
            }
        }
        var resultFail = false
        if (fail != null) {                                    // ── 失败页：点「重新挑战」──
            val k = nodeKey(st)
            val n = (nodeFailed[k] ?: 0) + 1
            nodeFailed[k] = n
            AppState.addLog("⚠ 战斗失败 $n/3（节点 ${jStr(st, "blockId")}；「${fail.optString("text")}」）")
            if (n >= 3) {
                when (failStrategy) {
                    RerollSettings.FAIL_RETREAT -> {
                        AppState.addLog("[✗] 同一节点连续失败 3 次 → 按策略「撤退重跑」：本局作废，自动开下一局")
                        retreatRequested = true
                        return true
                    }
                    RerollSettings.FAIL_WAIT -> {
                        manualTeamNodes.add(k)
                        AppState.addLog(
                            "[手动] 同一节点已失败 $n 次 → 回编队页后**不自动组队/不自动开战**，等你操作" +
                                "（节点 ${jStr(st, "blockId")}）",
                        )
                        tap(input, fail.optInt("x"), fail.optInt("y"), "重新挑战（回编队页）")
                        battleProbeAt = now + 2_500
                        battleT0 = 0L
                        battleSaid = 0
                        return true
                    }
                    else -> {
                        AppState.addLog("[✗] 同一节点连续失败 3 次 → 停手（PC note_battle_failed 同款）")
                        stopRequested = true
                        return true
                    }
                }
            }
            tap(input, fail.optInt("x"), fail.optInt("y"), "重新挑战")
            battleProbeAt = now + 2_500                   // 刚点过 → 3.5s 内别再点
            battleT0 = 0L
            battleSaid = 0
            resultFail = true
        } else if (win != null) {                             // ── 胜利页：点「下一步」──
            if (!battleWinSaid) {
                battleWinSaid = true
                val bl = if (battleT0 > 0) (now - battleT0) / 1000 else 0L
                AppState.addLog("战斗胜利 → 点「${win.optString("text")}」（战斗 ${bl}s，来源=${lastBtnSrc ?: "?"}）")
            }
            // ★胜利结算**有两页**（用户 2026-10-16）：①纯 WIN ②战斗奖励（金币）；**各点一次「下一步」**，
            //   所以日志里"点了两次"是正常流程，**不能**当重复点拦掉（拦了会把第 2 页最多卡 8s）。
            //   只跟失败页同款留 2.5s 冷却：同一页别在一次点击的动画里连点。
            battleProbeAt = now + 2_500
            tap(input, win.optInt("x"), win.optInt("y"), "下一步")
        }
        if (!resultFail) {
            // ★`battleRes0` 现在由"层 1 认页"置位（2026-10-23 两层化）：它 = 本场**首次认出结算页**的时刻，
            //   既当静置计时基准，也当"就绪多久还没读到按钮"的计时基准（原来是 `IsResultEnable` 起算）。
            if (battleRes0 > 0L) {
                if (now - battleRes0 >= BATTLE_RESULT_GRACE_MS && !battleFailSaid && !battleBtnSaid) {
                    battleFailSaid = true
                    AppState.addLog(
                        "    [!] 认出结算页 ${(now - battleRes0) / 1000}s 仍没读到「下一步/重新挑战」" +
                            "（GameState=$bs，顶框=${top.ifEmpty { "无" }}）→ 继续等",
                    )
                }
            } else {
                if (battleT0 == 0L) battleT0 = now
                val el = now - battleT0
                val k = (el / 30_000).toInt()
                if (el >= 30_000 && k != battleSaid) {
                    battleSaid = k
                    AppState.addLog("战斗中…（已 ${k * 30}s）")
                }
            }
            waitBattle++
            if (waitBattle >= MAZE_BATTLE_WAIT) {
                AppState.addLog("[✗] 在战斗视图里等待 $waitBattle 轮仍未结束，判定异常")
                stopRequested = true
            }
            return true
        }
        return false
    }

    /** 离开战斗视图 → 计时/档位复位（PC 6196~6201） */
    private fun resetBattleTimers() {
        inBattlePrev = false
        pageProbeAt = 0L
        resultProbeN = 0
        battleT0 = 0L
        battleFailSaid = false
        battleWinSaid = false
        battleSaid = 0
        battleRes0 = 0L
        waitBattle = 0
    }

    private fun intListOf(o: JSONObject, key: String): List<Int> {
        val a = o.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<Int>(a.length())
        for (i in 0 until a.length()) (a.opt(i) as? Number)?.let { out.add(it.toInt()) }
        return out
    }

    /**
     * 轮询游戏主循环帧计数，判断**游戏是否卡死** —— PC 同款口径（`gameTick` →
     * `UnityEngine.Time.frameCount`）：**帧不推进 = 游戏主循环被冻住**，而不是"页面元素没变"。
     *
     * 状态机只在"自动黎明界"窗口里调它（[FridaInjector.autoRecover]）。
     * @return null = 正常（帧在涨，或还在观察期）；非 null = 卡死原因（调用方据此自愈重启）
     */
    private suspend fun pollGameFrame(now: Long): String? {
        if (now - framePollAt < GAME_FRAME_POLL_MS) return null
        framePollAt = now
        val t = AgentApi.gameTick()
        val f = (t?.opt("frame") as? Number)?.toLong() ?: -1L
        if (f >= 0 && f != frameLast) {
            // 帧在涨 ⇒ 游戏活着（页面再慢也不动它）
            frameLast = f
            frameAdvanceAt = now
            frameSaidStale = false
            return null
        }
        // 帧没涨 / 读不到 ⇒ 计时（首次只建立基线）
        if (frameAdvanceAt == 0L) {
            frameAdvanceAt = now
            return null
        }
        val stale = now - frameAdvanceAt
        val limit = if (f >= 0) GAME_FRAME_STUCK_MS else GAME_FRAME_UNREADABLE_MS
        if (stale < limit) {
            if (!frameSaidStale && stale >= 10_000L) {
                frameSaidStale = true
                AppState.addLog(
                    if (f >= 0) "… 游戏帧计数已 ${stale / 1000}s 不涨（frame 卡在 $frameLast）→ 继续观察"
                    else "… 游戏帧计数读不到已 ${stale / 1000}s（agent 还连着）→ 继续观察",
                )
            }
            return null
        }
        return if (f >= 0) "游戏帧计数连续 ${stale / 1000}s 不涨（frame 卡在 $frameLast，游戏主循环冻住）"
        else "游戏帧计数连续 ${stale / 1000}s 读不到（agent 还连着 ⇒ 游戏多半已冻死）"
    }

    /** 重置帧看门狗（run 开始 / 自愈重启回来后必须调，否则拿旧基线会立刻误判"又卡死"） */
    private fun resetFrameWatch() {
        framePollAt = 0L
        frameAdvanceAt = SystemClock.elapsedRealtime()
        frameLast = -1L
        frameSaidStale = false
    }

    /** 心跳里附一句"游戏帧还在不在涨"——一眼区分"游戏真卡死"与"我们卡在逻辑上" */
    private fun frameHint(): String {
        if (frameAdvanceAt == 0L) return "（游戏帧：还没基线）"
        val age = (SystemClock.elapsedRealtime() - frameAdvanceAt) / 1000
        return if (age <= 3) "（游戏帧正常在涨）" else "（游戏帧已 ${age}s 不涨！）"
    }

    /**
     * 卡死自愈①：取证。把 agent **现在**看到的东西打出来（status/dialog/choices/buttons），
     * 便于事后定位"为什么停在这页不动"（杀掉进程后就取不到了，所以必须杀之前打）。
     * @param st 当前 labStatus（帧看门狗在它之前触发，故可能为 null → 这里自己再读一次）
     */
    private suspend fun dumpStuckDiag(st: JSONObject?) {
        val s = st ?: runCatching { AgentApi.labStatus() }.getOrNull()
        if (s == null) AppState.addLog("　status  = 读不到（游戏冻住时 labStatus 也会超时）")
        else AppState.addLog("　status  = ${s.toString().take(400)}")
        AgentApi.dialog()?.let { AppState.addLog("　dialog  = ${it.toString().take(300)}") }
        AgentApi.labChoices()?.let { AppState.addLog("　choices = ${it.toString().take(400)}") }
        AgentApi.findList("Btn|Button|Decide|Select|Close|Choice|Skip|Item")?.let {
            AppState.addLog("　buttons = ${it.toString().take(500)}")
        }
    }

    /**
     * 卡死自愈②：杀进程后，等游戏被 [FridaInjector] 拉起来 + agent 回连，再确保回到迷宫内。
     *
     * 重启后游戏可能 ①直接续在迷宫里（最多情况：黎明界进度在服务器），也可能 ②停在首页/黎明界 top
     * ——② 就照服务那边同一套走一遍「黎明界 top → 出发」（[Navigator.enterMaze] 只在"挑战中"才点出发，
     * 所以在 259 上会 resume 这一局，不会新开）。
     *
     * @return true = 已经回到迷宫、可以继续跑状态机；false = 超时/导航失败（调用方停手）
     */
    private suspend fun recoverAfterRestart(input: InputBackend): Boolean {
        AppState.addLog("自愈：等游戏被重新拉起 + agent 回连…")
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < STUCK_BACK_MS) {
            if (Foreground.isGameForeground(GameProcess.GAME_PKG) && AgentBridge.connected.value) break
            delay(1_000)
        }
        if (!Foreground.isGameForeground(GameProcess.GAME_PKG) || !AgentBridge.connected.value) {
            AppState.addLog("自愈：${STUCK_BACK_MS / 1000}s 内游戏/agent 没回来")
            return false
        }
        // ★"读不到"≠"不在迷宫"（2026-10-17 实机）：刚重注入那一下 `currentViewId()` 常返回 -1/0，
        //   老代码据此判"不在迷宫" ⇒ 去走「黎明界 top」；而实际就停在 **264 地图**上 ⇒ 导航把 264 当
        //   未知页面白等 16 轮 ⇒ 停手，**本局直接废掉** ✗（日志 12:10:55）。
        //   现在先重读几秒；仍读不到就当"仍在迷宫"——真正的判据是下面 `labStatus` 读不读得到 ✓
        var vid = -1
        val tv0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - tv0 < 6_000L) {
            vid = Navigator.currentViewId()
            if (vid > 0) break
            delay(300)
        }
        val inMaze = if (vid > 0) Navigator.isLabyrinthView(vid) else true
        if (!inMaze) {
            AppState.addLog("自愈：重启后在 ${Navigator.viewLabel(vid)}（不在迷宫内）→ 重新走「黎明界 top → 出发」")
            if (!Navigator.goToLabyrinthTop(input)) return false
            // NOT_CHALLENGING（到了 259 却没有进行中的开局）也在这里收手：Navigator 已提示「请检查当前登录账号」
            if (Navigator.enterMaze(input) != Navigator.EnterResult.ENTERED) return false
        } else {
            AppState.addLog("自愈：已在迷宫内（${Navigator.viewLabel(vid)}）→ 不重新导航，直接继续")
        }
        // 等迷宫状态可读（`labStatus` 有内容 = 真的续上了，这时回主循环才安全）
        val t1 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t1 < 60_000L) {
            if (AgentApi.labStatus() != null) {
                resetFrameWatch()          // 换了新游戏进程 ⇒ 帧基线从头建立（否则拿旧基线会立刻误判）
                return true
            }
            delay(1_000)
        }
        AppState.addLog("自愈：回到迷宫后 60s 内读不到迷宫状态")
        return false
    }

    /**
     * PC 版 `unknown_state_diag`(1061)：把现场一次性打出来（同一 `状态/节点类型` 只一次），
     * 照着这段就能补这个状态的 handler。
     *
     * ★★**先等、晚一点再看一遍**（2026-10-17 用户："未知状态的时候不要立刻扫描，晚一点再看一遍状态"）：
     *   实测这 4 次全是**换页/加载的瞬间**（11:54:49、11:57:52、12:00:23、12:03:43，下一轮自己就恢复）：
     *   页面还在切换 ⇒ 那一轮的 status/dialog/choices 都读不到真东西，却把整棵树扫一遍
     *   （`[slow] findList 2.8s` ✗，换页瞬间做全树还有把游戏卡住的风险）。
     *   现在：第一次见到只打一行"待观察"，**等 [UNKNOWN_DIAG_AFTER_MS] 再看**；
     *   若那时状态已经不在未知分支了，这一行就是唯一输出 ✓；真卡住才出完整现场。
     */
    private val unknownFirstSeen = HashMap<String, Long>()

    private suspend fun unknownState(st: JSONObject, dumped: MutableSet<String>) {
        val stt = st.optInt("statusType", -1)
        val key = "$stt/${jStr(st, "panelType")}"
        val now = SystemClock.elapsedRealtime()
        val firstAt = unknownFirstSeen.getOrPut(key) { now }
        if (now - firstAt < UNKNOWN_DIAG_AFTER_MS) {
            // 只报一次"先观察"（之后闭嘴，等真诊断或状态自己恢复）
            val skey = "$key|wait"
            if (dumped.add(skey)) {
                AppState.addLog(
                    "… 未知状态 [${statusName(stt)}($stt)]（panelType=${jStr(st, "panelType")}）" +
                        " → 可能是换页瞬间，先等 ${UNKNOWN_DIAG_AFTER_MS / 1000}s 再看（**不扫树**）",
                )
            }
            return
        }
        if (!dumped.add(key)) return
        AppState.addLog("═══ 未知状态诊断 [${statusName(stt)}($stt)]（panelType=${jStr(st, "panelType")}）" +
            "（持续 ${(now - firstAt) / 1000}s，不是换页瞬间）—— 照这段补 handler ═══")
        AppState.addLog("status  = ${st.toString().take(600)}")
        AgentApi.dialog()?.let { AppState.addLog("dialog  = ${it.toString().take(300)}") }
        AgentApi.labChoices()?.let { AppState.addLog("choices = ${it.toString().take(400)}") }
        AgentApi.labNodes()?.let { AppState.addLog("nodes   = ${it.toString().take(400)}") }
        // ★别用 no/ok/One/Two 这类短词 —— 名字普遍含 "(Clone)"/"TwoDigits"，子串匹配会命中整棵树（PC 注释）
        // ★根从"全树"改成 `dialog`（2026-10-17）：全树一次 2.8s ✗，而且这里已经"卡住"了，
        //   再拿全树遍历去卡一次游戏毫无意义；可点按钮都在对话框里（没有弹窗时自然返回空）。
        val btns = AgentApi.findList("Btn|Button|Decide|Select|Close|Choice|Skip|Item", "dialog")
        if (btns != null) AppState.addLog("buttons = ${btns.toString().take(500)}")
        AppState.addLog("══════════════════════════════════════════")
    }

    /** 按**节点名**（正则）找屏内激活节点 → (x,y)；找不到 null（PC `resolve` 137） */
    private suspend fun findNode(nameRe: String): Pair<Int, Int>? {
        val d = AgentApi.find(nameRe) ?: return null
        if (!d.has("x") || !d.has("y")) return null
        return d.optInt("x") to d.optInt("y")
    }

    /**
     * 点屏幕坐标。★照 PC `tap_xy`(2026-10-12)：**屏外一律不点**。
     * 起因：卡片/按钮"还没落位"（入场滑动动画）时坐标会跑到屏外，空点会伪装成"点了没反应/按钮无效"。
     * 这里兜一道（越界只报一次、不下发），返回 true = 真点下去了（调用方据此决定"算不算一次尝试"）。
     */
    private suspend fun tap(input: InputBackend, x: Int, y: Int, what: String): Boolean {
        if (x !in 0..scrW || y !in 0..scrH) {
            if (lastOOR != (x to y)) {
                lastOOR = x to y
                AppState.addLog("[!] 点击坐标 ($x,$y) 在屏幕外（${scrW}x${scrH}）→ 不点（$what：读取/换算出错了？）")
            }
            return false
        }
        return when (val r = input.tap(x, y)) {
            is com.lmj.labyrinthauto.input.InputResult.Ok -> {
                AppState.addLog("点了 $what ($x,$y)［${input.name}］"); true
            }
            is com.lmj.labyrinthauto.input.InputResult.Failed -> {
                AppState.addLog("点 $what 失败：${r.reason}"); false
            }
            com.lmj.labyrinthauto.input.InputResult.Unavailable -> {
                AppState.addLog("点 $what 失败：${input.name} 后端不可用"); false
            }
        }
    }

    /** 上次报过的"屏外坐标"（同值只报一次，照 PC `_TAP_OOR`） */
    private var lastOOR: Pair<Int, Int>? = null

    /**
     * 通用弹窗白名单（PC `MAZE_DIALOG_CONFIRM`）：命中就点那一颗，认不出来的弹窗**不猜**。
     * 抽出来是为了给"handler 读不到页面"时当**降级路径**（见 [handleSummonUnit]）—— 否则那种页会永久卡死。
     * @return true = 点了
     */
    private suspend fun tapDialogConfirm(input: InputBackend, top: String): Boolean {
        // ★2026-10-20：弹窗按钮链接 [LabBtnStore] 跨局回喂（结算页那种大弹窗的 DialogArea 子树
        //   要 3s+，2026-10-20 00:40 实测局末连 4 次 dialog 3.2~3.8s 只为点一颗 OneButtonWhite）。
        //   第一次遍历顺带学链落盘，之后（含跨局）直达零遍历。key = "dlg|<弹窗规范名>"。
        //   ⚠只有这里（带 top 的调用）会启用 agent 的学习/使用 —— 无 top 的 dialog() 永远遍历
        //   （2026-10-17 幽灵弹窗事故的保险，见 agent 端 _dlgTryLearned 注释）。
        val topKey = "dlg|" + top.removeSuffix("(Clone)")
        val known = appCtx?.let { LabBtnStore.getChains(it, topKey) }
        val d = AgentApi.dialog(top, if (known != null && known.length() > 0) known else null)
        val fresh = d?.optJSONArray("learnedList")
        if (fresh != null && fresh.length() > 0) {
            val c = appCtx
            if (c != null) {
                LabBtnStore.putChains(c, topKey, fresh)
                // ★**临时验证日志**（同 `[路径验证]`，验证一个版本后随前缀一起摘）
                AppState.addLog("[路径验证] 弹窗 ${top.removeSuffix("(Clone)")}：按钮链已学成落盘（下局起零遍历）")
            }
        }
        val bts = d?.optJSONArray("buttons") ?: return false
        if (bts.length() == 0) return false
        // ★"学到的路径"可观测性（2026-10-17）：① 第一次命中报一次；② **命中过又失效回退**也报一次
        //   —— 后者正是判断"索引路径能否跨弹窗实例复用"的关键证据（学不到就是白学，日志必须能看出来）。
        // ★**临时验证日志**（2026-10-20 用户：验证一个版本后摘除；统一 `[路径验证]` 前缀方便 grep）
        if (d.optBoolean("learned", false)) {
            if (dlgLearnSaid.add("$top|hit")) {
                AppState.addLog("[路径验证] 通用弹窗 ${top.ifEmpty { "?" }}：走学到的路径定位按钮（零遍历）")
            }
        } else if (dlgLearnSaid.contains("$top|hit") && dlgLearnSaid.add("$top|miss")) {
            AppState.addLog("[路径验证] 通用弹窗 ${top.ifEmpty { "?" }}：学到的路径失效（结构变了/实例销毁）→ 回退遍历并重学")
        }
        var hit: JSONObject? = null
        for (want in DIALOG_CONFIRM) {
            for (i in 0 until bts.length()) {
                val b = bts.optJSONObject(i) ?: continue
                if (b.optString("name") == want && hasXY(b)) {
                    hit = b
                    break
                }
            }
            if (hit != null) break
        }
        if (hit == null) return false
        // ★防"幽灵弹窗"无限连点（2026-10-17 事故，同一类坑在 Navigator 也出现过）：
        //   同一颗按钮（名字+坐标）连点 [DLG_TAP_MAX] 次仍没换页 ⇒ 判"点它没用"，返回 false
        //   让主循环继续往下走（比每轮回来再点它安全）。
        val sig = "${hit.optString("name")}@${hit.optInt("x")},${hit.optInt("y")}"
        if (sig == dlgTapSig) dlgTapN++ else { dlgTapSig = sig; dlgTapN = 1 }
        if (dlgTapN > DLG_TAP_MAX) {
            if (dlgTapN == DLG_TAP_MAX + 1) {
                AppState.addLog(
                    "[!] 同一颗弹窗按钮「${hit.optString("name")}」连点 $DLG_TAP_MAX 次都没推进 → **不再点它**" +
                        "（幽灵弹窗或点不动）",
                )
            }
            return false
        }
        tap(input, hit.optInt("x"), hit.optInt("y"), "对话框「${hit.optString("name")}」")
        return true
    }

    /**
     * 白名单**没命中**时的弹窗兜底（2026-10-16，用户截图「宝箱开封结果」卡死）。
     *
     * ★两道规则，都刻意保守：
     *   ① **只有一颗**按钮 ⇒ 只能是"确认/关闭"这类前进键（`OneButton*` 只是配色变体，名字各不相同，
     *      不可能穷举）——**先空点一次屏幕中心**（这类开箱/翻牌页点中心能跳过演出动画，用户 2026-10-16
     *      的要求），再点那颗按钮；
     *   ② **两颗及以上** ⇒ **不猜**（可能是"取消/放弃"），只把按钮名打出来（一页一次）好补白名单条目。
     *   ⚠中心空点只对 `DialogCommon*` 这类通用弹窗壳生效，且每页一次；其它页不空点。
     *   ★"结算后不管什么页面都点中心"已上移到调用方（[CenterTapGate]，每 3 轮才下来找一次按钮）；
     *     本函数只负责找按钮，不再负责加速。
     * @return true = 点了
     */
    private suspend fun tapDialogUnknown(
        input: InputBackend,
        tag: String,
        tries: MutableMap<String, Int>,
        top: String,
    ): Boolean {
        // ★按钮来源（2026-10-16 **二次修**）：原来只问 `dialog()`（= `dialogButtonsSnapshot`，它只认
        //   `/^(One|Two|Three)Button(Left|Right|Center|White|Blue)?$/` 这套**严格名单**）⇒ 宝箱页那颗
        //   「确认」的名字不在名单里 ⇒ `named` 为空 ⇒ **静默 `return false`**（20:50 又空转 90s 就是这么来的，
        //   和之前那次"节点名不在白名单"是同一类坑）。
        //   现在改成在**整个 DialogArea 子树**里按**宽名单**找（root="dialog"）：范围仍是"当前对话框"、
        //   碰不到背后的地图 HUD 按钮 ✓，但不再靠猜名字。
        val wide = "Btn|Button|Decide|Confirm|OK|Ok|Close|Cancel|Next"
        val arr = AgentApi.findTextIn(wide, "dialog", 12, null)
        val raw = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val o = arr?.optJSONObject(i) ?: return@mapNotNull null
            o.takeIf { hasXY(it) && it.optString("name").isNotEmpty() }
        }
        // ★★剔掉"标签子节点"（2026-10-17「宝箱开封结果」实测）：那页**只有一颗「确认」**，但候选里同时出现
        //   `OneButtonWhite` 和它的子节点 `OneButtonWhiteLabel` ⇒ 被判成"两颗 ⇒ 不猜" ✗ ⇒ 一直不点。
        //   判据：名字 = **另一个候选名 + Label/Text/Title** ⇒ 那是标签，丢掉 ✓。
        val names = raw.map { it.optString("name") }.toHashSet()
        val named = raw.filter { o ->
            val n = o.optString("name")
            val m = Regex("^(.+?)(Label|Text|Title)$").find(n)
            val base = m?.groupValues?.getOrNull(1)
            base == null || base !in names
        }
        // ★★白名单名字优先（强信号，2026-10-17）：`tapDialogConfirm` 靠 agent 的**严格快照**，实测这页
        //   它给空（按钮在 `DialogAnimationViewer` 里那一层）✗；而宽名单能拿到 `OneButtonWhite`（在白名单里 ✓）
        //   ⇒ 这里按白名单再匹配一次就直接点，不必因为"多颗"而放弃。
        val wl = named.firstOrNull { DIALOG_CONFIRM.contains(it.optString("name")) }
        if (wl != null) {
            AppState.addLog("弹窗（顶框=$top）按白名单命中「${wl.optString("name")}」→ 点（宽名单兜底路径）")
            // ★诊断（2026-10-17 用户要求）：宽名单能命中、严格快照却没给 ⇒ 把 `dialog()` 的**原始返回**
            //   打出来（每页一次），用来定位是"按钮 inactive"还是"层级不在 DialogArea" ⇒ 好把严格路径
            //   也修好，省掉这次宽名单遍历（约 1s）。
            if (tries.getOrPut("$tag|dlgraw", { 0 }) == 0) {
                tries["$tag|dlgraw"] = 1
                // ★带 top 调（2026-10-20）：无 top 的 dialog() 永远全遍历（大弹窗 3.7s）；这里要对比的
                //   本来就是"严格快照给没给"，带 top 的返回才对得上，顺带走学习路径不再慢。
                AppState.addLog("　dialog() 原始 = ${AgentApi.dialog(top)?.toString()?.take(400) ?: "null（超时/没连）"}")
            }
            // ★坐标缓存优先（2026-10-20）：FullSize 结算页的 OneButtonWhite 不在 DialogArea
            //   （DialogAnimationViewer 层），且按钮出现晚是游戏节奏 ⇒ 严格快照给不出。
            //   学一次坐标（+屏幕尺寸）落盘，之后直达；分辨率变了自动失效重学。
            val bn = wl.optString("name")
            val dm2 = appCtx?.resources?.displayMetrics
            var px = -1
            var py = -1
            if (dm2 != null) {
                val xk = "xy|dlg|$top|$bn"
                LabBtnStore.getXY(appCtx!!, xk, dm2.widthPixels, dm2.heightPixels)?.let { xy ->
                    px = xy.first; py = xy.second
                    if (tries.getOrPut("$tag|dlgxy", { 0 }) == 0) {
                        tries["$tag|dlgxy"] = 1
                        AppState.addLog("[路径验证] 弹窗 $top「$bn」：用缓存坐标直接点（${xy.first},${xy.second}）")
                    }
                }
            }
            if (px < 0) {
                // 学坐标：全树 find 一次（~1.1s，只此一次），成功即落盘
                val fxy = findNode(bn)
                if (fxy != null && dm2 != null) {
                    val c2 = appCtx
                    if (c2 != null) {
                        LabBtnStore.putXY(c2, "xy|dlg|$top|$bn", fxy.first, fxy.second, dm2.widthPixels, dm2.heightPixels)
                        AppState.addLog("[路径验证] 弹窗 $top「$bn」：坐标已学成落盘（下局直接点）")
                    }
                    px = fxy.first; py = fxy.second
                }
            }
            if (px > 0 && py > 0) {
                tap(input, px, py, "对话框「$bn」")
                return true
            }
            tap(input, wl.optInt("x"), wl.optInt("y"), "对话框「$bn」")   // 没学到 → 用宽名单坐标
            return true
        }
        if (named.isEmpty()) {
            // ★到这里 = 上面"点中央加速"已点过、本轮真检测仍无按钮 → 等下一轮
            //   （加速+降频逻辑已上移到本函数入口：FullSize/AnimationViewer 每轮点中央、每 3 轮一检）。
            // ★**不再静默**：DialogArea 里连"像按钮"的节点都没有 ⇒ 把该子树前 12 个节点名打出来
            //   （一页一次，够我们看清真实命名/判据哪里不对）。
            if (tries.getOrPut("$tag|dlgz", { 0 }) == 0) {
                tries["$tag|dlgz"] = 1
                val all = AgentApi.findTextIn(".*", "dialog", 12, null)
                val names = (0 until (all?.length() ?: 0)).mapNotNull { i ->
                    all?.optJSONObject(i)?.optString("name")?.takeIf { it.isNotEmpty() }
                }
                AppState.addLog(
                    "[!] 弹窗（顶框=$top）在 DialogArea 里找不到可点按钮（宽名单「$wide」也空）→ 不点；" +
                        "该子树前 12 个节点=" + names.joinToString(",").take(220),
                )
            }
            return false
        }
        if (named.size > 1) {
            if (tries.getOrPut("$tag|dlgu", { 0 }) == 0) {
                tries["$tag|dlgu"] = 1
                AppState.addLog(
                    "[!] 弹窗（顶框=$top）有 ${named.size} 颗按钮、都不在白名单 → 不猜（怕误点取消）｜ " +
                        "按钮=${named.joinToString(",") { it.optString("name") }.take(160)}",
                )
            }
            return false
        }
        val b = named[0]
        if (top.contains("DialogCommon") && tries.getOrPut("$tag|dlgc", { 0 }) == 0) {
            tries["$tag|dlgc"] = 1
            AppState.addLog("弹窗（顶框=$top）只有一颗按钮「${b.optString("name")}」→ 先空点屏幕中心跳过动画")
            tap(input, scrW / 2, scrH / 2, "弹窗·空点屏幕中心（跳过开箱/翻牌动画）")
            delay(600)
        }
        AppState.addLog("弹窗（顶框=$top）单按钮「${b.optString("name")}」不在白名单 → 直接点它")
        tap(input, b.optInt("x"), b.optInt("y"), "对话框「${b.optString("name")}」")
        return true
    }

    /** 诊断用：把当前弹窗的按钮名清单打成一行（补 handler 时要看页面上到底有什么） */
    private suspend fun dumpDialogButtons(where: String) {
        val bts = AgentApi.dialog()?.optJSONArray("buttons")
        if (bts == null) {
            AppState.addLog("[诊断] $where：读不到弹窗/按钮")
            return
        }
        val names = (0 until bts.length()).mapNotNull { i ->
            bts.optJSONObject(i)?.optString("name")?.takeIf { it.isNotEmpty() }
        }
        AppState.addLog("[诊断] $where 按钮(${names.size})=${names.joinToString(",").take(300)}")
    }

    /** LTD（`labStatus.choices`）里的候选简报：`序号:rewardId`，最多 6 条 —— 遗物选择排障用。
     *  它和"卡片上的 id"是**两条独立读法**（`LabyrinthStatus.Choices` vs 卡片 `choiceInfo`），
     *  两边都对得上 ⇒ 读数正常；只有卡片那侧是垃圾 ⇒ 是卡片路径坏了（2026-10-17 那次就是这个形状）。 */
    private fun ltdChoicesBrief(st: JSONObject): String {
        val a = st.optJSONArray("choices") ?: return "(无字段)"
        if (a.length() == 0) return "(空)"
        val sb = StringBuilder()
        for (i in 0 until minOf(a.length(), 6)) {
            val o = a.optJSONObject(i) ?: continue
            if (sb.isNotEmpty()) sb.append(",")
            sb.append(o.opt("i")).append(":").append(o.opt("rewardId"))
        }
        return sb.toString()
    }

    /** `obj.opt(key)` → 字符串（JSON null / 缺失 → ""），拼 tag 用 */
    private fun jStr(o: JSONObject, key: String): String {
        val v = o.opt(key) ?: return ""
        return if (v === JSONObject.NULL) "" else v.toString()
    }
}
