package com.lmj.labyrinthauto.frida

import android.content.Context
import android.os.SystemClock
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.logic.Navigator
import com.lmj.labyrinthauto.net.bilibili.GameServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 渠道服凭证抓取（2026-10-28 用户："把抓取逻辑连接上"）。
 *
 * **为什么需要它**：渠道服（小米/华为/vivo…）没有可用的账密登录，游戏服 `tool/sdk_login`
 * 只认渠道 SDK 换来的 `uid` + `access_key`。这两个值只能从**真正跑一遍渠道客户端**里拿 ——
 * 渠道 SDK 完成登录后，游戏自己会调
 * `Cute.SdkLoginTask.SetParameter(uid, access_key, platform, channel_id)`，
 * agent 侧（`agent_bridge.js` 的 `captureArm`）就挂在那个方法上把入参截下来。
 *
 * **一次抓取 = 一次独立启动**（不跑迷宫、不动存档）：
 * ```
 * force-stop 渠道客户端 → am start → 等 libil2cpp 映射 → 静置 → 注入 agent
 *   → captureArm（挂钩）→ 轮询 capturePoll → 拿到 uid/access_key
 *   → 退出游戏 → 回自己的界面（凭证由 UI 存回账号）
 * ```
 *
 * 两个必须知道的时序事实（决定了下面 [ATTEMPTS] 的重试策略）：
 *  ① 钩子必须挂在游戏**发起 sdk_login 之前**，晚一步就永远抓不到（登录只做一次）；
 *  ② 但注入太早（启动期）会把游戏打死 —— 实测要等 `libil2cpp.so` 映射后再静置几秒。
 *  ⇒ 第一次用**已验证安全**的 6s 静置；万一那时登录已经过去了，第二次改成 2.5s 抢时间。
 *
 * 另外：渠道 SDK 首次启动可能弹它自己的账号登录框（华为/小米账号）——
 * 抓取期间游戏在前台，用户可以直接点，不影响抓取（钩子在，登录完成后照样截得到）。
 */
object AccessCapture {

    /** 截到的凭证（platform / channelId 仅用于日志核对，登录只用 uid + accessKey） */
    data class Creds(
        val uid: String,
        val accessKey: String,
        val platform: String = "",
        val channelId: String = "",
    )

    /** 抓取失败；message 是给用户看的人话 */
    class Failure(message: String) : Exception(message)

    /** 等游戏进程出现（冷启动偶尔慢） */
    private const val PID_WAIT_MS = 40_000L

    /** 注入前静置：第 1 次用已验证安全的值，第 2 次抢时间（见类注释 ① ②） */
    private const val SETTLE_SAFE_MS = 6_000L
    private const val SETTLE_EARLY_MS = 2_500L

    /** 等钩子挂上（il2cpp 还没就绪时会返回 err，重试即可） */
    private const val ARM_TIMEOUT_MS = 25_000L

    /** 挂上钩子后等渠道登录的窗口 */
    private const val WATCH_WINDOW_MS = 45_000L

    /**
     * ★抓到凭证后**先等页面进 HOME 再关游戏**（2026-10-30 用户定案：就只认 HOME）。
     *
     * 为什么：抓到 `SetParameter` 的那一刻，游戏往往还在加载/往磁盘写东西，
     * 这时候直接 force-stop 容易把它的数据写坏（用户实测遇到的诡异问题）。
     *
     * 判据只有一个：`HOME(5)` —— 点完「点击屏幕 开始游戏」之后的第一个真正页面，
     * 到它就说明登录流程已经走完、不会再写盘 ⇒ 可以安全收尾。
     * 其它页面（`SPLASH(1)`/`TITLE(2)`/`NONE(0)`…）一律继续等，30s 超时后照常收尾。
     *
     * 等不到（agent 掉线 / 超时）也照常收尾，绝不能让这一步把整个抓取卡死。
     */
    private const val HOME_WAIT_MS = 30_000L
    private const val VIEW_HOME = 5

