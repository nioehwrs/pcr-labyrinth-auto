package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.logic.team.TeamRunLog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组队记录的 **run_id**（2026-10-24 用户："给每次组队加上一个尽量保证全世界唯一的 id，
 * 这样以后合并的时候不会重复"）。
 *
 * 为什么单独钉死：
 * * id 是**跨设备合并**的唯一依据 —— 两台机子各自跑完，把 jsonl 粘到一起时靠它去重；
 * * 格式里前两段给人看（哪关/什么时候），第三段才是唯一性来源（48 bit 随机）；
 *   ⇒ "看起来一样、实际不同"这件事必须由测试保证，不能靠肉眼。
 *
 * 纯 JVM，不需要设备（[TeamRunLog.newRunId] 不碰任何 Android API）。
 */
class TeamRunLogTest {

    /** `<stage>|<yyyyMMdd-HHmmss>-<mmm>|<12 位 hex>` */
    private val pattern = Regex("""^Boss_\d+\|\d{8}-\d{6}-\d{3}\|[0-9a-f]{12}$""")

    @Test
    fun `格式是 关卡 加 时间 加 随机段`() {
        val id = TeamRunLog.newRunId("Boss_302501")
        assertTrue("格式不符：$id", pattern.matches(id))
        assertEquals(3, id.split("|").size)
        // 前两段必须能看出"哪个关、什么时候"（人复盘时要能读）
        assertEquals("Boss_302501", id.split("|")[0])
    }

    @Test
    fun `连续生成一万个不重号`() {
        val seen = HashSet<String>()
        repeat(10_000) {
            assertTrue("重复的 run_id：${it}", seen.add(TeamRunLog.newRunId("EX_770121001")))
        }
        assertEquals(10_000, seen.size)
    }

    @Test
    fun `同一关卡同一时间戳也不重号`() {
        val ids = (0 until 2_000).map { TeamRunLog.newRunId("normal") }
        assertEquals("同一关卡生成的 id 必须两两不同", ids.size, ids.toSet().size)
        // 前两段（关卡|时间戳）相同的那些，唯一性只能靠第三段随机段 ⇒ 组内必须也全不同
        val byHead = ids.groupBy { it.substringBeforeLast('|') }
        for ((head, group) in byHead) {
            val tails = group.map { it.substringAfterLast('|') }
            assertEquals("同一时间戳「$head」下出现了重复随机段", group.size, tails.toSet().size)
        }
    }

    @Test
    fun `空关卡名也有稳定前缀`() {
        val id = TeamRunLog.newRunId("")
        assertTrue("格式不符：$id", Regex("""^unknown\|\d{8}-\d{6}-\d{3}\|[0-9a-f]{12}$""").matches(id))
    }

    /** 照 [com.lmj.labyrinthauto.logic.party.PartyPlan] 的 `buildRunRecord` 形状造一条 */
    private fun sampleRec(): JSONObject = JSONObject()
        .put("stage", "Boss_302501").put("algo", "burst").put("relic_skill", 300).put("floor", 5)
        .put("relics", JSONArray().put(JSONObject().put("id", "31001").put("effected", 2).put("name", "某遗物")))
        .put("pool", JSONArray().put(JSONObject().put("id", "106101").put("name", "某角色")))
        .put("team", JSONArray().put(JSONObject().put("id", "106101").put("name", "某角色").put("tier", 4).put("why", "坦克")))
        .put("order", JSONArray().put("106101"))
        .put("attrs", JSONObject().put("暗", 5))
        .put("types", JSONObject().put("魔法", 4))
        .put("attr_bonus", 5000)
        .put("weights", JSONObject().put("burst", JSONObject().put("tier", 10.0)))
        .put("score", 384.5).put("atk", "魔法")
        .put("spread", JSONObject.NULL).put("floor_rule", JSONObject.NULL)
        .put("failed", false).put("chosen", JSONObject.NULL)
        .put("questId", "770110901").put("blockId", 12).put("area", 5)
        .put("party", JSONObject().put("now", JSONArray()).put("target", JSONArray().put("106101")).put("done", true))

    @Test
    fun `写盘那一行是单行 JSON 且字段与 PC log_run 对齐`() {
        val rec = sampleRec()
        val rid = TeamRunLog.newRunId("Boss_302501")
        val line = TeamRunLog.composeLine(rec, rid, "2026-10-24 12:00:00", JSONObject().put("ver", "1.0(1)"))
        assertTrue("一行一条 JSON：不能含换行", !line.contains('\n') && !line.contains('\r'))
        val o = JSONObject(line)
        // ★PC `team_build.log_run()` 写出的键 —— 少一个 PC 页面就少一块信息
        for (k in listOf(
            "type", "run_id", "ts", "stage", "algo", "relic_skill", "floor", "relics", "pool",
            "team", "order", "attrs", "types", "attr_bonus", "weights", "chosen",
            "spread", "floor_rule", "failed",
        )) {
            assertTrue("缺字段：$k", o.has(k))
        }
        // Android 额外字段（合并多台设备时用来分清来源）
        assertEquals("run", o.getString("type"))
        assertEquals(rid, o.getString("run_id"))
        assertEquals("android", o.getString("source"))
        assertEquals("1.0(1)", o.getString("app"))
        assertEquals("1.0(1)", o.getJSONObject("device").optString("ver"))   // 设备信息整份带过去
        assertEquals("2026-10-24 12:00:00", o.getString("ts"))
        assertEquals(5, o.getInt("floor"))
        assertEquals(1, o.getJSONArray("team").length())
        assertTrue("原对象不能被就地改写", !rec.has("type") && !rec.has("run_id"))
    }
}
