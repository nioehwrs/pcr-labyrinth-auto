package com.lmj.labyrinthauto.logic

import com.lmj.labyrinthauto.core.AppState
import org.json.JSONArray
import org.json.JSONObject

/**
 * 事件选择决策（PC `handle_event_choice`(1088) + `EVENT_PICK` 表(319/5508)）。
 *
 * 事件页（EVENT_CHOICE=4）按 eventId 选第 N 张选项卡（N 从 1 起）；
 * 未配置时默认选第 1 项（与 PC `EVENT_PICK.get(eid, 1)` 行为一致）。
 *
 * 决策表 `EVENT_PICK`：eventId -> 选项序号（1 起），由 `event_choice.json` 加载填充
 * （PC 侧 `load_event_choice`(5507)：event_id -> 选第几项；缺 `pick` 字段的按第 1 项）。
 */

/** 单个事件选项（来自 `event_choice.json` 的 `choices` 数组） */
data class EventOption(
    val ord: Int,
    val comment: String,
    val desc: String,
    val choiceId: Int,
)

/** 一个事件：提示文字 + 可选项 + 表内默认选中的序号（1 起） */
data class EventEntry(
    val id: Int,
    val text: String,
    val options: List<EventOption>,
    val defaultPick: Int,
    /** 合并事件覆盖的多个 eventId（如「魔物们斗技场」各金币档位）；空 = 仅本 id */
    val allIds: List<Int> = emptyList(),
    /** 被锁定的选项 ord 集合（禁用、不可改）。如「料理完成！」选项2、「完美！」两选项 */
    val lockedOpts: Set<Int> = emptySet(),
    /** ord -> 选项下附注（如「料理完成！」选项2 标「没有实现自选角色逻辑」） */
    val optionNote: Map<Int, String> = emptyMap(),
) {
    /** 实际生效的 eventId 集合（合并事件展开为多个） */
    val effectiveIds: List<Int> get() = allIds.ifEmpty { listOf(id) }
}

/** 从选项描述里提取「阿尔法金币×N」的 N */
private val GOLD_RE = Regex("阿尔法金币×(\\d+)")

private fun goldOf(desc: String): Int? = GOLD_RE.find(desc)?.groupValues?.get(1)?.toIntOrNull()

/** 把描述里的单个金币档位替换为多档位（如 ×200 → ×200/300/400/500） */
private fun withGolds(desc: String, golds: List<Int>): String {
    if (golds.isEmpty()) return desc
    return GOLD_RE.replace(desc) { "阿尔法金币×${golds.joinToString("/")}" }
}

object EventPick {
    /** eventId -> 第几项（1 起）。由 [load] 从 `event_choice.json` 填充；空则 `pick` 一律取第 1 项。 */
    val EVENT_PICK: MutableMap<Int, Int> = mutableMapOf()

    /**
     * 复刻 PC `load_event_choice`(5507)：读 `event_choice.json` → 填入 [EVENT_PICK]
     * （event_id -> `pick` 字段，缺省/非数字按 1）。读不到或解析失败 → 留空（退化为永远选第 1 项）。
     * 与 `Tables`/`RelicTables` 同款 source-agnostic 设计：app 传 assets，单测传文件。
     */
    fun load(reader: (String) -> String) {
        EVENT_PICK.clear()
        try {
            val raw = JSONObject(reader("event_choice.json"))
            val it = raw.keys()
            var bad = 0
            while (it.hasNext()) {
                val k = it.next()
                val eid = k.toIntOrNull() ?: continue
                val v = raw.optJSONObject(k) ?: continue
                val p = v.opt("pick")
                val pick = (p as? Number)?.toInt() ?: p?.toString()?.toIntOrNull()
                // ★没写 `pick`（或写得不是正数）**不再当成"第 1 项"**（用户 2026-10-16："都不要随便选"）
                //   ⇒ 视为**未配置**，遇到这个事件就不点、留人工（见 [pick]）。
                if (pick == null || pick <= 0) { bad++; continue }
                EVENT_PICK[eid] = pick
            }
            AppState.addLog(
                "[事件] 已载入 event_choice.json：${EVENT_PICK.size} 个事件" +
                    (if (bad > 0) "（另有 $bad 个没写 pick → 视为未配置、遇到时不猜）" else ""),
            )
        } catch (e: Exception) {
            AppState.addLog("[事件] 未找到/读取失败 event_choice.json：${e.message} → **未配置的事件一律不点**（不猜）")
        }
    }

