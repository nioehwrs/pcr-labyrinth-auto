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
 * ★**2026-11-4 大清理**（用户："4 层的不动，其他都清理掉"）：
 *   2 / 3 / 5 层的「买几件 + 买完刷不刷」**全部只由 [ShopDp] 决定**；PC 时代的以下路径都已删除——
 *   `refresh_above` 门槛、q1 判据、2★/3★ 离线阈值表、以及 5 层 MDP（`ShopMdp` 模块已删除）。
 *   4 层保持原样（不刷新、不买 4 层专属件），走 [shopPlan] 里的"按优先级买"那一段。
 *   `dp=null`（曲线未加载）时：不刷新 + 仍按优先级买 1★/印记，属于降级而不是瘫痪。
 */
class ShopPlan(
    private val tables: RelicTables,
    private val buy2Star: Boolean = SHOP_BUY_2STAR,
    /**
     * ★同星级遗物的购买顺序（2026-10-22 用户）：`true` = 同星级内按「拿取遗物」的系列/小类序
     * （暴击 > 削弱 > 加速 > 守备 > 强化；系列内见 [RelicPick.relicSubOrder]）。
     * **星级本身不参与比较**（1★/2★/3★ 仍按原品类序/阈值分桶）。
     * `false` = PC 原行为（货架顺序），供 [com.lmj.labyrinthauto.RelicShopPortTest] 对拍。
     */
    private val sameStarRelicOrder: Boolean = true,
    /**
     * ★精确 DP 曲线（[ShopDp]）——**3 层与 5 层一起接**（3 层用 b=3、5 层用 b=2，四层不动）。
     * `null` = 完全走 PC 旧逻辑（[RelicShopPortTest] 对拍基线）；非 null 时该层的「买几件 + 是否刷新」
     * 由 [ShopDp.decide] 全权决定，PC 的 `SHOP_BUY_ORDER`/阈值表/`refresh_above`/q1 判据全部让位。
     *
     * 之所以能这么做：DP 的输入是「**当前货架上还剩什么** + 当前金币 + 剩余刷新次数」，
     * 所以「进店时已经买过几件」（闪退重进、买一半失败）天然是普通输入。
     */
    private val dp: Map<Int, ShopDp.Set>? = null,
) {

    /** 最近一次 [shopPlan] 里 DP 给出的「买完是否刷新」（供 [refreshDecision] 用；同轮顺序固定） */
    private var dpSuggestRefresh: Boolean = false
    private var dpLast: ShopDp.Decision? = null
    private var dpLastDisc: Int = 100
    private var dpLastRemaining: Int = 0

    /** 最近一次**真正跑 DP** 得到的清单（[shopPlan] 里配额为 null 时产出；调用方 [takeQuota] 取走） */
    private var lastQuota: Quota? = null

    /**
     * 最近一次 DP 的**候选件数**（= [dpCandidates] 大小）。
     *
     * 用途（2026-11-08）：给调用方判"空清单是不是不可信"——
     * **空清单 + 候选非空 = 自相矛盾**（DP 只要有一件买得起、品类认得，就一定会买，
     * 因为"买 k 件"的分数严格大于空集）。实测那类清单来自"进店第一帧 `availability` 还没填"，
     * 一旦缓存就会让**整家店**一件都不买。
     */
    var lastCandCount: Int = 0
        private set

    /**
     * ★**购买清单**（2026-11-02 用户："每轮决策一次，拉一个清单就行了，清单写的内容比如说
     * 1星:x件；2星:x件；3星:x件；印记:x件"）。
     *
     * 语义：**进店（或刷新后）算一次**，之后每次购买只按清单挑件、不再重跑 DP；
     * 「哪一件具体是哪个遗物 / 购买按钮在哪」仍由调用方每轮现读现取（不动原流程）。
     *
     * 计数是**可变的**：调用方每买掉一件就对应减一（[shopPlan] 只按剩余配额挑件，不会改它）。
     */
    class Quota(
        var n1: Int,
        var n2: Int,
        var n3: Int,
        var nb: Int,
        /** 清单买完后是否刷新（DP 的结论；刷新后清单作废，需重新决策） */
        val refresh: Boolean,
        /** 决策时的折扣整数百分比（日志用） */
        val discPct: Int,
    ) {
        fun isEmpty(): Boolean = n1 <= 0 && n2 <= 0 && n3 <= 0 && nb <= 0

        fun total(): Int = n1 + n2 + n3 + nb

        /** 按品类名减一（买掉一件后调用）；不是遗物/印记品类则忽略 */
        fun consume(kind: String) {
            when (kind) {
                "1★遗物" -> if (n1 > 0) n1--
                "2★遗物" -> if (n2 > 0) n2--
                "3★遗物" -> if (n3 > 0) n3--
                "随机印记" -> if (nb > 0) nb--
            }
        }

        override fun toString(): String =
            "1★${n1} 2★${n2} 3★${n3} 印记${nb}" + (if (refresh) " → 买完刷新" else " → 不刷")
    }

    /** 取走最近一次 DP 算出的清单（取一次就清空，避免过期清单被复用） */
    fun takeQuota(): Quota? = lastQuota.also { lastQuota = null }

    private fun kindOf(it: JSONObject): String = shopItemKind(it.opt("rewardId")).first

    /**
     * 按清单从候选里挑件（同品类取前 N 件，且**累计不超预算**）。
     *
     * ★同品类内的先后**就是三选一那套**：`cand` 由 [dpCandidates] 用 [sortShopBucket] 排好，而
     *   `sortShopBucket` 直接调 [RelicPick.sortSameStar]（暴击>削弱>加速>守备>强化，系列内见
     *   [RelicPick.relicSubOrder]，再按敌人/持有物的动态项）—— 与三选一遗物**同一个函数**，不另立规则。
     *   价格只在 `sortSameStar` 内部做同序兜底，这里**不再按价格重排**。
     *
     * 预算约束是必要的：清单是进店时按当时金币算的，执行期金币只会变少，
     * 货架上可能还挂着买不起的件 ⇒ 不能把它们排进 `picked`（日志会误导、调度也会空转）。
     *
     * 返回顺序仍是 `cand` 原序（1★ → 印记 → 2★ → 3★），即点击顺序。
     */
    private fun pickByQuota(cand: List<JSONObject>, q: Quota, budget: Int): List<JSONObject> {
        var n1 = q.n1
        var n2 = q.n2
        var n3 = q.n3
        var nb = q.nb
        var left = budget
        val out = mutableListOf<JSONObject>()
        for (it in cand) {
            val price = Py.num(it, "price")?.toInt() ?: 0
            if (price > left) continue                  // 买不起的跳过（不 break：品类顺序不是价格顺序）
            val take = when (kindOf(it)) {
                "1★遗物" -> n1 > 0
                "2★遗物" -> n2 > 0
                "3★遗物" -> n3 > 0
                "随机印记" -> nb > 0
                else -> false
            }
            if (!take) continue
            when (kindOf(it)) {
                "1★遗物" -> n1--
                "2★遗物" -> n2--
                "3★遗物" -> n3--
                else -> nb--
            }
            left -= price
            out.add(it)
        }
        return out
    }

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

        /**
         * 每层策略（2026-11-4 清理）—— **只留还有用的两个字段**：
         *  · `max_refresh`：本店最多刷几次。DP 只决定"该不该刷"，次数上限与游戏 `resettable` 取小仍靠它；
         *  · `no_area4_only`：不买"4 层商店专属"那几件（只有 4 层用，4 层不走 DP）。
         *
         * ★已删除的 PC 时代字段（DP 接管后全部失效）：
         *   `refresh_above`（刷新门槛，只剩日志文案）、`allow_3star`（`|| dpEnabled()` 恒真）、
         *   `greedy`（只在旧 q1 判据里用）、`no_star`（三层都是空表）、`no_directed_mark`（**零引用**，
         *   定向印记"永不买"已经写死在 [dpCandidates]/[shopPlan] 里）。
         */
        val SHOP_CFG: Map<Int, Map<String, Any?>> = mapOf(
            // 2/3 层同策略：最多刷 2 次（要不要刷由 DP 按剩余货架算）
            2 to mapOf("max_refresh" to 2),
            3 to mapOf("max_refresh" to 2),
            // 4 层：**原样不动** —— 不刷新；不买 4 层商店专属件
            4 to mapOf("max_refresh" to 0, "no_area4_only" to true),
            // 5 层：最多刷 2 次（买 2★/3★ 由 DP 决定）
            5 to mapOf("max_refresh" to 2),
        )

        /** 没登记层数的兜底：不刷新、不买 4 层专属件 */
        val SHOP_DEFAULT_CFG: Map<String, Any?> = mapOf(
            "max_refresh" to 0, "no_area4_only" to false,
        )

        /** PC 版 `SHOP_VALUE`(4823)：商品分值 —— 遗物 200 分/件、印记（=角色）100 分/件 */
        val SHOP_VALUE: Map<String, Int> = mapOf("遗物" to 200, "印记" to 100)

        /** PC 版 `SHOP_GROUP_SIZE`(4835)：每页(组)商品数 → 屏上槽位 = page*3 + idx */
        const val SHOP_GROUP_SIZE = 3

    }

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

    // ────────────────────────── 精确 DP（3/5 层接管） ──────────────────────────

    /** 该层是否交给精确 DP（2/3 层 b=3、5 层 b=2；四层不动；未加载曲线则不接管） */
    fun dpEnabled(area: Int?): Boolean = dpModel(area) != null

    /** 该层对应的曲线（b=2 / b=3）；不支持返回 null。★2 层与 3 层同曲线（货架同样是 3 印记） */
    private fun dpModel(area: Int?): ShopDp.Set? {
        val all = dp ?: return null
        val b = when (area) {
            2, 3 -> 3
            5 -> 2
            else -> return null
        }
        return all[b]
    }

    /**
     * 折扣 → **整数**百分比（只在换算时用浮点，比较一律整数）。
     * 游戏实际只有 100/90/75/65；这里按 5 就近取整并夹在 65..100（曲线有 8 档，取不到时 [ShopDp.Set] 会兜到最近档）。
     */
    fun discPercent(sh: JSONObject): Int {
        val raw = Py.roundInt(discOf(sh) * 100)
        return ((raw / 5) * 5).coerceIn(65L, 100L).toInt()
    }

    /**
     * 本店**剩余可刷新次数**（2=刚进店、1=已刷 1 次、0=不能再刷）。
     *
     * 权威来源是**游戏自己**：agent 读 `get_resettableNum` → `labShop.resettable`（还剩几次可刷），
     * 不依赖任何进程记忆。[done] 只用来套 PC 的策略上限（本店最多刷 `max_refresh` 次）：
     *   · 游戏说还能刷 3 次、策略只许 2 次 ⇒ 按 2（策略上限）
     *   · 闪退重进后 `done` 归零、游戏还剩 1 次 ⇒ 按 1（游戏为准）
     * 只有 `resettable` 读不到时才退回 `max_refresh - done`（此时才真的依赖记忆，日志里会体现）。
     */
    fun remainingRefresh(area: Int?, sh: JSONObject, done: Int): Int {
        val cfg = SHOP_CFG[area] ?: SHOP_DEFAULT_CFG
        val byDone = (cfgInt(cfg, "max_refresh", 0) - done).coerceAtLeast(0)
        val left = Py.num(sh, "resettable")?.toInt()
        return if (left != null) minOf(byDone, left.coerceAtLeast(0)) else byDone
    }

    /**
     * 当前货架上「参与 DP 决策」的件（顺序 = 1★遗物 → 随机印记 → 2★遗物 → 3★遗物，同星级内按 [sortShopBucket]）。
     *
     * 过滤规则与 PC 的 `SHOP_BUY_ORDER` / `no_star` / `no_area4_only` 一致；**定向印记永不买**（PC 同款）。
     * 不看钱够不够（DP 自己按预算枚举），但**必须**排除已售/不在货架的（那正是「剩余货架」的定义）。
     */
    private fun dpCandidates(
        area: Int?, sh: JSONObject, relicCtx: RelicPick.Ctx?, buy2StarNow: Boolean,
    ): List<JSONObject> {
        val cfg = SHOP_CFG[area] ?: SHOP_DEFAULT_CFG
        val a4 = area4OnlyIds().map { it.toString() }.toSet()
        val noStar = cfgInts(cfg, "no_star")
        val noArea4 = cfgBool(cfg, "no_area4_only")
        // 3 层这是**本次一起接上**：原 cfg 没有 allow_3star（走阈值表），DP 接管后由算法自己决定买不买 3★
        val allowThree = cfgBool(cfg, "allow_3star") || dpEnabled(area)
        val order = buildList {
            add("1★遗物"); add("随机印记")
            if (buy2StarNow || dpEnabled(area)) add("2★遗物")   // 5 层本就恒允许；3 层看 buy2Star
            if (allowThree) add("3★遗物")
        }
        val itemsArr = sh.optJSONArray("items")
        val all = if (itemsArr == null) emptyList<JSONObject>()
        else (0 until itemsArr.length()).mapNotNull { itemsArr.optJSONObject(it) }
        val shelf = all.filter {
            !Py.truthy(it.opt("isSold")) && (Py.num(it, "availability")?.toInt() ?: -1) == 1
        }
        val out = mutableListOf<JSONObject>()
        for (want in order) {
            val bucket = mutableListOf<JSONObject>()
            for (it in shelf) {
                val (kind, info) = shopItemKind(it.opt("rewardId"))
                if (kind != want) continue
                if (info != null && ((Py.num(info, "rarity")?.toInt() ?: 0) in noStar)) continue
                if (noArea4 && it.opt("rewardId").toString() in a4) continue
                if ((Py.num(it, "price")?.toInt() ?: 0) <= 0) continue    // 99999999 = 不可售
                bucket.add(it)
            }
            out.addAll(if (sameStarRelicOrder) sortShopBucket(bucket, relicCtx) else bucket)
        }
        return out
    }

    /**
     * **全货架诊断**（DP 模式算清单时打一次）：每件 = 品类(实付价) + 状态。
     * 状态：`已售` / `不在货架`（availability≠1）/ `✓买`（进了清单）/ `✗不买`（清单不要它）。
     * 有了它，"某件 1★ 为什么没买" 一眼可判 —— 是读取没看到（`不在货架`）、还是 DP 只要 N 件（`✗不买`）。
     */
    fun shelfBrief(sh: JSONObject, picked: List<JSONObject>): String {
        val arr = sh.optJSONArray("items") ?: return ""
        val chosen = picked.map { it.toString() }.toHashSet()
        val parts = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val kind = shopItemKind(it.opt("rewardId")).first
            val price = Py.num(it, "price")?.toInt() ?: 0
            val mark = when {
                Py.truthy(it.opt("isSold")) -> "已售"
                (Py.num(it, "availability")?.toInt() ?: -1) != 1 -> "不在货架"
                it.toString() in chosen -> "✓买"
                else -> "✗不买"
            }
            parts.add("$kind($price)$mark")
        }
        return if (parts.isEmpty()) "" else "｜货架：${parts.joinToString(" ")}"
    }

    /**
     * DP 那一行的日志（给人看；`desc` 必须与 PC 逐字一致，不能塞进去）。
     * @param quota 当前生效的清单（按清单买时传它；刚算完时传 null 走 [lastQuota]/[dpLast]）
     */
    fun dpBrief(quota: Quota? = null): String {
        val q = quota ?: lastQuota
        if (q != null) return "｜DP清单：disc=${q.discPct}% 剩余刷新=$dpLastRemaining $q"
        val d = dpLast ?: return "｜DP：—"
        return "｜DP：disc=$dpLastDisc% 剩余刷新=$dpLastRemaining 买${Integer.bitCount(d.mask)}件" +
            "(花${d.cost}分${d.score})" + (if (d.refresh) " → 买完刷新" else " → 不刷") +
            " 期望=${Py.fmt2(d.value)}"
    }

    // ────────────────────────── 商品识别 / 效率 ──────────────────────────

    /**
     * PC 版 `buy_2star_relic`(4397)：config.json 的 `shop_buy_2star` ＞ 代码默认 [SHOP_BUY_2STAR]。
     * ⚠设置页那个「允许购买2星遗物」开关**已删**（2026-11-02）：3/5 层由精确 DP 决定，2★ 买不买不再可配；
     *   本函数只留作 PC 口径参考（旧逻辑 / 对拍）。
     */
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
        /**
         * ★已有的**购买清单**（[Quota]）。传了就**不跑 DP**，只按清单从当前货架挑件；
         * 传 null 时（进店/刷新后）才跑 DP，并通过 [takeQuota] 把新清单交给调用方。
         */
        quota: Quota? = null,
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
            if (want == "2★遗物" && !buy2Star) continue
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
        // ★③ 精确 DP 接管（3/5 层，[dp] 非 null 时）：**覆盖**上面 ① 的结果。
        //   上面那段是 PC 旧逻辑（对拍基线/关闭 DP 时用），这里按「当前剩余货架 + 当前金币 + 剩余刷新次数」
        //   枚举子集重算 —— 所以「进店时已经买过几件」也能正常算（旧逻辑只会在满货架上想问题）。
        val model = dpModel(area)
        if (model != null) {
            val cand = dpCandidates(area, sh, relicCtx, buy2Star)
            lastCandCount = cand.size          // ★给调用方判"空清单可不可信"（见 lastCandCount 注释）
            val discPct = discPercent(sh)
            val remaining = remainingRefresh(area, sh, done)
            if (quota != null) {
                // ★**按清单买**（2026-11-02 用户）：清单是进店/刷新后算好的，执行期只照着挑件 ——
                //   不重跑 DP，所以中途买掉一件不会把后面的计划改掉（日志也稳定）。
                //   「具体哪个遗物 / 按钮坐标」仍旧每轮现读现取（候选里已按同星级序排好）。
                picked.clear()
                picked.addAll(pickByQuota(cand, quota, coin))
                dpSuggestRefresh = quota.refresh
                dpLastDisc = quota.discPct
                dpLastRemaining = remaining
                dpLast = null
            } else {
                val items = cand.map {
                    ShopDp.Item(Py.num(it, "price")?.toInt() ?: 0, shopValueOf(kindOf(it)))
                }
                val dec = ShopDp.decide(items, coin, discPct, remaining, model)
                dpLast = dec
                dpLastDisc = discPct
                dpLastRemaining = remaining
                dpSuggestRefresh = dec.refresh
                picked.clear()
                var q1 = 0
                var q2 = 0
                var q3 = 0
                var qb = 0
                for (i in cand.indices) if (dec.buys(i)) {
                    when (kindOf(cand[i])) {
                        "1★遗物" -> q1++
                        "2★遗物" -> q2++
                        "3★遗物" -> q3++
                        else -> qb++
                    }
                    picked.add(cand[i])
                }
                // 产出清单给调用方（下轮起按清单买，直到刷新/换店）
                lastQuota = Quota(q1, q2, q3, qb, dec.refresh, discPct)
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
            // ★2026-11-07：这里以前写"可刷新 N 次"，但 N 是**本店刷新上限**（cfg.max_refresh），
            //   和 DP 那行的"剩余刷新"（上限 − 已刷）不是一回事，并排看像自相矛盾 ⇒ 措辞改准。
            " 本店刷新上限${cfgInt(cfg, "max_refresh", 0)}次(门槛${Py.listStr(cfgInts(cfg, "refresh_above"))})" +
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
     * 「该不该刷新」—— **2/3/5 层只由 DP 决定**（结论在 [shopPlan] 同一轮里算好，见 [dpSuggestRefresh]）：
     *   刷得起、本店次数没用完、游戏 `resettable` > 0，这三条硬约束照旧由这里把关。
     *
     * ★PC 时代的 ①~⑤（买优先 / q1 门槛 / `refresh_above`）与 5 层 MDP 已于 2026-11-4 全部删除
     *   —— 那条路径只在 dp=null 时才会跑到，而 dp=null 现在直接"不刷新"。
     *
     * @param q1 已废弃（保留参数只为调用点不动；DP 判据不用它）
     */
    fun refreshDecision(area: Int?, sh: JSONObject, done: Int, q1: Double?): RefreshDecision {
        val cfg = SHOP_CFG[area] ?: SHOP_DEFAULT_CFG
        val coin = Py.num(sh, "coin")?.toInt() ?: 0
        val cost = Py.num(sh, "rerollCost")?.toInt() ?: 0
        val left = Py.num(sh, "resettable")?.toInt()          // 游戏自带的"还剩几次可刷"
        val mx = cfgInt(cfg, "max_refresh", 0)
        val thr = cfgInts(cfg, "refresh_above")

        // ★精确 DP 接管（3/5 层）：用 [ShopDp] 的「买完是否刷新」结论替代 q1 / refresh_above / 阈值表；
        //   刷得起、还剩次数、游戏还允许刷这些**硬约束照旧**。结论来自同一轮 [shopPlan]（DP 已按
        //   「当前剩余货架 + 剩余刷新次数」算过），所以「买了一半」「闪退重进」都不会误判。
        val dpm = dpModel(area)
        if (dpm != null) {
            val remaining = remainingRefresh(area, sh, done)
            val want = dpSuggestRefresh
            val can = remaining > 0 && cost != 0 && coin >= cost && (left == null || left > 0)
            val refresh = can && want
            val why = when {
                cost == 0 -> "无刷新按钮"
                remaining <= 0 -> "DP：本店刷新次数已用完"
                coin < cost -> "刷不起（金币 $coin < 费 $cost）"
                left != null && left <= 0 -> "游戏已无可刷次数"
                !want -> "DP：刷新的期望不高于现在买（剩余 $remaining 次）"
                else -> ""
            }
            val detail = "DP刷新评估：disc=${dpLastDisc}% 剩余=$remaining 余额=$coin 费=$cost " +
                "建议=${if (want) "刷" else "不刷"}"
            return RefreshDecision(refresh, null, why, detail)
        }

        // ★DP 不接管（4 层 / 曲线未加载）⇒ 一律不刷新。
        //   PC 时代的 q1 / refresh_above / 阈值表判据已于 2026-11-4 删除（DP 接管后全是死代码）。
        return RefreshDecision(false, null, "本层不刷新（4 层或未启用 DP）", "不刷新")
    }

    // ────────────────────────── 配置读取小工具 ──────────────────────────

    private fun cfgInt(cfg: Map<String, Any?>, key: String, dflt: Int): Int =
        (cfg[key] as? Number)?.toInt() ?: dflt

    private fun cfgBool(cfg: Map<String, Any?>, key: String): Boolean =
        cfg[key]?.let { Py.truthy(it) } ?: false

    private fun cfgInts(cfg: Map<String, Any?>, key: String): List<Int> =
        (cfg[key] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
}
