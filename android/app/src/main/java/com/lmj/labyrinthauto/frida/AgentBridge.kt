package com.lmj.labyrinthauto.frida

import com.lmj.labyrinthauto.core.AppState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * app 侧的 socket 服务端（`PLAN.md §4` 方案 A 的"接电话的"）。
 *
 * 协议：一行一条 JSON，UTF-8，\n 结尾；agent 主动连过来（agent_bridge.js）。
 * 用法：AgentBridge.start() 之后，`call("labStatus")` 就能拿到 agent 的返回值。
 */
object AgentBridge {

    const val PORT = 27183

    private val ids = AtomicLong(1L)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()

    /** 最近一次超时的调用名（去重：同名连续超时只报一次，直到有成功回包 —— 见 [call] 里的说明） */
    @Volatile
    private var lastTimeoutFn: String? = null

    /** 连续超时计数（任何调用都算；成功一次清零）—— 到 3 就 [noteStale] 触发自愈，见 [call]。 */
    private val timeoutStreak = java.util.concurrent.atomic.AtomicInteger(0)

    /** 最近一次**成功回包**的时刻（0 = 还没成功过）。用于区分"agent 在忙"和"agent 真死了"，见 [BUSY_GRACE_MS]。 */
    @Volatile
    private var lastOkAt = 0L

    /**
     * ★2026-10-27「拥堵不算掉线」：这段时间内**有过成功回包** ⇒ 超时只是单通道被重活占住。
     *
     * 真实病例（0.3.7 测试日志，12:11 / 12:38 / 13:19 各一次）：
     * ```
     * 12:11:12  labStatus（5000ms）超时 → 同时仍在飞：findTextIn（已等 5826ms）
     * 12:11:22  agent 疑似掉线（连续 3 次调用超时）→ 等它重连
     * ```
     * agent 根本没掉线，只是 `findTextIn` 全树遍历跑了 5.8s，把通道堵住了 —— 后面排队的
     * `viewId`（3s）、`dialog`（6s）排着队超时，攒满 3 次就判"掉线"，于是重注入 3 次、
     * 再杀游戏重来（13:19 那局白跑）。20s 内有过成功回包就说明连接是活的，不该判死。
     */
    private const val BUSY_GRACE_MS = 20_000L

    /** 在飞调用（id → "fn@发出时刻"）：超时时用它报出**真正还卡着的那次调用**（见 [call]）。
     *  ⚠为什么需要：先超时的往往是**便宜调用**（`viewId` T_FAST=3s），而真正卡住 agent 的常是
     *  之前发出的**重活**（堆扫描 `labChoices` 实测 3.1s ✗、全树 find ✗）—— 只看"第一个超时"会误判。 */
    private val pendingInfo = ConcurrentHashMap<Long, Pair<String, Long>>()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var clientOut: OutputStream? = null

    fun start() {
        if (running) return
        running = true
        Thread({ acceptLoop() }, "agent-server").apply { isDaemon = true }.start()
        AppState.addLog("agent 监听已开启（127.0.0.1:$PORT）")
    }

