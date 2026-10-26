package com.lmj.labyrinthauto.input

import android.os.SystemClock
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.frida.RootShell

/**
 * "现在前台是谁" —— 自动点击的**安全闸门**。
 *
 * `PLAN.md §0.2` 就定了这条规矩：**自动化只在游戏在前台时才点**（点错 App、点到系统弹窗都很危险；
 * 2026-10-14 实测确实会遇到：屏幕上是系统设置页时，盲点会点到设置项上）。
 *
 * ★★2026-10-27 换数据源：`dumpsys activity activities` → `dumpsys input`（**紧急**）
 *   下面这段是那次的真实崩溃栈（用户测试机 MuMu / vivo V2241A / Android 15 / x86_64）：
 *
 *     Fatal signal 11 (SIGSEGV), fault addr 0x6c, pid 848 (system_server)
 *     Cause: null pointer dereference
 *     #00 TaskUtils.shouldIgnoreDesktopHomeOcclusion
 *     #01 TaskFragment.getVisibility
 *     #02 Task.toFullString
 *     #03 TaskFragment.dumpInner / #04 Task.dump
 *     #18 RootWindowContainer.dumpActivities
 *     #19 ActivityTaskManagerService.dumpActivitiesLocked
 *     #23 ActivityManagerService.doDump
 *
 *   ⇒ 我们每 500ms 一次的 `dumpsys activity activities` 打在 `AMS.dumpActivitiesLocked` 上，
 *     system_server 在 Android 15 的 desktop-windowing 新代码里空指针崩溃，**整个系统所有 App
 *     一起消失**（现象就是"游戏和脚本都没了"，2026-10-23、2026-10-26 各中一次，
 *     tombstone_00 / tombstone_01 的 Cmdline 都是 `system_server`）。
 *   ⇒ 所以这里**永远不要**再用 `dumpsys activity`，也不要用不带参数的 `dumpsys window`
 *     （同样会遍历任务树，落到 `Task.dump → getVisibility` 同一个崩溃点）。
 *
 * ★现在用的来源：`dumpsys input` 的 `FocusedWindows`
 *   （`InputManagerService.dump`，dump 的是输入分发器状态，**完全不碰窗口/任务树**）。
 *   实测输出（Android 15）：
 *
 *     FocusedWindows:
 *       displayId=0, name='71d0adf com.bilibili.priconne/com.bilibili.priconne.MainActivity'
 *
 *   语义上它比原来的 `topResumedActivity` **更贴近我们要的东西**："下一次 tap 会落到哪个窗口"。
 *   差异（有意接受，且都偏保守）：
 *     · 输入法/系统弹窗抢到焦点 ⇒ 这里读到的不是游戏 ⇒ 不点（老逻辑会照点，本来就有风险）；
 *     · 真的没有焦点窗口（锁屏/转场）⇒ 读不到 ⇒ 不点。
 *   ⚠`grep -A3 -m1` 是必须的：`FocusedWindows:` 后面那行才是窗口名。
 *
 * 读不到 ⇒ `false`（**宁可不点**，绝不 fail-open）。
 * ⚠但"读不到"**不等于"切走了"** —— 调用方要区分（见 `Navigator.goToLabyrinthTop` 的闸门）。
 */
object Foreground {

    /** TTL 缓存：没缓存等于每轮起一个 `su dumpsys`（≈0.1~0.5s）。
     *  ⚠TTL 别太大：这是"点错 App"的安全闸门，宁可多查一次。 */
    @Volatile private var cachedPkg: String? = null
    @Volatile private var cachedAt = 0L
    private const val TTL_MS = 500L

    /** 一次读不到就刷屏；同一句话最多每 [LOG_EVERY_MS] 说一次（换源后要能看出"哪个界面读不到"）。 */
    @Volatile private var lastMissLogAt = 0L
    private const val LOG_EVERY_MS = 60_000L

    /** 当前前台包名；null = 没读出来（没有焦点窗口 / 焦点窗口名不含组件名 / 命令失败） */
    suspend fun packageName(): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt < TTL_MS) return cachedPkg
        val pkg = readFocusPkg()
        cachedPkg = pkg
        cachedAt = now
        return pkg
    }

    /** 游戏是不是在前台（不确定 ⇒ false） */
    suspend fun isGameForeground(gamePkg: String): Boolean = packageName() == gamePkg

    // ────────────────────────── 内部 ──────────────────────────

    /**
     * 从 `dumpsys input` 的 `FocusedWindows` 段取前台包名。
     *
     * ⚠读数失败**只返回 null，绝不回退到 `dumpsys activity` / `dumpsys window`** ——
     *   那两条都会把 system_server 打崩（见类注释）。宁可不点。
     */
    private suspend fun readFocusPkg(): String? {
        val out = RootShell.exec(CMD_INPUT, 8_000L).out
        val name = FOCUS_NAME.find(out)?.groupValues?.getOrNull(1)
        if (name != null) {
            COMPONENT.find(name)?.groupValues?.getOrNull(1)?.let { return it }
        }
        // 读不到：要么真没焦点窗口（锁屏/转场），要么焦点窗口名不含 "包名/Activity"。
        // 后者如果是常态（比如某个界面永远读不到），这里能第一时间在日志里看出来。
        val now = System.currentTimeMillis()
        if (now - lastMissLogAt > LOG_EVERY_MS) {
            lastMissLogAt = now
            AppState.addLog(
                "前台读不到（dumpsys input" +
                    (if (name != null) "：焦点窗口名「$name」不含组件名" else "：没有 FocusedWindows") +
                    "）→ 本轮不点",
            )
        }
        return null
    }

    /** 只取焦点窗口那几行：`FocusedWindows:` 之后 3 行内就有 name（全量 dump 没必要，也省时间）。 */
    private const val CMD_INPUT = "dumpsys input | grep -A3 -m1 FocusedWindows"

    /** `displayId=0, name='71d0adf com.bilibili.priconne/com.bilibili.priconne.MainActivity'` */
    private val FOCUS_NAME = Regex("name='([^']*)'")

    /** 匹配 `com.bilibili.priconne/com.bilibili.priconne.MainActivity` 里的包名（必须含至少一个点，
     *  免得把 `71d0adf` 这种窗口哈希当成包名）。 */
    private val COMPONENT = Regex("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+")
}
