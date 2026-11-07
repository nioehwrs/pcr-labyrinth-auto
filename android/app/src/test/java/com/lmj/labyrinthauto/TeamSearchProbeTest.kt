package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.data.Tables
import com.lmj.labyrinthauto.logic.team.Rules
import com.lmj.labyrinthauto.logic.team.StructSlots
import com.lmj.labyrinthauto.logic.team.TagIds
import com.lmj.labyrinthauto.logic.team.TeamBuild
import com.lmj.labyrinthauto.logic.team.TeamTuning
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * 探针（不发版，只用来量化）：**逐个结构位**验证「先占坑 + 只看单角色评级 ⇒ 局部最优≠全局最优」。
 *
 * 换人方式：
 *  · 坦克 → 用 `forceTank`（精确）；
 *  · 其它位 `build` 没有对应参数 ⇒ 把该位的**其他候选从 pool 里摘掉**，只留它一个（近似强制）。
 *
 * 统计按 **结构位 × 关卡类型** 分组，回答"到底哪一位、在哪类关会选错"。
 */
class TeamSearchProbeTest {

    private val root = findRepoRoot()
    private val tables = Tables.load { File(root, "android/app/src/main/assets/$it").readText() }
    private val rules = Rules(tables)

    private class Stat {
        var n = 0
        var better = 0
        var sum = 0.0
        var max = 0.0
        fun add(d: Double) {
            better++
            sum += d
            if (d > max) max = d
        }
    }

    private fun grpOf(stage: String) = when {
        stage.startsWith("Boss_") -> "Boss"
        stage.startsWith("EX_") -> "EX"
        stage == "normal" -> "normal"
        else -> "其它"
    }

    @Test
    fun 探针_枚举后是否真的取到了更高的整队分() {
        val f = File(root, "logs/_abort3/logs/team_runs.jsonl")
        if (!f.exists()) {
            println("跳过：没找到 team_runs.jsonl")
            return
        }
        val tb = TeamBuild(tables)
        val seen = HashSet<String>()
        var n = 0
        var better = 0
        var sum = 0.0
        var max = 0.0
        var worse = 0
        val samples = mutableListOf<String>()
        val byGrp = HashMap<String, IntArray>()          // [样本, 更好]

        for (line in f.readLines()) {
            if (line.isBlank()) continue
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val stage = o.optString("stage", "normal")
            val floor = o.optInt("floor", 1)
            val relics = o.optInt("relic_skill", 0)
            val poolJson = o.optJSONArray("pool") ?: continue
            val pool = (0 until poolJson.length()).map { poolJson.getJSONObject(it).getString("id") }
            if (pool.isEmpty()) continue
            if (!seen.add("$stage|$floor|$relics|${pool.joinToString(",")}")) continue

            val a = runCatching { tb.build(stage, pool, relics, floor) }.getOrNull() ?: continue
            val b = runCatching { tb.buildBest(stage, pool, relics, floor) }.getOrNull() ?: continue
            val as0 = a.score ?: continue
            val bs = b.score ?: continue
            n++
            val g = grpOf(stage)
            val arr = byGrp.getOrPut(g) { IntArray(2) }
            arr[0]++
            val d = bs - as0
            if (d > 1e-6) {
                better++
                arr[1]++
                sum += d
                if (d > max) max = d
                if (samples.size < 10) {
                    samples += String.format(
                        Locale.ROOT, "%s/第%d层 build=%.1f → buildBest=%.1f（+%.1f）",
                        stage, floor, as0, bs, d,
                    )
                }
            } else if (d < -1e-6) {
                worse++
            }
        }
        println("== 探针：buildBest（枚举结构位）vs build（原贪心）==")
        println("样本 $n 条 ｜ 更好 $better ｜ 更差 $worse")
        println(
            "平均提升 " + String.format(Locale.ROOT, "%.2f", if (better > 0) sum / better else 0.0) +
                " ｜ 最大提升 " + String.format(Locale.ROOT, "%.1f", max),
        )
        for ((g, arr) in byGrp.toSortedMap()) {
            println("  $g：${arr[1]}/${arr[0]} 条变好")
        }
        samples.forEach { println("  $it") }
    }

