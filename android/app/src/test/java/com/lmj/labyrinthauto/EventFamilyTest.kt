package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.logic.EventFamily
import com.lmj.labyrinthauto.logic.EventRetreatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手工事件族表（[EventFamily]）的口径测试：族归属、结果归一、老虎机的过程判定 */
class EventFamilyTest {

    @Test
    fun `事件族：一个事件只登记在一个族里`() {
        val seen = mutableSetOf<Int>()
        for (f in EventFamily.all) {
            for (e in f.eventIds) assertTrue("事件 $e 被重复登记到多个族", seen.add(e))
        }
    }

    @Test
    fun `事件族：公会与竞技场没有随机结果`() {
        assertFalse(EventFamily.byName("公会管理协会")!!.hasRandom)
        assertFalse(EventFamily.byName("魔物竞技场")!!.hasRandom)
        assertTrue(EventFamily.byName("钓鱼")!!.hasRandom)
    }

    @Test
    fun `事件族：钓鱼按累计件数分档（高星不退还低星）`() {
        // 每一格失败 ⇒ 只到手的那些件（★ 不还回去，所以是 0/1/2 件）
        assertEquals(131412, EventFamily.canonical(131412))
        assertEquals(231412, EventFamily.canonical(231412))
        assertEquals(331412, EventFamily.canonical(331412))
        // 三格全成功（331411）不登记：钓鱼成功不用撤
        assertNull(EventFamily.canonical(331411))
        // 中间"成功并进下一格"也不是结论
        assertNull(EventFamily.canonical(131411))
        assertNull(EventFamily.canonical(231411))
        assertEquals(3, EventFamily.byName("钓鱼")!!.outcomes.size)
    }

    @Test
    fun `事件族：迷路把 110811 与 110813 合并成一个遗物结果`() {
        assertEquals(110811, EventFamily.canonical(110813))
        assertEquals(110811, EventFamily.canonical(110811))
        assertEquals(110812, EventFamily.canonical(110812))
    }

    @Test
    fun `事件族：猜拳三张卡按胜败归成两条`() {
        assertEquals(111011, EventFamily.canonical(111021))
        assertEquals(111011, EventFamily.canonical(111031))
        assertEquals(111012, EventFamily.canonical(111022))
        assertEquals(111012, EventFamily.canonical(111032))
    }

    @Test
    fun `老虎机：成功1次 = 第1次成功后收手`() {
        val sm = EventFamily.SlotMachine()
        assertEquals(setOf<Int>(), sm.feed(131611))
        assertEquals(setOf(231621), sm.feed(231621))
    }

    @Test
    fun `老虎机：成功2次 = 两次成功后收手`() {
        val sm = EventFamily.SlotMachine()
        sm.feed(131611)
        sm.feed(231611)
        assertEquals(setOf(331621), sm.feed(331621))
    }

    @Test
    fun `老虎机：成功3次 = 三次全成功`() {
        val sm = EventFamily.SlotMachine()
        sm.feed(131611)
        sm.feed(231611)
        assertEquals(setOf(331611), sm.feed(331611))
    }

    @Test
    fun `老虎机：任意一次失败都算失败`() {
        assertEquals(setOf(131612), EventFamily.SlotMachine().feed(131612))
        EventFamily.SlotMachine().let {
            it.feed(131611)
            assertEquals(setOf(131612), it.feed(231612))
        }
        EventFamily.SlotMachine().let {
            it.feed(131611)
            it.feed(231611)
            assertEquals(setOf(131612), it.feed(331612))
        }
    }

    @Test
    fun `老虎机：没赢过就收手、或步骤对不上，都不算成功`() {
        assertEquals(setOf<Int>(), EventFamily.SlotMachine().feed(231621))
        EventFamily.SlotMachine().let {
            it.feed(131611)
            assertEquals(setOf<Int>(), it.feed(331621))   // 跳过了第 2 格
        }
    }

    @Test
    fun `条件：钓鱼配遗物不到2件时，第2格失败才撤`() {
        val t = EventRetreatRule.Tracker(
            listOf(EventRetreatRule.Cond(listOf(1314, 2314, 3314), resultId = 231412, useResult = true)),
        )
        assertEquals(emptyList<Int>(), t.feed(1314, 0, 131411))   // 第1格成功，继续
        assertEquals(listOf(0), t.feed(2314, 0, 231412))          // 第2格失败 ⇒ 1 件
    }

    @Test
    fun `条件：老虎机配成功2次，要两次成功后收手才撤`() {
        val t = EventRetreatRule.Tracker(
            listOf(EventRetreatRule.Cond(listOf(1316, 2316, 3316), resultId = 331621, useResult = true)),
        )
        assertEquals(emptyList<Int>(), t.feed(1316, 0, 131611))
        assertEquals(emptyList<Int>(), t.feed(2316, 0, 231611))
        assertEquals(listOf(0), t.feed(3316, 0, 331621))
    }

    @Test
    fun `条件：老虎机配失败，任意一步失败就撤`() {
        val t = EventRetreatRule.Tracker(
            listOf(EventRetreatRule.Cond(listOf(1316, 2316, 3316), resultId = 131612, useResult = true)),
        )
        assertEquals(emptyList<Int>(), t.feed(1316, 0, 131611))
        assertEquals(listOf(0), t.feed(2316, 0, 231612))
    }

    @Test
    fun `条件：活动会场的获得角色按事件前后角色差判定，三档不互斥`() {
        fun c(id: Int) = EventRetreatRule.Cond(listOf(1112), resultId = id, useResult = true)
        // 差值 2 ⇒ <3、<4、<5 全成立
        assertEquals(setOf(111221, 111222, 111223), EventFamily.unitOutcomesBelow(2))
        // 差值 3 ⇒ <4、<5
        assertEquals(setOf(111222, 111223), EventFamily.unitOutcomesBelow(3))
        // 差值 4 ⇒ 只有 <5
        assertEquals(setOf(111223), EventFamily.unitOutcomesBelow(4))
        // 差值 5 ⇒ 一个都不成立
        assertEquals(setOf<Int>(), EventFamily.unitOutcomesBelow(5))
        // 条件上：差 3 时配 <3 不撤、配 <4 撤
        assertEquals(emptyList<Int>(), EventRetreatRule.Tracker(listOf(c(111221))).feed(1112, 0, 111221, 3))
        assertEquals(listOf(0), EventRetreatRule.Tracker(listOf(c(111222))).feed(1112, 0, 111221, 3))
        // 金币那条不受角色差影响
        val gold = EventRetreatRule.Cond(listOf(1112), resultId = 111211, useResult = true)
        assertEquals(listOf(0), EventRetreatRule.Tracker(listOf(gold)).feed(1112, 0, 111211, 0))
    }

    @Test
    fun `事件族：只有活动会场那三个结果看角色差`() {
        assertTrue(EventFamily.isUnitRid(111221))
        assertTrue(EventFamily.isUnitRid(111223))
        assertFalse(EventFamily.isUnitRid(111211))
        assertFalse(EventFamily.isUnitRid(131412))
    }
}
