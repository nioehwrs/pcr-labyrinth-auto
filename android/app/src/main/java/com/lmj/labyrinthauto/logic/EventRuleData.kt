package com.lmj.labyrinthauto.logic

import com.lmj.labyrinthauto.core.AppState
import org.json.JSONArray
import org.json.JSONObject

/**
 * 凹分模式「条件 2」设置页要用的**事件 / 结果候选**（数据源 = assets 里的 `event_result.json`）。
 *
 * 只给 UI 用：把「事件 → 几张卡 → 每张卡的几个结算分支(rid)」摊平成两个下拉框的选项，
 * 并顺带把 rid 翻成「金币=500 / 印记 / 连锁下一事件…」——这样用户选的是**看得懂的结果**，
 * 存下来的是**游戏自己的结算码**（[EventRetreatRule.Rule.results] 里那两个 rid）。
 *
 * ⚠运行时判定**不读这个对象**（[MazeLoop] 用的是 [EventResult]）；两者读的是同一份 json，
 *   这里只是另一条"给人看"的读取路径 —— 别在这里改效果码语义。
 */
object EventRuleData {

    /**
     * 一个**随机结果** = 选定某个选项后，可能落到的分支（rid）。
     *
     * ★术语（用户 2026-11-03 纠正）：**选项**是玩家在屏幕上选的那张卡；
     *   **结果**是选完这张卡之后才产生的随机分支。
     *   例：魔物竞技场 → 选项「一试身手吧！」→ 结果「战斗格+金币=300」（**只有一个** ⇒ 没有分支结果）。
     */
    data class Result(
        val rid: Int,
        val effect: String,
        val desc: String,
    ) {
        /** 下拉项主标题，例：`金币=500` */
        val label: String get() = effect
        /** 下拉项副标题，例：`50％概率进入下一个事件` */
        val sub: String get() = desc
    }

    /**
     * 一个**选项**（一张卡）。
     *
     * @param results   该选项可能的随机结果（合并组里 = 各事件的结果合起来）
     * @param branching 这个选项**真的有分支结果**吗？
     *   —— 只要有任一事件下它是多分支才算（用户："魔物竞技场可以认为没有分支结果"：
     *   那边每个选项在每个事件下都只有一个结果，随机性在"遇到哪个事件"，不在"落到哪个分支"）。
     */
    data class Option(
        val slot: Int,
        val comment: String,
        val desc: String,
        val results: List<Result>,
        val branching: Boolean,
    ) {
        /** 下拉项主标题，例：`卡1 一试身手吧！`；合并组里文案不一致时只写 `卡1` */
        val label: String get() = if (comment.isBlank()) "卡$slot" else "卡$slot $comment"
        /** 下拉项副标题，例：`向战斗格子（普通难度）的 敌人发起挑战 ＋阿尔法金币×300` */
        val sub: String get() = desc
    }

    /**
     * 一个**事件**（同名事件已合并成一个选项）。
     *
     * ★同名事件要合并（用户 2026-11-03）：同一个事件文案会对应多个 id
     *   （「这里是公会管理协会…」= 1101/1102/1103/1104；「发现了魔物们聚集的斗技场！」= 1105/1109/1113/1117），
     *   下拉框里出现四个一模一样的条目没法用 ⇒ 合成一条，**这一条同时指向多个事件 id**。
     */
    data class Ev(
        val ids: List<Int>,
        val text: String,
        val short: String,
        val options: List<Option>,
    ) {
        /** 下拉项主标题：合并组**只写短名**（用户："短名就叫「公会管理协会」「魔物竞技场」"），单个事件用 `#id 文案` */
        val label: String
            get() = if (ids.size > 1) short else "#${ids.first()} $text"

        /** 下拉项副标题：合并组写「事件 #a~#b ｜ 原文」，单个事件副标题空着 */
        val sub: String
            get() = if (ids.size > 1) "事件 #${ids.first()}~#${ids.last()} ｜ $text" else ""

        /** 某个槽位的选项 */
        fun option(slot: Int): Option? = options.firstOrNull { it.slot == slot }

        /**
         * ★这个**事件**有没有**随机结果**？（用户 2026-11-03："选项不是问题，因为选项是我们自己选的…
         *   随机结果才是问题。没有随机结果的直接不显示后面的部分就行了"）
         *
         * ★判据在**事件**上、不在某张卡上：只要这个事件里有任一选项是多分支，选完就会随机落到不同
         *   结果，条件才需要写结果；否则（魔物竞技场 / 公会管理协会：每个选项都只有一个结果）整个
         *   「且结果是…」都不出现，条件退化成"遇到这个事件就撤"。
         *   —— 不能拿"当前选中的那张卡有没有分支"当判据：那会让"没选选项"时看不到结果层。
         */
        val hasRandom: Boolean get() = options.any { it.branching }

        /** 可以选的结果（跨选项；[slot] ≠ 0 时只留那张卡的），带上它属于哪个选项 */
        fun resultsFor(slot: Int): List<Pair<Option, Result>> =
            options.filter { slot == 0 || it.slot == slot }
                .flatMap { o -> o.results.map { r -> o to r } }
    }