    /** ★混编惩罚参数扫描：同一批队伍在不同 P 下的混编率/分数代价，输出 CSV 供画图 */
    @Test
    fun 探针_混编惩罚扫描() {
        val f = File(root, "logs/_abort3/logs/team_runs.jsonl")
        if (!f.exists()) {
            println("跳过：没找到 team_runs.jsonl")
            return
        }
        class Case(val stage: String, val floor: Int, val relics: Int, val pool: List<String>)
        val cases = mutableListOf<Case>()
        val seen = HashSet<String>()
        for (line in f.readLines()) {
            if (line.isBlank()) continue
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val stage = o.optString("stage", "normal")
            val floor = o.optInt("floor", 1)
            val relics = o.optInt("relic_skill", 0)
            val pj = o.optJSONArray("pool") ?: continue
            val pool = (0 until pj.length()).map { pj.getJSONObject(it).getString("id") }
            if (pool.isEmpty()) continue
            if (!seen.add("$stage|$floor|$relics|${pool.joinToString(",")}")) continue
            cases += Case(stage, floor, relics, pool)
            if (cases.size >= 260) break
        }
        println("扫描样本 ${cases.size} 条")

        val ps = listOf(0.0, 8.0, 12.0, 15.0, 20.0, 25.0)
        val csv = StringBuilder("penalty,n,mixed,avgScore,avgTier,offT5,offAll,bothRate\n")
        val tb = TeamBuild(tables)
        val detail = StringBuilder()
        var baseTeams = mutableMapOf<String, String>()

        for (p in ps) {
            TeamTuning.mixPenalty = p
            var n = 0
            var mixed = 0
            var sumScore = 0.0
            var sumTier = 0
            var offT5 = 0
            var offAll = 0
            var bothAll = 0
            var memberAll = 0
            for (c in cases) {
                val r = runCatching { tb.buildBest(c.stage, c.pool, c.relics, c.floor) }.getOrNull() ?: continue
                if (r.picked.isEmpty()) continue
                val score = r.score ?: continue
                n++
                sumScore += score
                val kinds = r.picked.map { u ->
                    val ts = rules.tagsOf(u)
                    if (TagIds.T["both"] in ts) "both" else rules.effAtk(u)
                }
                memberAll += kinds.size
                bothAll += kinds.count { it == "both" }
                val pn = kinds.count { it == "物理" }
                val mn = kinds.count { it == "魔法" }
                if (pn > 0 && mn > 0) mixed++
                val main = if (pn >= mn) "物理" else "魔法"
                r.picked.forEachIndexed { i, u ->
                    val t = rules.tierOf(u) ?: 0
                    sumTier += t
                    val k = kinds[i]
                    if (k != "both" && k.isNotEmpty() && k != main) {
                        offAll++
                        if (t >= 5) offT5++
                    }
                }
                val key = "${c.stage}|${c.floor}|${c.relics}"
                val desc = "${c.stage} 第${c.floor}层 score=${String.format(Locale.ROOT, "%.1f", score)} " +
                    r.picked.joinToString(",") { u ->
                        val nm = tables.unit(u)?.optString("name").orEmpty()
                        val k = kinds[r.picked.indexOf(u)]
                        "$nm(${rules.tierOf(u) ?: 0}${if (k == "both") "通用" else k})"
                    }
                if (p == 0.0) baseTeams[key] = desc
                if (p == 15.0 && baseTeams[key] != null && baseTeams[key] != desc) {
                    if (detail.lines().size < 12) {
                        detail.append(key).append('\n')
                        detail.append("  P=0  ：").append(baseTeams[key]).append('\n')
                        detail.append("  P=15 ：").append(desc).append('\n')
                    }
                }
            }
            csv.append(
                String.format(
                    Locale.ROOT, "%.1f,%d,%d,%.2f,%.2f,%d,%d,%.3f\n",
                    p, n, mixed,
                    if (n > 0) sumScore / n else 0.0,
                    if (n > 0) sumTier.toDouble() / n else 0.0,
                    offT5, offAll,
                    if (memberAll > 0) bothAll.toDouble() / memberAll else 0.0,
                ),
            )
            println("P=$p → 样本 $n 混编 $mixed 平均分 ${String.format(Locale.ROOT, "%.2f", if (n > 0) sumScore / n else 0.0)} 异系T5 $offT5")
        }
        TeamTuning.reset()
        File(root, "logs/_mix_scan.csv").writeText(csv.toString())
        File(root, "logs/_mix_detail.txt").writeText(detail.toString())
        println("已写 logs/_mix_scan.csv 与 logs/_mix_detail.txt")
    }

