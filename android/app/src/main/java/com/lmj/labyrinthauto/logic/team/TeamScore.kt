package com.lmj.labyrinthauto.logic.team

/**
 * ★**整队总分的唯一定义**（2026-11-4）。
 *
 * 现状的问题（这就是"想比较却没得比"的根因）：
 *  · `burst` 在 `attrPack` 换人之后**不重算** `res.score`，`aoe` **重算**；
 *  · 而外层 `variantBest` / `buildBest` 恰恰是拿 `score` 比大小。
 *  ⇒ 两个算法算出来的"分"不是同一量纲，跨算法比较（以及将来枚举结构位组合后比较）都不可信。
 *
 * 本文件先把**分项**定死，任何算法都必须能填出这六项；[Parts.total] 就是唯一的整队总分。
 * 各算法只需要在算完后把自己的分项塞进 [Parts]，不要再各自维护一个 `score`。
 *
 * ★当前只提供结构 + 汇总，**尚未接线**（现有算法仍走老路径）：
 *   接线点在 [TeamBuild] 的 `algoBurst`(总分汇总处) / `algoAoe`(attrPack 后重算处)，
 *   以及 `variantBest` / `buildBest` 的比较处（把 `res.score` 换成 `parts.total`）。
 */
object TeamScore {

    /** 整队分的六个分项（**全部**是线性相加，没有交叉项之外的隐式规则） */
    data class Parts(
        /** Σ 个人分：由各算法自己的评分函数给出（tier/职能/破甲/…） */
        val personal: Double = 0.0,
        /** 同属性天赋加成（`Rules.attrScore`，500/1000/2500/5000 → /1000×ATTR_W） */
        val attr: Double = 0.0,
        /** 同系别加成（`same_type` 权重） */
        val sameType: Double = 0.0,
        /** 多余坦克扣分（`TANK_EXTRA` × 超额个数） */
        val tankExtra: Double = 0.0,
        /** 站位跨度超限扣分（`RANGE_SPREAD_PENALTY` × 需换掉人数） */
        val spread: Double = 0.0,
        /** 队伍级加分（jiasu / aoe_core / edge / drain / direct 等） */
        val teamBonus: Double = 0.0,
    ) {
        /** ★唯一整队总分 */
        val total: Double get() = personal + attr + sameType + tankExtra + spread + teamBonus

        /** 一行明细（日志/影子对照用）：`整队 132.5 = 个人 100.0 + 属性 12.5 + 同系 8.0 + 坦克 -8.0 + 跨度 0.0 + 队伍 20.0` */
        fun line(): String = buildString {
            append("整队 ").append(fmt(total))
            append(" = 个人 ").append(fmt(personal))
            append(" + 属性 ").append(fmt(attr))
            append(" + 同系 ").append(fmt(sameType))
            append(" + 坦克 ").append(fmt(tankExtra))
            append(" + 跨度 ").append(fmt(spread))
            append(" + 队伍 ").append(fmt(teamBonus))
        }

        private fun fmt(x: Double): String = String.format(java.util.Locale.ROOT, "%.1f", x)
    }

    /** 比较两个方案：> 0 表示 a 更好（并列返回 0，调用方自行决定"保留先出现的"） */
    fun compare(a: Parts, b: Parts): Int = a.total.compareTo(b.total)
}
