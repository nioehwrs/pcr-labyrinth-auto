package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.data.Tables
import com.lmj.labyrinthauto.logic.team.TeamBuild
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 基线重生成工具：跑完即把 Android 端当前实现的 expect 覆盖回 tests/fixtures/team_fixtures.json。
 * fixtures 是 Android 回归快照（不再以 PC 版为基准）；组队算法改动后用它刷新受影响关卡。
 * 想全量重算就把 STAGES 改成 emptySet()。
 */
class TeamFixtureGenTest {

    private val STAGES = emptySet<String>()

    private fun objOfInt(m: Map<String, Int>?): Any {
        if (m == null) return JSONObject.NULL
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        return o
    }

    private fun objOfStr(m: Map<String, String>?): Any {
        if (m == null) return JSONObject.NULL
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        return o
    }

    @Test
    fun regen() {
        val root = findRepoRoot()
        val file = File(root, "tests/fixtures/team_fixtures.json")
        val fixtures = JSONObject(file.readText())
        val cases = fixtures.getJSONArray("cases")
        val tables = Tables.load { name -> File(root, "android/app/src/main/assets/$name").readText() }
        val tb = TeamBuild(tables)
        var touched = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val inp = c.getJSONObject("input")
            val stage = inp.getString("stage")
            if (STAGES.isNotEmpty() && stage !in STAGES) continue
            val pool = strList(inp.getJSONArray("pool"))
            val relics = inp.getInt("relic_skill")
            val floor = inp.getInt("floor")
            val size = if (inp.isNull("size")) null else inp.getInt("size")
            val failed = inp.getBoolean("failed")
            val res = tb.buildBest(stage, pool, relics, floor, size, failed)
            val exp = c.getJSONObject("expect")
            exp.put("score", res.score ?: JSONObject.NULL)
            exp.put("fail", res.fail ?: JSONObject.NULL)
            exp.put("warn", JSONArray(res.warn))
            exp.put("types", objOfInt(res.types))
            exp.put("reasons", objOfStr(res.reasons))
            exp.put("picked", JSONArray(res.picked))
            exp.put("log", JSONArray(res.log))
            exp.put("team", JSONArray(res.team ?: emptyList<String>()))
            exp.put("attr_bonus", res.attrBonusValue ?: JSONObject.NULL)
            exp.put("plan", JSONArray(res.team?.mapNotNull { it.toIntOrNull() } ?: emptyList<Int>()))
            exp.put("attrs", objOfInt(res.attrs))
            touched++
        }
        file.writeText(fixtures.toString())
        println("regen $touched cases")
    }
}