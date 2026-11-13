package com.lmj.labyrinthauto.reroll

import android.content.Context
import com.lmj.labyrinthauto.core.Account
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.net.bilibili.AccountLoginMaterial
import com.lmj.labyrinthauto.net.bilibili.AccountLoginResult
import com.lmj.labyrinthauto.net.bilibili.BilibiliGameGateway
import com.lmj.labyrinthauto.net.bilibili.BilibiliGameGatewayFactory
import com.lmj.labyrinthauto.net.bilibili.BilibiliGameSession
import com.lmj.labyrinthauto.net.bilibili.BilibiliLoginCoordinator
import com.lmj.labyrinthauto.net.bilibili.BilibiliSdkGateway
import com.lmj.labyrinthauto.net.bilibili.BilibiliSdkGatewayFactory
import com.lmj.labyrinthauto.net.bilibili.CaptchaChallenge
import com.lmj.labyrinthauto.net.bilibili.CaptchaProof
import com.lmj.labyrinthauto.net.bilibili.CaptchaSolution
import com.lmj.labyrinthauto.net.bilibili.GameLoginResult
import com.lmj.labyrinthauto.net.bilibili.InMemorySdkSessionStore
import com.lmj.labyrinthauto.net.bilibili.RemoteCaptchaSolver
import com.lmj.labyrinthauto.net.bilibili.SdkCaptchaResult
import com.lmj.labyrinthauto.net.bilibili.SdkSession
import com.lmj.labyrinthauto.net.bilibili.SdkSessionStore

/**
 * 串起「账号 → 游戏服会话 [BilibiliGameSession]」。**两条路，按账号所属服务器分流**：
 *
 *  * **B 服**：B 站账密 → SDK 登录（RSA + 签名 + 可能极验）换 uid/access_key →
 *    `tool/sdk_login`（l3 网关，platform=2/channel_id=1）。
 *  * **渠道服**：**没有**可用的账密登录，账号里存的就是 uid/access_key（由 `AccessCapture`
 *    从渠道客户端里抓回来，顺带带回该渠道的 `channel_id`）→ 直通 `tool/sdk_login`
 *    （l1 网关，platform=4/channel_id=各渠道）。与 AutoPCR 的 qsdkclient 一致，不经过任何 SDK。
 *
 * 验证码分两种：B 站 SDK 触发（[AccountLoginResult.CaptchaRequired]）与游戏服风控触发
 * （[GameLoginResult.RiskRequired]）—— 二者都通过 [completeCaptcha] 用极验 validate 完成。
 * 渠道服直通时若游戏服要验证，没有 SDK 会话可续（不猜），直接报人话失败。
 */
sealed interface LoginOutcome {
    data class Session(val session: BilibiliGameSession) : LoginOutcome
    data class CaptchaNeeded(val accountId: Long, val challenge: CaptchaChallenge) : LoginOutcome
    data class Failure(val message: String) : LoginOutcome
}

class LabyrinthLoginClient(context: Context, private val account: Account) {
    private val appContext = context.applicationContext

    // ★两个网关都**按需**构造（by lazy）。原因（2026-10-29 实测崩溃）：
    //   B 服 SDK 网关构造时要读 `com.bilibili.priconne` 的版本号当 APP-VER，
    //   而只装了渠道客户端的机器上那个包根本不存在 ⇒ getPackageInfo 抛 NameNotFoundException，
    //   连"渠道服登录"都还没开始就崩了。渠道服直通用不到 SDK 网关，就不该付这个代价。
    private val sdkGateway: BilibiliSdkGateway by lazy { BilibiliSdkGatewayFactory.create(appContext) }
    private val sessionStore: SdkSessionStore = InMemorySdkSessionStore()

    /** ★自动打码（2026-11-13）：复刻 autopcr remoteValidator，失败才弹手动极验。 */
    private val captchaSolver: RemoteCaptchaSolver by lazy {
        RemoteCaptchaSolver(
            baseUrl = captchaService(appContext),
            onStatus = { msg ->
                AppState.setRerollStatus(msg)
                AppState.addLog("自动验证：$msg")
            },
        )
    }
    private val loginCoordinator: BilibiliLoginCoordinator by lazy {
        BilibiliLoginCoordinator(sdkGateway, sessionStore, autoCaptcha = { captchaSolver.solve() })
    }
    private val gameGateway: BilibiliGameGateway by lazy {
        BilibiliGameGatewayFactory.create(
            appContext,
            account.server.packageName,
            BilibiliGameGatewayFactory.endpointFor(account.server),
            account.channelId.ifBlank { "1" },
        )
    }
    private val riskPending = mutableMapOf<Long, Pair<SdkSession, CaptchaChallenge>>()

    /** 登录（按账号所属服务器自动走 B 服 SDK 或渠道服直通）。 */
    suspend fun login(): LoginOutcome = try {
        if (account.isChannel) loginChannel() else loginBilibili()
    } catch (t: Throwable) {
        // 初始化就可能失败（对应服务器的客户端没装 ⇒ 读不到 APP-VER），别让异常把进程带走
        LoginOutcome.Failure(t.message?.takeIf { it.isNotBlank() } ?: "登录初始化失败")
    }

