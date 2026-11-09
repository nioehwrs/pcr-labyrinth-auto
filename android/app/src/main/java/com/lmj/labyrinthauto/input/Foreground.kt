package com.lmj.labyrinthauto.input

import android.os.Build
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
     *
     *  ★2026-11-09：500ms → 2000ms。原因：编组页连点间隔约 300ms，500ms 的 TTL 意味着
     *    **每 2 次点击就有 1 次缓存过期**，那一下要多跑一遍 `su dumpsys input`
     *    ⇒ 表现成"每两个点卡一下"的鼓点节奏（去掉固定 delay 后才暴露出来）。
     *    放宽到 2s 后，一个连点批次（5 个约 1.5s）最多只查一次。
     *  ★安全性不靠这个 TTL：[MazeLoop] 主循环每轮用 `packageName(force = true)` **强制刷新**，
     *    所以"前台被切走"仍然在下一轮（约 1s）就能发现。 */
    @Volatile private var cachedPkg: String? = null
    @Volatile private var cachedAt = 0L
    private const val TTL_MS = 2_000L

    /** 一次读不到就刷屏；同一句话最多每 [LOG_EVERY_MS] 说一次（换源后要能看出"哪个界面读不到"）。 */
    @Volatile private var lastMissLogAt = 0L
    private const val LOG_EVERY_MS = 60_000L

    /** 当前前台包名；null = 没读出来（没有焦点窗口 / 焦点窗口名不含组件名 / 命令失败）。
     *
     *  @param force `true` = 忽略 TTL 立刻重查。**主循环每轮、导航闸门**用这个；
     *               高频连点（[MazeLoop.tap]）用默认值走缓存，否则每 2 下就要多花 0.1~0.5s。 */
    suspend fun packageName(force: Boolean = false): String? {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - cachedAt < TTL_MS) return cachedPkg
        val pkg = readFocusPkg()
        cachedPkg = pkg
        cachedAt = now
        return pkg
    }

    /** 游戏是不是在前台（不确定 ⇒ false） */
    suspend fun isGameForeground(gamePkg: String): Boolean = packageName() == gamePkg

    // ────────────────────────── 内部 ──────────────────────────

    /**
     * 取前台包名。
     *
     * 两条来源，按系统版本分流（2026-10-27 实测补的坑）：
     *
     *  · **Android 13+（SDK ≥ 33）**：`dumpsys input` 的 `FocusedWindows` 段有内容，用它。
     *    实测（Android 15 / MuMu）：
     *      `FocusedWindows:` → `displayId=0, name='71d0adf com.bilibili.priconne/com.bilibili.priconne.MainActivity'`
     *
     *  · **Android 12 及以下（SDK ≤ 32）**：`dumpsys input` 里 `FocusedWindows:` 是**空段**
     *    （实测 Samsung b0q / Android 12 / SDK 32：`focusedWindows:` 后面直接是 `FocusRequests:`，
     *    里面只有一堆 `name='xxx ScreenDecorOverlay'` 这类系统窗口，没有带组件的窗口名）
     *    ⇒ 解析必然失败，表现为日志里的
     *      `前台读不到（dumpsys input：没有 FocusedWindows）→ 本轮不点`
     *    于是"读不到前台"→ 一步都不点（用户 2026-10-27 报的"Android 12 上不动了"）。
     *    这类系统改用 `dumpsys window displays` 里的 `mCurrentFocus`（实测有、且很快）。
     *
     *  ⚠为什么敢在 12 上用 `dumpsys window displays`：那条命令会遍历任务树，Android 15 上会踩
     *    `TaskUtils.shouldIgnoreDesktopHomeOcclusion`（desktop windowing 的 15 新代码）把 system_server
     *    打崩；**Android 12 没有这段代码**，实测安全。所以这里严格按 SDK 分流，绝不在 15 上回退。
     *
     *  ⚠两条都失败时**只返回 null**（不点），绝不回退到 `dumpsys activity`。
     */
    private suspend fun readFocusPkg(): String? {
        // 本机已经确定用哪条源之后，就只跑那一条（少起一次 su）
        if (!useWindowSource) {
            val out = RootShell.exec(CMD_INPUT, 8_000L).out
            val name = FOCUS_NAME.find(out)?.groupValues?.getOrNull(1)
            if (name != null) {
                COMPONENT.find(name)?.groupValues?.getOrNull(1)?.let { return it }
            }
            // ★2026-11-09 用户报障（伊丽莎白 / Android 14 / REDMI 25060RK16C）：
            //   那台 SDK 34 的 `dumpsys input` **也没有 FocusedWindows 段** ⇒ 前台永远读不到
            //   ⇒ 主循环"本轮不点" → 20s 超时 → 连撞 3 次停手
            //   （用户："点击一键黎明界，只打开游戏，没有点击动作"）。
            //   所以回退条件从 `<= 32` 放宽到 `<= 34`（Android 14 及以下）。
            //   **SDK 35+ 仍然不回退** —— Android 15 的 `dumpsys window displays` 会踩
            //   `TaskUtils.shouldIgnoreDesktopHomeOcclusion` 把 system_server 打崩（见方法注释）。
            if (Build.VERSION.SDK_INT <= 34) {
                useWindowSource = true
                AppState.addLog(
                    "前台判定：本机 dumpsys input 取不到焦点窗口（Android ${Build.VERSION.SDK_INT}）" +
                        "→ 改用 dumpsys window displays 的 mCurrentFocus（15 及以上不走这条）",
                )
            } else {
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
        }
        val w = RootShell.exec(CMD_WINDOW, 8_000L).out
        COMPONENT.find(w)?.groupValues?.getOrNull(1)?.let { return it }
        val now = System.currentTimeMillis()
        if (now - lastMissLogAt > LOG_EVERY_MS) {
            lastMissLogAt = now
            AppState.addLog("前台读不到（dumpsys window displays：没有 mCurrentFocus）→ 本轮不点")
        }
        return null
    }

    /** 本机是否改用 WMS 焦点源（Android 12 及以下；试出来一次就记住） */
    @Volatile private var useWindowSource = false

    /** 只取焦点窗口那几行：`FocusedWindows:` 之后 3 行内就有 name（全量 dump 没必要，也省时间）。 */
    private const val CMD_INPUT = "dumpsys input | grep -A3 -m1 FocusedWindows"

    /** 备用源（Android 14 及以下）：`mCurrentFocus=Window{… u0 包名/Activity}`。
     *  ★2026-11-09：有些 ROM/版本用 `mFocusedWindow`，两个都匹配（只取第一行）。 */
    private const val CMD_WINDOW =
        "dumpsys window displays | grep -m1 -E 'mCurrentFocus|mFocusedWindow'"

    /** `displayId=0, name='71d0adf com.bilibili.priconne/com.bilibili.priconne.MainActivity'` */
    private val FOCUS_NAME = Regex("name='([^']*)'")

    /** 匹配 `com.bilibili.priconne/com.bilibili.priconne.MainActivity` 里的包名（必须含至少一个点，
     *  免得把 `71d0adf` 这种窗口哈希当成包名）。 */
    private val COMPONENT = Regex("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+")
}
