package com.lmj.labyrinthauto.reroll

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 刷开局配置 + B 站账号/密码的本地持久化（SharedPreferences，明文存储，仅本机自动化自用）。 */
object RerollSettings {
    private const val PREF = "reroll"

    fun loadConfig(context: Context): RerollConfig {
        val d = RerollConfig()
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return d.copy(
            guildId = sp.getInt("guildId", d.guildId),
            difficulty = sp.getInt("difficulty", d.difficulty),
            maxCount = sp.getInt("maxCount", d.maxCount),
            eventValue = sp.getInt("eventValue", d.eventValue),
            maxValueGap = sp.getInt("maxValueGap", d.maxValueGap),
            // ★bossArea3/5 用新 key（*Sel）：旧 4 项 area3 数据作废，确保「默认全选」生效
            bossArea3 = readSet(sp, "bossArea3Sel", d.bossArea3),
            bossArea5 = readSet(sp, "bossArea5Sel", d.bossArea5),
        )
    }

    fun saveConfig(context: Context, config: RerollConfig) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit().apply {
            putInt("guildId", config.guildId)
            putInt("difficulty", config.difficulty)
            putInt("maxCount", config.maxCount)
            putInt("eventValue", config.eventValue)
            putInt("maxValueGap", config.maxValueGap)
            writeSet(this, "bossArea3Sel", config.bossArea3)
            writeSet(this, "bossArea5Sel", config.bossArea5)
            apply()
        }
    }

    /**
     * ★运行几次迷宫（2026-10-17 用户："设定好可以跑几次迷宫"）。**一局 = 刷开局 → 跑迷宫 → 正常结算退出**。
     *  1 = 老行为（跑一局就停）；**必须 ≥ 1**，0/负会在运行时被拒绝（提示「次数必须大于 0」）。
     */
    fun loadMazeRuns(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("mazeRuns", 1)

    fun saveMazeRuns(context: Context, n: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putInt("mazeRuns", n)
            .apply()
    }

    /**
     * ★状态悬浮窗开关（2026-10-17 用户）：只显示"第几局 ｜ 第几层 ｜ 当前状态"一行，不可点不交互。
     *  默认**关**（要用户自己开；开了之后每次启动助手都会自动显示）。
     */
    fun loadOverlayOn(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("overlayOn", false)

    fun saveOverlayOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("overlayOn", on)
            .apply()
    }

    // ── ★悬浮窗外观（2026-10-20 用户设计）────
    // 透明度：整数百分比 20~79（0.20~0.79）。上限 0.79 是 Android 12+ Untrusted-Touch 系统线
    //（>0.8 穿透触摸被丢弃）；下限 0.2（用户："完全消失了不如直接不开"）。
    // 可拖动：开着时悬浮窗拦截其区域内触摸（拖动调位置）⇒ 点一键黎明界/刷开局时会强制关闭。

    /** 透明度百分比（20~79，默认 79 = 原硬编码值） */
    fun loadOverlayAlpha(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("overlayAlpha", 79)
            .coerceIn(20, 79)

    fun saveOverlayAlpha(context: Context, pct: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putInt("overlayAlpha", pct.coerceIn(20, 79))
            .apply()
    }

    fun loadOverlayDraggable(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("overlayDraggable", false)

    fun saveOverlayDraggable(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("overlayDraggable", on)
            .apply()
    }

    /**
     * 拖动后的位置，存的是**窗口中心点**（cx, cy；null = 从未拖过 → 默认居中）。
     * ★为什么存中心而不是左缘（2026-10-20 用户提问：拖到最左后运行中内容变长会怎样）：
     *   窗口是 WRAP_CONTENT，运行中状态文本变长（"未运行" → "刷开局 3/5 轮"）窗口会变宽。
     *   存左缘 => 右缘伸出屏幕被裁；存中心 => 渲染时 x = cx - 宽/2（+clamp），内容变长
     *   自动保持居中、实在放不下才贴边（用户预期的"往另一边移动"）。
     */
    fun loadOverlayPos(context: Context): Pair<Int, Int>? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!p.contains("overlayX")) return null
        return p.getInt("overlayX", 0) to p.getInt("overlayY", 96)
    }

    /** @param cx/cy **窗口中心点**（OverlayWindow 把左缘换算后持久化） */
    fun saveOverlayPos(context: Context, cx: Int, cy: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putInt("overlayX", cx)
            .putInt("overlayY", cy)
            .apply()
    }

    /**
     * ★战斗失败策略（2026-10-18 用户）：同一节点连续失败 3 次后怎么处理。
     *  - [FAIL_STOP]    中断运行（旧行为，默认）
     *  - [FAIL_RETREAT] 撤退重跑（调 `labyrinth/retire`，本局作废、自动开下一局）
     *  - [FAIL_WAIT]    等待接手（回编队页、什么都不做，等用户自己组队/开战）
     */
    const val FAIL_STOP = 0
    const val FAIL_RETREAT = 1
    const val FAIL_WAIT = 2

    fun loadFailStrategy(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("failStrategy", FAIL_STOP)

    /**
     * ★刷开局方式（2026-10-18 用户）：0 = 接口 enter/retire（快、要账号密码）；1 = 局内刷（慢、不用账号密码）。
     */
    const val REROLL_API = 0
    const val REROLL_IN_GAME = 1

    fun loadRerollMode(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("rerollMode", REROLL_API)

    fun saveRerollMode(context: Context, v: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("rerollMode", v).apply()
    }

    fun saveFailStrategy(context: Context, v: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putInt("failStrategy", v)
            .apply()
    }

    /**
     * ★界面配色（2026-10-17 用户："深色 + 浅色都可切换"）。
     * ★默认**浅色**（2026-10-18 用户："刚装应用的时候，界面初始改为浅色"）——没手动切过就是浅色。
     */
    fun loadDarkOn(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("darkOn", false)

    fun saveDarkOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("darkOn", on)
            .apply()
    }

    /**
     * ★商店是否购买 2★遗物（2026-10-17 用户："允许购买2星遗物"开关）。
     * 默认开启，与 PC `SHOP_BUY_2STAR` 代码默认值一致。
     */
    fun loadShopBuy2Star(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("shopBuy2Star", true)

    fun saveShopBuy2Star(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("shopBuy2Star", on)
            .apply()
    }

    /**
     * ★商店是否启用 MDP 最优停止算法接管 5 层刷新/3★ 决策（2026-10-18 接入）。
     * 默认开启；出问题改 false 即可回退到 PC 旧逻辑（q1 + refresh_above）。
     */
    fun loadShopUseMdp(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("shopUseMdp", true)

    fun loadCredentials(context: Context): Pair<String, String> {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return (sp.getString("account", "") ?: "") to (sp.getString("password", "") ?: "")
    }

    fun saveCredentials(context: Context, account: String, password: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString("account", account)
            .putString("password", password)
            .apply()
    }

    /**
     * ★首次使用指引是否已展示过（2026-10-17 用户："第一次运行自动弹出说明，之后右上角可再看"）。
     * 默认 false ⇒ 首次进首页会自动弹；关掉后写 true，不再自动弹（但右上角「说明」仍可手动开）。
     */
    fun loadGuideShown(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("guideShown", false)

    fun saveGuideShown(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("guideShown", true)
            .apply()
    }

    private fun readSet(sp: SharedPreferences, key: String, default: Set<Int>): Set<Int> {
        val raw = sp.getString(key, null) ?: return default
        return raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    private fun writeSet(editor: SharedPreferences.Editor, key: String, set: Set<Int>) {
        editor.putString(key, set.joinToString(","))
    }

    /**
     * ★黎明界开局「自选角色」：迷宫公会 id(1~5) → 选中的 unitId 列表（空=用花名册默认 trio）。
     * 存成一份 JSON（"1":[...],"2":[...]）；读不到/解析失败 → 空 map（走默认）。
     */
    fun loadGuildPicks(context: Context): Map<Int, List<Int>> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("guildPicks", null)
            ?: return emptyMap()
        return try {
            val jo = JSONObject(raw)
            val m = LinkedHashMap<Int, List<Int>>()
            val kit = jo.keys()
            while (kit.hasNext()) {
                val k = kit.next().toIntOrNull() ?: continue
                val arr = jo.optJSONArray(k.toString()) ?: continue
                val l = ArrayList<Int>()
                for (i in 0 until arr.length()) (arr.opt(i) as? Number)?.toInt()?.let { l.add(it) }
                m[k] = l
            }
            m
        } catch (_: Throwable) { emptyMap() }
    }

    fun saveGuildPicks(context: Context, picks: Map<Int, List<Int>>) {
        val jo = JSONObject()
        picks.forEach { (k, v) -> jo.put(k.toString(), JSONArray(v)) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("guildPicks", jo.toString()).apply()
    }
}
