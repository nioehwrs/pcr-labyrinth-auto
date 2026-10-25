package com.lmj.labyrinthauto.logic.party

import android.content.Context
import android.util.Log
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.data.GameData
import com.lmj.labyrinthauto.data.RelicTables
import com.lmj.labyrinthauto.frida.AgentApi
import com.lmj.labyrinthauto.logic.team.C
import com.lmj.labyrinthauto.logic.team.Profiles
import com.lmj.labyrinthauto.logic.team.Res
import com.lmj.labyrinthauto.logic.team.TeamBuild
import org.json.JSONArray
import org.json.JSONObject

/**
 * 编队决策层（PC `party_plan`(2698) + `_stage_for`(2413) + `party_ctx_cached`(2443)）。
 * 只算"目标队 vs 当前队 + 一次能点的操作"，真正点击由 [com.lmj.labyrinthauto.logic.MazeLoop.handlePartyEdit] 执行。
 *
 * ★多分队策略见 [partyFillOk]（**分层**，与 PC 同步）：5 层 Boss 默认编满 [LAB_BOSS_FILL_PARTIES] 队；
 *   3 层 / 其它层按初始策略只编第 1 队，只有本节点失败过才补后备队。
 */
object PartyPlan {

    const val LAB_PARTY_SIZE = 5
    const val LAB_PARTY_TEAM_AUTO = true
    const val LAB_PARTY_AUTO = true
    const val LAB_PARTY_MIN_SLOTS = 1
    /** PC `LAB_SELECT_TAP_MAX`(447)：同一组候选卡最多点几次（点不动就停手，免得把游戏点卡）。
     *  ★**每次都是"真点下去"才计数**（PC 2026-10-12 的教训：卡片入场动画期坐标在屏外时点了不算一次），
     *   所以这一页允许 2 次机会，而不是"点一下没反应就永远不再点"（用户 2026-10-16 截图那个卡死）。 */
    const val LAB_SELECT_TAP_MAX = 2

    /** 同一组卡"再点一次"的最小间隔（给游戏反应/动画的时间）。PC 那边靠 2s 的"重读间隔"天然隔开。 */
    const val LAB_SELECT_RETAP_MS = 2_500L

    /** PC `LAB_PARTY_BATCH_MAX`(3432)：单节点最多连做几**批**（PC 超了就 `_party_stuck` 冷却）。
     *  ⚠**不是"本轮最多点几个"** —— 2026-10-16 这里曾被误用成每轮操作数上限（把"一次性点完"拆成两轮）。
     *  本侧现在不设批数上限：每轮都从**实际队伍**重新规划（不会多点/漏点），真卡住由运行级看门狗兜。 */
    const val LAB_PARTY_BATCH_MAX = 3
    const val LAB_PARTY_TAP_MS = 350L
    // ★没有"滚动次数上限"（用户 2026-10-16）：PC 有个 `LAB_PARTY_SCROLL_MAX=80` 的安全网，
    //   但次数到顶不代表"翻不出来"——真正的到头判据是"滚完屏上图标集合没变化"（见 `MazeLoop.scrollPartyList`
    //   的调用处）。次数上限只会把还能滚的情况提前掐掉，所以这里不设。
    /** PC `LAB_PARTY_SCROLL_SETTLE`：滚完角色一览后等动画停再点（网格数据是即时的、屏幕不是，
     *  立刻点会落到**旧位置**那张卡上；实机 0.9s 不够，PC 提到 1.5s）。 */
    const val LAB_PARTY_SCROLL_SETTLE = 1_500L
    /** 滚动角色一览的滑动时长（PC `swipe` 默认 350ms；太短会被识别成"甩动"，惯性滑过头） */
    const val LAB_PARTY_SCROLL_MS = 350L
    /** 点顶部队伍页签（`PartsPartySelectButtonN`）后等页面切队（切队是整页刷新，比点卡慢） */
    const val LAB_PARTY_SWITCH_MS = 1_200L

    /** ★编组页"刚出现后的静置"（PC `MAZE_PAGE_SETTLE`(364) 的等价物，2026-10-16 补）：
     *  页面刚出来时 UI 还没铺好、**旧页面的对话框实例仍在树上** ⇒ 这时读到的快照是**上一个节点的**
     *  （连同 `plan.done` 也是假的"已完成"）→ 会直接点「战斗开始」开打、根本没编队。
     *  实测：`挑战` → 1.0s → `战斗开始`，用的还是上一节点的 3 人队（用户 2026-10-16）。 */
    const val LAB_PARTY_SETTLE_MS = 1_500L

    const val LAB_PARTY_TAP_GAP = 3000L
    const val LAB_PARTY_BATCH_SETTLE = 1000L
    const val LAB_PARTY_RECHECK_S = 1500L
    const val LAB_PARTY_STABLE_MS = 200L
    const val LAB_PARTY_STABLE_TRIES = 3
    /** Boss 多分队：编组页可编 3 队（第 1 队倒了第 2 队接着打）。本常量 = **失败后**最多编满几队 */
    const val LAB_BOSS_FILL_PARTIES = 3

