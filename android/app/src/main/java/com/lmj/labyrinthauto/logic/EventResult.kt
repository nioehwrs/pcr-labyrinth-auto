package com.lmj.labyrinthauto.logic

import com.lmj.labyrinthauto.core.AppState
import org.json.JSONObject

/**
 * 黎明界**事件结果表**（`event_result.json`，由 PC 侧 `gen_event_result.py` 从 master 库生成）。
 *
 * 为什么能有这张表（2026-11-03 从日志反推出、再回库验证）：
 *   `eventResultId`（`LabyrinthStatus +0x40`）= **choice_id × 10 + 分支号**，
 *   而 choice_id = event_id × 10 + **卡片槽位**（1 起，槽位 = `labyrinth_event` 的
 *   choice_id_1/2/3 列顺序，正好就是屏幕上的卡片顺序）。
 *   ⇒ 选了第几张卡、落到第几支，结果**完全可以从 master 库算出来**，不用在游戏里一局局试。
 *   ⚠️ 坑：有的表把列写成倒序（如 1107 = 11073/11072/11071），此时槽位 1 的 cid 尾数是 3，
 *     所以**不能**用 `eventResultId / 100 % 10` 当槽位，必须以表里的 choice_id 为准（见 [where]）。
 *
 * 效果码 = `LabyrinthDefine.eEventEffectType`（dump.cs 193678）：
 *   1 固定金币 / 2 比例金币 / 3 遗物 / 4 印记 / 5 商店 / 6 战斗格 / 7 连锁下一事件 / 8 无效果。
 *   ★3/4/6/7 的 value 在库里回填的就是 result id 自己（不是遗物/印记 id），
 *     "具体拿到什么"（几星遗物、哪种印记）以选项描述文案为准。
 *   ★概率事件（钓鱼/料理/老虎机）的**失败兜底固定在库里写成「金币×500」**，而描述文案只写成功那支
 *     （"50％概率获得…并进入下一个事件"）—— 所以日志里看到「金币=500」就是没中。
 */
object EventResult {

    /**
     * 一条结果分支落在哪儿：事件/卡片槽位/分支号 + 该卡的**描述文案**。
     * 描述文案是判断「这条分支给几个印记」的唯一依据（库里的 value 只回填 result id）。
     */
    data class Hit(val eid: Int, val slot: Int, val branch: Int, val desc: String) {
        val label: String get() = "事件$eid 卡$slot 分支$branch"
    }

    private var raw: JSONObject? = null

    /** 效果码 -> 中文名（来自表里的 `effectNames`） */
    private val names = mutableMapOf<Int, String>()

    /** rid -> 命中信息（由 events 反查，正确处理 cid 倒序的事件） */
    private val hits = mutableMapOf<Int, Hit>()

    /**
     * **同名事件分组**：同一个 `text` 的事件算同一个选项
     * （公会管理协会 = 1101/1102/1103/1104；魔物竞技场 = 1105/1109/1113/1117）。
     * UI 的下拉框按这个合并（[EventRuleData]），运行期把条件里的 id 补全成整组。
     */
    private val groups = mutableMapOf<Int, List<Int>>()

    /**
     * ★每个"选项"（= 一张卡）有几种可能的结果：**1 = 没有分支结果**（魔物竞技场/公会都是这种，
     *   随机性在"遇到哪个事件变体"而不是"落到哪个分支"），>1 才是真·概率事件（钓鱼/料理…）。
     * 键 = "事件id:卡片槽位"。
     */
    private val branchCount = mutableMapOf<String, Int>()

    /**
     * ★**有随机结果的事件**（该事件里存在多分支的选项）。
     *   用户口径（2026-11-03）："选项不是问题…随机结果才是问题；没有随机结果的直接不显示后面的部分"
     *   ⇒ 判定"要不要写结果"看**事件**，不看某一张卡。
     */
    private val randomEvents = mutableSetOf<Int>()

