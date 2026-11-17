package com.lmj.labyrinthauto.reroll

import android.content.Context
import android.content.SharedPreferences
import com.lmj.labyrinthauto.core.SecureStore
import com.lmj.labyrinthauto.logic.EventRetreatRule
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * 刷开局配置 + B 站账号/密码的本地持久化。
 *
 * 配置项是明文 SharedPreferences（不是秘密）；**账密走 [SecureStore] 加密**（2026-10-30）。
 */
object RerollSettings {
    private const val PREF = "reroll"

    /**
     * ★黎明界「初始公会」id 范围（2026-11-13 维护新增 5 家）。
     * ★已用维护后的 master 库 `labyrinth_enter_guild` 校准：`guild_id` 1..10，
     *   新增 5 家确为 6..10，顺序同公告（原先按公告顺序的猜测成立）。
     * UI 选项 / 局数持久化 / 运行计划 / 校验都引用这里。
     */
    const val GUILD_MAX = 10
    val GUILD_IDS: IntRange = 1..GUILD_MAX

    /** 2026-11-13 维护新增的公会从 id 6 开始（6..10）；维护结束前一律不可用。 */
    const val NEW_GUILD_FROM = 6

    /**
     * ★新公会开放时刻，按 **UTC+8** 定义（换算成绝对 epoch 毫秒再比较，
     *   所以设备改时区 / 换地区都不影响判定）。
     * 维护结束时刻由用户确认（2026-11-09）：**2026-11-13 14:00 (UTC+8)** = 当天 06:00 UTC。
     * ★要改时刻只动这一处；设 `Long.MAX_VALUE` = 永不自动开放。
     */
    private val NEW_GUILD_OPEN_MILLIS: Long =
        Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00")).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 13, 14, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** 是否已到开放时刻（now = UTC 纪元毫秒） */
    fun newGuildsEnabled(now: Long = System.currentTimeMillis()): Boolean =
        now >= NEW_GUILD_OPEN_MILLIS

    /** 单个公会当前能否用：老的 1..5 一直可用，6..10 到点才可用 */
    fun guildEnabled(id: Int, now: Long = System.currentTimeMillis()): Boolean =
        id < NEW_GUILD_FROM || newGuildsEnabled(now)

    /** 当前可用的公会 id（UI / 运行计划 / 校验共用，避免各处各写一套） */
    fun enabledGuildIds(now: Long = System.currentTimeMillis()): List<Int> =
        GUILD_IDS.filter { guildEnabled(it, now) }

    private const val KEY_ACCOUNT = "account"
    private const val KEY_PASSWORD = "password"

    /** 加密 AAD：密文绑死"这个文件这个字段"，不能挪到别处复用 */
    private const val AAD_ACCOUNT = "$PREF:$KEY_ACCOUNT"
    private const val AAD_PASSWORD = "$PREF:$KEY_PASSWORD"

