package com.lmj.labyrinthauto.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * **应用内自动更新**（2026-10-25 用户："0.3.0 要带自动更新"）。
 *
 * ## 为什么长这样
 * 这个应用是 sideload 分发的（没上架任何商店），所以：
 * * **不可能静默安装**（除 root/设备管理员）⇒ 流程 = 检查 → 下载 → 拉起系统安装器让用户点一下；
 * * 更新包放在 **Gitee 公开仓库** `nioehwrs/labyrinth-auto-release`，源码在私有仓库，App 端不需要任何密钥；
 * * 判断版本只认 **versionCode**（递增整数），不比版本名字符串；
 * * 覆盖安装必须同签名 ⇒ `debug` 包不允许自更新（见 [isDebuggable]），并且下载后会**校验收到的包确实是本应用签的名**。
 *
 * ## 更新源
 * `https://gitee.com/nioehwrs/labyrinth-auto-release/raw/master/update/latest.json`
 * （由 `tools/release.py` 在发版时写入，字段见 [Info]）。可用 [saveUrl] 临时换源（镜像/自测），
 * 以后换托管（OSS 等）只改地址、代码不动。
 *
 * ## 安全
 * 三道校验：① HTTPS；② `sha256`（清单里给，防传输损坏/篡改）；③ **签名摘要与本机一致**
 * （防"别人拿个同包名的恶意 APK 塞进更新源"）。
 */
object UpdateChecker {

    /** 默认更新源（Gitee raw；发版脚本会维护这个文件） */
    const val DEFAULT_URL =
        "https://gitee.com/nioehwrs/labyrinth-auto-release/raw/master/update/latest.json"

    private const val PREF = "update"
    private const val KEY_URL = "url"
    private const val KEY_SKIP = "skipVersionCode"

    /** 清单（`latest.json`）—— 字段与 `tools/release.py` 一一对应 */
    data class Info(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val size: Long,
        val force: Boolean,
        val minVersionCode: Int,
        val notes: String,
    ) {
        fun isNewerThan(local: Int): Boolean = versionCode > local

        /** 本机版本低于 `minVersionCode` ⇒ 必须更新（清单里给的门槛，用来断掉有严重问题的老版本） */
        fun isBlocked(local: Int): Boolean = local < minVersionCode && minVersionCode > 0
    }

    sealed class Check {
        /** 发现新版 */
        data class New(val info: Info, val local: Int) : Check()

        /** 已是最新 */
        data class Latest(val local: Int, val remote: Int) : Check()

        /** 检查失败（网络/解析）——不打扰用户，只在手动检查时显示 */
        data class Failed(val message: String) : Check()
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ────────────────────────── 本地信息 / 设置 ──────────────────────────

    fun versionCode(ctx: Context): Int = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(0)

    fun versionName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    }.getOrDefault("?")

    /** debug 包不能自更新：签名与发布包不同，装上会报"应用未安装/签名冲突" */
    fun isDebuggable(ctx: Context): Boolean =
        (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun urlOf(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_URL, null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_URL

    fun saveUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_URL, url.trim()).apply()
    }

    /** "忽略此版本"：静默检查跳过它（手动检查仍然显示，免得用户真错过） */
    fun skipVersionCode(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_SKIP, 0)

