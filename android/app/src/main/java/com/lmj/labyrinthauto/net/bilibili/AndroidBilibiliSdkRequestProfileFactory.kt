package com.lmj.labyrinthauto.net.bilibili

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import java.security.MessageDigest
import java.util.UUID

class AndroidBilibiliSdkRequestProfileFactory(
    private val context: Context,
) {
    fun create(): BilibiliSdkRequestProfile {
        val gamePackage = readGamePackage()
        val installId = getOrCreateInstallId()
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty()
            .ifBlank { installId }
        val certDigest = gamePackage.certificateMd5
        val identitySeed = "$installId:$androidId:$certDigest"
        val buvid = "XZ${md5Hex(identitySeed).uppercase()}"
        val udid = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(identitySeed.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        val display = context.resources.displayMetrics

        return BilibiliSdkRequestProfile(
            linkedMapOf(
                "operators" to "5",
                "merchant_id" to "1",
                "isRoot" to "0",
                "domain_switch_count" to "0",
                "sdk_type" to "1",
                "sdk_log_type" to "1",
                "support_abis" to Build.SUPPORTED_ABIS.joinToString(","),
                "access_key" to "",
                "sdk_ver" to COMPATIBLE_SDK_VERSION,
                "oaid" to "",
                "dp" to "${display.widthPixels}*${display.heightPixels}",
                "original_domain" to "",
                "imei" to "",
                "version" to "1",
                "udid" to udid,
                "apk_sign" to certDigest,
                "platform_type" to "3",
                "old_buvid" to buvid,
                "android_id" to androidId,
                "fingerprint" to "",
                "mac" to "",
                "server_id" to SERVER_ID,
                "domain" to SDK_HOST,
                "app_id" to GAME_ID,
                "version_code" to gamePackage.versionCode.toString(),
                "net" to "4",
                "pf_ver" to Build.VERSION.RELEASE,
                "cur_buvid" to buvid,
                "c" to "1",
                "brand" to Build.BRAND,
                "channel_id" to "1",
                "uid" to "",
                "game_id" to GAME_ID,
                "ver" to gamePackage.versionName,
                "model" to Build.MODEL,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun readGamePackage(): GamePackageInfo {
        val packageManager = context.packageManager
        // ★人话报错（2026-10-23）：这里原先直接抛 `NameNotFoundException`，在 MuMu 15(Android 15) 上
        //   一按「一键黎明界」就把整个 app 进程崩掉（`runFull` 只有 try/finally、没有 catch）。
        //   两种原因都会走到这里：① 真没装游戏；② Android 11+ 的包可见性没声明 `<queries>`
        //   （manifest 已补）。不管哪种，都要给一句能照着办的话，而不是 FATAL。
        val packageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(GAME_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                packageManager.getPackageInfo(GAME_PACKAGE, PackageManager.GET_SIGNATURES)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            throw IllegalStateException(
                "这台设备/这个用户空间里查不到国服游戏（$GAME_PACKAGE）。" +
                    "B 站登录协议要读游戏包的签名和版本号，所以必须先把游戏装在同一用户里再跑" +
                    "（多开/分身里装的、或工作资料里的，本 app 看不到）",
                e,
            )
        }
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            packageInfo.signatures?.firstOrNull()
        } ?: error("无法读取国服游戏签名")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        return GamePackageInfo(
            versionName = packageInfo.versionName.orEmpty().ifBlank { "unknown" },
            versionCode = versionCode,
            certificateMd5 = signature.toByteArray().let(::md5Hex),
        )
    }

    private fun getOrCreateInstallId(): String {
        val preferences = context.getSharedPreferences(INSTALL_ID_STORE, Context.MODE_PRIVATE)
        preferences.getString(INSTALL_ID_KEY, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        check(preferences.edit().putString(INSTALL_ID_KEY, generated).commit()) { "无法保存协议安装标识" }
        return generated
    }

    private fun md5Hex(value: String): String = md5Hex(value.toByteArray(Charsets.UTF_8))

    private fun md5Hex(value: ByteArray): String = MessageDigest.getInstance("MD5")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class GamePackageInfo(
        val versionName: String,
        val versionCode: Long,
        val certificateMd5: String,
    )

    private companion object {
        const val GAME_PACKAGE = "com.bilibili.priconne"
        const val SDK_HOST = "line1-sdk-center-login-sh.biligame.net"
        const val GAME_ID = "1370"
        const val SERVER_ID = "1592"
        const val COMPATIBLE_SDK_VERSION = "3.4.2"
        const val INSTALL_ID_STORE = "protocol-install-identity"
        const val INSTALL_ID_KEY = "install-id"
    }
}
