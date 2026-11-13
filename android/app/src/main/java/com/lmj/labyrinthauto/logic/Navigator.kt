package com.lmj.labyrinthauto.logic

import android.os.SystemClock
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.frida.AgentApi
import com.lmj.labyrinthauto.frida.AgentBridge
import com.lmj.labyrinthauto.frida.GameProcess
import com.lmj.labyrinthauto.input.Foreground
import com.lmj.labyrinthauto.input.InputBackend
import android.content.Context
import com.lmj.labyrinthauto.frida.FridaInjector
import com.lmj.labyrinthauto.input.InputResult
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * "点到黎明界 top"这一步 —— PC 版 `pcr_auto.py` 状态机的简版（只做这一步，不多做）。
 *
 * 判据与 PC 版**逐条对齐**（`pcr_auto.py` 的 VIEW_NAMES / STEP_BUTTON / 说明注释）：
 *  - 按 `viewId` 分派；按钮一律按**节点名**定位（分辨率无关），**不做坐标兜底**；
 *  - SPLASH(1)/TITLE(2) 没有具名按钮 → 点屏幕中心，且不限次数；
 *  - HOME(5)：若弹了通用对话框（单按钮公告）**先点掉**，再点 `ButtonQuest`（冒险）；
 *  - QUEST_TOP(20) → `GUIButton_Labyrinth`（黎明界迷宫）；
 *  - LABYRINTH_TOP(259) → **到此为止**：绝不点 `labyrinth_thumb_start`（那是"出发"= 开局）。
 *    没在挑战中时本来也不该自动开局（PC 版同款规矩）。
 */
object Navigator {

    /** 直接照抄 PC 版（`pcr_auto.py:VIEW_NAMES`），两边看到同一串名字才好对拍 */
    private val VIEW_NAMES = mapOf(
        0 to "NONE", 1 to "SPLASH", 2 to "TITLE", 3 to "SD_DATA_MOVE", 4 to "ASSETS_DOWNLOAD",
        5 to "HOME", 20 to "QUEST_TOP", 26 to "DUNGEON_TOP",
        // ★47=剧情首页（2026-10-15 实测确认，底部导航第 4 个「剧情」）——不是死路：
        //   点底部那个「冒险」ButtonQuest 就能继续（见 STEP_BUTTON），与 PC 版一致
        47 to "STORY_TOP", 76 to "STORY_ADVENTURE", 77 to "MENU",
        // ★29 = BATTLE（`dump.cs` 的 `eViewId BATTLE = 29`，2026-10-26 核实）。
        //   PC 版那张表只抄了流程用到的 id，所以这里一直没有 29 —— 日志里就显示成 `?(29)`，
        //   排查时很容易被误当成"未知页面"（实际每场战斗都会经过它，结算页也是盖在 BATTLE 上的 dialog）。
        29 to "BATTLE",
        // ★104 = 新角色介绍（点屏幕推进的演出页；2026-10-23 实机踩到，见 [TAP_CENTER_VIEWS]）
        78 to "LOGING_BONUS", 104 to "NEW_CHARACTER_INTRODUCTION",
        259 to "LABYRINTH_TOP", 260 to "LABYRINTH_REWARD_LIST",
        261 to "LABYRINTH_REINFORCEMENT", 262 to "LABYRINTH_PICTURE_BOOK",
        263 to "LABYRINTH_GUILD_SETTING", 264 to "LABYRINTH_MAP", 265 to "LABYRINTH_COIN_SHOP",
    )

    /**
     * 没有具名按钮、**点屏幕任意处就推进**的界面（不限次数）。
     *
     * ★104 = `NEW_CHARACTER_INTRODUCTION`（dump.cs/eViewId）=「新角色介绍」演出页（2026-10-23 实机）：
     *   刷开局抽到角色后，客户端**第一次**见到这些角色就会播这一页（点一下推进，播过的角色不再播）。
     *   它既没有具名按钮、也没有底部导航 ⇒ 以前落到"未知页面：不猜着点，等它自己变"，
     *   连续 16 轮没推进就停手（日志：`停在未知页面 ?(104)｜顶层=null … 停手`）。
     *   新号/新设备上必现（老号早播过了，所以一直没暴露）。
     */
    private val TAP_CENTER_VIEWS = setOf(1, 2, 104)


    /** viewId → 要点的按钮节点名（PC 版 `STEP_BUTTON`；259 故意不在这里）。
     *  ★活动页（246/249 之类）**不做特判**（2026-10-19 用户：那就是普通活动页，不用仔细分辨）——
     *    它们不在表里 ⇒ 走下面的未知页面通用策略：有底部导航就点「冒险」回正常线。 */
    private val STEP_BUTTON = mapOf(
        5 to "ButtonQuest",
        20 to "GUIButton_Labyrinth",
        // ★剧情首页：点底部「冒险」继续（2026-10-15 实测；与 HOME 同一个按钮名）
        47 to "ButtonQuest",
    )

    /** 通用弹窗里可以放心点的"确定/关闭"按钮名（PC 版 `MAZE_DIALOG_CONFIRM`） */
    private val DIALOG_CONFIRM = listOf(
        "OneButtonWhite", "OneButtonBlue", "TwoButtonRight",
        "ThreeButtonRight", "TwoButtonCenter", "ThreeButtonCenter",
    )

