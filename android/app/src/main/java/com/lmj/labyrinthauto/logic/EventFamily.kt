package com.lmj.labyrinthauto.logic

/**
 * ★**手工整理的「事件族」**（用户 2026-11-03 口述整理，替代"按文案自动合并"）。
 *
 * 为什么需要这一层：
 *  · 同一个玩法在游戏里拆成好几个事件 id（钓鱼 = 1314/2314/3314、料理 = 1315/2315/3315、
 *    老虎机 = 1316/2316/3316），文案各不相同，"按 text 自动合并"合不到一起；
 *  · 每个玩法"算成功还是失败"不能只看单条 rid：老虎机的「成功 1 次 / 2 次 / 3 次」
 *    要看**过程中每一次的结果 + 最后选的是"继续"还是"放弃"**（见 [Seq]）。
 *
 * 所以条件里配的是"**族 + 结果名**"，不是"事件 id + 分支号"。
 * 表就是下面 [all] 这一坨，用户改口径只改这里。
 */
object EventFamily {

    /** 需要"看过程"的序列型结果（目前只有老虎机） */
    enum class Seq {
        NONE,

        /** 第 1 次成功后主动收手：1316 成功 → 2316 选「到这里就行了」 */
        SLOT_STOP1,

        /** 第 2 次成功后收手：1316 成功 → 2316 成功 → 3316 选「差不多该放弃了」 */
        SLOT_STOP2,

        /** 3 次全成功：1316 成功 → 2316 成功 → 3316 成功 */
        SLOT_ALL,

        /** 任意一次失败（131612 / 231612 / 331612） */
        SLOT_FAIL,
    }

    /**
     * 一条**结果可能性**（条件里结果下拉的一项）。
     *
     * @param id   存进条件里的值。★用"代表性 rid"，这样老数据（只存了 rid）也能对上。
     * @param name 下拉里显示的中文（用户定的名字）
     * @param rids 命中这些 rid 就算这个结果（[seq] ≠ NONE 时由序列判定，这里只用于显示/兼容）
     */
    data class Outcome(
        val id: Int,
        val name: String,
        val rids: List<Int> = emptyList(),
        val seq: Seq = Seq.NONE,
        /**
         * ★"**事件前后角色数差 < N**"型结果（用户 2026-11-03，只有活动会场用）。
         *   >0 表示这个结果不看 rid，只看 du = 事件后角色数 − 事件前角色数，
         *   满足 `du < unitBelow` 就成立。
         *   ★三档**不互斥**（du=2 时 <3、<4、<5 同时成立），用户要的就是这个。
         */
        val unitBelow: Int = 0,
    )

    /**
     * 一个**事件族**（条件下拉的一项）。
     *
     * @param name     下拉显示名
     * @param eventIds 归属的事件 id（条件里就存这一组）
     * @param outcomes 结果可能性；空表 = 这个族没有随机结果（条件只到"遇到就撤"）
     */
    data class Family(
        val name: String,
        val eventIds: List<Int>,
        val outcomes: List<Outcome> = emptyList(),
    ) {
        /** 有这个结果吗（没有 = 该族没有随机结果） */
        val hasRandom: Boolean get() = outcomes.isNotEmpty()

        fun outcome(id: Int): Outcome? = outcomes.firstOrNull { it.id == id }
    }

    /** 老虎机的三次 rid（成功 / 失败 / 收手），写在一起方便对账 */
    private const val SM_1_OK = 131611
    private const val SM_1_NG = 131612
    private const val SM_2_OK = 231611
    private const val SM_2_NG = 231612
    private const val SM_2_STOP = 231621
    private const val SM_3_OK = 331611
    private const val SM_3_NG = 331612
    private const val SM_3_STOP = 331621

