package com.lmj.labyrinthauto.frida

import android.content.Context
import android.os.SystemClock
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.logic.MazeLoop
import com.lmj.labyrinthauto.logic.Navigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 感知链路的总编排（P1 已跑通）：**保证游戏在跑 → 等它就绪 → 让 frida-server 注入 agent → 读页面名**。
 *
 * 链路（`PLAN.md §4` 定案）：
 *   1. assets 里的 `agent_base.js`（PC 版原样）+ `agent_bridge.js`（socket 转发层）拼成 `files/agent.js`
 *   2. 起 frida-server（没在听就用 `FridaServerInstaller` 自带那份）→ 用内嵌 `frida-core`
 *      （`FridaCore`）**当客户端**请它把 agent 注入进游戏（自注入 `frida-inject` 会被反作弊打死）
 *   3. agent 主动连回 `127.0.0.1:27183`（`AgentBridge`），之后一问一答
 *   4. 就绪判据、游戏调起、掉线/崩溃自愈都在这里：`waitGameReady()` / `GameProcess`
 */
object FridaInjector {

    /** P0/P1 阶段只对着这一个游戏做（渠道包名表见 PLAN.md §2） */
    const val GAME_PKG = GameProcess.GAME_PKG

    private const val INJECT_BIN = "/data/local/tmp/huabanxie-inject"
    private const val POLL_MS = 1_500L

    /**
     * 游戏"启动期"不许注入：实测冷启动后 15s / 45s 注入都会让它**原生崩溃**
     * （exit-info = reason 5 APP CRASH(NATIVE)）。
     *
     * 判据与 PC 版 `pcr_auto.py` 对齐（`so_mapped` / `step_wait_ready` / `ATTACH_DELAY`）：
     *   ① 主判据：`/proc/<pid>/maps` 里出现 `libil2cpp.so` → Unity 运行时已加载，注入才安全；
     *      ★**必须用 root 读**（普通 uid 读别人的 maps 是 Permission denied —— PC 版注释里也写了这条）；
     *   ② 拿不到 root 时的兜底：进程存活时长 >= MIN_GAME_AGE_MS（纯猜，保守）。
     */
    private const val KEY_SO = "libil2cpp.so"

    /** maps 里出现 libil2cpp.so 之后再静置这么久才注入（PC 版 ATTACH_DELAY = 2.0） */
    private const val SETTLE_MS = 2_000L

    /** ★慢启动设备的判据（2026-10-21 uuz 实测：低端模拟器启动慢，libil2cpp 出现得晚 = 整机都慢） */
    private const val SLOW_BOOT_MS = 3_000L

    /** 慢启动设备的额外静置（映射出现得越晚，初始化越没底，多等一会儿再 attach） */
 
    private const val SLOW_BOOT_EXTRA_MS = 4_000L

    /** 每次注入失败后，下一次注入前多等这么久（避免"杀→起→又太早注入"的循环） */
    private const val FAIL_EXTRA_STEP_MS = 2_000L

    /** 学习型静置的默认秒数 / 上限（慢启动设备最多等到 15s） */
    private const val DEFAULT_SETTLE_SEC = 2
    private const val MAX_SETTLE_SEC = 15

    /** 等就绪的总超时（PC 版 step_wait_ready 的 timeout = 120） */
    private const val READY_TIMEOUT_MS = 120_000L

    /** 轮询 maps 的间隔（PC 版 0.5s；这里是设备内 `su -c`，稍缓一点免得频繁起进程） */
    private const val MAPS_POLL_MS = 800L

    /** 拿不到 root 时的兜底闸门（时间口径，见上） */
    private const val MIN_GAME_AGE_MS = 90_000L

    /** 调起游戏后等进程出现的上限 */
    private const val PID_WAIT_MS = 20_000L

    /** ★agent 回连后**再静置**这么久才开始读页面/导航（2026-10-17）：`il2cpp 就绪` ≠ 游戏加载完，
     *  实测注入后 ~2s 游戏还在 `NONE(0)`，那一拍做 UI 遍历最容易把进程拖死 ✓。
     *  ★时长取 **8s**（用户 2026-10-17："5s、8s 都没事，不会拖慢多少 —— 游戏有几个开屏画面，跳不了多少"）：
     *  静置期与开屏画面**重叠** ⇒ 等于免费；这段正是"最不该动手"的窗口。
     *  （每次重新注入（含自愈重启后）都会走这一段 ✓。） */
    private const val POST_INJECT_SETTLE_MS = 8_000L

    /**
     * 连续调起多少次都见不到游戏进程就放弃（避免"崩→拉起→崩"死循环刷屏）。
     * 游戏活过一轮（注入成功）后计数清零。
     */
    private const val MAX_LAUNCH_TRIES = 3

