package com.lmj.labyrinthauto.logic.team

import com.lmj.labyrinthauto.data.Tables

/**
 * 组队引擎（P4 第②层）—— 逐条对应 PC 版 `team_build.py`（**行号写在每个函数上**，方便两边对照）。
 *
 * 契约（PC 版文件头的"输出契约"）：`plan(stage, pool, relics_skill, floor, size, failed)` →
 * **站位顺序（攻击范围升序）的 unitId 列表**；pcr_auto 拿这串 id 去编队界面选人，站位由游戏自排。
 *
 * 移植现状（2026-10-14）：
 *   ✅ `build`(375) / `_take`(449) / `_fill`(455) / `_first_tp`(466) / `_attr_pack`(488) /
 *      `tank_extra_*`(552,562) / `_rng`(575) / `range_spread`+`spread_*`(581~633) /
 *      `_attrlim_hard`(636) / `_finish`(665) / `_algo_sutong`(679)
 *   ⏳ `_algo_dot`(808) / `_algo_burst`(1318) / `_algo_aoe`(1104) / `_burst_score`(904) /
 *      `_aoe_score`(1072) / `_ghost_plan3`(1288) / `_variant_best`(1490) / `build_best`(1535)
 *
 * ★排序一律照 Python：`sorted(key=..., reverse=True)` = **元组逐项降序**，
 *   平局时 Python 的 `max`/`sorted` 都取**先出现**的那个 → Kotlin 用 `sortedWith(...).reversed()`
 *   和 `maxWithOrNull`（两者都保留首次出现，顺序一致）。这条对"逐字一致"很关键。
 */
class TeamBuild(private val tables: Tables) {

    val rules = Rules(tables)

    /** 单位中文名（PC 里等价 `U[uid]["name"]`）—— 组队记录（[TeamRunLog]）写人名用 */
    fun unitName(uid: String): String = tables.unit(uid)?.optString("name").orEmpty().ifEmpty { uid }

    /** 单位属性（火/水/风/光/暗）—— 组队记录里给"属性明细"用 */
    fun unitAttr(uid: String): String = tables.unit(uid)?.optString("attr").orEmpty()

    /** 单位攻击范围（站位跨度相关） */
    fun unitRange(uid: String): Int? = (tables.unit(uid)?.opt("range") as? Number)?.toInt()

    // ────────────────────────── build（375）──────────────────────────

    /** PC 版 `build(...)`：返回 res（含 team/picked/reasons/log/warn/…） */
    fun build(
        stage: String,
        poolIn: List<String>,
        relicsSkill: Int = 0,
        floor: Int = 1,
        size: Int? = null,
        forceAttr: String? = null,
        allowAttrlim: Boolean = false,
        forceTank: String? = null,
        forceJiasu: String? = null,
        failed: Boolean = false,
    ): Res {
        var pf: Map<String, Any?> = Profiles.applyFloor(Profiles.profileOf(stage), floor)
        // 本节点失败过才补坦克（PC 393 行）
        if (failed && pf["tank_on_fail"] != null && !truthy(pf["need_tank"])) {
            pf = LinkedHashMap(pf).apply {
                this["need_tank"] = pf["tank_on_fail"]
                this["tank_on_fail_used"] = true
            }
        }
        if (forceTank != null || forceJiasu != null) {
            pf = LinkedHashMap(pf).apply {
                if (forceTank != null) this["force_tank"] = forceTank
                if (forceJiasu != null) this["force_jiasu"] = forceJiasu
            }
        }
        val algo = pf["algo"] as? String ?: "burst"
        // pool 只留"表里有的"，转成字符串（PC 398：保持给定顺序）
        val pool = poolIn.map { it.toString() }.filter { tables.unit(it) != null }

        val cands = mutableListOf<String>()
        val weak = mutableListOf<String>()
        val drop = mutableListOf<Pair<String, String>>()
        for (uid in pool) {
            val ts = rules.tagsOf(uid)
            if (floor > 3 && TagIds.T["p3"] in ts) {
                drop.add(uid to "前3图(4图后屏蔽)")
                continue
            }
            // ★对策·多目标boss（u_fix_multi，2026-10-19 用户定）：美美（夏日）是**多目标特攻**，
            //   伤害机制全是多目标向 ⇒ 单体 boss/普通关**完全没用**。只在冰狼/绿龙两个
            //   多目标 boss 关保留（[C.MULTI_BOSS_STAGES]），其他关**直接剔除**（不是扣分）。
            if (TagIds.T["fixmulti"] in ts && stage !in C.MULTI_BOSS_STAGES) {
                drop.add(uid to "多目标特攻(只在冰狼/绿龙关上场)")
                continue
            }
            val t = rules.tierOf(uid, if (algo == "sutong") "sutong" else "all") ?: 0
            if (t >= 2) cands.add(uid) else weak.add(uid)
        }
        // 属性限定（PC 408~417）
        val alUnits = (cands + weak).filter { TagIds.T["attrlim"] in rules.tagsOf(it) }.toMutableList()
        val keep: (String) -> Boolean = when {
            forceAttr != null -> { u -> (tables.unit(u)?.optString("attr") ?: "?").let { if (it.isEmpty()) "?" else it } == forceAttr }
            allowAttrlim || !C.ATTR_FORCE -> { _ -> true }
            else -> { u -> TagIds.T["attrlim"] !in rules.tagsOf(u) }
        }
        val cands2 = cands.filter(keep).toMutableList()
        val weak2 = weak.filter(keep).toMutableList()
        val avail = cands2.size + weak2.size
        val n = minOf(size ?: C.SIZE, avail)
        // 领域硬约束：速通队不限；其它队默认最多 C.MAX_FIELD 个（profile 可用 max_field 覆盖）
        val maxField = if (algo == "sutong") Int.MAX_VALUE
                       else (pf["max_field"] as? Number)?.toInt() ?: C.MAX_FIELD
        // weak 按通用评级降序（PC 420）
        // ★平局必须保持原顺序：Python 的 `sort(key=…, reverse=True)` 是**稳定**的（不是"排完再倒过来"）
        weak2.sortWith(descByInt { rules.tierOf(it) ?: 0 })

        val res = Res(
            stage = pf["key"] as? String,
            algo = algo,
            cands = cands2,
            weak = weak2,
            drop = drop,
            attrlimUnits = alUnits,
            todo = pf["todo"] as? String,
            forceAttr = forceAttr,
            floorRule = (pf["floor_rule"] as? Number)?.toInt(),
            failed = failed,
        )
        res.maxField = maxField
        if (alUnits.isNotEmpty() && forceAttr == null && !allowAttrlim && C.ATTR_FORCE) {
            val names = alUnits.joinToString("、") { tables.unit(it)?.optString("name").orEmpty() }
            res.log.add("属性限定：$names 不参与默认队（改走同属性队尝试，见 build_best）")
        }
        if (n == 0) {
            // PC 431：`dict(res, team=[], picked=[], fail=…)` —— team/picked 明确给空，
            // 其余统计键（attrs/types/attr_bonus）**不存在**（对拍时要和 null 对齐）
            res.team = emptyList()
            res.picked.clear()
            res.fail = "池子里没有可用角色"
            return res
        }
        if (pf["todo"] != null) res.warn.add("⚠ ${pf["todo"]}")

        when (algo) {
            "sutong" -> algoSutong(pf, res.cands, res.weak, n, relicsSkill, res)
            "dot_only" -> algoDot(pf, res.cands, res.weak, n, relicsSkill, res)
            "aoe" -> algoAoe(pf, res.cands, res.weak, n, relicsSkill, res)
            else -> algoBurst(pf, res.cands, res.weak, n, relicsSkill, res)
        }
        finish(res)
        return res
    }

    // ────────────────────────── 小件（449~675）──────────────────────────

    /** PC 版 `_take`(449)：只在没拿过时记人 + 理由；领域硬约束在此拦截第 2+ 个领域角色 */
    private fun take(res: Res, uid: String, why: String) {
        if (uid !in res.picked) {
            if (res.maxField != Int.MAX_VALUE) {
                val ft = TagIds.T["field"]
                if (ft != null && ft in rules.tagsOf(uid)
                    && res.picked.count { ft in rules.tagsOf(it) } >= res.maxField) {
                    res.log.add("领域硬约束：跳过第 2+ 个领域角色 ${tables.unit(uid)?.optString("name").orEmpty()}")
                    return
                }
            }
            res.picked.add(uid)
            res.reasons[uid] = why
        }
    }

    /** PC 版 `_fill`(455)：按 rank 降序补齐，再拿 weak 凑数 */
    private fun fill(
        res: Res,
        cands: List<String>,
        weak: List<String>,
        n: Int,
        rank: (String) -> ComparableKey,
        why: String = "填充",
    ) {
        val rest = cands.filter { it !in res.picked }
        for (u in rest.sortedWith(keyComparator(rank))) {
            if (res.picked.size >= n) return
            take(res, u, why)
        }
        for (u in weak) {
            if (res.picked.size >= n) return
            take(res, u, "凑数（人不够）")
        }
    }

    /** PC 版 `_first_tp`(466)：关键角色（首动群充）；遗物技能值 ≥ SKILL_CUT 时整个不用 */
    private fun firstTp(
        res: Res,
        cands: MutableList<String>,
        relicsSkill: Int,
        replaceWithJiasu: Boolean = false,
    ) {
        val first = cands.filter { TagIds.T["first"] in rules.tagsOf(it) }
        if (relicsSkill >= C.SKILL_CUT) {
            cands.removeAll(first)
            val names = first.joinToString("、") { tables.unit(it)?.optString("name").orEmpty() }
            var msg = "遗物『开局技能值』=$relicsSkill ≥ ${C.SKILL_CUT} → 不用 首动群充（" +
                (if (names.isEmpty()) "池里没有" else names) + " 移出候选）"
            if (replaceWithJiasu) {
                val js = cands.filter { TagIds.T["jiasu"] in rules.tagsOf(it) }
                    .sortedWith(descByInt { rules.tierOf(it) ?: 0 })      // 稳定降序，平局保持顺序
                if (js.isNotEmpty()) {
                    take(res, js[0], "遗物够强 → 换高分加速角色")
                    msg += "；改用加速角色 ${tables.unit(js[0])?.optString("name").orEmpty()}"
                } else {
                    msg += "；池里也没有加速角色 → 该位空出"
                }
            }
            res.log.add(msg)
        } else if (first.isNotEmpty()) {
            // PC: max(key=tier) —— 只比评级，平局取**先出现**的（别掺 uid 做次级键）
            val u = first.maxWithOrNull(compareBy { rules.tierOf(it) ?: 0 })!!
            take(res, u, "关键角色·首动群充")
        }
    }

