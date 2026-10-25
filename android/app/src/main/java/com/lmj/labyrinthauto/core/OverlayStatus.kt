package com.lmj.labyrinthauto.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ★悬浮窗要显示的三件事（2026-10-17 用户）：**第几局 / 第几层 / 当前状态**。
 *
 * 定位：只被**写**（连跑循环写"第几局"，[com.lmj.labyrinthauto.logic.MazeLoop] 的各 handler
 * 写"第几层 + 在干什么"），由 [com.lmj.labyrinthauto.ui.OverlayWindow] 读走显示。
 * 这里**不做决策、不写日志、不碰设备** —— 纯粹是"给人看的一行字"，所以用最简单的三段字符串。
 *
 * 为什么不用 StateFlow<页面现场>：状态机本来每轮都有 `st`/`pageTop`，多背一份状态只会分叉；
 * 让写的人各自 `setXxx(...)`，显示端只负责拼行，最不容易出错。
 */
object OverlayStatus {
    /** 悬浮窗左侧状态色点：一眼看出是否在跑 / 是否出错 */
    enum class Level { IDLE, RUNNING, ERROR }

    /**
     * @param run   第几局，如 `第 2/3 局`
     * @param floor 第几层，如 `第 3 层`
     * @param state 当前状态，如 `编队：水兔 环奈 …` / `购买 2★ 毒液沙罗曼蛇`
     * @param level 运行等级（色点用）
     */
    data class Snap(
        val run: String = "",
        val floor: String = "",
        val state: String = "",
        val level: Level = Level.IDLE,
    ) {
        /** 合成一行（空段自动省略）—— 悬浮窗显示的就是它 */
        val line: String
            get() = listOf(run, floor, state).filter { it.isNotEmpty() }.joinToString(" ｜ ")
    }

    private val _snap = MutableStateFlow(Snap())
    val snap: StateFlow<Snap> = _snap.asStateFlow()

    fun setRun(s: String?) {
        _snap.value = _snap.value.copy(run = s?.take(20).orEmpty())
    }

    fun setFloor(s: String?) {
        // 放宽到 20：节点类型改成中文短名后（如「节点12345 普通战」）需要更多字数
        _snap.value = _snap.value.copy(floor = s?.take(20).orEmpty())
    }

    fun setState(s: String?) {
        _snap.value = _snap.value.copy(state = s?.take(64).orEmpty())
    }

    /** 显式设运行等级（色点用）。连跑开始 ⇒ RUNNING；失败 ⇒ ERROR；clear ⇒ IDLE */
    fun setLevel(l: Level) {
        _snap.value = _snap.value.copy(level = l)
    }

    /** 连跑结束/停手：整行清空（悬浮窗会显示 `…`），等级复位 IDLE */
    fun clear() {
        _snap.value = Snap()
    }
}
