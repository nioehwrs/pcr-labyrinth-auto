package com.lmj.labyrinthauto.logic.relic

import com.lmj.labyrinthauto.data.RelicTables
import com.lmj.labyrinthauto.logic.Py
import org.json.JSONObject

/**
 * 遗物选择决策层 —— 逐字对应 PC 版 `pcr_auto.py`（2026-10-14 那一版，`md5=…` 记在 `PLAN.md §8`）：
 *
 * | 这里 | PC 版 |
 * |---|---|
 * | [MARK_NAMES] / [MARK_ORDER] | `MARK_NAMES`(349) / `MARK_ORDER`(351) |
 * | [dotKindsOfInfo] / [dotRelicKinds] | `dot_kinds_of_info`(3877) / `dot_relic_kinds`(4096) |
 * | [countDotRelics] / [ownRelicStars] | `count_dot_relics`(3888) / `own_relic_stars`(3899) |
 * | [relicCtx] | `relic_ctx`(3927) |
 * | [relicSubOrder] / [rankSameStar] | `relic_sub_order`(3945) / `relic_sub_rank`(3975) |
 * | [relicPickNote] / [relicCardsDesc] | `relic_pick_note`(4017) / `relic_cards_desc`(4052) |
 * | [sortChoices] / [pickCard] | `pick_card`(5559) |
 * | [sortSameStar] | **无 PC 对应**：三选一与商店共用的"同级遗物序"（2026-10-22 抽出来的） |
 *
 * ★这里**不碰设备也不读文件**：PC 版 `relic_ctx` 要读 `labyrinth_state.json` 才知道
 * "3 区 Boss 是不是毒液沙罗曼蛇"，在 app 里那个状态本来就在内存里 → 由调用方传进来。
 *
 * 规则细节（为什么这么排）见 PC 版 `pick_policy.md` 第六章。
 */
class RelicPick(private val tables: RelicTables) {

    companion object {
        /** PC 版 `MARK_NAMES`(349)：大类 id → 中文名 */
        val MARK_NAMES: Map<Int, String> =
            mapOf(1 to "加速", 2 to "削弱", 3 to "暴击", 4 to "强化", 5 to "守备")

        /** PC 版 `MARK_ORDER`(351)：大类优先级（**越小越优先**）—— 暴击 > 削弱 > 加速 > 守备 > 强化 */
        val MARK_ORDER: List<Int> = listOf(3, 2, 1, 5, 4)

        /** PC 版 `DOT_RELIC_ID_RE`(456)：x2003=毒 x2002=诅咒（1/2/3★ 各一件，共 6 件） */
        private val DOT_RELIC_ID_RE = Regex("^[123]200[23]$")

        /** PC 版 `REL_DOT_MAX`(3867)：已持有 **超过** 这么多件 dot 遗物 → dot 在削弱系列里降级 */
        const val REL_DOT_MAX = 2

        /** PC 版 `REL_STEAL_NEED`(3869)：暴击·吸血持有**总星数** < 此值 → 提到"削弱大类"之上 */
        const val REL_STEAL_NEED = 20

        /** PC 版 `REL_DEF_STAR_MIN`(3870)：守备系列持有总星数 < 此值 → 可能被提升 */
        const val REL_DEF_STAR_MIN = 4

        /** PC 版 `BOSS_VENOM_SALAMANDER`(3871)：毒液沙罗曼蛇（3 区 Boss），特殊遗物规则要用它认 */
        const val BOSS_VENOM_SALAMANDER = 306604
    }

    /** 遗物决策上下文（PC 版 `relic_ctx` 的返回值）。 */
    class Ctx(
        val ownDot: Int,
        val nRelics: Int,
        val star: Map<String, Int>,
        val area: Int?,
        val special: Boolean,
    ) {
        companion object {
            /** PC 版兼容的旧口径：context 直接给个整数 own_dot（`pick_card(cards, ctx=0)` 的默认） */
            fun legacy(ownDot: Int): Ctx = Ctx(ownDot, 0, emptyMap(), null, false)
        }
    }

    // ────────────────────────── dot / 星数统计 ──────────────────────────

