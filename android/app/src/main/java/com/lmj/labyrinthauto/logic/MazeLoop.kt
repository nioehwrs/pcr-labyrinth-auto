package com.lmj.labyrinthauto.logic

import android.content.Context
import android.os.SystemClock
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.core.CenterTapGate
import com.lmj.labyrinthauto.core.LabBtnStore
import com.lmj.labyrinthauto.core.LogicTokenStore
import com.lmj.labyrinthauto.core.LogFile
import com.lmj.labyrinthauto.core.OverlayStatus
import com.lmj.labyrinthauto.core.TapDelayStore
import com.lmj.labyrinthauto.data.GameData
import com.lmj.labyrinthauto.reroll.RerollState
import com.lmj.labyrinthauto.reroll.RerollSettings
import com.lmj.labyrinthauto.logic.party.PartyPlan
import com.lmj.labyrinthauto.logic.relic.RelicPick
import com.lmj.labyrinthauto.logic.shop.ShopPlan
import com.lmj.labyrinthauto.logic.shop.ShopSampleLog
import com.lmj.labyrinthauto.logic.team.Rules
import com.lmj.labyrinthauto.logic.team.TagIds
import com.lmj.labyrinthauto.logic.team.TeamRunLog
import com.lmj.labyrinthauto.frida.AgentApi
import com.lmj.labyrinthauto.frida.AgentBridge
import com.lmj.labyrinthauto.frida.FridaInjector
import com.lmj.labyrinthauto.frida.GameProcess
import com.lmj.labyrinthauto.frida.RootShell
import com.lmj.labyrinthauto.input.Foreground
import com.lmj.labyrinthauto.input.InputBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **迷宫内状态机（P5 骨架）** —— 形状逐条对应 PC 版 `pcr_auto.py` 的 `maze_loop`(5900)：
 *
 * ```
 * 读一次地图 → 循环：
 *   labStatusLite() ──null──→ "不在迷宫/换区途中"（第 1 次 + 每 10 次报一次）；若已过结算 → 收工
 *      │   ★2026-11-08"需要什么读什么"：分派读只带**分派用得到**的字段；
 *      │     eventId/questId/choices 等由 handler 自己按需读（[extraIds]）。
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

    // ───────────────── ★凹分模式（2026-11-01 用户需求）─────────────────
    // 检测点：站在 5 层商店（50701）、下一步要前往 5 层 Boss（50801）时查一次。
    // 分数公式见 [RerollSettings.scoreBaseOf]（15200 + 100×角色 + 200×遗物，5 层结算页截图校准过）。
    private const val NODE_SHOP_5F = 50701
    private const val NODE_BOSS_5F = 50801

    /** 遗物决策层（PC `RelicPick`）—— 每局 run 开始时按当前 assets 重建 */
    private lateinit var relicPick: RelicPick

    /** 本局已遇 Boss 的 unitId（按区记）：PC 从 `labyrinth_state.json` 的 boss 列表取 3 区 Boss，
     *  这里改为"遇到 BOSS 节点时记下来"，让遗物特殊条件（毒液沙罗曼蛇）能对上（见 [handleRelicChoice]） */
    private val bossByArea = mutableMapOf<Int, Int>()

    /** ★每区 Boss 的**实战 questId**（2026-11-14）：来自刷开局地图，三选一多目标加权用。 */
    private val bossQuestByArea = mutableMapOf<Int, Int>()

    /** ★2026-11-13：最近一次「事件节点」的时刻/事件 id（判断三选一遗物、事件战斗是否来自事件） */
    private var lastEventAt = 0L
    private var lastEventId = 0

    /** ★2026-11-13：本场战斗是否已经记过「事件战斗」采样（进战斗时置位，离开战斗时清） */
    private var eventBattleSampled = false

    /** 同一组候选卡已点次数（按 tag；组变了重置）—— PC `REL_TAP_MAX`(471) 防重复砸同一坐标把游戏点卡 */
    private val relicChoiceTries = mutableMapOf<String, Pair<String, Int>>()

    /** PC 版 `REL_TAP_MAX`(471)：同一组候选卡最多点几次（点不动就不再点，等 idle 上限停手）。
     *  ★2026-11-14（用户）：2 → 4 —— 点空/点早再点一次是安全动作，别卡在同一坐标上干等。 */
    private const val REL_TAP_MAX = 4

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

    /**
     * ★进编组页后"等 UI 铺好"的**单轮**上限（2026-11-07）。
     *
     * 原来这件事分**两轮**做：第 1 轮 `awaitPartyIconsStable()` 之后直接 `return`，
     *   第 2 轮才继续 ⇒ 白等**一整个主循环**（外加多读一次编组页）。
     * 现在改成**同一轮内**轮询到"页面内容换成新的了"就继续 ⇒ 这个上限就是"本轮最多再等多久"。
     */
    private const val PARTY_ENTRY_WAIT_MS = 900L

    /**
     * ★Boss 页点「决定」后**等切队生效**的上限（2026-11-07）。
     *
     * ★为什么必须等：切队是**异步**的，点完 200ms 往往还没生效 ⇒ 原来判成"没反应"就**连点第 2 下**
     *   ⇒ **把中间的队伍整个跳过去**（队伍2 空、队伍3 有人 = "前面空着" = 非法配置 ⇒ 游戏不认
     *   ⇒ 点「决定」永远没反应 ⇒ 死循环卡死）。实机 18:36:10~18:36:32 反复点了 20 多次。
     *   现在改成"点一下 → 轮询等它真的切过去"，**绝不连点**。
     */
    private const val BOSS_SWITCH_WAIT_MS = 2_000L

    /**
     * 前台闸门"读不到焦点窗口"的容忍轮数（2026-11-02 实机事故）：
     * 点「挑战/开打」进战斗的**那一瞬**窗口在重建，`dumpsys window displays` 给不出
     * `mCurrentFocus`（21:27:35 实测），旧代码把 null 当"切走了"⇒ 立刻停手、整局作废。
     * 现在只当"这一刻读不到"：本轮不点、等下一轮；连挂 [FRONT_NULL_MAX] 轮（≈4s）才真停手。
     * （真被切走时读得到包名=别的 app ⇒ 那条路仍然立刻停手，不受影响。）
     */
    private const val FRONT_NULL_MAX = 12


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
     * "空清单但货架上有候选"最多丢弃几次。
     * 连续超过这个次数就不再纠缠（照清单执行），避免因为判据本身出错把流程卡在商店页。
     */
    private const val SHOP_QUOTA_REJECT_MAX = 6

    /**
     * 同一件点击**最多重试几次**（2026-11-02）：点完下一轮读回货架，若该槽位仍可买说明没点中，
     * 就再点一次；超过这个次数仍没买到 → 放弃该件（清单扣件继续下一件），避免死循环。
     */
    private const val SHOP_PEND_MAX = 2

    /**
     * ★**商店购买子链**（2026-11-02 用户方案："点购买 → 点确认 → 点买到商品的消息（此时计数）→
     * 回商店"）。实测 21 局 574 笔购买，序列**一笔不差**：
     *
     * ```
     * 559 次  DialogCommonMiddle → DialogCommonSmall → DialogLabyrinthShop
     *  15 次  Shop → Middle → Small → Shop        （多采到一次商店页，属抖动）
     * 按钮    TwoButtonRight（确认购买） → OneButtonBlue（到账） → OneButtonWhite（获得提示，压在商店页上）
     * ```
     *
     * `OneButtonWhite` 出现次数 = 购买笔数（574 = 574），即**每笔都有一层获得提示**，只是它 `top`
     * 仍报商店页 ⇒ 只能靠"走完 确认→到账→回商店"来判定成交。
     *
     * 为什么必须有这条链：买完到"回商店"之间要 ~4s，这期间 `handleShop` **根本没被调用**
     * （通用弹窗段点到按钮就 `continue`）⇒ 旧代码一回到商店就按"金币/货架"反推，既可能读到未刷新的
     * 快照，又会在交易未结束时重复点买（点在弹窗上 ✗）。所以这里用子链做**交易锁 + 到账计数**：
     *   · 交易进行中 ⇒ [handleShop] 本轮直接返回，不买下一件、不重算；
     *   · 走完 确认→到账→回商店 ⇒ **此时才计数**（扣清单、记"✔ 买到"）；
     *   · [SHOP_BUY_TIMEOUT_MS] 没走完 ⇒ 交回"金币/货架"判据兜底（那条已修成看钱和同款件数）。
     */
    private const val SHOP_BUY_TIMEOUT_MS = 15_000L

    /**
     * 点买之后"**等确认框**"单独给的时限（2026-11-02 实测补）：正常 ~1.7s 就弹出来，
     * 超过 4s 说明这一下点击没生效（进店第一帧的槽位坐标是残值 ⇒ 点在空处），
     * 应当立刻交回「金币/货架」判据重试，而不是占着交易锁干等满 [SHOP_BUY_TIMEOUT_MS]。
     */
    private const val SHOP_BUY_CONFIRM_WAIT_MS = 4_000L

    /**
     * ★商店金币读取重试（2026-10-18 用户："不会是进了商店、没读取到金币数，误以为没有操作直接退出吧"）。
     * coin 读到 null/0 时**先别决策**：按 0 走会"一件都买不起、也刷不起" ⇒ 直接点「关闭」退出，
     * 正好就是"5 层攒了 2w 金币、什么都没买就退出"。窗口内每 [SHOP_COIN_GAP_MS] 重读（读到 >0 立刻继续），
     * 超时仍为 0/空才按 0 继续（真一分钱没有时不会卡死）。
     */
    private const val SHOP_COIN_WAIT_MS = 10_000L

    /** ★2026-11-10：刷新后"新货架还没铺好"的宽限（这期间 `avail==0` 当未就绪，重读而不是关店） */
    private const val SHOP_REFRESH_SETTLE_MS = 1_200L

    /** ★2026-11-11（用户）：点「更新」没弹确认框 = 点早了/点空，**不是"本店不能刷"** ⇒ 重试上限 */
    private const val SHOP_REFRESH_TRY_MAX = 2

    /** ★连续多少轮都点不到「更新」才怀疑界面异常 → 停手留人工（不关店） */
    private const val SHOP_REFRESH_FAIL_MAX = 6

    // ── 商店「动画链」时间参数（2026-11-10 用户方案：买/刷新/关闭都按 弹窗出现→消失 推进） ──

    /**
     * 首下点击后，等"确认框出现"的上限（新框以顶层指针变化识别）。
     * ★这是"最长等多久"而非"固定等多久"：awaitNewBox 一发现新框就立即返回 ⇒ 快设备零影响。
     * ★2026-11-21 提 1000→5000（redroid 11 反馈商店购买出错）：实测确认框弹出延迟 ~1.7s，
     *   旧 1s 超时会让链断在半路、确认框随后被通用兜底乱点 ⇒ 选角色页读不到、游戏被点回标题页。
     *   放到 5s 给更慢的设备留余量；代价只在"真点空/无确认"时多等（极少发生）。
     */
    private const val SHOP_ANIM_BOX_WAIT_MS = 5_000L

    /**
     * 点确认后，等"框开始消失"的探测窗口；没开始消失就用新坐标补点。
     * ★2026-11-10 实测 220ms 太短（tween 在 +200~300ms 才启动）⇒ 每次都判"没生效"、多补一下，
     *   日志里每件都出现 `#3` 的白点；改成 400ms。
     */
    private const val SHOP_ANIM_CLOSE_PROBE_MS = 400L

    /**
     * 每个框最多补点几次（动画期点空是正常代价，多点当手段）。
     * ★2026-11-12（用户）：3 → **5** —— 停止点击的真正判据是「读到 `closing`」（[tapBoxButton] 的
     *   `xyAt` 里一读到位就返回 null），`max` 只是兜底上限；性能差的设备 tween 更慢、需要更多次补点，
     *   把上限调小反而对慢设备不友好。
     */
    private const val SHOP_ANIM_TAP_MAX = 8

    /** 到账确认链的最长时长（遗物 1 个框；印记 1+1；角色 1 个） */
    private const val SHOP_ANIM_RECEIPT_MS = 4_000L

    /**
     * ★A 方案：回到商店页后，等「刚买的那件标成已售」的上限。
     * 注意弹窗还盖着时下面的货架**不会变**（P5 截图），所以必须等 `drainShopReceipts` 回到商店后再读；
     * 用 [AgentApi.labShopItems]（3~5ms/次）每 120ms 一次。
     */
    private const val SHOP_ANIM_SOLD_WAIT_MS = 1_200L

    /** 刷新确认后，等"货架签名真的变了"的上限 */
    private const val SHOP_ANIM_REFRESH_MS = 2_500L

    /** 关闭确认后，等"离开商店页"的上限
     *  ★2026-11-20：2000→800。正常靠"商店开始收起"就返回；这里只是兜底上限（超时就交主循环读状态）。 */
    private const val SHOP_ANIM_CLOSE_BOX_MS = 800L

    /**
     * 动画链探针间隔（`labDlgBox` ~5~8ms）。
     * ★2026-11-13（用户）：60 → **70ms** —— 商店这些链只在等框出现/消失，70ms 一拍足够跟手，
     *   还能少刷一点 RPC（这个常量只被商店链用，其它模块不受影响）。
     */
    private const val SHOP_ANIM_POLL_MS = 70L


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
        fxDumps = 0
        fxAt = 0L
        outTailNode = 0
        outSpeedDone = false
        CenterTapGate.reset("新的一局开始")
        // ★演出加速补丁跟随 agent 会话（写一次持续整个会话）；agent 若重连，重新打一次
        labSpeedupOk = false
        labSpeedupTries = 0
        LogFile.init(ctx.applicationContext)
        LogFile.beginRun("maze")
        PlanProbe.startRun(ctx.applicationContext)      // ★观测：一局一个 maze_seq_*.jsonl
        BossChain.reset()                              // ★Boss 链影子：每局从头算
        // ★局号写进**本局文件**（见 [roundLabel] 的注释）：日志页靠这行认「第几局」
        roundLabel?.let { AppState.addLog("── 本局：第 $it 局 ──") }
        runFinished = false
        lastScore = emptyMap()
        lastRewards = emptyList()
        failStrategy = RerollSettings.loadFailStrategy(ctx.applicationContext)
        retreatRequested = false
        manualTeamNodes.clear()
        manualSaid.clear()
        try {
            runBody(input, ctx)
        } finally {
            // ★本局结束（含取消/异常）一律关掉，绝不带到下一局/刷初始
            CenterTapGate.reset("本局结束")
            roundLabel = null      // 局号只对本次 run 有效，别泄漏给"只跑迷宫"这类入口
            val endTag = when {
                runFinished -> "DONE"
                // ★带上具体策略（2026-11-02 用户："日志里面的失败，按策略撤退能不能简单写一下是什么策略"）：
                //   归档名变成 ABORT_RETREAT_SCORE / _NOPLAN / _FAIL3，日志页直接显示原因
                retreatRequested -> "ABORT_RETREAT_" + (MazeRetreat.lastReason?.tag ?: "OTHER")
                stopRequested -> "ABORT_STOP"
                else -> "ABORT_EXIT"
            }
            PlanProbe.endRun(endTag)        // ★观测：补 run_end 行并关文件（失败自禁用，不影响本局）
            LogFile.endRun(
                done = runFinished,
                tag = endTag,
                reason = when {
                    runFinished -> "已过结算页、本局完整跑完"
                    retreatRequested -> "按策略「撤退重跑」：" +
                        (MazeRetreat.lastReason?.cn ?: "未知原因") + " → 本局作废，继续下一局"
                    stopRequested -> "handler 判定异常/卡死 → 收工"
                    else -> "未到结算页就退出（取消/掉线/异常）"
                },
            )
        }
        // ★一局收尾：正常结束才清掉"层数/状态"（"第几局"由连跑循环管，别在这儿动）。
        //   ★2026-11-14（用户）：停手（打不过 / 判不出 / 需要人接手）时**保留**第 3 格并换醒目配色，
        //   否则界面上什么都看不到，用户不知道已经停了。下次 setFloor/setState 自动恢复普通样式。
        if (stopRequested) {
            OverlayStatus.setState("已停手：${stopReason ?: "需要手动处理"}", attention = true)
        } else {
            OverlayStatus.setState(null)
            OverlayStatus.setFloor(null)
        }
        return runFinished
    }

    private suspend fun runBody(input: InputBackend, ctx: Context) {
        AppState.addLog("── 迷宫内状态机启动 ──")
        // ★本局状态清零（2026-10-19 修）：这两个是单例字段，跨局残留 ⇒ "撤退重跑"开的新局
        //   在同一失败页从 4/3 起步、一失败就再触发撤退（实测 19:51/19:52 连续 4/3、5/3 死循环）
        nodeFailed.clear()
        manualTeamNodes.clear()
        stopReason = null                    // ★本局停手原因从头记（悬浮窗提示用）
        scoreChaseChecked = false            // ★凹分检测：每局重置（新局重新查一次）
        pendingEventRetreat = null            // ★条件 2：上局命中的断言不能带到本局
        saidLogicMode2 = false
        eventRuleTracker = EventRetreatRule.Tracker()   // ★条件 2：断言进度每局从头算
        logicEngine.reset()                  // ★逻辑模式：8 个 token 每局从 0 开始（若是同局续接，下面 fire 时会按 enterId 读回）
        logicEngine.rules = RerollSettings.loadLogicRules(ctx)  // 开局读一次（中途改配置下局生效，token 语义才稳定）
        nodeEventFacts.clear()
        logicFiredNode = null                // ★本局每个节点都能各 fire 一次
        logicRestoreTried = false            // ★cs128：续局恢复每局只试一次（见 [logicRuleFire]）
        logicEnterId = -1L
        MazeRetreat.resetReason()            // ★上局的撤退原因不能串到本局（归档 tag 要用）
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
            // ★策略页「角色选择」的两项设置（2026-11-13）：属性印记顺序 + 不使用 NPC。
            //   每局构造时读一次 ⇒ 改设置后下一局生效（与 RerollSettings 其它项同口径）。
            attrOrder = RerollSettings.loadAttrOrder(ctx),
            excludeNpc = RerollSettings.loadExcludeNpc(ctx),
            byModel = RerollSettings.loadPickByModel(ctx),
        )
        charaPickRef = charaPick            // ★悬浮窗"编队：五个名字"要查名册（见 handlePartyEdit）
        // ★结算奖励的名字表（中文）：道具 / EX装备 id → 中文名
        run {
            val rn = GameData.rewardNames(ctx)
            rewardItemNames = rn.items
            rewardExNames = rn.exEquip
        }
        // 商店决策层（PC shop_plan / refreshDecision）；
        // ★精确 DP（3 层 b=3 / 5 层 b=2）优先接管「买几件 + 买完刷不刷」，4 层与关闭时走 PC 旧逻辑
        val shopPlan = ShopPlan(
            GameData.relicTables(ctx),
            dp = if (RerollSettings.loadShopUseDp(ctx)) GameData.shopDp(ctx) else null,
        )
        // 遗物三选一决策层（PC RelicPick）
        relicPick = RelicPick(GameData.relicTables(ctx))
        // 编队决策层要用：关卡判定表（boss/ex）+ 加速遗物技能值口径（PC: party_ctx_cached / _ex_resolve）
        PartyPlan.loadTables(ctx)
        // 本局遗物决策用的"已遇 Boss / 已点次数"状态
        bossByArea.clear()
        bossQuestByArea.clear()
        shopRelicCtx = null; shopRelicCtxKey = null   // ★商店遗物序的上下文（按店缓存，跨局必须清）
        shopBuyStep = 0; shopBuyAt = 0L              // ★购买子链也不能跨局（上一局杀进程前的半截交易）
        frontNullRounds = 0                          // 前台读空计数（见 [FRONT_NULL_MAX]）
        switchArmed = false                          // 切屏保护每局重新按阶段开启（见字段注释）
        evSnapId = null                              // 事件效果快照（跨局必须清，见 [settleEventDiff]）
        relicChoiceTries.clear()
        partyScroll.clear()                     // 编队页"滚动找缺员"的每页状态（本局重来）
        partyScrollNodeKey = null               // ★2026-11-15：换局 ⇒ 滑动方向/减速状态从头算
        partyTeams.clear()                      // 多分队：本进程已确认过的各队
        partyFillSaid.clear()                   // 多分队：编队策略日志去重
        partyCleaned.clear()                    // Boss 页"腾空继承队"标记
        // ★★特别装备的两个"跨局残留"缓存（2026-10-22 用户："选完人之后有时候不选 ex 装备"）：
        //   ① `exeqSessCur`（★2026-11-08 改成**单例**了：EX 只出现在当前这个节点的编组页上，
        //      原来按 nodeKey 存的 map 已经不需要），里面的 `exDone=true` 会让
        //      **下一局同一节点静默跳过装备**（连日志都没有）；
        //   ② `exequipLastTeam1`（同队 ⇒ 认为装备没被动过）只在 `GameProcess.kill()` 时清过，
        //      局内刷开局/不杀进程连跑时，上一局的会留到本局 ⇒ 首战同队就直接跳过。
        //   新一局的装备是重置的，这两条假设只在本局内成立 ⇒ 每局开头必须清。
        exeqSessCur = null
        exequipLastTeam1 = null
        selNoCardHeals.clear()                  // 选人页"读不到卡片"的自愈计数
        relicBadHeals.clear()                   // 遗物候选"读数全是垃圾"的自愈计数
        battleDumps = 0                         // 战斗结算页对象字段抓取（每场一次、每局上限）
        saidRelicErr = ""                       // 持有遗物读取异常提示（本局重新报一次）
        resMemDiagKey = null                    // 结算按钮字段读诊断（每局重新抓一次）
        dlgTapSig = null                        // 弹窗点击熔断（本局重新计数）
        dlgTapN = 0
        seenKills = GameProcess.kills           // 本局开始时的"游戏被杀次数"基线（见主循环 0.35）
        // ★2026-11-10（用户反馈：新局商店里出现了"上一局的 3★ 清单"）：
        //   `shopQuota` 等是**单例字段**，跨局必须清 —— 否则新局进同一家店（blockId/area 相同）
        //   会沿用上一局的清单，按一个货架上根本不存在的品类挑件 ⇒ 一件不买。
        shopQuota = null; shopQuotaKey = null; shopQuotaRejectN = 0
        shopRefreshKey = null; shopRefreshCount = 0; shopRefreshAt = 0L
        shopRefreshFailN = 0
        shopBought = false
        shopPendKind = null; shopPendGi = -1; shopPendRetry = 0
        shopPendRid = ""; shopPendPrice = 0; shopPendCoinBefore = 0; shopPendCntBefore = 0
        shopBuyStep = 0; shopBuyAt = 0L
        shopDragCount = 0; shopDragTarget = null
        shopCoinBadKey = null; shopCoinBadAt = 0L
        shopRelicCtx = null
        SampleLog.reset()                        // ★本局随机信息采样：去重表清零（同一节点每局都可再记）
        ShopSampleLog.reset()                    // ★商店采样去重表：每局清零，否则 seen 跨局单调增长（文件有轮转、内存 set 没有）
        // 刷开局产物（reroll）注入：开局前就已知 3/5 区 Boss 与每区最优路线，
        // 消除"开局读不到 3 区 Boss / 完整地图"的差异（PLAN §0.6 原定不碰，本次已实现）。
        val reroll = RerollState.result
        val rerollRoute = reroll?.routePerArea
        rerollRoutes = rerollRoute                    // ★给移动分支用（见 [handleMove]）
        if (reroll != null) {
            reroll.bossByArea[3]?.let { bossByArea[3] = it }
            reroll.bossByArea[5]?.let { bossByArea[5] = it }
            reroll.bossQuestByArea[3]?.let { bossQuestByArea[3] = it }
            reroll.bossQuestByArea[5]?.let { bossQuestByArea[5] = it }
            val b3 = bossByArea[3]
            val b5 = bossByArea[5]
            val routeAreas = rerollRoute?.keys?.sorted()?.joinToString(",") ?: "无"
            AppState.addLog(
                "已接入刷开局产物 ▶ 3区Boss=${b3}${PartyPlan.bossName(b3)?.let { "($it)" } ?: ""} " +
                    "5区Boss=${b5}${PartyPlan.bossName(b5)?.let { "($it)" } ?: ""} " +
                    "预计算路线区=[$routeAreas] ｜ 判定=" +
                    reroll.summary.ifBlank { "价值=${reroll.actual}/${reroll.theory}" },
            )
            AppState.addLog(
                "（局内将沿预计算路线推进，不再现算 DP；路线来源会在每次 [路线] 日志里标注" +
                    "—— 路线里那个「总分」只是节点分值展示，不代表判定用了经典模式）",
            )
        } else {
            AppState.addLog("未检测到刷开局产物（RerollState 为空）→ 局内按 DP 现算路线走（PC 同款兜底）")
        }
        // ★2026-11-13：产物只属于这一局，消费完立刻清掉 —— 否则某局刷开局失败却仍进了状态机时，
        //   会沿用上一局的 Boss / 预计算路线走错图（全局单例跨局不清）。
        RerollState.result = null
        // 事件选项表（PC load_event_choice 5923）：event_id → 第几项（缺省 1）
        EventPick.load { name ->
            ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        // ★事件结果表（2026-11-03）：eventResultId ↔ 「事件/卡片/分支/效果」对照，由 master 库生成
        EventResult.load { name ->
            ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        // ★叠加用户在策略页「事件」里改过的选择（相对表内默认值的覆盖）
        EventPick.applyPicks(EventSettings.loadPicks(ctx))
        // 每个新局重置编队缓存（角色池/加速技能值可能不同）
        PartyPlan.appCtx = ctx          // ★「不使用 NPC」设置要读 App 存储（2026-11-13）
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
        // ★2026-11-12（用户）：本局启动阶段"前台不是游戏"只提示一次（不判切屏，见 switchArmed）
        var startupSaid = ""

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
                tapProbeKey = null               // 重连回来是"新现场"，旧的点击探针作废
                tapProbeTag = null
                tagAt = SystemClock.elapsedRealtime()
                hbAt = tagAt
                idle = 0
                tries.clear()
                pageRounds.clear()
                resetBattleTimers()
                AppState.addLog("[✓] 已重连并回到迷宫 → 继续跑本局")
                continue
            }
            // ★安全闸门（同 Navigator，2026-11-02 对齐）：**读到别的包** ⇒ 立刻停手；
            //   **读不到**只说明"这一刻没有焦点窗口"（进战斗/场景切换时窗口重建，实测 21:27:35）
            //   ⇒ 本轮不点、等下一轮，连挂 [FRONT_NULL_MAX] 轮才停手。
            // ★★2026-11-17（查清 ANR 根因后改）：这里原来每轮 `force = true` ——
            //   主循环 100ms 一轮 ⇒ **每 100ms 一次 `su dumpsys input`**（每次 1~2 个 fork，
            //   实测每秒 8~9 个）。`dumpsys` 要 system_server 序列化整个服务状态（几百 ms），
            //   每秒十次 dump 直接把 system_server 拖到 ANR ⇒ lmkd 以
            //   "device is not responding" 连环杀进程（实测内存 4.2GB 可用、助手 CPU 仅 2.5%
            //   仍被杀，21:23 与 22:31 两次都如此）。
            //   现在改走 **2s TTL 缓存**：`AutoForegroundService.watchLoop` 本身每 2s force 刷新
            //   （切屏锁存 `noteFocus` 也是它写的），这里命中缓存即可。
            //   ⇒ dumpsys 从 ~10 次/秒 降到 ~0.5 次/秒；切屏最坏延迟 2s，且此时游戏在后台、
            //     点击本就无效，风险远小于把系统拖死。
            // ★★2026-11-17：**前台判定改用 Unity `Application.isFocused`**（零 shell、微秒级）。
            //   原来每轮 `dumpsys input` ⇒ 每秒十次 dump 把 system_server 拖到 ANR ⇒
            //   lmkd 以 "device is not responding" 连环杀进程（实测内存 4.2GB 可用也照杀）。
            //   语义也精确：切走=false；游戏卡死/加载/演出=true（**不是**用帧计数——帧不涨分不清
            //   "卡死"和"切屏"，那个归帧看门狗管）。
            //   · true  ⇒ 直接当"前台就是游戏"，**一次 dumpsys 都不发**；
            //   · false ⇒ 用 dumpsys 复核一次（切屏很罕见，代价可忽略），以防 isFocused 失灵误停；
            //   · null  ⇒ 读不到（agent 未就绪）⇒ 回退原来的 dumpsys 缓存。
            val focusNow = SystemClock.elapsedRealtime()
            val focused = gameForeground(focusNow)
            val frontPkg = when (focused) {
                true -> GameProcess.pkg()
                false -> {
                    val real = Foreground.packageName(force = true)
                    if (real == GameProcess.pkg()) {
                        if (!focusMismatchSaid) {
                            focusMismatchSaid = true
                            AppState.addLog(
                                "… isFocused 报失焦但前台仍是游戏（不一致）→ 以 dumpsys 为准",
                            )
                        }
                        GameProcess.pkg()
                    } else {
                        real ?: "@失焦"
                    }
                }
                null -> Foreground.packageName()
            }
            if (frontPkg != null && frontPkg != GameProcess.pkg()) {
                // ★2026-11-12（用户："不用限制时间，规定运行到某个阶段才开始算"）：
                //   切屏判定只在**本局进过迷宫之后**才生效（见下面的 `switchArmed` 置位）。
                //   启动/重启阶段（前台是桌面/助手、游戏还在加载或刚闪退）一律不算切屏，
                //   交给注入器自愈（进程没了/15s 没映射 → kill+launch），这里只等。
                if (!switchArmed) {
                    if (startupSaid != frontPkg) {
                        startupSaid = frontPkg
                        AppState.addLog(
                            "启动阶段：前台是 $frontPkg → 还没进过迷宫，不判切屏（等游戏起来）",
                        )
                    }
                    delay(500)
                    continue
                }
                AppState.addLog("停手：前台已切到 $frontPkg（不再碰游戏）")
                return
            }
            if (frontPkg == null) {
                frontNullRounds++
                if (frontNullRounds == 1 || frontNullRounds % 10 == 0) {
                    AppState.addLog("… 前台读不到（dumpsys 没给）→ 本轮不点，继续等（第 $frontNullRounds 轮）")
                }
                if (frontNullRounds > FRONT_NULL_MAX) {
                    AppState.addLog("停手：前台连续 $frontNullRounds 轮读不到 → 不再碰游戏")
                    return
                }
                delay(300)
                continue
            }
            frontNullRounds = 0

            // ── ★0.35) 游戏被重启过 ⇒ 清掉"建立在游戏侧状态还在"之上的缓存 ──
            //   2026-10-16 用户："为什么卡死重启之后组队没有选特别装备，是因为本场阵容和上一场一样吗？"
            //   —— **是**：`特别装备` 的跳过判据是"`sig == exequipLastTeam1`（与上一场同一套队）⇒ 装备没被动过"，
            //   而**游戏被重启**时游戏侧是重建的、这条假设不成立 ⇒ 会跳过本该做的特别装备。
            //   收口在 `GameProcess.kill()`（所有重启路径都经它，含注入器自愈）⇒ 这里只比对它的计数。
            if (GameProcess.kills != seenKills) {
                seenKills = GameProcess.kills
                exequipLastTeam1 = null                 // ⇒ 本场强制重设一次特别装备
                exeqSessCur = null                      // ★同节点不能沿用"已配好装备"（游戏侧重建了，2026-10-22）
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

            // ★★**分派读**（2026-11-08"需要什么读什么"）：这轮只用它来**分派**（页面/状态/战场），
            //   不再拉整包 —— eventId/questId/choices 这些**只有个别 handler 用得到**的字段，
            //   由那些 handler 自己按需读（[AgentApi.labExtra]，纯内存读，~0ms）。
            val st = AgentApi.labStatusLite()
            // ★2026-11-12（用户）：切屏保护从**本局第一次在迷宫里读到状态**开始生效。
            //   启动/重启阶段（还没进迷宫）前台怎么变都不算切屏；进过迷宫后再切走才停手。
            if (st != null && !switchArmed) {
                val v0 = st.optInt("viewId", -1)
                if (Navigator.isLabyrinthView(v0)) {
                    switchArmed = true
                    AppState.addLog("切屏保护已开启（已进入迷宫 ${Navigator.viewLabel(v0)}）")
                }
            }
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
                    AppState.addLog("[!] 分派读为空（不在迷宫/换区途中/读取失败），等待…（第 $nullReads 次）")
                }
                logMapSignals(null)      // ★诊断：读空时也打（换区窗口正是这段）
                // ★2026-11-03：读不到时**顺手看一眼视图**。掉回标题/登录页或主界面说明客户端掉线/被重启了
                //   （上次就在这里静默等了 16 分钟，日志只有"等待…（第 460 次）"）。
                //   ★用户 2026-11-03："重启了也没事，登录页随便点一下，再按正常流程进迷宫就行" ⇒
                //     **自动**点屏幕登录 → 走「黎明界 top → 出发」把这一局接回来，不再等人工。
                if (nullReads == 3 || nullReads % 20 == 0) {
                    val vid = Navigator.currentViewId()
                    if (vid == 1 || vid == 2 || vid == 5) {
                        val why = if (vid == 5) "掉回主界面" else "掉到标题/登录页"
                        AppState.addLog("[!] 游戏${why}（${Navigator.viewLabel(vid)}）→ 自动点屏幕登录并重进迷宫")
                        if (!recoverAfterRestart(input, "回到登录页")) {
                            AppState.addLog("[✗] 登录后没能回到迷宫 → 停手")
                            return
                        }
                        nullReads = 0
                        AppState.addLog("[✓] 已重新进入迷宫 → 继续跑本局")
                        continue
                    }
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
            // ★★2026-11-22：旧区结束(out)动画加速 —— 区尾节点结算完回地图那几秒密集探测
            //   （BOSS 战期间 stt=6 不满足，不轮询；新区入场/area 由 [moveBusyNow] 的 inAnimation 分支管）
            speedOldAreaExit(st, stt)
            // tag（PC 6004）：带 view —— statusType 在换页时可能不变，只看状态会漏判"页面已换"
            // ★2026-10-27：`viewClass` 为空时原来退化成**裸数字**（日志里出现 `view=29`，
            //   排查时完全看不出是战斗页）。现在走 Navigator 的名字表 ⇒ `view=BATTLE(29)`。
            val viewPart = jStr(st, "viewClass").ifEmpty {
                jStr(st, "viewId").toIntOrNull()?.let { Navigator.viewLabel(it) } ?: jStr(st, "viewId")
            }
            val tag = "节点${jStr(st, "blockId")} [${jStr(st, "panelTypeName")}] " +
                "状态=${statusName(stt)} view=$viewPart " +
                "顶框=${jStr(st, "pageTop").ifEmpty { "无" }}"
            // ★流程观测（2026-10-31）：**只记录、不判定**，放在所有 continue 之前 ⇒ 战斗页也覆盖。
            //   ⚠不要在这里加任何"期望页/下一步"判断 —— 那是链化阶段的事（见 PlanProbe 类注释）。
            PlanProbe.tick(st, tag, stt, statusName(stt), viewPart, idle)
            // ★Boss 链影子（2026-10-31）：只把"当前这页属于第几步"解释出来写日志，不接管动作。
            //   跑几局看吻合率（seq_report 的【Boss 链】一节）再决定是否让它驱动。
            BossChain.tick(jStr(st, "panelTypeName"), jStr(st, "blockId"), jStr(st, "pageTop"), stt)
            // ★商店购买子链（2026-11-02）：只观测"确认框/到账提示"有没有走到，不点击（见 shopBuyStep）
            shopBuyWatch(jStr(st, "pageTop"))

            val now = SystemClock.elapsedRealtime()
            // ★场景切换避让（2026-10-17）：`viewClass` 一变（进/出战斗、换区）说明**场景正在加载**，
            //   这时做大范围遍历会把游戏主线程拖死（00:42 / 02:01 / 08:58 三次卡死都在切换后 ~2s 内）
            //   ⇒ 记一个"解除时刻"，其间只读状态、探测与弹窗遍历都先停（点按钮不受影响 ✓）。
            val vseen = jStr(st, "viewClass")
            if (vseen != lastViewSeen) {
                lastViewSeen = vseen
                sceneHoldUntil = now + SCENE_HOLD_MS
            }
            // ★2026-10-27「点早了就下次多等」的探针结算：上一轮点过带 learnKey 的按钮，这一轮回来看现场变没变。
            //   变了 ⇒ 点成功了（不回调，避免越学越慢）；没变 ⇒ 那会儿按钮还不可点 ⇒ 该 key 下次多等一点。
            //   判据是现成的 tag，不增加任何读取。
            curTag = tag
            tapProbeKey?.let { pk ->
                tapProbeKey = null
                if (tag == tapProbeTag) {
                    val c = appCtx
                    if (c != null) {
                        // 结算页是"明确很久"的例外：一次 +600、上限 3000；其余：一次 +300、上限 1200
                        val isSettle = pk.startsWith("settle|")
                        val nv = TapDelayStore.miss(
                            c, pk,
                            if (isSettle) 600L else 300L,
                            if (isSettle) 3_000L else 1_200L,
                        )
                        AppState.addLog(
                            "… 「$pk」点了现场没变（点早了）→ 该按钮下次多等 ${nv}ms" +
                                if (nv == 0L) "（已到上限，清空重学）" else "",
                        )
                    }
                }
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
                // ★窗口转移观测（2026-11-06）：换页时读一次归一化窗口名，比对上次点击的期望。
                //   只记日志、不改行为；用来攒「from + action → after + 耗时」样本。
                runCatching {
                    lastTop = AgentApi.labTop() ?: ""
                    checkTransition(lastTop)
                }
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
            val inBattleBranch = st.optBoolean("inBattle", false) &&
                BATTLE_INTERRUPT_PAGES.none { it in top }
            // ★2026-11-13（用户）：**事件触发的战斗**（魔物竞技场 1105x 这类）直接从事件页进 BATTLE、
            //   不走预览页 ⇒ 在这里补一条采样（"敌人编号" = 事件 id，事件决定打哪队敌人）。
            //   只在「15s 内刚有事件」时记，避免普通战斗重复记账。
            if (inBattleBranch) {
                if (!eventBattleSampled) {
                    eventBattleSampled = true
                    runCatching {
                        if (SystemClock.elapsedRealtime() - lastEventAt < 15_000L && lastEventId > 0) {
                            SampleLog.eventBattle(appCtx, jStr(st, "blockId"), lastEventId)
                        }
                    }
                }
            } else {
                eventBattleSampled = false
            }
            if (inBattleBranch) {
                // ★★「等待接手」的解除点（2026-11-09 用户）："检测到战斗中才继续自动操作"。
                //   用户自己组队开战后把「手动节点」标记清掉 ⇒ 后续流程恢复自动。
                if (manualTeamNodes.isNotEmpty()) {
                    AppState.addLog("[手动] 检测到战斗中 → 解除「等待接手」，恢复自动")
                    manualTeamNodes.clear()
                    manualSaid.remove(WAIT_GATE_TAG)
                }
                if (battleTick(input, st)) {
                    if (retreatRequested) {
                        AppState.addLog("[✗] 按策略「撤退重跑」→ 结束本局（服务层会调撤退 API 并继续下一局）")
                        return
                    }
                    idle = 0
                    // ★2026-11-17（用户：效率优先）：保持 100ms —— 实测降到 200ms 会拖慢结算页发现。
                    delay(POLL_MS)
                    continue
                }
            }
            // 能走到这里 ⇒ 已不在战斗视图（或失败结算已落回）→ 计时/档位复位（PC 6196~6201）
            resetBattleTimers()

            // ★★「等待接手」全局闸门（2026-11-09 用户）：等待期间用户可能自己打开 EX 三连页
            //   调整特别装备，而原来只有编组页/预览页两个 handler 会停手 —— 页面一离开编组页，
            //   下面的 EX 兜底(1.5)、通用弹窗白名单(0)、点屏幕中心就会把用户开的页**自动点掉**。
            //   ⇒ 这里一刀切：等待接手期间只要还没进战斗，就只观察、不点任何东西。
            if (manualTeamNodes.isNotEmpty() && !inBattleBranch) {
                if (manualSaid.add(WAIT_GATE_TAG)) {
                    AppState.addLog("[手动] 等待接手期间不自动操作（含特别装备页），进战斗后自动恢复")
                }
                idle = 0
                delay(POLL_MS)
                continue
            }

            // 1.5) ★特别装备三连页（叠在编队页之上、独立 DialogCommonBig/FullSize）。
            //     ★★2026-11-08 改法：三连页改成**由编组页 handler 打包跑完**（[handleExequipPacked]：
            //     点开「特别装备」后在**同一次调用里**把①②③走完）⇒ 主循环**正常情况下根本看不到 EX 页**。
            //     这里留的是**兜底**：万一 EX 页还压在上面（上一轮没收干净 / 用户手动开的），
            //     就**自己把它走完** —— 绝不能交给下面的通用弹窗白名单：那会命中
            //     ThreeButton*/TwoButton* ⇒ 误点"装备确定/确认"打乱三步顺序（2026-11-08 实测）。
            //     ★照 PC `EXEQ_CONTAINERS`(2906)：**只有 pageTop 是这两个容器**时才可能出这三页，否则**别每轮扫**
            //       —— `labExEquipTop` 要遍历 DialogArea 子节点 + 逐个 GetComponentInChildren，很贵；
            //       在预览页/编组页上每轮空扫会把"预览页出现→点挑战"拖到 7~8s（实测 2026-10-16）。
            if ((stt == 5 || stt == 6) && top in EXEQ_CONTAINERS && !exeqSess().exStuck) {
                val exTop = runCatching { AgentApi.labExEquipTop() }
                    .getOrNull()?.let { jStr(it, "top") } ?: ""
                if (exTop.isNotEmpty()) {
                    // ★★只要检测到 EX 页就接管（**不再看 exDone**）：
                    //   打包流程是幂等的（按当前页面状态决定该点哪个），而且能补上
                    //   "上次没点「装备确定」就走了"的缺口 —— 否则 EX 页留在屏幕上，
                    //   后面所有操作都在**错的页面**上做（2026-11-08 实测 10:29:36~10:29:45）。
                    val es = exeqSess()
                    // ★★2026-11-22（0.7.16 Boss 空二队卡 EX 页反馈）改成**状态判断**：
                    //   壳（EX 容器）从顶层消失后才能再接管 —— 壳真没了 exTop 为空、压根进不到这里；
                    //   能走到这说明壳还在退场动画 ⇒ 继续放行，绝不当成"EX 又开了"重走一遍
                    //   （旧版只放行固定 2s，慢设备动画实测最长 11s ⇒ 2s 后误接管，
                    //     在已切到的空二队上又走三连页、没角色可装备 ⇒ 卡在特殊装备页）。
                    //   ★不再靠时间"猜"壳消失没有；只有壳异常赖着不走才在 EXEQ_CLOSE_GUARD_MS 后
                    //     清标志、恢复接管兜底（仍受 EXEQ_FB_MAX 保护，不会无限循环）。
                    if (es.earlyExitAt > 0) {
                        if (System.currentTimeMillis() - es.earlyExitAt < EXEQ_CLOSE_GUARD_MS) {
                            idle = 0
                            delay(POLL_MS)
                            continue
                        }
                        es.earlyExitAt = 0     // 壳迟迟不退 ⇒ 放行到期，允许兜底重接管收掉它
                    }
                    es.fbN++
                    if (es.fbN > EXEQ_FB_MAX) {
                        es.exStuck = true
                        AppState.addLog(
                            "[!] 特别装备三连页连续 $EXEQ_FB_MAX 次收不掉 → 停止自动处理，请手动关掉它",
                        )
                    } else {
                        handleExequipPacked(input)
                    }
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
            //      特别装备三连页已搬：在上方 1.5) 处、白名单之前拦截，交给 handleExequipPacked（不进白名单，
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
                PAGE_LAB_RESULT in top ||        // ★迷宫结算页也算（2026-10-17：它上面跑一次 dialog() 实测 3.2s ✗）
                // ★★2026-11-20（205 事故）：**商店页也算 own page**。它有自己的 handler（handleShop），
                //   原来漏了 ⇒ 每轮都跑通用弹窗扫描 ⇒ 把商店里的 `OneButtonBlue` 当弹窗点了
                //   （实测：进店 0.4s 后又点一次 ⇒ 重复进店 ⇒ 205 回标题）。
                PAGE_SHOP in top ||
                // ★★2026-11-20（用户："初始角色一个个点 / 选角后等很久"）：**开局选角色页**同样漏了。
                //   它的 pageTop 是 `DialogCommonFullSize`（跟 EX 页同壳，不能按名字认），只能按状态认：
                //   statusType==1 的三种脸里，扣掉"战后选人"(Selection) 和"失败页"(QuestFailed) 剩下的
                //   就是**初始编成页**。不排除 ⇒ 每轮白烧 `dialog()`575ms + `findTextIn`575ms ≈1.15s，
                //   3 个角色被拆成 2s 才点完（实测 15:56:23.4→25.5）。
                (stt == 1 && PAGE_LAB_SELECTION !in top && PAGE_QUEST_FAILED !in top)
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
            // ★★结算演出加速（cs125，2026-11-22 用户方案）：把迷宫结算的演出时长一次性压到 0.05s
            //   （②屏滚分/三块逐个显示/关闭按钮等待/宝箱落下——全在 `LabyrinthDefineData` 静态单例，
            //   客户端视觉参数，写一次持续整个会话，游戏重启恢复默认）。
            //   ⚠Instance 只在迷宫运行期间非空（出迷宫即 Release）⇒ "未就绪"不算失败、每轮重试，
            //   每局 newRun 都会重置重打（防资产每局重建补丁丢失）；只有硬错误才计数（5 次放弃）。
            if (!labSpeedupOk && !inBattleNow) {
                val sp = runCatching { AgentApi.labSpeedup() }.getOrNull()
                if (sp?.optBoolean("ok") == true) {
                    labSpeedupOk = true
                    val n = sp.optJSONArray("params")?.length() ?: 0
                    AppState.addLog("… 结算演出加速已生效（$n 项演出时长 → 0.05s）")
                } else {
                    val err = sp?.let { jStr(it, "err") } ?: "无回包"
                    if (!err.startsWith("Instance 未就绪")) {
                        labSpeedupTries++
                        if (labSpeedupTries == 5) {
                            AppState.addLog("[!] 结算演出加速失败（$err）→ 仍用点中心加速")
                            labSpeedupOk = true
                        }
                    }
                }
            }
            // ★★「过了结算分数页」→ 打开中心加速（2026-10-22 用户方案）。
            //   ⚠必须放在通用弹窗段**之前**：奖励页第一屏（AnimationViewer）如果要先跑一次
            //   `dialog()`（实测 3~7s）才打开，就白白错过第一屏（11:34 那局正是：先烧 3.2s 才开）。
            //   判据："看到**新的非结算页面**"——分数页本身还挂着时不点；过渡期读空也不点。
            if (labResultSeen && !CenterTapGate.isActive() &&
                top.isNotEmpty() && PAGE_LAB_RESULT !in top && !inBattleNow
            ) {
                CenterTapGate.activate("已过结算分数页（$top）")
            }
            // ★2026-11-22 探针：BOSS/EX 结算点完「下一步」→ DialogLabyrinthResult 之间有一段
            //   **地图层的通关庆祝动画**（日志实证 ~4.5s、无 Dialog 壳）。每 300ms 采一次
            //   `labMapBusy`，看哪个句柄在跑（unitMove/areaSlide/finish/inAnimation/mapModeFinish）。
            if (((st.opt("blockId") as? Number)?.toInt() ?: -1) == 50801 &&
                !labResultSeen && !inBattleNow && now - celebrateAt >= 300L
            ) {
                celebrateAt = now
                val b = runCatching { AgentApi.labMapBusy() }.getOrNull()
                if (b != null && b.optBoolean("ok")) {
                    fun bit(k: String): Int = if (b.optBoolean(k)) 1 else 0
                    AppState.addLog(
                        "[庆祝] unitMove=${bit("unitMove")} slide=${bit("areaSlide")} finish=${bit("finish")} " +
                            "inAnim=${b.opt("inAnimation")} mapMode=${bit("isMapViewMode")} " +
                            "notFired=${bit("moveEventNotFired")} forceClear=${bit("isForcePlayPanelClearAnimation")} " +
                            "phase=${bit("phaseActive")} anim=${bit("animActive")}",
                    )
                }
                // ★★2026-11-22 回滚：这里原来每秒调 `labDumpActiveFx` 扫 canvas 找通关庆祝特效，
                //   实测在 BOSS 战（blockId=50801）期间与结算页重活叠加 ⇒ **游戏主循环冻住 80s**
                //   （10:46 那局自愈重启）。庆祝动画改走别的手段，这里不再做任何扫描。
            }
            // ★cs128 探针（2026-11-21 用户："加探针吧，验证一次花的时间比较多"）：结算尾段的
            //   演出壳/FullSize/Big 每个**新顶框类**只探一次（[AgentApi.probeShell] 只读、不点）。
            //   一次采集"3/4 页能不能直调"的全部信息：字段槽按钮 + 直调候选方法 + 对象字段 + 壳内按钮节点。
            if (labResultSeen && top.isNotEmpty()) {
                val shell = top.removeSuffix("(Clone)")
                if (shell in LAB_TAIL_PROBE_SHELLS && tries.getOrPut("$tag|probe_$shell", { 0 }) == 0) {
                    tries["$tag|probe_$shell"] = 1
                    val pr = runCatching { AgentApi.probeShell() }.getOrNull()
                    if (pr?.optBoolean("ok") == true) {
                        val ms = pr.optJSONArray("methods") ?: JSONArray()
                        val methods = (0 until ms.length()).joinToString(",") { ms.optString(it) }
                        AppState.addLog(
                            "[探针] $shell（comp=${pr.optString("compCls")}）" +
                                "字段槽按钮=[${jArrCompact(pr, "buttons", listOf("name", "x", "y"))}]" +
                                "｜直调候选=[$methods]",
                        )
                        AppState.addLog("[探针] $shell 节点=[${jArrCompact(pr, "nodes", listOf("name", "x", "y"))}]")
                        val flds = pr.optJSONArray("fields") ?: JSONArray()
                        val sb = StringBuilder()
                        for (i in 0 until flds.length()) {
                            sb.append(flds.optString(i)).append("；")
                            if ((i + 1) % 8 == 0 || i == flds.length() - 1) {
                                AppState.addLog("[探针] $shell 字段: ${sb.trimEnd('；')}")
                                sb.setLength(0)
                            }
                        }
                    } else {
                        AppState.addLog("[探针] $shell 失败：${pr?.optString("err").orEmpty().ifEmpty { "无回包" }}")
                    }
                    // ★2026-11-22（用户）：3 屏演出壳额外 dump **动画组件**（找能否用 Animator.speed 加速，
                    //   免去"必须点中心才能跳过"）。一次结算采一次，不依赖外部时机。
                    if (shell == "DialogAnimationViewer" || shell == "DialogLabyrinthResult") {
                        // ★2026-11-22（用户"2/3屏加速做掉"）：把壳内 Animator.speed 设大 ⇒ Spine/滚分瞬间播完。
                        val spd = runCatching {
                            AgentApi.obj("labSpeedupAnim", listOf<Any?>(50.0), timeoutMs = 4_000L)
                        }.getOrNull()
                        if (spd != null) {
                            AppState.addLog("[加速] $shell Animator=${spd.optInt("count")} speed=50（${spd.optString("err")}）")
                        }
                        // ★★2026-11-22：**2 屏不采组件** —— 它的壳挂在 canvas 下，`_walkForRoot` 会遍历
                        //   4936 个节点（实测 3649ms）。3 屏的壳是独立壳（55 节点），照常采。
                        val dc = if (shell == "DialogAnimationViewer") {
                            runCatching { AgentApi.obj("labDumpComps", timeoutMs = 15_000L) }.getOrNull()
                        } else null
                        if (dc != null) {
                            val hits = dc.optJSONArray("hits") ?: JSONArray()
                            AppState.addLog(
                                "[组件] ${dc.optString("top")} 节点=${dc.optInt("nodes")} 命中=${hits.length()}",
                            )
                            for (i in 0 until minOf(hits.length(), 20)) {
                                val h = hits.optJSONObject(i) ?: continue
                                val extra = buildString {
                                    if (h.has("speed")) append(" speed=").append(h.optDouble("speed"))
                                    if (h.has("dur")) append(" dur=").append(h.optDouble("dur"))
                                    if (h.has("alpha")) append(" alpha=").append(h.optDouble("alpha"))
                                }
                                AppState.addLog("[组件] ${h.optString("node")} → ${h.optString("comp")}$extra")
                            }
                        }
                    }
                }
            }
            // ★同一条避让：场景刚切换时不查通用弹窗（`dialog()` 要走 DialogArea 子树 ✗ 也属重活）。
            if (!inBattleNow && now >= sceneHoldUntil &&
                ((top.isNotEmpty() && !ownSkip) || idle >= DLG_FORCE_AFTER_IDLE)
            ) {
                // ★★结算后加速（2026-11-21 改版）：cs125 写内存加速后，3/4 屏（演出壳/FullSize）
                //   **不再点中心**，靠演出自己推进；其余结算后页面（Big 等）仍每轮点中心、每 3 轮真检测。
                //   ★探针（cs128，见上方探针块）保持原样继续采集，为将来换直调用——与点中心是两件事。
                //   关闭点见 [CenterTapGate.reset]：刷初始前 / 杀进程 / 本局开始与结束。
                if (CenterTapGate.isActive() && top.isNotEmpty()) {
                    // ★★2026-11-22 修复（用户实测"3/4 屏动画还是原速"）：**不要再排除 3/4 屏**。
                    //   cs125 只压了②屏滚分/宝箱参数，`DialogAnimationViewer`（演出壳）和
                    //   `DialogCommonFullSize` 的演出**根本没被加速**，只能靠点中心跳过；
                    //   之前被排除后动画原速播完，还要再被 dialog()/findTextIn 吃掉十几秒。
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
                } else if (tapDialogConfirm(input, top, tag)) {
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
                        // ★SUMMON_UNIT 三种脸：①初始编成（DialogArea/无 DialogLabyrinthSelection）
                        //   ②战后"选取奖励角色"页（pageTop=DialogLabyrinthSelection，3 张卡选 1）
                        //   ③★2026-11-19（用户实测）：**战斗失败结算页被报成 SUMMON_UNIT**
                        //     （失败页 DialogQuestFailed 出现时 statusType=1，而不是平时的 5/6）。
                        //     不认它 ⇒ 分派到"选角色页"handler、读不到就静默等 ⇒ 实测空转 120s+ 一动不动。
                        val top = jStr(st, "pageTop")
                        when {
                            // 失败页被报成 SUMMON_UNIT 时直接按结算页处理（不再单独打日志——下面
                            // 「战斗失败…」那行已经说清楚了，这里每拍都会进，打了会刷屏）
                            PAGE_QUEST_FAILED in top -> handleQuestFailed(input, st)
                            top.contains("DialogLabyrinthSelection") ->
                                handleLabSelection(input, st, tag, charaPick, tries, "战后选人")
                            else -> handleSummonUnit(input, st, tag, charaPick, tries, waitSaid)
                        }
                    }   // PC 2120 / 战后选人
                    2 -> handleLabSelection(input, st, tag, charaPick, tries, "门票")   // PC 门票页（同 DialogLabyrinthSelection）
                    3 -> { /* USE_RELIC 演出，仅等待、不做决策（PC MAZE_WAIT_ONLY={3}） */ }
                    4 -> handleEventChoice(input, tag, tries, jStr(st, "pageTop"))   // PC EVENT_CHOICE(1088)
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
                // ★**statusType 归零 ≠ 已回地图**（2026-11-4 用户指出）：事件结算后先弹
                //   DialogLabyrinthEvent，紧接着连弹 DialogCommon*(奖励道具/角色/装备)。
                //   原来这里不看顶框 ⇒ 一边弹窗一边就 settle + 撤退，撤退链陷在"点掉一个又弹一个"里
                //   （实测 4 轮点不完 → "4 轮都没能恢复现场"）。现在顶框没清空前只负责**点掉弹窗**。
                val topNow = st.optString("pageTop", "")
                // ★只拦「事件结算 / 战斗结算」这两个**专属**框（2026-11-4）：
                //   `DialogCommon*` 是通用确认框，正常流程（任务详情、普通确认）也会出现，
                //   抢过来处理会多绕一轮（tapDialogUnknown 是 1s 级遍历）⇒ 交给原 handleMove 链。
                //   奖励弹窗（DialogCommon*）则由撤退链的时间预算兜住。
                val stillPopup = topNow.isNotEmpty() && (
                    topNow.contains("DialogLabyrinthEvent") || topNow.contains("DialogFlatoutPlayer")
                    )
                if (stillPopup) {
                    acted = tapDialogUnknown(input, tag, tries, topNow)
                } else {
                    // ★★2026-11-20（用户）：顶框空 **≠** 已回地图 —— 事件收尾/奖励结算时，收益要在
                    //   地图页结算，中间有一段"空窗期"（无 top）但事件还在继续 ⇒ 只看 statusType==0
                    //   会误判"已回地图"、提前去点下一个节点。
                    //   真正判据：地图上**至少有一个「可前往」节点**（`PartsLabyrinthPanel.panelState==4`，亮着）。
                    val ready = runCatching { AgentApi.labMoveReady() }.getOrNull()
                    val canMove = if (ready?.optBoolean("ok") == true) {
                        ready.optInt("selectable", 0) > 0
                    } else {
                        true      // 读不到地图视图/面板 ⇒ 不拦（保持原行为）
                    }
                    if (!canMove) {
                        val nowR = SystemClock.elapsedRealtime()
                        if (moveReadySince == 0L) {
                            moveReadySince = nowR
                            AppState.addLog("… 顶框已空但地图上还没有可前往节点（事件/奖励结算中）→ 等")
                        } else if (nowR - moveReadySince <= MOVE_READY_WAIT_MAX_MS) {
                            // 仍在结算：不点、不结算、也不当成"无事可做"（别让 idle 涨上去）
                            delay(POLL_MS)
                            continue
                        } else {
                            AppState.addLog(
                                "[!] 等 ${(nowR - moveReadySince) / 1000}s 仍无可前往节点 → 按原逻辑继续（避免卡死）",
                            )
                            moveReadySince = 0L
                        }
                    } else {
                        moveReadySince = 0L
                    }
                    // ★回到地图 ⇒ 结算上一个事件的效果差（只在确实不在战斗时；见 [settleEventDiff]）
                    if (evSnapId != null && !st.optBoolean("inBattle", false)) settleEventDiff(null, "回地图")
                    // ★逻辑模式（2026-11-18）：节点结束时统一 fire 一次（在此之后才考虑撤退/移动）
                    logicRuleFire(st)
                    // ★条件 2：事件里命中断言 ⇒ 等这一刻（事件处理完、正准备前往下一节点，地图上有「撤退」按钮）再撤
                    if (pendingEventRetreat != null && doPendingEventRetreat(input)) return
                    acted = handleMove(input, st, screenW, screenH)   // PC 6373~6458
                }
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

    /** ★卡死判据（PC 口径）：游戏帧计数（`UnityEngine.Time.frameCount`）连续这么久**不推进** ⇒ 判定卡死。
     *  用户 2026-10-16 指定：跑迷宫期间游戏"暂停"30s 即可认为卡死。 */
    private const val GAME_FRAME_STUCK_MS = 30_000L

    /** ★帧计数**读不到**（`gameTick` 超时 / `Time` 类取不到）连续这么久 ⇒ 也判卡死（游戏冻死时 RPC 会超时）。
     *  阈值放长些：避开"刚注入、il2cpp 还没热起来"的启动窗口。 */
    private const val GAME_FRAME_UNREADABLE_MS = 60_000L

    /** 帧计数轮询间隔（`gameTick` 是 T_FAST 导出，1s 一次足够；别每 100ms 去问） */
    private const val GAME_FRAME_POLL_MS = 1_000L

    // ★2026-11-16：滚几行固定 1.5（设置项已移除），按本节点"方向反复"次数逐次 -0.1。

    /** 页面停了这么久、但游戏帧还在涨 ⇒ 打一次现场取证（**不重启**：只说明 handler 没动作） */
    private const val STALL_DIAG_MS = 60_000L

    /** ★卡死自愈：杀掉后等"游戏回到前台 + agent 回连"的上限 */
    private const val STUCK_BACK_MS = 90_000L

    /**
     * ★2026-11-20（用户）：同一地图节点的**重试间隔**。原为 1s 起、逐步退避到 3s
     *   （2026-10-13 用户："防走图时无意义点击"）。但换区动画期间头几下点击会被游戏吞掉
     *   （直调返回 ok、不弹确认框），3s 退避让"换区首节点"固定卡 ~6s（实测点 3 下、间隔 2s/3s）。
     *   用户口径："多点当手段，别拿等待换准确"、并明确"每 150ms 重点一次试试"
     *   ⇒ 降到 150ms 起、450ms 封顶。正常节点点一下就切页，用不到这个间隔 ⇒ 无副作用。
     */

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
        // ★★2026-11-18（用户报的 bug）：**等待接手期间必须一直显示醒目提示** —— 否则这里每轮
        //   都把红提示覆盖成"地图 / 编队 / 战斗失败"，用户根本看不到"已经停了、要你处理"。
        //   （实测 10:29 连续失败 3 次转人工后，悬浮窗上仍显示"战斗失败"，没有任何红色提示。）
        if (manualTeamNodes.isNotEmpty()) {
            OverlayStatus.setState("等待接手：请组队并开战", attention = true)
            return
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
    private const val BATTLE_TAP_MAX_TOTAL = 5
    private const val BATTLE_RETAP_GAP_MS = 6_000L

    /** PC `BATTLE_IDLE_WARN`(478)：点过挑战后停在本页这么多轮才提示"可能被盖住" */
    private const val BATTLE_IDLE_WARN = 20

    /** ★预览页上"一直拿不到「挑战」可点坐标"的轮数上限（按钮始终没落位 / 页面结构变了 ⇒ 停手） */
    private const val QD_NOT_FOUND_MAX = 30

    /** PC `PAGE_LAB_RESULT`(416)：迷宫结算页（打完最终 Boss 的 RESULT ①②屏）——靠 pageTop 认，
     *  它的 `statusType` 是 **NONE(0)**，不认它就会落进"移动"分支一直干等（2026-10-16 实测卡 186s） */
    private const val PAGE_LAB_RESULT = "DialogLabyrinthResult"

    /** 商店页（有自己的 handler `handleShop`；★2026-11-20 纳入 ownPage，防止通用弹窗误点其按钮） */
    private const val PAGE_SHOP = "DialogLabyrinthShop"

    /** PC `LAB_RESULT_TAP_MAX`(417)：结算页最多点几次（①②两屏各一次即可；点不动就不再砸）。
     *  ⚠数的是**真点下去的次数**（2026-10-16 修：原来数的是轮数，读不到按钮时 6 轮就把额度耗光）。 */
    private const val LAB_RESULT_TAP_MAX = 10

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

    /**
     * ★2026-11-19（用户）：**四类结算页**的完整集合，点击"从哪儿出发"统一用它。
     *
     * 原来胜/负分支各写自己的 `from`（胜利缺 `DialogQuestFailed`、失败缺 `DialogQuestResultWin`）——
     * 一旦"页面类型与按钮标签不一致"（失败页显示「下一步」，见 [battleResultPathPick] 的标签纠正），
     * 纠正后的分支会因为 `from` 不含当前页而**误判"已离开"** ⇒ 一下都不点、直接空等。
     * 放宽成全集是安全的：点击前确实就在其中之一。
     */
    private val RESULT_FROM = setOf(
        "DialogBackKeyCapture", "DialogLabyrinthExResult", "DialogQuestResultWin", "DialogQuestFailed",
    )

    /**
     * ★★2026-11-19（用户）：结算页的**最大投递次数**（= 原来 `retries` 的量级）。
     *
     * ★2026-11-20（cs122 翻案）：普通胜利页直调**真生效**（旧"假成功"= 读到残留壳非激活实例），
     *   但这里**不能改回 1** —— 胜利结算有**同壳两页**（WIN → 奖励页，同一 `DialogQuestResultWin` 壳），
     *   一次 [TapCheck.run] 里要投 **2 次**（第二页不设显式 expect，靠"expect 没命中就再投"推进）
     *   ⇒ pressMax ≥2 是推进刚需。每次投递前 agent 只认激活按钮，不会重复执行同一颗 onClick；
     *   正常路径投 2 次即出壳，直调失败由 pressFn 内部退坐标兜底。
     */
    private const val RESULT_PRESS_MAX = 12

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
     *   ② 认出来（win/fail/ex）后先静置 [RESULT_SETTLE_MS]（3000ms），再每 [RESULT_LIGHT_GAP_MS]（200ms）
     *      轻读取探测「下一步/重新挑战」，覆盖按钮出现（约 3.5~4.5s 后才 active）。
     *
     * ★2026-11-17 用户：静置**恢复并改为 3000ms**、探测间隔 400→200ms。理由：按钮 3.5s 才出现，
     *   先安静等 3s 避开页面切换的瞬间（状态切换 + 重读取叠加会让游戏崩），再 200ms 一探覆盖到 ~5s。
     *   轻读取每拍只是一次 `battleResultBtn`（缓存校验，~100ms、零遍历）。
     */
    /** ★"只认页"的探测间隔：一次只要几毫秒 ⇒ 可以勤问；结算页出现后 ≤ 这个间隔就能发现 */
    private const val BATTLE_PAGE_POLL_MS = 300L

    /** ★认出结算页后先静置这么久再开始找按钮（2026-11-17 用户：3000ms）。
     *  按钮实测 3.5~4.5s 才 active，先安静等 3s 避开页面切换的瞬间（状态切换+重读取叠加会崩）。 */
    private const val RESULT_SETTLE_MS = 3000L

    /**
     * ★静置之后"**轻量轮询**"的次数上限（2026-11-17 用户：间隔 200ms）。
     *
     * ★2026-11-21（用户）：每拍现在只做**固定路径（noScan，几 ms）**，不扫树。
     *   配额 10 → **50**（200ms × 50 ≈ 10s）⇒ "整体读取（含子树扫描）延迟到认出结算页 ~10s 后才做"，
     *   避免按钮未落位时反复白烧 1.5~2s 的重扫描（就是"胜利画面卡几秒"的元凶）。
     *   **每次命中就清零**（胜利结算有两页，第二页同样享受轻量轮询）；
     *   用完仍未命中 ⇒ 升级到**全套**探测（[BATTLE_READY_GAP_MS] 1.2s 一次，含子树扫描）继续找，绝不停。
     */
    private const val RESULT_PROBE_FAST_MAX = 50

    /** ★轻量轮询的间隔（2026-11-17 用户：200ms） */
    private const val RESULT_LIGHT_GAP_MS = 200L

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
     * 一直认不出来。久战里冒出来的对话框基本就是结算页；
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
    /** ★2026-11-22 探针：通关庆祝动画采样节流（每 300ms 一行 [庆祝]） */
    private var celebrateAt = 0L
    /** ★2026-11-22：距上次"结算页重压演出参数"的时间（LabyrinthDefineData 可能每次结算重建） */
    private var labSpeedupAtResult = 0L
    /** ★2026-11-22 探针：庆祝期间"canvas 两层特效扫描"已做几次（诊断计数） */
    private var fxDumps = 0
    /** ★2026-11-22 探针：上次特效扫描时间（**1s 节流**——两层扫描 100~300ms，4s 窗口能抓 4 次） */
    private var fxAt = 0L

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
    /** ★2026-11-19：本场"失败页显示「下一步」"的日志只打一次 */
    private var failNextSaid = false

    /** ★本**局**已抓过几次战斗结算页的对象字段（每场一次、每局最多 [BATTLE_DUMP_MAX] 次，见 [dumpBattleResult]） */
    private var battleDumps = 0

    /** 每局最多抓几次结算页（普通战 1 次 + Boss 战 1 次即可；留一点余量给"失败页"） */
    private const val BATTLE_DUMP_MAX = 3

    /** ★★**自动抓取 dump 总开关 = 关**（2026-10-17 用户："结算页没问题的话，就把 dump 关掉吧"）。
     *  为什么关：偏移/指针两条路都已验证可用（战斗结算「字段读@DialogLabyrinthExResult+0x108」、
     *  迷宫结算「DialogArea 优先 3ms」）⇒ 自动 dump 只是往日志里灌 7~8 千字符（1.7s/次），
     *  对决策没有任何帮助 ✗。代码与预算计数都留着，改这一个常量就能再打开（排新偏移时用）。 */
    private const val AUTO_RESULT_DUMP = false

    /** ★本局是否**完整跑完**（见到结算页并走到"本局结束"那条 return）—— 决定日志存成 `__DONE` 还是 `__ABORT_*` */
    private var runFinished = false

    /** ★悬浮窗"编队：五个名字"用：本局的名册（[runBody] 建好就放这里，供 [handlePartyEdit] 查名字） */
    private var charaPickRef: CharaPick? = null

    /** ★编队页"还没建好"只报一次（正常过渡，别每轮刷） */
    private var partyNotReadySaid = false

    /** ★编组页页签候选**只报一次**（2026-11-07：以前每轮都打一行 40 个候选） */
    private val partyTabsSaid = HashSet<String>()

    // ══ ★★并行预计算（2026-11-07 用户："进节点时一边处理怎么进编组页、一边算队伍"）════════
    //  · 后台线程**只做纯本地计算**（组队算法）⇒ 不占 agent 那条单通道；
    //  · 主流程同时做「点挑战 → 等过场 → 读编组页」⇒ 这一秒被藏进过场动画里。
    private val teamScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    /** ★后台那次"提前算队伍"（进编组页时 await 它 ⇒ 命中缓存，0 等待） */
    private var preTeamJob: kotlinx.coroutines.Deferred<Unit>? = null

    /** ★是否**已经走过迷宫结算页**（`DialogLabyrinthResult`）—— 只有它之后的 `DialogCommonBig`
     *  才允许"读不到按钮就点屏幕中央"这个定向兜底（2026-10-17 用户要求，只此一页） */
    private var labResultSeen = false

    /** ★★结算演出加速（cs125，2026-11-22）：`LabyrinthDefineData` 单例演出时长已压到 0.05s。
     *  补丁写进 agent 会话内存、持续有效；每局 newRun 重置重打（防资产每局重建补丁丢失）。
     *  [labSpeedupTries] 只计**硬错误**（"Instance 未就绪"不算，5 次硬错误放弃、退回点中心）。 */
    private var labSpeedupOk = false
    private var labSpeedupTries = 0

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

    /**
     * ★本局是整轮里的第几局（形如 `2/5`；由连跑循环在开跑前塞进来）。
     *
     * 2026-10-27：日志页要按「本次运行 → 第 N 局」两级展示，而连跑循环那句
     * `第 N/M 局开始` 是在 [LogFile.beginRun] **之前**打的 —— 它只会落进 session 和**上一局**的文件，
     * 本局文件里没有任何局号。所以在 beginRun 之后补一行只属于本局的标记。
     */
    var roundLabel: String? = null

    /** ★奖励页"点屏幕中央"的次数（按现场 tag 计，最多 [REWARD_CENTER_MAX] 次） */
    private val rewardCenterTaps = HashMap<String, Int>()

    /** ★奖励页定向兜底：同一现场最多点几次屏幕中央（一般 1~2 屏奖励，够了）。
     *  ★2026-11-14（用户）：2 → 4 —— 这页点中心只是推进，多点几下无害。 */
    private const val REWARD_CENTER_MAX = 4

    /** ★cs128：结算尾段探针的目标壳类（每个**新顶框类**只探一次；见主循环探针块注释） */
    private val LAB_TAIL_PROBE_SHELLS = setOf(
        "DialogAnimationViewer", "DialogCommonFullSize", "DialogCommonBig",
        // ★2026-11-22：加结算②屏（滚分）——排查"按钮抢在滚分结束前出现 ⇒ 看不到分数"
        "DialogLabyrinthResult",
    )

    /** ★刷开局算好的**每区最优路线**（`RerollState` 的 `routePerArea`）：移动分支要用它，
     *  不能再只在开局那一次传（见 [handleMove] 里的说明）。 */
    private var rerollRoutes: Map<Int, List<Int>>? = null

    /** ★cs127（用户 2026-11-21 定口径"每个直调只调一次"+ 改用 tapAndCheck）：直调投出后**等推进**的秒数；
     *  超过 ⇒ pressFn 内退**一次**坐标兜底（每屏也只投一次）。 */
    private const val LAB_RESULT_DIRECT_WAIT_S = 3

    /** ★cs127：直调+坐标兜底**都投过**仍没推进时，再等这么多秒就停手交人工（防永久空转）。 */
    private const val LAB_RESULT_BOTH_SPENT_S = 10

    /** ★cs127：tapAndCheck 探测窗口的 retries。直调档每轮 ≈10×60ms 探测 ⇒ 总窗口 ≈ 3.8s，
     *  必须盖过换屏动画（①→②约 0.5~1s），否则窗口外判"没生效"→ 主循环重进 → pressFn 的已投标记
     *  会放行坐标兜底，等于变相多点。 */
    private const val LAB_RESULT_PROBE_ROUNDS = 6

    /** ★弹窗点击熔断（2026-10-17）：同一颗按钮最多连点 [DLG_TAP_MAX] 次（防"幽灵弹窗"无限连点）。
     *  ★2026-11-14（用户）：3 → **8** —— 这类"提前点/点空再点"是安全动作，代价只是一次 tap；
     *  阈值太小会把"动画还没结束"的正常弹窗误判成幽灵弹窗（商店买完遗物卡死就是这个）。 */
    private const val DLG_TAP_MAX = 8

    /** ★熔断后静默多久允许再试（2026-11-14）：原来熔断是**永久**的，一旦某次连点没生效
     *  （点早了 / 动画还没结束），压在商店上的到账弹窗就再也没人点，商店"等它关掉"等到用户手动点。 */
    private const val DLG_RETRY_MS = 10_000L
    private var dlgTapSig: String? = null
    private var dlgTapN = 0
    private var dlgTapAt = 0L
    /**
     * ★★2026-11-20（用户："前进确认有时会弹两次"）：**移动确认框**（`DialogCommonSmall` 的
     * TwoButtonRight）每一下都是真实"确认"，会真的提交一次移动 ⇒ 连点会让游戏连着弹两次。
     * 与奖励弹窗（可以多点救场）不同，这一颗按 [MOVE_CONFIRM_ONCE_MS] 只投一次。
     */
    private var dlgMoveAt = 0L
    private const val MOVE_CONFIRM_ONCE_MS = 2_500L
    /** ★2026-11-20：地图动画（换区/移动）提示的节流时间戳 */
    private var moveBusySaidAt = 0L
    /** ★2026-11-20：本次"动画标记"开始时刻（0 = 没在忙） */
    private var moveBusySince = 0L
    /** ★2026-11-20：本次 busy 已被判成误报（放行到 busy 消失为止，避免反复判） */
    private var moveBusyFalseReport = false
    /** ★2026-11-22：本次换区三段动画加速的完成掩码（bit0/1/2=trans/area/result；busy 消失清零） */
    private var mapSpeedDone = 0
    private var mapSpeedRetryAt = 0L
    /** ★2026-11-22：旧区结束(out)动画加速——已进入的区尾节点（结算完回地图那几秒密集探测） */
    private var outTailNode = 0
    private var outSpeedDone = false
    private var outSpeedRetryAt = 0L
    /** ★2026-11-20：`inAnimation` 持续多久就判"粘住"。
     *  实测换区从 `inAnim=true`（20101 出现）到消失约 **7.6s**（session_164525 16:47:11.8→19.4）
     *  ⇒ 设 9s 覆盖正常换区，同时保留"真粘住也不永久卡死"的兜底。 */
    private const val MOVE_BUSY_MAX_MS = 9_000L
    /** ★2026-11-20：最近一次 busy 由哪些句柄贡献（诊断用） */
    private var lastBusyTag = ""
    /**
     * ★★2026-11-20（用户质疑"普通节点为什么也报动画在跑"）：**默认关闭**。
     *
     * 实测结论：这些 IEnumerator 句柄"非空"≠"正在跑"。`unitMoveIE` 在角色走完目标后**不会立刻置 null**，
     * 会持有上次移动的引用直到下次赋值 ⇒ 普通节点回地图（准备下一步）时照样非空
     * （session_161111：节点 10303 在 area1，根本没有换区，却连报 busy 卡死 28s）。
     * 换区动画确实只在区尾节点（x0601/x0701）之后发生，但这个信号区分不出来。
     * ⇒ 关掉闸门，恢复"用确认框/页面状态判断"的原行为；将来要实测换区时序再打开（只认 areaSlide）。
     */
    private const val MOVE_BUSY_GATE = true

    /**
     * ★★2026-11-20（用户）：**地图信号诊断开关** —— 跑一局观察换区时到底哪个字段在变。
     * 每 250ms 打一行 `[信号] …`。确认完把这里改 false 重编译即可停（不影响逻辑）。
     */
    private const val MAP_SIGNAL_LOG = false
    private var mapSignalAt = 0L

    /**
     * 打一行地图现场信号（含 `inAnimation` = 换区过渡动画真实播放标志）。
     * 只读、几 ms；读不到就跳过（不刷屏）。
     */
    private suspend fun logMapSignals(st: JSONObject?) {
        if (!MAP_SIGNAL_LOG) return
        val now = SystemClock.elapsedRealtime()
        if (now - mapSignalAt < 250L) return
        mapSignalAt = now
        val b = runCatching { AgentApi.labMapBusy() }.getOrNull() ?: return
        if (!b.optBoolean("ok")) return
        fun bit(k: String): Int = if (b.optBoolean(k)) 1 else 0
        fun any(k: String): String = if (b.isNull(k)) "?" else b.opt(k).toString()
        AppState.addLog(
            "[信号] a=${st?.opt("area") ?: "-"} b=${st?.opt("blockId") ?: "-"} " +
                "st=${st?.opt("statusType") ?: "-"} " +
                "| IE unitMove=${bit("unitMove")} areaSlide=${bit("areaSlide")} finish=${bit("finish")} " +
                "| inAnim=${any("inAnimation")} viewMode=${bit("isMapViewMode")} " +
                "notFired=${bit("moveEventNotFired")} forceClear=${bit("isForcePlayPanelClearAnimation")} " +
                "| active partsMap=${any("partsMapActive")} phase=${any("phaseActive")} anim=${any("animActive")}",
        )
    }

    /**
     * ★★2026-11-20（用户："你有能力读到换区动画吗？"）：**能**，但**不能只看"非空"**。
     *
     * `ViewLabyrinthMap` 的协程句柄：
     * `+0x100 unitMoveIE`（角色移动）/ `+0x108 areaChangeMapSlideIE`（**换区滑场**）/ `+0x110 mapModeFinish`。
     *
     * ⚠session_161111 实测：节点 10303（area1，回地图后根本没有换区）`busy` 一直为真，
     *   「等它结束再点节点」连打 9 次、卡死 28s。说明**这些句柄字段会长期持有对象**，
     *   "非空"不等于"正在跑"。⇒ 两条修正：
     *   ① 只认 `areaSlide` / `unitMove`（`mapModeFinishProcessIE` 长期非空，踢出判据）；
     *   ② 加时长兜底：同一段 busy 持续超 [MOVE_BUSY_MAX_MS] ⇒ 判误报、放行，直到 busy 自行消失。
     *      否则一旦误报就是永久卡死（比"盲点一下"严重得多）。
     */
    private suspend fun moveBusyNow(): Boolean {
        if (!MOVE_BUSY_GATE) return false                   // 见 [MOVE_BUSY_GATE]：判据不可靠，默认不拦
        val b = runCatching { AgentApi.labMapBusy() }.getOrNull() ?: return false
        if (!b.optBoolean("ok")) return false
        // ★★2026-11-20（实测验证）：**只认 `inAnimation`**（PartsLabyrinthMapTransition.inAnimation）。
        //   session_164525 日志证实：
        //     16:47:11.778 area1→2 换区后 inAnim=true → 投递 20203 被吞
        //     16:47:15.767 仍 inAnim=true → 再投仍被吞
        //     16:47:19.565 inAnim=?（动画对象已销毁）→ 再投 131ms 生效
        //   而普通节点（20402→20503、20203→20303）始终 inAnim=? ⇒ 从不误拦。
        //   IE 句柄（unitMove/areaSlide/finish）实测全为空、不可用，不再参与判定。
        val busy = b.optBoolean("inAnimation")
        if (!busy) {
            moveBusySince = 0L
            moveBusyFalseReport = false
            mapSpeedDone = 0                               // 换区结束 → 下次换区重新补设
            return false
        }
        // 诊断：换区过渡动画在跑
        lastBusyTag = "换区过渡动画 inAnimation"
        if (moveBusyFalseReport) return false              // 本轮 busy 已判误报 → 一直放行
        val now = SystemClock.elapsedRealtime()
        if (moveBusySince == 0L) moveBusySince = now
        // ★★2026-11-22：换区三段动画加速（trans/area/result）。inAnimation 上升沿起、busy 期间
        //   有限重试：`area`/`result` 是播放时动态创建的，上升沿那一刻常 no-comp（实测），
        //   补几次才能抓到（16:58 的 [1/7]→[3/7] 就是重试抓到 area）。掩码记已设上的段，
        //   全齐(0x07) 或 busy 消失即停；agent 指针缓存让已设段重复调用零 invoke。
        //   trans（新区入场）上升沿就有 ⇒ 第一次即设上；旧区结束(out clip)在 busy 之前、仍漏。
        if (mapSpeedDone != 0x03 && now - mapSpeedRetryAt >= 400L) {
            mapSpeedRetryAt = now
            val ts = runCatching {
                AgentApi.obj("labMapTransitionSpeed", listOf<Any?>(20.0), timeoutMs = 3_000L)
            }.getOrNull()
            if (ts != null) {
                val parts = ts.optJSONObject("parts")
                val keys = listOf("trans", "area")
                var mask = 0
                fun part(k: String): String {
                    val p = parts?.optJSONObject(k)
                    if (p != null && p.optBoolean("ok")) {
                        mask = mask or (1 shl keys.indexOf(k))
                        return "$k=${p.optDouble("speed")}"
                    }
                    return "$k=×${p?.optString("err") ?: "?"}"
                }
                val line = keys.joinToString(" ") { part(it) }
                mapSpeedDone = mask
                AppState.addLog("… 换区动画加速 [$mask/3] $line")
            }
        }
        if (now - moveBusySince > MOVE_BUSY_MAX_MS) {
            moveBusyFalseReport = true
            AppState.addLog(
                "[!] 地图动画标记（areaSlide/unitMove）持续 ${MOVE_BUSY_MAX_MS}ms 不消失 → 判误报，放行点节点",
            )
            return false
        }
        return true
    }

    /**
     * ★★2026-11-22：旧区结束（transition 的 **out** clip）动画加速。
     *
     * 难点：out 动画在**旧区最后一个节点结算完、回地图那几秒**才建对象（`CreateTransitionOutAnimation`
     * → createParts 动态 Instantiate），比 `inAnimation=true`（新区入场）早得多。抓 inAnimation 上升沿
     * 时 out 早已播完 ⇒ 需要单独的窗口。
     *
     * 窗口判据（用户口径）：站过区尾节点 → 等 `statusType==0`（结算完回地图）且**顶框清空**（结算弹窗关完）
     * → 这几秒密集探测设速。BOSS 战那几十秒 stt=6 不满足，完全不轮询 ⇒ 性能友好。
     *
     * 只设 trans 段（+0x28 的 Animator）—— out/in 是不同 prefab、不同 Animator 指针，各自独立；
     * agent 侧指针缓存保证同一对象只 invoke 一次。
     */
    private suspend fun speedOldAreaExit(st: JSONObject, stt: Int) {
        val bid = jStr(st, "blockId").toIntOrNull() ?: return
        val tailNodes = setOf(10601, 20601, 30701, 40701)
        if (bid in tailNodes && !outSpeedDone) outTailNode = bid       // 记住进了哪个区尾
        if (outTailNode == 0 || outSpeedDone) return
        // 必须：结算完回地图(stt==0) + 顶框清空 —— 这才是 out 对象刚建好的窗口
        if (stt != 0 || jStr(st, "pageTop").isNotEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - outSpeedRetryAt < 300L) return
        outSpeedRetryAt = now
        val ts = runCatching {
            AgentApi.obj("labMapTransitionSpeed", listOf<Any?>(20.0), timeoutMs = 3_000L)
        }.getOrNull() ?: return
        val trans = ts.optJSONObject("parts")?.optJSONObject("trans")
        if (trans != null && trans.optBoolean("ok")) {
            outSpeedDone = true
            AppState.addLog("… 旧区 $outTailNode 结束动画加速 trans=${trans.optDouble("speed")}")
        }
    }

    /** 上次见到的"游戏被杀次数"（[GameProcess.kills]）；一变 ⇒ 说明游戏被重建过，清编队/装备缓存 */
    private var seenKills = 0

    /** 前台连续"读不到焦点窗口"的轮数（见 [FRONT_NULL_MAX]）；每局开头清零 */
    private var frontNullRounds = 0

    /**
     * ★2026-11-12（用户："不用限制时间，规定运行到某个阶段才开始算"）：
     *   本局**切屏保护是否已开启** —— 只有第一次在迷宫里读到状态（260~265）才置 true。
     *   启动/重启阶段（还没进迷宫、前台是桌面/助手/游戏闪退中）一律不算切屏。
     */
    private var switchArmed = false

    /** 上次已经取证过的顶框名（同一页面不重复 dump —— 见 [dumpBattleResult]） */
    private var lastDumpTop: String? = null

    /** PC `_NODE_FAILED`(3787) / `_FAIL_TAP`：本局"这个节点失败过"（≤3 次重挑战，之后停手） */
    private val nodeFailed = HashMap<String, Int>()

    /**
     * ★Boss 开打时刻（nodeKey → epoch ms）。
     *
     * Boss 页三队都编好后，点最后一队的「决定」**就已经开打了**，但树上会残留一个**空的**
     * `DialogQuestDetail`（空壳：子树只有 23 个节点，真预览页有 200+；按钮坐标也不一样）。
     * 以前会把它当成"退回到预览页"⇒ 又去点一次「挑战」—— 那是在**战斗画面上乱点**
     * （实测 19:28:10 白等 6 秒才放弃，还有误触战斗界面的风险）。
     * ⇒ 点完决定后记个时刻，短时间内 [handleQuestDetail] 不再点挑战。
     */
    private val bossOpenedAt = HashMap<String, Long>()

    /** 同上：那句提示每个节点只打一次 */
    private val bossOpenedSaid = HashSet<String>()

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

    /** ★凹分模式：本局是否已经查过一次（每局只查一次，避免重复读数量/重复撤退） */
    private var scoreChaseChecked = false

    /**
     * ★凹分模式·条件 2：**已经命中断言、等回到地图再撤**（2026-11-03 用户需求）。
     *
     * 为什么不当场撤：命中发生在「事件演出页」（EVENT_EFFECT，上面压着 `DialogLabyrinthEvent`），
     * 这时去走撤退链属于 `Scene.OTHER`（先关陌生弹窗），既慢又容易点到事件按钮。
     * 而事件演出一结束就回到地图（statusType=0、顶框=空）—— 那正是 [scoreChaseCheck] 同一个撤退现场。
     * ★用户口径（2026-11-03）："撤退发生在**事件处理完、准备前往下一节点时**，此时能看到地图上的撤退按钮"
     *   ⇒ 命中后不当场撤（那时压着 `DialogLabyrinthEvent`，属于 `Scene.OTHER`，既慢又容易误点），
     *   先记一句 detail，等事件演完回到地图（statusType=0、顶框=空，正要往前走的这一刻）再撤。
     */
    private var pendingEventRetreat: String? = null

    /** ★条件 2 选「逻辑模式」时只提示一次（每局重置），别每个事件刷一行 */
    private var saidLogicMode2 = false

    /**
     * ★条件 2 的断言进度（每局重置）：记着"每条断言的哪些条件已经满足过"。
     * 断言里的条件可能指向不同事件、分发生在不同时刻 ⇒ 必须跨多次结算累积。
     */
    private var eventRuleTracker: EventRetreatRule.Tracker? = null

    /** ★逻辑模式（[LogicRule]）：token 引擎，每局重置 */
    private var logicEngine: LogicRule.Engine = LogicRule.Engine()

    /** 本节点收集到的事件结算事实：演出页/结算时采集，**节点结束时**统一喂给 [LogicRule] */
    private val nodeEventFacts = mutableListOf<LogicRule.EventFact>()

    /** ★逻辑模式：已经 fire 过的节点（`area:blockId`），每个节点只触发一次 */
    private var logicFiredNode: String? = null

    /**
     * ★cs128：token 续局恢复（2026-11-21 用户："不能一次跑完导致 token 丢失"）。
     * 每局只尝试一次：第一次 fire 时按当前 enterId 比对 [LogicTokenStore] 落盘快照——
     * 同局（服务器那局还在、App 重启续接）⇒ 读回 token + firedNode；新局 ⇒ 从 0 开始。
     */
    private var logicRestoreTried = false

    /** 本局的 enterId（从 fire 时的 st 取；落盘快照用） */
    private var logicEnterId = -1L

    /** ★策略=等待接手：这些节点关闭自动组队/自动开战，等用户自己操作 */
    private val manualTeamNodes = HashSet<String>()

    /** 手动节点提示只打一次 */
    private val manualSaid = HashSet<String>()

    /** ★「等待接手」全局闸门那行提示的去重键（解除时移除 ⇒ 下次等待还能再提示） */
    private const val WAIT_GATE_TAG = "__wait_gate__"

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
        // ★2026-11-15（用户）：同一关里来回滑（相邻两次方向相反）⇒ 每 2 次把本节点滑动比例调小 0.1，
        //   减少"滚过头 → 反向滚 → 又过头"的死循环。离开本节点即失效（见 [partyScrollTag]）。
        var lastDir: String? = null                    // 上次实际滚动的方向（up/down）
        var reverseCount = 0                           // "相邻两次方向相反"的累计次数
        var slowSteps = 0                              // 已减速次数（每 2 次反向 +1，每次 -0.1 行）
    }

    private val partyScroll = HashMap<String, PartyScroll>()

    /** ★2026-11-15（用户）：当前编队页所属**节点**（area/blockId）—— 换节点（离开这一关）时清空 [partyScroll] */
    private var partyScrollNodeKey: String? = null

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

    /** ★"编组页已经换成新内容了"（2026-11-07）：页面静置闸门改自适应用。 */
    private var partyPageReady = false
    /** ★进编组页那一刻的页面签名（[partyListSig]）：用来判断"页面是不是已经换成新内容了" */
    private var partyEntrySig: List<Int>? = null
    /** ★进编组页那一刻队伍是不是空的（新节点从空队开始 ⇒ 空 = 已经是新页面了） */
    private var partyEntryEmpty = false

    /** 上次点「挑战」的时刻（[BATTLE_RETAP_GAP_MS] 冷却用） */
    private var qdTapAt = 0L

    // ── 特别装备（EX 装备）三连页：决策-free 纯操作（PC `_exeq_step` + `handle_exequip`）──
    //   ① 特别装备设定   PartsDialogPartyExtraEquip（ThreeButton Left/Center/Right = 取消/自动装备/装备确定）
    //   ② 自动特别装备设定 PartsDialogRecommendSettingExtraEquip（labels 4 行 + checkBtn + TwoButton）
    //   ③ 优先属性值     PartsDialogExtraEquipSort（toggles + TwoButton）
    //   只读由 agent 的 labExEquip / labExEquipTop 提供（字段与 PC 探针一致）。
    // ★2026-11-09：③ 页不再选「稀有度」（会穿不上村好剑），改按目标队的输出系别选下面两项之一。
    //   真机探针抓到 ③ 页共 11 项，末两项即这两个名字：…物理暴击 / 魔法暴击 / 物理防御贯穿 / 魔法防御贯穿。
    private const val EXEQ_SORT_PHYS = "物理防御贯穿"
    private const val EXEQ_SORT_MAGIC = "魔法防御贯穿"
    private const val LAB_EXEQUIP_AUTO = true        // 是否自动走这套流程（PC LAB_EXEQUIP_AUTO）
    private const val LAB_EXEQUIP_SKIP_SAME = true   // 与上场同一套队才跳过（PC LAB_EXEQUIP_SKIP_SAME）
    private const val EXEQ_POLL_MS = 60L             // 打包流程内部的轮询间隔（签名读很轻，60ms 一探）
    private const val EXEQ_WAIT_MAX_MS = 3000L       // 等"三连页开出来"的上限（超了 ⇒ 放弃这次装备）
    private const val EXEQ_MAX_STEPS = 18            // 一次流程最多点几下（PC EXEQ_MAX_STEPS=8；
                                                       //   ★这里要多留：整条链最少 6 下（自动装备→优先→系别贯穿→确认→确认→装备确定），
                                                       //   每一下还有"没生效重试"的余量）
    private const val EXEQ_TAP_TRIES = 4             // 同一次调用里最多点几下（tapAndCheck 的 retries）
    private const val EXEQ_TAP_MAX = 8               // 同一个按钮最多点几下（防止在一个按钮上狂点）
    /** ★★连续"点不动/读不到"几次就**放弃这次装备**（2026-11-08 实测 11:44 那轮卡死 30 秒的教训）：
     *  EX 装备只是锦上添花，卡住就该放过 ⇒ 放过它，编组页照常开打。 */
    private const val EXEQ_STALL_MAX = 3
    /** ★tapAndCheck 里"这一刻不该点"的连续上限（超了就返回 false，让外层换策略） */
    private const val EXEQ_SKIP_MAX = 6
    //   ★2026-11-17：settleMs 已统一为 probeMs×2（见 TapCheck.run），不再保留 EXEQ 专用常量。
    private const val EXEQ_BTN_MOVE_PX = 12         // ★同一个按钮两次读出的坐标差多少就算"在动"（页面在切）
    private const val EXEQ_STILL_MS = 140L        // ★双读之间的间隔（要跨帧，否则两次读必然一样）
    // ★★"点击后探测"的通用参数（2026-11-08）：点完不是"等固定时长再读一次"，而是**连探几次**。
    //   页面切换动画 300~500ms，签名只在动画**结束**时才会变 ⇒ 固定短等待必然误判"没生效" ⇒ 多点。
    private const val PROBE_N = 4               // ★探几次（200ms 起步 + 3×150ms = 650ms 窗口）
    private const val PROBE_MS = 150L           // ★探针间隔
    private const val EXEQ_IDLE_WAIT_MS = 250L  // ★过渡态（EX 页在收尾、还没接管）的轮询间隔
    /** ★★2026-11-20（用户）：**最后一击「装备确定」直调成功后提前收工**，不等 EX 容器从顶层消失。
     *   实测过渡态最长 11s（见 6229 注释）——直调已确认投递成功就无需再等动画。
     *   关闭即回退成"等容器消失"的老行为（改常量或改成 false 重编译）。 */
    private const val EXEQ_EARLY_EXIT = true
    /** ★2026-11-22：提前收工后、EX 容器退场动画未结束期间的"放行"上限。
     *  原来的固定 2s 太短——实测过渡态最长 11s，慢设备上 2s 后壳还在，兜底门就误判
     *  "EX 又开了"重新接管，在已切到的空二队上又走一遍三连页 ⇒ 卡在特殊装备页（0.7.16 反馈）。
     *  现在改成状态判断：壳真消失了 exTop 为空、兜底门压根进不来（天然放行）；
     *  只有壳异常赖着不走才在 15s 后清标志、恢复正常兜底（仍受 EXEQ_FB_MAX 保护）。 */
    private const val EXEQ_CLOSE_GUARD_MS = 15_000L
    private const val EXEQ_FB_MAX = 3              // ★主循环兜底接管 EX 页的次数上限（超了 ⇒ 让用户手动关）
    private const val EXEQ_RETRY_WAIT_MS = 300L      // "这一轮点了没生效"时的等待（别把 agent 读爆）
    private const val EXEQ_AUTO_WAIT_MS = 900L       // 点完「自动装备」后等页面切到②的上限（超了 ⇒ 再点一次）
    private const val EXEQ_READ_FULL_MS = 4000L      // 按需读按钮（要坐标那次）的超时（别占住 agent 单通道太久）
    private const val EXEQ_TOTAL_BUDGET_MS = 30000L  // 整个 EX 流程的时间上限（超了 ⇒ 收手交回主循环）
    private const val EXEQ_OPEN_WAIT_MS = 30000L     // 就绪却读不到「特别装备」按钮的等待上限（PC EXEQ_OPEN_WAIT=30s）

    /**
     * ★特别装备三连页的**页身份**（= agent 侧 `labExEquip` 的 `top`）。
     *
     * 不用容器窗口名（`DialogCommonFullSize` / `DialogCommonBig`）：三个页可能都挂在同一个容器下，
     * 容器名**分不开**是哪一页；页身份是 agent 按**类**认的（PartsDialog*ExtraEquip），一一对应。
     */
    private const val EXEQ_PAGE_PARTY = "party"      // ① 特别装备设定
    private const val EXEQ_PAGE_SETTING = "setting"  // ② 自动特别装备设定
    private const val EXEQ_PAGE_SORT = "sort"        // ③ 优先属性值
    /** "当前**不在**特别装备三连页里"（[exeqSigNow] 返回空串 = 回到编组页了） */
    private const val EXEQ_OFF_PAGE = ""
    /** 读窗口失败时的标记（状态机里当"**没离开**"处理 ⇒ 不算这一步生效） */
    private const val EXEQ_UNKNOWN = "?"

    /** 单个节点（按 nodeKey）的特别装备流程会话（PC `_EXEQ_SESS` 按 (节点,状态,视图) 记） */
    private data class ExeqSess(
        var n: Int = 0,
        var opened: Boolean = false,
        var openedAt: Long = 0L,
        var auto: Boolean = false,
        var exDone: Boolean = false,
        var exShare: Boolean = true,
        var team1Sig: List<Int>? = null,
        /** ★上一次点击时的**页面签名**（签名没变 ⇒ 还没生效 ⇒ 别重复点） */
        var tapSig: String? = null,
        /** ★**正在点的那个按钮名**（多点时按"同一个按钮"计数，换按钮就清零） */
        var tapBtn: String? = null,
        /**
         * ★**正在点的那个按钮实例**（GameObject 指针，agent 侧给）——
         *   多点重试时用它把点击**锁在同一个按钮上**（同名按钮在同一页里可能有好几棵）。
         */
        var tapBtnId: String? = null,
        /**
         * ★★**上一次读到**的按钮坐标**（2026-11-08 实测加的）：
         *   同一个按钮实例（id 相同！）两次读出的坐标会**差很多** ——
         *     ②「确认」 (1179,960) → (1150,904)；①「装备确定」 (1626,960) → (1160,666)；
         *   那是**页面切换动画**里按钮在位移（第一次点击其实**已经生效**了，只是签名还没更新）。
         *   ⇒ 重试前拿它比对：位移大 ⇒ 按钮在动（页面在切）⇒ **这一次不点**（那个"移动中的位置"
         *     很可能压在**别的控件**上 —— 这才是真正的"多点落到不同目标"）；等它停稳了再点。
         */
        var tapX: Int = -1,
        var tapY: Int = -1,
        /** ★**同一个按钮**已经点了几下（防止在一个按钮上狂点） */
        var tapN: Int = 0,
        /**
         * ★"特别装备流程**还没走完**"（2026-11-08）——
         *   打包流程里 = "**正在跑**"；主循环的兜底段靠它判断要不要再跑一遍。
         */
        var pending: Boolean = false,
        /** ★三连页**确实打开过**（区分"还在开"和"已经走完回到编组页"——两者签名都是空串） */
        var exStarted: Boolean = false,
        /** ★**确实进过**②自动特别装备设定页**（⇒ "从②回来了" ⇒ change=false 才是"自动装备真没改动"） */
        var sawSetting: Boolean = false,
        /** ★点「自动装备」的**时刻**（用来判断"页面是不是还在切" ⇒ 等 vs 再点一次） */
        var autoAt: Long = 0L,
        /** ★**连续**读到"不在 EX 页"的次数（≥2 才判"真走完了"—— 防"最上层被别的东西盖住"误判） */
        var offN: Int = 0,
        /**
         * ★EX 三连页**收不了手**（已经收尾却还压在该页上）⇒ 停止自动处理，
         *   别让主循环每轮都重跑一遍打包流程（每轮 30s 太贵）。
         */
        var exStuck: Boolean = false,
        /** ★主循环**兜底接管**的次数（EX 页还在 ⇒ 再接管一次；超过上限就放弃） */
        var fbN: Int = 0,
        /** ★按需读按钮（要坐标那次）的**累计耗时** —— 用来定轮询/探针间隔（别想当然） */
        var fullMs: Long = 0L,
        /** ★**最后一次点击**的时刻（用来算"这一步从点到生效"用了多久） */
        var lastTapAt: Long = 0L,
        /** ★连续"点不动/读不到"的次数（≥[EXEQ_STALL_MAX] ⇒ 放弃这次装备，别卡住开打） */
        var stall: Int = 0,
        /** ★同一句诊断日志打了几次（防刷屏；成功一次就清零） */
        var sayN: Int = 0,
        /** ★本次特别装备要选的排序项（[exeqSortTarget] 按目标队算出；默认物理） */
        var sortTarget: String = EXEQ_SORT_PHYS,
        /** ★★2026-11-20：最后一击「装备确定」**直调真的投递成功**（ok=true）——
         *  与 [exDone]（投递前就置、失败也算）不同，这个只在 agent 确认 ok 时才置，
         *  用于 [EXEQ_EARLY_EXIT] 提前收工（确认点上了才敢不等容器消失）。 */
        var finalPressOk: Boolean = false,
        /** ★2026-11-20：提前收工的时刻（非 0 = 刚跳过"等容器消失"）——
         *  主循环兜底据此放行：EX 壳真消失后 exTop 为空进不来兜底门（天然放行），
         *  壳还在退场动画就继续放行，直到 [EXEQ_CLOSE_GUARD_MS] 超时才恢复接管。 */
        var earlyExitAt: Long = 0L,
    )

    /**
     * ★★选好最优队后**额外跑一次**（2026-11-09 用户口径）：算这队输出成员里物理多还是魔法多，
     *   不从组队算法里硬拉物理/魔法值。平局（含一个输出成员都没有）默认物理。
     *
     * · 输出成员 = 带「输出核心 u_dps」「单体输出 u_burst」「群体输出 u_aoe」之一的角色；
     * · 系别 = [Rules.effAtk]（已处理「类型反转」标签，物理↔魔法对调）；
     * · 返回值直接就是 ③ 排序页要选的那个选项名。
     */
    private fun exeqSortTarget(team: List<Int>): String {
        val c = appCtx ?: return EXEQ_SORT_PHYS
        val rules = Rules(GameData.tables(c))
        val dpsTags = setOf(TagIds.T["dps"], TagIds.T["burst"], TagIds.T["aoe"])
        var phys = 0
        var magic = 0
        for (u in team) {
            val id = u.toString()
            if (rules.tagsOf(id).none { it in dpsTags }) continue
            if (rules.effAtk(id) == "魔法") magic++ else phys++
        }
        return if (magic > phys) EXEQ_SORT_MAGIC else EXEQ_SORT_PHYS
    }

    /**
     * ★当前的特别装备会话（**单例**，2026-11-08）。
     *
     * 原来按 `nodeKey` 存 map，但 EX 只出现在**当前这一个节点**的编组页上（换节点时清），
     * 实质上同时只有一个 ⇒ 改成单例后，**EX 流程不需要 `labStatus` 里的 blockId**，
     * 就能完全自己跑完（主循环不再为它读全量快照）。
     */
    private var exeqSessCur: ExeqSess? = null
    /** 最近配好的队伍1 签名（sorted unitId，只记一套）—— 用于 SKIP_SAME（PC `_EXEQUIP_LAST`，Boss 只看队伍1） */
    private var exequipLastTeam1: List<Int>? = null

    private fun exeqSess(): ExeqSess = exeqSessCur ?: ExeqSess().also { exeqSessCur = it }

    /**
     * ★（已下沉到 agent 侧，2026-11-08）取某个名字的按钮。
     *
     * ~~同一页里**可能有多棵同名按钮**（嵌套弹窗/槽位里的也叫 ThreeButtonRight…），
     *   而"数组里第一个"在重读整包时**不稳定** ⇒ 多点时第二轮会点到**另一个目标**
     *   （②「确认」 (1179,960) → (1150,904)；①「装备确定」 (1626,960) → (1160,666) ✗）。~~
     *
     * ★现在由 [AgentApi.labExBtn] 在 agent 侧处理：① 传 `preferId`（GameObject 指针）命中就用它
     *   —— 把多点**锁在同一个按钮实例**上；② 没命中取**最靠下**的那个（y 最大 = 底部主按钮行）。
     *   而且**正常路径根本不走遍历**：通用按钮按**字段**取（DialogFundationObject）⇒ 天然唯一。
     */

    /**
     * ★从**签名**里取页身份（party / setting / sort）—— 签名格式 `页身份@容器名|状态…`
     * （容器名是 agent 侧"当前最上层"的归一化名，2026-11-08 加的：
     *   这样"页面切了"能被立刻看见，而不必等页内状态变）。
     */
    private fun exeqPageOf(sig: String): String? = when {
        sig.startsWith("$EXEQ_PAGE_PARTY@") -> EXEQ_PAGE_PARTY
        sig.startsWith("$EXEQ_PAGE_SETTING@") -> EXEQ_PAGE_SETTING
        sig.startsWith("$EXEQ_PAGE_SORT@") -> EXEQ_PAGE_SORT
        else -> null
    }

    /**
     * ★**轻量**读 EX 三连页的当前签名（agent 侧算好，只回一个字符串 —— 不带坐标、不带按钮）。
     *
     * 轮询用这个（几十 ms）；要按钮坐标时才走**按需读** [AgentApi.labExBtn]。
     * ★签名与按钮读是**同一个 agent 函数**算的（`_exeqSigOf` / `_exeqPageState`）⇒ 两边口径永远一致，
     *   所以"读按钮时带回来的签名 == 轻量读到的签名"这个校验是可靠的。
     *
     * @return 签名；`""`（[EXEQ_OFF_PAGE]）= 当前**不在**三连页里；`"?"`（[EXEQ_UNKNOWN]）= 读不到
     */
    private suspend fun exeqSigNow(): String =
        runCatching { AgentApi.labExEquipSig() }.getOrNull() ?: EXEQ_UNKNOWN

    /**
     * ★EX 流程的**诊断日志**（节流：同一轮失败只打前 3 句，免得刷屏）。
     *
     * ★2026-11-08：以前 EX 流程"点不动"是**完全静默**的 —— 整包读挂住时一声不吭地空转 30 秒，
     *   日志上什么都看不出来 ⇒ 只能靠猜。现在每次"这一轮不点"都说清**为什么**。
     */
    private fun exeqSay(sess: ExeqSess, msg: String) {
        if (sess.sayN < 3) {
            sess.sayN++
            AppState.addLog("    · $msg")
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

    /** ★2026-11-20：Boss（panelTypeName==BOSS）两轮选人——当前这一轮是"第一轮"（点完后会刷新出第二轮卡片）。
     *  第一轮用 `arrived`(卡片签名变化) 立即识别第二轮，不走超时；第二轮正常等 Middle。 */
    private var bossSelPendingRound2 = false

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

    /** 固定路径最近一次的失败原因（去重，避免每拍刷屏） */
    private var lastPathErr = ""

    /** 战后选人页：本页算好的决策上下文（own 数据），按卡片签名缓存，避免每轮重打慢 RPC */
    private var lastCtxSig: String? = null
    private var lastCtx: CharaPick.LabSelCtx? = null

    /** ★app 上下文（[LabBtnStore] 按钮路径落盘/回喂用；[run] 开头赋值） */
    private var appCtx: android.content.Context? = null

    /**
     * 编队「一行宽度（px）」整局缓存（2026-11-15）。
     * 第一次滚动反推出合理行距后存这里，之后所有滚动复用，不再每拍现取 ——
     * 否则向上滚时可见卡片分布变化，会把"隔行差(2×行距)"当"相邻差(1×行距)"，
     * 反推出约 2 倍行距（小鹿 1280×720：向下 rh≈144、向上 rh≈257 ⇒ 一次想滑 385px 撞边距、还滚过头）。
     * 每次 [run] 新建实例 ⇒ 自然清零，换分辨率/布局后下一局会重新反推。
     */
    private var partyRowHCache = 0.0

    /**
     * ★2026-10-27「点击学延迟」的探针（用户方案：一次点早了，下次同样的按钮就稍微晚点）。
     *
     * 机制：`tap(..., learnKey = ...)` 点下去时记下当时"现场指纹"，**下一轮**主循环重新算出 tag 后比对：
     *   · 变了 ⇒ 点成功了，什么都不做（成功不回调，免得把自己越学越慢）；
     *   · 没变 ⇒ 认定"那一会儿按钮还不可点" ⇒ [TapDelayStore.miss] 让这个 key 下次多等一点。
     * 判据只用现成的 `tag`（每轮本来就要算），**不增加任何读取**。
     */
    private var tapProbeKey: String? = null
    private var tapProbeTag: String? = null

    /** 本轮"现场指纹"（主循环每轮写；`lastTag` 是 `run()` 的局部变量，[tap] 里够不着，所以另存一份） */
    private var curTag: String? = null

    // ── 跨轮的"点击节流"记录（PC `_NODE_SAID_AT`，按节点记、**不清**）──
    //   ★2026-11-20：`nodeTapAt/nodeTapN` 已删（重复投递改由状态判据接管，见 [handleMove] 文档）。
    private val nodeSaidAt = HashMap<Int, Long>()


    /** PC `drag_count`(5966)：本节点已拖动次数（**换页清零**，见 maze_loop 6013） */
    @Volatile
    private var dragCount = 0

    @Volatile
    private var stopRequested = false           // handler 判定"卡死了"→ 请求收工

    /** ★停手原因（写进悬浮窗第 3 格，让人知道为什么要手动处理；2026-11-14 用户）。 */
    @Volatile
    private var stopReason: String? = null

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

    // ── ★2026-11-17：Unity `Application.isFocused`（零 shell 前台判定）────────────────
    /** 上次查询 isFocused 的时刻 / 结果（[FOCUS_TTL_MS] 缓存） */
    private var focusAt = 0L
    private var focusVal: Boolean? = null
    /** isFocused 与 dumpsys 不一致时只告警一次 */
    private var focusMismatchSaid = false

    /** 连续读到 null 的次数 / 放弃 isFocused 的截止时刻（读不到就别反复问） */
    private var focusNullCount = 0
    private var focusGiveUpUntil = 0L

    /** 缓存有效期：0.5s 刷新一次（切屏检测 0.5s 足够，且比每轮 100ms 省 80% 的 RPC） */
    private const val FOCUS_TTL_MS = 500L

    /** 游戏窗口是否获得焦点（`UnityEngine.Application.isFocused`，微秒级，零 shell）。 */
    private suspend fun gameForeground(now: Long): Boolean? {
        if (now < focusGiveUpUntil) return null
        if (now - focusAt >= FOCUS_TTL_MS) {
            focusAt = now
            val v = AgentApi.gameFocused()
            focusVal = v
            if (v == null) {
                // ★连续读不到（老 agent 没这个导出 / 类找不到）⇒ 60s 内别再问，直接用 dumpsys
                if (++focusNullCount >= 10) {
                    focusNullCount = 0
                    focusGiveUpUntil = now + 60_000L
                    AppState.addLog("… isFocused 连续 10 次读不到 → 60s 内改用 dumpsys 判前台")
                }
            } else {
                focusNullCount = 0
            }
        }
        return focusVal
    }

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
            if (nm !in waitSaid) {
                waitSaid.add(nm)
                AppState.addLog("（$nm：战斗中/演出 → 只等不点）")
            }
            // ★2026-11-14（用户）：这里**不再按"同一状态轮数"判停手**。
            //   90s 是游戏内计时，角色 UB 演出期间是暂停的 ⇒ 实际墙钟可能远超这个值，
            //   按轮数判会把"正常但很长的战斗/演出"误杀。真卡死交给帧看门狗
            //   （GAME_FRAME_STUCK_MS / GAME_FRAME_UNREADABLE_MS）。
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
                if (tapDialogConfirm(input, top, tag)) {
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
            // ★2026-11-12（用户）：点过「去邀请」后**必定**弹「收到角色」框 ⇒ 纳入状态链并点掉；
            //   之后可能是额外奖励框（拉比林斯等公会）也可能直接进地图，交给主循环按页面状态走。
            if (pick.consumeInvited()) drainSummonReceipts(input)
        }
    }

    /**
     * ★2026-11-12（用户）：选角「去邀请」后的**收到角色**链。
     *
     * 必定出现的「收到角色」框（`DialogCommonMiddle` + `OneButtonWhite`，和买印记同款）先点掉；
     * 之后不同公会不一样（拉比林斯有额外奖励框、有的直接进地图）⇒ 这里只做「见到 Dialog 就点掉」，
     * **连续 4 拍没有对话框**就收手，把后续交给主循环按页面状态决定。
     */
    /** ★2026-11-20：连续几拍"没有对话框"就收手（3 拍 ×120ms ≈ 360ms，够框退场） */
    private const val SUMMON_RECEIPT_IDLE = 3

    private suspend fun drainSummonReceipts(input: InputBackend) {
        val keys = listOf("OneButtonWhite", "OneButtonBlue", "TwoButtonRight", "ThreeButtonCenter")
        val t0 = SystemClock.elapsedRealtime()
        var idle = 0
        var taps = 0
        var lastBoxPtr = ""            // ★刚点过的框（ptr 不变 = 还没走，别重复点）
        while (SystemClock.elapsedRealtime() - t0 < 8_000L && taps < 6) {
            // ★★2026-11-20（用户："选角后等待时间有点长"）：先用**轻读** `labTop()`（几 ms）看还有没有对话框；
            //   没有就直接算一拍收手。原来每拍都调 `dlgBoxNow()`（`labDlgBox` 实测 500ms+）⇒
            //   "等框消失"这一件事就白烧 4×600ms ≈ 2.4s（实测 15:56:25.9→30.0 才回到地图）。
            val topQuick = runCatching { AgentApi.labTop() }.getOrNull().orEmpty()
            if (topQuick.isEmpty() || !topQuick.startsWith("Dialog")) {
                idle++
                if (idle >= SUMMON_RECEIPT_IDLE) {
                    AppState.addLog("选角后：收到角色框已处理完（$taps 个）→ 交回主循环")
                    return
                }
                delay(120)
                continue
            }
            val b = dlgBoxNow()
            val top = b?.optString("top").orEmpty()
            if (b == null || !top.startsWith("Dialog")) {
                idle++
                if (idle >= SUMMON_RECEIPT_IDLE) {
                    AppState.addLog("选角后：收到角色框已处理完（$taps 个）→ 交回主循环")
                    return
                }
                delay(120)
                continue
            }
            // ★★2026-11-20（用户："额外获得物弹窗没响应到，可能后面主循环点掉的"）：
            //   刚点过的**同一个框**（ptr 不变）不再重复点，等它退场/换框。
            val ptr = b.optString("ptr")
            if (ptr.isNotEmpty() && ptr == lastBoxPtr) {
                idle++
                if (idle >= SUMMON_RECEIPT_IDLE) {
                    AppState.addLog("选角后：同一个框点了没走（$top）→ 交回主循环")
                    return
                }
                delay(120)
                continue
            }
            idle = 0
            if (pickBoxBtn(b, keys) == null) { delay(120); continue }
            taps++
            AppState.addLog("选角后：收到角色 → 点掉（第 $taps 个框，$top）")
            // ★2026-11-19：走 `SelectConfirm|OneButtonWhite`（Middle 单白按钮），直调优先
            // ★★2026-11-20（用户）：某些公会/场景这一层是 `DialogCommonBig`（获得道具/角色），
            //   路径与 Middle 不同（[0,2,6] vs [0,2,3]）⇒ 按**实际顶框**选 key，避免 path-miss 退坐标。
            val boxKey = if (top.startsWith("DialogCommonBig")) {
                "ResultBig|OneButtonWhite"
            } else {
                "SelectConfirm|OneButtonWhite"
            }
            // ★★2026-11-20：直调成功就**短等推进**，不再走 [tapBoxButton] 的长等待
            //   （它内部 `tapAndCheck` 没看到框消失会白探 8×600ms≈4.8s —— 实测 3 个框耗 12.3s 的主因）。
            val prOk = runCatching { AgentApi.labPathPress(boxKey) }.getOrNull()?.optBoolean("ok") == true
            if (prOk) {
                lastBoxPtr = ptr
                delay(250)
            } else {
                tapBoxButton(input, keys, "收到角色", pathKey = boxKey)
                delay(150)
            }
        }
        AppState.addLog("选角后：收到角色处理结束（$taps 个框）")
    }

    // ★2026-11-20（已删）：`EVENT_SKIP_RE` —— 事件跳过改用 `labEventSkip()` 直调
    //   （`DialogLabyrinthEvent.dramaSkipButton+0x160`），不再靠名字搜节点。见 [handleEventEffect]。

    /**
     * PC `EVENT_BTN_RE`(321)：事件选项卡上每张卡的「选择」按钮（按 x 升序 = choice_id_1/2/3）。
     * ★PC 是**从 UI 树**按这个节点名找的（`find_list`），不是类读取。
     *   我们原来用类读取 `labEvent()`，实测会返回空卡 → 事件页卡死（2026-10-16）。
     *   名字可能带 "(Clone)" 后缀 → 只锚开头、不锚结尾。
     */
    private const val EVENT_CHOICE_BTN_RE = "^labyrinth_btn_main_square"

    /** 事件页对话框的类名前缀 —— 顶框不是它 ⇒ **页面还没铺开**，先别去树里找选项卡（2026-11-08） */
    private const val EVENT_DLG_PREFIX = "DialogLabyrinthEvent"
    /** 连续「顶框不是事件对话框」的轮数上限：到这儿就退回老行为（顶框名对不上也不能把整局卡住） */
    private const val EVENT_NOTREADY_MAX = 10
    /** 前几轮只在 `DialogArea` 子树里找；连续这么多轮都找不到才退到**父层**（节点多得多，别每轮都扫） */
    private const val EVENT_LAYER_AFTER_N = 3
    /**
     * ★"最上层对话框里找到的选项卡**数量不够**"时，等这么多轮才怀疑"真不在 top 里"并退 `dialog`。
     *
     * 正常情况下不够 = **按钮还在逐个建出来**（实测：顶框刚出现那一帧只有 1 颗，下一轮就齐了）
     * ⇒ 只需等一轮。给 3 轮（≈300ms）留余量，避免动画慢一点就白扫一次全树。
     */
    private const val EVENT_DIALOG_AFTER_N = 3

    /**
     * ★★2026-11-20（用户）：事件页**直调**的"只投一次"窗口。
     *   直调是真 onClick（`EventDelegate.Execute`），同一次选择重复投 = 游戏真的收到多次选中 ⇒
     *   状态错乱（三选一 205 事故同源）。所以同一张卡/同一个跳过按钮在 [EVENT_PRESS_HOLD_MS] 内
     *   只投一次，之后只等页面自己变。
     */
    private const val EVENT_PRESS_HOLD_MS = 2_500L
    /** 已直调过的选项卡钥匙 `ev|eventId|第N项` */
    private var evPressKey: String? = null
    private var evPressAt = 0L
    /** 已直调过的"演出跳过"钥匙 */
    private var evSkipKey: String? = null
    private var evSkipAt = 0L


    /**
     * 事件选择页（EVENT_CHOICE=4）：按 eventId 选第 N 张选项卡（PC `handle_event_choice` 1088）。
     * 读取走 agent 的 `labEvent`（返回每张卡的按钮屏幕坐标）；选项序号由 P4 `EventPick.pick` 给出（默认第 1 项）。
     * ★卡片按屏幕 x 左→右排序（与 PC `find_list` 按 x 排序后取第 N 张一致）。
     */
    private suspend fun handleEventChoice(
        input: InputBackend,
        tag: String,
        tries: MutableMap<String, Int>,
        pageTop: String,
    ) {
        // ★★2026-11-08 修（用户："这个找的逻辑明显错了"）：**页面还没铺开就别去树里找**。
        //   实测 12:44:47 那轮：`statusType` 已经是 EVENT_CHOICE，但**事件对话框还没建出来**（顶框=无）
        //   ⇒ 我们照样去 DialogArea 里按素材名（`labyrinth_btn_main_square`）找选项卡，必然 0 个
        //   ⇒ 白跑一趟遍历 + 打一条假告警 `[!] 事件页没有可点选项卡`，下一轮顶框变成
        //   `DialogLabyrinthEvent(Clone)` 才读得到。
        //   ⇒ 现在：顶框还不是事件对话框 = **还没就绪**，直接等下一轮（不找、不报错、不消耗处理轮次）。
        //   ★但**不能死等**：顶框名万一与预期不同（新的事件页变体）⇒ 等 [EVENT_NOTREADY_MAX] 轮
        //     就退回老行为（照找不误），别把整局卡在这一页。
        val kWait = "$tag|notready"
        val waited = tries[kWait] ?: 0
        if (!pageTop.startsWith(EVENT_DLG_PREFIX) && waited < EVENT_NOTREADY_MAX) {
            tries[kWait] = waited + 1
            if (waited == 0) {
                AppState.addLog(
                    "… 事件页还没铺开（顶框=${pageTop.ifBlank { "无" }}）→ 等下一轮再看（先不找选项卡）",
                )
            }
            return
        }
        val n = bump(tries, tag)
        // ★★2026-11-08 定案：**首选 `labEvent`**（字段读，实测首读 143ms、之后 2~3ms 有实例缓存）。
        //   它一次给全三样东西，比"多调几个接口 + 遍历 UI 树"强得多：
        //     ① `eventId`（`DialogLabyrinthEvent+0x170`，**真实值**；`labStatus` 里那个字段实测恒 0）；
        //     ② 每张卡的**文案**（`comment`/`result`）；
        //     ③ 每张卡的**按钮坐标**（`btnX`/`btnY`）。
        //   ★★最关键：实测事件页有 **3 个 `PartsLabyrinthEventSelectInfo` 槽位**，但只有 **2 张卡**
        //     —— 第 3 个槽的按钮在 **(960,893)（屏幕正中）**，`activeInHierarchy=true` 但**文案全空**。
        //     用 UI 树找必然多出这一颗 ⇒ 按 x 排序取第 2 项会点到它 ⇒ **选错卡**（15:53 事故的另一半）。
        //     而 `labEvent` 里空槽的 `comment`/`result` 就是空串 ⇒ **一次就能筛掉**。
        val ev = runCatching { AgentApi.labEvent() }.getOrNull()
        val eidEv = ev?.optInt("eventId")?.takeIf { it > 0 }
        val eid = eidEv ?: (extraIds()?.opt("eventId") as? Number)?.toInt()
        // ★2026-11-13（用户）：事件节点采样 —— {节点编号, 事件编号}；本 handler 没有 st，节点号从 tag 里取
        runCatching {
            val bid = Regex("节点(\\d+)").find(tag)?.groupValues?.getOrNull(1).orEmpty()
            SampleLog.event(appCtx, bid, eid ?: 0)
        }
        lastEventAt = SystemClock.elapsedRealtime()
        lastEventId = eid ?: 0
        // ★事件切换（2026-11-03）：先把**上一个事件**的效果差结算掉（它就是本事件的"before"），再给本事件拍快照
        if (n == 1) {
            if (evSnapId != null && evSnapId != eid) settleEventDiff(eid, "事件切换")
            snapshotEvent(eid)
        }
        // ★★取选项卡按钮：**首选 `labEvent` 给的卡片**（字段读，2~3ms），UI 树只作兜底。
        //
        //   为什么 UI 树不能当首选（2026-11-08 实测父链后搞清楚的）：
        //   ```
        //     这个事件只有 2 张卡，但 EventSelectInfoParent 下有 **3 个** PartsLabyrinthEventSelectInfo 槽：
        //       (683,893)  左卡「选择」   ← 真卡
        //       (1238,893) 右卡「选择」   ← 真卡
        //       (960,893)  屏幕正中       ← **空槽**（activeInHierarchy=true，但没有内容）
        //     三颗按钮的父链完全一样，`top`/`dialog`/`layer` 都能找到这 3 颗。
        //   ```
        //   ⇒ 按 x 排序取第 2 项会点到那颗**空槽按钮**（在屏幕正中，什么都不是）⇒ 选错卡。
        //   ⇒ 而 `labEvent` 里空槽的 `comment`/`result` 就是**空串**，一次就能筛掉；
        //     它同时还给出真实 `eventId` 和卡面文案。
        //
        //   ⚠UI 树那条路保留，仅在 `labEvent` 读不到卡片时兜底（旧版该接口有过返回空的历史）。
        val expectN = EventPick.totalChoices(eid)
        val evCards = (0 until (ev?.optJSONArray("cards")?.length() ?: 0)).mapNotNull { i ->
            ev?.optJSONArray("cards")?.optJSONObject(i)
        }.filter { c ->
            // ★"有内容的卡"：文案非空（空槽全空）+ 有按钮坐标
            (c.optString("comment").isNotEmpty() || c.optString("result").isNotEmpty()) &&
                (c.opt("btnX") as? Number) != null && (c.opt("btnY") as? Number) != null
        }.sortedBy { (it.opt("btnX") as? Number)?.toInt() ?: 0 }

        val btns: List<JSONObject> = if (evCards.isNotEmpty()) {
            evCards.map { c ->
                JSONObject()
                    .put("x", (c.opt("btnX") as? Number)?.toInt() ?: 0)
                    .put("y", (c.opt("btnY") as? Number)?.toInt() ?: 0)
                    .put("name", "E${c.opt("i")}")
            }
        } else {
            // ── 兜底：从 UI 树找（`^labyrinth_btn_main_square`），根用 `top`（最上层对话框子树）
            var arr = AgentApi.findList(EVENT_CHOICE_BTN_RE, "top")
            val gotTop = arr?.length() ?: 0
            // 数量不够 ⇒ 先当"按钮还在逐个建出来"等一轮；连续多轮还不够才退 DialogArea
            if (expectN > 0 && gotTop < expectN && n >= EVENT_DIALOG_AFTER_N) {
                if (n == EVENT_DIALOG_AFTER_N) {
                    AppState.addLog(
                        "… 事件(${eid ?: "?"}) labEvent 没给卡片、最上层对话框连续 $n 轮只找到 $gotTop/$expectN 个 → 退 DialogArea 再找",
                    )
                }
                arr = AgentApi.findList(EVENT_CHOICE_BTN_RE, "dialog")
            }
            if ((arr == null || arr.length() == 0) && n >= EVENT_LAYER_AFTER_N) {
                if (n == EVENT_LAYER_AFTER_N) {
                    AppState.addLog("… 事件选项卡在对话框里连续 $n 轮没找到 → 退到父层再找一次")
                }
                arr = AgentApi.findList(EVENT_CHOICE_BTN_RE, "layer")
            }
            (0 until (arr?.length() ?: 0))
                .mapNotNull { arr?.optJSONObject(it) }
                .filter { (it.opt("x") as? Number) != null && (it.opt("y") as? Number) != null }
                .sortedBy { (it.opt("x") as? Number)?.toInt() ?: 0 }
        }
        if (btns.isEmpty()) {
            // ★★2026-11-08 修：**别把"页面还在铺开"报成故障**。
            //   实测 12:44:48 顶框已经是 DialogLabyrinthEvent，但选项卡按钮还没 active/还没落位
            //   ⇒ 读 0 个是正常的，下一轮（~1s 后）就读到了。原来第 1、2 轮都打 `[!]`，纯噪声。
            //   现在：第 1 轮说"还在铺"，连续 [EVENT_LAYER_AFTER_N] 轮才升级成告警。
            val kNoBtn = "$tag|nobtn"
            val c = (tries[kNoBtn] ?: 0) + 1
            tries[kNoBtn] = c
            if (c == 1) {
                AppState.addLog("… 事件选项卡还没铺好 → 等下一轮（先不点）")
            } else if (c == EVENT_LAYER_AFTER_N) {
                AppState.addLog("[!] 事件页连续 $c 轮读不到选项卡（树里没找到 ${EVENT_CHOICE_BTN_RE}）→ 继续等，不猜")
            }
            return
        }
        tries.remove("$tag|nobtn")
        // ★★数量仍不足（连 DialogArea 全树都只找到部分）⇒ **不点**：少一张就可能选错卡（15:53 事故）。
        //   等下一轮（按钮在逐个建出来）；真的一直不足就靠下面的 `pick` 返回 null + 超时停手兜底。
        if (expectN > 0 && btns.size < expectN) {
            val kShort = "$tag|short"
            val c = (tries[kShort] ?: 0) + 1
            tries[kShort] = c
            if (c == 1 || c % 10 == 0) {
                AppState.addLog(
                    "[!] 事件(${eid ?: "?"})：只找到 ${btns.size}/$expectN 个选项卡 → 不点（等它铺全；第 $c 轮）",
                )
            }
            return
        }
        tries.remove("$tag|short")
        val want = EventPick.pick(eid, btns.size)
        if (want == null) {
            // ★这个事件**没配置选项** ⇒ **不猜**（用户 2026-10-16："事件也有二选一/三选一，都不要随便选"）。
            //   ① 把 event id + 屏上选项卡（序号/名字/坐标）打出来，方便往 `event_choice.json` 补一行；
            //   ② 不点，等人工点一次（页面推进后本函数自然不再被调用）；
            //   ③ 一直停在这页 [EVENT_NOPICK_GIVEUP_S] 秒 ⇒ 停手留人工（不无声空转）。
            // ★★2026-11-08：null 现在有**两个**原因（没配置 / 配置要的那项超出找到的数量），
            //   日志必须分开说 —— 否则"少找到一颗"会被写成"没配置"，看日志的人会被带偏。
            if (n == 1) {
                val cfg = eid?.let { EventPick.EVENT_PICK[it] }
                val reason = if (cfg != null) {
                    "**配置要选第 $cfg 项，但屏上只找到 ${btns.size} 个选项卡**（读数不全 → 等下一轮重读）"
                } else {
                    "**没配置选项**（event_choice.json 里没有它）→ **不猜、不点**；请人工选一次，或补一行配置"
                }
                AppState.addLog(
                    "[!] 事件(${eid ?: "?"}) $reason。候选=" +
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
                stopReason = "事件未配置（id=${eid ?: "?"}）"
                stopRequested = true
            }
            return
        }
        if (n == 1) {
            // ★各卡「可能结果」（来自事件结果表；分支是随机的，具体落哪支由游戏掷）
            val cand = eid?.let { EventResult.choicesText(it) }
            AppState.addLog(
                "事件(${eid ?: "?"})：选第 $want 项（共 ${btns.size} 项）" +
                    if (cand.isNullOrBlank()) "" else "｜各卡可能结果：$cand",
            )
        }
        // ★悬浮窗（2026-10-17 用户："事件选择：xxx（选项内容）"）：选项卡坐标走 UI 树（快），
        //   但**选项正文**只有类读取 `labEvent` 才给（`commentLabel`）⇒ 每页第一次调一次（几十 ms，
        //   而且是 DialogArea 优先，不是扫堆）拿到正文；拿不到就退回"第 N 项"。
        if (n == 1) {
            val cm = AgentApi.labEvent()?.optJSONArray("cards")
                ?.optJSONObject((want - 1).coerceAtLeast(0))?.optString("comment").orEmpty()
            OverlayStatus.setState("事件选择：" + cm.ifBlank { "第 $want 项" }.take(28))
        }
        val chosen = btns[(want - 1).coerceIn(0, btns.size - 1)]
        val cx = chosen.optInt("x")
        val cy = chosen.optInt("y")
        // ★★2026-11-20（用户）：**直调优先** —— 按卡片 `selectButton(+0x80)` 指针触发 onClick，不投坐标。
        //   好处：不看卡片"从屏下滑入"的坐标抖动、也不会误选空槽（3 槽 2 卡那种）。
        //   ★只投一次：直调是真 onClick，重复投会重复提交选择（205 同源）⇒ 已投过就只等页面变。
        val pk = "ev|${eid ?: 0}|$want"
        if (evPressKey == pk && SystemClock.elapsedRealtime() - evPressAt < EVENT_PRESS_HOLD_MS) {
            return
        }
        val pr = runCatching { AgentApi.labEventPress(want - 1) }.getOrNull()
        if (pr?.optBoolean("ok") == true) {
            evPressKey = pk
            evPressAt = SystemClock.elapsedRealtime()
            AppState.addLog("事件选项卡#$want（直调；eventId=${pr.opt("eventId")}）")
            return
        }
        // 卡片还在屏外（滑入动画中）⇒ 这一轮不点，等下一轮重读（不退回坐标硬点）
        if (pr?.optBoolean("offscreen") == true) {
            if (n <= 2) {
                AppState.addLog("… 事件选项卡#$want 还在屏外（${pr.optString("err")}）→ 等下一轮重读")
            }
            return
        }
        // ★★2026-11-20（用户："去掉吧"）：**坐标兜底整段删除**。理由同事件跳过 ——
        //   直调失败基本都是"这一拍时机不对"（实例没就绪/卡片还在滑入/读不到），
        //   等下一轮就会成功；退回坐标点击只会在错误时机点一下，是额外风险（点穿/误触）。
        //   `cx/cy` 仅保留在诊断里（下面日志），不再投递。
        if (n <= 2) {
            AppState.addLog(
                "… 事件选项卡#$want：直调未成功（" +
                    pr?.optString("err").orEmpty().ifEmpty { "无回包" } +
                    "）→ 等下一轮（不点坐标；读到的是 ($cx,$cy)）",
            )
        }
    }

    /**
     * ★**事件效果前后差**（2026-11-03 用户："1112 比较特殊，不一定能从结果 id 读出来，
     * 需要关注事件前后的角色数量变化，可能是 2~5 中的一个数"）。
     *
     * 金币在迷宫内读不到（没有字段），所以效果只能靠**持有量差**：
     *   · 角色数（`labUnits`）—— 1112 的"纯成为伙伴"就靠这个；
     *   · 遗物件数（`labOwnRelics` 的**条数**，不是 `count` 求和 —— 那是 EffectedCount）—— 钓鱼成功 = 遗物 +1。
     * 事件页第一次出现时拍快照，切到下一个事件或回到地图时结算一次，只写日志。
     *
     * ★2026-11-03 用户补充（1112）：「固定角色（黑骑/纯）+ 随机角色 2~4 个；**已持有黑骑就直接少给一个**」
     *   ⇒ 「角色 +N」**不等于**印记数，N = 增量 − (这次是否新给到黑骑)。
     *   所以快照要存**角色 id 集合**（不只是个数），结算时看新增了谁、里面有没有纯，
     *   再把「印记数」单独算出来写进日志/探针（见 [FIXED_PARTNER_UNITS]）。
     *
     * 斗技场（1105/1109/1113/1117）不拍快照：它们后面就是战斗，战斗收益会和事件收益混在一起。
     */
    private val EVENT_DIFF_IGNORE = setOf(1105, 1109, 1113, 1117)

    /**
     * 「纯」(黑骑)**本体** unit id —— 事件 1112 卡2 的固定角色，**只有本体算**（2026-11-03 用户）。
     * 夏日 113601 / 圣诞 124201 是别的形态，给了也不会顶掉本体 ⇒ 不算固定角色。
     * 已持有时游戏不再给，所以要把它从角色增量里剥掉才是真正的「印记数」。
     */
    private const val FIXED_PARTNER_UNIT = 104701

    /** 事件卡的描述里出现这个词 = 这张卡除了印记还会给固定角色（如 1112 的「纯成为伙伴」） */
    private const val FIXED_PARTNER_HINT = "成为伙伴"

    /**
     * 只有 1112 这个"固定角色 + 随机 2~4"的事件才需要把固定角色从增量里剥掉。
     * ★别推广到 1101~1104：那几张卡描述里也有「成为伙伴」，但给的是**印记**（角色不会立刻进持有池），
     *   照 1112 的算法会得出"印记数=0"的错数。
     */
    private const val FIXED_PARTNER_EVENT = 1112

    /**
     * ★★2026-11-20（用户）：判"真的回到地图"——`statusType==0 && top==""` 不够，
     *   事件收尾时收益在地图页结算，中间有"空窗期"（无 top）但事件仍在继续。
     *   真判据 = 地图上至少有一个**可前往**节点（`PartsLabyrinthPanel.panelState==4`）。
     *   这里记录"开始等可前往节点"的时刻（0 = 没在等）；超 [MOVE_READY_WAIT_MAX_MS] 就放行防卡死。
     */
    private var moveReadySince = 0L
    private const val MOVE_READY_WAIT_MAX_MS = 10_000L

    private var evSnapId: Int? = null
    private var evSnapUnits = -1
    private var evSnapRelics = -1
    /** 拍快照时的持有角色 id 集合（算「新增了谁」用；不只是个数） */
    private var evSnapUnitIds: Set<Int> = emptySet()
    /** 拍快照时的持有遗物 id 列表（保留重复，算「新增了哪几件」用） */
    private var evSnapRelicIds: List<String> = emptyList()
    /** 本次事件演出页读到的 eventResultId（结算日志带上，结果 id ↔ 分支 ↔ 实际增量 才能对上） */
    private var evRid: Int? = null

    /**
     * 一次持有量快照：角色数 / 遗物件数 / 角色 id 集合 / **遗物 id 列表**（读不到 = -1 / 空集）。
     * 遗物留**列表**（不是集合）是为了看得出"同一遗物是否有多条记录"（判断条数到底等于不等于件数）。
     */
    private class LabSnap(
        val units: Int,
        val relics: Int,
        val ids: Set<Int>,
        val relicIds: List<String> = emptyList(),
    )

    /** 上一次报过的"持有遗物读取异常"文案（同一条只报一次，别刷屏） */
    private var saidRelicErr = ""

    /** 上次打过"结算按钮字段读诊断"的顶框名（同一页只打一次，2026-11-19） */
    private var resMemDiagKey: String? = null

    private suspend fun labSnap(): LabSnap {
        val u = AgentApi.labUnits()?.optJSONArray("unitIds")
        val ids = if (u == null) emptySet() else
            (0 until u.length()).mapNotNull { (u.opt(it) as? Number)?.toInt() }.toSet()
        val own = AgentApi.labOwnRelics()
        val relicArr = own?.optJSONArray("relics")
        // ★2026-11-03 修正：件数 = **列表条数**，不是 `count` 求和。
        //   agent 里的 `count` 来自 `LabyrinthRelicInfo+0x1C = EffectedCount`（效果**已生效次数**），
        //   刚拿到的遗物还没生效 ⇒ 全是 0，求和永远是 0（PC 侧 pick_policy/team_build 早就记过这个坑，
        //   这里我照抄 PC 的 `count 累加` 说法写错了）。全项目其它地方（凹分检测/选人 ctx/dot 计数）
        //   用的都是 `relics.length()`，保持一致。
        val relics = if (relicArr == null) -1 else relicArr.length()
        // 遗物 id 列表（按返回顺序，保留重复）—— 用来算"新增了哪些"，也用来判断"条数 vs 件数"
        val relicIds = if (relicArr == null) emptyList() else
            (0 until relicArr.length()).mapNotNull { i ->
                relicArr.optJSONObject(i)?.opt("relicId")?.toString()
            }
        // 读失败时把 agent 的 err 露出来一次（以前静默吞掉，导致"遗物恒 0"查不出原因）
        // ★注意 Android 的 org.json：字段值是 JSON null 时 optString 会返回**字符串 "null"**，
        //   直接 isNotBlank() 会天天误报（2026-11-03 实机一轮就报了两条假警报）⇒ 显式排掉。
        val err = own?.optString("err").orEmpty().let { if (it == "null") "" else it }
        if (err.isNotBlank() && saidRelicErr != err) {
            saidRelicErr = err
            AppState.addLog("[!] 持有遗物读取异常（agent）：$err → 本次遗物件数按未知处理")
        }
        return LabSnap(u?.length() ?: -1, relics, ids, relicIds)
    }

    /** 角色 id → 中文名（查不到就回 id 字符串）；表未加载也不报错 */
    private fun unitNameOf(uid: Int): String {
        val c = appCtx ?: return uid.toString()
        val nm = runCatching {
            GameData.tables(c).unit(uid.toString())?.optString("name").orEmpty()
        }.getOrDefault("")
        return nm.ifBlank { uid.toString() }
    }

    /** 事件页第一次出现时拍快照（斗技场跳过，见 [EVENT_DIFF_IGNORE]） */
    private suspend fun snapshotEvent(id: Int?) {
        if (id != null && id in EVENT_DIFF_IGNORE) return
        val s = labSnap()
        evSnapId = id
        evSnapUnits = s.units
        evSnapRelics = s.relics
        evSnapUnitIds = s.ids
        evSnapRelicIds = s.relicIds
    }

    /** 结算上一个事件的效果差（只写日志）：切到下一个事件、或回到地图时调 */
    private suspend fun settleEventDiff(nextEventId: Int?, why: String) {
        val id = evSnapId ?: return
        evSnapId = null
        val s = labSnap()
        val du = if (s.units >= 0 && evSnapUnits >= 0) s.units - evSnapUnits else null
        val dr = if (s.relics >= 0 && evSnapRelics >= 0) s.relics - evSnapRelics else null
        val rid = evRid
        evRid = null
        val hit = rid?.let { EventResult.hitOf(it) }
        // ★★2026-11-08：**事后核对** ——「实际落在第几张卡」vs「event_choice.json 里配的第几项」。
        //   能抓到"选项卡没找全 ⇒ 被静默压成第 1 项"这类问题（实测 15:53:18 就是这样选错的：
        //   配置要卡2 金币750，屏上只找到 1 颗按钮 ⇒ 点了卡1 金币500，整条链一声不吭）。
        hit?.let { h ->
            val want = EventPick.EVENT_PICK[h.eid]
            if (want != null && want != h.slot) {
                AppState.addLog(
                    "[!] 事件${h.eid}：**实际落在卡${h.slot}，配置要的是卡$want** → 上次选择可能出错" +
                        "（选项卡没找全 / 点到了别的卡）",
                )
            }
        }
        // ★逻辑模式（2026-11-18）：采集本节点事件事实（只采集，**不判规则** —— 规则只在节点结束时 fire）
        if (rid != null && hit != null) {
            nodeEventFacts += LogicRule.EventFact(hit.eid, rid, hit.slot, du)
        }
        // ★"获得角色<N"型：只能用**结算后**的角色差判（演出页那一刻差恒为 0，见 [eventRuleCheck]）
        if (rid != null && du != null && EventFamily.isUnitRid(rid)) judgeUnitOutcome(hit, rid, du)
        // ★新增了哪些角色（不只是个数）：持有池为空（读失败）时算不出来，留空
        val added = if (evSnapUnitIds.isEmpty() || s.ids.isEmpty()) emptyList()
        else (s.ids - evSnapUnitIds).sorted()
        val addedNames = added.map { unitNameOf(it) }
        // ★遗物增量按 **id 多重集差**算（2026-11-03 用户："遗物数量没变，是因为这几个事件不给遗物吗"）：
        //   只有真的多出条目才打 `+新增=[id×n]`，顺手也能看出"同一 id 出现多条"（条数 ≠ 件数的那种情况）。
        val relicAdded = if (dr != null && dr != 0 && evSnapRelicIds.isNotEmpty() && s.relicIds.isNotEmpty()) {
            val before = evSnapRelicIds.groupingBy { it }.eachCount().toMutableMap()
            val out = mutableListOf<String>()
            for (rid2 in s.relicIds) {
                val n = before[rid2] ?: 0
                if (n > 0) before[rid2] = n - 1 else out += rid2
            }
            out
        } else emptyList()
        val fixedGained = added.any { it == FIXED_PARTNER_UNIT }
        // 1112 且这张卡描述里有「成为伙伴」⇒ 有固定角色；印记数 = 角色增量 − (固定角色是否新给)
        val hasFixed = hit?.eid == FIXED_PARTNER_EVENT && hit.desc.contains(FIXED_PARTNER_HINT)
        val tickets = if (hasFixed && du != null) du - (if (fixedGained) 1 else 0) else null
        if (du == null && dr == null && added.isEmpty()) return
        AppState.addLog(
            "事件效果观测（$why eventId=$id" + (rid?.let { " rid=$it" } ?: "") + "）：" +
                (hit?.label?.let { "$it ｜ " } ?: "") +
                "角色 ${evSnapUnits}→${s.units}" +
                (du?.let { "（${if (it >= 0) "+" else ""}$it）" } ?: "") +
                (if (addedNames.isEmpty()) "" else "新增=[${addedNames.take(6).joinToString("/")}" +
                    (if (addedNames.size > 6) "…共${addedNames.size}" else "") + "]") +
                " 遗物 ${evSnapRelics}→${s.relics}" +
                (dr?.let { "（${if (it >= 0) "+" else ""}$it）" } ?: "") +
                (if (relicAdded.isEmpty()) "" else "新增=[${relicAdded.take(8).joinToString("/")}" +
                    (if (relicAdded.size > 8) "…共${relicAdded.size}" else "") + "]") +
                (tickets?.let { " ｜ 印记数=$it" + if (hasFixed) "（含固定角色${if (fixedGained) "已新得" else "已持有"}）" else "" } ?: "") +
                (nextEventId?.let { " → 下一事件 eventId=$it" } ?: " → 回地图"),
        )
        // ★结构化落盘（探针同一个 jsonl，ev=event）：以后统计"某分支实际给几个"就看这里
        runCatching {
            val o = JSONObject()
                .put("eventId", id)
                .put("why", why)
                .put("unitsBefore", evSnapUnits).put("unitsAfter", s.units)
                .put("relicsBefore", evSnapRelics).put("relicsAfter", s.relics)
                .put("addedCount", added.size)
            rid?.let { o.put("resultId", it) }
            hit?.let {
                o.put("card", it.slot).put("branch", it.branch).put("where", it.label)
                if (it.desc.isNotEmpty()) o.put("desc", it.desc)
            }
            du?.let { o.put("dUnits", it) }
            dr?.let { o.put("dRelics", it) }
            if (added.isNotEmpty()) {
                val a = JSONArray()
                added.forEach { uid -> a.put(JSONObject().put("id", uid).put("name", unitNameOf(uid))) }
                o.put("added", a)
            }
            if (hasFixed) o.put("fixedGained", fixedGained)
            tickets?.let { o.put("tickets", it) }
            nextEventId?.let { o.put("nextEventId", it) }
            PlanProbe.eventEvent(o)
        }
    }

    /**
     * ★**事件结果观测**（2026-11-03）：演出页第一轮把 `eventResultId` 读出来，交给 [EventResult]
     * 直接翻成「事件/卡片/分支 → 效果」，并记下来给 [settleEventDiff] 做前后差对账。
     *
     * `eventResultId`（`LabyrinthStatus +0x40`）才是结果的权威来源；金币在迷宫内读不到
     * （LabyrinthTempData/LabyrinthStatus 都没有金币字段）⇒ 只能靠 id + 结果表 + 持有量前后差。
     *
     * ★2026-11-03 清理：这里原来还会 `findTextList` 抓一屏文字（探路期为了"能不能读到结果文案"）。
     *   实测抓到的是「地图 ｜ 状况 ｜ 角色 ｜ 迷宫遗物 …」这种**顶栏菜单**，不是结果文案，
     *   而且每次多花 1~2 次慢 RPC ⇒ 结果已能由 id + 结果表精确解出，抓取整段删掉。
     */
    private suspend fun observeEventResult(st: JSONObject) {
        // ★ids 是**按需读**的（分派读里没有 eventId/eventResultId/effectNum）⇒ 到这儿才读
        val x = extraIds()
        val eidRaw = (x?.opt("eventId") as? Number)?.toInt()
        val erid = (x?.opt("eventResultId") as? Number)?.toInt()
        val eff = (x?.opt("effectNum") as? Number)?.toInt()
        // ★记下来给 [settleEventDiff] 用：结算那一行要能对上「走了哪个分支 → 实际给了什么」
        if (erid != null && erid > 0) evRid = erid
        val decoded = erid?.let { EventResult.describe(it) } ?: "（结果表里没有这个 id）"
        AppState.addLog(
            "事件结果观测（块${jStr(st, "blockId")} eventId=$eidRaw eventResultId=$erid effectNum=$eff）：$decoded",
        )
    }

    /**
     * ★凹分模式·条件 2 判定（断言模式，2026-11-03 用户需求）：这次结算有没有让某条断言的条件被满足。
     *
     * 语义（用户口径）：一条断言 = 一组条件「当遇到事件 X 时，且结果是 Y 时」，
     *   条件之间是**「或」** —— **任意一条**被满足就撤退
     *   （"只要遇到了对应的事件、并满足选中的那个随机结果"即可）。
     *
     * 命中**不当场撤退**，只记 [pendingEventRetreat]：等事件处理完、**准备前往下一节点时**
     *   （回到地图、地图上能看到「撤退」按钮的那一刻）再撤 —— 见该字段与地图分支的注释。
     * 没配规则/模式是「无」/凹分总开关关着 ⇒ 一行都不打，完全不介入（与条件 1 同口径）。
     *
     * ★每轮都查（不只第一轮）：演出页刚弹出时 `eventResultId` 可能还没填上，
     *   等它填好的那一轮再判，才不会漏掉「结果在第 2~3 轮才出现」的事件。
     *   成本极低：读的是内存里的 SharedPreferences + 几十条规则的小列表比对。
     *
     * ★事件 id **以结果表反查为准** —— status 里的 `eventId` 实测一直是 0
     *   （日志里 8 条结果观测全是 `eventId=0 eventResultId=…`），而 rid → (事件, 卡, 分支) 是确定的。
     */
    private suspend fun eventRuleCheck() {
        if (pendingEventRetreat != null) return
        val ctx = appCtx ?: return
        if (!RerollSettings.loadScoreChaseOn(ctx)) return
        when (RerollSettings.loadScoreChaseMode2(ctx)) {
            EventRetreatRule.Mode.NONE -> return
            EventRetreatRule.Mode.LOGIC -> return   // ★逻辑模式：规则只在节点结束时 fire（见 logicRuleFire），演出页不判
            EventRetreatRule.Mode.ASSERT -> Unit
        }
        val tracker = eventRuleTracker ?: return
        // 用户可能中途改条件 ⇒ 每次都重读（SharedPreferences 是内存缓存，几乎零成本）
        tracker.conds = RerollSettings.loadScoreChaseConds(ctx, EventRetreatRule.Mode.ASSERT).map { c -> normalizeCond(c) }
        if (tracker.conds.isEmpty()) return
        val x = extraIds()                                   // ★按需读 ids（分派读里没有）
        val erid = (x?.opt("eventResultId") as? Number)?.toInt()?.takeIf { it > 0 } ?: return
        val hitInfo = EventResult.hitOf(erid)
        val eid = hitInfo?.eid
            ?: (x?.opt("eventId") as? Number)?.toInt()?.takeIf { it > 0 }
            ?: return
        // ★选项（卡片槽位）也要喂进去：条件可以只认某一张卡（"选了卡1 一试身手吧"）
        val slot = hitInfo?.slot ?: 0
        // ★"获得角色<N"型**不在这里判**：演出页这一刻事件效果还没生效，角色数差恒为 0
        //   （实测 22:43:58 判时 20→20，6 秒后结算才 20→23）⇒ 交给 [settleEventDiff] 用结算后的 du 判。
        if (EventFamily.isUnitRid(erid)) return
        val newly = tracker.feed(eid, slot, erid)
        if (newly.isNotEmpty()) {
            AppState.addLog(
                "凹分条件 2：条件已满足 " + newly.joinToString("、") { "条件 ${it + 1}" } +
                    "（事件 $eid" + (erid?.let { " 结果 $it" } ?: "") + "）",
            )
        }
        val hit = tracker.hitIndex()
        if (hit < 0) return
        val (done, total) = tracker.progress()
        val h = EventResult.hitOf(erid)
        val detail = "条件 ${hit + 1} 命中（$done/$total）" +
            (h?.let { " ｜ ${it.label}" + (if (it.desc.isNotBlank()) " ｜ ${it.desc}" else "") } ?: "")
        AppState.addLog("凹分条件 2：$detail → 撤退")
        pendingEventRetreat = detail
    }

    /**
     * ★凹分模式·条件 2 **逻辑模式**（2026-11-18）：**节点结束时统一 fire 一次**（由回地图分支调用）。
     * 其他时机（事件演出页等）一律不工作（用户明确）；事件事实在 [settleEventDiff] 里采集。
     *
     * ★每个节点只 fire 一次（[logicFiredNode] = `area:blockId`）：同一节点多轮回地图不重复触发，
     * 换节点（blockId 变）才再 fire。规则在开局时读入 [logicEngine]（中途改配置下局生效）。
     *
     * ★★cs128 续局（2026-11-21 用户报障）：**每次节点 fire 后把 token + firedNode 按 enterId 落盘**
     * （[LogicTokenStore]）；重启 App 续接同一局时第一次 fire 前按 enterId 读回，token 接着算
     * （T1=2 → 下个节点 3，不再从 1 开始）、同一节点也不重复 fire。新局 enterId 不同 ⇒ 从 0。
     */
    private fun logicRuleFire(st: org.json.JSONObject) {
        if (pendingEventRetreat != null) return
        val ctx = appCtx ?: return
        if (!RerollSettings.loadScoreChaseOn(ctx)) return
        if (RerollSettings.loadScoreChaseMode2(ctx) != EventRetreatRule.Mode.LOGIC) return
        if (logicEngine.rules.isEmpty()) return
        val area = (st.opt("area") as? Number)?.toInt() ?: -1
        val blockId = st.optString("blockId", "")
        val nodeKey = "$area:$blockId"

        // ★续局恢复：每局第一次 fire 时按 enterId 比对落盘快照（同局读回 / 新局从 0）
        val eid = (st.opt("enterId") as? Number)?.toLong() ?: -1L
        if (eid > 0L) logicEnterId = eid
        if (!logicRestoreTried) {
            logicRestoreTried = true
            val snap = LogicTokenStore.load(ctx)
            if (snap != null && eid > 0L && snap.enterId == eid) {
                for (i in 0 until LogicRule.TOKEN_COUNT) {
                    logicEngine.tokens[i] = snap.tokens[i].coerceIn(LogicRule.TOKEN_MIN, LogicRule.TOKEN_MAX)
                }
                logicFiredNode = snap.firedNode.ifEmpty { null }
                AppState.addLog(
                    "凹分条件 2（逻辑）续局恢复：enterId=$eid ｜ ${logicEngine.tokensState()} ｜ " +
                        "上次 fire 节点=${logicFiredNode ?: "无"}",
                )
            } else if (snap != null && eid > 0L) {
                AppState.addLog(
                    "凹分条件 2（逻辑）新局：enterId=$eid（快照是 ${snap.enterId}）→ token 从 0 开始",
                )
                LogicTokenStore.clear(ctx)
            }
        }

        if (nodeKey == logicFiredNode) return
        logicFiredNode = nodeKey
        val fired = logicEngine.fire(LogicRule.Ctx(area, nodeEventFacts))
        nodeEventFacts.clear()
        // ★写穿落盘：每个节点 fire 后都存（含"无命中"的节点——firedNode 也要记，防重启重复 fire）
        if (logicEnterId > 0L) {
            LogicTokenStore.save(ctx, logicEnterId, logicEngine.tokens, logicFiredNode ?: "")
        }
        if (fired.isEmpty()) {
            if (!saidLogicMode2) {
                saidLogicMode2 = true
                AppState.addLog(
                    "[i] 凹分条件 2：逻辑模式已启用（${logicEngine.rules.size} 条规则，节点结束时判定）" +
                        "｜本节点无命中 ${logicEngine.tokensState()}",
                )
            }
            return
        }
        val last = fired.last()
        AppState.addLog(
            "凹分条件 2（逻辑）节点${blockId} 层数$area：" +
                fired.joinToString("；") { it.log } + "｜${logicEngine.tokensState()}",
        )
        // ★撤退也走同一条"等回地图再撤"的兑现链（此时已在回地图，下一轮 doPendingEventRetreat 兑现）
        if (last.act is LogicRule.Act.Retreat) pendingEventRetreat = "逻辑模式：${last.log}"
    }

    /**
     * ★"获得角色<N"型结果的判定（用户 2026-11-03）：只看**事件前后角色数差** [du]，
     *   三档不互斥（du=2 时 <3、<4、<5 同时成立）。
     *
     * ★必须在 [settleEventDiff] 里调、不能在演出页判：实测演出页那一刻事件效果还没生效，
     *   角色数是 20→20（差 0），6 秒后结算才 20→23。早判会让"获得角色<3"永远成立 ⇒ 遇到就撤。
     */
    private fun judgeUnitOutcome(hit: EventResult.Hit?, rid: Int, du: Int) {
        if (hit == null || pendingEventRetreat != null) return
        val ctx = appCtx ?: return
        if (!RerollSettings.loadScoreChaseOn(ctx)) return
        if (RerollSettings.loadScoreChaseMode2(ctx) != EventRetreatRule.Mode.ASSERT) return
        val tracker = eventRuleTracker ?: return
        tracker.conds = RerollSettings.loadScoreChaseConds(ctx, EventRetreatRule.Mode.ASSERT).map { c -> normalizeCond(c) }
        if (tracker.conds.isEmpty()) return
        if (tracker.feed(hit.eid, hit.slot, rid, du).isEmpty()) return
        val i = tracker.hitIndex()
        if (i < 0) return
        val name = EventFamily.outcome(EventFamily.canonical(rid) ?: rid)?.name.orEmpty()
        val detail = "条件 ${i + 1} 命中" + (if (name.isNotEmpty()) "：$name" else "") +
            "（事件${hit.eid} 角色差$du）"
        AppState.addLog("凹分条件 2：$detail → 撤退")
        pendingEventRetreat = detail
    }

    /**
     * ★把条件归一化（用户 2026-11-03 的术语与口径）：
     *   · 事件 id 补全成**合并组**（公会管理协会 = 1101~1104、魔物竞技场 = 1105/1109/1113/1117）；
     *   · ★"选项"不进条件（用户："选项是我们自己选的，不是问题"）⇒ slot 归 0，
     *     结果 rid 本身带卡号，判定只看「事件 + 随机结果」；
     *   · ★这个**事件没有随机结果**时（魔物竞技场/公会：所有选项都只有一种结果），
     *     "结果是哪一个"没有意义 ⇒ 降级成「遇到这个事件就撤」
     *     （用户："没有随机结果的直接不显示后面的部分就行了"）。
     */
    private fun normalizeCond(c: EventRetreatRule.Cond): EventRetreatRule.Cond {
        var out = if (c.slot == 0) c else c.copy(slot = 0)
        val fam = EventFamily.of(out.eventIds.firstOrNull() ?: 0)
        if (fam != null) {
            // ★事件 id 补成**整族**（钓鱼 = 1314/2314/3314 …）
            if (out.eventIds != fam.eventIds) out = out.copy(eventIds = fam.eventIds)
        } else {
            // 没登记的事件（别的迷宫事件）⇒ 只按同名文案补组，行为跟以前一样
            val all = c.eventIds.flatMap { EventResult.groupOf(it) }.distinct().sorted()
            if (all.size > c.eventIds.size) out = out.copy(eventIds = all)
        }
        if (!out.useResult) return out
        // ★这个族没有随机结果 ⇒ 降级成"遇到就撤"
        if (fam?.hasRandom != true) return out.copy(resultId = 0, useResult = false)
        // ★"要结果但还没选"原样保留（ready=false ⇒ 不生效）；不能在这里降级，
        //   否则会悄悄变成"遇到就撤"（用户只是点开了【+】还没选）
        if (out.resultId <= 0) return out
        // ★结果 id 归一（老数据存的是具体 rid）
        val canon = EventFamily.canonical(out.resultId) ?: return out.copy(resultId = 0, useResult = false)
        return if (canon != out.resultId) out.copy(resultId = canon) else out
    }

    /** 回到地图后，把 [pendingEventRetreat] 兑现成一次真正的撤退（统一走 [MazeRetreat]） */
    private suspend fun doPendingEventRetreat(input: InputBackend): Boolean {
        val detail = pendingEventRetreat ?: return false
        pendingEventRetreat = null
        AppState.addLog("凹分条件 2：事件已处理完、准备前往下一节点 → 按断言撤退（$detail）")
        MazeRetreat.request(input, appCtx, MazeRetreat.Reason.EVENT_RULE, detail)
        return true
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
        // ★条件 2（断言）：每轮都判一次，等 [pendingEventRetreat] 兑现（回地图时撤）
        eventRuleCheck()
        // ★事件结果观测（2026-11-03）：演出页停留很短，第一轮就把 eventResultId 读一次（只读不点，见 [observeEventResult]）
        if (n == 1) observeEventResult(st)
        // ★★2026-11-20（用户）：跳过改**直调** —— `DialogLabyrinthEvent.dramaSkipButton(+0x160)` 字段直取。
        //   旧的文本搜索会命中屏幕正中的容器节点（实测连点 4 次 (640,360) 才打到真按钮 (1168,55)），
        //   又慢又准不了。★只投一次（直调是真 onClick）。
        val sk = "evskip|$tag"
        if (evSkipKey == sk && SystemClock.elapsedRealtime() - evSkipAt < EVENT_PRESS_HOLD_MS) return
        val skPr = runCatching { AgentApi.labEventSkip() }.getOrNull()
        if (skPr?.optBoolean("ok") == true) {
            evSkipKey = sk
            evSkipAt = SystemClock.elapsedRealtime()
            AppState.addLog("事件演出：跳过（直调）")
            return
        }
        // ★★2026-11-20（用户："为什么事件中需要点击屏幕中心？好像没有这个设计"）：
        //   **那个点屏幕中心是旧"文本搜索兜底"的残留** —— `findTextList("跳过|skip")` 命中的是整块
        //   对话框容器（坐标正好是屏幕正中 (640,360)），点了等于点对话框背景：既不是跳过，还可能误触。
        //   （2026-10-17 用户就说过"事件跳过不要点屏幕中心"；但当时代码只判"搜索为空"才不点，
        //     而容器有坐标 ⇒ 照样点了。）
        //   直调已能可靠跳过（实测每次下一轮就成功）⇒ **整段文本搜索/坐标兜底删除**：失败就等下一轮，
        //   最坏情况演出自己结束（statusType→0），不会卡死。
        if (n <= 2) {
            AppState.addLog(
                "… 事件跳过：直调未成功（" + skPr?.optString("err").orEmpty().ifEmpty { "无回包" } +
                    "）→ 等下一轮（不点坐标）",
            )
        }
        return
    }

    // ── 商店页跨轮状态（模拟 PC 模块级 _shop_drag / _shop_rr）──
    private var shopDragCount = 0
    private var shopDragTarget: String? = null
    private var shopRefreshCount = 0
    private var shopRefreshKey: String? = null

    /**
     * ★2026-11-10（用户反馈"刷新之后也有可能读取为空"）：本店**最近一次点「更新」**的时刻。
     * 刷新后货架的 `availability` 会先清零再重新填 ⇒ 这期间 `avail==0` 是"未就绪"，不是"买空了"。
     * 原来只有 `neverTouched`（进店第一帧）一道闸，**刷新后**没有任何保护。
     */
    private var shopRefreshAt = 0L

    /**
     * ★2026-11-10：本店「刷新点了但没生效」的**连续次数**（跨轮累计）。
     *
     * 用户口径（2026-11-11）：点早/点空只是执行问题，**不该反过来改变"该不该刷"的决策**，
     * 更不该因此提前关店 —— 失败就重试 / 下轮再试；次数只作"界面真出问题"的保护：
     * 连续 [SHOP_REFRESH_FAIL_MAX] 次都点不到才 `stopRequested`（停手留人工，**不关店**）。
     */
    private var shopRefreshFailN = 0



    /** ★「金币读到 0/空」的起始时刻 + 所属门店（换店或读到 >0 就清）；见 [SHOP_COIN_WAIT_MS] */
    private var shopCoinBadKey: String? = null
    private var shopCoinBadAt = 0L

    /** ★本店是否已经买过东西（买过之后金币 0 是正常值，不再当"读失败"重读；换店清零） */
    private var shopBought = false

    /**
     * ★**购买清单**（2026-11-02 用户："每轮决策一次，拉一个清单就行了 —— 1星:x件；2星:x件；
     * 3星:x件；印记:x件"）：进店或**刷新后**算一次，之后每次购买只按清单挑件，不再重跑 DP。
     * [shopQuotaKey] = `blockId|area|已刷次数`：换店/刷新/换层 ⇒ 清单作废、重新决策。
     *
     * 与「每件重算」的区别只在**计划稳定性**，省不了 `labShop` 那 ~500ms（取坐标必须读货架）。
     */
    private var shopQuota: ShopPlan.Quota? = null
    private var shopQuotaKey: String? = null

    /** "空清单但货架有候选"被丢弃的**连续**次数（超过 [SHOP_QUOTA_REJECT_MAX] 就不再纠缠，防卡死） */
    private var shopQuotaRejectN = 0

    /**
     * ★**待确认的一次购买**（2026-11-02 用户："判断'买了'的依据是什么，持有数量增加了？"）。
     *
     * 点完**不立刻扣清单**，而是下一轮读回货架核对，确认"真买到"才扣清单。
     *
     * ★判据（2026-11-02 真机日志修正）：**不能按"槽位是否仍可买"判** —— 实测买走后货架会
     *   **重排**，下一件顶到同一个 `page*3+idx` 上，于是那个位置永远"可买"，每笔都被记成
     *   "没买到→重试"（20:29:14 点 #0 付掉 1820、金币 12575→10755，却报"货架没变"）。
     *   现在用两个**硬证据**（任一成立即算到账）：
     *     ① 金币比点击前少了 ≥ 该件价格（买入必扣钱，最直接）；
     *     ② 该 `rewardId` 的**可买件数**变少（同款可能有多件，如随机印记 ×2 ⇒ 按 id 计数，不按位置）。
     *   两个都没有 ⇒ 这次点击确实没生效，不扣清单，下一轮再点（最多 [SHOP_PEND_MAX] 次）。
     *   这些量都来自**本来就要读的那份 `labShop`**，不额外增加 RPC。
     */
    private var shopPendKind: String? = null
    private var shopPendGi = -1
    private var shopPendRetry = 0

    /** 点击时的商品标识 / 价格 / 点击前金币 / 点击前该 id 的可买件数（到账确认用，见 [shopPendKind]） */
    private var shopPendRid: String? = null
    private var shopPendPrice = 0
    private var shopPendCoinBefore = -1
    private var shopPendCntBefore = 0

    /**
     * 购买子链步数：0=空闲 / 1=已点买（等确认框）/ 2=见过确认框 / 3=见得到账提示。
     * 见 [SHOP_BUY_TIMEOUT_MS] 上方的设计说明。**弹窗期只有主循环的 [shopBuyWatch] 能推进它**
     * （那时 `handleShop` 被通用弹窗段的 `continue` 跳过），所以它是纯观测、不点击。
     */
    private var shopBuyStep = 0
    private var shopBuyAt = 0L

    /**
     * ★**成交对账**（2026-11-02 用户："链观测买到了正确的东西就行"）：链只能证明"交易完成了"，
     * 证明不了"买的是清单上那一件"——若槽位坐标错，点到隔壁商品也照样弹确认框、照样扣钱。
     * 所以成交后拿**金币差额**再核一次：差额 == 该件价格 ⇒ 就是它；差额 >0 却不相等 ⇒ 点到别的了。
     */
    private var shopChkKind: String? = null
    private var shopChkPrice = 0
    private var shopChkCoin = -1

    /**
     * ★商店"同级遗物序"的上下文缓存（2026-10-22）：敌人情况 + 当前遗物，交给
     * [RelicPick.sortSameStar]。按店缓存（每轮读 `labOwnRelics` 太浪费）；**买走一件后置空** ⇒
     * 下一轮按新的持有状态重算（dot/吸血/守备的动态序会变）。每局开头也要清（同 blockId 会跨局重复）。
     */
    private var shopRelicCtx: RelicPick.Ctx? = null
    private var shopRelicCtxKey: String? = null

    /**
     * 购买子链**纯观测**：每轮调一次，只按 `pageTop` 推进 [shopBuyStep]，**不点击、不改流程**。
     *
     * 必须放在通用弹窗段**之前**：弹窗期（确认框/到账提示）`handleShop` 会被那段 `continue` 跳过，
     * 只有这里能看到"已经走到确认框 / 到账提示"。
     */
    private fun shopBuyWatch(top: String) {
        if (shopBuyStep <= 0 || shopBuyStep >= 3) return
        when {
            top.startsWith("DialogCommonMiddle") -> shopBuyStep = maxOf(shopBuyStep, 2)
            top.startsWith("DialogCommonSmall") -> shopBuyStep = 3
        }
    }

    /**
     * 某件商品当前**还能买**（未售 + `availability==1`）—— 商店"到账确认/同款计数"统一用这个口径，
     * 与 [ShopPlan.shopPlan] 里 `shelf` 的定义保持一致（PC `shop_plan` 同款）。
     */
    private fun shopBuyable(it: JSONObject): Boolean =
        !Py.truthy(it.opt("isSold")) && (Py.num(it, "availability")?.toInt() ?: -1) == 1

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
        // ★购买子链的交易锁（2026-11-02 用户方案，见 [SHOP_BUY_TIMEOUT_MS]）：
        //   能走到这里说明"已经回到商店页"（弹窗期本函数不会被调用）。
        if (shopBuyStep > 0) {
            val cost = SystemClock.elapsedRealtime() - shopBuyAt
            if (shopBuyStep >= 3) {
                // 走完 确认框 → 到账提示 → 回商店 ⇒ 这就是"买到"的铁证，**此时才计数**
                val kind = shopPendKind ?: "?"
                shopQuota?.consume(kind)
                AppState.addLog(
                    "商店：✔ 买到 $kind（#${shopPendGi} ${shopPendRid}）确认框→到账→回商店，${cost}ms" +
                        (shopQuota?.let { "；清单剩 $it" } ?: ""),
                )
                // 挂起对账：等本轮读到金币后核对"扣的钱是不是正好等于这件的价"
                shopChkKind = kind; shopChkPrice = shopPendPrice; shopChkCoin = shopPendCoinBefore
                shopBuyStep = 0; shopPendKind = null; shopPendGi = -1; shopPendRetry = 0
            } else {
                // ① 还在等确认框（step 1）：正常 ~1.7s 就弹；超过 [SHOP_BUY_CONFIRM_WAIT_MS] 说明
                //    **这一下点击根本没生效**（实测进店第一帧读到残值坐标，点在空处）⇒ 早点交回判据重试，
                //    别死等 15s。
                // ② 已经见到确认框/到账提示：整笔给 [SHOP_BUY_TIMEOUT_MS]（演出/网络慢时确实要几秒）。
                val limit = if (shopBuyStep <= 1) SHOP_BUY_CONFIRM_WAIT_MS else SHOP_BUY_TIMEOUT_MS
                if (cost <= limit) return      // 交易还在路上 ⇒ 本轮不买下一件、不重算、也不读货架
                AppState.addLog(
                    "[!] 商店：购买子链 ${limit / 1000}s 没走完（只到第 $shopBuyStep 步：$shopPendKind #${shopPendGi}）" +
                        " → 交回「金币/货架」判据",
                )
                shopBuyStep = 0; shopBuyAt = 0L      // pend 保留，交给下面的旧判据判
            }
        }
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
        // ★读取来源/耗时（2026-10-17 用户："商店还是在扫描"）：`src` = top / dlg / scan。
        //   ★2026-11-10：`top` = 从**当前最上层对话框**现取（不再用跨轮实例缓存 —— 刷新后会读到旧货架）；
        //   `scan` = 全堆扫描（实测数秒 ✗）—— 出现就说明前两路都没命中，日志里同时给出
        //   DialogArea 的子节点名 + 有没有目标组件（agent 的 `dlgMiss`），不用再靠猜。
        //   每种来源每页只报一次（正常应只出现 `top` 或 `dlg` 一行）。
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
            // 换店 ⇒ 买过标记也清；★购买清单/待确认件同样作废（不同店的货架/金币无关）
            shopRefreshCount = 0; shopRefreshKey = rkey; shopBought = false; shopQuota = null
            shopRefreshAt = 0L
            shopRefreshFailN = 0
            shopQuotaRejectN = 0
            shopPendKind = null; shopPendGi = -1; shopPendRetry = 0
            shopBuyStep = 0; shopBuyAt = 0L          // 换店 ⇒ 购买子链作废（新店的交易与上家无关）
        }
        // ★2026-11-11：用游戏 `resetCount` 对齐"本店已刷几次" —— 半路接手/闪退重进都能对上。
        //   本地 done 只会追加（max），不会把游戏已刷次数抹掉。
        val resetCnt = Py.num(sh, "resetCount")?.toInt()
        if (resetCnt != null && resetCnt > shopRefreshCount) {
            AppState.addLog("商店：对齐已刷新次数 $shopRefreshCount → $resetCnt（游戏 ShopResetCount）")
            shopRefreshCount = resetCnt
        }
        // ★金币读不到/读到 0 → **先别决策**（2026-10-18 用户："不会是没读取到金币数，误以为没操作直接退出吧"）：
        //   coin=0 时商品全被判"买不起"、刷新也"刷不起" ⇒ 立刻点「关闭」退出 —— 正好是"5 层 2w 什么都没买"。
        //   进店那几百 ms 常读不到 currentCoin 标签 ⇒ 窗口内每 [SHOP_COIN_GAP_MS] 重读，读到 >0 立刻照常决策。
        //   ★但**本店买过之后 0 是正常值**（刚花光；用户 2026-10-18："一旦购买了商品，这条规则就该失效"）：
        //     此时只有"空（根本没读到）"才继续重读，读到 0 就照常决策（没钱了就刷/关）。
        val coinRead = (sh.opt("coin") as? Number)?.toInt()
        // ★成交对账（见 [shopChkKind]）：上笔链判"买到"后，这里用金币差额确认买的**就是目标那件**。
        //   差额 == 该件价 ⇒ 正确（静默，✔ 行已说明）；>0 但不相等 ⇒ 点到了别的格子（价格不同）；
        //   差额 0 ⇒ 金币还是旧值（本轮读得早），不作为错误。
        shopChkKind?.let { k ->
            val paidNow = if (coinRead != null && shopChkCoin >= 0) shopChkCoin - coinRead else 0
            if (paidNow > 0 && paidNow != shopChkPrice) {
                AppState.addLog(
                    "[!] 商店对账：$k 清单价 $shopChkPrice、实际扣 $paidNow → **可能点到了别的商品**（请核对）",
                )
            }
            if (coinRead != null) shopChkKind = null
        }
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
        // ★上一件的**到账确认**（2026-11-02 用户："判断'买了'的依据是什么"）：
        //   点完不立刻扣清单，这里读回货架核对，**确认真买到才扣**；没到账就不扣、下一轮再点。
        //   ★判据见 [shopPendKind] 上方的修正说明：**不按槽位**（买完货架会重排，同位置永远是下一件），
        //     而是 ①金币少了该件价 或 ②同 rewardId 的可买件数少了 —— 任一成立即算买到。
        shopPendKind?.let { kind ->
            val gi = shopPendGi
            val rid = shopPendRid ?: ""
            val price = shopPendPrice
            val coinNow = coinRead
            val paid = coinNow != null && shopPendCoinBefore > 0 && shopPendCoinBefore - coinNow >= price
            val cntNow = items.count { shopBuyable(it) && it.opt("rewardId")?.toString() == rid }
            val gone = cntNow < shopPendCntBefore
            if (paid || gone) {
                shopQuota?.consume(kind)                    // ✔ 确认买到，清单才扣
                AppState.addLog(
                    "商店：✔ 买到 $kind（#${gi} $rid）" +
                        (if (paid) " 金币 $shopPendCoinBefore→$coinNow" else " 同款 $shopPendCntBefore→$cntNow 件") +
                        (if (shopPendRetry > 0) "（第 ${shopPendRetry + 1} 次点击生效）" else ""),
                )
                shopPendKind = null; shopPendGi = -1; shopPendRetry = 0
            } else if (shopPendRetry >= SHOP_PEND_MAX) {
                AppState.addLog(
                    "[!] 商店：$kind（#${gi} $rid）点了 $SHOP_PEND_MAX 次、金币和货架都没变 → 放弃该件" +
                        "（清单扣掉继续；若是「钱不够/被限购」，属正常）",
                )
                shopQuota?.consume(kind)
                shopPendKind = null; shopPendGi = -1; shopPendRetry = 0
            } else {
                shopPendRetry++
                AppState.addLog(
                    "[!] 商店：$kind（#${gi} $rid）上一轮点了但没到账（金币 $coinNow、同款 $cntNow 件）→ 重试第 $shopPendRetry 次",
                )
            }
        }
        // ★★2026-11-08 修（用户反馈 0.6.6：3 层商店 1.2 万金币一件没买）：
        //   **"货架/商品还没读全"的检查必须放在跑 DP 之前**。
        //   原来它在 [shopPlan] 之后 ⇒ 进店那一帧 `items`/`slots` 不全时，
        //   DP 会算出「买 0 件」的清单并被缓存（`shopQuota`）；而清单 key 只含 `blockId|area|已刷次数`
        //   ⇒ **整家店都按这张空清单执行**，一件都不买。
        //   实测日志特征：`金币=12090 可买=6/6 优先候选=[] 槽=[]` + `DP清单：1★0 2★0 3★0 印记0`。
        //   现在：没读全就**只重读、不跑 DP、不产出清单**。
        run {
            val slots0 = sh.optJSONArray("slots")
            val items0 = sh.optJSONArray("items")
            // ★★2026-11-08：**件数也要查**。agent 的 `_shopValid` 只要求 lineup 里
            //   `listCount >= 1`（它用来排除"过期的旧商店实例"），所以进店那一帧若只建好 1~2 件
            //   也能通过校验、`labShop` 正常返回 —— 半成品货架照样被拿去跑 DP。
            //   而游戏商店**恒为 6 件**（3 层=3 遗物+3 印记；5 层=3 遗物+1 定向+2 印记）
            //   ⇒ `items < 6` 就是"还没铺满"，此时**不决策、不产出清单**。
            // ★★2026-11-08：`availability` 是**运行时字段**，刚打开商店那一帧可能还是 0（未就绪）
            //   ⇒ `shelf` 为空 ⇒ DP 只能选"空集" ⇒ 清单全 0 ⇒ 被缓存成整店清单 ⇒ 之后一件都不买。
            //   （离线复现证明：正常输入下 DP 一定买满 ⇒ 问题只可能出在"输入还没就绪"这一瞬。）
            //   ★商品件数**不按 6 判**（用户 2026-11-08："商店没理由一个个上架"，
            //     而且各层件数不一定都是 6）—— 只有"一件都没有"才算异常。
            var avail = 0
            if (items0 != null) {
                for (i in 0 until items0.length()) {
                    val it0 = items0.optJSONObject(i) ?: continue
                    if (Py.truthy(it0.opt("isSold"))) continue
                    if ((Py.num(it0, "availability")?.toInt() ?: -1) == 1) avail++
                }
            }
            // ★只在"本店**一件都没买过**、也没刷过"时才把 `avail==0` 当"未就绪"。
            //   买空之后（或已刷过）`avail==0` 是**正常状态**（该刷新/该关店）——
            //   那种情况必须往下走，否则会卡在商店页出不去。
            val neverTouched = !shopBought && shopRefreshCount == 0
            // ★2026-11-10（用户反馈"刷新之后也有可能读取为空"）：**刷新后的短窗口**也算未就绪 ——
            //   刷新会整批换货架，新货的 availability 先清零再填；原来这道闸只覆盖"进店第一帧"，
            //   刷新后那几帧被当成"买空了"⇒ 不买也不刷，直接关店。
            //   只在本店**还没买过**时才这么判（买空之后 avail==0 仍是正常的"该关店"）。
            val justRefreshed = shopRefreshAt > 0 && !shopBought &&
                SystemClock.elapsedRealtime() - shopRefreshAt < SHOP_REFRESH_SETTLE_MS
            if (slots0 == null || slots0.length() == 0 ||
                items0 == null || items0.length() == 0 ||
                (avail == 0 && (neverTouched || justRefreshed))
            ) {
                if (n == 1 || n % 20 == 0) {
                    AppState.addLog(
                        "商店：货架还没就绪（商品 ${items0?.length() ?: 0} 件、可买 $avail 件、" +
                            "槽位 ${slots0?.length() ?: 0}）→ 重读，本轮不决策",
                    )
                }
                delay(300)
                return
            }
        }
        // ★清单生命周期（2026-11-02 用户："每轮决策一次，拉一个清单"）：
        //   key 里带 `shopRefreshCount` ⇒ **刷新后自动作废**重新决策；换店上面已清。
        //   清单存在时 [ShopPlan.shopPlan] 不跑 DP，只按清单从当前货架挑件（具体哪个遗物照旧现排序现取）。
        val qkey = "$rkey|$shopRefreshCount"
        if (shopQuotaKey != qkey) { shopQuotaKey = qkey; shopQuota = null }
        // ★2026-11-10（用户反馈："清单里 3★、货架上只有 3 个 1★ + 印记"）：清单 key **不含货架内容**，
        //   货架在脚本察觉之外变了（游戏自己刷新 / 跨局残留 / 刷新那一帧读到旧货架）时，
        //   清单会指向货架上不存在的品类 ⇒ 按清单挑件必然 0 件（本次截图就是这个）。
        //   先做一次**清单↔货架一致性**校验，不符就丢弃重算。
        val qFit = shopQuota
        if (qFit != null && !shopPlan.quotaFitsShelf(sh, qFit)) {
            AppState.addLog(
                "商店：清单 $qFit 与当前货架不符（货架=${shopPlan.shelfKindsBrief(sh)}）→ 丢弃清单，重新决策",
            )
            shopQuota = null
        }
        val plan0 = shopPlan.shopPlan(
            area, sh, done = shopRefreshCount, relicCtx = relicCtx, quota = shopQuota,
        )
        var picked = plan0.first
        var desc = plan0.second
        // ★2026-11-10（用户反馈：清单只要 3★、金币差一点 ⇒ 一件不买）：
        //   清单是**进店/刷新时**按当时金币算的；之后买别的/刷新扣钱，金币只会变少。
        //   若清单里的件到这一轮**一件都买不起**（`pickByQuota` 全跳过），就地丢弃清单、
        //   按**当前金币**重算一次（DP 有预算约束，会改买 1★/印记/2★）。
        //   原来没有这道兜底：清单非空 ⇒ 不走"空清单中毒"那段 ⇒ 直接关店（日志 `1★0 2★0 3★1` + `0/0 件`）。
        val q0 = shopQuota
        if (picked.isEmpty() && q0 != null && q0.total() > 0) {
            val c0 = (sh.opt("coin") as? Number)?.toInt() ?: 0
            AppState.addLog("商店：清单 $q0 一件都买不起（金币=$c0）→ 丢弃清单，按当前金币重算")
            shopQuota = null
            val p2 = shopPlan.shopPlan(
                area, sh, done = shopRefreshCount, relicCtx = relicCtx, quota = null,
            )
            picked = p2.first
            desc = p2.second
        }
        // ★★2026-11-08 排障（用户反馈 0.6.6："3 层商店 1.2 万金币却没买遗物/印记"）：
        //   把"决定买不买"的**输入**全打出来 —— 以后这类"钱够却不买"能一眼定位是
        //   ① 商品没识别出品类（kind=?）② 不在货架 ③ DP 没启用 ④ 清单是空的。
        if (n == 1) {
            val brief = items.joinToString(" ") { it0 ->
                val k0 = shopPlan.shopItemKind(it0.opt("rewardId")?.toString() ?: "").first
                "$k0/${Py.num(it0, "price")?.toInt() ?: -1}" +
                    (if (Py.truthy(it0.opt("isSold"))) "(已售)" else "") +
                    (if ((Py.num(it0, "availability")?.toInt() ?: -1) != 1) "(不在货架)" else "")
            }
            AppState.addLog(
                "商店：进店快照 dp=${shopPlan.dpEnabled(area)} 清单=${if (shopQuota == null) "无(将重算)" else "有"}" +
                    " 金币=${coinRead ?: -1} items=${items.size}" +
                    " slots=${sh.optJSONArray("slots")?.length() ?: -1}" +
                    " 本地done=$shopRefreshCount 游戏resetCount=${sh.opt("resetCount")}" +
                    " 游戏resettable=${sh.opt("resettable")}" +
                    " 剩余刷=${shopPlan.remainingRefresh(area, sh, shopRefreshCount)}" +
                    "｜$brief",
            )
        }
        // ★商店抽样（2026-11-4 用户）：记录"进店 / 每次刷新后"这一屏的星级构成 + 当前持有遗物，
        //   用来验证「1/2/3★ 各 1/3」是否与持有量相关（H_A 按件均匀 vs H_B 按星级均匀）。
        //   详见 [ShopSampleLog]；同一 (货架, 刷新次数) 只记一次。
        runCatching {
            val skey = "$rkey|$shopRefreshCount"
            val c0 = appCtx
            if (c0 != null && ShopSampleLog.shouldRecord(skey)) {
                val shelfArr = JSONArray()
                for (it0 in items) {
                    val rid = it0.opt("rewardId")?.toString() ?: continue
                    val (kind0, info0) = shopPlan.shopItemKind(rid)
                    shelfArr.put(
                        JSONObject().put("id", rid).put("kind", kind0)
                            .put("rarity", info0?.optInt("rarity") ?: 0)
                            .put("sold", it0.optBoolean("isSold", false)),
                    )
                }
                if (shelfArr.length() > 0) {
                    ShopSampleLog.record(
                        c0, skey,
                        JSONObject()
                            .put("ts", System.currentTimeMillis())
                            .put("area", area ?: -1)
                            .put("refresh", shopRefreshCount)
                            .put("coin", coinRead ?: -1)
                            .put("shelf", shelfArr)
                            .put("owned", AgentApi.labOwnRelics() ?: JSONObject.NULL),
                    )
                }
            }
        }
        var freshQuota = false
        if (shopQuota == null && shopPlan.dpEnabled(area)) {
            // 刚跑完 DP → 取走新清单，后续每轮按它买
            val qNew = shopPlan.takeQuota()
            // ★★2026-11-08 **防"空清单中毒"**：空清单 + 货架上有候选 = **自相矛盾**
            //   （DP 只要有一件买得起且品类认得，分数就严格大于空集 ⇒ 必买；离线复现也印证了）。
            //   实测它来自"进店第一帧 `availability` 还没填"，一旦缓存 ⇒ 整家店一件都不买。
            //   ⇒ 这种清单一律**丢弃**（下轮重算），宁可多跑一次 DP，也不让整家店空转。
            if (qNew != null && qNew.isEmpty() && shopPlan.lastCandCount > 0 &&
                shopQuotaRejectN < SHOP_QUOTA_REJECT_MAX
            ) {
                // 丢弃后**这一轮什么都不做**（不买、不刷新、也不关店）—— 否则会顺着空清单走到"关店"。
                shopQuotaRejectN++
                if (shopQuotaRejectN == 1) {
                    AppState.addLog(
                        "[!] 商店：DP 给出空清单、但货架上有 ${shopPlan.lastCandCount} 件可买 → " +
                            "丢弃这张清单，重读后再算（多半是进店那一帧还没就绪）",
                    )
                }
                delay(300)
                return
            }
            if (qNew != null && qNew.isEmpty() && shopPlan.lastCandCount > 0) {
                // 兜底：连续这么多次都"矛盾" ⇒ 不再纠缠（怕卡死在商店），照它执行
                AppState.addLog("[!] 商店：连续 $shopQuotaRejectN 次空清单仍矛盾 → 照它执行，不再重算")
            }
            shopQuotaRejectN = 0
            shopQuota = qNew
            freshQuota = shopQuota != null
            // ★全货架诊断：每件标 已售/不在货架/✓买/✗不买 —— "某件为什么没买" 一眼可判
            shopQuota?.let { AppState.addLog("商店：本次清单 $it${shopPlan.shelfBrief(sh, picked)}") }
        }
        // （"货架为空"的检查已于 2026-11-08 移到跑 DP **之前**，见上面。）
        if (n == 1 || n % 20 == 0) {
            // ★`desc` 与 PC 逐字一致（对拍测试守着），末尾这两段是给**人**看的可读口径
            //   （2026-10-17 用户："买完余=-2620 —— 日志修一下"：原来那个数会算上买不起的件）
            val coin0 = (sh.opt("coin") as? Number)?.toInt() ?: 0
            AppState.addLog(
                "商店：$desc${shopPlan.buyPlanBrief(picked, coin0)}" +
                    (if (shopPlan.dpEnabled(area)) shopPlan.dpBrief(shopQuota) else ""),
            )
        }
        if (!SHOP_AUTO) return
        // ★刚算出清单的这一轮**只记账、不点**（2026-11-02 实机）：进店/刷新的第一帧 UI 还没铺好，
        //   读到的是**上一页残留的槽位坐标** —— 实测点了 (1507,528)（空处）⇒ 确认框不弹 ⇒
        //   购买子链白等 15s；下一轮重读才是正确的 (492,525)。多等一轮（~0.5s）换掉这段空转。
        if (freshQuota) return
        val coin = (sh.opt("coin") as? Number)?.toInt() ?: 0
        // ★★2026-11-21（用户方案）：**第四步·购买顺序从商店后往前**（买哪些/每层遗物序都不动，只改执行顺序）。
        //   已购商品会被游戏归位到货架末尾 ⇒ 从后往前买时，未购件的 page/idx 不变；即便某件因
        //   快照延迟被重复选中，第二次指向的也是"另一件未购商品"而非刚买那件 ⇒ 避开对同一件
        //   连发两次购买请求（redroid 11 那次 205：链断→本地清单没扣→又选了同一个 #7）。
        //   排序键 = 商店坐标 r/page 降序、r 同则 c/idx 降序（page*3+idx = shopSlotOf）。
        val pickedBackToFront = picked.sortedByDescending { it0 ->
            val page = (it0.opt("page") as? Number)?.toInt() ?: 0
            val idx = (it0.opt("idx") as? Number)?.toInt() ?: 0
            page * ShopPlan.SHOP_GROUP_SIZE + idx
        }
        if (pickedBackToFront.size > 1 && n == 1) {
            AppState.addLog("商店：购买顺序按货架从后往前（防重复购）：" +
                pickedBackToFront.joinToString(",") { it0 ->
                    val p = (it0.opt("page") as? Number)?.toInt() ?: 0
                    val c = (it0.opt("idx") as? Number)?.toInt() ?: 0
                    "r${p}c${c}"
                })
        }
        // ① 先买：计划里第一个「未买 && 可买(availability==1) && 钱够」
        val tgt = pickedBackToFront.firstOrNull {
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
            // ★2026-11-20（用户）：4 层 3×3 商店最后一行被 UIPanel 裁掉 ⇒ vis=false，
            //   但购买按钮 active、y 仍在屏内 ⇒ 按 (page,idx) 结构定位的直调 pickup 照样能点
            //   （实测 r1c0 y=604 > 裁剪 bottom=579 直调成功）。所以不可见时**先 probe 直调路径**：
            //   命中 ⇒ 跳过上滑、直接走结构直调（不上滑这条路）；不命中 ⇒ 才退回原上滑兜底。
            val buyPage = (tgt.opt("page") as? Number)?.toInt() ?: (gi / ShopPlan.SHOP_GROUP_SIZE)
            val buyIdx = (tgt.opt("idx") as? Number)?.toInt() ?: (gi % ShopPlan.SHOP_GROUP_SIZE)
            val buyKey = "ShopBuy|Buy${buyPage}_${buyIdx}"
            var directErr = ""
            val directOK = !visOK && run {
                val pr = runCatching { AgentApi.labPathPress(buyKey, probe = true) }.getOrNull()
                if (pr?.optBoolean("ok") == true) true
                else { directErr = pr?.optString("err").orEmpty().ifEmpty { "无回包" }; false }
            }
            if (!visOK && !directOK && n <= 2) {
                AppState.addLog("商店：目标#$gi（$buyKey）直调 probe 未命中（$directErr）→ 退回上滑")
            }
            if (!visOK && !directOK) {
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
                // ★★2026-11-20（用户，C 方案）：**用 SetScrollBarValue(1.0) 滚到底**，不再真实 swipe。
                //   可复现、不依赖手势时长；滚完下一轮重读 slots，第 3 行就 `vis=true`，随后直调 `Buy2_x`。
                //   滚动条调不动（拿不到 controller）才退回真实上滑兜底。
                shopDragCount++
                AppState.addLog("商店：列表滚到底 SetScrollBarValue(1.0)（第 $shopDragCount 次，目标 $tgtTag）")
                val sr = runCatching { AgentApi.labShopScrollTo(1.0f) }.getOrNull()
                if (sr?.optBoolean("ok") != true) {
                    AppState.addLog("商店：SetScrollBarValue 失败（${sr?.optString("err").orEmpty().ifEmpty { "无回包" }}）→ 退回真实上滑")
                    val x = screenW / 2
                    val y1 = screenH * 800 / 1080
                    val y2 = screenH * 300 / 1080
                    input.swipe(x, y1, x, y2, SHOP_DRAG_MS)
                }
                delay(250)                                        // 给列表一帧重建时间
                return
            }
            // 可见 / 直调 probe 命中 → 直接点（目标可点后计数复位）
            //   ★directOK 时 vi 仍在（4 层 slots=items，vi=gi），s 的坐标仅作直调失败兜底用。
            // ★2026-11-20（用户"太快了看不清"）：这里原来每件商品都打一条
            //   「被裁剪但直调 probe 命中 → 不上滑」，看着像"又点了一次"（其实 probe 是只读）。
            //   现在不打了 —— 没出现"上滑商店列表"就说明走的是直调，且下一行 `买#N 确认` 才是真点。
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
            // ★**不在这里扣清单**（2026-11-02）：只记下"刚点了这一件"，下一轮读回货架核对到账才扣。
            //   否则一旦点击没生效（坐标被挡/动画期间/商品被抢），清单已少一件 ⇒ 那件永远不买了。
            shopPendKind = buyKind
            shopPendGi = gi
            shopPendRetry = 0
            // 记下"点击前"的快照：到账确认要用它比（金币 / 同款件数），见 [shopPendKind] 上方说明
            shopPendRid = tgt.opt("rewardId")?.toString() ?: ""
            shopPendPrice = (tgt.opt("price") as? Number)?.toInt() ?: 0
            shopPendCoinBefore = coin
            shopPendCntBefore = items.count { shopBuyable(it) && it.opt("rewardId")?.toString() == shopPendRid }
            // 开启购买子链：从现在起进入"交易锁"，回到商店页且走完确认+到账才计数（见 [SHOP_BUY_TIMEOUT_MS]）
            shopBuyStep = 1
            shopBuyAt = SystemClock.elapsedRealtime()
            // ★2026-11-10（用户方案）：不再"点一下回主循环等" —— 一次调用走完
            //   购买确认框出现 → 点确认 → 框开始消失 → 收到确认（遗物/印记/角色）→ 回商店。
            // ★2026-11-11（用户）：购买链固定三段（购买确认 → 购买完毕 →【仅印记】角色加入），
            //   窗口一定依次出现且形态不同 ⇒ 按框类型推进、**不计数**；这里只告诉链"是不是印记"。
            val isMark = buyKind.endsWith("印记")
            val ok = shopTapThenConfirm(
                input, s.optInt("x"), s.optInt("y"), "买#$vi($tgtTag)",
                listOf("TwoButtonRight", "ThreeButtonCenter", "OneButtonWhite"),
                expectReceipts = true,
                isMark = isMark,
                // ★2026-11-19：走 `LAB_PATHS` 的 `ShopBuy|Buy{page}_{idx}`（按结构定位，不靠坐标/滚动）
                //   ★2026-11-20：复用上面 probe 用的同一个 buyKey（含 4 层 page=2 的 Buy2_x）
                pathKey = buyKey,
                confirmPathKey = "ShopBuyConfirm|TwoButtonRight",   // ★2026-11-20：购买确认框（Middle 两按钮）
            )
            if (ok) {
                // 动画链已走完（确认框→到账→回商店）⇒ **立即结算**，不等下一轮
                shopBuyStep = 3
                settlePurchaseNow("动画链完成")
            }
            return
        }
        // ② 没可买的了 → 刷新或关闭（换店归零已提到 shopPlan 之前，这里不再重复）
        // q1（刷出≥1个1★概率）Kotlin 暂无 shop_pool_stats 探针 → 传 null（=不拦，与 PC q1 未知同款）
        // ★「该不该刷」只看读数（DP 结论 + 游戏剩余次数 + 金币）—— **点击成败不影响这个决策**
        val rd = shopPlan.refreshDecision(area, sh, shopRefreshCount, null)
        if (n == 1 || n % 20 == 0) AppState.addLog("商店：${if (rd.refresh) "决定刷新" else "不刷新：${rd.why}"}")
        if (rd.refresh) {
            val rb = sh.optJSONObject("rerollBtn")
            if (rb != null && hasXY(rb)) {
                // ★2026-11-11（用户）：点「更新」没弹确认框 = **这一下点早了/点空**，不等于"本店不能刷"。
                //   ⇒ 允许重试（每次重读货架拿最新按钮坐标），只有连续失败才判定"不可刷"。
                //   计数只在 [shopRefreshChain] 真的确认（确认框走完 + 货架签名变了）之后才 +1。
                var refreshed = false
                var tx = rb.optInt("x")
                var ty = rb.optInt("y")
                var sig = shopShelfSig(sh)
                var attempt = 0
                while (attempt < SHOP_REFRESH_TRY_MAX && !refreshed) {
                    attempt++
                    refreshed = shopRefreshChain(input, tx, ty, sig)
                    if (!refreshed && attempt < SHOP_REFRESH_TRY_MAX) {
                        AppState.addLog("[!] 商店：更新第 $attempt 次没等到确认框（点早了？）→ 重读按钮坐标再试")
                        delay(120)
                        val sh2 = AgentApi.labShop()
                        val rb2 = sh2?.optJSONObject("rerollBtn")
                        if (sh2 != null && rb2 != null && hasXY(rb2)) {
                            tx = rb2.optInt("x"); ty = rb2.optInt("y")
                            sig = shopShelfSig(sh2)
                        }
                    }
                }
                if (refreshed) {
                    shopRefreshFailN = 0
                    shopRefreshCount++
                    shopRefreshAt = SystemClock.elapsedRealtime()   // ★刷新后空货架宽限（见 [SHOP_REFRESH_SETTLE_MS]）
                    // 刷新后货架全新 ⇒ 待确认件/清单都作废（清单 key 里带 shopRefreshCount，下轮会重算）
                    shopPendKind = null; shopPendGi = -1; shopPendRetry = 0
                    shopBuyStep = 0; shopBuyAt = 0L      // 刷新即换货架 ⇒ 购买子链作废
                } else {
                    // ★点不到 ≠ 不能刷（用户 2026-11-11）：不计次、不关店，下一轮重新读货架再试；
                    //   只有连续很多轮都点不到才怀疑界面异常 → 停手留人工（**不主动关店**）。
                    shopRefreshFailN++
                    if (shopRefreshFailN >= SHOP_REFRESH_FAIL_MAX) {
                        AppState.addLog(
                            "[✗] 商店：更新连续 $shopRefreshFailN 轮点不到" +
                                "（游戏 resettable=${sh.opt("resettable")}、本地 done=$shopRefreshCount）" +
                                " → 停手留人工（不关店）",
                        )
                        stopRequested = true
                    } else {
                        AppState.addLog(
                            "[!] 商店：更新没生效（第 $shopRefreshFailN 轮，resettable=${sh.opt("resettable")}）" +
                                "→ 下一轮重读再试",
                        )
                    }
                }
                return
            }
            if (n == 1) AppState.addLog("[!] 想刷新但没读到「更新」按钮坐标 → 不点（留人工）")
            return
        }
        // ③ 刷不动/不划算 → 关闭退出
        val cb = sh.optJSONObject("closeBtn")
        if (cb != null && hasXY(cb)) {
            // ★2026-11-10（用户方案）：点关闭 → 关闭确认框出现 → 点确认 → 框开始消失 → 离开商店页
            shopCloseChain(input, cb.optInt("x"), cb.optInt("y"))
            return
        }
        if (n == 1) AppState.addLog("[!] 没可买的、也读不到「关闭」按钮坐标 → 停手（留人工）")
    }

    // ────────────── 商店：基于弹窗「出现/消失动画」的链（2026-11-10 用户方案） ──────────────

    /**
     * ★2026-11-10：「买到」**当场结算**（清单扣减 + 成交对账），不等下一轮主循环。
     * 日志实测：动画链 2.3s 走完，但 `✔ 买到` 要再等 ~2s（跨回主循环那一轮）—— 那 2s 就是用户说的"间隔长"。
     */
    private fun settlePurchaseNow(reason: String) {
        if (shopBuyStep < 3) return
        val cost = if (shopBuyAt > 0) SystemClock.elapsedRealtime() - shopBuyAt else 0L
        val kind = shopPendKind ?: "?"
        shopQuota?.consume(kind)
        AppState.addLog(
            "商店：✔ 买到 $kind（#${shopPendGi} ${shopPendRid}）$reason，${cost}ms" +
                (shopQuota?.let { "；清单剩 $it" } ?: ""),
        )
        shopChkKind = kind; shopChkPrice = shopPendPrice; shopChkCoin = shopPendCoinBefore
        shopBuyStep = 0; shopPendKind = null; shopPendGi = -1; shopPendRetry = 0
    }

    /** 读一次「当前弹窗箱体」（~5~8ms）；读不到/没有对话框返回 null。 */
    private suspend fun dlgBoxNow(): JSONObject? =
        runCatching { AgentApi.labDlgBox() }.getOrNull()?.takeIf { it.optBoolean("ok") }

    /** `labDlgBox.buttons[]` → { 槽位名 → (x,y) } */
    private fun boxButtonMap(box: JSONObject?): Map<String, Pair<Int, Int>> {
        val m = HashMap<String, Pair<Int, Int>>()
        val arr = box?.optJSONArray("buttons") ?: return m
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("name")
            if (n.isEmpty()) continue
            val x = (o.opt("x") as? Number)?.toInt() ?: continue
            val y = (o.opt("y") as? Number)?.toInt() ?: continue
            m[n] = x to y
        }
        return m
    }

    /**
     * 按**框的结构**选要点的按钮（不是死顺序）：
     *   · 有 `TwoButtonLeft`/`ThreeButtonLeft` ⇒ 这是「取消/确认」双按钮框 ⇒ 点 Right（确认）；
     *   · 没有左键 ⇒ 单按钮框（收到道具/提示）⇒ 走调用方给的顺序（OneButtonWhite / OneButtonBlue …）。
     * ★2026-11-10 实测：收到确认框上 `TwoButtonRight` 槽位也在（但没显示），死顺序会先点它 ⇒
     *   白点一下（`到账确认 → TwoButtonRight #1` 不生效）。改为先看有没有左键。
     */
    private fun pickBoxBtn(box: JSONObject?, keys: List<String>): Triple<String, Int, Int>? {
        val m = boxButtonMap(box)
        val hasLeft = m.containsKey("TwoButtonLeft") || m.containsKey("ThreeButtonLeft")
        // 单按钮框：**只认 OneButton***（TwoButtonRight 槽位可能还在、但没显示，点它等于白点）
        val order = if (hasLeft) listOf("TwoButtonRight", "ThreeButtonRight", "ThreeButtonCenter")
        else listOf("OneButtonWhite", "OneButtonBlue")
        for (k in order) {
            val xy = m[k] ?: continue
            return Triple(k, xy.first, xy.second)
        }
        for (k in keys) {
            val xy = m[k] ?: continue
            return Triple(k, xy.first, xy.second)
        }
        return null
    }

    /** 等「新框出现」：顶层指针从 [prevPtr] 变走，且 `top` 是 `Dialog*`。 */
    private suspend fun awaitNewBox(prevPtr: String, timeoutMs: Long): JSONObject? {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val b = dlgBoxNow()
            val ptr = b?.optString("ptr").orEmpty()
            val top = b?.optString("top").orEmpty()
            if (ptr.isNotEmpty() && ptr != prevPtr && top.startsWith("Dialog")) return b
            delay(SHOP_ANIM_POLL_MS)
        }
        return null
    }

    /**
     * 点当前框的某个按钮；没点中就**用下一拍的新坐标补点**（多点当手段），
     * 点完只探 [SHOP_ANIM_CLOSE_PROBE_MS] 有没有"开始消失"。
     * @return true = 框已开始消失/换掉
     */
    private suspend fun tapBoxButton(
        input: InputBackend,
        keys: List<String>,
        what: String,
        /** 日志前缀（默认"商店"；"失败页-下一步"那条弹窗传自己的，免得日志里看着像开店） */
        logPrefix: String = "商店",
        /**
         * ★2026-11-19（并入 feat/direct-trigger）：这条框的**固定路径 key**（`LAB_PATHS`）。
         * 给了就先直调（不投坐标），失败才退回"现取按钮坐标 + 点击"。
         * 例：`ShopBuyDone|OneButtonBlue`、`ShopSummonDone|OneButtonWhite`、`SelectConfirm|OneButtonWhite`。
         */
        pathKey: String? = null,
    ): Boolean {
        val base = dlgBoxNow() ?: return false
        val basePtr = base.optString("ptr")
        var saidFallback = false

        // ── 直调（给了 pathKey 才用）：成功返回 true；失败退回"现取坐标 + 点击" ──
        suspend fun pressDirect(): Boolean? {
            val key = pathKey ?: return null
            val pr = runCatching { AgentApi.labPathPress(key) }.getOrNull()
            if (pr?.optBoolean("ok") == true) return true
            if (!saidFallback) {
                saidFallback = true
                AppState.addLog(
                    "$logPrefix：$what 直调失败（$key / " +
                        "${pr?.optString("err").orEmpty().ifEmpty { "无回包" }}）→ 退回坐标点击",
                )
            }
            val b = dlgBoxNow() ?: return null
            if (b.optString("ptr") != basePtr) return null   // 已经换框/关掉 ⇒ 不点
            if (b.optBoolean("closing")) return null
            val hit = pickBoxBtn(b, keys) ?: return null
            return tap(input, hit.second, hit.third, "$logPrefix：$what")
        }

        // ── 坐标（没给 pathKey 时用）──
        suspend fun xyNow(): Pair<Int, Int>? {
            val b = dlgBoxNow() ?: return null
            if (b.optString("ptr") != basePtr) return null   // 已经换框/关掉 ⇒ 不点
            // ★2026-11-13（用户："tween 和 closing 在这里不能用吗"）：商店这几个壳的
            //   `outStarted/outFactor` 实测**恒为 0/1**（tween 不跑或偏移不对）⇒ 改用
            //   `proc`（`DialogBase.isProcess` +0xB4）：点生效后 0→1，比 tween 可靠。
            if (b.optBoolean("closing")) return null
            val hit = pickBoxBtn(b, keys) ?: return null
            return hit.second to hit.third
        }
        // ★2026-11-19（日志精简）：不再自己记「点第几下」—— tap() 已打「点了 商店：X (x,y)」，
        //   原来那行「商店：X → 点「按钮」#N」是同一次点击的第二次记录。
        // ★2026-11-12（用户）：**不再自己实现"首拍只读不点"** —— 统一交给 [TapCheck.run] 的
        //   `skipFirstHits`（与特别装备「全部」行同一套能力）；本函数只提供"点哪个按钮 + 什么算框没了"。
        //   判据：这个框 **开始消失**（`closing`）或换成别的框（ptr 变），都算这一下生效。
        return tapAndCheck(
            input, 0, 0, "$logPrefix：$what",
            from = setOf(basePtr),
            retries = SHOP_ANIM_TAP_MAX,
            readTop = {
                val b = dlgBoxNow()
                when {
                    b == null -> ""                                  // 没有对话框 = 已经关掉
                    // ★2026-11-13 同拍对比实测：`labDlgBox` 与 `labDlgClosing` 读到的完全一致，
                    //   `st=0`（退出 tween 从未启动）、`proc` 恒 1 ⇒ 通用提示框没有可用的"点生效"信号。
                    //   只能靠 **框消失 / ptr 变化**（下面的 reached）判，closing 留着无害。
                    b.optBoolean("closing") -> ""
                    else -> b.optString("ptr")
                }
            },
            unknownTop = "",
            reached = { p -> p.isEmpty() || p != basePtr },
            // ★2026-11-13（用户）：**回到原来的点击频率** —— 这些框没有"点生效"信号，
            //   接受偶尔多点一下（比等 1.2s 值得）。间隔 = settle 120ms + 6×60ms 探测 ≈ 500ms，
            //   最多 [SHOP_ANIM_TAP_MAX] 次；框一消失立刻走，不会真的点满。
            probe = 6,
            // ★probeMs 跟随设置挡位；skipFirstHits 走默认 1（2026-11-17 用户统一）
            // ★★2026-11-19：给了 pathKey 就**直调优先**（不投坐标）；失败退回同一套坐标逻辑。
            pressFn = if (pathKey == null) null else ::pressDirect,
            xyAt = if (pathKey != null) null else ::xyNow,
        )
    }

    /**
     * 点掉「到账确认」框，直到**回到商店页**。
     *
     * ★2026-11-11（用户）：按**买的东西**区分，不看时间 ——
     *   · 遗物：收到遗物 → 直接回商店（[needReceipts] = 1）；
     *   · 印记：收到印记 → 还要确认一次「收到角色」（[needReceipts] = 2）。
     * 点够了 [needReceipts] 个框、且当前在商店页就返回；不够就继续等（最多 4s）。
     */
    private suspend fun drainShopReceipts(input: InputBackend, isMark: Boolean, skipPtr: String): Boolean {
        val keys = listOf("OneButtonWhite", "OneButtonBlue", "TwoButtonRight", "ThreeButtonCenter")
        val t0 = SystemClock.elapsedRealtime()
        var lastPtr = skipPtr          // 购买确认框不算"到账框"
        var sawReceipt = false         // 见过并点掉「购买完毕」
        var sawRole = false            // 见过并点掉「角色加入」
        while (SystemClock.elapsedRealtime() - t0 < SHOP_ANIM_RECEIPT_MS) {
            val b = dlgBoxNow()
            if (b == null) { delay(SHOP_ANIM_POLL_MS); continue }
            val top = b.optString("top")
            val ptr = b.optString("ptr")
            val btns = boxButtonMap(b)
            // ★2026-11-19（日志精简）：不再逐层打「到账链看到顶层 …」（每笔购买 2~3 行，纯内部细节）
            if (top.startsWith("DialogLabyrinthShop")) {
                // ★按"见过哪个框"判定，**不计数**（用户 2026-11-11）：
                //   遗物：购买完毕点掉即可；印记：购买完毕 + 角色加入 都要点掉。
                if (sawReceipt && (!isMark || sawRole)) return true
                delay(SHOP_ANIM_POLL_MS)
                continue
            }
            if (!top.startsWith("Dialog")) { delay(SHOP_ANIM_POLL_MS); continue }
            if (ptr == lastPtr) { delay(SHOP_ANIM_POLL_MS); continue }
            // ★三个框靠**按钮结构**区分（2026-11-11 日志实测）：
            //   购买确认 = TwoButtonLeft + TwoButtonRight（已在 shopTapThenConfirm 点掉，作为 skipPtr 跳过）
            //   购买完毕 = OneButtonBlue
            //   角色加入 = OneButtonWhite
            val isReceipt = btns.containsKey("OneButtonBlue")
            val isRole = btns.containsKey("OneButtonWhite")
            if (!isReceipt && !isRole) { delay(SHOP_ANIM_POLL_MS); continue }
            val step = if (isReceipt) "购买完毕" else "角色加入"
            // ★2026-11-19：这两条框各有一条固定路径（蓝色单按钮 / 白色单按钮），直调优先
            val stepKey = if (isReceipt) "ShopBuyDone|OneButtonBlue" else "ShopSummonDone|OneButtonWhite"
            lastPtr = ptr
            if (tapBoxButton(input, keys, step, pathKey = stepKey)) {
                if (isReceipt) sawReceipt = true else sawRole = true
            }
        }
        return false
    }

    /**
     * ★A 方案（2026-11-11 用户）：买完在**链内**确认"这件真的标成已售"。
     *
     * 关键时序（用户 P5 截图）：购买完毕/角色加入弹窗还盖着时，**下面的商店货架不会变**；
     *   只有所有弹窗关闭、真正回到商店页后才重绘 ⇒ 必须在 `drainShopReceipts` 返回后调用。
     * 只读 `labShopItems`（3~5ms/次），每 120ms 一次，最多 [SHOP_ANIM_SOLD_WAIT_MS]。
     * @return true = 该 rewardId 已 `isSold`（买到且货架已更新）；false = 超时没变（交回主循环核对）
     */
    private suspend fun confirmSold(rid: String): Boolean {
        if (rid.isEmpty()) return true
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < SHOP_ANIM_SOLD_WAIT_MS) {
            val arr = runCatching { AgentApi.labShopItems() }.getOrNull()?.optJSONArray("items")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("rewardId") != rid) continue
                    if (Py.truthy(o.opt("isSold"))) {
                        AppState.addLog("商店：确认已售 $rid（+${SystemClock.elapsedRealtime() - t0}ms）")
                        return true
                    }
                }
            }
            delay(120)
        }
        AppState.addLog("[!] 商店：${SHOP_ANIM_SOLD_WAIT_MS}ms 内没等到 $rid 标成已售 → 交回主循环核对")
        return false
    }

    /**
     * 商店动作的「动画链」：点首下 → 等确认框**出现** → 点确认（点空补点）→ 等它**开始消失**。
     * @param expectReceipts true = 购买（之后有「购买完毕」；印记还多一个「角色加入」）
     * @param isMark        true = 印记（多等一个「角色加入」框）
     */
    private suspend fun shopTapThenConfirm(
        input: InputBackend,
        tx: Int, ty: Int, what: String,
        confirmKeys: List<String>,
        expectReceipts: Boolean,
        isMark: Boolean = false,
        /**
         * ★2026-11-19（并入 feat/direct-trigger）：这一下（买/刷新/关闭）的直调路径 key。
         * 直调成功就不投屏幕坐标（屏外/被裁剪的购买按钮也能点），失败才退回 `input.tap`。
         */
        pathKey: String? = null,
        /**
         * ★2026-11-20：点完「买/更新/关闭」后弹出的**确认框**的直调路径 key。
         * 三种确认框结构不同（购买=Middle 两按钮 / 更新、关闭=Small 两按钮），由调用方各自指定。
         */
        confirmPathKey: String? = null,
    ): Boolean {
        val baseBox = dlgBoxNow()
        val basePtr = baseBox?.optString("ptr").orEmpty()
        val baseTop = baseBox?.optString("top").orEmpty()
        // ★2026-11-10 用户反馈"这次点错了"：上一件的角色确认框还没关时，`labShop` 仍能从底下读到商店，
        //   于是这里直接点了商品 —— 那一下其实打在确认框上。顶层不是商店页就**先不点**（撤销购买意图）。
        if (baseTop.isNotEmpty() && !baseTop.startsWith("DialogLabyrinthShop")) {
            // ★2026-11-14（用户反馈"3 层 BOSS 前商店买完遗物不能自动关闭，要手动点"）：
            //   到账/确认框（`DialogCommon*`）会压在商店上面，它**要人点一下才会关**。
            //   原来这里只"等它关掉"，而主循环的通用弹窗白名单在本页被 ownPage 跳过 ⇒ 两边互相等，
            //   日志刷到 90s+，直到用户手动点。改成：先主动点掉挡路的通用弹窗。
            if (baseTop.startsWith("DialogCommon")) {
                // ★tag 带上 $what：熔断签名是 `tag|按钮名@坐标`，用固定 "shop" 会让
                //   "连续买好几件、每件都弹同一颗按钮"的**成功**点击也累加到 8 次 → 误熔断。
                if (tapDialogConfirm(input, baseTop.removeSuffix("(Clone)"), "shop|$what")) {
                    AppState.addLog("[!] 商店：$what 前顶层「$baseTop」挡路 → 先点掉它再继续")
                    return false
                }
            }
            AppState.addLog("[!] 商店：$what 前顶层「$baseTop」不是商店页 → 先不点（等它关掉）")
            shopBuyStep = 0; shopPendKind = null; shopPendGi = -1; shopPendRetry = 0
            return false
        }
        // ★★2026-11-19（并入 feat/direct-trigger）：**先直调**（`LAB_PATHS` 里的 ShopBuy/ShopReroll/ShopClose），
        //   不投屏幕坐标 ⇒ 屏外/被裁剪区的按钮也点得动（实测下排 y=604 越过裁剪 bottom=579 仍正常）。
        //   直调失败（表外 / agent 不支持 / 投递失败）才退回坐标点击 —— 行为与之前一致。
        var fired = false
        if (pathKey != null) {
            val pr = runCatching { AgentApi.labPathPress(pathKey) }.getOrNull()
            fired = pr?.optBoolean("ok") == true
            if (!fired) {
                AppState.addLog(
                    "商店：$what 直调失败（$pathKey / ${pr?.optString("err").orEmpty().ifEmpty { "无回包" }}）→ 退回坐标点击",
                )
            }
        }
        if (!fired) input.tap(tx, ty)
        val b = awaitNewBox(basePtr, SHOP_ANIM_BOX_WAIT_MS) ?: run {
            AppState.addLog("[!] 商店：$what 后 ${SHOP_ANIM_BOX_WAIT_MS}ms 没等到确认框（可能点空/无确认）")
            return false
        }
        // ★2026-11-19（日志精简）：不再打「确认框出现（…）」—— 正常流程必然出现，
        //   异常（没等到确认框 / 点了 N 次没消失）下面各有告警。
        val confirmPtr = b.optString("ptr")
        // ★2026-11-17（用户）：统一走默认 skipFirstHits=1（1×probeMs=150ms ≥ 原先 2×60=120ms）
        // ★2026-11-20：确认框也直调优先（购买=ShopBuyConfirm / 更新=ShopRerollConfirm / 关闭=ShopCloseConfirm）
        if (!tapBoxButton(input, confirmKeys, "$what 确认", pathKey = confirmPathKey)) {
            AppState.addLog("[!] 商店：$what 确认框点了 ${SHOP_ANIM_TAP_MAX} 次仍没开始消失")
            return false
        }
        // ★把**购买确认框**的指针带过去：它 out tween 期间仍会是最上层，不能算进"到账框"里
        //   （否则遗物 need=1 会把同一个框数两次、真正的「收到角色」又被跳过）。
        if (expectReceipts) {
            // ★到账链按框类型推进（购买完毕 / 角色加入），不计数；
            //   最后仍以 `isSold` 为硬证据，避免"看到商店就认为完成"。
            val drained = drainShopReceipts(input, isMark, confirmPtr)
            val sold = confirmSold(shopPendRid ?: "")
            if (!sold) {
                AppState.addLog("[!] 商店：到账链没走完（框全点=$drained）且货架没标已售 → 交回主循环核对")
                return false
            }
            return true
        }
        return true
    }

    /** 货架签名（刷新前/后对比用）：rewardId|价格|已售 逐件拼接。 */
    private fun shopShelfSig(sh: JSONObject): String {
        val arr = sh.optJSONArray("items") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            sb.append(o.optString("rewardId")).append('|')
                .append((o.opt("price") as? Number)?.toInt() ?: -1).append('|')
                .append(if (Py.truthy(o.opt("isSold"))) 1 else 0).append(';')
        }
        return sb.toString()
    }

    /** 刷新：点更新 → 确认框 → 等货架签名真的变了。 */
    private suspend fun shopRefreshChain(
        input: InputBackend, tx: Int, ty: Int, beforeSig: String,
    ): Boolean {
        // ★2026-11-12（用户实测 5 层商店"连刷两次、跳过一整页"）：**点空/框没探到 ≠ 没刷新**。
        //   以前这里 `!shopTapThenConfirm(...) → return false`，但游戏可能已经刷了（只是确认框的
        //   closing 没探到）⇒ 下一轮又刷一次，中间那页商品直接丢掉。现在只要**货架签名变了**就算成功。
        val tapped = shopTapThenConfirm(
            input, tx, ty, "更新",
            listOf("TwoButtonRight", "ThreeButtonRight", "OneButtonWhite"),
            expectReceipts = false,
            pathKey = "ShopReroll|RerollButton",     // ★2026-11-19：直调优先
            confirmPathKey = "ShopRerollConfirm|TwoButtonRight",   // ★2026-11-20：更新确认框（Small 两按钮）
        )
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < SHOP_ANIM_REFRESH_MS) {
            val sh = AgentApi.labShop()
            if (sh == null) { delay(SHOP_ANIM_POLL_MS); continue }
            val e = sh.optString("err")
            if (e.isNotEmpty() && e != "null") { delay(SHOP_ANIM_POLL_MS); continue }
            val sig = shopShelfSig(sh)
            if (sig.isNotEmpty() && sig != beforeSig) {
                AppState.addLog("商店：刷新确认 → 货架已更新（${SystemClock.elapsedRealtime() - t0}ms）")
                return true
            }
            delay(SHOP_ANIM_POLL_MS)
        }
        AppState.addLog(
            "[!] 商店：${SHOP_ANIM_REFRESH_MS}ms 内货架签名没变" +
                "（点击${if (tapped) "已确认" else "未确认"} → 刷新可能没生效）",
        )
        return false
    }

    /** 关闭：点关闭 → 确认框 → 等离开商店页。 */
    private suspend fun shopCloseChain(input: InputBackend, tx: Int, ty: Int): Boolean {
        if (!shopTapThenConfirm(
                input, tx, ty, "关闭",
                listOf("TwoButtonRight", "OneButtonWhite", "ThreeButtonCenter"),
                expectReceipts = false,
                pathKey = "ShopClose|CloseButton",     // ★2026-11-19：直调优先
                confirmPathKey = "ShopCloseConfirm|TwoButtonRight",   // ★2026-11-20：关闭确认框（Small 两按钮）
            )
        ) return false
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < SHOP_ANIM_CLOSE_BOX_MS) {
            // ★★2026-11-20（用户："关商店到回地图之间为什么 2s"）：**商店一出退场 tween 就算离开**。
            //   原来只认"一个 Dialog 都没有"，而退场期间 dlgBox 一直能看到 Dialog ⇒ 白等满
            //   [SHOP_ANIM_CLOSE_BOX_MS]=2s 上限才返回（实测 44.518→46.700 正好 2.18s）。
            val cl = runCatching { AgentApi.labDlgClosing() }.getOrNull()
            val clTop = cl?.optString("top").orEmpty()
            if (cl != null && cl.optBoolean("closing") && clTop.contains(PAGE_SHOP)) {
                AppState.addLog("商店：关闭确认 → 商店对话框开始收起 → 视为已离开（${SystemClock.elapsedRealtime() - t0}ms）")
                return true
            }
            val b = dlgBoxNow()
            if (b == null) { delay(SHOP_ANIM_POLL_MS); continue }
            val top = b.optString("top")
            if (!top.startsWith("Dialog")) {
                AppState.addLog("商店：关闭确认 → 已离开商店页（${top.ifEmpty { "无顶框" }}）")
                return true
            }
            delay(SHOP_ANIM_POLL_MS)
        }
        return false
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
     *   · 门票     → `pickTicket(cards)`（PC `handle_ticket_choice`：按策略页「属性印记顺序」取首个匹配，
     *                默认仍是 暗>火>光>风>水，2026-11-13 起可在设置里调）。
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
        /** 上一次点击时的卡片签名：用来分辨「Boss 第二轮」（卡片变了）和「上次没点上」（卡片没变） */
        prevSig: String? = null,
    ) {
        val n = bump(tries, tag)
        // ★2026-11-20（用户）：按节点类型明确两轮选人链。
        //   Boss（panelTypeName==BOSS，如 30701）：Selection(第一轮) → Selection(第二轮) → Middle。
        //   普通战/EX：Selection → Middle（一轮）。非选人页（门票）不参与。
        //   `prevSig==null` = 主循环首次进来（第一轮）；非 null = 递归重选（第二轮或没点上重试）。
        //   ★5 层 Boss（50801）分派天然走 DialogLabyrinthResult、不进这里 ⇒ 无需特判。
        val isBossSel = !kind.contains("门票") && st.optString("panelTypeName") == "BOSS"
        if (isBossSel && prevSig == null) bossSelPendingRound2 = true
        if (!isBossSel) bossSelPendingRound2 = false   // 非 Boss 页清掉，防跨节点残留
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
        // ★卡片签名变了 ⇒ 这不是"上次没点上"，而是**新的一轮选人**（Boss 战后有两轮）。
        //   此时清掉"已点过/已点次数"，按新卡片重新决策（2026-11-07 用户要求）。
        if (prevSig != null && sig != prevSig) {
            AppState.addLog("… $kind 页：卡片已刷新（新一轮选人），按新卡片重新决策")
            lastSelSig = null
            lastSelTaps = 0
            // ★2026-11-20：进入 Boss 第二轮 ⇒ 清掉"待第二轮"标志，第二轮按正常 Middle 链走。
            //   （arrived 命中路径已在上面清过；这里是超时递归进来的兜底。）
            bossSelPendingRound2 = false
        }
        // ★Boss 第一轮阶段2到达判据：卡片签名一变（第二轮卡片刷出）就算到达，不等 Middle 超时。
        //   提到变量让 Kotlin 正确推断为 suspend lambda（直接写在 if 分支里会丢 suspend 上下文）。
        val bossArrived: (suspend (String) -> Boolean)? =
            if (bossSelPendingRound2) suspend@{ top: String ->
                top.startsWith("DialogLabyrinthSelection") && selSigNow(tag) != sig
            } else null
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
            // ★三选一的目标（2026-11-14 用户："按已选全部敌人加权"）：本局 3 区/5 区首领的关卡 id。
            //   bossByArea 从刷开局产物带过来（见上面 reroll.bossByArea 那段），所以开局就知道这两个是谁。
            //   ★关卡 id 必须用**模型词表里的**（`labyrinth_boss.json` 的那套编号模型不认），
            //     所以走 LabModel.bossQuestOf（按"该区 + 敌人含这个 Boss"反查）。
            // ★2026-11-14（用户）：目标改成可配置（权重 + 生效区间），在「角色选择 → 推理模型」里调。
            //   条件按当前节点 blockId 判断（3图前 = blockId<30000、3图boss后 = >=30701）。
            val blockId = (st.opt("blockId") as? String)?.toIntOrNull()
                ?: (st.opt("blockId") as? Number)?.toInt() ?: 0
            val targets = ArrayList<Pair<Int, Double>>(6)
            PartyPlan.appCtx?.let { app ->
                for (cfg in RerollSettings.loadPickTargets(app)) {
                    if (cfg.weight <= 0.0) continue
                    if (!RerollSettings.condMatch(cfg.cond, blockId)) continue
                    val q = when (cfg.id) {
                        "boss3" -> bossQuestByArea[3]
                        "boss5" -> bossQuestByArea[5]
                        else -> cfg.id.removePrefix("ex_").toIntOrNull()
                    }
                    if (q != null && q > 0) targets.add(q to cfg.weight)
                }
            }
            pick.pickLabReward(
                cards, ctx, (st.opt("questId") as? Number)?.toInt() ?: 0, targets,
            )
        }
        // ★P4 判不出 / 选中的那张没坐标 ⇒ **不猜**（2026-10-16 用户："盲选太不靠谱，宁可重启游戏"）：
        //   走 [selNoDecide]（等 → 有界自愈重启 → 停手），**不再"退化为点第 1 张"**。
        //   （印记页本身的规则在 P4 `CharaPick.pickTicket`：按策略页设置的属性顺序（默认 暗>火>光>风>水）
        //     取第一张匹配的卡，全不匹配返回 null —— 那是 PC 同款的**规则缺省**，不是这里的瞎猜。）
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
        // ★★2026-11-20（用户）：三张卡是**依次出现**的 —— 只等"目标那张"落位不够：
        //   后面的卡还没进屏时，按 btnX 排出来的顺序会错位 ⇒ 选中/直调下标错 ⇒ 选错卡（UI 错位）。
        //   ⇒ 要求**所有**卡的「决定」坐标都在屏内（等最后一张也进来）才动手。
        val allCardsOnScreen = cards.all {
            val x = it.optInt("decideX")
            val y = it.optInt("decideY")
            x in 0 until scrW && y in 0 until scrH
        }
        // ★★2026-11-20（实测回退 cs112）：屏幕外卡片（decideY=819 > 屏高720）直调**虽能触发**，
        //   但会把卡片卡在滑入动画里、后续页面操作不了 ⇒ 补点 ⇒ 205（session_112948 两次复现）。
        //   ⇒ 无论直调还是坐标点击，屏外一律**先等落位**（本轮 return），不再用 canDirect 跳过。
        //   agent 侧 cs113 也恢复 `_pathBtnNow` 的"不在屏内就拒"——双保险。
        if (!onScreen || !allCardsOnScreen) {
            if (!onScreen && n == 1) {
                AppState.addLog("[!] $kind 页「决定」坐标 ($dx,$dy) 在屏外（大概在滑动动画中）→ 等下一轮重读")
            } else if (!allCardsOnScreen && n <= 3) {
                AppState.addLog("… $kind 页：还有卡片没进屏（三张依次出现）→ 等齐再选")
            }
            // ★诊断（2026-11-14）：判断到底是"页面还没落位"还是"读到的数据一直没变"。
            //   每 15 轮打一次 ptr（卡片对象指针）+ 坐标 + 卡片 id：ptr 不变 ⇒ agent 侧返回了旧对象；
            //   ptr 在变但坐标不变 ⇒ 页面本身如此。
            if (n == 1 || n % 15 == 0) {
                // 打印**每张卡**自己的「决定」坐标 —— 判断是不是"只有目标这张读错"，
                // 还是三张都错（布局问题）。3 个按钮必然同一行，同 y。
                val detail = cards.joinToString(" ｜ ") {
                    val u = (it.opt("unitId") as? Number)?.toInt() ?: 0
                    val x = it.optInt("decideX")
                    val y = it.optInt("decideY")
                    "$u@($x,$y)${if (y in 0 until scrH) "" else "✗"}"
                }
                AppState.addLog("[诊断] $kind 屏外 #$n：屏=${scrW}x${scrH} → $detail")
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
            // ★★2026-11-08 用户："三选一的页面上，多点几次选择是没关系的，不太怕点的快了"
            //   ⇒ 去掉原来"首点前死等 [SEL_TAP_LEAD_MS]=500ms"的做法（那是为了**一次点准**），
            //     改成 **tapAndCheck 模型**：点一下 → 探到离开就停；没离开就**用最新坐标**再点。
            //   · 为什么原来会点空：按钮从屏外滑入，三帧读到 (405,1187)→(944)→(887)，
            //     而旧代码重试时用的是**第一次算好的坐标** ⇒ 第二下还点在 944（按钮已经到 887）。
            //     ⇒ 现在每一下都走 [selDecideXY] **现取坐标**（屏外就跳过这一下，不计数）。
            //   · 多点安全：这页反复点**同一张卡**只会重复"选中它"，不会选错（三张卡各自独立）。
            var left = false      // 点完是否已离开选人页（快速重试用，两个点击分支都要用）
            // ★本轮**真取到坐标 / 没取到**的次数 —— 用来区分"点了没生效"和"一次都没点"（见下）
            var xyOk = 0
            var xyNull = 0
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
                left = tapAndCheck(
                    input, pb.optInt("x"), pb.optInt("y"), "$kind：选 $cid（树·选择）",
                    // ★2026-11-17（用户）：点前现取坐标——和首次同一读法（findSelButtons → 按 dx 最近），
                    //   按钮从屏外滑入时每拍坐标会变，取不到/屏外则这一拍不点。
                    xyAt = {
                        val arr = findSelButtons(12)
                        var best: JSONObject? = null
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val o = arr.opt(i) as? JSONObject ?: continue
                                val xx = (o.opt("x") as? Number)?.toInt() ?: continue
                                val yy = (o.opt("y") as? Number)?.toInt() ?: continue
                                if (xx !in 0 until scrW || yy !in 0 until scrH) continue
                                if (best == null || kotlin.math.abs(xx - dx) < kotlin.math.abs(best!!.optInt("x") - dx)) {
                                    best = o
                                }
                            }
                        }
                        best?.let { it.optInt("x") to it.optInt("y") }
                    },
                    from = setOf("DialogLabyrinthSelection"),
                    expect = selExpect(kind),
                    // ★2026-11-20（用户）：Boss 两轮选人的第一轮之后仍是 Selection（不是 Middle），
                    //   原来靠 6s 阶段2超时才递归 ⇒ 空等 6s。实测 Middle 25ms 就到、第二轮卡片 ~1.5s 刷新，
                    //   缩到 2s：正常情况不受影响，第二轮省 4s。
                    expectTimeoutMs = SEL_NEXT_PAGE_TIMEOUT_MS,
                    arrived = bossArrived,   // ★Boss 第一轮：卡片签名一变就立即到达，不等超时
                    // ★不猜设备速度（见下面 xyAt 分支的说明）
                    // ★probe=2：实测三选一页没有退出 tween 可读（tw 全程 0/0），真正切换在 ~1s 后
                    //   ⇒ 多探只是把"判没点到"往后推，没有收益；探 2 次就重试，多点无害。
                    retries = 8, probe = 2,
                    probeState = { selProbe("DialogLabyrinthSelection") },
                )
            } else {
                left = tapAndCheck(
                    input, dx, dy, "$kind：选 $cid",
                    from = setOf("DialogLabyrinthSelection"),
                    expect = selExpect(kind),
                    expectTimeoutMs = SEL_NEXT_PAGE_TIMEOUT_MS,   // ★2026-11-20：Boss 两轮选人省空等
                    arrived = bossArrived,   // ★Boss 第一轮：卡片签名一变就立即到达，不等超时
                    // ★★**不猜设备速度**（用户 2026-11-08："动画时间可能和设备有关，不能完全确定，
                    //   按快的写慢设备点两次都点不到；按慢的写快设备又要白等"）：
                    //   密集点 + 点完**立刻探**，页面一变就停 ⇒
                    //     · 快设备：第 2~3 下就中，不白等；
                    //     · 慢设备：多点几下也能中，不会点不到；
                    //     · `retries` 大**不会**让快设备多点（探到就 break），只是给慢设备留余量。
                    //   ★**点击间隔 = 200 + 150 = 350ms**（一秒最多 3 下）。用户 2026-11-08：
                    //     "350~500ms 这个范围挺好，200ms 一秒五次就太快了" ⇒ 别再往密里调。
                    //   ★probe=2（+350/500ms）：实测三选一页**没有**退出 tween（tw 全程 0/0），
                    //     真正切换在 ~1s 后（选卡动画）⇒ 多探几次只是把"判没点到"推迟 1s，没有收益
                    //     （"时间太长的 probe 就失去即时性价值"）⇒ 探 2 次就重试，多点无害。
                    retries = 8, probe = 2,
                    // ★每一下**现取坐标**（按钮还在滑入 ⇒ 旧坐标会点空；屏外则这一下不点）
                    // ★★兜底（2026-11-08 事故）：重读失败就退回**决策时那个坐标**（外层刚判过它在屏内）。
                    //   宁可点一下"稍微旧一点的坐标"（这页多点同一张卡没关系），也不要
                    //   "取不到坐标 ⇒ 一次都不点 ⇒ 空转"。
                    // ★★2026-11-19（并入 feat/direct-trigger）：三选一/门票卡走 `LAB_PATHS` 固定路径
                    //   （`SelectRole|Role0/1/2`、`SelectTicket|Ticket0/1/2`），**不投坐标**。
                    //   列表 i(0-based) ↔ Role0/1/2 ↔ 读取的 decideX 已核实三方对齐；
                    //   ⚠`choiceIndex` 是 **1-based**（1/2/3），绝不能拿来拼路径（会整体错一位选错人）。
                    pressFn = press@{
                        val cidx = res.index
                        val pkey = (if (kind == "门票") "SelectTicket|Ticket" else "SelectRole|Role") + cidx
                        val pr = runCatching { AgentApi.labPathPress(pkey) }.getOrNull()
                        if (pr?.optBoolean("ok") == true) {
                            xyOk++   // ★直调成功也算"点出去过"（lastSelTaps/viaTree 兜底依赖它）
                            return@press true
                        }
                        // ★★2026-11-20（用户）：直调失败 = **时机/条件不对的信号**（最常见「不在屏内」
                        //   = 按钮还在滑入动画），不是回退坐标的理由。坐标点击才会点穿/点错——
                        //   在错误时机退回坐标硬点是意外来源。⇒ 这一拍返回 null（不消耗 retries），
                        //   等下一拍落位后重试直调；连续 6 拍都不行则 tapAndCheck 收工、外层重进重决策。
                        xyNull++
                        // ★2026-11-20（用户）：只在头 3 次失败时提示，避免同一页刷屏（曾一次刷 7 行）
                        if (xyNull <= 3) {
                            AppState.addLog(
                                "$kind：路径直调失败（$pkey / ${pr?.optString("err").orEmpty().ifEmpty { "无回包" }}）" +
                                    "→ 等下一拍重试直调（不退回坐标硬点）",
                            )
                        }
                        return@press null
                    },
                    probeState = { selProbe("DialogLabyrinthSelection") },
                )
            }
            // ★★印记节点三选一专用：选完卡之后会**连弹两次**（收到印记 → 收到角色），
            //   就地用 tapAndCheck 两段点掉，不让主循环再按固定 900ms 慢慢摸（2026-11-09）。
            //   遗物三选一走 handleRelicChoice、角色三选一 kind≠"门票" ⇒ 都不进这里。
            if (left && kind == "门票") {
                closeTicketRewardChain(input)
            }
            // ★★2026-11-20：Boss 两轮选人的**第一轮**——`arrived`（卡片签名变化）命中即 left=true，
            //   但页面还是 Selection（第二轮卡片），**不能**当成 Middle 去关。清标志、按新卡递归重选第二轮。
            //   （非 Boss / 第二轮时 bossSelPendingRound2=false，走下面正常 Middle 关闭。）
            if (left && bossSelPendingRound2) {
                bossSelPendingRound2 = false
                lastSelSig = sig
                if (xyOk > 0) lastSelTaps++
                lastSelTapAt = SystemClock.elapsedRealtime()
                AppState.addLog("$kind：Boss 第一轮已选（卡片刷新）→ 立即进入第二轮重选")
                delay(120)
                return handleLabSelection(input, st, tag, pick, tries, kind, prevSig = sig)
            }
            // ★★角色三选一（战后选人）：选完卡之后的**单弹** Middle → 地图（2026-11-09）。
            //   只发生在战斗节点；普通战斗战后不给遗物，直接从角色三选一开始。
            // ★**不串链**：只在 left=true 时触发 —— left 来自上面 tapAndCheck 的阶段 2，
            //   战后选人的 expect = selExpect(kind) = DialogCommonMiddle ⇒ 已经**等到** Middle 出现。
            if (left && kind != "门票") {
                // ★★2026-11-20（用户）：**事件节点的战斗**（魔物竞技场等）收尾要多一层 ——
                //   选完角色后不是直接回地图，而是还有【获得道具-金币】弹窗
                //   （事件效果 = 进入战斗 + 获得 xx 金币）。
                //   判断依据：当前节点类型 = EVENT(5)，与面板 mapInfo.BlockType 同源（`labStatusLite.panelType`）。
                val isEventBattle = (st.opt("panelType") as? Number)?.toInt() == 5
                if (isEventBattle) {
                    closeEventBattleRewardChain(input)
                } else {
                    closeMiddleRewardDialog(input, "战后选人：关闭收到角色(Middle)")
                }
            }
            // ★★"一次都没点出去"要说出来（2026-11-08 事故：取不到坐标 ⇒ 静默空转 20 秒，
            //   日志里只有一屏幕 `⏸ 3 选 1`，完全看不出为什么）。节流：本轮第一次 + 每 10 次。
            if (xyOk == 0 && xyNull > 0) {
                val kNoXY = "$tag|noxy"
                val c = (tries[kNoXY] ?: 0) + 1
                tries[kNoXY] = c
                if (c == 1 || c % 10 == 0) {
                    AppState.addLog(
                        "[!] $kind 页一次都没点出去：取不到「决定」坐标（${selXYWhy.ifBlank { "?" }}）" +
                            "（第 $c 轮；本页决策=${if (cid == 0) "?" else cid}）",
                    )
                }
            } else if (xyOk > 0) {
                tries.remove("$tag|noxy")
            }
            // ★★2026-11-08 修死循环：`lastSelSig` 现在**不管点没点都记**（= "这一组卡已经处理过一轮"），
            //   而 `lastSelTaps` **只在真点出去过时才 +1**（= "真点了几次"）。
            //   原来两个都无条件更新 + 递归前把 sig 清成 null ⇒ 递归进来走"卡片签名变了"分支
            //   ⇒ `lastSelTaps` 每轮被清零 ⇒ 永远到不了上限 ⇒ 既不退树兜底也不停手
            //   （实测空转 20 秒，日志只有一屏幕 `⏸ 3 选 1`）。
            lastSelSig = sig
            if (xyOk > 0) lastSelTaps++
            lastSelTapAt = SystemClock.elapsedRealtime()
            // ★★快速重试（2026-11-07 用户）：点了没离开就**立刻重读卡片 + 重新决策 + 再点**，
            //   不等主循环下一轮（那要 1s+）。卡片滑入动画/位置刷新时这样能秒级自我修正。
            if (!left && lastSelTaps < SEL_RETRY_MAX) {
                // ★只有"点过但没生效"才清 sig（允许立刻重读重决策）；
                //   "一次都没点出去"则保留 sig ⇒ 递归进去会走节流分支（等 [LAB_SELECT_RETAP_MS]
                //   再试），既不会无限快转，也不会把计数清零。
                if (xyOk > 0) lastSelSig = null
                kotlinx.coroutines.delay(120)
                // ★带上本次的卡片签名：下一轮读出来若不同 ⇒ 是新一轮（Boss 两轮），不是没点上
                return handleLabSelection(input, st, tag, pick, tries, kind, prevSig = sig)
            }
        }
    }

    // ★（2026-11-08 删）`SEL_TAP_LEAD_MS` / `REL_TAP_LEAD_MS` = 500ms 的"首点前死等"。
    //   用户："三选一的页面上，多点几次选择是没关系的，不太怕点的快了" ⇒ 改成
    //   **点一下 → 探到离开就停 → 没走就现取坐标再点**（见 [tapAndCheck] 的 `xyAt`/`probe`）。
    //   ★原来那 500ms 治的是"点空"（按钮还在从屏外滑入），而"点空"的真因是**重试时用了旧坐标**。

    /** ★「选择」按钮（选人/门票/遗物页都在 DialogArea 里）：**先限根 `dialog`**（几十 ms ✓）、
     *  空了再全树兜底（2026-10-17：全树一次实测 ~3s ✗，这些页每轮都要找它 ⇒ 单通道常被占住 ⇒
     *  便宜调用（viewId/gameTick）被挤超时）。 */
    private suspend fun findSelButtons(max: Int): org.json.JSONArray? {
        // ★2026-11-12（用户："明确树结构就不要 find 了"）：选人/门票面板就是 DialogArea 里的
        //   `DialogLabyrinthSelection` ⇒ 只读 `dialog` 子树，不再整树兜底。
        return AgentApi.findTextList("选择", max, null, "dialog")
    }

    /**
     * ★★点「决定」前**现取坐标**（2026-11-08 用户："三选一的页面上，多点几次选择是没关系的，
     *   不太怕点的快了"）。
     *
     * 为什么不能"一次算好坐标就反复点"：按钮带**从屏外滑入的入场动画**，实测同一张卡三帧读到
     * ```
     * 12:41:54 (405,1187)  ← 屏外
     * 12:41:56 (405,944)   ← 点了，空
     * 12:41:57 (405,887)   ← 点了，中
     * ```
     * ⇒ 第一下拿旧坐标**必然点空**（这才是"总要点两下"的真正原因，不是需要等 500ms）。
     *
     * @param reread **重读卡片的那个接口** —— 必须是**决策时用的同一个**（见下）
     * @param rid 目标卡的 `rewardId` —— 用来锁定**同一张卡**
     * @param idx 目标卡下标（`rewardId` 对不上时兜底）
     * @return 屏内坐标；`null` = **这一下不该点**（还没落位 / 卡片已换）
     *
     * ★★★2026-11-08 事故（用户："选人一直没点下去"）：这个函数原来**写死**用 `labChoices` 重读。
     *   但选人页（`SUMMON_UNIT` + `DialogLabyrinthSelection`）的卡片是 `labSelection` 读出来的，
     *   **`labChoices` 在那页永远返回空**（实测 `{"cards":[],"why":"结构列表=n=3 找到DecideButton=0"}`）
     *   ⇒ 取不到坐标 ⇒ `tapAndCheck` **一次都不点** ⇒ 空转 20 秒，日志里只有一屏幕 `⏸ 3 选 1`。
     *   ⇒ 现在把"怎么重读"作为参数传进来，由调用方按页面类型给（与决策同源）。
     */
    private suspend fun selDecideXY(
        reread: suspend () -> org.json.JSONArray?,
        rid: Any?,
        idx: Int,
    ): Pair<Int, Int>? {
        val arr = runCatching { reread() }.getOrNull()
        if (arr == null || arr.length() == 0) {
            selXYWhy = "重读拿不到卡片（${if (arr == null) "接口报错/超时" else "空列表"}）"
            return null
        }
        var hit: JSONObject? = null
        if (rid != null && rid !== JSONObject.NULL && rid.toString().isNotEmpty()) {
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                if (c.opt("rewardId")?.toString() == rid.toString()) { hit = c; break }
            }
        }
        if (hit == null) {
            // ★★rewardId 对不上 ⇒ **退回下标**（2026-11-08 实测事故：这里原来直接 return null，
            //   于是"取不到坐标 ⇒ tapAndCheck 一次都不点 ⇒ 外层递归又把计数清零 ⇒ 无限循环 20 秒"）。
            //   ★只在张数够的情况下退，避免"3 张变 2 张"时错位点到别人。
            if (idx in 0 until arr.length()) {
                hit = arr.optJSONObject(idx)
                if (hit != null) selXYWhy = "rid=$rid 对不上，退回 idx=$idx"
            } else {
                selXYWhy = "rid=$rid 对不上且张数不足（${arr.length()} 张，idx=$idx）"
            }
        }
        if (hit == null) { if (selXYWhy.isEmpty()) selXYWhy = "取不到目标那张卡"; return null }
        if (!hasDecideXY(hit)) { selXYWhy = "目标那张没有 decideX/Y"; return null }
        val x = hit.optInt("decideX")
        val y = hit.optInt("decideY")
        // ★坐标在屏外（按钮还在从屏外滑入）⇒ 这一下不点（等落位）
        if (x !in 0 until scrW || y !in 0 until scrH) {
            selXYWhy = "坐标在屏外（$x,$y / 屏幕 ${scrW}x$scrH）"
            return null
        }
        selXYWhy = ""
        return x to y
    }

    /** 上一次 [selDecideXY] 返回 null 的原因（诊断日志用；成功时清空） */
    private var selXYWhy: String = ""

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
     * ★凹分检测（2026-11-01 用户需求）：5 层商店购物完、准备前往 5 层 Boss 时查一次分数，不够就撤退。
     *
     * 为什么这时能算：5 层结算里固定的部分是 15200（踏破 5600 + 普通 600 + 极难 6000 + 首领 3000），
     * 之后只随「角色加入分 100/个」「迷宫遗物分 200/个」变化；商店是最后能改变这两样的地方，
     * 过了它分数就定了（用户核对过 51 角色 + 25 遗物 = 25300 基础分）。
     *
     * 撤退动作统一交给 [MazeRetreat]（接口刷交给服务层、局内刷就地撤、撤不干净就重启游戏清现场），
     * 这里只负责"算分 + 决定要不要撤"，不关心怎么撤 —— 以后加新的凹分条件也照这个写法。
     *
     * 收尾语义：置 [retreatRequested] ⇒ 本局判失败/作废（不算完成、不消耗计划次数），
     * 服务层接着开下一轮「刷开局 → 跑迷宫」。
     *
     * @return true = 已触发撤退，调用方别再往 Boss 节点点
     */
    private suspend fun scoreChaseCheck(input: InputBackend): Boolean {
        if (scoreChaseChecked) return false
        val ctx = appCtx ?: return false
        // ★开关判断放在置位之前：凹分关着时**连"本局查过了"的痕迹都不留**，
        //   整条链路一点状态都不改（用户 2026-11-02："可以确定关闭时所有规则都不生效吧"）
        if (!RerollSettings.loadScoreChaseOn(ctx)) return false
        if (!RerollSettings.loadScoreChaseRetreatOn(ctx)) return false
        scoreChaseChecked = true

        val units = AgentApi.labUnits()
        val ownRelics = AgentApi.labOwnRelics()
        val unitCount = units?.optJSONArray("unitIds")?.length() ?: -1
        val relicCount = ownRelics?.optJSONArray("relics")?.length() ?: -1
        if (unitCount < 0 || relicCount < 0) {
            // 读不到就不拦（宁可打完，也别因为一次读失败白扔一局）
            AppState.addLog("[!] 凹分检测：读不到数量（角色=$unitCount 遗物=$relicCount）→ 本次不拦，继续打 Boss")
            return false
        }
        val base = RerollSettings.scoreBaseOf(unitCount, relicCount)
        val line = RerollSettings.loadScoreChaseBase(ctx)
        AppState.addLog(
            "凹分检测：角色 $unitCount×${RerollSettings.SCORE_PER_UNIT}" +
                " + 遗物 $relicCount×${RerollSettings.SCORE_PER_RELIC}" +
                " + 固定 ${RerollSettings.LAB_FIXED_BASE_SCORE} = 基础分 $base（阈值 $line）",
        )
        if (base >= line) {
            AppState.addLog("凹分检测：$base ≥ $line → 分数够，继续打 5 层 Boss")
            return false
        }

        AppState.addLog("凹分检测：$base < $line → 分数不够")
        MazeRetreat.request(input, ctx, MazeRetreat.Reason.SCORE_LOW, "基础分 $base < 阈值 $line")
        return true
    }

    /** 上一次因为"地图被弹窗盖着"而没点节点的顶框名（同值只报一次，别刷屏） */
    private var moveBlockedTop: String = ""

    /**
     * PC 版 `maze_loop` 的**"移动"分支**（6373~6458）：`statusType==0` 时点地图上的目标节点前进。
     *
     *   ① 优先按**预计算路线**走：取路线里"当前节点的下一站"（这就是"局内按路线前进"）；
     *   ② 不可点/没路线 → [Route.pickNode] 兜底（按类型优先级），并标 `（偏离路线）`；
     *   ③ **换区闸门**（[moveBusyNow]）：`inAnimation == true`（换区过渡动画在跑）⇒ 本轮不投递，
     *      动画结束（`inAnimation` 读不到/为 false）再点 —— 换区期间点必被吞；
     *   ④ 目标在屏外 → **横向拖动地图**把它拉进视野（最多 [DRAG_LIMIT] 次；时长越长=抬手越慢≈削弱惯性）；
     *   ⑤ 点完（含确认）后用 [awaitLeaveNode] 等"真的离开当前节点"（expect），
     *      避免主循环看到 statusType 仍是 0 就重复决策、重复点同一目标。
     *
     * @return true = 本轮点了或拖了（有效动作）；false = 什么也没找到（调用方据此加 `idle`）
     */
    private suspend fun handleMove(input: InputBackend, st: JSONObject, screenW: Int, screenH: Int): Boolean {
        // ★★2026-11-08 修（实测 12:43:01）：**遗物选中之后** `statusType` 会先变成 NONE(0)，
        //   而"收到遗物的确认框"还没盖上来 ⇒ 这一轮就走到这里去点**地图节点**，
        //   而顶框其实还是遗物框（`DialogLabyrinthSelection`）⇒ 那一下打在框上
        //   （运气好点空，运气不好点点到别的卡/别的按钮）。
        //   ⇒ 顶框不为空 = 地图被弹窗盖着 ⇒ **这一轮不点也不拖**，等弹窗被处理掉。
        val topNow = jStr(st, "pageTop")
        if (topNow.isNotEmpty()) {
            if (moveBlockedTop != topNow) {
                moveBlockedTop = topNow
                AppState.addLog("… 地图上还盖着「$topNow」→ 先不点节点（等弹窗处理完）")
            }
            return true
        }
        moveBlockedTop = ""
        logMapSignals(st)                                  // ★诊断：回地图后每 250ms 打一行信号
        // ★★2026-11-20（用户）：**换区滑场 / 角色移动动画在跑 ⇒ 现在点节点只会被吞**（还会排多个确认框）。
        //   直接读 `ViewLabyrinthMap` 的协程句柄（`labMapBusy`，几 ms）⇒ 动画期间这一轮不点。
        // ★★2026-11-22（用户）：**先读节点** —— 只要地图上已经有"可前往且已点亮"的节点，就说明能走了，
        //   不必死等 `inAnimation`（换区滑场动画 ~4s，实测动画没完节点就已就绪）。
        //   这复用了事件结束判定用的"可前进"信号（panelState==4 + anim=selectable_idle）。
        val nodesArr = AgentApi.labNodes()
        // ★别用 optJSONObject(Int)：各 org.json 实现里这个重载不一样（Android 运行时 / 单测的 json-java
        //   甚至会被解析成 "optJSONObject(String)"）→ 统一走 opt(i) as? JSONObject
        val nodes = if (nodesArr == null) emptyList()
        else (0 until nodesArr.length()).mapNotNull { nodesArr.opt(it) as? JSONObject }
        // ★★2026-11-22（用户）：**没有"可前往且已点亮"的节点 + 换区动画在跑 ⇒ 才等**。
        //   有节点就直接往下走（动画没完也能直调，直调不吃坐标）。
        if (nodes.none { Route.nodeClickable(it) } && moveBusyNow()) {
            val nowB = SystemClock.elapsedRealtime()
            if (nowB - moveBusySaidAt > 3_000L) {
                moveBusySaidAt = nowB
                AppState.addLog("… 换区动画在跑（$lastBusyTag）且暂无可用节点 → 等")
            }
            delay(150)
            return true
        }
        val nxt = intListOf(st, "next")
        val sel = nodes.filter {
            // ★2026-11-22：不只看 panelState==4（拓扑），还要"已点亮"（anim=selectable_idle）——
            //   事件/选择/弹窗期间节点是暗的、点不动，此时不该抢跑去点。
            //   ★**不再要求屏幕坐标**：直调 `onClickPanel` 不需要坐标（用户 2026-11-22）。
            Route.nodeClickable(it) &&
                (nxt.isEmpty() || (it.opt("blockId") as? Number)?.toInt() in nxt)
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
                // ★凹分检测（2026-11-01）：站在 5 层商店、下一步就要去 5 层 Boss ——
                //   此时角色数/遗物数已定，分数能提前算；不够就撤退，别往 50801 点下去。
                if (cur == NODE_SHOP_5F && want == NODE_BOSS_5F && scoreChaseCheck(input)) return true
                // ★优先有坐标的（可拖进视野），没有也接受——直调不需要坐标
                t = nodes.firstOrNull {
                    (it.opt("blockId") as? Number)?.toInt() == want &&
                        Route.nodeClickable(it) && (it.opt("x") as? Number) != null
                } ?: nodes.firstOrNull {
                    (it.opt("blockId") as? Number)?.toInt() == want && Route.nodeClickable(it)
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
            val nowE = SystemClock.elapsedRealtime()
            val curB = (st.opt("blockId") as? Number)?.toInt() ?: -1
            val now = System.currentTimeMillis()
            // ★2026-11-20（清理）：这里原来有「3 下 burst + 2s 冷却」「150→450ms 退避」「投递台账 5s」，
            //   全部删除 —— 都是拿**时间**去猜"要不要再点"。
            //   现在判据全在下面两处**状态**里：
            //     · [moveBusyNow]：`inAnimation == true`（换区动画在跑）⇒ 本轮不投；
            //     · [awaitLeaveNode]：点完等 `blockId/statusType` 变化（expect），没变就是被吞。
            //   而且 `awaitMoveConfirm`+`awaitLeaveNode` 是**同步阻塞**的，主循环不会在中间插入决策，
            //   重复投递的间隔天然由它们保证（约 3.8s），不需要额外时间窗。
            val bt = (t.opt("blockType") as? Number)?.toInt()
            val what = "前进到节点$nk[${Route.LAB_BLOCK_TYPE[bt] ?: Py.str(bt)}]$rt"
            OverlayStatus.setState("前往节点$nk")      // ★悬浮窗（细于 overlayTick 的默认描述）
            // ★★2026-11-20（用户方案）：**两级直调、各投一次、两级 expect 都精确指定**，全程无时间窗：
            //   ① 直调节点 `onClickPanel`  ⇒ expect = **「是否前往」确认框**（DialogCommonSmall）
            //   ② 直调【确认】            ⇒ expect = **该节点类型的首个页面**
            //        普通/困难/Boss → DialogQuestDetail（战斗预览）
            //        门票/遗物      → DialogLabyrinthSelection
            //        事件           → DialogLabyrinthEvent
            //        商店           → DialogLabyrinthShop
            //   两级各自 `pressMax=1`（固定只投一次），靠状态推进，主流程不会在中间介入重投同一个节点。
            val targetPages: Set<String> = when (bt) {
                2, 3, 8 -> setOf("DialogQuestDetail")            // NORMAL / HARD / BOSS → 战斗预览
                4, 6 -> setOf("DialogLabyrinthSelection")        // TICKET / RELIC → 选择页
                5 -> setOf("DialogLabyrinthEvent")               // EVENT → 事件页
                7 -> setOf("DialogLabyrinthShop")                // SHOP → 商店页
                else -> emptySet()
            }
            AppState.addLog(
                "[移动] 投递 目标=$nk（出发=$curB，期望页面=" +
                    (if (targetPages.isEmpty()) "任意" else targetPages.joinToString("/")) + "）",
            )
            nodeSaidAt[nk] = now                                         // 同一节点 30s 内只"重点"一次（PC 6425）
            val nkx = t.optInt("x")
            val nky = t.optInt("y")
            // ① 点节点：直调优先（失败才退坐标），**expect = 确认框**
            val gotConfirm = tapAndCheck(
                input, nkx, nky, what,
                from = setOf(""),
                expect = setOf("DialogCommonSmall"),
                expectTimeoutMs = 2_500L,
                retries = 3, probe = 2,
                pressFn = { AgentApi.pressPanel(nk)?.optBoolean("ok") == true },
                pressMax = 1,
                // 兜底：个别节点不弹确认框、点了直接进页面/战斗（此时阶段1 也算到达）
                arrived = suspend@{ tp: String ->
                    targetPages.any { tp.contains(it) } ||
                        runCatching { AgentApi.labInBattle() }.getOrDefault(false)
                },
            )
            // ② 确认框出现 ⇒ 直调【确认】，**expect = 该节点的首个页面**
            if (gotConfirm) {
                val topNow = runCatching { AgentApi.labTop() }.getOrNull().orEmpty()
                if (topNow.startsWith("DialogCommonSmall")) {
                    tapAndCheck(
                        input, nkx, nky, "确认前往$nk",
                        from = setOf("DialogCommonSmall"),
                        expect = targetPages,
                        expectTimeoutMs = 4_000L,
                        retries = 2, probe = 3,
                        pressFn = {
                            AgentApi.labPathPress("MoveConfirm|TwoButtonRight")?.optBoolean("ok") == true
                        },
                        pressMax = 1,
                        // 兜底：确认后直接进战斗（没有预览页）
                        arrived = suspend@{ _: String ->
                            runCatching { AgentApi.labInBattle() }.getOrDefault(false)
                        },
                    )
                }
            }
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
            // ★2026-11-22：不再要求"屏内"（直调不吃坐标）——只有真的没有"可前往且已点亮"的节点才会走到这
            "→ 未找到可前往节点（可前往+已点亮）；可前往=$nxt；全部节点=" +
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
            // ★questId 是**按需读**的（分派读里没有）⇒ 只有 BOSS 节点才读一次
            val q = extraIds()?.optString("questId").orEmpty()
            val a = (st.opt("area") as? Number)?.toInt()
            if (q.isNotBlank() && a != null) PartyPlan.bossOf(q)?.let { bossByArea[a] = it }
        }
        val top = jStr(st, "pageTop")
        // ★Boss 链断言（2026-11-01 实装第一刀）：Boss 节点上"页面与链期望对不上"就先**本轮不派发**，
        //   宁可不点也不点错（已知事故都是两页叠在一起时点错坐标）。连挂 3s 自动放行，不会卡死。
        //   非 Boss 节点 / 链未激活时 [BossChain.guard] 恒返回 true ⇒ 行为与以前完全一致。
        if (!BossChain.guard(top)) return
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
                        "**丢缓存重解析**（清 ${r?.opt("cleared") ?: "?"} 项）后重读 ｜LTD候选=${ltdChoicesBrief()}",
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
        // ★2026-11-13（用户）：三选一遗物采样 ——{节点编号, 三个候选星级}；事件触发的单独标记
        runCatching {
            // 事件触发的三选一：①紧跟在事件选择后（8s 内）；②或者**本场战斗就是事件战斗**
            //   （魔物竞技场选 HARD 后打完的遗物奖励——战斗耗时可能远超 8s，只靠时间窗会漏判）
            val fromEvent = eventBattleSampled || SystemClock.elapsedRealtime() - lastEventAt < 8_000L
            SampleLog.relic(appCtx, jStr(st, "blockId"), cards.map { relicPick.rarityOf(it) }, fromEvent)
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
        // ★★2026-11-08：**不再首点前死等 500ms**（同选人页 —— 用户："三选一多点几次没关系，不太怕点快"）。
        //   原来那 500ms 是在赌"再等一会儿按钮就落位"；现在上面已经判过"坐标在屏内"才走到这儿，
        //   而且每轮都是 `force 重读`拿到的最新坐标 ⇒ 直接点，点不中还有 [REL_TAP_MAX] 次机会。
        // ★★2026-11-08 三次改（实测 15:41:47）：**判据不能用顶框名**。
        //   遗物选中后 `statusType` 立刻变（RELIC_CHOICE(9) → SUMMON_UNIT(1)/NONE(0)），
        //   但**顶框名 3.9 秒都不变**（实测 15:41:47→15:41:52 才出现 DialogCommonBig）⇒
        //   只认顶框名 ⇒ 探 580ms 也判不出"已离开" ⇒ 白点第二下。
        //   ⇒ 判据改成 `statusType|pageTop` 组合签名（同一个思路见 [Navigator.locSig]）：
        //     任一变化就算"这一下生效了"。
        val fromSig = "${st.optInt("statusType", -1)}|${jStr(st, "pageTop")}"
        val tapped = tapAndCheck(
            input, dx, dy, "选择遗物：${c.opt("rewardId")}${if (n > 1) "（第 $n 次）" else ""}",
            from = setOf(fromSig),
            // 探针读**状态**（内存读 4ms）：statusType 一变就立刻确认，不必等顶框名
            readTop = {
                val l = runCatching { AgentApi.labStatusLite(timeoutMs = 1_200L) }.getOrNull()
                if (l == null) "!read" else "${l.optInt("statusType", -1)}|${l.optString("pageTop")}"
            },
            unknownTop = "!read",          // 读不到 ⇒ 当成"还没离开"（绝不误判成生效）
            // 不传 expect：离开遗物框就算完成（后面是确认框还是地图，交给状态机）
            // ★不猜设备速度（同选人页）：密集点 + 点完立刻探，页面一变就停
            retries = 8, probe = 2,
            // ★★2026-11-19（并入 feat/direct-trigger）：走 `SelectRelic|Relic{k}`（k = 0-based），不投坐标
            pressFn = press@{
                val pkey = "SelectRelic|Relic" + k
                val pr = runCatching { AgentApi.labPathPress(pkey) }.getOrNull()
                if (pr?.optBoolean("ok") == true) return@press true
                AppState.addLog(
                    "选择遗物：路径直调失败（$pkey / ${pr?.optString("err").orEmpty().ifEmpty { "无回包" }}）→ 退回坐标点击",
                )
                // ★退回原逻辑：每一下都重读**这张卡**的坐标（按钮在滑入 ⇒ 旧坐标会点空）；
                //   重读失败就退回本轮算好的坐标（外层刚判过它在屏内）
                val xy = selDecideXY(
                    reread = { AgentApi.labChoices(force = true)?.optJSONArray("cards") },
                    rid = c.opt("rewardId"),
                    idx = k,
                ) ?: if (dx in 0 until scrW && dy in 0 until scrH) dx to dy else null
                if (xy == null) return@press null
                tap(input, xy.first, xy.second, "选择遗物：${c.opt("rewardId")}")
            },
        )
        if (tapped) {
            relicChoiceTries[tag] = sig to n
            // ★悬浮窗：遗物三选一选中了谁（`2★ xxx`；查不到表就显示 rewardId）
            OverlayStatus.setState(
                "遗物：" + relicPick.relicLabel(c.opt("rewardId")).ifBlank { c.opt("rewardId").toString() },
            )
            AppState.addLog(
                "[遗物] 选 ${c.opt("rewardId")}（${cards.size} 张里认表 $known 张）：候选=[${cards.map { it.opt("rewardId") }}] " +
                    "理由=${relicPick.relicPickNote(c, ctx)} ｜LTD候选=${ltdChoicesBrief()}",
            )
            // ★★选完卡**就地**把「收到遗物」单弹点掉（轻读 + tapAndCheck，2026-11-09）。
            //   落点按节点类型分叉：遗物节点(RELIC)→地图；战斗节点(NORMAL/HARD/BOSS/EX…)→角色三选一页，
            //   后者必须等到 DialogLabyrinthSelection 出现才算走完，再由角色三选一流程继续推进。
            //   panelTypeName 读不到/是 NONE ⇒ 不预判落点，离开 Big 即完成（保守降级）。
            val panel = st.optString("panelTypeName", "")
            closeRelicRewardDialog(
                input,
                fromBattleNode = panel.isNotEmpty() && panel != "RELIC" && panel != "NONE",
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
     *   · **特别装备三连页**由 `handleExequipPacked` 驱动（编队就绪后**当场**把三连页走完，
     *     不进弹窗白名单，免得误点按钮坏队）；编队就绪后自动点开「特别装备」，走完才点「战斗开始」。
     *   · 多分队走 [PartyPlan.partyFillOk]：**5 层 Boss 默认编满 3 队**；3 层/其它层只编第 1 队
     *     （本节点失败过才补后备队）；**不该编的那一队空队放行**（点「决定」走过去，游戏允许不编满就挑战）。
     */
    /** [partyEditRound] 的返回状态 */
    private const val PARTY_STEP_DONE = 0      // 收工：等主循环/用户
    private const val PARTY_STEP_AGAIN = 1     // 还有下一步：马上在同一次调用里继续
    private const val PARTY_STEP_EXITED = 2    // 页面已经离开编组页（开打/退出）

    /**
     * ★2026-11-20：反馈式「分段加人」的滚动位置序列。
     * 虚拟池是滑窗（一屏 8 格、滑走换内容），不存在一个 v 能装下全部角色 ⇒ 按这几个位置跳，
     * 每段把池内缺员直调加掉。
     *
     * ★★2026-11-22（50801 Boss 卡死实测重定，用户口径）：旧 3 段 (0.35/0.85/0.60) **漏掉顶部区**——
     *   普通编组页池 ≤24 格全量可见时没事，Boss 页 scrollDataMax=56（7 行）、池上限 24 ⇒ 内容随位置换，
     *   107101 恰好在第一行，三段永远够不着 ⇒ 无限滚动"找不到角色"。
     *   实测：视窗 3 行；用户定的 0.21 —— 0.25 在 7 行时刚好能看到第一行、8 行时就不行了，
     *   0.21 留出头量（行数变多也够得着第一行）。序列 0.21/0.85/0.53：
     *   0.21 看第一~三行（含第一行），0.85 看尾部，0.53 补中间。加上池子**累积**特性
     *   （滚过的格子暂不回收、池上限 24），第一段的命中会在后续段保留。
     */
    private val PARTY_SCROLL_STEPS = floatArrayOf(0.21f, 0.85f, 0.53f)

    /**
     * 编组页主入口（2026-11-07 重做）：**一次调用内把整个流程跑完**。
     *
     * ★为什么要这样：原来每做完一步就 `return`，要等**主循环转一圈**（0.5~3 秒）才回来接着做。
     *   普通战无所谓，Boss 页就很难受 —— 三支队伍要"编队1 → 点决定切队2 → 编队2 → 点决定切队3 →
     *   编队3 → 点决定开打"，每一步之间都夹一次主循环空转（用户 2026-11-07："怎么这种情况下
     *   也要延迟点击队伍2、3呢？"、"完全可以检测到队伍2后直接算2队、编组2队，再点队伍3…"）。
     *   现在改成**循环**：[partyEditRound] 一圈做一件事，做完还有下一步就再来一圈（同一调用内）。
     *
     * ★注意两个"必须交回主循环"的地方：
     *   · **打开「特别装备」** —— 三连页由 [handleExequipPacked] **当场**跑完（2026-11-08 改法：
     *     不再"交回主循环"，主循环也就不会在 EX 页上读 labStatus / 命中弹窗白名单）；
     *   · **点「战斗开始」/「决定」之后** —— 页面要换，等主循环去看新页面。
     */
    private suspend fun handlePartyEdit(input: InputBackend, st: JSONObject, tag: String) {
        var round = 0
        while (round < 16) {
            round++
            val step = partyEditRound(input, st, tag)
            if (step != PARTY_STEP_AGAIN) return
        }
        AppState.addLog("[!] 编组页连续处理 $round 圈仍未收手 → 交回主循环，下一轮再看")
    }

    /** [partyEditRound] 的返回状态 */
    /**
     * 编组页的**一圈**：读页 → 算队伍 → （编/切队/开打）。返回 [PARTY_STEP_*]。
     */
    private suspend fun partyEditRound(input: InputBackend, st: JSONObject, tag: String): Int {
        // ★策略=等待接手：本节点失败 3 次后由用户自己组队 → 这里什么都不做（也不点「决定」）
        val nk0 = nodeKey(st)
        // ★2026-11-15（用户）：**离开这一关（area/blockId 变）⇒ 滑动方向/减速状态清零**，
        //   新节点从头算，不会把上一节点"来回滑 → 减速"带到下一关。
        //   注：tag 里含状态/顶框，页面内弹窗也会让它变；这里用节点钥匙，只在真的换节点时清。
        if (nk0 != partyScrollNodeKey) {
            partyScrollNodeKey = nk0
            partyScroll.clear()
        }
        if (nk0 in manualTeamNodes) {
            if (manualSaid.add(nk0)) {
                AppState.addLog("[手动] 节点 ${jStr(st, "blockId")}：等待你组队/开战（本节点自动组队已关闭）")
                OverlayStatus.setState("等待接手：请组队并开战", attention = true)
            }
            return PARTY_STEP_DONE
        }
        // ★★2026-11-07：改用**精简读** `labPartyLite`（只取决策要的字段：队伍/槽位 id/一览 id+vis/按钮坐标…）。
        //   整页 `labParty` 每次要带 29 个 icon 的中文 label + 40 个页签候选 + 一堆诊断字段回来，决策用不上。
        val _tRead = SystemClock.elapsedRealtime()
        var pt = AgentApi.labPartyLite()
        val _readMs = SystemClock.elapsedRealtime() - _tRead
        if (_readMs >= 80) AppState.addLog("… 读编组页 ${_readMs}ms（精简读）")
        val _tParty = SystemClock.elapsedRealtime()      // ★"进页 → 算出队伍"的总耗时（见收尾打点）
        if (pt == null) {
            AppState.addLog("[!] 编队页读不到（超时/没连上）→ 本轮不点")
            return PARTY_STEP_DONE
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
            return PARTY_STEP_AGAIN
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
        //   所以这一页**先等"页面真的换成新内容了"再动手**。
        //
        // ★★2026-11-07 重做（用户："从进入组队页面开始，到算出需要什么队伍，中间可能有些地方慢"）：
        //   原来分**两轮**：第 1 轮 `awaitPartyIconsStable()` 之后直接 `return`，第 2 轮才继续
        //   ⇒ 白等**一整个主循环**（外加多读一次编组页）。
        //   现在**同一轮内**轮询到"一览/槽位的内容稳住了"就继续动手；
        //   轮询用**极轻**的 `labPartyIcons`（~25ms，只读一览/槽位的 id 列表，不投影坐标）。
        // ★换节点 ⇒ 闸门重置（原来在这里把 `partyPageReady=false`，必须保留）。
        if (partyPageKey != tag) {
            partyPageKey = tag
            partyPageAt = SystemClock.elapsedRealtime()
            // ★记下"进场那一刻的样子"，用来判页面换没换（换了才需要重读整页）
            partyEntrySig = partyListSig(pt)
            partyPageReady = false
        }
        if (!partyPageReady) {
            // ★同轮内继续等（最多 [PARTY_ENTRY_WAIT_MS]），等到了就**直接往下**，不再 return。
            //   判据 = **一览/槽位的内容稳住了**（连续两次读相同 ⇒ 动画停）；
            //   ★顺带记下"内容换没换"（相对进场那一刻）—— 换了才需要重读整页。
            val w0 = SystemClock.elapsedRealtime()
            var waited = false
            var changed = false
            var prevSig: List<Int>? = null
            while (SystemClock.elapsedRealtime() - w0 < PARTY_ENTRY_WAIT_MS) {
                kotlinx.coroutines.delay(70)
                val raw = runCatching { AgentApi.labPartyIcons(timeoutMs = 800L) }.getOrNull() ?: continue
                if (raw.top != "DialogCommonPartyEdit") continue
                // ★签名 = 一览(listIds) + 槽位(ids) —— 与 [partyListSig] 同一口径
                //   （agent 侧两个读法取的是同一个列表，顺序一致）。
                val sig = raw.listIds + raw.ids
                if (sig.isEmpty()) continue
                if (sig != prevSig) {
                    prevSig = sig
                    changed = (sig != partyEntrySig)
                    continue                    // 还在动 ⇒ 再读一次
                }
                waited = true                    // 连续两次相同 ⇒ 稳了
                break
            }
            val waitMs = SystemClock.elapsedRealtime() - w0
            if (!waited) {
                AppState.addLog("… 编组页还没铺好（等 ${waitMs}ms）→ 本轮不动手，下轮再看")
                return PARTY_STEP_AGAIN
            }
            partyPageReady = true
            AppState.addLog("… 编组页就绪（等了 ${waitMs}ms${if (changed) "，内容已换" else ""}）")
            // ★内容换了 ⇒ 手上的 `pt` 是**进场时的旧快照** ⇒ 必须重读一次再往下走。
            if (changed) {
                val _tRe = SystemClock.elapsedRealtime()
                pt = AgentApi.labPartyLite() ?: pt
                AppState.addLog("… 重读编组页 ${SystemClock.elapsedRealtime() - _tRe}ms")
            }
        }
        // ★不按 nowMember 是否为空判断"是否进页"：空正好代表还没选人、正该开始填（PC 版同款，没有这条守卫）。
        // ★遗物是局内增长的，缓存不能"每局只建一次"（2026-11-5 用户发现组队记录里永远只有初始 2 件）：
        //   第一次进编组页时通常只有 1~2 件，之后整局复用 ⇒ 记录里的 relics / relic_skill 全错。
        //   现在按**当前遗物件数**作废缓存（件数变了就重读一次，代价 ~100ms，编组页本来就在等 settle）。
        val _tRel = SystemClock.elapsedRealtime()
        val ownNow = AgentApi.labOwnRelics()
        val relicNow = ownNow?.optJSONArray("relics")?.length() ?: -1
        val _relMs = SystemClock.elapsedRealtime() - _tRel
        val _tCtx = SystemClock.elapsedRealtime()
        if (partyCtxCache == null || (relicNow >= 0 && partyCtxCache!!.relics.size != relicNow)) {
            partyCtxCache = PartyPlan.partyCtx(ownNow)
        }
        val _ctxMs = SystemClock.elapsedRealtime() - _tCtx
        val ctx = partyCtxCache!!
        // ★等后台那次"提前算队伍"（2026-11-07）：它与「点挑战→过场→读页面」并行跑的，
        //   到这通常早就算完了 ⇒ `partyPlan` 直接命中缓存，**不再重算一遍**（省 ~1 秒）。
        //   等不到也没关系：下面照常实算，只是白跑了一次后台。
        runCatching { preTeamJob?.await() }
        preTeamJob = null
        // ★把"本节点失败过"传给规划：个别关卡档会据此补 1 个坦克/兼职坦克（`tank_on_fail`，用户 2026-10-16）
        val _tPlan = SystemClock.elapsedRealtime()
        // ★★按需补 ids：组队判定（`PartyPlan.stageFor`）要看 **questId** 才知道"这是不是 Boss 格"
        //   （分派读里没有 questId ⇒ 这里补读一次，~0ms）。
        fillIds(st)
        // ★2026-11-19（用户）：把**失败次数**传下去 —— 第 2/3 次尝试会换成排名第 2/3 的备选队
        val _nf = nodeFailed[nodeKey(st)] ?: 0
        val plan = PartyPlan.partyPlan(st, pt, ctx, failed = _nf > 0, attempt = _nf)
        val _planMs = SystemClock.elapsedRealtime() - _tPlan
        // ★★"进编组页 → 算出目标队伍"的分段耗时（2026-11-07 用户："这段中间可能有些地方慢"）。
        //   这几段**加起来**才是用户体感的"进页到出决策"的等待；算法本身只占 `_planMs`。
        // ★2026-11-19（日志精简）：正常（<600ms）不再每次进页都打一条，只有明显慢时才留，
        //   慢的时候仍能看到是哪一段拖的。
        val _totalMs = SystemClock.elapsedRealtime() - _tParty
        if (_totalMs >= 600) {
            AppState.addLog(
                "… 进页→出决策偏慢：共 ${_totalMs}ms " +
                    "（读页 ${_readMs}ms｜遗物 ${_relMs}ms｜持有池 ${_ctxMs}ms｜算队伍 ${_planMs}ms）",
            )
        }
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
                        MazeRetreat.request(input, appCtx, MazeRetreat.Reason.NO_PLAN, "连续 $n 轮算不出目标")
                        return PARTY_STEP_DONE
                    }
                    RerollSettings.FAIL_WAIT -> {
                        AppState.addLog("[!] 编组页连续 $n 轮算不出目标 → 等待接手（本节点转人工）")
                        manualTeamNodes.add(nk)
                        // ★2026-11-18（用户报的 bug）：这里原来只加节点、**没设悬浮提示** ⇒
                        //   而主循环的「等待接手」全局闸门（manualTeamNodes 非空就 continue）会让
                        //   `partyEditRound` 开头那段带红提示的代码**永远执行不到** ⇒ 用户只看到
                        //   "不动了"、没有任何提示。所以必须在**添加节点的这一刻**就把红提示打上。
                        OverlayStatus.setState("等待接手：算不出队伍，请手动组队/开战", attention = true)
                        return PARTY_STEP_DONE
                    }
                    else -> {
                        AppState.addLog("[✗] 编组页连续 $n 轮算不出目标 → 停手")
                        stopReason = "编组算不出可用队伍"
                        stopRequested = true
                        return PARTY_STEP_DONE
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
            // ★编组页顶部"队伍1/2/3"页签 —— **只在 Boss 页读一次**（2026-11-07）：
            //   以前每轮都从整页快照里带 40 个候选回来 + 打一行 ⇒ 现在单独调 `labPartyTabs`。
            //   ⚠普通战**只有队伍1**、根本不需要页签 ⇒ 一律不读（Boss 页才要切队腾空）。
            //   ★2026-11-07：改成**直接命中**（读 `selectPlate+0x28` 的页签数组），不再遍历整棵对话框子树。
            if (isBossPage(st) && partyTabsSaid.add(tag)) {
                AgentApi.labPartyTabs()?.let { tb ->
                    val hit = tb.optString("hit", "?")
                    val tc = tb.optJSONArray("tabCand")
                    if (tc != null && tc.length() > 0) {
                        AppState.addLog("编组页页签（$hit）= $tc")
                    } else {
                        AppState.addLog("[!] 编组页页签读不到（$hit）：${tb.optString("err", "")}")
                    }
                }
            }
        // ── ★"眼前这一队还编不编"（PC `party_fill_ok`(2579) 同款；用户 2026-10-16 定的分层策略）──
        //   5 层 Boss 默认编满 3 队；3 层/其它层按初始策略只编第 1 队（失败过才补后备队）。
        //   ★不编的那一队 ⇒ **放行**：空队也点「决定」走过去（游戏允许"有未编组的队伍也能挑战"），
        //     否则会卡在第 2 队等一个永远等不到的"编好"。
        //   ★分层名单在 [PartyPlan.LAB_BOSS_FILL_ALL_AREAS]；与 PC `LAB_BOSS_FILL_ALL_AREAS` 同步。
        val isBoss = isBossPage(st)
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
            val nspNow = (pt.opt("nowSelectParty") as? Number)?.toInt() ?: 0
            val others = (1..2).filter { PartyPlan.partyDicN(dic, it) > 0 }   // 队伍2=idx1、队伍3=idx2
            // ★页签坐标**只在真的要切队时才读**（2026-11-07）：
            //   2/3 队本来就是空的（大多数情况）⇒ 压根不需要页签 ⇒ 别白读一次。
            //   （以前是无条件先读再判断 ⇒ 每个 Boss 节点都多付一次 labPartyTabs 的钱。）
            if (others.isNotEmpty() || nspNow > 0) {
                // ★页签坐标单独取（2026-11-07：以前每轮整页快照都带 40 个候选回来）
                val tabs = AgentApi.labPartyTabs()?.optJSONObject("partyTabs")
                if (tabs != null) {
                    for (i in others) {
                        val tc = tabs.optJSONObject("${i + 1}") ?: continue
                        if (tc.opt("x") == null) continue
                        AppState.addLog(
                            "编队：Boss 页「队伍${i + 1}」是继承来的、还有 ${PartyPlan.partyDicN(dic, i)} 人 → " +
                                "切过去腾空（免得占着角色）",
                        )
                        // ★2026-11-20：切队页签直调（PartyEdit|Party{N}，LAB_PATHS 现成）
                        pathPressOrTap(input, "PartyEdit|Party${i + 1}", tc.optInt("x"), tc.optInt("y"), "切到队伍${i + 1}")
                        delay(PartyPlan.LAB_PARTY_SWITCH_MS)
                        clearPartyTeam(input, i + 1)
                    }
                    val t1 = tabs.optJSONObject("1")
                    if (t1 != null && t1.opt("x") != null) {
                        pathPressOrTap(input, "PartyEdit|Party1", t1.optInt("x"), t1.optInt("y"), "切回队伍1")
                        delay(PartyPlan.LAB_PARTY_SWITCH_MS)
                    }
                    AppState.addLog("编队：腾空完成 → 回队伍1 按算法重算")
                    return PARTY_STEP_AGAIN
                }
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
            // ★撤人批完成后**复用**这张快照（加入批要坐标 + 要核对撤人是否生效）⇒ 少读一次编组页
            var snapAfterRemove: JSONObject? = null
            if (removes.isNotEmpty()) {
                val snap = partySnap()
                if (snap == null) {
                    AppState.addLog("[!] 撤人：读不到编队页快照 → 本轮不撤，下轮再试")
                    delay(PartyPlan.LAB_PARTY_BATCH_SETTLE)
                    return PARTY_STEP_AGAIN
                }
                val nowIds = partyNowIds(snap)
                // ★顺序**照计划来**（PartyPlan 已按槽位下标降序 = 从左往右排好）。
                //   原来这里又按 x 升序重排了一遍：一旦坐标取自"角色一览"（x 是网格位置、与站位无关），
                //   就会把顺序打乱 ⇒ 先撤了右边、左边的人被补位右移，后面的坐标全偏（2026-10-18 回归）。
                // ★★2026-11-07 用户："看好下面 5 个格子都是谁，谁不需要就把它在格子里的坐标算出来，
                //   **按 x 从小到大**依次点过去，**过程中不用再读任何东西**"。
                //   ⇒ 一次快照算好全部坐标 → 按 x 升序（= 从左往右）依次点。
                //   为什么这样坐标不会失效：编组页队伍**右对齐**，撤掉**最左边**那个人时，
                //   右边的人**不动**（空位出现在左边）⇒ 一批坐标可以一直用到最后一个。
                //   （"从左往右"正是这个前提的由来；反着撤就会越撤越偏。）
                // ★★2026-11-19（并入 feat/direct-trigger）：**撤人先走直调**
                //   `PartsPartyEditPlate.onClickUnitIcon(UnitIcon)` —— 按 unitId 定位、不投坐标，
                //   所以不存在"撤掉左边的人、右边补位把坐标挪偏"的问题。
                //   没生效 / 整条链路不可用（agent 版本老、钩子装不上）才退回下面按坐标 tap。
                val rmWant = removes.map { it.unit }.filter { it in nowIds }.distinct()
                val rmDone = partyDirectPress("remove", rmWant)
                val rmLeft = if (rmDone == null) rmWant else rmWant.filter { it !in rmDone }
                if (rmDone != null && rmDone.isNotEmpty()) {
                    AppState.addLog("编队：撤人直调 ${rmDone.joinToString(",")}（Plate.onClickUnitIcon）")
                }
                val todo = removes.filter { it.unit in rmLeft }
                    .mapNotNull { op ->
                        val c = findUnitCoord(snap, op.unit, "槽位")
                        if (c != null) op to c else null
                    }
                    .sortedBy { it.second.first }          // ★x 从小到大 = 从左往右
                if (todo.isEmpty()) {
                    if (rmDone != null && rmDone.isNotEmpty()) {
                        // 直调已经撤掉了 ⇒ 只需等队伍名单更新（快照留给加入批复用）
                        snapAfterRemove = awaitPartyRoster(nowIds, wantSnap = true).snap
                    } else {
                        AppState.addLog("[!] 撤人：快照里找不到要撤的人（${removes.size} 个目标）→ 下轮再试")
                    }
                } else {
                    AppState.addLog(
                        "编队：撤人 ${todo.size} 个（坐标一次算好，按从左往右依次点，中途不读）",
                    )
                    // ★2026-11-17 诊断（用户要求）：编队前把快照里的坐标来源打进日志，
                    //   定位"点歪 / 点完顶框突变 / 误开战"——icons=下排槽位，scroll=角色一览，btn=按钮坐标。
                    runCatching {
                        fun dumpArr(a: org.json.JSONArray?): String {
                            if (a == null) return "-"
                            val sb = StringBuilder()
                            for (k in 0 until a.length()) {
                                val o = a.opt(k) as? JSONObject ?: continue
                                sb.append("${o.optInt("unitId")}@${o.opt("x")},${o.opt("y")}")
                                if (o.has("vis")) sb.append("/v${o.opt("vis")}")
                                sb.append(' ')
                            }
                            return sb.toString().trim()
                        }
                        AppState.addLog(
                            "  [dbg] scr=${scrW}x${scrH}｜icons=[${dumpArr(snap.optJSONArray("icons"))}]" +
                                "｜scroll=[${dumpArr(snap.optJSONArray("scrollIcons"))}]" +
                                "｜btn=${snap.optJSONObject("buttons")?.toString() ?: "null"}",
                        )
                    }
                    for ((op, c) in todo) {
                        tap(input, c.first, c.second, "编队：remove ${op.unit}")
                        // ★2026-11-07：不再死等 350ms ⇒ **等这个人真的出队**就立刻点下一个。
                        // ★★2026-11-09 修正：`awaitUnitInTeam` 内部就会**轮询到 350ms 上限**，
                        //   超时后再 `delay(350)` ⇒ 最坏 700ms/人（原注释"最坏不比原来差"是错的）。
                        //   实测"换人时每两个顿一下"就是它 ⇒ 现在只补到**最小间隔 350ms**。
                        val t0 = SystemClock.elapsedRealtime()
                        if (!awaitUnitInTeam(op.unit, want = false, label = "rm")) {
                            val spent = SystemClock.elapsedRealtime() - t0
                            AppState.addLog("编队：等 remove ${op.unit} 出队超时 ${spent}ms（只补到最小间隔）")
                            if (spent < PartyPlan.LAB_PARTY_TAP_MS) delay(PartyPlan.LAB_PARTY_TAP_MS - spent)
                        }
                    }
                    // ★2026-11-07：不再固定等 1 秒 ⇒ 轮询队伍名单，撤掉了就立刻走。
                    //   顺便把"撤完的快照"留给加入批复用（省一次读）。
                    snapAfterRemove = awaitPartyRoster(nowIds, wantSnap = true).snap
                }
            }
            // ── ①b 加入：**也一次性点完**（用户 2026-10-16：加人不改变"选择区"里其他卡的位置 ——
            //   选择区是按角色排的静态网格、与队伍无关；PC 注释实测"空队与满队两次坐标一致"）
            //   ★注意：**不是**"填左侧空位"——新人站队伍里哪儿由它的**攻击范围**决定（游戏自己重排），
            //   所以我们只点"选择区"里的卡，队伍那侧的位置与我们无关 ✓────────────────────────────────
            //   ★找不到坐标的（一览里滚出可见区）**不当错误**：留给下一轮的滚动分支去滚出来。
            val adds = ops.filter { it.action == "add" }
            // ★2026-11-20：屏外缺员（在一览全量里、但不在可见区）也要加入 —— 直调按 unitId 定位、
            //   不吃坐标，所以不再"等 swipe 滚进屏才加"。和可见 add 一起作为直调候选。
            val offAddUids = plan.offscreen.map { it.unit }
            if (adds.isEmpty() && offAddUids.isEmpty()) {
                return PARTY_STEP_AGAIN      // ★撤人批已经在上面等过队伍更新了 ⇒ 不用再白等 1 秒
            }
            val snap2 = snapAfterRemove ?: partySnap()
            if (snap2 == null) {
                AppState.addLog("[!] 加入：读不到编队页快照 → 本轮不加，下轮再试")
                delay(PartyPlan.LAB_PARTY_BATCH_SETTLE)
                return PARTY_STEP_AGAIN
            }
            val nowIds2 = partyNowIds(snap2)
            // ★顺手用这张快照核对**撤人**生效没有（省掉单独一次读）：还留在队里就别再加人
            val stuckRm = removes.map { it.unit }.filter { it in nowIds2 }
            if (stuckRm.isNotEmpty()) {
                AppState.addLog(
                    "[!] 撤人核对：${stuckRm.joinToString(",")} 还在队里（点歪了？）→ 本轮不加人，下轮按实际队伍重算",
                )
                delay(PartyPlan.LAB_PARTY_BATCH_SETTLE)
                return PARTY_STEP_AGAIN
            }
            // ★★2026-11-20（用户确认）：**反馈式分段直调** —— 整套方案，不是"把 tap 换成直调"。
            //   虚拟池是滑窗（一屏 8 个、滑走换内容），不存在一个滚动位置能装下全部角色 ⇒
            //   按 PARTY_SCROLL_STEPS（8 段均匀 0→1，见字段注释）逐段跳：每段读当前池子 uid，把池内还缺的
            //   按 unitId 直调加掉，remaining 递减；某段没命中就跳下一段。每步读池子校正 ⇒ 偏差只会多跳、不漏。
            //   顺序：撤人(①a 已完成，队伍区常驻可见) → 分段跳+直调加人 → 剩余可见项坐标兜底。
            val remaining = (adds.map { it.unit } + offAddUids)
                .filter { it !in nowIds2 }.distinct().toMutableSet()
            val addDoneAll = mutableSetOf<Int>()
            var snapForTap = snap2
            var finalBar: Float? = null
            for (v in PARTY_SCROLL_STEPS) {
                if (remaining.isEmpty()) break
                val sr = runCatching { AgentApi.labPartyScrollTo(v) }.getOrNull()
                if (sr?.optBoolean("ok") != true) {
                    AppState.addLog("编队：分段跳 $v 不可用（${sr?.optString("err") ?: "无返回"}）→ 跳下一段")
                    continue
                }
                sr.opt("bar")?.let { finalBar = (it as? Number)?.toFloat() }
                // 读当前池子 uid（滚动后一览内容变了）
                val slots = runCatching { AgentApi.labPartySlots() }.getOrNull()
                val poolUids = slots?.optJSONArray("pool")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optInt("unitId") }
                }.orEmpty()
                val hit = remaining.intersect(poolUids.toHashSet()).toList()
                if (hit.isEmpty()) {
                    AppState.addLog("编队：分段跳 $v（池 ${poolUids.size}）无缺员命中 → 跳下一段")
                    continue
                }
                val done = partyDirectPress("add", hit) ?: emptySet()
                addDoneAll += done
                remaining -= done
                AppState.addLog(
                    "编队：分段跳 $v（池 ${poolUids.size}）命中 ${hit.size} → 直调 ${done.size} 个" +
                        "（${done.joinToString(",")}），剩 ${remaining.size}",
                )
            }
            // 分段滚动改了滚动条 ⇒ 坐标兜底基于最新快照（若期间滚过）。
            if (finalBar != null && remaining.isNotEmpty()) {
                snapForTap = partySnap() ?: snap2
            }
            val addDone = addDoneAll
            // 直调没生效的：可见的按坐标 tap 兜底（用最新快照）；屏外的留在 offscreen 交下一轮。
            val addTodo = adds.filter { it.unit !in nowIds2 && it.unit !in addDone }       // 已在队里/已直调的不必再点
                .mapNotNull { op ->
                    // ★坐标**一次算好**，整批点完中途不读（2026-11-07 用户要求）。
                    //   点的是"角色一览"里的卡 —— 一览是候选池，编不编它都在那儿、位置不动 ⇒ 坐标稳定。
                    //   （槽位那边会补位，但加入点的是一览，不受影响。）
                    val c = findUnitCoord(snapForTap, op.unit, op.via) ?: return@mapNotNull null
                    op to c
                }
            val addSkipped = adds.filter { it.unit in nowIds2 }.map { it.unit }
            if (addSkipped.isNotEmpty()) {
                AppState.addLog("〔跳过〕加入 ${addSkipped.joinToString(",")}：快照里已在队里")
            }
            // ★直调已经加进去的、以及本来就在队里的都不算 noCoord ⇒ 只剩"要 tap 但找不到坐标的"
            val noCoord = adds.map { it.unit }.filter { u ->
                u !in nowIds2 && u !in addDone && addTodo.none { it.first.unit == u }
            }
            if (noCoord.isNotEmpty()) {
                AppState.addLog(
                    "… 加入：${noCoord.joinToString(",")} 本页没有可点坐标（不在可见区？）→ 交给滚动分支",
                )
            }
            if (addTodo.isNotEmpty()) {
                // ★2026-11-19（日志精简）：不再先打一行"加入 N 个"—— 下面每人一条「点了 编队：add X」已够。
                for ((op, c) in addTodo) {
                    tap(input, c.first, c.second, "编队：add ${op.unit}")
                    // ★2026-11-07：不再死等 350ms ⇒ **等这个人真的进队**就立刻点下一个。
                    // ★★2026-11-09 修正：`awaitUnitInTeam` 内部就会**轮询到 350ms 上限**，
                    //   超时后再 `delay(350)` ⇒ 最坏 700ms/人 ⇒ 换人时"每两个顿一下"。
                    //   现在只补到**最小间隔 350ms**（命中即走，最坏不比固定等待差）。
                    val t0 = SystemClock.elapsedRealtime()
                    if (!awaitUnitInTeam(op.unit, want = true, label = "add")) {
                        val spent = SystemClock.elapsedRealtime() - t0
                        AppState.addLog("编队：等 add ${op.unit} 进队超时 ${spent}ms（只补到最小间隔）")
                        if (spent < PartyPlan.LAB_PARTY_TAP_MS) delay(PartyPlan.LAB_PARTY_TAP_MS - spent)
                    }
                }
            }
            // ★2026-11-07：不再固定等 1 秒 ⇒ 轮询队伍名单，**加的人都进来了、撤的人都出去了**就立刻走。
            //   ★成功路径**不再读整页**（原来那一次 100~300ms 白花在这儿）—— 核对只需要名单。
            val w = awaitPartyRoster(nowIds2, ok = { ids -> partyBatchOk(ops, addTodo, ids) })
            var after = w.ids
            var afterSnap: JSONObject? = null
            // ★核对不过 → **再等一会儿复读一次**（PC 3744~3757 同款，2026-11-01 补）：
            //   编组页刚铺开/增删动画没停时，那一下点会被游戏吞掉，1s 静置不够 ⇒ 误判"点了没反应"；
            //   而下一轮会对着同一张卡再点一下 —— 一览里那张卡是**切换**语义（再点=把人撤掉）⇒ 加/撤来回拉。
            if (!w.ok) {
                delay(PartyPlan.LAB_PARTY_RECHECK_S)
                val second = partySnap()
                val a2 = second?.let { partyNowIds(it) }
                if (a2 != null) {
                    if (a2 != after) {
                        AppState.addLog(
                            "编队：复读一次 → 实际队伍变了（${after.joinToString(",")} → ${a2.joinToString(",")}）",
                        )
                    }
                    afterSnap = second
                    after = a2
                }
            }
            if (after != null) {
                val badRm = ops.filter { it.action == "remove" && it.unit in after }.map { it.unit }
                val badAdd = addTodo.map { it.first.unit }.filter { it !in after }
                if (badRm.isNotEmpty() || badAdd.isNotEmpty()) {
                    // ★现场（PC `party_stuck` 同款，2026-11-01）：这一行就是"为什么加不进去"的证据 ——
                    //   `√/×` = 该卡是否落在角色一览的裁剪带内（× = 屏上看不见/被裁，点了也白点）；
                    //   `nowMemberN` = 队伍权威名单的原始长度（≠ 下面 `队=` 的个数 ⇒ 读到的人被过滤掉了）。
                    AppState.addLog(
                        "[!] 编队核对：撤了还在=[${badRm.joinToString(",")}]｜加了没成=[${badAdd.joinToString(",")}] " +
                            "｜队=${after.joinToString(",")}（nowMemberN=${afterSnap?.optInt("nowMemberN")}）" +
                            "｜槽位=${partyIconIds(afterSnap, "icons")}" +
                            "｜一览=${partyIconIds(afterSnap, "scrollIcons")}" +
                            "｜队伍=${((afterSnap?.opt("nowSelectParty") as? Number)?.toInt() ?: 0) + 1} " +
                            "｜目标=${plan.target} → 下轮按实际队伍重算",
                    )
                }
            }
            return PARTY_STEP_AGAIN
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
            // ★cs127（2026-11-21，用户："不要退化 swipe"）：进滚动分支前先跑一遍**分段直调**。
            //   现场复盘（Boss_319401 三队）：上一队把滚动条停在半路 → 这一队目标整批屏外
            //   ⇒ ops 全空直接掉进滚动分支，白 swipe 3 次（3.3s，还触发"方向反复"惩罚），
            //   而下一轮分段跳一次全中。直调不吃坐标、池子累积 ⇒ 先按 PARTY_SCROLL_STEPS
            //   跳一遍清缺员，清干净的这轮就不滚了；一个都没命中的才照旧 swipe。
            if (off.isNotEmpty()) {
                var remain = off.map { it.unit }.distinct().toMutableSet()
                var anyHit = false
                for (v in PARTY_SCROLL_STEPS) {
                    if (remain.isEmpty()) break
                    val sr = runCatching { AgentApi.labPartyScrollTo(v) }.getOrNull()
                    if (sr?.optBoolean("ok") != true) continue
                    val slots = runCatching { AgentApi.labPartySlots() }.getOrNull()
                    val poolUids = slots?.optJSONArray("pool")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optInt("unitId") }
                    }.orEmpty()
                    val hit = remain.intersect(poolUids.toHashSet()).toList()
                    if (hit.isEmpty()) continue
                    val done = partyDirectPress("add", hit) ?: emptySet()
                    if (done.isNotEmpty()) anyHit = true
                    remain -= done
                    AppState.addLog(
                        "编队：滚动前分段跳 $v（池 ${poolUids.size}）命中 ${hit.size} → 直调 ${done.size} 个" +
                            "（${done.joinToString(",")}），剩 ${remain.size}",
                    )
                }
                if (anyHit) {
                    // 直调清掉过人 ⇒ 这一轮不滚（下轮重算 offscreen，清剩下的再决定滚不滚）
                    AppState.addLog(
                        if (remain.isEmpty()) "编队：滚动前分段直调已清完缺员 → 不再滚动"
                        else "编队：滚动前分段直调清了 ${off.size - remain.size}/${off.size} 个，剩 $remain 下轮再试",
                    )
                    return PARTY_STEP_AGAIN
                }
            }
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
                    if (scrollPartyList(input, pt, dir, ps.slowSteps)) {
                        ps.count++
                        // ★2026-11-15（用户）：同一关来回滑（相邻两次方向相反）⇒ **每 2 次把滑动比例调小 0.1**，
                        //   让来回摆动的幅度逐次收敛，别在两端反复弹。离开本节点时随 [partyScroll] 一起消失。
                        if (ps.lastDir != null && ps.lastDir != dir) {
                            ps.reverseCount++
                            if (ps.reverseCount % 2 == 0) {
                                ps.slowSteps++
                                AppState.addLog(
                                    "编队：本节点来回滑动 ${ps.reverseCount} 次（方向反复）→ 滑动比例 -0.1" +
                                        "（累计 -${"%.1f".format(0.1f * ps.slowSteps)} 行，本节点生效）",
                                )
                            }
                        }
                        ps.lastDir = dir
                        if (ps.count <= 2 || ps.count % 10 == 0) {
                            AppState.addLog(
                                "编队：滚动角色一览找缺员（第 ${ps.count} 次，方向=${if (dir == "down") "下" else "上"}，" +
                                    "当前可见 ${vis.size} 张）",
                            )
                        }
                    }
                    // ★2026-11-07：从"固定等 1.5s"改成"轮询到一览稳定"（滚完屏上那排会变，
                    //   连续两次相同 ⇒ 滚到位了）。`swipe` 的 350ms 是滑动本身，不能省（会被当甩动）。
                    //   ★`before` = 滚动**前**的签名 ⇒ 必须"先变化再稳定"，否则动画没开始就判稳了。
                    awaitPartyIconsStable(timeoutMs = 1_200L, before = partyListSig(pt))
                }
            }
            return PARTY_STEP_AGAIN
        }
        // ② 已就绪（或"这一队不编 ⇒ 放行"）→ 决定「特别装备」+ 点「决定/战斗开始」（防抖：gap 内不重复点）
        if ((plan.done || bossAdv) && PartyPlan.LAB_PARTY_AUTO) {
            val key = nk                               // nodeKey(st)，上面已算过
            val sess = exeqSess()
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
            // ── ★特别装备：编队就绪后自动点开（LAB_EXEQUIP_AUTO）→ 之后**打包**走完三连页 ──
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
                        // ★选好最优队后**额外跑一次**：按输出成员的系别决定 ③ 页选哪个排序项
                        //  （物理多 ⇒ 物理防御贯穿；魔法多 ⇒ 魔法防御贯穿；平局默认物理）。
                        sess.sortTarget = exeqSortTarget(plan.target)
                        AppState.addLog("特别装备：目标队输出系别 → 排序选「${sess.sortTarget}」")
                        // ★2026-11-22（用户）：BOSS 战只有**一队**穿 EX，第 2/3 队不再处理 ⇒ 恒共享。
                        //   （原来按 `nowSelectParty` 判第几队，第 2/3 队会去"取消勾选"，但本作只有一队。）
                        sess.exShare = true
                        if (sess.exShare) sess.team1Sig = sig
                        sess.opened = true
                        sess.openedAt = System.currentTimeMillis()
                        // ★★2026-11-19（并入 feat/direct-trigger）：这颗是 `DialogCommonPartyEdit+0x158` 的
                        //   `CustomUIButton` ⇒ 直调 `onClick` 委托链，不投屏幕坐标（不会点空/点到叠层，
                        //   也省掉 `input tap` 的 ~110ms）。直调失败才退回坐标点击。
                        partyBtnOrTap(input, "exequip", eq.optInt("x"), eq.optInt("y"),
                            "打开特别装备${if (sess.exShare) "" else "（第2/3队：不共用其他角色装备）"}")
                        // ★★2026-11-20（用户）：不再死等 525ms（LAB_PARTY_TAP_MS×1.5）——
                        //   改成**轮询 EX 三连页出现**（签名非空即到），最快几十 ms 就能往下走。
                        //   实测这 525ms 白等占了"打开→自动装备"731ms 的大头；上限 1.5s 兜底。
                        val exOpenT0 = android.os.SystemClock.elapsedRealtime()
                        while (android.os.SystemClock.elapsedRealtime() - exOpenT0 < 1_500L) {
                            val s0 = exeqSigNow()
                            if (s0.isNotEmpty() && s0 != EXEQ_UNKNOWN) break
                            delay(50)
                        }
                        // ★★2026-11-08：三连页改成**打包跑完**（原来是"点开就交回主循环、由 handleExequip 单步接管"）。
                        //   现在在**同一次调用里**把①②③走完 ⇒ 走完就回到编组页 ⇒ 下一轮重新读页再点「战斗开始」。
                        //   （旧做法的坑：主循环下一轮看到的是 EX 页，顶框 DialogCommonBig ⇒ 通用弹窗白名单
                        //     会命中 ThreeButton*/TwoButton* ⇒ 误点"装备确定/确认"；而且 EX 页上读 labStatus
                        //     是纯浪费 —— 打包之后主循环根本不会看到 EX 页。）
                        handleExequipPacked(input)
                        return PARTY_STEP_AGAIN      // ★重读编组页 ⇒ 接着点「战斗开始」
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
                    return PARTY_STEP_DONE
                }
                val now = System.currentTimeMillis()
                // ★防抖（避免重复开打）—— 但 **Boss 放行路径要绕开**：
                //   Boss 页每点一次「决定」是"切到下一队"，三支队伍要连着点完；
                //   受这个 gap 限制就会变成"点一次 → 等 gap → 再点"（用户说的"延迟点击队伍2、3"）。
                //   切队/连点的安全由 [BOSS_SWITCH_WAIT_MS] 的"等它真切过去"保证（绝不连点）。
                if (!bossAdv && now - partyDoneAt < PartyPlan.LAB_PARTY_TAP_GAP) {
                    delay(150)
                    return PARTY_STEP_DONE
                }
                partyDoneAt = now
                val bt = pt.optJSONObject("buttons") ?: JSONObject()
                val dec = bt.optJSONObject("decide")
                    // ★要不要编后备队（5 层 Boss 默认编满 3 队 / 失败过也要补队）：
                    //   true ⇒ 切到队伍2/3 后**要接着编那一队**；false ⇒ 那两队空着也合法，一路点到底。
                    val needBackup = (nodeFailed[nodeKey(st)] ?: 0) > 0 || fillAll
                if (dec != null && dec.opt("x") != null) {
                    // ★这一队算确认了 → 记下来（下一队缩池的兜底；`already` 判据也用它）
                    if (plan.done && plan.target.isNotEmpty()) {
                        val lst = partyTeams.getOrPut(key) { mutableListOf() }
                        val t = plan.target.sorted()
                        if (t !in lst) lst.add(t)
                    }
                    weStartedFight.add(key)                  // 记"这局是我们点的战斗开始"
                    if (bossAdv) {
                        // ★Boss 多队：三队挤在同一个编组页，这颗按钮的实际效果是"**切到下一队**"
                        //   （顶框不变）⇒ 判据 = **窗口 + 队伍索引** 的二元组（2026-11-07 实测可用）：
                        //     · 顶框离开 PartyEdit ⇒ 开战了
                        //     · nowSelectParty 变了 ⇒ 切队成功（主循环下一轮会判断这队要不要编）
                        //
                        // ★★★2026-11-07 修：切队是**异步**的，原来点完只等 200ms 就判"没反应"、
                        //   于是**连点第 2 下** ⇒ **把中间的队伍整个跳过去**（队伍2 空着、队伍3 有人
                        //   ⇒ "前面空着"是**非法配置** ⇒ 游戏不认 ⇒ 点「决定」永远没反应 ⇒ 死循环卡死，
                        //   实测 18:36:10~18:36:32 反复点 20 多次）。用户 2026-11-07："点下一队的时候等待
                        //   时间太短，连点 2 下直接点到队伍 3 了"。
                        //   现在改成：**点一下 → 轮询等它真的切过去**（最多 [BOSS_SWITCH_WAIT_MS]），
                        //   切过去了就停；实在没动才再点一次（**绝不连点**）。
                        // ★★2026-11-07 用户："3 层没失败的情况下，不能直接按普通战斗处理吗" ——
                        //   游戏机制上**做不到**：Boss 页的「决定」在队伍1/2 上就是"切到下一队"
                        //   （实测 19:01:00 点完 idx 0→1），三队都要**逐一确认**才能开打
                        //   （"后面空着"允许、"前面空着"不允许 ⇒ 最后一队点完才是退出/开打）。
                        //   但**我们自己的处理**可以无视它：
                        //     · 3 层没失败（不编后备队）⇒ 队伍2/3 空着也合法 ⇒ **一路点到底**，
                        //       直到编组框消失（用户 2026-11-07："我们可以无视它直接一直点到组队页面消失啊！"）；
                        //     · 5 层 / 失败过（要编后备队）⇒ 切到队伍2 就**立刻开始算+编这一队**，
                        //       编好再点下一队 —— 全部在**同一次调用**里做完，不用等主循环转圈。
                        var advOk = false
                        var advTry = 0
                        var advNeedCompile = false      // ★切到了"要编"的队伍 ⇒ 交回外圈去编它
                        var advLeft = false             // ★编组框已消失 ⇒ 开打/退出
                        while (advTry < 5) {
                            advTry++
                            val (bt0, bi0) = AgentApi.labPartyIdx()
                            // ★把三队的人数一起打出来 —— "前面空着"这种非法配置一眼能看出来。
                            val _pt = AgentApi.labPartyLite()
                            val _dic = _pt?.optJSONObject("partyDic")
                            AppState.addLog(
                                "… Boss 决定前：队伍1=${PartyPlan.partyDicN(_dic, 0)}人 " +
                                    "队伍2=${PartyPlan.partyDicN(_dic, 1)}人 " +
                                    "队伍3=${PartyPlan.partyDicN(_dic, 2)}人" +
                                    "（本队=第${((_pt?.opt("nowSelectParty") as? Number)?.toInt() ?: 0) + 1}队）",
                            )
                            // ★2026-11-20：Boss 决定直调（同 6016 的 partyBtnOrTap("decide")）
                            partyBtnOrTap(input, "decide", dec.optInt("x"), dec.optInt("y"), "决定·跳过本队（$curN 人，允许空）")
                            // ★轮询等切队生效（**期间绝不再点**）
                            val t0 = SystemClock.elapsedRealtime()
                            var bt1 = bt0
                            var bi1 = bi0
                            while (SystemClock.elapsedRealtime() - t0 < BOSS_SWITCH_WAIT_MS) {
                                kotlinx.coroutines.delay(120)
                                val (b, i) = AgentApi.labPartyIdx()
                                bt1 = b
                                bi1 = i
                                if (b != "DialogCommonPartyEdit") break          // 离开编组页 ⇒ 开战了
                                if (i >= 0 && i != bi0) break                      // 队伍索引变了 ⇒ 切队成功
                            }
                            advOk = bt1 != "DialogCommonPartyEdit" || (bi1 >= 0 && bi1 != bi0)
                            AppState.addLog(
                                "◧ Boss 决定：[$bt0 idx=$bi0] → [$bt1 idx=$bi1]" +
                                    "（第 $advTry 次${if (advOk) "生效" else "没反应"}，" +
                                    "等了 ${SystemClock.elapsedRealtime() - t0}ms）",
                            )
                            // ★切到了队伍2/3：这一队**要编** ⇒ 交回外圈（重新读页 → 算 → 编），
                            //   不用等主循环转一圈（用户 2026-11-07："完全可以检测到队伍2后，直接开始
                            //   计算2队，编组2队，然后点队伍3，计算并编组，进入战斗"）。
                            if (advOk) {
                                if (bt1 != "DialogCommonPartyEdit") {
                                    advLeft = true              // 编组框消失 ⇒ 开打了
                                    break
                                }
                                if (bi1 > 0) {
                                    if (needBackup) {
                                        advNeedCompile = true    // 这一队要编 ⇒ 交回外圈去编它
                                        break
                                    }
                                    continue                    // 不用编 ⇒ 立刻接着点下一次
                                }
                                break                        // 还在队伍1 ⇒ 收手
                            }
                            // 没反应 ⇒ 循环继续（再点一次；最多 5 次）
                        }
                        if (advLeft) {
                            // ★点完最后一队的「决定」= **已经开打了**（实测 19:26:33 点完 → 19:26:34 进入战斗）
                            //   ⇒ 树上残留的 DialogQuestDetail 是**空壳**，别再去点它的「挑战」。
                            bossOpenedAt[nodeKey(st)] = System.currentTimeMillis()
                            return PARTY_STEP_EXITED
                        }
                        if (advNeedCompile) return PARTY_STEP_AGAIN
                    } else if (isBoss) {
                        // ★★Boss 关**即使编满了也不能重试**（2026-11-07 用户）：
                        //   三队在同一个编组页里，这颗按钮的实际效果常常是"切到下一队"——
                        //   顶框还是 PartyEdit、tapAndCheck 判成"没生效"就会连点，
                        //   于是 2/3 队没编就被直接跳过。这里**只点一次**，并等它切过去。
                        val (pit0, pidx0) = AgentApi.labPartyIdx()
                        // ★2026-11-19：直调优先（`DialogCommonPartyEdit+0x110` 的 CustomUIButton）
                        partyBtnOrTap(input, "decide", dec.optInt("x"), dec.optInt("y"), "战斗开始（Boss·切队/开战）")
                        var pbt1 = pit0
                        var pidx1 = pidx0
                        val pt0 = SystemClock.elapsedRealtime()
                        while (SystemClock.elapsedRealtime() - pt0 < BOSS_SWITCH_WAIT_MS) {
                            kotlinx.coroutines.delay(120)
                            val (b, i) = AgentApi.labPartyIdx()
                            pbt1 = b
                            pidx1 = i
                            if (b != "DialogCommonPartyEdit") break
                            if (i >= 0 && i != pidx0) break
                        }
                        AppState.addLog(
                            "◧ Boss 编组：点前 [$pit0 idx=$pidx0] → 点后 [$pbt1 idx=$pidx1]" +
                                "（等了 ${SystemClock.elapsedRealtime() - pt0}ms）",
                        )
                        // ★和 bossAdv 一样：切到队伍2/3 且这一队要编 ⇒ 交回外圈去编它
                        if (pbt1 != "DialogCommonPartyEdit") {
                            bossOpenedAt[nodeKey(st)] = System.currentTimeMillis()
                            return PARTY_STEP_EXITED
                        }
                        if (pidx1 > 0 && needBackup) return PARTY_STEP_AGAIN
                        return PARTY_STEP_DONE
                    } else {
                        // ★普通战：「战斗开始」的终点是**战斗中**（顶框为空）——离开编组页即算生效
                        // ★★2026-11-08 改成**探针制**（原来是 settleMs=800 固定等一次）：
                        //   · 原来"点完死等 800ms 再读一次"——切走了也得等满；而 2026-11-07 实测
                        //     200ms 又太快（过场要先盖一层 DialogQuestDetail）⇒ 被判"没生效"连点 3 下。
                        //   · 现在：**200ms 起步 + 最多探 4 次**（每次隔 150ms）⇒ 覆盖同样的 650ms 窗口，
                        //     但**切走立刻停**（不用等满）；没切走就继续探，探完才重试 ⇒ 不会连点。
                        //   · 【战斗开始】(1676,908) 与预览页【挑战】(1635,915) 只差 41px ⇒ 多点很危险，
                        //     所以宁可多探也别多点。
                        tapAndCheck(
                            input, dec.optInt("x"), dec.optInt("y"), "战斗开始",
                            // ★不重读坐标（2026-11-17 用户）：生效判据是**状态**（from 带 "@battle"、
                            //   expect = @battle），进战斗即算成功。若真点进去了却还在重试，重读
                            //   ButtonDecide 会读到战斗结算页的「继续/Next」——那说明这一下早成功了，
                            //   重读反而会误点。所以这里坚持用首次快照坐标 + 多探，绝不重读。
                            // ★★from 里带 "@battle"：**进战斗了也算"离开"** ——
                            //   进战斗时编组页对话框可能还在（labTop 不变）⇒ 只看窗口名判不出生效
                            //   ⇒ 会连点（实测 10:39:55 连点 6 下，5 下是点在战斗视图里，纯浪费）。
                            from = setOf("DialogCommonPartyEdit", "@battle"),
                            retries = 6,
                            probe = PROBE_N,
                            // 阶段2 等"进入战斗"（无对话框，用 inBattle 判）
                            expect = setOf("@battle"),
                            // ★★2026-11-19（并入 feat/direct-trigger）：直调 onClick 委托链代替坐标点击
                            //   （`DialogCommonPartyEdit+0x110`）。⚠不能直接调 `onClickDecide`（实测 NPE）。
                            //   直调整个失败时 partyBtnOrTap 内部会退回坐标点击。
                            pressFn = {
                                partyBtnOrTap(
                                    input, "decide",
                                    dec.optInt("x"), dec.optInt("y"), "战斗开始",
                                )
                            },
                        )
                        return PARTY_STEP_EXITED
                    }
                } else {
                    AppState.addLog("[!] 编队页找不到「战斗开始」按钮（ButtonDecide）")
                    return PARTY_STEP_DONE
                }
            }
        }
        return PARTY_STEP_DONE
    }

    /**
     * ★★特别装备三连页：**打包自己跑完**（2026-11-08，不走主循环）。
     *
     * · 编组页点开「特别装备」后**当场**把三连页走完，主循环**根本不会看到 EX 页**
     *   ⇒ 也就不会在 EX 页上读 `labStatus`（EX 页上根本用不上那些字段）。
     * · 内部循环 = 「读签名（状态检测）→ 决策 → 点一下 → 再读签名看变了没」，
     *   每步只有**一次**轻量读（签名）+ **一次**按需读（只要那一个按钮的坐标）。
     * · 流程是确定的线性序列：编组页 →（点「特别装备」）→ ①特别装备设定 → ②自动特别装备设定
     *   → ③优先属性值 → ② → ① →（点「装备确定」）→ 编组页。
     */
    private suspend fun handleExequipPacked(input: InputBackend) {
        if (!LAB_EXEQUIP_AUTO) {
            AppState.addLog("    ⏸ 特别装备：LAB_EXEQUIP_AUTO=false → 不自动，等手动")
            return
        }
        val sess = exeqSess()
        val t0 = System.currentTimeMillis()
        var fullFail = 0                              // 连续读不到整包的次数（agent 不在状态 ⇒ 收手）
        // ★2026-11-19：三连页直调的可用性（旧版 agent 不支持 ⇒ 连续 2 次失败就改回坐标点击，不会卡住）
        var exeqDirectOk = true
        var exeqDirectFail = 0
        sess.n = 0                                   // ★每次进入 = 一轮新的尝试（决策基于当前页面状态，幂等）
        // ★★每次尝试都要把"上一轮的现场"清干净（2026-11-08）：
        //   · `exStarted` 不清的话 ⇒ 上一轮走完（exStarted=true）后，这一轮刚点开「特别装备」
        //     读到"不在页上"就会被当成"已经走完" ⇒ 立刻退出，装备根本没设。
        //   · `sawSetting`/`auto` 是"**这一轮**有没有进过②/点过自动装备"，跨轮复用会误判。
        sess.exStarted = false
        sess.offN = 0
        sess.sawSetting = false
        sess.auto = false
        sess.autoAt = 0L
        sess.tapSig = null
        sess.tapBtn = null
        sess.tapBtnId = null
        sess.tapX = -1
        sess.tapY = -1
        sess.tapN = 0
        sess.finalPressOk = false                    // ★2026-11-20：新一轮重置"最后一击已确认"
        sess.earlyExitAt = 0                         // ★2026-11-22：新一轮重置"等壳消失"标志（否则旧值误伤新流程）
        sess.stall = 0                               // ★连续失败计数（新一轮清零）
        sess.sayN = 0                                // ★诊断日志节流（新一轮清零）
        sess.pending = true                          // ★"流程进行中"（收尾处靠它判"异常收手"）
        OverlayStatus.setState("特别装备")
        while (System.currentTimeMillis() - t0 < EXEQ_TOTAL_BUDGET_MS) {
            // ── ① 状态检测（轻量：只回一个签名字符串）──
            val tDet = android.os.SystemClock.elapsedRealtime()
            val sigNow = exeqSigNow()
            val detMs = android.os.SystemClock.elapsedRealtime() - tDet
            if (sigNow == EXEQ_UNKNOWN) { delay(EXEQ_POLL_MS); continue }   // 读不到 ⇒ 等一下再试
            if (sigNow == EXEQ_OFF_PAGE) {
                // ★空签名 = 不在三连页里 ⇒ 两种含义：**还没开出来**，或**已经走完回到编组页**
                if (!sess.exStarted) {
                    val el = System.currentTimeMillis() - t0
                    if (el > EXEQ_WAIT_MAX_MS) {
                        // ★三连页压根没开出来（点没生效 / 页面没弹）⇒ **放弃这次装备**，别卡住流程
                        AppState.addLog("    [!] 点了「特别装备」但 ${el}ms 后三连页还没出来 → 放弃装备，直接准备开打")
                        sess.pending = false
                        sess.exDone = true
                        break
                    }
                    delay(EXEQ_POLL_MS); continue         // 页面正在开 ⇒ 等它
                }
                // ★连续两次读到空才判"真走完"（一次可能是"最上层被别的东西盖住"的误判）
                sess.offN++
                if (sess.offN < 2) { delay(EXEQ_POLL_MS); continue }
                // ★★2026-11-20（用户）：最后一击「装备确定」**已确认直调投递成功** ⇒ 立刻收工，
                //   不再等 EX 容器从顶层消失（那只是关闭动画，实测过渡态最长 11s）。
                //   依据：直调 ok=true 已确认 onClick 执行；且签名连续两轮读空 = 三连页确实在退。
                if (EXEQ_EARLY_EXIT && sess.finalPressOk) {
                    sess.earlyExitAt = System.currentTimeMillis()
                    AppState.addLog(
                        // ★文案不写死「装备确定」：最后一击可能是它，也可能是「取消（自动装备后无改动）」
                        "    ✓ 特别装备走完（最后一击已确认投递成功 → 提前收工，不等容器消失）" +
                            "｜从最后一次点击算起 ${System.currentTimeMillis() - sess.lastTapAt}ms",
                    )
                    sess.pending = false
                    sess.fbN = 0
                    break
                }
                // ★★★2026-11-08 实测 10:29:36 修：**光看签名空是不够的** ——
                //   切页动画期间会**短暂读空**（②页正在关、①页还没接管，最上层一时没有 EX 组件）。
                //   那时若就判"走完" ⇒ ①页其实还开着、**「装备确定」根本没点**（装备没设上！），
                //   而且 EX 页留在屏幕上 ⇒ 后面的操作全在错的页面上做。
                //   ⇒ 空签名 + **顶层仍是 EX 容器** ⇒ 是过渡态，继续等（等①页露出来再点「装备确定」）。
                //   ★实测 10:39:41~10:39:52 这个过渡态**持续了 11 秒** ⇒
                //     ① 轮询别那么密（60ms → 250ms，agent 单通道也省点）② 日志**别刷屏**（同一句只打一次）。
                val topNow = runCatching { AgentApi.labTop() }.getOrNull() ?: ""
                if (EXEQ_CONTAINERS.any { topNow.startsWith(it.substringBefore("(")) }) {
                    if (sess.offN == 2) {
                        AppState.addLog("    · 签名读空但顶层还是 EX 容器($topNow) ⇒ 过渡态，继续等（等①页接管）")
                    }
                    delay(EXEQ_IDLE_WAIT_MS)
                    continue
                }
                AppState.addLog(
                    "    ✓ 特别装备三连页走完（回到编组页）" +
                        "｜从最后一次点击算起用了 ${System.currentTimeMillis() - sess.lastTapAt}ms" +
                        "（含等 EX 页容器从顶层消失的动画时间）",
                )
                sess.pending = false                    // ★走完了 ⇒ 主循环不用再轻量探测
                sess.fbN = 0                            // ★干净收尾 ⇒ 兜底接管计数归零
                // ★兜底：正常流程最后一击是「装备确定」（那时已置 exDone），
                //   走到这儿还没置 ⇒ 是被**别的方式**关掉的（用户手动关 / 页面自己收回）。
                //   不置 exDone 的话编组页会一直卡在"等装备"（永远不点「战斗开始」）⇒ 这里补上。
                if (!sess.exDone) {
                    sess.exDone = true
                    AppState.addLog("    · （没点过「装备确定」就回到了编组页 ⇒ 按“已处理”放行）")
                }
                break
            }
            sess.offN = 0
            sess.exStarted = true                       // ★三连页确实开着
            // ── ② 页身份 / 阶段位（签名里就有，不用再读整包）──
            val page = exeqPageOf(sigNow)
            if (page == EXEQ_PAGE_SETTING) sess.sawSetting = true     // ★进过② ⇒ 之后 change=false 才是"真没改动"
            if (sess.tapSig != sigNow) {
                sess.tapSig = sigNow
                sess.tapN = 0
                // ★2026-11-19（日志精简）：正常切页（通常 5~26ms）不再打这行；只有读得明显慢
                //   （>=300ms，说明 agent/游戏在卡）才提示 —— 保留了"间隔是否够"的诊断价值。
                if (detMs >= 300) {
                    AppState.addLog(
                        "    [!] 特别装备：状态检测 ${detMs}ms 偏慢（签名=$sigNow）｜按钮读累计 ${sess.fullMs}ms",
                    )
                }
            }
            if (fullFail >= 3) {
                AppState.addLog("    [!] 特别装备：连续 $fullFail 次读不到页面（agent 不在状态）→ 收手")
                break
            }
            if (sess.n >= EXEQ_MAX_STEPS) {
                AppState.addLog("    [!] 特别装备流程点了 $EXEQ_MAX_STEPS 下仍未结束 → 停手")
                break
            }
            // ── ③ 查**转移表**：这一步做什么、去哪（★只用轻量签名 ⇒ 几十 ms）──
            //   表的一条规则 = **（当前页, 条件）→（动作, 目标页）**；
            //   签名里已经带了页内关键状态（change/row/checked/sel）⇒ 不用等整包就知道目标页。
            var rule = exeqRuleOf(sigNow, sess)
            if (rule == null) {
                // ★没命中任何规则 ⇒ 页内状态读不全（change/checked/row/sel 拿不到）⇒ 本轮不点，等一下再读
                if (sess.n < 2) {
                    AppState.addLog("    [!] $page 页：读不到页内状态（change/checked/row/sel）→ 本轮不点")
                }
                delay(EXEQ_POLL_MS); continue
            }
            // ★★2026-11-21（用户）：①页「自动装备」在某些角色下是**暗的**（没可装备的东西 ⇒ 禁用）。
            //   直调 `onClick` 会**绕过禁用检查**（游戏真实点击走 `onDisabledClick`）⇒ 误触发。
            //   ⇒ 此时**直接点「取消」收尾**，与「装备确定」暗时的口径一致（算作 EX 装备部分完成）。
            //   探测只在①页且这一步本是"点自动装备"时做一次（~10ms），其余步骤零开销。
            if (rule.page == EXEQ_PAGE_PARTY && rule.btnKey == "ThreeButtonCenter") {
                val pb = runCatching {
                    AgentApi.labExBtn("ThreeButtonCenter", sess.tapBtnId, timeoutMs = EXEQ_READ_FULL_MS)
                }.getOrNull()
                if (pb != null && pb.has("enabled") && !pb.optBoolean("enabled", true)) {
                    rule = ExeqRule(
                        page = EXEQ_PAGE_PARTY,
                        cond = { _, _ -> true },
                        what = "取消（自动装备是暗的）",
                        target = EXEQ_OFF_PAGE,
                        btnKey = "ThreeButtonLeft",
                        isFinal = true,
                    )
                    exeqSay(sess, "「自动装备」是暗的（该角色没可装备的东西）→ 点「取消」收尾，算装备完成")
                }
            }
            // ★"此刻该等"（刚点完「自动装备」、页面正在切到②）⇒ 不点，等一下再看（省一次读）
            if (rule.waitWhile?.invoke(sess) == true) { delay(EXEQ_POLL_MS); continue }
            // ── ④ 执行转移：**点一下，等到达目标页**（tapAndCheck 阶段 1 的语义）──
            //   · ★**按需读按钮（~10ms）每轮只做一次**：拿动作 + 按钮坐标，之后**复用同一个坐标** ⇒
            //     多点也点在**同一个位置**上（不会落到别的目标）；页面若在动 ⇒ 下一轮外层会重读拿新坐标。
            //   · ★读回来的签名必须与外层的轻量签名**一致**（不一致 ⇒ 页面正在切 ⇒ 这一轮不点）。
            //   · ★★方案 B：判据是**"到达目标页了没"**（不是"签名变了没"）：
            //     没到目标 ⇒ 这次没生效 ⇒ **重试同一个动作**（同一按钮实例），不重新决策点别的。
            // ★★2026-11-16（用户）：**每次点击前都重读坐标**，不再复用本轮上一次的坐标。
            //   实测（22:49 那次）：EX 页刚打开、入场动画未结束时读到的按钮坐标是过渡态的
            //   （「自动装备」读到 y=760，正常 y≈934）⇒ 点在装备格上打开了「选择装备」页；
            //   而重试又原样点了同一个错坐标，把一次误触放大成卡死 30 秒。
            //   动画中的点击通常无效 —— 只要**下一次换新坐标**就好，所以每次 xyAt 都重新读。
            val ok = tapAndCheck(
                input, 0, 0, rule.what,
                from = setOf(sigNow),
                retries = EXEQ_TAP_TRIES,
                readTop = { exeqSigNow() },
                unknownTop = EXEQ_UNKNOWN,
                // ★★这次转移的**落点**（转移表给的）⇒ 到达它才算这次转移完成
                reached = { sig -> exeqReached(sig, sigNow, rule.target) },
                // ★★点完**探**几次（不是"等一下就重试"）—— 页面动画没走完时签名不会变，
                //   探到"变了"就停；探完还没变才判"真没生效"（那时按钮已静止，再点才点得准）。
                probe = PROBE_N,
                // ★2026-11-19（用户）：探测间隔从 1.5× 提到 **2×probeMs** —— 对齐 0.7.2 的手感：
                //   0.7.2 这一步的首次探测是写死的 `EXEQ_TAP_SETTLE_MS = 300ms`，而默认档
                //   （最快 150ms）×2 = 300ms，正好一致。三连页切页动画比普通页慢，探太快会在
                //   按钮还在动时判"没到达" ⇒ 重试 ⇒ 第二下落在动画中的错坐标上（点穿到装备格）。
                //   ★probeMs 同时是 TapCheck.run 里 effSettle / skipDelayMs 的默认值 ⇒ 首次探测、
                //     后续探测、首点前跳拍三处都跟着 ×2。
                probeMs = TapCheck.currentProbeMs() * 2L,
                // ★2026-11-17（用户）：统一走默认 skipFirstHits=1（1×probeMs=150ms，已覆盖原先
                //   prio0 的 2×60=120ms 需求）；不再按 btnKey 单独设。
                // ★★2026-11-19（并入 feat/direct-trigger）：改为**直调**（`pressFn`，不投屏幕坐标）。
                //   三连页是**叠三层**的，坐标点很容易落到下面那层；而且省掉 `input tap` 的 ~110ms。
                //   返回 Boolean?：true=已投递，false=投递失败收工，null=这一刻不点（跳过重试）。
                //   （lambda label 仍沿用 `xyAt@`，减少改动面。）
                pressFn = xyAt@{
                    // ① 页面已经切走？⇒ 这一次不点（外层循环会重新读页面状态）
                    if (exeqSigNow() != sigNow) return@xyAt null
                    // ② 拿动作：★**每次都重读那一个按钮**（按需读，~10ms）—— 坐标随动画实时更新；
                    //   只有读回来的签名与这一拍一致时才点，避免点到别的目标。
                    val act = run {
                        val tFull = android.os.SystemClock.elapsedRealtime()
                        // ★★**按需读**（2026-11-08）：只要**这一步要点的那个按钮** + 当前签名，
                        //   不再读"整包"（整包会把页里其它控件也过一遍：setting 的 4 行文字/复选框、
                        //   sort 的 11 个 toggle 文字 —— 每个都是一次游戏线程调用 ⇒ 纯浪费）。
                        val xr = AgentApi.labExBtn(rule.btnKey, sess.tapBtnId, timeoutMs = EXEQ_READ_FULL_MS)
                        sess.fullMs += android.os.SystemClock.elapsedRealtime() - tFull
                        if (xr == null) {
                            fullFail++
                            exeqSay(sess, "按钮读失败（agent 没回包）→ 这一轮不点")
                            return@xyAt null
                        }
                        fullFail = 0
                        val sr = xr.optString("sig")
                        // ★读数与外层不一致 ⇒ 页面正在切/状态在变 ⇒ 这一轮**不点**（避免点到别的按钮）
                        if (sr != sigNow) {
                            exeqSay(sess, "读数签名≠轻量签名（页面在切）→ 这一轮不点")
                            return@xyAt null
                        }
                        // ★按**转移表**的动作取按钮（外层已用签名定好"这一步做什么、去哪"；
                        //   这里只补上按钮实例 + 最新坐标）
                        val a = exeqActOf(rule, xr) ?: run {
                            exeqSay(sess, "${rule.what}：按钮读不到（页面数据不全）→ 这一轮不点")
                            return@xyAt null
                        }
                        // 多点按**同一个按钮实例**计数（换按钮/换实例就清零）
                        val bn = a.btn.optString("name")
                        val bid = a.btn.optString("id")
                        if (sess.tapBtn != bn || (bid.isNotEmpty() && sess.tapBtnId != bid)) {
                            sess.tapBtn = bn
                            sess.tapBtnId = bid
                            sess.tapN = 0
                        }
                        a
                    }
                    if (sess.tapN >= EXEQ_TAP_MAX) {
                        exeqSay(sess, "${rule.what}：同一个按钮已点 $EXEQ_TAP_MAX 下 → 不再点")
                        return@xyAt null
                    }
                    sess.tapN++
                    sess.n++
                    sess.tapX = act.btn.optInt("x")
                    sess.tapY = act.btn.optInt("y")
                    sess.lastTapAt = System.currentTimeMillis()
                    if (act.markAuto) { sess.auto = true; sess.autoAt = System.currentTimeMillis() }
                    if (act.isFinal) {
                        sess.exDone = true
                        // 只有队伍1（exShare）配完才更新基线（PC：Boss 只看队伍1，队伍2/3 不覆盖）
                        if (sess.exShare) {
                            val sig1 = sess.team1Sig
                            if (!sig1.isNullOrEmpty()) exequipLastTeam1 = sig1
                        }
                    }
                    // ★把**按钮实例 id**（指针尾 8 位）打进日志：多点重试时用它核对"点的是不是同一个按钮"
                    //   ★顺带打出**目标页**（转移表给的落点）—— 日志里能直接看到"这一步要去哪"
                    AppState.addLog(
                        "特别装备：${act.what} → ${act.target.ifEmpty { "编组页" }}" +
                            "（按钮=${act.btn.optString("name")}" +
                            "@${act.btn.optString("id").takeLast(8)} 签名=$sigNow）",
                    )
                    // ★★直调（`labExPress`）：同一 key + **上次的按钮实例 id**（把重试锁在同一颗按钮上）。
                    //   ③页的排序项是 `PartsUnitSortTypeToggle`，agent 侧会自动改走无参 `OnClick()`。
                    if (!exeqDirectOk) {
                        // 直调已判定不可用（旧版 agent）⇒ 退回坐标点击
                        return@xyAt tap(input, act.btn.optInt("x"), act.btn.optInt("y"), rule.what)
                    }
                    // ★★2026-11-22（实机探测确认）：复选框是 `UIToggle`（GameObject "Toggle"），
                    //   直调 `CustomUIButton.onClick` **不切 value**（连点 3 次仍 false）——
                    //   真正的直调是 `UIToggle.set_value(bool)`（实测一次生效、复读校验通过）。
                    if (rule.btnKey == "check") {
                        val want = !rule.what.contains("取消")
                        val r = runCatching {
                            AgentApi.obj("labExCheckSet", listOf<Any?>(want), timeoutMs = EXEQ_READ_FULL_MS)
                        }.getOrNull()
                        if (r?.optBoolean("ok") == true) return@xyAt true
                        AppState.addLog(
                            "特别装备：勾选直调失败（${r?.optString("err").orEmpty().ifEmpty { "无回包" }}）→ 退回坐标点击",
                        )
                        return@xyAt tap(input, act.btn.optInt("x"), act.btn.optInt("y"), rule.what)
                    }
                    val pr = AgentApi.labExPress(rule.btnKey, sess.tapBtnId, timeoutMs = EXEQ_READ_FULL_MS)
                    // ★`pressTwoPhase` 返回轮询后的最终结果（ok/via/err），没有 queued
                    if (pr?.optBoolean("ok") == true) {
                        exeqDirectFail = 0
                        // ★2026-11-20：最后一击「装备确定」直调确认成功 ⇒ 允许提前收工（不等容器消失）
                        if (act.isFinal) sess.finalPressOk = true
                        return@xyAt true
                    }
                    if (++exeqDirectFail >= 2) {
                        exeqDirectOk = false
                        AppState.addLog("特别装备：直调连续 $exeqDirectFail 次不可用 → 改回坐标点击")
                    }
                    exeqSay(
                        sess,
                        "直调投递失败（${pr?.optString("err").orEmpty().ifEmpty { "agent 没回包" }}）→ 这一轮不点",
                    )
                    return@xyAt null
                },
            )
            // ★生效了 ⇒ 立刻进下一步（不等）；没生效 ⇒ 等一下再来（别把 agent 读爆）
            if (ok) {
                sess.stall = 0
                sess.sayN = 0
            } else {
                // ★★2026-11-08 修（实测 11:44 那轮卡死）：**连续点不动就放弃这次装备**，
                //   别再空转到 30 秒预算耗尽（那一轮整包读挂住 ⇒ 一声不吭地卡了 30 秒）。
                sess.stall++
                if (sess.stall >= EXEQ_STALL_MAX) {
                    AppState.addLog(
                        "    [!] 特别装备：连续 ${sess.stall} 次点不动/读不到 → 放弃这次装备" +
                            "（不卡住开打）",
                    )
                    sess.pending = false
                    sess.exDone = true
                    break
                }
                delay(EXEQ_RETRY_WAIT_MS)
            }
        }
        // ── 收尾：正常走完（回到编组页）已在循环里清了 pending；
        //   能走到这儿 = **异常收手**（步数/预算用尽）⇒ 放弃这次装备，
        //   别让主循环的兜底探测把它变成"每轮重试"的死循环。
        if (sess.pending) {
            AppState.addLog("    [!] 特别装备流程异常收手（步数/预算用尽）→ 放弃这次装备，直接准备开打")
            sess.pending = false
            sess.exDone = true
        }
    }

    /** 一次 EX 点击的**决策结果**（点哪个按钮 + 这次点击的副作用） */
    private data class ExeqAct(
        /** 人话描述（打日志用） */
        val what: String,
        /** 要点的按钮（整包里的一项：x/y/name/id） */
        val btn: JSONObject,
        /** ★**目标页**（这次转移的落点）：party / setting / sort / `""`=回到编组页 */
        val target: String,
        /** ★点完这个按钮后，"已经点过自动装备"要置真 */
        val markAuto: Boolean = false,
        /** ★这一步是**收尾**（装备确定 / 取消）—— 点完整个流程就结束了 */
        val isFinal: Boolean = false,
    )

    /**
     * ★★**EX 三连页的显式转移表**（2026-11-08 方案 B；用户口径：
     * "靠当前页面和目标页面去控制点击"）。
     *
     * 一条规则 = **（当前页, 条件）→（动作, 目标页）**：
     *   · 当前页 `page`   = 页身份（party / setting / sort）
     *   · 条件   `cond`   = 用**轻量签名**判定（几十 ms ⇒ 不用等整包就知道"这一步要去哪"）
     *   · 动作   `what`   = 人话描述
     *   · 目标页 `target` = ★这次点击**要去哪一页**（转移的落点）
     *   · 取按钮 `pick`   = 从**整包**里取要点的那个（按钮随页内状态变 ⇒ 由整包数据决定；
     *                        带 GameObject 实例 id，坐标每次现取）
     *
     * ★"目标页"是这次显式化的**核心**：
     *   以前点完只要"签名变了"就算生效（可能只是页内状态变了）⇒ 点歪了也当成功；
     *   现在必须**到达目标页**（或同页但状态真的变了）才算这次转移完成
     *   ⇒ 没到目标 ⇒ **重试同一个动作**（同一按钮实例），而不是重新决策去点别的按钮。
     */
    private data class ExeqRule(
        val page: String,
        val cond: (String, ExeqSess) -> Boolean,
        val what: String,
        val target: String,
        /** ★**要点的那个按钮**的键**（agent 侧"按需读"这一个：按钮名 / "prio0" / "check" /
         *  `"sort:<选项名>"`）—— 读法见 [AgentApi.labExBtn]（只读这一个按钮 + 当前签名，不读整包）。 */
        val btnKey: String,
        /** ★按钮名要**按会话状态**现算时用（③ 页的排序项随目标队系别变；命中时 [exeqRuleOf] 会 copy 覆盖） */
        val btnKeyOf: ((ExeqSess) -> String)? = null,
        val markAuto: Boolean = false,
        val isFinal: Boolean = false,
        /** ★"此刻该等"（如：刚点完「自动装备」、页面正在切）⇒ 不点，等一下再看（省一次读） */
        val waitWhile: ((ExeqSess) -> Boolean)? = null,
    )

    /** ★**EX 三连页的转移表本体**（顺序 = 优先级，第一条 cond 成立的生效） */
    private val EXEQ_RULES: List<ExeqRule> by lazy {
        listOf(
            // ── ① 特别装备设定（party）──
            ExeqRule(
                page = EXEQ_PAGE_PARTY,
                cond = { sig, _ -> exeqTriOf(sig, "change") == true },
                what = "装备确定",
                target = EXEQ_OFF_PAGE,
                btnKey = "ThreeButtonRight",
                isFinal = true,
            ),
            ExeqRule(
                page = EXEQ_PAGE_PARTY,
                cond = { sig, s -> exeqTriOf(sig, "change") == false && s.auto && !s.sawSetting },
                what = "自动装备（等切到②·超时重试）",
                target = EXEQ_PAGE_SETTING,
                // ★刚点完「自动装备」，页面正在切到② ⇒ change=false 只是**中间态**，
                //   绝不能据此判"自动装备后无改动"（那会跳到「取消」⇒ 点错按钮，2026-11-08 实测）。
                //   超过 [EXEQ_AUTO_WAIT_MS] 还没切过去 ⇒ 那次点击没生效 ⇒ 再点一次（见 waitWhile）。
                btnKey = "ThreeButtonCenter",
                markAuto = true,
                waitWhile = { s -> System.currentTimeMillis() - s.autoAt < EXEQ_AUTO_WAIT_MS },
            ),
            ExeqRule(
                page = EXEQ_PAGE_PARTY,
                cond = { sig, s -> exeqTriOf(sig, "change") == false && s.auto && s.sawSetting },
                what = "取消（自动装备后无改动）",
                target = EXEQ_OFF_PAGE,
                btnKey = "ThreeButtonLeft",
                isFinal = true,
            ),
            ExeqRule(
                page = EXEQ_PAGE_PARTY,
                cond = { sig, _ -> exeqTriOf(sig, "change") == false },
                what = "自动装备",
                target = EXEQ_PAGE_SETTING,
                btnKey = "ThreeButtonCenter",
                markAuto = true,
            ),
            // ── ② 自动特别装备设定（setting）──
            //   ★"设好了"的判据 = 第 ① 行当前值 == 本次要选的排序项（不再是写死的「稀有度」）。
            ExeqRule(
                page = EXEQ_PAGE_SETTING,
                cond = { sig, s ->
                    val r = exeqFieldOf(sig, "row")
                    r != null && r != "?" && r != s.sortTarget
                },
                what = "「全部」行 进「优先属性值」",
                target = EXEQ_PAGE_SORT,
                btnKey = "prio0",
            ),
            ExeqRule(
                page = EXEQ_PAGE_SETTING,
                cond = { sig, s ->
                    exeqFieldOf(sig, "row") == s.sortTarget &&
                        exeqTriOf(sig, "checked") == false && s.exShare
                },
                what = "复选框(补勾)",
                target = EXEQ_PAGE_SETTING,
                btnKey = "check",
            ),
            ExeqRule(
                page = EXEQ_PAGE_SETTING,
                cond = { sig, s ->
                    exeqFieldOf(sig, "row") == s.sortTarget &&
                        exeqTriOf(sig, "checked") == true && !s.exShare
                },
                what = "复选框(取消勾选·不抢别队装备)",
                target = EXEQ_PAGE_SETTING,
                btnKey = "check",
            ),
            ExeqRule(
                page = EXEQ_PAGE_SETTING,
                cond = { sig, s ->
                    // ★checked 必须是 **true/false**（"null" = 读不到 ⇒ 不点，等一下再读）
                    exeqFieldOf(sig, "row") == s.sortTarget && exeqTriOf(sig, "checked") != null
                },
                what = "确认",
                target = EXEQ_PAGE_PARTY,
                btnKey = "TwoButtonRight",
            ),
            // ── ③ 优先属性值（sort）──
            ExeqRule(
                page = EXEQ_PAGE_SORT,
                cond = { sig, s -> exeqFieldOf(sig, "sel") == s.sortTarget },
                what = "确认",
                target = EXEQ_PAGE_SETTING,
                btnKey = "TwoButtonRight",
            ),
            // ★选中目标项（物理/魔法防御贯穿）：btnKey 按会话状态现算，见 [exeqRuleOf]。
            ExeqRule(
                page = EXEQ_PAGE_SORT,
                cond = { _, _ -> true },
                what = "选系别防御贯穿",
                target = EXEQ_PAGE_SORT,
                btnKey = "",
                btnKeyOf = { "sort:" + it.sortTarget },
            ),
        )
    }

    /**
     * ★按**轻量签名**查转移表（几十 ms）—— 决定"这一步做什么、去哪"。
     *
     * ★用签名而不是整包：签名里已经有页内关键状态（change / row / checked / sel），
     *   所以**不用等整包读**就知道目标页 ⇒ 点击确认的判据可以当场定下来。
     *
     * @return null = 页内状态读不全（change/checked/row/sel 拿不到）⇒ 本轮不点
     */
    private fun exeqRuleOf(sig: String, sess: ExeqSess): ExeqRule? {
        val page = exeqPageOf(sig) ?: return null
        for (r in EXEQ_RULES) {
            if (r.page != page) continue
            if (!r.cond(sig, sess)) continue
            // ★③ 页「选排序项」的动作随队伍系别变 ⇒ 命中时现算按钮名与日志文案
            return r.btnKeyOf?.let { f ->
                r.copy(what = "「${sess.sortTarget}」", btnKey = f(sess))
            } ?: r
        }
        return null
    }

    /** ★按转移表的动作，取**按需读回来的那一个按钮**（取不到 ⇒ 不点） */
    private fun exeqActOf(r: ExeqRule, x: JSONObject): ExeqAct? {
        val btn = x.optJSONObject("btn") ?: return null
        if (btn.opt("x") == null) return null
        return ExeqAct(r.what, btn, r.target, r.markAuto, r.isFinal)
    }

    /**
     * ★**这次转移到达目标了没**（方案 B 的核心判据）。
     *
     * · 目标页 ≠ 当前页 ⇒ 必须**页身份**变成目标页
     * · 目标页 == 当前页（如②页点复选框、③页点选项）⇒ 必须**页内状态**真的变了（签名变了）
     *
     * @param sig 刚读到的签名；[fromSig] 发起这次转移时的签名；[target] 转移表给的目标页
     */
    private fun exeqReached(sig: String, fromSig: String, target: String): Boolean {
        if (sig == EXEQ_UNKNOWN) return false
        val p = exeqPageIdOf(sig)
        val fp = exeqPageIdOf(fromSig)
        return if (p == fp) sig != fromSig else p == target
    }

    /**
     * ★从**签名**里取 `|key=value` 的值（签名格式 `页身份@容器|状态…`）。
     *
     * 签名里已经带了页内关键状态（change / row / checked / sel）⇒
     *   转移表的**条件**可以只用轻量签名判定（几十 ms），不用等整包读。
     */
    private fun exeqFieldOf(sig: String, key: String): String? {
        val i = sig.indexOf("|$key=")
        if (i < 0) return null
        val rest = sig.substring(i + key.length + 2)
        val j = rest.indexOf('|')
        return if (j < 0) rest else rest.substring(0, j)
    }

    /**
     * ★签名里的**三态**字段（true / false / null）。
     *
     * `null` = **读不到**（agent 侧读失败时写的 "null" 字符串）——
     *   ★不能把它当成"false"或"已确定"，否则会误判成"没改动 ⇒ 点取消"这类**点错按钮**的事。
     */
    private fun exeqTriOf(sig: String, key: String): Boolean? = when (exeqFieldOf(sig, key)) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** ★签名的**页身份**（party / setting / sort / `""`=不在三连页 / `"?"`=读不到） */
    private fun exeqPageIdOf(sig: String): String = when {
        sig == EXEQ_UNKNOWN -> EXEQ_UNKNOWN
        else -> exeqPageOf(sig) ?: EXEQ_OFF_PAGE
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
     * ★当前队伍是不是**空的**（2026-11-07）。
     *
     * 用途：判断"编组页是不是已经换成新节点的内容了" —— 新节点从**空队**开始，
     *   所以读到空队就说明页面已经是新的（不用再等满 1.5s）。
     */
    private fun partyTeamEmpty(pt: JSONObject): Boolean {
        val nm = pt.optJSONArray("nowMember") ?: return false
        for (i in 0 until nm.length()) {
            if (nm.optInt(i, 0) != 0) return false
        }
        return true
    }

    /**
     * ★编队页的"内容签名"（2026-11-07）：**一览**（`scrollIcons`，按列表顺序）+ **槽位**（`icons`）。
     *
     * 用途：滚动角色一览**之前**取一份，滚动后交给 [awaitPartyIconsStable] 当 `before` ——
     *   必须"先变了、再稳定"才算滚到位（只看"连读两次相同"的话，滑动动画还没开始就判稳了）。
     */
    private fun partyListSig(pt: JSONObject): List<Int> {
        val out = ArrayList<Int>()
        val a = pt.optJSONArray("scrollIcons")
        for (i in 0 until (a?.length() ?: 0)) {
            out.add((a!!.optJSONObject(i)?.opt("unitId") as? Number)?.toInt() ?: 0)
        }
        val s = pt.optJSONArray("icons")
        for (i in 0 until (s?.length() ?: 0)) {
            out.add((s!!.optJSONObject(i)?.opt("unitId") as? Number)?.toInt() ?: 0)
        }
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
    private suspend fun scrollPartyList(
        input: InputBackend,
        pt: JSONObject,
        dir: String,
        /** ★2026-11-15：本节点已累计的减速次数（每 2 次方向反复 +1，每次 -0.1 行） */
        slowSteps: Int = 0,
    ): Boolean {
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
        // ★2026-11-15：整局缓存「一行宽度」。第一次反推出合理值后存进 [partyRowHCache]，
        //   之后所有滚动**复用**，不再每拍现取 —— 否则向上滚时可见卡片分布变了，
        //   会把"隔行差(2×行距)"当成"相邻差(1×行距)"反推出约 2 倍行距
        //   （小鹿 1280×720：向下 rh≈144、向上 rh≈257 ⇒ 一次想滑 385px 撞屏幕边距、还滚过头）。
        //   取"所有相邻坐标差里的最小桶"（用户：取坐标差最小的数值，要求 >20），
        //   而非"高频最小"——隔行差必为相邻差的整数倍、只会更大，最小桶恒等于真行距。
        var rh = 0.0
        if (partyRowHCache > 20.0) {
            rh = partyRowHCache
        } else if (icons.size >= 2) {
            val cols = HashMap<Int, MutableList<Int>>()
            for ((x, y) in icons) cols.getOrPut(Math.round(x / 60f) * 60) { mutableListOf() }.add(y)
            val gaps = ArrayList<Int>()
            for (ys in cols.values) {
                val s = ys.toSortedSet().toList()
                for (i in 0 until s.size - 1) {
                    val g = kotlin.math.abs(s[i + 1] - s[i])
                    if (g in 20..400) gaps.add(g)                     // 合理行高区间（下限放宽到 20）
                }
            }
            if (gaps.isNotEmpty()) {
                val buckets = HashMap<Int, Int>()
                for (g in gaps) {
                    val k = Math.round(g / 8f) * 8                    // 量化到 8px 桶，容忍几像素噪声
                    buckets[k] = (buckets[k] ?: 0) + 1
                }
                // ★取"最小桶"（不再要求高频）：相邻行的真实间距必然最小，隔行差是其整数倍、只会更大
                rh = (buckets.keys.minOrNull() ?: 0).toDouble()
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
        if (rh > 20.0) partyRowHCache = rh                            // ★缓存，整局复用

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
        // ★2026-11-16（用户）：设置页的「编队滚动行数」已移除 —— 固定 1.5 行；
        //   同一关来回滑时按 [slowSteps] 每次 -0.1（下限 1.0），自动收敛摆动幅度、别滚过头。
        val baseRows = 1.5f
        val rows = (baseRows - 0.1f * slowSteps).coerceAtLeast(1.0f).toDouble()
        val want = rows * rh                                           // ★一次滚几行 × 行距
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
                "＝${got.toInt()}px（约 ${"%.1f".format(got / rh)} 行；目标 ${"%.1f".format(rows)} 行＝${want.toInt()}px）" +
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
            // ★2026-11-07：改用轻量接口（`labPartyPlan` 拿成员名单，~25ms）——
            //   原来读整页 `labParty`（~900ms）就为了知道"队里还有几个人 + 1 号位在哪"。
            val plan = AgentApi.labPartyPlan()
            if (plan == null) {
                AppState.addLog("[!] 清队伍$teamNo：读不到编队页快照 → 停止清这队")
                return
            }
            val members = plan.members.filter { it != 0 }
            val left = members.size
            if (left == 0) return
            // ★★2026-11-22（用户）：拆人改**直调**（同主流程撤人那套：按 uid 一次全点完 +
            //   Plate.onClickUnitIcon + 复核）；坐标连点 1 号位那条路退为兜底。
            if (round == 0) AppState.addLog("清队伍$teamNo：$left 人直调撤下（Plate.onClickUnitIcon）")
            val done = partyDirectPress("remove", members)
            if (done != null) {
                delay(PartyPlan.LAB_PARTY_BATCH_SETTLE)
                val after = AgentApi.labPartyPlan()?.members?.count { it != 0 } ?: -1
                if (after == 0) return
                AppState.addLog("清队伍$teamNo：直调后还剩 $after 人 → 再补一轮（第 ${round + 2}/3 轮）")
                return@repeat
            }
            // ── 坐标兜底（原路线）：1 号位 = 站位序第一个人（`nowMember[0]`，= 画面上**最右**那个槽位），
            //    撤下后后面的人往右补位 ⇒ 对着同一格连点即可。
            val c = runCatching { AgentApi.labPartyIconPos(members.first(), "槽位") }.getOrNull()
            if (c == null) {
                AppState.addLog("[!] 清队伍$teamNo：读不到 1 号位坐标 → 停止清这队")
                return
            }
            val n = left.coerceAtMost(5)
            AppState.addLog("清队伍$teamNo：直调不可用 → 对着 1 号位（最右）连点 $n 次")
            for (i in 1..n) {
                tap(input, c.first, c.second, "清队伍$teamNo·1 号位（$i/$n）")
                delay(PartyPlan.LAB_PARTY_TAP_MS)
            }
            delay(PartyPlan.LAB_PARTY_BATCH_SETTLE)
            val after = AgentApi.labPartyPlan()?.members?.count { it != 0 } ?: -1
            if (after == 0) return
            AppState.addLog("清队伍$teamNo：点完还剩 $after 人 → 再补一轮（第 ${round + 2}/3 轮）")
        }
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
     * ★读**最新**编队快照（PC `handle_party_edit` 里那句 `script.exports_sync.lab_party_flush()` 同款）。
     *
     * agent 侧 `labPartySnapshot` 有 1s 的结果缓存（`PARTY_TTL_MS`）；点完一下若不 flush 就读，
     * 很可能拿到**点之前**的队伍 ⇒ 自检误判"加了没成" → 下一轮对着同一张卡再点一下，
     * 而一览里那张卡是**切换**语义（再点一下=把人撤掉）⇒ 加/撤来回拉、界面上看就是"同一个人点了上千次"。
     * PC 侧批首/批末/复读三处都 flush，Android 之前一次都没调过（`labPartyFlush` 只声明没用）。
     */
    private suspend fun partySnap(): JSONObject? {
        AgentApi.labPartyFlush()
        // ★2026-11-07：改用**精简读** `labPartyLite` —— 只取决策要的字段
        //   （队伍名单 / 槽位 id / 一览 id+vis / 按钮坐标 …），不读每个 icon 的中文 label、
        //   不带页签候选与诊断字段 ⇒ 每轮省掉一大坨序列化+传输+解析。
        //   ★要**槽位坐标**的地方（点撤人）请用 `labPartyIconPos(uid, "槽位")` 现取。
        return AgentApi.labPartyLite()
    }

    /** 整批核对：撤的人是否真出队、加的人是否真入队（[handlePartyEdit] 批末用；PC 3739 `_okteam` 同款） */
    private fun partyBatchOk(
        ops: List<PartyPlan.Op>,
        added: List<Pair<PartyPlan.Op, Pair<Int, Int>>>,
        now: Set<Int>,
    ): Boolean {
        if (ops.any { it.action == "remove" && it.unit in now }) return false
        return added.all { it.first.unit in now }
    }

    /** 快照里某个图标数组的 unitId 列表（`√`=在裁剪带内可点、`×`=被裁/屏外）—— 只用于卡住时的现场日志 */
    private fun partyIconIds(pt: JSONObject?, arr: String): String {
        val a = pt?.optJSONArray(arr) ?: return "-"
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            val u = (c.opt("unitId") as? Number)?.toInt() ?: continue
            if (u == 0) continue
            val vis = c.opt("vis")
            out.add("$u" + if (vis === JSONObject.NULL || vis == false) "×" else "√")
        }
        return if (out.isEmpty()) "-" else out.joinToString(" ")
    }

    /**
     * ★点一下之后，**等这个角色真的进出队**（2026-11-07 用户："各个地方间隔都变小了"）。
     *
     * 原来一批人是"点一下 → 固定等 350ms → 再点下一个" ⇒ 一批 4 个人光间隔就 1.4 秒，
     *   而游戏其实 50~150ms 就把队伍更新好了。
     * 现在改成：点完**轮询队伍名单**（极轻的 `labPartyPlan`，~25ms），
     *   这个角色**真的**进出队了就立刻点下一个（通常 80~150ms）；
     *   等不到就按原来的 350ms 上限继续（**最坏不比原来差**）。
     *
     * @param want true = 等这个人**进**队；false = 等这个人**出**队
     * @return true = 已看到生效；false = 超时/读不到（调用方按原节奏继续）
     */
    private suspend fun awaitUnitInTeam(
        unit: Int,
        want: Boolean,
        timeoutMs: Long = PartyPlan.LAB_PARTY_TAP_MS,
        label: String = "",
    ): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        var n = 0
        var first = -1
        var last = -1
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            n++
            val plan = runCatching { AgentApi.labPartyPlan(timeoutMs = 700L) }.getOrNull()
            if (plan == null || plan.top != "DialogCommonPartyEdit") {
                AppState.addLog(
                    "编队[$label] 等 $unit ${if (want) "进队" else "出队"}：读不到编组页（第${n}轮）→ 放弃" +
                        "｜当前顶框=${plan?.top ?: "读取失败"}",   // ★2026-11-17 诊断：区分"读失败"还是"顶框被切走"
                )
                return false
            }
            val cnt = plan.members.count { it != 0 }
            if (first < 0) first = cnt
            last = cnt
            if ((unit in plan.members) == want) {
                // ★2026-11-19（日志精简）：第 1 轮就命中是常态，不打；只有重试后（n>1）才记
                if (n > 1) {
                    AppState.addLog(
                        "编队[$label] $unit ${if (want) "进队" else "出队"} 重试 ${n - 1} 次后命中 " +
                            "${SystemClock.elapsedRealtime() - t0}ms（人数 $first→$last）",
                    )
                }
                return true
            }
            kotlinx.coroutines.delay(50)
        }
        AppState.addLog(
            "编队[$label] $unit ${if (want) "进队" else "出队"} **超时** 第${n}轮 " +
                "${SystemClock.elapsedRealtime() - t0}ms（人数 $first→$last）" +
                (if (first == last) "｜同队人数没变 ⇒ 这一下可能没点进去" else "｜人数变了但没读到它"),
        )
        return false
    }

    // ────────────────────────── 直调（并入 feat/direct-trigger，2026-11-19）──────────────────────────

    /**
     * ★编组页加/减角色的**直调**（不点屏幕）：返回**确实生效**的 unitId 集合。
     *
     * 加 → `PartsPartyEditScrollController.onClickUnitIcon`；撤 → `PartsPartyEditPlate.onClickUnitIcon`
     * （撤人只能在 Plate 上做：池里 icon 的 `onClick` 只挂了 ScrollController 一条委托，
     *  跟着它走永远只有"加"——这正是以前"点已选角色反而又加了一个"的原因）。
     *
     * @return `null` = 整条链路不可用（未连接 / agent 不支持 / 钩子装不上）⇒ 调用方退回坐标点击。
     *   `Set` = 实际生效的那些（可能少于请求的：`ok=true` 只代表没抛异常）。
     */
    private suspend fun partyDirectPress(kind: String, uids: List<Int>): Set<Int>? {
        if (uids.isEmpty()) return emptySet()
        val want: Set<Int> = uids.toHashSet()
        val r = runCatching { AgentApi.labPartyPress(kind, uids) }.getOrNull() ?: return null
        if (!r.has("done")) {
            AppState.addLog(
                "编队：${if (kind == "add") "加" else "撤"}人直调不可用" +
                    "（${r.optString("err", "无返回")}）→ 退回点击",
            )
            return null
        }
        // ★复核：读一次队伍名单，确认到底谁变了（连续投递时偶发中间一次不生效）
        val after = runCatching { AgentApi.labPartyPlan(timeoutMs = 1_200L) }.getOrNull()
            ?.members?.filter { it != 0 }?.toSet()
        if (after == null) {
            AppState.addLog("编队：${if (kind == "add") "加" else "撤"}人直调后读不到队伍 → 退回点击")
            return null
        }
        val done = if (kind == "add") want.filter { it in after } else want.filter { it !in after }
        val miss = want - done.toHashSet()
        if (miss.isNotEmpty()) {
            AppState.addLog(
                "编队：${if (kind == "add") "加" else "撤"}人直调没生效 ${miss.joinToString(",")} → 退回点击",
            )
        }
        return done.toHashSet()
    }

    /**
     * ★编组页三颗按钮的**直调 + 坐标兜底**：`decide`（战斗开始）/ `cancel` / `exequip`（特别装备）。
     *
     * 这三颗都是 `DialogCommonPartyEdit` 的 `CustomUIButton` 字段（`+0x110/+0x120/+0x158`），
     * 按组件指针直调 `onClick` 委托链，既快又不会点空；坐标点击在"两页叠在一起"时
     * 容易落到上层同位置的按钮上（实测把「挑战」点成了「战斗开始」）。
     *
     * @return true = 这一下已经发出去了（直调或坐标兜底都算）
     */
    private suspend fun partyBtnOrTap(
        input: InputBackend,
        which: String,
        x: Int,
        y: Int,
        what: String,
    ): Boolean {
        val pr = runCatching { AgentApi.labPartyBtn(which) }.getOrNull()
        // ★`pressTwoPhase` 返回轮询后的最终结果（ok/via/err），没有 queued
        if (pr?.optBoolean("ok") == true) return true
        val err = pr?.optString("err").orEmpty().ifEmpty { "无回包" }
        AppState.addLog("$what：直调失败（$err）→ 退回坐标点击")
        return tap(input, x, y, what)
    }

    /**
     * ★按 `LAB_PATHS` 固定路径**直调**，失败退回坐标点击。
     *
     * 只对"页面上**唯一**的那颗按钮"用：路径是**按钮名 + 固定下标链**，页里若有多颗同名按钮
     * （三选一的 Role0/1/2、地图里 13 个同名格子），下标链必须真机验证过才敢用。
     *
     * @param key `"<页名>|<按钮名>"`，见 [AgentApi.labPathPress]
     */
    private suspend fun pathPressOrTap(
        input: InputBackend,
        key: String,
        x: Int,
        y: Int,
        what: String,
    ): Boolean {
        val pr = runCatching { AgentApi.labPathPress(key) }.getOrNull()
        if (pr?.optBoolean("ok") == true) return true
        val err = pr?.optString("err").orEmpty().ifEmpty { "无回包" }
        AppState.addLog("$what：路径直调失败（$key / $err）→ 退回坐标点击")
        return tap(input, x, y, what)
    }

    /**
     * ★等队伍名单变成"想要的样子"（2026-11-07 用户："选完几个可见角色后到滑动之前还是有一段间隔"）。
     *
     * 原来批点完是**固定等 1 秒**（[PartyPlan.LAB_PARTY_BATCH_SETTLE]）—— 那一秒是纯等，
     *   而游戏通常 100~300ms 就把队伍更新好了 ⇒ 白等。
     * 现在用**最轻**的 `labPartyPlan`（~25ms，只读队伍名单，不投影坐标、**不走 agent 那 1s TTL 缓存**）
     *   轮询：一旦达成就立刻返回；没达成就等到原来的 1 秒上限（行为不比原来差）。
     *
     * @param before 点之前的队伍名单（[ok] 为 null 时的判据 = "名单变了"）
     * @param ok 自定义判据（例：加的人都进了、撤的人都出去了）
     * @param wantSnap 是否要**完整**精简快照（核对/现场日志要 icons/scrollIcons/buttons）
     * @return 等待结果（见 [RosterWait]）
     */
    private suspend fun awaitPartyRoster(
        before: Set<Int>,
        ok: ((Set<Int>) -> Boolean)? = null,
        timeoutMs: Long = PartyPlan.LAB_PARTY_BATCH_SETTLE,
        wantSnap: Boolean = false,
    ): RosterWait {
        val t0 = SystemClock.elapsedRealtime()
        var ids = before
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val plan = runCatching { AgentApi.labPartyPlan(timeoutMs = 1_200L) }.getOrNull()
            if (plan != null) {
                // ★页面已经不是编组页了 ⇒ 别再拿空名单当"队伍变了"（否则会误判成"人全没了"）
                if (plan.top != "DialogCommonPartyEdit") break
                ids = plan.members.filter { it != 0 }.toSet()
                if (ok?.invoke(ids) ?: (ids != before)) break
            }
            kotlinx.coroutines.delay(60)
        }
        // ★完整精简快照**只在需要时**才读（核对失败时的现场日志用）
        //   —— 成功路径不再白读一次整页（那一次 100~300ms）。
        val snap = if (wantSnap) runCatching { partySnap() }.getOrNull() else null
        return RosterWait(ids = ids, ok = ok?.invoke(ids) ?: (ids != before), snap = snap)
    }

    /** [awaitPartyRoster] 的结果 */
    private class RosterWait(
        /** 最后看到的队伍名单 */
        val ids: Set<Int>,
        /** 是否在超时前达成判据 */
        val ok: Boolean,
        /** 完整精简快照（只在 `wantSnap=true` 时才读） */
        val snap: JSONObject?,
    )

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
        // ★2026-11-17（用户）：撤人（via="槽位"）**只认下排槽位数组 icons**，绝不退回角色一览
        //   scrollIcons —— 点一览里同 id 的卡不会撤人，反而会误触别的元素（本次实测就退到一览坐标
        //   (618,434) 点下去直接开战）。找不到就返回 null，交给下一轮/滚动分支。
        val order = if (via == "槽位") listOf("icons") else listOf("scrollIcons", "icons")
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
                    val xi = c.optInt("x")
                    val yi = c.optInt("y")
                    // ★屏内校验：挡掉"未布局 / 投影到屏幕外"的垃圾坐标（快：纯数值比较）
                    if (xi !in 0 until scrW || yi !in 0 until scrH) continue
                    return xi to yi
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
                OverlayStatus.setState("等待接手：请点开战", attention = true)
            }
            return
        }
        // ★★2026-11-07：Boss 三队编好后，点最后一队的「决定」**就已经开打**了，但树上会残留一个
        //   **空的** DialogQuestDetail（空壳：子树只有 23 个节点，真预览页有 200+；「挑战」按钮坐标也不一样
        //   —— 找不到就退回常量坐标 (1635,915)）。以前会把它当成"退回到预览页"又点一次「挑战」
        //   ⇒ 那是**在战斗画面上乱点**（实测 19:28:10 点完立刻 BATTLE(29)，还白等 6 秒）。
        //   ⇒ 点完决定后一段时间内不再点挑战，等主循环去看战斗画面。
        val _boAt = bossOpenedAt[nk]
        if (_boAt != null && System.currentTimeMillis() - _boAt < 20_000L) {
            if (bossOpenedSaid.add(nk)) {
                AppState.addLog("… Boss 已开打（树上那个 DialogQuestDetail 是**残留空壳**）→ 不再点挑战，等进入战斗")
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
            // ★Boss 链（2026-11-01）：已经过了「编队·开战」(step≥5) ⇒ 这个预览页只是**战斗过渡**，
            //   不再重试点「挑战」（那一下的坐标在编组页里恰好是「战斗开始」⇒ 历史事故点）。
            if (BossChain.isAfterBattleStart()) {
                if (n == BATTLE_IDLE_WARN || n % 50 == 0) {
                    AppState.addLog("… [链] 已开战（step≥5）：预览页只是过渡 → 不再点「$BATTLE_START_BTN」")
                }
                return
            }
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
        // ★2026-11-19（日志精简）：预览页读得慢（>=300ms）才提示；正常 20~100ms 不再每次打。
        val qdMs = qd?.optInt("_ms") ?: -1
        if (n == 1 && qdMs >= 300) {
            AppState.addLog(
                "… 预览页读得慢：${qdMs}ms（最上层=$qTop，子树节点=${qd?.optInt("nodes") ?: -1}）",
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
                }
                p = fp
            }
        }
        val pFinal = p
        if (pFinal != null && tries.getOrPut("QD_TAPS", { 0 }) < BATTLE_TAP_MAX_TOTAL) {
            tries["QD_TAPS"] = taps + 1
            qdTapAt = SystemClock.elapsedRealtime()
            weStartedFight.add(nodeKey(st))            // 记下"这场是我们开的"（PC `_LAST_FIGHT_TEAM` 的简化）
            // ★终点确定是编组页（2026-11-07 用户）：短停 200ms + 多点几次，
            //   宁可多戳几下，也不要站着等过场动画
            // ★★2026-11-07 用户：settleMs 200 → **600ms**。原来点完只等 200ms 就判"没生效"，
            //   而进战斗/进编组的过场动画要 400~600ms 顶框才变 ⇒ 明明点成功了却被判成没生效、连点好几下。
            //   【挑战】和【战斗开始】这两个按钮都可能在**同一个位置**（预览页「挑战」(1635,915) vs
            //   编组页「战斗开始」(1676,908)，只差 41px）⇒ 多点的那几下有误点风险 ⇒ 加长间隔而不是加次数。
            // ★★2026-11-07 用户："进节点的时候就知道 boss 是谁了，此时一边处理怎么进入编队页面，
            //   一边去算队伍" —— 点挑战**之前**就把目标队伍算好，
            //   与「点挑战 → 等过场动画 → 读编组页」并行（后台只做纯计算，不占 agent 的 RPC 通道）。
            //   ⇒ 进编组页后 [handlePartyEdit] 直接命中缓存，省掉整整一次算法（~1 秒）。
            runCatching {
                val preCtx = PartyPlan.partyCtx()          // 2 个 RPC：持有池 + 遗物技能值（大地图就能读）
                // ★按需补 ids：预计算也要 questId（判断是不是 Boss 格）⇒ 分派读里没有，这里补一次
                fillIds(st)
                preTeamJob = teamScope.async {
                    // ★2026-11-19：把失败次数一起给出去 —— 失败重编时预计算就直接算"该用的那一队"
                    val _nfPre = nodeFailed[nodeKey(st)] ?: 0
                    PartyPlan.precompute(preCtx, st, _nfPre > 0, _nfPre)
                }
            }
            // ★2026-11-13（用户）：战斗节点采样（普通战 / EX 战，**不含 Boss**）——{节点编号, 关卡(敌人组)编号}
            //   Boss 也走这个预览页（`panelTypeName=BOSS`）⇒ 必须显式排除。
            runCatching {
                val ptn = jStr(st, "panelTypeName")
                if (!ptn.equals("BOSS", true)) {
                    SampleLog.battle(appCtx, jStr(st, "blockId"), jStr(st, "questId"), ptn.equals("HARD", true))
                }
            }
            // ★2026-11-20：预览页「开打」改直调（qKey=`QDStart|UIButton_Unit`，LAB_PATHS 现成）。
            //   ★★关键：直调是真 `onClick`，而那颗坐标在编组页里正好是「战斗开始」——一旦预览页
            //   被编组页盖住还直调，就会用错队伍开战。所以原来的**点前复查照搬进 pressFn**：
            //   复查不过回 null（这拍不点、不消耗重试），过了才直调；失败退回坐标点击。
            //   pressMax 默认 1：直调只投一次，之后只探（不重复触发进编组）。
            val qWhat = "预览·开打（进编组，questId=${jStr(st, "questId")}）"
            tapAndCheck(
                input, pFinal.first, pFinal.second, qWhat,
                pressFn = {
                    // ① 顶框已不是预览页 ⇒ 这拍不点
                    val t = runCatching { AgentApi.labTop(800L) }.getOrNull() ?: ""
                    if (!t.startsWith("DialogQuestDetail")) {
                        if (tries.getOrPut("QD_PRE_TOP", { 0 }) == 0) {
                            tries["QD_PRE_TOP"] = 1
                            AppState.addLog("… 点前复查：顶框=「$t」不是预览页 → 这拍不点「$BATTLE_START_BTN」")
                        }
                        null
                    } else {
                        // ② 编组页已在屏 / 读不到 ⇒ 都保守不点；只有明确 false 才直调
                        val present = runCatching { AgentApi.labPartyPresent(1_000L) }.getOrNull()
                        if (present != false) {
                            if (tries.getOrPut("QD_PRE_PARTY", { 0 }) == 0) {
                                tries["QD_PRE_PARTY"] = 1
                                val why = if (present == true) "编组页已在屏" else "编组页状态读不到（保守）"
                                AppState.addLog("… 点前复查：$why → 这拍不点「$BATTLE_START_BTN」")
                            }
                            null
                        } else {
                            pathPressOrTap(input, qKey, pFinal.first, pFinal.second, qWhat)
                        }
                    }
                },
                from = setOf("DialogQuestDetail"),
                retries = 8,
                // ★2026-11-17（用户）：进编组页的过场约 400~600ms，原来 probe 未传（只探 1 次、等 150ms）
                //   ⇒ 页面还没切完就判"没生效"，害得点两下。改成探 4 次（覆盖 4×probeMs，默认约 600ms），
                //   页面早切完就早停、不死等。
                probe = 4,
                // ★「挑战」的语义**唯一**：就是进编组页（用户 2026-11-07："点挑战进 @battle 是有问题的吧"）。
                //   之前这里加过 "@battle"（当时误以为"Boss 点挑战会直接进战斗"）⇒ 害处有两个：
                //     ① 阶段2 会把"没进编组页"也判成成功 ⇒ 掩盖问题；
                //     ② 阶段2 里只要 expect 带 "@battle" 就会 continue 掉窗口名检查
                //        ⇒ "目标是进编组页"永远等不到 ⇒ 白等 6 秒（实测 19:44:06~19:44:12）。
                //   真正"点完挑战却已经在战斗"的情况（编组页点完最后一队的「决定」就已开打，
                //   预览页没销毁还挂在树上）由 [bossOpenedAt] 挡住 —— **不去点那个残留对话框**才是正解。
                expect = setOf("DialogCommonPartyEdit"),
                // ★兜底：编组页一般 1 秒内就位，等不到就交回主循环（它会继续处理编组页），别卡 6 秒。
                expectTimeoutMs = 3_000L,
            )
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

    /**
     * ★这个节点是不是 **Boss 页**（2026-11-07）。
     *
     * 用途：Boss 页的队伍是**上一关继承来的**，要把 2/3 队腾空 ⇒ 需要读编组页顶部的
     *   「队伍1/2/3」页签坐标（`labPartyTabs`，首读要遍历整棵子树 ~900ms）。
     *   **普通战完全用不到页签** ⇒ 一律不读（省掉那 ~900ms）。
     */
    private fun isBossPage(st: JSONObject): Boolean =
        st.optInt("panelType") == 8 || st.optInt("statusType") == 6

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
        val (win, fail) = battleResultBtns(wide = true, deep = true)
        // ★失败页也抓一次（与胜利页共用"一局一次"的标志）：这是同一段链路的另一条分支，
        //   而且失败页的按钮名/位置与胜利页不同，正需要对上偏移。
        //   ★只在 [AUTO_RESULT_DUMP] 打开时才抓（默认关，见那里的说明）。
        if (AUTO_RESULT_DUMP && fail != null && battleDumps < BATTLE_DUMP_MAX) {
            battleDumps++
            dumpBattleResult(jStr(st, "pageTop"))
        }
        if (fail == null) {
            // ★★2026-11-19（用户）：**失败页也可能只显示「下一步」**（既不是「重新挑战」，也不是胜利）。
            //   这时 battleResultBtns 会按**标签**把它归到 win ⇒ fail 为空、win 非空。
            //   行为等同胜利（点完会弹一个单按钮小弹窗，再正常进三选一/遗物），
            //   但**不记失败、也不记胜利**，日志单独标出来。
            if (win != null) {
                if (!failNextSaid) {
                    failNextSaid = true
                    AppState.addLog(
                        "战斗失败（页面显示「${win.optString("text")}」）→ 点它继续（不记失败）",
                    )
                }
                val left = tapAndCheck(
                    input, win.optInt("x"), win.optInt("y"), "下一步（失败页）",
                    // ★2026-11-19（用户）：与另两条**同构** —— 点前现读坐标。
                    //   agent 现在会把失败页的「下一步」按标签判成 `failnext`，固定路径能直接命中；
                    //   某一拍读不到（过渡态 offscreen）就这拍不点、下一拍再取，不会用过期坐标硬点。
                    pressFn = oncePerResultPage("下一步（失败页）") {
                        battleResultPressOrTap(input, wantWin = true, what = "下一步（失败页）")
                    },
                    pressMax = RESULT_PRESS_MAX,
                    directFast = false,   // ★结算页 pressFn 直调失败会退坐标，保留慢节奏防 lmkd 连点事故
                    from = RESULT_FROM,
                    probe = 8,
                    retries = 25,
                    // ★★2026-11-20（用户定口径）：**两级 expect 的第一级** —— 失败页 except **小窗**
                    //   （星辉吐息改判的「遗物效果结果」/收到角色·道具收据，都是 `DialogCommonSmall`）；
                    //   第二级（小窗 except 三选一）在 [dismissFailNextReceipt] 里走。
                    expect = setOf(PAGE_SMALL),
                )
                if (left) {
                    // ★★2026-11-19（用户）：第三条路比另两条多一份**单按钮小弹窗**
                    //   （`DialogCommonSmall` + `OneButtonWhite`）：
                    //   下一步 → 小弹窗 → 三选一。这里主动点掉它（判据=**框开始消失**，同商店那套），
                    //   再等三选一出现；不让主循环的通用白名单兜（那样日志里看不出是谁点的）。
                    dismissFailNextReceipt(input)
                }
                battleProbeAt = SystemClock.elapsedRealtime() + 2_500
                return
            }
            AppState.addLog("… 战斗失败页：还没读到「重新挑战」按钮 → 等它出现（不点别的）")
            return
        }
        val k = nodeKey(st)
        val n = (nodeFailed[k] ?: 0) + 1
        nodeFailed[k] = n
        // ★这一场打完了 ⇒ 清掉「Boss 已开打」标记（否则重挑战回到预览页会被跳过）
        bossOpenedAt.remove(k)
        AppState.addLog("⚠ 战斗失败 $n/3（节点 ${jStr(st, "blockId")}；「${fail.optString("text")}」）")
        if (n >= 3) {
            when (failStrategy) {
                RerollSettings.FAIL_RETREAT -> {
                    AppState.addLog("[✗] 同一节点连续失败 3 次 → 按策略「撤退重跑」：本局作废，自动开下一局")
                    MazeRetreat.request(input, appCtx, MazeRetreat.Reason.FAIL_3_TIMES, "节点 ${jStr(st, "blockId")}")
                    return
                }
                RerollSettings.FAIL_WAIT -> {
                    manualTeamNodes.add(k)
                    AppState.addLog(
                        "[手动] 同一节点已失败 $n 次 → 回编队页后**不自动组队/不自动开战**，等你操作" +
                            "（节点 ${jStr(st, "blockId")}）",
                    )
                    tapAndCheck(
                        input, fail.optInt("x"), fail.optInt("y"), "重新挑战（回编队页）",
                        pressFn = { battleResultPressOrTap(input, wantWin = false, what = "重新挑战") },
                        pressMax = RESULT_PRESS_MAX,
                        directFast = false,   // ★结算页 pressFn 直调失败会退坐标，保留慢节奏防 lmkd 连点事故
                        from = RESULT_FROM,
                        retries = 12,
                        expect = setOf(PAGE_QUEST_DETAIL),
                    )
                    delay(1_500)
                    return
                }
                else -> {
                    AppState.addLog("[✗] 同一节点连续失败 3 次 → 停手（PC note_battle_failed 同款）")
                    stopReason = "节点连续失败 3 次"
                    stopRequested = true
                    return
                }
            }
        }
        tapAndCheck(
            input, fail.optInt("x"), fail.optInt("y"), "重新挑战",
            pressFn = { battleResultPressOrTap(input, wantWin = false, what = "重新挑战") },
            pressMax = RESULT_PRESS_MAX,
            directFast = false,   // ★结算页 pressFn 内部会退坐标，保留慢节奏防 lmkd 连点事故
            from = RESULT_FROM,
            // ★settle 用默认 1×probeMs；连点由 probe=8（≈1.2s 窗口）兜住
            probe = 8,
            retries = 12,
            expect = setOf(PAGE_QUEST_DETAIL),
        )
        delay(1_500)
    }

    /**
     * ★★2026-11-20（用户定口径）：**"失败-下一步"两级 expect 的第二级** —— 小窗（`DialogCommonSmall` +
     * `OneButtonWhite`，星辉吐息改判的「遗物效果结果」/收到角色·道具的收据）→ expect **三选一**。
     *
     * 时序（2026-11-20 探测实测）：结算页「下一步」直调 → 「遗物效果结果」小窗 → 直调「关闭」
     * → `DialogLabyrinthSelection` 三选一。上一级 [tapAndCheck] 的 expect 已是 `PAGE_SMALL`，
     * 进来时小窗多半已在；也可能根本没弹、直接就到三选一（那样这里直接返回）。
     */
    private suspend fun dismissFailNextReceipt(input: InputBackend) {
        // ① 确认小窗在场（没弹窗、已到三选一 ⇒ 直接返回）
        val t0 = SystemClock.elapsedRealtime()
        var small = false
        while (SystemClock.elapsedRealtime() - t0 < 3_000L) {
            val top = runCatching { AgentApi.labTop() }.getOrNull().orEmpty()
            if (top.startsWith(PAGE_LAB_SELECTION)) return   // 没弹窗，已经到三选一
            if (top.startsWith(PAGE_SMALL)) { small = true; break }
            delay(150)
        }
        if (!small) return
        // ② 小窗直调「关闭」→ expect **三选一**（两级 expect 的第二级）。
        //    ★直调只投一次（收据类弹窗重复点无意义）；定位不到（框已在收起）跳拍重试，
        //      一直不行就交回主循环白名单兜底。
        tapAndCheck(
            input, 0, 0, "小窗「关闭」→三选一",
            pressFn = {
                val pr = runCatching { AgentApi.pressNode("^OneButtonWhite$", "dialog") }.getOrNull()
                if (pr != null && pr.optBoolean("ok")) true else null
            },
            from = setOf(PAGE_SMALL),
            expect = setOf(PAGE_LAB_SELECTION),
            probe = 8,
            retries = 12,
            pressMax = 1,
            directFast = true,      // 纯直调、无点穿风险 ⇒ 加速档
        )
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
        // ★★★2026-11-21（用户定口径"每个直调只调一次" + 用户指路）：改用 **tapAndCheck 链**
        //   （与"重新挑战/小窗关闭"同一模型），替代手搓的标记+等待窗口。
        //   50801 迷宫结算两屏：①「下一步」= PartsLabyrinthResultItemView.onClickNextButton、
        //   ②「关闭」= PartsLabyrinthResultScoreView.onClickCloseButton。
        //   · "只调一次"由 TapCheck 的 **pressMax=1** 内置保证：投一次后纯探测、绝不重投；
        //     重投额度只在**换屏**（which 变了 = 新屏，投它自己的那一次）时刷新。
        //   · ①→② 是**同壳换屏**（top 不变）⇒ 窗口名 expect 判不出 ⇒ 用 probeState 一次快照判
        //     （left = 读链 which 变成 "close"，与 [labResultSnapshot] 同源）。
        //   · 屏判定以读链为准；agent cs127 也改成同源判屏（+0x248/+0x250 部件激活态），
        //     不再认错屏（旧扫描只认"节点 active"，②屏上①屏节点仍 active ⇒ 认成「下一步」白投）。
        //   · 直调投出 [LAB_RESULT_DIRECT_WAIT_S]s 没推进 → pressFn 内退**一次**坐标兜底（[labResultCoord]）；
        //     直调+坐标都投过仍没推进 → [LAB_RESULT_BOTH_SPENT_S]s 后停手交人工（见 [labResultPressFn]）。
        val wantScr = jStr(r, "which").ifEmpty { "next" }
        // ★★2026-11-22（用户）：**在 1 屏压一次就够** —— 滚分协程是在 2 屏 `SetData()` 时才启动、
        //   启动那一刻才读时长；1→2 屏之间有几秒，趁这时把时长压到 0.05，协程启动就读到 0.05。
        //   （结算页第一次出现必然是 1 屏 which="next"，所以这里压 = 1 屏压；每局只压一次。）
        if (labSpeedupAtResult == 0L) {
            labSpeedupAtResult = SystemClock.elapsedRealtime()
            val sp = runCatching { AgentApi.labSpeedup() }.getOrNull()
            if (sp?.optBoolean("ok") == true) {
                val n = sp.optJSONArray("params")?.length() ?: 0
                AppState.addLog("… 结算①屏压演出参数（$n 项 → 0.05s，赶在滚分协程启动前）")
            }
        }
        if (wantScr == "close") {
            // ★②屏处理前：分数还没读到就先用内存补 + 截图留证（2026-10-23 / 2026-11-10，只做一次）
            if (tries.getOrPut("$tag|shot", { 0 }) == 0) {
                tries["$tag|shot"] = 1
                maybeFillScoreFromLtd("关闭前")
                // ★★2026-11-22（用户"滚分还是没加速到"）：**等分数文本稳定再截图** ——
                //   滚分协程读的参数不好保证生效（`LabyrinthDefineData` 实例/缓存），干脆自适应：
                //   文本连续两次相同 = 滚完了 ⇒ 截图。滚得快几乎不等，最慢 2s 兜底。
                var prev = ""
                val w0 = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - w0 < 2_000L) {
                    val rr = runCatching { AgentApi.labResult() }.getOrNull()
                    val tx = rr?.optJSONObject("text")?.optString("score").orEmpty()
                    if (tx.isNotEmpty() && tx == prev) break
                    if (tx.isNotEmpty()) prev = tx
                    delay(120)
                }
                if (prev.isNotEmpty()) {
                    AppState.addLog("… 截图前等分数稳定：$prev（${SystemClock.elapsedRealtime() - w0}ms）")
                }
                // 文件名优先界面文本，其次内存口径总分（否则是 score_x）
                captureScoreShot(
                    prev.ifEmpty { score.ifEmpty { lastScore["总分"]?.toString().orEmpty() } },
                )
            }
            // ②「关闭」→ 出壳（演出壳/白名单弹窗由通用分支接管）⇒ expect 空 = 离开结算壳就算完成
            tapAndCheck(
                input, 0, 0, "结算②「关闭」",
                from = setOf(PAGE_LAB_RESULT),
                pressFn = labResultPressFn(input, tag, tries, "close", score, r),
                pressMax = 1,
                directFast = true,
                probe = 10,
                retries = LAB_RESULT_PROBE_ROUNDS,
            )
        } else {
            // ①「下一步」→ ②屏（同壳换屏，probeState 判 which）
            tapAndCheck(
                input, 0, 0, "结算①「下一步」",
                from = setOf(PAGE_LAB_RESULT),
                pressFn = labResultPressFn(input, tag, tries, "next", score, r),
                pressMax = 1,
                directFast = true,
                probe = 10,
                retries = LAB_RESULT_PROBE_ROUNDS,
                probeState = {
                    val rr = runCatching { AgentApi.labResult() }.getOrNull()
                    TapCheck.Probe(
                        top = if (rr?.optBoolean("ok") == true) PAGE_LAB_RESULT else "",
                        left = rr?.optBoolean("ok") == true && jStr(rr, "which") == "close",
                        diag = "src=${rr?.optString("src")?.ifEmpty { "?" }}",
                    )
                },
            )
        }
    }

    /**
     * ★cs127：结算页 pressFn 工厂（[tapAndCheck] 链用）。一屏（`scr` = "next"/"close"）的生命周期：
     *   ① 未投过 → 投直调（`AgentApi.battleResultPress`，agent cs127 自己同源判屏）；投出 = true。
     *     没投出去（agent 报错/超时没回包）也**算已投**：不重投，等窗口走完退坐标。
     *   ② 直调已投、[LAB_RESULT_DIRECT_WAIT_S]s 内 → null（纯探测，tapAndCheck 的 probe 在跑）。
     *   ③ 窗口走完 → 坐标兜底**一次**（[labResultCoord]）→ tap（「关闭」先补分数 + 截图）。
     *   ④ 直调+坐标都投过 → 再等 [LAB_RESULT_BOTH_SPENT_S]s 没推进就停手交人工；之后纯探测（null）。
     *   ★已投标记存 [tries]（跨轮/跨 tapAndCheck 调用持久）——TapCheck 每次重入 pressMax 会重置，
     *     "只调一次"必须靠自己的标记，不能靠它的额度。
     */
    private fun labResultPressFn(
        input: InputBackend,
        tag: String,
        tries: MutableMap<String, Int>,
        scr: String,
        score: String,
        r: JSONObject,
    ): suspend () -> Boolean? {
        val dirKey = "$tag|dir_$scr"
        val coordKey = "$tag|coord_$scr"
        return {
            val nowS = (SystemClock.elapsedRealtime() / 1000L).toInt()
            if (!tries.containsKey(dirKey)) {
                tries[dirKey] = 1
                tries["$tag|dirat_$scr"] = nowS
                val pr = runCatching { AgentApi.battleResultPress() }.getOrNull()
                if (pr?.optBoolean("ok") == true) {
                    tries["$tag|rtaps"] = (tries["$tag|rtaps"] ?: 0) + 1
                    AppState.addLog(
                        "… 结算页「${jStr(pr, "label")?.ifEmpty { "?" } ?: "?"}」" +
                            "（直调 ${jStr(pr, "compCls")?.ifEmpty { "CustomUIButton" } ?: "CustomUIButton"}）",
                    )
                    true
                } else {
                    // 没投出去（agent 报错/超时没回包）："直调后等推进"的窗口没有意义（压根没投）
                    // ⇒ 把已投时刻回拨到窗口之外，下一轮 pressFn 直接走坐标兜底，不再干等 3s。
                    // （agent cs128 给迷宫结算壳加了优先快查，首查 ~3.5s 的慢调用应基本消失）
                    tries["$tag|dirat_$scr"] = nowS - LAB_RESULT_DIRECT_WAIT_S
                    null
                }
            } else if (!tries.containsKey(coordKey)) {
                val at = tries["$tag|dirat_$scr"] ?: 0
                if (nowS - at < LAB_RESULT_DIRECT_WAIT_S) {
                    // 直调窗口内：纯探测。★null 会吃 skipMax(6)×30ms 的跳拍额度 ⇒ 先垫 400ms
                    //   让一次 tapAndCheck 调用的 null 轮覆盖 ~2.5s，少几次"返回 false→主循环重进"的空转
                    delay(400)
                    null
                } else {
                    // 窗口走完 ⇒ 坐标兜底**一次**
                    tries[coordKey] = 1
                    tries["$tag|coordat_$scr"] = nowS
                    AppState.addLog(
                        "… 结算页「${if (scr == "close") "关闭" else "下一步"}」直调已投、${nowS - at}s 没推进 → 坐标兜底（一次）",
                    )
                    val c = labResultCoord(r, scr, tag, tries)
                    if (c == null) {
                        if (tries.getOrPut("$tag|cnb_$scr", { 0 }) == 0) {
                            AppState.addLog("… 结算页坐标兜底也没读到屏内坐标（$scr）→ 下一轮再看")
                        }
                        null
                    } else {
                        val (lab, pxy) = c
                        if (lab == "关闭") maybeFillScoreFromLtd("关闭前")
                        if (lab == "关闭") {
                            captureScoreShot(score.ifEmpty { lastScore["总分"]?.toString().orEmpty() })
                        }
                        tries["$tag|rtaps"] = (tries["$tag|rtaps"] ?: 0) + 1
                        AppState.addLog("迷宫结算：点了「$lab」(${pxy.first},${pxy.second})")
                        tap(input, pxy.first, pxy.second, "结算·$lab", learnKey = "settle|result")
                        true
                    }
                }
            } else {
                // 直调+坐标都投过：再等 BOTH_SPENT 就停手（防永久空转）
                val at = tries["$tag|coordat_$scr"] ?: 0
                if (nowS - at >= LAB_RESULT_BOTH_SPENT_S &&
                    tries.getOrPut("$tag|bothstop_$scr", { 0 }) == 0
                ) {
                    AppState.addLog("[✗] 结算页直调+坐标都投过、${nowS - at}s 仍没推进 → 停手（这一页请人工点掉）")
                    stopRequested = true
                }
                delay(400)                     // 同上：垫掉 null 跳拍额度，减少外层空转
                null
            }
        }
    }

    /**
     * ★cs127：结算页坐标兜底定位（[labResultPressFn] 的坐标档用，每屏只投一次）。
     * 链条（2026-10-16 定）：① `cands` 里 which 那颗、`on=true`、屏内（最准，避免点到上一屏那颗）
     * → ② `cands` 任何一颗只要屏内（`on` 只作优选、不作闸）→ ③ 节点树按名字找（屏内、下半屏）。
     * ⚠「撤退（无报酬）」「返回（有报酬）」不在这三个来源里 ⇒ 天然点不到。
     * ★③不再需要旧的轮数闸门：坐标兜底只在"直调窗口走完"（≥[LAB_RESULT_DIRECT_WAIT_S]s）后才跑，
     *   入场动画早已结束。
     */
    private suspend fun labResultCoord(
        r: JSONObject,
        want: String,
        tag: String,
        tries: MutableMap<String, Int>,
    ): Pair<String, Pair<Int, Int>>? {
        val cands = r.optJSONObject("cands")
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
        if (xy == null) {                                    // ③ 兜底：节点树按名字找
            val half = (scrH * 0.5).toInt()
            for ((re, lab) in listOf(LAB_RESULT_NEXT_RE to "下一步", LAB_RESULT_CLOSE_RE to "关闭")) {
                val lst = AgentApi.findTextIn(re, "top", 6, half) ?: continue
                for (i in 0 until lst.length()) {
                    val b = lst.opt(i) as? JSONObject ?: continue
                    val x = (b.opt("x") as? Number)?.toInt() ?: continue
                    val y = (b.opt("y") as? Number)?.toInt() ?: continue
                    if (!inBox(x, y)) continue
                    label = lab
                    xy = x to y
                    if (tries.getOrPut("$tag|rb2", { 0 }) == 0) {
                        AppState.addLog("… 结算页对象字段拿不到按钮（$bad）→ 节点树按名字找到「$label」@$x,$y")
                    }
                    break
                }
                if (xy != null) break
            }
        }
        return xy?.let { label to it }
    }

    /**
     * ★2026-11-10（用户要求）：结算**分数屏**留证截图。
     *
     * 时机：**按钮已定位、即将按下**的那一刻（调用点在 [handleLabResult] 的 tap 之前）——
     * 不是刚进页面：那时 CountUp 可能还在滚、按钮也还没落位。
     * 范围：只对 ②屏（CLEAR + 总分，按钮是「关闭」）；①屏「加入的角色」没有分数，不截。
     * 存放：**模拟器的系统截图目录** `/sdcard/Pictures/Screenshots/` —— 雷电等模拟器会把它映射到
     *   电脑的共享文件夹（和系统截图摆在一起），不用再从 App 私有目录 `adb pull`。
     *   文件名 `score_<总分>_<时间>.png`；用 root 写 + `stat` 校验，绕开 App 在公共存储上的权限差异。
     * 失败只记一行，**不影响点按流程**。
     */
    private suspend fun captureScoreShot(score: String) {
        val safeScore = score.filter { it.isDigit() }.ifEmpty { "x" }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        // ★2026-11-22（用户）：MuMu 的**共享图片目录**是 /sdcard/Screenshots（不是 Pictures/Screenshots），
        //   存这里才会出现在电脑的共享文件夹里。
        val pubDir = "/sdcard/Screenshots"
        val privDir = "/sdcard/Android/data/com.lmj.labyrinthauto/files/shots"
        val name = "score_${safeScore}_$ts.png"
        val pub = "$pubDir/$name"
        val priv = "$privDir/$name"
        val t0 = SystemClock.elapsedRealtime()
        // ★2026-11-22（用户"根本找不到图，加检测"）：一条命令做完 建目录 → 截图 → 复制到 App 私有目录
        //   → `ls -l` 报两份文件的真实详情（存在与否/大小/权限/所有者）。screencap 的 stderr 也一并带回。
        val r = RootShell.exec(
            "mkdir -p $pubDir $privDir; " +
                "screencap -p $pub 2>&1; rc=\$?; " +
                "cp -f $pub $priv 2>&1; " +
                "echo \"---rc=\$rc---\"; " +
                "ls -l $pub $priv 2>&1",
            10_000L,
        )
        val ms = SystemClock.elapsedRealtime() - t0
        val lines = r.out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val rcLine = lines.firstOrNull { it.startsWith("---rc=") } ?: ""
        val lsLines = lines.filter { it.contains(".png") }
        val pubSize = lsLines.firstOrNull { it.contains("$pubDir/") }
            ?.split(Regex("\\s+"))?.getOrNull(4)?.toLongOrNull() ?: 0L
        val privSize = lsLines.firstOrNull { it.contains("$privDir/") }
            ?.split(Regex("\\s+"))?.getOrNull(4)?.toLongOrNull() ?: 0L
        if (pubSize > 0L) {
            AppState.addLog(
                "迷宫结算：分数截图已存 $pub（${pubSize / 1024}KB，${ms}ms）" +
                    "｜私有副本 ${privSize / 1024}KB",
            )
        } else {
            AppState.addLog("[!] 分数截图失败（exit=${r.exit} $rcLine，${ms}ms）")
        }
        // 诊断：把 ls 的真实结果原样打出来（用户"找不到图"时据此判断到底写没写、写哪了）
        for (ln in lsLines.take(4)) AppState.addLog("[截图诊断] $ln")
        if (lsLines.isEmpty()) AppState.addLog("[截图诊断] ls 无 png 输出，原始=${lines.takeLast(3).joinToString(" | ")}")
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
     * ★2026-11-17（用户）：结算按钮**固定路径 + 每次现读**的坐标来源（供 tapAndCheck 的 xyAt 复用）。
     *
     * 为什么不用缓存坐标 / 不做屏幕探索：
     *  · 缓存坐标会过时、反过来变成干扰项；屏幕探索（findTextIn 半屏扫）会捞到同名干扰节点。
     *  · 这里走 agent 的 `battleResultBtn`：记住结算按钮的 prefab 节点**路径**（同 session 指针稳定），
     *    每次现读坐标 + 配对**标签文字**校验，零遍历（约 3 次调用），结构变了自动重学。
     *    ⇒ 路径固定、读取轻量、不依赖会过时的坐标值。
     *
     * @param wantWin true=只要能推进胜利页的「下一步/次へ/Next」；false=只要能推进失败页的「挑战/Retry」
     * @return 屏内坐标；null = 这一拍读不到/标签对不上（TapCheck 会当"这拍不点"重试，绝不误点）
     */
    /** 从 `{x,y}` 取屏内坐标（越界/缺字段 → null） */
    private fun btnXYOf(o: JSONObject?): Pair<Int, Int>? {
        val x = (o?.opt("x") as? Number)?.toInt() ?: return null
        val y = (o?.opt("y") as? Number)?.toInt() ?: return null
        if (x !in 0 until scrW || y !in 0 until scrH) return null
        return x to y
    }

    /**
     * **固定路径现读**结算按钮坐标（点击前复查）。
     *
     * ★2026-11-18（用户）：**点击和检测必须用同一条链路** —— 否则「检测靠 A 命中、点前复查用 B
     *   读不到」就会一次都不点、卡死在结算页。这里和探测一样调 [battleResultPick]（=固定路径），
     *   四类结算页（普通/EX/BOSS 胜负）都实测命中，4~16ms、零扫描。
     *   取不到就返回 null（这拍不点、TapCheck 继续探），**不用任何缓存坐标兜底**。
     */
    private suspend fun battleResultXY(wantWin: Boolean): Pair<Int, Int>? {
        // ★★2026-11-18（用户）：**点击和检测必须用同一条链路** —— 否则「检测靠 A 命中、点前复查用 B
        //   读不到」就会一次都不点、卡死在结算页（11:19 胜利页实测：检测走"缓存/学习"命中 NextButton，
        //   而点前只用字段读 ⇒ 读不到 ⇒ 卡 90s+）。
        val bot = (scrH * 0.85).toInt()
        val (w, f) = battleResultPick(bot) ?: return null
        return btnXYOf(if (wantWin) w else f)
    }

    /**
     * ★★2026-11-22（用户口径）：**同一结算页只直调一次，之后纯等** —— 包在 `pressFn` 外面做页签名去重。
     *
     * 背景：胜利结算是**一壳两页**（first `NextButton` → later `NextButtonLarge`，同 `DialogQuestResultWin`
     * 壳）⇒ `TapCheck` 用窗口名判不出换页，历史上靠 `pressMax=12` 连投推进，实测每场都出现
     * `直调 2 轮`——直调没有"点不到"，重复投就是**真的重复执行 onClick**（有副作用的页危险）。
     *
     * 判据用 agent 现成的 `battleResultPath(noScan)`（固定路径、4~16ms、零扫描）：
     *   · first 页命中 `RES_WIN_SUB` ⇒ stage 空 / node 空；
     *   · later 页命中 `RES_WIN_LATER` ⇒ stage="later" / node 带 `NextButtonLarge`。
     * 签名变了才算"换页"、放行下一次；同一签名重复出现一律返回 null（这一拍只探测、不投递）。
     *
     * @return 包装后的 `pressFn`：读不到签名 / 同页已投过 ⇒ null（不消耗 retries）；
     *         真投递成功才记下签名。
     */
    private fun oncePerResultPage(what: String, inner: suspend () -> Boolean?): suspend () -> Boolean? {
        val fired = mutableSetOf<String>()
        return {
            val pg = runCatching {
                AgentApi.obj("battleResultPath", listOf<Any?>(true), AgentApi.T_FAST)
            }.getOrNull()
            val key = if (pg?.optBoolean("ok") == true) {
                (pg.optString("stage").ifEmpty { "first" }) + "|" +
                    pg.optString("kind") + "|" + pg.optString("node")
            } else ""
            if (key.isEmpty()) {
                null                                  // 过渡态读不到页 ⇒ 这一拍不投
            } else if (key in fired) {
                null                                  // ★同页已经投过 ⇒ 只探测，绝不重复 Execute
            } else {
                val r = inner()
                if (r == true) {
                    fired += key
                    AppState.addLog("$what：直调投递（页=$key → 已记 ${fired.size} 页，此后同页不再投）")
                }
                r
            }
        }
    }

    /**
     * ★★**结算按钮直调**（2026-11-19 并入 feat/direct-trigger）：不投屏幕坐标，直接触发按钮的
     * `onClick` 委托链。直调与检测走**同一条固定层级路径**，所以"检测到的"和"点下去的"必是同一颗。
     *
     * ★收益：结算页要连点十几下，坐标点击每次 ~110ms；历史上 1.4s 连点 7 下把系统 input 拖到
     *   无响应、随后 lmkd 杀进程。直调是进程内调用、10ms 级，且不受入场动画/遮挡影响。
     *
     * ★四类结算页（普通胜/负、BOSS/EX 胜负）直调**全部真生效**。
     *   普通"胜利页直调无效"是**旧误判**（cs109）：当时从 `DialogQuestResultWin+0x108` 字段槽
     *   拿按钮，命中的是上一场残留壳的非激活实例；2026-11-20 探测工具实测：按"只认激活层级"
     *   的扫描链拿到同一方法名（`PartsQuestResultFormer.OnClickNext`）的按钮，直调一次即推进
     *   （NextButton → NextButtonLarge → 出结算页，viewId 29→264）。
     *
     * @return `true` = 已发出；`null` = 这一刻不该点（页面在切 / 与期望胜负不符）⇒ 跳过重试
     */
    private suspend fun battleResultPressOrTap(
        input: InputBackend,
        wantWin: Boolean,
        what: String,
    ): Boolean? {
        val pr = runCatching { AgentApi.battleResultPress() }.getOrNull()
        // ★`pressTwoPhase` 返回轮询后的最终结果（ok/kind/via/err），没有 queued
        if (pr?.optBoolean("ok") == true) {
            val kind = pr.optString("kind")
            // 直调命中的页与调用方期望不符（要胜利页却读到失败页）⇒ 这一拍不点，交回外层重新认页
            if (wantWin && kind == "fail") {
                AppState.addLog("$what：直调读到失败页（kind=fail）→ 与期望不符，本轮不点")
                return null
            }
            if (!wantWin && kind != "fail" && kind != "failnext") {
                // ★failnext（cs123，2026-11-20 公会版实测）：失败壳上的「下一步」=「星辉吐息」遗物
                //   把败北改判成胜利的确认按钮，onClick 同 onClickNextButton，直调后弹
                //   「遗物效果结果」通知 → 关闭 → 进胜利流程三选一。按它就对了。
                AppState.addLog("$what：直调读到胜利页（kind=$kind）→ 与期望不符，本轮不点")
                return null
            }
            return true
        }
        val err = pr?.optString("err").orEmpty().ifEmpty { "无回包" }
        // 直调不可用 ⇒ 退回"固定路径现读坐标再点"（同一套定位，只是投坐标）
        val xy = battleResultXY(wantWin) ?: run {
            AppState.addLog("$what：直调失败（$err），且固定路径也读不到坐标 → 本轮不点")
            return null
        }
        AppState.addLog("$what：直调失败（$err）→ 退回坐标点击")
        return tap(input, xy.first, xy.second, what)
    }

    /**
     * 结算按钮的**唯一读取链路**（检测与点前现读共用；2026-11-18 用户要求）。
     *
     * ★2026-11-18（用户）：四类结算页（普通胜/负、EX、BOSS 胜/负）实测固定路径全命中后，
     *   删掉了原 ②缓存档（battleResultBtn）与 ③字段读档（battleResultMem）—— 一整轮 20+ 次结算
     *   零回落，两档已无作用。现在只剩 [battleResultPathPick] 固定层级路径（实测 4~16ms）。
     *   读不到时由 [battleResultBtns] 的**树遍历**兜底（窄名单→宽名单），不动。
     *
     * @return (win, fail)；两者都 null = 这一刻读不到（调用方这拍不点、继续探）
     */
    private suspend fun battleResultPick(bot: Int, allowScan: Boolean = true): Pair<JSONObject?, JSONObject?>? =
        battleResultPathPick(bot, allowScan)

    /**
     * ★★2026-11-18（用户要求提为**主路径**）：失败页「重新挑战」的**固定层级路径**读数。
     *
     * `DialogQuestFailed → Main[0] → contents[0] → Anchor[4] → UIButton_Next[2]`（实测取证）
     * ⇒ 约 50 次 il2cpp invoke ≈ 1~3ms，比字段读（先扫 72 槽找 Parts、再扫 60 槽找按钮）省一个数量级，
     * 更比树遍历学习省两个数量级（且那条路对失败页根本学不到）。
     *
     * @return `(null, {x,y,text})` = 命中「重新挑战」；`null` = 这条路径不通（交给后面的档）
     */
    private suspend fun battleResultPathPick(
        bot: Int,
        allowScan: Boolean = true,
    ): Pair<JSONObject?, JSONObject?>? {
        // ★2026-11-18：带上 errOut —— `obj()` 在"没连上/超时/agent 报错"时只返回 null，
        //   不传 errOut 就完全静默（这轮日志里一条"固定路径"都没有，就是被这么吞掉的）。
        var callErr = ""
        val r = AgentApi.obj(
            "battleResultPath",
            // ★2026-11-21：allowScan=false ⇒ noScan，agent 只走固定路径、不扫子树（快速轮询用）
            if (allowScan) emptyList() else listOf<Any?>(true),
            timeoutMs = AgentApi.T_SLOW,
            errOut = { callErr = it },
        )
        if (r == null) {
            if (callErr.isNotEmpty() && callErr != lastPathErr) {
                lastPathErr = callErr
                AppState.addLog("… 固定路径调用失败：$callErr")
            }
            return null
        }
        if (r.opt("ok") != true) {
            val why = r.optString("err").orEmpty()
            if (why.isNotEmpty() && why != lastPathErr) {
                lastPathErr = why
                AppState.addLog("… 固定路径未命中（$why）→ 回落下一档")
            }
            return null
        }
        lastPathErr = ""
        val kind = r.optString("kind")
        val x = r.optInt("x")
        val y = r.optInt("y")
        val lb = r.optString("label").trim()
        // ★2026-11-18：这两道校验原来是静默 return null（日志里连"固定路径"字样都没有，
        //   固定路径命中后卡在哪完全无据可查）。补上去重诊断，把 agent 返回的原始值带出来。
        if (x !in 0 until scrW || y !in 0 until scrH || y < bot || lb.isEmpty()) {
            val why = "校验未过(kind=$kind xy=($x,$y) 屏=${scrW}x${scrH} bot=$bot label=[$lb])"
            if (why != lastPathErr) {
                lastPathErr = why
                AppState.addLog("… 固定路径未命中（$why）→ 回落下一档")
            }
            return null
        }
        val o = JSONObject().put("x", x).put("y", y).put("text", lb)
        val ms = r.optInt("ms")
        // ★★2026-11-19（用户）：**失败页也有可能出现「下一步」**（不是「重新挑战」）。
        //   固定路径是按**容器类名**定 kind（DialogQuestFailed ⇒ fail），不看标签 ⇒ 会把
        //   「下一步」当成「重新挑战」：于是被记成"失败 +1"、还期望回到预览页（DialogQuestDetail）
        //   ⇒ 到不了就重试 12 次、在错的页面上乱点。
        //   ⇒ 这里按**按钮标签**纠正一次（标签本来就已经读回来了）：
        //     标签像「下一步/次へ/Next」 ⇒ 一律当 win（推进），不记失败、不期望预览页。
        val isWinLabel = WIN_LABELS.any { lb.contains(it, ignoreCase = true) }
        val isFailLabel = FAIL_LABELS.any { lb.contains(it, ignoreCase = true) }
        val effKind = when {
            // ★2026-11-19：agent 已在失败页按标签判好 "failnext"（失败但显示「下一步」）⇒ 保留这个值，
            //   只为日志可辨；下面的返回仍把它归到 win 槽（失败页分支会单独处理，见 handleQuestFailed）。
            kind == "failnext" -> "failnext"
            isWinLabel -> "win"
            isFailLabel -> "fail"
            else -> kind            // 标签读不到/不认识 ⇒ 维持原判（后面还有树遍历兜底按标签分）
        }
        lastBtnSrc = "固定路径($effKind)@${ms}ms"
        if (!battleBtnSaid) {
            AppState.addLog("结算按钮来源：固定路径 $effKind 「$lb」@($x,$y)（${ms}ms）")
        }
        if (effKind != kind) {
            // 只在"页面类型与标签不一致"时打一行，方便对日志确认这种边界情况发生了。
            AppState.addLog("[!] 结算页类型与按钮标签不一致：页面=$kind 标签「$lb」→ 按「$effKind」处理")
        }
        return if (effKind == "fail") null to o else o to null
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
        // ★★**第一优先：固定层级路径**（[battleResultPick] → [battleResultPathPick]，实测 4~16ms）。
        //   2026-11-18 四类结算页全命中后已删掉原缓存/字段读两档，只剩这一条快路径。
        // ★**多重试几次**再降级到树遍历：结算页刚出现那一瞬按钮还没落位（首拍常 offscreen），
        //   以前当场退到树遍历（147~180ms/次；"换页瞬间做遍历"正是卡死过的形状）。
        //   现在同一轮里隔 200ms 重读几次（≈0.4s），任一次成功就用它 ✓（都失败才走下面的树）。
        // ★用**时间预算**兜住次数上限（见 [RESULT_READ_BUDGET_MS]）：第一次之后就按已用时间决定
        //   还要不要重试，避免"结算动画期连续 3 次遍历 DialogArea"把游戏主线程读死。
        val readT0 = SystemClock.elapsedRealtime()
        for (i in 0 until RESULT_READ_TRIES) {
            // ★★2026-11-18：与点前现读**同一条链路**（[battleResultPick]）——检测与点击必须一致。
            battleResultPick(bot)?.let { return it }
            if (i == RESULT_READ_TRIES - 1) break
            if (SystemClock.elapsedRealtime() - readT0 >= RESULT_READ_BUDGET_MS) break
            delay(RESULT_READ_GAP_MS)
        }
        // ★2026-11-19（用户）：固定路径全灭 ⇒ 打一次**字段读诊断**（只读，不改判定）：
        //   把每个 CustomUIButton 的「层级路径 + 配对标签 + 坐标」列出来，
        //   用于取证"失败页显示【下一步】"这类路径未知的形态（同一顶框只打一次）。
        dumpResultMemDiagOnce()
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
     * ★2026-11-19（用户）：结算页按钮的**字段读诊断**（只读，不参与胜负判定）。
     *
     * 用途：固定路径读不到时，把 `DialogQuestFailed` / `DialogQuestResultWin` / `DialogLabyrinthExResult`
     * 里每个 `CustomUIButton` 的「层级路径 + 配对标签 + 坐标」打进日志 —— 专为
     * **"失败页显示【下一步】"** 这类路径未知的形态取证；拿到路径后可直接新增固定路径常量。
     *
     * 只做一次/同顶框（[resMemDiagKey]），避免每拍都读一遍字段。
     */
    private suspend fun dumpResultMemDiagOnce() {
        val top = runCatching { AgentApi.labTop(800L) }.getOrNull() ?: return
        if (!RESULT_TOP_RE.containsMatchIn(top)) return
        val key = top.removeSuffix("(Clone)")
        if (resMemDiagKey == key) return
        resMemDiagKey = key
        val r = runCatching { AgentApi.battleResultMem() }.getOrNull() ?: run {
            AppState.addLog("… 结算按钮诊断（$key）：battleResultMem 调用失败")
            return
        }
        val cands = r.optJSONArray("cands")
        if (cands == null || cands.length() == 0) {
            AppState.addLog(
                "… 结算按钮诊断（$key）：字段读没找到 CustomUIButton" +
                    (r.optString("err").takeIf { it.isNotEmpty() }?.let { "（err=$it）" } ?: ""),
            )
            return
        }
        AppState.addLog("… 结算按钮诊断（$key）：共 ${cands.length()} 个候选（路径｜标签｜坐标）")
        for (i in 0 until minOf(cands.length(), 8)) {
            val c = cands.optJSONObject(i) ?: continue
            val lbRaw = c.opt("label")
            val lb = if (lbRaw == null || lbRaw === JSONObject.NULL) "" else lbRaw.toString().trim()
            AppState.addLog(
                "    [${i + 1}] ${c.optString("path").ifEmpty { "（未取到路径）" }}" +
                    "  「${lb.ifEmpty { "«无标签»" }}」@(${c.optInt("x")},${c.optInt("y")})",
            )
        }
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
                    "… 认出结算页（$kind）→ 静置 ${RESULT_SETTLE_MS}ms 等按钮落位，再每 ${RESULT_LIGHT_GAP_MS}ms 探一次",
                )
                // ★标定用（2026-11-07）：把战斗结果的**原始字段**记下来，跑一胜一负就能确定
                //   gameState 的枚举含义，从而不再靠"等按钮出现"判胜负。只记录，不参与判断。
                runCatching { AgentApi.labBattleResult() }.getOrNull()?.let { r ->
                    AppState.addLog(
                        "◧ 战斗结果原始值：inBattle=${r.inBattle} resultEnable=${r.resultEnable} gameState=${r.gameState}",
                    )
                }
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
                    "）→ 静置 ${RESULT_SETTLE_MS}ms 后开始找",
            )
        }
        // ★久战兜底（见 [RESULT_TOP_FALLBACK_MS]）：认不出类名也不能干等到超时停手。
        if (battleRes0 == 0L && top.isNotEmpty() && battleT0 > 0 &&
            now - battleT0 >= RESULT_TOP_FALLBACK_MS
        ) {
            battleRes0 = now
            AppState.addLog(
                "… 战斗已 ${(now - battleT0) / 1000}s、顶框=${top}（没认出结算页类名）→ 按结算处理，开始找按钮",
            )
        }
        // ══ 层 2：认出来 + 静置够了，才去找按钮 ══════════════════════════════════════════════
        var win: JSONObject? = null
        var fail: JSONObject? = null
        // ★2026-11-17 用户：静置 3000ms（避开页面切换瞬间）后，再 200ms 一探轻读取找按钮
        //   （按钮实测 3.5~4.5s 才 active ⇒ 3000ms 静置 + 10×200ms 覆盖到 ~5s）。
        val settled = battleRes0 > 0L && now - battleRes0 >= RESULT_SETTLE_MS
        val bot = (scrH * 0.85).toInt()
        // ⚠必须尊重 `battleProbeAt`：胜利页点完会把它 +2.5s（防"同一页连点"；
        //   20:22 那场 2 秒点了 4 下就是这个没生效）。
        // ★场景切换避让（2026-10-17）：刚进/出战斗的前 [SCENE_HOLD_MS] 内**不探测**——那时战斗场景
        //   还在加载，探测也可能撞上游戏自己的加载 ⇒ 三次卡死的共同形状。
        val gated = now >= sceneHoldUntil && now >= battleHoldUntil

        // ── 2a：**轻量轮询**（2026-11-17：静置 3000ms 后每 200ms 探一次，共 10 拍）──────────────
        //   ★2026-11-18（用户："固定路径应该做成主要方法"）：这里原来只调 `battleResultBtnPick`
        //   （缓存校验/学习）⇒ 胜利页缓存一命中就再也不会走固定路径，固定路径永远当不上主路径
        //   （实测胜利页连一条固定路径日志都没有，就是因为被这一档短路了）。改走统一链路
        //   [battleResultPick] = 固定路径（缓存/字段读两档已于 2026-11-18 删除）。
        //   固定路径实测 4~16ms，不重试不扫树，200ms 间隔照旧成立。
        //   实测按钮在"认出"后 3.5~4.5s 才 active ⇒ 3000ms 静置 + 200ms×10 ≈ 覆盖到 5s。
        if (settled && resultProbeN < RESULT_PROBE_FAST_MAX && gated &&
            now - battleProbeAt >= RESULT_LIGHT_GAP_MS
        ) {
            battleProbeAt = now
            // ★2026-11-21（用户）：快速轮询阶段**第一页只做固定路径、不扫子树**（noScan）——
            //   按钮未落位时固定路径返回 offscreen/node-inactive，扫子树必然也找不到，却是
            //   1.5~2s 的重活（堵住单通道 ⇒ 胜利画面卡几秒）。
            //   ★第二页（本场已认过一次按钮 battleBtnSaid）**没有固定路径、只能靠扫描** ⇒
            //     这一页要传 allowScan=true（否则第二页永远找不到）。
            val p = battleResultPick(bot, allowScan = battleBtnSaid)
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

        // ── 2b：轻量 10 次都没命中 ⇒ 升级到**全套**（固定路径 + 窄名单树遍历），1.2s 一次 ──
        //   到这里说明固定路径没读到（按钮未落位/结构变化），才降级到树遍历兜底。
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
            // ★这一场打完了 ⇒ 清掉「Boss 已开打」标记（否则重挑战回到预览页会被跳过）
            bossOpenedAt.remove(k)
            AppState.addLog("⚠ 战斗失败 $n/3（节点 ${jStr(st, "blockId")}；「${fail.optString("text")}」）")
            if (n >= 3) {
                when (failStrategy) {
                    RerollSettings.FAIL_RETREAT -> {
                        AppState.addLog("[✗] 同一节点连续失败 3 次 → 按策略「撤退重跑」：本局作废，自动开下一局")
                        MazeRetreat.request(input, appCtx, MazeRetreat.Reason.FAIL_3_TIMES, "节点 ${jStr(st, "blockId")}")
                        return true
                    }
                    RerollSettings.FAIL_WAIT -> {
                        manualTeamNodes.add(k)
                        AppState.addLog(
                            "[手动] 同一节点已失败 $n 次 → 回编队页后**不自动组队/不自动开战**，等你操作" +
                                "（节点 ${jStr(st, "blockId")}）",
                        )
                        // ★2026-11-18（用户）：转人工那一刻就要出现红色提示（不等下一轮刷新）
                        OverlayStatus.setState("等待接手：请组队并开战", attention = true)
                        tapAndCheck(
                            input, fail.optInt("x"), fail.optInt("y"), "重新挑战（回编队页）",
                            pressFn = { battleResultPressOrTap(input, wantWin = false, what = "重新挑战") },
                            pressMax = RESULT_PRESS_MAX,
                            directFast = false,   // ★结算页 pressFn 直调失败会退坐标，保留慢节奏防 lmkd 连点事故
                            from = RESULT_FROM,
                                retries = 12,
                            expect = setOf(PAGE_QUEST_DETAIL),
                        )
                        battleProbeAt = now + 2_500
                        battleT0 = 0L
                        battleSaid = 0
                        return true
                    }
                    else -> {
                        AppState.addLog("[✗] 同一节点连续失败 3 次 → 停手（PC note_battle_failed 同款）")
                        stopReason = "节点连续失败 3 次"
                        stopRequested = true
                        return true
                    }
                }
            }
            // ★2026-11-17（用户）：和胜利页对齐——tapAndCheck + 固定路径现读坐标（xyAt），
            //   出口是**预览页（敌人信息 DialogQuestDetail，编组页之前的一个页面）**。
            //   点空/动画未落位会自动重读坐标重试，不再"点一下就死等"。
            tapAndCheck(
                input, fail.optInt("x"), fail.optInt("y"), "重新挑战",
                pressFn = { battleResultPressOrTap(input, wantWin = false, what = "重新挑战") },
                pressMax = RESULT_PRESS_MAX,
                directFast = false,   // ★结算页 pressFn 内部会退坐标，保留慢节奏防 lmkd 连点事故
                from = RESULT_FROM,
                // ★settle 用默认 1×probeMs；连点由 probe=8（≈1.2s 窗口）兜住
                probe = 8,
                retries = 12,
                // 阶段2：重新挑战回到**预览页**（敌人信息展示，编组页之前那页）
                expect = setOf(PAGE_QUEST_DETAIL),
            )
            battleProbeAt = now + 2_500                   // 刚点过 → 3.5s 内别再点
            battleT0 = 0L
            battleSaid = 0
            resultFail = true
        } else if (win != null) {                             // ── 胜利页：点「下一步」──
            // ★这一场打完了 ⇒ 清掉「Boss 已开打」标记
            bossOpenedAt.remove(nodeKey(st))
            if (!battleWinSaid) {
                battleWinSaid = true
                val bl = if (battleT0 > 0) (now - battleT0) / 1000 else 0L
                AppState.addLog("战斗胜利 → 点「${win.optString("text")}」（战斗 ${bl}s，来源=${lastBtnSrc ?: "?"}）")
            }
            // ★胜利结算**有两页**（用户 2026-10-16）：①纯 WIN ②战斗奖励（金币）；**各点一次「下一步」**，
            //   所以日志里"点了两次"是正常流程，**不能**当重复点拦掉（拦了会把第 2 页最多卡 8s）。
            //   只跟失败页同款留 2.5s 冷却：同一页别在一次点击的动画里连点。
            battleProbeAt = now + 2_500
            // ★结算「下一步」的终点是战后选人/选遗物（都在 DialogLabyrinthSelection，2026-11-07 用户）。
            //   同样是短停 200ms + 多点几次：结算/奖励页本身要连点好几下，多戳几下正好。
            tapAndCheck(
                input, win.optInt("x"), win.optInt("y"), "下一步",
                // ★2026-11-17（用户）：点前**固定路径现读**坐标——不缓存坐标值（坐标会过时成干扰项），
                //   而是每拍走 agent 的 battleResultBtn（记住结算按钮的 prefab 节点路径，零遍历、
                //   约 3 次调用、配对标签「下一步」校验，结构变了自动重学）。路径固定、读取轻量，
                //   每次现取 ⇒ 既跟得上落位动画、又不依赖会过时的缓存坐标。
                //   ⚠更早那版用 findTextIn(half=scrH/2,root=top) 探索 + 无标签校验 ⇒ 捞到干扰
                //     节点 (1109,652) ⇒ 点空/抓不到。这里换成固定路径现读，见 [battleResultXY]。
                pressFn = oncePerResultPage("下一步") {
                    battleResultPressOrTap(input, wantWin = true, what = "下一步")
                },
                pressMax = RESULT_PRESS_MAX,
                directFast = false,   // ★结算页「下一步」pressFn 内部退坐标，保留 400ms 慢节奏防 lmkd 连点事故
                from = RESULT_FROM,
                // ★2026-11-13（用户："走了几个节点之后闪退"）：200ms → **400ms**。
                //   日志 00:38:58~59 在 1.4s 内连点 7 次「下一步」（每次都是 root `input tap`），
                //   紧接着 00:39:01 系统 `device is not responding` 触发 lmkd 杀进程 —— 点击太密会把
                //   input/系统拖到无响应。400ms/次（含 tap ~110ms）≈ 2 次/秒，够推进又不会打满。
                // ★2026-11-17（用户：效率优先）：settle 回到默认 **1×probeMs**（首探更快，不拖节奏）。
                //   连点风险由 **probe=8** 兜住：点完连探 8 次（≈1.2s 窗口），探到页面变即停；
                //   只有"8 次都没变"才点第 2 次 ⇒ 两次点击间隔 ≥1.2s，不会形成点击洪水。
                //   （18:19 那次 1s 内 6 连点的根因是 settle 150ms 且**只探 1 次**。）

                probe = 8,
                // 结算页按钮落位不稳（2026-11-07）：25 次 × (400ms + 探测) ≈ 10 秒窗口
                retries = 25,
                // 阶段2：普通战 → 选人/选遗物（Selection）；**最终 Boss(50801) → 直接进迷宫结算**
                //   （实测 17:41:39 顶框=DialogLabyrinthResult）⇒ 两个都要认，否则会白等 6 秒
                expect = setOf("DialogLabyrinthSelection", "DialogLabyrinthResult"),
            )
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
            // ★2026-11-14（用户）：不再按轮数判"战斗不结束"。上面已有每 30s 一行的
            //   「战斗中…（已 Xs）」进度日志；真卡死由帧看门狗兜底。
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
        failNextSaid = false
        battleSaid = 0
        battleRes0 = 0L
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
        // ★2026-11-12：诊断也限定在对话框子树（不再整树）
        AgentApi.findList("Btn|Button|Decide|Select|Close|Choice|Skip|Item", "dialog")?.let {
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
    private suspend fun recoverAfterRestart(input: InputBackend, tag: String = "自愈"): Boolean {
        AppState.addLog("$tag：等游戏在前台 + agent 回连…")
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < STUCK_BACK_MS) {
            if (Foreground.isGameForeground(GameProcess.pkg()) && AgentBridge.connected.value) break
            delay(1_000)
        }
        if (!Foreground.isGameForeground(GameProcess.pkg()) || !AgentBridge.connected.value) {
            AppState.addLog("$tag：${STUCK_BACK_MS / 1000}s 内游戏/agent 没回来")
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
            AppState.addLog("$tag：当前在 ${Navigator.viewLabel(vid)}（不在迷宫内）→ 重新走「${if (vid == 1 || vid == 2) "点屏幕登录 → " else ""}黎明界 top → 出发」")
            if (!Navigator.goToLabyrinthTop(input)) return false
            // NOT_CHALLENGING（到了 259 却没有进行中的开局）也在这里收手：Navigator 已提示「请检查当前登录账号」
            if (Navigator.enterMaze(input) != Navigator.EnterResult.ENTERED) return false
        } else {
            AppState.addLog("$tag：已在迷宫内（${Navigator.viewLabel(vid)}）→ 不重新导航，直接继续")
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
        AppState.addLog("$tag：回到迷宫后 60s 内读不到迷宫状态")
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
        // ★2026-11-12（用户："明确树结构就不要 find 了"）：先走**当前 View / 最上层对话框**子树
        //   （几十节点，几十 ms）；这两层都没有才退整树 —— 这里只在"学迷之按钮坐标"时用一次，
        //   成功后落盘到 [LabBtnStore]，之后直达。
        for (root in arrayOf("view", "top", "dialog")) {
            val a = AgentApi.findList(nameRe, root) ?: continue
            val o = a.optJSONObject(0) ?: continue
            if (o.has("x") && o.has("y")) return o.optInt("x") to o.optInt("y")
        }
        // ★2026-11-12 实测回归（HOME 页 `ButtonQuest` 不在 view/layer/dialog）：**恢复整树兜底**，
        //   但只在最后、并打日志说明"这个按钮需要补根"，避免再次因为删兜底卡死。
        AgentApi.find(nameRe)?.let {
            if (it.has("x") && it.has("y")) {
                // ★走整树兜底时逐个根报数，方便定位按钮到底挂在哪层
                val where = StringBuilder()
                for (r in arrayOf("view", "top", "dialog", "layer")) {
                    val c = runCatching { AgentApi.findList(nameRe, r)?.length() ?: 0 }.getOrDefault(0)
                    where.append(r).append('=').append(c).append(' ')
                }
                AppState.addLog("迷宫：按钮「$nameRe」只在整树里找到（各根：$where）")
                return it.optInt("x") to it.optInt("y")
            }
        }
        return null
    }

    /**
     * 点屏幕坐标。★照 PC `tap_xy`(2026-10-12)：**屏外一律不点**。
     * 起因：卡片/按钮"还没落位"（入场滑动动画）时坐标会跑到屏外，空点会伪装成"点了没反应/按钮无效"。
     * 这里兜一道（越界只报一次、不下发），返回 true = 真点下去了（调用方据此决定"算不算一次尝试"）。
     */
    private suspend fun tap(
        input: InputBackend,
        x: Int,
        y: Int,
        what: String,
        /** ★2026-10-27：非 null 表示这颗按钮参与"点早了就下次多等"的自学习（见 [tapProbeKey]）。 */
        learnKey: String? = null,
        /**
         * ★2026-11-19（日志精简）：是否登记"窗口转移观测"（主循环下一轮打 `⟳ from--动作-->to`）。
         *   [tapAndCheck]/[tapAndAwait] 自己已经打 `⤷ from--动作-->to`（同一件事）⇒ 传 false，避免两遍。
         *   普通 [tap] 仍传默认 true（它是这类点击唯一的转移记录）。
         */
        markTrans: Boolean = true,
    ): Boolean {
        // ★★★**前台闸门**（2026-11-08 实测 10:39:55 补的）：前台不是游戏 ⇒ **这一下不点**。
        //   实测：用户切到助手前台看日志，助手**还在连点「战斗开始」6 下** ——
        //   那 6 下全打在**助手窗口**上（用户看到的"乱点"），游戏一根手指都没收到。
        //   主循环开头本来也有这道闸门，但 handler 在**同一次主循环迭代里**连点很多下
        //   （编组页一次点 6 个角色、EX 页连点），等它回到循环开头已经晚了好几秒。
        //   ⇒ 放到 [tap] 里：**每次点击前**都查一次。
        //     · [Foreground.packageName] 有 2s TTL 缓存 ⇒ **一个连点批次内几乎零成本**；
        //       ★2026-11-17：主循环也改走 TTL 了（每 100ms 一次 force 的 `su dumpsys`
        //       会把 system_server 拖到 ANR）；真实现查由 watchLoop 每 2s 负责；
        //     · **读不到(null) ≠ 切走** ⇒ 照点（与主循环口径一致，宁可不误停也别漏点）。
        // ★2026-11-17：**优先复用主循环刚查到的 Unity `isFocused`**（0.5s 缓存）——
        //   明确失焦时**零开销**拦截（同一个 handler 连点很多下也不会漏）；
        //   其余情况仍走下面的 dumpsys TTL（一个连点批次内命中缓存，不再发命令）。
        if (focusVal == false) {
            if (lastFrontBlockPkg != "@失焦") {
                lastFrontBlockPkg = "@失焦"
                AppState.addLog("停手：游戏已失焦（isFocused=false）→ 这一下不点")
            }
            return false
        }
        val fp = Foreground.packageName()
        if (fp != null && fp != GameProcess.pkg()) {
            if (lastFrontBlockPkg != fp) {
                lastFrontBlockPkg = fp
                AppState.addLog("停手：前台已切到 $fp（这一下不点游戏）")
            }
            return false
        }
        if (x !in 0..scrW || y !in 0..scrH) {
            if (lastOOR != (x to y)) {
                lastOOR = x to y
                AppState.addLog("[!] 点击坐标 ($x,$y) 在屏幕外（${scrW}x${scrH}）→ 不点（$what：读取/换算出错了？）")
            }
            return false
        }
        // ★2026-11-09：量一下输入本身的耗时（`input tap` 要 fork 进程，正常 0.2~0.4s）。
        //   只在 >=200ms 时附在日志里，便于验证"点击节奏"是否均匀，平时不刷屏。
        val tIn = SystemClock.elapsedRealtime()
        val r = input.tap(x, y)
        val inCost = SystemClock.elapsedRealtime() - tIn
        return when (r) {
            is com.lmj.labyrinthauto.input.InputResult.Ok -> {
                AppState.addLog(
                    "点了 $what ($x,$y)［${input.name}］" + if (inCost >= 200) " 输入 ${inCost}ms" else "",
                )
                PlanProbe.act(what)          // ★观测：挂到当前页的动作列表（不落盘、不判定）
                if (markTrans) markTransition(what)   // ★观测：等主循环下一轮看窗口有没有变（不改行为）
                // ★2026-10-27：参与自学习的按钮，挂个探针 —— 下一轮用"现场 tag 变没变"判它点早了没有
                if (learnKey != null) {
                    tapProbeKey = learnKey
                    tapProbeTag = curTag
                }
                true
            }
            is com.lmj.labyrinthauto.input.InputResult.Failed -> {
                AppState.addLog("点 $what 失败：${r.reason}"); false
            }
            com.lmj.labyrinthauto.input.InputResult.Unavailable -> {
                AppState.addLog("点 $what 失败：${input.name} 后端不可用"); false
            }
        }
    }

    /**
     * **点击 + 等窗口转移**（2026-11-06 状态机）：点完之后每 50ms 看一眼当前窗口，
     * 命中 [expect] 就立刻返回，不再傻等固定 900ms。
     *
     * 语义（用户 2026-11-06 定的）：
     *  · **起点和终点一定会停住**，中间的过渡态（空窗/地图一闪）不需要处理；
     *  · 判据是"**离开 from** 并且**进入 expect 之一**"，没有捕获到中间态也不影响正确性；
     *  · 超时返回 false，**不改变原有失败路径**（交给调用方的兜底逻辑重判）。
     *
     * 为什么能这么轮询：[AgentApi.labTop] 实测中位 4ms / P90 22ms（只遍历 DialogArea 子节点）。
     *
     * @param expect 期望进入的窗口集合（多选一）；传空集表示"只要离开 from 就算成功"
     */
    private suspend fun tapAndAwait(
        input: InputBackend,
        x: Int,
        y: Int,
        what: String,
        from: String,
        expect: Set<String>,
        timeoutMs: Long = 2_500L,
    ): Boolean {
        // ★markTrans=false：本函数命中/超时都会自己打 `⤷`，不必再重复一条 `⟳`
        if (!tap(input, x, y, what, markTrans = false)) return false
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val t = runCatching { AgentApi.labTop() }.getOrNull() ?: ""
            // ★必须**离开 from**才算成功：否则 expect 里若含当前窗口会"原地命中"、等于没等
            val hit = (t != from) &&
                (if (expect.isEmpty()) t.isNotEmpty() else t in expect)
            if (hit) {
                if (TRANS_VERBOSE) {
                    AppState.addLog(
                        "⤷ [$from] --$what--> [$t] ${android.os.SystemClock.elapsedRealtime() - t0}ms（命中）",
                    )
                }
                return true
            }
            kotlinx.coroutines.delay(50)
        }
        // ★超时后再读一次（4ms）：探测窗口内没动静，不代表"已经在窗口之后"——
        //   实测"第一次超时、第二次 4ms 命中"就是这种情况。不补这一读，调用方会立刻重试点击，
        //   而那一下很可能已经落在刚弹出的新窗口上（点错）。
        val last = runCatching { AgentApi.labTop() }.getOrNull() ?: ""
        if (last != from && (expect.isEmpty() || last in expect)) return true
        AppState.addLog("⤷ [$from] --$what--> 超时 ${timeoutMs}ms（期望 ${expect.ifEmpty { setOf("离开 $from") }}）")
        return false
    }

    /**
     * **点一下 → 停 [settleMs] → 看窗口有没有离开 from**（选人页专用）。
     *
     * 跟 [tapAndAwait] 的区别：这里没有"超时"这个维度，只回答"这一下有没有生效"。
     * 没生效由调用方决定再点几次（`handleLabSelection` 的 `SEL_RETRY_MAX`），
     * 所以两条控制线不会互相穿透。
     *
     * ★2026-11-08 加了三个参数（给"特别装备三连页"用）：
     *   · [readTop] —— "当前在哪"的探针。EX 流程里"在哪"= **页面签名**（不是窗口名），
     *     所以这里不能写死 [AgentApi.labTop]。
     *   · [unknownTop] —— 探针**读失败**时返回什么。默认 `""`；EX 流程给 `"?"`，
     *     并且**读失败不算"离开 from"**（否则会把"读不到"误判成"点生效了"）。
     *   · [xyAt] —— **每次点击前现取坐标**。切页动画期间按钮坐标在动（实测 setting「确认」
     *     动画中 (1182,965) vs 稳定 (1179,960)）⇒ 用旧坐标会点空。返回 null = "这一刻不该点"。
     */
    private suspend fun tapAndCheck(
        input: InputBackend,
        x: Int,
        y: Int,
        what: String,
        from: Set<String>,
        settleMs: Long = 0L,               // 0 = 自动 probeMs×2（2026-11-17 用户统一口径）
        retries: Int = 1,
        expect: Set<String> = emptySet(),
        expectTimeoutMs: Long = 6_000L,
        readTop: (suspend () -> String)? = null,
        unknownTop: String = "",
        xyAt: (suspend () -> Pair<Int, Int>?)? = null,
        probe: Int = 0,
        probeMs: Long = TapCheck.currentProbeMs(),
        reached: (suspend (String) -> Boolean)? = null,
        /** ★阶段2自定义到达判据（Boss 两轮选人的"卡片签名变了"），见 [TapCheck.run]。 */
        arrived: (suspend (String) -> Boolean)? = null,
        /** 一次快照探针（top + 判据 + 诊断同源）；给了它就不再读 readTop / reached。 */
        probeState: (suspend () -> TapCheck.Probe)? = null,
        /** ★首点前先「只读不点」几拍（新页入场动画期间按钮坐标还在动）；见 [TapCheck.run] */
        skipFirstHits: Int = 1,
        /** ★★直调执行器（2026-11-19）：给了它就**代替**坐标点击，重试/探针/判据全复用；见 [TapCheck.run] */
        pressFn: (suspend () -> Boolean?)? = null,
        /** ★直调最多真投几次（默认 1，不可放开：2026-11-20 放开到 4 实测复现 205，见 [TapCheck.run]） */
        pressMax: Int = 1,
        /** ★直调是否走加速档；结算页（pressFn 内部退坐标）传 false 保留慢节奏，见 [TapCheck.run] */
        directFast: Boolean = true,
    ): Boolean = TapCheck.run(
        // ★markTrans=false：本函数自己在命中时已打 `⤷`，别再让主循环重复打一条 `⟳`
        tapFn = { tx, ty, tw -> tap(input, tx, ty, tw, markTrans = false) },
        x = x, y = y, what = what, from = from,
        settleMs = settleMs, retries = retries,
        expect = expect, expectTimeoutMs = expectTimeoutMs,
        readTop = readTop, unknownTop = unknownTop,
        xyAt = xyAt, probe = probe, probeMs = probeMs, reached = reached,
        arrived = arrived,
        probeState = probeState,
        // ★2026-11-17：skipDelayMs 改由 TapCheck 默认（= probeMs，跟挡位联动），不再写死 60ms
        skipMax = EXEQ_SKIP_MAX,
        skipFirstHits = skipFirstHits,
        pressFn = pressFn,
        pressMax = pressMax,
        directFast = directFast,
        verbose = TRANS_VERBOSE,
    )

    /**
     * **等编队页卡片铺好 / 滚到位**（2026-11-07）：替代原来固定的 `LAB_PARTY_SETTLE_MS`(1.5s)
     * 与 `LAB_PARTY_SCROLL_SETTLE`(1.5s)。
     *
     * 判据：`labPartyIcons` 读回来的 **一览 unitId 列表（`listIds`）+ 槽位列表（`ids`）**
     * **连续两次相同**（等于入场动画结束 / 滚动停住）。
     * ★**必须看「一览」**：滚动角色一览时"队伍那一排"**不会变**、只有一览会变 ——
     *   只看槽位的话，滚完立刻就判"稳了"（等于没等）⇒ 点下去落在旧位置那张卡上。
     * 轻量接口只读长度 + unitId（~15ms），所以 70ms 一探也不心疼。
     *
     * @param timeoutMs 兜底上限；到了还没稳定就直接往下走（保持原行为，不会卡死）
     * @param before **滚动前**的签名（可选）：滚动场景传进来 ⇒ 必须**先变化**再算稳定，
     *        否则滑动动画还没开始就读到了旧列表，等于没等。
     */
    private suspend fun awaitPartyIconsStable(
        timeoutMs: Long = PartyPlan.LAB_PARTY_SETTLE_MS,
        before: List<Int>? = null,
    ) {
        val t0 = SystemClock.elapsedRealtime()
        var prev: List<Int>? = null
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val raw = runCatching { AgentApi.labPartyIcons() }.getOrNull()
            val top = raw?.top ?: ""
            val listIds = raw?.listIds ?: emptyList()
            val slotIds = raw?.ids ?: emptyList()
            val sig = listIds + slotIds
            val ready = listIds.any { it != 0 } || slotIds.any { it != 0 }
            if (top == "DialogCommonPartyEdit" && ready) {
                val changed = before == null || sig != before
                if (changed && sig == prev) {
                    val used = SystemClock.elapsedRealtime() - t0
                    if (used > 150) {
                        AppState.addLog(
                            "… 编队页就绪用时 ${used}ms（一览 ${listIds.count { it != 0 }} 张 / 槽位 ${slotIds.count { it != 0 }} 人）",
                        )
                    }
                    return
                }
            }
            prev = sig
            kotlinx.coroutines.delay(70)
        }
        AppState.addLog("… 编队页 ${timeoutMs}ms 内未稳定，按原样继续")
    }

    /** 上次报过的"屏外坐标"（同值只报一次，照 PC `_TAP_OOR`） */
    private var lastOOR: Pair<Int, Int>? = null

    /** ★上一次被"前台闸门"拦下的包名（同一句只打一次，别刷屏） */
    private var lastFrontBlockPkg: String? = null

    /** ★上次报过的"弹窗已在收起"的顶框名（同一弹窗只报一次，2026-11-19 日志精简） */
    private var lastClosingSaid: String? = null

    // ── 窗口转移观测（2026-11-06 状态机前置）──────────────────────────
    //   只记录、不改变任何行为：点击前记下"当时窗口"，主循环下一轮看到窗口变了就落一行。
    //   目的是攒出「from + action → after + 耗时」的真实样本，再据此定义状态机的期望转移。
    private var lastTop = ""
    private var transPend: Triple<String, String, Long>? = null
    private var transCount = 0
    /** labTop 耗时采样只做一次（跨局保持） */


    /** `tapAndAwait` 命中时是否打日志（先开着，方便逐条核对转移；稳定后可关） */
    private val TRANS_VERBOSE = true

    /**
     * 选人页每次点击后的等待统一为 probeMs×2（2026-11-17 用户），不再单独设 settleMs。
     */

    /** 遗物选卡 → 顶框变成 `DialogCommonBig` 的最长等待：实测最长 3.9s（15:41:47→15:41:52）。
     *  探针是 3ms 的 [AgentApi.labTop]，100ms 一探 ⇒ 弹窗一出现就点，不是固定死等。 */
    private const val RELIC_REWARD_WAIT_MS = 4_500L

    /** 确认按钮「落位」探测：弹窗打开动画里按钮会漂，连读这么多次、两次差 ≤8px 才用。
     *  不是加大等待换准确，是用 30ms 的轻读**更快知道现在能不能点**（2026-11-09 事故）。 */
    private const val DLG_XY_TRIES = 6
    private const val DLG_XY_GAP_MS = 60L

    /** 选人页最多点几次（每次都重新读卡片位置/属性；2026-11-07 用户定为 10） */
    private val SEL_RETRY_MAX = 10

    /**
     * 选人页（[DialogLabyrinthSelection]）点一张卡之后的期望窗口：
     *  · 战后选人 → `DialogCommonMiddle`
     *  · 门票 / 遗物 → `DialogCommonBig`
     *
     * ★**不放 Selection 自己**：`tapAndAwait` 要求"必须离开 from"，
     *   放了会原地命中、等于没等。Boss 两轮选人的情况交给超时兜底——
     *   4.5s 还没走就返回 false，上层按 `lastSelTaps` 计数决定再选一次还是放弃。
     */
    private fun selExpect(kind: String): Set<String> =
        // ★实测（2026-11-07）：kind 的取值是「战后选人 / 门票 / 遗物 / 选择角色」——
        //   "战后选人"和"选择角色"都去 DialogCommonMiddle，只有门票/遗物去 DialogCommonBig。
        if (kind.contains("选人") || kind == "选择角色") {
            setOf("DialogCommonMiddle")
        } else {
            setOf("DialogCommonBig")
        }

    /** ★2026-11-20：三选一点完后等"下一页"的阶段2上限。正常 Middle 25ms 就到；
     *  Boss 两轮选人第一轮后仍是 Selection，等这个上限就递归重选（原 6000ms 空等太久）。 */
    private const val SEL_NEXT_PAGE_TIMEOUT_MS = 2_000L

    /** ★2026-11-20：读当前选人页卡片签名（与 handleLabSelection 里的 `sig` 同算法），
     *  用于 Boss 第一轮 `arrived` 判据——卡片刷新（签名变）即算"已进入第二轮"。
     *  只接受"3 张有效卡"（和读卡合格判据一致），过渡态读到空/缺卡返回 "" ⇒ 不算刷新。 */
    private suspend fun selSigNow(tag: String): String {
        val cards = runCatching {
            AgentApi.labSelection(force = true)?.optJSONArray("cards")
        }.getOrNull() ?: return ""
        if (cards.length() != 3) return ""
        val uids = (0 until 3).map { cards.optJSONObject(it)?.opt("unitId") }
        if (uids.any { it == null || (it as? Number)?.toInt() == 0 }) return ""
        return tag + "|" + uids.map { (it as Number).toInt() }.sorted().joinToString(",")
    }

    /**
     * ★★印记节点三选一（kind="门票"）之后的**两连弹**（2026-11-09 现场抓证）：
     * ```
     * DialogCommonBig(收到印记) --关闭--> DialogCommonMiddle(收到角色) --关闭--> 地图(无弹窗)
     * ```
     * 遗物三选一（statusType=9，走 handleRelicChoice）与角色三选一（kind=战后选人/选择角色）
     * **都不是**这个响应方式 ⇒ 只在 handleLabSelection 里 `kind == "门票" && left` 时调用。
     *
     * 两段都走 [tapAndCheck]：点完立刻探 labTop，变了就走，不睡固定 900ms。
     * ★★2026-11-09 读法定案（与 EX 三连页 `labExBtn` / 编队 `labExEquipSig` 同一范式）：
     *   **专门拆一个轻读取** [AgentApi.labDlgBtn] —— 按 `DialogCommonAbstract +0x118` 字段直取按钮，
     *   零遍历、几十 ms；替换掉 `dialog()` 的 `DialogArea` 子树全量遍历（冷 0.5~0.6s / 大页 3~3.9s）。
     * ★★2026-11-09 事故修正：**「每下现取坐标」对弹窗按钮是反的**。
     *   [TapCheck] 的注释说"切页动画期间坐标在动 ⇒ 旧坐标会点空"，那是三选一卡片；
     *   弹窗关闭按钮出现时静止，点击后却随**收起动画上移** ⇒ 每下重读拿到的是漂到地图节点带
     *   （实测 Big 959,958→959,890；Middle 958,875→959,789；地图节点在 x≈960,y=563~825）
     *   ⇒ 多点误触下个节点，弹出「移动目标确认」DialogCommonSmall，状态机却以为已回地图。
     *   ⇒ 改为 **段入口读一次「已落位」坐标，段内固定**；每下只用 3ms 的 labTop 校验壳还在。
     * 失败只打日志，主循环的 tapDialogConfirm 仍会兜底 ⇒ 不会比旧逻辑更容易卡住。
     */
    // ═══════════ ★★「关闭已启动」判据（2026-11-09 定案，替代"等窗口消失"） ═══════════
    //  实测 7 段关闭（门票 Big/Middle、遗物 Big、战后选人 Middle，2026-11-09）：
    //    · 按钮层 mState / initEnable / IsPressed / curBoxCollider **全程不变**
    //      （游戏关弹窗不经"禁用按钮"，回调里直接 Close() ⇒ 按钮上没有"已点击"标记）
    //    · 退出 tween（mStarted=1、mFactor 0→1）在点击后 **+200~300ms** 启动
    //    · 整页（顶层 Dialog → DialogCommonAbstract）到 **+800ms** 才消失
    //  ⇒ 阶段 1 用「关闭已启动」判"这一下生效了"：点到了 +200~300ms 就停手、不补第二下；
    //    没启动就继续重试 ⇒ 不会"点了没反应就放弃"。
    //  ⇒ 判据与点击都只用一次 3~6ms 轻读；[dlgReadyToTap] 在**紧邻 tap 之前**再确认一次
    //    "壳还在 + 没在关"，把"判断时还在、点下去已消失"的窗口压到最小。
    /** 阶段 1 的**单次快照**探针：`top` 与"是否已离开"来自同一次读取。
     *  ★省掉原来"先 labTop 再判关闭态"的第二次 RPC —— 不只是快，更重要的是两次读之间
     *    状态可能已经变化（top 说还在、closing 说已关），判据就不自洽了。
     *  ★读失败（RPC 超时 / agent 内部异常）⇒ `left=false`，**绝不把"读不到"当成"已离开"**。 */
    private suspend fun dlgProbe(shell: String): TapCheck.Probe {
        val r = runCatching { AgentApi.labDlgClosing() }.getOrNull()
        if (r == null || r.optString("path") == "err") return TapCheck.Probe("", false)
        val top = r.optString("top")
        return TapCheck.Probe(
            top = top,
            left = top != shell || r.optBoolean("closing", false),
        )
    }

    /** 紧邻 tap 之前的最后确认：**一次轻读同时判「壳还在」和「没在关」**。
     *  任一不成立就不给坐标（不消耗 retries，[TapCheck] 会稍后再试）。
     *  ★比原来只看 labTop 更严：关闭动画已启动时，即便窗口对象还在，也不再点第二下。 */
    private suspend fun dlgReadyToTap(shell: String, xy: Pair<Int, Int>): Pair<Int, Int>? {
        val r = runCatching { AgentApi.labDlgClosing() }.getOrNull() ?: return null
        if (r.optString("top") != shell) return null
        if (r.optBoolean("closing", false)) return null
        return xy
    }

    /**
     * ★2026-11-19（并入 feat/direct-trigger）：**按固定路径直调，失败退回"最后确认 + 坐标点击"**。
     *
     * 用于三选一之后那几颗单按钮弹窗（`ResultBig|OneButtonWhite` / `SelectConfirm|OneButtonWhite`）：
     * 直调不投坐标 ⇒ 不受入场动画/叠层影响；失败才走原来的 [dlgReadyToTap] + tap。
     *
     * @param readyXY 退回时**现取**的坐标；返回 null = 这一刻不该点（跳过重试）
     * @return `true` = 已发出；`null` = 这一刻不该点
     */
    private suspend fun pathPressOrReady(
        input: InputBackend,
        pathKey: String,
        what: String,
        readyXY: suspend () -> Pair<Int, Int>?,
    ): Boolean? {
        val pr = runCatching { AgentApi.labPathPress(pathKey) }.getOrNull()
        if (pr?.optBoolean("ok") == true) return true
        AppState.addLog(
            "$what：路径直调失败（$pathKey / ${pr?.optString("err").orEmpty().ifEmpty { "无回包" }}）→ 退回坐标点击",
        )
        val xy = readyXY() ?: return null
        return tap(input, xy.first, xy.second, what)
    }
    // ═══════════ 结束 ═══════════

    /** ★三选一页的阶段 1 探针：**一次读取**同时给 top / 关闭判据 / 诊断串。
     *
     * 三选一（`DialogLabyrinthSelection`）也是 `DialogBase` 子类 ⇒ 退出 tween 与确认框同源
     * （`labDlgClosing` 已按 DialogBase 兜底），所以用同一条判据：
     *   「顶框已不是这个壳」或「退出动画已启动」= 这一下生效了。
     * 诊断串（`tw` / `proc` / `selNum` / `listN` / `remain`）跟同一次读取返回，
     * **不额外发 RPC、不阻塞 probe** —— probe 仍按 settle/probeMs 的节奏跑。
     */
    private suspend fun selProbe(shell: String): TapCheck.Probe {
        val r = runCatching { AgentApi.labSelState() }.getOrNull()
        if (r == null || r.optString("path") == "err") return TapCheck.Probe("", false)
        val top = r.optString("top")
        val diag = "tw=${r.optString("tw")} proc=${r.optString("proc")}" +
            " selNum=${r.optString("selNum")} listN=${r.optString("listN")}" +
            " remain=${r.optString("remainText")}"
        // ★top != shell（换了页）或 Selection 自己开始收起（closing）⇒ 这一下生效。
        //   （2026-11-20 曾加过"Small 收起不算离开"的三分判据，实测 Small 是 closing=false 停着、
        //     那套没触发且无效，已回退；205 的真正根因是屏外卡片被过早直调，见上面 !onScreen 闸门。）
        return TapCheck.Probe(
            top = top,
            left = top != shell || r.optBoolean("closing", false),
            diag = diag,
        )
    }

    private suspend fun closeTicketRewardChain(input: InputBackend) {
        val bigXY = stableDlgConfirmXY("DialogCommonBig")
        if (bigXY == null) {
            AppState.addLog("[!] 门票两连弹：Big 按钮没落位 → 交给主循环通用弹窗兜底")
            return
        }
        val first = tapAndCheck(
            input, bigXY.first, bigXY.second, "门票：关闭收到印记(Big)",
            from = setOf("DialogCommonBig"),
            expect = setOf("DialogCommonMiddle"),
            probe = 5, retries = 3,
            // ★2026-11-19：直调优先（`ResultBig|OneButtonWhite`），失败退回"确认 + 坐标"
            pressFn = {
                pathPressOrReady(input, "ResultBig|OneButtonWhite", "门票：关闭收到印记(Big)") {
                    dlgReadyToTap("DialogCommonBig", bigXY)
                }
            },
            probeState = { dlgProbe("DialogCommonBig") },
        )
        if (!first) {
            AppState.addLog("[!] 门票两连弹：Big → Middle 未确认 → 交给主循环通用弹窗兜底")
            return
        }
        closeMiddleRewardDialog(input, "门票：关闭收到角色(Middle)")
    }

    /** 段入口取一次「落位」的确认按钮坐标：弹窗打开动画里按钮 y 会漂（实测 876↔960），
     *  连读最多 [DLG_XY_TRIES] 次（每次 ~30ms 轻读 + [DLG_XY_GAP_MS] 间隔），
     *  两次差 ≤8px 才算落位。等不到就返回 null ⇒ 主循环兜底，**不拿漂着的坐标去点**。 */
    private suspend fun stableDlgConfirmXY(shell: String): Pair<Int, Int>? {
        var last: Pair<Int, Int>? = null
        repeat(DLG_XY_TRIES) { i ->
            val p = dlgConfirmBtnXY(shell)
            if (p != null) {
                val l = last
                if (l != null && kotlin.math.abs(p.first - l.first) <= 8 &&
                    kotlin.math.abs(p.second - l.second) <= 8
                ) return p
                last = p
            }
            if (i < DLG_XY_TRIES - 1) delay(DLG_XY_GAP_MS)
        }
        return null
    }

    /**
     * ★★「收到角色」`DialogCommonMiddle` 单弹 → 地图（2026-11-09 抽出来给两处共用）：
     *   · 印记三选一两连弹的**第二弹**（Big 收到印记 → Middle 收到角色 → 地图）
     *   · 角色三选一（战后选人）之后的**唯一一弹**（Middle → 地图）
     *
     * 两处都是 `DialogCommonMiddle` + `OneButtonWhite`(958,870)：历史日志多次出现
     * `顶框=DialogCommonMiddle(Clone)` → `点了 对话框「OneButtonWhite」(958,870)` → `顶框=无`；
     * 现场抓证时这个壳是「角色加入」(`PartsDialogLabyrinthGetUnitResult`)。
     * ★[expect] = 空串（无对话框）：**不能**只判「离开 Middle 就算成功」，否则误触出来的
     *   `DialogCommonSmall`（移动目标确认）会被当成已回地图 ⇒ 状态转换认为自己不在这个地方。
     * @return 是否确认到了地图；false 只打日志，主循环 `tapDialogConfirm` 仍会兜底。
     */
    private suspend fun closeMiddleRewardDialog(input: InputBackend, what: String): Boolean {
        val xy = stableDlgConfirmXY("DialogCommonMiddle")
        if (xy == null) {
            AppState.addLog("[!] $what 按钮没落位 → 交给主循环通用弹窗兜底")
            return false
        }
        val ok = tapAndCheck(
            input, xy.first, xy.second, what,
            from = setOf("DialogCommonMiddle"),
            expect = setOf(""),
            expectTimeoutMs = 2_000L,
            probe = 5, retries = 3,
            // ★2026-11-19：直调优先（`SelectConfirm|OneButtonWhite`），失败退回"确认 + 坐标"
            pressFn = {
                pathPressOrReady(input, "SelectConfirm|OneButtonWhite", what) {
                    dlgReadyToTap("DialogCommonMiddle", xy)
                }
            },
            probeState = { dlgProbe("DialogCommonMiddle") },
        )
        if (!ok) {
            AppState.addLog("[!] $what 未确认 → 交给主循环通用弹窗兜底")
        }
        return ok
    }

    /**
     * ★★2026-11-20（用户）：**不预设顺序**地把奖励弹窗点完 —— 事件战用它，普通战不用。
     *
     * 为什么不能写死链条：事件收尾连弹的 `DialogCommon*` **个数和顺序都因事件而异**
     * （有时 Middle→Big，有时只有一个，有时还夹 Small）。写死 `Middle→Big` 会：
     * 没有 Big 的事件白等 4s 超时；顺序相反的则直接断链。
     *
     * ⇒ 改成"**按当前顶框逐层点掉**"：
     * ```
     * 循环（最多 maxRounds 层）：
     *   top = labTop()
     *   top 为空          → 返回（收尾完成；真正的"回地图"判据在主循环：有可前往节点）
     *   Big / Middle      → 直调对应 OneButtonWhite（失败退坐标），等这一层开始消失
     *   其它壳（Small 等）→ 立刻返回，交给主循环（不猜）
     * ```
     */
    private suspend fun closeEventBattleRewardChain(input: InputBackend, maxRounds: Int = 4) {
        repeat(maxRounds) { i ->
            val top = runCatching { AgentApi.labTop() }.getOrNull() ?: return
            if (top.isEmpty()) return                                  // 顶框空 = 收尾完成
            val shell = top.removeSuffix("(Clone)")
            val key = when (shell) {
                "DialogCommonBig" -> "ResultBig|OneButtonWhite"
                "DialogCommonMiddle" -> "SelectConfirm|OneButtonWhite"
                else -> {
                    if (i == 0) AppState.addLog("事件战：顶框是 $shell（不在奖励弹窗白名单）→ 交回主循环")
                    return
                }
            }
            // 已经在收起 ⇒ 让主循环等它走完，别再点
            if (dlgProbe(shell).left) return
            val xy = stableDlgConfirmXY(shell)
            if (xy == null) {
                AppState.addLog("事件战：$shell 按钮没落位 → 交回主循环")
                return
            }
            val ok = tapAndCheck(
                input, xy.first, xy.second, "事件战：关闭 $shell",
                from = setOf(shell),
                expect = emptySet(),        // 不关心下一层是谁，离开这一层就算过
                probe = 5, retries = 3,
                pressFn = {
                    pathPressOrReady(input, key, "事件战：关闭 $shell") {
                        dlgReadyToTap(shell, xy)
                    }
                },
                probeState = { dlgProbe(shell) },
            )
            if (!ok) {
                AppState.addLog("事件战：$shell 没关掉 → 交回主循环")
                return
            }
        }
        AppState.addLog("事件战：奖励弹窗连点了 $maxRounds 层仍未清空 → 交回主循环")
    }

    /**
     * ★★遗物三选一之后的**单弹**（2026-11-09 现场截图 + 历史日志取证；用户 11-09 补充两条分叉）：
     * ```
     * ① 遗物节点(panelTypeName=RELIC)：
     *    三选一 --statusType 立刻变--> DialogCommonBig(获得道具/收到遗物)
     *           --OneButtonWhite(958,960)--> 顶框=无（地图）         ← 结束状态：地图
     * ② 战斗节点（NORMAL/HARD/BOSS/EX… 一切非 RELIC）：
     *    三选一 --statusType 立刻变--> DialogCommonBig
     *           --OneButtonWhite(958,960)--> DialogLabyrinthSelection  ← 结束状态：角色三选一
     *           再由角色三选一流程继续推进
     * ```
     * 两类节点的 Big **都是** `DialogCommonBig`（日志 `顶框=DialogCommonBig(Clone)` 印证）；
     * 与印记三选一不同：印记是 Big→Middle→地图 **两连弹**，遗物只有这一弹。
     *
     * ★顶框名有最长 3.9s 的延迟（实测 15:41:47→15:41:52），所以先用便宜的 [AgentApi.labTop]
     *   （3ms，100ms 一探）等 Big 出现；一出现就用 [dlgConfirmBtnXY]（[AgentApi.labDlgBtn]
     *   字段直取、零遍历）现取坐标 + [tapAndCheck] 点掉。
     * ★[fromBattleNode] 决定阶段 2 的落点判据：战斗节点必须**等到** `DialogLabyrinthSelection`
     *   才算这次转移完成（否则状态机会以为还在奖励链里）；遗物节点离开 Big 就算完成。
     * ★本来就没弹窗 / 等不到 / 换成别的通用壳 ⇒ 静默交回主循环兜底，不阻塞流程。
     */
    private suspend fun closeRelicRewardDialog(input: InputBackend, fromBattleNode: Boolean) {
        val t0 = SystemClock.elapsedRealtime()
        var emptyN = 0
        var seen = false
        while (SystemClock.elapsedRealtime() - t0 < RELIC_REWARD_WAIT_MS) {
            val t = runCatching { AgentApi.labTop() }.getOrNull() ?: ""
            if (t == "DialogCommonBig") { seen = true; break }
            if (t in DLG_GENERIC_SHELLS) return   // 不是预期的那个壳 ⇒ 别白等，交给主循环
            // 连续两次"没有对话框"才认定本来就没弹窗（单次读失败不误判）
            if (t.isEmpty()) {
                if (++emptyN >= 2 && SystemClock.elapsedRealtime() - t0 > 400L) return
            } else {
                emptyN = 0
            }
            delay(100L)
        }
        if (!seen) return   // 没等到 ⇒ 交给主循环通用弹窗兜底
        val xy = stableDlgConfirmXY("DialogCommonBig")
        if (xy == null) {
            AppState.addLog("[!] 遗物确认框：Big 按钮没落位 → 交给主循环通用弹窗兜底")
            return
        }
        val ok = tapAndCheck(
            input, xy.first, xy.second, "遗物：关闭收到遗物(Big)",
            from = setOf("DialogCommonBig"),
            // ★分叉：战斗节点必须等到角色三选一页；遗物节点必须等到「无对话框」，
            //   不能只判"离开 Big" ⇒ 否则误触出来的 DialogCommonSmall 会被当成已回地图。
            expect = if (fromBattleNode) setOf("DialogLabyrinthSelection") else setOf(""),
            expectTimeoutMs = 3_000L,
            probe = 5, retries = 3,
            // ★2026-11-19：直调优先（`ResultBig|OneButtonWhite`），失败退回"确认 + 坐标"
            pressFn = {
                pathPressOrReady(input, "ResultBig|OneButtonWhite", "遗物：关闭收到遗物(Big)") {
                    dlgReadyToTap("DialogCommonBig", xy)
                }
            },
            probeState = { dlgProbe("DialogCommonBig") },
        )
        if (!ok) {
            AppState.addLog(
                "[!] 遗物确认框：Big → ${if (fromBattleNode) "角色三选一" else "地图"} 未确认 → 交给主循环兜底",
            )
        }
    }

    /** 轻读失败回退的节流日志（按壳 + 10s 间隔，避免每次点击前刷屏） */
    private var dlgLiteFallbackShell = ""
    private var dlgLiteFallbackAt = 0L

    /** 现取当前顶框里那颗「确认/关闭」按钮坐标：**顶框名对不上就不给坐标**（不越框点）。
     *  ★主路 = [AgentApi.labDlgBtn] 字段直取（零遍历，几十 ms）；agent 已校验顶框名 +
     *    按钮 GameObject 名字 + activeInHierarchy，命中的才给。
     *  ★兜底 = 轻读失败（壳结构变了 / 不是通用弹窗）才退回 [AgentApi.dialog] 子树遍历，
     *    并回喂按钮链给 [LabBtnStore]（与主循环 tapDialogConfirm 共用缓存），绝不硬点。
     *    ★失败必须留日志：真机验证时靠"没有这行"来证明轻读全程生效。
     *  ★★2026-11-09：先用 3ms 的 labTop 确认壳还在 / 已经在了。**顶框不对就直接不给坐标**，
     *    否则 [stableDlgConfirmXY] 连读时若撞上弹窗正在创建，会把"还没出现"误判成轻读故障
     *    ⇒ 退回 dialog() 子树遍历（0.5s+，且可能读到别的弹窗按钮坐标）。 */
    private suspend fun dlgConfirmBtnXY(shell: String): Pair<Int, Int>? {
        val cur = runCatching { AgentApi.labTop() }.getOrNull()
        if (cur != shell) return null
        val fast = runCatching { AgentApi.labDlgBtn(shell) }.getOrNull()
        val d = if (fast?.optBoolean("ok", false) == true && fast.optString("top") == shell) {
            fast
        } else {
            val why = fast?.optString("err").takeUnless { it.isNullOrEmpty() } ?: "无回包/超时"
            val now = SystemClock.elapsedRealtime()
            if (dlgLiteFallbackShell != shell || now - dlgLiteFallbackAt > 10_000L) {
                dlgLiteFallbackShell = shell
                dlgLiteFallbackAt = now
                AppState.addLog("[!] 通用弹窗轻读失败（$shell，$why）→ 本次退回 dialog 遍历")
            }
            val topKey = "dlg|" + shell
            val known = appCtx?.let { LabBtnStore.getChains(it, topKey) }
            val slow = runCatching {
                AgentApi.dialog(shell, if (known != null && known.length() > 0) known else null)
            }.getOrNull() ?: return null
            val fresh = slow.optJSONArray("learnedList")
            if (fresh != null && fresh.length() > 0) {
                appCtx?.let { LabBtnStore.putChains(it, topKey, fresh) }
            }
            slow
        }
        val bts = d.optJSONArray("buttons") ?: return null
        for (want in DIALOG_CONFIRM) {
            for (i in 0 until bts.length()) {
                val b = bts.optJSONObject(i) ?: continue
                if (b.optString("name") == want && hasXY(b)) return b.optInt("x") to b.optInt("y")
            }
        }
        return null
    }

    private fun markTransition(what: String) {
        if (transCount > 400) return
        transPend = Triple(lastTop, what, android.os.SystemClock.elapsedRealtime())
    }

    private fun checkTransition(top: String) {
        val p = transPend ?: return
        val dt = android.os.SystemClock.elapsedRealtime() - p.third
        if (top != p.first) {
            transCount++
            AppState.addLog("⟳ [${p.first.ifEmpty { "-" }}] --${p.second}--> [${top.ifEmpty { "-" }}] ${dt}ms")
            transPend = null
        } else if (dt > 2500) {
            transCount++
            AppState.addLog("⟳ [${p.first.ifEmpty { "-" }}] --${p.second}--> [未变化] ${dt}ms")
            transPend = null
        }
    }

    /**
     * 通用弹窗白名单（PC `MAZE_DIALOG_CONFIRM`）：命中就点那一颗，认不出来的弹窗**不猜**。
     * 抽出来是为了给"handler 读不到页面"时当**降级路径**（见 [handleSummonUnit]）—— 否则那种页会永久卡死。
     * @return true = 点了
     */
    private suspend fun tapDialogConfirm(input: InputBackend, top: String, tag: String = ""): Boolean {
        // ★★2026-11-20（205 事故·硬闸门）：**只处理通用弹窗壳**。业务页（商店/选人/编组/事件/
        //   预览/迷宫结算）上的按钮由各自 handler 负责；在这里按"按钮名白名单"点会误触——
        //   实测进商店 0.4s 后把商店里的 `OneButtonBlue` 当弹窗点了 ⇒ 重复进店 ⇒ 205 回标题。
        val shellTop = top.removeSuffix("(Clone)")
        if (shellTop.startsWith(PAGE_SHOP) || shellTop.startsWith(PAGE_LAB_SELECTION) ||
            shellTop.startsWith(PAGE_PARTY_EDIT) || shellTop.startsWith("DialogLabyrinthEvent") ||
            shellTop.startsWith(PAGE_QUEST_DETAIL) || shellTop.startsWith(PAGE_LAB_RESULT)
        ) {
            return false
        }
        // ★2026-11-13（用户："走了几个节点之后闪退" + "消失中的按钮位置"）：
        //   弹窗**已经在收起**（out tween 启动 / factor 开始降）就别再点 —— 按钮位置在飘，
        //   点下去等于点在空处（日志 00:48:48：`确认框：开始消失 +893ms` 之后又点了
        //   `TwoButtonRight @(1096,667)`）。直接返回 true 表示"已处理"，主循环等它消失。
        val cl = runCatching { AgentApi.labDlgClosing() }.getOrNull()
        if (cl != null) {
            val cTop = cl.optString("top")
            val closingNow = cl.optBoolean("closing") ||
                (cl.optInt("started", 0) == 1 && cl.optDouble("factor", 1.0) < 0.99)
            if (closingNow && (top.isEmpty() || cTop.contains(top.removeSuffix("(Clone)")))) {
                // ★2026-11-19（日志精简）：同一个弹窗只提示一次（原来每次进这里都打，一局 20+ 条）
                if (tag.isNotEmpty() && lastClosingSaid != cTop) {
                    lastClosingSaid = cTop
                    AppState.addLog("弹窗已在收起（$cTop）→ 不再点")
                }
                return true
            }
        }
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
            }
        }
        val bts = d?.optJSONArray("buttons") ?: return false
        if (bts.length() == 0) return false
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
        // ★★2026-11-09 事故修正：签名必须带**节点现场** [tag]。
        //   原来只有「按钮名@坐标」，同一个 `TwoButtonRight@1179,744` 在不同节点的移动确认框
        //   坐标一模一样 ⇒ 前两次明明**成功移动了**，计数却一直累加 ⇒ 第 3 次把正常弹窗
        //   误判成幽灵弹窗熔断，卡在「移动目标确认」18s（与 [labResultSig] 同款修法）。
        val sig = "$tag|${hit.optString("name")}@${hit.optInt("x")},${hit.optInt("y")}"
        val now = SystemClock.elapsedRealtime()
        if (sig == dlgTapSig) dlgTapN++ else { dlgTapSig = sig; dlgTapN = 1 }
        if (dlgTapN > DLG_TAP_MAX) {
            // ★2026-11-14（用户反馈"买完遗物商店不能自动关闭"）：熔断不再永久 —— 静默
            //   [DLG_RETRY_MS] 后再试一轮。"幽灵节点"最坏只是每 10s 白点一次；而
            //   "点早了 / 动画还没结束"的弹窗能自救，不会再和商店互相干等。
            if (now - dlgTapAt < DLG_RETRY_MS) {
                if (dlgTapN == DLG_TAP_MAX + 1) {
                    AppState.addLog(
                        "[!] 同一颗弹窗按钮「${hit.optString("name")}」连点 $DLG_TAP_MAX 次都没推进 → " +
                            "暂缓 ${DLG_RETRY_MS / 1000}s 再试",
                    )
                }
                return false
            }
            AppState.addLog("… 弹窗「${hit.optString("name")}」已静默 ${DLG_RETRY_MS / 1000}s → 再点一轮")
            dlgTapN = 1
        }
        dlgTapAt = now
        // ★★2026-11-20（用户："前进确认有时会弹两次"）：**移动确认框只投一次**。
        //   每一下 TwoButtonRight 都是一次真实"确认"⇒ 真的提交一次移动；连点会让游戏排队弹两次。
        //   （奖励弹窗仍允许连点救场，所以只对 CommonSmall + TwoButtonRight 单独限流。）
        val shellNow = top.removeSuffix("(Clone)")
        // ★★2026-11-20（205 事故·第二次确认）：`DialogCommonSmall` 是「是否前往」专用壳，
        //   点过 `TwoButtonRight` 之后**这个壳上任何按钮都不再点**。
        //   实测（session_162251 16:24）：11.481 直调 `MoveConfirm|TwoButtonRight`（前往）→
        //   12.430 Small 还在退场、按钮树里 TwoButtonRight 已消失、**只剩一颗 `OneButtonBlue`** ⇒
        //   白名单接着点它 ⇒ 同一节点第二次提交移动 ⇒ 13.7 进 Splash/205。
        if (shellNow == "DialogCommonSmall") {
            // ⚠不能用 tag 做 key：移动请求提交后 `CurrentBlockId` 立刻从 10201 变成 10301，
            //   而画面上盖的还是"10201→10301"那个确认框 ⇒ tag 一变，按 tag 的限流就失效了。
            //   ⇒ 纯时间窗：2.5s 内点过「前往」，这个壳上的任何按钮都不再点。
            if (now - dlgMoveAt < MOVE_CONFIRM_ONCE_MS) return true
            if (hit.optString("name") == "TwoButtonRight") dlgMoveAt = now
        }
        // ★★2026-11-20（用户："钓鱼结束收获用的还是 tap"）：结果/收获类单按钮弹窗**先直调**。
        //   `DialogCommonBig`(获得印记/道具/遗物) 和 `DialogCommonMiddle`(收到角色) 的 OneButtonWhite
        //   都有固定路径 ⇒ 直调不投坐标、不受入场动画与叠层影响。
        //   ★只在这一签名的**第一次**尝试时直调（dlgTapN==1），失败就退回坐标点击，避免重复投 onClick。
        if (dlgTapN == 1 && hit.optString("name") == "OneButtonWhite") {
            val oneKey = when (top.removeSuffix("(Clone)")) {
                "DialogCommonBig" -> "ResultBig|OneButtonWhite"
                "DialogCommonMiddle" -> "SelectConfirm|OneButtonWhite"
                else -> null
            }
            if (oneKey != null) {
                val pr = runCatching { AgentApi.labPathPress(oneKey) }.getOrNull()
                if (pr?.optBoolean("ok") == true) {
                    AppState.addLog("对话框「OneButtonWhite」（直调 $oneKey）")
                    return true
                }
            } else if (top.removeSuffix("(Clone)") == "DialogCommonSmall") {
                // ★2026-11-20（探测实测）：Small 壳上的 OneButtonWhite（如公会「遗物效果结果」通知窗）
                //   没有稳定的 canvas 固定路径 ⇒ 用名字直调（root=dialog 只走激活节点，
                //   实测 ^OneButtonWhite$ matches=1、Execute ok → 通知关、落三选一）。失败退坐标。
                val pr = runCatching { AgentApi.pressNode("^OneButtonWhite$", "dialog") }.getOrNull()
                if (pr?.optBoolean("ok") == true) {
                    AppState.addLog("对话框「OneButtonWhite」（直调 pressNode@Small）")
                    return true
                }
            }
        }
        // ★移动确认框也走直调（`MoveConfirm|TwoButtonRight`），失败才坐标点击
        if (shellNow == "DialogCommonSmall" && hit.optString("name") == "TwoButtonRight") {
            val pr = runCatching { AgentApi.labPathPress("MoveConfirm|TwoButtonRight") }.getOrNull()
            if (pr?.optBoolean("ok") == true) {
                AppState.addLog("确认框：前往（直调）")
                return true
            }
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
                }
            }
            if (px < 0) {
                // 学坐标：全树 find 一次（~1.1s，只此一次），成功即落盘
                val fxy = findNode(bn)
                if (fxy != null && dm2 != null) {
                    val c2 = appCtx
                    if (c2 != null) {
                        LabBtnStore.putXY(c2, "xy|dlg|$top|$bn", fxy.first, fxy.second, dm2.widthPixels, dm2.heightPixels)
                    }
                    px = fxy.first; py = fxy.second
                }
            }
            // ★2026-10-27 弹窗按钮也参与"点早了就下次多等"（普通档：一次 +300、上限 1200、超了清空）。
            //   实测病例（12:08:26）商店「关闭」连点 3 次同坐标才关掉 —— 弹窗刚出现时按钮还不吃点击。
            val dlgKey = "dlg|${top.removeSuffix("(Clone)")}|$bn"
            val dlgExtra = appCtx?.let { TapDelayStore.extra(it, dlgKey) } ?: 0L
            if (dlgExtra > 0) {
                AppState.addLog("… 弹窗「$bn」按学到的延迟先等 ${dlgExtra}ms 再点")
                delay(dlgExtra)
            }
            if (px > 0 && py > 0) {
                tap(input, px, py, "对话框「$bn」", learnKey = dlgKey)
                return true
            }
            tap(input, wl.optInt("x"), wl.optInt("y"), "对话框「$bn」", learnKey = dlgKey)   // 没学到 → 用宽名单坐标
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
            // ★静音"选角色面板"（2026-10-30 用户：日志噪音）：它的顶框也是 `DialogCommonFullSize`，
            //   里面只有 `reset_button` + 一堆 `ButtonObj`（12 颗），每局都会命中这条"都不在白名单"警告。
            //   它不是待确认的弹窗，是我们要操作的那一页自己，所以照样"不猜"，但不再刷日志。
            val names = named.map { it.optString("name") }
            val charSelectPanel = names.all { it == "reset_button" || it == "ButtonObj" }
            if (!charSelectPanel && tries.getOrPut("$tag|dlgu", { 0 }) == 0) {
                tries["$tag|dlgu"] = 1
                AppState.addLog(
                    "[!] 弹窗（顶框=$top）有 ${named.size} 颗按钮、都不在白名单 → 不猜（怕误点取消）｜ " +
                        "按钮=${names.joinToString(",").take(160)}",
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

    /** ★探针用：JSONArray 里的对象字段拼成紧凑字符串（字段名/名字 + 坐标） */
    private fun jArrCompact(o: JSONObject, key: String, fields: List<String>): String {
        val arr = o.optJSONArray(key) ?: return ""
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val s = fields.joinToString("/") { f ->
                jStr(it, f).ifEmpty {
                    val n = (it.opt(f) as? Number)
                    if (n != null && n.toDouble() % 1.0 == 0.0) n.toInt().toString() else jStr(it, f)
                }
            }
            if (s.isNotBlank()) out.add(s)
        }
        return out.joinToString("、")
    }

    /** LTD（`labStatus.choices`）里的候选简报：`序号:rewardId`，最多 6 条 —— 遗物选择排障用。
     *  它和"卡片上的 id"是**两条独立读法**（`LabyrinthStatus.Choices` vs 卡片 `choiceInfo`），
     *  两边都对得上 ⇒ 读数正常；只有卡片那侧是垃圾 ⇒ 是卡片路径坏了（2026-10-17 那次就是这个形状）。
     *
     *  ★`choices` 是**按需读**的（分派读里没有）⇒ 这儿现读一次（~0ms，纯内存读）。 */
    private suspend fun ltdChoicesBrief(): String {
        val a = extraIds()?.optJSONArray("choices") ?: return "(无字段)"
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

    /**
     * ★★**按需读：非分派字段**（ids）—— 见 [AgentApi.labExtra]。
     *
     * 分派读（`labStatusLite`）里**没有** eventId/questId/eventResultId/effectNum/curChoice/remainChoice
     * —— 那些只有个别 handler 用得到（事件页要 eventId、编组判定要 questId…）⇒ 要时再读。
     * 全是**内存读**，实测 ~0ms。
     */
    private suspend fun extraIds(): JSONObject? = runCatching { AgentApi.labExtra() }.getOrNull()

    /**
     * ★把**按需读**到的 ids 补进状态对象（就地补，省得下游再改签名）。
     *
     * 只在**真的要用**的那条线上调：编组判定（[PartyPlan.stageFor]）要看 questId 才知道
     * "这是不是 Boss 格"⇒ 不能每轮都读，但走到编组/预览那条线时必须补上。
     */
    private suspend fun fillIds(st: JSONObject): JSONObject {
        val x = extraIds() ?: return st
        for (k in arrayOf("questId", "eventId", "eventResultId", "effectNum", "curChoice", "remainChoice")) {
            if (x.has(k) && !x.isNull(k)) st.put(k, x.get(k))
        }
        return st
    }
}
