package com.lmj.labyrinthauto.net.bilibili

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

object BilibiliGameGatewayFactory {
    /**
     * 渠道差异：B 服与渠道服（UO）是两套独立网关，资源密钥与 PLATFORM-ID 均不同。
     * 取值对照 cc004/autopcr 的 sdk/sdkclients.py（bsdkclient / qsdkclient）。
     */
    data class ChannelEndpoint(
        val bootstrap: String,
        val resKey: String,
        val platformId: String,
    ) {
        companion object {
            val BILIBILI = ChannelEndpoint(
                bootstrap = "https://l3-prod-all-gs-gzlj.bilibiligame.net/",
                resKey = "ab00a0a6dd915a052a2ef7fd649083e5",
                platformId = "2",
            )

            /** 渠道服（小米 / 华为 / vivo 等联运）。 */
            val CHANNEL_UO = ChannelEndpoint(
                bootstrap = "https://l1-prod-uo-gs-gzlj.bilibiligame.net/",
                resKey = "d145b29050641dac2f8b19df0afe0e59",
                platformId = "4",
            )
        }
    }

    /** 账号所属服务器 → 网关（B 服 l3 / 渠道服 l1）。 */
    fun endpointFor(server: GameServer): ChannelEndpoint =
        if (server.isChannelServer) ChannelEndpoint.CHANNEL_UO else ChannelEndpoint.BILIBILI

    /**
     * @param channelId `tool/sdk_login` 的 `channel_id`（B 服 "1"；渠道服从抓取带回，如 MuMu=65）
     */
    fun create(
        context: Context,
        gamePackageName: String,
        endpoint: ChannelEndpoint,
        channelId: String = "1",
    ): BilibiliGameGateway {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        return BilibiliGameProtocolGateway(
            client = client,
            bootstrapEndpoint = endpoint.bootstrap.toHttpUrl(),
            profile = AndroidGameProtocolProfileFactory(
                context.applicationContext,
                gamePackageName,
                endpoint.resKey,
                endpoint.platformId,
                channelId,
            ).create(),
            json = Json { ignoreUnknownKeys = true },
        )
    }
}