    /** ★全部事件族（用户 2026-11-03 整理；改口径就改这里） */
    val all: List<Family> = listOf(
        // ===== 无随机结果：遇到就撤 =====
        Family("公会管理协会", listOf(1101, 1102, 1103, 1104)),
        Family("魔物竞技场", listOf(1105, 1109, 1113, 1117)),

        // ===== 钓鱼（3 格）：★拿到的高星遗物**不会把低星还回去**，所以看的是"累计件数" =====
        //   第 1 格失败 ⇒ 0 件（< 1）；第 2 格失败 ⇒ 只剩第 1 格的 1 件（< 2）；
        //   第 3 格失败 ⇒ 2 件（< 3）；三格全成功 ⇒ 3 件。
        //   中间"成功并进下一格"的那些 rid（131411 / 231411）不是结论，不登记（会自然匹配到"没有结果"）。
        Family(
            "钓鱼",
            listOf(1314, 2314, 3314),
            listOf(
                Outcome(131412, "遗物<1件", listOf(131412)),
                Outcome(231412, "遗物<2件", listOf(231412)),
                Outcome(331412, "遗物<3件", listOf(331412)),
            ),
        ),

        // ===== 料理（3 格）：成功=随机印记×2，失败=金币500 =====
        //   中间"进下一格"的 rid（131511/231511/331511）不参与判定（用户：不会去选自选印记那条）
        Family(
            "料理",
            listOf(1315, 2315, 3315),
            listOf(
                Outcome(231521, "成功→随机印记×2", listOf(231521, 331521)),
                Outcome(131512, "失败→金币=500", listOf(131512, 231512, 331512)),
            ),
        ),

        // ===== 老虎机（3 格）：★要看过程（每次的结果 + 最后收手/继续） =====
        Family(
            "老虎机",
            listOf(1316, 2316, 3316),
            listOf(
                Outcome(SM_2_STOP, "成功1次→金币=2000", seq = Seq.SLOT_STOP1),
                Outcome(SM_3_STOP, "成功2次→金币=4000", seq = Seq.SLOT_STOP2),
                Outcome(SM_3_OK, "成功3次→金币=8000", seq = Seq.SLOT_ALL),
                Outcome(SM_1_NG, "失败→金币=500", listOf(SM_1_NG, SM_2_NG, SM_3_NG), Seq.SLOT_FAIL),
            ),
        ),

        // ===== 单事件 =====
        Family(
            "不可思议的石板",
            listOf(1107),
            listOf(
                Outcome(110731, "金币=1500", listOf(110731)),
                Outcome(110732, "金币=2000", listOf(110732)),
                Outcome(110721, "职业印记×1", listOf(110721)),
                Outcome(110722, "全体选择印记×1", listOf(110722)),
                Outcome(110711, "遗物(★2)", listOf(110711)),
                Outcome(110712, "遗物(★3)", listOf(110712)),
            ),
        ),
        Family(
            "在地下城迷路了",
            listOf(1108),
            // 110811 与 110813 先合并（看不出区别）；卡1 的"金币=1000"没有随机性，不列入
            listOf(
                Outcome(110811, "遗物(★1)", listOf(110811, 110813)),
                Outcome(110812, "随机印记×2", listOf(110812)),
            ),
        ),
        Family(
            "石头剪刀布",
            listOf(1110),
            // 三张卡同构：胜利=3000 / 失败=1000
            listOf(
                Outcome(111011, "获胜→金币=3000", listOf(111011, 111021, 111031)),
                Outcome(111012, "失败→金币=1000", listOf(111012, 111022, 111032)),
            ),
        ),
        Family(
            "箱子里传出的声音",
            listOf(1111),
            // ★111121/111122/111123 对应 ★1/★2/★3 是按分支顺序推的（表里没写星级）
            listOf(
                Outcome(111111, "金币=2000", listOf(111111)),
                Outcome(111121, "遗物(★1)", listOf(111121)),
                Outcome(111122, "遗物(★2)", listOf(111122)),
                Outcome(111123, "遗物(★3)", listOf(111123)),
            ),
        ),
        Family(
            "活动会场出现了魔物",
            listOf(1112),
            // ★三个"获得角色"按**事件前后角色数差**判（用户 2026-11-03）：差值正常 2~5，
            //   满足 du < 3 / < 4 / < 5 就成立，**三档不互斥**。
            //   差值不只看选项（还取决于是否已有本体"纯"）⇒ 必须比数量，不能只看 rid。
            listOf(
                Outcome(111211, "金币=2000", listOf(111211)),
                Outcome(111221, "获得角色<3", unitBelow = 3),
                Outcome(111222, "获得角色<4", unitBelow = 4),
                Outcome(111223, "获得角色<5", unitBelow = 5),
            ),
        ),
    )

    /**
     * 老虎机那几步的 rid：它们**本身不是结论**（"继续/收手/某一次成功"），
     * 要靠 [SlotMachine] 结合前后几步才能定 ⇒ 不能退化按 rid 直接命中。
     */
    private val seqRids = setOf(
        SM_1_OK, SM_1_NG, SM_2_OK, SM_2_NG, SM_2_STOP, SM_3_OK, SM_3_NG, SM_3_STOP,
    )