    /** 连续注入失败多少次就杀游戏重开（PC 版的自愈路径） */
    private const val MAX_INJECT_FAILS = 3

    /** agent 掉线多久（游戏还活着）⇒ 开始**原地重注入**（★不再直接杀游戏，见下） */
    private const val AGENT_LOST_MS = 20_000L

    /**
     * ★agent 掉线后**先原地重注入**（不杀游戏）：每次间隔 + 累计次数，都失败才杀游戏重开。
     *
     * 2026-10-16 用户："战斗中你切进程干什么" —— 日志 19:58 那场战斗：agent 掉线（viewId 连续超时），
     * 但**游戏进程一直活着、战斗正常在打**，旧逻辑 20s 后直接 `GameProcess.kill()` ⇒ 这一局直接废掉。
     * 而"agent 死"≠"游戏坏"：注入循环**只在 pid 变化时注入**，agent 死在同一个 pid 里时原本**没有**
     * 重注入这条路 ⇒ 杀游戏成了唯一出路。现在补上这条路：对**同一 pid** 重新 attach + 重挂脚本
     * （frida-server 还在），每条 8s、最多 3 次（20s + 24s ≈ 44s），仍连不回来才判定"进程里的坏状态
     * 无可救药" → 杀游戏重开（= PC `on_detached` 的路径，退化成最后一档）。
     */
    private const val REINJECT_TRIES = 3

    /** 原地重注入的间隔（attach 本身要几秒，别怼太快） */
    private const val REINJECT_GAP_MS = 8_000L

    /** ★单次"原地重注入"的**等待上限**（2026-10-17）：进程卡死时 frida 自己的 `create_script`
     *  会等到它内部超时（实测 **25s** ✗），把"决定杀游戏"整整拖后 25s。超时（或失败信息里带
     *  Timeout）⇒ 判定进程已僵，**不再重试**、直接进"杀游戏"档（这一次实测能省 ~25s）。 */
    private const val REINJECT_TIMEOUT_MS = 8_000L

    /** 设备上 frida-server 的地址与二进制（app 会先看它活没活，不活就用 root 拉起来） */
    private const val FRIDA_SERVER = "127.0.0.1:1234"
    private const val FRIDA_SERVER_PORT = 1234
    private const val FRIDA_SERVER_BIN = "/data/local/tmp/huabanxie-base"

    /**
     * 方案 B（默认）：app 内嵌 libfrida-core，**当 frida-server 的客户端**去注入。
     *   - 走的是 frida-server 的注入实现（= PC 版那条路），实测不会被这游戏的反作弊打死；
     *   - app 只在"frida-server 没起来"这一步需要 root（`su` 拉起来），注入本身不需要。
     */
    private const val USE_FRIDA_CORE = true

    /**
     * 方案 A 的自注入（frida-inject）：★实测**不能用**，留着只作反面教材。
     *
     * 实测（2026-10-14，MuMu + PCR 11.7.2）：frida-inject 只要注入进去，哪怕脚本只有一句
     * console.log，游戏进程也会立刻自己退出（exit-info = reason 1 EXIT_SELF，
     * frida-inject 侧打印 "Process terminated"）；而让 frida-server attach 同一个进程、
     * 加载同一个 agent，游戏毫发无损。⇒ 反作弊认的是"自己注入"这条路径，不是 agent 内容。
     */
    private const val SELF_INJECT = false

    /** pid → app 第一次看到它的时刻（elapsedRealtime，毫秒）—— 时间兜底口径 + 日志用 */
    private val firstSeen = HashMap<Int, Long>()

    /** root 探测失败过就记住，别每轮重试（会刷屏、反复弹授权框） */
    @Volatile
    private var rootUnavailable = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var proc: Process? = null

    @Volatile
    private var pidFile: File? = null

    val running: Boolean get() = job?.isActive == true

    /**
     * 崩溃/卡死**自愈总闸**（默认关）：false = 助手开着也**绝不自动重启游戏**（游戏没了只提示、等它自己起来，
     * 注入连败/agent 掉线也只提示不杀进程）；true = 仅在「自动黎明界」跑迷宫期间由本进程托管
     * —— 那段时间（启动游戏 → 结算）崩了/卡死才杀进程/重启。
     * 由 [com.lmj.labyrinthauto.service.AutoForegroundService] 在跑迷宫时打开、结束时关闭。
     */
    @Volatile
    var autoRecover = false


    /** ★注入前静置秒数（2026-10-21 用户设计"学习型等待"）：**持久化**——这次几秒注入成功，
    之后都按这个等；注入失败一次 +1s（上限 [MAX_SETTLE_SEC]）。换设备/清数据会回到默认值。 */
    @Volatile
    private var settleSec = DEFAULT_SETTLE_SEC

    /** 本次实际使用的注入前静置（waitGameReady 记、注入成功后固化） */
    private var lastSettleMs = SETTLE_MS

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("frida", Context.MODE_PRIVATE)