    /**
     * PC 版 `_attr_pack`(488)：同属性优化（净收益必须 > 0 才换）。
     *
     * @param ok       换进来的角色也要符合本算法的入选条件
     * @param protect  **结构位不许被换掉**（坦克/加速等）
     * @param scfn     该算法的真实个人评分函数；给了就按"整队分数变化"判净收益（PC 2026-10-13 夜修的）
     * @param teampen  队伍级扣分（如多余坦克），也算进净收益
     */
    private fun attrPack(
        res: Res,
        cands: List<String>,
        ok: ((String) -> Boolean)? = null,
        protect: Set<String> = emptySet(),
        nOfSwap: Int = 3,
        scen: String = "all",
        tierW: Double = 10.0,
        scfn: ((String) -> Double)? = null,
        teampen: ((List<String>) -> Double)? = null,
    ) {
        repeat(nOfSwap) {
            val base = rules.attrBonus(rules.countsAttr(res.picked))
            var bestKey: List<Double>? = null
            var bestU: String? = null
            var bestV: String? = null
            var bestGain = 0
            var bestGainPts = 0.0
            var bestNet = 0.0
            for (u in res.picked.toList()) {
                if (u in protect) continue
                val tsu = rules.tagsOf(u)
                if (TagIds.T["first"] in tsu || TagIds.T["dbreak"] in tsu) continue
                for (v in cands) {
                    if (v in res.picked) continue
                    val tsv = rules.tagsOf(v)
                    if (TagIds.T["first"] in tsv || TagIds.T["dbreak"] in tsv) continue
                    if (ok != null && !ok(v)) continue
                    // 领域硬约束：v 是领域角色且已达上限 → 该次替换会引入第 2+ 个领域，跳过
                    if (res.maxField != Int.MAX_VALUE) {
                        val ft = TagIds.T["field"]
                        if (ft != null && ft in tsv
                            && res.picked.count { ft in rules.tagsOf(it) } >= res.maxField) continue
                    }
                    val tu = rules.tierOf(u, scen) ?: 0
                    val tv = rules.tierOf(v, scen) ?: 0
                    if (scfn == null && tv < tu - C.ATTR_TOL) continue
                    val newTeam = res.picked.map { if (it == u) v else it }
                    val gain = rules.attrBonus(rules.countsAttr(newTeam)) - base
                    if (gain <= 0) continue
                    val gainPts = gain * C.ATTR_W / 1000.0
                    val net: Double
                    val dv: Int
                    val du: Int
                    if (scfn != null) {
                        fun valOf(t: List<String>): Double =
                            t.sumOf { scfn(it) } + rules.attrScore(t) + (teampen?.invoke(t) ?: 0.0)
                        net = valOf(newTeam) - valOf(res.picked)
                        dv = 0; du = 0
                    } else {
                        net = (tv - tu) * tierW + gainPts
                        dv = tv; du = tu
                    }
                    if (net <= 0) continue
                    val key = listOf(net, gainPts, (dv - du).toDouble())
                    if (bestKey == null || compareKeys(key, bestKey) > 0) {
                        bestKey = key; bestU = u; bestV = v
                        bestGain = gain; bestGainPts = gainPts; bestNet = net
                    }
                }
            }
            val u = bestU ?: return
            val v = bestV!!
            val idx = res.picked.indexOf(u)
            res.picked[idx] = v
            res.reasons[v] = "同属性优化（替下 ${tables.unit(u)?.optString("name").orEmpty()}，" +
                "+$bestGain＝${fmt1(bestGainPts)} 分 ｜ 净 ${fmtSigned1(bestNet)}）"
            res.reasons.remove(u)
            res.swaps.add(
                "${tables.unit(u)?.optString("name").orEmpty()} → ${tables.unit(v)?.optString("name").orEmpty()}" +
                    "（同属性 +$bestGain＝${fmt1(bestGainPts)} 分 ｜ 净 ${fmtSigned1(bestNet)}）",
            )
        }
    }

    /** PC 版 `tank_extra_penalty`(552)：多余**真坦克**扣分（兼职坦克不算） */
    private fun tankExtraPenalty(team: List<String>, needTank: Boolean): Double {
        if (C.TANK_EXTRA == 0.0) return 0.0
        val n = team.count { TagIds.T["tank"] in rules.tagsOf(it) }
        return C.TANK_EXTRA * maxOf(0, n - (if (needTank) 1 else 0))
    }

    /** PC 版 `tank_extra_key`(562)：填充排序键 —— 已满足坦克名额时，再带真坦克要扣分 */
    private fun tankExtraKey(held: List<String>, needTank: Boolean, sc: (String) -> Double): (String) -> Double = { u ->
        var s = sc(u)
        if (C.TANK_EXTRA != 0.0 && TagIds.T["tank"] in rules.tagsOf(u)) {
            val n = held.count { TagIds.T["tank"] in rules.tagsOf(it) }
            if (n >= (if (needTank) 1 else 0)) s += C.TANK_EXTRA
        }
        s
    }

    /** PC 版 `_rng`(575)：站位（攻击范围）；缺失按 9999 */
    private fun rng(uid: String): Int = tables.unit(uid)?.optInt("range", 9999) ?: 9999

    /** 对外：攻击范围（站位）。编队页"移除角色"要按它从大到小移（见 PartyPlan）。 */
    fun attackRange(uid: String): Int = rng(uid)

    /** PC 版 `range_spread`(581)：站位跨度 = max(range) − min(range)（<2 人算 0） */
    private fun rangeSpread(team: List<String>): Int {
        val rs = team.map { rng(it) }
        return if (rs.size >= 2) rs.max() - rs.min() else 0
    }

    /** PC 版 `spread_offenders`(587)：导致跨度超限的那些角色（最少需拿掉的人数） */
    private fun spreadOffenders(team: List<String>, lim: Int): List<String> {
        val rs = team.map { rng(it) to it }.sortedBy { it.first }
        var bI = 0
        var bJ = 0
        var j = 0
        for (i in rs.indices) {
            if (j < i) j = i
            while (j + 1 < rs.size && rs[j + 1].first - rs[i].first <= lim) j++
            if (j - i > bJ - bI) {
                bI = i; bJ = j
            }
        }
        val inWin = rs.subList(bI, bJ + 1).map { it.second }.toSet()
        return rs.map { it.second }.filter { it !in inWin }
    }

    /** PC 版 `spread_ok`(608)：没登记限制 ⇒ 恒 true */
    private fun spreadOk(team: List<String>, pf: Map<String, Any?>): Boolean {
        val lim = (pf["range_spread"] as? Number)?.toInt() ?: return true
        return rangeSpread(team) <= lim
    }

    /** PC 版 `spread_penalty`(614)：超限 ⇒ 每个"多余角色"扣固定分（返回 扣分≤0, 跨度, 限制） */
    private fun spreadPenalty(team: List<String>, pf: Map<String, Any?>): Triple<Double, Int, Int?> {
        val lim = (pf["range_spread"] as? Number)?.toInt()
        val sp = rangeSpread(team)
        if (lim == null || sp <= lim) return Triple(0.0, sp, lim)
        val n = spreadOffenders(team, lim).size
        return Triple(-round1(C.RANGE_SPREAD_PENALTY * n), sp, lim)
    }

    /** PC 版 `spread_note`(626)：进日志的一句人话 */
    private fun spreadNote(team: List<String>, spread: Int, lim: Int?, pen: Double, hard: Boolean = false): String {
        if (pen == 0.0) {
            return "站位跨度 $spread ≤ $lim ✓" + (if (hard) "（硬限制）" else "")
        }
        val off = spreadOffenders(team, lim!!)
        // PC：`"%s %s" % (U[u]["name"] or u, U[u].get("range"))`
        //   → 名字为空时**回落成 uid**；range 缺失时按 Python 渲染成 `None`
        val det = off.joinToString("、") { u ->
            val nm = tables.unit(u)?.optString("name").orEmpty().ifEmpty { u }
            "$nm ${pyStr(rangeOrNull(u))}"
        }
        return "站位跨度 $spread > $lim → 超限（最少需换掉 ${off.size} 人：$det）各扣 ${fmt1(C.RANGE_SPREAD_PENALTY)}" +
            " → 整队扣 ${fmt1(-pen)} 分"
    }

    /** PC 版 `_attrlim_hard`(636)：属性限定是硬限制（同属性 < ATTR_MIN 不带） */
    private fun attrlimHard(res: Res, cands: List<String>, weak: List<String>) {
        repeat(3) {
            val cnt = rules.countsAttr(res.picked)
            val bad = res.picked.firstOrNull { u ->
                TagIds.T["attrlim"] in rules.tagsOf(u) &&
                    (cnt[attrOf(u)] ?: 1) < C.ATTR_MIN
            } ?: return
            var repl: String? = null
            for (v in cands + weak) {
                if (v in res.picked || TagIds.T["attrlim"] in rules.tagsOf(v)) continue
                if (repl == null || (rules.tierOf(v) ?: 0) > (rules.tierOf(repl) ?: 0)) repl = v
            }
            if (repl != null) {
                res.picked[res.picked.indexOf(bad)] = repl
                res.reasons[repl] = "属性限定硬限制（替下 ${tables.unit(bad)?.optString("name").orEmpty()}）"
                res.reasons.remove(bad)
                res.log.add(
                    "属性限定硬限制：${tables.unit(bad)?.optString("name").orEmpty()} 换下（同属性 " +
                        "${cnt[attrOf(bad)] ?: 1}<${C.ATTR_MIN}）→ ${tables.unit(repl)?.optString("name").orEmpty()}",
                )
            } else {
                res.picked.remove(bad)
                res.reasons.remove(bad)
                res.log.add(
                    "属性限定硬限制：${tables.unit(bad)?.optString("name").orEmpty()} 不带（同属性 " +
                        "${cnt[attrOf(bad)] ?: 1}<${C.ATTR_MIN} 且无替补）",
                )
            }
        }
    }