    fun saveSkipVersionCode(ctx: Context, code: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_SKIP, code).apply()
    }

    // ────────────────────────── 检查 ──────────────────────────

    suspend fun check(ctx: Context): Check = withContext(Dispatchers.IO) {
        val local = versionCode(ctx)
        val base = urlOf(ctx)
        val url = base + (if (base.contains("?")) "&" else "?") + "t=" + System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "lmj-auto/${versionName(ctx)}")
                .header("Cache-Control", "no-cache")     // Gitee raw 有 CDN 缓存，发版后要能立刻看到
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext Check.Failed("HTTP ${r.code}（更新源不可达）")
                val body = r.body?.string().orEmpty()
                val info = parse(body) ?: return@withContext Check.Failed("latest.json 解析失败（格式不对）")
                if (info.isNewerThan(local)) Check.New(info, local)
                else Check.Latest(local, info.versionCode)
            }
        } catch (t: Throwable) {
            Check.Failed(t.message ?: t.toString())
        }
    }

    /** 解析清单（纯函数，单测直接喂字符串） */
    fun parse(text: String): Info? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val code = o.optInt("versionCode", 0)
        val apk = o.optString("apkUrl").trim()
        if (code <= 0 || apk.isEmpty()) return null
        return Info(
            versionCode = code,
            versionName = o.optString("versionName").ifBlank { code.toString() },
            apkUrl = apk,
            sha256 = o.optString("sha256").trim(),
            size = o.optLong("size", 0L),
            force = o.optBoolean("force", false),
            minVersionCode = o.optInt("minVersionCode", 1),
            notes = o.optString("notes").trim(),
        )
    }

    // ────────────────────────── 下载 ──────────────────────────

    /**
     * 下载 APK 到 `files/update/`，带进度（1% 一次）。
     * 已下过且 sha256 对得上 → 直接复用（省流量）。
     */
    suspend fun download(
        ctx: Context,
        info: Info,
        onProgress: (pct: Int, done: Long, total: Long) -> Unit = { _, _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.filesDir, "update").apply { mkdirs() }
        val out = File(dir, "labyrinth-${info.versionName}.apk")
        if (out.isFile && out.length() > 0 && info.sha256.isNotEmpty() &&
            sha256Of(out).equals(info.sha256, ignoreCase = true)
        ) {
            onProgress(100, out.length(), out.length())
            return@withContext out
        }

        val part = File(dir, out.name + ".part")
        part.delete()
        AppState.addLog("开始下载 ${info.versionName}（约 ${info.size / 1048576}MB）…")
        try {
            val req = Request.Builder().url(info.apkUrl).header("User-Agent", "lmj-auto").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("下载失败：HTTP ${r.code}")
                val total = r.body?.contentLength()?.takeIf { it > 0 } ?: info.size
                val input = r.body?.byteStream() ?: throw IllegalStateException("响应为空")
                var done = 0L
                var lastPct = -1
                FileOutputStream(part).use { fo ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        fo.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (pct != lastPct) { lastPct = pct; onProgress(pct, done, total) }
                    }
                    fo.flush()
                }
            }
            if (info.sha256.isNotEmpty()) {
                val got = sha256Of(part)
                if (!got.equals(info.sha256, ignoreCase = true)) {
                    part.delete()
                    throw IllegalStateException("sha256 校验失败（收到 ${got.take(12)}…，应为 ${info.sha256.take(12)}…）")
                }
            }
            out.delete()
            if (!part.renameTo(out)) {
                part.copyTo(out, overwrite = true)
                part.delete()
            }
            AppState.addLog("下载完成：${out.name}（${out.length() / 1048576}MB）")
            out
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    // ────────────────────────── 安装 ──────────────────────────

    /** 真正拉起系统安装器；返回 null = 已拉起，否则是给人看的原因 */
    fun install(ctx: Context, apk: File): String? {
        if (!apk.isFile || apk.length() <= 0) return "安装包不在了，请重新下载"
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            openInstallPermission(ctx)
            return "需要先允许「安装未知应用」：已帮你打开设置页，打开开关后回来再点一次「安装」"
        }
        verifySignature(ctx, apk)?.let { return it }     // 签名不符就别装了（防投毒）
        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
            AppState.addLog("已拉起系统安装器：${apk.name}（点「安装」即完成更新）")
            null
        } catch (t: Throwable) {
            "拉起安装器失败：${t.message}"
        }
    }

    fun openInstallPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { AppState.addLog("打不开「安装未知应用」设置页：${it.message}") }
    }

    /**
     * 校验下载到的 APK 是不是**我们自己签的包**：包名一致 + 签名摘要有交集。
     * 返回 null = 通过；否则是给人看的原因。
     */
    fun verifySignature(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val theirs = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, flags) }.getOrNull()
            ?: return "读不出安装包信息（文件损坏？）"
        if (theirs.packageName != ctx.packageName) return "包名不符（${theirs.packageName}）"
        val mine = runCatching { signaturesOf(pm.getPackageInfo(ctx.packageName, flags)) }.getOrDefault(emptySet())
        val other = runCatching { signaturesOf(theirs) }.getOrDefault(emptySet())
        if (mine.isEmpty() || other.isEmpty()) return "读不到签名，不敢装"
        return if (mine.intersect(other).isEmpty()) "签名与本机不一致（可能被改过），已拒绝安装" else null
    }

    private fun signaturesOf(pi: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo ?: return emptySet()
            val signers = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            return signers.map { sha256Of(it.toByteArray()) }.toSet()
        }
        @Suppress("DEPRECATION")
        return (pi.signatures ?: emptyArray()).map { sha256Of(it.toByteArray()) }.toSet()
    }

    fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun sha256Of(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format(Locale.US, "%02x", it) }
}
