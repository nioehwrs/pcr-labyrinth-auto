package com.lmj.labyrinthauto.logic

import org.json.JSONArray
import org.json.JSONObject

/**
 * ★凹分模式·条件 2 的**逻辑模式**（最小闭环，2026-11-18 用户拍板）。
 *
 * 与 [EventRetreatRule]（断言模式）的区别：断言是"遇到事件/结果就撤"，一条条件一件事；
 * 逻辑模式把句子统一成 **「如果 条件A（且/或 条件B）则 动作」**，并且
 * ★**只在节点结束时统一触发、按顺序触发，其他时机一律不工作**（用户明确）。
 *
 * 一条规则：
 * ```
 * 条件A [且/或 条件B]  →  动作
 * ```
 *  · 条件原子：① 遇到 [事件族]（且结果是 [结果]）；② 变量 [</>/=/<=/>=] 整数。
 *  · 变量：**8 个固定 token**（[TOKEN_COUNT]，暂显示 T1..T8，名字以后替换）+ 当前层数。
 *  · 动作：**撤退** / **改 token**（+= / -= / =，钳制 [TOKEN_MIN]..[TOKEN_MAX]）。
 *
 * ★求值语义（用户确认）：
 *  1. 按顺序跑；改 token 后**后面的规则立刻看到新值**；
 *  2. 命中「撤退」⇒ 立刻停，**后面的规则不再跑**（本局要走人了）；
 *  3. token 每局重置为 0；每个节点只 fire 一次（去重用调用方的 nodeKey）。
 *
 * ★"事件"条件读的是**本节点收集到的 [EventFact]**：演出页只读 fact（见 MazeLoop.observeEventResult /
 *   settleEventDiff），不在那时判规则；到回地图那一刻才一次性 fire。
 */
object LogicRule {

    const val MAX_RULES = 30
    const val TOKEN_COUNT = 8
    const val TOKEN_MIN = 0
    const val TOKEN_MAX = 99

    /** token 显示名集中在这里，用户给出正式名称后只改这一处 */
    fun tokenName(i: Int): String = "T${i + 1}"

    /** 比较符 */
    enum class Op(val key: String, val cn: String) {
        LT("<", "<"), GT(">", ">"), EQ("=", "="), LE("<=", "≤"), GE(">=", "≥"),
        ;

        fun test(a: Int, b: Int): Boolean = when (this) {
            LT -> a < b
            GT -> a > b
            EQ -> a == b
            LE -> a <= b
            GE -> a >= b
        }

        companion object {
            fun of(key: String?): Op = entries.firstOrNull { it.key == key } ?: EQ
        }
    }

    /** 两个条件怎么连 */
    enum class Combine(val key: String, val cn: String) {
        AND("and", "且"), OR("or", "或"),
        ;

        companion object {
            fun of(key: String?): Combine = entries.firstOrNull { it.key == key } ?: AND
        }
    }

    /** 变量：8 个 token 之一 / 当前层数 */
    sealed class Variable {
        data class Tok(val i: Int) : Variable()
        data object Area : Variable()
    }

    /** 条件原子 */
    sealed class Atom {
        /**
         * 遇到 [eventIds] 里的事件（同名事件合并后可能多个）。
         * [resultId] = 0 ⇒ 无视结果（遇到就成立）；>0 ⇒ 本节点结算命中该结果才算。
         */
        data class EventAtom(val eventIds: List<Int>, val resultId: Int = 0) : Atom()

        /** [v] [op] [n] */
        data class CompareAtom(val v: Variable, val op: Op = Op.EQ, val n: Int = 0) : Atom()
    }

    /**
     * token 改动方式。
     * ★2026-11-21 用户："+= / -= 是编程语言，换成自然一点" ⇒ 显示文案改「加/减/设为」。
     * `key`（add/sub/set）是存档与协议字段，**保持不变**，老规则照常读。
     */
    enum class TokOp(val key: String, val cn: String) {
        ADD("add", "加"), SUB("sub", "减"), SET("set", "设为"),
        ;

        companion object {
            fun of(key: String?): TokOp = entries.firstOrNull { it.key == key } ?: ADD
        }
    }

    /** 动作 */
    sealed class Act {
        data object Retreat : Act()
        data class TokAct(val i: Int, val op: TokOp = TokOp.ADD, val n: Int = 1) : Act() {
            fun desc(): String = "${LogicRule.tokenName(i)} ${op.cn} $n"
        }
    }

    /** 一条规则：[条件A]（[combine] [条件B]）→ [act]；[b] 为 null 时只看条件A */
    data class Rule(
        val a: Atom,
        val combine: Combine = Combine.AND,
        val b: Atom? = null,
        val act: Act = Act.Retreat,
    )

