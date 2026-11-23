package com.lmj.labyrinthauto.logic

import android.content.Context
import org.json.JSONObject
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.frida.AgentApi
import com.lmj.labyrinthauto.frida.GameProcess
import com.lmj.labyrinthauto.input.Foreground
import kotlinx.coroutines.delay

/**
 * ★游戏内重启（2026-11-23）：**不杀进程**，走 UI 链「主菜单 → 回到标题画面 → 确认」回到标题页。
 *
 * 背景：接口刷模式下每局开头 `GameProcess.kill()` 冷启动，游戏要把 ~1.9GB 资产重新灌进 guest
 * （实测 1 分钟 RSS +900MB），这个负载尖峰会让 MuMu 的 lmkd 进 panic 模式
 * （`reason: device is not responding`），专杀最大的后台进程 —— 就是本 app（RunJournal 看门狗
 * 就是为这个兜的底）。回标题 = 客户端只回到登录前状态，不触发资产重载，从源头掐掉触发条件。
 *
 * 实测链路（官服 B 服，2026-11-23，全程 `pressNode` 直调、不用坐标）：
 *   1. 任何看得到导航条（footer）的页（259 实测）→ 直调 `ButtonMenu` → view 77（MENU）
 *   2. MENU 页直调 `BackTitle`（ViewMenuTop.BottomButtonGroup）→ 弹 `DialogCommonSmall` 确认框
 *   3. 直调 `TwoButtonRight`（root=dialog）→ 转场启动（SPLASH(1)，6~9s 后落 TITLE(2)）
 *   4. ★到 3 就返回（转场与 reroll 并行）；之后 Navigator 从 SPLASH/TITLE 点屏幕中心
 *      （`TAP_CENTER_VIEWS` 两态都点）→ SDK 自动登录 → HOME(5)，约 12~17s（B 服实测全自动）
 *
 * 渠道适配（2026-11-23 用户定稿：游戏内重启是唯一正常路径，**不提供改用冷启动的开关**）：
 * B 服回标题后再登录**始终自动**；部分渠道服冷启动自动登录、但游戏内重启后的 SDK 登录
 * 会弹**手动确认窗** → Navigator 的标题页停留超时会 kill 自愈（等效回退冷启动），
 * 只是每局白等几十秒。这类渠道临时不适配 → 引导用户发反馈。
 */
object InGameRestart {

    /** 看得到导航条（footer 含 `ButtonMenu`）的页面（eViewId）。MENU(77) 本身也有 footer（进入后还能再点） */
    private val FOOTER_VIEWS = setOf(5, 20, 47, 77, 259)

    private const val VIEW_MENU = 77
    private const val VIEW_SPLASH = 1
    private const val VIEW_TITLE = 2

    /** 确认框（`DialogCommonSmall`）弹出/转场的单步等待上限 */
    private const val STEP_WAIT_MS = 12_000L
    /** 确认后等"转场已启动"的上限（实测 ≤3s 进 SPLASH、6~9s 落 TITLE，留足余量） */
    private const val TITLE_WAIT_MS = 30_000L

    /** 调起游戏后等到前台的上限（am start 到窗口获得焦点，模拟器上几秒） */
    private const val FRONT_WAIT_MS = 15_000L