    /** ★验证 need_direct 新口径：EX_265000 第4层（用户 2026-11-5 指出"单体打不到 boss"） */
    @Test
    fun 复现_EX265000_定向关选人() {
        val f = File(root, "logs/team_runs_export.jsonl")
        if (!f.exists()) {
            println("跳过：没有 team_runs_export.jsonl")
            return
        }
        val line = f.readLines().firstOrNull {
            it.contains("\"EX_265000\"") && it.contains("2026-11-05 10:47:03")
        } ?: return
        val o = JSONObject(line)
        val pj = o.getJSONArray("pool")
        val pool = (0 until pj.length()).map { pj.getJSONObject(it).getString("id") }
        val tb = TeamBuild(tables)
        val r = tb.buildBest("EX_265000", pool, 0, 4)
        for (uid in listOf("125701", "180601", "134501", "133101", "106701", "118101")) {
            println(
                "  $uid ${tables.unit(uid)?.optString("name")} tier=${rules.tierOf(uid)} " +
                    "tags=${rules.tagsOf(uid)}",
            )
        }
        println("旧队 = 兰法/帆稀/克萝茜/莱拉耶尔/凯露（score ${o.optDouble("score", 0.0)}）")
        println("新队 = ${r.picked}  score=${r.score}")
        for (u in r.picked) {
            val ts = rules.tagsOf(u)
            val mark = listOfNotNull(
                if (TagIds.T["fixrelic"] in ts) "定向" else null,
                if (TagIds.T["aoe"] in ts) "群攻" else "非群攻",
                if (TagIds.T["dps"] in ts) "输出位" else null,
            ).joinToString("/")
            println("   $u ${tables.unit(u)?.optString("name")}  [$mark]")
        }
    }

    /** 复现实跑里「规则算不出目标（池 15 个）」的 EX_310707 第2层 */
    @Test
    fun 复现_EX310707_组队为空() {
        val pool = listOf(
            "106501", "104301", "102501", "100701", "180801", "130801", "130101", "127501",
            "124201", "110301", "107001", "106901", "106701", "132801", "111901",
        )
        val tb = TeamBuild(tables)
        for (failed in listOf(false, true)) {
            val rr = tb.buildBest("EX_310707", pool, 0, 2, 5, failed)
            println("[failed=$failed] picked=${rr.picked} score=${rr.score} warn=${rr.warn} fail=${rr.fail}")
        }
        val r0 = tb.build("EX_310707", pool, 0, 2, 5, allowAttrlim = true)
        println("[build allowAttrlim=true] picked=${r0.picked} team=${r0.team} warn=${r0.warn} fail=${r0.fail}")
        val r = tb.buildBest("EX_310707", pool, 0, 2)
        println("picked  = ${r.picked}")
        println("team    = ${r.team}")
        println("score   = ${r.score}")
        println("fail    = ${r.fail}")
        println("warn    = ${r.warn}")
        println("log尾巴 = ${r.log.takeLast(10)}")
    }

    /** ★复核本次实跑：用同一批 pool 重跑 buildBest，看混编率（判断 P=12 到底有没有生效） */
    @Test
    fun 探针_本次实跑的混编率与重跑对比() {
        val f = File(root, "logs/_new/team_runs.jsonl")
        if (!f.exists()) {
            println("跳过：没有 logs/_new/team_runs.jsonl")
            return
        }
        val tb = TeamBuild(tables)
        val seen = HashSet<String>()
        var n = 0
        var recMixed = 0
        var reMixed = 0
        var same = 0
        val byStage = HashMap<String, IntArray>()   // [重跑队数, 重跑混编]
        val all = f.readLines().filter { it.isNotBlank() }
        println("文件共 ${all.size} 条，只看最后 400 条（本次运行）")
        for (line in all.takeLast(400)) {
            if (line.isBlank()) continue
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val stage = o.optString("stage", "normal")
            val floor = o.optInt("floor", 1)
            val relics = o.optInt("relic_skill", 0)
            val pj = o.optJSONArray("pool") ?: continue
            val pool = (0 until pj.length()).map { pj.getJSONObject(it).getString("id") }
            if (pool.isEmpty()) continue
            if (!seen.add("$stage|$floor|$relics|${pool.joinToString(",")}")) continue
            if (n >= 260) break
            val r = runCatching {
                tb.buildBest(stage, pool, relics, floor, 5, o.optBoolean("failed", false))
            }.getOrNull() ?: continue
            val picked = r.picked.toList()
            if (picked.isEmpty()) continue
            n++
            val kinds = picked.map { u ->
                val ts = rules.tagsOf(u)
                if (TagIds.T["both"] in ts) "both" else rules.effAtk(u)
            }
            val p = kinds.count { it == "物理" }
            val m = kinds.count { it == "魔法" }
            val mixedNow = p > 0 && m > 0
            if (mixedNow) reMixed++
            // 记录里那支队伍（旧队伍）是否混编
            val tj = o.optJSONArray("team")
            if (tj != null) {
                val ty = o.optJSONObject("types")
                val p0 = ty?.optInt("物理", 0) ?: 0
                val m0 = ty?.optInt("魔法", 0) ?: 0
                if (p0 > 0 && m0 > 0) recMixed++
                val recIds = (0 until tj.length()).map { tj.getJSONObject(it).getString("id") }.toSet()
                if (recIds == picked.toSet()) same++
            }
            val arr = byStage.getOrPut(stage.substringBefore("_")) { IntArray(2) }
            arr[0]++
            if (mixedNow) arr[1]++
        }
        println("== 本次实跑 vs 重跑 ==")
        println("样本 $n 条 ｜ 重跑混编 ${reMixed}（${if (n > 0) reMixed * 100 / n else 0}%）" +
            " ｜ 记录里混编 ${recMixed}（${if (n > 0) recMixed * 100 / n else 0}%）｜ 队伍完全相同 $same")
        for ((k, v) in byStage.toSortedMap()) {
            println("  %-10s 队 %3d ｜ 重跑混编 %3d(%2d%%)".format(k, v[0], v[1], if (v[0] > 0) v[1] * 100 / v[0] else 0))
        }
    }