    /** PC 版 `_finish`(665)：站位顺序（范围升序）+ 统计 */
    private fun finish(res: Res) {
        res.team = res.picked.sortedBy { rng(it) }     // Python sorted 是稳定排序；Kotlin sortedBy 同样稳定
        val attrs = rules.countsAttr(res.picked)
        res.attrs = attrs
        val ty = LinkedHashMap<String, Int>()
        for (u in res.picked) {
            val k = rules.effAtk(u).ifEmpty { "?" }
            ty[k] = (ty[k] ?: 0) + 1
        }
        res.types = ty
        res.attrBonusValue = rules.attrBonus(attrs)
    }

    // ────────────────────────── 算法 1：速通（679）──────────────────────────

    private fun algoSutong(
        pf: Map<String, Any?>,
        cands: MutableList<String>,
        weak: List<String>,
        n: Int,
        relicsSkill: Int,
        res: Res,
    ) {
        val w = C.W["sutong"]!!
        val bonus: Map<String, Double> = (pf["bonus_tags"] as? Map<*, *>)
            ?.entries?.associate { it.key.toString() to (it.value as Number).toDouble() } ?: emptyMap()
        val bonusTs = bonus.keys

        fun bname(ts: Set<String>): String =
            (ts intersect bonusTs).sorted().joinToString("、") { t ->
                tables.defs[t]?.optString("name")?.takeIf { it.isNotEmpty() } ?: t
            }

        fun sc(u: String): Double {
            val ts = rules.tagsOf(u)
            var s = (rules.tierOf(u, "sutong") ?: 0) * w["tier"]!!
            if (TagIds.T["aoe"] in ts) s += w["aoe"]!!
            if (TagIds.T["clear"] in ts) s += w["clear"]!!
            if (TagIds.T["dbreak"] in ts) s += w["dbreak"]!!
            if (TagIds.T["dbreakq"] in ts) s += w["dbreakq"]!!
            if (TagIds.T["both"] in ts) s += w["both"]!!
            if (TagIds.T["attrlim"] in ts) s += w["attrlim"]!!
            if (TagIds.T["weaksingle"] in ts) s += w["weaksingle"]!!
            if (bonusTs.isNotEmpty()) s += bonus.entries.filter { it.key in ts }.sumOf { it.value }
            return s
        }

        // 关卡强制位：坦克 / 强击退 —— 后面的同属性优化不许换掉
        val must = mutableSetOf<String>()
        if (truthy(pf["need_tank"])) {
            val tps = cands.filter { TagIds.T["tank"] in rules.tagsOf(it) }.toMutableList()
            if (pf["need_tank"] != "tank") tps += cands.filter { TagIds.T["mtank"] in rules.tagsOf(it) }
            if (tps.isNotEmpty()) {
                val tk = tps.maxWithOrNull(compareBy({ rules.tierOf(it, "sutong") ?: 0 }, { it }))!!
                take(res, tk, "坦克（本节点失败过：站最前扛）")
                must.add(tk)
            } else {
                res.warn.add("⚠ 关卡要求坦克（失败后补），但池里没有坦克/兼职坦克")
            }
        }

        firstTp(res, cands, relicsSkill)

        val dbr = cands.filter { TagIds.T["dbreak"] in rules.tagsOf(it) }
        if (dbr.isNotEmpty()) {
            take(res, dbr.maxWithOrNull(compareByDouble { sc(it) })!!, "双破甲（至少一个）")
        } else {
            res.log.add("没有「双破甲」→ 备选路线：同系「群攻 + 单破甲」")
            val brks = cands.filter {
                val ts = rules.tagsOf(it)
                TagIds.T["pbreak"] in ts || TagIds.T["mbreak"] in ts
            }
            val aoes = cands.filter { TagIds.T["aoe"] in rules.tagsOf(it) }
            var pair: Pair<String, String>? = null
            var best: Int? = null
            for (a in aoes) {
                val ta = rules.effAtk(a)
                for (b in brks) {
                    val tbs = rules.tagsOf(b)
                    val tb = mutableSetOf<String>()
                    if (TagIds.T["pbreak"] in tbs) tb.add("物理")
                    if (TagIds.T["mbreak"] in tbs) tb.add("魔法")
                    if (ta !in tb) continue
                    val k = (rules.tierOf(a, "sutong") ?: 0) + (rules.tierOf(b, "sutong") ?: 0)
                    if (best == null || k > best!!) {
                        pair = a to b; best = k
                    }
                }
            }
            if (pair != null) {
                take(res, pair!!.first, "备选路线·同系群体输出")
                take(res, pair!!.second, "备选路线·同系单破甲")
            } else {
                res.warn.add("⚠ 找不到『同系群攻+单破甲』组合")
            }
        }

        // 护盾·遗物（空花）：1 个 dot 破盾
        fun isDot(u: String): Boolean = rules.dotKindsOf(u).isNotEmpty() || TagIds.T_DOT in rules.tagsOf(u)
        val minDot = (pf["min_dot"] as? Number)?.toInt()
        if (minDot != null) {
            val got = res.picked.filter { isDot(it) }
            if (got.size < minDot) {
                val pd = cands.filter { it !in res.picked && isDot(it) }
                if (pd.isNotEmpty()) {
                    take(
                        res,
                        pd.maxWithOrNull(compareBy({ rules.dotKindsOf(it).size }, { rules.tierOf(it, "sutong") ?: 0 }))!!,
                        "dot 破盾（护盾·遗物：1 个就够）",
                    )
                } else {
                    res.warn.add("⚠ 关卡有护盾·遗物、需要 dot 破盾，但池里没有 dot 角色")
                }
            }
        }

        // 4/5 图专项：1 个强击退（压缩阵型，切治疗机兵）
        val needKnock = (pf["need_knock"] as? Number)?.toInt()
        if (needKnock != null) {
            val got = res.picked.filter { TagIds.T["knock"] in rules.tagsOf(it) }
            if (got.size < needKnock) {
                val pk = cands.filter { it !in res.picked && TagIds.T["knock"] in rules.tagsOf(it) }
                if (pk.isNotEmpty()) {
                    val kn = pk.maxWithOrNull(compareBy({ rules.tierOf(it, "sutong") ?: 0 }, { sc(it) }))!!
                    take(res, kn, "强击退（压缩阵型，切治疗机兵）")
                    must.add(kn)
                } else {
                    res.warn.add("⚠ 关卡要 1 个强击退（切治疗机兵），但池里没有")
                }
            }
        }

        // 排序键：(对策加分或速通, 群攻, 场景评级, 评分)
        fun rank(u: String): ComparableKey {
            val ts = rules.tagsOf(u)
            val bbu = bonusTs.isNotEmpty() && ts.any { it in bonusTs }
            return ComparableKey(
                listOf(
                    if (bbu || TagIds.T["clear"] in ts) 1.0 else 0.0,
                    if (TagIds.T["aoe"] in ts) 1.0 else 0.0,
                    (rules.tierOf(u, "sutong") ?: 0).toDouble(),
                    sc(u),
                ),
            )
        }

        val rest = cands.filter { it !in res.picked && TagIds.T["tank"] !in rules.tagsOf(it) }
        for (u in rest.sortedWith(keyComparator(::rank))) {
            if (res.picked.size >= n) break
            val ts = rules.tagsOf(u)
            val hasBb = bonusTs.isNotEmpty() && ts.any { it in bonusTs }
            if (TagIds.T["clear"] in ts || hasBb) {
                var why = if (TagIds.T["clear"] in ts) {
                    if (TagIds.T["aoe"] in ts) "速通适配群攻" else "速通（非群攻）"
                } else {
                    "对策加分（${bname(ts)}）"
                }
                if (TagIds.T["clear"] in ts && hasBb) why += "；对策加分（${bname(ts)}）"
                take(res, u, why)
            }
        }

        fill(res, cands, weak, n, ::rank, "兜底")

        val isBb: (String) -> Boolean = { u -> bonusTs.isNotEmpty() && rules.tagsOf(u).any { it in bonusTs } }
        attrPack(
            res,
            cands,
            ok = { u ->
                val ts = rules.tagsOf(u)
                TagIds.T["tank"] !in ts &&
                    (TagIds.T["clear"] in ts || (minDot != null && isDot(u)) || isBb(u))
            },
            protect = (res.picked.filter { u -> (minDot != null && isDot(u)) || isBb(u) }.toSet()) + must,
            scfn = { u -> sc(u) },
        )
        attrlimHard(res, cands, weak)
    }

    // ────────────────────────── 多方案 / 属性限定（1490~1571）──────────────────────────

    /**
     * PC 版 `_variant_best`(1490)：**多方案比较**（换坦克/加速，比整队总分）。
     * ★只在登记了 `range_spread` 的档启用；其它关卡**行为完全不变**。
     *
     * 注意：到"真的要比方案"那一步需要 `_burst_score`(904) —— 而 `base.score` 只有 burst 档才有，
     * 所以这条分支在 burst 移植前不会被执行（速通/dot 的 score 是 None，直接原样返回）。
     */
    private fun variantBest(
        stage: String,
        poolIn: List<String>,
        relicsSkill: Int,
        floor: Int,
        size: Int?,
        base: Res,
        failed: Boolean,
    ): Res {
        val pf = Profiles.profileOf(stage)
        if (!truthy(pf["range_spread"]) || base.score == null) return base
        val ids = poolIn.map { it.toString() }.filter { tables.unit(it) != null }
        val w = C.W["burst"]!!
        val atk = (pf["atk"] as? String) ?: "物理"
        fun sc(u: String): Double = burstScore(u, atk, w, false, false, false)

        val nt = pf["need_tank"] as? String
        var tks: List<String> = when {
            nt == "tank" -> {
                val t = ids.filter { TagIds.T["tank"] in rules.tagsOf(it) }
                if (t.isNotEmpty()) t else ids.filter { TagIds.T["mtank"] in rules.tagsOf(it) }
            }
            nt != null -> ids.filter {
                val ts = rules.tagsOf(it); TagIds.T["tank"] in ts || TagIds.T["mtank"] in ts
            }
            else -> emptyList()
        }
        val jss = ids.filter { TagIds.T["jiasu"] in rules.tagsOf(it) }
        tks = tks.sortedWith(descByDoubleVal { sc(it) }).take(3)
        val jssTop = jss.sortedWith(descByDoubleVal { sc(it) }).take(2)
        if (tks.size <= 1 && jssTop.size <= 1) return base        // 没有可换的

        var best = base
        val tried = mutableListOf("原方案" to base.score!!)
        val tkOpts: List<String?> = if (tks.isEmpty()) listOf(null) else tks.map { it }
        for (tk in tkOpts) {
            for (js in listOf<String?>(null) + jssTop) {
                if (tk == null && js == null) continue
                val alt = build(
                    stage, poolIn, relicsSkill, floor, size,
                    forceTank = tk, forceJiasu = js, failed = failed,
                )
                val altScore = alt.score ?: continue
                val nm = (if (tk != null) name(tk) else "原坦克") + (if (js != null) "+" + name(js) else "")
                tried.add(nm to altScore)
                if (altScore > best.score!!) {
                    alt.log.add("★多方案：换坦克/加速成 $nm 后总分 $altScore > 原队 ${best.score} → 取这队")
                    best = alt
                }
            }
        }
        best.log.add("多方案比总分（坦克×加速）：" + tried.joinToString(" ｜ ") { "${it.first}=${it.second}" })
        return best
    }