    /** 本节点一次事件结算的事实（由 MazeLoop 采集） */
    data class EventFact(
        val eid: Int,
        val rid: Int,
        val slot: Int,
        /** 事件前后角色数增量；"获得角色<N"型结果要用，读不到传 null */
        val unitGain: Int? = null,
    )

    /** 一次 fire 的上下文 */
    data class Ctx(val area: Int, val events: List<EventFact>)

    /** fire 的结果：命中第几条规则 + 动作 + 给日志的一句话 */
    data class Fired(val index: Int, val act: Act, val log: String)

    /** 规则引擎：token 状态 + 按顺序求值 */
    class Engine(var rules: List<Rule> = emptyList()) {
        val tokens = IntArray(LogicRule.TOKEN_COUNT)

        /** 每局重置：token 全 0 */
        fun reset() {
            tokens.fill(LogicRule.TOKEN_MIN)
        }

        /**
         * 节点结束时统一触发一次：每条规则都判定，改 token 后继续跑（后续规则看到新值）；
         * ★只有「撤退」才停，后面的规则不再跑。返回所有命中的规则（按规则顺序）。
         */
        fun fire(ctx: Ctx): List<Fired> {
            val out = mutableListOf<Fired>()
            for (i in rules.indices) {
                val r = rules[i]
                val okA = eval(r.a, ctx)
                val ok = if (r.b == null) okA else {
                    val okB = eval(r.b, ctx)
                    if (r.combine == Combine.AND) okA && okB else okA || okB
                }
                if (!ok) continue
                when (val act = r.act) {
                    is Act.Retreat -> {
                        out += Fired(i, act, "规则 ${i + 1} 命中 → 撤退")
                        break   // ★撤退 ⇒ 停，后面不再跑
                    }
                    is Act.TokAct -> {
                        applyTok(act)
                        out += Fired(i, act, "规则 ${i + 1} 命中 → ${act.desc()}（${tokensState()}）")
                    }
                }
            }
            return out
        }

        private fun applyTok(act: Act.TokAct) {
            val i = act.i.coerceIn(0, LogicRule.TOKEN_COUNT - 1)
            val v = when (act.op) {
                TokOp.ADD -> tokens[i] + act.n
                TokOp.SUB -> tokens[i] - act.n
                TokOp.SET -> act.n
            }
            tokens[i] = v.coerceIn(LogicRule.TOKEN_MIN, LogicRule.TOKEN_MAX)
        }

        fun token(i: Int): Int = tokens[i.coerceIn(0, LogicRule.TOKEN_COUNT - 1)]

        fun tokensState(): String =
            (0 until LogicRule.TOKEN_COUNT).joinToString(" ") { "${LogicRule.tokenName(it)}=${tokens[it]}" }

        private fun eval(atom: Atom, ctx: Ctx): Boolean = when (atom) {
            is Atom.EventAtom -> evalEvent(atom, ctx.events)
            is Atom.CompareAtom -> {
                val v = when (atom.v) {
                    is Variable.Tok -> token(atom.v.i)
                    Variable.Area -> ctx.area
                }
                atom.op.test(v, atom.n)
            }
        }

        /** 本节点任一事件满足 (事件 id + 可选结果) 即成立 */
        private fun evalEvent(atom: Atom.EventAtom, events: List<EventFact>): Boolean {
            if (atom.eventIds.isEmpty()) return false
            return events.any { f ->
                if (f.eid !in atom.eventIds) false
                else if (atom.resultId <= 0) true
                else atom.resultId in outcomesOf(f)
            }
        }

        companion object {
            /**
             * 一条 fact 命中的结果 id（与 [EventRetreatRule.Tracker] 同口径，但 here 是**节点结束时**
             * 拿最终 rid 判，不需要老虎机的逐步状态：终局 rid 本身就是 Outcome.id）。
             */
            fun outcomesOf(f: EventFact): Set<Int> {
                val rid = f.rid
                if (EventFamily.isUnitRid(rid)) {
                    return if (f.unitGain == null) setOf(rid) else EventFamily.unitOutcomesBelow(f.unitGain)
                }
                return EventFamily.canonical(rid)?.let { setOf(it) } ?: setOf(rid)
            }
        }
    }

    // ────────────────────────── 显示文字（UI/日志共用）──────────────────────────

    fun variableName(v: Variable): String = when (v) {
        is Variable.Tok -> tokenName(v.i)
        Variable.Area -> "当前层数"
    }

