package com.lmj.labyrinthauto.logic

/** 一条结算奖励（已按 type:id 汇总，name 为中文名） */
data class RewardEntry(val key: String, val name: String, val count: Int)

/** 一局完整跑完的记录（成绩 + 奖励），供「整轮运行结束」汇总 */
data class RunRecord(
    val index: Int,
    val score: Map<String, String>,
    val rewards: List<RewardEntry>,
)

/**
 * 连跑期间的"已完成局"累加器：
 *  - 每局**完整跑完**时 [add] 一条；
 *  - 整轮运行结束（自然跑完 / 某局失败 / 手动停止 / 异常）时由
 *    [com.lmj.labyrinthauto.service.AutoForegroundService] 调 [summaryLines] 打日志；
 *  - 中断时同样输出**前面已完成**的局（[records] 里已有的）。
 */
object MazeSummary {

    private val records = mutableListOf<RunRecord>()

    fun reset() = records.clear()

    fun add(r: RunRecord) {
        records.add(r)
    }

    fun isEmpty(): Boolean = records.isEmpty()

    fun records(): List<RunRecord> = records.toList()

    /** 生成汇总日志行（逐条已完成的局 + 奖励合计）；空则返回空列表 */
    fun summaryLines(planned: Int, done: Int): List<String> {
        if (records.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        out.add("═══ 本轮汇总：成功 $done 局 / 计划 $planned ═══")
        val aggOrder = ArrayList<String>()
        val aggName = HashMap<String, String>()
        val aggSum = LinkedHashMap<String, Int>()
        for (r in records) {
            val s = r.score
            fun f(k: String): String = s[k]?.takeIf { it.isNotEmpty() && it != "-" } ?: "-"
            val scoreTxt = "总分=${f("总分")} ｜ 难度=${f("难度")} ｜ 得分率=${f("得分率")} ｜ " +
                "累计点=${f("累计点")} ｜ 强化点=${f("强化点")} ｜ 迷宫币=${f("迷宫币")}"
            val rwTxt = if (r.rewards.isEmpty()) "无" else
                r.rewards.joinToString("、") { "${it.name}×${it.count}" }
            out.add("  第 ${r.index} 局：$scoreTxt")
            out.add("    奖励：$rwTxt")
            for (e in r.rewards) {
                if (!aggSum.containsKey(e.key)) {
                    aggOrder.add(e.key)
                    aggName[e.key] = e.name
                }
                aggSum[e.key] = (aggSum[e.key] ?: 0) + e.count
            }
        }
        if (aggOrder.isNotEmpty()) {
            out.add("  ── 奖励合计：" + aggOrder.joinToString("、") { "${aggName[it]}×${aggSum[it]}" })
        }
        return out
    }
}
