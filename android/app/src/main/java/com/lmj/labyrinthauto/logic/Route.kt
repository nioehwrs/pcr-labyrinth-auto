package com.lmj.labyrinthauto.logic

import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.frida.AgentApi
import org.json.JSONObject

/**
 * **节点价值评分 & 最优路线** —— 逐字对应 PC 版 `pcr_auto.py`：
 *
 * | 这里 | PC 版 |
 * |---|---|
 * | [BLOCK_SCORE] / [EVENT_SCORE_IGNORED] / [blockScore] | 5604 / 5608 / `block_score`(5617) |
 * | [buildDp] / [pathFrom] / [optimalPath] / [areaTheoretical] | `build_dp`(5623) / `path_from`(5642) / `optimal_path`(5652) / `area_theoretical`(5665) |
 * | [mapByArea] / [checkValuePerfect] | `map_by_area`(5673) / `check_value_perfect`(5681) |
 * | [pickNode] | `pick_node`(5433) |
 * | [fmtRoute] | `fmt_route`(5757) |
 * | [refreshRoute] | `refresh_route`(5860) |
 *
 * ★PC 注释（5590~5602）：这套算法**整份来自 labyrinth.py（autopcr 插件）**，2026-10-10 用真图
 *   （enter_id=224）在 **350 / 100 / 0 三个口径**下逐区对拍：分数、路线、理论分**完全一致**。
 *   我们照同一口径对拍（`tools/gen_route_fixtures.py`）。
 *   ★原文件里的"基础路线"档（`_can_reach`/`_has_path_with_min_types`）**按用户意见不采用**，这里也没有。
 */
object Route {

    /** PC `BLOCK_SCORE`(5604)：普通战 300 / 精英战 1200 / 门票 400 / 事件 350 / 遗物 300 / 商店 100 / Boss 1900 */
    val BLOCK_SCORE: MutableMap<Int, Int> =
        mutableMapOf(0 to 0, 1 to 0, 2 to 300, 3 to 1200, 4 to 400, 5 to 350, 6 to 300, 7 to 100, 8 to 1900)

    /** PC `EVENT_SCORE_IGNORED`(5608)：忽略事件（= labyrinth.py 的 `_SCORE_MAP_NO_EVENT`） */
    const val EVENT_SCORE_IGNORED = 0

    /** PC `LAB_BLOCK_TYPE`(344)：块类型 → 名字（仅日志可读性） */
    val LAB_BLOCK_TYPE: Map<Int, String> =
        mapOf(1 to "NONE", 2 to "NORMAL", 3 to "HARD", 4 to "TICKET", 5 to "EVENT", 6 to "RELIC", 7 to "SHOP", 8 to "BOSS")

    /** PC `MAZE_TARGET_PREFER`(343)：前进节点类型优先级（越靠前越优先） */
    val MAZE_TARGET_PREFER: List<Int> = listOf(6, 7, 5, 2, 4, 3, 8, 1)

    /** PC `MAZE_NODE_SELECTABLE`(331)：ePanelState.SELECTABLE_FUTURE_SECTOR = 可前往 */
    const val NODE_SELECTABLE = 4

    /** PC `apply_lab_state`(5814)：事件格价值可被刷开局的 `event_value` 覆盖（口径必须一致） */
    fun setEventValue(v: Int) {
        BLOCK_SCORE[5] = v
    }

    // ────────────────────────── 纯函数（可对拍）──────────────────────────

    /** PC `block_score`(5617) */
    fun blockScore(btype: Int?, ignoreEvent: Boolean = false): Int {
        if (ignoreEvent && btype == 5) return EVENT_SCORE_IGNORED
        return BLOCK_SCORE[btype] ?: 0
    }

