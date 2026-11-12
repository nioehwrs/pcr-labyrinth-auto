package com.lmj.labyrinthauto.reroll

import android.content.Context
import android.os.SystemClock
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.core.CenterTapGate
import com.lmj.labyrinthauto.core.LabBtnStore
import com.lmj.labyrinthauto.core.OverlayStatus
import com.lmj.labyrinthauto.frida.AgentApi
import com.lmj.labyrinthauto.frida.AgentBridge
import com.lmj.labyrinthauto.frida.GameProcess
import com.lmj.labyrinthauto.frida.RootShell
import com.lmj.labyrinthauto.input.Foreground
import com.lmj.labyrinthauto.input.InputBackend
import com.lmj.labyrinthauto.logic.Navigator
import com.lmj.labyrinthauto.logic.TapCheck
import com.lmj.labyrinthauto.logic.party.PartyPlan
import com.lmj.labyrinthauto.net.labyrinth.LabyrinthMapNode
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * ★**局内刷开局**（2026-10-18 用户："不调 API、不需要账号密码，代价是慢"）。
 *
 * 和 [RerollController]（走服务器 enter/retire）的区别：这里**完全用游戏内的正常入口**开一局，
 * 读地图判断，不满意就点「撤退（无报酬）」，满意就停在选人页直接接 [com.lmj.labyrinthauto.logic.MazeLoop]。
 * 优点：不需要登录凭证、不碰接口；缺点：每刷一次要过一遍游戏 UI（十几秒）。
 *
 * ## 页面/节点（2026-10-18 实测采集，1920×1080）
 * | 页面 | 识别 | 目标节点 |
 * |---|---|---|
 * | 259 顶部 | `ViewLabyrinthTop` | `labyrinth_thumb_start`（出发） |
 * | 公会选择 | `ViewLabyrinthGuildSetting(Clone)` | 每卡 `DepartureButton`；卡内 `GuildNameLabel` 用于认公会 |
 * | 公会确认 | 同上 View 之上的弹层 | `CancelButton` / `DifficultyChangeButton` / `StartButton` |
 * | 难度变更 | `PartsLabyrinthDifficultySettingPlate(Clone)` | 行内 `SelectDifficultyButton` + `DifficultyNumLabel`；底部 `TwoButtonRight`=变更 |
 * | 出发奖励 | 单按钮弹窗 | `OneButtonWhite`（关闭） |
 * | 地图 | `ViewLabyrinthMap(Clone)` | `MapButton` / `LabyrinthRetireButton` / `TwoButtonRight`（确认撤退） |
 * | 角色选择 | `DialogButtons` 底栏 | `DecideButton`（去邀请，交给 MazeLoop） |
 *
 * ★坐标一律用**节点自己报的**（[AgentApi.find] / [AgentApi.findList] 的 x/y）；
 *   ★按钮没出现时**在 [FIND_MS] 内每隔 [GAP_MS] 重找一次**（用户 2026-10-19 要求），不一次判死。
 */
object InGameReroll {

    /** 单次找按钮的总时限（超时才算失败） */
    private const val FIND_MS = 8_000L

    /**
     * ★**学习档**单次 `labBtn` 的调用时限（2026-10-31 修「撤退失败 → 局内刷开局停手」）。
     *
     * `labBtn` 分两态：命中已学路径 = 几十 ms；**第一次学路径**要遍历"当前 View + DialogArea 子树"。
     * 慢机器/大页面上实测学到 **3~7s**（日志：`[slow] labBtn 7064ms`），而原来一律按 3s 卡超时 ⇒
     * 结果被丢掉、路径**也存不下来**（落盘只在拿到回包时做）⇒ 下一轮又从头学 ⇒
     * 每轮都是"重学 + 超时"，最后「撤退」连点 3 次都没读到确认框、8s 找不到按钮就停手。
     * 0.4.0 重装后缓存为空（缓存在 `filesDir/lab_btn.json`，卸载即清），必然踩这个坑。
     */
    private const val LEARN_MS = 12_000L

    /** 重找间隔（labBtn 命中路径只有几十 ms，所以可以很短；学习时会自然慢一点） */
    private const val GAP_MS = 200L

    /** ★统一的"等状态变化"轮询间隔：页面/弹窗/难度一变就继续，不再固定睡 N 毫秒。
     *  实测页面切换/弹窗出现大约 150~400ms，200ms 足够且不浪费。 */
    private const val POLL_MS = 200L

    /**
     * ★"刚点完/刚切页"的固定小延迟（2026-10-19 用户：点太快的地方加 100~200ms）。
     * 只用在**没有状态可等的**场合（点完地图、关完弹窗这类切页动作），
     * 能轮询的地方一律轮询（[POLL_MS]），不靠睡。
     */
    private const val SETTLE_MS = 200L

    /** ★点完"关闭"类按钮后，等弹窗**淡出**的时间（弹窗是渐隐的，等太短会误判成没关掉） */
    private const val DLG_CLOSE_MS = 700L

    /** ★难度弹窗里找目标行时最多上滑几次（2026-10-19：滑 6 次 = 15s 纯浪费，先降到 2） */
    private const val DIFF_SCROLL_MAX = 2

    /** 公会列表是否被拖过（选过靠后的公会）；下次选人前先拖回最左 */
    private var guildListScrolled = false

    /** ★上一次 run 实际刷到的轮数（结束提示"跑了 N 轮"用；run 开始时清零） */
    @Volatile
    var lastAttempt = 0
        private set

    /**
     * ★true = 上一局**按策略作废**（目前是「凹分模式」分数不足主动撤退，2026-11-01）⇒
     * 下一局即使当前这局路线合格也**不许沿用**，直接撤退重刷。
     *
     * 不这么拦就会死循环：废局的路线本来就合格（刷开局那套判据只看路线/价值/Boss），
     * 沿用 = 再跑一遍注定要撤的图。[MazeLoop] 撤退没撤干净时置位，[takeoverCurrentMaze] 消费后清零。
     */
    @Volatile
    var forceRerollNext = false

    /**
     * 供撤退模块 [com.lmj.labyrinthauto.logic.MazeRetreat] 复用：**按现场**把当前局撤掉。
     *
     * 分流（每种现场都有对应的一条链，不用调用方关心）：
     *  · 战斗失败页 → [retreatFromFailPage]（点「结束」→「撤退（无报酬）」）
     *  · 战斗/任务详情面板 → **不点**（战斗进行中打断不安全，返回 false 让调用方清现场）
     *  · 259 top / 地图页 / 选人面板 / 其他面板 → [retreat]（先收敛现场，再走「地图」→「撤退」→「确认」）
     *
     * @return true = 已回到 259（本局按"撤退无报酬"作废，不计完成）
     */
    suspend fun retreatNow(input: InputBackend): Boolean {
        val top = AgentApi.uiSig()?.optString("top").orEmpty()
        if (top.contains("QuestFailed")) return retreatFromFailPage(input)
        if (classifyScene() == Scene.BATTLE) {
            AppState.addLog("撤退：当前在战斗/任务面板 → 不在战斗中强行点，交给上层清现场")
            return false
        }
        return retreat(input)
    }

    /**
     * ★**固定路径表**（落盘 `files/ui_btn_paths.json`）：`key → (root, path)`。
     * UI 层级在同一个游戏版本内是固定的 ⇒ 第一次学完就存下来，之后（含 app 重启）
     * 直接把路径传给 agent 直达，**完全不用再学/扫**；只有节点名校验失败（游戏更新）才重学覆盖。
     */
    private var appCtx: Context? = null

    /** 迷宫公会 id → 公会选择页上的名字（卡内 `GuildNameLabel` 的文字）。
     *  ★2026-11-13 维护新增 5 家：id 暂按公告顺序假设为 6..10（与 `RerollSettings.GUILD_IDS` 对齐）。 */
    private val GUILD_CN = mapOf(
        1 to "美食殿堂",
        2 to "破晓之星",
        3 to "咲恋救济院",
        4 to "王宫骑士团",
        5 to "拉比林斯",
        6 to "慈乐之音",
        7 to "恶魔伪王国军",
        8 to "伊丽莎白牧场",
        9 to "墨丘利财团",
        10 to "暮光流星群",
    )

    /**
     * 跑一次「局内刷开局」：成功时 [RerollState.result] 就位，且游戏**停在角色选择页**（可直接选人）。
     *
     * @return true = 刷到合格开局
     */
    /** 前台被切走时的中止信号（用户 2026-11-09："切应用自动中止"）。 */
    private class AbortedException(msg: String) : Exception(msg)

    suspend fun run(input: InputBackend, ctx: Context): Boolean = try {
        runBody(input, ctx)
    } catch (e: AbortedException) {
        AppState.addLog("局内刷开局：${e.message} → **已中止**（不再点游戏）")
        false
    } finally {
        // 一次运行结束就收闸，别把"切屏"记到下一局去
        Foreground.disarmSwitchWatch()
    }

