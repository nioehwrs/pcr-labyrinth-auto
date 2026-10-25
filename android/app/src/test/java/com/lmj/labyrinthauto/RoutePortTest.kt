package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.logic.Route
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P5 对拍（**节点价值 / 最优路线 / 节点选择**）。
 *
 * 基准：`tests/fixtures/route_fixtures.json`（`tools/gen_route_fixtures.py` 跑 PC 版生成）：
 *   · `score_cases`(66)：`block_score` 三口径（event_value 350/100/0）× 全类型 × ignore_event
 *   · `map_cases`(93)  ：31 张图（**含刷开局验过的那张真图**，78 节点五区）× 三口径 ——
 *                        `build_dp`/`nxt`/`optimal_path`/`area_theoretical`/`check_value_perfect`/`fmt_route`
 *                        + `refresh_route`（现算 DP 与"用记住的路线"两条路）
 *   · `pick_cases`(140)：`pick_node`（屏内/可前往/在 next 内/类型优先级/平局取 blockId 小者）
 *
 * ★真图那条还带 `stored_value`：`labyrinth_state.json` 里 **PC 当年验过的** 实际/理论分（18150/18150），
 *   等于拿"历史验证结论"再钉一次。
 */
class RoutePortTest {

    private val root = findRepoRoot()
    private val fx = JSONObject(File(root, "tests/fixtures/route_fixtures.json").readText())