    fun stop() {
        running = false
        runCatching { clientOut?.close() }
        clientOut = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _connected.value = false
        pending.values.forEach { runCatching { it.cancel() } }
        pending.clear()
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            // ★必须绑通配地址（双栈），不能只绑 "127.0.0.1"：
            //   frida 的 Socket 把 127.0.0.1 当 localhost 解析，第一次尝试可能走 ::1，
            //   只绑 IPv4 时它直接拿到 Connection refused（实测踩过）。
            //   绑通配后 v4/v6 通吃；下面再校验只收本机回环来的连接。
            ss.bind(InetSocketAddress(PORT), 4)
            serverSocket = ss
            while (running) {
                val sock = ss.accept()
                attach(sock)
            }
        } catch (t: Throwable) {
            if (running) AppState.addLog("agent 监听结束：${t.message}")
        }
    }

    private fun attach(sock: Socket) {
        if (sock.inetAddress?.isLoopbackAddress != true) {
            AppState.addLog("拒绝非本机连接：${sock.inetAddress}")
            runCatching { sock.close() }
            return
        }
        runCatching { clientOut?.close() }
        clientOut = sock.getOutputStream()
        AppState.addLog("agent 已连上（来自 ${sock.inetAddress?.hostAddress}）")
        _connected.value = true
        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) onLine(line)
                }
            } catch (_: Throwable) {
            } finally {
                _connected.value = false
                AppState.addLog("agent 断开（会自动重连）")
            }
        }, "agent-read").apply { isDaemon = true }.start()
    }

    /**
     * 连续超时若干次后判定"这条连接已经死了"：关掉旧 socket 并置为未连接，
     * 让 agent 侧的看门狗/重连逻辑重新连进来（对端进程被杀时，我们的写不会报错）。
     */
    fun noteStale(reason: String) {
        if (!_connected.value && clientOut == null) return
        AppState.addLog("agent 疑似掉线（$reason）→ 等它重连")
        _connected.value = false
        runCatching { clientOut?.close() }
        clientOut = null
    }

    private fun onLine(line: String) {
        val obj = runCatching { JSONObject(line) }.getOrNull() ?: return
        if (obj.has("event")) {
            // agent 的 console 转发（agent_bridge.js）：打进 app 日志，别丢了
            if (obj.optString("event") == "log") {
                obj.optString("line").takeIf { it.isNotBlank() }?.let {
                    AppState.addLog("[agent] ${it.take(200)}")
                }
            }
            return                            // hello / log 这类事件，不用回包
        }
        val id = obj.optLong("id", -1L)
        if (id < 0) return
        pending.remove(id)?.complete(obj)
    }

    /**
     * 发一条命令并等回包。
     * @return agent 的回包 JSON（`ok`/`data`/`err`）；null = 没连上或超时。
     */
    suspend fun call(fn: String, args: List<Any?> = emptyList(), timeoutMs: Long = 5_000L): JSONObject? {
        val out = clientOut ?: return null
        val id = ids.incrementAndGet()
        val t0 = System.currentTimeMillis()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        pendingInfo[id] = fn to t0
        val req = JSONObject().apply {
            put("id", id)
            put("fn", fn)
            put("args", JSONArray(args))
        }
        try {
            synchronized(this) {
                out.write((req.toString() + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (t: Throwable) {
            pending.remove(id)
            return null
        }
        val res = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(id)
        pendingInfo.remove(id)
        // ★超时要留证据（2026-10-17 卡死复盘）：agent 是**单条 RPC 通道**，一次调用卡住 ⇒ 后面所有调用
        //   全部超时（表现为"viewId 连续 3 次超时"，看着像掉线 ✗）。同名连续超时只报一次。
        //   ★并且把**此刻还没回来的调用**一起报出来 —— 先超时的往往是便宜调用（`viewId` 3s），
        //   真正卡住 agent 的多半是之前那次**重活**（`labChoices` 扫堆 3.1s / 全树 find ✗）。
        if (res == null) {
            val timeoutAt = System.currentTimeMillis()
            // ★★连续超时 ⇒ 判定"这条连接死了"并触发自愈（2026-10-17 第四次卡死复盘）：
            //   原来只有**注入循环**的 `viewId` 计数会调 [noteStale]，而导航阶段卡住时那个循环没在看
            //   ⇒ 一卡到底（日志里 90s+ 全是超时、没人管 ✗）。现在放在最底层：**任何**调用连续 3 次
            //   超时 ⇒ noteStale（置未连接 ⇒ 注入循环按 autoRecover 重注入 / 杀进程重开 ✓）。成功一次清零。
            // ★2026-10-27 加一道"拥堵豁免"（见 [BUSY_GRACE_MS]）：**别的调用还在飞 + 20s 内有过成功回包**
            //   ⇒ 这是单通道被重活占住，连接是活的，**既不算掉线、也不累积计数**（清零，等重活回来）。
            //   否则一次 5.8s 的全树遍历就能把后面排队的轻调用全部超时攒成"掉线"，进而误触发
            //   重注入 / 杀游戏重来。
            val othersInflight = pendingInfo.keys.any { it != id }
            val busy = othersInflight && (timeoutAt - lastOkAt) < BUSY_GRACE_MS
            if (busy) {
                timeoutStreak.set(0)
            } else if (timeoutStreak.incrementAndGet() >= 3) {
                timeoutStreak.set(0)
                noteStale("连续 3 次调用超时（最近：$fn）")
            }
            if (lastTimeoutFn != fn) {
                lastTimeoutFn = fn
                val now = timeoutAt
                val inflight = pendingInfo.entries
                    .filter { it.key != id }
                    .joinToString("｜") { "${it.value.first}（已等 ${now - it.value.second}ms）" }
                    .take(240)
                // ★三种情形要分清（2026-10-17 实机教训：上一版把"只有自己在飞"误判成"进程僵了" ✗,
                //   其实那次是 `labShop` **自己**跑了 8686ms 的重活 ⇒ 措辞必须区分"它自己慢"和"没人占着"）：
                //   ① 别的调用还在飞 ⇒ 是那次重活占住了单通道；
                //   ② 只有它自己在飞、且已跑很久 ⇒ **它就是那个重活**（自己慢，不是掉线）；
                //   ③ 只有它自己在飞、刚发出就超时（≈timeoutMs）⇒ agent 没应答（真僵/断开）。
                val selfMs = now - t0
                AppState.addLog(
                    "agent 调用超时：$fn（${timeoutMs}ms 没回包，实际等了 ${selfMs}ms）→ " +
                        when {
                            inflight.isNotEmpty() ->
                                "同时仍在飞：$inflight（多半是那次重活占住了单通道）" +
                                    if (busy) " ⇒ 按拥堵处理，不判掉线" else ""
                            selfMs >= timeoutMs * 3 / 2 ->
                                "当时只有它自己在飞，且它自己就跑了 ${selfMs}ms ⇒ **它就是那个重活**（不是掉线）"
                            else -> "当时没有别的调用在飞 ⇒ agent 没应答（真僵/断开，等自愈）"
                        },
                )
            }
        } else {
            lastTimeoutFn = null
            timeoutStreak.set(0)
            lastOkAt = System.currentTimeMillis()      // 拥堵豁免的判据：最近一次"活着"的证据
        }
        return res
    }
}