    private suspend fun runBody(input: InputBackend, ctx: Context): Boolean {
        // ★局内刷初始前也关"屏幕中心加速"（2026-10-22 用户）：刷完停在选人页，不能带上一局的开关。
        CenterTapGate.reset("局内刷初始前")
        val cfg = RerollSettings.loadConfig(ctx)
        // ★Boss 白名单判定要用的关卡表（labyrinth_boss.json → questId → unitId）：
        //   接口刷（RerollUseCase）和跑迷宫（MazeLoop）各自会加载，**局内刷这条路径原来不加载**
        //   ⇒ bossMap 空 ⇒ Boss 全是 null，而且白名单判定被整体跳过（只按价值判合格）。
        //   2026-10-19 实测日志：`3区Boss=null 5区Boss=null` + logcat `questId=770331801 → unitId=null`。
        PartyPlan.loadTables(ctx)
        // ★「最大次数」选"不限"（-1）就是**不限**，别偷偷压成 50（2026-10-19 用户发现：
        //   界面上明明有"不限/30/50/100"，局内刷却写死 50 ⇒ 和接口刷的口径对齐）
        val unlimited = cfg.maxCount <= 0
        val nMax = if (unlimited) 9_999 else cfg.maxCount

        AppState.addLog(
            "── 局内刷开局：目标 公会=${GUILD_CN[cfg.guildId] ?: cfg.guildId} 难度=${cfg.difficulty} " +
                "上限=${if (unlimited) "不限" else "$nMax 次"} ──",
        )
        lastAttempt = 0
        guildListScrolled = false
        appCtx = ctx.applicationContext

        // ★第 0 步：先进游戏（没启动就启动、不在前台就切过去、页面不认识就报一声）
        if (!ensureGameReady(ctx)) return false

        // ★执行时可能**正在迷宫里**（上次跑到一半 / 手动开的局）——接口刷的语义是
        //   "先检查当前局合不合格，不合格撤退"，局内刷也对齐（2026-10-19 用户）：
        //   合格 → 沿用当前局直接结束；不合格 → 从地图撤退回 259 再正常刷；
        //   战斗中/退不到地图 → 停手（战斗中撤退不安全）。
        when (takeoverCurrentMaze(input, cfg)) {
            true -> return true
            false -> return false
            null -> Unit   // 不在迷宫（或已撤退回 259）→ 走正常"到 top → 开新局"流程
        }

        var atTop = Navigator.goToLabyrinthTop(input, ctx = ctx)
        if (!atTop) {
            // 实测 2026-10-19：跑到一半前台被打回助手（前台=com.lmj.labyrinthauto）→ 顶起游戏再来一次
            AppState.addLog("局内刷开局：导航没到 top → 重新顶起游戏再试一次")
            GameProcess.launch(ctx)
            delay(2_500)
            atTop = Navigator.goToLabyrinthTop(input, ctx = ctx)
        }
        if (!atTop) {
            AppState.addLog("局内刷开局：还是没走到黎明界 top → 停手")
            return false
        }

        aborted = false
        // ★2026-11-10：启动/导航都结束、游戏确实在前台了才开切屏闸门 ——
        //   从这一刻起，任何一次前台读取只要明确读到"不是游戏"就锁存，直到本次运行结束；
        //   放这么晚是为了不把启动阶段（前台本来是助手、脚本自己还要把游戏顶起来）误判成切屏。
        Foreground.armSwitchWatch(GameProcess.pkg())
        for (i in 1..nMax) {
            // ★2026-11-09（用户要求）：**切应用自动中止** —— 前台不是游戏就整条链停手。
            if (!frontOk()) return false
            lastAttempt = i                    // ★结束提示"跑了 N 轮"用（service 的 finally 读）
            AppState.addLog("局内刷开局·第 $i/$nMax 次")
            // ★悬浮窗第二格（2026-10-19 用户）：刷开局阶段写"正在刷第几轮"
            //   （后面 MazeLoop 接管时会改成"第 N 层"，不冲突；"不限"就不带分母）
            OverlayStatus.setFloor(if (unlimited) "刷开局 第 $i 轮" else "刷开局 $i/$nMax 轮")
            if (!enterOnce(input, cfg.guildId, cfg.difficulty)) {
                AppState.addLog("局内刷开局·第 $i 次：进迷宫失败 → 停手")
                return false
            }
            // ★刚进图/刚关掉出发奖励，地图数据可能还没刷出来 ⇒ 先给一拍再读（[SETTLE_MS]）
            delay(SETTLE_MS)
            val nodes = waitMap()
            if (nodes == null) {
                AppState.addLog("局内刷开局·第 $i 次：等不到地图数据 → 停手")
                return false
            }
            val j = RerollJudge.judge(nodes, cfg)
            if (j.ok) {
                // ★悬浮窗第三格：合格了就写一句好消息（下一局开跑前都看得见）
                OverlayStatus.setState("合格 ✓ ${j.summary}")
                RerollState.result = RerollResult.Success(
                    enterId = 0L,                       // 局内开的不一定有 enter_id（不对外暴露），迷宫侧不用它
                    guildId = cfg.guildId,
                    difficulty = cfg.difficulty,
                    nodes = nodes,
                    bossByArea = j.bossByArea,
                    routePerArea = j.routePerArea,
                    actual = j.actual,
                    theory = j.theory,
                    summary = j.summary,
                )
                AppState.addLog(
                    "局内刷开局完成（第 $i 次）：3区Boss=${PartyPlan.bossName(j.bossByArea[3]) ?: "?"} " +
                        "5区Boss=${PartyPlan.bossName(j.bossByArea[5]) ?: "?"} " +
                        "${j.summary} → 停在选人页，直接开始",
                )
                return true
            }
            val why = j.fail.joinToString("；")
            AppState.addLog("局内刷开局·第 $i 次不合格：$why → 撤退重来")
            // ★悬浮窗第三格（2026-10-19 用户）：**上一次**不合格的理由，一直留到下一轮判定出来为止
            OverlayStatus.setState("不合格：$why")
            if (!retreat(input)) {
                AppState.addLog("局内刷开局：撤退失败 → 停手")
                return false
            }
            // ★2026-11-09：600 → SETTLE_MS(200)。下一轮 [enterOnce] 开头还会等页面，不需要在这里多睡。
            delay(SETTLE_MS)
        }
        AppState.addLog("局内刷开局：$nMax 次都不合格 → 停手")
        return false
    }

    // ────────────────────────── 第 0 步：进游戏 ──────────────────────────

    /**
     * 进游戏（2026-10-19 用户："第一步是要切到游戏内，如果游戏没启动就启动一下，
     * 如果当前所在页面不认识就报一下，然后走之前进入黎明界 top 的流程"）。
     *
     * 顺序：进程 → 前台 → 页面；只有前两步会拦（起不来/切不过去 = 真没法继续），
     * 页面不认识**只报一声**，后面交给 [Navigator.goToLabyrinthTop] 自己找路。
     */
    private suspend fun ensureGameReady(ctx: Context): Boolean {
        // ① 进程在不在
        var pid = gamePid()
        if (pid.isEmpty()) {
            AppState.addLog("局内刷开局：游戏没在跑 → 启动它")
            GameProcess.launch(ctx)
            val t0 = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - t0 < 30_000L) {
                pid = gamePid()
                if (pid.isNotEmpty()) break
                delay(1_000)
            }
            if (pid.isEmpty()) {
                AppState.addLog("局内刷开局：启动后 30s 仍没有游戏进程 → 停手")
                return false
            }
            AppState.addLog("局内刷开局：游戏已起 pid=$pid，等它初始化")
            delay(3_000)
        }

        // ② 切到前台（am start 对已存在的任务就是"带到前台"）
        if (!bringGameFront(ctx)) return false

        // ②.5 ★等 agent 连上（2026-10-19 实测：切前台后注入还要 1~3s，
        //   不等就立刻读页面 ⇒ viewId=-1 ⇒ 导航直接"agent 没连上"收摊）
        if (!waitAgent(60_000L)) {
            AppState.addLog("局内刷开局：60s 内 agent 没连上（注入失败/游戏没起来）→ 停手")
            return false
        }

        // ②.6 注入期间前台可能又被打回助手 ⇒ 再顶一次游戏
        if (!bringGameFront(ctx)) return false

