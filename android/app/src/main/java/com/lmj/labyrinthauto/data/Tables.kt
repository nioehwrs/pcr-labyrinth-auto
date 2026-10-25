package com.lmj.labyrinthauto.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 游戏数据表（`units.json` / `unit_tags.json` / `tag_defs.json`）。
 *
 * 为什么这样设计：
 *   - **表要能逐字读**：`PLAN.md §5` 的 P4 要求"与 PC 版逐项一致"，所以这里只做**最薄的读取**，
 *     不做任何字段改名/派生（派生逻辑留在 `logic/team` 里，好和 Python 一行行对）；
 *   - **来源可换**：app 运行从 assets 读、JVM 单测从文件系统读、将来 PC 侧也可以喂字符串 ——
 *     所以构造只收 `reader: (名字) -> JSON 文本` 一个函数。
 *
 * PC 版对应物：`team_build.load()`（U / TAGS / DEFS 三份）。
 */
class Tables private constructor(
    /** units.json：uid(str) → 角色信息（role/range/attr/atk_type/name…） */
    val units: Map<String, JSONObject>,
    /** unit_tags.json：uid(str) → {tags:[…], tier:N, tier_<scen>:N}（**缺项就是没有**，别填默认值） */
    val tags: Map<String, JSONObject>,
    /** tag_defs.json 的 tags[] → id → 定义（name/group/scope…） */
    val defs: Map<String, JSONObject>,
) {

    fun unit(uid: String): JSONObject? = units[uid]

    /** 该角色的标签集合（PC 版 `tags_of`） */
    fun tagSetOf(uid: String): Set<String> {
        val arr = tags[uid]?.optJSONArray("tags") ?: return emptySet()
        val out = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    /** 场景评级（PC 版 `tier_of`：`tier_<scen>` 缺则回落 `tier`；都没有 = null） */
    fun tierOf(uid: String, scen: String = "all"): Int? {
        val v = tags[uid] ?: return null
        val t = if (scen != "all") v.opt("tier_$scen").takeIf { it != JSONObject.NULL } else null
        val chosen = t ?: v.opt("tier").takeIf { it != JSONObject.NULL }
        return when (chosen) {
            is Number -> chosen.toInt()
            else -> null
        }
    }

    companion object {

        const val UNITS = "units.json"
        const val TAGS = "unit_tags.json"
        const val DEFS = "tag_defs.json"

        /** 从"名字 → JSON 文本"的读取函数构造（app 传 assets、单测传文件）。 */
        fun load(reader: (String) -> String): Tables {
            val units = objMap(JSONObject(reader(UNITS)))
            val tags = objMap(JSONObject(reader(TAGS)))
            val defsArr = JSONObject(reader(DEFS)).optJSONArray("tags") ?: JSONArray()
            val defs = LinkedHashMap<String, JSONObject>(defsArr.length())
            for (i in 0 until defsArr.length()) {
                val o = defsArr.optJSONObject(i) ?: continue
                defs[o.optString("id")] = o
            }
            return Tables(units, tags, defs)
        }

        private fun objMap(root: JSONObject): Map<String, JSONObject> {
            val out = LinkedHashMap<String, JSONObject>(root.length())
            val it = root.keys()
            while (it.hasNext()) {
                val k = it.next()
                root.optJSONObject(k)?.let { v -> out[k] = v }
            }
            return out
        }
    }
}
