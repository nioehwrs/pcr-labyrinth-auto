package com.lmj.labyrinthauto.frida

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.lmj.labyrinthauto.core.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * huabanxie-server（**改过名的 frida-server**）的**预热 + 启动**。
 *
 * ## 为什么"启动时就下"（2026-10-25 用户）
 * 原来 42MB 的压缩包是**等到点「一键黎明界」时才下载** —— 那时注入器正在等 server，
 * 游戏进不去、流程空转（真实日志：11:50:02 才开始下，前面 20s 全耗在"游戏没到前台"）。
 * 现在：
 * * **应用启动后几秒**就在后台把运行组件准备好（`LmjApp` 调 [prepare]），点开始时基本是即开即用；
 * * 还会先看**设备上有没有同版本**（`/data/local/tmp/huabanxie-base`，老环境/镜像里常常已经有一份）
 *   ⇒ 有就直接复用，**连 42MB 都不用下**；
 * * 下载支持**断点续传**（`.part` 保留 + HTTP Range），网络抖一下就接着来，不用从头再来。
 *
 * ## 为什么不能换成官方 frida-server
 * 这份是 PC 那边 `D:\pcrtool\assets\frida\huabanxie-server` 的**改名版**（SELinux 标签
 * `huabanxie_memfd`、进程名不含 frida），专门躲游戏的反注入检测。官方原版会被拦 ⇒ 闪退。
 *
 * ## 流程
 * * [prepare]：缓存 → 设备已有（按字节数认）→ 下载（gz，Range 续传）→ 解压 + **sha256 校验** → chmod；
 * * [ensure]：端口通就直接用；否则 [prepare] 拿文件 → 用 root 拷到 `/data/local/tmp/huabanxie-base`
 *   → `-l 0.0.0.0:1234 -D` 拉起（**唯一**需要 root 的地方，首次会弹 MuMu 授权框）。
 * * 没网/下载失败也不阻断：手工把 ELF 推到 `$DEVICE_PATH` 起服务，[ensure] 看到端口通就跳过。
 */
object FridaServerInstaller {

    /**
     * 发布件的 frida 版本 —— 必须与三处一致：
     * ① 本文件下载的 huabanxie-server；② PC 端 frida 客户端；③ 内嵌的 libfrida-core devkit（见 [FridaInjector]）。
     * 升级时要三处一起换，并同步 [BASE_URL] 的 tag。
     */
    private const val FRIDA_VERSION = "17.16.4"

    /**
     * 发布源：Gitee **公开**发布仓库里一个**固定 tag** 的 Release 附件。
     * 固定 tag ⇒ 地址不随发版变化（APK 更新走另一个 `latest.json`）。
     */
    private const val BASE_URL =
        "https://gitee.com/nioehwrs/labyrinth-auto-release/releases/download/frida-$FRIDA_VERSION"

    /** 设备上放哪儿（沿用既有环境的名字，方便和 PC 版共用同一份） */
    private const val DEVICE_PATH = "/data/local/tmp/huabanxie-base"

    /** 私有目录里的基础名（后面接 `-<abi>`；也认老版本无后缀的那份，老用户不用重下） */
    private const val LOCAL_BASE = "huabanxie-base-$FRIDA_VERSION"

    /** 端口（和 FridaInjector.FRIDA_SERVER_PORT 一致） */
    const val DEFAULT_PORT = 1234

    /**
     * 每个 ABI 的发布件。[rawBytes] / [rawSha256] 是**解压后**的大小与摘要 ——
     * 校验解压结果而不是 gz 的摘要，这样发版时重新压缩也不会让老客户端报"校验失败"。
     * 值由 `tools/release.py` 打印，改 frida 版本时一起改。
     */
    private class Asset(val abi: String, val rawBytes: Long, val rawSha256: String)

    private val ASSETS = listOf(
        Asset("x86_64", 111_587_168L, "dfbcb2d1f856967e9816a465eb0e5a9c3aab1bb75a0b9c35d33c0db1ea626938"),
        // arm64 那份还没做（当前 abiFilters 只放 x86_64，装不到真机上）；做了就在这加一行
    ).associateBy { it.abi }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    /** 预热互斥：启动预热和"点开始时兜底下载"可能同时发生，绝不能下两遍 */
    private val prepLock = Mutex()

