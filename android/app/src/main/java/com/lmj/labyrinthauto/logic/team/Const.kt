package com.lmj.labyrinthauto.logic.team

/**
 * 组队引擎的常量表 —— **逐字抄自 PC 版 `team_build.py`**（改动请两边一起改，用 `tools/gen_team_fixtures.py` 重生成探针即可验）。
 *
 * 对照点（PC 版行号，2026-10-14 的 `md5=4da9fa7970ff6308932b44909236cb9b` 那一版）：
 *   `T`(67) / `DOT_KINDS`(118) / `FIX_TAGS`(121) / `T_DOT`(123) / `T_DOT_SUP`(124)
 *   / `C`(127) / `PROFILES`(262) / `profile_of`(343) / `_apply_floor`(353)
 *
 * 注意：这里**只放数据**，不放逻辑（逻辑在 `Rules`/`TeamBuild` 里）——
 * 这样探针能直接拿 `T`/`W`/`PROFILES` 跟 Python 那份比对（`team_probes.json` 的 `consts`）。
 */
object TagIds {   // PC: T

    // —— 标签 id（PC 版 67~116 行，含每条的用途注释）
    val T: Map<String, String> = mapOf(
        "clear" to "u_clear",             // 速通
        "aoe" to "u_aoe",                 // 群体输出
        // 注：单体/群体用的都是现成标签（tag_defs.json）：
        //   `u_burst` = 单体输出、`u_aoe` = 群体输出、`u_dps` = 输出核心。
        //   （`u_magic_break` 是「魔法破甲」，跟攻击范围无关，别再拿它当单体。）
        "dps" to "u_dps",                 // 输出核心
        "burst" to "u_burst",             // 单体输出
        "pbreak" to "u_break",            // 物理破甲
        "mbreak" to "u_magic_break",     // 魔法破甲
        "pbuff" to "u_buff",              // 物理buff
        "mbuff" to "u_magic_buff",      // 魔法buff
        "tp" to "u_tp",                   // 群充
        "heal" to "u_heal",               // 治疗
        "jiasu" to "u_haste",      // 加速（减少待机时间）
        "first" to "u_first_tp",          // 首动群充（速通关键角色）
        // ★2026-11-12：**物理**首动群充（目前只有「望（解放者）」）——只惠及物理角色，
        //   与上面泛用的 `u_first_tp` 分开，避免把物理速通队带进魔法速通的判断。
        "first_phys" to "u_first_tp_phys",
        "dbreak" to "u_dbreak",           // 双破甲
        "dbreakq" to "u_dbreak_eq",       // 等效双破甲（霸瞳皇帝，加分项）
        "both" to "u_both",               // 物法通用
        "field" to "u_field",      // 领域（领域 buff 不叠加：非速通队硬限最多 1 个）
        "rev" to "u_type_reverse",        // 类型反转（视为相反类型）
        "attrlim" to "u_attr_limit",    // 属性限定
        "p3" to "u_first3_maps",         // 前3图
        "tank" to "u_tank",
        "mtank" to "u_magetank",
        "drain" to "u_tp_drain",      // 抽蓝
        "cc" to "u_cc",                   // 控制
        "taunt" to "u_taunt",      // 嘲讽
        "knock" to "u_knockback",      // 强击退（选人策略要用：第一个必拿）
        // 单体效果低（负面标签）：只有 4 个角色带，全是输出向 → 可在评分里无差别扣分
        "weaksingle" to "u_single_low_eff",
        // 定向输出 = 标签 u_fix_relic（2026-10-13 起叫「对策·美空」）—— 只给美空那关（EX_770121401）用，
        //   **别当成通用的"打遗物"**去套别的关
        "fixrelic" to "u_fix_relic",
        // 对策·最后排（2026-10-13 加）：专打最后排（EX_770121001 4/5 图的"治疗机兵"）；
        //   只打凯露(插班生)/妮侬(夏日)/克萝茜三个，且**不进 FIX_TAGS**
        "fixback" to "u_fix_backline",
        // 对策·多目标boss（2026-10-19 用户定）：美美（夏日）是**多目标特攻**——伤害机制全是多目标向，
        //   单体 boss/普通关**完全没用**（不是"单体弱"，是没有）。只在冰狼/绿龙两个多目标 boss 关上场
        //   （[C.MULTI_BOSS_STAGES]），其他地方编队时**当不存在**（直接剔除，不是扣分）
        "fixmulti" to "u_fix_multi",
        // 对boss弱（2026-10-13 加）：非速通场景扣分，权重 = C.BOSSWEAK
        "bossweak" to "u_boss_weak",
    )