    /**
     * 走 UI 链回到标题页。前提：游戏进程活着、agent 已连。
     *
     * ★任何一步没成（按钮找不到 / 页面不对 / 超时）都返回 false —— 调用方回退
     * `GameProcess.kill()` 冷启动，行为与旧版完全一致。链走到一半失败也没关系，
     * 杀进程对任何页面都有效。
     *
     * @return true = 确认已生效、回标题转场已启动（SPLASH/TITLE），后续交给 reroll + Navigator
     */
    suspend fun returnToTitle(ctx: Context): Boolean {
        val t0 = android.os.SystemClock.elapsedRealtime()

        // ⓪ 游戏必须在前台（上一局正常退出后助手会被带回前台）：am start 幂等 ——
        //   进程活着就只是把窗口顶上来，**不会**重启进程、不触发资产重载。
        val pkg = GameProcess.pkg()
        if (Foreground.packageName(force = true) != pkg) {
            AppState.addLog("游戏内重启：游戏不在前台 → 先调起（am start，进程不动）")
            if (!GameProcess.launch(ctx, pkg)) return false
            val f0 = android.os.SystemClock.elapsedRealtime()
            var front = false
            while (android.os.SystemClock.elapsedRealtime() - f0 < FRONT_WAIT_MS) {
                if (Foreground.packageName(force = true) == pkg) { front = true; break }
                delay(500)
            }
            if (!front) {
                AppState.addLog("游戏内重启：${FRONT_WAIT_MS / 1000}s 内游戏没到前台 → 回退冷启动")
                return false
            }
        }

        // ① 入口页：必须看得到导航条（agent 没连上时 viewId 会回 -1，同样在这里拦住）
        val vid = AgentApi.viewId()
        if (vid !in FOOTER_VIEWS) {
            AppState.addLog("游戏内重启：当前页 ${Navigator.viewLabel(vid)} 看不到导航条 → 走不了 UI 链")
            return false
        }

        // ② 主菜单（footer 右下 ButtonMenu → MENU(77)）
        val r1 = pressStep("^ButtonMenu$")
        if (r1?.optBoolean("ok") != true) {
            AppState.addLog("游戏内重启：ButtonMenu 直调没成（${r1?.optString("err") ?: "无响应"}）")
            return false
        }
        if (!waitViewIn(setOf(VIEW_MENU), STEP_WAIT_MS)) {
            AppState.addLog("游戏内重启：点了主菜单但 ${STEP_WAIT_MS / 1000}s 内没到 MENU 页（现在 ${Navigator.viewLabel(AgentApi.viewId())}）")
            return false
        }

        // ③ 回到标题（MENU 页左下 BackTitle → 弹确认框）
        val r2 = pressStep("^BackTitle$")
        if (r2?.optBoolean("ok") != true) {
            AppState.addLog("游戏内重启：BackTitle 直调没成（${r2?.optString("err") ?: "无响应"}）")
            return false
        }
        if (!waitTopDialog("DialogCommonSmall", STEP_WAIT_MS)) {
            AppState.addLog("游戏内重启：点了「回到标题」但确认框没弹出来（top=${AgentApi.uiSig()?.optString("top")}）")
            return false
        }

        // ④ 确认（DialogCommonSmall 两键版，右键 = 确认）
        val r3 = pressStep("^TwoButtonRight$", "dialog")
        if (r3?.optBoolean("ok") != true) {
            AppState.addLog("游戏内重启：确认键直调没成（${r3?.optString("err") ?: "无响应"}）")
            return false
        }
        // ⑤ 只等"转场已启动"（view 变成 SPLASH(1) 或 TITLE(2)）就返回 —— 不死等 TITLE：
        //   确认后那 6~9s 转场是纯客户端演出，reroll 是纯 API 调用，两者并行，能省整段转场时间。
        //   点屏幕加速交给后面 Navigator（它本来就在 SPLASH/TITLE 点中心）。
        //   ⚠认不出转场（停在 77/确认框没关）仍算失败 → 回退杀进程。
        if (!waitViewIn(setOf(VIEW_SPLASH, VIEW_TITLE), TITLE_WAIT_MS)) {
            AppState.addLog("游戏内重启：确认后 ${TITLE_WAIT_MS / 1000}s 内转场没启动（现在 ${Navigator.viewLabel(AgentApi.viewId())}）")
            return false
        }
        // ⑥ ★进程没动 ⇒ agent 侧的实例/TTL 缓存（上一局的 dialog / LTD 指针）全部失效 —— 清一遍。
        //   重注入路径 agent 全新无缓存，这里补齐对齐；回标题后 UI 整个重建，旧指针本来也没用了。
        runCatching { AgentApi.labResetCaches() }
        AppState.addLog("游戏内重启 → 回标题转场已启动（${(android.os.SystemClock.elapsedRealtime() - t0) / 1000}s，进程没动）")
        return true
    }

