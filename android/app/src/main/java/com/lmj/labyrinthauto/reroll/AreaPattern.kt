package com.lmj.labyrinthauto.reroll

import com.lmj.labyrinthauto.net.labyrinth.LabyrinthMapNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★刷开局「**自定义模式**」（用户 2026-11-03）：不再算分数价值，而是**按节点类型序列**判定 ——
 *   「5 个区域都要能找到一条**满足自选节点顺序**的路线」才算合格。
 *
 * 例（区域 1，6 列）：
 * ```
 *   起始 → 普通战 → [印记] → 普通战 → [事件] → 遗物
 * ```
 *  · 池里只有 1 种类型的列 ⇒ 固定，界面不给改（起始/遗物/商店/首领…）；
 *  · 池里有多种 ⇒ 下拉可选（印记/事件、遗物/商店…）；
 *  · ★池里同时有普通战(2) 和 EX 战(3) ⇒ **固定为 EX 战**、不可切换
 *    （用户："如果选项中包含普通战和EX战，则固定为EX战"）。
 *
 * [COL_POOL] 取自实测地图（`assets/labyrinth_state.json`，难度 5）：
 *   区域1 6 列 / 区域2、3 各 7 列 / 区域4、5 各 8 列。
 */
object AreaPattern {

    /** 0 = 不限 */
    const val ANY = 0

    const val T_START = 1
    const val T_NORMAL = 2
    const val T_EX = 3
    const val T_TICKET = 4
    const val T_EVENT = 5
    const val T_RELIC = 6
    const val T_SHOP = 7
    const val T_BOSS = 8

    /** 类型 → 界面上的名字（4 = 门票格，用户 2026-11-03：就叫「门票」，别叫印记） */
    fun name(type: Int): String = when (type) {
        ANY -> "不限"
        T_START -> "起始"
        T_NORMAL -> "普通战"
        T_EX -> "EX战"
        T_TICKET -> "门票"
        T_EVENT -> "事件"
        T_RELIC -> "遗物"
        T_SHOP -> "商店"
        T_BOSS -> "首领"
        else -> "未知$type"
    }

    /** ★每个区域**每一列**可能出现的类型池（实测地图；列序 = 从起点到终点） */
    val COL_POOL: Map<Int, List<List<Int>>> = linkedMapOf(
        1 to listOf(listOf(T_START), listOf(T_NORMAL), listOf(T_TICKET, T_EVENT), listOf(T_NORMAL), listOf(T_TICKET, T_EVENT), listOf(T_RELIC)),
        2 to listOf(listOf(T_START), listOf(T_TICKET, T_EVENT), listOf(T_NORMAL), listOf(T_RELIC, T_SHOP), listOf(T_NORMAL, T_EX), listOf(T_TICKET, T_EVENT), listOf(T_RELIC)),
        3 to listOf(listOf(T_START), listOf(T_NORMAL), listOf(T_EVENT, T_RELIC), listOf(T_TICKET, T_EVENT), listOf(T_NORMAL, T_EX), listOf(T_SHOP), listOf(T_BOSS)),
        // ★2026-11-12（实测 54 条路线）：区域 4 第 5、6 列「**有且仅有一个 EX**」，但 EX 在哪一列不固定，
        //   （6/7 次 405=EX/406=普通，1/7 次 405=普通/406=EX）⇒ 第 5 列池子也必须含 EX。
        //   UI 上两列都显示「EX战」且不可改（含普通+EX ⇒ isFixed），判定按**组约束**见 [matchArea]。
        4 to listOf(listOf(T_START), listOf(T_TICKET, T_RELIC), listOf(T_NORMAL, T_EX), listOf(T_EVENT), listOf(T_NORMAL, T_EX), listOf(T_NORMAL, T_EX), listOf(T_TICKET, T_RELIC), listOf(T_SHOP)),
        5 to listOf(listOf(T_START), listOf(T_NORMAL), listOf(T_EVENT, T_RELIC), listOf(T_NORMAL, T_EX), listOf(T_RELIC), listOf(T_NORMAL, T_EX), listOf(T_SHOP), listOf(T_BOSS)),
    )