    /**
     * PC 版 `build_best`(1535)：**属性限定策略** —— 默认队（属性限定角色不参与）与
     * "同属性队"（按属性限定的属性另编一队）各自算**整队总分**，取高的；日志里记下对比。
     */
    fun buildBest(
        stage: String,
        pool: List<String>,
        relicsSkill: Int = 0,
        floor: Int = 1,
        size: Int? = null,
        failed: Boolean = false,
    ): Res {
        val n = size ?: C.SIZE
        var res = build(stage, pool, relicsSkill, floor, size, failed = failed)
        res = variantBest(stage, pool, relicsSkill, floor, size, res, failed)

        val al = res.attrlimUnits
        if (al.isEmpty()) return res
        val attrs = al.mapNotNull {
            tables.unit(it)?.optString("attr")?.takeIf { a -> a.isNotEmpty() }
        }.toSortedSet().toList()

        if (res.score == null) {
            // 该档没有整队总分（速通/dot）→ 不比较，只记一行
            val names = al.filter { tables.unit(it) != null }
                .joinToString("、") { tables.unit(it)?.optString("name").orEmpty() }
            res.log.add("属性限定：$names 已在默认队里排除（本档暂无整队总分可比，未试同属性队）")
            return res
        }
        for (attr in attrs) {
            val same = pool.map { it.toString() }.filter { tables.unit(it)?.optString("attr") == attr }
            if (same.size < n) {
                res.log.add("属性限定：同属性（$attr）只有 ${same.size} 人 < $n → 不试同属性队")
                continue
            }
            var alt = build(stage, same, relicsSkill, floor, size, forceAttr = attr, failed = failed)
            alt = variantBest(stage, same, relicsSkill, floor, size, alt, failed)
            val altScore = alt.score ?: continue
            res.log.add("属性限定：同属性队（$attr）整队总分 $altScore ｜ 默认队 ${res.score}")
            if (altScore > res.score!!) {
                alt.log.add("★属性限定：改用**同属性队**（$attr $altScore > ${res.score}）")
                res = alt
            }
        }
        return res
    }

    // ────────────────────────── 尚未移植（下一批）──────────────────────────

    // ────────────────────────── 算法 2：纯 dot 关（808）──────────────────────────

    private fun algoDot(
        pf: Map<String, Any?>,
        candsIn: MutableList<String>,
        weakIn: List<String>,
        n: Int,
        relicsSkill: Int,
        res: Res,
    ) {
        // 属性限定 = 硬排除（好朋友X 不凑同属性，这类角色基本没戏）
        var cands = candsIn.filter { TagIds.T["attrlim"] !in rules.tagsOf(it) }.toMutableList()
        var weak = weakIn.filter { TagIds.T["attrlim"] !in rules.tagsOf(it) }

        fun isDot(u: String): Boolean =
            rules.dotKindsOf(u).isNotEmpty() || TagIds.T_DOT in rules.tagsOf(u)

        if (cands.none { isDot(it) }) {
            res.fail = "池子里没有任何 dot 角色 → 组队失败"
            return
        }

        // 坦克|兼职坦克 必须有；dot 关优先选【非抑制】坦克（抑制者=全队 dot 减半）
        val tanks = cands.filter {
            val ts = rules.tagsOf(it)
            TagIds.T["tank"] in ts || TagIds.T["mtank"] in ts
        }
        if (tanks.isNotEmpty()) {
            val good = tanks.filter { TagIds.T_DOT_SUP !in rules.tagsOf(it) }
            val pick = (if (good.isNotEmpty()) good else tanks)
                .maxWithOrNull(compareBy { rules.tierOf(it) ?: 0 })!!      // 平局取先出现的（与 Python max 一致）
            val note = if (good.isNotEmpty()) "" else "（仅 dot抑制坦克可选 → 全队 dot 减半）"
            take(res, pick, "坦克|兼职坦克（必须）$note")
        } else {
            res.warn.add("⚠ 池里没有 坦克|兼职坦克（这关要求必须有）")
        }

        firstTp(res, cands, relicsSkill, replaceWithJiasu = true)

        // dot 位：最多 3 人，种类越多越好。
        // ★抑制者（薇奥莉特）会让【全队】dot 效率 ×½ ⇒ 边际收益必须按"全队 dot 当量"的净增量算
        val have = mutableSetOf<String>()
        for (u in res.picked) have.addAll(rules.dotKindsOf(u))
        fun teamSup(): Double =
            if (res.picked.any { TagIds.T_DOT_SUP in rules.tagsOf(it) }) 0.5 else 1.0

        for (round in 0 until 3) {
            var best: String? = null
            var bestKey: Pair<Double, Int>? = null
            val sup = teamSup()
            val old = sup * have.size
            for (u in cands) {
                if (u in res.picked) continue
                val ks = rules.dotKindsOf(u)
                if ((ks - have).isEmpty()) continue                    // 不带来新种类就不选（种类优先）
                val nsup = if (TagIds.T_DOT_SUP in rules.tagsOf(u) || sup < 1.0) 0.5 else 1.0
                val gain = nsup * (have + ks).size - old
                val key = gain to (rules.tierOf(u) ?: 0)
                // ★Kotlin 的 Pair 不是 Comparable（Python 元组可以直接比）→ 显式逐项比、严格大于才换
                val bk = bestKey
                if (bk == null || key.first > bk.first || (key.first == bk.first && key.second > bk.second)) {
                    best = u; bestKey = key
                }
            }
            val b = best ?: break
            if (bestKey!!.first <= 0) break                            // 加谁都不增 dot 当量 → 收手
            val preSup = teamSup()                                     // ★注意：判"×½"用的是**加她之前**的队倍率
            val newKinds = (rules.dotKindsOf(b) - have).size
            val suffix = if (TagIds.T_DOT_SUP in rules.tagsOf(b) || preSup < 1.0) "，dot抑制×½（全队）" else ""
            take(res, b, "dot（种类 +$newKinds$suffix）")
            have.addAll(rules.dotKindsOf(b))
        }

        // ★后门封堵：dot 循环没选抑制者 ⇒ 后面"治疗/输出/凑数"也不许她混进来（否则全队照样减半）
        if (res.picked.none { TagIds.T_DOT_SUP in rules.tagsOf(it) }) {
            cands = cands.filter { TagIds.T_DOT_SUP !in rules.tagsOf(it) }.toMutableList()
            weak = weak.filter { TagIds.T_DOT_SUP !in rules.tagsOf(it) }
        }

        // 余位：治疗（群充加分）→ 单体输出核心
        fun rankHeal(u: String): ComparableKey {
            val ts = rules.tagsOf(u)
            return ComparableKey(
                listOf(
                    if (TagIds.T["heal"] in ts) 1.0 else 0.0,
                    if (TagIds.T["tp"] in ts) 1.0 else 0.0,
                    (rules.tierOf(u) ?: 0).toDouble(),
                ),
            )
        }
        for (u in cands.filter { it !in res.picked }.sortedWith(keyComparator(::rankHeal))) {
            if (res.picked.size >= n) break
            val ts = rules.tagsOf(u)
            if (TagIds.T["heal"] in ts) {
                take(res, u, "治疗位" + (if (TagIds.T["tp"] in ts) "（带群充）" else ""))
            }
        }

        fun rankCore(u: String): ComparableKey {
            val ts = rules.tagsOf(u)
            return ComparableKey(
                listOf(
                    if (TagIds.T["dps"] in ts && TagIds.T["burst"] in ts) 1.0 else 0.0,
                    if (TagIds.T["dps"] in ts) 1.0 else 0.0,
                    if (TagIds.T["burst"] in ts) 1.0 else 0.0,
                    (rules.tierOf(u) ?: 0).toDouble(),
                ),
            )
        }
        for (u in cands.filter { it !in res.picked }.sortedWith(keyComparator(::rankCore))) {
            if (res.picked.size >= n) break
            if (TagIds.T["dps"] in rules.tagsOf(u)) take(res, u, "单体输出核心（余位）")
        }

        // 不凑同属性（不做 _attr_pack）；属性限定已硬排除
        fill(res, cands, weak, n, { u -> ComparableKey(listOf((rules.tierOf(u) ?: 0).toDouble())) }, "兜底")
    }

    // ────────────────────────── 算法 3：单体爆发（904~1487）──────────────────────────

