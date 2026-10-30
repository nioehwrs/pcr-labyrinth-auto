package com.lmj.labyrinthauto.net.bilibili

import android.content.Context
import android.os.Build

data class GameProtocolProfile(
    val appVersion: String,
    val headers: Map<String, String>,
    /** `tool/sdk_login` 的 `platform`：B 服 `"2"`，渠道服 `"4"`。 */
    val platform: String = "2",
    /**
     * `tool/sdk_login` 的 `channel_id`：B 服 `"1"`；
     * 渠道服按渠道区分（MuMu=65），只能从客户端抓取时带回来，不能猜。
     */
    val channelId: String = "1",
)

/**
 * 协议层的游戏包不再固定为 B 服：由调用方传入已确认的渠道包名
 * （账号所属服务器的客户端，见 GameServer），使渠道服客户端也能提供 APP-VER。
 */
class AndroidGameProtocolProfileFactory(
    private val context: Context,
    private val gamePackageName: String,
    /** 资源密钥：B 服与渠道服不同。 */
    private val resKey: String = RES_KEY,
    /** PLATFORM-ID：B 服 2，渠道服 4。 */
    private val platformId: String = "2",
    /** CHANNEL-ID：B 服 1，渠道服见 [GameProtocolProfile.channelId]。 */
    private val channelId: String = "1",
) {
    fun create(): GameProtocolProfile {
        // ★人话报错（2026-10-23）：`targetSdk=35` 且 manifest 没声明 `<queries>` 时，Android 11+
        //   会把"没交互过的包"藏起来 ⇒ 这里直接抛 `NameNotFoundException`，看起来像"没装游戏"。
        //   manifest 已补 `<queries><package …/></queries>`；这里再兜一层，出错时给能照着办的话。
        val packageInfo = try {
            context.packageManager.getPackageInfo(gamePackageName, 0)
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            throw IllegalStateException(
                "查不到游戏包 $gamePackageName（APP-VER 要靠它的版本号）。" +
                    "如果游戏确实装了，多半是 Android 11+ 的包可见性没声明 —— 见 AndroidManifest 的 <queries>",
                e,
            )
        }
        val appVersion = packageInfo.versionName.orEmpty()
            .ifBlank { error("无法读取游戏版本：$gamePackageName") }
        val model = listOf(Build.BRAND, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
        return GameProtocolProfile(
            appVersion = appVersion,
            headers = linkedMapOf(
                "Accept-Encoding" to "gzip",
                "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android ${Build.VERSION.RELEASE})",
                "X-Unity-Version" to UNITY_VERSION,
                "APP-VER" to appVersion,
                "BATTLE-LOGIC-VERSION" to "4",
                "BUNDLE-VER" to "",
                "DEVICE" to "2",
                "DEVICE-NAME" to model.ifBlank { "Android" },
                "EXCEL-VER" to "1.0.0",
                "GRAPHICS-DEVICE-NAME" to "Android",
                "IP-ADDRESS" to "",
                "KEYCHAIN" to "",
                "LOCALE" to "CN",
                "PLATFORM-OS-VERSION" to "Android ${Build.VERSION.RELEASE} / API-${Build.VERSION.SDK_INT}",
                "REGION-CODE" to "",
                "RES-KEY" to resKey,
                "RES-VER" to DEFAULT_RES_VERSION,
                "SHORT-UDID" to "0",
                "PLATFORM" to platformId,
                "PLATFORM-ID" to platformId,
                "CHANNEL-ID" to channelId,
            ),
            platform = platformId,
            channelId = channelId,
        )
    }

    private companion object {
        const val UNITY_VERSION = "2021.3.20f1c1"
        const val RES_KEY = "ab00a0a6dd915a052a2ef7fd649083e5"
        const val DEFAULT_RES_VERSION = "10002200"
    }
}