        // ③ 当前页面认不认识 —— 不认识只报一声，继续走导航
        val vid = Navigator.currentViewId()
        if (vid < 0) {
            AppState.addLog("局内刷开局：当前页面读不到（viewId=$vid）→ 交给导航流程自己找路")
        } else {
            val known = vid == Navigator.TARGET_VIEW || Navigator.isLabyrinthView(vid) || vid in 0..2
            AppState.addLog(
                "局内刷开局：当前页面 ${Navigator.viewLabel(vid)}" +
                    if (known) "" else "（**不认识**，仍按导航流程走）",
            )
        }
        return true
    }

    /** 游戏进程 pid（读不到/非数字 ⇒ 空串） */
    private suspend fun gamePid(): String {
        val t = RootShell.exec("pidof ${GameProcess.pkg()}", 8_000L).text.trim()
        return if (t.isNotEmpty() && t.all { it.isDigit() }) t else ""
    }

    /** 把游戏顶到前台（已在就秒过）；切不过去返回 false。 */
    private suspend fun bringGameFront(ctx: Context): Boolean {
        if (Foreground.isGameForeground(GameProcess.pkg())) return true
        AppState.addLog("局内刷开局：游戏不在前台 → 切过去")
        GameProcess.launch(ctx)
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < 20_000L) {
            if (Foreground.isGameForeground(GameProcess.pkg())) return true
            delay(700)
        }
        AppState.addLog("局内刷开局：20s 内没切到游戏前台 → 停手")
        return false
    }

    /** 等 agent 连上（注入要几秒；连上才有 viewId / 节点可读）。 */
    private suspend fun waitAgent(timeoutMs: Long): Boolean {
        if (AgentBridge.connected.value) return true
        AppState.addLog("局内刷开局：等 agent 注入连上…")
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            if (AgentBridge.connected.value) return true
            delay(500)
        }
        return false
    }

    // ────────────────────────── 单次：259 → 进图 ──────────────────────────

    /**
     * 从 259 开一局：
     *  - 挑战中 → 点出发直接进图（无弹窗）；
     *  - 未挑战 → 出发 → 公会选择 → 确认（必要时改难度）→ 出发 → 关掉出发奖励。
     */
    private suspend fun enterOnce(input: InputBackend, guildId: Int, difficulty: Int): Boolean {
        // ★刚到 259 时页面还在过渡，立刻点会被吞（2026-10-19 实测 01:24:19）。
        delay(250)
        val fromTop = Navigator.currentViewId() == Navigator.TARGET_VIEW
        var vid = 264
        // 链A：259 点「出发」→ 出口只可能是 263（公会选择）或 264（挑战中直接进图）
        if (fromTop) vid = chainStart(input) ?: return false
        // 链B：263 公会选择 → 出口一定是 264
        if (vid == 263) {
            if (!chainGuild(input, guildId, difficulty)) return false
            vid = 264
        }
        if (vid != 264) {
            AppState.addLog("[✗] 进局：本该在 264，实际 viewId=$vid → 失败")
            return false
        }
        // 链C：入场弹窗 → 出口一定是「选人面板就绪」
        return chainEntryPanels(input, resumed = !fromTop)
    }

    /**
     * 【链 A】259 点「出发」——**入口唯一的链**。
     *
     * 出口只有两个可能：**263（公会选择）** 或 **264（挑战中 / 直接进图）**。
     * 点空了（没换页）就再点，最多 3 次。
     */
    private suspend fun chainStart(input: InputBackend): Int? {
        val b = findBtn("labyrinth_thumb_start", FIND_MS)
        if (b == null) {
            AppState.addLog("局内刷开局：${FIND_MS / 1000}s 内没找到「出发」")
            return null
        }
        // ★2026-11-09：统一走 [tapToView]（= TapCheck）—— 点完只认"页面真的变了"，
        //   点空立刻重试（原来 `tapNode` + `waitView(4s)` 点空要白等 4 秒）。
        val ok = tapToView(
            input,
            b.optInt("x"),
            b.optInt("y"),
            "出发",
            from = Navigator.TARGET_VIEW,
            expect = setOf(263, 264),
            expectTimeoutMs = 4_000L,
            retries = 3,
        )
        val vid = Navigator.currentViewId()
        if (ok) {
            AppState.addLog("局内刷开局：链A（出发）完成 → 出口 viewId=$vid")
            return vid
        }
        AppState.addLog("[✗] 链A：连点 3 次「出发」都没离开 ${Navigator.TARGET_VIEW}（viewId=$vid）")
        return null
    }

    /**
     * 【链 B】263 公会选择 → 选公会 → 确保难度 → 确认出发。
     *
     * **出口一定在 264**（用户 2026-10-31："选公会链结束一定在入场链"）。
     * 出口不符就报错，不再"猜着往下走"。
     */
    private suspend fun chainGuild(input: InputBackend, guildId: Int, difficulty: Int): Boolean {
        AppState.addLog("局内刷开局：链B（选公会）开始")
        if (!selectGuild(input, guildId)) return false
        // 不睡固定时间：ensureDifficulty 内部读难度本身就是"等它建好"的轮询
        if (!ensureDifficulty(input, difficulty)) return false
        if (!tapStartConfirm(input)) return false
        val vid = waitView(setOf(264), 6_000L)
        if (vid != 264) {
            AppState.addLog("[✗] 链B 出口不对：确认出发后应在 264，实际 viewId=$vid")
            return false
        }
        AppState.addLog("局内刷开局：链B（选公会）完成 → 出口 264")
        return true
    }

    /**
     * 【链 C】入场链：逐层关掉白名单弹窗（出发奖励 / 公会专属遗物 / 印记…）。
     *
     * **出口一定是「选人面板就绪」**（用户 2026-10-31："入场链结束一定在选人面板"）——
     * 判据是 `labCharaPageQuick.ok && buttons.map 坐标 > 0`，不是"top 为空"。
     *
     * ★面板与面板之间有 `top == null` 的过渡帧：这里只**等**，不点、不当作到站。
     * ★唯一例外：`resumed=true`（挑战中直接进图、上一局可能停在半路）时，
     *   "稳定无面板"也算到站，否则会白等 30s。
     */
    private suspend fun chainEntryPanels(
        input: InputBackend,
        resumed: Boolean,
        timeoutMs: Long = 30_000L,
    ): Boolean {
        val whitelist = setOf("OneButtonWhite", "TwoButtonRight", "ThreeButtonCenter", "OneButtonBlue")
        val t0 = SystemClock.elapsedRealtime()
        var taps = 0
        var unknownSaid = false
        var looseHits = 0
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            if (!frontOk()) return false
            val q = AgentApi.labCharaPageQuick()
            val mx = q?.optJSONObject("buttons")?.optJSONObject("map")?.optInt("x", -1) ?: -1
            if (q?.optBoolean("ok") == true && mx > 0) {
                AppState.addLog("局内刷开局：链C（入场）完成 → 选人面板已就绪（本链关了 $taps 层弹窗）")
                return true
            }
            // ★兜底只用于"**面板确实在**、但坐标读不出来"这一种情况（2026-10-31 13:52 白等 30s）：
            //   与 MazeLoop 完全相同的判据 —— `statusType==1` 且有顶框 —— 并且**要连续 3 拍成立**
            //   （≈1.5s），不吃瞬时帧。坐标读不出不影响后续：撤退阶段 `dismissCharaPanel` 自己轮询等。
            val st = AgentApi.labStatus()
            val top = st?.optString("pageTop").let { t -> if (t == null || t == "null") "" else t }
            if (st != null && st.optInt("statusType", -1) == 1 && top.isNotEmpty()) {
                looseHits++
                if (looseHits >= 3) {
                    AppState.addLog(
                        "局内刷开局：链C（入场）完成 → 选人页已在（statusType=1 顶框=$top 连续 $looseHits 拍；" +
                            "坐标严格判据未通过：ok=${q?.optBoolean("ok")} err=${q?.optString("err")}）",
                    )
                    return true
                }
            } else {
                looseHits = 0
            }
            // ★2026-11-09：`dialog()` 在选人面板（DialogCommonFullSize）上实测 **5004ms**，
            //   而这里是 200ms 一拍 ⇒ 直接把 agent 单通道堵死（viewId 3s 超时 → 判掉线 →
            //   反复原地重注入，最后一局都没跑起来）。这里只需要「顶框 + 白名单按钮坐标」，
            //   用字段直取的 labDlgBtn（~50ms）完全等价。
            val d = AgentApi.labDlgBtn("")
            val dlgTop = d?.optString("top").orEmpty()
            if (dlgTop.isNotEmpty()) {
                val a = d?.optJSONArray("buttons")
                var hit: JSONObject? = null
                if (a != null) {
                    for (i in 0 until a.length()) {
                        val b = a.optJSONObject(i) ?: continue
                        if (whitelist.contains(b.optString("name"))) { hit = b; break }
                    }
                }
                if (hit != null && taps < 16) {
                    taps++
                    // ★★2026-11-09（和"三选一"同一思路）：**每次都点，坐标用这一拍现读的**。
                    //   弹窗展开动画里读到的坐标会漂（实测 y 944→960）⇒ 第一下会点空；
                    //   但**下一拍重读就是新坐标**，直接补点即可 —— 不需要"先等坐标稳定"（白等 200ms）。
                    //   点完只探 450ms：**检测到"开始关闭"就停**，没关就下一拍重取坐标再点。
                    AppState.addLog(
                        "局内刷开局：链C·点掉一层「${hit.optString("name")}」" +
                            "(${hit.optInt("x")},${hit.optInt("y")})",
                    )
                    input.tap(hit.optInt("x"), hit.optInt("y"))
                    awaitUntil(450L) {
                        val c = AgentApi.labDlgClosing()
                        c == null || c.optBoolean("closing", false) ||
                            c.optString("top").orEmpty() != dlgTop
                    }
                    continue
                }
                if (hit == null && !unknownSaid) {
                    unknownSaid = true
                    AppState.addLog("[!] 链C：弹窗里没有白名单按钮（${pageSig()}）→ 只等不点")
                }
            } else if (resumed && stableTopGone(2_500L)) {
                AppState.addLog("局内刷开局：链C（入场）→ 挑战中且没有选人面板，按到站处理")
                return true
            } else if (!unknownSaid && top.isNotEmpty()) {
                // ★2026-10-31 13:52:37 的坑：顶框已经是 DialogCommonFullSize（选人面板就在屏幕上），
                //   但 labCharaPageQuick 一直不 ok、dialog() 又返回 open=false ⇒ 整段 30s 全在空等。
                //   这里把"到底读到什么"打一次出来，便于下次直接定位。
                unknownSaid = true
                AppState.addLog(
                    "[!] 链C：顶框=$top 但读不出面板（labCharaPageQuick.ok=${q?.optBoolean("ok")}" +
                        " err=${q?.optString("err")} node=${q?.optString("node")}" +
                        " dialog.top=$dlgTop）→ 只等不点",
                )
            }
            delay(POLL_MS)
        }
        AppState.addLog("[✗] 链C 超时：${timeoutMs / 1000}s 内没等到选人面板（${pageSig()}）")
        return false
    }

    /**
     * 公会确认弹窗上的「出发（StartButton）」。
     *
     * ★为什么不能像别的按钮那样"找到就点"（2026-10-19 实测 01:31:12~01:31:19）：
     *   选完公会 **0s** 就点 ⇒ 确认弹窗还在往下滑，读到的坐标 (1415,954) 比稳定值 (1410,950)
     *   偏了几像素 ⇒ 一下点空；而原来只点一次、之后只干等 ⇒ 一直卡在公会页 263，
     *   最后 12s 找不到「关闭出发奖励」、判"没进到地图"收摊。
     *
     * 两步：①先等弹窗**铺稳**（坐标连续两次一致）；②点了**没离开公会页就再点**（最多 3 次）。
     */
    private suspend fun tapStartConfirm(input: InputBackend): Boolean {
        waitStable("StartButton")
        val b = findBtn("StartButton", 2_000L) ?: return false
        // ★2026-11-09：统一走 [tapToView]，并用「确认框开始消失」判这一下生效
        //   （弹窗是本地的先关，比 viewId 变早）⇒ 点空立刻重试，不再死等 4s。
        return tapToView(
            input,
            b.optInt("x"),
            b.optInt("y"),
            "确认出发",
            from = 263,
            expect = setOf(264),
            dialogClosing = true,
            expectTimeoutMs = 5_000L,
            retries = 3,
        )
    }

    /**
     * ★2026-11-09：通用"等状态成立"——每 [POLL_MS] 试一次 [cond]，成立就立刻返回。
     *
     * 专门用来替代"点完固定睡 400ms"这类无用等待：能轮询的一律轮询（见文件头注释的口径），
     * 状态早就成立时是 **0 等待**，没成立时最多等到 [timeoutMs]（不比原来差）。
     */
    private suspend fun awaitUntil(timeoutMs: Long, cond: suspend () -> Boolean): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            if (cond()) return true
            if (SystemClock.elapsedRealtime() - t0 >= timeoutMs) return false
            delay(POLL_MS)
        }
    }

    /**
     * ★2026-11-09：刷开局的「点一下 → 等页面转移」统一走 [TapCheck]（和迷宫主循环同一套语义），
     * 不再手写 `tapNode` + `waitView(4~6s)` —— 那样点空也要死等超时。
     *
     *  · **阶段 1**：点完按 probe 节奏探"页面变了没"；[dialogClosing] = true 时改看
     *    「弹窗开始消失」（`labDlgClosing`，本地的，比 viewId 变还早）
     *    ⇒ 点空会**立刻重试**（retries），点到了立刻停手。
     *  · **阶段 2**：只等目标页出现（`expectTimeoutMs`）。
     *
     * @param from 点击前的 viewId
     * @param expect 期望到达的 viewId 集合
     * @param dialogClosing 这次点击是"关一个弹窗" ⇒ 用「窗口开始消失」判生效
     */
    private suspend fun tapToView(
        input: InputBackend,
        x: Int,
        y: Int,
        what: String,
        from: Int,
        expect: Set<Int>,
        dialogClosing: Boolean = false,
        expectTimeoutMs: Long = 5_000L,
        retries: Int = 3,
    ): Boolean {
        val fromS = from.toString()
        val expectS = expect.map { it.toString() }.toSet()
        return TapCheck.run(
            tapFn = { tx, ty, _ ->
                // ★2026-11-09：切应用就停手（返回 false ⇒ TapCheck 不会再补点）
                if (!frontOk()) {
                    false
                } else {
                    AppState.addLog("局内刷开局：点「$what」($tx,$ty)")
                    input.tap(tx, ty) is com.lmj.labyrinthauto.input.InputResult.Ok
                }
            },
            x = x,
            y = y,
            what = what,
            from = setOf(fromS),
            expect = expectS,
            // ★2026-11-09：重试节奏收紧 —— 原来 200×4=0.8s 才补点，用户实测"第二次点击明显慢"。
            //   现在 150/300/450ms 三拍没反应就补点（≈0.45s，用户要的 0.5s 量级）；
            //   命中路径不受影响（第 1~2 拍就停手）。
            settleMs = 150L,
            probe = 3,
            probeMs = 150L,
            retries = retries,
            expectTimeoutMs = expectTimeoutMs,
            readTop = { Navigator.currentViewId().toString() },
            probeState = {
                val v = Navigator.currentViewId().toString()
                var closed = false
                var diag = ""
                if (dialogClosing) {
                    val c = AgentApi.labDlgClosing()
                    if (c != null) {
                        closed = c.optBoolean("closing", false) || c.optString("top").isEmpty()
                        // ★2026-11-09（诊断）：`Probe.diag` 会进 `⏱ probe` 轨迹日志 ——
                        //   用来确认「选人面板 DialogCommonFullSize」关闭时有没有比"顶框变空"
                        //   更早的可读信号（`proc`=isProcess / `st`=tween.mStarted / `f`=mFactor）。
                        diag = "proc=${c.optInt("proc")} st=${c.optInt("started")} " +
                            "f=${c.optDouble("factor")} top=${c.optString("top")}"
                    }
                }
                TapCheck.Probe(v, v != fromS || closed, diag)
            },
        )
    }

    /** 等 viewId 变成 [want] 里的任意一个（超时返回当前值）。 */
    private suspend fun waitView(want: Set<Int>, timeoutMs: Long): Int {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val v = Navigator.currentViewId()
            if (v in want) return v
            delay(POLL_MS)
        }
        return Navigator.currentViewId()
    }

    /**
     * 公会选择页选公会。
     *
     * ⚠2026-10-19 实测：**救济院第一屏就在**，但按 `GuildNameLabel` 文字匹配失败 ⇒ 白白拖了一屏
     * （拖完顺序变了，还在可能点错卡）。原因多半是文字读取为空/写法不同。
     * 所以：**列表在最左时直接按卡片顺序点**（第 guildId 张 = 该公会），文字只用来"确认/兜底"；
     * 只有目标卡根本不在屏上（一屏只露 4 张）才拖。
     * ★2026-11-13 后共 10 家公会 ⇒ 拖动轮数放宽到 12，够覆盖最后一家的卡。
     */
    private suspend fun selectGuild(input: InputBackend, guildId: Int): Boolean {
        val want = GUILD_CN[guildId] ?: return false

        // ① 先看**当前位置**有没有（列表会保持上次选公会的大致位置，常常一读就中）
        var btns = readCards()
        findGuildCard(btns, want)?.let { return tapGuildCard(input, it, want) }

        // ② 不在可见区 ⇒ 先**往右拖到最左**（左边可能还有被推出的卡）。
        //    ★2026-11-09 用户："没有左划可能是个漏洞" —— 以前只会往左翻，
        //    列表停在右边、目标在左边时永远找不到。这里先回最左，再统一往左翻。
        var backSteps = 0
        while (backSteps++ < 12) {
            if (!frontOk()) return false
            val leftmost = btns.minOfOrNull { it.optInt("x", 9999) } ?: 9999
            if (leftmost <= 350) break                    // 已经到最左（第一张 x≈287）
            AppState.addLog(
                "局内刷开局：「$want」不在可见区 → 往右拖回左边" +
                    "（最左 x=$leftmost，可见=${namesBrief(btns)}）",
            )
            input.swipe(450, 605, 1_450, 605, 500)        // 内容右移 = 露出左边
            delay(300)
            btns = readCards()
            findGuildCard(btns, want)?.let { return tapGuildCard(input, it, want) }
        }

        // ③ 从最左开始**往左翻**，逐屏找
        var fwdSteps = 0
        while (fwdSteps++ < 12) {
            if (!frontOk()) return false
            AppState.addLog("局内刷开局：「$want」不在可见区 → 往左翻（可见=${namesBrief(btns)}）")
            input.swipe(1_450, 605, 450, 605, 500)        // 内容左移 = 露出右边
            delay(300)
            btns = readCards()
            findGuildCard(btns, want)?.let { return tapGuildCard(input, it, want) }
        }

        AppState.addLog("局内刷开局：找不到公会「$want」（回拖 ${backSteps - 1} 屏 / 左翻 ${fwdSteps - 1} 屏）→ 失败")
        return false
    }

    /** 公会名归一化：卡上实测会读成「破晓 之星」「王宫骑士团 （NIGHTMARE）」，去掉空白再比。 */
    private fun normGuildName(s: String): String =
        s.replace("\n", "").replace(" ", "").replace("　", "")

    private fun findGuildCard(btns: List<JSONObject>, want: String): JSONObject? {
        val w = normGuildName(want)
        if (w.isEmpty()) return null
        return btns.firstOrNull { normGuildName(it.optString("name")).contains(w) }
    }

    private fun namesBrief(btns: List<JSONObject>): String =
        btns.joinToString(" ") { it.optString("name") }.take(120)

    /** 点目标卡；点了没弹确认框就**重读、同一张再试**（绝不换卡 —— 相邻那张是别的公会）。 */
    private suspend fun tapGuildCard(input: InputBackend, b: JSONObject, want: String): Boolean {
        AppState.addLog("局内刷开局：选公会「$want」（按名字）(${b.optInt("x")},${b.optInt("y")})")
        if (tapCard(input, b)) return true
        AppState.addLog("局内刷开局：点了没反应 → 重读列表再试同一张（不换卡）")
        delay(POLL_MS)
        val b2 = findGuildCard(readCards(), want)
        if (b2 != null) {
            AppState.addLog("局内刷开局：重试 (${b2.optInt("x")},${b2.optInt("y")})")
            if (tapCard(input, b2)) return true
        }
        return false
    }

    /**
     * ★一次**原始**读取（字段直取 → 兜底 findList），**不等位置稳定**。
     *
     * 字段直取：`labGuildCards`（实测 9ms，替代 `findList` 的 500~916ms）。
     * ★2026-11-10 恢复旧判据：**半个按钮不算在屏幕内**。
     *   36ea6f0 那次把 `W`/`H` 放宽了 ±60，还把 `onscreen=false` 的半露卡坐标照收，
     *   ⇒「王宫骑士团」只露半张（中心 x=1907 / W=1920）也被按名字选中去点，
     *   点在裁掉的那半边没反应，重读重试还是同一张 ⇒ 整轮刷开局失败。
     *   现在只认 `on=true` 且**整个按钮在屏内**的卡；半露卡照旧走拖动流程等它完整露出。
     */
    private suspend fun readCardsRaw(): List<JSONObject> {
        val r = runCatching { AgentApi.labGuildCards() }.getOrNull()
        val w = r?.optInt("W", 1920) ?: 1920
        val h = r?.optInt("H", 1080) ?: 1080
        val arr = r?.optJSONArray("cards")
        if (r != null && r.optBoolean("ok") && arr != null) {
            val raw = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                // `on` = agent 算出的「按钮中心在屏内」；中心都不在 ⇒ 半露/屏外，本来就不算
                if (c.has("on") && !c.optBoolean("on")) continue
                if (c.optInt("x", -9999) == -9999) continue
                raw.add(c)
            }
            val out = fullyOnScreen(raw, w, h)
            if (out.size >= 2) return out
        }
        // 兜底：字段直取拿不到时，从**公会设置对话框自己的子树**找（`root="dialog"`，几十节点）。
        // ★2026-11-12（用户："明确树结构就不要 find 了"）：不再走整棵 UI 树（661 节点 ≈1.8s）。
        //   findList 已按中心 onscreen 过滤，但仍要剔掉「只露半个按钮」的卡，否则会点空。
        var list = sortedByX(AgentApi.findList("DepartureButton", "dialog"))
        var waited = 0L
        while (list.size < 2 && waited < 1_500L) {
            delay(POLL_MS)
            waited += POLL_MS
            list = sortedByX(AgentApi.findList("DepartureButton", "dialog"))
        }
        return fullyOnScreen(list, w, h)
    }

    /**
     * ★「整个按钮在屏幕内」才算可见。
     * 卡宽由相邻卡中心距推得（实测 4 张为 287/827/1367/1907 ⇒ 间距 540、半宽 270）；
     * 中心离屏幕左右缘不足半宽 ⇒ 按钮有一半被裁掉，不算在屏幕内。
     * 算不出间距（只剩一张卡）时退化为只看中心，与旧 `findList` 的 onscreen 判据一致。
     */
    private fun fullyOnScreen(list: List<JSONObject>, w: Int, h: Int): List<JSONObject> {
        val xs = list.map { it.optInt("x", -9999) }.sorted()
        var gap = 0
        for (i in 1 until xs.size) {
            val d = xs[i] - xs[i - 1]
            if (d in 120..900 && (gap == 0 || d < gap)) gap = d
        }
        val half = gap / 2
        val out = ArrayList<JSONObject>()
        for (c in list) {
            val cx = c.optInt("x", -9999)
            val cy = c.optInt("y", -9999)
            if (cx < 0 || cx > w || cy < 0 || cy > h) continue
            if (half > 0 && (cx - half < 0 || cx + half > w)) continue
            out.add(c)
        }
        out.sortBy { it.optInt("x") }
        return out
    }

    /**
     * ★读公会卡并**等位置稳定**（连续两次 x 差 ≤2 才返回）。
     *
     * ★2026-11-09 用户指出：**列表会保持上次选公会时的滚动位置**。
     *   但刚进 263 / 拖完列表时卡池还在动画，读到的 x 是**默认值**（表现为"总是最左"）
     *   ⇒ 和实际显示不一致，于是误判"目标不在可见区"、白拖一屏。
     *   所以必须等两拍一致（通常多花 200ms）。
     */
    private suspend fun readCards(): List<JSONObject> {
        var prev = readCardsRaw()
        var last = prev
        repeat(4) {
            if (prev.size >= 2) {
                delay(POLL_MS)
                val cur = readCardsRaw()
                last = cur
                val stable = cur.size == prev.size &&
                    cur.indices.all { abs(cur[it].optInt("x") - prev[it].optInt("x")) <= 2 }
                prev = cur
                if (stable) {
                    logCards(cur)
                    return cur
                }
            } else {
                delay(POLL_MS)
                prev = readCardsRaw()
                last = prev
            }
        }
        logCards(last)
        return last
    }

    private fun logCards(list: List<JSONObject>) {
        AppState.addLog(
            "[guildCards] ${list.size} 张 " +
                list.joinToString(" ") { "${it.optString("name")}(${it.optInt("x")})" }.take(160),
        )
    }

    /** 点一张公会卡，并等确认弹窗出来（没弹 ⇒ 返回 false，让上层重读再试）。 */
    private suspend fun tapCard(input: InputBackend, b: JSONObject): Boolean {
        // ★2026-11-09：切应用就让整条链停手，不在助手窗口上乱点
        if (!frontOk()) return false
        // ★2026-11-10：卡已经过 [fullyOnScreen] 过滤，坐标必然是屏幕内的真实按钮中心，
        //   不再需要把负坐标 clamp 到 4（那是给半露卡擦屁股的，会把点击挪到屏幕边缘白点）。
        input.tap(b.optInt("x"), b.optInt("y"))
        // ★2026-11-09：原来这里固定睡 400ms 等弹窗展开，但 [findBtn] **本身就是轮询**的
        //   （按钮一出现就返回）⇒ 这 400ms 是纯浪费，去掉。它只用来判断"弹窗有没有出来"，
        //   不点它，所以坐标还没站稳也无所谓。
        return findBtn("StartButton", 2_000L) != null
    }

    /**
     * ★执行刷开局时可能**正在迷宫里**（上次跑到一半 / 手动开的局）。
     * 按用户定的规矩（2026-10-19，附截图）：**点进去先判断地图**——
     *
     *  · 合格 → **不用管**：跑迷宫流程里直接继续推进（RerollState 填好 return true）；
     *  · 不合格 / 读不到图 → 撤退（`retreat`：左下「地图」按钮和地图页「撤退」按钮都是
     *    通用的，找到任一即可走撤退流程）；
     *  · 两个按钮**一定次数都找不到**（多半在战斗中/异常页）→ 按故障处理，停手留人工。
     *
     * 返回值：true=已处理完（合格沿用）/ false=故障停手 / null=不在迷宫或已撤退回 259。
     */
    private suspend fun takeoverCurrentMaze(input: InputBackend, cfg: RerollConfig): Boolean? {
        val vid0 = Navigator.currentViewId()
        // ★战斗失败页（撤退重跑策略的中止点，2026-10-19 用户截图定流程）：
        //   本局已废（战斗都失败了）→ 不用判定，直接点「结束」→「撤退（无报酬）」回 259
        val top = AgentApi.uiSig()?.optString("top").orEmpty()
        if (top.contains("QuestFailed")) {
            AppState.addLog("局内刷开局：停在战斗失败页 → 本局已废，点「结束」→「撤退（无报酬）」")
            return if (retreatFromFailPage(input)) null else false
        }
        if (!Navigator.isLabyrinthView(vid0)) {
            // 已经不在迷宫（撤干净了）：作废标记没用了，清掉免得影响以后
            forceRerollNext = false
            return null
        }
        // ★上一局按策略作废（凹分不足）⇒ 不判定合格，直接撤退重开（见 [forceRerollNext]）
        if (forceRerollNext) {
            forceRerollNext = false
            AppState.addLog("局内刷开局：上一局已按策略作废（凹分不足）→ 不沿用，直接撤退重刷")
            return if (retreat(input)) null else false
        }
        // ★★2026-11-08（用户："读当前地图时应该能读到是从什么公会开局的"）：
        //   局内刷原来**只判"地图合不合格"**，完全不看公会和难度 ⇒ 手动开着 A 公会/难度4 的局、
        //   配置是 B 公会/难度5 时会直接沿用，跑完当成目标配置的成绩（接口刷那条路一直有
        //   `guildOk` / `diffOk` 两道校验，这里没有）。
        //   现在一次 `labStatusLite` 拿两个字段（都是 `LabyrinthTempData` 的内存读）：
        //     · `guildId` @ 0x1C —— 这一局是从哪个初始公会开的
        //     · `difficulty` @ 0x5C —— 这一局的难度
        val stNow = runCatching { AgentApi.labStatusLite() }.getOrNull()
        val curGuild = stNow?.optInt("guildId", -1) ?: -1
        val curDiff = stNow?.optInt("difficulty", -1) ?: -1
        if (curGuild > 0 && curGuild != cfg.guildId) {
            AppState.addLog(
                "局内刷开局：当前这局是「${GUILD_CN[curGuild] ?: curGuild}」≠ 目标「" +
                    "${GUILD_CN[cfg.guildId] ?: cfg.guildId}」→ 撤退重开",
            )
            return if (retreat(input)) null else false
        }
        if (curDiff > 0 && curDiff != cfg.difficulty) {
            AppState.addLog("局内刷开局：当前这局难度 $curDiff ≠ 目标 ${cfg.difficulty} → 撤退重开")
            return if (retreat(input)) null else false
        }
        AppState.addLog("局内刷开局：当前在迷宫里（${Navigator.viewLabel(vid0)}）→ 先看这局合不合格")
        // 先直接读图：子菜单（角色选择/奖励/强化/商店…）是叠在地图上的对话框，地图数据通常还能读
        delay(SETTLE_MS)
        var nodes = waitMap(6_000L)
        if (nodes == null && vid0 != 264) {
            // 读不到 → 借**通用的「地图」按钮**回地图页再读一次
            findBtn("MapButton", 3_000L)?.let {
                input.tap(it.optInt("x"), it.optInt("y"))
                AppState.addLog("局内刷开局：点「地图」回到地图页再读")
                delay(SETTLE_MS)
                nodes = waitMap(8_000L)
            }
        }
        val j = nodes?.let { RerollJudge.judge(it, cfg) }
        if (j?.ok == true) {
            AppState.addLog("局内刷开局：当前这局合格（${j.summary}）→ 不用管，直接沿用/继续推进")
            RerollState.result = RerollResult.Success(
                enterId = 0L,
                // ★填**实际读到的**（校验已通过，正常情况下与配置一致；读不到才回落到配置）
                guildId = if (curGuild > 0) curGuild else cfg.guildId,
                difficulty = if (curDiff > 0) curDiff else cfg.difficulty,
                nodes = nodes!!,
                bossByArea = j.bossByArea,
                routePerArea = j.routePerArea,
                actual = j.actual,
                theory = j.theory,
                summary = j.summary,
            )
            OverlayStatus.setState("沿用当前局 ✓")
            return true
        }
        AppState.addLog(
            if (j == null) "局内刷开局：在迷宫里但读不到地图数据 → 按不合格撤退重开"
            else "局内刷开局：当前这局不合格（${j.fail.joinToString("；")}）→ 撤退重开",
        )
        return if (retreat(input)) null else false
    }

    /**
     * ★战斗失败页的撤退（2026-10-19 用户截图定流程，路线 A）：
     * 失败弹窗点「结束」→「结束确认」窗（取消/撤退（无报酬）/返回（有报酬））点「撤退（无报酬）」
     * →（若还有确认窗则点右键）→ 等 259。
     * 按钮定位**结构优先**（弹窗自己的按钮列表按 x 排序取位），文字精确匹配兜底；
     * 整个查找在 [timeoutMs] 内多轮重试（同 `readRetry` 的精神）。找不到 → 按故障处理（停手）。
     */
    private suspend fun retreatFromFailPage(input: InputBackend): Boolean {
        val end = findByStructureOrText("DialogQuestFailed", 0, "结束", 6_000L) ?: run {
            AppState.addLog("[✗] 局内刷开局：失败页上找不到「结束」按钮 → 按故障处理，停手")
            return false
        }
        input.tap(end.optInt("x"), end.optInt("y"))
        AppState.addLog("局内刷开局：点「结束」(${end.optInt("x")},${end.optInt("y")})")
        // 确认窗的类名实时读（DialogCommonBig/Small 都出现过，不写死）
        val confirmTop = AgentApi.uiSig()?.optString("top").orEmpty().ifBlank { null } ?: "DialogCommonBig"
        val retire = findByStructureOrText(confirmTop, 1, "撤退（无报酬）", 8_000L) ?: run {
            AppState.addLog("[✗] 局内刷开局：结束确认窗上找不到「撤退（无报酬）」→ 按故障处理，停手")
            return false
        }
        input.tap(retire.optInt("x"), retire.optInt("y"))
        AppState.addLog("局内刷开局：点「撤退（无报酬）」(${retire.optInt("x")},${retire.optInt("y")})")
        // 「撤退（无报酬）」本身已是确认语义；若游戏再弹一个二次确认（右键），2s 内出现就点掉
        val c = findBtn("TwoButtonRight", 2_000L)
        if (c != null) {
            waitStable("TwoButtonRight")
            input.tap(c.optInt("x"), c.optInt("y"))
            AppState.addLog("局内刷开局：点「确认」(${c.optInt("x")},${c.optInt("y")})")
        }
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < 12_000L) {
            if (Navigator.currentViewId() == Navigator.TARGET_VIEW) {
                AppState.addLog("局内刷开局：已撤退回到黎明界 top")
                return true
            }
            delay(POLL_MS)
        }
        AppState.addLog("局内刷开局：撤退后 12s 没回到 top → 停手")
        return false
    }

    /**
     * 失败页/确认窗按钮的统一查找：**结构优先 + 文字兜底 + 多轮重试**。
     *
     * ① 结构：`dialog(topCls)` 拿弹窗自己的按钮列表（agent 会学路径，之后直达），
     *    按 **x 排序**取第 [pos] 颗 —— 失败弹窗两颗（结束|重新挑战）取 0 =「结束」；
     *    结束确认窗三颗（取消|撤退|返回）取 1 =「撤退（无报酬）」；
     * ② 文字兜底：结构没拿到（类名不符/按钮还没铺好）→ 按文字**整串相等**找
     *    （不能模糊包含：确认窗的说明文字也含"撤退"）。
     * 每轮先①后②，[timeoutMs] 内反复（与 `readRetry` 同一精神）。
     */
    private suspend fun findByStructureOrText(
        topCls: String,
        pos: Int,
        text: String,
        timeoutMs: Long,
    ): JSONObject? {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            // ① 结构：弹窗自己的按钮，按屏幕 x 排序
            val d = AgentApi.dialog(topCls)
            if (d?.optBoolean("open", false) == true) {
                val btns = d.optJSONArray("buttons")
                if (btns != null) {
                    val sorted = (0 until btns.length())
                        .mapNotNull { btns.optJSONObject(it) }
                        .filter { it.optInt("x", -1) > 0 }
                        .sortedBy { it.optInt("x") }
                    sorted.getOrNull(pos)?.let { return it }
                }
            }
            // ② 文字兜底（整串相等，防点进说明文字）
            val arr = AgentApi.findTextList("Btn|Button", 30, null, "dialog")
            firstMatch(arr) {
                it.optString("text").replace("\n", "") == text && it.optInt("x", -1) > 0
            }?.let { return it }
            delay(GAP_MS)
        }
        return null
    }

    /** 确认页：难度对不对；不对就开「难度变更」→ 选行 → 变更。 */
    private suspend fun ensureDifficulty(input: InputBackend, want: Int): Boolean {
        var cur = readDifficulty()
        if (cur == null) {
            // ★读不到 = 读取失败（图标名对不上已知名单）⇒ **再等一拍重读**，
            //   不直接开难度弹窗（2026-10-19 用户："都没读到说明读取是失败的，应该重新读"）
            AppState.addLog("局内刷开局：难度读不到（图标名不在 1..5 名单里）→ 等 1s 重读")
            delay(1_000)
            cur = readDifficulty(retries = 3)
        }
        if (cur == want) return true
        if (cur == null) {
            // 重读仍失败：当前难度显示的就是我们上局选的（读不到≠不对），按原样继续，
            // 绝不凭"读不到"就开难度弹窗（那正是上一版滑 6 次空白的来源）
            AppState.addLog("局内刷开局：难度重读仍失败 → 不动难度（弹窗里显示的就是上局所选，继续）")
            return true
        }
        AppState.addLog("局内刷开局：当前难度 $cur ≠ $want → 变更")
        if (!tapDifficultyButton(input)) return false
        if (!pickDifficulty(input, want)) return false
        if (!tapNode(input, "TwoButtonRight", "变更确认")) return false
        // 变了没有：轮询读难度，读到目标就立刻继续（最多 5s）
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < 5_000L) {
            if (readDifficulty() == want) return true
            delay(POLL_MS)
        }
        val now = readDifficulty()
        if (now != null && now != want) {
            AppState.addLog("局内刷开局：变更后难度仍为 $now（期望 $want）→ 失败")
            return false
        }
        return true
    }

    /**
     * 点「难度变更」——⚠页面上有**两颗**同名按钮：顶部那颗（1688,82）是公会设置页的，
     * 确认弹窗打开时它被盖住、点了没反应（2026-10-19 实测卡在这）。
     * 这里取 **y 最大**的那颗（弹窗底部工具条），并按时限反复找。
     */
    private suspend fun tapDifficultyButton(input: InputBackend): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < FIND_MS) {
            // ★限定在对话框子树（两颗同名按钮都在里面，取 y 最大那颗）
            val arr = AgentApi.findList("DifficultyChangeButton", "dialog")
            var best: JSONObject? = null
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (best == null || o.optInt("y") > best.optInt("y")) best = o
                }
            }
            if (best != null) {
                input.tap(best.optInt("x"), best.optInt("y"))
                AppState.addLog("局内刷开局：点「难度变更」(${best.optInt("x")},${best.optInt("y")})")
                return true
            }
            delay(GAP_MS)
        }
        AppState.addLog("局内刷开局：${FIND_MS / 1000}s 内没找到「难度变更」")
        return false
    }

    /** 全部合法的难度图标 sprite 名（推断：`labyrinth_icon_difficulty_num_N`，N=1..5） */
    private val DIFF_SPRITES = (1..5).map { "labyrinth_icon_difficulty_num_$it" }

    /**
     * 读当前选中的难度。
     *
     * ★这个数字在 UI 上是**图标**（UISprite），不是文字 ⇒ 读 sprite 名（agent `labDiffNum`）。
     * ★**严格校验**（2026-10-19 用户："试着找到全部难度的图标名字，都没读到说明读取失败"）：
     *   sprite 名必须精确等于 [DIFF_SPRITES] 里的一个（`labyrinth_icon_difficulty_num_1..5`）。
     *   名字对不上 = 读到的是别的东西（弹窗里某行的图标/别的 difficulty 节点）⇒ **判读取失败重读**，
     *   绝不把可疑数字当成当前难度。全失败再退回文字（别的区服可能是文字）。
     */
    private suspend fun readDifficulty(retries: Int = 4): Int? {
        var lastSprite = ""
        repeat(retries) { i ->
            val j = AgentApi.obj("labDiffNum", timeoutMs = 3_000L)
            lastSprite = j?.optString("sprite").orEmpty()
            val bySprite = DIFF_SPRITES.firstOrNull { lastSprite.equals(it, ignoreCase = true) }
            if (bySprite != null) return bySprite.last().digitToInt()
            if (i < retries - 1) delay(300)             // 换页/弹窗动画期间可能读到旧节点，隔一拍再读
        }
        // ★2026-11-12：数字文本也限定在难度弹窗子树（不再整树 find）
        val t = AgentApi.findText("difficultyNumOneDigit", "dialog")?.optString("text").orEmpty()
        return Regex("\\d+").find(t)?.value?.toIntOrNull()?.also {
            AppState.addLog("局内刷开局：图标名没对上（最后读到 \"$lastSprite\"）→ 用文字兜底 = $it")
        }
    }

    /**
     * 难度弹窗里选目标行。
     *
     * ⚠不能用 x 对齐：实测所有行 `SelectDifficultyButton.x` 都是 957、`DifficultyNumLabel.x` 都是 700，
     *   **x 区分不了行**（不同行只有 y 变）。也不该写死 +303。
     *
     * 做法：
     *  ① **顺序配对**（首选）：`findTextList("DifficultyNumLabel")` 与 `findList("SelectDifficultyButton")`
     *     都是从上到下的 DFS 顺序；两列表**行数相同**时下标一一对应，直接取同下标的圆钮。
     *  ② 行数对不上（有行被裁掉/多出别的行）→ 退化为"标签**正下方**最近的圆钮"
     *     （`y > label.y` 里 dy 最小；没有下方的才取纵向最近），不依赖固定偏移。
     */
    private suspend fun pickDifficulty(input: InputBackend, want: Int): Boolean {
        delay(SETTLE_MS)                            // 弹窗刚点开有展开动画，立刻读会读到空
        for (round in 1..DIFF_SCROLL_MAX) {
            // ★2026-11-12：限定在难度弹窗子树（`dialog`），不再整树遍历
            val labels = AgentApi.findTextList("DifficultyNumLabel", max = 12, root = "dialog")
            // ★诊断（2026-10-19）：一直"上滑难度列表"却找不到行 ⇒ 先看看弹窗里到底有什么。
            //   只读一次，不改变点击行为；看清了再改成按节点选（而不是按文字"难度N"）。
            if (round == 1) {
                AppState.addLog(
                    "局内刷开局：难度弹窗节点 → " +
                        nodesBrief(AgentApi.findTextList(".*", 25, null, "dialog")),
                )
            }
            val idx = indexOfLabel(labels, want)
            if (idx >= 0) {
                val radios = AgentApi.findList("SelectDifficultyButton", "dialog")
                val r = if (radios != null && radios.length() == labels!!.length()) {
                    radios.optJSONObject(idx)                       // ① 顺序配对
                } else {
                    nearestBelow(radios, labels!!.optJSONObject(idx).optInt("y"))   // ② 正下方最近
                }
                if (r != null) {
                    AppState.addLog("局内刷开局：选难度 $want (${r.optInt("x")},${r.optInt("y")})")
                    input.tap(r.optInt("x"), r.optInt("y"))
                    return true
                }
            }
            // ★弹窗里压根没有可选行 ⇒ 多半是「难度变更」没打开弹窗（点了被盖住的那颗）
            //   ⇒ 再点一次按钮，而不是对着空白上滑（2026-10-19 实测：滑了 6 次共 15s 什么也没滑出来）
            if (AgentApi.findList("SelectDifficultyButton", "dialog")?.length() ?: 0 == 0) {
                AppState.addLog("局内刷开局：难度弹窗里没有可选行（弹窗可能没打开）→ 再点一次「难度变更」")
                if (!tapDifficultyButton(input)) return false
                delay(SETTLE_MS)
                continue
            }
            // 目标行在可视区外 → 难度列表上滑
            AppState.addLog(
                "局内刷开局：第 $round 次没看到「难度$want」→ 上滑难度列表" +
                    (if (round == 1) "（可见行：${labelsBrief(labels)}）" else ""),
            )
            input.swipe(945, 850, 945, 350, 500)
            delay(400)                                  // 等列表惯性停住
        }
        AppState.addLog("局内刷开局：难度弹窗里找不到「难度$want」→ 失败")
        return false
    }

    /**
     * 标签列表里「难度 N」的下标（-1 = 没找到）。
     * ⚠按**文字里的数字**匹配（不是 `contains("难度N")`）：不同区服文案可能是「难度5」「難度5」或只有「5」。
     */
    private fun indexOfLabel(labels: JSONArray?, want: Int): Int {
        if (labels == null) return -1
        for (i in 0 until labels.length()) {
            val t = labels.optJSONObject(i)?.optString("text")?.replace("\n", "") ?: continue
            if (Regex("\\d+").find(t)?.value?.toIntOrNull() == want) return i
        }
        return -1
    }

    /** 诊断：把可见行标签的文字拼成一行（找不到目标时打一次）。 */
    private fun labelsBrief(labels: JSONArray?): String {
        if (labels == null || labels.length() == 0) return "(空)"
        val sb = StringBuilder()
        for (i in 0 until labels.length()) {
            val t = labels.optJSONObject(i)?.optString("text")?.replace("\n", "") ?: ""
            if (sb.isNotEmpty()) sb.append(" | ")
            sb.append(if (t.isEmpty()) "(无文字)" else t)
        }
        return sb.toString()
    }

    /** 诊断：把节点清单拼成一行（名字+坐标），找不到目标时打一次。 */
    private fun nodesBrief(arr: JSONArray?, max: Int = 12): String {
        if (arr == null || arr.length() == 0) return "(空)"
        val sb = StringBuilder()
        for (i in 0 until minOf(arr.length(), max)) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("text").replace("\n", "")
            if (sb.isNotEmpty()) sb.append(" | ")
            sb.append("${o.optString("name")}(${o.optInt("x")},${o.optInt("y")})")
            if (t.isNotEmpty()) sb.append("=\"$t\"")
        }
        return sb.toString()
    }

    /** 取"在 [labelY] 正下方最近"的节点；没有下方节点时取纵向最近的。 */
    private fun nearestBelow(arr: JSONArray?, labelY: Int): JSONObject? {
        if (arr == null || arr.length() == 0) return null
        var below: JSONObject? = null
        var belowD = Int.MAX_VALUE
        var any: JSONObject? = null
        var anyD = Int.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val dy = o.optInt("y") - labelY
            if (abs(dy) < anyD) { anyD = abs(dy); any = o }
            if (dy > 0 && dy < belowD) { belowD = dy; below = o }
        }
        return below ?: any
    }

    // ────────────────────────── 读图 / 撤退 ──────────────────────────

    // ────────────────────────── 中止（前台被切走）──────────────────────────

    /** 前台被切走时置位（用户 2026-11-09："局内刷开局没有切应用自动中止"）。 */
    @Volatile private var aborted = false

    /**
     * 前台还是游戏吗？不是 ⇒ 置位 [aborted] 并返回 false（调用方立刻 return）。
     * **读不到（null）不算切走** —— 和主循环/`tap()` 的口径一致，宁可不误停。
     */
    private suspend fun frontOk(): Boolean {
        // ★2026-11-09：命中后**抛异常**穿透，而不是让每层自己 `return false`。
        //   之前只 return false，`dismissCharaPanel`/`retreat` 这类没写检查的循环会一直空转
        //   （实测"日志里有一条中止，之后还在继续检测点地图"）。
        if (aborted) throw AbortedException("已中止")
        // ★2026-11-10：先看**锁存**。切屏那一瞬间可能正好卡在撤退循环里没查前台，
        //   而服务自愈随后又把游戏顶回前台 ⇒ 只靠"现在读到谁"永远判不出切走过。
        Foreground.switchedAway()?.let {
            aborted = true
            throw AbortedException("前台已切到 $it")
        }
        val fp = runCatching { Foreground.packageName() }.getOrNull()
        if (fp != null && fp != GameProcess.pkg()) {
            aborted = true
            throw AbortedException("前台已切到 $fp")
        }
        return true
    }

    /** 等 `labMap()` 出面板（进图后几千 ms 内就会有；最多 [timeoutMs]）。 */
    private suspend fun waitMap(timeoutMs: Long = 20_000L): List<LabyrinthMapNode>? {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            if (!frontOk()) return null
            val m = AgentApi.labMap()
            val arr = m?.optJSONArray("panels")
            if (arr != null && arr.length() > 0) return parsePanels(arr)
            delay(300)
        }
        return null
    }

    private fun parsePanels(arr: JSONArray): List<LabyrinthMapNode> {
        val out = ArrayList<LabyrinthMapNode>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val next = ArrayList<Long>()
            o.optJSONArray("next")?.let { na ->
                for (k in 0 until na.length()) next.add(na.optLong(k))
            }
            val q = o.optInt("questId", 0)
            out.add(
                LabyrinthMapNode(
                    area = o.optInt("area"),
                    column = o.optInt("column"),
                    row = o.optInt("row"),
                    blockId = o.optLong("blockId"),
                    blockType = o.optInt("blockType"),
                    questId = if (q > 0) q else null,
                    nextBlockIds = next,
                    isAreaLastPoint = false,
                ),
            )
        }
        return out
    }

    /**
     * 不满意 → 撤退回 259。
     *
     * ★入口按用户定的规矩（2026-10-19，附截图）：**左下「地图」按钮（迷宫任意页通用，
     *   角色选择页/子菜单都有）和地图页的「撤退」按钮都是通用的——找到任一即可走撤退流程**：
     *  · 找到「地图」→ 点它进地图页 → 点「撤退」；
     *  · 找到「撤退」（已在地图页）→ 直接点；
     *  · 两个交替找 8 轮（≈10s）都没有 → **按故障处理，停手**（多半在战斗中/异常页）。
     */
    private suspend fun retreat(input: InputBackend): Boolean {
        // ★2026-10-31（用户："不能正确检测到初始角色选择的页面，一直在尝试点撤退按钮，
        //   但应该先点地图按钮退到地图才对"）：选人页是盖在地图上的模态面板，HUD 只有
        //   「地图 / 角色 / 迷宫遗物」，那颗「撤退」还在树里但**点不动**。
        //   ⇒ 先离开面板回地图，再走原来的撤退流程。
        //   ★★第二版（同一天，用户："各处都很慢、还可能点错"）：上一版用 labBtn 找「地图」这个名字，
        //     实测代价 6.9~7.3s/次 —— **地图页上压根没这颗按钮**，agent 必须走完整棵子树才敢说没有。
        //     现在全部换成便宜调用：
        //       ① `labStatus`（实测 7ms）拿 statusType/pageTop；
        //       ② 是选人页（statusType=1 且有顶框，与 MazeLoop 同一判据）→ `labCharaPageQuick`
        //          （12ms）**直接拿面板自己报的「地图」坐标**去点（不搜名字，也就不会点到被盖住的按钮）；
        //       ③ 其余情况直接撤退（撤退按钮命中缓存 4ms）。
        // ★★2026-10-31 第三版：改成**固定链条**（用户："刷开局可以明确设定状态转换链条"）。
        //   ① 选人面板让开（点面板自报的「地图」；坐标没算好就**等**，绝不退回点撤退）
        //   ② 顶框**稳定**消失（连续两次读到 null）—— 专门吸收面板之间的过渡帧
        //   ③ 点「撤退」→ 断言 dialog() 里出现 TwoButtonRight
        //   ④ 点确认 → 断言回到 259
        //   每步失败都报"卡在哪一步"，不再出现"点三次撤退都没反应"这种含糊失败。
        // ★★2026-10-31 第四版（用户："进入时状态不是固定的，甚至可能包含 5 个链以外的节点"）：
        //   进局现场取决于**上一局停在哪** —— 可能直接停在选人面板，也可能停在战斗面板
        //   （战斗面板要点「取消」→「查看地图」才回得到地图）。所以撤退前先**判断处在哪条链的哪个位置**，
        //   处理完回到地图，再走【地图页主链】撤退。
        //   分类用便宜调用：viewId(1ms) + labStatus(7ms) + labCharaPageQuick(12ms)，不碰 labBtn。
        // ★**按时间预算重试**（2026-11-4 用户）：原来是固定 4 轮，一轮失败就少一次机会 ——
        //   而事件结算后会连弹一串奖励窗，中间还有"地图可见但输入锁着"的过渡帧（`pageTop` 是空的，
        //   任何顶框判据都抓不到）⇒ 固定轮次必然提前放弃。
        //   现在改成"直到 30 秒还没撤出去才算失败"，期间每次失败都等 800ms 重判，
        //   既能吃掉弹窗串，也能吞掉过渡帧；**只在真的要撤退时走这里**，不影响平时的移动节奏。
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        var round = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            // ★2026-11-10：切屏闸门要放在长循环**每轮开头**。这个循环最长空转 30s，
            //   里面全是"等 800ms 重判"，没有它的话切了屏也会一直转到超时（雷电 9 实测）。
            //   `frontOk()` 命中锁存时抛 [AbortedException] 直接穿透到 [run]。
            if (!frontOk()) return false
            round++
            val sc = classifyScene()
            AppState.addLog("局内刷开局：撤退前现场 = ${sceneName(sc)}（${pageSig()}）")
            when (sc) {
                Scene.TOP -> {
                    AppState.addLog("局内刷开局：已经在 259 top，不用撤退")
                    return true
                }
                Scene.MAP -> {
                    if (!stableTopGone(5_000L)) {
                        AppState.addLog("[!] 撤退②：顶框没稳定消失（${pageSig()}）→ 重新判定现场")
                        delay(600)
                        continue
                    }
                    if (retreatFromMap(input)) return true
                    // 没撤出去：可能是"地图看得见但点不动"的过渡帧 ⇒ 等一会再来，别当成失败
                    AppState.addLog("[!] 撤退主链没成功（第 $round 次，剩 ${(deadline - SystemClock.elapsedRealtime()) / 1000}s）→ 等 800ms 重判")
                    delay(800)
                }
                Scene.CHARA_PANEL -> {
                    if (!dismissCharaPanel(input)) {
                        AppState.addLog("[!] 撤退：选人面板没让开（第 $round 次）→ 等 800ms 重判")
                        delay(800)
                    }
                }
                Scene.BATTLE -> {
                    if (!leaveBattlePanel(input)) {
                        AppState.addLog("[!] 撤退：战斗面板没退出（第 $round 次）→ 等 800ms 重判")
                        delay(800)
                    }
                }
                Scene.OTHER -> {
                    // 一次连点最多 3 个；点完还判 OTHER 也不立刻放弃，等下一轮
                    if (!clearOtherDialog(input)) {
                        AppState.addLog("[!] 撤退：未知面板点不掉（第 $round 次）→ 等 800ms 重判")
                        delay(800)
                    }
                }
            }
        }
        AppState.addLog("[✗] 撤退：30 秒内没把现场恢复成「地图页并撤出去」（第 $round 次，${pageSig()}）")
        return false
    }

    /** 【地图页主链】点「撤退」→ 断言确认框 → 点确认 → 断言回到 259。 */
    private suspend fun retreatFromMap(input: InputBackend): Boolean {
        val e = findBtn("LabyrinthRetireButton", FIND_MS)
        if (e == null) {
            AppState.addLog("[✗] 撤退③：找不到「撤退」按钮（view=${Navigator.currentViewId()}）")
            return false
        }
        AppState.addLog("局内刷开局：点「撤退」(${e.optInt("x")},${e.optInt("y")})")
        input.tap(e.optInt("x"), e.optInt("y"))
        // ★先查确认框、没有再补点（补点会落在刚弹出的框上把它关掉）
        var confirm = findConfirmButton(4_000L)
        if (confirm == null) {
            AppState.addLog("局内刷开局：点撤退后没弹确认框 → 补点一次「撤退」")
            if (!tapNode(input, "LabyrinthRetireButton", "撤退（补点）")) return false
            confirm = findConfirmButton(5_000L)
        }
        if (confirm == null) {
            AppState.addLog("[✗] 撤退④：确认框没出来（顶框=${pageSig()}）")
            return false
        }
        for (t in 1..2) {
            val b = confirm ?: findConfirmButton(4_000L) ?: break
            val bx = b.optInt("x", -1)
            val by = b.optInt("y", -1)
            if (bx <= 0 || by <= 0) break
            AppState.addLog("局内刷开局：点「确认撤退」($bx,$by)（dialog 报的坐标）")
            // ★2026-11-09：走 [tapToView]，「确认框开始消失」= 这一下生效（本地，比 viewId 变早）；
            //   retries=1 —— 外层循环会重读坐标再点（弹窗可能挪位置）。
            val ok = tapToView(
                input,
                bx,
                by,
                "确认撤退",
                from = Navigator.currentViewId(),
                expect = setOf(Navigator.TARGET_VIEW),
                dialogClosing = true,
                expectTimeoutMs = 4_000L,
                retries = 1,
            )
            if (ok) {
                AppState.addLog("局内刷开局：已撤退回到黎明界 top")
                return true
            }
            AppState.addLog("局内刷开局：点了确认还在 ${Navigator.currentViewId()} → 重来（第 $t/2 次）")
            confirm = null            // 下一轮重新读坐标（不怕它挪位置）
        }
        AppState.addLog("[✗] 撤退⑤：点确认后没回到 259（view=${Navigator.currentViewId()}）")
        return false
    }

    /** 进局/撤退时的**现场分类**（对应"现在是哪条链的哪个位置"）。 */
    private enum class Scene { TOP, CHARA_PANEL, MAP, BATTLE, OTHER }

    private fun sceneName(s: Scene): String = when (s) {
        Scene.TOP -> "259 黎明界 top"
        Scene.CHARA_PANEL -> "264 地图 + 初始选人面板"
        Scene.MAP -> "264 地图页（无面板）"
        Scene.BATTLE -> "264 战斗/任务详情面板"
        Scene.OTHER -> "其他（未知面板/非迷宫页）"
    }

    /**
     * ★**判断进入时停在哪条链**（2026-10-31 用户需求）。
     *
     * 只用便宜调用，全部实测：`viewId` 1ms、`labStatus` 7ms；选人面板再补一次
     * `labCharaPageQuick`（12ms）。
     *
     * statusType 取值来自 agent（与 MazeLoop 日志里的 `状态=SUMMON_UNIT(1)` / `QUEST(5)` /
     * `BATTLE(29)` 同一套）：1=选人页、5=关卡详情、29=战斗。
     */
    private suspend fun classifyScene(): Scene {
        val vid = Navigator.currentViewId()
        if (vid == Navigator.TARGET_VIEW) return Scene.TOP
        if (vid != 264) return Scene.OTHER
        val st = AgentApi.labStatus() ?: return Scene.OTHER
        val top = st.optString("pageTop").let { if (it == "null") "" else it }
        val type = st.optInt("statusType", -1)
        if (type == 1 && top.isNotEmpty()) return Scene.CHARA_PANEL
        if (type == 29 || type == 5) return Scene.BATTLE
        if (top.isEmpty()) return Scene.MAP
        return Scene.OTHER
    }

    /**
     * ★**第 6 条链（暂定）**：停在战斗/关卡详情面板时，先「取消」再「查看地图」回到地图。
     *
     * 2026-10-31 用户："如果之前某次停在战斗面板，可能就要从战斗选人点取消，
     * 再点一个查看地图按钮，回到地图，然后点撤退和确认"。
     * ⚠这条链的按钮名只在 PC 版里确认过（`MapViewModeButton = 查看地图`），Android 上没实测；
     *   第一次跑会打日志，按日志再校正名字即可。
     */
    private suspend fun leaveBattlePanel(input: InputBackend): Boolean {
        // ① 取消（战斗/关卡详情里的 TwoButtonLeft = 取消）
        // ★2026-11-09：`dialog()` 全量遍历太慢 ⇒ 用字段直取（只读 7 个通用按钮字段）。
        val d = AgentApi.labDlgBtn("")
        val a = d?.optJSONArray("buttons")
        var cancel: JSONObject? = null
        if (a != null) {
            for (i in 0 until a.length()) {
                val b = a.optJSONObject(i) ?: continue
                if (b.optString("name") == "TwoButtonLeft") { cancel = b; break }
            }
        }
        if (cancel != null) {
            AppState.addLog("局内刷开局：战斗面板 → 点「取消」(${cancel.optInt("x")},${cancel.optInt("y")})")
            // ★2026-11-09：走 [tapToView]，判「面板开始消失」；点空立刻补点（不再固定睡）。
            tapToView(
                input,
                cancel.optInt("x"),
                cancel.optInt("y"),
                "取消（战斗面板）",
                from = Navigator.currentViewId(),
                expect = emptySet(),
                dialogClosing = true,
                retries = 1,
            )
        }
        // ② 查看地图（回到 264 地图页）
        val mapBtn = findBtn("MapViewModeButton", FIND_MS)
        if (mapBtn != null) {
            val ok = tapToView(
                input,
                mapBtn.optInt("x"),
                mapBtn.optInt("y"),
                "查看地图",
                from = Navigator.currentViewId(),
                expect = setOf(264),
                expectTimeoutMs = 4_000L,
                retries = 2,
            )
            if (ok) return true
        }
        AppState.addLog("[!] 撤退⑥：战斗面板没能回到地图（${pageSig()}）→ 交回主循环重判")
        return false
    }

    /** 其他未知弹窗：只点白名单里的通用确认按钮（点掉一层就交给主循环重判）。 */
    private suspend fun clearOtherDialog(input: InputBackend): Boolean {
        val whitelist = setOf("OneButtonWhite", "TwoButtonRight", "ThreeButtonCenter", "OneButtonBlue")
        // ★2026-11-09：同 [findConfirmButton] —— `dialog()` 全量遍历太慢（0.7~5s），换字段直取。
        val d = AgentApi.labDlgBtn("")
        val a = d?.optJSONArray("buttons")
        if (a != null) {
            // ★一次最多连点 3 个（2026-11-4）：奖励弹窗是一串，一次只点一个会把轮次白白耗光
            for (pass in 1..3) {
                val dd = if (pass == 1) d else AgentApi.labDlgBtn("")
                val aa = dd?.optJSONArray("buttons") ?: break
                var hit = false
                for (i in 0 until aa.length()) {
                    val b = aa.optJSONObject(i) ?: continue
                    if (whitelist.contains(b.optString("name"))) {
                        AppState.addLog("局内刷开局：未知面板 → 点掉「${b.optString("name")}」(${b.optInt("x")},${b.optInt("y")})")
                        // ★2026-11-09：走 [tapToView] —— 判据是「这层弹窗开始消失」（labDlgClosing），
                        //   不再固定睡 SETTLE_MS；点空（弹窗没动）就立刻补点。
                        tapToView(
                            input,
                            b.optInt("x"),
                            b.optInt("y"),
                            "点掉「${b.optString("name")}」",
                            from = Navigator.currentViewId(),
                            expect = emptySet(),
                            dialogClosing = true,
                            retries = 1,
                        )
                        hit = true
                        break
                    }
                }
                if (!hit) break
            }
            return true
        }
        AppState.addLog("[!] 撤退：未知面板且没有白名单按钮（${pageSig()}）→ 只等不点")
        delay(SETTLE_MS)
        return false
    }

    /**
     * ★**撤退链第①步**：选人面板挡着就让开（点它自报的「地图」）。
     *
     * 2026-10-31 的两次翻车都在这：
     * * 面板刚弹出时 `labCharaPageQuick` 的 `buttons.map` **坐标还没算好**（这次日志里
     *   `[!] 选人页上读不到「地图」坐标 → 照旧尝试撤退` 就是它）——正确做法是**等**，不是退回点撤退；
     * * 用 `labBtn("MapButton")` 找名字要 6.9~7.3s/次（地图页上根本没这颗按钮）。
     *
     * 返回 true = 现在没有面板挡着（或成功点掉）。
     */
    private suspend fun dismissCharaPanel(input: InputBackend, timeoutMs: Long = 8_000L): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val st = AgentApi.labStatus() ?: break
            val top = st.optString("pageTop").let { if (it == "null") "" else it }
            if (top.isEmpty()) return true
            if (st.optInt("statusType", -1) != 1) {
                AppState.addLog("局内刷开局：顶框=$top（不是选人页）→ 不动它")
                return true
            }
            val q = AgentApi.labCharaPageQuick()
            val mp = q?.optJSONObject("buttons")?.optJSONObject("map")
            val mx = mp?.optInt("x", -1) ?: -1
            val my = mp?.optInt("y", -1) ?: -1
            if (mx > 0 && my > 0) {
                AppState.addLog("局内刷开局：选人页（顶框=$top）→ 点面板上的「地图」($mx,$my) 退回地图")
                // ★2026-11-09：用 [tapToView] 判「面板开始消失」（本地 200~400ms）。
                //   原来直接 `stableTopGone(5s)` 要等"完全消失 + 连续两拍"，实测多花 ~1s
                //   —— 这就是"点地图到点撤退之间间隔较长"的来源。
                val gone = tapToView(
                    input,
                    mx,
                    my,
                    "地图（收起选人面板）",
                    from = Navigator.currentViewId(),
                    expect = emptySet(),
                    dialogClosing = true,
                    retries = 1,
                )
                // ★2026-11-09：`tapToView` 命中（3 拍轮询出来的，不是单帧）就说明顶框已空，
                //   不用再 `stableTopGone` 等"连续两拍"多花 200ms。
                if (gone || stableTopGone(2_000L)) {
                    AppState.addLog("局内刷开局：面板已收起")
                    return true
                }
                AppState.addLog("局内刷开局：点了地图面板还在（${pageSig()}）→ 再试一次")
                delay(500)
            } else {
                // ★面板刚弹出、按钮坐标还没算好 ⇒ 等它铺好，**不要**退回去点撤退
                delay(300)
            }
        }
        AppState.addLog("[!] 撤退①：等不到选人面板的「地图」坐标（${pageSig()}）")
        return false
    }

    /**
     * ★顶框**稳定**消失：连续两次（间隔 [POLL_MS]）读到 `top == null` 才算。
     *
     * 为什么要"稳定"：进局演出里面板与面板之间存在 `top == null` 的过渡帧（2026-10-31 用户指出），
     * 单次读到 null 就当成"已经在地图、可以点撤退"正是之前点进面板的根因。
     */
    private suspend fun stableTopGone(timeoutMs: Long): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        var okRun = 0
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val s = AgentApi.uiSig()
            val t = s?.optString("top") ?: ""
            if (s != null && (t.isBlank() || t == "null")) {
                okRun++
                if (okRun >= 2) return true
            } else {
                okRun = 0
            }
            delay(POLL_MS)
        }
        return false
    }

    /**
     * ★**确认框按钮**用 `dialog()` 读，不走 `labBtn`（2026-10-31 用户："撤退确认框没有读到"）。
     *
     * 实测同一页：
     * * `dialog()` → **747ms**，直接给出 `buttons: [{name:"TwoButtonLeft"(取消), TwoButtonRight(确认)}]`
     *   以及**当下**的坐标（1179,744）；
     * * `labBtn("TwoButtonRight")` → **3.7~7s**（它从 `canvas` 整棵树找，找不到名字还要走完整个子树），
     *   3s 的窗口根本等不到 ⇒ 日志里"还没有确认框 → 补点一次"反复出现，最后 `8s 内没找到「确认撤退」`。
     *
     * 返回 `{name,x,y}`（右侧那颗 = 确认）；没有框就 null。
     */
    private suspend fun findConfirmButton(timeoutMs: Long = 3_000L): JSONObject? {
        val t0 = SystemClock.elapsedRealtime()
        var lastX = -1
        var lastY = -1
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            // ★2026-11-09：`dialog()` 是全量遍历（同一页实测 747ms，**选人面板上 5004ms**），
            //   放在 200ms 的轮询里会把 agent 单通道堵死 ⇒ 换字段直取的 labDlgBtn（~50ms）。
            //   它覆盖的正是这几个通用按钮字段（TwoButtonRight / TwoButtonLeft / OneButtonWhite…）。
            val d = AgentApi.labDlgBtn("")
            val a = d?.optJSONArray("buttons")
            if (a != null) {
                for (i in 0 until a.length()) {
                    val b = a.optJSONObject(i) ?: continue
                    if (b.optString("name") != "TwoButtonRight") continue
                    val x = b.optInt("x", -1)
                    val y = b.optInt("y", -1)
                    // ★★2026-11-09：确认框有**展开动画**，刚出现那一下投影出的坐标还在漂
                    //   （实测 1075,647 → 稳定值 1179,744，差 ~100px）⇒ 点空，然后白等
                    //   `waitView(259, 6000)` 六秒才重试。这里要求**连续两拍坐标一致**再返回
                    //   （多花一拍 ~200ms，换掉那 6 秒）。
                    if (x == lastX && y == lastY) return b
                    lastX = x
                    lastY = y
                    break
                }
            }
            delay(POLL_MS)
        }
        return null
    }

    // ────────────────────────── 找节点小工具 ──────────────────────────

    /**
     * 按**元素名**取按钮并点它（2026-10-19 用户："正确方式是直接按元素 id"）。
     *
     * 走 agent 的 `labBtn`：**学一次指针**（只遍历当前 View / DialogArea 子树，不是整树），
     * 之后每次只做"读缓存指针 → 校验 GameObject 名 → 现算坐标"（约 5 次调用，不再遍历）。
     * 按钮还没出现时按 [GAP_MS] 重试，直到 [timeoutMs]。
     */
    private suspend fun tapNode(
        input: InputBackend,
        name: String,
        label: String = name,
        timeoutMs: Long = FIND_MS,
        /** true = **丢掉缓存路径重新学**（点偏了/布局变了时用；学完会覆盖旧路径） */
        fresh: Boolean = false,
    ): Boolean {
        val r = findBtn(name, timeoutMs, fresh)
        if (r == null) {
            AppState.addLog("局内刷开局：${timeoutMs / 1000}s 内没找到「$label」")
            return false
        }
        val x = r.optInt("x", -1)
        val y = r.optInt("y", -1)
        input.tap(x, y)
        AppState.addLog("局内刷开局：点「$label」($x,$y)（${r.optString("hit").orEmpty()}）")
        return true
    }

    /**
     * 等某个按钮的**坐标站稳**（连续两次一致，±2px）——弹窗/切页刚发生时按钮还在滑，
     * 这时候读到的坐标点下去会落空（2026-10-19 实测：确认撤退 y=719 → 稳定 744；
     * 确认出发 (1415,954) → 稳定 (1410,950)）。站不稳最多等 [timeoutMs] 就照点。
     */
    private suspend fun waitStable(name: String, timeoutMs: Long = 3_000L) {
        var lastX = -1
        var lastY = -1
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val r = findBtn(name, 2_000L)
            if (r == null) { delay(POLL_MS); continue }
            val x = r.optInt("x", -1)
            val y = r.optInt("y", -1)
            if (abs(x - lastX) <= 2 && abs(y - lastY) <= 2) return   // 不再动 = 铺好了
            lastX = x
            lastY = y
            delay(POLL_MS)
        }
    }

    /**
     * 只取按钮坐标（**不点**）：同样是 `labBtn`（缓存路径直达，几十 ms），
     * 用来在看坐标/等稳定时避免误触。命中时顺手把新学到的路径落盘。
     */
    private suspend fun findBtn(
        name: String,
        timeoutMs: Long = FIND_MS,
        fresh: Boolean = false,
    ): JSONObject? {
        val key = "${pageSig()}|$name"
        val known = if (fresh) null else appCtx?.let { LabBtnStore.get(it, key) }
        val t0 = SystemClock.elapsedRealtime()
        // ★学习档放宽时限（见 [LEARN_MS]）：
        //   · 已学到路径（known != null 且非 fresh）→ 仍是 3s 的便宜调用；
        //   · 需要学习、且调用方给了正常窗口（≥3s）→ 给 LEARN_MS，保证**一次学成并落盘**；
        //   · 1.2s 级别的"快速探测"（`retreat()` 里交替探「地图」「撤退」）→ 仍按 3s，免得把
        //     "这页没有这按钮"拖成好几秒一轮。
        val learning = fresh || known == null
        // ★"有缓存"不等于"一定快"：缓存路径校验失败时 agent 仍要**重学**（实测 3.7~3.8s），
        //   原来这一档给 3s ⇒ 每次都被砍、永远学不成。给 6s（命中缓存的实际往返仍是 4ms）。
        val callMs = if (learning && timeoutMs >= 3_000L) LEARN_MS else 6_000L
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            // 第 5 个参数 fresh：让 agent 连它进程内的缓存也丢掉，真的重学一次
            val r = AgentApi.obj("labBtn", listOf(key, name, known?.first, known?.second, fresh), callMs)
            val x = r?.optInt("x", -1) ?: -1
            val y = r?.optInt("y", -1) ?: -1
            if (x > 0 && y > 0) {
                // 第一次学到的路径 → 落盘固定下来（之后直达，不再扫）
                if (r?.optString("hit") == "学习") {
                    val root = r.optString("root")
                    val pa = r.optJSONArray("path")
                    val ctx = appCtx
                    if (root.isNotEmpty() && pa != null && ctx != null) {
                        val list = ArrayList<Int>()
                        for (i in 0 until pa.length()) list.add(pa.optInt(i))
                        LabBtnStore.put(ctx, key, root, list)
                    }
                }
                return r
            }
            delay(GAP_MS)
        }
        return null
    }

    /** 当前页面签名 `viewId|顶层对话框名`（几十 ms；比 find 便宜两个量级）。 */
    private suspend fun pageSig(): String {
        val s = AgentApi.uiSig() ?: return "?"
        val v = s.optInt("view", -1)
        val t = s.optString("top").let { if (it.isBlank() || it == "null") "" else it }
        return "$v|$t"
    }

    private fun firstMatch(arr: JSONArray?, pred: (JSONObject) -> Boolean): JSONObject? {
        if (arr == null) return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (pred(o)) return o
        }
        return null
    }

    /** `findList` 的结果按屏幕横坐标升序（左→右）；DFS 顺序不可靠（实测从右往左）。 */
    private fun sortedByX(arr: JSONArray?): List<JSONObject> {
        val list = ArrayList<JSONObject>()
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
        list.sortBy { it.optInt("x") }
        return list
    }

    /** 取 x 最接近 [x] 的那个节点（公会卡按横坐标对齐）。 */
    private fun closestTo(arr: JSONArray?, x: () -> Int): JSONObject? {
        if (arr == null || arr.length() == 0) return null
        val want = x()
        var best: JSONObject? = null
        var bestD = Int.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val d = abs(o.optInt("x") - want)
            if (d < bestD) { bestD = d; best = o }
        }
        return best
    }
}