    /** ★哪些层的 Boss **默认**就编满 [LAB_BOSS_FILL_PARTIES] 队（用户 2026-10-16 定，与 PC 同步）：
     *  · **5 层 Boss：默认编满 3 队**（终盘 Boss，多一队后备更稳）；
     *  · **3 层 Boss（其它层）：按初始策略** —— 平时只编第 1 队直接开打，只有本节点失败过才补 2/3 队。 */
    val LAB_BOSS_FILL_ALL_AREAS = setOf(5)

    private var bossMap: Map<String, Int> = emptyMap()   // questId → 敌人 unitId
    private var bossNameMap: Map<Int, String> = emptyMap() // unitId → 名字（labyrinth_boss.json 的 name）
    private var exMap: Map<String, Int> = emptyMap()      // questId → 敌人 unitId（与区无关）
    private var relicTables: RelicTables? = null
    private var teamBuild: TeamBuild? = null              // TeamBuild 是 class，需要 Tables 实例

    /** 读两张关卡判定表（labyrinth_boss.json / stages.json）+ 一次 EX 镜像扩展（PC `_ex_resolve` 同款） */
    fun loadTables(ctx: Context) {
        val read: (String) -> String = { name ->
            ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        bossMap = loadBossMap(read)
        exMap = loadExMap(read)
        bossNameMap = loadBossNames(ctx)
        // 把登记的 EX_<questId> 档镜像成与区无关的 EX_<敌人unitId>
        var mirrors = 0
        for ((k, v) in Profiles.PROFILES) {
            if (k.startsWith("EX_") && k.length > 3 && k.substring(3).all { it.isDigit() }) {
                val eid = exMap[k.substring(3)]
                if (eid != null) {
                    Profiles.addMirror("EX_$eid", v)
                    mirrors++
                }
            }
        }
        relicTables = RelicTables.load(read)
        teamBuild = TeamBuild(GameData.tables(ctx))
        // ★关卡判定表自检（2026-10-16 加）：这三个数字就是"EX/Boss 档能不能命中"的**全部输入**。
        //   当天 `loadExMap` 把 stages.json 的 `enemies`（对象数组 `[{name,unitId}]`）当裸整数解析 ⇒
        //   exMap 空表 ⇒ 所有 EX 格退成 normal（速通队），而日志里当时**完全看不出来**（用户只能目视
        //   发现"这队像速通队"）⇒ 补这行 + 空表告警，让这条路自己会喊。
        val exProfiles = Profiles.PROFILES.keys.count { it.startsWith("EX_") }
        AppState.addLog(
            "[组队] 关卡判定表：boss=${bossMap.size} 条 questId ｜ ex=${exMap.size} 条 ｜ " +
                "EX 档=$exProfiles（+镜像 $mirrors）",
        )
        if (exMap.isEmpty()) {
            AppState.addLog("[!] stages.json 的 questId→敌人映射为空 ⇒ **所有 EX 格都会退成速通队**（查 loadExMap 的解析）")
        }
    }

    fun loadBossMap(read: (String) -> String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        try {
            val d = JSONObject(read("labyrinth_boss.json"))
            val it = d.keys()
            while (it.hasNext()) {
                val k = it.next()
                // labyrinth_boss.json 的值是对象 {hp, name, unitId}，取其中的 unitId
                val v = d.optJSONObject(k)
                val uid = v?.optInt("unitId")?.takeIf { it != 0 }
                if (uid != null) out[k] = uid
            }
        } catch (e: Exception) {
            Log.e("PartyPlan", "labyrinth_boss.json 读取失败", e)
        }
        return out
    }

    /** unitId → 名字（同表 name 字段；用于「3 区 Boss 是谁」这类展示） */
    private fun loadBossNames(ctx: Context): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        try {
            val d = JSONObject(ctx.assets.open("labyrinth_boss.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            val it = d.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = d.optJSONObject(k)
                val uid = v?.optInt("unitId")?.takeIf { it != 0 } ?: continue
                val name = v.optString("name").takeIf { it.isNotEmpty() } ?: continue
                out[uid] = name
            }
        } catch (e: Exception) {
            Log.e("PartyPlan", "labyrinth_boss.json 读名字失败", e)
        }
        return out
    }

    fun loadExMap(read: (String) -> String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        try {
            val d = JSONObject(read("stages.json"))
            val it = d.keys()
            while (it.hasNext()) {
                val key = it.next()
                // ★只收 `EX_` 档（PC `_ex_qid_to_enemy()` 同款）。stages.json 里还有一堆 `Boss_*` 档，
                //   那些走 `labyrinth_boss.json` 那条路；不筛前缀会让普通格也命中 EX 档。
                if (!key.startsWith("EX_")) continue
                val v = d.optJSONObject(key) ?: continue
                // ★★enemies 是**对象数组** `[{name, unitId}, …]`，不是裸整数数组！2026-10-16 修：
                //   原写法 `enemies.opt(0).toString().toIntOrNull()` ⇒ opt(0) 是 "{"name":…,"unitId":…}"
                //   的字符串 ⇒ 恒 null ⇒ `continue` ⇒ **exMap 整个是空表**。后果：
                //     ① `EX_<敌人unitId>` 镜像档（Profiles.addMirror）一个都没注册；
                //     ② 区 2~5 的 EX 格 questId 与登记的不一致（表里只登记区 1 那几个）⇒ `exResolve` 全 null
                //        ⇒ `stageFor` 一律返回 "normal"（= **速通队**）—— 正是用户 2026-10-16 报的
                //        "EX/小 boss 战斗却按速通队编队"。
                val enemies = v.optJSONArray("enemies") ?: JSONArray()
                var eid: Int? = null
                for (i in 0 until enemies.length()) {
                    val e = enemies.optJSONObject(i) ?: continue
                    val u = e.optInt("unitId")
                    if (u != 0) { eid = u; break }   // PC 取 `_eids[0]`
                }
                if (eid == null) continue
                val quests = v.optJSONArray("quests") ?: JSONArray()
                for (i in 0 until quests.length()) {
                    val q = quests.opt(i)?.toString()?.takeIf { s -> s.isNotEmpty() && s != "null" } ?: continue
                    out[q] = eid
                }
            }
        } catch (e: Exception) {
            Log.e("PartyPlan", "stages.json 读取失败", e)
        }
        return out
    }

    /** PC `relic_skill_total`(4884)：加速遗物"开局获得技能值"合计（按 relicId 去重） */
    fun relicSkillTotal(own: JSONObject?): Pair<Int, List<String>> {
        var total = 0
        val detail = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val relics = own?.optJSONArray("relics") ?: return 0 to detail
        val rt = relicTables ?: return 0 to detail
        for (i in 0 until relics.length()) {
            val x = relics.optJSONObject(i) ?: continue
            val rid = x.opt("relicId")?.toString()?.takeIf { it != "null" && it.isNotEmpty() } ?: continue
            if (!seen.add(rid)) continue
            val r = rt.rk(rid)
            val v = (r?.opt("start_skill") as? Number)?.toInt() ?: 0
            if (v == 0) continue
            total += v
            detail.add("${(r?.optString("name") ?: rid).ifEmpty { rid }}(+$v)")
        }
        return total to detail
    }

    data class PartyCtx(
        val sk: Int,
        val det: List<String>,
        val own: List<Int>,
        val relics: List<JSONObject>,
        val ownErr: String?,
        /** ★持有池明细（USER/NPC 各几个 + 集合内部计数）——2026-10-16 查"池读少"用 */
        val poolNote: String = "",
    )

    /** PC `party_ctx_cached`(2443)：本页只问一次 RPC（加速遗物技能值 + 本局持有角色池） */
    suspend fun partyCtx(): PartyCtx {
        val own = AgentApi.labOwnRelics()
        val (sk, det) = relicSkillTotal(own)
        val pu = AgentApi.labUnits()
        val unitIds = intListOf(pu?.optJSONArray("unitIds"))
        val relics = (0 until (own?.optJSONArray("relics")?.length() ?: 0)).mapNotNull { own!!.optJSONArray("relics").optJSONObject(it) }
        // ★持有池明细（2026-10-16 查"池读少"）：USER/NPC 各几个 + `OwnUnitInfoSet` 内部计数。
        //   页面"可编入 28"而这里只给 15 时，看 cap/空槽/命中就能分清：集合本身只有 15 个（页面侧多出
        //   租借/NPC），还是我们**漏读**（cap 明明够大却没命中——槽位步长/偏移不对）。
        var nUser = 0; var nNpc = 0; var nOther = 0
        val us = pu?.optJSONArray("units") ?: JSONArray()
        for (i in 0 until us.length()) {
            when ((us.optJSONObject(i)?.opt("unitType") as? Number)?.toInt()) {
                1 -> nUser++
                2 -> nNpc++
                else -> nOther++
            }
        }
        val errTxt = pu?.optString("err")?.takeIf { it.isNotEmpty() && it != "null" }
        // ★集合计数（2026-10-16 查"页面可编入 > 持有"）：`count` = HashSet 里的**元素数**、
        //   `cap` = `_entries` 数组长度。**count ≈ cap 且都小 ⇒ 集合真的只有这几个**（不是漏读，
        //   是这字段本来就只装那些）；若 cap 很大却只命中几个 ⇒ 才是读法/偏移的问题。
        val poolNote = "USER $nUser / NPC $nNpc" +
            (if (nOther > 0) " / 其它 $nOther" else "") +
            " ｜ 集合 count=${pu?.opt("setCount")} cap=${pu?.opt("setCap")} 扫描=${pu?.opt("setScan")}" +
            " 空槽=${pu?.opt("setEmpty")} 命中=${pu?.opt("setHit")}" +
            (errTxt?.let { " ｜ err=$it" } ?: "")
        return PartyCtx(sk, det, unitIds, relics, pu?.optString("err"), poolNote)
    }

    /** PC `_stage_for`(2413)：当前格子 → team_build 的关卡 key */
    fun stageFor(st: JSONObject): Pair<String, String?> {
        val q = st.opt("questId")?.toString()?.takeIf { it != "null" && it.isNotEmpty() }
        val isBoss = st.optInt("panelType") == 8 || st.optInt("statusType") == 6 || st.optInt("blockType") == 8
        if (q != null && isBoss) {
            val bid = bossMap[q]
            if (bid != null) {
                val key = "Boss_$bid"
                return key to if (Profiles.hasProfile(key)) null else "Boss 格：$key 没专属档 → 按 burst 兜底"
            }
            return "normal" to "Boss 格：boss 表查不到($q) → 先按 normal 算"
        }
        val k = exResolve(q)
        if (k != null) return k to null
        return "normal" to null
    }

    /** 按 questId 查本局 Boss 的 unitId（PC `labyrinth_boss.json` 映射；遗物特殊条件用） */
    fun bossOf(questId: String?): Int? = if (questId.isNullOrBlank()) null else bossMap[questId]

    /** 按 unitId 查 Boss 名字（展示用，如「3 区 Boss 是谁」） */
    fun bossName(unitId: Int?): String? = if (unitId == null) null else bossNameMap[unitId]

    private fun exResolve(q: String?): String? {
        if (q.isNullOrEmpty()) return null
        val ks = "EX_$q"
        if (Profiles.hasProfile(ks)) return ks
        val eid = exMap[q]
        if (eid != null) {
            val ak = "EX_$eid"
            if (Profiles.hasProfile(ak)) return ak
        }
        return null
    }

    /** `partyDic` 里第 idx 队（0 基）的人数（PC `_party_dic_n`(2569)）。partyDic 是**游戏侧**编成，含上次留下的队。 */
    fun partyDicN(dic: JSONObject?, idx: Int): Int = dic?.optJSONArray("$idx")?.length() ?: 0

    /** `partyDic` 里"编满 size 人"的队数（PC 调用点里的 `_full_n`） */
    fun partyFullN(dic: JSONObject?, size: Int): Int {
        if (dic == null) return 0
        var n = 0
        val it = dic.keys()
        while (it.hasNext()) {
            if ((dic.optJSONArray(it.next())?.length() ?: 0) >= size) n++
        }
        return n
    }

    /**
     * PC `party_fill_ok`(2579)：编组页「**眼前这一队还要不要编**」（Boss 多分队策略）→ (ok, why)。
     *
     * ★分层策略（用户 2026-10-16 定，与 PC 同步）：
     *   · **5 层 Boss**（[LAB_BOSS_FILL_ALL_AREAS]，`fillAll`）：**默认就编满** maxParties 队；
     *   · **3 层 Boss / 其它层**：按初始策略，平时只编 **1 队**、直接开打；
     *     只有本节点**失败过**才继续编，直到游戏里编满 maxParties 队（第 1 队倒了有后备队接着打）。
     *
     * ★`planDone` 是"当前队伍 == 算法目标"，判据以它为准 —— 不能看"队满没满"：普通节点会
     *   **沿用上一关留下的满队**（PC 2026-10-12 踩坑：`cur_n==5` 被判成"不用编"→ 用旧队开打）。
     */
    fun partyFillOk(
        isBoss: Boolean,
        curN: Int,
        dic: JSONObject?,
        nsp: Any?,
        failed: Boolean,
        already: Boolean,
        size: Int,
        maxParties: Int,
        planDone: Boolean,
        fillAll: Boolean,
    ): Pair<Boolean, String> {
        if (planDone) return false to "本队已按目标编好（$curN 人）"
        if (!isBoss) return true to "普通页：按目标编（当前 $curN 人）"
        val firstN = partyDicN(dic, 0)
        val fullN = partyFullN(dic, size)
        val n = (nsp as? Number)?.toInt()
        if (failed || fillAll) {
            val who = if (failed) "失败过" else "本层 Boss 默认编满"
            // ★第 1 队**永远重算**（2026-10-16）：`fullN` 是**游戏里**已编满的队数，而 Boss 页的队是
            //   **上一关继承来的** ⇒ 不能拿它当"第 1 队不用编"（非 Boss 关也继承上一关队伍、照样重编）。
            //   这条只用来决定"要不要继续编第 2/3 队"。
            if (n != null && n > 0 && fullN >= maxParties) return false to "$who 但已编满 $fullN 队"
            return true to "$who → 编后备队（$fullN/$maxParties 队）"
        }
        if (n != null && n > 0) {
            return false to "只编 1 队：当前是队伍${n + 1}（游戏里 队伍1 已 $firstN 人）"
        }
        if (already) return false to "只编 1 队：本进程已确认过一队"
        return true to "编队伍1（当前 $firstN 人）"
    }

    data class Op(val action: String, val unit: Int, val via: String, val x: Int, val y: Int)
    data class Off(val unit: Int, val dir: String)
    data class Plan(
        val ok: Boolean,
        val done: Boolean,
        val target: List<Int>,
        val cur: List<Int>,
        val miss: List<Int>,
        val extra: List<Int>,
        val ops: List<Op>,
        val skipped: List<Int>,
        val offscreen: List<Off>,
        val lines: List<String>,
        val missDetail: List<String>,
        /** ★组队记录（2026-10-24）：结构化一条（PC `log_run` 同字段）。
         *  由 [com.lmj.labyrinthauto.logic.MazeLoop] 在"结论变化"时交给
         *  [com.lmj.labyrinthauto.logic.team.TeamRunLog.append] 写盘（空队/池空为 null，与 PC 一致）。 */
        val runRec: JSONObject? = null,
    )

    /**
     * PC `party_plan`(2698)：目标队伍 vs 当前队伍 + 一次算好的全部操作。
     * @param st 状态（取 questId / panelType / area）
     * @param pt 编队页快照（AgentApi.labParty 的 JSON）
     * @param ctx 本局持有角色池 + 加速遗物技能值
     * @param failed **本节点失败过**（用户 2026-10-16）—— 传给 `team_build`，让它按关卡档里的
     *   `tank_on_fail` 补 1 个坦克/兼职坦克（站最前由游戏按攻击范围自排）。
     *   ⚠Android 侧**一直没把 failed 传下去** ⇒ 这条规则在手机上从未生效（PC 一直在传）。
     */
    fun partyPlan(st: JSONObject, pt: JSONObject, ctx: PartyCtx, failed: Boolean = false): Plan {
        val curNm = intListOf(pt.optJSONArray("nowMember")).filter { it != 0 }
        val cur = if (curNm.isNotEmpty()) curNm else (pt.optJSONArray("icons") ?: JSONArray())
            .toListOfObj().mapNotNull { (it.opt("unitId") as? Number)?.toInt() }.filter { it != 0 }
        val ui = intListOf(pt.optJSONArray("scrollIds")).filter { it != 0 }
        val tapOf = LinkedHashMap<Int, JSONObject>()
        for (c in (pt.optJSONArray("scrollIcons") ?: JSONArray()).toListOfObj()) {
            val u = (c.opt("unitId") as? Number)?.toInt() ?: continue
            if (u == 0 || c.opt("x") == null) continue
            if (c.opt("vis") === JSONObject.NULL || c.opt("vis") == false) continue
            tapOf.putIfAbsent(u, c)
        }
        val slotOf = LinkedHashMap<Int, JSONObject>()
        for (c in (pt.optJSONArray("icons") ?: JSONArray()).toListOfObj()) {
            val u = (c.opt("unitId") as? Number)?.toInt() ?: continue
            if (u == 0 || c.opt("x") == null) continue
            slotOf.putIfAbsent(u, c)
        }
        // 角色池（有序去重）：可用 + 一览全量；本局没持有则退用页内可编入
        val allOrdered = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()
        for (l in intListOf(pt.optJSONArray("usable")) + intListOf(pt.optJSONArray("scrollIds"))) {
            if (l != 0 && seen.add(l)) allOrdered.add(l)
        }
        val offered = allOrdered.toSet()
        // ★池 = **页面可编入**（`usable + scrollIds`），不再并 `labUnits` 持有（用户 2026-10-16 建议）──
        //   · 它才是"**这一格**现在能编进去的人"：含租借/NPC，也天然排除"本关限定/限属性"不许上的人
        //     （并集反而可能把"这关不让上"的人算进目标 ⇒ 目标里有人编不进去 ⇒ 空转）；
        //   · **不多花任何时间**：这两个数组就在我们每轮都要读的 `labParty` 快照里，取池只是内存里合一下，
        //     没有额外 RPC（原来那次 `labUnits` 是 `ctx` 用的，照旧、不增加）；
        //   · 页面读不完整（快照残缺）时才退回 `labUnits` 持有池。
        //   ⚠背景：实测 `labUnits`（`OwnUnitInfoSet`）只有 3 个（`cap=3`、空槽 0 ⇒ 集合本身就这么大），
        //   而本页可编入 7 个（多出的含**本局刚选到的奖励角色**）⇒ 旧代码按"持有 3"算目标 ⇒ 目标 3 人、
        //   当前队正好等于目标 ⇒ **一步不编**（就是用户看到的"又没组队"）。
        var pool = allOrdered.ifEmpty { ctx.own }
        // 多分队：去掉别队用过的（不抢别人已占的角色）
        val usedOther = mutableSetOf<Int>()
        val partyDic = pt.optJSONObject("partyDic") ?: JSONObject()
        val nsp = pt.opt("nowSelectParty")
        for (key in partyDic.keys()) {
            if (key == nsp?.toString()) continue
            val arr = partyDic.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val u = (arr.opt(i) as? Number)?.toInt() ?: continue
                if (u != 0 && u !in cur) usedOther.add(u)
            }
        }
        if (usedOther.isNotEmpty()) pool = pool.filter { it !in usedOther }
        if (pool.isEmpty()) {
            return Plan(false, false, emptyList(), cur, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                listOf("读不到角色池 → 不点任何东西"), emptyList())
        }
        val (stage, note) = stageFor(st)
        val floor = (st.opt("area") as? Number)?.toInt() ?: 1
        var tb = teamBuild!!.buildBest(
            stage, pool.map { it.toString() }, relicsSkill = ctx.sk, floor = floor,
            size = LAB_PARTY_SIZE, failed = failed,          // ★失败过 ⇒ 让规则表决定要不要补坦克
        )
        if (tb.team.isNullOrEmpty()) {
            // ★空队兜底（2026-10-19 用户）：放宽属性限定重编一次——过滤链（前3图屏蔽/attrlim 剔除/
            //   tier 门槛）可能把薄池筛空（实测 4-2 HARD：池 32 个算不出目标 → 编组页死等 33 分钟）
            tb = teamBuild!!.build(
                stage, pool.map { it.toString() }, relicsSkill = ctx.sk, floor = floor,
                size = LAB_PARTY_SIZE, failed = failed, allowAttrlim = true,
            )
        }
        val target = tb.team?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        if (target.isEmpty()) {
            // ★仍算不出：把决策现场**打包进日志**（2026-10-19 用户）——之前只有"池 N 个"一个数，
            //   定位不了是哪道过滤筛空的（stage/floor/池成员+tier/构建内部日志）
            val rules = teamBuild?.rules
            val diag = mutableListOf(
                "规则算不出目标（池 ${pool.size} 个）→ 不点（放宽属性限定后仍空）",
                "　stage=$stage floor=$floor",
                "　池=" + pool.joinToString(",") { u -> "$u(t${rules?.tierOf(u.toString()) ?: "?"})" },
            )
            tb.log.take(8).forEach { diag.add("　$it") }
            return Plan(false, false, emptyList(), cur, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), diag, emptyList())
        }
        val miss = target.filter { it !in cur }
        val extra = cur.filter { it !in target }
        val missDetail = mutableListOf<String>()
        if (offered.isNotEmpty()) {
            for (u in miss) {
                missDetail.add(when {
                    u in tapOf || u in slotOf -> "$u 有可点坐标却没加成功"
                    u in offered -> "$u 在角色一览全量里、但当前不在屏上（需滚动）"
                    else -> "$u 页面未提供（本局未持有？或 Boss 页只放部分角色）"
                })
            }
        }
        // offscreen：miss 里"在页内全量、但不在可见区"的 → 要往哪个方向滚
        val offscreen = mutableListOf<Off>()
        if (allOrdered.isNotEmpty()) {
            val visIds = (pt.optJSONArray("scrollIcons") ?: JSONArray()).toListOfObj()
                .mapNotNull { c ->
                    val u = (c.opt("unitId") as? Number)?.toInt()
                    if (u != null && u != 0 && c.opt("vis") !== JSONObject.NULL && c.opt("vis") != false) u else null
                }
                .toSet()
            val vidx = allOrdered.mapIndexedNotNull { i, u -> if (u in visIds) i else null }
            // ★PC 2851~2860 同款：还缺的人可能在可见窗口的**上**侧（比如滚过头了）——那时要往上滚。
            val lo = vidx.minOrNull() ?: 0
            val hi = vidx.maxOrNull() ?: -1
            for (u in miss) {
                if (u in tapOf || u in slotOf || u !in allOrdered) continue
                val iu = allOrdered.indexOf(u)
                if (iu > hi) offscreen.add(Off(u, "down"))       // 在窗口下方 → 向下滚（露出列表后面）
                else if (iu < lo) offscreen.add(Off(u, "up"))    // 在窗口上方 → 向上滚
            }
        }
        val lines = mutableListOf<String>()
        // ★组队记录（2026-10-24 用户）：把这次**编队决策**打包成一条结构化记录（字段对齐 PC `log_run`）。
        //   真正写盘在 [com.lmj.labyrinthauto.logic.MazeLoop]（"结论变了"才写，避免每 200ms 刷一行）。
        val runRec = buildRunRecord(st, ctx, tb, stage, floor, failed, pool, cur, target, miss, extra)
        // ★把**算法**也打出来（2026-10-16 加）：normal/sutong 就是"速通队"，burst/dot_only/aoe 才是有针对性的档。
        //   用户看日志时"像不像速通队"这一眼就能确认，不用去猜关卡 key。
        lines.add("关卡=$stage（算法=${tb.algo}） 层=$floor ｜ 池=持有${ctx.own.size}/本页可编入${ui.size} ｜ 加速遗物技能值=${ctx.sk}")
        // ★"本节点失败过"的规则生效情况写清楚（用户 2026-10-16：失败一次后要补坦克/兼职坦克）
        if (failed) {
            val tf = com.lmj.labyrinthauto.logic.team.Profiles
                .applyFloor(com.lmj.labyrinthauto.logic.team.Profiles.profileOf(stage), floor)["tank_on_fail"]
            lines.add(
                if (tf != null) "★ 本节点失败过 → 按关卡档补：坦克/兼职坦克（tank_on_fail=$tf）"
                else "★ 本节点失败过（本关档没登记 tank_on_fail → 不额外补坦克）",
            )
        }
        lines.add("目标(${target.size})=${target} ｜ 当前(站位序)=${cur}")
        // ★把**池**和**算法的决策过程**也打出来（用户 2026-10-16）：这样"自动组出来是不是现在这队"
        //   当场就能对——**池 → 算法日志（逐项打分/取舍）→ 目标**三段都在日志里。
        //   （`target` 就是 `TeamBuild.buildBest` 的输出，不是继承来的队伍；两者相等=真的不用改。）
        lines.add("池(${pool.size}，取本页可编入)=$pool")
        if (ctx.poolNote.isNotEmpty()) lines.add("持有明细（仅参考）：${ctx.poolNote}")
        // ★页面可编入 > `labUnits` 持有是**正常**的（页面会多给租借/NPC，也可能少给"本关限定"）。
        //   池子现在直接用页面这份 ⇒ 这行只作对照，不再是"目标不可信"。
        if (allOrdered.size != ctx.own.size) {
            lines.add("对照：本页可编入 ${allOrdered.size} 个 ｜ labUnits 持有 ${ctx.own.size} 个（以页面为准）")
        }
        tb.log.take(8).forEach { lines.add("算法：$it") }
        if (note != null) lines.add("注：$note")
        if (miss.isEmpty() && extra.isEmpty()) {
            lines.add("✓ 已就绪（集合一致；顺序由游戏按攻击范围自排）→ 接下来：战斗开始(自动)")
            return Plan(true, true, target, cur, miss, extra, emptyList(), emptyList(), offscreen, lines, missDetail, runRec)
        }
        if (missDetail.isNotEmpty()) lines.add("还缺成员详情：" + missDetail.joinToString("；"))
        val ops = mutableListOf<Op>()
        val skipped = mutableListOf<Int>()
        // ★先移除"多出来"的，**按攻击范围从大到小**（= 站位最左 → 最右），再添加"缺的"。
        //   编组页队伍是**右对齐**（空位在最左）：移除右侧角色会让左侧的人右移 ⇒ 预先算好的槽位坐标失效。
        //   从最左（范围最大）开始移，右侧的人位置不动，后面的坐标仍然有效。
        //   （用户 2026-10-18：这条以前修过又退化了；这次按 range 显式排，不依赖槽数组下标方向。）
        val rangeOf = { u: Int -> teamBuild?.attackRange(u.toString()) ?: 9999 }
        val xOf = { u: Int ->
            val v = slotOf[u]?.opt("x")
            if (v is Number) v.toInt() else Int.MAX_VALUE
        }
        val rmOrder = extra.sortedWith(
            compareByDescending<Int> { rangeOf(it) }.thenBy { xOf(it) },
        )
        if (rmOrder.isNotEmpty()) {
            lines.add(
                "移除顺序(攻击范围降序)=" +
                    rmOrder.joinToString("→") { "$it(range=${rangeOf(it)})" },
            )
        }
        for (u in rmOrder) {
            var c = tapOf[u]
            var via = "一览"
            if (c == null) { c = slotOf[u]; via = "槽位" }
            if (c != null && c.opt("x") != null) {
                ops.add(Op("remove", u, via, c.optInt("x"), c.optInt("y")))
            } else skipped.add(u)
        }
        for (u in miss) {
            val c = tapOf[u]
            if (c != null && c.opt("x") != null) {
                ops.add(Op("add", u, "一览", c.optInt("x"), c.optInt("y")))
            } else skipped.add(u)
        }
        lines.add("还缺=${miss} ｜ 多出=${extra}")
        if (ops.isNotEmpty()) {
            lines.add("操作计划(${ops.size} 步，一次点完再核对)：" +
                ops.joinToString(" → ") { "${it.action} ${it.unit}@(${it.x},${it.y})·${it.via}" })
        } else {
            lines.add("没有可点的差异 → 本轮不点")
        }
        if (skipped.isNotEmpty()) lines.add("⚠ 这些差异在本页没有坐标（要滚动？）：$skipped")
        return Plan(ops.isNotEmpty(), false, target, cur, miss, extra, ops, skipped, offscreen, lines, missDetail, runRec)
    }

    /**
     * 组装一条**组队记录**（2026-10-24 用户："加上记录这部分的功能"）。
     * 字段逐项对齐 PC `team_build.log_run()`，这样 PC 的「组队记录 / 组队模拟」页能直接读：
     * ```
     *   stage / algo / relic_skill / floor / relics[{id,effected,name}] / pool[{id,name}]
     *   team[{id,name,tier,why,range}] / order[]（站位序）/ attrs / types / attr_bonus
     *   weights（当时权重快照）/ score / atk / spread / floor_rule / failed / chosen
     *   questId / blockId / area（定位"哪个格子"）
     *   party{now,target,miss,extra,done}（Android 独有：这次规划的现场）
     * ```
     * `type` / `run_id` / `ts` / `device` 由 [com.lmj.labyrinthauto.logic.team.TeamRunLog] 写入时补。
     */
    private fun buildRunRecord(
        st: JSONObject,
        ctx: PartyCtx,
        tb: Res,
        stage: String,
        floor: Int,
        failed: Boolean,
        pool: List<Int>,
        cur: List<Int>,
        target: List<Int>,
        miss: List<Int>,
        extra: List<Int>,
    ): JSONObject {
        val ids: (List<Int>) -> JSONArray = { l -> JSONArray().also { a -> l.forEach { a.put(it.toString()) } } }
        val rules = teamBuild?.rules

        val relics = JSONArray()
        for (x in ctx.relics) {
            val rid = x.opt("relicId")?.toString()?.takeIf { it != "null" && it.isNotEmpty() } ?: continue
            val n = ((x.opt("count") ?: x.opt("effected")) as? Number)?.toInt() ?: 1
            val nm = relicTables?.rk(rid)?.optString("name").orEmpty().ifEmpty { rid }
            relics.put(JSONObject().put("id", rid).put("effected", n).put("name", nm))
        }
        val poolArr = JSONArray()
        for (u in pool) {
            poolArr.put(JSONObject().put("id", u.toString()).put("name", teamBuild?.unitName(u.toString()) ?: u.toString()))
        }
        val teamArr = JSONArray()
        for (u in tb.picked) {
            teamArr.put(
                JSONObject()
                    .put("id", u)
                    .put("name", teamBuild?.unitName(u) ?: u)
                    .put("tier", rules?.tierOf(u) ?: JSONObject.NULL)
                    .put("why", tb.reasons[u] ?: "")
                    .put("range", teamBuild?.unitRange(u) ?: JSONObject.NULL),
            )
        }
        // ★权重快照（PC 记 `weights=C.W`）：以后改了权重，旧记录的分数还能按当时的表解释
        val weights = JSONObject()
        C.W.forEach { (k, m) ->
            weights.put(k, JSONObject().also { o -> m.forEach { (kk, vv) -> o.put(kk, vv) } })
        }
        return JSONObject()
            .put("stage", tb.stage ?: stage)
            .put("algo", tb.algo)
            .put("relic_skill", ctx.sk)
            .put("floor", floor)
            .put("relics", relics)
            .put("pool", poolArr)
            .put("team", teamArr)
            .put("order", ids(target))
            .put("attrs", JSONObject().also { o -> tb.attrs?.forEach { (k, v) -> o.put(k, v) } })
            .put("types", JSONObject().also { o -> tb.types?.forEach { (k, v) -> o.put(k, v) } })
            .put("attr_bonus", tb.attrBonusValue ?: JSONObject.NULL)
            .put("weights", weights)
            .put("score", tb.score ?: JSONObject.NULL)
            .put("atk", tb.atk ?: JSONObject.NULL)
            .put("spread", tb.spread ?: JSONObject.NULL)
            .put("floor_rule", tb.floorRule ?: JSONObject.NULL)
            .put("failed", failed)
            .put("chosen", JSONObject.NULL)
            .put("questId", st.opt("questId") ?: JSONObject.NULL)
            .put("blockId", st.opt("blockId") ?: JSONObject.NULL)
            .put("area", st.opt("area") ?: JSONObject.NULL)
            .put(
                "party",
                JSONObject()
                    .put("now", ids(cur))
                    .put("target", ids(target))
                    .put("miss", ids(miss))
                    .put("extra", ids(extra))
                    .put("done", miss.isEmpty() && extra.isEmpty()),
            )
    }

    private fun intListOf(a: JSONArray?): List<Int> {
        if (a == null) return emptyList()
        val out = mutableListOf<Int>()
        for (i in 0 until a.length()) (a.opt(i) as? Number)?.toInt()?.let { out.add(it) }
        return out
    }

    private fun JSONArray.toListOfObj(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (i in 0 until length()) (opt(i) as? JSONObject)?.let { out.add(it) }
        return out
    }
}