    /**
     * PC `build_dp`(5623)：从最后一列往前 `dp[b] = score(b) + max(dp[后继])`，`nxt[b]` = 最优后继。
     * 跨区后继不在 dp 里 → 记 0（与原实现一致）。
     */
    fun buildDp(
        blocks: List<JSONObject>,
        ignoreEvent: Boolean = false,
    ): Pair<LinkedHashMap<Int, Int>, LinkedHashMap<Int, Int?>> {
        val dp = LinkedHashMap<Int, Int>()
        val nxt = LinkedHashMap<Int, Int?>()
        val cols = blocks.mapNotNull { (it.opt("column") as? Number)?.toInt() }.distinct().sortedDescending()
        for (col in cols) {
            for (b in blocks) {
                if ((b.opt("column") as? Number)?.toInt() != col) continue
                var best = 0
                var bestN: Int? = null
                val nx = b.optJSONArray("next")
                if (nx != null) {
                    for (i in 0 until nx.length()) {
                        val nid = (nx.opt(i) as? Number)?.toInt() ?: continue
                        val v = dp[nid]
                        if (v != null && v > best) {
                            best = v
                            bestN = nid
                        }
                    }
                }
                val bid = (b.opt("blockId") as? Number)?.toInt() ?: continue
                dp[bid] = blockScore((b.opt("blockType") as? Number)?.toInt(), ignoreEvent) + best
                nxt[bid] = bestN
            }
        }
        return dp to nxt
    }

    /** PC `path_from`(5642)：从任意节点沿 nxt 回溯出路径 */
    fun pathFrom(dp: Map<Int, Int>, nxt: Map<Int, Int?>, startId: Int, limit: Int = 64): List<Int> {
        if (startId !in dp) return emptyList()
        val path = mutableListOf(startId)
        while (true) {
            val n = nxt[path.last()] ?: break
            if (path.size >= limit) break
            path.add(n)
        }
        return path
    }

    data class PathScore(val score: Int, val path: List<Int>)

    /** PC `optimal_path`(5652)：起始列最高分节点 → 沿 nxt 回溯 */
    fun optimalPath(blocks: List<JSONObject>, ignoreEvent: Boolean = false): PathScore {
        val (dp, nxt) = buildDp(blocks, ignoreEvent)
        val cols = blocks.mapNotNull { (it.opt("column") as? Number)?.toInt() }.distinct().sorted()
        if (cols.isEmpty()) return PathScore(0, emptyList())
        val starts = blocks.filter { (it.opt("column") as? Number)?.toInt() == cols[0] }
        if (starts.isEmpty()) return PathScore(0, emptyList())
        var bestId: Int? = null
        var bestVal = Int.MIN_VALUE
        for (b in starts) {
            val bid = (b.opt("blockId") as? Number)?.toInt() ?: continue
            val v = dp[bid] ?: 0
            if (bestId == null || v > bestVal) {      // Python max：平局取**先出现**的
                bestId = bid
                bestVal = v
            }
        }
        val sid = bestId ?: return PathScore(0, emptyList())
        return PathScore(dp[sid] ?: 0, pathFrom(dp, nxt, sid))
    }

    /** PC `area_theoretical`(5665)：Σ（每列里同列区块的最大分） */
    fun areaTheoretical(blocks: List<JSONObject>, ignoreEvent: Boolean = false): Int {
        val cols = LinkedHashMap<Int, MutableList<JSONObject>>()
        for (b in blocks) {
            val c = (b.opt("column") as? Number)?.toInt() ?: continue
            cols.getOrPut(c) { mutableListOf() }.add(b)
        }
        return cols.values.sumOf { v -> v.maxOf { blockScore((it.opt("blockType") as? Number)?.toInt(), ignoreEvent) } }
    }

    /** PC `map_by_area`(5673)：全图按 area 分组 */
    fun mapByArea(panels: List<JSONObject>): Map<Int, List<JSONObject>> {
        val out = LinkedHashMap<Int, MutableList<JSONObject>>()
        for (p in panels) {
            val a = (p.opt("area") as? Number)?.toInt() ?: continue
            out.getOrPut(a) { mutableListOf() }.add(p)
        }
        return out
    }

    data class Detail(val area: Int, val actual: Int, val theory: Int, val path: List<Int>)
    data class ValueCheck(val ok: Boolean, val actual: Int, val theory: Int, val details: List<Detail>)

