package com.lmj.labyrinthauto.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * **EX 装备快照**（2026-11-06 用户需求）：跑黎明界会自动换装、把原本的装备拆下来，
 * 所以开跑前存一份、整轮结束后按记录还原。
 *
 * 一条记录 = `{id, ts, note, state}`，`state = {unitId: {slot: serialId}}`（只含当时装着东西的槽位）。
 * 落盘：`files/ex_equip_records.json`（最多保留 [MAX] 条，旧的自动丢）。
 */
data class ExEquipRecord(
    val id: Long,
    val ts: Long,
    val note: String,
    /** unitId → (slot → serialId)；serialId=0 表示该槽为空 */
    val state: Map<Int, Map<Int, Int>>,
) {
    val unitCount: Int get() = state.count { (_, slots) -> slots.values.any { it > 0 } }
    val equipCount: Int get() = state.values.sumOf { slots -> slots.values.count { it > 0 } }
}

object ExEquipStore {

    private const val MAX = 20
    private const val FILE = "ex_equip_records.json"

    /** 标记"这轮跑之前已经存过"，用于异常退出后下次启动提示还原 */
    private const val KEY_PENDING = "ex_equip_pending_restore"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun all(ctx: Context): List<ExEquipRecord> {
        val f = file(ctx)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i -> parse(arr.optJSONObject(i)) }
        }.getOrDefault(emptyList()).sortedByDescending { it.ts }
    }

    fun latest(ctx: Context): ExEquipRecord? = all(ctx).firstOrNull()

    /** 存一条新记录（顺带裁掉超出的旧记录） */
    fun save(ctx: Context, state: Map<Int, Map<Int, Int>>, note: String = ""): ExEquipRecord {
        val list = all(ctx).toMutableList()
        val rec = ExEquipRecord(
            id = System.currentTimeMillis(),
            ts = System.currentTimeMillis(),
            note = note,
            state = state,
        )
        list.add(0, rec)
        write(ctx, list.take(MAX))
        return rec
    }

    fun delete(ctx: Context, id: Long) {
        write(ctx, all(ctx).filterNot { it.id == id })
    }

    private fun write(ctx: Context, list: List<ExEquipRecord>) {
        val arr = JSONArray()
        list.forEach { rec ->
            val st = JSONObject()
            rec.state.forEach { (uid, slots) ->
                val s = JSONObject()
                slots.forEach { (slot, serial) -> s.put(slot.toString(), serial) }
                st.put(uid.toString(), s)
            }
            arr.put(
                JSONObject()
                    .put("id", rec.id)
                    .put("ts", rec.ts)
                    .put("note", rec.note)
                    .put("state", st),
            )
        }
        runCatching { file(ctx).writeText(arr.toString()) }
    }

    private fun parse(o: JSONObject?): ExEquipRecord? {
        o ?: return null
        val st = o.optJSONObject("state") ?: return null
        val state = LinkedHashMap<Int, Map<Int, Int>>()
        val uids = st.keys()
        while (uids.hasNext()) {
            val uid = uids.next().toIntOrNull() ?: continue
            val slotsObj = st.optJSONObject(uid.toString()) ?: continue
            val slots = LinkedHashMap<Int, Int>()
            val sk = slotsObj.keys()
            while (sk.hasNext()) {
                val slot = sk.next().toIntOrNull() ?: continue
                slots[slot] = slotsObj.optInt(slot.toString(), 0)
            }
            state[uid] = slots
        }
        return ExEquipRecord(
            id = o.optLong("id"),
            ts = o.optLong("ts"),
            note = o.optString("note"),
            state = state,
        )
    }

    // ────────────────────────── 待还原标记（异常退出用） ──────────────────────────

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("ex_equip", Context.MODE_PRIVATE)

    /** 开跑前存了快照 ⇒ 记一笔"跑完要还原"；正常还原后清掉 */
    fun markPending(ctx: Context, recordId: Long) {
        prefs(ctx).edit().putLong(KEY_PENDING, recordId).apply()
    }

    fun clearPending(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_PENDING, 0L).apply()
    }

    /** 有待还原的记录 id（0 = 没有） */
    fun pendingRecordId(ctx: Context): Long = prefs(ctx).getLong(KEY_PENDING, 0L)

    /** 「跑完自动还原」开关（默认开，2026-11-06 用户） */
    fun autoRestore(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_restore", true)

    fun setAutoRestore(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("auto_restore", on).apply()
    }
}