    /**
     * PC 版 `_burst_score`(904)：单体爆发评分。
     * `atk` = "物理"/"魔法"/"mixed"（mixed = 不区分系别，可混编）；`noBreak` = 破甲不计分；
     * `boss` = Boss 关 → 用场景评级 `tier_boss`。
     */
    private fun burstScore(
        uid: String,
        atk: String,
        w: Map<String, Double>,
        noType: Boolean,
        noBreak: Boolean,
        boss: Boolean,
    ): Double {
        val ts = rules.tagsOf(uid)
        val mixed = atk == "mixed" || noType
        val t = if (boss) rules.tierOf(uid, "boss") else rules.tierOf(uid)
        var s = (t ?: 0) * w["tier"]!!
        if (!mixed && (rules.effAtk(uid) == atk || TagIds.T["both"] in ts)) s += w["same_type"]!!
        if (TagIds.T["dps"] in ts) {
            s += w["core"]!!
            if (TagIds.T["aoe"] in ts) s += w["aoe_core"]!!
        }
        if (TagIds.T["burst"] in ts) s += w["single"]!!
        if (TagIds.T["weaksingle"] in ts) s += w["weaksingle"]!!
        // ★对策·美空（need_direct 关卡，2026-10-19 用户）：她的持续治疗让**没有定向手段的
        //   单体输出**完全没用 —— 重扣（打不出有效伤害）。有 u_fix_relic（定向）才豁免。
        //   惩罚通过 w 里带不带这个键生效：只有 need_direct 的档会加上它。
        w["nondirect_single"]?.let { nd ->
            if (TagIds.T["dps"] in ts && TagIds.T["burst"] in ts && TagIds.T["fixrelic"] !in ts) s += nd
        }
        if (!noBreak) {
            if (TagIds.T["pbreak"] in ts) {
                s += if (mixed || atk == "物理") w["bp_break"]!! else w["gauge_break"]!!
            }
            if (TagIds.T["mbreak"] in ts) {
                s += if (mixed || atk == "魔法") w["bp_break"]!! else w["gauge_break"]!!
            }
        }
        if (TagIds.T["pbuff"] in ts && (mixed || atk == "物理")) s += w["bp_buff"]!!
        if (TagIds.T["mbuff"] in ts && (mixed || atk == "魔法")) s += w["bp_buff"]!!
        if (TagIds.T["tp"] in ts) s += w["tp"]!!
        if (TagIds.T["heal"] in ts) s += w["heal"]!!
        if (TagIds.T["drain"] in ts) s += w["drain"]!!
        if (TagIds.T["jiasu"] in ts) s += w["jiasu"]!!
        val roles = listOf(
            TagIds.T["dps"], TagIds.T["burst"], TagIds.T["pbreak"], TagIds.T["mbreak"],
            TagIds.T["pbuff"], TagIds.T["mbuff"], TagIds.T["tp"], TagIds.T["jiasu"],
            TagIds.T["drain"], TagIds.T["heal"],
        ).count { it in ts }
        if (roles >= 3) s += w["multi"]!!                      // 多功能更优
        if (TagIds.T["bossweak"] in ts) s += C.BOSSWEAK        // 对boss弱 → 非速通档扣分
        return s
    }

    /**
     * PC 版 `_burst_team`(941)：纯按评分取（同系=加分），人不够拿异系/凑数堆填。
     * 约束：need_tank / need_heal / need_cc / need_direct / ghost；★加速**至多 1 个**。
     * `fixed` 里的都是**结构位**（后面的同属性优化不许换掉）。
     */
    private fun burstTeam(
        cands: List<String>,
        weak: List<String>,
        n: Int,
        atk: String,
        pf: Map<String, Any?>,
    ): BurstTeamResult {
        var w = C.W["burst"]!!
        val boss = (pf["key"] as? String ?: "").startsWith("Boss_")
        // ★双敌/多目标：几个敌人加起来就是群体 → 「单体效果低」免扣
        if (truthy(pf["multi"])) w = w + ("weaksingle" to 0.0)
        // ★对策·美空（need_direct）：非定向的单体输出重扣（见 burstScore 里的说明）
        if (truthy(pf["need_direct"])) w = w + ("nondirect_single" to C.NONDIRECT_SINGLE)
        val mixed = truthy(pf["no_type"])
        val nb = truthy(pf["no_break"])
        fun sc(u: String): Double = burstScore(u, atk, w, mixed, nb, boss)

        val picked = mutableListOf<String>()
        val warns = mutableListOf<String>()
        val fixed = mutableListOf<String>()
        fun add(u: String) {
            picked.add(u)
            fixed.add(u)
        }

        // ① 必需位
        val nt = pf["need_tank"] as? String
        if (nt != null) {
            val tip: String
            val tks: List<String>
            if (nt == "tank") {
                tks = cands.filter { TagIds.T["tank"] in rules.tagsOf(it) }
                tip = "真坦克"
            } else {
                tks = cands.filter {
                    val ts = rules.tagsOf(it); TagIds.T["tank"] in ts || TagIds.T["mtank"] in ts
                }
                tip = "坦克或兼职坦克"
            }
            val fTk = pf["force_tank"] as? String                  // "换个坦克再编一队"指定的人选
            if (fTk != null && fTk in tks) {
                add(fTk)
            } else if (tks.isNotEmpty()) {
                add(tks.maxWithOrNull(compareByDouble { sc(it) })!!)
            } else if (nt == "tank") {
                // 池里没有真坦克 → 退一步让**兼职坦克**顶位（并要求它站最前）
                val mt = cands.filter { TagIds.T["mtank"] in rules.tagsOf(it) }
                if (mt.isNotEmpty()) {
                    val pick = mt.maxWithOrNull(compareByDouble { sc(it) })!!
                    add(pick)
                    warns.add("⚠ 池里没有真坦克 → 用兼职坦克 ${name(pick)} 顶坦克位（要求它站最前）")
                } else {
                    warns.add("⚠ 关卡要求真坦克，池里连兼职坦克都没有 → 坦克位空出")
                }
            } else {
                warns.add("⚠ 关卡要求$tip，但池里没有")
            }
        }
        if (truthy(pf["need_heal"])) {
            val hl = cands.filter { TagIds.T["heal"] in rules.tagsOf(it) && it !in picked }
            if (hl.isNotEmpty()) add(hl.maxWithOrNull(compareByDouble { sc(it) })!!)
            else warns.add("⚠ 关卡要求 1 治疗，但池里没有治疗角色")
        }
        if (truthy(pf["need_cc"])) {
            val cc = cands.filter {
                it !in picked && (TagIds.T["cc"] in rules.tagsOf(it) || TagIds.T["taunt"] in rules.tagsOf(it))
            }
            if (cc.isNotEmpty()) add(cc.maxWithOrNull(compareByDouble { sc(it) })!!)
            else warns.add("⚠ 关卡要求 嘲讽|控制，但池里没有")
        }
        if (truthy(pf["need_direct"])) {
            val dr = cands.filter { it !in picked && TagIds.T["fixrelic"] in rules.tagsOf(it) }
            if (dr.isNotEmpty()) {
                add(dr.maxWithOrNull(compareByDouble { sc(it) })!!)
            } else {
                val ac = cands.filter {
                    it !in picked && TagIds.T["dps"] in rules.tagsOf(it) && TagIds.T["aoe"] in rules.tagsOf(it)
                }
                if (ac.isNotEmpty()) add(ac.maxWithOrNull(compareByDouble { sc(it) })!!)
                else warns.add("⚠ 关卡要求 定向输出|核心群体输出，但池里都没有")
            }
        }
        if (truthy(pf["ghost"])) {
            val ny = cands.filter { "u_fix_ghost" in rules.tagsOf(it) }
            if (ny.isNotEmpty()) add(ny[0])                        // 方案1：涅比亚必上
        }

        // ② 评分填充（加速至多 1）
        val jsSeen = picked.filter { TagIds.T["jiasu"] in rules.tagsOf(it) }.toMutableList()
        val fk0 = tankExtraKey(picked, pf["need_tank"] != null) { sc(it) }
        val fJs = pf["force_jiasu"] as? String
        val fk: (String) -> Double = if (fJs != null) {
            { u -> fk0(u) + (if (u == fJs) 1000.0 else 0.0) }
        } else {
            fk0
        }
        var order = cands.sortedWith(Comparator { a, b -> fk(b).compareTo(fk(a)) })
        // ★坦克必须站 1 号位：只先取 range 比它大的，不够才放宽（并 warn）
        if (pf["need_tank"] != null) {
            val tk = fixed.firstOrNull {
                val ts = rules.tagsOf(it); TagIds.T["tank"] in ts || TagIds.T["mtank"] in ts
            }
            val tkRng = tk?.let { rangeOrNull(it) }
            if (tkRng != null) {
                val front = order.filter { rng(it) > tkRng }
                val rest = order.filter { it !in front }
                order = front + rest
                val short = (n - picked.size) - front.size
                if (short > 0) {
                    warns.add(
                        "⚠ 坦克 ${name(tk)}(range=$tkRng) 要站最前，但池里 range 比它大的只有 ${front.size} 个 → " +
                            "有 $short 个位置可能站到坦克前面",
                    )
                }
            }
        }
        val hard = truthy(pf["range_spread_hard"]) && truthy(pf["range_spread"])
        val defer = mutableListOf<String>()
        for (u in order) {
            if (picked.size >= n) break
            if (u in picked) continue
            if (hard && !spreadOk(picked + u, pf)) {
                defer.add(u)
                continue
            }
            if (TagIds.T["jiasu"] in rules.tagsOf(u)) {
                if (jsSeen.isNotEmpty()) continue               // 已有一个加速 → 不再要
                jsSeen.add(u)
            }
            picked.add(u)
        }
        for (u in defer) {                                      // 硬限制凑不满 → 放宽（打 warn）
            if (picked.size >= n) break
            if (u in picked) continue
            picked.add(u)
            warns.add(
                "⚠ 站位跨度硬限制（≤${pyStr(pf["range_spread"])}）：凑不满 $n 人 → " +
                    "放进 ${name(u)}(range=${pyStr(rangeOrNull(u))}) → 跨度 ${rangeSpread(picked)}",
            )
        }
        for (u in weak) {
            if (picked.size >= n) break
            if (u !in picked) picked.add(u)
        }
        return BurstTeamResult(picked, warns, fixed)
    }

