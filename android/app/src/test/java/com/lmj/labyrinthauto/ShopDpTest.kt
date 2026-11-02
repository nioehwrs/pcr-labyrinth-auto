package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.logic.Py
import com.lmj.labyrinthauto.logic.shop.ShopDp
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 商店**精确 DP**（[ShopDp]）与离线模型的对拍。
 *
 * 两边**故意不同源**：
 *   · 基准：`tools/gen_shop_dp_fixtures.py` 用 `gen_shop_dp.Model` 的真函数 `w`/`v`/`f` 算出来
 *     （Python 直接枚举子集，**不读 JSON 曲线**）；
 *   · 被测：Kotlin `ShopDp.decide` 读 `assets/shop_curves.json` 的 G/H 曲线 + 运行时枚举。
 *
 * 一趟同时验证：① 曲线打包（含 4 位小数舍入）没算错；② Kotlin 的二分/掩码/刷新费写对了。
 * 覆盖 b=2(5层) / b=3(3层) × 4 档折扣 × remaining(2/1/0) × 10 种星级组合 × 10 档金币。
 */
class ShopDpTest {

    private val root = findRepoRoot()

    private val curves: Map<Int, ShopDp.Set> = ShopDp.load { name ->
        File(root, "android/app/src/main/assets/$name").readText()
    }

    private val fx = JSONObject(File(root, "tests/fixtures/shop_dp_fixtures.json").readText())

    @Test
    fun 精确DP与离线模型逐项一致() {
        val cases = fx.getJSONArray("cases")
        assertTrue("基准条数太少（${cases.length()}），八成没重新生成", cases.length() >= 2000)
        assertTrue("assets 里缺 shop_curves.json 的 b=2/b=3", curves.containsKey(2) && curves.containsKey(3))

        val bad = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val b = c.getInt("b")
            val set = curves[b] ?: error("shop_curves.json 缺 bCount=$b")
            val arr = c.getJSONArray("items")
            val items = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                ShopDp.Item(o.getInt("price"), o.getInt("score"))
            }
            val d = ShopDp.decide(items, c.getInt("money"), c.getInt("disc"), c.getInt("remaining"), set)

            // 同星级遗物完全等价 ⇒ 只比「各星级买了几件 / 印记几件 / 是否刷新 / 花了多少」
            var n1 = 0; var n2 = 0; var n3 = 0; var nb = 0
            for (k in items.indices) {
                if (!d.buys(k)) continue
                when (arr.getJSONObject(k).getInt("kind")) {
                    1 -> n1++
                    2 -> n2++
                    3 -> n3++
                    else -> nb++
                }
            }
            val exp = c.getJSONObject("expect")
            val got = "n1=$n1 n2=$n2 n3=$n3 nb=$nb refresh=${if (d.refresh) 1 else 0} cost=${d.cost}"
            val want = "n1=${exp.getInt("n1")} n2=${exp.getInt("n2")} n3=${exp.getInt("n3")} " +
                "nb=${exp.getInt("nb")} refresh=${exp.getInt("refresh")} cost=${exp.getInt("cost")}"
            if (got != want) {
                bad.add(
                    "#$i b=$b disc=${c.getInt("disc")} R=${c.getInt("remaining")} " +
                        "money=${c.getInt("money")} ｜ Kotlin=$got ｜ Python=$want",
                )
            }
        }
        assertTrue("DP 与离线模型不一致 ${bad.size}/${cases.length()} 例：\n" + bad.take(10).joinToString("\n"), bad.isEmpty())
    }

    // ────────────────────────── ② 购买清单 ──────────────────────────

    private val tables = com.lmj.labyrinthauto.data.RelicTables.load { name ->
        File(root, "android/app/src/main/assets/$name").readText()
    }

    private val shopFx = JSONObject(File(root, "tests/fixtures/relic_shop_fixtures.json").readText())

    private fun ids(l: List<JSONObject>): List<String> = l.map { it.opt("rewardId").toString() }

    /**
     * 「清单」必须等价于「一次性决策」：同一份货架下，
     *   ① 跑 DP 得到的 `picked` == 按清单挑出来的 `picked`；
     *   ② 清单里减掉刚买的那一件后，挑出来的就是原来的后半段（计划不会中途改口）。
     */
    @Test
    fun 清单模式与一次决策一致且可递减() {
        val p = com.lmj.labyrinthauto.logic.shop.ShopPlan(tables, dp = curves)
        val cases = shopFx.getJSONArray("shop_cases")
        var checked = 0
        var skippedDiffPrice = 0
        for (i in 0 until cases.length()) {
            val inp = cases.getJSONObject(i).getJSONObject("input")
            val area = if (inp.isNull("area")) null else inp.getInt("area")
            if (area != 3 && area != 5) continue                  // 只有 3/5 层接 DP
            val sh = inp.getJSONObject("sh")
            // fixture 会把「4 层专属（price=9999）」这类异常件塞进 3/5 层货架，而**真实游戏同星级同价**
            // （折扣统一）。异价时 DP 会挑便宜的、清单按三选一序挑，两者本就不可比 ⇒ 跳过。
            val priceOfKind = HashMap<String, MutableSet<Int>>()
            val its = sh.optJSONArray("items")
            for (j in 0 until (its?.length() ?: 0)) {
                val it = its!!.optJSONObject(j) ?: continue
                if (Py.truthy(it.opt("isSold"))) continue
                if ((Py.num(it, "availability")?.toInt() ?: -1) != 1) continue
                val k = p.shopItemKind(it.opt("rewardId")).first
                priceOfKind.getOrPut(k) { HashSet() }.add(Py.num(it, "price")?.toInt() ?: 0)
            }
            if (priceOfKind.values.any { it.size > 1 }) { skippedDiffPrice++; continue }

            val (p1, _) = p.shopPlan(area, sh, done = 0)
            val q = p.takeQuota() ?: continue
            assertTrue("清单总件数与 picked 不符：$q / ${ids(p1)}", q.total() == p1.size)

            val (p2, _) = p.shopPlan(area, sh, done = 0, quota = q)
            assertTrue("按清单挑件与一次决策不一致：${ids(p1)} vs ${ids(p2)}", ids(p1) == ids(p2))

            if (p1.isNotEmpty()) {
                // 模拟「买掉第一件」：金币同时减少（真实场景就是这两个一起变），再按剩余清单挑件
                val paid = Py.num(p1[0], "price")?.toInt() ?: 0
                val coin = Py.num(sh, "coin")?.toInt() ?: 0
                val sh2 = JSONObject(sh.toString()).put("coin", coin - paid)
                q.consume(p.shopItemKind(p1[0].opt("rewardId")).first)
                val (p3, _) = p.shopPlan(area, sh2, done = 0, quota = q)
                assertTrue(
                    "买掉一件后计划改了口：期望 ${ids(p1).drop(1)} 实际 ${ids(p3)}",
                    ids(p3) == ids(p1).drop(1),
                )
            }
            checked++
        }
        assertTrue("3/5 层用例太少（$checked，跳过异价 $skippedDiffPrice）", checked >= 20)
    }
}
