package com.lmj.labyrinthauto.frida

import android.content.Context
import android.content.Intent
import com.lmj.labyrinthauto.core.Accounts
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

    /** 国服包名：**只作兜底默认值**，实际要操作哪个客户端看 [pkg] */
    const val GAME_PKG = "com.bilibili.priconne"

    /**
     * ★当前要操作的目标客户端包名（2026-10-30 用户："一键黎明界写死了 B 服"）。
     *
     * 以前全局写死 `com.bilibili.priconne`，于是渠道服（`com.bilibili.priconne.yofun.mumu` 等）
     * 用户点「一键黎明界」会被直接判成"没装国服"而停手。现在由 [syncTargetFromAccount]
     * 在服务收到意图时按**当前选中的账号**设定，所有默认参数都走 [pkg]。
     */
    @Volatile
    private var targetPkg: String = GAME_PKG

    /** 当前目标客户端包名（渠道服就是渠道包）。 */
    fun pkg(): String = targetPkg

    /**
     * 按"当前选中的账号"切换目标客户端；返回最终生效的包名。
     *
     * 调用点：服务每次收到意图（onStartCommand）时一次、助手启动时一次。没有选中账号时
     * 保持原值（= 国服），不会因为"账号表是空的"就把目标改成不存在的东西。
     */
    fun syncTargetFromAccount(ctx: Context): String {
        val acct = Accounts.selected(ctx)
        val p = acct?.server?.packageName
        if (!p.isNullOrBlank() && p != targetPkg) {
            targetPkg = p
            AppState.addLog("目标客户端：${acct.server.displayName}（$p）")
        }
        return targetPkg
    }

    /** 显式指定目标（已知包名的场景，如抓凭证）。 */
    fun setTarget(pkg: String) {
        if (pkg.isNotBlank()) targetPkg = pkg
    }

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
     *
     * @param pkg 目标包名；默认是自动化跑的那个包。[AccessCapture] 会传渠道包名
     *   （抓 uid/access_key 时启动的是各渠道客户端，不是默认那个包）。
     */
    suspend fun kill(pkg: String = targetPkg): Boolean {
        kills++
        // ★杀进程 = 会话边界（2026-10-22 用户）：屏幕中心加速只在"本局结算后"有效，进程一杀必然失效，
        //   别让上一局的开关带到新开局的选人页。
        CenterTapGate.reset("杀游戏进程")
        AppState.addLog("杀游戏进程（force-stop $pkg）")
        val r = RootShell.exec("am force-stop $pkg", 15_000L)
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
    suspend fun launch(ctx: Context, pkg: String = targetPkg): Boolean {
        val comp = resolveLauncherComponent(pkg)
        val cmd = if (comp != null) {
            "am start -n $comp"
        } else {
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg"
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
        val intent = app.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            AppState.addLog("读不到 $pkg 的 launcher intent（没装？）")
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
    private suspend fun resolveLauncherComponent(pkg: String): String? = RootShell.exec(
        "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg",
        12_000L,
    ).out.lineSequence().map { it.trim() }.firstOrNull { it.contains("/") }
}
