package com.lmj.labyrinthauto.data

import android.content.Context
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.logic.shop.ShopDp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 运行时的游戏数据（assets 里的表）。
 *
 * 为什么要有这一层：单测里 `Tables.load` 直接读文件，app 运行时得从 assets 读 ——
 * 这里就是那个"从 assets 读 + 惰性缓存一次"的入口（表随 APK 走，运行中不会变）。
 */
object GameData {

    @Volatile
    private var tables: Tables? = null

    fun tables(ctx: Context): Tables = tables ?: synchronized(this) {
        tables ?: Tables.load { name ->
            ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.also { tables = it }
    }

    @Volatile
    private var guildPool: GuildPoolData? = null

    @Volatile
    private var relicTables: RelicTables? = null

    /** 遗物 / 商店数据表（`relic_rank.json` / `shop_items.json`），惰性缓存一次 */
    fun relicTables(ctx: Context): RelicTables = relicTables ?: synchronized(this) {
        relicTables ?: RelicTables.load { name ->
            ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.also { relicTables = it }
    }

    @Volatile
    private var shopDp: Map<Int, ShopDp.Set>? = null

    /**
     * ★商店精确 DP 的 G/H 曲线（`shop_curves.json`，bCount → 曲线；约 54KB）。
     * 读不到就返回空 Map（[ShopPlan] 会判定为「未加载」→ 自动回退 PC 旧逻辑，不影响开局）。
     */
    fun shopDp(ctx: Context): Map<Int, ShopDp.Set> = shopDp ?: synchronized(this) {
        shopDp ?: run {
            val m = try {
                ShopDp.load { name -> ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() } }
            } catch (t: Throwable) {
                AppState.addLog("读不到 shop_curves.json：${t.message} → 商店回退 PC 旧逻辑")
                emptyMap()
            }
            m.also { shopDp = it }
        }
    }

    @Volatile
    private var rewardNames: RewardNamesData? = null

    /** ★结算奖励 id → 中文名（`lab_reward_names.json`，从中文主数据 item_data / ex_equipment_data 生成） */
    fun rewardNames(ctx: Context): RewardNamesData = rewardNames ?: synchronized(this) {
        rewardNames ?: run {
            val items = LinkedHashMap<String, String>()
            val ex = LinkedHashMap<String, String>()
            try {
                val d = JSONObject(
                    ctx.assets.open("lab_reward_names.json").bufferedReader(Charsets.UTF_8).use { it.readText() },
                )
                d.optJSONObject("items")?.let { o ->
                    val k = o.keys()
                    while (k.hasNext()) {
                        val kk = k.next()
                        items[kk] = o.optString(kk)
                    }
                }
                d.optJSONObject("exEquip")?.let { o ->
                    val k = o.keys()
                    while (k.hasNext()) {
                        val kk = k.next()
                        ex[kk] = o.optString(kk)
                    }
                }
            } catch (t: Throwable) {
                AppState.addLog("读不到 lab_reward_names.json：${t.message} → 奖励只显示 id")
            }
            RewardNamesData(items, ex).also { rewardNames = it }
        }
    }

    /**
     * 黎明界「开局自选角色」花名册：`lab_guild_pool.json`（按迷宫公会 id 1~5 主键，含剧情公会全套角色
     * [char_id]、各角色可选形态、默认 trio、已排除的 FES/公主 形态）。
     * 识别公会 = 屏上候选 `cands` 的角色(char_id = unit_id/100) 是否全部落在本公会花名册内
     * （各公会角色互不相交 ⇒ 唯一命中）。
     */
    fun guildPool(ctx: Context): GuildPoolData = guildPool ?: synchronized(this) {
        guildPool ?: run {
            val d = try {
                JSONObject(
                    ctx.assets.open("lab_guild_pool.json").bufferedReader(Charsets.UTF_8).use { it.readText() },
                )
            } catch (t: Throwable) {
                AppState.addLog("读不到 lab_guild_pool.json：${t.message} → 开局选人退化为只读观测")
                guildPool = GuildPoolData(emptyMap())
                return GuildPoolData(emptyMap())
            }
            val gs = d.optJSONObject("guilds") ?: JSONObject()
            val out = LinkedHashMap<Int, GuildPool>()
            val it = gs.keys()
            while (it.hasNext()) {
                val k = it.next()
                val labId = k.toIntOrNull() ?: continue
                val g = gs.optJSONObject(k) ?: continue
                val charIds = g.optJSONArray("char_ids")?.toIntSet() ?: emptySet()
                val selIds = g.optJSONArray("selectable_ids")?.toIntSet() ?: emptySet()
                val def = g.optJSONArray("default_picks")?.toIntList() ?: emptyList()
                val excl = g.optJSONArray("excluded_fes")?.toFormList() ?: emptyList()
                val poolArr = g.optJSONArray("pool") ?: JSONArray()
                val pool = ArrayList<PoolChar>()
                for (i in 0 until poolArr.length()) {
                    val c = poolArr.optJSONObject(i) ?: continue
                    val forms = c.optJSONArray("forms")?.toFormList() ?: emptyList()
                    pool.add(
                        PoolChar(
                            charCn = c.optString("char_cn"),
                            charIds = c.optJSONArray("char_ids")?.toIntList() ?: emptyList(),
                            best = c.optInt("best"),
                            forms = forms,
                        ),
                    )
                }
                out[labId] = GuildPool(
                    labId = labId, cn = g.optString("cn"), storyGuildId = g.optInt("story_guild_id"),
                    jp = g.optString("jp"), charIds = charIds, selectableIds = selIds,
                    defaultPicks = def, pool = pool, excludedFes = excl,
                )
            }
            val data = GuildPoolData(out)
            guildPool = data
            data
        }
    }

    private fun JSONArray.toIntSet(): Set<Int> {
        val s = LinkedHashSet<Int>()
        for (i in 0 until length()) (opt(i) as? Number)?.toInt()?.let { s.add(it) }
        return s
    }

    private fun JSONArray.toIntList(): List<Int> {
        val l = ArrayList<Int>()
        for (i in 0 until length()) (opt(i) as? Number)?.toInt()?.let { l.add(it) }
        return l
    }

    private fun JSONArray.toFormList(): List<PoolForm> {
        val l = ArrayList<PoolForm>()
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            l.add(PoolForm(o.optInt("unit_id"), o.optString("cn").ifEmpty { o.optString("en") },
                o.optString("en"), o.optInt("rarity"), o.optInt("is_limited"),
                o.optString("source")))
        }
        return l
    }
}

