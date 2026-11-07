package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.data.RelicTables
import com.lmj.labyrinthauto.logic.Py
import com.lmj.labyrinthauto.logic.relic.RelicPick
import com.lmj.labyrinthauto.logic.shop.ShopPlan
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P4 对拍（**第③层：遗物选择 + 商店决策**）。
 *
 * 基准：`tests/fixtures/relic_shop_fixtures.json`（`tools/gen_relic_shop_fixtures.py` 跑 PC 版生成）。
 *
 * | 用例组 | 基准来自 |
 * |---|---|
 * | `relic_cases`(220) | PC 版**真函数**：`pick_card` / `relic_ctx` / `relic_pick_note` / `relic_cards_desc` / `own_relic_stars` / `count_dot_relics` |
 *
 * ★**2026-11-4**：`shop_cases` / `refresh_cases` 两组已删除。
 *   2/3/5 层的商店买/刷新现在**全由 [com.lmj.labyrinthauto.logic.shop.ShopDp] 决定**，
 *   PC 时代的 `shop_plan` 阈值表与 `shop_step` 的 q1/refresh_above 判据已从 ShopPlan 里清掉，
 *   拿 `dp=null` 去对拍已经没有意义（那条路径只剩"4 层按优先级买"）。
 *
 * ★`extra_rank`：基准里带了一批**合成遗物**（真表走不到的分支，如"吸血总星数 ≥20"）；
 *   两边必须用同一份 → 这里把它并进 `relic_rank.json` 再建表。
 */
class RelicShopPortTest {

    private val root = findRepoRoot()
    private val fx = JSONObject(File(root, "tests/fixtures/relic_shop_fixtures.json").readText())
    private val assets = File(root, "android/app/src/main/assets")

    private val tables: RelicTables = run {
        val base = JSONObject(File(assets, RelicTables.RELIC_RANK).readText())
        fx.optJSONObject("extra_rank")?.let { extra ->
            val it = extra.keys()
            while (it.hasNext()) {
                val k = it.next()
                base.put(k, extra.get(k))
            }
        }
        RelicTables.load { name ->
            if (name == RelicTables.RELIC_RANK) base.toString() else File(assets, name).readText()
        }
    }
    private val relic = RelicPick(tables)
    // ★sameStarRelicOrder=false：对拍基准来自 PC（PC 没有"同星级遗物序"）⇒ 用 PC 原行为比
    private val shop = ShopPlan(tables, sameStarRelicOrder = false)

    // ────────────────────────── ① 遗物选择 ──────────────────────────

    @Test
    fun 遗物选择与PC版逐项一致() {
        val cases = fx.getJSONArray("relic_cases")
        assertTrue("基准条数太少（${cases.length()}），八成没重新生成", cases.length() >= 200)
        var same = 0
        val bad = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val inp = cases.getJSONObject(i).getJSONObject("input")
            val exp = cases.getJSONObject(i).getJSONObject("expect")
            val cardsArr = inp.getJSONArray("cards")
            val cards = (0 until cardsArr.length()).map { cardsArr.getJSONObject(it) }
            val own = inp.optJSONObject("own")
            val area = if (inp.isNull("area")) null else inp.getInt("area")
            val boss3 = if (inp.isNull("boss3")) null else inp.getInt("boss3")
            val areaTxt = if (inp.isNull("area")) "null" else "$area"
            val diffs = mutableListOf<String>()
            fun eq(n: String, mine: Any?, theirs: Any?) {
                if (canon(mine) != canon(theirs)) {
                    diffs.add("$n: Kotlin=${canon(mine).take(300)} ｜ PC=${canon(theirs).take(300)}")
                }
            }
            val ctx = relic.relicCtx(own, area, boss3)
            eq("stars", ctx.star, exp.opt("stars"))
            eq("own_dot", ctx.ownDot, exp.opt("own_dot"))
            eq("dots", cards.map { relic.dotRelicKinds(it.opt("rewardId")) }, exp.opt("dots"))
            eq(
                "ctx",
                mapOf(
                    "own_dot" to ctx.ownDot, "n_relics" to ctx.nRelics, "star" to ctx.star,
                    "area" to ctx.area, "special" to ctx.special,
                ),
                exp.opt("ctx"),
            )
            val k = relic.pickCard(cards, ctx)
            eq("pick", k, exp.opt("pick"))
            if (cards.isNotEmpty()) {
                eq("note", relic.relicPickNote(cards[k.coerceIn(0, cards.size - 1)], ctx), exp.opt("note"))
            }
            eq("desc", relic.relicCardsDesc(cards, ctx), exp.opt("desc"))
            if (diffs.isEmpty()) same++ else if (bad.size < 6) {
                bad.add(
                    "case#$i area=$areaTxt cards=${cardsArr.length()} " +
                        "own=${inp.optJSONObject("own")?.optJSONArray("relics")?.length() ?: 0}\n      " +
                        diffs.joinToString("\n      "),
                )
            }
        }
        assertTrue(
            "遗物选择对拍：一致 $same / ${cases.length()}\n\n" + bad.joinToString("\n"),
            same == cases.length(),
        )
    }
}
