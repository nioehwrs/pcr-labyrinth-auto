package com.lmj.labyrinthauto.net.bilibili

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录编排层的「自动打码 ↔ 手动极验」分流（2026-11-13）。
 * 真机端到端没法验证，这里用假网关把能确定的逻辑钉死：
 * 自动成功就不弹窗、失败/异常/未通过都要回退手动、自动结果必须整组替换 challenge。
 */
class BilibiliLoginCoordinatorTest {

    private val material = AccountLoginMaterial(
        accountId = 1L,
        credentialKey = "key",
        loginId = "user",
        password = "pwd",
        server = GameServer.CN_BILIBILI,
    )

    /** 远端打码服务返回的整组结果（与本项目 start_captcha 的那组不同）。 */
    private val remoteSolution = CaptchaSolution(
        challenge = "remote-challenge",
        gtUserId = "remote-gtid",
        validate = "remote-validate",
    )

    /** 手动 WebView 用的本地 start_captcha 结果。 */
    private val localChallenge = CaptchaChallenge(
        gt = "gt",
        challenge = "local-challenge",
        gtUserId = "local-gtid",
        captchaType = "1",
    )

    private class FakeGateway(
        results: List<SdkLoginResult>,
        private val captchaResult: SdkCaptchaResult,
    ) : BilibiliSdkGateway {
        private val queue = ArrayDeque(results)
        val loginCalls = mutableListOf<CaptchaSolution?>()
        var startCaptchaCalls = 0

        override suspend fun login(
            credentials: SdkLoginCredentials,
            captcha: CaptchaSolution?,
        ): SdkLoginResult {
            loginCalls.add(captcha)
            return queue.removeFirstOrNull() ?: SdkLoginResult.Rejected(-1, "没有更多预设响应")
        }

        override suspend fun startCaptcha(): SdkCaptchaResult {
            startCaptchaCalls++
            return captchaResult
        }
    }

    private class FakeStore : SdkSessionStore {
        var saved: SdkSession? = null
        override suspend fun save(key: String, session: SdkSession) { saved = session }
        override suspend fun read(key: String): SdkSession? = saved
        override suspend fun delete(key: String) { saved = null }
    }

    @Test
    fun `自动打码成功直接登录 且不弹验证码`() = runBlocking {
        val gateway = FakeGateway(
            listOf(SdkLoginResult.CaptchaRequired, SdkLoginResult.Success(SdkSession("42", "ak"))),
            SdkCaptchaResult.Ready(localChallenge),
        )
        val store = FakeStore()
        val coordinator = BilibiliLoginCoordinator(gateway, store, autoCaptcha = { remoteSolution })

        val result = coordinator.start(material)

        assertTrue(result is AccountLoginResult.Success)
        assertEquals("42", (result as AccountLoginResult.Success).uid)
        assertEquals(0, gateway.startCaptchaCalls)
        assertEquals(2, gateway.loginCalls.size)
        assertNull(gateway.loginCalls[0])
        assertEquals(remoteSolution, gateway.loginCalls[1])
        assertEquals("ak", store.saved?.accessKey)
        assertNull(coordinator.pendingChallenge(1L))
    }

    @Test
    fun `自动打码返回空时回退手动极验`() = runBlocking {
        val gateway = FakeGateway(
            listOf(SdkLoginResult.CaptchaRequired),
            SdkCaptchaResult.Ready(localChallenge),
        )
        val coordinator = BilibiliLoginCoordinator(gateway, FakeStore(), autoCaptcha = { null })

        val result = coordinator.start(material)

        assertTrue(result is AccountLoginResult.CaptchaRequired)
        assertEquals(localChallenge, (result as AccountLoginResult.CaptchaRequired).challenge)
        assertEquals(1, gateway.startCaptchaCalls)
        assertEquals(localChallenge, coordinator.pendingChallenge(1L))
    }

    @Test
    fun `自动打码抛异常也回退手动极验`() = runBlocking {
        val gateway = FakeGateway(
            listOf(SdkLoginResult.CaptchaRequired),
            SdkCaptchaResult.Ready(localChallenge),
        )
        val coordinator = BilibiliLoginCoordinator(
            gateway,
            FakeStore(),
            autoCaptcha = { error("offline") },
        )

        val result = coordinator.start(material)

        assertTrue(result is AccountLoginResult.CaptchaRequired)
        assertEquals(1, gateway.startCaptchaCalls)
    }

    @Test
    fun `自动结果仍被判为需要验证码时回退手动`() = runBlocking {
        val gateway = FakeGateway(
            listOf(SdkLoginResult.CaptchaRequired, SdkLoginResult.CaptchaRequired),
            SdkCaptchaResult.Ready(localChallenge),
        )
        val coordinator = BilibiliLoginCoordinator(gateway, FakeStore(), autoCaptcha = { remoteSolution })

        val result = coordinator.start(material)

        assertTrue(result is AccountLoginResult.CaptchaRequired)
        assertEquals(remoteSolution, gateway.loginCalls[1])
        assertEquals(1, gateway.startCaptchaCalls)
    }

    @Test
    fun `手动完成后用本地 challenge 重登`() = runBlocking {
        val gateway = FakeGateway(
            listOf(SdkLoginResult.CaptchaRequired, SdkLoginResult.Success(SdkSession("7", "ak2"))),
            SdkCaptchaResult.Ready(localChallenge),
        )
        val coordinator = BilibiliLoginCoordinator(gateway, FakeStore())

        val first = coordinator.start(material)
        assertTrue(first is AccountLoginResult.CaptchaRequired)
        val done = coordinator.completeCaptcha(1L, CaptchaProof("manual-validate"))

        assertTrue(done is AccountLoginResult.Success)
        assertEquals("7", (done as AccountLoginResult.Success).uid)
        assertEquals(
            CaptchaSolution("local-challenge", "local-gtid", "manual-validate"),
            gateway.loginCalls[1],
        )
        assertNull(coordinator.pendingChallenge(1L))
    }

    @Test
    fun `start_captcha 失败时把原因透传而不是崩`() = runBlocking {
        val gateway = FakeGateway(
            listOf(SdkLoginResult.CaptchaRequired),
            SdkCaptchaResult.ProtocolFailure("验证码响应无法解析：JSON 顶层不是对象"),
        )
        val coordinator = BilibiliLoginCoordinator(gateway, FakeStore(), autoCaptcha = { null })

        val result = coordinator.start(material)

        assertTrue(result is AccountLoginResult.Failure)
        val failure = result as AccountLoginResult.Failure
        assertEquals(LoginFailureKind.Protocol, failure.kind)
        assertNotNull(failure.message)
        assertTrue(failure.message.contains("JSON 顶层不是对象"))
    }
}
