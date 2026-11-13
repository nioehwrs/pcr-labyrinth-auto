package com.lmj.labyrinthauto.net.bilibili

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * ★自动打码（2026-11-13）：1:1 复刻 autopcr `sdk/validator.py:remoteValidator()`。
 *
 * `GET {service}/geetest_renew` 拿 uuid → 轮询 `GET {service}/check/{uuid}`：
 *  · 有 `queue_num`：排队中；>=35 放弃，否则等 `min(n,3)*10` 秒（autopcr 原样）
 *  · `info` 为字符串：`fail` / `url invalid` 放弃，`in running` 等 8 秒
 *  · `info` 为对象且带 `validate`：成功，返回整组 `{challenge, gt_user_id, validate}`
 *  · 最多 6 轮；任何失败都返回 null，调用方回退到手动极验弹窗
 *
 * ★关键：`challenge` / `gt_user_id` 必须用打码服务返回的这一组——它自己走了一遍
 *   `start_captcha` 并完成验证；不能沿用本项目 [BilibiliSdkHttpGateway.startCaptcha] 的那一组。
 *   autopcr `localValidator` 的注释（`滑动流程会换用新的 challenge`）与 `login2()` 都是这个口径。
 *
 * 请求只带 User-Agent，不含任何账号信息；服务地址来自 `assets/config.json` 的 `captcha_service`。
 */
class RemoteCaptchaSolver(
    private val baseUrl: String = DEFAULT_SERVICE,
    private val fetch: (suspend (String) -> String)? = null,
    private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val onStatus: (String) -> Unit = {},
) {
    suspend fun solve(): CaptchaSolution? = withContext(Dispatchers.IO) {
        val renewed = runCatching { get("$baseUrl/geetest_renew") }
            .getOrElse {
                onStatus("自动打码服务不可用，改为手动验证")
                return@withContext null
            }
        val uuid = parseUuid(renewed)
        if (uuid.isNullOrBlank()) {
            onStatus("自动打码服务返回异常，改为手动验证")
            return@withContext null
        }
        repeat(MAX_ROUNDS) {
            val payload = runCatching { get("$baseUrl/check/$uuid") }
                .getOrElse {
                    onStatus("自动打码查询失败，改为手动验证")
                    return@withContext null
                }
            when (val state = parseCheck(payload)) {
                is Check.Queued -> {
                    if (state.num >= QUEUE_GIVE_UP) {
                        onStatus("自动打码排队过长（${state.num}），改为手动验证")
                        return@withContext null
                    }
                    val seconds = minOf(state.num, 3) * 10
                    onStatus("自动验证排队中（${state.num}），约 ${seconds}s 后再查")
                    if (seconds > 0) sleep(seconds * 1000L)
                }
                is Check.Failed -> {
                    onStatus("自动打码失败（${state.reason}），改为手动验证")
                    return@withContext null
                }
                Check.Running -> {
                    onStatus("自动验证进行中…")
                    sleep(RUNNING_WAIT_MS)
                }
                is Check.Solved -> {
                    onStatus("自动验证完成")
                    return@withContext state.solution
                }
                Check.Unknown -> {
                    onStatus("自动验证返回未知结果，改为手动验证")
                    return@withContext null
                }
            }
        }
        onStatus("自动验证超时，改为手动验证")
        null
    }

    private suspend fun get(url: String): String = fetch?.invoke(url) ?: httpGet(url)

    /** `check` 响应的几种状态（抽成纯函数，便于用实测样本做单测）。 */
    sealed interface Check {
        data class Queued(val num: Int) : Check
        data class Failed(val reason: String) : Check
        data object Running : Check
        data class Solved(val solution: CaptchaSolution) : Check
        data object Unknown : Check
    }

    companion object {
        /** 与 `assets/config.json` 的 `captcha_service` 一致；改配置可覆盖。 */
        const val DEFAULT_SERVICE = "https://pcrd.tencentbot.top"
        private const val MAX_ROUNDS = 6
        private const val QUEUE_GIVE_UP = 35
        private const val RUNNING_WAIT_MS = 8_000L

        private val json = Json { ignoreUnknownKeys = true }
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        private fun httpGet(url: String): String {
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("User-Agent", "autopcr/1.0.0")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                return response.body?.string() ?: throw IOException("Empty response")
            }
        }

        fun parseUuid(payload: String): String? =
            obj(payload)?.text("uuid")?.takeIf { it.isNotBlank() }

        fun parseCheck(payload: String): Check {
            val o = obj(payload) ?: return Check.Unknown
            val queue = o.scalar("queue_num")?.trim()?.toIntOrNull()
            if (queue != null) return Check.Queued(queue)
            return when (val info = o["info"]) {
                is JsonPrimitive -> when (val s = info.contentOrNull) {
                    "fail", "url invalid" -> Check.Failed(s)
                    "in running" -> Check.Running
                    else -> Check.Unknown
                }
                is JsonObject -> {
                    val validate = info.text("validate")
                    val challenge = info.text("challenge")
                    val gtUserId = info.text("gt_user_id")
                    if (validate.isBlank() || challenge.isBlank() || gtUserId.isBlank()) Check.Unknown
                    else Check.Solved(CaptchaSolution(challenge, gtUserId, validate))
                }
                else -> Check.Unknown
            }
        }

        private fun obj(payload: String): JsonObject? = runCatching {
            json.parseToJsonElement(payload) as? JsonObject
        }.getOrNull()

        private fun JsonObject.text(name: String): String = scalar(name).orEmpty()

        /** 与 [SdkResponseCodec] 同款：数字 / 字符串 / 布尔都取字面值，null / 对象 / 数组视作缺失。 */
        private fun JsonObject.scalar(name: String): String? =
            (this[name] as? JsonPrimitive)?.contentOrNull
    }
}