    @Volatile private var localReady: File? = null
    @Volatile private var deviceReady = false

    // ────────────────────────── 预热（启动时调） ──────────────────────────

    /**
     * 把运行组件准备好（**不启动服务**）。幂等、并发安全；应用启动后由 `LmjApp` 调用。
     *
     * @param wifiOnly true = 只在 WiFi 下自动下载（用流量时不偷偷下 42MB，等真正开跑再说）
     * @return 私有目录里的那份（null = 用设备上已有的那份 / 暂时没有可用文件）
     */
    suspend fun prepare(ctx: Context, wifiOnly: Boolean = false): File? = prepLock.withLock {
        localReady?.let { if (it.isFile && it.length() > 0) return it }
        if (deviceReady) return null

        val abi = abiOf()
        val asset = ASSETS[abi] ?: run {
            // 界面只说人话；abi 这种细节进日志
            if (!AbiRepair.deviceSupportsX86_64()) {
                // ★ARM 真机（2026-10-30）：不是"发布件漏了"，是这套工具链只有 x86_64 一份 ——
                //   说清楚，别让用户以为是下载失败。
                AppState.setComponent("需要 x86 模拟器")
                AppState.addLog(
                    "运行组件不可用：这台设备的 CPU 是 ${AbiRepair.deviceAbiText()}，本工具只在 " +
                        "x86_64 模拟器（MuMu / 雷电等 x86 版）上工作，ARM 真机暂不支持",
                )
            } else {
                AppState.setComponent("运行组件不可用")
                AppState.addLog("运行组件缺失：$abi 架构没有发布件")
            }
            return null
        }

        // ① 私有目录缓存
        cached(ctx, asset)?.let { f ->
            f.setExecutable(true, false)
            localReady = f
            AppState.setComponent("运行组件已就绪")
            return f
        }

        // ② 设备上已经有一份同版本的（老环境/镜像里常带）→ 省下 42MB
        if (deviceHas(asset)) {
            deviceReady = true
            AppState.setComponent("运行组件已就绪（复用设备上的副本，未下载）")
            AppState.addLog("设备上已有同版本的运行组件（${asset.rawBytes} 字节）→ 跳过下载")
            return null
        }

        // ③ 只在"不计流量"的网络下自动下（省流量；用流量时等开跑那一刻再下）
        if (wifiOnly && !canAutoDownload(ctx)) {
            AppState.setComponent("运行组件未下载")
            AppState.addLog("当前走的是移动流量，先不自动下载运行组件（开始跑时再下，约 42MB）")
            return null
        }

        return downloadAndInstall(ctx, asset)
    }

    /** 下载（断点续传）→ 解压 → sha256 校验 → chmod。失败保留 `.part` 供下次续传。 */
    private suspend fun downloadAndInstall(ctx: Context, asset: Asset): File? {
        val gzName = "huabanxie-server-$FRIDA_VERSION-${asset.abi}.gz"
        val url = "$BASE_URL/$gzName"
        val part = File(ctx.filesDir, "${LOCAL_BASE}-${asset.abi}.part.gz")
        val out = File(ctx.filesDir, "${LOCAL_BASE}-${asset.abi}")
        val had = if (part.isFile) part.length() else 0L
        AppState.addLog(
            "开始下载运行组件（解压后 ${asset.rawBytes / 1048576}MB，压缩包约 42MB）" +
                if (had > 0) "，从 ${had / 1048576}MB 续传" else "",
        )
        AppState.setComponent("运行组件下载中…")

        try {
            download(url, part) { pct, done, total ->
                AppState.setComponent("运行组件下载中 $pct%（${done / 1048576}/${total / 1048576}MB）")
                if (pct % 12 < 2) AppState.addLog("下载运行组件：$pct%（${done / 1048576}MB / ${total / 1048576}MB）")
            }
        } catch (t: Throwable) {
            AppState.setComponent("运行组件下载失败")
            AppState.addLog("下载失败：${t.message}（已下的部分保留，可续传；没网也可以手动推到 $DEVICE_PATH）")
            return null
        }

        val rawSha = try {
            gunzip(part, out)
        } catch (t: Throwable) {
            AppState.addLog("解压失败：${t.message} → 删掉重下")
            part.delete()
            out.delete()
            AppState.setComponent("运行组件解压失败")
            return null
        }
        part.delete()

        if (out.length() != asset.rawBytes || !rawSha.equals(asset.rawSha256, ignoreCase = true)) {
            AppState.addLog(
                "运行组件校验失败（${out.length()}/${asset.rawBytes}，sha256 ${rawSha.take(12)}…）→ 已删除",
            )
            out.delete()
            AppState.setComponent("运行组件校验失败")
            return null
        }
        out.setExecutable(true, false)
        localReady = out
        AppState.setComponent("运行组件已就绪")
        // 只报大小/校验结果，不写文件名（2026-10-28 用户："日志不要显示下载的组件名"）
        AppState.addLog("运行组件已就绪（${out.length()} 字节，sha256 校验通过）")
        return out
    }

