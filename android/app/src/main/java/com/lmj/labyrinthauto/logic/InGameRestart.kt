package com.lmj.labyrinthauto.logic

import android.content.Context
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
 * 渠道适配（用户交代）：B 服回标题后再登录**始终自动**；部分渠道服冷启动自动登录、
 * 但游戏内重启后的 SDK 登录会弹**手动确认窗** → Navigator 的标题页停留超时会 kill 自愈
 * （等效回退冷启动），只是每局白等几十秒 —— 所以留了 `RerollSettings.loadInGameRestart` 开关。
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
        val r1 = AgentApi.pressNode("^ButtonMenu$")
        if (r1?.optBoolean("ok") != true) {
            AppState.addLog("游戏内重启：ButtonMenu 直调没成（${r1?.optString("err") ?: "无响应"}）")
            return false
        }
        if (!waitViewIn(setOf(VIEW_MENU), STEP_WAIT_MS)) {
            AppState.addLog("游戏内重启：点了主菜单但 ${STEP_WAIT_MS / 1000}s 内没到 MENU 页（现在 ${Navigator.viewLabel(AgentApi.viewId())}）")
            return false
        }

        // ③ 回到标题（MENU 页左下 BackTitle → 弹确认框）
        val r2 = AgentApi.pressNode("^BackTitle$")
        if (r2?.optBoolean("ok") != true) {
            AppState.addLog("游戏内重启：BackTitle 直调没成（${r2?.optString("err") ?: "无响应"}）")
            return false
        }
        if (!waitTopDialog("DialogCommonSmall", STEP_WAIT_MS)) {
            AppState.addLog("游戏内重启：点了「回到标题」但确认框没弹出来（top=${AgentApi.uiSig()?.optString("top")}）")
            return false
        }

        // ④ 确认（DialogCommonSmall 两键版，右键 = 确认）
        val r3 = AgentApi.pressNode("^TwoButtonRight$", "dialog")
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
}