    /**
     * PC 版 `_ghost_plan3`(1288)：幽灵 方案3 —— **前卫坦克(range≤300) + 后卫(range 尽量 ≥600)**。
     * 站位是游戏按 range 升序自排的 ⇒ 坦克必须是全队 range 最小的那个，所以只挑 range > 坦克 的当后卫。
     *
     * @return (picked, tank, gap) 或 null（池里没有 range≤300 的坦克 / 坦克占不到 1 号位）
     */
    private fun ghostPlan3(cands: List<String>, weak: List<String>, n: Int, atk: String): Triple<List<String>, String, Int>? {
        val w = C.W["burst"]!!
        fun sc(u: String): Double = burstScore(u, atk, w, false, false, false)   // ★PC 这里没传 boss/no_type
        val tanks = cands.filter { TagIds.T["tank"] in rules.tagsOf(it) && rng(it) <= 300 }
        var bestKey: List<Double>? = null
        var bestPicked: List<String>? = null
        var bestTk = ""
        var bestGap = 0
        for (tk in tanks.sortedWith(descByDoubleVal { sc(it) })) {
            val others = (cands + weak).filter { it != tk && rng(it) > rng(tk) }
            if (others.size < n - 1) continue                  // 这个坦克太靠后，占不到 1 号位
            val far = others.filter { rng(it) >= 600 }.sortedWith(descByDoubleVal { sc(it) })
            val farSet = far.toSet()
            val near = others.filter { it !in farSet }.sortedWith(descByInt { rng(it) })  // 兜底：range 大的先
            val picked = mutableListOf(tk)
            picked.addAll(far.take(n - 1))
            if (picked.size < n) picked.addAll(near.take(n - picked.size))
            val gap = if (picked.size > 1) picked.drop(1).minOf { rng(it) } - rng(tk) else 0
            val key = listOf(gap.toDouble(), picked.sumOf { sc(it) })
            val bk = bestKey
            if (bk == null || compareKeys(key, bk) > 0) {
                bestKey = key; bestPicked = picked; bestTk = tk; bestGap = gap
            }
        }
        val p = bestPicked ?: return null
        return Triple(p, bestTk, bestGap)
    }

    /** PC 版 `_algo_burst`(1318)：单体爆发（差异全在 profile 参数里）。 */
    private fun algoBurst(
        pf: Map<String, Any?>,
        cands: MutableList<String>,
        weak: List<String>,
        n: Int,
        relicsSkill: Int,
        res: Res,
    ) {
        val w = C.W["burst"]!!
        val wt = C.W["burst_team"]!!
        val atkFixed = pf["atk"] as? String
        val ghost = truthy(pf["ghost"])
        val edge = (pf["edge"] as? String) ?: "物理"            // 系别偏好：默认物理略优
        val opts: List<String> = when {
            truthy(pf["no_type"]) -> listOf("mixed")
            atkFixed != null -> listOf(atkFixed)
            else -> listOf("物理", "魔法")
        }

        var bestTotal: Double? = null
        var bestAtk = ""
        var bestPicked: List<String> = emptyList()
        var bestWarns: List<String> = emptyList()
        var bestProt: Set<String> = emptySet()
        val branch = mutableListOf<Pair<String, Double>>()

        for (atk in opts) {
            val bt = burstTeam(cands, weak, n, atk, pf)
            val picked = bt.picked
            // ★注意：PC 这里（和 _scb / ghost_plan3）**都没有**把 boss 传进 _burst_score
            fun sc(u: String): Double = burstScore(u, atk, w, truthy(pf["no_type"]), truthy(pf["no_break"]), false)
            var total = picked.sumOf { sc(it) } + rules.attrScore(picked) +
                tankExtraPenalty(picked, pf["need_tank"] != null)
            val prot = bt.fixed.toMutableSet()
            val js = picked.filter { TagIds.T["jiasu"] in rules.tagsOf(it) }
            if (js.isNotEmpty()) {
                total += wt["jiasu"]!!                            // 有加速 → 额外加分
                prot.add(js[0])
            }
            if (truthy(pf["need_aoe_core"])) {
                val ac = picked.filter { TagIds.T["dps"] in rules.tagsOf(it) && TagIds.T["aoe"] in rules.tagsOf(it) }
                if (ac.isNotEmpty()) {
                    total += wt["aoe_core"]!!                     // 第一个核心群攻有额外收益
                    prot.add(ac[0])
                }
            }
            if (atk == edge && atkFixed == null && atk != "mixed") total += wt["edge"]!!
            if (truthy(pf["drain_team"])) {
                val dn = picked.filter { TagIds.T["drain"] in rules.tagsOf(it) }
                if (dn.isNotEmpty()) {
                    total += wt["drain"]!!
                    prot.add(dn[0])
                }
            }
            if (truthy(pf["need_direct"])) {
                val dr = picked.filter { TagIds.T["fixrelic"] in rules.tagsOf(it) }
                if (dr.isNotEmpty()) {
                    total += wt["direct"]!!
                    prot.add(dr[0])
                }
            }
            if (ghost) {
                val gh = picked.filter { "u_fix_ghost" in rules.tagsOf(it) }
                if (gh.isNotEmpty()) {
                    total += 8.0                                      // 方案1 成立
                    prot.add(gh[0])
                }
            }
            branch.add(atk to round1(total))
            val bt0 = bestTotal
            if (bt0 == null || total > bt0) {
                bestTotal = total; bestAtk = atk; bestPicked = picked
                bestWarns = bt.warns; bestProt = prot
            }
        }

        val atk = bestAtk
        res.picked.clear()
        res.picked.addAll(bestPicked)
        res.atk = atk
        res.score = round1(bestTotal!!)
        res.warn.addAll(bestWarns)
        val prot = bestProt

        // ★谁顶了"坦克位"（真坦克/兼职坦克都可能）—— 理由里要能看出这条关卡需求
        var tankSlot: String? = null
        if (pf["need_tank"] != null) {
            for (u in res.picked) {
                if (u in prot) {
                    val ts = rules.tagsOf(u)
                    if (TagIds.T["tank"] in ts || TagIds.T["mtank"] in ts) {
                        tankSlot = u
                        break
                    }
                }
            }
        }
        for (u in res.picked) {
            val ts = rules.tagsOf(u)
            res.reasons[u] = when {
                u == tankSlot ->
                    if (TagIds.T["tank"] in ts) "坦克（关卡要求）" else "兼职坦克（顶关卡要求的坦克位）"
                ghost && "u_fix_ghost" in ts -> "对策·幽灵领主（方案1）"
                TagIds.T["tank"] in ts && pf["need_tank"] != null -> "坦克（关卡要求）"
                TagIds.T["heal"] in ts && truthy(pf["need_heal"]) -> "治疗（关卡要求）"
                truthy(pf["need_cc"]) && (TagIds.T["cc"] in ts || TagIds.T["taunt"] in ts) -> "嘲讽/控制（关卡要求）"
                truthy(pf["need_direct"]) && TagIds.T["fixrelic"] in ts -> "定向输出（对策·美空，关卡要求）"
                TagIds.T["dps"] in ts && TagIds.T["aoe"] in ts ->
                    "核心群体输出" + (if (truthy(pf["need_aoe_core"])) "（第一个有额外收益）" else "")
                TagIds.T["dps"] in ts && TagIds.T["burst"] in ts -> "核心单体输出"
                TagIds.T["dps"] in ts -> "输出核心"
                TagIds.T["burst"] in ts -> "单体输出"
                atk != "mixed" && rules.effAtk(u) != atk && rules.effAtk(u).isNotEmpty() &&
                    TagIds.T["both"] !in ts -> "异系填充（同系人不够）"
                else -> {
                    val extra = listOf(
                        "破甲" to listOf(TagIds.T["pbreak"], TagIds.T["mbreak"]),
                        "buff" to listOf(TagIds.T["pbuff"], TagIds.T["mbuff"]),
                        "群充" to listOf(TagIds.T["tp"]),
                        "加速" to listOf(TagIds.T["jiasu"]),
                        "治疗" to listOf(TagIds.T["heal"]),
                        "抽蓝" to listOf(TagIds.T["drain"]),
                    ).filter { (_, tt) -> tt.any { x -> x in ts } }.map { it.first }
                    if (extra.isNotEmpty()) "多功能/辅助（${extra.joinToString("、")}）" else "填充"
                }
            }
        }

        val side = when {
            atkFixed != null -> "关卡指定"
            atk == "mixed" -> "关卡允许混编"
            edge == "none" -> "各算一遍取高，无系别偏好"
            else -> "各算一遍取高，$edge 有 +${pyNum(wt["edge"]!!)} 偏好"
        }
        res.log.add("整队系别 = **${if (atk == "mixed") "不区分（可混编）" else atk}**（$side）")
        if (branch.size > 1) {
            res.log.add("分支得分： " + branch.joinToString(" / ") { "${it.first} ${it.second}" } + " → 选 $atk")
        }
        if (truthy(pf["need_aoe_core"])) res.log.add("关卡要求：第一个「核心群体输出」有额外收益")
        if (truthy(pf["need_cc"])) res.log.add("关卡要求：队里要有 嘲讽|控制")
        if (truthy(pf["need_direct"])) res.log.add("关卡要求：定向输出（对策·美空）**更优**；不是定向的单体输出会重扣")
        if (truthy(pf["no_break"])) res.log.add("本体 0 防御 → 破甲不计分")
        if (atk != "mixed") {
            val same = res.picked.filter {
                rules.effAtk(it) == atk || rules.effAtk(it).isEmpty() || TagIds.T["both"] in rules.tagsOf(it)
            }
            if (same.size < res.picked.size) {
                res.log.add("⚠ 同系只有 ${same.size} 个 → 有 ${res.picked.size - same.size} 个位置是**异系填充**（人不够）")
            }
        }

        // 幽灵：没有涅比亚才走方案3，否则方案4
        if (ghost && res.picked.none { "u_fix_ghost" in rules.tagsOf(it) }) {
            val p3 = ghostPlan3(cands, weak, n, atk)
            if (p3 != null) {
                val (picked3, tk, gap) = p3
                res.picked.clear()
                res.picked.addAll(picked3)
                res.reasons.clear()
                for (u in res.picked) {
                    res.reasons[u] = if (u == tk) {
                        "方案3·前卫坦克（range≤300）"
                    } else {
                        "方案3·后卫（与 1 号位拉开 $gap 躲 AOE）"
                    }
                }
                val minRng = res.picked.filter { it != tk }.minOfOrNull { rangeOrNull(it) ?: 0 } ?: 0
                res.log.add(
                    "无涅比亚 → 走**方案3**：前卫坦克 ${name(tk)}(range=${pyStr(rangeOrNull(tk))}) + " +
                        "后卫（最小 range=$minRng，拉开 $gap）",
                )
                if (gap < 200) {
                    res.warn.add(
                        "⚠ 池里缺少 range≥600 的后卫 → 只拉开 $gap（躲 AOE 效果差，" +
                            "1 号位坦克=${name(tk)} range=${pyStr(rangeOrNull(tk))}）",
                    )
                }
            } else {
                res.log.add("无涅比亚、也凑不出方案3（前卫≤300 + 后卫拉开）→ 退到方案4：纯单体输出")
            }
        }

        val scen = if ((res.stage ?: "").startsWith("Boss_")) "boss" else "all"
        fun scb(u: String): Double = burstScore(u, atk, w, truthy(pf["no_type"]), truthy(pf["no_break"]), false)
        // ★teampen 含"站位跨度超限"扣分 ⇒ 换人时也不会把跨度换大
        val teampen: (List<String>) -> Double = { t ->
            tankExtraPenalty(t, pf["need_tank"] != null) + spreadPenalty(t, pf).first
        }
        var tkRng: Int? = null
        if (pf["need_tank"] != null) {
            for (u in res.picked) {
                if (u in prot) {
                    val ts = rules.tagsOf(u)
                    if (TagIds.T["tank"] in ts || TagIds.T["mtank"] in ts) {
                        tkRng = rangeOrNull(u)
                        break
                    }
                }
            }
        }
        val tkr = tkRng
        val okFront: (String) -> Boolean = { u -> if (tkr == null) true else rng(u) > tkr }
        if (atk == "mixed") {
            attrPack(res, cands, protect = prot, scen = scen, ok = okFront, scfn = { scb(it) }, teampen = teampen)
        } else {
            attrPack(
                res, cands, protect = prot, scen = scen, scfn = { scb(it) }, teampen = teampen,
                ok = { u ->
                    okFront(u) && (rules.effAtk(u) == atk || rules.effAtk(u).isEmpty() ||
                        TagIds.T["both"] in rules.tagsOf(u))
                },
            )
        }
        // ★整队总分在上面已记入 res.score（PC 同款：_attr_pack 就地改 picked，这里不再重算）；
        //   这里只按**最终**队伍叠加站位跨度扣分
        val (sp, spread, lim) = spreadPenalty(res.picked, pf)
        if (lim != null) {
            res.spread = spread
            res.score = round1((res.score ?: 0.0) + sp)
            res.log.add(spreadNote(res.picked, spread, lim, sp, truthy(pf["range_spread_hard"])))
        }
    }

