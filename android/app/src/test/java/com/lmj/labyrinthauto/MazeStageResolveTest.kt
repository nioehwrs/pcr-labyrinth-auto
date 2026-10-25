package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.logic.party.PartyPlan
import com.lmj.labyrinthauto.logic.team.Profiles
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 关卡档「检测路径」回归测试（2026-10-16 加）。
 *
 * 起因：`PartyPlan.loadExMap` 把 `stages.json` 的 `enemies`（**对象数组** `[{name,unitId}]`）当裸整数解析
 * ⇒ `exMap` 整表为空 ⇒ `stageFor` 的第二跳（questId → 敌人 unitId → `EX_<敌人unitId>`）永远失败
 * ⇒ **所有 EX 格都退成 normal（速通队）**，而日志里当时什么都看不出来（用户是靠目视"这队像速通队"发现的）。
 *
 * 这里把路径钉死：表能读出来 + 每场 EX 在它出现的**每个区**都能解析到档位。
 * 纯读 assets + 纯函数，不需要设备（同 `TeamPortProbeTest`，走 `./gradlew test`）。
 */
class MazeStageResolveTest {

    private val root: File = findRepoRoot()
    private fun read(name: String): String = File(root, "android/app/src/main/assets/$name").readText()

    /** stages.json 里所有 `EX_*` 档 → 它登记的 questId 列表（每个区一个 questId） */
    private fun exEntries(): List<Pair<String, List<String>>> {
        val d = JSONObject(read("stages.json"))
        val out = mutableListOf<Pair<String, List<String>>>()
        val it = d.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (!k.startsWith("EX_")) continue
            val qs = d.optJSONObject(k)?.optJSONArray("quests") ?: continue
            out += k to (0 until qs.length()).map { i -> qs.opt(i)?.toString().orEmpty() }
        }
        return out
    }

    @Test
    fun 关卡判定表能读出且每场EX在每个区都能解析到档位() {
        val exMap = PartyPlan.loadExMap(::read)

        // ① 表本身非空 —— 这条就是当天踩的坑（空表 ⇒ 所有 EX 格退速通，且日志无声）
        assertTrue(
            "loadExMap 读出来是空表：stages.json 的 enemies 结构又变了吗？（enemies 是对象数组 [{name,unitId}]）",
            exMap.isNotEmpty(),
        )

        // ② 按 loadTables 的同一算法算"声明的 EX 档 ∪ 镜像档"
        val declared = Profiles.PROFILES.keys.filter { it.startsWith("EX_") }.toSet()
        val mirrors = declared.mapNotNull { k -> exMap[k.removePrefix("EX_")]?.let { "EX_$it" } }.toSet()
        val have = declared + mirrors

        // ③ 每场 EX、它出现的**每一个区**都要能解析：questId 直接命中，或经敌人 unitId 命中
        val bad = mutableListOf<String>()
        val entries = exEntries()
        for ((k, qs) in entries) {
            for (q in qs) {
                val direct = "EX_$q" in have
                val via = exMap[q]?.let { "EX_$it" in have } ?: false
                if (!direct && !via) bad += "$k 的 questId $q 解析不到档位（direct=$direct via=$via）"
            }
        }
        assertTrue(
            "有 EX 格解析不到档位（共 ${entries.size} 场）—— 要么 loadExMap 解析坏了，" +
                "要么这个新 EX 遭遇还没登记档位（该补 Profiles）:\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )

        // ④ 两个"双敌"档按**怪物 id** 命中：区号只是"同一怪物在不同区的属性版本"，身份是怪物。
        //    （美空按数据只出现在 4/5 区，所以原来那两个 EX_770121301/EX_770121401 的区 1 写法永远命不中。）
        assertTrue("护盾·遗物+黄蜂女王（怪物 261001）没有档位", Profiles.hasProfile("EX_261001"))
        assertTrue("治愈·遗物+美空（怪物 265000）没有档位", Profiles.hasProfile("EX_265000"))
        assertEquals("770421401 应能查到敌人 265000", 265000, exMap["770421401"] ?: -1)
        assertEquals("770120201 应能查到敌人 310707", 310707, exMap["770120201"] ?: -1)
    }

    /**
     * Boss 关的「检测路径」：`panelType==8 / statusType==6` 判"是不是 Boss 格"（agent 每轮上报 panelType），
     * 再用 `questId → labyrinth_boss.json → unitId` 得到档位键 `Boss_<unitId>`（PC `_stage_for` 同款）。
     * 刷开局的 `bossByArea[3]/[5]` 走的是同一张 `bossMap`（`labMap` 的 `blockType==8` 面板 + questId），
     * 所以这里核对它，等于两条路一起核对。
     */
    @Test
    fun boss关能定位到哪个boss() {
        val bossMap = PartyPlan.loadBossMap(::read)
        // ① 表非空 —— 与 exMap 同一类解析错（值是对象 {hp,name,unitId}，不是裸整数）
        assertTrue(
            "loadBossMap 读出来是空表：labyrinth_boss.json 的值是对象 {hp,name,unitId}",
            bossMap.isNotEmpty(),
        )
        assertTrue("labyrinth_boss.json 只读出 ${bossMap.size} 条，像是解析残了", bossMap.size >= 200)

        // ② 与 stages.json 的 Boss_* 档交叉核对：那一档登记的每个 questId 都应指向同一个 boss unitId
        val stages = JSONObject(read("stages.json"))
        val bad = mutableListOf<String>()
        var n = 0
        val it = stages.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (!k.startsWith("Boss_")) continue
            val v = stages.optJSONObject(k) ?: continue
            val bid = v.optInt("boss")
            val qs = v.optJSONArray("quests") ?: continue
            n++
            for (i in 0 until qs.length()) {
                val q = qs.opt(i)?.toString() ?: continue
                if (bossMap[q] != bid) bad += "$k 的 questId $q → bossMap=${bossMap[q]}，应为 $bid"
            }
        }
        assertTrue("没有解析到任何 Boss 档", n > 0)
        assertTrue("Boss 档对不上（共 $n 档）：\n" + bad.joinToString("\n"), bad.isEmpty())

        // ③ 档位键 = Boss_<unitId>；stages.json 登记过的这 10 个 boss 都要有专属档
        for (uid in listOf(301206, 306604, 312505, 303306, 302501, 315004, 319401, 301701, 310103, 319604)) {
            assertTrue("Boss_$uid 没有专属档（stages.json 里登记过这个 boss）", Profiles.hasProfile("Boss_$uid"))
        }
    }
}