    /** PC 版 `dot_kinds_of_info`(3877)：遗物信息 → dot 种类（毒/诅咒）；吃 (info, id) 两个参数 */
    fun dotKindsOfInfo(info: JSONObject?, rid: Any?): List<String> {
        val e = info?.optString("effect").orEmpty()
        val ks = listOf("中毒", "诅咒").filter { it in e }
        if (ks.isNotEmpty()) return ks
        val s = rid?.toString() ?: "None"
        return if (DOT_RELIC_ID_RE.matches(s)) listOf(if (s.endsWith("3")) "中毒" else "诅咒") else emptyList()
    }

    /** PC 版 `dot_relic_kinds`(4096)：遗物是不是 dot 类 → 种类名列表（可能为空） */
    fun dotRelicKinds(relicId: Any?): List<String> = dotKindsOfInfo(tables.rk(relicId), relicId)

    /**
     * PC 版 `count_dot_relics`(3888)：本局已持有 dot 遗物**件数**（毒/诅咒）。
     * ★`labOwnRelics` 里那个字段是 `EffectedCount`（效果生效次数）不是件数 ⇒ 按**唯一 id** 数。
     */
    fun countDotRelics(own: JSONObject?): Int {
        val arr = own?.optJSONArray("relics") ?: return 0
        val ids = HashSet<String>()
        for (i in 0 until arr.length()) {
            val rid = arr.optJSONObject(i)?.opt("relicId") ?: continue
            if (dotRelicKinds(rid).isNotEmpty()) ids.add(rid.toString())
        }
        return ids.size
    }

