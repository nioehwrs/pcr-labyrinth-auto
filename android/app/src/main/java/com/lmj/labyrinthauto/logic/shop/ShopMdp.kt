package com.lmj.labyrinthauto.logic.shop

import com.lmj.labyrinthauto.data.RelicTables
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import org.json.JSONObject

/**
 * 商店购买「有限期 MDP / 最优停止」——**独立模块，尚未接入** ShopPlan / MazeLoop 的正式流程。
 *
 * 规则（2026-10-18 用户修正）：
 *  - 分值 = 真实结算分数（遗物 200 / 印记 100；难度倍率不影响决策）。
 *  - 折扣非固定 0.65：disc = 印记实付 / 1200；遗物实付 = 原价(1800/2800/3800) × disc。
 *  - 货架：3层=3随机遗物+3随机印记；5层=3随机遗物+1定向+2随机（定向窗口未做→暂按 2 随机印记 = 5 件）。
 *  - 刷新 2 次固定 300/600，**不打折**。
 *  - 抽取比例未知 → 假设「与星级无关」：每次刷新的 3 件遗物每件星级独立等概率 1/3。
 *    同星级遗物完全等价（同价同分），故 MDP 不依赖 ownedRelicIds。
 *  - 3层暂固定不买 3★；5层 3★ 由算法（T(R) 阈值）决定；buy2Star 默认开。
 *
 * 提供两块：
 *   - [poolStats]：池分布 + q1（PC 旧模型）—— 给现有 refreshDecision 填 q1 用（Phase 1，未改动）。
 *   - [solve] / [shouldBuy3Star] / [shouldRefresh]：DP 解 —— 替换 done>=max_refresh / refresh_above 用（Phase 3）。
 */
private typealias Item = Triple<String, Int, Int>

object ShopMdp {

    private val RELIC_BASE = mapOf(1 to 1800, 2 to 2800, 3 to 3800)   // 遗物原价（按星级）
    private const val MARK_BASE = 1200                                 // 随机印记原价
    private const val VAL_RELIC = 200                                  // 遗物结算分/件
    private const val VAL_MARK = 100                                   // 印记结算分/件
    private val REFRESH_COST = intArrayOf(300, 600)                    // 刷新费（不打折）

    data class PoolStats(
        val poolByRarity: Map<Int, Int>,
        val availByRarity: Map<Int, Int>,
        val p1: Double?,
        val q1: Double?,
    ) {
        override fun toString(): String =
            "pool=$poolByRarity avail=$availByRarity p1=${p1.fmt(3)} q1=${q1.fmt(3)}"
    }

    // ─────────────────────────── ① 池分布 + q1（PC 旧模型，喂现有 refreshDecision） ───────────────────────────

    private fun relicPoolIds(tables: RelicTables): Map<Int, JSONObject> {
        val out = linkedMapOf<Int, JSONObject>()
        for ((k, v) in tables.rank) {
            val s = k.toString()
            if (s.length != 5 || s[0] !in "123" || s[1] !in "12345") continue
            if (v.optBoolean("shop_only", false)) continue
            out[k] = v
        }
        return out
    }

    private fun area4OnlyIds(tables: RelicTables): Set<Int> {
        val out = mutableSetOf<Int>()
        for ((k, v) in tables.rank) {
            if (v.optBoolean("shop_only", false) || v.optInt("mark", -1) == 0) out.add(k)
        }
        return out
    }

    fun poolStats(tables: RelicTables, ownedRelicIds: Set<Int>, area: Int?): PoolStats {
        if (area == 4) {
            val fx = area4OnlyIds(tables)
            return PoolStats(mapOf(3 to fx.size), mapOf(3 to fx.size), null, null)
        }
        val pool = relicPoolIds(tables)
        val tot = mutableMapOf<Int, Int>()
        for (v in pool.values) tot.merge(v.optInt("rarity", 0), 1) { a, b -> a + b }
        val avail = tot.toMutableMap()
        for (k in pool.keys) if (k in ownedRelicIds) {
            avail.merge(pool[k]!!.optInt("rarity", 0), -1) { a, b -> a + b }
        }
        val nAll = avail.values.sum().coerceAtLeast(0)
        val n1 = (avail[1] ?: 0).coerceAtLeast(0)
        val p1 = if (nAll > 0) n1.toDouble() / nAll else null
        val q1 = if (p1 != null) 1.0 - (1.0 - p1).pow(3.0) else null
        return PoolStats(tot, avail, p1, q1)
    }