    /** dot 种类（细标签）；`dot` 只表示"有 dot" */
    val DOT_KINDS: List<String> = listOf(
        "u_poison", "u_deadly_poison", "u_curse",
        "u_hex", "u_fire_dmg", "u_dot_other",
    )   // 毒 猛毒 诅咒 恶咒 火伤 其他dot

    /** 对策标签（tag → 名字）：选人策略里"缺该对策就优先拿"用得到 */
    val FIX_TAGS: Map<String, String> = mapOf(
        "u_fix_relic" to "美空",
        "u_fix_ghost" to "幽灵领主",
        "u_fix_icewolf" to "冰狼",
        "u_fix_multi" to "多目标boss",
    )

    const val T_DOT = "u_dot"          // dot（泛化）
    const val T_DOT_SUP = "u_dot_suppress"      // dot抑制（负面：种类计数 ×½）
}

/** 全局参数（PC 版 `class C`，127~193 行）—— 改这里就行 */
object C {

    const val SIZE = 5
    const val SKILL_CUT = 300        // 遗物『开局获得技能值』≥此值 → 不用 首动群充（判据是 >=）
    const val ATTR_MIN = 4           // 属性限定 硬限制：同属性人数下限
    const val ATTR_TOL = 1           // 同属性优化允许的评级下降幅度
    // 同属性加成折算：游戏给 500/1000/2500/5000 → /1000×ATTR_W
    // ★2026-11-07 用户定：整体**提高 50%**（5.0 → 7.5）⇒ 2人=3.75 / 3人=7.5 / 4人=18.75 / 5人=37.5
    const val ATTR_W = 7.5
    const val LOG = "team_runs.jsonl"
    const val BOSSWEAK = -12.0       // 「对boss弱」的扣分（只用于非速通档）

    /** ★带 u_fix_multi（对策·多目标boss，美美·夏日）**只允许**上场的关卡（stageFor 产出的 key）：
     *  冰霜魔狼(3区Boss=319604)、绿龙(5区Boss=302501，奇美拉)——全游戏仅有的两个多目标 boss。
     *  其他关编队时该角色直接剔除（2026-10-19 用户："其他地方直接忽略存在"）。 */
    val MULTI_BOSS_STAGES = setOf("Boss_319604", "Boss_302501")
    const val TANK_EXTRA = -8.0      // 多余坦克扣分（只扣真坦克、只扣超过关卡要求数的）
    const val RANGE_SPREAD_PENALTY = 15.0   // 站位跨度超限：每多一个角色固定扣这么多

    /** ★对策·美空（need_direct）：**无指向单体**（输出或破甲）重扣，定向/群攻豁免。
     *
     *  2026-11-5 用户口径修正：判定不看"是不是爆发"，而看**打不打得到**——
     *  · 有 `u_fix_relic`（定向）⇒ 打得到，还额外 **+15**（[NONDIRECT_FIX_BONUS]，数量不限）；
     *  · 有 `u_aoe`（群体）⇒ 当能打到，不罚（范围标注不精细）；
     *  · 只有单体且无定向 ⇒ 打不到，输出/破甲贡献≈0 ⇒ 扣 40。
     *
     *  取 -40：tier5 单体（50+6+4+同系8≈68）扣完 ≈28，低于 tier3 定向（30+15=45）⇒
     *  定向够就轮不到它；定向不够时才用来补位。
     */
    const val NONDIRECT_SINGLE = -40.0

