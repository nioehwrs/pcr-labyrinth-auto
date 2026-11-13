package com.lmj.labyrinthauto.net.bilibili

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * ★B 站 SDK 响应解析（2026-11-13）：等价 autopcr `bsgamesdk.sendpost()` 里的 `json.loads(res)`。
 *
 * autopcr 拿到响应后直接按 key 取值（Python 动态类型，字段是 int / str / None 都无所谓，
 * 也不声明「响应长什么样」）。早先这里用 `@Serializable data class` 做静态映射，
 * 服务端把 `captcha_type` 从字符串改成数字 `1` 后，kotlinx.serialization 直接抛异常
 * ⇒ 验证码永远报「响应无法解析」（线上反馈 2026-11-13）。
 *
 * 现在统一解析成 [JsonObject] 再按名取标量，把 Kotlin/Python 的类型差异抹平：
 *  · 数字 / 字符串 / 布尔 → 都取字面值（数字 `1` 与字符串 `"1"` 等价，对应 Python 的 `str(v)`）
 *  · 字段缺失 / null / 对象 / 数组 → 视为没有，走默认值，不抛异常
 *  · 未知字段天然忽略（等价 Python dict 里不取的 key，无需 `ignoreUnknownKeys`）
 */
object SdkResponseCodec {

    data class Rsa(val code: Int, val hash: String, val rsaKey: String)

    data class Login(
        val code: Int,
        val uid: String,
        val accessKey: String,
        val needCaptcha: Boolean,
        val message: String,
    )

    data class Captcha(
        val code: Int,
        val gt: String,
        val challenge: String,
        val gtUserId: String,
        val captchaType: String,
        val message: String,
    )

    fun rsa(json: Json, payload: String): Rsa = json.objectOf(payload).let { o ->
        Rsa(
            code = o.intOf("code"),
            hash = o.textOf("hash"),
            rsaKey = o.textOf("rsa_key"),
        )
    }

    fun login(json: Json, payload: String): Login = json.objectOf(payload).let { o ->
        Login(
            code = o.intOf("code"),
            uid = o.textOf("uid"),
            accessKey = o.textOf("access_key"),
            needCaptcha = o.textOf("need_captch").lowercase() in setOf("true", "1"),
            message = o.textOf("message").ifBlank { o.textOf("server_message") },
        )
    }

    fun captcha(json: Json, payload: String): Captcha = json.objectOf(payload).let { o ->
        Captcha(
            code = o.intOf("code"),
            gt = o.textOf("gt"),
            challenge = o.textOf("challenge"),
            gtUserId = o.textOf("gt_user_id"),
            captchaType = o.textOf("captcha_type", "1"),
            message = o.textOf("message").ifBlank { o.textOf("server_message") },
        )
    }

    /** 非 JSON / 顶层不是对象：抛带固定文案的异常，绝不把响应体塞进 message（login 响应里有 access_key）。 */
    private fun Json.objectOf(payload: String): JsonObject {
        val element = try {
            parseToJsonElement(payload)
        } catch (failure: Throwable) {
            throw IllegalArgumentException("JSON 解析失败（${failure::class.simpleName}）", failure)
        }
        return element as? JsonObject ?: throw IllegalArgumentException("JSON 顶层不是对象")
    }

    private fun JsonObject.textOf(name: String, fallback: String = ""): String = scalarOf(name) ?: fallback

    private fun JsonObject.intOf(name: String, fallback: Int = -1): Int =
        scalarOf(name)?.trim()?.toIntOrNull() ?: fallback

    /** 只认标量；对象 / 数组 / null 都视作缺失。 */
    private fun JsonObject.scalarOf(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull
}
