package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.reroll.RerollSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 凹分模式的两个纯计算（2026-11-01 用户需求）：基础分公式 + 难度倍率换算。
 *
 * 基准来自 5 层结算页截图：51 角色 + 25 遗物，难度 5，总分 50,600，明细
 * 踏破 5600 / 角色 5100 / 遗物 5000 / 普通 600 / 极难 6000 / 首领 3000。
 */
class ScoreChaseTest {

    @Test
    fun `结算页校准：51 角色 25 遗物 → 基础分 25300`() {
        assertEquals(25_300, RerollSettings.scoreBaseOf(51, 25))
    }

    @Test
    fun `难度 5 的 2_0 倍率 → 与截图总分 50600 一致`() {
        val base = RerollSettings.scoreBaseOf(51, 25)
        assertEquals(50_600, RerollSettings.scoreThreshold(5, base))
    }

    @Test
    fun `不同难度的倍率换算不丢一分`() {
        // 1.4f/1.7f 是二进制近似值，直接 toInt() 会少 1 —— 这里钉死四舍五入
        assertEquals(23_000, RerollSettings.scoreThreshold(1, 23_000))
        assertEquals(27_600, RerollSettings.scoreThreshold(2, 23_000))
        assertEquals(32_200, RerollSettings.scoreThreshold(3, 23_000))
        assertEquals(39_100, RerollSettings.scoreThreshold(4, 23_000))
        assertEquals(54_000, RerollSettings.scoreThreshold(5, 27_000))
    }

    @Test
    fun `空局的最低基础分`() {
        // 一个角色都没有也有固定分（踏破 + 三个 Boss）
        assertEquals(15_200, RerollSettings.scoreBaseOf(0, 0))
    }
}