    /** 这条 rid 是不是老虎机的"过程步" */
    fun isSeqRid(rid: Int): Boolean = rid in seqRids

    /** 这条 rid 属于"角色数差 < N"型结果吗（活动会场的三个"获得角色"）：是的话判定改看 du */
    fun isUnitRid(rid: Int): Boolean =
        all.asSequence().flatMap { it.outcomes.asSequence() }
            .any { it.unitBelow > 0 && (rid == it.id || rid in it.rids) }

    /** 角色差 [du] 成立的所有"角色 < N"结果（★不互斥，可能同时成立若干条） */
    fun unitOutcomesBelow(du: Int): Set<Int> =
        all.asSequence().flatMap { it.outcomes.asSequence() }
            .filter { it.unitBelow > 0 && du < it.unitBelow }
            .map { it.id }.toSet()

    /** 事件 id → 族（不在任何族里 ⇒ null） */
    fun of(eid: Int): Family? = all.firstOrNull { eid in it.eventIds }

    /** 结果 id → 结果（跨族查找） */
    fun outcome(id: Int): Outcome? =
        all.asSequence().flatMap { it.outcomes.asSequence() }.firstOrNull { it.id == id }

    /** 结果 id → 它所属的族（UI 里反查"这条条件配的是哪个族的哪个结果"） */
    fun familyOfOutcome(id: Int): Family? = all.firstOrNull { f -> f.outcomes.any { it.id == id } }

    /**
     * 任意 rid → 它所属**结果**的 id（老数据兼容：以前存的是具体 rid，比如 110813）。
     * 不属于任何登记结果 → null。
     */
    fun canonical(rid: Int): Int? =
        all.asSequence().flatMap { it.outcomes.asSequence() }
            .firstOrNull { rid == it.id || rid in it.rids }?.id

    /** 族名 → 族（测试/日志用） */
    fun byName(name: String): Family? = all.firstOrNull { it.name == name }

    /**
     * ★把一次结算的 rid 判定成"命中了哪些结果"。
     *
     * 普通结果：rid 在 [Outcome.rids] 里就算命中；
     * 老虎机：要靠**前几步的 rid**（[seq]），所以用 [SlotMachine] 这类有状态的判定器。
     */
    object RidJudge {

        /** 单条 rid → 直接命中（不含序列型） */
        fun direct(rid: Int): Set<Int> {
            val out = mutableSetOf<Int>()
            for (f in all) for (o in f.outcomes) {
                if (o.seq == Seq.NONE && rid in o.rids) out += o.id
            }
            return out
        }
    }

    /**
     * ★老虎机的**过程判定**：喂每一次结算的 rid，回答"这一步达成了哪个结论"。
     *
     * 用户口径（2026-11-03）：
     *  · 成功 1 次 = 第 1 次成功后选「到这里就行了……」（231621）；
     *  · 成功 2 次 = 第 1、2 次都成功后选「差不多该放弃了……」（331621）；
     *  · 成功 3 次 = 三次全成功（331611）；
     *  · 失败 = 任意一次失败（131612 / 231612 / 331612）。
     */
    class SlotMachine {
        private val seen = mutableListOf<Int>()

        /** 喂一条 rid；返回本次新达成的结果 id（可能空） */
        fun feed(rid: Int): Set<Int> {
            when (rid) {
                SM_1_OK -> {
                    seen.clear()
                    seen += rid
                    return emptySet()
                }
                SM_2_OK -> {
                    if (seen.lastOrNull() == SM_1_OK) seen += rid
                    return emptySet()
                }
                SM_2_STOP -> {
                    val hit = seen.lastOrNull() == SM_1_OK
                    seen.clear()
                    return if (hit) setOf(SM_2_STOP) else emptySet()
                }
                SM_3_OK -> {
                    val hit = seen.size >= 2 && seen[0] == SM_1_OK && seen[1] == SM_2_OK
                    seen.clear()
                    return if (hit) setOf(SM_3_OK) else emptySet()
                }
                SM_3_STOP -> {
                    val hit = seen.size >= 2 && seen[0] == SM_1_OK && seen[1] == SM_2_OK
                    seen.clear()
                    return if (hit) setOf(SM_3_STOP) else emptySet()
                }
                SM_1_NG, SM_2_NG, SM_3_NG -> {
                    seen.clear()
                    return setOf(SM_1_NG)
                }
            }
            return emptySet()
        }

        /** 换局/换事件族时清掉 */
        fun reset() = seen.clear()
    }
}
