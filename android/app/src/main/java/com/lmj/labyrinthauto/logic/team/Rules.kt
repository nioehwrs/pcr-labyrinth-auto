package com.lmj.labyrinthauto.logic.team

import com.lmj.labyrinthauto.data.Tables

/**
 * 组队引擎的**纯函数层**（P4 第①层）：读表 + 标签/评级/系别/属性分。
 *
 * 逐条对应 PC 版 `team_build.py` 的同名函数：
 *   `tags_of`(217) / `tier_of`(221) / `eff_atk`(230) / `attr_bonus`(238) / `counts_attr`(244)
 *   / `dot_kinds_of`(252) / `attr_score`(256)
 *
 * ★这一层用 `tools/gen_team_fixtures.py` 生成的**探针**（`team_probes.json`）逐项对拍；
 *   算法层（`TeamBuild.build/_algo_*`）才用 500 条全链路用例。分层是为了"出问题知道是哪一层"。
 */
class Rules(private val tables: Tables) {

    /** PC 版 `tags_of`：该角色的标签集合（没有 = 空集，别填默认值） */
    fun tagsOf(uid: String): Set<String> = tables.tagSetOf(uid)

    /** PC 版 `tier_of`：场景评级（`tier_<scen>` 缺则回落 `tier`）；未评级 = null */
    fun tierOf(uid: String, scen: String = "all"): Int? = tables.tierOf(uid, scen)

    /** PC 版 `eff_atk`：有效伤害系别（带「类型反转」标签时物理↔魔法对调） */
    fun effAtk(uid: String): String {
        val a = tables.unit(uid)?.optString("atk_type").orEmpty()
        if (TagIds.T["rev"] in tagsOf(uid)) {
            return when (a) {
                "物理" -> "魔法"
                "魔法" -> "物理"
                else -> a
            }
        }
        return a
    }

    /** PC 版 `attr_bonus`：同属性人数 → 天赋加成（游戏量级 500/1000/2500/5000；6 人及以上不另加） */
    fun attrBonus(counts: Map<String, Int>): Int {
        val tbl = mapOf(1 to 0, 2 to 500, 3 to 1000, 4 to 2500, 5 to 5000)
        return counts.values.filter { it >= 2 }.sumOf { tbl[it] ?: 0 }
    }

    /** PC 版 `counts_attr`：属性 → 人数（缺属性记 "?"） */
    fun countsAttr(team: List<String>): Map<String, Int> {
        val c = LinkedHashMap<String, Int>()
        for (u in team) {
            val a = tables.unit(u)?.optString("attr")?.takeIf { it.isNotEmpty() } ?: "?"
            c[a] = (c[a] ?: 0) + 1
        }
        return c
    }

    /** PC 版 `dot_kinds_of`：该角色带的 dot 种类集合 */
    fun dotKindsOf(uid: String): Set<String> {
        val ts = tagsOf(uid)
        return TagIds.DOT_KINDS.filter { it in ts }.toSet()
    }

    /** PC 版 `attr_score`：队伍总用的同属性加分（折算到评分量级：/1000×ATTR_W） */
    fun attrScore(team: List<String>): Double =
        attrBonus(countsAttr(team)) * C.ATTR_W / 1000.0
}