    /**
     * 解析 `event_choice.json` → 事件列表（含提示与选项文字），供策略页「事件」做成可修改单选。
     * 选项按 `ord` 排序、事件按 id 排序；解析失败返回空列表。
     */
    fun loadEntries(reader: (String) -> String): List<EventEntry> {
        val out = mutableListOf<EventEntry>()
        try {
            val raw = JSONObject(reader("event_choice.json"))
            val keys = raw.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val eid = k.toIntOrNull() ?: continue
                val v = raw.optJSONObject(k) ?: continue
                val text = v.optString("text", "")
                val pick = (v.opt("pick") as? Number)?.toInt()
                    ?: v.optString("pick").toIntOrNull() ?: 1
                val opts = mutableListOf<EventOption>()
                val arr = v.optJSONArray("choices")
                if (arr is JSONArray) {
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        opts += EventOption(
                            ord = c.optInt("ord", i + 1),
                            comment = c.optString("comment", ""),
                            desc = c.optString("description", ""),
                            choiceId = c.optInt("choice_id", 0),
                        )
                    }
                }
                opts.sortBy { it.ord }
                out += EventEntry(eid, text, opts, pick.coerceAtLeast(1))
            }
            out.sortBy { it.id }

            // ★合并「魔物们斗技场」（仅金币不同）→ 同一张卡；选项描述里把金币写成各档位
            val arena = out.filter { it.text == "发现了魔物们聚集的斗技场！" }
            if (arena.size > 1) {
                val canonical = arena.minBy { it.id }
                val newOptions = canonical.options.map { opt ->
                    val golds = arena.mapNotNull { a ->
                        a.options.firstOrNull { it.ord == opt.ord }?.desc?.let { goldOf(it) }
                    }
                    opt.copy(desc = withGolds(opt.desc, golds))
                }
                out.removeAll(arena)
                out += canonical.copy(options = newOptions, allIds = arena.map { it.id })
                out.sortBy { it.id }
            }

            // ★「料理完成了！」(2315) 选项2 锁定 + 标注未实现自选角色逻辑；
            //   「完美！」(3315) 两个选项都锁定
            out.replaceAll { e ->
                when (e.id) {
                    2315 -> e.copy(
                        lockedOpts = setOf(2),
                        optionNote = mapOf(2 to "（没有实现自选角色逻辑）"),
                    )
                    3315 -> e.copy(lockedOpts = setOf(1, 2))
                    else -> e
                }
            }
        } catch (e: Exception) {
            AppState.addLog("[事件] 解析事件表失败：${e.message}")
        }
        return out
    }

    /** 用用户保存的覆盖项（eventId -> 选中序号）刷新 [EVENT_PICK]，使运行时按用户选择点选。 */
    fun applyPicks(picks: Map<Int, Int>) {
        for ((eid, ord) in picks) {
            if (ord > 0) EVENT_PICK[eid] = ord
        }
    }

    /**
     * 返回应点的选项卡序号（1 起，已夹在 [1, n]）；**null = 这个事件没配置过 ⇒ 不要猜**。
     *
     * ★2026-10-16 用户："事件里也是二选一/三选一，**都不要随便选**" ⇒ 去掉原来的 `?: 1`
     *   （未配置时默认第 1 项）。调用方（`handleEventChoice`）遇到 null 会**不点 + 打印候选**，
     *   等人工选一次或补一行 `event_choice.json`；一直不选则停手留人工。
     * @param eventId 事件 id（LabyrinthStatus.eventId）
     * @param n       选项卡总数
     */
    fun pick(eventId: Int?, n: Int): Int? {
        if (n <= 0) return null
        val want = eventId?.let { EVENT_PICK[it] } ?: return null
        return want.coerceIn(1, n)
    }
}