    /** need_direct 关里**定向输出**的加分（每个等效，不限数量） */
    const val NONDIRECT_FIX_BONUS = 15.0
    const val ATTR_FORCE = true      // 属性限定：默认不取；池里有则另编"同属性队"比整队总分
    const val MAX_FIELD = 1          // 领域硬约束：非速通队领域角色上限（领域 buff 不叠加；profile 可用 max_field 覆盖）

    /** 各算法的权重 */
    val W: Map<String, Map<String, Double>> = mapOf(
        // weaksingle：速通**不扣**（0）—— 速通打群怪，"单体效果低"基本等于"群攻强"
        "sutong" to mapOf(
            "tier" to 10.0, "aoe" to 3.0, "clear" to 3.0, "dbreak" to 6.0, "dbreakq" to 4.0,
            "first" to 8.0, "both" to 1.0, "attrlim" to -6.0, "weaksingle" to 0.0,
        ),
        "dot" to mapOf(
            "tier" to 6.0, "type" to 10.0, "core" to 3.0, "single" to 2.0, "tp" to 2.0, "heal" to 2.0,
        ),
        "burst" to mapOf(
            "tier" to 10.0, "core" to 6.0, "single" to 4.0, "group" to 0.0, "aoe_core" to 6.0,
            "same_type" to 8.0, "bp_break" to 6.0, "bp_buff" to 3.0, "tp" to 3.0, "heal" to 2.0,
            "jiasu" to 3.0, "drain" to 2.0, "multi" to 2.0, "gauge_break" to -3.0,
            "weaksingle" to -12.0,
        ),
        // burst 的**队伍级**加分（不是在单体评分里）
        "burst_team" to mapOf(
            "jiasu" to 6.0, "aoe_core" to 12.0, "edge" to 4.0, "drain" to 6.0, "direct" to 6.0,
        ),
        "aoe" to mapOf(
            "tier" to 10.0, "aoe" to 7.0, "core" to 6.0, "single" to 1.0, "same_type" to 8.0,
            "buff" to 3.0, "tp" to 3.0, "heal" to 1.0, "jiasu" to 2.0, "multi" to 2.0,
            "dot" to 3.0, "solo_core" to -7.0,
            // ★「多目标输出优先」档专用（2026-11-07 用户定，**只给冰狼**）：多目标 boss 里"队里有个群攻就行"是错的
            //   —— 多目标输出**越多越好**，要占满队伍。aoe_priority = 群攻加分量（普通多目标 boss 是 7，这里抬到 15，
            //   让群攻在评级略低时也能压过单体核心 ⇒ 队伍里尽量多群攻）。
            //   ★破甲**不计分**、破甲手也**不免**单体核心的罚（用户 2026-11-07）。
            "aoe_priority" to 15.0,
        ),
    )
}

/**
 * 关卡 → 算法档（PC 版 `PROFILES`，262~336 行）。
 * 值里可能有：`algo`(必) / need_tank("tank"|"tank_or_mtank") / edge("物理"|"魔法") /
 * no_type / no_break / multi / need_heal / need_aoe_core / drain_team / need_cc / need_direct /
 * ghost / bing / min_dot / range_spread / range_spread_hard / by_floor / tank_on_fail / bonus_tags …
 */
object Profiles {