    private fun loadSettleSec(ctx: Context): Int {
        settleSec = prefs(ctx).getInt("injectSettleSec", DEFAULT_SETTLE_SEC)
            .coerceIn(DEFAULT_SETTLE_SEC, MAX_SETTLE_SEC)
        return settleSec
    }

    private fun saveSettleSec(ctx: Context, sec: Int) {
        settleSec = sec.coerceIn(DEFAULT_SETTLE_SEC, MAX_SETTLE_SEC)
        prefs(ctx).edit().putInt("injectSettleSec", settleSec).apply()
    }

    fun start(ctx: Context) {
        if (running) {
            AppState.addLog("注入已经在跑了（忽略重复请求）")
            return
        }
        job = scope.launch { loop(ctx.applicationContext.also { loadSettleSec(it) }) }
    }

    fun stop() {
        val p = proc
        proc = null
        runCatching { p?.destroy() }
        val pf = pidFile
        job?.cancel()
        job = null
        scope.launch {
            if (pf != null && pf.exists()) {
                RootShell.exec(killInjectorCmd(pf), 8_000L)
            }
        }
        firstSeen.clear()
        AgentBridge.stop()
        AppState.setFridaStatus("已停止")
        AppState.setPageName(null)
    }

    /** 把两个 assets 拼成实际注入的脚本文件（每次重建，保证跟 APK 里的版本一致）。 */
    fun agentFile(ctx: Context): File {
        val out = File(ctx.filesDir, "agent.js")
        val base = ctx.assets.open("agent_base.js").use { it.readBytes() }
        val bridge = ctx.assets.open("agent_bridge.js").use { it.readBytes() }
        out.outputStream().use {
            it.write(base)
            it.write('\n'.code)
            it.write(bridge)
        }
        return out
    }