    /**
     * PC 版 `own_relic_stars`(3899)：持有遗物按"系列/小类"求**总星数**（星数 = rarity × 件数）。
     * ★同一遗物只算一次（那个字段是 EffectedCount，不是件数）。
     */
    fun ownRelicStars(own: JSONObject?): Map<String, Int> {
        val out = linkedMapOf(
            "mark1" to 0, "mark2" to 0, "mark3" to 0, "mark4" to 0, "mark5" to 0,
            "steal" to 0, "regen" to 0, "dot" to 0, "n" to 0,
        )
        val arr = own?.optJSONArray("relics") ?: return out
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val rid = arr.optJSONObject(i)?.opt("relicId") ?: continue
            if (!seen.add(rid.toString())) continue
            val info = tables.rk(rid) ?: JSONObject()
            val rar = Py.num(info, "rarity")?.toInt() ?: 0
            val mk = Py.num(info, "mark")?.toInt()
            if (mk != null && mk in 1..5) out["mark$mk"] = out["mark$mk"]!! + rar
            val eff = info.optString("effect")
            if (mk == 3 && "生命值吸收" in eff) out["steal"] = out["steal"]!! + rar
            if (mk == 5 && "持续回复生命值" in eff) out["regen"] = out["regen"]!! + rar
            if (dotRelicKinds(rid).isNotEmpty()) out["dot"] = out["dot"]!! + rar
            out["n"] = out["n"]!! + 1
        }
        return out
    }

    /**
     * PC 版 `relic_ctx`(3927)：遗物决策上下文（每组候选算一次）。
     *
     * @param area 当前区（PC 版从 `st["area"]` 取）
     * @param boss3UnitId 本局 **3 区 Boss** 的 unitId（PC 版从 `labyrinth_state.json` 取）
     */
    fun relicCtx(own: JSONObject?, area: Int?, boss3UnitId: Int?): Ctx = Ctx(
        ownDot = countDotRelics(own),
        nRelics = own?.optJSONArray("relics")?.length() ?: 0,
        star = ownRelicStars(own),
        area = area,
        // ★特殊条件：当前区 ≤ 3 **且** 本局 3 区 Boss＝毒液沙罗曼蛇（用户 2026-10-13）
        special = area != null && area <= 3 && boss3UnitId == BOSS_VENOM_SALAMANDER,
    )

    // ────────────────────────── 排序 / 理由 ──────────────────────────

    /** PC 版 `relic_sub_order`(3945)：同星级内的**小类**序（越小越优先） */
    fun relicSubOrder(info: JSONObject?, eff: String): Int {
        val mk = Py.num(info, "mark")?.toInt()
        if (mk == 3) {          // 暴击：吸血0 > 敌人受到暴伤增加1 > 暴伤增加2 > 暴击增加3
            if ("生命值吸收" in eff) return 0
            if ("敌人受到" in eff && "暴击" in eff) return 1
            if ("暴击伤害" in eff) return 2
            if ("暴击＋" in eff || "暴击+" in eff) return 3
            return 9
        }
        if (mk == 5) {          // 守备：持续回复0 > 生命上限1 > 魔法盾2 > 物理盾3 > 回复增强4
            if ("持续回复生命值" in eff) return 0
            if ("生命值＋" in eff || "生命值+" in eff) return 1
            if ("魔法攻击无效化屏障" in eff) return 2
            if ("物理攻击无效化屏障" in eff) return 3
            if ("回复强化" in eff) return 4
            return 9
        }
        return 0
    }

    /**
     * PC 版 `relic_sub_rank`(3975)：同星级内的排序 → (大类序, 小类序)，**越小越优先**。
     *
     * · dot（削弱·毒/诅咒）：本局一件都没有 → -2.0（最优先）；已持有 > [REL_DOT_MAX] 件 → 削弱内部垫底
     * · 暴击·吸血：持有总星数 < [REL_STEAL_NEED] → -1.5（提到削弱大类之上）；≥ 20 → 只维持暴击内部最高
     * · 守备：总星数 < [REL_DEF_STAR_MIN] 且（暴击≥4 或 削弱≥4 或 特殊条件）→ -0.5
     * · 特殊条件下守备<4 无条件提升；且"持续回复生命"为 0 时排 -1.0（仅次于上面两条）
     * · 暴击·普通攻击强化：仍落在"加速"大类之下（index(1) + 0.5）
     */
    fun rankSameStar(info: JSONObject?, rid: Any?, ctx: Ctx): Pair<Double, Int> {
        val star = ctx.star
        val ownDot = ctx.ownDot
        val special = ctx.special
        val mk = Py.num(info, "mark")?.toInt()
        val eff = info?.optString("effect").orEmpty()
        val sub = relicSubOrder(info, eff)
        if (mk == 2 && dotKindsOfInfo(info, rid).isNotEmpty()) {
            if (ownDot == 0) return -2.0 to sub                       // 一件都没有 → 同星级最优先
            if (ownDot > REL_DOT_MAX) return MARK_ORDER.indexOf(2).toDouble() to 1   // 削弱内部垫底
            return MARK_ORDER.indexOf(2).toDouble() to sub
        }
        if (mk == 3 && "生命值吸收" in eff) {
            if ((star["steal"] ?: 0) < REL_STEAL_NEED) return -1.5 to sub
            return MARK_ORDER.indexOf(3).toDouble() to sub             // ≥20 → 只维持暴击内部最高
        }
        if (mk == 5) {
            if (special && "持续回复生命值" in eff && (star["regen"] ?: 0) == 0) return -1.0 to sub
            if ((star["mark5"] ?: 0) < REL_DEF_STAR_MIN &&
                (special || (star["mark3"] ?: 0) >= REL_DEF_STAR_MIN || (star["mark2"] ?: 0) >= REL_DEF_STAR_MIN)
            ) {
                return -0.5 to sub                                     // 高于暴击/削弱
            }
            return MARK_ORDER.indexOf(5).toDouble() to sub
        }
        if (mk == 3 && "普通攻击强化" in eff) return (MARK_ORDER.indexOf(1) + 0.5) to sub  // 老规则：低于加速
        return (if (mk != null && mk in MARK_ORDER) MARK_ORDER.indexOf(mk).toDouble() else 99.0) to sub
    }

    /** PC 版 `relic_pick_note`(4017)：选中遗物的一句话理由（打日志用） */
    fun relicPickNote(c: JSONObject, ctx: Ctx): String {
        val star = ctx.star
        val ownDot = ctx.ownDot
        val rid = c.opt("rewardId")
        val v = tables.rk(rid) ?: JSONObject()
        val eff = v.optString("effect")
        val mk = Py.num(v, "mark")?.toInt()
        val ks = dotRelicKinds(rid)
        val ksTxt = ks.joinToString("、")
        if (ks.isNotEmpty() && mk == 2) {
            if (ownDot == 0) return "（dot·$ksTxt：本局还没有 dot → 同星级最优先）"
            if (ownDot > REL_DOT_MAX) {
                return "（dot·$ksTxt：已持有 $ownDot 件（>$REL_DOT_MAX）→ 削弱内部垫底）"
            }
            return "（dot·$ksTxt：已持有 $ownDot 件 → 削弱内部优先）"
        }
        if (mk == 3 && "生命值吸收" in eff) {
            val st = star["steal"] ?: 0
            return if (st < REL_STEAL_NEED) {
                "（暴击·吸血：持有总星数 $st < $REL_STEAL_NEED → 高于削弱）"
            } else {
                "（暴击·吸血：持有总星数 $st ≥ $REL_STEAL_NEED → 只维持暴击内部最高）"
            }
        }
        if (mk == 5) {
            if (ctx.special && "持续回复生命值" in eff && (star["regen"] ?: 0) == 0) {
                return "（守备·持续回复：本局还没有，且 3 区是毒液沙罗曼蛇 → 同星级最优先）"
            }
            if ((star["mark5"] ?: 0) < REL_DEF_STAR_MIN &&
                (ctx.special || (star["mark3"] ?: 0) >= REL_DEF_STAR_MIN || (star["mark2"] ?: 0) >= REL_DEF_STAR_MIN)
            ) {
                return "（守备：持有总星数 ${star["mark5"] ?: 0} < $REL_DEF_STAR_MIN → 高于暴击/削弱）"
            }
            return "（守备内部：持续回复 > 生命上限 > 魔法盾 > 物理盾 > 回复增强）"
        }
        if (mk == 3 && "普通攻击强化" in eff) return "（暴击·普通攻击强化：优先级低于加速）"
        return ""
    }

    /** PC 版 `relic_cards_desc`(4052)：把候选卡整理成可统计的结构（写选择日志用） */
    fun relicCardsDesc(cards: List<JSONObject>, ctx: Ctx): List<JSONObject> {
        val out = ArrayList<JSONObject>(cards.size)
        for ((n, c) in cards.withIndex()) {
            val rid = c.opt("rewardId")
            val v = tables.rk(rid) ?: JSONObject()
            val (ser, sub) = rankSameStar(v, rid, ctx)
            out.add(
                JSONObject()
                    .put("card", n + 1)
                    .put("rewardId", rid ?: JSONObject.NULL)
                    .put("name", v.opt("name") ?: JSONObject.NULL)
                    .put("rarity", v.opt("rarity") ?: JSONObject.NULL)
                    .put("mark", v.opt("mark") ?: JSONObject.NULL)
                    .put("mark_name", MARK_NAMES[Py.num(v, "mark")?.toInt()] ?: "?")
                    .put("effect", v.optString("effect").replace("\n", " "))
                    .put("dot", org.json.JSONArray(dotRelicKinds(rid)))
                    .put("series_rank", ser)
                    .put("sub_rank", sub),
            )
        }
        return out
    }

    // ────────────────────────── 选择 ──────────────────────────

    /** ★这张候选卡的 id 我们**认得**吗（遗物表里有）—— 给"读数异常就不猜"的闸门用
     *  （2026-10-17：实测候选 id 给过 `[1179648, 2084821607, …]` 这种垃圾值 ⇒ 三张全查不到 ⇒
     *   [pickCard] 的兜底"等价于取第一张"= **盲选**，用户当场看出"退避第一件了"）。 */
    fun isKnown(c: JSONObject): Boolean = tables.rk(c.opt("rewardId")) != null

    /**
     * ★给人看的一行（2026-10-17 用户：悬浮窗要"购买 2★ xxx（遗物名字）"）：`2★ 毒液沙罗曼蛇`。
     * 查不到名字（表里没这个 rewardId，比如印记/道具）⇒ 空串，调用方自己退化成别的描述。
     */
    fun relicLabel(rid: Any?): String {
        val info = tables.rk(rid) ?: return ""
        val name = info.optString("name").ifBlank { return "" }
        val rarity = info.optInt("rarity")
        return (if (rarity > 0) "${rarity}★ " else "") + name
    }

    /**
     * ★★**同级遗物排序（唯一入口）**（2026-10-22 用户要求抽出来）：
     * 把 [items] 按 [rankSameStar] 升序（越小越优先）稳定排序。
     *
     * · `ctx != null` → 用完整规则（含本局持有状态：无 dot 最优先 / 吸血清零 / 守备阈值）；
     * · `ctx == null` → 只用静态系列/小类序（无本局状态；给 PC 对拍）；
     * · `tieBreak` 是同键兜底：三选一传 `choiceNum`，商店传价格。
     *
     * 三选一：[sortChoices]（星级仍要比较）；商店：`ShopPlan.sortShopBucket`（星级已分桶，不比较）。
     */
    fun sortSameStar(
        items: List<JSONObject>,
        ctx: Ctx?,
        tieBreak: (JSONObject) -> Double = { 0.0 },
    ): List<JSONObject> = items.sortedWith(Comparator { a, b ->
        val (ba, sa) = rankKey(a, ctx)
        val (bb, sb) = rankKey(b, ctx)
        var c = ba.compareTo(bb)
        if (c == 0) c = sa.compareTo(sb)
        if (c == 0) c = tieBreak(a).compareTo(tieBreak(b))
        c
    })

    /**
     * ★同级遗物排序的**键**（唯一实现，三选一/商店都从这里取）：`(大类序, 小类序)`，越小越优先。
     * `ctx == null` → 只用静态系列/小类序（无本局持有状态；PC 对拍/未接入时）。
     */
    fun rankKey(item: JSONObject, ctx: Ctx?): Pair<Double, Double> {
        val rid = item.opt("rewardId")
        val info = tables.rk(rid) ?: return 99.0 to 99.0
        val (big, sub) = if (ctx != null) rankSameStar(info, rid, ctx) else staticRank(info)
        return big to sub.toDouble()
    }

    /** 静态（无本局持有状态）的同级序：系列序 + 小类序，与 [rankSameStar] 的非动态分支一致 */
    private fun staticRank(info: JSONObject?): Pair<Double, Int> {
        val mk = Py.num(info, "mark")?.toInt() ?: 0
        val eff = info?.optString("effect").orEmpty()
        val sub = relicSubOrder(info, eff)
        // 暴击·普通攻击强化：仍落在"加速"大类之下（index(1) + 0.5）
        if (mk == 3 && "普通攻击强化" in eff) return (MARK_ORDER.indexOf(1) + 0.5) to sub
        val i = MARK_ORDER.indexOf(mk)
        return (if (i >= 0) i.toDouble() else 99.0) to sub
    }

    /**
     * ★**三选一排序**（2026-10-22 抽出）：**星级降序 → [rankSameStar] → choiceNum 升序**。
     * 三张卡可能不同星级，所以星级仍参与；同级部分由 [sortSameStar] 的同一把尺决定。
     * @return `(原索引, 卡)` 从优到劣
     */
    fun sortChoices(cards: List<JSONObject>, ctx: Ctx): List<Pair<Int, JSONObject>> {
        fun key(i: Int, c: JSONObject): List<Double> {
            val rid = c.opt("rewardId")
            val info = tables.rk(rid)
            val cnRaw = Py.num(c, "choiceNum")?.takeIf { it != 0.0 } ?: (i + 1).toDouble()
            if (info == null) return listOf(1.0, 0.0, 99.0, 99.0, cnRaw)   // 无数据 → 排最后
            val (big, sub) = rankKey(c, ctx)                              // ★同级遗物序（同一入口）
            return listOf(0.0, -(Py.num(info, "rarity") ?: 0.0), big, sub, cnRaw)
        }
        return cards.withIndex()
            .sortedWith(Comparator { a, b -> cmp(key(a.index, a.value), key(b.index, b.value)) })
            .map { it.index to it.value }
    }

    /**
     * PC 版 `pick_card`(5559)：按 **星级降序 → 大类序 → 小类序 → choiceNum 升序** 选一张，返回索引。
     * 无该遗物数据时排最后；若全无数据，则等价于取第一张卡。
     */
    fun pickCard(cards: List<JSONObject>, ctx: Ctx): Int {
        if (cards.isEmpty()) return 0
        return sortChoices(cards, ctx).first().first
    }

    /** 逐项比较（Python 元组语义：先比第一项，相等则比下一项） */
    private fun cmp(a: List<Double>, b: List<Double>): Int {
        for (i in a.indices) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return 0
    }
}
