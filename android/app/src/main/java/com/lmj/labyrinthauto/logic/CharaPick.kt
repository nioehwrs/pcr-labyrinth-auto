package com.lmj.labyrinthauto.logic

import android.os.SystemClock
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.labmodel.LabModel
import com.lmj.labyrinthauto.data.GuildPoolData
import com.lmj.labyrinthauto.data.RelicTables
import com.lmj.labyrinthauto.data.Tables
import com.lmj.labyrinthauto.frida.AgentApi
import com.lmj.labyrinthauto.input.InputBackend
import com.lmj.labyrinthauto.logic.team.Rules
import com.lmj.labyrinthauto.logic.team.TagIds
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * **选角色页**（SUMMON_UNIT 的对话框面孔）—— PC 版 `handle_summon_unit`(1819) 的 ① 那条路：
 *
 * ```
 * labCharaPageQuick()                        ← 精准读取（PartsDialogLabyrinthTicketAcquireCharacter）
 *   → _guild_rule_line(1438)                 ← 按"五公会固定三人"识别当前公会（dry-run）
 *   → _auto_pick_chara(1359)                 ← 连点补齐/取消（一批点完、下一轮回读核对）
 *   → 齐了点「去邀请」(decideButton+0xA8)
 * ```
 *
 * 数据：`lab_guild_pool.json`（五公会花名册，识别与默认 trio 见 `GameData.guildPool`；
 *   实际选哪三个由 `pickResolver` 取用户自选名单，空则回退默认 trio）。
 *
 * ★动作状态（`_CHARA_ACT` 1590）按**页面实例 `ta`** 记，跨轮保留 —— 连点批次/是否已邀请都靠它；
 *   PC 在"换页"时**不清**它（键本来就随页面变）。
 */