    /**
     * 方案 B：请设备上的 frida-server 去注入（注入动作不归 app 干）。
     * 整套流程里唯一需要 root 的地方是"frida-server 没起来时把它拉起来"。
     *
     * 这里是个**常驻小循环**：游戏没起来就等，起来了就注入；游戏重启（pid 变了）就重新注入。
     * —— 之前写成"一次性"的，结果第一次没赶上游戏在跑就永远卡在"等外部注入"，
     *    再点「注入 Frida」被当成重复请求忽略（实测踩过）。
     */
    private suspend fun injectViaFridaCore(ctx: Context, agent: File) {
        var lastPid = -1
        var launchTries = 0
        var injectFails = 0
        var agentLostSince = 0L          // 非 0 = 正处在"agent 掉线"状态（自愈计时）
        var lastReinjectAt = 0L          // 上次"原地重注入"的时刻（间隔节流）
        var reinjectN = 0                // 本次掉线已原地重注入几次
        var reinjectDead = false         // ★重注入"超时"失败 ⇒ 进程已僵，别再试，直接杀游戏（见 [REINJECT_TIMEOUT_MS]）
        var serverReady = false          // 本次跑迷宫期间 frida-server / libfrida 是否已就绪
        var wasRecover = false           // 上一轮 autoRecover 是否开着（用于检测"刚进/刚出"跑迷宫）
        while (true) {
            // ★注入只服务于「自动黎明界」：总闸关着就**待命** —— 不调起游戏、不注入、不自愈、不读页。
            //   这样"助手开着"也绝不碰游戏进程（用户 2026-10-16 要求：关着时没必要注入）。
            if (!autoRecover) {
                if (wasRecover) {
                    wasRecover = false
                    serverReady = false
                    lastPid = -1
                    launchTries = 0
                    injectFails = 0
                    agentLostSince = 0L
                    lastReinjectAt = 0L
                    reinjectN = 0
                    reinjectDead = false
                    AppState.addLog("自动黎明界结束 → 停止注入/自愈，之后不再碰游戏进程（待命）")
                }
                AppState.setFridaStatus("待命（未跑迷宫，不注入游戏）")
                delay(POLL_MS)
                continue
            }
            if (!wasRecover) {
                wasRecover = true
                AppState.addLog("进入自动黎明界 → 接管游戏进程（调起 → 等就绪 → 注入；崩溃/卡死自愈）")
            }

            // frida-server / libfrida 就绪检查（**只在开闸后做**；失败记一次、下轮重试，不 return 掉整个循环，
            // 这样每次跑迷宫都会重新尝试接管）
            if (!serverReady) {
                FridaCore.logAvailability()
                if (!FridaCore.available) {
                    AppState.setFridaStatus("libfrida_bridge.so 没加载上（看日志）")
                    delay(POLL_MS)
                    continue
                }
                if (!ensureFridaServer(ctx)) {
                    AppState.setFridaStatus("frida-server 没起来，注入没法做")
                    delay(POLL_MS)
                    continue
                }
                serverReady = true
            }

            var pid = gamePid()
            if (pid <= 0) {
                // 开闸期间游戏没在跑 → 调起它（= "刷完开局 / 重启后调起游戏"的入口）
                if (lastPid > 0) {
                    AppState.addLog("游戏（pid=$lastPid）没了 —— 崩了或被系统回收，重新调起它")
                    launchTries = 0                      // 它活过一轮 → 计数清零，允许重来
                }
                lastPid = -1
                launchTries++
                if (launchTries > MAX_LAUNCH_TRIES) {
                    AppState.addLog("连续 $MAX_LAUNCH_TRIES 次调起后游戏都没活下来 → 本回合停 10s 再试（不 return，留给下次跑迷宫）")
                    AppState.setFridaStatus("游戏起不来（连续 $MAX_LAUNCH_TRIES 次）")
                    launchTries = 0
                    delay(10_000)
                    continue
                }
                AppState.setFridaStatus("游戏没在跑，调起它（第 $launchTries 次）…")
                GameProcess.launch(ctx)
                pid = waitForGamePid(PID_WAIT_MS)
                if (pid <= 0) {
                    AppState.addLog("调起后 ${PID_WAIT_MS / 1000}s 没看到游戏进程（第 $launchTries 次）")
                    delay(3_000)
                    continue
                }
                AppState.addLog("游戏已起来 pid=$pid（第 $launchTries 次调起）")
            }
            if (pid != lastPid) {
                // 记下"app 第一次看到这个 pid 的时刻"（时间兜底口径 + 日志用）
                val startedAt = firstSeen.getOrPut(pid) { SystemClock.elapsedRealtime() }
                // ★启动期闸门：等到"可以安全注入"（主判据 = libil2cpp 已映射，见常量注释）
                //   —— 这就是"调起游戏后稍微过一会再注入"：等 il2cpp 映射 + 静置 SETTLE_MS
                val readyWhy = waitGameReady(pid, startedAt)
                if (readyWhy != null) {
                    // ★僵死/提前退出 => 进程已坏，别傻等，杀游戏重开（2026-10-21 uuz 实测）
                    if (readyWhy.contains("僵死") || readyWhy.contains("提前退出")) {
                        launchTries = 0
                        GameProcess.kill()
                        lastPid = -1
                        delay(1_500)
                        continue
                    }
                    delay(2_000)
                    continue
                }
                if (lastPid <= 0) {
                    AppState.addLog("找到游戏 pid=$pid（起了 ${(SystemClock.elapsedRealtime() - startedAt) / 1000}s），开始注入")
                }
                AppState.setFridaStatus("注入中（frida-core → frida-server）…")
                val err = FridaCore.attachAndLoad(FRIDA_SERVER, pid, agent.absolutePath, eternalize = true)
                if (err.isEmpty()) {
                    lastPid = pid
                    launchTries = 0                  // 游戏活了（被注入成功）→ 调起计数清零
                    injectFails = 0
                    // ★学习型等待（2026-10-21 用户）：这次几秒注入成功，之后都按这个等
                    val ok = (lastSettleMs / 1000).toInt().coerceIn(DEFAULT_SETTLE_SEC, MAX_SETTLE_SEC)
                    if (ok != settleSec) {
                        AppState.addLog("注入成功 ⇒ 该设备的注入前等待固化为 ${ok}s（以后默认）")
                        saveSettleSec(ctx, ok)
                    }
                    AppState.addLog("已让 frida-server 把 agent 注入进 pid=$pid（脚本 ${agent.length()} 字节）")
                    AppState.setFridaStatus("注入完成，等 agent 回连…")
                } else {
                    injectFails++
                    AppState.addLog("注入失败（第 $injectFails 次）：$err")
                    AppState.setFridaStatus("注入失败：${err.take(72)}")
                    // ptrace 类失败 => 进程大概率已僵死（2026-10-21 uuz 设备实测）：attach 写内存
                    //   失败后进程进 T 状态——gamePid 还看得见、maps 里却连 libil2cpp 都没有，
                    //   不自愈就会卡在"等映射 120s"。这类错误立即杀游戏重开，别攒次数。
                    val fatal = err.contains("Unable to write to process memory") ||
                        err.contains("No such process")
                    if (fatal || injectFails >= MAX_INJECT_FAILS) {
                        injectFails = 0
                        // 注入失败 ⇒ 该设备的等待 +1s 并持久化（下次注入前多等一会儿）
                        val up = (settleSec + 1).coerceAtMost(MAX_SETTLE_SEC)
                        if (up != settleSec) {
                            AppState.addLog("注入失败 ⇒ 该设备的注入前等待 ${settleSec}s → ${up}s（已记住）")
                        }
                        saveSettleSec(ctx, up)
                        AppState.addLog(
                            if (fatal) "注入失败且进程多半已僵死 → 自愈：杀掉游戏重开"
                            else "连续 $MAX_INJECT_FAILS 次注入失败 → 自愈：杀掉游戏重开",
                        )
                        GameProcess.kill()
                        lastPid = -1                 // 下一轮走"游戏没在跑，调起它"
                        delay(1_500)
                        continue
                    }
                    delay(5_000)
                    continue
                }
            }
            // 自愈（★2026-10-16 改：**先原地重注入，实在不行才杀游戏**）：agent 掉线但游戏还活着（pid 没变）
            //   ⇒ 先假设"只是 agent 死在进程里"，对**同一 pid** 重新 attach + 重挂脚本（frida-server 还在）。
            //   只有重注入 [REINJECT_TRIES] 次都连不回来，才认定进程里的坏状态无可救药 → 杀游戏重开。
            //   ⚠为什么这条改动重要：原逻辑 20s 后直接 kill ⇒ **战斗中 agent 一掉线就把这一局废掉**
            //   （用户 2026-10-16："战斗中你切进程干什么"）。
            if (lastPid > 0 && !AgentBridge.connected.value) {
                val nowMs = SystemClock.elapsedRealtime()
                if (agentLostSince == 0L) {
                    agentLostSince = nowMs
                } else {
                    val lost = nowMs - agentLostSince
                    val deadLine = AGENT_LOST_MS + REINJECT_TRIES * REINJECT_GAP_MS
                    if (!reinjectDead && lost > AGENT_LOST_MS && lost <= deadLine) {
                        // ── 中间档：原地重注入（不杀游戏）──
                        if (nowMs - lastReinjectAt >= REINJECT_GAP_MS) {
                            lastReinjectAt = nowMs
                            reinjectN++
                            AppState.addLog(
                                "agent 掉线 ${lost / 1000}s，游戏（pid=$lastPid）还活着 → **原地重注入**" +
                                    "（第 $reinjectN/$REINJECT_TRIES 次，先不杀游戏）",
                            )
                            // ★限时（见 [REINJECT_TIMEOUT_MS]）：进程僵住时 frida 自己会等 25s，不能陪它等。
                            val err = withTimeoutOrNull(REINJECT_TIMEOUT_MS) {
                                FridaCore.attachAndLoad(FRIDA_SERVER, lastPid, agent.absolutePath, eternalize = true)
                            } ?: "超时（${REINJECT_TIMEOUT_MS / 1000}s 没回来）"
                            AppState.addLog(
                                if (err.isEmpty()) "原地重注入已完成 → 等 agent 回连（这一局保住了）"
                                else "原地重注入失败：$err",
                            )
                            // ★失败信息里带"超时/Timeout"⇒ 进程已经僵了（frida 连脚本都建不出来），
                            //   重试只是白等 ⇒ 立刻改判"杀游戏重开"（2026-10-17 实测省下 ~25s）。
                            if (err.contains("超时") || err.contains("Timeout")) {
                                reinjectDead = true
                                AppState.addLog("重注入是**超时**失败 ⇒ 判定进程已僵，不再重试，直接杀游戏重开")
                            }
                        }
                    } else if (reinjectDead || lost > deadLine) {
                        agentLostSince = 0L
                        lastReinjectAt = 0L
                        AppState.addLog(
                            "agent 掉线 ${lost / 1000}s，原地重注入 $reinjectN 次仍连不回来 → 杀掉游戏重来（自愈）",
                        )
                        reinjectN = 0
                        reinjectDead = false
                        GameProcess.kill()
                        lastPid = -1
                        delay(1_500)
                        continue
                    }
                }
            } else {
                agentLostSince = 0L
                lastReinjectAt = 0L
                reinjectN = 0
                reinjectDead = false
            }
            delay(2_000)
        }
    }