    private fun jsonList(a: JSONArray?): List<JSONObject> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun intList(a: JSONArray?): List<Int> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { (a.opt(it) as? Number)?.toInt() }
    }

    @Test
    fun block分值与PC版一致() {
        val cases = fx.getJSONArray("score_cases")
        var same = 0
        val bad = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val inp = cases.getJSONObject(i).getJSONObject("input")
            val exp = cases.getJSONObject(i).opt("expect")
            Route.setEventValue(inp.getInt("event_value"))
            val bt = if (inp.isNull("block_type")) null else inp.getInt("block_type")
            val mine = Route.blockScore(bt, inp.getBoolean("ignore_event"))
            if (canon(mine) == canon(exp)) {
                same++
            } else if (bad.size < 6) {
                bad.add("case#$i bt=$bt ignore=${inp.getBoolean("ignore_event")} ev=${inp.getInt("event_value")}: Kotlin=$mine ｜ PC=$exp")
            }
        }
        Route.setEventValue(350)
        assertTrue("block_score 对拍：一致 $same / ${cases.length()}\n" + bad.joinToString("\n"), same == cases.length())
    }

    @Test
    fun 路线DP与PC版逐项一致() {
        val cases = fx.getJSONArray("map_cases")
        assertTrue("基准条数太少（${cases.length()}），八成没重新生成", cases.length() >= 60)
        var same = 0
        val bad = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val inp = cases.getJSONObject(i).getJSONObject("input")
            val exp = cases.getJSONObject(i).getJSONObject("expect")
            val name = inp.optString("name")
            val panels = jsonList(inp.getJSONArray("panels"))
            Route.setEventValue(inp.getInt("event_value"))
            val diffs = mutableListOf<String>()
            fun eq(n: String, mine: Any?, theirs: Any?) {
                if (canon(mine) != canon(theirs)) {
                    diffs.add("$n: Kotlin=${canon(mine).take(220)} ｜ PC=${canon(theirs).take(220)}")
                }
            }

            val byArea = exp.getJSONObject("by_area")
            for (areaKey in byArea.keys()) {
                val area = areaKey.toInt()
                val e = byArea.getJSONObject(areaKey)
                val blk = panels.filter { (it.opt("area") as? Number)?.toInt() == area }
                val (dp, nxt) = Route.buildDp(blk, false)
                eq("a$area.dp", dp.mapKeys { it.key.toString() }, e.opt("dp"))
                eq("a$area.nxt", nxt.mapKeys { it.key.toString() }, e.opt("nxt"))
                val opt = Route.optimalPath(blk, false)
                eq("a$area.optimal", mapOf("score" to opt.score, "path" to opt.path), e.opt("optimal"))
                eq("a$area.theoretical", Route.areaTheoretical(blk, false), e.opt("theoretical"))
                eq("a$area.fmt", Route.fmtRoute(blk, opt.path), e.opt("fmt"))
            }

            val vc = Route.checkValuePerfect(panels, false)
            eq(
                "check",
                mapOf(
                    "ok" to vc.ok, "actual" to vc.actual, "theory" to vc.theory,
                    "details" to vc.details.map {
                        mapOf("area" to it.area, "actual" to it.actual, "theory" to it.theory, "path" to it.path)
                    },
                ),
                exp.opt("check"),
            )
            // ★真图：和 labyrinth_state.json 里 PC 当年验证过的分再对一次
            exp.opt("stored_value")?.let { sv ->
                if (sv is JSONObject) {
                    eq("stored.actual", vc.actual, sv.opt("actual"))
                    eq("stored.theory", vc.theory, sv.opt("theory"))
                }
            }

            // refresh_route：① 现算 DP（换图前清缓存，免得命中上一张图的路线）
            Route.reset()
            runBlocking {
                val now = exp.optJSONArray("refresh_now")
                if (now != null) {
                    for (j in 0 until now.length()) {
                        val c = now.getJSONObject(j)
                        val p = Route.refreshRoute(c.getInt("area"), c.getInt("cur"), force = true, panels = panels)
                        eq("refresh_now[${c.optInt("area")}/${c.optString("tag")}]", p, c.opt("path"))
                    }
                }
                // ② 用"刷开局记住的路线"（PC 会优先用它；两条路同一口径）
                val savedArr = inp.optJSONArray("saved_route")
                val saved = if (savedArr == null) null else (0 until savedArr.length()).map { savedArr.getInt(it) }
                val sv = exp.optJSONArray("refresh_saved")
                if (sv != null && saved != null) {
                    for (j in 0 until sv.length()) {
                        val c = sv.getJSONObject(j)
                        val area = c.getInt("area")
                        // savedRoute 是"**该区**的整条路线"（PC: stt["route"][area]）→ 先按区过滤
                        val areaIds = panels.filter { (it.opt("area") as? Number)?.toInt() == area }
                            .mapNotNull { (it.opt("blockId") as? Number)?.toInt() }
                        val sub = saved.filter { it in areaIds }
                        val p = Route.refreshRoute(
                            area, c.getInt("cur"), force = true, panels = panels, savedRoute = sub,
                        )
                        eq("refresh_saved[$area/${c.optString("tag")}]", p, c.opt("path"))
                    }
                    Route.reset()      // 换图前清缓存，免得命中上一张图的路线
                }
            }
            Route.setEventValue(350)
            if (diffs.isEmpty()) same++ else if (bad.size < 5) {
                bad.add("case#$i「$name」ev=${inp.getInt("event_value")}\n      " + diffs.joinToString("\n      "))
            }
        }
        assertTrue("路线 DP 对拍：一致 $same / ${cases.length()}\n\n" + bad.joinToString("\n"), same == cases.length())
    }

    @Test
    fun 选项节点与PC版一致() {
        val cases = fx.getJSONArray("pick_cases")
        assertTrue("基准条数太少（${cases.length()}），八成没重新生成", cases.length() >= 100)
        var same = 0
        val bad = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val inp = cases.getJSONObject(i).getJSONObject("input")
            val exp = cases.getJSONObject(i).getJSONObject("expect")
            val nodes = jsonList(inp.getJSONArray("nodes"))
            val nxt = intList(inp.optJSONArray("next"))
            val t = Route.pickNode(nodes, nxt, inp.getInt("screen_w"), inp.getInt("screen_h"))
            val mine = mapOf(
                "blockId" to (t?.opt("blockId") as? Number)?.toInt(),
                "blockType" to (t?.opt("blockType") as? Number)?.toInt(),
                "panelState" to (t?.opt("panelState") as? Number)?.toInt(),
                "x" to (t?.opt("x") as? Number)?.toInt(),
                "y" to (t?.opt("y") as? Number)?.toInt(),
            )
            if (canon(mine) == canon(exp)) {
                same++
            } else if (bad.size < 6) {
                bad.add("case#$i next=$nxt nodes=${nodes.size}: Kotlin=${canon(mine)} ｜ PC=${canon(exp)}")
            }
        }
        assertTrue("pick_node 对拍：一致 $same / ${cases.length()}\n" + bad.joinToString("\n"), same == cases.length())
    }
}
