package com.lmj.labyrinthauto.logic

import org.json.JSONArray
import org.json.JSONObject

/**
 * ★凹分模式 **条件 2**（2026-11-03 用户需求）：按「遇到的事件 + 结算结果」判要不要撤退。
 *
 * 规则条数不定、用户自由增减（上限 [MAX_CONDS]），两种模式（[Mode]）：
 *  · **断言模式**（[Mode.ASSERT]，本轮实现）：一条条**条件**各自独立，**任意一条**被满足就撤退；
 *  · **逻辑模式**（[Mode.LOGIC]）：UI 已能选，但**暂不生效**（用户："先做断言模式"）。
 *
 * ★**只有一层**：条件 2 底下就是一张条件列表（条件 1、条件 2、…），不再有"断言"那一层
 *   （用户："这两层是一样的，不要断言 123 这样分层，只保留条件层"）。
 *
 * ★一条条件的形状（用户原话："当遇到 xxx 时，且结果是 yyy 时"）：
 * ```
 * 条件 1   当遇到事件是 [#2315 料理完成了！… ▾] 时，且结果是 [卡1 … → 印记 ▾] 时
 * 条件 2   当遇到事件是 [#1104 这里是公会管理协会！… ▾] 时，且结果是 [卡2 … ▾] 时
 * ＋ 增加条件                                    3/30
 * ```
 *   每条条件 = **一个事件下拉 + 一个结果下拉**（结果只有一条）。
 *
 * ★条件之间是**「或」**（断言模式）：**任意一条**条件被满足就撤退 ——
 *   "只要遇到了对应的事件（并满足选中的那个结果）"即可。
 *
 * ★为什么"结果"记的是 `eventResultId`（rid）而不是文案：rid 是游戏自己的结算码
 *   （`LabyrinthStatus+0x40` = choice_id×10 + 分支号，见 [EventResult]），
 *   运行时直接拿它比对，不用读屏猜文案；UI 侧把它翻成「卡2 这样应该就足够了 → 印记」给人看。
 */
object EventRetreatRule {

    /** 条件条数上限（用户：暂定 30） */
    const val MAX_CONDS = 30

    /** 条件 2 的模式（下拉框单选：无 / 断言模式 / 逻辑模式） */
    enum class Mode(val key: String, val cn: String) {
        NONE("none", "无"),
        ASSERT("assert", "断言模式"),
        LOGIC("logic", "逻辑模式"),
        ;

        companion object {
            /** 存的是 [key]（老版本/脏数据 → [NONE]） */
            fun of(key: String?): Mode = entries.firstOrNull { it.key == key } ?: NONE
        }
    }

    /**
     * 一个条件 = 「当遇到事件 X 时（且结果是 Y 时），自动撤退」。
     *
     * @param eventIds 事件 id 列表（**同名事件合并后可能不止一个**：公会管理协会 = 1101~1104）
     * @param slot **已退役**（用户 2026-11-03："选项是我们自己选的，不是问题"）：
     *   选项不进句子、也不参与判定（rid 自带卡号），归一化后恒为 0；保留字段只为兼容老数据。
     * @param resultId `eventResultId`；0 = 还没选结果
     * @param useResult **是否要求匹配某个随机结果**：
     *   · `false` = 无视随机结果（遇到这个事件就满足）—— 界面上用【−】切到这种；
     *   · `true`  = 仅在落到选中的那个随机结果时才算满足（用【+】切回来）。
     *     这种状态下还没选结果 ⇒ 条件**未配好**、不会触发
     *     （不能退化成"无视结果"，否则【−】/【+】两个按钮就没有区别了）。
     */
    data class Cond(
        val eventIds: List<Int> = emptyList(),
        val slot: Int = 0,
        val resultId: Int = 0,
        val useResult: Boolean = false,
    ) {
        /** 事件选过了才算配好了 */
        val valid: Boolean get() = eventIds.isNotEmpty() && eventIds.all { it > 0 }

        /** 可以生效：事件选了，且（不用结果 or 结果也选了） */
        val ready: Boolean get() = valid && (!useResult || resultId > 0)

        /** 这次结算 (事件 id, 选项槽位, 结果 rid) 满不满足本条件 */
        fun satisfiedBy(eid: Int, slot: Int, rid: Int?): Boolean {
            if (!ready || eid !in eventIds) return false
            if (this.slot != 0 && slot != this.slot) return false   // 选了某个选项就必须是对那张卡
            if (!useResult) return true                              // 无视随机结果
            return rid != null && rid == resultId
        }
    }

    /**
     * ★运行期：喂每一次事件结算，记住"哪些条件被满足过"，并回答"该不该撤"。
     *
     * ★条件之间是**「或」**：**任意一个**条件被满足 ⇒ 撤退
     *   （`done` 只是为了日志里不重复报同一条条件）。
     */
    class Tracker(var conds: List<Cond> = emptyList()) {
        private val done = HashSet<Int>()

        /** 老虎机的"看过程"判定状态（只在 [EventFamily] 的老虎机族里有意义） */
        private val slotMachine = EventFamily.SlotMachine()

