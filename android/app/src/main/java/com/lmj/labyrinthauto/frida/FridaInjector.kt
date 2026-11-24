package com.lmj.labyrinthauto.frida

import android.content.Context
import android.os.SystemClock
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.input.Foreground
import com.lmj.labyrinthauto.logic.MazeLoop
import com.lmj.labyrinthauto.logic.Navigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * ★目标客户端包名不再写死（2026-10-30 用户："一键黎明界写死了 B 服"）。
     * 一律取 [GameProcess.pkg]：服务收到意图时已按**当前选中账号**的渠道同步过，
     * 渠道服（`com.bilibili.priconne.yofun.mumu` 等）不会再有"没装国服"的误判。
     */
    @Deprecated("改用 GameProcess.pkg()", ReplaceWith("GameProcess.pkg()"))
    val GAME_PKG: String get() = GameProcess.pkg()

    private const val INJECT_BIN = "/data/local/tmp/huabanxie-inject"
    private const val POLL_MS = 1_500L

    /**
     * 日志脱敏（2026-10-28 用户："日志不要显示下载的组件名，和所有的 huabanxie-server 字样"）。
     *
     * 注入器的 stdout/stderr 里会带真实二进制名和路径（`/data/local/tmp/huabanxie-inject` 之类），
     * 这些日志是用户会直接发出去的，所以统一换成「运行组件」。
     */
    private fun redact(text: String): String =
        text.replace(Regex("huabanxie[\\w.\\-]*"), "运行组件")

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

    /**
     * ★★2026-11-11（用户）：不再有"注入前固定静置"。
     *
     * 旧做法是「libil2cpp.so 映射后再等 6s」——但映射 ≠ il2cpp 运行时初始化完成，
     * 好设备白等 6s，慢设备又可能不够。现在改为：
     *   映射 → 立即注入 → agent 每 250ms 检查 `il2cpp_domain_get()` 非空才算 ready
     *   → 上层等 `ready`（上限 [IL2CPP_READY_TIMEOUT_MS]）。
     * 这样"能不能用"由运行时事实决定，不再拿固定时间赌。
     */
    private const val IL2CPP_READY_TIMEOUT_MS = 30_000L

    /**
     * ★2026-11-24（雷电连崩反馈）：状态闸门（so r-xp + global-metadata + 前台）全满足后
     * **再静置 4s** 才 attach —— 避开 il2cpp_init 刚完成、引擎还在跑初始化最脆弱的窗口。
     * 从 2s 调到 4s：实测 2s 仍崩的那次 metadata 只用了 206ms 就出现（引擎初始化刚起步），
     * 而成功的那次 metadata 用了 2441ms（自然多等了 2.2s）—— "快就绪"反而是危险信号。
     * 与旧版"从游戏打开瞬间算 6s"的区别：那是猜时间，这是状态到位才等，好设备也不白等太多。
     */
    private const val INJECT_SETTLE_MS = 4_000L

    /**
     * ★2026-11-12：注入前最多等这么久「前台变成游戏」。
     *   启动时会先弹 `com.android.permissioncontroller`（权限页）抢前台，游戏在后台时注入容易把游戏带走。
     *   这是**状态判定**（前台包名），不是固定 sleep；等到就立刻注入。
     */
    private const val FRONT_WAIT_BEFORE_INJECT_MS = 10_000L

    /** 等就绪的总超时（PC 版 step_wait_ready 的 timeout = 120） */
    private const val READY_TIMEOUT_MS = 120_000L

    /**
     * ★2026-11-12（用户："闪退恢复没有进程状态检测和重启"）：
     *   起游戏后这么久 `libil2cpp.so` 仍没出现在 maps ⇒ 判定"进程卡死/闪退残留"，
     *   立刻返回失败让注入循环 kill + launch（不再傻等 [READY_TIMEOUT_MS] 的 120s）。
     *   正常冷启动 2~5s 内就会映射，15s 留足慢设备余量。
     */
    // ★2026-11-22：15s→45s —— 慢模拟器（D 状态加载大 so）15 秒常常不够，会被误判"卡死"而反复杀进程重启。