    fun loadConfig(context: Context): RerollConfig {
        val d = RerollConfig()
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return d.copy(
            guildId = sp.getInt("guildId", d.guildId),
            difficulty = sp.getInt("difficulty", d.difficulty),
            // ★「最大次数」这一项 UI 已删（用户 2026-11-03）⇒ 一律不限；
            //   旧 prefs 里残留的 30/50/100 不再生效（否则用户会被一个看不见的数字卡住）
            maxCount = -1,
            eventValue = sp.getInt("eventValue", d.eventValue),
            maxValueGap = sp.getInt("maxValueGap", d.maxValueGap),
            // ★bossArea3/5 用新 key（*Sel）：旧 4 项 area3 数据作废，确保「默认全选」生效
            bossArea3 = readSet(sp, "bossArea3Sel", d.bossArea3),
            bossArea5 = readSet(sp, "bossArea5Sel", d.bossArea5),
            // ★判定模式（2026-11-03）：0 经典（分数价值）/ 1 自定义（节点序列）
            judgeMode = sp.getInt("judgeMode", d.judgeMode),
            areaPlan = sp.getString("areaPlan", d.areaPlan) ?: d.areaPlan,
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
            putInt("judgeMode", config.judgeMode)
            putString("areaPlan", config.areaPlan)
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
     * ★**「选择多项」模式**（2026-11-08 用户需求）：按**初始公会**分别设局数。
     *
     * 开启后：「运行次数」输入框换成 5 个（每个初始公会一个次数），点「一键黎明界」会
     * **按公会依次执行**；悬浮窗仍显示 `第 X/总局数 局`。
     *
     * ★为什么实现很简单：换公会 = 改**刷开局的「初始公会」**（`RerollConfig.guildId`）。
     *   每次刷开局都会 `loadConfig()` 现读（`RerollUseCase` / `InGameReroll`），
     *   所以只要能"每局开跑前把 guildId 改成这一局该跑的那家"就够了，不涉及游戏内 UI 自动化。
     */
    fun loadMultiGuild(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("mazeMultiGuild", false)

    fun saveMultiGuild(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("mazeMultiGuild", on)
            .apply()
    }

    /**
     * 各初始公会的局数：**下标 = 公会 id（1..[GUILD_MAX]）**，`[0]` 恒 0 占位；长度 GUILD_MAX+1。
     * 全 0 = 没配（此时「一键黎明界」应当被拦住）。
     * ★旧版只存 6 段（0..5）：短字符串照样能读，新增的 6..10 默认 0 ⇒ 老配置不丢。
     */
    fun loadGuildRuns(context: Context): IntArray {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("mazeGuildRuns", null) ?: return IntArray(GUILD_MAX + 1)
        val out = IntArray(GUILD_MAX + 1)
        raw.split(",").forEachIndexed { i, s ->
            if (i in 0..GUILD_MAX) out[i] = (s.trim().toIntOrNull() ?: 0).coerceAtLeast(0).coerceAtMost(999)
        }
        return out
    }

    fun saveGuildRuns(context: Context, runs: IntArray) {
        val txt = (0..GUILD_MAX).joinToString(",") { runs.getOrElse(it) { 0 }.coerceAtLeast(0).toString() }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString("mazeGuildRuns", txt)
            .apply()
    }

    /** 多公会模式下这条计划一共有几局；**未开放公会填的局数不计入**，
     *  否则会出现「按钮能点、实际一局都不跑」。 */
    fun multiGuildTotal(runs: IntArray, now: Long = System.currentTimeMillis()): Int =
        enabledGuildIds(now).sumOf { runs.getOrElse(it) { 0 } }

    /** 初始公会的显示名（日志用；与 `REROLL_GUILDS` 一一对应） */
    fun guildName(id: Int): String = when (id) {
        1 -> "美食殿堂"
        2 -> "破晓之星"
        3 -> "咲恋救济院"
        4 -> "王宫骑士团"
        5 -> "拉比林斯"
        6 -> "慈乐之音"
        7 -> "恶魔伪王国军"
        8 -> "伊丽莎白牧场"
        9 -> "墨丘利财团"
        10 -> "暮光流星群"
        else -> "公会$id"
    }

    /**
     * ★状态悬浮窗开关（2026-10-17 用户）：只显示"第几局 ｜ 第几层 ｜ 当前状态"一行，不可点不交互。
     *  ★2026-11-10（用户："授权完成后还要自己手动打开"）：默认改为**开**。
     *    没授权时首页只显示「去授权」按钮；授权回来后 MainActivity 的 onResume / 首页的
     *    LaunchedEffect 会自动把悬浮窗显示出来、开关也直接是开着的，不用再拨一次。
     *    用户手动关过（写过 overlayOn=false）就一直尊重那个选择，不会被自动重开。
     */
    fun loadOverlayOn(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("overlayOn", true)

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

    // ★「允许购买2星遗物」开关已删（2026-11-02 用户）：3/5 层由精确 DP 全权决定，开关不再起作用。
    //   旧的 pref 键 `shopBuy2Star` 留在设备上也不影响（不再有人读）。

    /**
     * ★商店是否启用 MDP 最优停止算法接管 5 层刷新/3★ 决策（2026-10-18 接入）。
     * ⚠仅在精确 DP 不可用（`shopUseDp=false` 或曲线读不到）时才生效；DP 优先。
     * 默认开启；出问题改 false 即可回退到 PC 旧逻辑（q1 + refresh_above）。
     */
    fun loadShopUseMdp(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("shopUseMdp", true)

    /**
     * ★商店是否启用「精确 DP」（[com.lmj.labyrinthauto.logic.shop.ShopDp]，2026-11-02 接入）：
     * 3 层(b=3) / 5 层(b=2) 的「买几件 + 买完刷不刷」改由离线 G/H 曲线 + 运行时子集枚举决定；
     * 4 层不动。默认开启；置 false 即回退 PC 旧逻辑（q1 + refresh_above + 阈值表 + 5 层 MDP）。
     */
    fun loadShopUseDp(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("shopUseDp", true)

    // ───────────────── ★凹分模式（2026-11-01 用户需求）─────────────────
    // 目的：监测最终分数，主动放弃那些分数不够的局。
    // 检测点只有一处 —— **5 层 Boss 之前的商店（节点 50701）购物之后**：5 层 Boss 本身分数固定，
    // 过了这一关分数基本就定了，没必要等打完再看。
    // 阈值 = 基础值 × 难度倍率（难度 1~5 → 1.0 / 1.2 / 1.4 / 1.7 / 2.0）；存的是**基础值**，
    // 界面显示时再乘倍率，这样换难度不用重调滑块。

    /** 基础阈值区间与步进（界面上按这个给滑块） */
    const val SCORE_BASE_MIN = 23000
    const val SCORE_BASE_MAX = 27000
    const val SCORE_BASE_STEP = 100

    private val DIFF_MULT = floatArrayOf(1f, 1.2f, 1.4f, 1.7f, 2f)

    /** 难度倍率：1..5 → 1.0 / 1.2 / 1.4 / 1.7 / 2.0 */
    fun diffMultiplier(diff: Int): Float = DIFF_MULT[(diff - 1).coerceIn(0, DIFF_MULT.size - 1)]

    /** 实际撤退阈值 = 基础值 × 难度倍率。
     *  ★必须**四舍五入**：1.4f/1.7f 这类倍率是二进制近似值（1.4f*23000 = 32199.999…），
     *  直接 `toInt()` 会少 1 分，界面和判定都会显示 32,199。 */
    fun scoreThreshold(diff: Int, base: Int): Int = Math.round(base * diffMultiplier(diff))

    /** 5 层结算里**与角色/遗物无关**的固定基础分：踏破 5600 + 普通 600 + 极难 6000 + 首领 3000 */
    const val LAB_FIXED_BASE_SCORE = 15_200
    const val SCORE_PER_UNIT = 100
    const val SCORE_PER_RELIC = 200

    /**
     * 本局**基础分**（不含难度倍率）= 15200 + 100×角色数 + 200×遗物数 —— 凹分检测用。
     *
     * 校准：51 角色 + 25 遗物 → 15200 + 5100 + 5000 = 25300（难度 5 ×2.0 = 50,600，与结算页截图一致）。
     * ★运行时**不乘倍率**：滑块存的就是基础分，两边同量纲直接比；乘完的数是给用户看的。
     */
    fun scoreBaseOf(unitCount: Int, relicCount: Int): Int =
        LAB_FIXED_BASE_SCORE + SCORE_PER_UNIT * unitCount + SCORE_PER_RELIC * relicCount

    /** 总开关。默认关：关掉时策略页里连下面的子项都不显示（同「状态悬浮窗」的做法）。 */
    fun loadScoreChaseOn(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("scoreChaseOn", false)

    fun saveScoreChaseOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("scoreChaseOn", on)
            .apply()
    }

    /** 子条件 1：分数不足自动撤退。默认开（总开关关着时它没意义）。 */
    fun loadScoreChaseRetreatOn(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("scoreChaseRetreat", true)

    fun saveScoreChaseRetreatOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("scoreChaseRetreat", on)
            .apply()
    }

    // 难度**不在这里存**：凹分用的就是「刷开局」页选的难度（[loadConfig] 的 difficulty），
    // 两处各存一份只会互相打架（2026-11-01 用户）。

    /** 子条件 1 的基础阈值（23000~27000，100 一档），默认 25000。存基础值，显示时乘倍率。 */
    fun loadScoreChaseBase(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt("scoreChaseBase", 25000)
            .coerceIn(SCORE_BASE_MIN, SCORE_BASE_MAX) / SCORE_BASE_STEP * SCORE_BASE_STEP

    fun saveScoreChaseBase(context: Context, base: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putInt("scoreChaseBase", base.coerceIn(SCORE_BASE_MIN, SCORE_BASE_MAX) / SCORE_BASE_STEP * SCORE_BASE_STEP)
            .apply()
    }

    // ── 子条件 2：按「遇到的事件 + 结果」判撤退（2026-11-03 用户需求，见 [EventRetreatRule]）──
    //   模式下拉框：无 / 断言模式 / 逻辑模式（逻辑模式暂未实现，选了也不判定）。
    //   断言列表整份存成一段 JSON —— 条数不定（上限 30），拆成 N 个 key 反而难维护。

    /** 条件 2 的模式，默认**无**（不改变现有行为）。 */
    fun loadScoreChaseMode2(context: Context): EventRetreatRule.Mode =
        EventRetreatRule.Mode.of(
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("scoreChaseMode2", null),
        )

    fun saveScoreChaseMode2(context: Context, mode: EventRetreatRule.Mode) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString("scoreChaseMode2", mode.key)
            .apply()
    }

    /**
     * 条件列表（没配过 → 空表）。整份存成一段 JSON：条数不定（上限 [EventRetreatRule.MAX_CONDS]）。
     *
     * ★**断言 / 逻辑两种模式各存一份**（用户 2026-11-03："各自有一个本地保存"）：
     *   切模式只是换一份配置来看，互不覆盖；"保存"和"生不生效"是两件事
     *   （逻辑模式暂时不判定，但配置照常能编能存）。
     */
    fun loadScoreChaseConds(
        context: Context,
        mode: EventRetreatRule.Mode = EventRetreatRule.Mode.ASSERT,
    ): List<EventRetreatRule.Cond> =
        EventRetreatRule.decode(
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(keyOfConds(mode), null),
        )

    fun saveScoreChaseConds(
        context: Context,
        conds: List<EventRetreatRule.Cond>,
        mode: EventRetreatRule.Mode = EventRetreatRule.Mode.ASSERT,
    ) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(keyOfConds(mode), EventRetreatRule.encode(conds))
            .apply()
    }

    /** 两种模式各自的 prefs key（NONE 沿用断言那份，方便"关掉再打开"看到原配置） */
    private fun keyOfConds(mode: EventRetreatRule.Mode): String = when (mode) {
        EventRetreatRule.Mode.LOGIC -> "scoreChaseRulesLogic"
        else -> "scoreChaseRules"
    }

    // ────────────────────────── 点击探测间隔（2026-11-17 用户）──────────────────────────
    /**
     * ★tapAndCheck「点完探页面变了没」的间隔挡位，默认 150ms。
     *  · 只作用于**用默认 probeMs 的调用点**（商店关框 / 关闭收到 / 三选一 / 遗物 / 战斗开始 /
     *    EX 装备 / 结算下一步 / 预览开打 / 局内刷开局 / 导航）；
     *  · 屏幕中心慢页 = 本值 ×2（见 Navigator）；
     *  · 各调用的 settleMs（点完第一次探之前的等待）**不受影响**（那些值是逐个坑调出来的）。
     */
    val TAP_PROBE_STEPS: List<Int> = listOf(150, 200, 250, 300, 350)
    const val TAP_PROBE_DEFAULT = 150

    fun loadTapProbeMs(context: Context): Int {
        val v = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt("tapProbeMs", TAP_PROBE_DEFAULT)
        return if (v in TAP_PROBE_STEPS) v else TAP_PROBE_DEFAULT
    }

    fun saveTapProbeMs(context: Context, ms: Int) {
        val v = if (ms in TAP_PROBE_STEPS) ms else TAP_PROBE_DEFAULT
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt("tapProbeMs", v).apply()
    }

    fun loadCredentials(context: Context): Pair<String, String> {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        // 老版本的明文在这里会被 SecureStore 原地升级成密文
        return (SecureStore.get(sp, KEY_ACCOUNT, AAD_ACCOUNT) ?: "") to
            (SecureStore.get(sp, KEY_PASSWORD, AAD_PASSWORD) ?: "")
    }

    fun saveCredentials(context: Context, account: String, password: String) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        SecureStore.put(sp, KEY_ACCOUNT, account, AAD_ACCOUNT)
        SecureStore.put(sp, KEY_PASSWORD, password, AAD_PASSWORD)
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
     * ★黎明界开局「自选角色」：迷宫公会 id → 选中的 unitId 列表（空=用花名册默认 trio）。
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

    // ────────────────────────── 角色选择（2026-11-13 用户：策略页新增折叠卡）──────────────────────────

    /** 五属性的全部取值（也是属性印记顺序的默认值：暗>火>光>风>水，与旧写死顺序一致）。 */
    val ATTR_ALL: List<String> = listOf("暗", "火", "光", "风", "水")

    /**
     * ★属性印记选择顺序：门票（印记）节点三选一按这个顺序取第一张属性匹配的卡。
     * 存逗号串；读取时只保留合法属性并补齐缺失项 —— 旧数据/坏数据不能让某个属性凭空消失。
     */
    fun loadAttrOrder(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("attrOrder", null)
            ?: return ATTR_ALL
        val out = LinkedHashSet<String>()
        for (x in raw.split(',')) {
            val a = x.trim()
            if (a in ATTR_ALL) out.add(a)
        }
        out.addAll(ATTR_ALL)
        return out.toList()
    }

    fun saveAttrOrder(context: Context, order: List<String>) {
        val clean = LinkedHashSet<String>()
        for (a in order) if (a in ATTR_ALL) clean.add(a)
        clean.addAll(ATTR_ALL)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("attrOrder", clean.joinToString(",")).apply()
    }

    /** ★角色选择页面与组队时不使用 NPC 角色（2026-11-13 用户），默认 false。 */
    fun loadExcludeNpc(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("excludeNpc", false)

    fun saveExcludeNpc(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("excludeNpc", on).apply()
    }

    /** 编队方法：旧版规则 */
    const val TEAM_RULE = "rule"
    /** 编队方法：推理模型 */
    const val TEAM_MODEL = "model"
    /** 编队方法：**混合** —— 第一次用模型，打不过就切旧版规则（2026-11-15 用户） */
    const val TEAM_MIXED = "mixed"

    /**
     * ★编队方法（2026-11-15 用户）：三选一，**默认混合**。
     *
     * 混合 = 本节点**第一次**打用模型（模型可能更强），失败过就用旧版规则（规则版会按关卡档补坦克，
     * 在"知道打不过"之后更稳）。理由：模型不一定比原来的策略好，别一条道走到黑。
     *
     * 不管哪种模式，模型算不出（未加载 / 关卡不在词表 / 角色不足 5 人）都会静默回落规则版。
     *
     * ★2026-11-16（用户）：**默认改回「旧版规则」** —— 模型不一定比规则好，先按规则稳定跑。
     *   这里只动**默认值**：用户自己已经选过（SharedPreferences 里有值）的照旧读取，不受影响。
     */
    fun loadTeamMode(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("teamMode", TEAM_RULE)
            ?.takeIf { it == TEAM_RULE || it == TEAM_MODEL || it == TEAM_MIXED }
            ?: TEAM_RULE

    fun saveTeamMode(context: Context, mode: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("teamMode", mode).apply()
    }

    /**
     * ★战后角色三选一方法（2026-11-14 用户）：true = 推理模型，false = 旧版规则。
     * ★2026-11-16（用户）：**默认改回「旧版规则」**（原来默认模型）。只动默认值，
     *   用户已经选过的（SharedPreferences 里有值）照旧。
     */
    fun loadPickByModel(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("pickByModel", false)

    fun savePickByModel(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("pickByModel", on).apply()
    }

    /** 3 区首领编几队（模型设置；默认 1，失败过强制 3）。 */
    fun loadBossPartyCount(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("bossPartyCount", 1)

    fun saveBossPartyCount(context: Context, n: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt("bossPartyCount", n.coerceIn(1, 3)).apply()
    }

    // ────────────────────────── 三选一的「目标」配置（2026-11-14 用户）──────────────────────────

    /**
     * 一个三选一目标：权重 + 生效区间。
     *
     * ★权重为 0 表示不参与；★**每个非 0 项都会增加计算时间**（每项 = 1 次基线 + 3 个候选）。
     *
     * 条件语义（用户 2026-11-14 定义，按节点 blockId 判断，5 位数字）：
     *  · `ANY`            始终适用
     *  · `BEFORE_2`       2 图前（不含 2 图）⇒ blockId < 20000
     *  · `AFTER_2`        2 图后（含 2 图）   ⇒ blockId >= 20000
     *  · `BEFORE_3`       3 图前（不含 3 图） ⇒ blockId < 30000（= 20601 及之前）
     *  · `AFTER_3`        3 图后（含 3 图）   ⇒ blockId >= 30000（= 30101 及之后）
     *  · `BEFORE_3_BOSS`  3 图首领前          ⇒ blockId < 30701
     *  · `AFTER_3_BOSS`   3 图首领后（含）    ⇒ blockId >= 30701
     *
     * 例：某项权重 3、条件「3 图前」，那么到了 3 图及之后这一项按 0 处理。
     */
    data class PickTargetCfg(
        val id: String,
        val label: String,
        val weight: Double,
        val cond: String,
    )

    /** 条件枚举（顺序 = UI 下拉顺序） */
    val PICK_CONDS: List<Pair<String, String>> = listOf(
        "ANY" to "通用",
        "BEFORE_2" to "2图前",
        "AFTER_2" to "2图后",
        "BEFORE_3" to "3图前",
        "AFTER_3" to "3图后",
        "BEFORE_3_BOSS" to "3图boss前",
        "AFTER_3_BOSS" to "3图boss后",
    )

    /**
     * 默认值（对齐决策系统 `unit_choice_targets`）：
     * 2 个首领 + **ex_weights 里的全部 14 项**（含 PC 上权重为 0 的那 10 项，方便按需开启）。
     */
    val PICK_TARGET_DEFAULTS: List<PickTargetCfg> = listOf(
        PickTargetCfg("boss3", "3 层首领", 3.0, "BEFORE_3_BOSS"),
        PickTargetCfg("boss5", "5 层首领", 2.0, "AFTER_3_BOSS"),
        // 名字取自 game_catalog.json 的 `recruitment.ex_quests`（原样照抄，和 PC 界面一致）
        PickTargetCfg("ex_770120101", "幽灵领主", 2.0, "BEFORE_3_BOSS"),
        PickTargetCfg("ex_770120201", "好朋友X", 4.0, "BEFORE_3_BOSS"),
        PickTargetCfg("ex_770120301", "岛鲸", 0.0, "ANY"),
        PickTargetCfg("ex_770120401", "反物质兽", 0.0, "ANY"),
        PickTargetCfg("ex_770120501", "巨大吞拿鱼", 0.0, "ANY"),
        PickTargetCfg("ex_770120601", "熔岩龙", 0.0, "ANY"),
        PickTargetCfg("ex_770120701", "爆炸·遗物", 0.0, "ANY"),
        PickTargetCfg("ex_770120801", "破坏·遗物", 0.0, "ANY"),
        PickTargetCfg("ex_770120901", "护盾·遗物", 0.0, "ANY"),
        PickTargetCfg("ex_770121001", "治愈·遗物", 0.0, "ANY"),
        PickTargetCfg("ex_770121101", "攻击·遗物", 0.0, "ANY"),
        PickTargetCfg("ex_770121201", "诅咒·遗物", 0.0, "ANY"),
        PickTargetCfg("ex_770421301", "黄蜂女王", 3.0, "AFTER_3_BOSS"),
        PickTargetCfg("ex_770421401", "美空", 3.0, "AFTER_3_BOSS"),
    )

    fun loadPickTargets(context: Context): List<PickTargetCfg> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("pickTargets", null) ?: return PICK_TARGET_DEFAULTS
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<PickTargetCfg>(PICK_TARGET_DEFAULTS.size)
            for (d in PICK_TARGET_DEFAULTS) {
                val o = (0 until arr.length()).map { arr.optJSONObject(it) }
                    .firstOrNull { it?.optString("id") == d.id }
                out.add(
                    if (o == null) d
                    else d.copy(
                        weight = o.optDouble("w", d.weight).coerceIn(0.0, 10.0),
                        cond = o.optString("c").takeIf { c -> PICK_CONDS.any { it.first == c } } ?: d.cond,
                    ),
                )
            }
            out
        } catch (e: Exception) {
            PICK_TARGET_DEFAULTS
        }
    }

    fun savePickTargets(context: Context, list: List<PickTargetCfg>) {
        val arr = JSONArray()
        for (t in list) {
            arr.put(JSONObject().put("id", t.id).put("w", t.weight).put("c", t.cond))
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("pickTargets", arr.toString()).apply()
    }

    /** 条件是否命中（`blockId` 是 5 位节点编号，如 20601 / 30701） */
    fun condMatch(cond: String, blockId: Int): Boolean = when (cond) {
        "BEFORE_2" -> blockId < 20000
        "AFTER_2" -> blockId >= 20000
        "BEFORE_3" -> blockId < 30000
        "AFTER_3" -> blockId >= 30000
        "BEFORE_3_BOSS" -> blockId < 30701
        "AFTER_3_BOSS" -> blockId >= 30701
        else -> true          // ANY
    }
}