    // ─────────────────────────── ② DP 解（Monte Carlo + 子集枚举） ───────────────────────────

    data class MdpResult(
        /** 最优刷新门槛：G ≥ 该值才刷新（R>0）。替换 PC 的 per-refresh refresh_above 列表。 */
        val refreshGateG: Int,
        /** 买 3★ 阈值 T(R)：G ≥ T(R) 才买 3★（仅当货架已无可买更好的）。 */
        val t3StarByR: Map<Int, Int>,
        /** 调试用：R → V(G,R) 网格（与 grid 对齐）。 */
        val sampleW: Map<Int, DoubleArray>,
    )

    /**
     * 采样一个货架（5层=3遗物+2随机印记；3层=3遗物+3随机印记）。
     * 3 件遗物每件星级独立等概率 1/3；allow3Star=false 时不含 3★（3层固定不买），buy2Star=false 时不含 2★。
     */
    private fun sampleShelf(
        area: Int,
        rng: Random,
        disc: Double,
        allow3Star: Boolean,
        buy2Star: Boolean,
    ): List<Item> {
        val markPrice = (MARK_BASE * disc).roundToInt()
        val shelf = mutableListOf<Item>()
        repeat(3) {
            val r = 1 + rng.nextInt(3)                       // 1/2/3 等概率（与星级无关假设）
            if (r == 3 && !allow3Star) return@repeat
            if (r == 2 && !buy2Star) return@repeat
            val price = (RELIC_BASE[r]!! * disc).roundToInt()
            shelf.add(Item("${r}\u2605\u9057\u7269", price, VAL_RELIC))
        }
        val nMark = if (area == 5) 2 else 3                  // 5层忽略定向印记 → 2 件随机印记
        repeat(nMark) { shelf.add(Item("\u968f\u673a\u5370\u8bb0", markPrice, VAL_MARK)) }
        return shelf
    }

    /**
     * 解 DP（Monte Carlo + 子集枚举）。参数化折扣 disc（= 印记实付/1200），结果随 (area, maxRefresh, disc) 变。
     * @param disc 当前折扣（disc = 印记实付 / 1200）。默认 0.65 仅作占位，真实调用应传运行时值。
     * @param allow3Star 是否允许买 3★（5层 true，3层 false）。
     * @param buy2Star 是否允许买 2★（默认开）。
     */
    fun solve(
        tables: RelicTables? = null,
        area: Int,
        maxRefresh: Int,
        disc: Double = 0.65,
        allow3Star: Boolean = (area == 5),
        buy2Star: Boolean = true,
        gMax: Int = 15000,
        step: Int = 100,
        nSamples: Int = 4000,
        seed: Long = 1L,
    ): MdpResult {
        val rng = Random(seed)
        val markPrice = (MARK_BASE * disc).roundToInt()
        val nMark = if (area == 5) 2 else 3
        val grid = (0..gMax step step).toList()
        val shelves = List(nSamples) { sampleShelf(area, rng, disc, allow3Star, buy2Star) }

        // 逆向归纳：先终局 W0，再 W1..W(maxRefresh)
        val wByR = mutableMapOf<Int, DoubleArray>()
        val b3ByR = mutableMapOf<Int, DoubleArray>()
        val refrByR = mutableMapOf<Int, DoubleArray>()
        val (w0, b3_0, _) = evalShelves(shelves, grid, null, 0, maxRefresh, markPrice)
        wByR[0] = w0; b3ByR[0] = b3_0
        var wPrev = w0
        for (r in 1..maxRefresh) {
            val (w, b3, refr) = evalShelves(shelves, grid, wPrev, r, maxRefresh, markPrice)
            wByR[r] = w; b3ByR[r] = b3; refrByR[r] = refr
            wPrev = w
        }

        // 刷新门槛：最顶层（r=maxRefresh，即首次决策）P(刷新) 首次 ≥0.9 的最小 G（稳健，避开 Monte Carlo 噪声）；
        // 若样本峰值 <0.9 则退化为首次 ≥0.5。0.9 代表「MDP 强烈建议刷」，贴合 5 层 greedy 花光哲学。
        val refrTop = refrByR[maxRefresh] ?: refrByR[1]
        var refreshGateG = 0
        if (refrTop != null) {
            var found = -1
            for (gi in grid.indices) if (refrTop[gi] >= 0.9) { found = gi; break }
            if (found < 0) for (gi in grid.indices) if (refrTop[gi] >= 0.5) { found = gi; break }
            if (found >= 0) refreshGateG = grid[found]
        }
        // 买 3★ 阈值 T(R)：b3≥0.5 的最小 G（每块 R）
        val t3 = mutableMapOf<Int, Int>()
        for (r in 0..maxRefresh) {
            val arr = b3ByR[r] ?: continue
            for (gi in grid.indices) if (arr[gi] >= 0.5) { t3[r] = grid[gi]; break }
        }
        return MdpResult(refreshGateG = refreshGateG, t3StarByR = t3, sampleW = wByR)
    }

