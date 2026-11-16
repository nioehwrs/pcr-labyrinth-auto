package com.lmj.labyrinthauto.logic.shop

import org.json.JSONObject
import kotlin.math.abs

/**
 * 商店「精确 DP」（方案 A，2026-11-02 与用户确认）—— **独立模块**，由 [ShopPlan] 按开关调用。
 *
 * ## 为什么不是「区间表」
 * 整套 DP 里只有两个量**既贵又与当前货架无关**：
 *   · `G(钱)` = 换货后不能再换时的期望分
 *   · `H(钱)` = 换货后还能再花 600 换一次时的期望分
 * 它们只依赖 (bCount, 折扣, 金币)，所以离线枚举成阶梯曲线（`tools/gen_shop_dp.py` → `shop_curves.json`，约 54KB）。
 * 而「**当前货架已知**」的那一层（`V`/`F`）只需枚举 2^n 个子集（n ≤ 6 ⇒ ≤ 64 次）+ 查曲线：
 *
 *   · 已刷 2 次（不能再刷）：`W = max{ 子集分数 }`
 *   · 还能刷 1 次（费 600）  ：`V = max{ 子集分数 + G(钱-花费-600) }`
 *   · 刚进店（还能刷 2 次）  ：`F = max{ 子集分数 + H(钱-花费-300) }`
 *
 * ## 关键收益：天然支持「进店时货架已经被买过几件」
 * 因为输入是「**当前货架上还剩什么**」而不是「这一轮最开始是什么」，所以闪退重进（买完 1 件就断）、
 * 买一半失败、游戏自己刷过 —— 全都只是普通的输入，**不需要为部分状态另建表**（那要 720~960 张表）。
 * 顺带：货架越空 ⇒ `V/F` 越低 ⇒ 自动更想刷新，无需额外规则。
 *
 * 与已删除的旧 `ShopMdp`（Monte Carlo 4000 样本）的区别：这里是**精确枚举**（27 种星级组合 × 32 子集全展开），
 * 没有采样噪声，也不再需要「P(刷新)≥0.9」这类稳健化处理。
 * 区间表（`shop_dp_5f.json` / `shop_dp_3f.json`）保留，只作为对拍基线。
 */
object ShopDp {

    /** assets 里的曲线文件 */
    const val CURVES = "shop_curves.json"

    /** 刷新费（不打折）：第一次 300、第二次 600 —— 与离线模型一致 */
    private val REFRESH_COST = intArrayOf(300, 600)

    private const val EPS = 1e-9

    /**
     * ★2026-11-16：**同价值判定容差**（"省钱的 tie-break"专用）。
     *   曲线值是 4 位小数打包的，精度 ~5e-5 ⇒ 用 1e-9 会把"本应相等"的两条路径判成严格不等，
     *   tie-break 就失效了（实测 2400 例里有 2 例两边因此选得不一样）。分数是整数，真差值最小 1 分，
     *   所以 1e-3 既覆盖舍入误差、又不会把"真的更优"误判成 tie。
     */
    private const val TIE_EPS = 1e-3

    /** 一段阶梯曲线：`xs` 严格递增（单位=**实付金币**），`ys` 对应期望分 */
    class Curve(val xs: IntArray, val ys: DoubleArray) {