    /**
     * 解析 `event_result.json` → 事件列表（按 id 升序；解析失败 → 空表，UI 自行降级）。
     *
     * 与 [EventPick.loadEntries] 一样是 source-agnostic 的：app 传 assets 读取器，单测可传文件。
     */
    fun load(reader: (String) -> String): List<Ev> {
        val out = mutableListOf<Ev>()
        try {
            val o = JSONObject(reader("event_result.json"))
            // 效果码 → 中文（表里自带；缺失时用兜底名）
            val names = mutableMapOf<Int, String>()
            o.optJSONObject("effectNames")?.let { n ->
                val ks = n.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    k.toIntOrNull()?.let { names[it] = n.optString(k) }
                }
            }
            val results = o.optJSONObject("results")
            val evs = o.optJSONObject("events") ?: return emptyList()
            // ★先按 id 收齐，再按**文案**合并同名事件（公会管理协会 / 魔物竞技场 …）
            val byText = LinkedHashMap<String, MutableList<Pair<Int, JSONObject>>>()
            val keys = evs.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val eid = k.toIntOrNull() ?: continue
                val e = evs.optJSONObject(k) ?: continue
                val text = e.optString("text", "")
                byText.getOrPut(text) { mutableListOf() } += eid to e
            }
            for ((text, group) in byText) {
                val ids = group.map { it.first }.sorted()
                // 按槽位合并选项（合并组里同一个槽位 = 同一个选项名，结果合起来）
                val slotMap = LinkedHashMap<Int, Option>()
                // ★同一个槽位在组内各事件的文案（合并组里可能各不相同 ⇒ 要单独对账）
                val slotComments = LinkedHashMap<Int, MutableList<Pair<Int, String>>>()
                val slotDescs = LinkedHashMap<Int, MutableList<Pair<Int, String>>>()
                for ((eid, e) in group) {
                    val cards = e.optJSONArray("cards") ?: continue
                    for (i in 0 until cards.length()) {
                        val c = cards.optJSONObject(i) ?: continue
                        val slot = c.optInt("slot", i + 1)
                        val comment = c.optString("comment", "")
                        val desc = c.optString("desc", "")
                        slotComments.getOrPut(slot) { mutableListOf() } += eid to comment
                        slotDescs.getOrPut(slot) { mutableListOf() } += eid to desc
                        val brs = c.optJSONArray("branches") ?: continue
                        val rs = mutableListOf<Result>()
                        for (j in 0 until brs.length()) {
                            val b = brs.optJSONObject(j) ?: continue
                            val rid = b.optInt("rid", 0)
                            if (rid <= 0) continue
                            val fx = b.optJSONArray("effects")
                                ?: results?.optJSONArray(rid.toString())
                            rs += Result(rid, effectText(fx, names), desc)
                        }
                        val prev = slotMap[slot]
                        slotMap[slot] = Option(
                            slot = slot,
                            comment = prev?.comment ?: comment,
                            desc = prev?.desc ?: desc,
                            results = (prev?.results ?: emptyList()) + rs,
                            // ★"有分支结果" = 有任一事件下这个选项是多分支；
                            //   魔物竞技场/公会：每个事件下都只有 1 个 ⇒ 没有分支结果（用户口径）
                            branching = (prev?.branching ?: false) || rs.size > 1,
                        )
                    }
                }
                // ★合并组里**同一个卡位的文案可能各不相同**（公会管理协会：卡1 在 1101 是"想要坦克型"、
                //   在 1104 却是"想要攻击型"）⇒ 标题不能只抄第一个事件的文案，否则会骗人。
                //   文案一致（魔物竞技场：卡1 都是"一试身手吧！"）就照常用；不一致就只写"卡N"，
                //   把各事件的文案列到副标题里。
                val opts = slotMap.entries.map { (slot, opt) ->
                    val cm = slotComments[slot].orEmpty().filter { it.second.isNotBlank() }
                    val cs = cm.map { it.second }.distinct()
                    val ds = slotDescs[slot].orEmpty().filter { it.second.isNotBlank() }
                            .map { it.second }.distinct()
                    when {
                        cs.size > 1 -> opt.copy(
                            comment = "",
                            desc = cm.joinToString("｜") { "${it.first}：${it.second}" },
                        )
                        ds.size > 1 -> opt.copy(
                            comment = cs.firstOrNull() ?: opt.comment,
                            desc = slotDescs[slot].orEmpty()
                                .filter { it.second.isNotBlank() }
                                .joinToString("｜") { "${it.first}：${it.second}" },
                        )
                        else -> opt.copy(
                            comment = cs.firstOrNull() ?: opt.comment,
                            desc = ds.firstOrNull() ?: opt.desc,
                        )
                    }
                }
                out += Ev(ids, text, shortName(text), opts.sortedBy { it.slot })            }
            out.sortBy { it.ids.first() }
        } catch (e: Exception) {
            AppState.addLog("[事件] 条件 2 读取事件候选失败：${e.message}")
        }
        return out
    }

    /**
     * 合并组的短名（用户就叫「公会管理协会」「魔物竞技场」）。
     * 没登记的文案就用原文 —— 不猜、不改写游戏里的说法。
     */
    private fun shortName(text: String): String = when {
        text.startsWith("这里是公会管理协会") -> "公会管理协会"
        text.startsWith("发现了魔物们聚集的斗技场") -> "魔物竞技场"
        else -> text
    }

    /**
     * 效果数组 → 一行中文。与 [EventResult] 的写法保持一致：
     * 金币带数值、连锁事件只写"连锁事件"（下一事件 rid = 自身 + 100000）、其它只写种类。
     */
    private fun effectText(arr: JSONArray?, names: Map<Int, String>): String {
        if (arr == null || arr.length() == 0) return "无效果"
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONArray(i) ?: continue
            val t = p.optInt(0)
            val v = p.optInt(1)
            val nm = names[t] ?: "效果$t"
            out += when (t) {
                1, 2 -> "$nm=$v"
                7 -> nm
                8 -> "无效果"
                else -> nm
            }
        }
        return out.joinToString("+").ifEmpty { "无效果" }
    }
}