    /**
     * ★2026-11-12（实测）：**区域 4 的第 5、6 列合计只有一个 EX 战**（EX 可能在任意一列），
     *   所以这两列不能各自要求 EX，而是「组内至少走一个 EX」。
     *
     * 注意与**单列必须 EX** 的区别（区域 2/3/5 也有含 EX 的列，那些是单列精确匹配、必须 EX）；
     * 只有这里登记的区域才走「组约束」。
     */
    private val EX_GROUP: Map<Int, IntRange> = mapOf(4 to 5..6)

    /** 这一列能选什么；池里只有一项 ⇒ 固定 */
    fun poolOf(area: Int, col: Int): List<Int> = COL_POOL[area]?.getOrNull(col - 1).orEmpty()

    /** 这一列界面上是否**固定不可改**：池里只有一种，或池里同时有普通战/EX战（固定取 EX） */
    fun isFixed(area: Int, col: Int): Boolean {
        val p = poolOf(area, col)
        return p.size <= 1 || (T_NORMAL in p && T_EX in p)
    }

    /**
     * 默认选择：★池里有普通战 + EX 战 ⇒ 固定 EX 战（用户口径）；
     * 否则取池里第一项（= 实测那局出现的值，同时也是"最普通"的那个）。
     */
    fun defaultChoice(pool: List<Int>): Int = when {
        pool.isEmpty() -> ANY
        T_NORMAL in pool && T_EX in pool -> T_EX
        else -> pool.first()
    }

    /** 一个区域的期望：[cols] 下标 i = 第 i+1 列要的类型（0 = 不限） */
    data class AreaPlan(val area: Int, val cols: List<Int>) {
        fun want(col: Int): Int = cols.getOrNull(col - 1) ?: ANY
        /** 这一列是否固定（池里没得选，或带 EX 的那条规则） */
        fun locked(col: Int): Boolean = isFixed(area, col)
    }

    /** 默认计划（每区按池子取默认值） */
    fun defaults(): List<AreaPlan> = COL_POOL.entries.sortedBy { it.key }.map { (area, cols) ->
        AreaPlan(area, cols.map { defaultChoice(it) })
    }

    /** 存成 JSON：`{"1":[1,2,4,2,5,6],…}` */
    fun encode(plans: List<AreaPlan>): String {
        val o = JSONObject()
        for (p in plans.sortedBy { it.area }) {
            val a = JSONArray()
            p.cols.forEach { a.put(it) }
            o.put(p.area.toString(), a)
        }
        return o.toString()
    }

    /** 读回；缺的列/缺的区域用默认补齐（配置永远和 [COL_POOL] 对齐） */
    fun decode(raw: String?): List<AreaPlan> {
        val base = defaults().associateBy { it.area }.toMutableMap()
        if (raw.isNullOrBlank()) return base.values.sortedBy { it.area }
        runCatching {
            val o = JSONObject(raw)
            for ((area, def) in base.toList()) {
                val a = o.optJSONArray(area.toString()) ?: continue
                val cols = def.cols.toMutableList()
                for (i in cols.indices) {
                    val v = a.optInt(i, cols[i])
                    cols[i] = v
                }
                base[area] = def.copy(cols = cols)
            }
        }
        return base.values.sortedBy { it.area }
    }

    /**
     * 匹配结果：成功的区域 → 路线；[ok] = 5 个区域全部找到。
     * [failAt] = 没匹配上的区域 → **卡在第几列**（1 起；诊断/悬浮窗用）。
     */
    data class MatchResult(
        val routes: Map<Int, List<Int>>,
        val failAt: Map<Int, Int> = emptyMap(),
    ) {
        val ok: Boolean get() = routes.size >= 5
        val missing: List<Int> get() = (1..5).filter { it !in routes }
    }