    val PROFILES: Map<String, Map<String, Any>> = mapOf(
        // 速通
        "normal" to mapOf("algo" to "sutong"),
        "EX_770120701" to mapOf("algo" to "sutong"),      // 野性角鹿+纯的暗影…
        "EX_770120801" to mapOf("algo" to "sutong"),      // 小型哥布林+淘气豆狸…
        // EX_770121001（治愈·遗物那关）：3 图及以下不变；4/5 图要强击退+打最后排的，失败过再补坦克
        //   ★`u_fix_relic`（对策·美空）**不算**进来（用户 2026-10-13 更正）
        "EX_770121001" to mapOf(
            "algo" to "sutong",
            "by_floor" to mapOf(
                4 to mapOf(
                    "need_knock" to 1,
                    "bonus_tags" to mapOf("u_fix_backline" to 10),
                    "tank_on_fail" to "tank_or_mtank",
                ),
            ),
        ),
        "EX_770121101" to mapOf("algo" to "sutong"),      // 北海狮首领+尖刺大螃蟹…
        "EX_770121201" to mapOf("algo" to "sutong"),      // 野性角鹿+淘气豆狸…
        // 纯 dot 关
        "EX_770120201" to mapOf("algo" to "dot_only"),    // 好朋友X
        // 单体爆发
        // ★岛鲸：失败后补 1 个坦克/兼职坦克（2026-10-20 用户；与巨大吞拿鱼同款 tank_on_fail）
        "EX_770120301" to mapOf(
            "algo" to "burst",
            "tank_on_fail" to "tank_or_mtank",
        ),
        "EX_770120401" to mapOf("algo" to "burst"),       // 反物质兽
        // 巨大吞拿鱼（EX 单体小boss；敌人 unitId=312307，5 个区同一个 boss：
        //   questId 770120501/770220501/770320501/770420501/770520501）
        //   ★2026-10-16 用户要求：**本节点失败过** ⇒ 补 1 个坦克/兼职坦克（站最前由游戏自排）。
        //     ● 挂**主档**而不是镜像键：区 1 直接命中这里；区 2~5 的 questId 查不到主档
        //       ⇒ `stageFor` 走"questId → 敌人 unitId → `EX_312307`"，由镜像指向**本 dict**，
        //       所以失败补坦克在**任何区**都生效（实机 18:14:33 是区 2/节点 20502 输的那场）。
        //     ● 只在失败后触发，所以"区 1 这 boss 弱、不需要坦克"也不受影响。
        //   ⚠两侧同步：PC `team_build.PROFILES` 的 `EX_770120501`。
        "EX_770120501" to mapOf("algo" to "burst", "tank_on_fail" to "tank_or_mtank"),
        // 熔岩龙：edge=物理（不再硬限物理队）；站位跨度硬限 550；need_tank 必须是字符串
        "EX_770120601" to mapOf(
            "algo" to "burst", "need_tank" to "tank", "edge" to "物理",
            "range_spread" to 550, "range_spread_hard" to true,
        ),
        "EX_770120101" to mapOf("algo" to "burst", "ghost" to true),   // 幽灵领主
        // Boss 关
        "Boss_301206" to mapOf(
            "algo" to "burst", "need_tank" to "tank", "need_aoe_core" to true,
            "no_type" to true, "no_break" to true,
        ),                                                                // 巨型魔像（0 防御、可混编）
        "Boss_306604" to mapOf(
            "algo" to "burst", "need_tank" to "tank_or_mtank", "need_heal" to true,
        ),                                                                // 毒液沙罗曼蛇
        "Boss_312505" to mapOf("algo" to "burst", "need_tank" to "tank"), // 厄勒克特拉夫人
        "Boss_303306" to mapOf("algo" to "burst", "need_tank" to "tank"), // 暗黑滴水嘴兽
        // 奇美拉 / 领主哥布林 / 究极守护者：坦克或兼职 + 魔法更优 + 抽蓝额外加分
        "Boss_302501" to mapOf(
            "algo" to "burst", "need_tank" to "tank_or_mtank", "edge" to "魔法", "drain_team" to true,
        ),
        // ★领主哥布林：用户 2026-10-13 定必须有**真坦克**（tank_or_mtank 会让兼职坦克顶位）
        "Boss_315004" to mapOf(
            "algo" to "burst", "need_tank" to "tank", "edge" to "魔法", "drain_team" to true,
        ),
        "Boss_319401" to mapOf(
            "algo" to "burst", "need_tank" to "tank_or_mtank", "edge" to "魔法", "drain_team" to true,
        ),
        // 炸脖龙：坦克或兼职 + 魔法更优 + 第 1 个核心群攻有额外收益
        "Boss_301701" to mapOf(
            "algo" to "burst", "need_tank" to "tank_or_mtank", "edge" to "魔法", "need_aoe_core" to true,
        ),
        // 多目标 boss
        "Boss_310103" to mapOf("algo" to "aoe"),                          // 愤怒巨龙
        // 冰霜魔狼：dot 加分 + 黑姐（对策·冰狼）优先当坦克 + **多目标输出优先**（群攻越多越好）
        "Boss_319604" to mapOf("algo" to "aoe", "bing" to true, "aoe_priority" to true),
        // 双敌 / 五敌 EX（遗物系）
        "EX_770120901" to mapOf("algo" to "sutong", "min_dot" to 1),      // 护盾·遗物+空花（五敌）
        // ★这两个**双敌**档按**怪物 id** 命中（2026-10-16 修，用户口径：EX 的区号只是"同一怪物在不同区的
        //   属性版本"，**身份是怪物**）：
        //     · 护盾·遗物+黄蜂女王 → 怪物 261001（stages.json 登记在区 4/5：questId 770421301/770521301）
        //     · 治愈·遗物+美空     → 怪物 265000（同上 770421401/770521401；美空按数据**只出现在 4/5 区**）
        //   原来写的是区 1 的 `EX_770121301` / `EX_770121401` —— 那两个 questId 在 stages.json 里**不存在**
        //   （美空不会出现在 1 区）⇒ `EX_<questId>` 永远命不中、`exMap` 里也查不到 ⇒ 镜像也生不出来
        //   ⇒ 这两场在**任何区**都退成 normal（速通队）。
        //   改用怪物 id 后，`stageFor` 的第二跳（questId → 敌人 unitId → `EX_<敌人unitId>`）在任何区都能命中。
        "EX_261001" to mapOf("algo" to "burst", "need_cc" to true, "multi" to true),
        // 治愈·遗物+美空（怪物 265000，4/5 区）：edge="none" = **无系别偏好**（2026-10-19 用户：
        //   "去掉物理队更优的设定"）——默认的物理 +4 会把可用的魔法对策卡压出去
        // ★2026-11-5 用户：失败后队伍里尽量带坦克（同岛鲸/吞拿鱼那套 tank_on_fail）
        "EX_265000" to mapOf(
            "algo" to "burst", "need_direct" to true, "multi" to true, "edge" to "none",
            "tank_on_fail" to "tank_or_mtank",
        ),
    )

