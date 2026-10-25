package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.data.Tables
import com.lmj.labyrinthauto.logic.team.C
import com.lmj.labyrinthauto.logic.team.Profiles
import com.lmj.labyrinthauto.logic.team.Rules
import com.lmj.labyrinthauto.logic.team.TagIds
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * P4 对拍（**第①层**）：常量表 + 纯函数 + 关卡档 与 PC 版 `team_build.py` **逐项**比。
 *
 * 基准由 `tools/gen_team_fixtures.py` 跑 PC 版生成 → `tests/fixtures/team_probes.json`
 * （313 个角色的 tags/tier/eff_atk/dot_kinds + 25 关卡 × 5 图数的 profile + 常量表）。
 *
 * 这一层不需要设备：`./gradlew test`（用真的 org.json，见 app/build.gradle.kts 的注释）。
 * 全链路（500 条例子的 `plan()/team/reasons`，基准 `team_fixtures.json`）等第②层（`build/_algo_*`）
 * 移植完再开 —— 就是本文件最后那个 `@Ignore` 的测试。
 */
class TeamPortProbeTest {

    private val root: File = findRepoRoot()
    private val probes: JSONObject = JSONObject(File(root, "tests/fixtures/team_probes.json").readText())
    private val tables: Tables = Tables.load { name ->
        File(root, "android/app/src/main/assets/$name").readText()
    }

    @Test
    fun 常量表与PC版一致() {
        val c = probes.getJSONObject("consts")
        val bad = mutableListOf<String>()
        fun cmp(name: String, mine: Any?, theirs: Any?) {
            if (canon(mine) != canon(theirs)) bad += "$name\n    Kotlin=${canon(mine)}\n    PC    =${canon(theirs)}"
        }
        cmp("T", TagIds.T, c.getJSONObject("T"))
        cmp("DOT_KINDS", TagIds.DOT_KINDS, c.getJSONArray("DOT_KINDS"))
        cmp("FIX_TAGS", TagIds.FIX_TAGS, c.getJSONObject("FIX_TAGS"))
        cmp("T_DOT", TagIds.T_DOT, c.getString("T_DOT"))
        cmp("T_DOT_SUP", TagIds.T_DOT_SUP, c.getString("T_DOT_SUP"))
        cmp("C.SIZE", C.SIZE, c.getInt("SIZE"))
        cmp("C.SKILL_CUT", C.SKILL_CUT, c.getInt("SKILL_CUT"))
        cmp("C.ATTR_MIN", C.ATTR_MIN, c.getInt("ATTR_MIN"))
        cmp("C.ATTR_TOL", C.ATTR_TOL, c.getInt("ATTR_TOL"))
        cmp("C.ATTR_W", C.ATTR_W, c.getDouble("ATTR_W"))
        cmp("C.BOSSWEAK", C.BOSSWEAK, c.getDouble("BOSSWEAK"))
        cmp("C.TANK_EXTRA", C.TANK_EXTRA, c.getDouble("TANK_EXTRA"))
        cmp("C.RANGE_SPREAD_PENALTY", C.RANGE_SPREAD_PENALTY, c.getDouble("RANGE_SPREAD_PENALTY"))
        cmp("C.ATTR_FORCE", C.ATTR_FORCE, c.getBoolean("ATTR_FORCE"))
        cmp("C.W", C.W, c.getJSONObject("W"))
        assertTrue("常量表与 PC 版不一致：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun 纯函数与关卡档逐项一致() {
        val rules = Rules(tables)
        val bad = mutableListOf<String>()
        fun note(s: String) {
            if (bad.size < 25) bad += s
        }

        val units = probes.getJSONObject("units")
        var n = 0
        val it = units.keys()
        while (it.hasNext() && bad.size < 25) {
            val uid = it.next()
            n++
            val p = units.getJSONObject(uid)
            if (rules.tagsOf(uid).sorted() != strList(p.getJSONArray("tags"))) note("$uid.tags")
            val tiers = p.getJSONObject("tier")
            val tk = tiers.keys()
            while (tk.hasNext()) {
                val scen = tk.next()
                val raw = tiers.opt(scen)
                val exp = if (raw == null || raw === JSONObject.NULL) null else (raw as Number).toInt()
                if (rules.tierOf(uid, scen) != exp) note("$uid.tier[$scen] → Kotlin=${rules.tierOf(uid, scen)} PC=$exp")
            }
            val ea = if (p.optString("eff_atk") == "null") "" else p.optString("eff_atk")
            if (rules.effAtk(uid) != ea) note("$uid.eff_atk → Kotlin=${rules.effAtk(uid)} PC=$ea")
            if (rules.dotKindsOf(uid).sorted() != strList(p.getJSONArray("dot_kinds"))) note("$uid.dot_kinds")
        }
        assertTrue("角色探针不一致（查了 $n 个，前若干条）：\n" + bad.joinToString("\n"), bad.isEmpty())

        val profs = probes.getJSONObject("profiles")
        val pbad = mutableListOf<String>()
        val pk = profs.keys()
        while (pk.hasNext()) {
            val key = pk.next()
            val parts = key.split("|")
            val mine = Profiles.applyFloor(Profiles.profileOf(parts[0]), parts[1].toInt())
            if (canon(mine) != canon(profs.getJSONObject(key))) {
                if (pbad.size < 10) {
                    pbad += "$key\n    Kotlin=${canon(mine)}\n    PC    =${canon(profs.getJSONObject(key))}"
                }
            }
        }
        assertTrue("关卡档不一致（共 ${profs.length()} 组）：\n" + pbad.joinToString("\n"), pbad.isEmpty())
    }

    // 全链路（500 例）在 `TeamPortFullTest` 里；这里只管"常量 + 纯函数 + 关卡档"这一层。
}

// 公共小工具（canon/q/strList/findRepoRoot）在 TestCanon.kt 里，probe 与 full 两个测试共用。
