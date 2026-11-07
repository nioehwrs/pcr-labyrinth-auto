package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.data.Tables
import com.lmj.labyrinthauto.logic.team.TeamBuild
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P4 对拍（**第②层**）：500 条用例的**全链路**比 —— `plan/team/picked/reasons/types/attrs/attr_bonus/fail/log/warn`。
 *
 * 基准：`tests/fixtures/team_fixtures.json`。
 * ★**2026-11-4 起基准改为「Android 当前实现」的回归快照**（不再是 PC 版）：
 *   `variantBest` 已扩成「坦克×治疗×首动×加速、所有需要这些位的关卡」，这是**有意的改进**，
 *   与 PC 原行为必然不同（历史回放 467 条里 79 条变好、0 条变差）。
 *   所以本测试现在的语义是"改动前后有没有意外漂移"，不是"和 PC 一致"。
 *   要重新生成基准：临时写一个跑 `buildBest` 覆盖 expect 的测试（见 git 历史 TeamFixtureGenTest）。
 * 分类统计（这样移植期间也能看出进展，而不是"全红"）：
 *   ✅ 一致 ｜ ❌ 不一致（列出前几条差异）｜ ⏳ 尚未移植（`NotImplementedError`）｜ 💥 其他异常
 *
 * ★暂不比 `score`：它由 `build_best`（还没移植）填，等移植完再加进来。
 */
class TeamPortFullTest {

    private val root = findRepoRoot()
    private val fixtures = JSONObject(File(root, "tests/fixtures/team_fixtures.json").readText())
    private val tables = Tables.load { name -> File(root, "android/app/src/main/assets/$name").readText() }

    @Test
    fun 全链路组队与PC版逐项一致() {
        val cases = fixtures.getJSONArray("cases")
        var same = 0
        var nDiff = 0
        val mismatches = mutableListOf<String>()
        val unported = sortedMapOf<String, Int>()
        var errors = 0

        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val inp = c.getJSONObject("input")
            val exp = c.getJSONObject("expect")
            val stage = inp.getString("stage")
            val pool = strList(inp.getJSONArray("pool"))
            val relics = inp.getInt("relic_skill")
            val floor = inp.getInt("floor")
            val size = if (inp.isNull("size")) null else inp.getInt("size")
            val failed = inp.getBoolean("failed")

            val res = try {
                // ★基准是 PC 版的 build_best（不是 build）—— plan 也是从它出来的
                TeamBuild(tables).buildBest(stage, pool, relics, floor, size, failed)
            } catch (e: NotImplementedError) {
                val k = (e.message ?: "?").split("：").last().split(" ").first()
                unported[k] = (unported[k] ?: 0) + 1
                continue
            } catch (e: Throwable) {
                errors++
                if (mismatches.size < 8) mismatches.add("[异常] case#$i $stage floor=$floor pool=${pool.size} → ${e::class.simpleName}: ${e.message}")
                continue
            }

            val diffs = mutableListOf<String>()
            fun eq(name: String, mine: Any?, theirs: Any?) {
                if (canon(mine) != canon(theirs)) {
                    diffs.add("$name: Kotlin=${canon(mine).take(240)} ｜ PC=${canon(theirs).take(240)}")
                }
            }
            // 一律用 opt（有的用例里键可能是 null/缺失，比如"池子里没有可用角色"那条早退路径）
            eq("plan", res.team?.mapNotNull { it.toIntOrNull() } ?: emptyList<String>(), exp.opt("plan"))
            eq("team", res.team, exp.opt("team"))
            eq("picked", res.picked, exp.opt("picked"))
            eq("reasons", res.reasons, exp.opt("reasons"))
            eq("types", res.types, exp.opt("types"))
            eq("attrs", res.attrs, exp.opt("attrs"))
            eq("attr_bonus", res.attrBonusValue, exp.opt("attr_bonus"))
            eq("score", res.score, exp.opt("score"))
            eq("fail", res.fail, exp.opt("fail"))
            eq("log", res.log, exp.opt("log"))
            eq("warn", res.warn, exp.opt("warn"))

            if (diffs.isEmpty()) {
                same++
            } else {
                nDiff++                                   // 全量计数（展示只留前 8 条）
                if (mismatches.size < 8) {
                    mismatches.add(
                        "case#$i stage=$stage floor=$floor relics=$relics failed=$failed pool=${pool.size}\n      " +
                            diffs.joinToString("\n      "),
                    )
                }
            }
        }

        val msg = buildString {
            append("全链路对拍：一致 $same / ${cases.length()}")
            append(" ｜ 不一致 $nDiff 条（下面最多列 8 条）")
            if (unported.isNotEmpty()) append(" ｜ 尚未移植 ").append(unported.entries.joinToString("、") { "${it.key}×${it.value}" })
            if (errors > 0) append(" ｜ 异常 $errors")
            append("\n\n")
            append(mismatches.joinToString("\n"))
        }
        assertTrue(msg, same == cases.length())
    }
}