    fun atomText(a: Atom): String = when (a) {
        is Atom.EventAtom -> {
            val fam = EventFamily.all.firstOrNull { f -> f.eventIds.containsAll(a.eventIds) }?.name
                ?: (a.eventIds.firstOrNull()?.let { "事件$it" } ?: "（事件）")
            if (a.resultId > 0) "遇到【$fam】且结果是【${EventFamily.outcome(a.resultId)?.name ?: a.resultId}】"
            else "遇到【$fam】"
        }
        is Atom.CompareAtom -> "${variableName(a.v)} ${a.op.cn} ${a.n}"
    }

    fun actText(a: Act): String = when (a) {
        is Act.Retreat -> "撤退"
        is Act.TokAct -> a.desc()
    }

    /** 整条规则一行，给规则列表预览 */
    fun ruleText(r: Rule): String = buildString {
        append("如果 ").append(atomText(r.a))
        if (r.b != null) append(" ").append(r.combine.cn).append(" ").append(atomText(r.b))
        append(" → ").append(actText(r.act))
    }

    // ────────────────────────── 编解码（SharedPreferences 一段 JSON）──────────────────────────

    /**
     * 形如 `[{"a":{…},"l":"and","b":{…},"x":{…}},…]`。字段名压到最短。
     *  · 原子 `t`：`e`=事件（"e" 事件id数组 / "r" 结果id）；`c`=比较（"v" 变量 / "o" 比较符 / "n" 值）
     *  · 变量 "v"：`t0`..`t7` = token 下标，`a` = 层数
     *  · 动作 "x"：`{"t":"ret"}` 撤退 / `{"t":"tk","i":0,"p":"add","n":1}` 改 token
     */
    fun encode(rules: List<Rule>): String {
        val arr = JSONArray()
        for (r in rules.take(MAX_RULES)) {
            val o = JSONObject().put("a", encodeAtom(r.a)).put("l", r.combine.key).put("x", encodeAct(r.act))
            r.b?.let { o.put("b", encodeAtom(it)) }
            arr.put(o)
        }
        return arr.toString()
    }

    fun decode(raw: String?): List<Rule> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Rule>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val a = decodeAtom(o.optJSONObject("a")) ?: continue
                val b = decodeAtom(o.optJSONObject("b"))
                val act = decodeAct(o.optJSONObject("x")) ?: continue
                out += Rule(a, Combine.of(o.optString("l", "and")), b, act)
            }
            out.take(MAX_RULES)
        } catch (_: Exception) {
            // 脏数据 ⇒ 当"没有规则"：宁可不判，也别因为一段坏 JSON 每局都撤
            emptyList()
        }
    }

    private fun encodeAtom(a: Atom): JSONObject = when (a) {
        is Atom.EventAtom -> {
            val es = JSONArray()
            a.eventIds.forEach { es.put(it) }
            JSONObject().put("t", "e").put("e", es).put("r", a.resultId)
        }
        is Atom.CompareAtom -> JSONObject()
            .put("t", "c")
            .put("v", when (val v = a.v) {
                is Variable.Tok -> "t${v.i}"
                Variable.Area -> "a"
            })
            .put("o", a.op.key)
            .put("n", a.n)
    }

    private fun decodeAtom(o: JSONObject?): Atom? {
        if (o == null) return null
        return when (o.optString("t")) {
            "e" -> {
                val es = o.optJSONArray("e")
                val ids = mutableListOf<Int>()
                if (es != null) for (k in 0 until es.length()) (es.opt(k) as? Number)?.toInt()?.let { ids += it }
                if (ids.isEmpty()) null else Atom.EventAtom(ids, o.optInt("r", 0))
            }
            "c" -> {
                val v = when (val s = o.optString("v")) {
                    "a" -> Variable.Area
                    else -> {
                        val i = s.removePrefix("t").toIntOrNull() ?: return null
                        if (i !in 0 until LogicRule.TOKEN_COUNT) return null
                        Variable.Tok(i)
                    }
                }
                Atom.CompareAtom(v, Op.of(o.optString("o")), o.optInt("n", 0))
            }
            else -> null
        }
    }

    private fun encodeAct(a: Act): JSONObject = when (a) {
        is Act.Retreat -> JSONObject().put("t", "ret")
        is Act.TokAct -> JSONObject().put("t", "tk").put("i", a.i).put("p", a.op.key).put("n", a.n)
    }

    private fun decodeAct(o: JSONObject?): Act? {
        if (o == null) return null
        return when (o.optString("t")) {
            "ret" -> Act.Retreat
            "tk" -> {
                val i = o.optInt("i", -1)
                if (i !in 0 until LogicRule.TOKEN_COUNT) null
                else Act.TokAct(i, TokOp.of(o.optString("p")), o.optInt("n", 1))
            }
            else -> null
        }
    }
}
