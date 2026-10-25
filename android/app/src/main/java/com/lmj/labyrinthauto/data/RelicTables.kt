package com.lmj.labyrinthauto.data

import org.json.JSONObject

/**
 * 遗物 / 商店数据表（`relic_rank.json` / `shop_items.json`）。
 *
 * 与 [Tables] 同一套思路：**只做最薄的读取 + PC 版同款字段投影**，派生逻辑留在 `logic` 里。
 *
 * 投影必须逐字照 `pcr_auto.load_relic_rank()`(5452) / `load_shop_items()`(5481)：
 * 它们的调用方（`_rk`/`_si`）拿到的**不是原始 JSON**，而是 Python 归一化过的字段
 * （`int(v.get("rarity", 0))` / `bool(v.get("shop_only"))` …）——
 * 少一个字段或多一个类型，`area4_only_ids()`(4931) 这类判据就会和 PC 分叉。
 *
 * 键一律 **int**（PC 同款：`out[int(k)] = …`），查的时候 int/str 都试（见 [rk] / [si]）。
 */
class RelicTables private constructor(
    /** relic_rank.json → {relic_id: {rarity, mark, name, price, effect, mark_count, shop_only, start_skill, skill_up}} */
    val rank: Map<Int, JSONObject>,
    /** shop_items.json → {item_id: {name, kind, role, base_price, num}} */
    val items: Map<Int, JSONObject>,
) {

    /** PC 版 `_rk`(5526)：取遗物信息；RPC 给的 rewardId **可能是 int 也可能是 str** → 两种都试 */
    fun rk(rid: Any?): JSONObject? = lookup(rank, rid)

    /** PC 版 `_si`(5539)：取商店非遗物商品（印记「サイン」）信息 */
    fun si(rid: Any?): JSONObject? = lookup(items, rid)

    private fun lookup(m: Map<Int, JSONObject>, rid: Any?): JSONObject? {
        (rid as? Number)?.let { return m[it.toInt()] }
        val s = rid?.toString() ?: return null
        return m[s.toIntOrNull() ?: return null]
    }

    companion object {
        const val RELIC_RANK = "relic_rank.json"
        const val SHOP_ITEMS = "shop_items.json"

        /** 从"名字 → JSON 文本"的读取函数构造（app 传 assets、单测传文件） */
        fun load(reader: (String) -> String): RelicTables {
            val raw = JSONObject(reader(RELIC_RANK))
            val rank = LinkedHashMap<Int, JSONObject>(raw.length())
            val it = raw.keys()
            while (it.hasNext()) {
                val k = it.next()
                val id = k.toIntOrNull() ?: continue
                val v = raw.optJSONObject(k) ?: continue
                rank[id] = JSONObject()
                    .put("rarity", num(v, "rarity")?.toInt() ?: 0)          // int(v.get("rarity", 0))
                    .put("mark", num(v, "mark")?.toInt() ?: 0)              // int(v.get("mark", 0))
                    .put("name", v.optString("name", ""))
                    .put("price", v.opt("price") ?: JSONObject.NULL)       // 原样（可能没有）
                    .put("effect", v.opt("effect") ?: JSONObject.NULL)
                    .put("mark_count", v.opt("mark_count") ?: JSONObject.NULL)
                    .put("shop_only", jsonTruthy(v.opt("shop_only")))      // bool(...)
                    .put("start_skill", num(v, "start_skill")?.toInt() ?: 0)
                    .put("skill_up", num(v, "skill_up")?.toInt() ?: 0)
            }
            val rawItems = JSONObject(reader(SHOP_ITEMS))
            val items = LinkedHashMap<Int, JSONObject>(rawItems.length())
            val it2 = rawItems.keys()
            while (it2.hasNext()) {
                val k = it2.next()
                val id = k.toIntOrNull() ?: continue
                val v = rawItems.optJSONObject(k) ?: continue
                items[id] = JSONObject()
                    .put("name", v.optString("name", ""))
                    .put("kind", v.optString("kind", "其他").ifEmpty { "其他" })
                    .put("role", v.optString("role", ""))
                    .put("base_price", v.opt("base_price") ?: JSONObject.NULL)
                    .put("num", v.opt("num") ?: JSONObject.NULL)
            }
            return RelicTables(rank, items)
        }

        private fun num(o: JSONObject, k: String): Double? = (o.opt(k) as? Number)?.toDouble()

        private fun jsonTruthy(v: Any?): Boolean = when (v) {
            null, JSONObject.NULL -> false
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> v.isNotEmpty()
            else -> true
        }
    }
}