private const val IL2CPP_MAP_STUCK_MS = 45_000L

    /** 轮询 maps 的间隔（PC 版 0.5s；这里是设备内 `su -c`，稍缓一点免得频繁起进程） */
    private const val MAPS_POLL_MS = 800L

    /** 拿不到 root 时的兜底闸门（时间口径，见上） */
    private const val MIN_GAME_AGE_MS = 90_000L

    /** 调起游戏后等进程出现的上限 */
    private const val PID_WAIT_MS = 20_000L

    /**
     * 连续调起多少次都见不到游戏进程就放弃（避免"崩→拉起→崩"死循环刷屏）。
     * 游戏活过一轮（注入成功）后计数清零。
     */
    private const val MAX_LAUNCH_TRIES = 3

    /**
     * ★连续取不到几次 pid 才认定"游戏没了"（2026-11-02 实测）。
     *
     * 踩过的坑：10:04:52 游戏刚进战斗的那一瞬间，`gamePid()` 的三条路（app 内 `pidof`、
     * frida-server 枚举、root `pidof`）一起失败了一拍，于是**活着的**游戏被判成"崩了"：
     * am start（无效）→ 自愈重注入 → 停在战斗恢复页 `BATTLE(29)+DialogBackKeyCapture`
     * 不认识，35 秒后停手。单拍失败不能当死亡，等到 [POLL_MS]×3 ≈ 4.5 秒都取不到再说。
     */
    private const val PID_MISS_LIMIT = 3

    /** 连续注入失败多少次就杀游戏重开（PC 版的自愈路径） */
    private const val MAX_INJECT_FAILS = 3

    /**
     * ★连续"注入失败 → 杀游戏重开"多少圈就**暂停自愈**（2026-10-29 用户报障加）。
     *
     * 现象（`logs/魔法真步/session_20261029_100409.txt`）：设备陷入死循环 ——
     * ```
     * 已调起游戏 → 游戏已起来 pid=5500 → libil2cpp.so 已映射（起了 0s）→ 静置 15s
     * → 注入失败：attach 5500 失败：Unable to write to process memory: No such process
     * → 注入失败且进程多半已僵死 → 自愈：杀掉游戏重开  →（回到第一行，无限）
     * ```
     * 单轮必然杀游戏 ⇒ 只要失败原因是**持续性**的（游戏装的是 arm64 包只走 Houdini 转译、
     * 反作弊拦注入、运行组件与游戏 ABI 不匹配…），就会永远转圈：日志刷屏、反复 force-stop、
     * 烧电，用户只能看到游戏被连着杀。
     *
     * ⇒ 连撞 [MAX_INJECT_CYCLES] 次就停手 [CYCLE_PAUSE_MS]，把现场诊断和"该查什么"写进日志，
     *   决定权交回用户（他可以点停止，或换包/换设备）。**不解决根因也不至于无限重启。**
     */
    private const val MAX_INJECT_CYCLES = 3
    private const val CYCLE_PAUSE_MS = 120_000L

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

    /** 注入循环当前卡在哪一步（只给日志/诊断看，不参与决策；上层 90s 超时会读它）。 */
    @Volatile
    var stage: String = "未启动"
        private set

    /**
     * ★「这一代游戏的 agent 已连 + il2cpp ready」完成信号（2026-11-13）。
     *
     * 值是就绪当时的 [GameProcess.kills]；上层重启后等 `>= 本次 kill 后的代际`，
     * 而不是等裸 [AgentBridge.connected] —— 后者只证明有 loopback socket 连上，
     * 既不证明属于这一代进程，也不证明 il2cpp domain 已建好（pollLoop 里还要单独等 ready）。
     * 用代际而不是布尔，天然杜绝「上一代残留的 true 秒过等待」；-1 = 当前没有可用 agent。
     */
    private val _readyGen = MutableStateFlow(-1)
    val readyGen: StateFlow<Int> = _readyGen.asStateFlow()

    /**
     * 注入循环最近一次**成功注入**的 pid 与当时的游戏代际。
     * pollLoop 的 `ready` 只是 agent 端 il2cpp domain 好了，不能证明它属于这一代游戏：
     * 旧进程里 eternalize 的脚本重连也会让 ready 通过。发布 [readyGen] 前必须核对这两个值。
     */
    @Volatile
    var boundPid = -1
        private set

    @Volatile
    var boundGen = -1
        private set


    fun start(ctx: Context) {
        if (running) {
            AppState.addLog("注入已经在跑了（忽略重复请求）")
            return
        }
        job = scope.launch { loop(ctx.applicationContext) }
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
                RootShell.exec(killInjectorCmd(pf), 8_000L, direct = true)
            }
        }
        firstSeen.clear()
        boundPid = -1
        boundGen = -1
        _readyGen.value = -1
        AgentBridge.stop()
        AppState.setFridaStatus("已停止")
        AppState.setPageName(null)
    }

    // ────────────────────── 抓取（[AccessCapture]）用的门面 ──────────────────────
    //   抓 uid/access_key 走的是**另一条独立启动路径**（启动渠道客户端 → 注入 → 截 SDK 登录参数 → 退出），
    //   跟这里的常驻自愈循环不共用状态，所以只把几步"设备侧动作"单独暴露出去，别复制一份实现。

    /** 让 frida-server 就绪（没在听就用自带那份拉起来）。true = 端口已通 */
    suspend fun ensureServer(ctx: Context): Boolean = ensureFridaServer(ctx)

    /** 等 `libil2cpp.so` 映射（null = 好了；否则是人话原因）。抓取时注入前必须过这一关。 */
    suspend fun waitMapped(pid: Int): String? = waitIl2CppMapped(pid)

    /** 请 frida-server 把 agent 注入 pid（空串 = 成功）。 */
    suspend fun inject(pid: Int, agent: File): String =
        FridaCore.attachAndLoad(FRIDA_SERVER, pid, agent.absolutePath, eternalize = true)

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
        var pidMisses = 0                 // ★连续几次取不到 pid（单拍失败不算死，见 [PID_MISS_LIMIT]）
        var launchTries = 0
        var injectFails = 0
        var injectCycles = 0              // ★"注入失败 → 杀游戏重开"的连续圈数（见 [MAX_INJECT_CYCLES]）
        var serverRestarts = 0            // ★cs128：注入失败时"重启运行组件"已连做几次（防重启死循环）
        var agentLostSince = 0L          // 非 0 = 正处在"agent 掉线"状态（自愈计时）
        var lastReinjectAt = 0L          // 上次"原地重注入"的时刻（间隔节流）
        var reinjectN = 0                // 本次掉线已原地重注入几次
        var reinjectDead = false         // ★重注入"超时"失败 ⇒ 进程已僵，别再试，直接杀游戏（见 [REINJECT_TIMEOUT_MS]）
        var serverReady = false          // 本次跑迷宫期间 frida-server / libfrida 是否已就绪
        var wasRecover = false           // 上一轮 autoRecover 是否开着（用于检测"刚进/刚出"跑迷宫）
        // ★2026-11-13：自愈 kill 之后，下一拍必须真的走「调起游戏」分支。
        //   `am force-stop` 是异步的，残留/僵死 pid 仍会被 gamePid() 读到 ⇒ 旧逻辑认为"游戏在跑"，
        //   永远不调 am start，只对着坏 pid 反复等映射，直到上层 90s 超时停手（见事故诊断包）。
        var forceLaunch = false
        /** 本次自愈杀掉的坏 pid：forceLaunch 期间仍被 [gamePid] 读到就一律当残留忽略。 */
        var stalePid = -1
        // ★架构闸门（2026-10-29）：每次进城只查一次（dumpsys 也有开销）。见 [AbiRepair.ensure]。
        var abiChecked = false
        // ★确定性修不了（包内无 x86_64 / 没装 / 没 root）⇒ 本次进城彻底静默，别再折腾游戏。
        var abiBlocked = false
        // ★pid 误读只报一次/局（2026-11-03，见下面 pid 校验；每拍刷屏没意义）
        var saidPidOdd = 0
        var pidChangeTries = 0           // 连续「注入撞上旧 pid」的次数（见 [InjectRecoveryPolicy.pidChanged]）

        /** 「agent 还连着」必须同时满足：连在这个 pid 上、且这期间没发生过 kill（代际没变）。 */
        fun agentAliveFor(pid: Int): Boolean =
            pid > 0 && AgentBridge.connected.value && boundPid == pid && boundGen == GameProcess.kills

        /** 全量重置（重注入路径用）：连同 bound 信息一起清。 */
        fun resetLoopState() {
            lastPid = -1
            pidMisses = 0
            launchTries = 0
            injectFails = 0
            injectCycles = 0
            pidChangeTries = 0
            agentLostSince = 0L
            lastReinjectAt = 0L
            reinjectN = 0
            reinjectDead = false
            forceLaunch = false
            stalePid = -1
            boundPid = -1
            boundGen = -1
            saidPidOdd = 0
            firstSeen.clear()
            _readyGen.value = -1
        }

        /**
         * ★软重置（2026-11-23，用户："同一个 pid 没必要拆"）：只清上一局的**失败计数 / 自愈状态**，
         * **保留 `lastPid` / `boundPid` / `boundGen`** —— 局间不杀进程后，同一个 pid 重拆重注入
         * 纯属浪费（~2s），还会把回标题链的直调投进"agent 刚回连、游戏主线程忙"的窗口
         * （实测 10 次开局 4 次"投递超时"→ 白白回退杀进程）。bound 信息是否真的可用由
         * 开闸时的 reuse 检查决定（语义 = `agentAliveFor`：在线 + kill 代际没变）。
         */
        fun softResetLoopState() {
            pidMisses = 0
            launchTries = 0
            injectFails = 0
            injectCycles = 0
            pidChangeTries = 0
            agentLostSince = 0L
            lastReinjectAt = 0L
            reinjectN = 0
            reinjectDead = false
            forceLaunch = false
            stalePid = -1
            saidPidOdd = 0
            firstSeen.clear()
        }

        while (true) {
            // ★注入只服务于「自动黎明界」：总闸关着就**待命** —— 不调起游戏、不注入、不自愈、不读页。
            //   这样"助手开着"也绝不碰游戏进程（用户 2026-10-16 要求：关着时没必要注入）。
            if (!autoRecover) {
                if (wasRecover) {
                    wasRecover = false
                    serverReady = false
                    abiBlocked = false               // 下次进城重新给一次机会（用户可能换了包 / 给了 root）
                    // ★软重置：保留 bound 信息 —— 关闸期间游戏没死的话，开闸直接沿用现有注入。
                    softResetLoopState()
                    stage = "待命（未跑迷宫）"
                    AppState.addLog("自动黎明界结束 → 停止注入/自愈，之后不再碰游戏进程（待命）")
                }
                AppState.setFridaStatus("待命（未跑迷宫，不注入游戏）")
                delay(POLL_MS)
                continue
            }
            if (!wasRecover) {
                wasRecover = true
                abiChecked = false               // 新一轮进城 → 重新确认一次游戏架构
                abiBlocked = false
                // ★沿用检查（2026-11-23，用户："同一个 pid 没必要拆"）：上一局进程没被 kill
                //   （代际没变）、agent 还在线 ⇒ 完整沿用现有注入，只清失败计数。
                //   任何一条不满足（杀过进程 / agent 掉了）⇒ 全量重置走重注入，行为同旧版。
                val reusePid = boundPid
                val canReuse = reusePid > 0 && boundGen == GameProcess.kills && AgentBridge.connected.value
                if (canReuse) {
                    softResetLoopState()
                    lastPid = reusePid           // 本拍 pid == lastPid ⇒ 跳过"等映射 / 重注入"
                    stage = "沿用现有注入（pid=$reusePid）"
                    AppState.addLog("上一局进程没动（pid=$reusePid）且 agent 在线 → 沿用现有注入，不重拆重注")
                } else {
                    resetLoopState()
                    stage = "接管中"
                    AppState.addLog("进入自动黎明界 → 接管游戏进程（调起 → 等就绪 → 注入；崩溃/卡死自愈）")
                }
            }

            // ★窗口内不再"整体停摆"：serverReady / 架构闸门 / 等就绪 / 注入都照常做，
            //   只有「pid<=0 时自己 am start」那一下被压住（见下面）。这样局内刷那种
            //   长时间窗口也不会挡住 agent 回连，又能保证启动意图唯一。

            // frida-server / libfrida 就绪检查（**只在开闸后做**；失败记一次、下轮重试，不 return 掉整个循环，
            // 这样每次跑迷宫都会重新尝试接管）
            if (!serverReady) {
                FridaCore.logAvailability()
                if (!FridaCore.available) {
                    // ★ARM 真机：桥接库本来就只打了 x86_64，这里别报成"加载失败，去看日志"，
                    //   直接说清"这套工具链不支持这台设备"（2026-10-30）。
                    AppState.setFridaStatus(
                        if (AbiRepair.deviceSupportsX86_64()) {
                            "libfrida_bridge.so 没加载上（看日志）"
                        } else {
                            "这台设备的 CPU 是 ${AbiRepair.deviceAbiText()}：只支持 x86_64 模拟器，ARM 真机暂不支持"
                        },
                    )
                    delay(POLL_MS)
                    continue
                }
                if (!ensureFridaServer(ctx)) {
                    AppState.setFridaStatus("运行组件没起来，注入没法做")
                    delay(POLL_MS)
                    continue
                }
                serverReady = true
            }

            // ★架构闸门（2026-10-30 修订）：进城（autoRecover 打开 = 用户点了某个"接管游戏"的入口）
            //   之后的**第一拍无条件**确认 ABI，不再挂在 `pid <= 0` 上。
            //
            //   为什么可以无条件：ARM 模式下 agent 根本连不上（`Navigator.currentViewId()` 恒 -1、
            //   MazeLoop 也跑不起来），所以"游戏已经在迷宫里跑着，别打断它"这个顾虑不成立 ——
            //   那个状态我们压根感知不到。而 x86_64 下 `ensure()` 只读一次 dumpsys、零副作用。
            //   ⇒ 唯一会被杀进程的组合是"arm64 + 游戏在跑"，而那本来就等于什么都做不了。
            //
            //   放这里而不是各入口各写一遍：一键黎明界 / 只刷开局 / 进迷宫 / 走到黎明界 top
            //   全部覆盖，且不依赖 1.5s 轮询的相位。
            if (!abiChecked) {
                abiChecked = true
                val fix = AbiRepair.ensure(GameProcess.pkg())
                if (!fix.ok) {
                    AppState.addLog("游戏架构检查未通过：${fix.detail}")
                    AppState.setFridaStatus("架构不可用：${fix.detail.take(48)}")
                    if (fix.fatal) {
                        // ★确定性修不了 ⇒ 停手。再走下去必然是「等映射 120s → 注入失败 3 次
                        //   → 杀游戏 3 轮 → 暂停 2 分钟」，只会反复杀游戏刷屏，用户还拿不到结果。
                        AppState.addLog(
                            "⇒ 确定性原因（换全 ABI 的安装包 / 装游戏 / 给 root 才能解决）→ " +
                                "本次进城不注入、不自愈；处理完后重开「一键黎明界」会自动重试",
                        )
                        abiBlocked = true
                    }
                }
            }
            if (abiBlocked) {
                AppState.setFridaStatus("架构不可用，已停止注入（处理完重开一键黎明界）")
                delay(POLL_MS)
                continue
            }

            var pid = gamePid()
            // ★自愈闭环（2026-11-13 事故）：kill 之后 `am force-stop` 是异步的，残留 pid 会让下面
            //   `pid <= 0` 那个**唯一调 am start** 的分支进不去 ⇒ 每轮对着同一个坏进程白等 15s 映射，
            //   连等 4 轮直到上层 90s 超时停手。只在 forceLaunch 这个异常分支纠偏：
            //   正常路径连一次多出来的查询都没有，更没有新增等待。
            if (forceLaunch) {
                when {
                    // ★只有 agent 确实连在**这个 pid**、且期间没 kill 过，才算"进程活着"。
                    //   旧写法光看 connected：kill 之后滞后为 true 会把刚设的 forceLaunch 清掉。
                    agentAliveFor(pid) -> {
                        forceLaunch = false
                        stalePid = -1
                    }
                    pid > 0 && pid != stalePid -> {
                        AppState.addLog("自愈后游戏已换成新 pid=$pid（旧 $stalePid 退干净了）→ 按新进程继续等就绪")
                        forceLaunch = false
                        stalePid = -1
                    }
                    pid > 0 -> {
                        AppState.addLog("自愈后旧 pid=$pid 仍在（force-stop 残留）→ 不认它，重新调起游戏")
                        pid = -1
                        pidMisses = 0
                    }
                }
            }
            // ★2026-11-03 用户报障（12:26:42 那次"突然回到登录页"）：
            //   `gamePid()` 偶尔会返回**不是游戏**的 pid（实测拿到 6185 —— 那既不是 AMS 起过的进程、
            //   exit-info 里也没有，纯属误读：pidof/frida 枚举在内存吃紧、pushservice 反复起停时会吐垃圾）。
            //   旧逻辑把这种 pid 当"游戏换了实例"⇒ waitGameReady 立刻判"进程提前退出"⇒ force-stop
            //   把**健康在跑**的游戏杀掉 ⇒ 新实例停在标题/登录页，本局全废。
            //
            //   下面两道闸（顺序不能反）：
            //   ① **agent 还连着 ⇒ 游戏只可能是 lastPid**（agent 在进程内，连着就证明那个进程活着）；
            //   ② pid 变了必须先验 `/proc/<pid>/cmdline` 是不是包名本体（挡 :pushservice / su 包装 / 同名进程）。
            if (lastPid > 0 && pid != lastPid && agentAliveFor(lastPid)) {
                if (saidPidOdd == 0) {
                    saidPidOdd++
                    AppState.addLog("pid 读成 $pid，但 agent 还连在 pid=$lastPid 上 ⇒ 当误读，忽略（不换 pid、不杀游戏）")
                }
                pid = lastPid
            }
            if (pid > 0 && pid != lastPid) {
                val verified = verifyMainPid(pid, GameProcess.pkg())
                if (verified == false) {
                    if (saidPidOdd == 0) {
                        saidPidOdd++
                        AppState.addLog("pid $pid 不是游戏主进程（/proc/$pid/cmdline 不是 ${GameProcess.pkg()}）⇒ 当没取到")
                    }
                    pidMisses++
                    if (pidMisses < PID_MISS_LIMIT) {
                        delay(POLL_MS)
                        continue
                    }
                    // 连续几拍只看到"不是本体"的进程（如只剩 :pushservice）⇒ 当游戏没在跑，走下面调起
                    pid = -1
                    pidMisses = 0
                }
            }
            // ★单拍取不到不算死（见 [PID_MISS_LIMIT]）：进战斗 / 大加载的瞬间三路检测可能一起失败
            if (pid <= 0 && lastPid > 0) {
                pidMisses++
                if (pidMisses < PID_MISS_LIMIT) {
                    delay(POLL_MS)
                    continue
                }
            }
            if (pid > 0) pidMisses = 0
            if (pid <= 0) {
                // ★上层正在显式操作进程（kill / launch / 重装）：启动意图归它，这一拍不发 am start，
                //   只等下一拍看到 pid。窗口结束（或上层失败后 reset）时 forceLaunch / pidMisses
                //   都还在，注入循环会照常补调起，不存在"没人拉起游戏"的死角。
                if (GameProcess.externalRestart) {
                    stage = "显式进程操作窗口：等上层调起游戏"
                    pidMisses = 0
                    delay(POLL_MS)
                    continue
                }
                // 开闸期间游戏没在跑 → 调起它（= "刷完开局 / 重启后调起游戏"的入口）
                if (lastPid > 0) {
                    AppState.addLog(
                        "游戏（pid=$lastPid）没了 —— 连续 $pidMisses 次取不到进程（崩了或被系统回收），重新调起它",
                    )
                    launchTries = 0                      // 它活过一轮 → 计数清零，允许重来
                }
                pidMisses = 0
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
                // ★forceLaunch 时排除刚 force-stop 的残留 pid：它还挂在 pidof 里，但不是新调起的游戏。
                //   复用原有 20s 等待窗口，不新增等待；正常路径 excludePid=-1，行为完全不变。
                val excludePid = stalePid
                forceLaunch = false
                stalePid = -1
                GameProcess.launch(ctx)
                pid = waitForGamePid(GameProcess.pkg(), PID_WAIT_MS, excludePid)
                if (!autoRecover) continue               // 等 pid 期间上层停手了 ⇒ 不碰游戏
                if (pid <= 0) {
                    AppState.addLog("调起后 ${PID_WAIT_MS / 1000}s 没看到游戏进程（第 $launchTries 次）")
                    stalePid = excludePid
                    forceLaunch = true                // 下轮仍强制重新调起，别退回"对着残留 pid 等映射"
                    delay(3_000)
                    continue
                }
                AppState.addLog("游戏已起来 pid=$pid（第 $launchTries 次调起）")
            }
            if (pid != lastPid) {
                // 记下"app 第一次看到这个 pid 的时刻"（时间兜底口径 + 日志用）
                val startedAt = firstSeen.getOrPut(pid) { SystemClock.elapsedRealtime() }
                // ★启动期闸门：等到"可以安全注入"（主判据 = libil2cpp 已映射，见常量注释）
                //   —— 等 il2cpp 映射后**立即注入**；真正的"il2cpp 可用"由 agent 轮询 domain，上限 30s
                stage = "等 libil2cpp 映射（pid=$pid）"
                val readyWhy = waitGameReady(pid, startedAt)
                // ★总闸可能在这段等待里被上层关掉（90s 超时停手）⇒ 别拿结果继续 kill / 注入
                if (!autoRecover) continue
                if (readyWhy != null) {
                    // ★进程已坏 ⇒ 别傻等，杀游戏重开（2026-10-21 uuz 实测）。
                    //   2026-11-13 补：判据统一交给 [InjectRecoveryPolicy]（纯函数、有单测）——
                    //   事故里那句「15s 内 libil2cpp.so 一直没映射（进程卡死/闪退残留）」旧判据漏了，
                    //   于是对同一个坏 pid 连等 4 轮 15s，直到上层 90s 超时停手。
                    if (InjectRecoveryPolicy.badProcess(readyWhy)) {
                        // ★2026-11-03 兜底闸（2026-11-13 收紧）：只有 agent 确实连在**这个 pid**、
                        //   且期间没 kill 过，才说明进程活着。kill 后 connected 滞后为 true 时，
                        //   旧写法会跳过自愈，对着坏 pid 一轮轮等 15s 映射直到上层 90s 停手。
                        if (agentAliveFor(pid)) {
                            AppState.addLog("等就绪异常（$readyWhy），但 agent 还连在 pid=$pid 上 ⇒ 不杀游戏，继续跑")
                            delay(POLL_MS)
                            continue
                        }
                        stage = "等就绪判坏进程（$readyWhy）→ 自愈杀游戏重开"
                        launchTries = 0
                        GameProcess.kill()
                        lastPid = -1
                        forceLaunch = true
                        stalePid = pid
                        waitGameGone()
                        continue
                    }
                    delay(2_000)
                    continue
                }
                if (lastPid <= 0) {
                    AppState.addLog("找到游戏 pid=$pid（起了 ${(SystemClock.elapsedRealtime() - startedAt) / 1000}s），开始注入")
                }
                stage = "注入 pid=$pid"
                AppState.setFridaStatus("注入中（运行组件）…")
                // ★V7（2026-10-30）：首次注入原来**没有超时** —— 进程僵住时会一直等到 frida 内部超时
                //   （实测 25s），把"决定重试/杀游戏"整整拖后。这里补上限：取重注入上限的 2 倍
                //   （首次 attach 要建 session + load script，比同 pid 重注入慢一点，别误杀）。
                val injectTimeout = REINJECT_TIMEOUT_MS * 2
                val err = withTimeoutOrNull(injectTimeout) {
                    FridaCore.attachAndLoad(FRIDA_SERVER, pid, agent.absolutePath, eternalize = true)
                } ?: "超时（${injectTimeout / 1000}s 没回来）"
                if (!autoRecover) continue               // 关闸期间别再按注入结果杀/拉游戏
                if (err.isEmpty()) {
                    lastPid = pid
                    launchTries = 0                  // 游戏活了（被注入成功）→ 调起计数清零
                    injectFails = 0
                    injectCycles = 0
                    serverRestarts = 0
                    pidChangeTries = 0
                    forceLaunch = false
                    stalePid = -1
                    boundPid = pid
                    boundGen = GameProcess.kills     // 之后任何一次 kill 都会让这条连接失效
                    stage = "已注入 pid=$pid，等 agent 回连"
                    AppState.addLog("已让运行组件把 agent 注入进 pid=$pid（脚本 ${agent.length()} 字节）")
                    AppState.setFridaStatus("注入完成，等 agent 回连…")
                } else {
                    injectFails++
                    stage = "注入失败（第 $injectFails 次）：${err.take(60)}"
                    AppState.addLog("注入失败（第 $injectFails 次）：$err")
                    AppState.setFridaStatus("注入失败：${err.take(72)}")
                    // ★cs128（2026-11-21 实测：上一局游戏被 force-stop 时 eternalize 会话随进程死掉，
                    //   frida-server 被卡死 —— 端口还在 LISTEN ⇒ ensureFridaServer/portOpen 判"活着"
                    //   永远不会重启它；后续 attach 连 3 次 16s 超时，最后整局停手）。
                    //   注入失败先探**运行组件**是否还响应；僵了 ⇒ 重启组件、本轮不杀游戏
                    //   （杀游戏治不了组件的病），下一轮直接重注入。
                    if (!fridaServerAlive()) {
                        if (serverRestarts < 2) {
                            serverRestarts++
                            AppState.addLog(
                                "注入失败且运行组件没响应（探测 systemui 5s 无回包）⇒ 组件僵了 → " +
                                    "重启运行组件（第 $serverRestarts 次，不杀游戏）",
                            )
                            stage = "重启运行组件"
                            val ok = FridaServerInstaller.restart(ctx, FRIDA_SERVER_PORT)
                            AppState.addLog(
                                if (ok) "运行组件已重启 → 下一轮重注入（pid 不变不用等映射）"
                                else "运行组件重启失败 → 下一轮 ensure 再试",
                            )
                            delay(2_000)
                            continue
                        }
                        AppState.addLog("运行组件已重启 $serverRestarts 次仍注入失败 → 放弃组件路线，走杀游戏自愈")
                    }
                    // ptrace 类失败 => 进程大概率已僵死（2026-10-21 uuz 设备实测）：attach 写内存
                    //   失败后进程进 T 状态——gamePid 还看得见、maps 里却连 libil2cpp 都没有，
                    //   不自愈就会卡在"等映射 120s"。这类错误立即杀游戏重开，别攒次数。
                    val fatal = err.contains("Unable to write to process memory") ||
                        err.contains("No such process")
                    if (fatal || injectFails >= MAX_INJECT_FAILS) {
                        // ★先分清"进程真没了"和"还活着、只是 frida 写内存失败"（2026-10-29）：
                        //   以前一律当僵死杀游戏，于是持续性原因（arm64 包走 Houdini / 反作弊拦注入…）
                        //   会变成「注入失败 → 杀 → 重开 → 再失败」的死循环，永远刷屏。
                        val diag = diagnoseInject(pid)
                        AppState.addLog("注入失败诊断：$diag")
                        val alive = gamePid(GameProcess.pkg())
                        // ★★2026-11-13（0.6.20 诊断包）：attach 的是**上一局已死的旧 pid**，而上层
                        //   刚 kill→launch 调起的新进程正在跑（日志 `pidof=22124（≠21741）`）。
                        //   旧逻辑只在 `alive == pid` 时原地重试，这种"换代"一律判成进程僵死 ⇒
                        //   force-stop 把健康的新进程一起杀掉，之后再没人 am start ⇒ 90s 超时停手。
                        //   ⇒ 换代就不杀，下一轮直接按新 pid 重新等就绪 + 注入（不加等待、不动正常流程）；
                        //   累计失败到 [MAX_INJECT_FAILS] 仍会退回下面的 kill 自愈，不会空转。
                        if (InjectRecoveryPolicy.pidChanged(pid, alive) && pidChangeTries < MAX_INJECT_FAILS) {
                            pidChangeTries++
                            injectFails = 0           // 换了进程，失败计数从头算（原来会累加到第 3 次就误杀新进程）
                            stage = "注入撞上旧 pid=$pid → 按新 pid=$alive 重注"
                            AppState.addLog(
                                "注入撞上旧 pid=$pid（当前游戏已是 pid=$alive）⇒ 不杀新进程，下一轮按新 pid 重新注入",
                            )
                            lastPid = -1
                            continue
                        }
                        if (alive == pid && injectFails == 1) {
                            AppState.addLog("进程（pid=$pid）还在 ⇒ 先原地重试注入一次，不杀游戏")
                            delay(2_000)
                            continue
                        }
                        injectFails = 0
                        pidChangeTries = 0
                        injectCycles++
                        if (injectCycles >= MAX_INJECT_CYCLES) {
                            injectCycles = 0
                            stage = "注入反复失败 → 暂停自愈 ${CYCLE_PAUSE_MS / 1000}s"
                            GameProcess.kill()
                            stalePid = pid
                            forceLaunch = true        // 暂停结束后必须真的重新调起，别对着残留 pid 等映射
                            lastPid = -1
                            AppState.addLog(
                                "连续 $MAX_INJECT_CYCLES 次「注入失败 → 杀游戏重开」→ **暂停自愈 " +
                                    "${CYCLE_PAUSE_MS / 1000}s**（不再无限重启）。常见原因：游戏装的是 " +
                                    "arm64 包而设备是 x86 模拟器（Houdini 转译下注入后摸不到 il2cpp）、" +
                                    "反作弊拦注入、运行组件与游戏 ABI 不匹配。诊断：$diag",
                            )
                            AppState.setFridaStatus("注入反复失败，已暂停自愈 2 分钟（看日志里的诊断）")
                            // ★分段可取消：上层关闸后最长 1s 退出，不再把 120s 阻塞段带进下一局
                            var paused = 0L
                            while (paused < CYCLE_PAUSE_MS && autoRecover) {
                                delay(1_000)
                                paused += 1_000
                            }
                            continue
                        }
                        AppState.addLog(
                            if (fatal) "注入失败且进程多半已僵死 → 自愈：杀掉游戏重开"
                            else "连续 $MAX_INJECT_FAILS 次注入失败 → 自愈：杀掉游戏重开",
                        )
                        stage = "注入失败自愈：杀掉游戏重开"
                        GameProcess.kill()
                        lastPid = -1                 // 下一轮走"游戏没在跑，调起它"
                        forceLaunch = true
                        stalePid = pid
                        waitGameGone()
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
                        stalePid = lastPid
                        stage = "agent 掉线自愈：杀掉游戏重开"
                        GameProcess.kill()
                        lastPid = -1
                        forceLaunch = true
                        waitGameGone()
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
     *   ① 主判据（需要 root）：轮询 `/proc/<pid>/maps` 直到出现 `libil2cpp.so`；
     *      ★映射后**立即注入**，"il2cpp 可用"交给 agent 轮询 `domain_get`（上层最多等 30s）；
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
            // ★2026-11-12（用户："优先用状态判定，而不是延迟"）：
            //   ① 代码段 `r-xp` 已就位（见 [waitIl2CppMapped]）⇒ linker 重定位基本完成；
            //   ② 再等**前台真的变成游戏**（权限页/系统页会抢前台，后台启动时注入容易把游戏带走）。
            //   两个状态都满足就立刻注入，不再固定 sleep。
            val pkg = runCatching { GameProcess.pkg() }.getOrNull().orEmpty()
            var fgOk = false
            if (pkg.isNotEmpty()) {
                fgOk = waitFrontGame(pkg, FRONT_WAIT_BEFORE_INJECT_MS)
            }
            // ★★2026-11-24（雷电连崩反馈）：状态判据全满足后**再静置 [INJECT_SETTLE_MS]**才注入 ——
            //   映射/前台只是"初始化基本就位"，引擎最初 2~3s 仍是最脆弱窗口（11-24 雷电三次
            //   都在"起了 3s → 立即注入"时进程直接 died: fore TOP；旧做法"从打开瞬间算 6s"
            //   被用户否掉：死板、好设备白等）。这里保留状态判据、只在它刚满足时加 2s 缓冲。
            delay(INJECT_SETTLE_MS)
            // 静置期间崩了 ⇒ 别再 attach（那正是 "Unable to write to process memory: No such process"）
            // verifyMainPid：null=pidof 超时不确定（别误判成死）；false=真没了；true=还在。
            val alive = if (pkg.isNotEmpty()) verifyMainPid(pid, pkg) else fastPid(pkg) == pid
            if (alive == false) {
                val why = "注入静置 ${INJECT_SETTLE_MS}ms 期间游戏进程 $pid 退出（r-xp/前台刚就绪就崩）"
                AppState.addLog(why)
                AppState.setFridaStatus(why.take(60))
                return why
            }
            AppState.addLog(
                "$KEY_SO 已映射（游戏起了 ${ms / 1000}s）+ 前台${if (pkg.isEmpty()) "?" else if (fgOk) "=游戏" else "仍不是游戏（照常注入）"}" +
                    " → 静置 ${INJECT_SETTLE_MS / 1000}s 后注入（il2cpp 就绪由 agent 自己等）",
            )
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

    /**
     * 等游戏进程**真的消失**（2026-10-29）：`am force-stop` 是异步的，杀完立刻 `am start`
     * 很容易撞上"还没退干净的旧进程" —— 于是 `pidof` 拿到它、maps 里当然早有 libil2cpp
     * （日志里那句 `已映射（游戏起了 0s）` 往往就是这么来的），静置完再 attach，进程正好退掉，
     * 报 `No such process`，看起来像"注入失败"。
     *
     * 最多等 4s；没杀干净也往下走（`am start` 会把已有进程带回前台，不至于更坏）。
     */
    private suspend fun waitGameGone(timeoutMs: Long = 4_000L) {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            if (fastPid(GameProcess.pkg()) <= 0) return
            delay(300)
        }
        AppState.addLog("force-stop 后 ${timeoutMs / 1000}s 进程还在（继续往下走，可能撞到旧进程）")
    }

    /**
     * ★cs128（2026-11-21）：探**运行组件**（frida-server）是否还在正常应答。
     * 端口通 ≠ 组件活着 —— 组件可能被上一局死掉的游戏进程（eternalize 会话）卡死：
     * 端口还在 LISTEN ⇒ `portOpen`/`ensureFridaServer` 永远判"活着"，而 attach 全部挂起。
     * 做法：借 findPid 对 **systemui**（Android 上必然存在的进程）做一次 frida-server 往返，
     * 5s 内应答（哪怕回 -1"没找到"）= 组件活着；5s 没回包 = 僵了。
     * ⚠`withTimeoutOrNull` 取消不了已经进 JNI 的阻塞调用（泄漏一个 IO 线程）——
     *   所以探测本身只在"注入失败"之后才做，频度极低；泄漏可接受。
     */
    private suspend fun fridaServerAlive(): Boolean {
        val r = withTimeoutOrNull(5_000L) {
            runCatching { FridaCore.findPid(FRIDA_SERVER, "com.android.systemui") }.getOrDefault(-1)
        }
        return r != null && r > 0
    }

    /**
     * 注入失败的现场快照（2026-10-29 用户报障后加）。
     *
     * 用户只能把日志发过来，所以日志必须**自己说清**失败原因 —— 下面几个值能把三种情况分开：
     *  * 进程到底还在不在：`pidof` + `/proc/<pid>/status` 的 State（T/D/t = 僵死）；
     *  * 游戏是不是 arm64 包在 x86 模拟器上跑：`primaryCpuAbi=arm64-v8a` ⇒ Houdini 转译，
     *    这种情况下注入"看起来成功"也读不到 il2cpp（见 2026-10-29 那台 MuMu 的实测）；
     *  * 是崩了还是被反作弊干掉：logcat 里的 FATAL / has died / Force finishing；
     *  * ★cs128：运行组件还响应吗（端口通 ≠ 组件活着，attach 挂起时组件可能已僵）。
     */
    private suspend fun diagnoseInject(pid: Int): String {
        val parts = ArrayList<String>()
        runCatching {
            val alive = gamePid()
            parts.add("pidof=$alive" + if (alive == pid) "（同 pid）" else "（≠$pid）")
            val state = RootShell.exec("awk '/^State:/{print \$2}' /proc/$pid/status", 3_000L, direct = true).out.trim()
            parts.add("state=${state.ifBlank { "已退出" }}")
            // ★cs128：把"运行组件还响应吗"写进诊断（端口通 ≠ 组件活着，attach 挂起时组件可能已僵）
            parts.add("组件=" + if (fridaServerAlive()) "有响应" else "没响应（僵）")
            val abi = RootShell.exec("dumpsys package ${GameProcess.pkg()} | grep -m1 primaryCpuAbi", 8_000L)
                .out.substringAfter("primaryCpuAbi=", "").trim()
            parts.add("游戏ABI=${abi.ifBlank { "?" }}")
            val crash = RootShell.exec(
                "logcat -d -t 400 | grep -iE \"Fatal signal|FATAL EXCEPTION|has died|Force finishing\" | tail -3",
                8_000L,
            ).out.trim()
            if (crash.isNotBlank()) parts.add("崩溃线索=${crash.replace('\n', ' ').take(220)}")
        }
        return parts.joinToString(" ｜ ").ifBlank { "（诊断没取到）" }
    }

    /**
     * ★2026-11-12（用户："优先用状态判定，而不是延迟"）：等**前台真的变成游戏**。
     * 启动时会先弹 `com.android.permissioncontroller`（权限页）抢前台，游戏在后台时注入容易被带走；
     * 这是包名状态判定，等到就立刻注入，超时返回 false（调用方照常注入，不卡死）。
     */
    private suspend fun waitFrontGame(pkg: String, timeoutMs: Long): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val f = runCatching { Foreground.packageName(force = true) }.getOrNull()
            if (f == pkg) return true
            delay(250)
        }
        return false
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
        var mappedAt = 0L
        while (true) {
            val dt = SystemClock.elapsedRealtime() - t0
            // ★2026-11-12（用户："优先用状态判定，而不是延迟"）：等**可执行段**（`r-xp`）而不是文件头段（`r--p`）——
            //   `r--p` 出现时 linker 还在重定位，此时注入会把游戏带走（实测 0s 注入后 1s 崩）。
            val r = RootShell.exec("grep -m1 'r-xp.*$KEY_SO' /proc/$pid/maps", 8_000L, direct = true)
            if (r.out.contains(KEY_SO)) {
                // ★2026-11-13（用户："现在为什么这么容易闪退"）：`r-xp` 只代表**代码段映射完**，
                //   linker 重定位 / `il2cpp_init` 未必完成 —— 冷启动 0s 就注入正是之前把游戏带崩的场景。
                //   再等 `global-metadata.dat` 出现在 maps（`il2cpp_init` 才 mmap 它）＝"可安全注入"的状态。
                //   最多等 6s；等不到也放行（个别包 metadata 打包在 so 里/名字不同，不能因此卡死）。
                val md0 = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - md0 < 6_000L) {
                    val md = RootShell.exec("grep -m1 'global-metadata.dat' /proc/$pid/maps", 3_000L, direct = true)
                    if (md.out.contains("global-metadata.dat")) {
                        AppState.addLog(
                            "…$KEY_SO 已映射 + global-metadata 已加载" +
                                "（+${SystemClock.elapsedRealtime() - md0}ms）→ 可注入状态（静置 ${INJECT_SETTLE_MS / 1000}s 后 attach）",
                        )
                        return null
                    }
                    delay(200)
                }
                AppState.addLog("…$KEY_SO 已映射，6s 内没看到 global-metadata.dat → 按 r-xp 放行注入")
                return null
            }
            if (r.text.contains("No such file") || r.text.contains("No such process")) {
                return "进程提前退出（pid $pid 没了）"
            }
            // 兜底：so 已映射、却一直等不到 `r-xp`（个别 ROM 段标志不同）⇒ 5s 后按已加载处理，不卡死
            if (mappedAt == 0L && dt > 1_000L) {
                val any = RootShell.exec("grep -m1 $KEY_SO /proc/$pid/maps", 3_000L, direct = true)
                if (any.out.contains(KEY_SO)) mappedAt = SystemClock.elapsedRealtime()
            }
            if (mappedAt > 0L && SystemClock.elapsedRealtime() - mappedAt > 5_000L) {
                AppState.addLog("…$KEY_SO 已映射但 5s 内没等到 r-xp 段 → 按已加载处理")
                return null
            }
            // 进程还在、so 却一直不映射：看一眼进程状态，T/D/t（ptrace 挂起/僵死）=> 判定坏状态
            //   （2026-10-21 uuz 实测：attach 写内存失败后进程卡 T 状态，maps 停在注入前，
            //    傻等映射要 120s 超时 —— 早发现早杀游戏重开）
            if (dt > 3_000) {
                val st = RootShell.exec("awk '{print \$3}' /proc/$pid/stat", 3_000L, direct = true)
                val state = st.out.trim()
                if (state.isEmpty()) return "进程提前退出（pid $pid 的 /proc 没了）"
                // ★★2026-11-22（用户反馈"启动反复重启/手点却能进"）：**`D` 状态不能当僵死**——
                //   D = 不可中断睡眠，启动时加载大 so / 资源是**正常表现**（慢模拟器上持续十几秒）。
                //   原来 3s 就判死 ⇒ force-stop 打断加载 ⇒ 再启动更慢 ⇒ 恶性循环（日志实测 4 次）。
                //   D 交给下面的 `IL2CPP_MAP_STUCK_MS` 兜底；T/t（ptrace 挂起）、Z/X（僵尸/死）才是真坏。
                if (state == "T" || state == "t" || state == "Z" || state == "X") {
                    return "进程僵死/已退出（pid $pid 状态=$state）"
                }
            }
            // ★2026-11-12（用户："闪退恢复没有进程状态检测和重启"）：pid 还在、可 `maps` 里连
            //   `libil2cpp.so` 的影子都没有 ⇒ 进程只是"在"，实际卡在开屏/白屏（闪退残留）。
            //   再傻等 120s 没意义 ⇒ 判卡死，交给注入循环 kill + launch。
            if (dt >= IL2CPP_MAP_STUCK_MS) {
                return "起游戏后 ${IL2CPP_MAP_STUCK_MS / 1000}s 内 $KEY_SO 一直没映射（进程卡死/闪退残留）"
            }
            if (dt >= READY_TIMEOUT_MS) {
                return "等 $KEY_SO 映射超时（${READY_TIMEOUT_MS / 1000}s；最后输出：${r.text.take(80)}）"
            }
            if (!said && dt > 8_000) {
                // PC 版同款：8 秒还没读到就把"实际读到什么"打一次（读不到往往是 root 没生效）
                said = true
                AppState.addLog(
                    "…已等 ${dt / 1000}s 仍未映射：rc=${r.exit} " +
                        "out=${redact(r.out.trim().take(40))} err=${redact(r.err.trim().take(80))}",
                )
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
        AppState.addLog("运行组件端口没通，改用自带的那一份…")
        return FridaServerInstaller.ensure(ctx, FRIDA_SERVER_PORT)
    }

    /** ★2026-11-10：端口探测必须走 IO 线程，主线程会被 StrictMode 拦（NetworkOnMainThreadException）。 */
    private suspend fun portOpen(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", FRIDA_SERVER_PORT), 800) }
            true
        }.getOrDefault(false)
    }

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
    suspend fun gamePid(pkg: String = GameProcess.pkg()): Int {
        fastPid(pkg).let { if (it > 0) return it }

        // 注意：这个函数每 2 秒被调一次，**别在里面打日志**（会刷屏 —— 实测踩过）
        val viaFrida = FridaCore.findPid(FRIDA_SERVER, pkg)
        if (viaFrida > 0) return viaFrida

        return parseFirstPid(RootShell.exec("pidof $pkg", 8_000L).out)
    }

    /**
     * 校验 pid 真的是**游戏主进程**（2026-11-03 用户报障后加，见 loop 里那两道闸）。
     *
     * 判据：`/proc/<pid>/cmdline` 的第一个参数 == 包名。
     *   · Android 上 app 进程的 argv[0] 就是进程名（`com.bilibili.priconne`）；
     *   · `:pushservice` 之类子进程是 `包名:xxx` ⇒ 不相等 ⇒ 判否；
     *   · 我们自己的 root shell（`su`）之类更是不同 ⇒ 判否；
     *   · 进程已经没了 ⇒ 读不到 ⇒ **null（未知）**。
     * 返回 null 时调用方按"没把握但也不能因此杀游戏"处理（继续走原流程）。
     * 只在 pid 变化时调（罕见），不会拖慢每拍。
     */
    private suspend fun verifyMainPid(pid: Int, pkg: String): Boolean? {
        if (pid <= 0) return false
        // ★★2026-11-15（用户）：**只信 `pidof`，不再读 `/proc/<pid>/cmdline`**。
        //   `pidof <包名>` 精确匹配进程名 ⇒ 只回主进程（子进程是 `包名:xxx`，匹配不上）。
        //   实测：游戏刚启动那几秒整机卡顿（前台 dumpsys 读不到、网络 15s 超时），连**活进程**的
        //   `/proc` 读也会超时，反过来把常驻 root shell 拖垮（日志「常驻 root shell 没回应」）。
        //   读 cmdline 只是为了排除子进程，pidof 已经能排除，没必要再碰 /proc。
        val cur = RootShell.exec("pidof $pkg", 2_000L)
        if (cur.exit == -1) return null          // pidof 自己超时（系统卡）⇒ 不确定，别当死处理
        val pids = Regex("\\d+").findAll(cur.out).map { it.value.toInt() }.toSet()
        return pid in pids
    }

    /** 轻量版：只跑一次 `pidof`（不碰 frida-server）。给"等进程消失"这种高频判定用。 */
    private fun fastPid(pkg: String): Int = runCatching {
        ProcessBuilder("pidof", pkg).start().inputStream.bufferedReader().readText()
    }.getOrDefault("").let { parseFirstPid(it) }

    /**
     * `pidof` 可能一次给**多个** pid（游戏带子进程时），旧写法 `toIntOrNull()` 会整串解析失败、
     * 直接掉到 frida 兜底甚至返回 -1 ⇒ 明明有进程却判"没起"。取第一个有效 pid。
     */
    private fun parseFirstPid(out: String): Int =
        out.split(' ', '\n', '\t').mapNotNull { it.trim().toIntOrNull() }.firstOrNull { it > 0 } ?: -1

    /** 等游戏进程出现（调起之后用）。@return pid；-1 = 超时没等到 */
    suspend fun waitForGamePid(
        pkg: String = GameProcess.pkg(),
        timeoutMs: Long,
        excludePid: Int = -1,
    ): Int {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            delay(1_000)
            val p = gamePid(pkg)
            // ★只给自愈用：排除刚 force-stop 的残留 pid（它仍挂在 pidof 里，却不是新调起的游戏）。
            //   复用原有 20s 窗口，不新增等待；正常路径 excludePid=-1，行为不变。
            if (p > 0 && p != excludePid) return p
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
            RootShell.exec(killInjectorCmd(pf), 8_000L, direct = true)

            val gamePid = RootShell.exec("pidof ${GameProcess.pkg()}", 8_000L).out.trim()
            if (gamePid.isEmpty()) {
                AppState.setFridaStatus("游戏没在跑（${GameProcess.pkg()}），先启动游戏")
                return
            }
            AppState.addLog("游戏 pid=$gamePid，开始注入…")

            val cmd = "echo \$\$ > ${pf.absolutePath}; exec $INJECT_BIN -p $gamePid -s ${agent.absolutePath}"
            proc = RootShell.spawn(cmd) { line -> AppState.addLog("[inject] ${redact(line)}") }
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
        // ★2026-11-11：等 il2cpp 就绪的起始时刻（0 = 还没开始等）；只在"回连后"计时
        var readyWaitAt = 0L
        var readyWarned = false
        while (true) {                            // 取消靠 delay() 抛 CancellationException（同 AutoForegroundService）
            delay(POLL_MS)
            // ★★2026-10-27：**总闸关了就一次都别问 agent**。
            //   实测 13:56:46 已经打印"自动黎明界结束 → 停止注入/自愈，之后不再碰游戏进程（待命）"，
            //   可这里仍在每 1.5s 调 `viewId`（13:57:57 起连续超时），三次之后自己把状态刷成
            //   "agent 掉线，等它重连…" —— 待命期自问自答，日志里看着像故障，还会误导后续判断。
            //   （另一条腿 `loop()` 早就在 `!autoRecover` 时待命了，只有这条漏了。）
            if (!autoRecover) {
                toldConnected = false             // 下次开闸重新握手，别复用旧状态
                lastPage = null
                lastPageTop = null
                _readyGen.value = -1
                AppState.setFridaStatus("待命（未跑迷宫，不注入游戏）")
                continue
            }
            if (!AgentBridge.connected.value) {
                if (toldConnected) {
                    toldConnected = false
                    _readyGen.value = -1          // 上一代 agent 不可用，别让上层误判"重启完成"
                    AppState.addLog("agent 掉线，等它重连…")
                } else {
                    _readyGen.value = -1
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
                // 跟 PC 版一致：先等 agent 的 il2cpp 就绪（它的 ready 导出 = !!g_il2cpp，
                // ★cs95 起还要求 `il2cpp_domain_get()` 非空 —— 映射 ≠ 初始化完成），
                // 没就绪就调 labStatus 只会拿到 null。
                val r = AgentBridge.call("ready", timeoutMs = 4_000L)
                if (r == null || !r.optBoolean("data", false)) {
                    if (readyWaitAt == 0L) readyWaitAt = SystemClock.elapsedRealtime()
                    val waited = SystemClock.elapsedRealtime() - readyWaitAt
                    // ★2026-11-11（用户）：最多提示到 [IL2CPP_READY_TIMEOUT_MS]=30s；超了只告警、
                    //   继续等，**不杀游戏**（映射已在、agent 已连，多半只是启动慢）。
                    if (waited >= IL2CPP_READY_TIMEOUT_MS && !readyWarned) {
                        readyWarned = true
                        AppState.addLog(
                            "[!] 注入后 ${waited / 1000}s il2cpp 仍未就绪（domain 未建好）→ 继续等，不杀游戏",
                        )
                    }
                    AppState.setFridaStatus("agent 已连，等 il2cpp 就绪…（${waited / 1000}s）")
                    continue
                }
                // ★agent 端 ready 只说明 il2cpp domain 好了，还得确认它属于**这一代游戏**：
                //   残留旧进程里 eternalize 的脚本重连也会让 ready 通过。注入循环成功注入后
                //   才写 [boundPid]/[boundGen]；还没写就下一拍再看（别锁 toldConnected）。
                if (boundPid <= 0 || boundGen != GameProcess.kills) {
                    AppState.setFridaStatus("agent 已连，等注入循环确认这一代…")
                    continue
                }
                val readyMs = if (readyWaitAt > 0) SystemClock.elapsedRealtime() - readyWaitAt else 0L
                readyWaitAt = 0L
                readyWarned = false
                toldConnected = true
                // ★只有「agent 已连 + il2cpp domain 就绪」才宣布这一代完成，上层等的正是它
                _readyGen.value = GameProcess.kills
                // ★打脚本构建号：验证「轻读」改动时，开局就能确认注入的是哪一版 agent
                //  （改过读逻辑时 JS_BUILD 会递增；PROBE 之外的常规日志原本看不到它）。
                val jsb = runCatching {
                    AgentBridge.call("jsBuild", timeoutMs = 3_000L)?.optString("data")
                }.getOrNull()
                AppState.addLog(
                    "agent 已回连（il2cpp 就绪，等了 ${readyMs}ms），脚本构建号=${jsb ?: "读不到"}，开始读页面名",
                )
                // ★★2026-11-11（用户）：**去掉注入后的 8s 静置**。
                //   那 8s 的原始原因是 2026-10-17 的旧 pollLoop 在 `NONE(0)`（游戏还在开屏）时做过
                //   **UI 遍历**，把游戏卡死过。现在这里只调 `viewId`（`getViewId`，纯字段读、极便宜），
                //   `labStatus` 也只在 `vid==264` 且隔 3 轮才读 ⇒ 加载页根本不会产生重操作，
                //   再等 8s 纯属白等。

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