    /** 对每个抽样货架枚举子集，返回 (W, b3=买3★概率, refr=刷新概率)。 */
    private fun evalShelves(
        shelves: List<List<Item>>,
        grid: List<Int>,
        wNext: DoubleArray?,             // null = R=0 终局
        rLevel: Int,                     // 决策前剩余刷新次数
        maxRefresh: Int,
        markPrice: Int,                  // 刷新后必出现的最便宜件（印记实付）
    ): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val step = if (grid.isEmpty()) 1 else (grid[1] - grid[0])
        val c = if (wNext == null) 0 else REFRESH_COST.getOrElse(maxRefresh - rLevel) { 0 }
        val wOut = DoubleArray(grid.size)
        val b3Out = DoubleArray(grid.size)
        val refrOut = DoubleArray(grid.size)
        for (shelf in shelves) {
            val n = shelf.size
            val best = DoubleArray(grid.size)
            val bb3 = DoubleArray(grid.size)
            val bref = DoubleArray(grid.size)
            for (mask in 0 until (1 shl n)) {
                var cost = 0
                var value = 0
                var b3f = false
                for (i in 0 until n) {
                    if (mask and (1 shl i) != 0) {
                        val (k, p, v) = shelf[i]
                        cost += p
                        value += v
                        if (k == "3\u2605\u9057\u7269") b3f = true
                    }
                }
                for (gi in grid.indices) {
                    val g = grid[gi]
                    if (cost > g) continue
                    val gAfter = g - cost
                    val didRefr = wNext != null && rLevel > 0 && gAfter >= c && (gAfter - c) >= markPrice
                    val vv: Double = if (didRefr) value + lift(wNext!!, gAfter - c, step) else value.toDouble()
                    if (vv > best[gi]) {
                        best[gi] = vv
                        bb3[gi] = if (b3f) 1.0 else 0.0
                        bref[gi] = if (didRefr) 1.0 else 0.0
                    }
                }
            }
            for (gi in grid.indices) {
                wOut[gi] += best[gi]
                b3Out[gi] += bb3[gi]
                refrOut[gi] += bref[gi]
            }
        }
        val m = shelves.size.toDouble()
        for (gi in grid.indices) {
            wOut[gi] /= m
            b3Out[gi] /= m
            refrOut[gi] /= m
        }
        return Triple(wOut, b3Out, refrOut)
    }

    private fun lift(w: DoubleArray, gold: Int, step: Int): Double {
        if (gold <= 0) return w[0]
        val gi = ((gold.toDouble() / step).roundToInt()).coerceIn(0, w.lastIndex)
        return w[gi]
    }

    // ─────────────────────────── ③ 接入期用的判定（Phase 3） ───────────────────────────

    /** 是否该刷新（R>0 且 G ≥ 刷新门槛）。仅服务于 5层 MDP 接管，3★ 决策已改用离线阈值表 [ShopPlan.threeStarThresholds]。 */
    fun shouldRefresh(result: MdpResult, gold: Int, remainingRefresh: Int): Boolean {
        return remainingRefresh > 0 && gold >= result.refreshGateG
    }

    // ─────────────────────────── 调试：摘要 ───────────────────────────

    fun summary(result: MdpResult, area: Int, maxRefresh: Int): String {
        val t = result.t3StarByR.entries.sortedBy { it.key }
            .joinToString(" ") { "T(${it.key})=${it.value}" }
        return "area=$area maxRefresh=$maxRefresh \u5237\u65b0\u95e8\u69db\u2248${result.refreshGateG}  $t"
    }

    private fun Double?.fmt(n: Int): String =
        if (this == null) "?" else String.format("%.${n}f", this)
}
