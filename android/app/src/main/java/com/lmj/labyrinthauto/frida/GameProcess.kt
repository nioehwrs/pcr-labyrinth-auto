package com.lmj.labyrinthauto.frida

import android.content.Context
import android.content.Intent
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.core.CenterTapGate

/**
 * 游戏进程的"生杀" —— 对应 PC 版 `pcr_auto.py` 的 [1]/[2] 两步：
 *   [1] `step_kill()`  ：`am force-stop`（只在"要重开"时用，例如换开局 / 看门狗复位）
 *   [2] `step_launch()`：解析 launcher activity → 启动它
 *
 * （[3] "等到 `libil2cpp.so` 已映射"在 `FridaInjector.waitGameReady()` 里 —— 它必须和注入节奏
 *   绑在一起，不能单独成一个孤立步骤；pid 轮询也留在那儿，因为走的是 frida-core 的进程枚举。）
 *
 * 两个函数都是**幂等**的：已经在跑就什么都不做。P5 的状态机以后直接调这两个。
 */
object GameProcess {

    /** 渠道包名表见 `PLAN.md §2`；P1~P2 只做这一个 */
    const val GAME_PKG = "com.bilibili.priconne"

    /** ★游戏被杀的累计次数（所有重启路径都经 [kill] ⇒ 用它对上层广播"游戏重启过"）。
     *  用途：`MazeLoop` 靠它发现"游戏被重建过"，从而清掉那些**建立在"游戏侧状态还在"之上的缓存**
     *  （典型：`特别装备` 的"与上一场同一套队 ⇒ 装备没被动过"跳过判据 —— 2026-10-16 用户实测
     *  "卡死重启之后组队没有选特别装备"就是这个）。 */
    @Volatile
    var kills = 0
        private set

    /**
     * [1] 杀掉游戏进程（= PC 版 `step_kill()` 的 `am force-stop`）。
     * 杀别的 app 需要 root。
     */
    suspend fun kill(): Boolean {
        kills++
        // ★杀进程 = 会话边界（2026-10-22 用户）：屏幕中心加速只在"本局结算后"有效，进程一杀必然失效，
        //   别让上一局的开关带到新开局的选人页。
        CenterTapGate.reset("杀游戏进程")
        AppState.addLog("杀游戏进程（force-stop $GAME_PKG）")
        val r = RootShell.exec("am force-stop $GAME_PKG", 15_000L)
        val failed = r.text.contains("Error", ignoreCase = true) || r.text.contains("Exception", ignoreCase = true)
        if (failed) AppState.addLog("force-stop 结果：${r.text.take(120)}")
        return !failed
    }

    /**
     * [2] 调起游戏（= PC 版 `step_launch()`）。两条路：
     *   ① **首选 root `am start`**：PC 版就是这么干的（它经 adb 的 shell 执行 `am start`），最可靠；
     *   ② 兜底：常规 `getLaunchIntentForPackage` + `startActivity`（不需要 root）。
     *
     * ★② 那条路有个坑（2026-10-14 实测）：Android 10+ 的"后台启动 Activity"限制里
     *   **前台服务不算豁免**。我们只有前台服务、没有可见窗口时，`startActivity` 不抛异常但会被
     *   系统静默掐掉，logcat 里是：
     *     `W ActivityTaskManager: Background activity start … allowBackgroundActivityStart: false`
     *     `E ActivityTaskManager: Abort background activity start from <uid>`
     *   —— 所以不能靠它，只能当"root 那条起不来"时的备用。
     */
    suspend fun launch(ctx: Context): Boolean {
        val comp = resolveLauncherComponent()
        val cmd = if (comp != null) {
            "am start -n $comp"
        } else {
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $GAME_PKG"
        }
        val r = RootShell.exec(cmd, 20_000L)
        val failed = r.exit == -1 ||
            r.text.contains("Error", ignoreCase = true) ||
            r.text.contains("Exception", ignoreCase = true) ||
            r.text.contains("Permission denial", ignoreCase = true)
        if (!failed) {
            AppState.addLog("已调起游戏（root am start）")
            return true
        }
        AppState.addLog("root am start 失败（${r.text.take(120)}）→ 试常规 Intent")

        val app = ctx.applicationContext
        val intent = app.packageManager.getLaunchIntentForPackage(GAME_PKG)
        if (intent == null) {
            AppState.addLog("读不到 $GAME_PKG 的 launcher intent（没装？）")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { app.startActivity(intent); true }.getOrDefault(false)
        AppState.addLog(
            if (ok) "已发 Intent 调起游戏（若 20s 后仍无进程，看 logcat 有没有 Abort background activity start）"
            else "Intent 调起抛异常",
        )
        return ok
    }

    /** 解析游戏的 launcher 组件名（= PC 版那条 `cmd package resolve-activity --brief`）。 */
    private suspend fun resolveLauncherComponent(): String? = RootShell.exec(
        "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $GAME_PKG",
        12_000L,
    ).out.lineSequence().map { it.trim() }.firstOrNull { it.contains("/") }
}