    /**
     * 等"可以安全注入"（PC 版 `step_wait_ready` + `ATTACH_DELAY` 的同款语义）。
     *
     *   ① 主判据（需要 root）：轮询 `/proc/<pid>/maps` 直到出现 `libil2cpp.so`，再静置 SETTLE_MS；
     *   ② 兜底（拿不到 root）：退回"进程存活时长 >= MIN_GAME_AGE_MS"这个时间口径。
     *
     * @return true = 现在可以注入了
     */
    /** @return null = 可以注入了；非 null = 失败原因（人话，主循环据此决定要不要杀游戏重开） */
    private suspend fun waitGameReady(pid: Int, startedAt: Long): String? {
        if (rootUsable()) {
            AppState.setFridaStatus("等 $KEY_SO 映射（pid=$pid）…")
            val why = waitIl2CppMapped(pid)
            if (why != null) {
                AppState.addLog("等就绪失败：$why")
                AppState.setFridaStatus(why.take(60))
                return why
            }
            val ms = SystemClock.elapsedRealtime() - startedAt
            // ★自适应静置（2026-10-21 uuz 实测：低端模拟器启动慢，映射出现得晚 = 初始化更没底，
            //   这时候 attach 会把进程搞成僵死态）；注入失败过的（settleExtraMs）也再加码。
            val settle = settleSec * 1000L +
                if (ms > SLOW_BOOT_MS) SLOW_BOOT_EXTRA_MS else 0
            lastSettleMs = settle                 // 记下本次实际静置（注入成功后固化为默认值）
            AppState.addLog(
                "$KEY_SO 已映射（游戏起了 ${ms / 1000}s）→ 静置 ${settle / 1000}s 再注入" +
                    (if (ms > SLOW_BOOT_MS) "（慢启动设备 +${SLOW_BOOT_EXTRA_MS / 1000}s）" else "") +
                    (if (settleSec != DEFAULT_SETTLE_SEC) "（该设备学过：注入前等 ${settleSec}s）" else ""),
            )
            delay(settle)
            return null
        }
        val age = SystemClock.elapsedRealtime() - startedAt
        val waitMs = MIN_GAME_AGE_MS - age
        if (waitMs > 0) {
            AppState.setFridaStatus("游戏刚起来，再等 ${waitMs / 1000}s 注入…")
            AppState.addLog("注入闸门（时间兜底）：游戏已运行 ${age / 1000}s → 再等 ${waitMs / 1000}s")
            delay(waitMs)
            return "游戏刚起来（时间兜底闸门），下轮再看"
        }
        return null
    }

