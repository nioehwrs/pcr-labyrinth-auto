package com.lmj.labyrinthauto.frida

import android.content.Context
import android.os.Build
import com.lmj.labyrinthauto.core.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * frida-server（**改过名的 huabanxie-server**）按需下载 + 起服务。
 *
 * ## 为什么改成"下载"（2026-10-25 用户："把 frida-server 单拆出来，让应用本体负责下载"）
 * 那份裸 ELF 是 **106MB**，塞进 APK 会让包体变成 169MB：
 * * Gitee 的 Release 附件单文件上限约 100MB ⇒ 169MB 的 APK 根本传不上去；
 * * 用户每次更新也要白下 106MB。
 * 现在它作为**独立发布件**挂在 Gitee Release 上（gzip 后约 42MB），首跑时下载一次、存进私有目录，
 * 之后就一直复用 —— APK 掉到 60MB 出头，而且**升级 frida 不必重发 APK**。
 *
 * ## 为什么不能换成官方 frida-server
 * 这份是 PC 那边 `D:\pcrtool\assets\frida\huabanxie-server` 的**改名版**（SELinux 标签
 * `huabanxie_memfd`、进程名不含 frida），专门用来躲游戏的反注入检测。官方原版会被拦 ⇒ 闪退。
 * 所以发布件必须是我们自己那两份 gz，**不能**去 frida 官网下。
 *
 * ## 流程（[ensure]）
 * ① 端口已经通 → 什么都不做；
 * ② 私有目录里没有 → 从发布源下载 `huabanxie-server-<版本>-<abi>.gz`
 *    → **解压后按 sha256 校验**（内容正确性；重新压缩也不影响）→ 解压到私有目录 → chmod 755；
 * ③ `/data/local/tmp` 那份不是同一份（按大小比）→ 用 root 拷过去；
 * ④ 用 root 以常驻方式拉起（`-l 0.0.0.0:1234 -D`）。
 *
 * ★这是整套流程里**唯一**需要 root 的地方（写 /data/local/tmp + 起 root 进程），
 *   首次会弹 MuMu 的 SuperUser 授权框，选「允许」即可（想省事可以勾"永久记住"）。
 * ★下载失败/没网时仍有兜底：手工把那份 ELF 推到 `/data/local/tmp/huabanxie-base` 并起服务即可，
 *   [ensure] 看到端口通就直接跳过。
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
     * 每个 ABI 的发布件。
     *
     * [rawBytes] / [rawSha256] 是**解压后**的大小与摘要 —— 校验解压结果而不是 gz 的摘要，
     * 这样发版时重新压缩（不同 gzip 级别/mtime）也不会让老客户端报"校验失败"。
     * 值由 `tools/release.py` 打印，改 frida 版本时一起改。
     */
    private class Asset(val abi: String, val rawBytes: Long, val rawSha256: String)

    private val ASSETS = listOf(
        Asset("x86_64", 111_587_168L, "dfbcb2d1f856967e9816a465eb0e5a9c3aab1bb75a0b9c35d33c0db1ea626938"),
        // arm64 那份还没做（当前 abiFilters 只放 x86_64，装不到真机上）；做了就加一行，代码不用动
    ).associateBy { it.abi }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    /** 保证 frida-server 在跑；true = 端口已经通了。 */
    suspend fun ensure(ctx: Context, port: Int = DEFAULT_PORT): Boolean {
        if (portOpen(port)) return true

        val local = ensureLocalCopy(ctx) ?: run {
            AppState.setFridaStatus("拿不到 frida-server（下载失败或该架构没有发布件，看日志）")
            return false
        }

        if (!deviceCopyUpToDate(local)) {
            AppState.addLog("把 huabanxie-server 拷到 $DEVICE_PATH（要 root，首次会弹授权框）…")
            val r = RootShell.exec(
                "cp -f ${local.absolutePath} $DEVICE_PATH && chmod 755 $DEVICE_PATH && ls -l $DEVICE_PATH",
                180_000L,
            )
            AppState.addLog("拷贝结果：${r.text.take(160)}")
        }

        AppState.addLog("启动 huabanxie-server（root）：$DEVICE_PATH -l 0.0.0.0:$port -D")
        RootShell.exec("$DEVICE_PATH -l 0.0.0.0:$port -D", 20_000L)
        repeat(25) {
            delay(400)
            if (portOpen(port)) {
                AppState.addLog("huabanxie-server 已起来（127.0.0.1:$port 通了）")
                return true
            }
        }
        AppState.addLog("等不到 huabanxie-server 端口（看是不是 root 被拒了）")
        return false
    }

    // ────────────────────────── 本地那份：缓存优先，缺了就下载 ──────────────────────────

    private suspend fun ensureLocalCopy(ctx: Context): File? = withContext(Dispatchers.IO) {
        val abi = abiOf()
        val asset = ASSETS[abi]
        if (asset == null) {
            AppState.addLog(
                "没有 $abi 架构的 huabanxie-server 发布件（只在 x86_64 模拟器上验证过）→ " +
                    "请把 ELF 手工推到 $DEVICE_PATH 并起服务，或改用 x86_64 模拟器",
            )
            return@withContext null
        }

        cached(ctx, asset)?.let { f ->
            f.setExecutable(true, false)
            return@withContext f
        }

        val url = "$BASE_URL/huabanxie-server-$FRIDA_VERSION-${asset.abi}.gz"
        val part = File(ctx.filesDir, "${LOCAL_BASE}-${asset.abi}.part.gz")
        val out = File(ctx.filesDir, "${LOCAL_BASE}-${asset.abi}")
        AppState.addLog(
            "首次运行要下载 huabanxie-server（改名版 frida-server）" +
                "约 ${asset.rawBytes / 1048576}MB（压缩包约 42MB）：$url",
        )

        val ok = try {
            download(url, part, asset.rawBytes)
        } catch (t: Throwable) {
            AppState.addLog("下载失败：${t.message}（没网也可以手动推到 $DEVICE_PATH）")
            false
        }
        if (!ok) {
            part.delete()
            return@withContext null
        }

        val rawSha = try {
            gunzip(part, out)
        } catch (t: Throwable) {
            AppState.addLog("解压失败：${t.message}")
            part.delete()
            out.delete()
            return@withContext null
        }
        part.delete()

        if (out.length() != asset.rawBytes || !rawSha.equals(asset.rawSha256, ignoreCase = true)) {
            AppState.addLog(
                "huabanxie-server 校验失败（大小 ${out.length()}/${asset.rawBytes}，" +
                    "sha256 ${rawSha.take(12)}…）→ 已删除，稍后重试",
            )
            out.delete()
            return@withContext null
        }
        out.setExecutable(true, false)
        AppState.addLog("已下载并校验：${out.name}（${out.length()} 字节）")
        out
    }

    /** 私有目录里已有的那份：先认带 abi 的新名字，再认老版本无后缀的（老用户不重复下载 42MB）。 */
    private fun cached(ctx: Context, asset: Asset): File? {
        for (name in listOf("$LOCAL_BASE-${asset.abi}", LOCAL_BASE)) {
            val f = File(ctx.filesDir, name)
            if (f.isFile && f.length() == asset.rawBytes) return f
        }
        return null
    }

    /** 流式下载到 [dst]，每 6% 打一条进度；正常结束返回 true（大小不符算失败）。 */
    private fun download(url: String, dst: File, expectRaw: Long): Boolean {
        dst.parentFile?.mkdirs()
        dst.delete()
        val req = Request.Builder().url(url).header("User-Agent", "lmj-auto").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val total = resp.body?.contentLength() ?: -1L
            val input = resp.body?.byteStream() ?: throw IllegalStateException("响应为空")
            var lastPct = -10
            var done = 0L
            FileOutputStream(dst).use { fo ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    fo.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct >= lastPct + 6) {
                            lastPct = pct
                            AppState.addLog("下载 huabanxie-server：$pct%（${done / 1048576}MB / ${total / 1048576}MB）")
                        }
                    }
                }
                fo.flush()
            }
            AppState.addLog("下载完成：${dst.length()} 字节（期望约 ${expectRaw / 1048576}MB 解压后）")
        }
        return dst.length() > 0
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

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 800) }
        true
    }.getOrDefault(false)

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