    // ────────────────────────── 算法 4：多目标 boss（1072~1285）──────────────────────────

    /**
     * PC 版 `_aoe_score`(1072)：多目标 boss 评分。
     * ★不扣「单体效果低」（多目标里"对单弱"不是缺点）；★boss 档用场景评级 `tier_boss`。
     */
    private fun aoeScore(uid: String, atk: String, w: Map<String, Double>, bing: Boolean, boss: Boolean): Double {
        val ts = rules.tagsOf(uid)
        val t = if (boss) rules.tierOf(uid, "boss") else rules.tierOf(uid)
        var s = (t ?: 0) * w["tier"]!!
        if (rules.effAtk(uid) == atk || TagIds.T["both"] in ts) s += w["same_type"]!!
        if (TagIds.T["aoe"] in ts) s += w["aoe"]!!
        if (TagIds.T["dps"] in ts) s += w["core"]!!
        if (TagIds.T["burst"] in ts) s += w["single"]!!
        if ((TagIds.T["pbuff"] in ts && atk == "物理") || (TagIds.T["mbuff"] in ts && atk == "魔法")) {
            s += w["buff"]!!
        }
        if (TagIds.T["tp"] in ts) s += w["tp"]!!
        if (TagIds.T["heal"] in ts) s += w["heal"]!!
        if (TagIds.T["jiasu"] in ts) s += w["jiasu"]!!
        // "非群攻的输出核心"扣分；但它若还干 群充/治疗/加速 的活 → 免扣
        val teamFuncs = setOf(TagIds.T["tp"], TagIds.T["heal"], TagIds.T["jiasu"])
        if (TagIds.T["dps"] in ts && TagIds.T["aoe"] !in ts && (ts intersect teamFuncs).isEmpty()) {
            s += w["solo_core"]!!
        }
        if (bing && (TagIds.T_DOT in ts || rules.dotKindsOf(uid).isNotEmpty())) s += w["dot"]!!
        val roles = listOf(
            TagIds.T["aoe"], TagIds.T["dps"], TagIds.T["pbuff"],
            TagIds.T["mbuff"], TagIds.T["tp"], TagIds.T["jiasu"],
        ).count { it in ts }
        if (roles >= 3) s += w["multi"]!!
        if (TagIds.T["bossweak"] in ts) s += C.BOSSWEAK
        return s
    }

    /**
     * PC 版 `_algo_aoe`(1104)：多目标 boss —— 1 坦克（真坦克优先）+ **恰好 1 加速** + 多个高分核心群攻。
     *
     * ★角色兼两职（如 纯 = 坦克+加速）时**两种分配都算一遍取更好的**；
     * ★坦克必须站 1 号位（只先取 range 比它大的，不够才放宽并 warn）；
     * ★硬跨度限制档：先跳过会超限的候选，凑不满再放宽（warn）。
     */
    private fun algoAoe(
        pf: Map<String, Any?>,
        cands: MutableList<String>,
        weak: List<String>,
        n: Int,
        relicsSkill: Int,
        res: Res,
    ) {
        val w = C.W["aoe"]!!
        val wt = C.W["burst_team"]!!
        val bing = truthy(pf["bing"])
        val boss = (pf["key"] as? String ?: "").startsWith("Boss_")
        val edge = (pf["edge"] as? String) ?: "魔法"        // aoe 默认魔法略优（profile 可覆盖）
        val edgeW = wt["edge"]!!
        val opts: List<String> = (pf["atk"] as? String)?.let { listOf(it) } ?: listOf("魔法", "物理")

        var bestTotal: Double? = null
        var bestAtk = ""
        var bestPicked: List<String> = emptyList()
        var bestRoles: Map<String, String> = emptyMap()
        var bestNotes: List<String> = emptyList()
        var bestWarns: List<String> = emptyList()

        for (atk in opts) {
            fun sc(u: String): Double = aoeScore(u, atk, w, bing, boss)

            val jsAll = cands.filter { TagIds.T["jiasu"] in rules.tagsOf(it) }
                .sortedWith(descByDoubleVal { sc(it) })
            var tanks = cands.filter { TagIds.T["tank"] in rules.tagsOf(it) }
            var iceFirst: String? = null
            if (bing) {
                val ice = tanks.filter { "u_fix_icewolf" in rules.tagsOf(it) }
                if (ice.isNotEmpty()) {
                    iceFirst = ice[0]
                    tanks = listOf(iceFirst) + tanks.filter { it != iceFirst }
                }
            }

            // ① 组装 (坦克, 加速) 候选组合
            val variants = mutableListOf<Triple<String?, String?, String?>>()
            if (tanks.isNotEmpty()) {
                var t3 = tanks.sortedWith(descByDoubleVal { sc(it) }).take(3)
                val ifx = iceFirst
                if (ifx != null && ifx !in t3) t3 = listOf(ifx) + t3.take(2)
                for (tk in t3) {
                    val js = jsAll.filter { it != tk }
                    variants.add(Triple(tk, if (js.isNotEmpty()) js[0] else null, "坦克"))
                    if (TagIds.T["jiasu"] in rules.tagsOf(tk)) {          // 她兼两职 → 也试"她当加速"
                        val alt = t3.filter { it != tk }
                        if (alt.isNotEmpty()) variants.add(Triple(alt[0], tk, "坦克"))
                    }
                }
            } else {
                val mt = cands.filter { TagIds.T["mtank"] in rules.tagsOf(it) }
                    .sortedWith(descByDoubleVal { sc(it) })
                if (mt.isNotEmpty()) {
                    val tk = mt[0]
                    val js = jsAll.filter { it != tk }
                    variants.add(Triple(tk, if (js.isNotEmpty()) js[0] else null, "兼职坦克"))
                }
            }
            if (variants.isEmpty()) {
                variants.add(Triple(null, if (jsAll.isNotEmpty()) jsAll[0] else null, null))
            }

            val seen = mutableSetOf<String>()
            for ((tk, js, kind) in variants) {
                if (!seen.add("$tk|$js")) continue
                val picked = mutableListOf<String>()
                val roles = LinkedHashMap<String, String>()
                val notes = mutableListOf<String>()
                val warns = mutableListOf<String>()

                // ② 坦克
                if (tk != null) {
                    picked.add(tk)
                    when {
                        bing && tk == iceFirst -> {
                            roles[tk] = "对策·冰狼（专业对策坦克）"
                            notes.add("坦克=黑姐（对策·冰狼 专业对策坦克）")
                        }
                        kind == "兼职坦克" -> {
                            roles[tk] = "兼职坦克（没有真坦克，容易出意外）"
                            warns.add("⚠ 池里没有真坦克 → 用兼职坦克 ${name(tk)} 顶（容易出意外）")
                        }
                        else -> roles[tk] = "坦克"
                    }
                }

                // ③ 加速：恰好 1 个
                if (js != null && js !in picked) {
                    picked.add(js)
                    roles[js] = "加速（恰好 1 个）"
                    notes.add("加速恰好 1 个" + (if (js == tk) "（与坦克同为一人）" else ""))
                }

                // ④ 其余按评分填
                val fk = tankExtraKey(picked, true) { sc(it) }
                var order = cands
                    .filter { it !in picked && TagIds.T["jiasu"] !in rules.tagsOf(it) }
                    .sortedWith(Comparator { a, b -> fk(b).compareTo(fk(a)) })   // 稳定降序
                if (tk != null) {
                    val tkRng = rangeOrNull(tk)
                    if (tkRng != null) {
                        val front = order.filter { rng(it) > tkRng }
                        val rest = order.filter { it !in front }
                        order = front + rest
                        val short = (n - picked.size) - front.size
                        if (short > 0) {
                            warns.add(
                                "⚠ 坦克 ${name(tk)}(range=$tkRng) 要站最前，但池里 range 比它大的只有 " +
                                    "${front.size} 个 → 有 $short 个位置可能站到坦克前面",
                            )
                        }
                    }
                }

                val hard = truthy(pf["range_spread_hard"]) && truthy(pf["range_spread"])
                val defer = mutableListOf<String>()
                for (u in order) {
                    if (picked.size >= n) break
                    if (hard && !spreadOk(picked + u, pf)) {
                        defer.add(u)
                        continue
                    }
                    picked.add(u)
                }
                for (u in defer) {
                    if (picked.size >= n) break
                    picked.add(u)
                    warns.add(
                        "⚠ 站位跨度硬限制（≤${pyStr(pf["range_spread"])}）：凑不满 $n 人 → " +
                            "放进 ${name(u)}(range=${pyStr(rangeOrNull(u))}) → 跨度 ${rangeSpread(picked)}",
                    )
                }
                for (u in weak) {
                    if (picked.size >= n) break
                    picked.add(u)
                }

                // ★加速位的最终判定：队里只要有人带 加速 就算满足（坦克兼任也算）
                val jsMembers = picked.filter { TagIds.T["jiasu"] in rules.tagsOf(it) }
                if (jsMembers.isEmpty()) {
                    warns.add("⚠ 池里没有『加速』角色 → 加速位空出（关卡规则 1 坦克 + 1 加速）")
                } else if (tk != null && js == null && tk in jsMembers) {
                    notes.add("加速由坦克 ${name(tk)} 兼任（池里没有别的加速）")
                    roles[tk] = roles[tk] + " + 加速（兼任）"
                }
                if (picked.none { TagIds.T["aoe"] in rules.tagsOf(it) }) {
                    warns.add("⚠ 队里没有「群体输出」——多目标 boss 需要群攻，池里可能真没有")
                }

                var total = picked.sumOf { sc(it) } + rules.attrScore(picked) +
                    tankExtraPenalty(picked, true)
                if (atk == edge) total += edgeW
                val bt = bestTotal
                if (bt == null || total > bt) {
                    bestTotal = total; bestAtk = atk; bestPicked = picked
                    bestRoles = roles; bestNotes = notes; bestWarns = warns
                }
            }
        }

        res.picked.clear()
        res.picked.addAll(bestPicked)
        res.atk = bestAtk
        res.warn.addAll(bestWarns)
        val keep = bestRoles.keys.toSet()                       // 坦克位/加速位受保护
        for (u in res.picked) {
            val ts = rules.tagsOf(u)
            res.reasons[u] = when {
                u in bestRoles -> bestRoles[u]!!
                TagIds.T["dps"] in ts && TagIds.T["aoe"] in ts -> "核心群体输出"
                TagIds.T["aoe"] in ts -> "群体输出"
                TagIds.T["dps"] in ts -> "输出核心"
                else -> "辅助/buff/群充"
            }
        }
        // ★`W["edge"]` 在 Python 里是 **int** 4（`%s` → "4"）；Kotlin 里是 Double 4.0 → 必须按 int 渲染
        val sideNote = if (pf["atk"] != null) "关卡指定" else "各算一遍取高，$edge 有 +${pyNum(edgeW)} 偏好"
        res.log.add("整队系别 = **$bestAtk**（$sideNote）")
        res.log.addAll(bestNotes)

        val atkUse = res.atk ?: bestAtk
        fun sc2(u: String): Double = aoeScore(u, atkUse, w, bing, boss)
        var tkRng2: Int? = null
        for (u in res.picked) {
            if (u in keep && (TagIds.T["tank"] in rules.tagsOf(u) || TagIds.T["mtank"] in rules.tagsOf(u))) {
                tkRng2 = rangeOrNull(u)
                break
            }
        }
        val tkR2 = tkRng2
        attrPack(
            res,
            cands,
            ok = { u ->
                (if (tkR2 == null) true else rng(u) > tkR2) &&
                    (rules.effAtk(u) == atkUse || rules.effAtk(u).isEmpty() || TagIds.T["both"] in rules.tagsOf(u))
            },
            protect = keep,
            scfn = { u -> sc2(u) },
            teampen = { t -> tankExtraPenalty(t, true) + spreadPenalty(t, pf).first },
            scen = if (boss) "boss" else "all",
        )

        // ★整队总分：换人之后重算一次（同属性分会变），并计入站位跨度扣分
        val (sp, spread, lim) = spreadPenalty(res.picked, pf)
        res.score = round1(
            res.picked.sumOf { sc2(it) } + rules.attrScore(res.picked) +
                tankExtraPenalty(res.picked, true) + (if (atkUse == edge) edgeW else 0.0) + sp,
        )
        if (lim != null) {
            res.spread = spread
            res.log.add(spreadNote(res.picked, spread, lim, sp, truthy(pf["range_spread_hard"])))
        }
    }

