package com.lmj.labyrinthauto.logic.shop

import com.lmj.labyrinthauto.data.RelicTables
import com.lmj.labyrinthauto.logic.Py
import com.lmj.labyrinthauto.logic.relic.RelicPick
import org.json.JSONObject

/**
 * 商店决策层（**不点击**）—— 逐字对应 PC 版 `pcr_auto.py`：
 *
 * | 这里 | PC 版 |
 * |---|---|
 * | [SHOP_BUY_ORDER] / [SHOP_BUY_2STAR] / [SHOP_CFG] | 4389 / 4394 / 4402 |
 * | [shopItemKind] / [shopValueOf] / [shopRate] | `shop_item_kind`(4428) / `shop_value_of`(4444) / `shop_rate`(4453) |
 * | [priceNote] | `_price_note`(4470) |
 * | [shopPlan] / [shopSlotOf] / [area4OnlyIds] | `shop_plan`(4482) / `shop_slot_of`(4549) / `area4_only_ids`(4931) |
 * | [refreshDecision] | `shop_step`(4641~4714) 里的"该不该刷新"判据 |
 *
 * ★[refreshDecision] 与其它几个**性质不同**：PC 版没有单独的函数，那段判据是**内联**在 `shop_step`
 *   里的（而且中间夹着 `shop_pool_stats` 这种要读设备的调用）。这里把它**原样抽出来**、
 *   把设备侧的量（`q1` / 本店已刷次数）当参数传进来 —— 逻辑逐行照抄，没有加新规则。
 *
 * ★[useMdp] 5 层可选接管：开启后，5 层的「刷新判据」改由 [ShopMdp] 的运行时 DP 解决定（[ShopMdp.shouldRefresh]）。
 *   3★/2★遗物决策已**统一**改用离线精确阈值表 [threeStarThresholds]/[twoStarThresholds]（折扣→阈值，见伴随对象），
 *   不论 [useMdp] 开关、对所有层生效；2★在 5 层下全权由阈值决定，不再受 `buy2Star` 开关影响（开关只管非 5 层的无脑买）。
 *   抛弃了原 PC 的 `done>=max_refresh` 兜底与 MDP 的 T(R) 实时阈值。
 *   ⚠[RelicShopPortTest]（默认 [useMdp]=false）若覆盖 5 层 R≥1 的 3★ 场景，结果会变化，需同步更新对拍基线。
 */
