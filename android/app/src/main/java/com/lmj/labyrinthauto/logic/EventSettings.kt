package com.lmj.labyrinthauto.logic

import android.content.Context
import android.content.SharedPreferences

/**
 * 策略页「事件」的用户选择持久化：eventId -> 选中的选项序号（1 起）。
 * 仅存**用户的覆盖项**（相对 `event_choice.json` 表内 `pick` 的偏差）；未改的事件走表内默认值。
 * 存储为 `"id:ord,id:ord"` 形式，避免 serialization 依赖。
 */
object EventSettings {
    private const val PREF = "event_picks"

    fun loadPicks(context: Context): Map<Int, Int> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("picks", null) ?: return emptyMap()
        val map = mutableMapOf<Int, Int>()
        raw.split(",").forEach { pair ->
            val idx = pair.indexOf(":")
            if (idx > 0) {
                val id = pair.substring(0, idx).toIntOrNull()
                val ord = pair.substring(idx + 1).toIntOrNull()
                if (id != null && ord != null && ord > 0) map[id] = ord
            }
        }
        return map
    }

    fun savePicks(context: Context, picks: Map<Int, Int>) {
        val raw = picks.entries.joinToString(",") { "${it.key}:${it.value}" }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("picks", raw).apply()
    }
}
