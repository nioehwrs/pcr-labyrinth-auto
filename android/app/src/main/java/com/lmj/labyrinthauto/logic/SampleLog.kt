package com.lmj.labyrinthauto.logic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ★**随机信息采样**（2026-11-13 用户需求）：把「一局里随机生成的东西」分开记到三个文件，
 *   供事后统计（用法与 [com.lmj.labyrinthauto.logic.shop.ShopSampleLog] 一致）。
 *
 * ```
 * logs/battle_samples.jsonl  {"ts":…,"type":"battle","blockId":"20301","questId":"770211001","ex":false}
 * logs/relic_samples.jsonl   {"ts":…,"type":"relic","blockId":"20601","rarities":[3,1,2]}
 *                            {"ts":…,"type":"relic_event","blockId":"20801","rarities":[2,2,3]}
 * logs/event_samples.jsonl   {"ts":…,"type":"event","blockId":"20401","eventId":12345}
 * ```
 *  · battle：**普通战 / EX 战**（不含 Boss）；`questId` = 这一组敌人的关卡编号。
 *  · relic ：三选一遗物的**星级**；事件触发的记成 `relic_event`。
 *  · event ：事件节点的 `eventId`。
 *
 * 同一 (`type`, `blockId`) 在**本局**只记一次（[reset] 由每局开头调用）。
 */
object SampleLog {

    /** 三类样本（各自一个文件） */
    enum class Kind(val file: String, val cn: String) {
        BATTLE("battle_samples.jsonl", "战斗"),
        RELIC("relic_samples.jsonl", "遗物"),
        EVENT("event_samples.jsonl", "事件"),
    }

    private const val MAX_BYTES = 8L * 1024 * 1024

    private val seen = HashSet<String>()

    /** 每局开头清去重表 */
    fun reset() = synchronized(this) { seen.clear() }

    fun fileOf(ctx: Context, kind: Kind): File =
        File(File(ctx.getExternalFilesDir(null), "logs"), kind.file)

    private fun append(ctx: Context, kind: Kind, o: JSONObject) {
        runCatching {
            val dir = File(ctx.getExternalFilesDir(null), "logs")
            dir.mkdirs()
            val f = File(dir, kind.file)
            if (f.length() > MAX_BYTES) {
                val old = File(dir, kind.file.replace(".jsonl", ".old.jsonl"))
                old.delete()
                f.renameTo(old)
            }
            o.put("ts", System.currentTimeMillis())
            f.appendText(o.toString() + "\n")
        }
    }

    private fun once(ctx: Context, kind: Kind, type: String, blockId: String, o: JSONObject) {
        val key = "$type|$blockId"
        synchronized(this) { if (!seen.add(key)) return }
        o.put("type", type)
        o.put("blockId", blockId)
        append(ctx, kind, o)
    }

    /** 战斗节点（普通/EX）：blockId + 关卡（敌人组）编号 */
    fun battle(ctx: Context?, blockId: String, questId: String, ex: Boolean) {
        ctx ?: return
        if (blockId.isEmpty() || questId.isEmpty()) return
        once(ctx, Kind.BATTLE, "battle", blockId, JSONObject().put("questId", questId).put("ex", ex))
    }

    /**
     * ★2026-11-13（用户）：**事件触发的战斗**（魔物竞技场 1105x 这类）——它直接从事件页进 `BATTLE`，
     *   不走预览页 ⇒ 没有 `questId`；"敌人编号"就是事件 id（事件决定打哪队敌人）。
     *   写进 `battle_samples.jsonl`，`type=battle_event`。
     */
    fun eventBattle(ctx: Context?, blockId: String, eventId: Int) {
        ctx ?: return
        if (blockId.isEmpty() || eventId <= 0) return
        once(ctx, Kind.BATTLE, "battle_event", blockId, JSONObject().put("eventId", eventId))
    }

    /** 三选一遗物：blockId + 三个候选的星级（事件触发的记成 `relic_event`） */
    fun relic(ctx: Context?, blockId: String, rarities: List<Int>, fromEvent: Boolean) {
        ctx ?: return
        if (blockId.isEmpty() || rarities.isEmpty()) return
        val arr = JSONArray()
        rarities.forEach { arr.put(it) }
        once(
            ctx, Kind.RELIC, if (fromEvent) "relic_event" else "relic", blockId,
            JSONObject().put("rarities", arr),
        )
    }

    /** 事件节点：blockId + eventId */
    fun event(ctx: Context?, blockId: String, eventId: Int) {
        ctx ?: return
        if (blockId.isEmpty() || eventId <= 0) return
        once(ctx, Kind.EVENT, "event", blockId, JSONObject().put("eventId", eventId))
    }

    /** 单个文件统计 */
    data class One(val count: Int = 0, val count2: Int = 0, val bytes: Long = 0) {
        val total: Int get() = count + count2
    }

    /** UI 统计：三类各自的条数 + 文件大小（`count2` 只对遗物有意义 = 事件触发的条数） */
    fun stats(ctx: Context): Map<Kind, One> = Kind.values().associateWith { k ->
        val f = fileOf(ctx, k)
        if (!f.exists()) return@associateWith One()
        var n = 0
        var n2 = 0
        runCatching {
            f.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                if (o.optString("type") == "relic_event") n2++ else n++
            }
        }
        One(n, n2, f.length())
    }
}