        /** 二分：最后一个 `xs[i] <= money` 的 `ys[i]`（不足 → 0） */
        fun at(money: Int): Double {
            if (money < 0 || xs.isEmpty()) return 0.0
            if (money < xs[0]) return 0.0
            if (money >= xs[xs.size - 1]) return ys[ys.size - 1]
            var lo = 0
            var hi = xs.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (xs[mid] <= money) lo = mid else hi = mid - 1
            }
            return ys[lo]
        }
    }

    /** 一套 bCount 的曲线（5 层 = b=2，3 层 = b=3） */
    class Set(val bCount: Int, val g: Map<Int, Curve>, val h: Map<Int, Curve>) {

        fun gAt(discPct: Int, money: Int): Double = pick(g, discPct).at(money)

        fun hAt(discPct: Int, money: Int): Double = pick(h, discPct).at(money)

        /** 档位缺失时取最接近的档（游戏实际只有 100/90/75/65，其余是保险档） */
        private fun pick(m: Map<Int, Curve>, discPct: Int): Curve {
            m[discPct]?.let { return it }
            val near = m.keys.minByOrNull { abs(it - discPct) } ?: error("shop_curves 为空")
            return m.getValue(near)
        }
    }

    /** 货架上的一件（只要价格 + 分数：遗物 200 / 印记 100） */
    class Item(val price: Int, val score: Int)

    /**
     * 决策结果：[mask] 是按传入 [items] 顺序的子集位掩码（买哪几件），[refresh] 是「买完就刷新」。
     * `value` 是「分数 + 后续期望」的合计（调试/对拍用）。
     */
    class Decision(
        val mask: Int,
        val cost: Int,
        val score: Int,
        val refresh: Boolean,
        val value: Double,
    ) {
        fun buys(i: Int): Boolean = (mask and (1 shl i)) != 0
    }

    /** 从「名字 → JSON 文本」读曲线（app 传 assets、单测传文件）；返回 bCount → Set */
    fun load(reader: (String) -> String): Map<Int, Set> {
        val root = JSONObject(reader(CURVES))
        val out = LinkedHashMap<Int, Set>()
        for (key in root.keys()) {
            val o = root.optJSONObject(key) ?: continue
            val b = o.optInt("bCount", 0)
            if (b <= 0) continue
            out[b] = Set(b, parseCurves(o.optJSONObject("g")), parseCurves(o.optJSONObject("h")))
        }
        return out
    }

    private fun parseCurves(o: JSONObject?): Map<Int, Curve> {
        val m = LinkedHashMap<Int, Curve>()
        if (o == null) return m
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next().toIntOrNull() ?: continue
            val arr = o.optJSONArray(k.toString()) ?: continue
            val xs = IntArray(arr.length())
            val ys = DoubleArray(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                xs[i] = p.optInt(0)
                ys[i] = p.optDouble(1, 0.0)
            }
            if (xs.isEmpty()) continue
            m[k] = Curve(xs, ys)
        }
        return m
    }

    /**
     * 对**当前剩余货架**求最优（买哪几件 + 买完是否刷新）。
     *
     * @param items    货架上**还能买**的件（已按业务过滤：定向印记/本层不买的星级/买不起的都别传进来）
     * @param money    当前金币
     * @param discPct  折扣整数百分比（100/90/75/65）
     * @param remaining 本店**剩余可刷新次数**（2=刚进店、1=已刷 1 次、0=不能再刷）
     */
    fun decide(items: List<Item>, money: Int, discPct: Int, remaining: Int, model: Set): Decision {
        val n = items.size
        val masks = 1 shl n
        val costs = IntArray(masks)
        val scores = IntArray(masks)
        for (mask in 1 until masks) {
            val low = mask and (-mask)                 // 最低位
            val i = Integer.numberOfTrailingZeros(low)
            val prev = mask xor low
            costs[mask] = costs[prev] + items[i].price
            scores[mask] = scores[prev] + items[i].score
        }

        // ① 不刷新：分数优先，同分花费更少（与离线模型 w() 一致）
        var best = Decision(0, 0, 0, false, 0.0)
        for (mask in 0 until masks) {
            if (costs[mask] > money) continue
            if (scores[mask] > best.score || (scores[mask] == best.score && costs[mask] < best.cost)) {
                best = Decision(mask, costs[mask], scores[mask], false, scores[mask].toDouble())
            }
        }
        // ② 刷新：只有**严格更优**才替换（同值不刷 —— 别白花 300/600）
        val fee = when {
            remaining >= 2 -> REFRESH_COST[0]
            remaining == 1 -> REFRESH_COST[1]
            else -> 0
        }
        if (fee > 0) {
            for (mask in 0 until masks) {
                val cost = costs[mask]
                if (cost + fee > money) continue
                val rest = money - cost - fee
                val after = if (remaining >= 2) model.hAt(discPct, rest) else model.gAt(discPct, rest)
                val v = scores[mask] + after
                // ★2026-11-16（用户）：**同为“买完刷新”的方案**、期望分又一样时，选**花费更少**的
                //   （剩余金币多 = 弱支配：钱多不会让后续更差）。
                //   曲线 hAt/gAt 是按金币分桶的阶梯，同一桶内取值相同 ⇒ 会出现"期望一样但一个更费钱"的 tie；
                //   原逻辑只判严格更优，tie 时保留先遍历到的（mask 小的），不保证省钱。
                //   ⚠只在 best 也是刷新方案时启用 —— 不能拿它替换"不刷新"（那会白花刷新费换同样的分）。
                val better = v > best.value + TIE_EPS ||
                    (best.refresh && kotlin.math.abs(v - best.value) <= TIE_EPS && cost < best.cost)
                if (better) best = Decision(mask, cost, scores[mask], true, v)
            }
        }
        return best
    }
}