class CharaPick(
    private val tables: Tables,

    private val pool: GuildPoolData,
    private val pickResolver: (Int) -> List<Int>,
    private val rules: Rules,
    private val relics: RelicTables,
    /** ★属性印记选择顺序（2026-11-13 用户）：门票三选一按它取第一张；默认沿用旧的 暗>火>光>风>水。 */
    private val attrOrder: List<String> = ATTR_ORDER,
    /** ★不使用 NPC 角色（2026-11-13 用户）：战后角色三选一先跳过 agent 标了 `npc` 的卡；
     *  三张全是 NPC 时忽略该设置（照常选，别停手）。 */
    private val excludeNpc: Boolean = false,
    /** ★三选一用推理模型（2026-11-14 用户）：算不出时静默走规则版。 */
    private val byModel: Boolean = false,
) {

    /**
     * 模型版选人的缓存（同一组卡 + 同一池 + 同一目标 ⇒ 结果复用，别每轮重算 4 秒）。
     * ★**只存下标，不能存整条结果**：结果里带着 cards[idx] 的 JSON 快照，缓存它会让后续每轮都用
     * "卡片还在滑入动画中"的旧坐标（实测 decideY=1187 在屏外，卡死 300+ 轮）。
     */
    private var modelKey: String? = null
    private var modelIdx: Int = -1

    /**
     * ★2026-11-12（用户）：点过「去邀请」后，**必定**弹一个「收到角色」框（和买印记的到账框同款）。
     *   `autoPick` 只负责点「去邀请」，框由 [MazeLoop] 的状态链接住并点掉；
     *   这里只留一个一次性标志，让调用方知道"该开始收框了"。
     */
    @Volatile private var invitedPending = false
    private var justInvited: Boolean
        get() = invitedPending
        set(v) { invitedPending = v }

    /**
     * ★★2026-11-20（用户）：直调是**真 onClick**，同一个 unitId 重复投 = 重复选中/取消。
     * 这里记"刚投过的 uid + 时刻"，[PRESS_HOLD_MS] 内不再投（等下一轮回读 `picked` 复核）。
     */
    private val pressedAt = HashMap<Int, Long>()
    private val loggedNoWin = HashSet<Int>()

    /** 取走「刚点过去邀请」标志（取一次就清，避免重复进收框链） */
    fun consumeInvited(): Boolean {
        val v = invitedPending
        invitedPending = false
        return v
    }

    companion object {
        /** PC: `LAB_CHARA_AUTO`(1488) / `LAB_CHARA_INVITE`(1489) —— 自动点选 + 自动邀请 */
        const val AUTO = true
        const val INVITE = true

        /** 慢做法（BURST=false）的护栏：单页最多点几次 */
        const val MAX_TAPS = 8                 // PC: LAB_CHARA_MAX_TAPS(1490)
        const val TAP_GAP_MS = 1_400L          // PC: LAB_CHARA_TAP_GAP(1491)

        /** ★连点（PC: LAB_CHARA_BURST 1492）：一次把"取消多余+补齐缺失"全点掉，三格之间不回读 */
        const val BURST = true
        /** ★2026-11-12（用户："组队页飞快，初始选还在一个个点"）：格间间隔 350 → 120。
         *  `input.tap` 本身 ~110ms，120ms 足够游戏识别成两次独立点击（编队页就是无间隔连点）；
         *  万一太快被吞，下一轮回读会纠正（`batch` 最多 2 批）。 */
        const val TAP_MS = 120L
        /** 连点后回读静置：原来 1.2s 是保守值，现在 600ms（核对由下一轮回读做，点错会补点） */
        const val BURST_SETTLE_MS = 600L
        const val BATCH_MAX = 2                // PC: LAB_CHARA_BATCH_MAX(1495)：最多连点几批

        /** ★2026-11-12（用户）：本页**第一次点击前**等 120ms —— 和格间间隔一致；
         *  实测 60ms 时首批第一个偶发被吃掉（`已选 2/3`），补点一次即可接受（`batch` 最多 2 批）。 */
        const val FIRST_TAP_DELAY_MS = 120L

        /** ★格子坐标「尚未落位」时最多等几轮（2026-10-18）：页面刚弹出那几拍 cells 还没有 x/y，
         *  早期实现一见没坐标就 `done=true` 永久停手 ⇒ 新模拟器（首读略慢）必现「卡在选角色页不点」。
         *  现在**没坐标就下一轮再读**，只有连这么多轮都读不到才真停手（≈12s，防真坏页面死循环）。 */
        const val MAX_NO_XYZ_ROUNDS = 12

        /** ★2026-11-20：同一 uid 的直调去重窗口（直调=真 onClick，重复投会重复选中）。 */
        const val PRESS_HOLD_MS = 2_000L

        // ── 选人策略常量（PC pcr_auto.py 常量区 420~443 逐字）──
        const val PICK_MIN_TIER = 0            // PICK_MIN_TIER：特殊优先只对够格候选举手（0=不限）
        const val DOT_KINDS_MAX = 3            // PICK_RULES["dot_kinds_max"]
        const val DOT_SOFT_BONUS = 1           // PICK_RULES["dot_soft_bonus"]
        const val TANK_AREA = 3                // PICK_RULES["tank_area"]

        /** 属性次序（PC ATTR_ORDER / PICK_ATTR_ORDER / TICKET_ATTR_ORDER 同源）：暗>火>光>风>水。
         *  ★2026-11-13 起可通过策略页「角色选择 → 属性印记选择顺序」调整；这里只作默认值。 */
        val ATTR_ORDER: List<String> = listOf("暗", "火", "光", "风", "水")
    }

    /** PC `_CHARA_ACT`(1590)：页面实例(ta) → 动作状态 */
    private class Act {
        var n = 0
        var batch = 0
        var invited = false
        var at = 0L
        var done = false
        var noXyz = 0        // 连续「格子坐标未落位」轮数（见 [MAX_NO_XYZ_ROUNDS]）
    }

    private val acts = HashMap<String, Act>()

    fun uname(uid: Int): String =
        tables.unit(uid.toString())?.optString("name")?.takeUnless { it.isEmpty() } ?: "?"

    /** PC 选人策略用：角色 labyrinth 评级（tier，5=S…1=D；0=未评级）—— `unit_tags.json` 的 `tier` */
    fun tierOf(uid: Int): Int = tables.tags[uid.toString()]?.optInt("tier") ?: 0

    /** PC 选人策略用：角色属性（火/水/风/光/暗）—— `units.json` 的 `attr` */
    fun attrOf(uid: Int): String = tables.unit(uid.toString())?.optString("attr") ?: ""

    // ────────────────────────── 战后/门票三选一决策（P4，不点击）──────────────────────────
    // 逐字对应 PC pcr_auto.py：常量区(420~443) + lab_pick_ctx(4110) + lab_select_rank(4153)
    //   + handle_ticket_choice(4333)。底座 + L5~L1 特殊优先全在这里，handler 只取 PLAN 点击。

    /** 遗物 id 规律兜底（PC DOT_RELIC_ID_RE）：x2003=毒 / x2002=诅咒（1/2/3★ 各一件） */
    private val DOT_RELIC_ID_RE = Regex("^[123]200[23]$")

    private fun attrIdx(attr: String): Int {
        val i = attrOrder.indexOf(attr)
        return if (i < 0) attrOrder.size else i
    }

    /** 卡面属性：优先按 unitId 查表；门票/遗物卡可能无 unitId → 从 topText/bottomText 抠 暗火光风水 */
    fun cardAttr(c: JSONObject): String {
        val uid = (c.opt("unitId") as? Number)?.toInt() ?: 0
        val a = attrOf(uid)
        if (a.isNotEmpty()) return a
        val txt = "${c.opt("topText")} ${c.opt("bottomText")}"
        for (at in ATTR_ORDER) if (txt.contains(at)) return at
        return ""
    }

    /** 遗物是否 dot 类（削弱系列：中毒/诅咒）→ 种类名集合（PC `dot_relic_kinds`(4096) 同款） */
    private fun relicDotOf(rid: Any?): Set<String> {
        val info = relics.rk(rid)
        val e = info?.optString("effect").orEmpty()
        val ks = listOf("中毒", "诅咒").filter { it in e }
        if (ks.isNotEmpty()) return ks.toSet()
        val s = rid?.toString() ?: "None"
        return if (DOT_RELIC_ID_RE.matches(s)) setOf(if (s.endsWith("3")) "中毒" else "诅咒") else emptySet()
    }

    /**
     * PC `lab_pick_ctx`(4110)：把"本局已持有角色/遗物 + 层数"换算成能力集合。
     * unitIds/relicIds 来自 agent 的 labUnits/labOwnRelics；relicDot 由 [relicDotOf] 算。
     */
    fun buildCtx(area: Int?, unitIds: List<Int>, relicIds: List<Any?>, nRelics: Int): LabSelCtx {
        val tankTag = TagIds.T["tank"]!!; val mtankTag = TagIds.T["mtank"]!!
        val knockTag = TagIds.T["knock"]!!; val drainTag = TagIds.T["drain"]!!
        val dotKinds = HashSet<String>()
        var hasTank = false; var hasMtank = false; var hasKnock = false; var hasDrainTank = false
        val ownFix = HashSet<String>(); val fixBest = HashMap<String, Int>()
        for (uid in unitIds) {
            val s = uid.toString()
            val ts = rules.tagsOf(s)
            val tier = rules.tierOf(s) ?: 0
            dotKinds.addAll(rules.dotKindsOf(s))
            if (tankTag in ts) hasTank = true
            if (mtankTag in ts) hasMtank = true
            if (knockTag in ts) hasKnock = true
            if (tier >= 5 && drainTag in ts && (tankTag in ts || mtankTag in ts)) hasDrainTank = true
            for (ft in TagIds.FIX_TAGS.keys) if (ft in ts) {
                ownFix.add(ft); fixBest[ft] = fixBest.getOrDefault(ft, 0).coerceAtLeast(tier)
            }
        }
        val relicDot = HashSet<String>(); for (rid in relicIds) relicDot.addAll(relicDotOf(rid))
        return LabSelCtx(area, unitIds.size, nRelics, dotKinds, relicDot,
            hasTank, hasMtank, hasKnock, hasDrainTank, ownFix, fixBest, "", own = unitIds)
    }

    /**
     * PC `lab_select_rank`(4153) —— 战后"选取奖励角色"三选一，返回要选的那张卡（= PLAN，**不点击**）。
     * 排序键 = (-level, -(tier+soft), 属性序号, 卡片序号)；level：5=dot / 4=坦克 / 3=强击退 /
     * 2=抽蓝坦克 / 1=对策 / 0=纯评分（属性只在同评级内起作用、不越级——由排序键天然保证）。
     * ★PC 表示不一致（原样保留）：ctx.dotKinds 是标签 id、ctx.relicDot 是中文名，union 不重叠 ⇒
     *   dotTotal 实为两边条目相加（不是"去重种类数"）。
     */
    fun pickLabReward(
        cards: List<JSONObject>,
        ctx: LabSelCtx,
        questId: Int = 0,
        /** ★多目标（2026-11-14 用户："按已选全部敌人加权"）：关卡 questId → 权重 */
        targets: List<Pair<Int, Double>> = emptyList(),
    ): LabPickResult? {
        if (cards.isEmpty()) return null
        // ★推理模型（2026-11-14 用户）：逐个候选算「加进池子后最优队胜率提升」，多目标按权重加权；
        //   模型不可用 / 关卡不在词表 / 候选全不认识 ⇒ 静默走下面的规则版。
        if (byModel && ctx.own.isNotEmpty()) {
            // ★不选 NPC 角色（2026-11-13 用户）：模型路径也必须遵守 —— 先把 NPC 卡从候选里剔除
            //   （与下方规则版同口径：三张全 NPC 时忽略该设置）。模型只在剩下的卡里算、选。
            val candIdx = if (excludeNpc) {
                val nonNpc = cards.indices.filter { !cards[it].optBoolean("npc", false) }
                if (nonNpc.isNotEmpty()) nonNpc else cards.indices.toList()
            } else {
                cards.indices.toList()
            }
            val candCards = candIdx.map { cards[it] }
            val ids = candCards.map { (it.opt("unitId") as? Number)?.toInt() ?: 0 }
            // ★缓存（2026-11-14）：模型算一次要 4~5 秒，而 `handleLabSelection` 每轮循环都会调进来，
            //   卡片还没被点中之前会**重复算好几遍**（实测同一组卡算了两次，白等一倍时间）。
            //   key = 候选卡（已排除 NPC）+ 持有池 + 目标关卡，任一变化就重算；modelIdx 存候选下标，取时映射回原 cards。
            val key = ids.joinToString(",") + "|" + ctx.own.sorted().hashCode() + "|" +
                targets.joinToString(",") { it.first.toString() } + "|" + questId
            if (key == modelKey && modelIdx in candCards.indices) {
                // ★用**当前这轮**的卡片对象（坐标是新的），只复用下标
                val i = candIdx[modelIdx]
                AppState.addLog("选角色：模型版（命中缓存）→ 第 ${i + 1} 张")
                return LabPickResult(cards[i], i, "模型：胜率增益最高", 0, 0)
            }
            val qs = targets.ifEmpty {
                if (questId > 0) listOf(questId to 1.0) else emptyList()
            }
            val t0 = SystemClock.elapsedRealtime()
            val idx = LabModel.pickChar(ids, ctx.own, qs, 2)
            if (idx != null && idx in candCards.indices) {
                val i = candIdx[idx]
                AppState.addLog(
                    "选角色：模型版 ${SystemClock.elapsedRealtime() - t0}ms（${qs.size} 个目标）" +
                        " → 第 ${i + 1} 张（unitId=${ids[idx]}）",
                )
                modelKey = key
                modelIdx = idx
                return LabPickResult(cards[i], i, "模型：胜率增益最高", 0, 0)
            }
        }
        val tankTag = TagIds.T["tank"]!!; val mtankTag = TagIds.T["mtank"]!!
        val knockTag = TagIds.T["knock"]!!; val drainTag = TagIds.T["drain"]!!
        // ★不使用 NPC 角色（2026-11-13 用户）：agent 的 labSelection 给每张卡带 `npc`（unitType==2）。
        //   先剔掉 NPC 卡；**三张全是 NPC 时忽略该设置**（照常选，别停手）。
        //   用原始下标取卡，不要重排 —— 返回的 index 要拿去点第几张卡。
        val idxs = if (excludeNpc) {
            val nonNpc = cards.indices.filter { !cards[it].optBoolean("npc", false) }
            if (nonNpc.isNotEmpty()) nonNpc else cards.indices.toList()
        } else {
            cards.indices.toList()
        }
        val rows = ArrayList<RankRow>(idxs.size)
        for (i in idxs) {
            val c = cards[i]
            val uid = (c.opt("unitId") as? Number)?.toInt() ?: 0
            val s = uid.toString()
            val ts = if (uid != 0) rules.tagsOf(s) else emptySet()
            val tier = if (uid != 0) (rules.tierOf(s) ?: 0) else 0
            val dotk = if (uid != 0) rules.dotKindsOf(s) else emptySet()
            val attr = if (uid != 0) (tables.unit(s)?.optString("attr") ?: "") else cardAttr(c)
            val aidx = attrIdx(attr)
            var lvl = 0; var why = "评分"; var soft = 0; var softWhy = ""
            val ok = tier >= PICK_MIN_TIER            // PICK_MIN_TIER=0 ⇒ 只要有表就举手
            val dotTotal = (ctx.dotKinds union ctx.relicDot).size
            if (ok && dotk.isNotEmpty()) {
                if (dotTotal == 0) { lvl = 5; why = "dot（当前角色与遗物都没有 dot）" }
                else if (dotTotal < DOT_KINDS_MAX) {
                    soft = DOT_SOFT_BONUS
                    softWhy = "dot（已有 %d 类 dot，给 +%d 档软优先）".format(dotTotal, soft)
                }
            }
            if (lvl == 0 && ok && (ctx.area ?: 0) >= TANK_AREA && !ctx.hasTank && !ctx.hasMtank
                && (tankTag in ts || mtankTag in ts)) {
                lvl = 4
                why = "坦克（第 %s 层，队里没有坦克）%s".format(ctx.area, if (mtankTag in ts) "（兼职坦克）" else "")
            }
            if (lvl == 0 && ok && knockTag in ts && !ctx.hasKnock) { lvl = 3; why = "强击退（第一个）" }
            if (lvl == 0 && ok && !ctx.hasDrainTank && tier >= 5 && drainTag in ts
                && (tankTag in ts || mtankTag in ts)) { lvl = 2; why = "S 级「抽蓝+坦克」（第一个）" }
            if (lvl == 0 && ok) {
                val fix = TagIds.FIX_TAGS.keys.filter { it in ts }
                val newFix = fix.filter { it !in ctx.ownFix }
                if (newFix.isNotEmpty()) {
                    lvl = 1; why = "对策（当前没有）：" + newFix.joinToString("、") { TagIds.FIX_TAGS[it] ?: it }
                } else if (fix.isNotEmpty() && tier > (fix.map { ctx.fixBest.getOrDefault(it, 0) }.maxOrNull() ?: 0)) {
                    lvl = 1; why = "对策（已持有但这张评级更高）：" + fix.joinToString("、") { TagIds.FIX_TAGS[it] ?: it }
                }
            }
            if (lvl == 0 && soft != 0) why = softWhy
            rows.add(RankRow(c, i, why, lvl, tier, soft, aidx))
        }
        rows.sortWith(compareBy({ -it.lvl }, { -(it.tier + it.soft) }, { it.aidx }, { it.i }))
        val top = rows.first()
        // ★★"这是真判出来的，还是被缺省带走的？"（2026-10-17 用户："比如水兔，是不是默认选1了"）
        //   排序键最后一档是**卡片序号** ⇒ 三张在（评级+软加分 / 属性）上完全相同时，必然选序号最小的
        //   那张（PC 同款缺省）。在"确实看不出差别"时这合理，但必须**可见**，不能悄悄发生 ✗。
        //   所以把两种情况写进 `why`（会被打日志）：
        //     ① 前两张完全同分 ⇒ 「按卡片序号取第 N 张」（= 用户感觉到的"默认选 1"）；
        //     ② 某张查不到评级（unit 表缺项）⇒ 「⚠N 张查不到评级」——**这才是最常见的根因**：
        //        评级全是 0 ⇒ lvl 全 0 ⇒ 同分 ⇒ 永远选第 1 张。
        val tied = rows.size > 1 && rows[1].let {
            it.lvl == top.lvl && (it.tier + it.soft) == (top.tier + top.soft) && it.aidx == top.aidx
        }
        val noTier = rows.count { it.tier <= 0 && ((it.c.opt("unitId") as? Number)?.toInt() ?: 0) != 0 }
        val noId = rows.count { ((it.c.opt("unitId") as? Number)?.toInt() ?: 0) == 0 }
        var whyOut = top.why
        if (noTier > 0) whyOut += "｜⚠$noTier 张查不到评级（表里没这个 unitId）"
        if (noId > 0) whyOut += "｜⚠$noId 张没读出 unitId"
        if (tied) whyOut += "｜前两张完全同分 ⇒ 按卡片序号取第 ${top.i + 1} 张"
        return LabPickResult(top.c, top.i, whyOut, top.lvl, top.tier)
    }

    /**
     * PC `handle_ticket_choice`(4333) —— 门票节点三张「某属性的随机印记×3」，按属性顺序（暗>火>光>风>水）
     * 取第一个匹配上的。属性从卡面文案（topText/bottomText）抠（卡无 unitId）。
     * ★2026-10-16 用户："**都不要随便选**" ⇒ **三张都没读出属性就返回 null（不猜）**，调用方会走
     *   [MazeLoop.selNoDecide]（不点 → 有界自愈重启 → 停手留人工）。原来这里"取第 1 张"就是瞎选。
     */
    fun pickTicket(cards: List<JSONObject>): LabPickResult? {
        if (cards.isEmpty()) return null
        for (attr in attrOrder) {
            val hit = cards.firstOrNull { cardAttr(it) == attr } ?: continue
            return LabPickResult(hit, cards.indexOf(hit), "门票：$attr 属性优先", 0, 0)
        }
        return null                    // ★读不出属性 ⇒ 不猜（原来取第 1 张）
    }

    /** 决策上下文（PC `lab_pick_ctx`）：当前局面 = 层数 + 本局已持有角色/遗物 → 已具备的能力集合 */
    data class LabSelCtx(
        val area: Int?, val nUnits: Int, val nRelics: Int,
        val dotKinds: Set<String>, val relicDot: Set<String>,
        val hasTank: Boolean, val hasMtank: Boolean, val hasKnock: Boolean, val hasDrainTank: Boolean,
        val ownFix: Set<String>, val fixBest: Map<String, Int>, val err: String,
        /** ★本局持有角色（2026-11-14）：三选一接推理模型时要用。 */
        val own: List<Int> = emptyList(),
    )

    /** 决策结果（= PLAN）：选中哪张卡 + 为什么（供日志/统计复盘） */
    data class LabPickResult(val card: JSONObject, val index: Int, val why: String, val level: Int, val tier: Int)

    /** 排序中间行（PC `lab_select_rank` 的 out 元素） */
    private data class RankRow(val c: JSONObject, val i: Int, val why: String, val lvl: Int, val tier: Int, val soft: Int, val aidx: Int)

    // ────────────────────────── 识别公会（dry-run）──────────────────────────

    /**
     * 替代 PC `_guild_rule_line`(1438)：用「花名册」识别当前公会（候选角色全部落进某公会 ⇒ 唯一命中），
     * 再按【用户自选 / 默认 trio】算出应选的精确 unitId（按角色去重；误配/本次未提供的跳过）。
     *
     * @return (公会显示名, 应点击的 unitId 列表)；识别不出 / 无有效选点 → null
     */
    fun ruleLine(q: JSONObject, firstTime: Boolean): Pair<String, List<Int>>? {
        if (pool.guilds.isEmpty()) return null
        val cands = intList(q, "cands")
        // ★2026-11-14：旧日志把「候选池为空」和「有角色不属本公会」混成同一句"匹配不到"，
        //   排查时只能靠猜。这里分开，并且首次失败就把 cands 原样打出来（最多 40 个）。
        if (cands.isEmpty()) {
            if (firstTime) {
                AppState.addLog(
                    "[规则] 选角色页候选池为空（cands=[]）：已选 ${intList(q, "picked").size} 个、" +
                        "可选数=${q.opt("selectableNum")}、节点=${q.optString("node")} " +
                        "→ 读不到候选，不是花名册漏角色",
                )
            }
            return null
        }
        val labId = pool.identify(cands) ?: run {
            if (firstTime) {
                AppState.addLog(
                    "[规则] 候选池匹配不到公会花名册：cands=" + cands.take(40).joinToString(",") +
                        " ／ /100=" + cands.take(40).map { it / 100 }.joinToString(","),
                )
            }
            return null
        }
        val g = pool.guilds[labId]!!
        val picks = pickResolver(labId)
        val desired = pool.desiredUnitIds(labId, picks)
        if (desired.isEmpty()) {
            if (firstTime) AppState.addLog("[规则] 公会 ${g.cn}：无有效选点（自选为空且默认 trio 缺失）")
            return null
        }
        // 把"想要的精确形态"落到屏上实际提供的形态：精确命中优先，否则取该角色任一提供形态
        val candByChar = cands.groupBy { it / 100 }
        val click = LinkedHashSet<Int>()
        val skipped = mutableListOf<String>()
        for (d in desired) {
            val offers = candByChar[d / 100] ?: run { skipped.add(uname(d)); null }
                ?: continue
            click.add(offers.firstOrNull { it == d } ?: offers.first())
        }
        if (click.isEmpty()) {
            if (firstTime) AppState.addLog("[!] 公会 ${g.cn}：候选池里一个都没给（${skipped.joinToString("、")}）")
            return null
        }
        if (firstTime) {
            AppState.addLog(
                "[规则] 公会开局花名册：" + pool.guilds.entries.joinToString(" ｜ ") { (id, gg) ->
                    "$id=${gg.cn}→" + gg.defaultPicks.joinToString("、") { uname(it) }
                },
            )
        }
        val pk = intList(q, "picked")
        val miss = click.filter { it !in pk }
        val extra = pk.filter { it !in click }          // 规则之外被选的（多选/选错）
        val plan = if (miss.isEmpty()) "（已齐 ✓）" else miss.joinToString("、") { u -> "$u ${uname(u)}" }
        AppState.addLog(
            "[规则] 当前公会=${g.cn} → 应选 ${click.joinToString("、") { uname(it) }}" +
                " ｜ 已选 ${pk.size}/${click.size} ｜ 还需点 $plan" +
                (if (extra.isEmpty()) "" else "　⚠规则外已选 " + extra.joinToString("、") { "$it ${uname(it)}" }) +
                (if (skipped.isEmpty()) "" else "　⚠本次未提供 " + skipped.joinToString("、") + "（跳过）"),
        )
        return g.cn to click.toList()
    }

    // ────────────────────────── 自动点选 ──────────────────────────

    /**
     * PC `_auto_pick_chara`(1359)：按公会固定三人自动点选。
     * ★连点：一次把"取消多余的 + 补齐缺的"全点掉（间隔 [TAP_MS]，中间不回读）→
     *   静置 [BURST_SETTLE_MS] → **下一轮回读时核对** → 齐了就点「去邀请」。
     */
    suspend fun autoPick(input: InputBackend, q: JSONObject, ids: List<Int>) {
        val ta = q.optString("ta")
        val act = acts.getOrPut(ta) { Act() }
        if (act.done) return
        val now = System.currentTimeMillis()
        if (BURST) {
            if (now < act.at) return                       // 连点后的静置：等游戏把这批点击吃进去
        } else if (now - act.at < TAP_GAP_MS) {
            return
        }
        val cellOf = cellMap(q)
        val pk = intList(q, "picked")
        val want = ids.filter { it !in pk }                // 还缺的
        val extra = pk.filter { it !in ids }               // 规则外多选的

        if (want.isNotEmpty() || extra.isNotEmpty()) {     // ① 还没对齐 → 点
            if (BURST) {
                if (act.batch >= BATCH_MAX) {
                    act.done = true
                    AppState.addLog("[!] 连点 $BATCH_MAX 批仍未对齐（已选=${pk.joinToString("、")}) → 停手")
                    return
                }
                val seq = extra.map { "取消" to it } + want.map { "点选" to it }
                val noxy = seq.map { it.second }.filter { !hasX(cellOf[it]) }
                if (noxy.isNotEmpty()) {
                    // ★页面刚弹出那几拍 cells 还没 x/y（滑入动画）——**不能**永久停手，下一轮再读；
                    //   只有连 [MAX_NO_XYZ_ROUNDS] 轮都读不到（真坏页面）才 done。
                    act.noXyz++
                    if (act.noXyz == 1) {
                        AppState.addLog(
                            "[…] 选角色格子坐标还没落位，等页面稳定后重试：" +
                                noxy.joinToString("、") { "$it ${uname(it)}" },
                        )
                    }
                    if (act.noXyz >= MAX_NO_XYZ_ROUNDS) {
                        act.done = true
                        AppState.addLog(
                            "[!] 连续 ${MAX_NO_XYZ_ROUNDS} 轮读不到格子坐标 → 停手：" +
                                noxy.joinToString("、") { "$it ${uname(it)}" },
                        )
                    }
                    return
                }
                act.noXyz = 0
                // ★本页**第一次点击前**等 [FIRST_TAP_DELAY_MS]（2026-10-17 用户："初始选角色加 0.5 延迟"）：
                //   页面刚弹出来时格子还在落位，一次埋 3 个点上去会点歪（实测把「铃莓（春日）」
                //   点成了「铃莓（新年）」✗，靠下一轮回读才纠正）。只等一次，之后的重试/补点不再等。
                val firstTap = act.n == 0
                act.batch++
                act.n += seq.size
                act.at = now + BURST_SETTLE_MS + (if (firstTap) FIRST_TAP_DELAY_MS else 0L)
                AppState.addLog(
                    "选择角色：" + seq.joinToString("、") { (a, u) ->
                        "$u ${uname(u)}" + (if (a == "取消") "（取消）" else "")
                    },
                )
                if (firstTap) {
                    AppState.addLog("… 选角色页：第一次点前等 ${FIRST_TAP_DELAY_MS}ms 让格子落位")
                    delay(FIRST_TAP_DELAY_MS)
                }
                for ((_, u) in seq) {
                    // ★★2026-11-20（用户）：**直调优先** —— `onClickUnitIconCallback(IUnitIcon)`，
                    //   按 uid 在当前滑窗里定位，不投坐标（不吃滑入动画、不会点歪）。
                    //   ★同一 uid [PRESS_HOLD_MS] 内只投一次；投过就不再投、也不再补坐标点击。
                    val nowP = SystemClock.elapsedRealtime()
                    val lastP = pressedAt[u] ?: 0L
                    var handled = false
                    if (nowP - lastP >= PRESS_HOLD_MS) {
                        val pr = runCatching { AgentApi.labCharaPagePress("pick", u) }.getOrNull()
                        if (pr?.optBoolean("ok") == true) {
                            pressedAt[u] = nowP
                            handled = true
                        } else if (pr?.optBoolean("notInWindow") == true && loggedNoWin.add(u)) {
                            AppState.addLog("… 选角色 $u 不在当前滑窗内 → 退回坐标点击")
                        }
                    } else {
                        handled = true      // 刚投过，这一轮不重复投
                    }
                    if (!handled) {
                        val c = cellOf[u]!!
                        tap(input, c.optInt("x"), c.optInt("y"), "$u ${uname(u)}")
                    }
                    if (TAP_MS > 0) delay(TAP_MS)
                }
                return
            }
            // 慢做法：每轮只点一格、每步回读
            val u = (extra + want).first()
            if (act.n >= MAX_TAPS) {
                act.done = true
                AppState.addLog("[!] 已点 $MAX_TAPS 次仍未对齐 → 停手（防乱点）")
                return
            }
            val c = cellOf[u]
            if (!hasX(c)) {
                act.done = true
                AppState.addLog("[!] $u ${uname(u)} 找不到格子坐标 → 停手")
                return
            }
            act.n++
            act.at = now
            AppState.addLog((if (u in extra) "取消选角" else "选择角色") + "：$u ${uname(u)}")
            tap(input, c!!.optInt("x"), c.optInt("y"), "$u ${uname(u)}")
            return
        }

        if (INVITE && !act.invited) {                      // ② 齐了 → 去邀请（decideButton+0xA8）
            act.at = now + 3_000L                          // 邀请后多等一会（页面要变）
            // ★★2026-11-20（用户）：**直调优先** —— `onClickDecideButton()`（先探 isEnableDecideButton），
            //   不投坐标；失败才退回坐标点击。
            val pr = runCatching { AgentApi.labCharaPagePress("decide") }.getOrNull()
            if (pr?.optBoolean("ok") == true) {
                act.invited = true
                AppState.addLog("邀请角色（直调 onClickDecideButton）")
                justInvited = true                         // ★交给 [MazeLoop] 把「收到角色」框点掉
                return
            }
            val fb = q.optJSONObject("buttons")?.optJSONObject("decide")
            if (!hasX(fb)) {
                act.done = true
                AppState.addLog(
                    "[!] 「去邀请」直调失败（${pr?.optString("err").orEmpty().ifEmpty { "无回包" }}）且没读到坐标 → 不点",
                )
                return
            }
            act.invited = true
            AppState.addLog("邀请角色（坐标兜底）")
            tap(input, fb!!.optInt("x"), fb.optInt("y"), "去邀请")
            justInvited = true                             // ★交给 [MazeLoop] 把「收到角色」框点掉
            return
        }
        act.done = true
    }

    // ────────────────────────── 小工具 ──────────────────────────

    private fun intList(o: JSONObject, key: String): List<Int> {
        val a: JSONArray = o.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<Int>(a.length())
        for (i in 0 until a.length()) {
            val v = a.opt(i)
            if (v is Number) out.add(v.toInt())
        }
        return out
    }

    /** PC `_auto_pick_chara` 里的 `cell_of`：unitId → 屏上那格（同 id 取第一处） */
    private fun cellMap(q: JSONObject): Map<Int, JSONObject> {
        val a = q.optJSONArray("cells") ?: return emptyMap()
        val out = LinkedHashMap<Int, JSONObject>()
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            val u = c.opt("unitId")
            if (u is Number && u.toInt() != 0) out.putIfAbsent(u.toInt(), c)
        }
        return out
    }

    private fun hasX(b: JSONObject?): Boolean =
        b != null && b.opt("x") != null && b.opt("x") !== JSONObject.NULL

    /** PC `_xy`(1473)：按钮/格子坐标 → "(x,y)"，读不到 "-" */
    private fun xy(c: JSONObject?): String = if (hasX(c)) "(${c!!.optInt("x")},${c.optInt("y")})" else "-"

    private suspend fun tap(input: InputBackend, x: Int, y: Int, what: String) {
        when (val r = input.tap(x, y)) {
            is com.lmj.labyrinthauto.input.InputResult.Ok -> AppState.addLog("点了 $what ($x,$y)［${input.name}］")
            is com.lmj.labyrinthauto.input.InputResult.Failed -> AppState.addLog("点 $what 失败：${r.reason}")
            com.lmj.labyrinthauto.input.InputResult.Unavailable -> AppState.addLog("点 $what 失败：${input.name} 后端不可用")
        }
    }
}