    /**
     * ★**必须点一下屏幕**才会触发渠道登录（2026-10-29 用户实测确认）：
     * 游戏先停在「点击屏幕 开始游戏」的标题页，**此时还没登录**，点一下才走渠道 SDK 登录 →
     * `SetParameter` 才会被调用。不点就永远等不到（第一版就是这么白等的）。
     *
     * 节奏：注入完成后先等 [TAP_FIRST_DELAY_MS]（那时多半还在加载，点早了没意义），
     * 之后每 [TAP_INTERVAL_MS] 点一次、最多 [TAP_MAX] 次（标题页/公告页点中心都是"继续"，
     * 误点无害；一旦抓到凭证立刻停手）。
     */
    private const val TAP_FIRST_DELAY_MS = 3_000L
    private const val TAP_INTERVAL_MS = 6_000L
    private const val TAP_MAX = 5

    private const val POLL_MS = 500L
    private const val ATTEMPTS = 2

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 当前走到哪一步（null = 没在抓）；UI 直接显示这一行 */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val lock = Mutex()

    /** 最近一次失败原因（UI 的 Toast 之外，日志里也留一份） */
    private var lastArmErr: String = ""

    /**
     * 抓一次凭证。**会重启客户端进程**（先 force-stop，再 am start），成功后再把它关掉。
     *
     * @throws Failure 失败原因（root / 没装 / 注入失败 / 没等到登录 …）
     */
    suspend fun capture(ctx: Context, server: GameServer): Creds = lock.withLock {
        if (_busy.value) throw Failure("已经在抓了，等这一次结束")
        _busy.value = true
        try {
            run(ctx, server)
        } finally {
            _busy.value = false
            _status.value = null
        }
    }

    // ────────────────────────────── 主流程 ──────────────────────────────

    private suspend fun run(ctx: Context, server: GameServer): Creds {
        val pkg = server.packageName

        // ── 前置检查（都在动手之前做，免得白重启一次游戏）──
        step("检查环境…")
        if (FridaInjector.autoRecover) {
            throw Failure("正在自动跑迷宫：先停掉自动化再抓取（两边会抢同一个游戏进程）")
        }
        if (!installed(ctx, pkg)) {
            throw Failure("这台设备没装「${server.displayName}」客户端（$pkg），先装上它再抓")
        }
        if (RootShell.detect() == null) {
            throw Failure("没有 root：抓取要注入游戏进程（顺便 force-stop / am start），先在设备上允许 root")
        }
        if (!FridaCore.available) {
            throw Failure("运行组件没加载上，先联网让它自动下载（约 46MB）")
        }
        // ★架构闸门（2026-10-29 实测定案）：注入硬依赖 x86_64 —— ARM 走 Houdini 转译时，
        //   x86_64 宿主进程的模块表里根本没有 libil2cpp.so，注入"成功"也摸不到 il2cpp。
        //   不是 x86_64 就用设备内已有的 base.apk 本地重装一次（零下载、不联网、保存档、实测 8s）。
        //   这比去改 MuMu 的 abi-select 表靠谱：那张表只在"装"的时候生效，且 /system 只读改不动。
        step("检查游戏架构…")
        val abi = AbiRepair.ensure(pkg)
        if (!abi.ok) {
            // ★fatal = 确定性修不了（包内无 x86_64 / 没装 / 没 root）：直接说清原因，别让用户反复点
            throw Failure(
                if (abi.fatal) "这台设备上没法注入这个游戏：${abi.detail}"
                else "游戏架构不是 ${AbiRepair.TARGET_ABI}，自动修复也没成：${abi.detail}",
            )
        }
        AgentBridge.start()                       // 幂等：没在听就开一个
        step("准备运行组件…")
        if (!FridaInjector.ensureServer(ctx)) {
            throw Failure("运行组件没起来（frida-server 端口 1234 不通，看日志）")
        }
        val agent = FridaInjector.agentFile(ctx)
        AppState.addLog(
            "开始抓取 ${server.displayName} 的 uid / access_key" +
                "（客户端 $pkg，脚本 ${agent.length()} 字节）",
        )

        var lastWhy = ""
        for (attempt in 1..ATTEMPTS) {
            val settle = if (attempt == 1) SETTLE_SAFE_MS else SETTLE_EARLY_MS
            when (val r = tryOnce(ctx, server, pkg, agent, attempt, settle)) {
                is Attempt.Got -> return finishOk(ctx, pkg, r.creds)
                is Attempt.Fail -> {
                    lastWhy = r.why
                    AppState.addLog("第 $attempt 次没抓到：$lastWhy")
                }
            }
        }

        AppState.addLog("抓取失败：$lastWhy")
        throw Failure(
            lastWhy.ifBlank { "没抓到 uid / access_key" } +
                "（游戏还开着：如果它弹了渠道登录框，登录后可以再点一次抓取）",
        )
    }