    fun load(reader: (String) -> String) {
        raw = null
        names.clear()
        hits.clear()
        groups.clear()
        branchCount.clear()
        randomEvents.clear()
        try {
            val o = JSONObject(reader("event_result.json"))
            raw = o
            o.optJSONObject("effectNames")?.let { n ->
                val ks = n.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    k.toIntOrNull()?.let { names[it] = n.optString(k) }
                }
            }
            val evs = o.optJSONObject("events")
            if (evs != null) {
                // ★同名事件分组（同一 text 的一组 id）
                val byText = mutableMapOf<String, MutableList<Int>>()
                val ks0 = evs.keys()
                while (ks0.hasNext()) {
                    val k = ks0.next()
                    val eid = k.toIntOrNull() ?: continue
                    val text = evs.optJSONObject(k)?.optString("text", "") ?: ""
                    byText.getOrPut(text) { mutableListOf() } += eid
                }
                for (list in byText.values) {
                    for (eid in list) groups[eid] = list.sorted()
                }
                val ks = evs.keys()
                while (ks.hasNext()) {
                    val eid = ks.next().toIntOrNull() ?: continue
                    val cards = evs.optJSONObject(eid.toString())?.optJSONArray("cards") ?: continue
                    for (i in 0 until cards.length()) {
                        val c = cards.optJSONObject(i) ?: continue
                        val slot = c.optInt("slot", i + 1)
                        val cid = c.optInt("choiceId")
                        val brs = c.optJSONArray("branches") ?: continue
                        for (j in 0 until brs.length()) {
                            val rid = brs.optJSONObject(j)?.optInt("rid") ?: continue
                            hits[rid] = Hit(eid, slot, rid - cid * 10, c.optString("desc"))
                        }
                        // ★这个选项（这张卡）有几个可能结果
                        branchCount["$eid:$slot"] = brs.length()
                        // ★这个事件里只要有一张卡是多分支 ⇒ 这个事件"有随机结果"
                        if (brs.length() > 1) randomEvents += eid
                    }
                }
            }
            val n = o.optJSONObject("results")?.length() ?: 0
            AppState.addLog("[事件] 已载入事件结果表：$n 条分支（eventResultId 可解出「事件/卡片/分支/效果」）")
        } catch (e: Exception) {
            AppState.addLog("[事件] 事件结果表缺失（event_result.json）：${e.message} → 只记录原始 eventResultId")
        }
    }

    /**
     * 这个事件属于哪个**合并组**（同名事件合并成一个选项）。
     * 表没载入 / 没有同名事件 → 就它自己一个。
     */
    fun groupOf(eid: Int): List<Int> = groups[eid] ?: listOf(eid)

    /** 这个"选项"（事件 id + 卡片槽位）有几种可能的结果；表里没有 → 0 */
    fun branchesOf(eid: Int, slot: Int): Int = branchCount["$eid:$slot"] ?: 0

    /** 这个**事件**有没有随机结果（任一选项多分支）；表没载入 → false */
    fun evHasRandom(eid: Int): Boolean = eid in randomEvents

    /** 这个事件**所在的合并组**里有没有随机结果（同名事件合并成一条，任一个成员有就算） */
    fun groupHasRandom(eid: Int): Boolean = groupOf(eid).any { it in randomEvents }

    private fun effectsOf(rid: Int): List<String> {
        val arr = raw?.optJSONObject("results")?.optJSONArray(rid.toString()) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONArray(i) ?: continue
            val t = p.optInt(0)
            val v = p.optInt(1)
            val nm = names[t] ?: "效果$t"
            out += when (t) {
                1, 2 -> "$nm=$v"                      // 金币
                7 -> "$nm(${v + 100000})"             // 连锁：下一事件的 rid = 自身 + 100000
                3, 4, 6 -> nm                         // 遗物/印记/战斗格：具体内容看选项描述
                else -> nm
            }
        }
        return out
    }

    /**
     * rid -> "事件1112 卡2 分支1 → 印记 ｜ 随机职能的 随机印记×2"；表里没有 → null。
     *
     * ★2026-11-03 用户（做饭链）：「成功 1 次后选择结束 = **两个**随机印记，失败 = 500 金币」——
     *   库里 effect 只写「印记」这个**种类**，给几个/给多少金币只在**卡片描述**里 ⇒ 描述必须一起打出来，
     *   否则 `→ 印记` 看不出是 1 个还是 2 个，`→ 金币=500` 也容易被当成"什么都没给"。
     */
    fun describe(rid: Int): String? {
        val fx = effectsOf(rid)
        val h = hits[rid]
        if (fx.isEmpty() && h == null) return null
        return (h?.label ?: "事件?") + " → " +
            (if (fx.isEmpty()) "（无效果）" else fx.joinToString("+")) +
            (h?.desc?.takeIf { it.isNotBlank() }?.let { " ｜ $it" } ?: "")
    }

    /** rid -> 命中的事件/卡片/分支/卡片文案；表里没有 → null */
    fun hitOf(rid: Int): Hit? = hits[rid]

    /** 事件页的「各卡可能结果」摘要（用于选项日志）："①金币=1500|金币=2000 ②印记 ③遗物" */
    fun choicesText(eid: Int): String? {
        val cards = raw?.optJSONObject("events")?.optJSONObject(eid.toString())
            ?.optJSONArray("cards") ?: return null
        if (cards.length() == 0) return null
        val marks = "①②③④⑤"
        val parts = mutableListOf<String>()
        for (i in 0 until cards.length()) {
            val c = cards.optJSONObject(i) ?: continue
            val brs = c.optJSONArray("branches") ?: continue
            val outs = mutableListOf<String>()
            for (j in 0 until brs.length()) {
                val rid = brs.optJSONObject(j)?.optInt("rid") ?: continue
                val fx = effectsOf(rid)
                outs += if (fx.isEmpty()) "无" else fx.joinToString("+") { it.substringBefore("(") }
            }
            val slot = c.optInt("slot", i + 1)
            val mk = if (slot in 1..marks.length) marks[slot - 1].toString() else "#$slot"
            parts += mk + outs.distinct().joinToString("|")
        }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }
}
