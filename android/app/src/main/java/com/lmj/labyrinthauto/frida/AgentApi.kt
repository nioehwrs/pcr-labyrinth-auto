package com.lmj.labyrinthauto.frida

import android.os.SystemClock
import com.lmj.labyrinthauto.core.AppState
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * App 侧的 agent 调用层（`PLAN.md §5` 的 P2："rpc.exports 全量接到 socket + App 侧统一调用封装"）。
 *
 * 设计原则：
 *   1. **不写死函数名表**：`raw(fn, args)` 按名字转发到 agent 的 `rpc.exports[fn]`
 *      —— agent 那边新增一个导出，这里一行都不用改（这就是"全量转发"）。
 *   2. 上面再加**类型化封装**：每个常用导出一个方法，返回 Kotlin 类型，调用方不再自己
 *      `optBoolean("ok")` / `optJSONObject("data")` 那一套（以前 `Navigator` 就是这么写的）。
 *   3. 所有方法**都不抛异常**：没连/超时/agent 报错 → 返回 null（或 -1/false）。
 *      自动化最怕半路抛异常打断整局；要原因就传 `errOut`。
 *
 * 约定：`fn` 名字与 PC 版 `script.exports_sync.<fn>` 完全一致（便于对拍与互相查代码）。
 */
object AgentApi {

    /** 便宜的读（viewId/ready/dialog/…） */
    const val T_FAST = 3_000L

    /** ★`dialog()`（DialogArea 子树遍历）单独的超时（2026-10-17 实机：迷宫结算页/结算后奖励页上
     *  这页 UI 树很大 ⇒ 一次 3.1~3.7s，用 [T_FAST]=3s 会**假超时**（日志刷"agent 调用超时"，
     *  连续 3 次还会被判"疑似掉线"⇒ 触发自愈重启 ✗）。它本质是"慢但会回"，给到 6s。 */
    const val T_DIALOG = 6_000L

    /** 含堆扫描/全树遍历的重读（labStatus/labMap/labChoices/…） */
    const val T_SLOW = 8_000L

    /** 全量 dump（tree/stuckDump） */
    const val T_DUMP = 20_000L

    val connected: Boolean get() = AgentBridge.connected.value

    // ── 最底层：一行 JSON 问答 ────────────────────────────────────────────────

    /** 原始调用：拿 agent 的整包 `{id,ok,data,err}`；null = 没连/超时 */
    suspend fun raw(fn: String, args: List<Any?> = emptyList(), timeoutMs: Long = T_FAST): JSONObject? =
        AgentBridge.call(fn, args, timeoutMs)

    /** 调用并取出 `data`；失败（没连/超时/ok=false）返回 null（JSON null 也当 null） */
    suspend fun any(
        fn: String,
        args: List<Any?> = emptyList(),
        timeoutMs: Long = T_FAST,
        errOut: ((String) -> Unit)? = null,
    ): Any? {
        val r = raw(fn, args, timeoutMs)
        if (r == null) {
            errOut?.invoke("$fn：没连上或超时")
            return null
        }
        if (!r.optBoolean("ok", false)) {
            errOut?.invoke("$fn：${r.optString("err").take(140)}")
            return null
        }
        val v = r.opt("data")
        return if (v == null || v === JSONObject.NULL) null else v
    }

    suspend fun obj(
        fn: String,
        args: List<Any?> = emptyList(),
        timeoutMs: Long = T_SLOW,
        errOut: ((String) -> Unit)? = null,
    ): JSONObject? = any(fn, args, timeoutMs, errOut) as? JSONObject

    suspend fun arr(
        fn: String,
        args: List<Any?> = emptyList(),
        timeoutMs: Long = T_SLOW,
        errOut: ((String) -> Unit)? = null,
    ): JSONArray? = any(fn, args, timeoutMs, errOut) as? JSONArray