    /** root 能不能用（探测一次就记住，失败不每轮重试）。 */
    private suspend fun rootUsable(): Boolean {
        if (rootUnavailable) return false
        val ok = RootShell.detect() != null
        if (!ok) {
            rootUnavailable = true
            AppState.addLog("拿不到 root → maps 判据用不了，退回时间兜底闸门")
        }
        return ok
    }

    /**
     * 轮询到 `libil2cpp.so` 出现在 `/proc/<pid>/maps` 里。
     * @return null = 已映射；否则是失败原因（人话）
     *
     * ★必须用 root 读（`su -c grep …`）：普通 uid 读别人的 maps 是 Permission denied，
     *   那样 grep 永远没输出、一路等到超时 —— PC 版注释里记过这个坑（2026-10-10）。
     * `grep -m1` 只取第一条，输出极小。
     */
    private suspend fun waitIl2CppMapped(pid: Int): String? {
        val t0 = SystemClock.elapsedRealtime()
        var said = false
        while (true) {
            val dt = SystemClock.elapsedRealtime() - t0
            val r = RootShell.exec("grep -m1 $KEY_SO /proc/$pid/maps", 8_000L)
            if (r.out.contains(KEY_SO)) return null
            if (r.text.contains("No such file") || r.text.contains("No such process")) {
                return "进程提前退出（pid $pid 没了）"
            }
            // 进程还在、so 却一直不映射：看一眼进程状态，T/D/t（ptrace 挂起/僵死）=> 判定坏状态
            //   （2026-10-21 uuz 实测：attach 写内存失败后进程卡 T 状态，maps 停在注入前，
            //    傻等映射要 120s 超时 —— 早发现早杀游戏重开）
            if (dt > 3_000) {
                val st = RootShell.exec("awk '{print \$3}' /proc/$pid/stat", 3_000L)
                val state = st.out.trim()
                if (state == "T" || state == "D" || state == "t") {
                    return "进程僵死（pid $pid 状态=$state，注入 ptrace 失败留下的坏状态）"
                }
            }
            if (dt >= READY_TIMEOUT_MS) {
                return "等 $KEY_SO 映射超时（${READY_TIMEOUT_MS / 1000}s；最后输出：${r.text.take(80)}）"
            }
            if (!said && dt > 8_000) {
                // PC 版同款：8 秒还没读到就把"实际读到什么"打一次（读不到往往是 root 没生效）
                said = true
                AppState.addLog("…已等 ${dt / 1000}s 仍未映射：rc=${r.exit} out=${r.out.trim().take(40)} err=${r.err.trim().take(80)}")
            }
            delay(MAPS_POLL_MS)
        }
    }

    /**
     * frida-server 在听就直接用；不在听就走"自带的那份"：
     *   assets 里释放 → root 拷到 /data/local/tmp → root 拉起（见 FridaServerInstaller）。
     */
    private suspend fun ensureFridaServer(ctx: Context): Boolean {
        if (portOpen()) return true
        AppState.addLog("frida-server 没在听 $FRIDA_SERVER，走自带的那一份…")
        return FridaServerInstaller.ensure(ctx, FRIDA_SERVER_PORT)
    }

    private fun portOpen(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", FRIDA_SERVER_PORT), 800) }
        true
    }.getOrDefault(false)