    const val TARGET_VIEW = 259

    /** 上次报过的"未知页面"viewId（同值只报一次，避免每轮刷屏） */
    private var lastUnknownVid = -1

    /** PC 版 `DONE_VIEW`：走到 264 = 已进入黎明界地图 */
    const val DONE_VIEW = 264

    /**
     * 是不是"**迷宫内**"的视图（能读到迷宫状态的那几个）。
     *
     * ★不含 259(`LABYRINTH_TOP`)：那是**入口页**（还没进去），PC 版也是从 264(`LABYRINTH_MAP`)
     * 才算"已进入黎明界"、才进 `maze_loop` 调 `labStatus`。实测在 259 上调它只会白烧全堆扫描
     * （日志：`[findMap] … +0xF8 指向 LabyrinthTopMenuButton（视图=ViewLabyrinthTop，不是地图视图？）`）。
     */
    fun isLabyrinthView(viewId: Int): Boolean = viewId in 260..265

    fun viewLabel(viewId: Int): String = VIEW_NAMES[viewId]?.let { "$it($viewId)" } ?: "?($viewId)"

    /**
     * 从"当前页面"一路点到 LABYRINTH_TOP(259)。
     * @return true = 已到达（或本来就在）259；false = 中途停手（原因会写进日志）
     */
    suspend fun goToLabyrinthTop(
        input: InputBackend,
        maxRounds: Int = 200,
        /** 用于"游戏进程不在就主动拉起"；传 null 时只等前台（旧行为） */
        ctx: Context? = null,
    ): Boolean {
        AppState.addLog("── 目标：点到黎明界 top（259）──")
        if (!input.isAvailable()) {
            AppState.addLog("停手：点击后端不可用（没有 root）")
            return false
        }
        // ★安全闸门：只在**游戏前台**时才点（点错 App、点到系统弹窗都很危险）。
        //   ⚠但"**读不到**"不能立刻判失败（2026-10-16 实测踩到：一键黎明界刚 kill→launch 的那一两秒，
        //   无障碍被摘时只能靠 `su dumpsys`，它常返回空 ⇒ 读不到）。老代码在这一格直接 `return false`
        //   ⇒ **整条自动链路无声收摊**（日志还说成"前台已切到游戏"，纯误导：那句是同一个语句里
        //   第二次读的值，判定用的是第一次读空的值）。
        //   现在：**给游戏 [FRONT_WAIT_MS] 时间回到前台**，只有"读到、且不是游戏"才停手。
        val t0 = SystemClock.elapsedRealtime()
        var front = ""
        var launched = 0
        var pidCheckedAt = 0L
        while (true) {
            val p = Foreground.packageName()
            if (p == GameProcess.pkg()) break
            front = p ?: "读不到"
            val now = SystemClock.elapsedRealtime()
            // ★2026-11-12（用户："闪退恢复没有游戏进程检测和重启"）：闪退后前台一直是助手，
            //   只等前台永远等不到（等满 [FRONT_WAIT_MS] 就停手）。这里每 3s 看一眼游戏进程，
            //   不在就主动 `am start` 拉起（最多 2 次），再继续等它到前台。
            if (ctx != null && now - pidCheckedAt >= 3_000L) {
                pidCheckedAt = now
                if (FridaInjector.gamePid() <= 0 && launched < 2) {
                    launched++
                    AppState.addLog("…前台不是游戏、进程也不在 → 主动调起游戏（第 $launched 次）")
                    GameProcess.launch(ctx)
                }
            }
            if (now - t0 >= FRONT_WAIT_MS) {
                AppState.addLog(
                    "停手：${FRONT_WAIT_MS / 1000}s 内游戏没到前台（前台=$front）—— 自动点击只允许打在游戏上",
                )
                return false
            }
            delay(500)
        }
        var lastView = -1
        var stuck = 0
        var frontNull = 0
        // ★2026-11-12（用户反馈 ik："开始游戏界面无限点击"）：TITLE/SPLASH 上点中心的累计次数
        var centerTaps = 0
        // ★2026-11-12（用户）：**当前页面停留起点** —— 超过 [PAGE_STUCK_RESTART_MS] 没换页就重启游戏。
        //   `0` = 还没开始计（agent 未连/刚注入完成），注入前的启动时间一律不算。
        var pageSince = 0L
        // ★防"幽灵弹窗"无限连点（2026-10-17 事故：agent 学习路径给出同名幽灵节点 ⇒ 这里连点 40+ 次
        //   把"进迷宫"顶死）。同一颗按钮（名字+坐标）连点 [DLG_TAP_MAX] 次仍没换页 ⇒ 判定"点它没用"，
        //   不再点它（交给下面的 stuck 判定收工），比无限连点安全。
        var lastDlgSig: String? = null
        var dlgTapN = 0
        var agentWaitAt = 0L
        repeat(maxRounds) {
            // ★2026-11-12（用户）：**注入完成前不算"页面停留"** —— 拉起游戏 → 等映射 → 注入 →
            //   回连 → ready 这一段页面当然一直不变，不能因此触发 [PAGE_STUCK_RESTART_MS] 的
            //   "25s 重启"（否则启动慢的机器会被反复杀掉）。这里只等，最多 [AGENT_WAIT_MS]。
            if (!AgentBridge.connected.value) {
                val nw = SystemClock.elapsedRealtime()
                if (agentWaitAt == 0L) agentWaitAt = nw
                if (nw - agentWaitAt >= AGENT_WAIT_MS) {
                    AppState.addLog("停手：等 agent 注入/回连 ${AGENT_WAIT_MS / 1000}s 仍未连上")
                    return false
                }
                pageSince = 0L                    // 不计入页面停留
                delay(500)
                return@repeat
            }
            agentWaitAt = 0L
            if (pageSince == 0L) pageSince = SystemClock.elapsedRealtime()
            // 闸门每轮复查：**读到、且不是游戏** ⇒ 立刻停手（真切走了）；只是"读不到" ⇒ 本轮先不点、
            //   继续等（刚换页/刚重启时偶发读空，不该把整局判成失败）
            val fp = Foreground.packageName()
            if (fp != null && fp != GameProcess.pkg()) {
                AppState.addLog("停手：前台已切到 $fp（不再往游戏上点）")
                return false
            }
            if (fp == null) {
                frontNull++
                if (frontNull == 1 || frontNull % 20 == 0) {
                    AppState.addLog("… 前台读不到（dumpsys 没给）→ 本轮不点，继续等（第 $frontNull 轮）")
                }
                delay(500)
                return@repeat
            }
            frontNull = 0
            // 分派判据用**独立的 viewId 导出**（便宜、任何页面都有）。
            // ★别用 labStatus 的字段：它只在迷宫内有内容，TITLE/HOME 会返回 null（实测踩过）。
            val vid = currentViewId()
            if (vid < 0) {
                delay(500)
                return@repeat
            }
            AppState.setViewLabel(viewLabel(vid))

            if (vid != lastView) {
                AppState.addLog("页面 → ${viewLabel(vid)}")
                lastView = vid
                stuck = 0
                centerTaps = 0
                pageSince = SystemClock.elapsedRealtime()
                lastDlgSig = null                 // 换页 ⇒ 弹窗熔断计数清零
                dlgTapN = 0
            } else {
                // PC 版规矩：SPLASH/TITLE 这类没有具名按钮的界面**不限次数**
                //（游戏冷启动/加载可能要十几秒甚至更久，点早了就是白点）
                if (vid !in TAP_CENTER_VIEWS) stuck++
            }

            // ★2026-11-12（用户："25s 没到下个页面就直接重启进程"）：
            //   同一页面停留超过 [PAGE_STUCK_RESTART_MS] ⇒ 直接 kill（注入器会重新拉起+注入），
            //   不再靠"点别的位置"去猜 —— 停在标题页/加载页本身就是游戏侧卡住。
            if (pageSince > 0L && SystemClock.elapsedRealtime() - pageSince >= PAGE_STUCK_RESTART_MS) {
                AppState.addLog(
                    "停手：${viewLabel(vid)} 停留 ${PAGE_STUCK_RESTART_MS / 1000}s 没推进 → 重启游戏进程",
                )
                GameProcess.kill()
                // ★2026-11-13：总闸没开时注入循环在待命，没人把游戏拉回来（ACTION_GOTO_LAB
                //   这类独立入口就是这个状态）⇒ 自己补一次 am start，别留个死进程。
                // ★显式进程操作窗口内注入器被压制、不会自己 am start ⇒ 这个「卡页重启」的
                //   拉起动作必须由导航自己负责，否则游戏被 kill 后没人调回来。
                if (ctx != null && (!FridaInjector.autoRecover || GameProcess.externalRestart)) {
                    AppState.addLog("…注入器待命 / 显式进程操作窗口 ⇒ 自己把游戏调回来（不留死进程）")
                    GameProcess.launch(ctx)
                }
                return false
            }
            if (vid == TARGET_VIEW) {
                // ★文案（2026-10-30）：原来写"不点出发，这一步到此结束"，下一行迷宫状态机就点了出发，
                //   日志看起来自相矛盾。真实语义 = 本阶段只负责"点到 top"，出发交给迷宫状态机。
                AppState.addLog("已到「黎明界 top」${viewLabel(vid)} → 本阶段到此结束，交给迷宫状态机（接下来会点出发）")
                return true
            }

            // ★★**只有 264（LABYRINTH_MAP）才算"已经在局内"**（2026-10-19 用户）。
            //   260~263/265 是迷宫**子菜单**（奖励/强化/图鉴/公会设置/商店）：把 263 当成"在局内"
            //   会让后续状态机停在公会设置页上（`labStatus` 永远为空、findMap 反复堆扫描 → 卡死，实测过）。
            //   ⚠264 仍然要"不必回 259"（2026-10-17：自愈后停在地图，回 259 再出发既慢又可能新开一局）。
            if (vid == 264) {
                AppState.addLog("已在迷宫地图 ${viewLabel(vid)} → 不必回到 259，直接交给迷宫状态机")
                return true
            }
            if (vid in 260..265) {
                // 迷宫子菜单 → 点它的返回按钮回 259（找不到就本轮不点，等它自己变）
                val p = findNode("btn_back")
                if (p != null) {
                    tapUntilChange(input, p.first, p.second, "迷宫菜单返回（${viewLabel(vid)}）", vid)
                    return@repeat
                }
            }

            // ① 有通用对话框（公告/提示）先点掉，再谈别的
            //   ★★加载/瞬态页**不查**（2026-10-17 第四次卡死）：游戏刚注入、页面还是 `NONE(0)`（加载中）时，
            //   这一句会去调 `dialog()` = **DialogArea 子树遍历** ⇒ 实测直接把 agent 卡死（连 `ready` 都不回）、
            //   导航僵住 90s+ ✗。这些页只"等它自己变"/点中心 ✓（`SPLASH/TITLE` 本来也是这么处理的）。
            val dlg = if (vid in NO_HEAVY_VIEWS) null else dialogConfirmButton()
            if (dlg != null) {
                val sig = "${dlg.first}@${dlg.second},${dlg.third}"
                if (sig == lastDlgSig) dlgTapN++ else { lastDlgSig = sig; dlgTapN = 1 }
                if (dlgTapN > DLG_TAP_MAX) {
                    // ★熔断：这颗按钮点不动（幽灵弹窗/点上去没反应）⇒ 停止连点，让它走 stuck 判定
                    if (dlgTapN == DLG_TAP_MAX + 1) {
                        AppState.addLog(
                            "[!] 同一颗弹窗按钮「${dlg.first}」连点 $DLG_TAP_MAX 次都没推进 → **不再点它**" +
                                "（幽灵弹窗或点不动；要看真弹窗名请查 `现场` 日志的顶框）",
                        )
                    }
                } else {
                    // ★点完**探到对话框没了**就往下走（不再固定 delay 700）
                    tapUntilChange(input, dlg.second, dlg.third, "对话框「${dlg.first}」", vid)
                    return@repeat
                }
            }

            // ② 按页面点对应的具名按钮
            val btnName = STEP_BUTTON[vid]
            if (btnName != null) {
                // ★同一条避让：加载/瞬态页不做 `find`（全树遍历 ✗ —— 它的 1.5s 遍历缓存一次也要几百 ms~2s）
                val p = if (vid in NO_HEAVY_VIEWS) null else findNode(btnName)
                if (p != null) {
                    // ★点完**探到换页**就走（原来固定 delay 900ms）
                    tapUntilChange(input, p.first, p.second, btnName, vid)
                    return@repeat
                }
                AppState.addLog("这一页没有节点「$btnName」（${viewLabel(vid)}），下一轮再看")
            } else if (vid in TAP_CENTER_VIEWS) {
                // ★屏幕中心用**真实游戏相机分辨率**（AgentApi.screen()），不要写死 1920×1080；
                //   否则在非 1920×1080 的模拟器上（如 1280×720）会点偏（2026-10-18 用户反馈）。
                val sc = AgentApi.screen()
                val cw = (sc?.optInt("w", 0) ?: 0).let { if (it > 0) it else REF_W }
                val ch = (sc?.optInt("h", 0) ?: 0).let { if (it > 0) it else REF_H }
                // ★2026-11-12（用户反馈 ik："开始游戏界面无限点击，进不去"）：
                //   TITLE/SPLASH 点中心是**不限次数**的，一旦这一页的"开始"不在中心（新版 UI/公告挡住/
                //   点中心不响应），就会无限打转。⇒ 点上 6 次还没推进：先 dump 一次现场（顶框/按钮/
                //   DialogArea 子节点），之后每 4 轮改点一次**底部**（有些标题页的「开始」在下方）。
                centerTaps++
                if (centerTaps > 6 && centerTaps % 4 == 1) {
                    val b = runCatching { AgentApi.labDlgBox() }.getOrNull()
                    val kids = runCatching { AgentApi.labDlgKids() }.getOrNull()
                    val kb = StringBuilder()
                    val ka = kids?.optJSONArray("kids")
                    for (i in 0 until (ka?.length() ?: 0)) {
                        val o = ka?.optJSONObject(i) ?: continue
                        kb.append(o.optString("name")).append(if (o.optBoolean("active")) "✓" else "✗").append(' ')
                    }
                    val ba = b?.optJSONArray("buttons")
                    val bn = if (ba == null) "" else
                        (0 until ba.length()).joinToString(",") { ba.optJSONObject(it)?.optString("name").orEmpty() }
                    AppState.addLog(
                        "标题页点中心 $centerTaps 次没推进｜顶框=${b?.optString("top").orEmpty().ifEmpty { "无" }}" +
                            " 按钮=$bn｜DialogArea=[$kb]",
                    )
                }
                // ★2026-11-12（用户："不要猜着点，25s 没到下个页面就重启进程"）：
                //   不再试点底部（那是猜坐标）；只在卡住时 dump 一次现场，重启由下面的
                //   [PAGE_STUCK_RESTART_MS] 统一兜底。
                tapUntilChange(input, cw / 2, ch / 2, "屏幕中心", vid, settleMs = 300L, probe = 1, probeMs = 300L)
            } else {
                // ★未知页面（2026-10-19 用户定的**通用策略**）：活动页之类的没有通用确认按钮可点时，
                //   只要**底部有导航栏**就直接点「冒险」（ButtonQuest，同名多个时 findNode 取最靠下的
                //   = 底部导航）回到正常线 —— 不用一个个去分辨这是活动页还是什么页。
                //   连它都没有（战斗中/真异常页）才"停在未知页面"等。
                val q = if (vid in NO_HEAVY_VIEWS) null else findNode("ButtonQuest")
                if (q != null) {
                    if (vid != lastUnknownVid) {
                        lastUnknownVid = vid
                        AppState.addLog("未知页面 ${viewLabel(vid)} 有底部导航 → 点「冒险」回正常线")
                    }
                    tapUntilChange(input, q.first, q.second, "底部导航「冒险」", vid)
                    return@repeat
                }
                // 真·异常页：不猜着点，但**不立刻失败** —— 冷启动/换页会经过 0(NONE) 这类瞬态，
                //   立刻 return 会把"游戏还在加载"误判成失败（一键黎明界 kill+重启后必踩，实测 2026-10-16）。
                //   照 PC `MAX_ATTEMPTS`：不点、等它自己变；连续 stuck 轮没推进才收工（下面统一判定）。
                if (vid != lastUnknownVid) {
                    lastUnknownVid = vid
                    // ★"顺便认识一下"：把最上层对话框/视图名一起打出来（uiSig 很便宜，几十 ms）。
                    val top = AgentApi.uiSig()?.optString("top").orEmpty()
                    AppState.addLog(
                        "停在未知页面 ${viewLabel(vid)}｜顶层=${top.ifBlank { "?" }}" +
                            "：（没有弹窗、也没有底部导航，不猜着点，等它自己变）" +
                            "｜想认它就 adb: am startservice -n com.lmj.labyrinthauto/.service" +
                            ".AutoForegroundService -a com.lmj.labyrinthauto.action.TREE --es tag v$vid",
                    )
                }
            }

            if (stuck > 15) {
                AppState.addLog("停手：${viewLabel(vid)} 上连续 $stuck 轮没推进")
                return false
            }
            delay(700)
        }
        AppState.addLog("停手：超过 $maxRounds 轮没到 259")
        return false
    }

