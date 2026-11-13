package com.lmj.labyrinthauto.net.bilibili

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BilibiliLoginCoordinator(
    private val gateway: BilibiliSdkGateway,
    private val sessionStore: SdkSessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** ★autopcr 口径：自动打码器；返回 null / 抛异常时回退手动极验弹窗。 */
    private val autoCaptcha: (suspend () -> CaptchaSolution?)? = null,
) {
    private val mutex = Mutex()
    private val pending = mutableMapOf<Long, PendingLogin>()

    suspend fun start(material: AccountLoginMaterial): AccountLoginResult = mutex.withLock {
        pending.remove(material.accountId)
        when (val result = gateway.login(material.toCredentials())) {
            is SdkLoginResult.Success -> persistSuccess(material, result.session)
            SdkLoginResult.CaptchaRequired -> {
                // ★autopcr `bsgamesdk.login()`：login1 返回 200000 后立刻 make_captch() → login2()，
                //   不把「需要验证码」抛给上层。这里同样先自动解，失败才退回手动弹窗。
                val solution = try {
                    autoCaptcha?.invoke()
                } catch (failure: Throwable) {
                    null
                }
                if (solution != null) {
                    when (val retry = gateway.login(material.toCredentials(), solution)) {
                        is SdkLoginResult.Success -> persistSuccess(material, retry.session)
                        SdkLoginResult.CaptchaRequired -> manualCaptcha(material)
                        is SdkLoginResult.Rejected -> failure(LoginFailureKind.Rejected, retry.message, material)
                        is SdkLoginResult.NetworkFailure -> failure(LoginFailureKind.Network, retry.message, material)
                        is SdkLoginResult.ProtocolFailure -> failure(LoginFailureKind.Protocol, retry.message, material)
                    }
                } else {
                    manualCaptcha(material)
                }
            }
            is SdkLoginResult.NetworkFailure -> failure(LoginFailureKind.Network, result.message, material)
            is SdkLoginResult.ProtocolFailure -> failure(LoginFailureKind.Protocol, result.message, material)
            is SdkLoginResult.Rejected -> failure(LoginFailureKind.Rejected, result.message, material)
        }
    }

    /** 手动兜底：拿 start_captcha 的 gt/challenge 给 WebView，等用户解完 [completeCaptcha]。 */
    private suspend fun manualCaptcha(material: AccountLoginMaterial): AccountLoginResult =
        when (val captcha = gateway.startCaptcha()) {
            is SdkCaptchaResult.Ready -> {
                pending[material.accountId] = PendingLogin(material, captcha.challenge)
                AccountLoginResult.CaptchaRequired(captcha.challenge)
            }
            is SdkCaptchaResult.NetworkFailure -> failure(LoginFailureKind.Network, captcha.message, material)
            is SdkCaptchaResult.ProtocolFailure -> failure(LoginFailureKind.Protocol, captcha.message, material)
            is SdkCaptchaResult.Rejected -> failure(LoginFailureKind.Rejected, captcha.message, material)
        }

    suspend fun completeCaptcha(
        accountId: Long,
        proof: CaptchaProof,
    ): AccountLoginResult = mutex.withLock {
        val attempt = pending[accountId]
            ?: return@withLock AccountLoginResult.Failure(
                LoginFailureKind.InvalidState,
                "登录验证已失效，请重新开始",
            )
        if (proof.validate.isBlank() || proof.validate.length > MAX_CAPTCHA_TOKEN_LENGTH) {
            return@withLock AccountLoginResult.Failure(LoginFailureKind.InvalidState, "验证码结果无效")
        }
        val solution = CaptchaSolution(
            challenge = attempt.challenge.challenge,
            gtUserId = attempt.challenge.gtUserId,
            validate = proof.validate,
        )
        when (val result = gateway.login(attempt.material.toCredentials(), solution)) {
            is SdkLoginResult.Success -> {
                pending.remove(accountId)
                persistSuccess(attempt.material, result.session)
            }
            SdkLoginResult.CaptchaRequired -> {
                pending.remove(accountId)
                AccountLoginResult.Failure(LoginFailureKind.Rejected, "验证未通过，请重新开始登录")
            }
            is SdkLoginResult.NetworkFailure -> failure(LoginFailureKind.Network, result.message, attempt.material)
            is SdkLoginResult.ProtocolFailure -> failure(LoginFailureKind.Protocol, result.message, attempt.material)
            is SdkLoginResult.Rejected -> {
                pending.remove(accountId)
                failure(LoginFailureKind.Rejected, result.message, attempt.material)
            }
        }
    }

    suspend fun cancel(accountId: Long) {
        mutex.withLock { pending.remove(accountId) }
    }

    suspend fun pendingChallenge(accountId: Long): CaptchaChallenge? = mutex.withLock {
        pending[accountId]?.challenge
    }

    private suspend fun persistSuccess(
        material: AccountLoginMaterial,
        session: SdkSession,
    ): AccountLoginResult {
        val stored = SdkSession(session.uid, session.accessKey, clock())
        sessionStore.save(material.credentialKey, stored)
        return AccountLoginResult.Success(stored.uid)
    }

    private fun failure(
        kind: LoginFailureKind,
        message: String,
        material: AccountLoginMaterial,
    ): AccountLoginResult.Failure {
        val safe = message
            .let { if (material.loginId.isNotBlank()) it.replace(material.loginId, "<redacted>") else it }
            .let { if (material.password.isNotBlank()) it.replace(material.password, "<redacted>") else it }
            .ifBlank { "登录失败" }
            .take(MAX_MESSAGE_LENGTH)
        return AccountLoginResult.Failure(kind, safe)
    }

    private fun AccountLoginMaterial.toCredentials() = SdkLoginCredentials(loginId, password)

    private data class PendingLogin(
        val material: AccountLoginMaterial,
        val challenge: CaptchaChallenge,
    )

    private companion object {
        const val MAX_MESSAGE_LENGTH = 200
        const val MAX_CAPTCHA_TOKEN_LENGTH = 4096
    }
}
