package com.lmj.labyrinthauto.logic.team

/**
 * ★组队**可调参数**（2026-11-4，物法混编实验用）。
 *
 * 背景：现在 `burstScore`/`aoeScore` 里只有"同系 +same_type(8)"，异系**不扣分**，
 * 而 tier 每级是 10 分 ⇒ 一个 T5 异系角色净贡献 50 分，为了保住 8 分加成放弃它并不划算，
 * 于是 59% 的队伍都是混编。
 *
 * [mixPenalty] = **异系每人的扣分**（0 = 现状，不扣）。扫描这个值就能看出
 * "混编率 vs 整队分"的权衡曲线，挑一个"混编明显减少、分数基本不跌"的点。
 *
 * ★默认 **12.0**（2026-11-4 用户定）：历史 176 支队伍回放，混编率 32% → 8.5%，
 *   而成员 tier 合计只从 22.09 掉到 21.82（5 人合计 -0.27 ≈ 每人 0.05 档）——几乎零成本。
 *   曲线上 P=12 就拐平了（15/20/25 的混编率分别是 8%/5.7%/5.7%，收益已到头）。
 */
object TeamTuning {

    /** 异系角色的扣分（每人）；0 = 回到"只加同系 8 分、异系不扣"的老行为 */
    const val DEFAULT_MIX_PENALTY = 12.0

    @Volatile
    var mixPenalty: Double = DEFAULT_MIX_PENALTY

    /** 异系但"通用型"(soft) 角色的折算比例（0.5 = 只扣一半；标签还没加，先留口） */
    @Volatile
    var softRatio: Double = 0.5

    fun reset() {
        mixPenalty = DEFAULT_MIX_PENALTY
        softRatio = 0.5
    }
}