        /**
         * 喂一次结算；返回**新满足**的条件下标。
         *
         * ★结果比对走 [EventFamily] 的**族口径**：一条 rid 可能代表"遗物<2件"，
         *   也可能要靠前几步（老虎机的成功 1/2/3 次）才能定 ⇒ 先换成结果 id 再比。
         *
         * @param unitGain 事件前后**角色数增量**（读不到传 null）：只有"获得角色"类结果要用，
         *   已有本体纯时实测增量为 0 ⇒ 不能算"获得了角色"。
         */
        fun feed(eid: Int, slot: Int, rid: Int?, unitGain: Int? = null): List<Int> {
            if (rid == null) return emptyList()
            val outs = outcomesOf(rid, unitGain)
            val newly = mutableListOf<Int>()
            for (i in conds.indices) {
                if (i in done) continue
                val c = conds[i]
                if (!c.ready || eid !in c.eventIds) continue
                if (c.slot != 0 && slot != c.slot) continue   // 兼容老数据里残留的卡位限制
                if (c.useResult && c.resultId !in outs) continue
                done.add(i)
                newly += i
            }
            return newly
        }

        /**
         * 这条 rid 命中的**结果 id**。
         *  · "角色差 < N"型（活动会场的获得角色）⇒ **完全看 du**，三档不互斥；
         *  · 在事件族表里能找到 ⇒ 换成族的结果 id（同一结果的多个 rid 归一）；
         *  · 老虎机那几步 ⇒ 由 [EventFamily.SlotMachine] 结合过程定，**不**退回 rid 本身
         *    （否则"选继续/收手"也会被当成结论）；
         *  · 完全没登记的事件 ⇒ 就当它自己是结果 id（老行为，不影响其它迷宫事件）。
         */
        private fun outcomesOf(rid: Int, unitGain: Int?): Set<Int> {
            if (EventFamily.isUnitRid(rid)) {
                // 读不到角色数 ⇒ 退化成"按 rid 命中它自己那条"（宁可少撤，也别卡死）
                return if (unitGain == null) setOf(rid) else EventFamily.unitOutcomesBelow(unitGain)
            }
            val hit = EventFamily.RidJudge.direct(rid) + slotMachine.feed(rid)
            if (hit.isNotEmpty()) return hit
            // 登记过却不成立（序列对不上）⇒ 明确不命中，别退回 rid 本身
            val registered = EventFamily.canonical(rid) != null || EventFamily.isSeqRid(rid)
            return if (registered) emptySet() else setOf(rid)
        }

        /** 换局时清掉"看过程"的状态（老虎机） */
        fun resetProcess() = slotMachine.reset()

        /** 命中的是第几条条件（**任意一条**满足就算；没有 → -1） */
        fun hitIndex(): Int {
            for (i in conds.indices) if (i in done) return i
            return -1
        }

        /** 已满足的条件数 / 总条件数（只用于日志） */
        fun progress(): Pair<Int, Int> = done.size to conds.size
    }

    /**
     * 存成一份 JSON 数组：`[{"e":[1101,1102],"r":110111,"m":1},…]`
     * （字段名压到 1 个字母：SharedPreferences 里字符串越短越好，含义看这里即可）
     *  · `e` 事件 id **列表**（同名事件合并后可能多个）
     *  · `r` 结果 id
     *  · `m` 1 = 要求匹配结果（【+】状态）、0 = 无视随机结果（【−】状态）
     */
    fun encode(conds: List<Cond>): String {
        val arr = JSONArray()
        for (c in conds.take(MAX_CONDS)) {
            val e = JSONArray()
            c.eventIds.forEach { e.put(it) }
            arr.put(
                JSONObject()
                    .put("e", e)
                    .put("s", c.slot)
                    .put("r", c.resultId)
                    .put("m", if (c.useResult) 1 else 0),
            )
        }
        return arr.toString()
    }

    /** 读回条件；读不到/解析失败 → 空表（等于"没有条件"，不拦任何局） */
    fun decode(raw: String?): List<Cond> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Cond>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // ★兼容旧格式（2026-11-03 上午那版"断言"分组）：{"c":[{"e":..,"r":..},…]}
                //   → 拍平成一串条件（用户当时配的条件不能因为改了结构就丢）。
                val old = o.optJSONArray("c")
                if (old != null) {
                    for (k in 0 until old.length()) {
                        val c = old.optJSONObject(k) ?: continue
                        out += decodeCond(c)
                    }
                    continue
                }
                out += decodeCond(o)
            }
        } catch (_: Exception) {
            // 坏数据一律当"没有条件"：宁可这局照打，也别因为一段脏 JSON 把每局都撤掉
        }
        return out.take(MAX_CONDS)
    }

    /** 单条条件的读取（兼容 `e` 是数字的旧写法；旧写法里"没选结果"= 无视结果） */
    private fun decodeCond(o: JSONObject): Cond {
        val ids = mutableListOf<Int>()
        when (val e = o.opt("e")) {
            is JSONArray -> for (k in 0 until e.length()) (e.opt(k) as? Number)?.toInt()?.let { ids += it }
            is Number -> ids += e.toInt()
        }
        val r = o.optInt("r", 0)
        val m = o.optInt("m", -1)
        return Cond(
            eventIds = ids,
            slot = o.optInt("s", 0),
            resultId = r,
            // 新格式有 m 用 m；旧格式没 m ⇒ 按"选了结果就要匹配、没选就无视"还原
            useResult = if (m >= 0) m == 1 else r > 0,
        )
    }
}