    /**
     * ★安全杀注入器（2026-10-26）：原来是裸的 `kill -9 $(cat inject.pid)`。
     *
     * 那个 pid 属于**上一次会话**的注入器，进程早退了 ⇒ 这个 pid 可能已经被系统**复用给任何进程**，
     * 而我们手里是 **root** —— 杀错一次就是"跑着跑着工具/游戏直接消失"，而且一点日志都不会留
     * （SIGKILL 不给人收尾的机会）。这与用户反馈的 Android 15 症状高度吻合，所以先堵住。
     *
     * 现在先读 `/proc/<pid>/cmdline` 校验身份，只有确实是注入器才杀。
     */
    private fun killInjectorCmd(pf: File): String {
        val me = android.os.Process.myPid()
        return "p=\$(cat ${pf.absolutePath} 2>/dev/null); " +
            "if [ -n \"\$p\" ] && [ \"\$p\" != \"$me\" ] && " +
            "grep -qa 'huabanxie-inject' /proc/\$p/cmdline 2>/dev/null; then " +
            "kill -9 \$p; echo \"已杀掉旧注入器 pid=\$p\"; " +
            "else echo \"跳过 kill（pid=\${p:-空} 已不是注入器，可能被系统复用）\"; fi; " +
            "rm -f ${pf.absolutePath}"
    }

    /**
     * 游戏 pid（尽量免 root，顺序）：
     *   ① 本地 `pidof`（app 能不能看到别的 uid 的进程看设备配置）
     *   ② **借 frida-core 的进程枚举**（frida-server 在跑就已经是全量的，最稳）
     *   ③ 兜底才用 root 的 pidof
     */
    private suspend fun gamePid(): Int {
        val plain = runCatching {
            ProcessBuilder("pidof", GAME_PKG).start().inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")
        plain.toIntOrNull()?.let { if (it > 0) return it }

        // 注意：这个函数每 2 秒被调一次，**别在里面打日志**（会刷屏 —— 实测踩过）
        val viaFrida = FridaCore.findPid(FRIDA_SERVER, GAME_PKG)
        if (viaFrida > 0) return viaFrida

        return RootShell.exec("pidof $GAME_PKG", 8_000L).out.trim().toIntOrNull() ?: -1
    }

    /** 等游戏进程出现（调起之后用）。@return pid；-1 = 超时没等到 */
    private suspend fun waitForGamePid(timeoutMs: Long): Int {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            delay(1_000)
            val p = gamePid()
            if (p > 0) return p
        }
        return -1
    }

    private suspend fun loop(ctx: Context) {
        AppState.setFridaStatus("准备 agent 文件…")
        val agent = try {
            agentFile(ctx)
        } catch (t: Throwable) {
            AppState.setFridaStatus("拼 agent 脚本失败：${t.message}")
            return
        }
        AppState.addLog("agent 脚本已就绪（${agent.length()} 字节）")

        AgentBridge.start()

        if (USE_FRIDA_CORE) {
            // ★两条腿必须并行：注入监督那条是 while(true) 的常驻循环（游戏没起来就等、
            //   pid 变了就重注），串行写的话"读页面名"永远轮不到 —— P1 第一次联调就卡死在这。
            coroutineScope {
                launch { injectViaFridaCore(ctx, agent) }
                pollLoop()
            }
            return
        }
        if (SELF_INJECT) {
            AppState.setFridaStatus("申请 root…（若弹授权框请选「允许」）")
            if (RootShell.detect() == null) {
                AppState.setFridaStatus("root 未授权：点「注入 Frida」再来一次")
                return
            }

            val pf = File(ctx.filesDir, "inject.pid")
            pidFile = pf
            RootShell.exec(killInjectorCmd(pf), 8_000L)

            val gamePid = RootShell.exec("pidof $GAME_PKG", 8_000L).out.trim()
            if (gamePid.isEmpty()) {
                AppState.setFridaStatus("游戏没在跑（$GAME_PKG），先启动游戏")
                return
            }
            AppState.addLog("游戏 pid=$gamePid，开始注入…")

            val cmd = "echo \$\$ > ${pf.absolutePath}; exec $INJECT_BIN -p $gamePid -s ${agent.absolutePath}"
            proc = RootShell.spawn(cmd) { line -> AppState.addLog("[inject] $line") }
            if (proc == null) {
                AppState.setFridaStatus("注入器没起来（看日志）")
                return
            }
            AppState.setFridaStatus("注入器已起，等 agent 回连…")
        } else {
            AppState.setFridaStatus("等外部注入（agent 回连）")
            AppState.addLog("app 自身不注入：实测本游戏吃 frida-inject 会被反作弊打死，改走「外部注入 + 回连」")
        }

        pollLoop()
    }

