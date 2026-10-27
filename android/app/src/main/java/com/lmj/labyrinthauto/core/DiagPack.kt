package com.lmj.labyrinthauto.core

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断包（2026-10-27 用户方案 P1：**按选择打包 → 保存到本地**）。
 *
 * 为什么要它：日志页已经能按「本次运行 / 某一局」选中，但让用户自己找文件、改名、发出来，
 * 十次里有八次发错（发成上一次的、发成 session、或者根本没找到下载目录）。
 * 这里把**用户选中的那几份**打成一个 zip：
 *
 * ```
 * lab-diag_20261027_2147.zip
 *   ├ README.txt        版本 / 选择内容 / 文件清单
 *   └ logs/…            选中的 session 与单局日志（原样，不改名）
 * ```
 *
 * 三条口径：
 *  * **只打用户选的**，绝不扫全量历史（用户明确："打包只按用户选择生成"）；
 *  * 日志里已经带环境信息（机型/系统/屏幕/版本），README 不再重复问；
 *  * 包名用 ASCII（中文文件名在 adb / 微信里容易乱码），给人看的中文只出现在 README 与 UI。
 *
 * 2026-10-27 第二次修订（用户）：**生成 ≠ 落盘**。打包只写进 cache，界面上给
 * 「取消 / 保存到本地」两个按钮 —— 保存的动作就是以前那个「复制到下载」（模拟器里没有
 * 微信/QQ，分享面板是废的），所以这里不再提供"发送/分享"。
 */
object DiagPack {

    private val nameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    data class Result(
        /** zip 文件名（`lab-diag_20261027_233403.zip`） */
        val name: String,
        /** 生成在 cache 里的 zip（"保存到本地"时才复制进下载目录） */
        val file: File,
        val bytes: Long,
        val fileCount: Int,
    )

    /**
     * @param title 用户选的是哪一块（"本次运行 · 10-27 21:47" / "第 3 局 · 22:16"），写进 README 与文件名
     * @param logs  要打进去的日志文件（session + 单局；重复路径自动去重）
     */
    fun build(ctx: Context, title: String, logs: List<File>): Result {
        val files = logs.filter { it.isFile }.distinctBy { it.absolutePath }
        require(files.isNotEmpty()) { "没有可打包的日志" }

        val base = "lab-diag_${nameFmt.format(Date())}"
        val dir = File(ctx.cacheDir, "diag").apply { mkdirs() }
        val zip = File(dir, "$base.zip")
        var count = 0
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
            put("README.txt", readme(ctx, title, files))
            for (f in files) {
                out.putNextEntry(ZipEntry("logs/${f.name}"))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
                count++
            }
        }
        return Result("$base.zip", zip, zip.length(), count)
    }

    /**
     * 「保存到本地」= 以前的「复制到下载」：写进**公共 Download**，返回给人看的落点
     * （`下载/lab-diag_xxx.zip`）。API29+ 走 MediaStore（不需要权限），老系统直接落盘。
     */
    fun save(ctx: Context, r: Result): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, r.name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("系统拒绝了写入下载目录")
            resolver.openOutputStream(uri)?.use { out -> r.file.inputStream().use { it.copyTo(out) } }
                ?: error("打不开下载目录的输出流")
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "下载/${r.name}"
        }

        @Suppress("DEPRECATION")
        val pub = android.os.Environment
            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        pub.mkdirs()
        val target = File(pub, r.name)
        runCatching { r.file.copyTo(target, overwrite = true) }
            .onFailure { error("写入下载目录失败：${it.message}") }
        return "下载/${r.name}"
    }

    /** 用户点了「取消」：把 cache 里那份删掉，别在白占空间 */
    fun discard(r: Result) {
        runCatching { r.file.delete() }
    }

    /** 包里那份 README（只说事实：什么版本、选的哪一块、装了哪些文件） */
    private fun readme(ctx: Context, title: String, files: List<File>): String {
        val ver = runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName ?: "?"} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()})"
        }.getOrDefault("?")
        val sb = StringBuilder()
        sb.appendLine("黎明界助手 · 诊断包")
        sb.appendLine("====================")
        sb.appendLine("生成时间：${dayFmt.format(Date())}")
        sb.appendLine("应用版本：$ver")
        sb.appendLine("选择内容：$title")
        sb.appendLine()
        sb.appendLine("包含文件（原样，未改名）：")
        for (f in files) sb.appendLine("  logs/${f.name}    ${(f.length() + 1023) / 1024} KB")
        sb.appendLine()
        sb.appendLine("日志开头已经带了环境信息（机型 / 系统 / 屏幕 / 版本 / 是否模拟器）。")
        return sb.toString()
    }
}