    /** 单次尝试的结果（用返回值而不是回调：`run` 里要 `return` 得靠它） */
    private sealed interface Attempt {
        data class Got(val creds: Creds) : Attempt
        data class Fail(val why: String) : Attempt
    }

    /** 一次完整的"重启 → 注入 → 抓"。 */
    private suspend fun tryOnce(
        ctx: Context,
        server: GameServer,
        pkg: String,
        agent: java.io.File,
        attempt: Int,
        settleMs: Long,
    ): Attempt {
        step("第 $attempt 次：重启游戏（${server.displayName}）…")
        GameProcess.kill(pkg)
        delay(1_000)
        if (!GameProcess.launch(ctx, pkg)) return Attempt.Fail("调不起 ${server.displayName}（$pkg）")

        step("等游戏进程…")
        val pid = FridaInjector.waitForGamePid(pkg, PID_WAIT_MS)
        if (pid <= 0) return Attempt.Fail("调起后 ${PID_WAIT_MS / 1000}s 没看到游戏进程")
        AppState.addLog("游戏已起来 pid=$pid，等 Unity 运行时…")

        step("等 Unity 运行时（libil2cpp 映射）…")
        FridaInjector.waitMapped(pid)?.let { return Attempt.Fail(it) }

        step("静置 ${settleMs / 1000}s 再注入…")
        delay(settleMs)

        step("注入抓取脚本…")
        val err = FridaInjector.inject(pid, agent)
        if (err.isNotEmpty()) return Attempt.Fail("注入失败：$err")

        step("等 agent 就绪并挂钩…")
        if (!armHook()) {
            return Attempt.Fail("抓取钩子没挂上（${lastArmErr.ifBlank { "agent 没回连" }}）")
        }

        step("等渠道登录（会自动点屏幕推进）…")
        watch(pkg)?.let { return Attempt.Got(it) }
        return Attempt.Fail(
            if (FridaInjector.gamePid(pkg) <= 0) {
                "注入后游戏退出了（启动期注入太早，游戏被带走）"
            } else {
                "等 ${WATCH_WINDOW_MS / 1000}s、自动点了 $TAP_MAX 次屏幕，也没等到渠道登录"
            },
        )
    }

    /**
     * 点一下屏幕中心（root `input tap`，与自动化那条点击后端同一条路）。
     *
     * 为什么必须点：游戏停在「点击屏幕 开始游戏」标题页时**还没登录**，
     * 点一下才走渠道 SDK 登录 → `SdkLoginTask.SetParameter` 才会被调用。
     * 坐标按当前分辨率算，不写死（模拟器/真机尺寸不同）。
     */
    private suspend fun tapScreenCenter() {
        val (w, h) = screenSize()
        RootShell.exec("input tap ${w / 2} ${h / 2}", 6_000L)
    }

    @Volatile
    private var cachedSize: Pair<Int, Int>? = null

    /** 屏幕分辨率（`wm size`；有 Override size 时以它为准，否则用 Physical size）。 */
    private suspend fun screenSize(): Pair<Int, Int> {
        cachedSize?.let { return it }
        val out = RootShell.exec("wm size", 5_000L).out
        val hit = Regex("(\\d+)\\s*x\\s*(\\d+)").findAll(out).lastOrNull()
        val v = if (hit != null) {
            hit.groupValues[1].toInt() to hit.groupValues[2].toInt()
        } else {
            1600 to 900           // 兜底（本机 MuMu 就是 1600×900）
        }
        cachedSize = v
        return v
    }