    /**
     * PC `check_value_perfect`(5681)：「价值偏差=0」判定 —— 逐区比"最优路线实际分"与"理论最高分"。
     * ★合格 ⟺ 实际分 ≥ 理论分 ⟺ 这条路**每一列都踩在该列最高分上**。
     */
    fun checkValuePerfect(panels: List<JSONObject>, ignoreEvent: Boolean = false): ValueCheck {
        var act = 0
        var theo = 0
        val det = mutableListOf<Detail>()
        for ((area, blk) in mapByArea(panels).toSortedMap()) {
            val (s, path) = optimalPath(blk, ignoreEvent)
            val t = areaTheoretical(blk, ignoreEvent)
            act += s
            theo += t
            det.add(Detail(area, s, t, path))
        }
        return ValueCheck(act >= theo, act, theo, det)
    }

    /**
     * PC `pick_node`(5433)：按 [MAZE_TARGET_PREFER] 选"可前往"的目标节点。
     * 条件 = `panelState==SELECTABLE` 且坐标在屏内 且（`nxt` 为空 或 blockId 在 `nxt` 里）；
     * 排序 = (类型优先级, blockId)。没有则返回 null。
     *
     * @param nxt 状态里的 `next` 列表（当前节点的后继）；空 = 不按它过滤
     */
    /**
     * ★2026-11-22（用户实测）：节点**现在能不能点**。
     *
     * `panelState==4` 只是"拓扑上有路"——事件/遗物选择/弹窗期间它一样是 4，但节点是**暗的、点不动**。
     * 真正的判据是 `PartsLabyrinthPanel.currentAnimationName`：
     *   · `""`（空）      = 还没点亮（处理中）⇒ 不能点
     *   · `selectable_idle` = 已点亮 ⇒ 可点
     *   （已走过=clear / 当前所在=current_normal_idle）
     * 兼容：旧数据/单测里没有 `anim` 字段时按原逻辑放行（只看 panelState）。
     */
    fun nodeClickable(n: JSONObject): Boolean {
        val st = (n.opt("panelState") as? Number)?.toInt()
        if (st != NODE_SELECTABLE) return false
        if (!n.has("anim")) return true
        val a = n.optString("anim")
        return a == "selectable_idle" || a == "selectable_animation_idle"
    }

    fun pickNode(nodes: List<JSONObject>, nxt: Collection<Int>?, screenW: Int, screenH: Int): JSONObject? {
        // ★★2026-11-22（用户）：**彻底不看坐标** —— 直调 `onClickPanel` 不吃坐标，
        //   坐标只用于"拖进视野"的兜底；之前 `y in 0..screenH` 在横屏设备上恒失败
        //   （`H` 取了 pixelHeight=1920，y 全偏），导致明明可前往却"未找到屏内节点"。
        //   screenW/screenH 参数保留（调用方还在传），这里不再用。
        val cand = nodes.filter { n ->
            nodeClickable(n) &&
                (nxt.isNullOrEmpty() || (n.opt("blockId") as? Number)?.toInt() in nxt)
        }
        if (cand.isEmpty()) return null
        var best: JSONObject? = null
        var bestKey: List<Int>? = null
        for (n in cand) {
            val bt = (n.opt("blockType") as? Number)?.toInt()
            val pref = MAZE_TARGET_PREFER.indexOf(bt)
            val k = listOf(if (pref >= 0) pref else MAZE_TARGET_PREFER.size, (n.opt("blockId") as? Number)?.toInt() ?: 0)
            val bk = bestKey
            if (bk == null || k[0] < bk[0] || (k[0] == bk[0] && k[1] < bk[1])) {
                best = n
                bestKey = k
            }
        }
        return best
    }