    /** 统计物法混编的实际比例（用户 2026-11-4：记录里混编很多） */
    @Test
    fun 探针_物法混编比例() {
        val f = File(root, "logs/_abort3/logs/team_runs.jsonl")
        if (!f.exists()) {
            println("跳过：没找到 team_runs.jsonl")
            return
        }
        val seen = HashSet<String>()
        var n = 0
        var pure = 0
        var mixedTeam = 0
        val offTier = HashMap<Int, Int>()      // 混编时"异系角色"的 tier 分布
        val sameTier = HashMap<Int, Int>()     // 混编时"同系角色"的 tier 分布
        val byGrp = HashMap<String, IntArray>() // [队伍数, 混编数]
        var bothCnt = 0

        for (line in f.readLines()) {
            if (line.isBlank()) continue
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val stage = o.optString("stage", "normal")
            val tj = o.optJSONArray("team") ?: continue
            if (tj.length() < 4) continue
            val ids = (0 until tj.length()).map { tj.getJSONObject(it).getString("id") }
            val key = "$stage|${ids.sorted().joinToString(",")}"
            if (!seen.add(key)) continue
            n++
            val kinds = ids.map { u ->
                val ts = rules.tagsOf(u)
                if (TagIds.T["both"] in ts) "both" else rules.effAtk(u)
            }
            bothCnt += kinds.count { it == "both" }
            val p = kinds.count { it == "物理" }
            val m = kinds.count { it == "魔法" }
            val g = grpOf(stage)
            val arr = byGrp.getOrPut(g) { IntArray(2) }
            arr[0]++
            if (p > 0 && m > 0) {
                mixedTeam++
                arr[1]++
                // 少数派 = 异系
                val main = if (p >= m) "物理" else "魔法"
                ids.forEachIndexed { i, u ->
                    val k = kinds[i]
                    if (k != main && k != "both") {
                        val t = rules.tierOf(u) ?: 0
                        offTier[t] = (offTier[t] ?: 0) + 1
                    } else if (k == main) {
                        val t = rules.tierOf(u) ?: 0
                        sameTier[t] = (sameTier[t] ?: 0) + 1
                    }
                }
            } else {
                pure++
            }
        }
        println("== 探针：物法混编 ==")
        println("去重队伍 $n 支 ｜ 纯系 $pure ｜ 混编 $mixedTeam（${if (n > 0) mixedTeam * 100 / n else 0}%）｜ both 角色 $bothCnt 人次")
        for ((g, arr) in byGrp.toSortedMap()) println("  $g：${arr[1]}/${arr[0]} 支混编")
        println("混编队伍里 —— 异系角色 tier 分布: " + offTier.toSortedMap().entries.joinToString(" ") { "T${it.key}×${it.value}" })
        println("混编队伍里 —— 同系角色 tier 分布: " + sameTier.toSortedMap().entries.joinToString(" ") { "T${it.key}×${it.value}" })
    }