    /** 反复调 `captureArm` 直到 hooked（il2cpp 未就绪时 agent 会回 err，重试即可）。 */
    private suspend fun armHook(): Boolean {
        lastArmErr = ""
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < ARM_TIMEOUT_MS) {
            val r = AgentBridge.call("captureArm", timeoutMs = 4_000L)
            if (r != null && r.optBoolean("ok", false)) {
                val d = r.optJSONObject("data")
                if (d != null && d.optBoolean("hooked", false)) return true
                lastArmErr = d?.optString("err").orEmpty().ifBlank { "未挂上" }
            }
            delay(POLL_MS)
        }
        return false
    }

    /** 轮询 `capturePoll` 直到拿到完整凭证 / 超窗 / 游戏没了。 */
    private suspend fun watch(pkg: String): Creds? {
        val t0 = SystemClock.elapsedRealtime()
        var tick = 0
        var taps = 0
        var lastTapEl = -1L
        while (SystemClock.elapsedRealtime() - t0 < WATCH_WINDOW_MS) {
            // ★先点屏幕：不点就停在标题页、渠道登录根本不会发生（见 [TAP_FIRST_DELAY_MS]）
            val el = SystemClock.elapsedRealtime() - t0
            val tapDue = if (taps == 0) el >= TAP_FIRST_DELAY_MS else (el - lastTapEl >= TAP_INTERVAL_MS)
            if (taps < TAP_MAX && tapDue) {
                taps++
                lastTapEl = el
                tapScreenCenter()
                AppState.addLog("点一下屏幕（第 $taps 次）→ 推进到渠道登录")
                step("等渠道登录（已点 $taps 次屏幕）…")
            }

            val r = AgentBridge.call("capturePoll", timeoutMs = 4_000L)
            val d = r?.takeIf { it.optBoolean("ok", false) }?.optJSONObject("data")
            if (d != null && d.optBoolean("got", false)) {
                val uid = d.optString("uid").trim()
                val ak = d.optString("accessKey").trim()
                if (uid.isNotEmpty() && ak.isNotEmpty()) {
                    return Creds(
                        uid = uid,
                        accessKey = ak,
                        platform = d.optString("platform"),
                        channelId = d.optString("channelId"),
                    )
                }
                if (uid.isNotEmpty()) {
                    AppState.addLog("抓到 uid=$uid 但 access_key 为空（一次不完整，继续等）")
                }
            }
            // 每 ~5s 看一次进程还在不在（pid 查询有开销，别每轮都查）
            if (++tick % 10 == 0 && FridaInjector.gamePid(pkg) <= 0) return null
            delay(POLL_MS)
        }
        return null
    }

    /** 成功收尾：等页面稳定 → 摘钩子 → 关游戏 → 回到助手界面（账号由调用方写回）。 */
    private suspend fun finishOk(ctx: Context, pkg: String, creds: Creds): Creds {
        // ★2026-10-30 用户：**别在抓到凭证的瞬间强杀进程** —— 那一刻游戏还在加载/落盘，
        //   强杀容易把它的数据写坏。等页面推进到 TITLE(2)（已到 HOME(5) 也算加载完）再关；
        //   等不到（agent 掉了 / 超时）照样收尾，不能让这一步把整个抓取卡住。
        waitStablePage()
        AgentBridge.call("captureStop", timeoutMs = 2_000L)
        GameProcess.kill(pkg)
        // 游戏关掉后用户会停在桌面，把助手拉回前台（root am start，失败也无所谓）
        RootShell.exec("am start -n ${ctx.packageName}/.MainActivity", 8_000L)
        step("完成")
        AppState.addLog(
            "抓取成功：uid=${creds.uid}" +
                (if (creds.platform.isNotBlank()) " platform=${creds.platform}" else ""),
        )
        return creds
    }

    /**
     * 等游戏进 `HOME(5)`（登录后首屏）再让上层关游戏；见类常量的判据说明。
     *
     * 这里只做"等"，不做决策 —— 超时/agent 掉线都会返回，由 [finishOk] 继续收尾。
     */
    private suspend fun waitStablePage() {
        val t0 = SystemClock.elapsedRealtime()
        var last = Int.MIN_VALUE
        while (SystemClock.elapsedRealtime() - t0 < HOME_WAIT_MS) {
            if (!AgentBridge.connected.value) {
                AppState.addLog("agent 已断开 → 不等 HOME 了，直接收尾")
                return
            }
            val vid = runCatching { Navigator.currentViewId() }.getOrDefault(-1)
            if (vid == VIEW_HOME) {
                AppState.addLog("页面已到 ${Navigator.viewLabel(vid)}（登录流程走完）→ 现在关游戏")
                return
            }
            if (vid != last) {
                last = vid
                AppState.addLog("等页面进 HOME 再关游戏…当前 ${Navigator.viewLabel(vid)}")
            }
            delay(POLL_MS)
        }
        AppState.addLog("等 HOME 超时（${HOME_WAIT_MS / 1000}s）→ 仍然关游戏")
    }

    private fun step(text: String) {
        _status.value = text
    }

    private fun installed(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess
}