    /** 等 viewId 落进 [wants] 里任意一个。 */
    private suspend fun waitViewIn(wants: Set<Int>, timeoutMs: Long): Boolean {
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            if (AgentApi.viewId() in wants) return true
            delay(300)
        }
        return false
    }

    /** 等最上层对话框变成 [name]（`uiSig` 便宜，几十 ms 一次）。 */
    private suspend fun waitTopDialog(name: String, timeoutMs: Long): Boolean {
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val sig = AgentApi.uiSig()
            if (sig != null && sig.optString("top").removeSuffix("(Clone)") == name) return true
            delay(300)
        }
        return false
    }

    /** 点返回后等到黎明界 top 的上限（转场几秒，留余量） */
    private const val TOP_WAIT_MS = 12_000L

    /**
     * ★直调一步，带**投递超时重试**（2026-11-23 实测 0.7.21 首轮：10 次开局里 4 次
     * "投递超时：游戏线程 4000ms 内没执行"→ 直接回退杀进程冷启动）。
     *
     * 根因：每局开始注入会话全拆重建（`自动黎明界结束 → 停止注入 → 进入自动黎明界 → 重注入同一个 pid`），
     * 回标题链恰好顶着"agent 刚回连、游戏主线程还在忙 frida 脚本加载"的窗口投递 —— 4s 太紧。
     * ⇒ 超时放宽到 10s + 投递超时再重试一次（等 3s 让游戏线程喘口气）；
     *   节点真找不到这类失败不重试（回退语义不变）。
     */
    private suspend fun pressStep(nameRe: String, root: String? = null): JSONObject? {
        var last: JSONObject? = null
        repeat(2) { i ->
            if (i > 0) {
                AppState.addLog("游戏内重启：${nameRe.removeSurrounding("^", "$")} 投递超时 → 3s 后重试")
                delay(3_000)
            }
            last = AgentApi.pressNode(nameRe, root, probe = false, timeoutMs = 10_000L)
            if (last?.optBoolean("ok") == true) return last
            if (last?.optString("err")?.contains("投递超时") != true) return last
        }
        return last
    }

    /** 现场收敛循环的轮数上限（编组页→预览页→地图→259，每层一步，留足余量） */
    private const val CONVERGE_ROUNDS = 10

    /**
     * ★接口刷「撤退重跑」的游戏内路径（2026-11-23 用户定稿+实机验证）：把客户端从迷宫内**带回黎明界
     * top(259)**，进程全程不动。失败页/预览页/裸地图都走**真撤退**（HUD 撤退链/失败页链，
     * 服务器局当场作废）；只有迷宫子菜单走返回键（旧局由下一局 reroll 开局 `top()+retire` 清）。
     *
     * 撤退触发点不止地图一种，按现场分派（全部直调、不用坐标 tap）：
     *  · **战斗失败页**（FAIL_3_TIMES，`handleQuestFailed` 触发时正停在 DialogQuestFailed）→
     *    [retreatFromFailPageDirect]：「结束」→「撤退（无报酬）」→ 二重确认 → 259（2026-11-23 实测闭环）；
     *  · **编组页**（NO_PLAN，连续算不出队伍）→ `labPartyBtn("cancel")` 丢弃 → 回预览页
     *    （2026-11-23 实测：onClickCancel 直调有效，落 264+预览页）；
     *  · **战斗预览页 / 裸地图**（DialogQuestDetail / 264）→ [retreatViaHud]。
     *    ★头部返回 `btn_back` 关不掉预览页（实测：onHeaderBackKey 执行了 ex=0x0 但盖层不动）；
     *    裸地图也统一走 HUD 撤退（同一个按钮、同一串调用，2026-11-23 用户拍板）——
     *    真撤退当场作废服务器局，不再依赖"下一局 reroll 顺带清"；
     *  · **迷宫子菜单**（260~263/265，HUD 不在）→ `btn_back` 回 259，旧局由下一局 reroll 的 API 清；
     *  · 顶层是别的通用弹窗（DialogCommon*）→ 认不出，不猜，回退。
     *
     * 预览页/编组页现场的撤退走 **HUD 撤退按钮**（[retreatViaHud]）：`LabyrinthRetireButton`
     * （PartsLabyrinthMapInformation.onClickRetireButton）在盖层下也是激活的，两键确认一次
     * （右键）直接落 259 —— 2026-11-23 在预览页开着时实测闭环。
     *
     * @return true = 已回 259（页面已收敛，可继续游戏内重启链/等 reroll）；false = 调用方回退杀进程路径
     */
    suspend fun backToTopFromMaze(): Boolean {
        repeat(CONVERGE_ROUNDS) { round ->
            val vid = AgentApi.viewId()
            if (vid == 259) {
                AppState.addLog("撤退（游戏内）：已回黎明界 top（进程没动${if (round == 0) "" else "，收敛 $round 步"}）")
                return true
            }
            val top = AgentApi.uiSig()?.optString("top").orEmpty().removeSuffix("(Clone)")
            when {
                top.startsWith("DialogQuestFailed") -> {
                    if (!retreatFromFailPageDirect()) return false
                }
                top.startsWith("DialogCommonPartyEdit") -> {
                    val r = AgentApi.labPartyBtn("cancel")
                    if (r?.optBoolean("ok") != true) {
                        AppState.addLog("撤退（游戏内）：编组页「取消」直调没成（${r?.optString("err") ?: "无响应"}）→ 回退")
                        return false
                    }
                }
                top.startsWith("DialogQuestDetail") -> {
                    if (!retreatViaHud()) return false
                }
                top.startsWith("DialogCommon") -> {
                    AppState.addLog("撤退（游戏内）：顶层是未识别的通用弹窗（$top）→ 不猜，回退")
                    return false
                }
                vid == 264 -> {
                    // 裸地图：HUD 撤退（与预览页同一条链、同一个按钮，真撤退）
                    if (!retreatViaHud()) return false
                }
                vid in 260..265 -> {
                    val r = pressStep("^btn_back$")
                    if (r?.optBoolean("ok") != true) {
                        AppState.addLog("撤退（游戏内）：迷宫页返回键直调没成（${r?.optString("err") ?: "无响应"}）→ 回退")
                        return false
                    }
                }
                else -> {
                    AppState.addLog("撤退（游戏内）：现场认不出（${Navigator.viewLabel(vid)}/top=$top）→ 回退")
                    return false
                }
            }
            delay(800)      // 每收敛一层给转场一点时间，下一轮重新读现场
        }
        AppState.addLog("撤退（游戏内）：$CONVERGE_ROUNDS 轮没收敛到 259（现在 ${Navigator.viewLabel(AgentApi.viewId())}）→ 回退")
        return false
    }

    /**
     * HUD 撤退链（2026-11-23 实测，全程 EventDelegate.Execute ok/ex=0x0）：
     * `LabyrinthRetireButton`（PartsLabyrinthMapInformation.onClickRetireButton，盖层下也激活）
     * → 两键确认窗「确认」（`TwoButtonRight`，只有一层）→ 259。真撤退（无报酬作废服务器局）。
     * 预览页与裸地图共用这条链（按钮是地图 HUD 自己的部件，现场一致，无需区分）。
     */
    private suspend fun retreatViaHud(): Boolean {
        val r = pressStep("^LabyrinthRetireButton$")
        if (r?.optBoolean("ok") != true) {
            AppState.addLog("撤退（游戏内）：HUD「撤退」直调没成（${r?.optString("err") ?: "无响应"}）→ 回退")
            return false
        }
        if (!waitTopDialog("DialogCommonSmall", STEP_WAIT_MS)) {
            AppState.addLog("撤退（游戏内）：点了 HUD「撤退」但确认窗没弹出来 → 回退")
            return false
        }
        val c = pressStep("^TwoButtonRight$", "dialog")
        if (c?.optBoolean("ok") != true) {
            AppState.addLog("撤退（游戏内）：撤退确认直调没成（${c?.optString("err") ?: "无响应"}）→ 回退")
            return false
        }
        if (!waitViewIn(setOf(259), TOP_WAIT_MS)) {
            AppState.addLog("撤退（游戏内）：HUD 撤退后 ${TOP_WAIT_MS / 1000}s 没回 259（现在 ${Navigator.viewLabel(AgentApi.viewId())}）→ 回退")
            return false
        }
        return true
    }

    /**
     * 战斗失败页的直调撤退链（2026-11-23 实测，全程 EventDelegate.Execute ok/ex=0x0）：
     * 「结束」（`UIButton_UnitList` → DialogQuestFailed.onClickEndButton，节点名误导）
     * → 三键确认窗「撤退（无报酬）」（`ThreeButtonCenter` → DialogCommonSmall.onClickCenterButton）
     * → 二重确认「确认」（`TwoButtonRight` → onClickRightButton，4s 内出现才点）
     * → 等 259。
     */
    private suspend fun retreatFromFailPageDirect(): Boolean {
        val end = pressStep("^UIButton_UnitList$", "dialog")
        if (end?.optBoolean("ok") != true) {
            AppState.addLog("撤退（游戏内）：失败页「结束」直调没成（${end?.optString("err") ?: "无响应"}）→ 回退")
            return false
        }
        if (!waitTopDialog("DialogCommonSmall", STEP_WAIT_MS)) {
            AppState.addLog("撤退（游戏内）：点了「结束」但确认窗没弹出来 → 回退")
            return false
        }
        val c = pressStep("^ThreeButtonCenter$", "dialog")
        if (c?.optBoolean("ok") != true) {
            AppState.addLog("撤退（游戏内）：「撤退（无报酬）」直调没成（${c?.optString("err") ?: "无响应"}）→ 回退")
            return false
        }
        // 二重确认：4s 内弹出 DialogCommonSmall 就点右键；没弹（直接落 259）也无妨。只点一次。
        val t0 = android.os.SystemClock.elapsedRealtime()
        var confirmed = false
        while (android.os.SystemClock.elapsedRealtime() - t0 < 4_000L && !confirmed) {
            if (AgentApi.viewId() == 259) break
            val sig = AgentApi.uiSig()
            if (sig?.optString("top").orEmpty().startsWith("DialogCommonSmall")) {
                pressStep("^TwoButtonRight$", "dialog")
                confirmed = true
            }
            delay(300)
        }
        if (!waitViewIn(setOf(259), TOP_WAIT_MS)) {
            AppState.addLog("撤退（游戏内）：失败页撤退后 ${TOP_WAIT_MS / 1000}s 没回 259（现在 ${Navigator.viewLabel(AgentApi.viewId())}）→ 回退")
            return false
        }
        return true
    }
}
