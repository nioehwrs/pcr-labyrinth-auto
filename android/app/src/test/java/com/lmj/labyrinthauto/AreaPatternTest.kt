package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.net.labyrinth.LabyrinthMapNode
import com.lmj.labyrinthauto.reroll.AreaPattern
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 自定义判定模式（[AreaPattern]）：区域模板、EX 规则、5 区路线匹配 */
class AreaPatternTest {

    private fun loadMap(): List<LabyrinthMapNode> {
        val f = File(findRepoRoot(), "android/app/src/main/assets/labyrinth_state.json")
        val o = JSONObject(f.readText())
        val arr = o.getJSONArray("map")
        return (0 until arr.length()).map { i ->
            val n = arr.getJSONObject(i)
            val next = n.optJSONArray("next")
            LabyrinthMapNode(
                area = n.getInt("area"),
                column = n.getInt("column"),
                row = n.getInt("row"),
                blockId = n.getLong("blockId"),
                blockType = n.getInt("blockType"),
                questId = n.optInt("questId", 0).takeIf { it > 0 },
                nextBlockIds = (0 until (next?.length() ?: 0)).map { next!!.getLong(it) },
                isAreaLastPoint = false,
            )
        }
    }

    @Test
    fun `模板：区域列数分别是 6 7 7 8 8`() {
        assertEquals(6, AreaPattern.COL_POOL.getValue(1).size)
        assertEquals(7, AreaPattern.COL_POOL.getValue(2).size)
        assertEquals(7, AreaPattern.COL_POOL.getValue(3).size)
        assertEquals(8, AreaPattern.COL_POOL.getValue(4).size)
        assertEquals(8, AreaPattern.COL_POOL.getValue(5).size)
    }

    @Test
    fun `默认计划能在这局实测地图上找到 5 个区域的路线`() {
        val nodes = loadMap()
        val m = AreaPattern.match(nodes, AreaPattern.defaults())
        assertTrue("区域 ${m.missing} 没匹配上", m.ok)
        // 每条路线都从第 1 列走到最后一列
        val byId = nodes.associateBy { it.blockId }
        for ((area, path) in m.routes) {
            assertTrue("区域 $area 路线太短：$path", path.size >= 3)
            assertEquals(1, byId.getValue(path.first().toLong()).column)
            val lastCol = nodes.filter { it.area == area }.maxOf { it.column }
            assertEquals(lastCol, byId.getValue(path.last().toLong()).column)
        }
    }

    @Test
    fun `某一列配成该列不可能出现的类型就不合格`() {
        val nodes = loadMap()
        // 区域1 第 3 列只有 印记(4)/事件(5)，配成"商店"(7) 必然找不到
        val plans = AreaPattern.defaults().map {
            if (it.area == 1) it.copy(cols = it.cols.toMutableList().also { c -> c[2] = AreaPattern.T_SHOP }) else it
        }
        val m = AreaPattern.match(nodes, plans)
        assertFalse(m.ok)
        assertEquals(listOf(1), m.missing)
    }

    @Test
    fun `只有一种类型的列固定，普通战与EX战同池时固定EX`() {
        // 区域1 第 2 列只有普通战 ⇒ 固定
        assertTrue(AreaPattern.isFixed(1, 2))
        // 区域2 第 5 列有普通战(2)+EX(3) ⇒ 固定，且默认取 EX
        assertTrue(AreaPattern.isFixed(2, 5))
        assertEquals(AreaPattern.T_EX, AreaPattern.defaultChoice(AreaPattern.poolOf(2, 5)))
        // 区域1 第 3 列有 印记/事件 ⇒ 可切换
        assertFalse(AreaPattern.isFixed(1, 3))
        assertEquals(2, AreaPattern.poolOf(1, 3).size)
    }

    @Test
    fun `计划能 JSON 往返，缺列用默认补齐`() {
        val plans = AreaPattern.defaults().map {
            if (it.area == 3) it.copy(cols = it.cols.toMutableList().also { c -> c[2] = AreaPattern.T_RELIC }) else it
        }
        val back = AreaPattern.decode(AreaPattern.encode(plans))
        assertEquals(plans, back)
        // 空串 / 脏数据 ⇒ 默认
        assertEquals(AreaPattern.defaults(), AreaPattern.decode(""))
        assertEquals(AreaPattern.defaults(), AreaPattern.decode("不是 json"))
        // 只写了一列 ⇒ 其余列保持默认
        val partial = AreaPattern.decode("""{"1":[1,2,5]}""")
        assertEquals(AreaPattern.T_EVENT, partial.first { it.area == 1 }.cols[2])
        assertNotNull(partial.firstOrNull { it.area == 5 })
    }

    @Test
    fun `缺区域的地图直接不合格`() {
        val nodes = loadMap().filter { it.area != 4 }
        val m = AreaPattern.match(nodes, AreaPattern.defaults())
        assertFalse(m.ok)
        assertEquals(listOf(4), m.missing)
        assertNull(m.routes[4])
    }
}
