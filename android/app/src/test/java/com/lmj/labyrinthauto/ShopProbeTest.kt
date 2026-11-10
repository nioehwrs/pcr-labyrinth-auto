package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.data.RelicTables
import com.lmj.labyrinthauto.logic.shop.ShopDp
import com.lmj.labyrinthauto.logic.shop.ShopPlan
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 商店 DP 的最小回归（2026-11-08 由来：用户反馈「3 层商店 1.2 万金币一件没买」）。
 *
 * 定位结论：**DP 逻辑本身没问题**，正常输入下一定会买满；那次是全 0 清单被缓存
 * （进店第一帧 `availability` 还没填 ⇒ 货架为空 ⇒ DP 选空集 ⇒ 清单 `1★0 2★0 3★0 印记0`）。
 * 修法在 `MazeLoop.handleShop`：货架没就绪就**不决策、不产出清单**。
 *
 * 这里把三种典型货架的期望钉住，顺便把详细结果写到 `build/shop_probe.txt` 方便人工看。
 */
class ShopProbeTest {

    private val root: File = run {
        var d: File = File(System.getProperty("user.dir") ?: ".").absoluteFile
        // 单元测试的 workingDir 是模块目录（android/app）⇒ 往上找到仓库根（含 tests/fixtures）
        while (d.parentFile != null && !File(d, "tests/fixtures").exists()) {
            d = d.parentFile!!
        }
        d
    }
    private val assets = File(root, "android/app/src/main/assets")
    private val tables = RelicTables.load { n -> File(assets, n).readText() }
    private val dp = ShopDp.load { n -> File(assets, n).readText() }

    private fun item(rid: Long, price: Int): JSONObject = JSONObject()
        .put("rewardType", 1).put("rewardId", rid).put("price", price)
        .put("isSold", 0).put("availability", 1)

    private fun shelf(vararg spec: Pair<Long, Int>): JSONObject {
        val arr = JSONArray()
        for ((rid, price) in spec) arr.put(item(rid, price))
        return JSONObject().put("coin", 12090).put("resettable", 2)
            .put("items", arr).put("slots", JSONArray())
    }

    private fun run(title: String, sh: JSONObject, sb: StringBuilder): List<JSONObject> {
        val plan = ShopPlan(tables, dp = dp)
        val (picked, desc) = plan.shopPlan(3, sh, done = 0)
        sb.append("=== $title ===\n")
        sb.append("dpEnabled(3)=${plan.dpEnabled(3)}\n")
        sb.append("desc=$desc\n")
        sb.append("picked=${picked.map { it.opt("rewardId").toString() + "/" + it.opt("price") }}\n")
        sb.append("quota=${plan.takeQuota()}\n")
        sb.append("brief=${plan.shelfBrief(sh, picked)}\n")
        sb.append("kind=${(0 until (sh.optJSONArray("items")?.length() ?: 0)).map { i ->
            val it0 = sh.optJSONArray("items")!!.optJSONObject(i)
            plan.shopItemKind(it0.opt("rewardId")).first
        }}\n\n")
        return picked
    }

    @Test
    fun probe() {
        val sb = StringBuilder()
        // ① 全 1★ + 印记（用户遇到的那种"很划算却不买"）⇒ 3 件 1★ 必须全买
        //   ★2026-11-10 概率改 13:6:1 后：DP 会少买 1 个印记、把 780 金留到刷新后用
        //     （刷新后货架期望更便宜，同样金币能买更多件 ⇒ 期望分 1705.3 → 1720.0，仍是最优解）。
        //     判据从「6 件全买」放宽为「1★ 全买 + 至少 5 件」，仍然挡住"很划算却不买"。
        val p1 = run("3层：3×1★ + 3×印记，12090 金", shelf(
            11001L to 1170, 11002L to 1170, 11003L to 1170,
            31000999L to 780, 31000999L to 780, 31000999L to 780,
        ), sb)
        val ids1 = p1.map { it.optLong("rewardId") }.toSet()
        assertTrue("1★×3 应该全买", ids1.containsAll(listOf(11001L, 11002L, 11003L)))
        assertTrue("很划算的货架不能只买一两件", p1.size >= 5)

        // ② 全是 3★ ⇒ 只买 3 件印记（3★ 不买）
        val p2 = run("3层：全 3★", shelf(
            30001L to 2470, 31001L to 2470, 30001L to 2470,
            31000999L to 780, 31000999L to 780, 31000999L to 780,
        ), sb)
        assertEquals("3★ 不该买", 3, p2.size)

        // ③ 2★ 主导 ⇒ 印记 3 件 + 2★ 至少 1 件
        val p3 = run("3层：3×2★ + 3×印记", shelf(
            21001L to 1820, 21002L to 1820, 21001L to 1820,
            31000999L to 780, 31000999L to 780, 31000999L to 780,
        ), sb)
        assertTrue("2★ 场景至少要买印记", p3.size >= 3)

        val out = File(root, "build/shop_probe.txt")
        out.parentFile?.mkdirs()
        out.writeText(sb.toString())
    }
}
