package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.core.OverlayStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 状态悬浮窗那一行字的拼装规则（2026-10-17 加）。
 *
 * 为什么值得一个测试：悬浮窗只有一行、用户盯着它判断"跑到哪了"，
 * 所以"空段不能留下孤立的分隔符"这种小事必须钉死（否则会显示成 `第 2/3 局 ｜ ｜ 编队：…`）。
 * 纯函数，不需要设备。
 */
class OverlayStatusTest {

    @Test
    fun `三段齐全按顺序拼`() {
        val s = OverlayStatus.Snap(run = "第 2/3 局", floor = "第 3 层", state = "编队：水兔 环奈")
        assertEquals("第 2/3 局 ｜ 第 3 层 ｜ 编队：水兔 环奈", s.line)
    }

    @Test
    fun `空段自动省略且不留分隔符`() {
        assertEquals("第 3 层", OverlayStatus.Snap(floor = "第 3 层").line)
        assertEquals("第 2/3 局 ｜ 战斗中", OverlayStatus.Snap(run = "第 2/3 局", state = "战斗中").line)
        assertEquals("", OverlayStatus.Snap().line)
    }

    @Test
    fun `写入会截断超长段`() {
        OverlayStatus.setState("x".repeat(200))
        assertEquals(64, OverlayStatus.snap.value.state.length)
        OverlayStatus.setRun("第 " + "9".repeat(50) + " 局")
        assertEquals(20, OverlayStatus.snap.value.run.length)
        OverlayStatus.clear()
        assertEquals("", OverlayStatus.snap.value.line)
    }
}
