package com.lmj.labyrinthauto.reroll

import android.content.Context
import com.lmj.labyrinthauto.net.bilibili.AccountLoginMaterial
import com.lmj.labyrinthauto.net.bilibili.AccountLoginResult
import com.lmj.labyrinthauto.net.bilibili.BilibiliGameGatewayFactory
import com.lmj.labyrinthauto.net.bilibili.BilibiliGameSession
import com.lmj.labyrinthauto.net.bilibili.BilibiliLoginCoordinator
import com.lmj.labyrinthauto.net.bilibili.BilibiliSdkGateway
import com.lmj.labyrinthauto.net.bilibili.BilibiliSdkGatewayFactory
import com.lmj.labyrinthauto.net.bilibili.CaptchaChallenge
import com.lmj.labyrinthauto.net.bilibili.CaptchaProof
import com.lmj.labyrinthauto.net.bilibili.CaptchaSolution
import com.lmj.labyrinthauto.net.bilibili.GameLoginResult
import com.lmj.labyrinthauto.net.bilibili.GameServer
import com.lmj.labyrinthauto.net.bilibili.InMemorySdkSessionStore
import com.lmj.labyrinthauto.net.bilibili.SdkCaptchaResult
import com.lmj.labyrinthauto.net.bilibili.SdkSession
import com.lmj.labyrinthauto.net.bilibili.SdkSessionStore

/**
 * 串起「B 站账密登录 → 游戏服登录」：
 *  - B 站 SDK 登录（RSA 加密密码 + 签名 + 可能触发 geetest 验证码）拿到 uid/access_key；
 *  - 用 uid/access_key 走 tool/sdk_login 拿到游戏服会话 [BilibiliGameSession]。
 * 验证码分两种：B 站 SDK 触发（[AccountLoginResult.CaptchaRequired]）与游戏服风控触发
 * （[GameLoginResult.RiskRequired]）—— 二者都通过 [completeCaptcha] 用极验 validate 完成。
 */
sealed interface LoginOutcome {
    data class Session(val session: BilibiliGameSession) : LoginOutcome
    data class CaptchaNeeded(val accountId: Long, val challenge: CaptchaChallenge) : LoginOutcome
    data class Failure(val message: String) : LoginOutcome
}

class LabyrinthLoginClient(context: Context) {
    private val appContext = context.applicationContext
    private val sdkGateway: BilibiliSdkGateway = BilibiliSdkGatewayFactory.create(appContext)
    private val sessionStore: SdkSessionStore = InMemorySdkSessionStore()
    private val loginCoordinator = BilibiliLoginCoordinator(sdkGateway, sessionStore)
    private val gameGateway = BilibiliGameGatewayFactory.create(
        appContext,
        GameServer.CN_BILIBILI.packageName,
        BilibiliGameGatewayFactory.ChannelEndpoint.BILIBILI,
    )
    private val riskPending = mutableMapOf<Long, Pair<SdkSession, CaptchaChallenge>>()

    /**
     * 仅支持 B 站账密登录（RSA + 极验）换 uid/access_key，再走 tool/sdk_login 进游戏服。
     * 账号/密码来自 UI 输入（持久化于本机 SharedPreferences），不读取任何配置文件中的凭证。
     */
    suspend fun login(account: String, password: String): LoginOutcome {
        val material = AccountLoginMaterial(
            accountId = ACCOUNT_ID,
            credentialKey = CREDENTIAL_KEY,
            loginId = account,
            password = password,
            server = GameServer.CN_BILIBILI,
        )
        val sdkSession = when (val r = loginCoordinator.start(material)) {
            is AccountLoginResult.Success -> readSession()
                ?: return LoginOutcome.Failure("登录成功但取不到会话")
            is AccountLoginResult.CaptchaRequired -> return LoginOutcome.CaptchaNeeded(ACCOUNT_ID, r.challenge)
            is AccountLoginResult.Failure -> return LoginOutcome.Failure(r.message)
        }
        return finishGameLogin(sdkSession)
    }

    suspend fun completeCaptcha(validate: String): LoginOutcome {
        // 优先当作 B 站 SDK 验证码完成
        if (loginCoordinator.pendingChallenge(ACCOUNT_ID) != null) {
            return when (val r = loginCoordinator.completeCaptcha(ACCOUNT_ID, CaptchaProof(validate))) {
                is AccountLoginResult.Success -> {
                    val session = readSession() ?: return LoginOutcome.Failure("验证成功但取不到会话")
                    finishGameLogin(session)
                }
                is AccountLoginResult.CaptchaRequired -> LoginOutcome.CaptchaNeeded(ACCOUNT_ID, r.challenge)
                is AccountLoginResult.Failure -> LoginOutcome.Failure(r.message)
            }
        }
        val pending = riskPending[ACCOUNT_ID] ?: return LoginOutcome.Failure("验证码已失效，请重新开始登录")
        val (sdkSession, challenge) = pending
        val solution = CaptchaSolution(challenge.challenge, challenge.gtUserId, validate)
        return when (val g = gameGateway.loginAndLoadProfile(sdkSession, ACCOUNT_ID.toString(), solution)) {
            is GameLoginResult.Success -> LoginOutcome.Session(g.session)
            is GameLoginResult.RiskRequired -> LoginOutcome.Failure("游戏服仍要求验证，请稍后在游戏内完成验证后重试")
            is GameLoginResult.SessionRejected -> LoginOutcome.Failure(g.message)
            is GameLoginResult.Rejected -> LoginOutcome.Failure(g.message)
            is GameLoginResult.Maintenance -> LoginOutcome.Failure(g.message)
            is GameLoginResult.NetworkFailure -> LoginOutcome.Failure(g.message)
            is GameLoginResult.ProtocolFailure -> LoginOutcome.Failure(g.message)
        }
    }

    private suspend fun finishGameLogin(session: SdkSession): LoginOutcome {
        return when (val g = gameGateway.loginAndLoadProfile(session, ACCOUNT_ID.toString())) {
            is GameLoginResult.Success -> LoginOutcome.Session(g.session)
            is GameLoginResult.RiskRequired -> when (val c = sdkGateway.startCaptcha()) {
                is SdkCaptchaResult.Ready -> {
                    riskPending[ACCOUNT_ID] = session to c.challenge
                    LoginOutcome.CaptchaNeeded(ACCOUNT_ID, c.challenge)
                }
                is SdkCaptchaResult.NetworkFailure -> LoginOutcome.Failure("游戏服要求验证但验证码初始化网络失败")
                is SdkCaptchaResult.ProtocolFailure -> LoginOutcome.Failure("游戏服要求验证但验证码响应无法解析")
                is SdkCaptchaResult.Rejected -> LoginOutcome.Failure("游戏服要求验证但被拒：${c.message}")
            }
            is GameLoginResult.SessionRejected -> LoginOutcome.Failure(g.message)
            is GameLoginResult.Rejected -> LoginOutcome.Failure(g.message)
            is GameLoginResult.Maintenance -> LoginOutcome.Failure(g.message)
            is GameLoginResult.NetworkFailure -> LoginOutcome.Failure(g.message)
            is GameLoginResult.ProtocolFailure -> LoginOutcome.Failure(g.message)
        }
    }

    private suspend fun readSession(): SdkSession? = sessionStore.read(CREDENTIAL_KEY)

    private companion object {
        const val ACCOUNT_ID = 1L
        const val CREDENTIAL_KEY = "lab-reroll-1"
    }
}
