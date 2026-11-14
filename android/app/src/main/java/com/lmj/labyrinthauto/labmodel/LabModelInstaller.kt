package com.lmj.labyrinthauto.labmodel

import android.content.Context
import com.lmj.labyrinthauto.core.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 编队模型组件（3 个 onnx + metadata）的下载 / 解压 / 校验（2026-11-14）。
 *
 * 与 [com.lmj.labyrinthauto.frida.FridaServerInstaller] 同一套路：
 *  * Gitee **固定 tag** 的 Release 附件（地址不随 App 版本变）；
 *  * 支持 Range 断点续传（`.part` 保留）；
 *  * 校验整包 sha256 + 字节数，不符就删掉重下；
 *  * 失败**不阻断**任何功能 —— [LabModel] 看到文件不全就回落规则版。
 *
 * 落地目录统一为 `filesDir/labmodel/`（不依赖外部存储权限）；`getExternalFilesDir` 那份只作兼容读取。
 */
object LabModelInstaller {

    private const val TAG = "[模型]"

    /** 组件版本 = 导出日期。换模型就换 tag + 三个常量，App 不用升级。 */
    private const val VERSION = "20261114"

    private const val BASE_URL =
        "https://gitee.com/nioehwrs/labyrinth-auto-release/releases/download/labmodel-$VERSION"

    private const val ZIP_NAME = "labmodel-$VERSION.zip"

    /** 由 `tools` 打包时算出的整包大小与 sha256（2026-11-14 首发版） */
    private const val ZIP_BYTES = 13_639_317L
    private const val ZIP_SHA = "a956b4b894f1f2b470f7b5a7d5719b5175633116a2d941c7550064abc116f437"

    /** 解压后必须齐全的四个文件 */
    private val FILES = listOf("context.onnx", "decoder.onnx", "critic.onnx", "metadata.json")

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private val lock = Mutex()

    @Volatile
    private var lastFailAt = 0L

    /** 模型目录（统一放私有目录；外部目录那份只作兼容） */
    fun dirOf(ctx: Context): File = File(ctx.filesDir, "labmodel")

    /** 四个文件都在就算就绪（内容完整性由下载时的整包 sha256 保证）。 */
    fun ready(ctx: Context): Boolean {
        val dir = dirOf(ctx)
        return FILES.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } } ||
            compatReady(ctx)
    }

    private fun compatReady(ctx: Context): Boolean {
        val dir = File(ctx.getExternalFilesDir(null), "labmodel")
        return FILES.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }
    }

    /**
     * 保证模型就绪（幂等、并发安全）。**失败不抛**，只记一次日志并在 10 分钟内不再重试。
     * @return true = 文件齐全（含复用既有副本）
     */
    suspend fun ensure(ctx: Context): Boolean {
        if (ready(ctx)) {
            AppState.setModel("已就绪")
            return true
        }
        return lock.withLock {
            if (ready(ctx)) {
                AppState.setModel("已就绪")
                return true
            }
            val now = System.currentTimeMillis()
            // ★失败后只压 60 秒（2026-11-14）：原来 10 分钟内直接返回 false，用户断网重连后
            //   要等 10 分钟或重启 App 才会重试，太钝。组件准备本身调用频率不高，1 分钟足够防抖。
            if (lastFailAt > 0 && now - lastFailAt < 60_000L) return false
            val ok = runCatching { downloadAndUnzip(ctx) }.getOrElse { t ->
                AppState.addLog("$TAG 组件下载/解压失败：${t.javaClass.simpleName}: ${t.message}（稍后重试）")
                false
            }
            if (!ok) lastFailAt = System.currentTimeMillis()
            AppState.setModel(if (ok) "已就绪" else "未就绪（联网后自动重试）")
            ok
        }
    }

    private suspend fun downloadAndUnzip(ctx: Context): Boolean {
        val dir = dirOf(ctx).apply { mkdirs() }
        val part = File(dir, "$ZIP_NAME.part")
        val url = "$BASE_URL/$ZIP_NAME"
        val had = if (part.isFile) part.length() else 0L
        AppState.addLog(
            "$TAG 开始下载模型组件（${ZIP_BYTES / 1048576}MB）" +
                if (had > 0) "，从 ${had / 1048576}MB 续传" else "",
        )
        download(url, part) { pct, done, total ->
            AppState.setModel("下载中 $pct%（${done / 1048576}/${total / 1048576}MB）")
            if (pct % 10 < 3) {
                AppState.addLog("$TAG 下载模型组件：$pct%（${done / 1048576}/${total / 1048576}MB）")
            }
        }
        if (part.length() != ZIP_BYTES) {
            AppState.addLog("$TAG 模型组件大小不符（${part.length()}/$ZIP_BYTES）→ 删掉重下")
            part.delete()
            return false
        }
        val sha = sha256(part)
        if (!sha.equals(ZIP_SHA, ignoreCase = true)) {
            AppState.addLog("$TAG 模型组件 sha256 不符（${sha.take(12)}…）→ 删掉重下")
            part.delete()
            return false
        }
        unzip(part, dir)
        part.delete()
        if (!ready(ctx)) {
            AppState.addLog("$TAG 解压后文件不齐 → 视为失败")
            return false
        }
        AppState.addLog("$TAG 模型组件已就绪（${dir.absolutePath}）")
        return true
    }

    /** 只解白名单里的四个文件（防 zip slip / 多余文件覆盖别的东西）。 */
    private fun unzip(zip: File, dir: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (name in FILES) {
                    val out = File(dir, name)
                    FileOutputStream(out).use { fo -> zin.copyTo(fo, 1 shl 20) }
                }
                zin.closeEntry()
            }
        }
    }

    private suspend fun download(url: String, part: File, onProgress: (Int, Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            part.parentFile?.mkdirs()
            var have = if (part.isFile) part.length() else 0L
            val builder = Request.Builder().url(url).header("User-Agent", "lmj-auto")
            if (have > 0) builder.header("Range", "bytes=$have-")
            http.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val resume = resp.code == 206
                if (!resume) have = 0L
                val body = resp.body ?: throw IllegalStateException("响应为空")
                val total = body.contentLength().let { if (it > 0) have + it else 0L }
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
                                if (pct >= lastPct + 3) {
                                    lastPct = pct
                                    onProgress(pct, done, total)
                                }
                            }
                        }
                    }
                    fo.flush()
                }
            }
        }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
    }
}