    // ────────────────────────── 启动 ──────────────────────────

    /**
     * ★cs128：组件**僵死**时强杀重启（2026-11-21 实测：上一局游戏被 force-stop 时，eternalize
     * 的 agent 会话随进程一起死，frida-server 被卡死 —— 端口还在 LISTEN ⇒ [ensure]/portOpen
     * 一直判"活着"、永远不会重启它；后续 attach 全部 16s 超时。杀游戏治不了组件的病，必须重启组件本身）。
     */
    suspend fun restart(ctx: Context, port: Int = DEFAULT_PORT): Boolean {
        AppState.addLog("重启运行组件：root 杀旧进程（$DEVICE_PATH）…")
        RootShell.exec("pkill -f huabanxie-base", 8_000L, direct = true)
        var waited = 0
        while (waited < 5_000) {           // ★repeat{} 是 lambda，里面不能 break（2026-11-21 踩过）
            if (!portOpen(port)) break
            delay(500)
            waited += 500
        }
        return ensure(ctx, port)
    }

    /** 保证服务在跑；true = 端口已经通了。 */
    suspend fun ensure(ctx: Context, port: Int = DEFAULT_PORT): Boolean {        // ★运行组件（2026-11-4 从 APK 摘出）：注入前必须就绪 —— 用户点"开始"就是在等这个，
        //   所以这里**不限制网络**（wifiOnly=false），下完/校验通过才往下走。
        val bridge = prepareBridge(ctx, wifiOnly = false)
        if (bridge == null || !FridaCore.ensureLoaded(ctx)) {
            AppState.setFridaStatus("运行组件未就绪（下载或校验失败，看日志；联网后会自动重试）")
            return false
        }

        if (portOpen(port)) return true

        // 正常情况启动时早就准备好了（prepare 命中缓存/设备副本）；
        // wifiOnly=false：真正开跑时**不再限制网络**，该下就下（用户点了开始就是在等这个）
        val local = prepare(ctx, wifiOnly = false)
        if (local == null && !deviceReady) {
            AppState.setFridaStatus("拿不到运行组件（下载失败或该架构没有发布件，看日志）")
            return false
        }
        if (local != null && !deviceCopyUpToDate(local)) {
            AppState.addLog("把运行组件拷到设备目录（要 root，首次会弹授权框）…")
            val r = RootShell.exec(
                "cp -f ${local.absolutePath} $DEVICE_PATH && chmod 755 $DEVICE_PATH && ls -l $DEVICE_PATH",
                180_000L,
                direct = true,   // 长命令不占常驻 shell 通道（否则 180s 内堵住所有 root 命令）
            )
            AppState.addLog("拷贝结果：${redact(r.text.take(160))}")
        }
        return launch(port)
    }

