package com.lmj.labyrinthauto.net.bilibili

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SDK 响应解析与 autopcr `json.loads` 对齐（2026-11-13 线上反馈）：
 * 服务端 `captcha_type` 返回数字 1，旧的 `@Serializable String` 会直接抛异常。
 * 这里用实测响应样本钉住「字段类型可以随便变」这个口径。
 */
class SdkResponseCodecTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 2026-11-13 实测 `api/client/start_captcha` 的响应（原样，含未知字段与数字 captcha_type）。 */
    private val realCaptcha = """
        {"requestId":"38e6f996e2844dc28add2cbe543cd1ec",
         "timestamp":"1794539982657",
         "code":0,
         "challenge":"2251c0f9263364e4ee594d51a60a19c7",
         "gt_user_id":"92126f1a089b4e008193d1dccb7b6c8a",
         "gs":1,
         "gt":"245273d7fa73f7b657098bf7441fe12f",
         "captcha_type":1,
         "server_message":""}
    """.trimIndent()

    @Test
    fun `实测验证码响应能取到三个字段`() {
        val c = SdkResponseCodec.captcha(json, realCaptcha)
        assertEquals(0, c.code)
        assertEquals("245273d7fa73f7b657098bf7441fe12f", c.gt)
        assertEquals("2251c0f9263364e4ee594d51a60a19c7", c.challenge)
        assertEquals("92126f1a089b4e008193d1dccb7b6c8a", c.gtUserId)
        assertEquals("1", c.captchaType)
    }

    @Test
    fun `同一字段数字或字符串都能读`() {
        val asString = SdkResponseCodec.captcha(
            json,
            """{"code":"0","gt":"g","challenge":"c","gt_user_id":"u","captcha_type":"1"}""",
        )
        assertEquals(0, asString.code)
        assertEquals("g", asString.gt)
        assertEquals("1", asString.captchaType)

        val asNumber = SdkResponseCodec.captcha(
            json,
            """{"code":0,"gt":"g","challenge":"c","gt_user_id":"u","captcha_type":2}""",
        )
        assertEquals("2", asNumber.captchaType)
    }

    @Test
    fun `缺失 null 对象数组与未知字段都按 autopcr 一样忽略`() {
        val c = SdkResponseCodec.captcha(
            json,
            """{"code":0,"gt":"g","challenge":"c","gt_user_id":"u",
                "captcha_type":null,"message":null,"extra":{"a":1},"list":[1,2]}""",
        )
        assertEquals("1", c.captchaType)
        assertEquals("", c.message)
        assertEquals("g", c.gt)

        val missing = SdkResponseCodec.captcha(json, """{"code":0}""")
        assertEquals(0, missing.code)
        assertEquals("", missing.gt)
        assertEquals("", missing.challenge)
        assertEquals("", missing.gtUserId)
    }

    @Test
    fun `login 响应混合类型也能判出需要验证码`() {
        val risk = SdkResponseCodec.login(
            json,
            """{"code":200000,"uid":0,"need_captch":true,"message":"需要验证"}""",
        )
        assertEquals(200000, risk.code)
        assertTrue(risk.needCaptcha)

        val ok = SdkResponseCodec.login(
            json,
            """{"code":"0","uid":123456,"access_key":"ak","need_captch":"0"}""",
        )
        assertEquals(0, ok.code)
        assertEquals("123456", ok.uid)
        assertEquals("ak", ok.accessKey)
        assertFalse(ok.needCaptcha)
    }

    @Test
    fun `rsa 响应字符串 code 也能读`() {
        val r = SdkResponseCodec.rsa(
            json,
            """{"code":"0","hash":"h","rsa_key":"k","unknown":1}""",
        )
        assertEquals(0, r.code)
        assertEquals("h", r.hash)
        assertEquals("k", r.rsaKey)
    }

    @Test
    fun `顶层不是对象或不是 JSON 时给出可读原因`() {
        try {
            SdkResponseCodec.captcha(json, "[1,2,3]")
            fail("数组应当被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("顶层不是对象"))
        }
        try {
            SdkResponseCodec.captcha(json, "{\"access_key\":\"SECRET\"")
            fail("残缺 JSON 应当被拒绝")
        } catch (e: IllegalArgumentException) {
            val msg = e.message.orEmpty()
            assertTrue("实际异常=$msg", msg.contains("JSON 解析失败"))
            assertFalse("实际异常=$msg", msg.contains("SECRET"))
        }
    }
}