    /** PC `fmt_route`(5757)：把路线渲染成可读串：`c0中【RELIC】B10101#300-…` */
    fun fmtRoute(panels: List<JSONObject>, pathIds: List<Int>): String {
        val m = HashMap<Int, JSONObject>()
        for (p in panels) (p.opt("blockId") as? Number)?.toInt()?.let { m[it] = p }
        val cols = HashMap<Int, Int>()
        for (p in panels) {
            val c = (p.opt("column") as? Number)?.toInt() ?: continue
            cols[c] = (cols[c] ?: 0) + 1
        }
        val out = mutableListOf<String>()
        for (bid in pathIds) {
            val p = m[bid]
            if (p == null) {
                out.add("B${bid}(?)")
                continue
            }
            val col = (p.opt("column") as? Number)?.toInt() ?: 0
            val row = (p.opt("row") as? Number)?.toInt() ?: 0
            val bt = (p.opt("blockType") as? Number)?.toInt()
            val rl = when {
                (cols[col] ?: 1) <= 1 -> "合流"
                row == 1 -> "上"
                row == 2 -> "中"
                else -> "下"
            }
            out.add("c$col$rl【${LAB_BLOCK_TYPE[bt] ?: Py.str(bt)}】B$bid#${blockScore(bt)}")
        }
        return out.joinToString("-")
    }

    // ────────────────────────── 缓存（PC `_ROUTE` 5777）──────────────────────────

    @Volatile
    private var routeArea: Int? = null

    @Volatile
    private var routePath: List<Int> = emptyList()

    @Volatile
    private var routePanels: List<JSONObject> = emptyList()

    fun cachedPanels(): List<JSONObject> = routePanels

    fun reset() {
        routeArea = null
        routePath = emptyList()
        routePanels = emptyList()
    }

    /**
     * PC `refresh_route`(5860)：算出并缓存"当前区、从当前节点到结束"的最高分路线。
     * 命中缓存（同区 & 当前节点在路线里）直接用；否则读一次全图重算。
     *
     * @param savedRoute ★刷开局记住的那条（`labyrinth_state.json` 的 `route[area]`）—— PC 会**优先**用它
     *        （"用记住的路线直接推进"，人不在那条路线上才现算 DP；两者同一口径、已验证一致 5873~5883）。
     *        **我们这边已接通**：[com.lmj.labyrinthauto.logic.MazeLoop] 开局时若 `RerollState.result` 非空，
     *        会把刷开局算好的 `routePerArea[curArea]` 作为 `savedRoute` 传进来，局内沿预计算路线推进，
     *        不再现算 DP（与 PC「直接获取完整地图、决定前进方向」一致）。
     */
    suspend fun refreshRoute(
        curArea: Int,
        curBlock: Int,
        ignoreEvent: Boolean = false,
        force: Boolean = false,
        panels: List<JSONObject>? = null,
        savedRoute: List<Int>? = null,
    ): List<Int> {
        if (!force && routeArea == curArea && curBlock in routePath) return routePath
        val pl = panels ?: run {
            val a = AgentApi.labMap()?.optJSONArray("panels")
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }
        val blk = pl.filter { (it.opt("area") as? Number)?.toInt() == curArea }
        var path: List<Int>? = null
        var src = "现算 DP"
        var total: Int? = null
        if (savedRoute != null && savedRoute.isNotEmpty()) {
            val ids = blk.mapNotNull { (it.opt("blockId") as? Number)?.toInt() }.toSet()
            val i = savedRoute.indexOf(curBlock)
            if (i >= 0) {
                val p = savedRoute.drop(i).filter { it in ids }
                path = p
                src = "刷开局记住的路线"
                total = blk.filter { (it.opt("blockId") as? Number)?.toInt() in p }
                    .sumOf { blockScore((it.opt("blockType") as? Number)?.toInt(), ignoreEvent) }
            }
        }
        if (path == null) {
            val (dp, nxt) = buildDp(blk, ignoreEvent)
            path = pathFrom(dp, nxt, curBlock)
            total = dp[curBlock]
        }
        routeArea = curArea
        routePath = path
        routePanels = pl
        if (path.isNotEmpty()) {
            AppState.addLog("[路线] area$curArea：从 B$curBlock 到结束  ${fmtRoute(blk, path)}（$src，总分=$total）")
        } else {
            AppState.addLog("[路线] area$curArea：从 B$curBlock 算不出路线（该节点不在 dp 里？）→ 回退按类型优先级走")
        }
        return path
    }
}