    /**
     * 「259 → 进迷宫」的结果。
     *
     * ★为什么不用 Boolean（2026-10-25 用户）：`false` 同时表示"没进去、该重试"和"到了 259 但压根
     *   没有进行中的开局"，上层只能一律重试 3 次 ⇒ 游戏明明就停在 259，还要空转十来秒、再打一句
     *   "连续 3 次都没成"，把"登错号"这种**确定性问题**伪装成偶发失败。
     */
    enum class EnterResult {
        /** 已进入迷宫地图（264），可交给迷宫内状态机 */
        ENTERED,

        /**
         * 已到 259，但服务器说**没有进行中的开局**（`labChallenging` = false）。
         * 按约定不自动开局 ⇒ 立即结束、不重试。
         * 正常流程是"先刷开局 → 再启动游戏"，所以这种情况**多半是当前登录的账号不对**
         * （登错号 / 小号没开局）——日志会提示用户去核对账号。
         */
        NOT_CHALLENGING,

        /** 真失败（agent 没连、导航没走到 259、点了出发没进图…）—— 值得重试 */
        FAILED,
    }

    /**
     * 259 → 进迷宫（PC 版外层状态机在 259 上的动作，`pcr_auto.py` 6923~6939）：
     * **只有"挑战中"才点 `labyrinth_thumb_start`（出发）**，会直接进地图不弹框；
     * 未挑战 → 不自动开局（PC 同款规矩），返回 [EnterResult.NOT_CHALLENGING]。
     */
    suspend fun enterMaze(input: InputBackend, maxTries: Int = 20): EnterResult {
        // ★2026-11-12（用户："想用状态驱动的点击"）：259 → 264 这段改成**状态机**，不再"点一下盲等 14s"。
        //   每 200ms 读一次 `labStatusLite`（viewId + pageTop，~10ms），按状态推进：
        //     · 264              → 进图，交棒给迷宫内状态机（唯一成功出口）
        //     · 260~263/265      → 误入黎明界菜单 → 点「返回」回 259（最多 2 次，再多就判失败）
        //     · 0/1 (NONE/SPLASH) → 加载转场，累计等 ≤10s
        //     · 259 + 有 Dialog*  → 先点掉公告/确认/票不足提示，再判断
        //     · 259 + challenging=false → 没开局（多半登错号）→ NOT_CHALLENGING
        //     · 259 + challenging=true  → 点「出发」（最多 [maxTries] 次，每次给 2.5s 反应）
        //     · 其它页面          → FAILED（掉线/顶号/异常）
        val t0 = System.currentTimeMillis()
        val budgetMs = 30_000L
        var taps = 0
        var menuHits = 0
        var loadWait = 0L
        var lastSaid = ""
        fun sayOnce(msg: String) {
            if (msg != lastSaid) { lastSaid = msg; AppState.addLog(msg) }
        }
        val gamePkg = runCatching { GameProcess.pkg() }.getOrNull().orEmpty()
        while (System.currentTimeMillis() - t0 < budgetMs) {
            if (!AgentBridge.connected.value) {
                AppState.addLog("停手：agent 没连上")
                return EnterResult.FAILED
            }
            // ★2026-11-12（用户）：进图阶段也要判前台 —— 切到助手/别的 app 就停手，不盲点
            if (gamePkg.isNotEmpty()) {
                val fp = runCatching { Foreground.packageName() }.getOrNull()
                if (fp != null && fp != gamePkg) {
                    AppState.addLog("停手：前台已切到 $fp（进图阶段不再点游戏）")
                    return EnterResult.FAILED
                }
            }
            // ★2026-11-12 修「进图途中页面变成 ?(-1)」：259 页 `labStatusLite` 的零扫描取不到 LTD
            //   （日志 `+0xF8 指向 LabyrinthTopMenuButton`）⇒ 返回 null、viewId 读成 -1。
            //   改用独立的 `viewId`（不依赖 LTD），259/264 都能稳定读到。
            val vid = currentViewId()
            if (vid == 264) {
                AppState.addLog("已进入地图 ${viewLabel(vid)} → 交给迷宫内状态机")
                return EnterResult.ENTERED
            }
            if (vid in LAB_MENU_VIEWS) {
                menuHits++
                if (menuHits > 2) {
                    AppState.addLog("停手：连续误入黎明界菜单（${viewLabel(vid)}）→ 不进图")
                    return EnterResult.FAILED
                }
                AppState.addLog("进图途中误入菜单 ${viewLabel(vid)}（第 $menuHits 次）→ 点返回回 259")
                val b = findNode("btn_back")
                if (b != null) tap(input, b.first, b.second, "返回（离开菜单）")
                delay(400)
                continue
            }
            if (vid == 0 || vid == 1) {
                loadWait += POLL_ENTER_MS
                if (loadWait > 10_000L) {
                    AppState.addLog("停手：加载页停留 ${loadWait / 1000}s（${viewLabel(vid)}）→ 不进图")
                    return EnterResult.FAILED
                }
                delay(POLL_ENTER_MS)
                continue
            }
            if (vid != TARGET_VIEW) {
                AppState.addLog("进图途中页面变成 ${viewLabel(vid)}（不在 259）→ 失败")
                return EnterResult.FAILED
            }
            // 259：先看有没有弹窗挡着（公告 / 确认 / 票不足提示）—— 原来这种情况会白等满 14s
            val cb = runCatching { dialogConfirmButton() }.getOrNull()
            if (cb != null) {
                AppState.addLog("259 上有弹窗挡着（${cb.first}）→ 先点掉")
                tap(input, cb.second, cb.third, "弹窗·${cb.first}")
                delay(300)
                continue
            }
            when (AgentApi.labChallenging()) {
                false -> {
                    AppState.addLog("已到「黎明界 top」但 259 未处于「挑战中」→ 按约定不自动开局，本步结束（PC 同款）")
                    // ★用户 2026-10-25：正常流程一定是"先刷开局 → 再启动游戏"，此时服务器必然有进行中的开局；
                    //   查不到 ⇒ 基本是**当前登录账号不对**（登错号 / 小号）。这类问题重试一百次也没用。
                    AppState.addLog("★请检查当前登录账号：该账号下没有进行中的黎明界开局（多半是登错号了）")
                    return EnterResult.NOT_CHALLENGING
                }
                null -> {
                    sayOnce("259 挑战中状态未知 → 稍后重读")
                    delay(POLL_ENTER_MS)
                    continue
                }
                else -> Unit
            }
            if (taps >= maxTries) break
            // ★2026-11-12（用户："应该有一种直接通过节点的读取方式"）：**字段直取**——
            //   不 findNode、不学坐标、不缓存。`ViewLabyrinthTop.challengeButton(+0xE8)` → transform
            //   → 相机投影，一次 RPC（~5~15ms）直接拿到按钮坐标。
            val sb = runCatching { AgentApi.labStartBtn() }.getOrNull()
            val px = sb?.optInt("x", -1) ?: -1
            val py = sb?.optInt("y", -1) ?: -1
            if (sb?.optBoolean("ok") != true || px < 0 || py < 0) {
                sayOnce("259 读不到出发按钮（${sb?.optString("err")?.ifEmpty { "rpc" } ?: "rpc"}）→ 稍后重试")
                delay(POLL_ENTER_MS)
                continue
            }
            taps++
            tap(input, px, py, "出发（挑战中=是，第 $taps 次）")
            // ★2026-11-12（用户："点了一下出发，下一次点击已经 2 秒后"）：
            //   原来点完给 2.5s 反应窗口，页面没变就一直等到满 ⇒ 两次点击间隔 2s+。
            //   现在只等 [ENTER_TAP_SETTLE_MS]（800ms，4 拍×200ms）；没变化就回去**再点一次**。
            val w0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - w0 < ENTER_TAP_SETTLE_MS) {
                delay(POLL_ENTER_MS)
                val v2 = currentViewId()
                if (v2 == 264 || v2 != TARGET_VIEW) break
            }
        }
        AppState.addLog("停手：进图超时（点了 $taps 次出发）")
        return EnterResult.FAILED
    }

    private const val REF_W = 1920
    private const val REF_H = 1080

    /** ★加载/瞬态页（`viewId`：0=NONE 1=SPLASH）：**只等、只点中心**，不做任何 UI 遍历。
     *  2026-10-17 第四次卡死：游戏刚注入、页面还是 `NONE(0)` 时调 `dialog()`（DialogArea 遍历）⇒
     *  agent 直接卡死（连 `ready` 都不回）、导航僵住 90s+ ✗。
     *  ★TITLE(2) **不在**这个名单里（2026-10-21 数据下载框实测）：TITLE 是加载完成后的**稳定页**，
     *    服务器推新数据时「数据下载」弹窗（TwoButton 取消/确认）会停在这儿 ⇒ 必须查 dialog 点掉，
     *    否则会傻点屏幕中心两分多钟（7555 13:54~13:57 实测）。NONE/SPLASH 仍免查（真瞬态）。 */
    private val NO_HEAVY_VIEWS = setOf(0, 1)

    /** 开始点击前，等游戏回到前台的时限（刚 kill→launch / 换页那一两秒"前台"常读不到） */
    private const val FRONT_WAIT_MS = 20_000L

    /**
     * ★2026-11-12（用户）：**注入/回连没完成时最多等这么久**。
     *   拉起游戏 → 等 `libil2cpp.so` 映射 → 注入 → 回连 → ready（agent 侧上限 30s）这段
     *   全都发生在注入完成前，不能算作"页面卡住"；60s 覆盖慢设备，超了才停手。
     */
    private const val AGENT_WAIT_MS = 60_000L

    /**
     * ★2026-11-12（用户）：**同一页面停留这么久还没换页 ⇒ 直接重启游戏进程**。
     *   标题页/加载页本来就"点一下推进一段"，但一旦卡住（点中心不响应/公告挡住/游戏侧卡死），
     *   以前会无限点下去（0.6.14 实机：TITLE 页点了 1 分 40 秒）。25s 足够覆盖正常冷启动+登录。
     */
    private const val PAGE_STUCK_RESTART_MS = 25_000L

    /**
     * ★旧：点「出发」前固定等 500ms（等 259 切屏动画）。
     * ★2026-11-12 停用：改为「坐标学一次 + 直点 + 点两次没进再重学」——不再靠时间等待，
     *   也不再每次 `findNode` 遍历 view 子树。
     */
    @Suppress("unused")
    private const val ENTER_TAP_DELAY_MS = 500L

    /** ★进图状态机（259 → 264）的探针间隔：`viewId` ~5ms，200ms 一拍够快 */
    private const val POLL_ENTER_MS = 200L

    /** ★点「出发」后的反应窗口：800ms 内页面没变就回去补点（原来 2.5s，两次点击间隔太长） */
    private const val ENTER_TAP_SETTLE_MS = 800L

    /** 黎明界子菜单页（260 奖励列表 / 261 强化 / 262 图鉴 / 263 公会设置 / 265 金币店）——不算进图 */
    private val LAB_MENU_VIEWS = setOf(260, 261, 262, 263, 265)

    /** ★同一颗弹窗按钮最多连点几次（超过 ⇒ 判"点不动"，见 2026-10-17 幽灵弹窗事故） */
    private const val DLG_TAP_MAX = 3

    /** 当前视图号（agent 的 `viewId` 导出；-1 = 没读到）。给服务/上层判断"现在在哪"用 */
    suspend fun currentViewId(): Int = AgentApi.viewId()

    /**
     * 按名字取按钮坐标。
     *
     * ★同名多个时取**最靠下**的那个（2026-10-19 实测）：七周年活动皮肤页里
     * `GUIGroup_ButtonQuest`(243,794)（活动入口）排在底部导航 `ButtonQuest`(1076,1035) 前面，
     * 按"第一个匹配"点就会跳进活动页（246→249），越走越远。底部导航永远在最下方 ⇒ 取 y 最大。
     */
    private suspend fun findNode(nameRe: String): Pair<Int, Int>? {
        // ★2026-11-12（用户："缓存不可靠，直接换成轻读取"）：
        //   原来 `AgentApi.findList(nameRe)` 走**整棵 UI 树**（实测 661 节点 ≈1.8s，还靠 1.5s 遍历缓存兜）。
        //   而这里找的按钮（底部导航 `ButtonQuest` / `btn_back` / `labyrinth_thumb_start`）**都在当前 View 子树里**
        //   ⇒ 先走 `root="view"`（几十节点，几十 ms）；查不到再试 `layer`（DialogArea 同层，覆盖挂在外层的按钮）；
        //   两者都空才退回无根整树（极少见，只作兜底）。
        // ★`footer` = 底部导航层（`UI Root/ViewsArea/PartsFooter`）：HOME 的 `ButtonQuest` 就在这里，
        //   几十个节点，比整树兜底快一个量级。
        for (root in arrayOf("view", "footer", "layer", "dialog")) {
            val hit = pickBottom(AgentApi.findList(nameRe, root))
            if (hit != null) {
                if (root == "dialog") AppState.addLog("导航：按钮「$nameRe」只在 dialog 里找到")
                return hit
            }
        }
        // ★2026-11-12 实测回归：HOME(5) 页的底部导航 `ButtonQuest` **不在 view / layer / dialog 任何一个根里**
        //   （删掉全树兜底后，HOME 上连续 16 轮读不到 ⇒ 停手）。⇒ 兜底恢复，但**只在最后**用，
        //   并打日志说明"这个按钮需要补根"，方便下次定位它挂在哪个 UI 根上。
        pickBottom(AgentApi.findList(nameRe))?.let {
            // ★2026-11-12（用户："把捕获的内容加上"）：兜底命中时**逐个根报数**，
            //   一眼看出这个按钮到底挂在哪层（`view/layer/dialog/top` 各几颗；全 0 = 在更外层）。
            //   只在兜底时付出这 4 次小查询，正常页面走不到这里。
            val where = StringBuilder()
            for (r in arrayOf("view", "layer", "dialog", "top")) {
                val c = runCatching { AgentApi.findList(nameRe, r)?.length() ?: 0 }.getOrDefault(0)
                where.append(r).append('=').append(c).append(' ')
            }
            AppState.addLog("导航：按钮「$nameRe」只在整树里找到（各根：$where）")
            return it
        }
        return null
    }

    /**
     * 同名多个时取**最靠下**的那个（2026-10-19 实测）：七周年活动页里 `GUIGroup_ButtonQuest`(243,794)
     * 排在底部导航 `ButtonQuest`(1076,1035) 前面，按"第一个匹配"会跳进活动页；底部导航永远在最下方。
     */
    private fun pickBottom(arr: JSONArray?): Pair<Int, Int>? {
        if (arr == null || arr.length() == 0) return null
        var bx = 0
        var by = Int.MIN_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has("x") || !o.has("y")) continue
            val y = o.optInt("y")
            if (y > by) { by = y; bx = o.optInt("x") }
        }
        return if (by == Int.MIN_VALUE) null else bx to by
    }

    /** 通用弹窗的"确定/关闭"按钮：名字必须在白名单里，认不出来就不点（PC 版同款）。
     *
     *  ★2026-11-09：`dialog()` 是全量遍历（同一页 0.7s，选人面板上可达 5s），这里只需要
     *    通用按钮白名单 ⇒ 换成字段直取的 [AgentApi.labDlgBtn]（~50ms），按钮名完全一致。 */
    private suspend fun dialogConfirmButton(): Triple<String, Int, Int>? {
        val d = AgentApi.labDlgBtn("") ?: return null
        if (d.optString("top").isBlank()) return null
        val arr = d.optJSONArray("buttons") ?: return null
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val n = b.optString("name")
            if (DIALOG_CONFIRM.contains(n)) return Triple(n, b.optInt("x"), b.optInt("y"))
        }
        return null
    }

    private suspend fun tap(input: InputBackend, x: Int, y: Int, what: String): Boolean {
        return when (val r = input.tap(x, y)) {
            is InputResult.Ok -> {
                AppState.addLog("点了 $what ($x,$y)［${input.name}］")
                true
            }
            is InputResult.Failed -> { AppState.addLog("点 $what 失败：${r.reason}"); false }
            InputResult.Unavailable -> { AppState.addLog("点 $what 失败：${input.name} 后端不可用"); false }
        }
    }

    /**
     * "当前在哪"的组合签名：`viewId|最上层对话框名` —— 两者**任一变化**都算"页面变了"。
     *
     * ★为什么要合起来：进入迷宫之前的流程里，点「确定」关掉公告时 **viewId 不变**（还在首页），
     *   只有顶层对话框消失；而点「冒险」/「黎明界」时是 **viewId 变**、顶层对话框没动。
     *   只看其中一个都会漏判。
     *
     * @return `"!read"` = 这一轮读不到（[TapCheck] 会把它当成"还没离开"，绝不误判成"点生效了"）
     */
    private suspend fun locSig(vid: Int): String {
        if (vid < 0) return "!read"
        val top = runCatching { AgentApi.labTop() }.getOrNull() ?: return "!read"
        return "$vid|$top"
    }

    /**
     * ★**点一下 → 探到页面真的变了就立刻往下走**（2026-11-08 用户："这个流程是很顺的，
     *   符合 tapAndCheck 的使用场景"）。
     *
     * 原来这里每点一下都**固定 `delay(700~900ms)`**：
     *   · 切完得早 ⇒ 白等（首页那一段实测 `冒险 → 公告 → 冒险 → 黎明界` 来回花了 7 秒）；
     *   · 切完得晚 ⇒ 下一轮再读一次，还可能重复点同一个按钮（日志里 `ButtonQuest` 点了两次）。
     * 现在改成探针：**变了就走，没变就继续探**（[probe]×[probeMs] 的窗口）。
     *
     * @param fromVid 点之前的 viewId（判据 = `viewId|顶框` 不再是它）
     */
    private suspend fun tapUntilChange(
        input: InputBackend, x: Int, y: Int, what: String, fromVid: Int,
        settleMs: Long = 150L, probe: Int = 4, probeMs: Long = 150L,
    ): Boolean {
        val from = locSig(fromVid)
        if (from == "!read") return false        // 连"现在在哪"都读不到 ⇒ 不点（跟主循环同口径）
        return TapCheck.run(
            tapFn = { tx, ty, tw -> tap(input, tx, ty, tw) },
            x = x, y = y, what = what,
            from = setOf(from),
            readTop = { locSig(currentViewId()) },
            unknownTop = "!read",
            settleMs = settleMs, probe = probe, probeMs = probeMs,
        )
    }
}
