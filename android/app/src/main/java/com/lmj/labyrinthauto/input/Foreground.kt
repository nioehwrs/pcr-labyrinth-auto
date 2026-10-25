package com.lmj.labyrinthauto.input

import com.lmj.labyrinthauto.core.AppState
import android.os.SystemClock
import com.lmj.labyrinthauto.frida.RootShell

/**
 * "现在前台是谁" —— 自动点击的**安全闸门**。
 *
 * `PLAN.md §0.2` 就定了这条规矩：**自动化只在游戏在前台时才点**（点错 App、点到系统弹窗都很危险；
 * 2026-10-14 实测确实会遇到：屏幕上是系统设置页时，盲点会点到设置项上）。
 *
 * 只有一个来源：root `dumpsys activity activities` 的 `topResumedActivity`
 * （约 100ms，起一个 su；有 500ms 缓存）。无障碍那条线已整体移除（2026-10-16）。
 *
 * 读不到 ⇒ `false`（**宁可不点**，绝不 fail-open）。
 * ⚠但"读不到"**不等于"切走了"** —— 调用方要区分（见 `Navigator.goToLabyrinthTop` 的闸门）。
 */
object Foreground {

    /** TTL 缓存：**无障碍不是必须的**（点击后端走 root），没连时每次读都得起一个 `su dumpsys`
     *  （≈0.1~0.5s），而主循环每轮都在查 ⇒ 没缓存等于每轮跑一次 su。2026-10-16 加。
     *  ⚠TTL 别太大：这是"点错 App"的安全闸门，宁可多查一次。 */
    @Volatile private var cachedPkg: String? = null
    @Volatile private var cachedAt = 0L
    private const val TTL_MS = 500L

    /** 当前前台包名；null = 两个来源都没读出来 */
    suspend fun packageName(): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt < TTL_MS) return cachedPkg
        // 只有一个来源：root `dumpsys`（无障碍已整体移除，2026-10-16）
        val out = COMPONENT.find(
            RootShell.exec("dumpsys activity activities | grep -m1 topResumedActivity", 12_000L).out,
        )?.groupValues?.getOrNull(1)
        cachedPkg = out
        cachedAt = now
        return out
    }

    /** 游戏是不是在前台（不确定 ⇒ false） */
    suspend fun isGameForeground(gamePkg: String): Boolean = packageName() == gamePkg

    /** 匹配 `ActivityRecord{… u0 com.bilibili.priconne/com.bilibili.priconne.MainActivity …}` 里的组件名 */
    private val COMPONENT = Regex("([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+")
}