    suspend fun int(fn: String, args: List<Any?> = emptyList(), def: Int = -1, timeoutMs: Long = T_FAST): Int {
        val v = any(fn, args, timeoutMs)
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: def
            else -> def
        }
    }

    suspend fun bool(fn: String, args: List<Any?> = emptyList(), timeoutMs: Long = T_FAST): Boolean? =
        any(fn, args, timeoutMs) as? Boolean

    suspend fun str(fn: String, args: List<Any?> = emptyList(), timeoutMs: Long = T_FAST): String? =
        any(fn, args, timeoutMs) as? String

    // ── 基础 ─────────────────────────────────────────────────────────────────

    /** 当前视图号（游戏 `eViewId`）；-1 = 没读到。名字表见 `Navigator.viewLabel` */
    suspend fun viewId(): Int = int("viewId", def = -1)

    /** il2cpp 是否就绪（agent 的 ready 导出 = `!!g_il2cpp`） */
    suspend fun ready(): Boolean = bool("ready") ?: false

    /** agent 的构建号（确认跑的是哪一版脚本；PC 版 attach 时也打这个） */
    suspend fun jsBuild(): String? = str("jsBuild")

    /** 游戏 UI 相机的像素尺寸 `{w,h}` —— 我们坐标（WorldToScreenPoint）所在坐标系 */
    suspend fun screen(): JSONObject? = obj("screen", timeoutMs = T_FAST)

    /** 游戏主循环心跳 `{frame,time,exists,err}`（看门狗：帧数不涨=游戏被冻住） */
    suspend fun gameTick(): JSONObject? = obj("gameTick", timeoutMs = T_FAST)

    /** 卡死现场：所有线程调用栈（字符串数组） */
    suspend fun stuckDump(): JSONArray? = arr("stuckDump", timeoutMs = T_DUMP)

    // ── 迷宫内状态（贵；只在迷宫页调，判据见 `Navigator.isLabyrinthView`）──────────

    /** 迷宫内完整快照（含 `pageTop` = 最上层对话框名、battle*、`_ms` 耗时） */
    suspend fun labStatus(errOut: ((String) -> Unit)? = null): JSONObject? =
        obj("labStatus", timeoutMs = T_SLOW, errOut = errOut)

    /**
     * ★★**分派读**（2026-11-08，"需要什么读什么"）：主循环每轮只用来**分派**的那些字段。
     *
     * 含 `area/blockId/enterId/difficulty/panelType/statusType/next/viewId/viewClass/pageTop`
     * + `inBattle/battleView/battleState/battleResultEnable`。
     *
     * ★**不含** `eventId/questId/eventResultId/effectNum/curChoice/remainChoice/choices` ——
     *   那些只有个别 handler 用得到 ⇒ 要时再调 [labExtra]（按需读）。
     */
    suspend fun labStatusLite(timeoutMs: Long = T_SLOW): JSONObject? =
        obj("labStatusLite", emptyList(), timeoutMs)

    /**
     * ★轻量「当前窗口」（2026-11-06 状态机）：只返回 `{top, raw, ms}`，
     * `top` 是**归一化**后的对话框名（去掉 `(Clone)` 之类后缀，空串 = 没有对话框）。
     *
     * 比 [labStatus] 快一个数量级（后者要构快照/读地图字段），可以用 60ms 级轮询。
     */
    suspend fun labTop(timeoutMs: Long = 1_500L): String? {
        val o = obj("labTop", timeoutMs = timeoutMs) ?: return null
        val top = o.optString("top", "")
        return if (top == "null") "" else top
    }

    /** 同上，但要完整对象（含 `raw`/`ms`，调试用） */
    suspend fun labTopRaw(timeoutMs: Long = 1_500L): JSONObject? = obj("labTop", timeoutMs = timeoutMs)

    /**
     * ★轻量「窗口 + 编组页队伍索引」（2026-11-07，Boss 三队状态机）。
     * @return `(窗口名, 队伍索引)`；索引 -1 = 不在编组页 / 读不到
     */
    suspend fun labPartyIdx(timeoutMs: Long = 1_500L): Pair<String, Int> {
        val o = obj("labPartyIdx", timeoutMs = timeoutMs) ?: return "" to -1
        val top = o.optString("top", "").let { if (it == "null") "" else it }
        return top to o.optInt("idx", -1)
    }

    /**
     * ★极简「是否在战斗中」（2026-11-07）：只读 ViewManager → 当前 View 类名，
     * 比 [labStatus] 快一个数量级（后者要构造全量快照）。
     */
    suspend fun labInBattle(timeoutMs: Long = 1_500L): Boolean =
        obj("labView", timeoutMs = timeoutMs)?.optBoolean("inBattle", false) == true

    /**
     * ★轻量「战斗结果原始值」：[gameState] 是 BattleManager 状态机枚举（胜/负不同值，待标定），
     * [resultEnable] 是结算按钮是否可点。比等按钮出现快得多。
     */
    data class BattleRaw(val inBattle: Boolean, val resultEnable: Boolean?, val gameState: Int?)

    /**
     * ★轻量「编队页图标清单」（2026-11-07）：只读 plate 数组长度 + 每格 unitId，
     * 不读坐标/相机投影（那才是 `labParty` 的 893ms）。
     *
     * @param ids **队伍那一排**（`plate+0xC8`，5 格）的 unitId；**0 表示空槽**
     * @param listIds **角色一览当前窗口**（`scrollController+0x70`）的 unitId
     *        ★滚动一览时队伍那一排**不会变**、只有它会变 ⇒ 判断"滚到位了没"必须看它。
     */
    data class PartyIconsRaw(
        val top: String,
        val idx: Int,
        val ids: List<Int>,
        val listIds: List<Int>,
    )

    suspend fun labPartyIcons(timeoutMs: Long = 1_500L): PartyIconsRaw? {
        val o = obj("labPartyIcons", timeoutMs = timeoutMs) ?: return null
        val top = o.optString("top", "").let { if (it == "null") "" else it }
        fun arr(name: String): List<Int> {
            val a = o.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).map { a.optInt(it, 0) }
        }
        return PartyIconsRaw(
            top = top,
            idx = o.optInt("idx", -1),
            ids = arr("ids"),
            listIds = arr("listIds"),
        )
    }

    /**
     * ★轻量「编队决策包」（2026-11-07）：`(窗口, 队伍索引, 屏上 uid 列表, 一览 uid 列表, 已编 uid 列表)`。
     * 不读坐标、不读名字 —— 决策只需要这三样。
     */
    data class PartyPlanRaw(
        val top: String,
        val idx: Int,
        val ids: List<Int>,
        val listIds: List<Int>,
        val members: List<Int>,
    )

    suspend fun labPartyPlan(timeoutMs: Long = 1_500L): PartyPlanRaw? {
        val o = obj("labPartyPlan", timeoutMs = timeoutMs) ?: return null
        fun arr(name: String): List<Int> {
            val a = o.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).map { a.optInt(it, 0) }
        }
        return PartyPlanRaw(
            top = o.optString("top", "").let { if (it == "null") "" else it },
            idx = o.optInt("idx", -1),
            ids = arr("ids"),
            listIds = arr("listIds"),
            members = arr("members"),
        )
    }

    /**
     * ★★轻量「编队决策**精简读**」（2026-11-07 用户："只取需要的内容就好了，不用都取吧"）。
     *
     * `labParty` 是整页快照：29 个 icon 每个带 3 个中文 label + 40 个页签候选 + 一堆诊断字段，
     * 每轮编队页都要序列化/传输/解析一大坨，而决策**根本用不到**那些。
     * 这里只取决策要的：队伍名单 / 槽位 id / 一览 id+vis / 可编入 / 一览全量 / 各队成员 / 按钮坐标。
     *
     * ★字段名与 `labParty` 一致 ⇒ Kotlin 侧 `PartyPlan` / `MazeLoop` 不用改。
     */
    suspend fun labPartyLite(timeoutMs: Long = T_SLOW): JSONObject? =
        obj("labPartyLite", timeoutMs = timeoutMs)

    /**
     * ★编组页顶部页签（`tabCand` 候选 + `partyTabs` 队伍1/2/3 坐标）。
     * 以前每轮都从 `labParty` 里带 40 个候选回来 ⇒ 现在只在"Boss 页切队腾空"时单独调。
     */
    suspend fun labPartyTabs(timeoutMs: Long = T_SLOW): JSONObject? =
        obj("labPartyTabs", timeoutMs = timeoutMs)

    /**
     * ★轻量「某个角色在当前编队页图标上的坐标」（2026-11-07）。
     * 只对命中的那一个 icon 做投影（`labParty` 是 21 个全做，所以 893ms）。
     *
     * @param via **先找哪个区域**（与 [com.lmj.labyrinthauto.logic.MazeLoop.findUnitCoord] 同款）：
     *            `"槽位"` → 队伍那一排优先（**撤人**点的是底排那张卡）；
     *            `"一览"`/null → 角色一览优先（**加人**点的是一览里那张）。
     * @return `(x, y)`；null = 不在本屏 / 不在编组页
     */
    suspend fun labPartyIconPos(uid: Int, via: String? = null, timeoutMs: Long = 1_500L): Pair<Int, Int>? {
        val o = obj("labPartyIconPos", listOf(uid, via ?: ""), timeoutMs) ?: return null
        if (!o.optBoolean("found", false)) return null
        return o.optInt("x", 0) to o.optInt("y", 0)
    }

    suspend fun labBattleResult(timeoutMs: Long = 1_500L): BattleRaw? {
        val o = obj("labBattleResult", timeoutMs = timeoutMs) ?: return null
        val hasRe = o.has("resultEnable") && !o.isNull("resultEnable")
        val hasGs = o.has("gameState") && !o.isNull("gameState")
        return BattleRaw(
            inBattle = o.optBoolean("inBattle", false),
            resultEnable = if (hasRe) o.optBoolean("resultEnable", false) else null,
            gameState = if (hasGs) o.optInt("gameState", 0) else null,
        )
    }

    /** 地图上所有节点面板：**是数组** `[{blockId,blockType,panelState,x,y}]`（PC `lab_nodes` 1020 同款） */
    suspend fun labNodes(): JSONArray? = arr("labNodes", timeoutMs = T_SLOW)
    suspend fun labMap(): JSONObject? = obj("labMap", timeoutMs = T_SLOW)
    suspend fun labEvent(): JSONObject? = obj("labEvent", timeoutMs = T_SLOW)
    /** 选取页（选角色/印记）。[force]=true 绕过 agent 的 1s 读缓存（滑入动画期间重读用） */
    suspend fun labSelection(force: Boolean = false): JSONObject? =
        obj("labSelection", listOf(force), timeoutMs = T_SLOW)
    /** ★选取页读取的**自证**（cards 为空时用）：返回 `{cards, ticketId, diag:{scanned,picked,probe[],err}}` */
    suspend fun labSelectionDiag(force: Boolean = false): JSONObject? =
        obj("labSelectionDiag", listOf(force), timeoutMs = T_SLOW)

    /** 遗物/道具选择页。[force]=true 绕过 agent 的 2s 读缓存 + 1.2s 失败退避 */
    suspend fun labChoices(force: Boolean = false): JSONObject? =
        obj("labChoices", listOf(force), timeoutMs = T_SLOW)
    suspend fun labShop(): JSONObject? = obj("labShop", timeoutMs = T_SLOW)
    suspend fun labResult(): JSONObject? = obj("labResult", timeoutMs = T_SLOW)

    /** ★结算奖励（LabyrinthTempData 内存字段）：`{ok, result:{...}, questFinish[], challenge[], rewardInfo[], normalBox[[]], rareBox[[]]}` */
    suspend fun labRewards(): JSONObject? = obj("labRewards", timeoutMs = T_SLOW)

    /** ★战斗结算页"对象字段"抓取（2026-10-16）：返回最上层对话框 + 屏内按钮节点的
     *  GameObject 指针 / 候选组件（`类名@实例指针`）/ 字段槽，用于将来"按字段读按钮坐标"（不再扫树）。 */
    suspend fun battleResultDump(): JSONObject? = obj("battleResultDump", timeoutMs = T_SLOW)

    /** ★★战斗结算按钮的**字段读**（2026-10-16）：`{ok, cands:[{x,y,label,src}]}`。
     *  每个候选带**配对标签文字**（判胜负）与坐标（判点哪）；`ok=false` ⇒ 调用方回退树遍历。 */
    suspend fun battleResultMem(): JSONObject? = obj("battleResultMem", timeoutMs = T_SLOW)

    /** ★★结算按钮"**学一次、之后只校验**"（2026-10-16）：`{ok,hit,name,x,y,label}`。
     *  首次走一次"只限 DialogArea"的遍历学习并记住节点指针；之后只做"读指针 + 校验 + 算坐标"（零遍历）。 */
    suspend fun battleResultBtn(minY: Int): JSONObject? =
        obj("battleResultBtn", listOf(minY), T_SLOW)

    /**
     * ★★"只认页"的**便宜**探测（2026-10-23，两层化的第 1 层）：返回 `"win"` / `"fail"` / `"ex"`，
     * 认不出来返回 null。
     *
     * 为什么单独一个调用：`_resPageKind` 只是在 DialogArea 的子节点上做几次
     * `GetComponentInChildren`（战斗中 DialogArea 基本是空的 ⇒ **个位数毫秒**），而"找结算按钮"
     * 一次要 0.5~2s。以前两者被打包成同一个 `battleResultBtns()`，于是"认页"这件便宜事只能
     * 跟着贵调用一起降频（战斗中 10s 才问一次 ⇒ 结算页出现后最多等 10s 才发现）。
     * 拆开后：认页可以勤问（300ms），找按钮只在认出来之后做（见 MazeLoop `battleTick`）。
     */
    suspend fun resPageKind(): String? {
        val r = obj("resPageKind", timeoutMs = T_FAST) ?: return null
        if (r.opt("ok") != true) return null
        val k = r.opt("kind")
        if (k == null || k === JSONObject.NULL) return null
        val s = k.toString().trim()
        return if (s.isEmpty() || s == "null") null else s
    }

    /** ★通用"读某指针对象的字段槽"（2026-10-16 门票页取证）：`{cls, slots:[...]}` */
    suspend fun objSlots(ptrStr: String, from: Int = 0x10, to: Int = 0x1c0): JSONObject? =
        obj("objSlots", listOf(ptrStr, from, to), T_SLOW)
    suspend fun labOwnRelics(): JSONObject? = obj("labOwnRelics", timeoutMs = T_SLOW)
    suspend fun labUnits(): JSONObject? = obj("labUnits", timeoutMs = T_SLOW)
    suspend fun labChallenging(): Boolean? = bool("labChallenging", timeoutMs = T_FAST)

    /**
     * ★战斗设定读取（只读，2026-10-22）：与 pcrtool `force_settings` 同源三项
     * `{useUbSetMode, resetSkillReserveEveryTime, autoSpeedButtonState}`。
     *
     * 为什么要 start/poll 两段：这三个 getter 是 **IFix 模板**，pcrtool 实测只能在游戏主线程调，
     * RPC 线程直调有断言（崩溃）风险；而 Frida JS 单线程，RPC 里同步等主线程回调会死锁。
     * ⇒ agent 侧 `battleSettingsStart` 投递到 `runOnGameThread`，这里轮询 `battleSettingsPoll` 取结果。
     *
     * @return 结果对象（`pending` 仅出现在内部轮询，最终返回必带 `ok`）；超时/未连接返回带 `err` 的对象或 null。
     */
    suspend fun battleSettings(timeoutMs: Long = 4_000L): JSONObject? {
        val st = obj("battleSettingsStart", emptyList(), T_FAST) ?: return null
        if (!st.optBoolean("queued", false)) return st          // 投递失败 → 直接回错误对象
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            delay(120)
            val r = obj("battleSettingsPoll", emptyList(), T_FAST) ?: continue
            if (!r.optBoolean("pending", true)) return r
        }
        return JSONObject().put("ok", false).put("err", "读取超时：游戏线程 ${timeoutMs}ms 内没执行")
    }

    /**
     * ★轻量页面签名 `{view, top}`（viewId + 最上层对话框名，几十 ms）。
     * 用途：给"按钮坐标缓存"做 key —— 同一页 + 同一顶层对话框下按钮位置稳定，
     * 命中就直接点，**省掉 2s 级的全树 find**（2026-10-19 用户："全树扫描要尽力避免"）。
     */
    suspend fun uiSig(): JSONObject? = obj("uiSig", timeoutMs = T_FAST)
    suspend fun labCharaPageQuick(): JSONObject? = obj("labCharaPageQuick", timeoutMs = T_SLOW)

    /** 编队页只读快照；`diag=true` 带现场 dump */
    /**
     * 编队页全量快照。
     * @param noXY **跳过每个 icon 的坐标投影**（2026-11-07）：投影约占 893ms 里的 700ms。
     *             编队决策只需要 `icons[].unitId` / `nowMember` / `buttons`，不需要坐标；
     *             真正要点谁时用 [labPartyIconPos] 单点取（~20ms）。
     */
    suspend fun labParty(diag: Boolean = false, noXY: Boolean = false): JSONObject? =
        obj("labParty", listOf(diag, noXY), T_SLOW)

    /** 让编队页的读取缓存立刻失效（点完一下必须读到最新编队） */
    suspend fun labPartyFlush(): Boolean = bool("labPartyFlush") ?: false

    /** ★**丢缓存重解析**：清掉 agent 侧所有读取缓存（实例/TTL/LTD 指针），返回 `{ok, cleared}`。
     *  页面读不到时的**中间档**——比重启游戏便宜 30~40s，且能判断"是不是缓存问题"。 */
    suspend fun labResetCaches(): JSONObject? = obj("labResetCaches", timeoutMs = T_SLOW)

    /**
     * 特别装备三连页；`full=true` 连按钮坐标一起算。
     *
     * ★`only` = **只投影这一个按钮**（按钮名 / "prio0" / "check" / "rare"）；
     *   null = 老行为（把整棵子树里的按钮全投影一遍，①页实测 380ms）。
     */
    suspend fun labExEquip(full: Boolean = false, only: String? = null, timeoutMs: Long = T_SLOW): JSONObject? =
        obj("labExEquip", listOf(full, only ?: ""), timeoutMs)

    /**
     * ★**极轻**：只读特别装备三连页的**签名**（一个字符串）—— 状态机轮询专用。
     *
     * 不算坐标、不读按钮、不回传整包 JSON（只要 `labels[0]` / `checked` / 选中的那一项），
     * 比 [labExEquip] 轻一个量级。
     * @return 签名；`""` = 当前不在三连页里；`null` = 读不到
     */
    suspend fun labExEquipSig(timeoutMs: Long = T_FAST): String? {
        val o = obj("labExEquipSig", emptyList(), timeoutMs) ?: return null
        val err = o.optString("err")
        if (err.isNotEmpty() && err != "null") return null
        if (o.isNull("sig")) return null
        return o.optString("sig")
    }

    /** 最上层是不是"特别装备三连页"之一 */
    suspend fun labExEquipTop(): JSONObject? = obj("labExEquipTop", timeoutMs = T_FAST)

    /**
     * ★★**按需读按钮**（2026-11-08）：三连页"这一步只点一个按钮" ⇒ 只读**那一个** + 当前签名。
     *
     * @param key 按钮名（`ThreeButtonRight` / `TwoButtonRight` …）或 `"prio0"` / `"check"` / `"rare"`
     * @param preferId ★**优先取这个实例**（GameObject 指针）—— 同一页可能有多个同名按钮，
     *   传上次点的那个 ⇒ 多点时锁死在同一个实例上（取不到时 agent 侧回退"最靠下"的主按钮行）
     * @return `{sig, top, btn:{name,x,y,id}}`；`btn` 为 null = 这页上没有这个按钮
     *
     * ★为什么不用 [labExEquip]：整包读会把**页里其它控件**也过一遍（setting 的 4 行文字 + 复选框、
     *   sort 的 11 个 toggle 文字 —— 每个都是一次游戏线程调用）⇒ 纯浪费；本流程每步只点一个按钮。
     */
    suspend fun labExBtn(key: String, preferId: String? = null, timeoutMs: Long = T_SLOW): JSONObject? =
        obj("labExBtn", listOf(key, preferId ?: ""), timeoutMs)

    /**
     * ★★**按需读：非分派字段**（2026-11-08）—— ids + LTD 候选清单。
     *
     * `{eventId, questId, eventResultId, effectNum, curChoice, remainChoice, choices[]}`。
     * 只有个别 handler 用得到（事件页要 eventId、编组页要 questId…）⇒ 不塞进每轮都跑的分派读。
     */
    suspend fun labExtra(timeoutMs: Long = T_FAST): JSONObject? = obj("labExtra", emptyList(), timeoutMs)

    /**
     * 战斗格子预览页一步到位：`{top, start:{name,x,y}, party, nodes, ms}`。
     * **只走"最上层对话框"子树**（几十个节点），替代原来"2~3 次整树 find"（整树 ≈2s/次）。
     * 一次同时给出：最上层对话框名 / 「挑战」坐标 / 编组页零件在不在（防误点「战斗开始」）。
     */
    suspend fun labQuestDetail(
        startBtnRe: String = "UIButton_Unit",
        knownRoot: String? = null,
        knownPath: List<Int>? = null,
    ): JSONObject? = obj("labQuestDetail", listOf(startBtnRe, knownRoot, knownPath), T_SLOW)

    // ── 通用对话框 / 找节点（按**节点名**定位，分辨率无关）────────────────────────

    /** 当前通用对话框：`{open, buttons:[{name,x,y}], darkFilters, learned}`。
     *  ★`topCls` 可选（2026-10-17）：传当前 `pageTop` ⇒ agent 用"**学到的按钮索引路径**"直接定位
     *  （零遍历，回包里 `learned=true`）；顶框类名/路径任一级不符就自动回退遍历。 */
    suspend fun dialog(topCls: String? = null, knownChains: org.json.JSONArray? = null): JSONObject? =
        when {
            topCls.isNullOrEmpty() -> obj("dialog", timeoutMs = T_DIALOG)
            else -> obj("dialog", listOf(topCls, knownChains), T_DIALOG)
        }

    /**
     * ★★通用弹窗「按钮轻读」（2026-11-09）：按 `DialogCommonAbstract` 的按钮字段**直取** +
     * 名字/active 校验 + 只投影命中的按钮，**零遍历**（与 `labExBtn` 同一范式，几十 ms）。
     * 对比 [dialog]：走 `DialogArea` 子树遍历，设备日志实测冷 0.5~0.6s、大结算页 3~3.9s
     * ⇒ [T_DIALOG] 才给到 6s；这里用 [T_FAST] 就够。
     *
     * [topCls] = 调用方看到的当前顶框名；对不上或一颗按钮都没激活 ⇒ `ok=false`，
     * 调用方应退回 [dialog] 兜底，**绝不硬点**。
     * 返回 `{ok, top, buttons:[{name,x,y,id}], ms, err}`。
     */
    suspend fun labDlgBtn(topCls: String, timeoutMs: Long = T_FAST): JSONObject? =
        obj("labDlgBtn", listOf(topCls), timeoutMs)

    /**
     * ★★「关闭已启动」轻读（2026-11-09 定案）—— `tapAndCheck` 阶段 1 的"这一下生效了"判据。
     *
     * 返回 `{top, raw, path, proc, started, factor, closing, ms, err}`：
     *   · `top` = 当前最上层对话框规范名（与 [labTop] 同源）
     *   · `closing` = 退出动画正在跑（`started=1 && factor<0.95`）或整页已消失
     *   · `proc` = `DialogBase.isProcess`；`started`/`factor` = `outMainTweenAlpha` 的
     *     `UITweener.mStarted` / `mFactor`
     *
     * 实测（2026-11-09，7 段关闭）：退出 tween 在点击后 **+200~300ms** 启动，整页到 +800ms
     * 才消失；按钮层 `mState/IsPressed` 全程不变（游戏关弹窗不走"禁用按钮"）。
     * 所以用它判"点生效了"比等窗口消失早 500~600ms。
     * ⚠基线 `factor` 可能是 1、`proc` 可能是 1（打开过程残留）⇒ 只能认 `started` 由 0 变 1
     * 且 `factor` 从小往上涨，**不能只看 `proc==1`**。
     * 零 hook、只读字段，实测 3~6ms。
     */
    suspend fun labDlgClosing(timeoutMs: Long = T_FAST): JSONObject? =
        obj("labDlgClosing", timeoutMs = timeoutMs)

    /**
     * ★三选一页的**阶段 1 探针**（2026-11-09）：一次读取同时给「顶框名 + 关闭判据 + 诊断」。
     *
     * 三选一（`DialogLabyrinthSelection`）也是 `DialogBase` 子类 ⇒ 退出 tween 与确认框同源，
     * 所以可以用和 [labDlgClosing] 一样的判据（`started==1 && factor<0.95`）。
     * 返回 `{path, top, closing, proc, tw, selNum, listN, remainText, remainActive, cardIdx[], ms, err}`：
     *   · `closing` 就是判据；`tw`/`proc`/`selNum`/`listN`/`remain` 只是**顺带**诊断（进 probe 轨迹日志）
     * 零 hook、只读字段（含一次 `get_text`），实测 8~18ms。
     */
    suspend fun labSelState(timeoutMs: Long = T_FAST): JSONObject? =
        obj("labSelState", timeoutMs = timeoutMs)

    /**
     * ★公会选择页（263）卡列表**轻读**（2026-11-09 定型）：字段直取，真机实测 **9ms**。
     *
     * 返回 `{ok, view, ltd, wrapCount, cards[], ms, err}`：
     *   · `ltd` = `LabyrinthTempData+0xF0` 的 GuildId/Difficulty/SelectDifficulty/DifficultyMax
     *   · `cards[]` = 每张 `PartsLabyrinthGuildSettingPlate` 的 `name`（guildNameLabel 文本）
     *     和 `x`/`y`（departureButton 投影坐标）；**离屏卡 `x`/`y` 为 -1**
     * 有了 `name` 就能**按名字**选公会，不用再假设"第 N 张 = 第 N 个公会"。
     */
    suspend fun labGuildCards(timeoutMs: Long = T_FAST): JSONObject? =
        obj("labGuildCards", timeoutMs = timeoutMs)

    /** 第一个"屏内+激活+名字匹配"的节点：`{name,x,y}`；找不到 null */
    suspend fun find(nameRe: String): JSONObject? = obj("find", listOf(nameRe), T_SLOW)

    /** 所有匹配节点 `[{name,x,y}]`（DFS 顺序）；`root` 可选（如 `"dialog"` = 只走 DialogArea 子树 ⇒ 快得多） */
    suspend fun findList(nameRe: String, root: String? = null): JSONArray? =
        arr("findList", if (root == null) listOf(nameRe) else listOf(nameRe, root), T_SLOW)

    /** 同 find，但连节点内标签文字一起返回 `{name,text,x,y}`；`root` 可选（如 `"dialog"` ⇒ 快得多） */
    suspend fun findText(nameRe: String, root: String? = null): JSONObject? =
        obj("findText", if (root == null) listOf(nameRe) else listOf(nameRe, root), T_SLOW)

    /** findText 的列表版；`minY` = 只收屏幕 y ≥ minY 的（找屏幕最底部的按钮）；
     *  ★`root` 可选（2026-10-17 加）：传 `"dialog"` 只走 DialogArea 子树 ⇒ 省掉 2~3s 的全树遍历 ✓ */
    suspend fun findTextList(nameRe: String, max: Int = 40, minY: Int? = null, root: String? = null): JSONArray? =
        arr("findTextList", if (root == null) listOf(nameRe, max, minY) else listOf(nameRe, max, minY, root), T_SLOW)

    /** 只在指定子树里找文字（`rootName="battle"` 快、传 null 整棵树准但慢） */
    suspend fun findTextIn(nameRe: String, rootName: String? = null, max: Int? = null, minY: Int? = null): JSONArray? =
        arr("findTextIn", listOf(nameRe, rootName, max, minY), T_SLOW)

    /** 全量 dump 所有"屏内+激活"节点 `[{name,x,y,d}]`（慢，少用） */
    suspend fun tree(): JSONArray? = arr("tree", timeoutMs = T_DUMP)

    // ── P2 对拍探针 ───────────────────────────────────────────────────────────

    /**
     * 逐项对拍：对一组导出逐个调用，把结果按 `PROBE <fn> <json>` 打进 app 日志。
     * PC 版那边跑 `tools/pc_probe.py` 打**同一格式**（同一个页面、同一时刻），然后逐条比对。
     *
     * 故意**不放** `gameTick`（frame/time 每调必变）和 `tree`（太大）：对拍要的是可比较的稳定字段；
     * `labStatus` 里的 `_ms` 是耗时，比对时忽略（见 tools/probe_diff.py）。
     */
    suspend fun probeAll(out: java.io.File? = null) {
        AppState.addLog("── PROBE 开始（对拍；两边都是 `PROBE <fn> <json>`）──")
        val sb = StringBuilder()
        for ((fn, args) in PROBE_CALLS) {
            val r = raw(fn, args, T_SLOW)
            // 打的是"函数的返回值本身"（= PC 侧 script.exports_sync.<fn>() 的返回），
            // 两边格式一致才好逐条比。调用失败单独标出来，免得和"真的返回 null"混了。
            val line = when {
                r == null -> "{\"__call_failed__\":\"没连上或超时\"}"
                !r.optBoolean("ok", false) ->
                    JSONObject().put("__call_failed__", r.optString("err").take(140)).toString()
                else -> {
                    val v = r.opt("data")
                    when {
                        v == null || v === JSONObject.NULL -> "null"
                        // ★字符串要加引号：直接 toString() 出来是裸文本，不是合法 JSON
                        //（对拍第一轮就踩到了：app 侧 `cs41` vs PC 侧 `"cs41"`）
                        v is String -> JSONObject.quote(v)
                        else -> v.toString()
                    }
                }
            }
            sb.append("PROBE ").append(fn).append(' ').append(line).append('\n')
            AppState.addLog("PROBE $fn ${line.take(200)}")   // 日志会被系统截断，所以另存一份文件
        }
        AppState.addLog("── PROBE 结束 ──")
        if (out != null) {
            runCatching { out.writeText(sb.toString()) }
        }
    }

    private val PROBE_CALLS: List<Pair<String, List<Any?>>> = listOf(
        "ready" to emptyList(),
        "jsBuild" to emptyList(),
        "viewId" to emptyList(),
        "screen" to emptyList(),
        "dialog" to emptyList(),
        "labStatus" to emptyList(),
        "labNodes" to emptyList(),
        "labMap" to emptyList(),
        "labEvent" to emptyList(),
        "labSelection" to emptyList(),
        "labSelectionDiag" to listOf(false),
        "labChoices" to emptyList(),
        "labShop" to emptyList(),
        "labResult" to emptyList(),
        "battleResultDump" to emptyList(),
        "objSlots" to listOf("", 16, 448),
        "battleResultMem" to emptyList(),
        "battleResultBtn" to listOf(0),
        "resPageKind" to emptyList(),
        "labOwnRelics" to emptyList(),
        "labUnits" to emptyList(),
        "labParty" to listOf(false),
        "labResetCaches" to emptyList(),
        "labExEquip" to listOf(false),
        "labExEquipTop" to emptyList(),
        "labExEquipSig" to emptyList(),
        "labQuestDetail" to listOf("UIButton_Unit"),
        "labChallenging" to emptyList(),
        "labCharaPageQuick" to emptyList(),
        "findList" to listOf("Button", "dialog"),
        "findTextList" to listOf("Button", 5),
    )
}