    /**
     * 渠道服：uid / access_key 直接当 `tool/sdk_login` 的入参。
     *
     * 注意 `channel_id` 必须对 —— 它是渠道维度的，猜错会被网关当别的渠道拒掉，
     * 所以抓取时一并存进账号（[Account.channelId]）；这里缺了就直接报错，不兜底。
     */
    private suspend fun loginChannel(): LoginOutcome {
        if (account.loginId.isBlank() || account.secret.isBlank()) {
            return LoginOutcome.Failure("渠道服账号缺 uid / access_key：先在账号卡里点「抓取」")
        }
        if (account.channelId.isBlank()) {
            return LoginOutcome.Failure("渠道服账号缺 channel_id：点「抓取」重新取一次（会一并带回来）")
        }
        val session = SdkSession(account.loginId, account.secret, System.currentTimeMillis())
        return when (val g = gameGateway.loginAndLoadProfile(session, CHANNEL_DEVICE_SEED)) {
            is GameLoginResult.Success -> LoginOutcome.Session(g.session)
            is GameLoginResult.RiskRequired -> {
                // 渠道服虽没有 SDK 账密，但 tool/sdk_login 只需要 uid/access_key + 验证结果，
                // 与 autopcr qsdkclient 一样可以直接自动打码重试。
                val solution = runCatching { captchaSolver.solve() }.getOrNull()
                if (solution == null) {
                    LoginOutcome.Failure("游戏服要求验证码：自动验证不可用，先在游戏里手动进一次黎明界")
                } else {
                    when (val g2 = gameGateway.loginAndLoadProfile(session, CHANNEL_DEVICE_SEED, solution)) {
                        is GameLoginResult.Success -> LoginOutcome.Session(g2.session)
                        else -> LoginOutcome.Failure("游戏服要求验证码：自动验证未通过，先在游戏里手动进一次黎明界")
                    }
                }
            }
            is GameLoginResult.SessionRejected -> LoginOutcome.Failure(g.message)
            is GameLoginResult.Rejected -> LoginOutcome.Failure(g.message)
            is GameLoginResult.Maintenance -> LoginOutcome.Failure(g.message)
            is GameLoginResult.NetworkFailure -> LoginOutcome.Failure(g.message)
            is GameLoginResult.ProtocolFailure -> LoginOutcome.Failure(g.message)
        }
    }

    /**
     * B 服：账密 → SDK 换 uid/access_key → 游戏服。
     * 账号/密码来自账号表（持久化于本机 SharedPreferences），不读取任何配置文件中的凭证。
     */
    private suspend fun loginBilibili(): LoginOutcome {
        if (account.loginId.isBlank() || account.secret.isBlank()) {
            return LoginOutcome.Failure("B 服账号或密码为空")
        }
        val material = AccountLoginMaterial(
            accountId = ACCOUNT_ID,
            credentialKey = CREDENTIAL_KEY,
            loginId = account.loginId,
            password = account.secret,
            server = account.server,
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
            is GameLoginResult.RiskRequired -> {
                // ★autopcr `sessionmgr._ensure_token` 在 is_risk 时同样 do_captcha() 后重试
                //   tool/sdk_login；这里先自动解，失败才退回手动弹窗。
                val solution = runCatching { captchaSolver.solve() }.getOrNull()
                if (solution != null) {
                    when (val g2 = gameGateway.loginAndLoadProfile(session, ACCOUNT_ID.toString(), solution)) {
                        is GameLoginResult.Success -> LoginOutcome.Session(g2.session)
                        else -> riskViaManual(session)
                    }
                } else {
                    riskViaManual(session)
                }
            }
            is GameLoginResult.SessionRejected -> LoginOutcome.Failure(g.message)
            is GameLoginResult.Rejected -> LoginOutcome.Failure(g.message)
            is GameLoginResult.Maintenance -> LoginOutcome.Failure(g.message)
            is GameLoginResult.NetworkFailure -> LoginOutcome.Failure(g.message)
            is GameLoginResult.ProtocolFailure -> LoginOutcome.Failure(g.message)
        }
    }

    /** 手动兜底：游戏服风控时拿一组新的 gt/challenge 交给 WebView。 */
    private suspend fun riskViaManual(session: SdkSession): LoginOutcome =
        when (val c = sdkGateway.startCaptcha()) {
            is SdkCaptchaResult.Ready -> {
                riskPending[ACCOUNT_ID] = session to c.challenge
                LoginOutcome.CaptchaNeeded(ACCOUNT_ID, c.challenge)
            }
            is SdkCaptchaResult.NetworkFailure -> LoginOutcome.Failure("游戏服要求验证但验证码初始化网络失败")
            is SdkCaptchaResult.ProtocolFailure -> LoginOutcome.Failure("游戏服要求验证但验证码响应无法解析")
            is SdkCaptchaResult.Rejected -> LoginOutcome.Failure("游戏服要求验证但被拒：${c.message}")
        }

    private suspend fun readSession(): SdkSession? = sessionStore.read(CREDENTIAL_KEY)

    private companion object {
        const val ACCOUNT_ID = 1L
        const val CREDENTIAL_KEY = "lab-reroll-1"

        /** 渠道服没有 SDK 设备种子，协议只要求非空（与 B 服一样用固定串，避免每次登录变化触发风控）。 */
        const val CHANNEL_DEVICE_SEED = "1"

        /** `assets/config.json` 的 captcha_service；读不到就用 autopcr 同一个默认地址。 */
        fun captchaService(ctx: Context): String = runCatching {
            val txt = ctx.assets.open("config.json").bufferedReader().use { it.readText() }
            org.json.JSONObject(txt).optString("captcha_service")
                .takeIf { it.startsWith("http", ignoreCase = true) }
        }.getOrNull() ?: RemoteCaptchaSolver.DEFAULT_SERVICE
    }
}
