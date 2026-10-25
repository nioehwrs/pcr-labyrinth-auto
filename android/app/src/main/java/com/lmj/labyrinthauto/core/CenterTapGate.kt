package com.lmj.labyrinthauto.core

/**
 * ★「屏幕中心加速」总闸（2026-10-22 用户改版）。
 *
 * 语义：**只有本局过了结算分数页（`DialogLabyrinthResult`）之后**，才允许"不管当前是什么页面，
 * 每轮点一下屏幕中心"来推进开箱/翻牌/奖励展示。
 *
 * 为什么不能只放在 [com.lmj.labyrinthauto.logic.MazeLoop] 里：
 * 它的关闭点发生在**迷宫循环之外** —— API 刷初始、局内刷初始、杀游戏进程、一局结束。
 * 之前只在"编队页"里重置，第二局**开局选角色**（发生在第一个编队页之前）就带着上一局的 true
 * ⇒ 在选人页误点屏幕中央（实机踩过）。
 *
 * 调用约定：
 *  - 打开：[activate] —— `MazeLoop` 在"已见过结算页、且出现了新的非结算页面"时调用；
 *  - 关闭：[reset] —— 每次刷初始前 / 每次杀进程 / 每局开始与结束。
 *    重复调用无副作用（只有真的"开→关"才写一行日志）。
 */
object CenterTapGate {

    @Volatile
    private var active = false

    fun isActive(): Boolean = active

    /** 本局已过结算分数页 → 打开（之后不再挑页面，见 `MazeLoop` 的加速分支） */
    fun activate(reason: String) {
        if (active) return
        active = true
        AppState.addLog("开启屏幕中心加速（$reason）：之后不管什么页面，每轮点中心推进")
    }

    /** 会话边界关闭。重复调用无副作用 */
    fun reset(reason: String) {
        if (!active) return
        active = false
        AppState.addLog("关闭屏幕中心加速（$reason）")
    }
}