    /**
     * "读页面名"那条腿：agent 连上后一轮轮问它当前在哪一页（两种注入方式共用）。
     * 取消靠 delay() 抛 CancellationException（同 AutoForegroundService）。
     */
    private suspend fun pollLoop() {
        var lastPage: String? = null
        var lastPageTop: String? = null
        var toldConnected = false
        var tick = 0
        var misses = 0
        while (true) {                            // 取消靠 delay() 抛 CancellationException（同 AutoForegroundService）
            delay(POLL_MS)
            if (!AgentBridge.connected.value) {
                if (toldConnected) {
                    toldConnected = false
                    AppState.addLog("agent 掉线，等它重连…")
                }
                AppState.setFridaStatus(
                    when {
                        // 没在跑迷宫 → 没注入，谈不上"等外部注入"，就明说待命
                        !autoRecover -> "待命（未跑迷宫，不注入游戏）"
                        SELF_INJECT -> "注入器在跑，agent 未回连"
                        else -> "跑迷宫中，等 agent 回连…"
                    }
                )
                continue
            }
            if (!toldConnected) {
                // 跟 PC 版一致：先等 agent 的 il2cpp 就绪（它的 ready 导出 = !!g_il2cpp），
                // 没就绪就调 labStatus 只会拿到 null。
                val r = AgentBridge.call("ready", timeoutMs = 4_000L)
                if (r == null || !r.optBoolean("data", false)) {
                    AppState.setFridaStatus("agent 已连，等 il2cpp 就绪…")
                    continue
                }
                toldConnected = true
                AppState.addLog("agent 已回连（il2cpp 就绪），开始读页面名")
                // ★★"再晚点挂"（2026-10-17 用户："再晚点挂就行了吧"）：il2cpp 就绪 ≠ **游戏加载完**。
                //   实测（09:04）注入后 2s、页面还是 `NONE(0)` 时那一拍最危险 —— 我们在那儿做过 UI 遍历
                //   ⇒ 把游戏卡死。虽然调用侧已加"加载/瞬态页不做遍历"，这里再**静置 [POST_INJECT_SETTLE_MS]**
                //   才开始读页面（纯保险 ✓，代价只有这几秒 ✓）。
                delay(POST_INJECT_SETTLE_MS)
            }
            // ① viewId：便宜、任何页面都有 —— 状态机的分派判据（PC 版也是用它）
            val vidRes = AgentBridge.call("viewId", timeoutMs = 3_000L)
            if (vidRes == null) {
                misses++
                if (misses >= 3) {
                    AgentBridge.noteStale("viewId 连续 $misses 次超时")
                    misses = 0
                }
                AppState.setFridaStatus("agent 已连，但读视图超时")
                continue
            }
            misses = 0
            val vid = if (vidRes.optBoolean("ok", false)) vidRes.optInt("data", -1) else -1
            val viewText = if (vid >= 0) Navigator.viewLabel(vid) else "?($vid)"
            AppState.setViewLabel(viewText)

            // ② labStatus：贵（含堆扫描），而且**只在迷宫内有内容**（TITLE/HOME 返回 null），
            //    所以隔几轮读一次，只用来拿"最上层对话框名"。
            tick++
            // ★只在迷宫内调 labStatus：它含全堆扫描（贵），而且**只在迷宫内有内容**
            //（PC 版同样只在 maze_loop 里调；实测标题页每 ~6s 一次全堆扫描纯属浪费）。
            // ★去重（2026-10-16）：跑迷宫时 `MazeLoop` 主循环每轮都在读同一份 labStatus，
            //   这里再读一次纯属重复（PC 只有一个循环，没这问题）⇒ 主循环刚读过就跳过本轮。
            //   主循环一停，时间戳过期，仪表盘的"最上层对话框"读取自动恢复（不用开关/try-finally）。
            // ★只在**264（迷宫地图）**上轮询（2026-10-19 用户："刷开局阶段暂停地图轮询"）：
            //   公会选择(263)/确认页也在 260~265 里，但那时根本没有地图，读了只会退化成堆扫描。
            if (tick % 3 == 0 && vid == 264 && !MazeLoop.statusFresh()) {
                val res = AgentBridge.call("labStatus", timeoutMs = 5_000L)
                val data = if (res != null && res.optBoolean("ok", false)) res.optJSONObject("data") else null
                val page = data?.optString("pageTop")?.let { if (it.isBlank() || it == "null") "（无对话框）" else it }
                if (page != null) {
                    AppState.setPageName(page)
                    if (page != lastPageTop) {          // 只在变化时记一行（否则每 ~4.5s 刷一条）
                        lastPageTop = page
                        AppState.addLog("最上层对话框 → $page")
                    }
                }
            }
            if (viewText != lastPage) {
                lastPage = viewText
                AppState.addLog("页面 → $viewText")
            }
            AppState.setFridaStatus("运行中（agent 已连）")
        }
    }
}