/** 花名册里的一个可选形态 */
data class PoolForm(
    val unitId: Int, val cn: String, val en: String,
    val rarity: Int, val isLimited: Int,
    /** 来源：初始 / 常驻 / 限定 / 公主限定 / 联动 */
    val source: String = "",
)

/** 花名册里的一个角色（可能含多个形态；charIds 为其各形态的 character_id） */
data class PoolChar(
    val charCn: String, val charIds: List<Int>, val best: Int, val forms: List<PoolForm>,
)

/** 一个迷宫公会的可选花名册 */
data class GuildPool(
    val labId: Int, val cn: String, val storyGuildId: Int, val jp: String,
    val charIds: Set<Int>, val selectableIds: Set<Int>,
    val defaultPicks: List<Int>, val pool: List<PoolChar>, val excludedFes: List<PoolForm>,
)

/** 全部迷宫公会花名册（按迷宫公会 id 1~5 主键） */
data class GuildPoolData(val guilds: Map<Int, GuildPool>) {
    /** 由屏上候选 unitIds 识别当前公会（各公会角色互不相交 ⇒ 候选角色全落在一家里即唯一） */
    fun identify(cands: List<Int>): Int? {
        val candChars = cands.map { it / 100 }.toSet()
        if (candChars.isEmpty()) return null
        return guilds.values.firstOrNull { g -> candChars.all { c -> c in g.charIds } }?.labId
    }

    /**
     * 给定公会 + 用户配置（unitId 列表，空=用默认 trio），返回**应点击的精确 unitId**（跳过不在本公会
     * 花名册内的误配项；不按角色去重 —— 全公会任选 3 个单位，同一角色的不同形态可各自被选）。
     */
    fun desiredUnitIds(labId: Int, picks: List<Int>): List<Int> {
        val g = guilds[labId] ?: return emptyList()
        val chosen = if (picks.isNotEmpty()) picks else g.defaultPicks
        val out = ArrayList<Int>()
        for (uid in chosen) {
            if (g.pool.any { it.forms.any { f -> f.unitId == uid } }) out.add(uid)
        }
        return out
    }
}

/** 结算奖励 id → 中文名：道具(item_data) / EX装备(ex_equipment_data) */
data class RewardNamesData(
    val items: Map<String, String>,
    val exEquip: Map<String, String>,
)
