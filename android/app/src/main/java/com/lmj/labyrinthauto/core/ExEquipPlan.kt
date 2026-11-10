package com.lmj.labyrinthauto.core

/**
 * EX 装备还原**计划计算**（纯逻辑，不碰网络）。算法照抄 autopcr `exequip.py` 的恢复模块：
 *
 * 1. 先建反查表「这件装备现在在谁身上」——EX 装备**独占**，A 装着 B 就拿不到；
 * 2. 逐槽位比对快照：一致就跳过；**目标装备被别人占着 ⇒ 先把占用的那个槽卸下来**；
 * 3. **先提交全部 remove、再提交全部 apply**（同一批请求里出现"既卸又装同一件"会冲突）；
 * 4. 快照里的装备若已不存在（被炼成/卖掉）⇒ 跳过并记入 [Plan.missing]；
 * 5. **只动快照里出现过的槽位**：记录之外的角色、以及记录之后新上的装备一律不碰。
 */
object ExEquipPlan {

    /** 一次槽位变更：把 [unitId] 的第 [slot] 槽设为 [serialId]（0 = 卸下） */
    data class Change(val unitId: Int, val slot: Int, val serialId: Int)

    data class Plan(
        val remove: List<Change> = emptyList(),
        val apply: List<Change> = emptyList(),
        val missing: List<Int> = emptyList(),
        val unchanged: Int = 0,
    ) {
        val isEmpty: Boolean get() = remove.isEmpty() && apply.isEmpty()
        fun summary(): String =
            "还原计划：卸下 ${remove.size} ｜ 装上 ${apply.size} ｜ 已一致 $unchanged" +
                if (missing.isEmpty()) "" else " ｜ 装备已不存在 ${missing.size} 件"
    }

    /**
     * @param target  快照（unitId → slot → serialId）
     * @param current 当前状态（同结构；缺失的槽按 0 处理）
     * @param alive   现存的 EX 装备 serialId 集合（快照里不在其中的视为"已消失"）
     */
    fun build(
        target: Map<Int, Map<Int, Int>>,
        current: Map<Int, Map<Int, Int>>,
        alive: Set<Int>,
    ): Plan {
        // 反查：serialId → 谁装着（slot）
        val position = HashMap<Int, Pair<Int, Int>>()
        current.forEach { (uid, slots) ->
            slots.forEach { (slot, serial) ->
                if (serial > 0) position[serial] = uid to slot
            }
        }

        val remove = ArrayList<Change>()
        val apply = ArrayList<Change>()
        val missing = LinkedHashSet<Int>()
        val touched = HashSet<Pair<Int, Int>>()
        var unchanged = 0

        target.toSortedMap().forEach { (uid, slots) ->
            // ★快照里没有的角色不碰（autopcr 同款：只动快照覆盖到的角色）。
            // ⚠2026-11-10 修「根本没有还原」：不能因为**当前这个角色一件 EX 都没装**就跳过 ——
            //   `parseSnapshot` 只收非空槽，脚本换装把装备拆光后 `current[uid]` 就是 null，
            //   旧代码直接 `return@forEach` ⇒ 整个角色被跳过 ⇒ `plan` 为空 ⇒ 报"已经和记录一致"。
            val curSlots = current[uid] ?: emptyMap()
            slots.toSortedMap().forEach slotLoop@{ (slot, rawTarget) ->
                // ⚠同理：**目标槽当前为空（不在 curSlots 里）正是要装回的情况**，不能跳过。
                val targetSerial = rawTarget.coerceAtLeast(0)
                val currentSerial = (curSlots[slot] ?: 0).coerceAtLeast(0)
                if (targetSerial == currentSerial) {
                    unchanged++
                    return@slotLoop
                }
                if (targetSerial > 0 && targetSerial !in alive) {
                    missing.add(targetSerial)          // 装备已经不存在了
                    return@slotLoop
                }
                if (targetSerial > 0) {
                    val occupy = position[targetSerial]
                    if (occupy != null && occupy != (uid to slot)) {
                        val key = occupy
                        if (key !in touched) {
                            remove.add(Change(key.first, key.second, 0))
                            touched.add(key)
                        }
                    }
                }
                apply.add(Change(uid, slot, targetSerial))
            }
        }
        return Plan(remove, apply, missing.toList(), unchanged)
    }
}