    /** PC 版 `plan`(1575)：正式契约 —— 站位顺序的 unitId 列表（走 `build_best`） */
    fun plan(
        stage: String,
        pool: List<String>,
        relicsSkill: Int = 0,
        floor: Int = 1,
        size: Int? = null,
        failed: Boolean = false,
    ): List<Int> = buildBest(stage, pool, relicsSkill, floor, size, failed)
        .team?.mapNotNull { it.toIntOrNull() } ?: emptyList()

    // ────────────────────────── 工具 ──────────────────────────

    private fun attrOf(uid: String): String =
        tables.unit(uid)?.optString("attr")?.takeIf { it.isNotEmpty() } ?: "?"

    /** 角色名（PC 里到处 `U[u]["name"]`；缺了给空串，与 Python 的 `or ""` 一致） */
    private fun name(uid: String): String = tables.unit(uid)?.optString("name").orEmpty()

    /** 角色的 range；缺失 = null（PC 的 `U[u].get("range")`，注意和 `_rng` 的 9999 是两回事） */
    private fun rangeOrNull(uid: String): Int? = tables.unit(uid)?.opt("range")
        ?.takeIf { it !== org.json.JSONObject.NULL }
        ?.let { (it as Number).toInt() }

    /** Python 的 `%s` 渲染：None → `"None"`（日志里会原样出现，别写成 Kotlin 的 `"null"`） */
    private fun pyStr(v: Any?): String = if (v == null) "None" else v.toString()

    /**
     * 把"Python 里是 int 的数"按 int 渲染（日志用）。
     * 例：`C.W["burst_team"]["edge"]` 在 Python 是 `4`（`%s`→"4"），Kotlin 侧存成 Double 4.0 → 得抹掉 `.0`。
     */
    private fun pyNum(x: Double): String =
        if (x.isFinite() && x == Math.floor(x)) x.toLong().toString() else x.toString()

    /** Python 的 `truthy`：这里的语义是"键存在且不是 None/False/空串" */
    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        else -> true
    }

    /**
     * Python 的 `round(x, 1)` —— **half-even**（银行家舍入），不是四舍五入。
     * 用 BigDecimal 取 double 的精确十进制值再 HALF_EVEN，语义与 CPython 一致；
     * 分数会进日志（`spread_note` 等），差一个 0.1 就会对拍失败。
     */
    private fun round1(x: Double): Double =
        java.math.BigDecimal(x).setScale(1, java.math.RoundingMode.HALF_EVEN).toDouble()

    /** Python 的 `%.1f` */
    private fun fmt1(x: Double): String = String.format(java.util.Locale.ROOT, "%.1f", x)

    /** Python 的 `%+.1f` */
    private fun fmtSigned1(x: Double): String = String.format(java.util.Locale.ROOT, "%+.1f", x)

    /** 元组比较：逐项比，返回 >0 表示 a 更大（Python 元组语义） */
    private fun compareKeys(a: List<Double>, b: List<Double>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }
}

/** 排序键：按 Python 元组语义逐项降序比较 */
class ComparableKey(val parts: List<Double>) : Comparable<ComparableKey> {
    override fun compareTo(other: ComparableKey): Int {
        for (i in 0 until minOf(parts.size, other.parts.size)) {
            val c = parts[i].compareTo(other.parts[i])
            if (c != 0) return c
        }
        return parts.size.compareTo(other.parts.size)
    }
}

/** `sorted(key=..., reverse=True)`：元组逐项降序、平局保留先出现的（与 Python 一致） */
internal fun keyComparator(rank: (String) -> ComparableKey): Comparator<String> =
    Comparator { a, b -> rank(b).compareTo(rank(a)) }

/** `max(key=lambda u: sc(u))`：数值键的最大值（平局取先出现） */
internal fun compareByDouble(sc: (String) -> Double): Comparator<String> =
    Comparator { a, b -> sc(a).compareTo(sc(b)) }

/**
 * `sorted(key=…, reverse=True)` 的**稳定降序**：平局保持原顺序。
 * ★别用 `sortedWith(升序).reversed()` —— 那会把平局也倒过来，和 Python 不一致（逐字对拍会挂）。
 */
internal fun <T> descByInt(f: (T) -> Int): Comparator<T> = Comparator { a, b -> f(b).compareTo(f(a)) }

internal fun descByDoubleVal(f: (String) -> Double): Comparator<String> =
    Comparator { a, b -> f(b).compareTo(f(a)) }

/** PC 版 `_burst_team` 的返回：picked / warns / fixed（结构位） */
internal class BurstTeamResult(
    val picked: MutableList<String>,
    val warns: MutableList<String>,
    val fixed: MutableList<String>,
)

/** 组队结果（对应 PC 版那个 `res` dict；字段名与 Python 逐字一致，便于对拍） */
class Res(
    val stage: String?,
    val algo: String,
    val cands: MutableList<String>,
    val weak: MutableList<String>,
    val drop: MutableList<Pair<String, String>>,
    val attrlimUnits: MutableList<String>,
    val todo: String?,
    val forceAttr: String?,
    val floorRule: Int?,
    val failed: Boolean,
) {
    val log = mutableListOf<String>()
    val warn = mutableListOf<String>()
    val reasons = LinkedHashMap<String, String>()
    val swaps = mutableListOf<String>()
    val picked = mutableListOf<String>()

    /** 领域硬约束：当前队允许的最多领域角色数（Int.MAX_VALUE = 不限，速通队用）；选人/交换时据此拦截 */
    var maxField: Int = Int.MAX_VALUE

    // `_finish` 填的统计（★与 Python 一致：**只有走到 `_finish` 才有值**；
    //  "池子里没有可用角色"那条早退路径下它们是"没有这个键"= null）
    var team: List<String>? = null
    var attrs: Map<String, Int>? = null
    var types: Map<String, Int>? = null
    var attrBonusValue: Int? = null

    var score: Double? = null
    var fail: String? = null

    /** burst/aoe 选定的攻击系别（PC 的 `res["atk"]`） */
    var atk: String? = null

    /** 实际站位跨度（PC 只在登记了 `range_spread` 的档里填） */
    var spread: Int? = null

    /** PC 版 `_take` 的核心语义（已拿过就不重复记） */
    fun take(uid: String, why: String) {
        if (uid !in picked) {
            picked.add(uid)
            reasons[uid] = why
        }
    }
}
