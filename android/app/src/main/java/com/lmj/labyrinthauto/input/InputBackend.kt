package com.lmj.labyrinthauto.input

/**
 * 点击后端接口。**只走 root**（`RootInputBackend`：`su -c input tap`，与 PC 版同一条路）。
 * ★无障碍后端已整体移除（2026-10-16）：没 root 时 frida-server 都起不来，兜底没有意义。
 *
 * 约定（与 PC 版 `tap_xy` 对齐）：
 *  - 坐标一律是**参考系 1920×1080**（PC 版/agent 的坐标），由各个实现自己换算；
 *  - 屏外坐标 → 直接失败，不点（PC 版有同样的总闸，避免"点穿了"未知位置）。
 */
sealed interface InputResult {
    /** 手势已被系统接受（不代表游戏一定响应 —— 与 PC 版一样，"点完回读校验"是调用方的事）。 */
    data object Ok : InputResult

    /** 明确失败（坐标出界、服务没连、手势被拒…）。 */
    data class Failed(val reason: String) : InputResult

    /** 后端当前不可用（没授权/没连接）。 */
    data object Unavailable : InputResult
}

interface InputBackend {
    /** 给日志/诊断看的人话名字。 */
    val name: String

    /** 后端此刻能不能用（root 已就绪…）。 */
    fun isAvailable(): Boolean

    suspend fun tap(x: Int, y: Int): InputResult

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMillis: Long = 300L): InputResult

    suspend fun back(): InputResult
}