    /**
     * ★核心判定：**5 个区域都要找到一条满足自选节点顺序的路线**。
     *
     * @return 命中的区域与路线（没命中的区不在 [MatchResult.routes] 里，其卡住列见 [MatchResult.failAt]）
     */
    fun match(nodes: List<LabyrinthMapNode>, plans: List<AreaPlan>): MatchResult {
        val out = LinkedHashMap<Int, List<Int>>()
        val failAt = LinkedHashMap<Int, Int>()
        if (nodes.isEmpty()) {
            for (a in 1..5) failAt[a] = 1
            return MatchResult(out, failAt)
        }
        val byArea = nodes.groupBy { it.area }
        for (area in 1..5) {
            val list = byArea[area]
            if (list == null) {
                failAt[area] = 1
                continue
            }
            val plan = plans.firstOrNull { it.area == area }
            val (path, badCol) = matchArea(list, plan)
            if (path == null) failAt[area] = badCol else out[area] = path
        }
        return MatchResult(out, failAt)
    }

    /**
     * 单个区域：沿 next 走一条"每列类型都对得上"的路。
     *
     * @return (路径 or null, 卡住的列号)。失败时返回**能走到的最远列 + 1**，方便说清"卡在第几列"。
     */
    private fun matchArea(nodes: List<LabyrinthMapNode>, plan: AreaPlan?): Pair<List<Int>?, Int> {
        if (nodes.isEmpty()) return null to 1
        val byId = HashMap<Int, LabyrinthMapNode>(nodes.size)
        for (n in nodes) byId[n.blockId.toInt()] = n
        val lastCol = nodes.maxOf { it.column }
        // ★2026-11-12（用户："区域4 第5、6列有且仅有 1 个 EX，必须走 EX，但 EX 在哪列不重要"）：
        //   两列类型都放宽为 NORMAL/EX，但整条路径**必须走过至少一个 EX**（走到最后一列时校验）。
        //   原来按单列精确匹配 ⇒ 405 是 EX 的局（6/7）会被判失败。
        // ★区域 4 第 5/6 列走**组约束**（见 [EX_GROUP]）；其余区域（含区域 3 的第 5 列）
        //   仍是单列精确匹配，`want = EX` 就**必须 EX**，不会被放宽。
        val exCols: IntRange = plan?.let { EX_GROUP[it.area] } ?: IntRange.EMPTY
        fun ok(n: LabyrinthMapNode): Boolean {
            val w = plan?.want(n.column) ?: ANY
            if (w == ANY) return true
            if (n.column in exCols) {
                return n.blockType == T_NORMAL || n.blockType == T_EX
            }
            return n.blockType == w
        }
        var furthest = 0                       // 这一区能合法走到的最远列
        // 记忆化： (blockId, 是否已走过 EX) → 从它出发能不能走到最后（null = 走不通）
        val memo = HashMap<Int, List<Int>?>()
        fun dfs(n: LabyrinthMapNode, sawEx: Boolean): List<Int>? {
            val id = n.blockId.toInt()
            if (n.column > furthest) furthest = n.column
            val hasEx = sawEx || (n.column in exCols && n.blockType == T_EX)
            if (n.column >= lastCol) {
                if (!exCols.isEmpty() && !hasEx) return null   // 组内没走到 EX ⇒ 这条路线不合格
                return listOf(id)
            }
            val key = id * 2 + (if (hasEx) 1 else 0)
            if (memo.containsKey(key)) return memo[key]
            memo[key] = null            // 先占位，防环
            for (nid in n.nextBlockIds) {
                val m = byId[nid.toInt()] ?: continue
                if (m.column <= n.column) continue
                if (!ok(m)) continue
                val p = dfs(m, hasEx) ?: continue
                val res = ArrayList<Int>(p.size + 1)
                res += id
                res += p
                memo[key] = res
                return res
            }
            return null
        }
        // 起点必须是第 1 列且类型对得上
        for (s in nodes.filter { it.column == 1 && ok(it) }.sortedBy { it.row }) {
            dfs(s, false)?.let { return it to 0 }
        }
        return null to (furthest + 1).coerceAtMost(lastCol)
    }
}
