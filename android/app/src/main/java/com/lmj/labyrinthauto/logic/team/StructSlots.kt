package com.lmj.labyrinthauto.logic.team

/**
 * ★**结构位台账**（2026-11-4）：把散在四个算法里的「先占坑」规则集中到**一处**。
 *
 * 为什么要有这个文件：
 *  · 现在"坦克该带谁"在 `algoSutong` / `burstTeam` / `algoAoe` / `variantBest` 里各写一遍，
 *    字符串 `"tank"` 硬编码 4 处 —— 加一种坦克标签要改 5 个地方；
 *  · 「先选坦克、再填其他人」这个顺序假设了局部最优=全局最优，而坦克的真实价值还包含
 *    「对同属性/同系别加成的贡献」这类**队伍级交叉项**（见 [TeamScore]）。
 *    要改成"枚举结构位组合再比整队分"，前提就是先把"有哪些位、怎么判、几个、怎么选"列清楚。
 *
 * ★本文件**只做声明**，不改变任何现有行为；算法仍按老路径跑。
 *   下一步（枚举结构位组合）直接读 [ALL] / [requiredSlots] 即可。
 */
object StructSlots {

    /** 一位结构位 */
    data class Slot(
        /** 稳定标识（日志/枚举用） */
        val key: String,
        val cn: String,
        /** 满足这个位的标签（**命中任意一个**就算这个位的人） */
        val tags: List<String>,
        /** 这一关至少几个（0 = 默认不是必需位；真正要不要看 [required]） */
        val minCount: Int,
        /** 至多几个（超了扣分或直接禁止） */
        val maxCount: Int,
        /** 现状：候选是怎么挑的（台账用，不参与计算） */
        val pick: String,
        /** 这关是否需要这一位（只看 profile 静态部分；动态条件见 [dynamic]） */
        val required: (Map<String, Any?>) -> Boolean,
        /** 依赖运行时状态的条件（不能只看 profile），没有 = null */
        val dynamic: String? = null,
    ) {
        /** 这个角色算不算这个位的人 */
        fun matches(tagsOf: Set<String>): Boolean = tags.any { it in tagsOf }
    }

    private fun tag(key: String, fallback: String): String = TagIds.T[key] ?: fallback

    /** profile 里的整数/布尔读法（值可能是 Int / Boolean / String） */
    fun intOf(pf: Map<String, Any?>, key: String, d: Int = 0): Int = when (val v = pf[key]) {
        null -> d
        is Number -> v.toInt()
        is Boolean -> if (v) 1 else 0
        is String -> v.toIntOrNull() ?: d
        else -> d
    }

    /** profile 里"有这个开关吗"（true / 正数 / 非空字符串 都算） */
    fun has(pf: Map<String, Any?>, key: String): Boolean = when (val v = pf[key]) {
        null -> false
        is Boolean -> v
        is Number -> v.toInt() > 0
        is String -> v.isNotEmpty() && v != "false" && v != "0"
        else -> true
    }

