package com.lmj.labyrinthauto.reroll

import android.util.Log
import com.lmj.labyrinthauto.logic.party.PartyPlan
import com.lmj.labyrinthauto.logic.Route
import com.lmj.labyrinthauto.net.labyrinth.LabyrinthMapNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * PC `judge()`（reroll.py:721）的 Android 移植：两项判定
 *  ① Boss 白名单：3/5 区 Boss 的 unitId 要在名单里（复用 [PartyPlan.bossOf] 从 questId 映射到 unitId）
 *  ② 价值偏差：复用 [Route.checkValuePerfect]（每区「最优路线得分」vs「理论最高分」），
 *     用 [RerollConfig.maxValueGap] 把关（不再有「偏差区数」维度）。
 * 判 Boss / 判价值偏差**永远执行**，不靠开关控制（不判 Boss = 把 5 个固定 Boss 全选即可）。
 * 同时产出每区最优路线（供 MazeLoop 直接推进）与 3/5 区 Boss 的 unitId。
 */
data class JudgeResult(
    val ok: Boolean,
    val fail: List<String>,
    val bossByArea: Map<Int, Int>,
    val routePerArea: Map<Int, List<Int>>,
    val actual: Int,
    val theory: Int,
    /** 给日志/悬浮窗的一句话摘要：经典 = `价值=5690/5690`；自定义 = `节点顺序=5/5` */
    val summary: String = "价值=$actual/$theory",
)

object RerollJudge {
    fun judge(nodes: List<LabyrinthMapNode>, config: RerollConfig): JudgeResult {
        val panels = nodes.map { toPanel(it) }

        val bossByArea = mutableMapOf<Int, Int>()
        val bossDiag = mutableListOf<String>()
        for (p in panels) {
            if (p.optInt("blockType") == 8) {
                val q = p.optString("questId")
                val area = p.optInt("area")
                val uid = PartyPlan.bossOf(q)
                bossDiag += "block_type=8 area=$area questId=$q → unitId=$uid"
                if (uid != null) bossByArea[area] = uid
            }
        }
        if (bossDiag.isNotEmpty()) {
            Log.d("RerollJudge", "Boss 节点解析：\n" + bossDiag.joinToString("\n"))
        } else {
            Log.d("RerollJudge", "本次 enter 响应未找到 block_type=8 的 Boss 节点（共 ${panels.size} 面板）")
        }

        val fail = mutableListOf<String>()
        // ★判 Boss 永远执行（不需要开关）。白名单为空集则该区不检查。
        //   提示里用**名字**（2026-10-19 用户：别写 unitId），查不到名字才退回 id。
        val a3 = bossByArea[3]
        val a5 = bossByArea[5]
        if (a3 != null && a3 !in config.bossArea3) {
            fail += "3 区 Boss ${PartyPlan.bossName(a3) ?: a3} 不在白名单"
        }
        if (a5 != null && a5 !in config.bossArea5) {
            fail += "5 区 Boss ${PartyPlan.bossName(a5) ?: a5} 不在白名单"
        }

        var ok = fail.isEmpty()
        var actual = 0
        var theory = 0
        var routePerArea: Map<Int, List<Int>> = emptyMap()
        var summary = ""

        if (config.judgeMode == RerollConfig.MODE_CUSTOM) {
            // ★自定义模式（2026-11-03）：不比分数，只要**5 个区域都能找到一条满足自选节点顺序的路线**。
            val plans = AreaPattern.decode(config.areaPlan)
            val m = AreaPattern.match(nodes, plans)
            summary = "节点顺序=${m.routes.size}/5"
            if (!m.ok) {
                ok = false
                // ★说清**哪个区卡在第几列、那一列要什么**（用户要能对着配置排查，2026-11-03）
                val bits = m.failAt.entries.sortedBy { it.key }.map { (a, col) ->
                    val want = plans.firstOrNull { it.area == a }?.want(col) ?: AreaPattern.ANY
                    val tail = if (want != AreaPattern.ANY) "要${AreaPattern.name(want)}" else "该区没节点"
                    "区域$a 第${col}列($tail)"
                }
                fail += "节点顺序：${bits.joinToString("、")} 找不到"
            } else {
                routePerArea = m.routes
            }
            Log.d(
                "RerollJudge",
                "自定义判定：" + (1..5).joinToString(" ") {
                    if (it in m.routes) "区域$it✓" else "区域$it✗第${m.failAt[it]}列"
                },
            )
        } else {
            // ★经典模式：判价值偏差永远执行，仅由 maxValueGap（分数差）决定。
            Route.setEventValue(config.eventValue)
            val value = Route.checkValuePerfect(panels)
            actual = value.actual
            theory = value.theory
            val gap = theory - actual
            val mg = config.maxValueGap
            if (mg >= 0 && gap > mg) {
                ok = false
                fail += "价值偏差 差 ${gap} 分（允许 ${mg}）"
            }
            routePerArea = value.details.associate { it.area to it.path }
            summary = "价值=$actual/$theory"
        }

        return JudgeResult(ok, fail, bossByArea, routePerArea, actual, theory, summary)
    }

    private fun toPanel(node: LabyrinthMapNode): JSONObject = JSONObject().apply {
        put("area", node.area)
        put("column", node.column)
        put("row", node.row)
        put("blockId", node.blockId)
        put("blockType", node.blockType)
        put("questId", node.questId?.toString() ?: "")
        val arr = JSONArray()
        node.nextBlockIds.forEach { arr.put(it) }
        put("next", arr)
        put("visited", 0)
    }
}
