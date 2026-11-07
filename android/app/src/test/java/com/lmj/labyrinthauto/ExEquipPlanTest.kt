package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.core.ExEquipPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** EX 装备还原计划（口径照 autopcr exequip.py） */
class ExEquipPlanTest {

    @Test
    fun 完全一致时不需要任何操作() {
        val st = mapOf(1001 to mapOf(0 to 11, 1 to 12))
        val p = ExEquipPlan.build(st, st, alive = setOf(11, 12))
        assertTrue(p.isEmpty)
        assertEquals(2, p.unchanged)
    }

    @Test
    fun 装备被别的角色占着_先卸后装() {
        // 快照：1001 拿 11；现在 2002 拿着 11（1001 自己是空槽）
        val target = mapOf(1001 to mapOf(0 to 11))
        val current = mapOf(
            1001 to mapOf(0 to 0),
            2002 to mapOf(0 to 11),
        )
        val p = ExEquipPlan.build(target, current, alive = setOf(11))
        assertEquals(listOf(ExEquipPlan.Change(2002, 0, 0)), p.remove)
        assertEquals(listOf(ExEquipPlan.Change(1001, 0, 11)), p.apply)
        assertTrue(p.missing.isEmpty())
    }

    @Test
    fun 快照里角色当前没有该槽_不处理() {
        // 快照有 1001/slot1，但当前完全没这个角色（角色池变了）
        val target = mapOf(1001 to mapOf(1 to 11))
        val current = mapOf(2002 to mapOf(0 to 5))
        val p = ExEquipPlan.build(target, current, alive = setOf(11, 5))
        assertTrue(p.isEmpty)
    }

    @Test
    fun 装备已不存在_记为missing且不装() {
        val target = mapOf(1001 to mapOf(0 to 99))
        val current = mapOf(1001 to mapOf(0 to 0))
        val p = ExEquipPlan.build(target, current, alive = setOf(11))
        assertTrue(p.isEmpty)
        assertEquals(listOf(99), p.missing)
    }

    @Test
    fun 卸下快照里为空但当前有货的槽() {
        val target = mapOf(1001 to mapOf(0 to 0))
        val current = mapOf(1001 to mapOf(0 to 7))
        val p = ExEquipPlan.build(target, current, alive = setOf(7))
        assertEquals(listOf(ExEquipPlan.Change(1001, 0, 0)), p.apply)
        assertTrue(p.remove.isEmpty())
    }

    @Test
    fun 记录之外的角色一律不碰() {
        val target = mapOf(1001 to mapOf(0 to 11))
        val current = mapOf(
            1001 to mapOf(0 to 11),
            2002 to mapOf(0 to 22, 1 to 33),
        )
        val p = ExEquipPlan.build(target, current, alive = setOf(11, 22, 33))
        assertTrue(p.isEmpty)   // 2002 的 22/33 不该被卸
    }

    @Test
    fun 同一件装备被两个槽同时抢_只卸一次() {
        // 快照里 1001/0 和 1001/1 都写 11（异常数据）⇒ apply 两次，remove 只来自占用者
        val target = mapOf(
            1001 to mapOf(0 to 11, 1 to 11),
        )
        val current = mapOf(
            1001 to mapOf(0 to 0, 1 to 0),
            2002 to mapOf(0 to 11),
        )
        val p = ExEquipPlan.build(target, current, alive = setOf(11))
        assertEquals(1, p.remove.size)
        assertEquals(2, p.apply.size)
    }
}