class ShopPlan(
    private val tables: RelicTables,
    private val buy2Star: Boolean = SHOP_BUY_2STAR,
    /** 是否启用 MDP 最优停止算法接管 5 层刷新/3★ 决策（false = 走 PC 旧逻辑，与对拍测试一致）。 */
    private val useMdp: Boolean = false,
    /**
     * ★同星级遗物的购买顺序（2026-10-22 用户）：`true` = 同星级内按「拿取遗物」的系列/小类序
     * （暴击 > 削弱 > 加速 > 守备 > 强化；系列内见 [RelicPick.relicSubOrder]）。
     * **星级本身不参与比较**（1★/2★/3★ 仍按原品类序/阈值分桶）。
     * `false` = PC 原行为（货架顺序），供 [com.lmj.labyrinthauto.RelicShopPortTest] 对拍。
     */
    private val sameStarRelicOrder: Boolean = true,
) {

    /** PC 版 `shop_step` 抽出来的"不刷新"结论 */
    class RefreshDecision(
        /** 该不该点「更新」 */
        val refresh: Boolean,
        /** 触发门槛（PC `thr[done]`；null = 该次没有门槛） */
        val need: Int?,
        /** 不刷新时的人话原因（PC 的 `_why`） */
        val why: String,
        /** 刷新评估那一行（PC 仅在 `SHOP_REFRESH_SHOW_EV and MAZE_VERBOSE` 时打） */
        val detail: String,
    )

    companion object {
        /** PC 版 `SHOP_BUY_ORDER`(4389)：购买优先级（越靠前越优先）。★定向印记**永不买**，所以不在序列里 */
        val SHOP_BUY_ORDER: List<String> = listOf("1★遗物", "随机印记", "2★遗物")

        /** PC 版 `SHOP_BUY_2STAR`(4394)：**代码默认值**（运行时以 config.json 的 `shop_buy_2star` 为准） */
        const val SHOP_BUY_2STAR = true

        /** PC 版 `SHOP_CFG`(4402)：每层的买/刷新策略 */
        val SHOP_CFG: Map<Int, Map<String, Any?>> = mapOf(
            // 3 层：保守 —— 钱不多就不主动刷第二次（留钱到 5 层花）；refresh_above = 第 1/2 次刷新前的最低余额
            3 to mapOf(
                "refresh_above" to listOf(3100, 4200), "max_refresh" to 2,
                "no_star" to emptyList<Int>(), "no_directed_mark" to false,
            ),
            // 4 层：不刷新；不买"4 层商店专属"那 6 件
            4 to mapOf(
                "refresh_above" to emptyList<Int>(), "max_refresh" to 0,
                "no_star" to emptyList<Int>(), "no_directed_mark" to false, "no_area4_only" to true,
            ),
            // 5 层：尽量多买多刷新；3★遗物例外 + greedy（目标是花光，不赌 q1）
            5 to mapOf(
                "refresh_above" to emptyList<Int>(), "max_refresh" to 2,
                "no_star" to emptyList<Int>(), "no_directed_mark" to true,
                "allow_3star" to true, "greedy" to true,
            ),
        )

        /** PC 版 `SHOP_DEFAULT_CFG`(4424) */
        val SHOP_DEFAULT_CFG: Map<String, Any?> = mapOf(
            "refresh_above" to emptyList<Int>(), "max_refresh" to 0,
            "no_star" to emptyList<Int>(), "no_directed_mark" to false,
            "allow_3star" to false, "no_area4_only" to false, "greedy" to false,
        )

        /** PC 版 `SHOP_VALUE`(4823)：商品分值 —— 遗物 200 分/件、印记（=角色）100 分/件 */
        val SHOP_VALUE: Map<String, Int> = mapOf("遗物" to 200, "印记" to 100)

        /** PC 版 `SHOP_GROUP_SIZE`(4835)：每页(组)商品数 → 屏上槽位 = page*3 + idx */
        const val SHOP_GROUP_SIZE = 3

        /** PC 版 `SHOP_REFRESH_MIN_Q1`(4825)：「钱紧」时要求的"刷出≥1个1★"概率下限 */
        const val SHOP_REFRESH_MIN_Q1 = 0.5

        /** PC 版 `SHOP_CHEAP_EXPECT`(4638)：刷新后会出现的最便宜价（印记 ≈ 780/件） */
        const val SHOP_CHEAP_EXPECT = 780

        /**
         * 3★遗物「折扣→阈值」映射（理论 DP 离线精确算出）：折扣整数百分比(65~100, 步长5) →
         * `(R=1层阈值=已刷1次, R=2层阈值=刚进店)`，单位金币。
         * 含义：买完 1★/印记/2★ 后，剩余金币 ≥ 对应阈值才买该件 3★；折扣越高(数越大)阈值越高
         * （3★更贵、确定的 200 分更不划算）。R=0（已刷2次，终止层）不判阈值，直接买光能买得起的。
         */
        fun threeStarThresholds(discountPercent: Int): Pair<Int, Int> = when (discountPercent) {
            65  -> 9500  to 15600
            70  -> 10200 to 16700
            75  -> 10900 to 17800
            80  -> 11600 to 19000
            85  -> 12300 to 20100
            90  -> 13000 to 21300
            95  -> 13700 to 22400
            100 -> 14200 to 23300
            else -> throw IllegalArgumentException("折扣 $discountPercent 不在 65..100(步长5) 范围")
        }

        /**
         * 2★遗物「折扣→阈值」映射（由 [ShopMdp] 的 DP 重算，见 tools/dp_two_star_check.py 的 `table` 模式）：
         * 折扣整数百分比(65~100, 步长5) → `(R=1层阈值=已刷1次, R=2层阈值=刚进店)`，单位金币。
         * 含义：买完 1★/印记 后，剩余金币 ≥ 对应阈值才买该件 2★（与 [threeStarThresholds] 同机制）。
         * ⚠方向：R=2（刚进店，金币还能再摸两轮货架）比 R=1 更"惜金"，故 **R=2 阈值 > R=1 阈值**。
         *   （旧表把两者写反且量级偏小：R=2 实际要到 ~8200@65、随折扣升到 ≈12000+ 才买；R=1 约 4200→6200。）
         * R=0 不判阈值直接买光。5 层下此表全权决定 2★ 买否，不再受 buy2Star 开关影响。
         */
        fun twoStarThresholds(discountPercent: Int): Pair<Int, Int> = when (discountPercent) {
            65  -> 4200  to 8200
            70  -> 4400  to 9200
            75  -> 4400  to 10200
            80  -> 5200  to 9800
            85  -> 5400  to 10400
            90  -> 6200  to 12000
            95  -> 6200  to 11200
            100 -> 6200  to 12000
            else -> throw IllegalArgumentException("折扣 $discountPercent 不在 65..100(步长5) 范围")
        }
    }

    // ────────────────────────── MDP（5层最优停止，运行时 solve） ──────────────────────────

    private val mdpCache = mutableMapOf<String, ShopMdp.MdpResult>()

    /** 从当前货架的随机印记实付价反推折扣 disc = 印记实付 / 1200；读不到则回落 0.65。 */
    private fun discOf(sh: JSONObject): Double {
        val itemsArr = sh.optJSONArray("items") ?: return 0.65
        for (i in 0 until itemsArr.length()) {
            val it = itemsArr.optJSONObject(i) ?: continue
            if (!Py.truthy(it.opt("isSold")) && shopItemKind(it.opt("rewardId")).first == "随机印记") {
                val p = Py.num(it, "price")?.toDouble() ?: continue
                if (p > 0.0) return (p / 1200.0).coerceIn(0.1, 1.5)
            }
        }
        return 0.65
    }

    /** 取 5 层 MDP 解（同 disc 缓存；非 5 层或关闭 MDP 返回 null）。 */
    private fun getMdp(area: Int?, sh: JSONObject): ShopMdp.MdpResult? {
        if (!useMdp || area != 5) return null
        val disc = discOf(sh)
        val key = "5|${((disc * 1000).toInt())}"
        return mdpCache.getOrPut(key) {
            val maxRefresh = cfgInt(SHOP_CFG[area] ?: SHOP_DEFAULT_CFG, "max_refresh", 2)
            ShopMdp.solve(tables, area = 5, maxRefresh = maxRefresh, disc = disc,
                allow3Star = true, buy2Star = buy2Star || area == 5)
        }
    }

    // ────────────────────────── 商品识别 / 效率 ──────────────────────────

    /** PC 版 `buy_2star_relic`(4397)：config.json 的 `shop_buy_2star` ＞ 代码默认 [SHOP_BUY_2STAR] */
    fun buy2starRelic(configValue: Boolean?): Boolean = configValue ?: SHOP_BUY_2STAR

    /**
     * PC 版 `shop_item_kind`(4428)：rewardId → (品类, info)。
     * 品类 = `1★遗物` / `2★遗物` / `3★遗物` / `随机印记` / `定向印记` / `其他`。
     */
    fun shopItemKind(rewardId: Any?): Pair<String, JSONObject?> {
        val r = tables.rk(rewardId)
        if (r != null) return "${Py.num(r, "rarity")?.toInt() ?: 0}★遗物" to r
        val s = tables.si(rewardId)
        if (s != null) return s.optString("kind").ifEmpty { "其他" } to s
        return "其他" to null
    }

    /** PC 版 `shop_value_of`(4444) */
    fun shopValueOf(kind: String?): Int = when {
        kind != null && kind.endsWith("遗物") -> SHOP_VALUE["遗物"]!!
        kind != null && kind.endsWith("印记") -> SHOP_VALUE["印记"]!!
        else -> 0
    }

    /** PC 版 `shop_rate`(4453)："分/金"效率（越高越划算）；信息不足返回 null */
    fun shopRate(price: Any?, kind: String?): Double? {
        val v = shopValueOf(kind)
        val p = (price as? Number)?.toDouble() ?: return null
        return if (v != 0 && p != 0.0) v / p else null
    }

    // ────────────────────────── ★同星级遗物顺序（2026-10-22）──────────────────────────

    /** 遗物序：三选一/商店共用 [RelicPick.sortSameStar]，这里只借它的表，不重写规则 */
    private val relicPick = RelicPick(tables)

    /**
     * ★**商店排序**（2026-10-22 用户："三选一排序和商店排序分别做一个函数，都调用同级遗物排序"）：
     * 传进来的 [bucket] 已经是**同一星级**（星级由原有品类序/阈值分桶决定，这里不比较），
     * 只按 [RelicPick.sortSameStar] 排；价格做同键兜底（同星通常同价）。
     *
     * @param ctx 敌人情况 + 当前遗物（[RelicPick.relicCtx]）；null = 只按静态系列/小类序
     */
    fun sortShopBucket(bucket: List<JSONObject>, ctx: RelicPick.Ctx?): List<JSONObject> =
        relicPick.sortSameStar(bucket, ctx) { Py.num(it, "price")?.toInt()?.toDouble() ?: 0.0 }

    /** PC 版 `_price_note`(4470)：`（基准1200 -35%）` —— 有基准价才给（用来反推商店折扣） */
    fun priceNote(it: JSONObject, info: JSONObject?): String {
        val base = info?.opt("base_price")?.takeIf { it != JSONObject.NULL }
            ?: info?.opt("price")?.takeIf { it != JSONObject.NULL } ?: return ""
        val baseN = (base as? Number)?.toDouble() ?: return ""
        val p = (it.opt("price") as? Number)?.toDouble() ?: return ""
        if (baseN >= 100000) return ""                    // 99999999 = 特殊/不可售，跳过
        val pct = Py.roundInt((p / baseN - 1.0) * 100)
        return "（基准${Py.str(base)} ${if (pct >= 0) "+" else ""}$pct%）"
    }

    /** PC 版 `area4_only_ids`(4931)：4 层商店的固定阵容（30001 + 3X006 ×5）—— 两边都**按数据判**，不手写 id */
    fun area4OnlyIds(): Set<Int> {
        val out = HashSet<Int>()
        for ((k, v) in tables.rank) {
            if (k.toString().length != 5) continue
            if (Py.truthy(v.opt("shop_only")) || (Py.num(v, "mark")?.toInt() ?: -1) == 0) out.add(k)
        }
        return out
    }

    // ────────────────────────── 买什么 ──────────────────────────

    /** PC 版 `shop_plan`(4482)：按配置算"该买什么"（**不点击**）。返回 (要买的列表, 说明文字) */
    fun shopPlan(
        area: Int?, sh: JSONObject, buy2Star: Boolean = this.buy2Star, done: Int = 0,
        /** ★同级遗物序的上下文（敌人情况 + 当前遗物）；null = 只用静态系列序（PC 对拍/未接入时） */
        relicCtx: RelicPick.Ctx? = null,
    ): Pair<List<JSONObject>, String> {
        val cfg = SHOP_CFG[area] ?: SHOP_DEFAULT_CFG
        val a4 = area4OnlyIds().map { it.toString() }.toSet()
        val coin = Py.num(sh, "coin")?.toInt() ?: 0
        val itemsArr = sh.optJSONArray("items")
        val all = if (itemsArr == null) emptyList<JSONObject>()
        else (0 until itemsArr.length()).mapNotNull { itemsArr.optJSONObject(it) }
        val alive = all.filter { !Py.truthy(it.opt("isSold")) }
        val shelf = alive.filter { (Py.num(it, "availability")?.toInt() ?: -1) == 1 }   // 1 = PURCHASE 货架上有
        // ★availability 只表示"还能买"，**不含钱够不够** ⇒ 钱不够的必须排除
        val buyable = shelf.filter { (Py.num(it, "price")?.toInt() ?: 0) <= coin }
        val poor = shelf.filter { (Py.num(it, "price")?.toInt() ?: 0) > coin }
        val noStar = cfgInts(cfg, "no_star")
        val noArea4 = cfgBool(cfg, "no_area4_only")

        // ① 按优先级挑候选（并过滤"本层不买"的星级）
        val picked = mutableListOf<JSONObject>()
        for (want in SHOP_BUY_ORDER) {
            if (want == "2★遗物") {
                if (!buy2Star) continue
                if (cfgBool(cfg, "allow_3star")) continue   // 5层：2★ 交由②按阈值逐件判
            }
            val bucket = mutableListOf<JSONObject>()
            for (it in buyable) {
                val (kind, info) = shopItemKind(it.opt("rewardId"))
                if (kind != want) continue
                if (info != null && ((Py.num(info, "rarity")?.toInt() ?: 0) in noStar)) continue
                if (noArea4 && it.opt("rewardId").toString() in a4) continue
                bucket.add(it)
            }
            // ★同星级内按"拿取遗物"的序（2026-10-22）；关闭时保持货架顺序（PC 对拍）
            picked.addAll(if (sameStarRelicOrder) sortShopBucket(bucket, relicCtx) else bucket)
        }
        // ② 2★/3★ 遗物（仅 allow_3star=5层）：买完 1★/印记 后，**逐件按折扣→阈值**判断（替代无脑买 / PC兜底）。
        //   顺序先 2★ 后 3★（2★更便宜、效率更高、阈值更低）；阈值由理论 DP 离线精确算好
        //   （见 [twoStarThresholds]/[threeStarThresholds]）。R=0（已刷2次，终止层）阈值按 0 → 直接买光。
        //   若某件不买 → 后面更贵的也不买（break），由调用方接着走刷新决策。
        if (cfgBool(cfg, "allow_3star")) {
            val rawPct = Py.roundInt(discOf(sh) * 100)
            val discPct = ((rawPct / 5) * 5).coerceIn(65, 100).toInt()
            val remaining = cfgInt(cfg, "max_refresh", 0) - done   // R = 本店剩余可刷新次数
            val baseSpend = picked.sumOf { Py.num(it, "price")?.toInt() ?: 0 }
            var restCoin = coin - baseSpend

            // 2★：5 层一律按阈值逐件判，不再受 buy2Star 开关影响（开关只管非 5 层，见①）
            val (t2R1, t2R2) = twoStarThresholds(discPct)
            val twoThr = when {
                remaining <= 0 -> 0                                  // R=0：不判，直接买光
                remaining == 1 -> t2R1                                // 已刷1次
                else -> t2R2                                          // 刚进店(remaining>=2)
            }
            val twos = buyable.filter { shopItemKind(it.opt("rewardId")).first == "2★遗物" }
                .filter { !(noArea4 && it.opt("rewardId").toString() in a4) }
                .let { l -> if (sameStarRelicOrder) sortShopBucket(l, relicCtx) else l.sortedBy { Py.num(it, "price") ?: 0.0 } }
            for (it in twos) {
                val p = Py.num(it, "price")?.toInt() ?: 0
                if (p <= restCoin && (remaining <= 0 || restCoin >= twoThr)) {
                    picked.add(it); restCoin -= p
                } else break
            }

            // 3★：按阈值逐件判
            val (tR1, tR2) = threeStarThresholds(discPct)
            val threeThr = when {
                remaining <= 0 -> 0                                  // R=0：不判，直接买光
                remaining == 1 -> tR1                                // 已刷1次
                else -> tR2                                          // 刚进店(remaining>=2)
            }
            val threes = buyable.filter { shopItemKind(it.opt("rewardId")).first == "3★遗物" }
                .filter { !(noArea4 && it.opt("rewardId").toString() in a4) }
                .let { l -> if (sameStarRelicOrder) sortShopBucket(l, relicCtx) else l.sortedBy { Py.num(it, "price") ?: 0.0 } }
            for (it in threes) {
                val p = Py.num(it, "price")?.toInt() ?: 0
                if (p <= restCoin && (remaining <= 0 || restCoin >= threeThr)) {
                    picked.add(it); restCoin -= p
                } else break
            }
        }
        // ⚠`desc` 与 PC `shop_plan` **逐字一致**（有对拍测试 [RelicShopPortTest] 守着）⇒ 别在这里改文案。
        //   "买完余"的口径修正（2026-10-17 用户："买完余=-2620 —— 日志修一下"）放在 [buyPlanBrief]，
        //   由 MazeLoop 追加在日志后面（PC 的 `coin - 全部候选价` 会出负数，看不懂）。
        val spend = picked.sumOf { Py.num(it, "price")?.toInt() ?: 0 }

        // 顺带把每件候选的"槽位 + 可见性"打出来（✓=可直接点，↓=被裁需先拖动）
        val slots = sh.optJSONArray("slots")
        val stxt = picked.map {
            val si = shopSlotOf(it, alive)
            val vis = if (slots != null && si >= 0 && si < slots.length()) {
                slots.optJSONObject(si)?.opt("vis")
            } else {
                null
            }
            val visTxt = when {
                vis == null || vis == JSONObject.NULL -> ""
                Py.truthy(vis) -> "✓"
                else -> "↓"
            }
            "$si$visTxt"
        }
        val poorTxt = if (poor.isNotEmpty()) {
            " 钱不够=${poor.size}件(最贵${poor.maxOf { Py.num(it, "price")?.toInt() ?: 0 }})"
        } else {
            ""
        }
        // 货架上"分/金"最高的一件（用于对账：刷新值不值就看它）
        var effRate: Double? = null
        var effKind = ""
        for (i in buyable) {
            val k = shopItemKind(i.opt("rewardId")).first
            val r = shopRate(i.opt("price"), k)
            if (r != null && (effRate == null || r > effRate!!)) {
                effRate = r
                effKind = k
            }
        }
        val effTxt = if (effRate != null) " 最优效率=${Py.fmt3(effRate!!)}($effKind)" else ""
        val desc = "金币=${Py.str(coin)} 可买=${buyable.size}/${alive.size}" +
            " 优先候选=${Py.listStr(picked.map { shopItemKind(it.opt("rewardId")).first })}" +
            " 槽=${Py.listStr(stxt)}${if (stxt.isEmpty()) "" else "(✓可见 ↓需拖动)"}" +
            poorTxt + effTxt +
            " 可刷新${cfgInt(cfg, "max_refresh", 0)}次(门槛${Py.listStr(cfgInts(cfg, "refresh_above"))})" +
            " 买完余=${Py.str(coin - spend)}"
        return picked to desc
    }

    /**
     * ★"买完余"的**可读口径**（2026-10-17 用户："买完余=-2620 —— 日志修一下"）：`desc` 里的
     * `金币 - 全部候选价` 会算进**买不起**的件，于是出现 `买完余=-2620` 这种看不懂的数。
     * 这里照"每轮先买最优先那件"的真实口径：按顺序把钱花到不够为止，报"计划买 k/N 件、花 X、余 Y"。
     *
     * ⚠只用于**日志展示**（MazeLoop 追加在 `desc` 之后），不参与决策、也不改 `desc` 本身
     * （`desc` 必须与 PC 逐字一致，[RelicShopPortTest] 守着）。
     */
    fun buyPlanBrief(picked: List<JSONObject>, coin: Int): String {
        var n = 0
        var spend = 0
        var rest = coin
        for (it in picked) {
            val p = Py.num(it, "price")?.toInt() ?: 0
            if (p > rest) break
            rest -= p
            spend += p
            n++
        }
        // ★把"同星级里的实际顺序"打出来（2026-10-22）：遗物给 `2★ 名字`，印记给品类名。
        //   ⚠只改这里（展示用），`desc` 一个字符都不动（对拍要求逐字一致）。
        val seq = picked.map {
            relicPick.relicLabel(it.opt("rewardId")).ifBlank { shopItemKind(it.opt("rewardId")).first }
        }
        val seqTxt = if (seq.isEmpty()) "" else "｜顺序=${seq.joinToString(" > ")}"
        return "｜按优先级能买 ${n}/${picked.size} 件、花 $spend、余 $rest$seqTxt"
    }

    /**
     * PC 版 `shop_slot_of`(4549)：商品 → 屏上「购买」槽位下标。
     * 实测：lineup 是"页优先、页内左→右"，屏上槽位按 上→下、左→右 ⇒ slot = page*3 + idx；
     * page/idx 缺失时退化为它在 items 里的次序。
     */
    fun shopSlotOf(it: JSONObject, items: List<JSONObject>): Int {
        // PC 版是 `int(it.get("page") or 0) * 3 + int(it.get("idx") or 0)`：
        //   page/idx **缺失就是 0**（不报错、也不会走下面的兜底）；只有"有值但转不成整数"才走兜底。
        val page = Py.intAfterOr0(it.opt("page"))
        val idx = Py.intAfterOr0(it.opt("idx"))
        if (page != null && idx != null) return page * SHOP_GROUP_SIZE + idx
        // 兜底：按**值**找它自己在列表里的次序（Python `items.index(it)` 比的是 dict 内容）
        //   —— 不能指望 JSONObject.equals 在各实现里一致（Android 运行时的 org.json 就是按引用比）
        val mine = it.toString()
        val i = items.indexOfFirst { x -> x.toString() == mine }
        return if (i >= 0) i else 0
    }

    // ────────────────────────── 刷不刷 ──────────────────────────

    /**
     * PC 版 `shop_step`(4641~4714) 里的"该不该刷新"判据（设备侧的量由参数给）：
     *
     *   ① 买优先 —— **调用方保证**：还有可买的就先买（买是确定收益，刷新是随机的），不会走到这儿
     *   ② 次数：本店已刷 `done` < `cfg.max_refresh`，且游戏自带的 `resettable` > 0
     *   ③ 刷得起：金币 ≥ 刷新费（费会涨：300 → 600）
     *   ④ 刷完还买得起：金币 − 费 ≥ 刷新后会出现的最便宜价（印记 ≈ 780），否则这次刷新纯浪费
     *   ⑤ q1 把关：钱宽裕（金币−费 ≥ 两件印记）→ 直接刷；钱紧 → 要求 `q1 ≥ 0.5`
     *   另外 `cfg.refresh_above`（3 层 3100/4200）仍作为**额外门槛**生效
     *
     * ★MDP 接管（5层开启）：[useMdp] && area==5 时，上面的 q1/refresh_above 判据被 [ShopMdp.shouldRefresh]
     *   替代（仍保留费用/游戏次数等硬约束）。3/4 层与 [useMdp]=false 走上面原逻辑。
     *
     * @param q1 「刷出 ≥1 个 1★」的概率（PC 从 `shop_pool_stats` 读；null = 未知，按"不拦"处理）
     */
    fun refreshDecision(area: Int?, sh: JSONObject, done: Int, q1: Double?): RefreshDecision {
        val cfg = SHOP_CFG[area] ?: SHOP_DEFAULT_CFG
        val coin = Py.num(sh, "coin")?.toInt() ?: 0
        val cost = Py.num(sh, "rerollCost")?.toInt() ?: 0
        val left = Py.num(sh, "resettable")?.toInt()          // 游戏自带的"还剩几次可刷"
        val mx = cfgInt(cfg, "max_refresh", 0)
        val thr = cfgInts(cfg, "refresh_above")

        // ★MDP 接管（5层开启）：用 ShopMdp.shouldRefresh 替代 PC 的 q1/refresh_above 判据
        if (useMdp && area == 5) {
            val mdp = getMdp(area, sh)
            val remaining = mx - done
            val canRefresh = cost != 0 && coin >= cost && (left == null || left > 0)
            val refresh = canRefresh && (mdp != null && ShopMdp.shouldRefresh(mdp, coin, remaining))
            val gate = mdp?.refreshGateG ?: 0
            val why = when {
                cost == 0 -> "无刷新按钮"
                coin < cost -> "刷不起（金币 $coin < 费 $cost）"
                left != null && left <= 0 -> "游戏已无可刷次数"
                refresh -> ""
                else -> "MDP：金币 $coin < 门槛 $gate（剩余刷新 $remaining）"
            }
            val detail = "MDP刷新评估：gate=$gate 剩余刷新=$remaining 余额=$coin 费=$cost"
            return RefreshDecision(refresh, null, why, detail)
        }

        // ★刷新后至少要还买得起一件：基准用"刷新后会出现的最便宜价"（不能拿当前货架 —— 常被买空只剩贵的）
        val itemsArr = sh.optJSONArray("items")
        val rest = if (itemsArr == null) emptyList<Int>()
        else (0 until itemsArr.length()).mapNotNull { itemsArr.optJSONObject(it) }
            .filter { !Py.truthy(it.opt("isSold")) }
            .map { Py.num(it, "price")?.toInt() ?: 0 }
        val cheap = if (rest.isNotEmpty()) rest.min() else 0
        val cheapExpect = if (rest.isNotEmpty()) {
            (rest.filter { it > 0 } + SHOP_CHEAP_EXPECT).min()
        } else {
            SHOP_CHEAP_EXPECT
        }
        val canAfter = (coin - cost) >= cheapExpect
        val rich = (coin - cost) >= 2 * cheapExpect
        val q1Ok = cfgBool(cfg, "greedy") || rich || q1 == null || q1 >= SHOP_REFRESH_MIN_Q1
        val detail = "刷新评估：q1(刷出≥1个1★)=${if (q1 == null) "?" else Py.fmt2(q1)} ｜ 费=$cost" +
            " 余额-费=${coin - cost} 最便宜一件=$cheap ｜ " +
            (if (rich) "钱宽裕(≥两件) → 直接刷"
            else "钱紧 → 需 q1≥${Py.fmt2(SHOP_REFRESH_MIN_Q1)} ${if (q1Ok) "✓" else "✗"}") +
            (if (canAfter) "" else "  ⚠刷完买不起任何一件")

        if (done < mx && cost != 0 && coin >= cost && (left == null || left > 0) && canAfter && q1Ok) {
            val need = thr.getOrNull(done)
            if (need == null || coin > need) return RefreshDecision(true, need, "", detail)
            return RefreshDecision(
                false, need,
                "金币 $coin 未超过门槛 $need（第${done + 1}次）→ 改为退出", detail,
            )
        }
        val why = when {
            cost != 0 && coin < cost -> "刷不起（金币 $coin < 费 $cost）"
            !canAfter -> "刷完买不起任何一件（余额-费 ${coin - cost} < 最便宜 $cheap）"
            !q1Ok -> "钱紧且 q1=${Py.str(q1)} < ${Py.fmt2(SHOP_REFRESH_MIN_Q1)}（不值得赌）"
            else -> "已刷 $done/$mx 次"
        }
        return RefreshDecision(false, null, why, detail)
    }

    // ────────────────────────── 配置读取小工具 ──────────────────────────

    private fun cfgInt(cfg: Map<String, Any?>, key: String, dflt: Int): Int =
        (cfg[key] as? Number)?.toInt() ?: dflt

    private fun cfgBool(cfg: Map<String, Any?>, key: String): Boolean =
        cfg[key]?.let { Py.truthy(it) } ?: false

    private fun cfgInts(cfg: Map<String, Any?>, key: String): List<Int> =
        (cfg[key] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
}