    /**
     * EX 档镜像：`PartyPlan` 加载 boss/ex 表后，把登记的 `EX_<questId>` 档镜像成与区无关的
     * `EX_<敌人unitId>`（PC `_ex_resolve` 的同款目的）。**不影响** `PROFILES`（对拍基准不变）。
     */
    private val MIRROR: MutableMap<String, Map<String, Any>> = LinkedHashMap()

    fun addMirror(key: String, base: Map<String, Any>) {
        if (key !in MIRROR && key !in PROFILES) MIRROR[key] = LinkedHashMap(base)
    }

    fun hasProfile(key: String): Boolean = key in PROFILES || key in MIRROR

    /** 关卡 key 或算法名 → profile（未知关卡按 burst 处理并提示）—— PC 版 `profile_of` */
    fun profileOf(stage: String): MutableMap<String, Any?> {
        (PROFILES[stage] ?: MIRROR[stage])?.let { return LinkedHashMap(it).apply { this["key"] = stage } }
        if (stage == "sutong" || stage == "dot_only" || stage == "burst") {
            return mutableMapOf("algo" to stage, "key" to stage)
        }
        return mutableMapOf("algo" to "burst", "key" to stage, "todo" to "未登记关卡，按 burst 处理")
    }

    /**
     * 按"图数"细分关卡档（PC 版 `_apply_floor`）：档里可写 `by_floor: {4: {...}}`，
     * `floor >= 4` 时把"不超过 floor 的最大那档"合并进来，并记 `floor_rule`。
     */
    fun applyFloor(pf: Map<String, Any?>, floor: Int): Map<String, Any?> {
        val bf = pf["by_floor"] as? Map<*, *> ?: return pf
        var use: Int? = null
        for (k in bf.keys.mapNotNull { (it as? Number)?.toInt() }.sorted()) {
            if (floor >= k) use = k
        }
        if (use == null) return pf
        val out = LinkedHashMap<String, Any?>(pf)
        (bf[use] as? Map<*, *>)?.forEach { (k, v) -> out[k.toString()] = v }
        out["floor_rule"] = use
        return out
    }
}