    /** 对拍只看**队伍**（不看 log）—— 区分"日志文本变了"和"队伍真的换了" */
    @Test
    fun 探针对拍_只看队伍是否变化() {
        val fx = JSONObject(File(root, "tests/fixtures/team_fixtures.json").readText())
        val cases = fx.getJSONArray("cases")
        val tb = TeamBuild(tables)
        var same = 0
        var diff = 0
        var err = 0
        val samples = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val inp = c.getJSONObject("input")
            val exp = c.getJSONObject("expect")
            val stage = inp.getString("stage")
            val pj = inp.getJSONArray("pool")
            val pool = (0 until pj.length()).map { pj.get(it).toString() }
            val relics = inp.optInt("relic_skill", 0)
            val floor = inp.optInt("floor", 1)
            val r = runCatching { tb.buildBest(stage, pool, relics, floor) }.getOrNull()
            if (r == null) {
                err++
                continue
            }
            val ej = exp.getJSONArray("team")
            val want = (0 until ej.length()).map { ej.get(it).toString() }.toSet()
            if (r.picked.toSet() == want) {
                same++
            } else {
                diff++
                if (samples.size < 5) {
                    samples += "case#$i $stage 期望=${want.sorted()} 实得=${r.picked.sorted()}"
                }
            }
        }
        println("== 对拍：只看队伍 ==")
        println("500 条：队伍一致 $same ｜ 不同 $diff ｜ 异常 $err")
        samples.forEach { println("  $it") }
    }

    @Test
    fun 探针_逐个结构位枚举换人的收益() {
        val f = File(root, "logs/_abort3/logs/team_runs.jsonl")
        if (!f.exists()) {
            println("跳过：没找到 team_runs.jsonl")
            return
        }
        val tb = TeamBuild(tables)
        val tkTag = TagIds.T["tank"] ?: "u_tank"
        val mtTag = TagIds.T["mtank"] ?: "u_magetank"
        // 统计：[位][组] → Stat（分母是"这一位真的被选中了"的记录数）
        val stats = HashMap<String, HashMap<String, Stat>>()
        val seen = HashSet<String>()
        var used = 0

        for (line in f.readLines()) {
            if (line.isBlank()) continue
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val stage = o.optString("stage", "normal")
            val floor = o.optInt("floor", 1)
            val relics = o.optInt("relic_skill", 0)
            val poolJson = o.optJSONArray("pool") ?: continue
            val pool = (0 until poolJson.length()).map { poolJson.getJSONObject(it).getString("id") }
            if (pool.isEmpty()) continue
            if (!seen.add("$stage|$floor|$relics|${pool.joinToString(",")}")) continue
            if (used >= 220) break

            val base = runCatching { tb.build(stage, pool, relics, floor) }.getOrNull() ?: continue
            val bs = base.score ?: continue
            used++
            val grp = grpOf(stage)
            val picked = base.picked.toList()

            for (slot in StructSlots.ALL) {
                if (slot.key == "field") continue                  // 领域约束本来就被绕过，单独看
                val holders = picked.filter { slot.matches(rules.tagsOf(it)) }
                if (holders.isEmpty()) continue                    // 这一位这局没被选中 ⇒ 不算分母
                val cands = pool.filter { slot.matches(rules.tagsOf(it)) }
                if (cands.size <= 1) continue
                val st = stats.getOrPut(slot.key) { HashMap() }.getOrPut(grp) { Stat() }
                st.n++

                var best = bs
                if (slot.key == "tank") {
                    for (u in cands) {
                        val r = runCatching { tb.build(stage, pool, relics, floor, forceTank = u) }.getOrNull() ?: continue
                        val s = r.score ?: continue
                        if (s > best + 1e-6) best = s
                    }
                } else {
                    // 近似强制：把这一位的其他候选从 pool 摘掉，只留 c
                    for (c in cands.take(4)) {
                        val filtered = pool.filter { it == c || !slot.matches(rules.tagsOf(it)) }
                        if (filtered.size == pool.size) continue
                        val r = runCatching { tb.build(stage, filtered, relics, floor) }.getOrNull() ?: continue
                        val s = r.score ?: continue
                        if (s > best + 1e-6) best = s
                    }
                }
                if (best > bs + 1e-6) st.add(best - bs)
            }
        }

        println("== 探针：逐个结构位（去重后 $used 条有 score 的记录）==")
        println(
            String.format(
                Locale.ROOT, "%-10s %-8s %5s %6s %8s %8s", "结构位", "关卡", "选中", "可更好", "占比", "平均/最大",
            ),
        )
        for ((key, byGrp) in stats) {
            val cn = StructSlots.ALL.first { it.key == key }.cn
            for ((g, st) in byGrp.toSortedMap()) {
                println(
                    String.format(
                        Locale.ROOT, "%-10s %-8s %5d %6d %7d%%   +%.1f/+%.1f",
                        cn, g, st.n, st.better, if (st.n > 0) st.better * 100 / st.n else 0,
                        if (st.better > 0) st.sum / st.better else 0.0, st.max,
                    ),
                )
            }
        }
    }
}