    private suspend fun launch(port: Int): Boolean {
        RootShell.exec("chmod 755 $DEVICE_PATH", 8_000L)
        AppState.addLog("启动运行组件（root）：-l 0.0.0.0:$port -D")
        val start = RootShell.exec("$DEVICE_PATH -l 0.0.0.0:$port -D", 20_000L)
        AppState.addLog(
            "启动命令 exit=${start.exit} 输出：${redact(start.text.take(300)).ifBlank { "（空）" }}",
        )
        repeat(25) {
            delay(400)
            if (portOpen(port)) {
                AppState.addLog("运行组件已起来（127.0.0.1:$port 通了）")
                return true
            }
        }
        // ★诊断（2026-11-10）：雷电 Android 9 上设备侧端口明明在 LISTEN，portOpen 却一直 false。
        //   portOpen 内部把异常吞了，这里在失败时单独连一次并把真实原因写进日志，便于定位
        //   「连不上」到底是超时、拒绝还是权限，以及区分「启动慢于 10s 窗口」。
        val why = withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2_000) }
                "复核时其实通了（只是启动慢于 10s 窗口）"
            }.getOrElse { t -> "${t.javaClass.simpleName}: ${t.message}" }
        }
        AppState.addLog("等不到运行组件端口：$why")
        return false
    }

    // ────────────────────────── 文件 / 网络 ──────────────────────────

    /**
     * 设备上那份是不是同版本。
     *
     * ★2026-10-26 用户："替换过倒是不用太担心……算了加个检查吧" ——
     * 原来**只比字节数**：镜像里那份如果被换过（大小相同、内容不同），会被当成好的直接复用，
     * 然后启动失败或行为诡异，且很难查。现在补上 sha256：
     *  * 有 `sha256sum` 输出就以它为准（106MB 算一次 ~1s，且每个进程只查一次）；
     *  * 命令不存在 / 读不了（极老 toybox）时**退化为只比大小**，不让检查本身变成新的失败点。
     */
    private suspend fun deviceHas(asset: Asset?): Boolean {
        if (asset == null) return false
        val text = runCatching {
            RootShell.exec("ls -l $DEVICE_PATH; sha256sum $DEVICE_PATH 2>/dev/null", 30_000L, direct = true).text
        }.getOrDefault("")
        if (text.contains("No such")) return false
        if (!text.contains(asset.rawBytes.toString())) return false
        val sha = Regex("[0-9a-fA-F]{64}").find(text)?.value ?: return true
        if (sha.equals(asset.rawSha256, ignoreCase = true)) return true
        AppState.addLog(
            "设备上的运行组件大小对但 sha256 不符（${sha.take(12)}…，应为 ${asset.rawSha256.take(12)}…）" +
                "→ 不复用它，改为下载",
        )
        return false
    }

    private fun abiAsset(): Asset? = ASSETS[abiOf()]

    /** 私有目录里已有的那份：先认带 abi 的新名字，再认老版本无后缀的（老用户不重复下载 42MB）。 */
    private fun cached(ctx: Context, asset: Asset): File? {
        for (name in listOf("$LOCAL_BASE-${asset.abi}", LOCAL_BASE)) {
            val f = File(ctx.filesDir, name)
            if (f.isFile && f.length() == asset.rawBytes) return f
        }
        return null
    }

    // ────────────────────────── 运行组件 .so（2026-11-4 从 APK 里摘出来） ──────────────────────────

    /**
     * 运行组件的版本 = frida 版本 + **桥协议版本**。
     *
     * `bridge.c` 里的 JNI 方法签名一变就必须 +1：App 的 `external fun` 与它是紧耦合，
     * 加载旧 .so 会 `UnsatisfiedLinkError`、严重时 native 崩溃 ⇒ **版本不符就绝不加载**。
     */
    val BRIDGE_VER: String = "$FRIDA_VERSION-1"

    /** 解压后大小与摘要（由 `tools/build_frida_bridge.ps1` 产物算出；改 bridge 后要一起改） */
    private const val BRIDGE_RAW_BYTES = 121_707_296L
    private const val BRIDGE_SHA = "a42db0a8c380c0f310d89f76fac22d06afa7585025b08ad3f20ee6a0f881ea33"

    private val bridgeLock = Mutex()

    @Volatile
    private var bridgeReady: File? = null

    private fun bridgeLocal(ctx: Context): File =
        File(File(ctx.filesDir, "lib"), "libfrida_bridge-$BRIDGE_VER.so")

    /** 私有目录里那份（存在且大小对）。[FridaCore] 只认这个，不负责下载。 */
    fun bridgeFile(ctx: Context): File? {
        val f = bridgeLocal(ctx)
        return if (f.isFile && f.length() == BRIDGE_RAW_BYTES) f else null
    }

    /**
     * 把运行组件准备好（**不加载**）。幂等、并发安全；应用启动时与 [prepare] 一起调。
     *
     * 之前它打在 APK 里（121MB，压缩后占一半体积）；现在和 huabanxie-server 同待遇：
     * 首次启动后台下载（gz 约 46MB，Range 续传）→ 解压 → sha256 校验 → 落盘。
     *
     * @return 可加载的 .so（null = 还没准备好：移动流量下跳过 / 下载或校验失败）
     */
    suspend fun prepareBridge(ctx: Context, wifiOnly: Boolean = false): File? = bridgeLock.withLock {
        bridgeReady?.let { if (it.isFile && it.length() == BRIDGE_RAW_BYTES) return it }
        bridgeFile(ctx)?.let {
            bridgeReady = it
            return it
        }
        if (wifiOnly && !canAutoDownload(ctx)) {
            AppState.addLog("当前走的是移动流量，先不自动下载运行组件（开始跑时再下，约 46MB）")
            return null
        }

        val dir = File(ctx.filesDir, "lib").apply { mkdirs() }
        val gz = File(dir, "libfrida_bridge-$BRIDGE_VER.so.gz")
        val tmp = File(dir, "libfrida_bridge-$BRIDGE_VER.so.tmp")
        val had = if (gz.isFile) gz.length() else 0L
        AppState.addLog(
            "开始下载运行组件（解压后 ${BRIDGE_RAW_BYTES / 1048576}MB，压缩包约 46MB）" +
                if (had > 0) "，从 ${had / 1048576}MB 续传" else "",
        )
        try {
            download("$BASE_URL/libfrida_bridge-$BRIDGE_VER.so.gz", gz) { pct, done, total ->
                // ★状态栏与 frida-server 共用一个口径（都叫"运行组件"），别分成两个名字
                AppState.setComponent("运行组件下载中 $pct%（${done / 1048576}/${total / 1048576}MB）")
                if (pct % 20 < 3) AppState.addLog("下载运行组件：$pct%（${done / 1048576}MB）")
            }
        } catch (t: Throwable) {
            AppState.setComponent("运行组件下载失败")
            AppState.addLog("运行组件下载失败：${t.message}（已下的部分保留，可续传）")
            return null
        }

        val sha = try {
            gunzip(gz, tmp)
        } catch (t: Throwable) {
            gz.delete()
            tmp.delete()
            AppState.setComponent("运行组件解压失败")
            AppState.addLog("运行组件解压失败：${t.message} → 删掉重下")
            return null
        }
        if (sha != BRIDGE_SHA) {
            gz.delete()
            tmp.delete()
            AppState.setComponent("运行组件校验失败")
            AppState.addLog("运行组件校验失败（sha256 ${sha.take(12)}… ≠ ${BRIDGE_SHA.take(12)}…）→ 删掉重下")
            return null
        }
        val target = bridgeLocal(ctx)
        target.delete()
        if (!tmp.renameTo(target)) {
            // 极少数情况下 rename 跨挂载点会失败，退回复制
            runCatching { tmp.copyTo(target, overwrite = true) }
            tmp.delete()
            if (!target.isFile || target.length() != BRIDGE_RAW_BYTES) {
                AppState.addLog("运行组件落盘失败 → 下次再试")
                return null
            }
        }
        gz.delete()
        bridgeReady = target
        AppState.setComponent("运行组件已就绪")
        AppState.addLog("运行组件已就绪（${target.length() / 1048576}MB）")
        target
    }

    /** 带 Range 的流式下载：已有 `.part` 就续传（206），服务器不支持就重头来（200）。 */
    private fun download(url: String, part: File, onProgress: (Int, Long, Long) -> Unit) {
        part.parentFile?.mkdirs()
        var have = if (part.isFile) part.length() else 0L
        val builder = Request.Builder().url(url).header("User-Agent", "lmj-auto")
        if (have > 0) builder.header("Range", "bytes=$have-")
        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val resume = resp.code == 206
            if (!resume) have = 0L
            val body = resp.body ?: throw IllegalStateException("响应为空")
            val bodyLen = body.contentLength()
            val total = if (bodyLen > 0) have + bodyLen else 0L
            var done = have
            var lastPct = -10
            FileOutputStream(part, resume).use { fo ->
                val buf = ByteArray(1 shl 20)
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        fo.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct >= lastPct + 3) { lastPct = pct; onProgress(pct, done, total) }
                        }
                    }
                }
                fo.flush()
            }
        }
    }

    /** 解压 gz 到 [out]，边写边算 sha256（返回十六进制摘要）。 */
    private fun gunzip(src: File, out: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        GZIPInputStream(src.inputStream(), 1 shl 20).use { input ->
            FileOutputStream(out).use { fo ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                    fo.write(buf, 0, n)
                }
                fo.flush()
            }
        }
        return md.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    /** 设备上那份是不是同一份（按大小比；文件名也认一下，避免误判别的文件）。 */
    private suspend fun deviceCopyUpToDate(local: File): Boolean {
        val text = RootShell.exec("ls -l $DEVICE_PATH", 8_000L).text
        if (text.contains("No such file") || text.contains("No such")) return false
        return text.contains(local.length().toString())
    }

    /**
     * ★2026-11-10：必须在 [Dispatchers.IO] 上跑。抓凭证是从 Compose 的
     * `rememberCoroutineScope()`（主线程）一路调进来的，原来直接在主线程 `Socket.connect()`
     * ⇒ StrictMode 抛 `NetworkOnMainThreadException`，又被 `runCatching` 吞成 false
     * ⇒ 设备侧端口明明在 LISTEN，App 却永远判"没通"，抓凭证在 ensureServer 就中止。
     */
    private suspend fun portOpen(port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 800) }
            true
        }.getOrDefault(false)
    }

    /**
     * 能不能"顺手"下载（WiFi / 有线 / 系统标注的不计费网络；移动流量留给"真正开跑时"）。
     *
     * ⚠ 2026-10-27 实测（0.3.10 前）：这里以前**恒为 false** —— manifest 少声明
     * `ACCESS_NETWORK_STATE`，[ConnectivityManager.getNetworkCapabilities] 直接抛 SecurityException，
     * 又被 runCatching 静默吞掉，于是"启动自动下载运行组件"在任何设备上都没真正执行过
     * （老用户机器里早有副本，所以从来没暴露）。
     *
     * 现在：判不出来就**允许下载**并写日志 —— 宁可多花 42MB，也不要永远不下。
     */
    private fun canAutoDownload(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        if (caps == null) {
            AppState.addLog("判不出当前网络（activeNetwork=$active）→ 按「可以下载」处理")
            return@runCatching true
        }
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrElse {
        AppState.addLog("网络判定出错（${it.javaClass.simpleName}: ${it.message}）→ 按「可以下载」处理")
        true
    }

    /**
     * 日志脱敏（2026-10-28 用户："日志不要显示下载的组件名，和所有的 huabanxie-server 字样"）。
     *
     * root 命令的输出（`ls -l` / `sha256sum`）会带真实文件名和路径，进日志前一律换成「运行组件」；
     * 用正则挡住整个名字（含版本与架构后缀），以后别处再漏也能兜住。
     */
    private fun redact(text: String): String = text
        .replace(Regex("huabanxie[\\w.\\-]*"), "运行组件")
        .replace(DEVICE_PATH, "运行组件")

    /** 当前 ABI 的规范名（与发布件命名一致） */
    private fun abiOf(): String {
        val a = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase(Locale.US) ?: ""
        return when {
            a.startsWith("x86_64") -> "x86_64"
            a.startsWith("arm64") -> "arm64"
            else -> a
        }
    }
}