    /** ★全部结构位（顺序 = 现有算法的取人顺序，便于对照） */
    val ALL: List<Slot> = listOf(
        Slot(
            key = "tank", cn = "坦克",
            tags = listOf(tag("tank", "u_tank"), tag("mtank", "u_magetank")),
            minCount = 1, maxCount = 1,
            pick = "只按场景评级 max(tier)；多余坦克另扣 TANK_EXTRA",
            required = { pf -> has(pf, "need_tank") },
            dynamic = "失败后按 tank_on_fail 强制补坦克",
        ),
        Slot(
            key = "tp", cn = "首动群充",
            tags = listOf(tag("first", "u_first_tp"), tag("tp", "u_tp")),
            minCount = 0, maxCount = 1,
            pick = "max(tier)",
            required = { false },
            dynamic = "遗物技能值 < SKILL_CUT(300) 才启用；够高就换加速角色",
        ),
        Slot(
            key = "break", cn = "破甲",
            tags = listOf(tag("pbreak", "u_break"), tag("mbreak", "u_magic_break"), tag("dbreak", "u_dbreak")),
            minCount = 1, maxCount = 2,
            pick = "max(sc)；优先双破甲，其次「同系群攻 + 单破甲」",
            required = { pf -> !has(pf, "no_break") },
        ),
        Slot(
            key = "dot", cn = "dot",
            tags = listOf(TagIds.T_DOT),
            minCount = 0, maxCount = 3,
            pick = "按「全队 dot 当量净增量」贪心，抑制者 ×½",
            required = { pf -> intOf(pf, "min_dot") > 0 },
        ),
        Slot(
            key = "knock", cn = "强击退",
            tags = listOf(tag("knock", "u_knockback")),
            minCount = 1, maxCount = 1,
            pick = "直接 take（第一个满足的）",
            required = { pf -> has(pf, "need_knock") },
        ),
        Slot(
            key = "cc", cn = "控制",
            tags = listOf(tag("cc", "u_cc"), tag("taunt", "u_taunt")),
            minCount = 1, maxCount = 1,
            pick = "直接 take",
            required = { pf -> has(pf, "need_cc") },
        ),
        Slot(
            key = "direct", cn = "定向（对策·美空）",
            tags = listOf(tag("fixrelic", "u_fix_relic")),
            minCount = 1, maxCount = 1,
            pick = "直接 take；非定向单体输出另扣 NONDIRECT_SINGLE",
            required = { pf -> has(pf, "need_direct") },
        ),
        Slot(
            key = "heal", cn = "治疗",
            tags = listOf(tag("heal", "u_heal")),
            minCount = 1, maxCount = 1,
            pick = "rankHeal：(治疗, 群充, 评级)",
            required = { pf -> has(pf, "need_heal") },
        ),
        Slot(
            key = "jiasu", cn = "加速",
            tags = listOf(tag("jiasu", "u_haste")),
            minCount = 0, maxCount = 1,
            pick = "burst/aoe 只枚举 top2（variantBest）",
            required = { pf -> has(pf, "force_jiasu") },
            dynamic = "部分档要求「恰好 1 个」",
        ),
        Slot(
            key = "aoe_core", cn = "群攻核心",
            tags = listOf(tag("aoe", "u_aoe")),
            minCount = 1, maxCount = 1,
            pick = "aoe 算法内按 aoeScore 取",
            required = { pf -> has(pf, "need_aoe_core") },
        ),
        Slot(
            key = "field", cn = "领域",
            tags = listOf(tag("field", "u_field")),
            minCount = 0, maxCount = 1,
            pick = "只在 take 处拦截（burst/aoe 有绕过路径）",
            required = { false },
            dynamic = "领域不叠加：非速通队至多 max_field(默认1)；速通队不限",
        ),
    )

    /** 这关**必需**的结构位（只看 profile 静态部分） */
    fun requiredSlots(pf: Map<String, Any?>): List<Slot> = ALL.filter { it.required(pf) }

    /** 这个角色能填哪些位 */
    fun slotsOf(tagsOf: Set<String>): List<Slot> = ALL.filter { it.matches(tagsOf) }

    /**
     * 一行台账（日志用）：这关需要哪些结构位 + 首动群充到底启不启用。
     * 例：`结构位：坦克 / 治疗 / 方向(need_cc) ｜ 首动群充：技能值 280<300 → 启用 ｜ 领域≤1`
     */
    fun describe(pf: Map<String, Any?>, relicsSkill: Int): String {
        val req = requiredSlots(pf)
        val reqTxt = if (req.isEmpty()) "无" else req.joinToString(" / ") { it.cn }
        val tp = if (relicsSkill in 0 until C.SKILL_CUT) "启用（技能值 $relicsSkill<${C.SKILL_CUT}）"
        else "省掉（技能值 $relicsSkill≥${C.SKILL_CUT}）"
        val fieldMax = if (pf["algo"] == "sutong") "不限" else "≤" + intOf(pf, "max_field", C.MAX_FIELD)
        return "结构位：$reqTxt ｜ 首动群充：$tp ｜ 领域：$fieldMax"
    }
}
