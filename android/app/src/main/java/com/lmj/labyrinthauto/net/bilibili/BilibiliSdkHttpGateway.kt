package com.lmj.labyrinthauto.net.bilibili

import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class BilibiliSdkHttpGateway(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl,
    private val requestProfile: BilibiliSdkRequestProfile,
    private val signer: BilibiliSdkSigner,
    private val json: Json,
    private val epochSeconds: () -> Long,
    private val passwordEncryptor: RsaPasswordEncryptor = RsaPasswordEncryptor(),
    private val dispatcher: CoroutineContext = Dispatchers.IO,
) : BilibiliSdkGateway {
    override suspend fun login(
        credentials: SdkLoginCredentials,
        captcha: CaptchaSolution?,
    ): SdkLoginResult = withContext(dispatcher) {
        try {
            val rsa = SdkResponseCodec.rsa(json, post(RSA_PATH, requestProfile.rsaFields()))
            if (rsa.code != SUCCESS || rsa.hash.isBlank() || rsa.rsaKey.isBlank()) {
                return@withContext SdkLoginResult.ProtocolFailure("登录密钥协商失败")
            }
            val encryptedPassword = passwordEncryptor.encrypt(rsa.hash, credentials.password, rsa.rsaKey)
            val response = SdkResponseCodec.login(
                json,
                post(LOGIN_PATH, requestProfile.loginFields(credentials, encryptedPassword, captcha)),
            )
            when {
                response.code == SUCCESS && response.accessKey.isNotBlank() && response.uid.isNotBlank() ->
                    SdkLoginResult.Success(SdkSession(response.uid, response.accessKey))
                response.code == CAPTCHA_REQUIRED || response.needCaptcha -> SdkLoginResult.CaptchaRequired
                else -> SdkLoginResult.Rejected(response.code, response.message.take(MAX_MESSAGE_LENGTH))
            }
        } catch (failure: IOException) {
            SdkLoginResult.NetworkFailure("网络连接失败：${describe(failure)}")
        } catch (failure: Throwable) {
            SdkLoginResult.ProtocolFailure("登录响应无法解析：${describe(failure)}")
        }
    }

    override suspend fun startCaptcha(): SdkCaptchaResult = withContext(dispatcher) {
        try {
            val response = SdkResponseCodec.captcha(json, post(CAPTCHA_PATH, requestProfile.captchaFields()))
            // ★与 autopcr 的 captch() 对齐：它直接返回 json.loads 的 dict，validator 只取
            //   gt / challenge / gt_user_id，不看 code；这里也只以「三个字段齐全」为准。
            if (response.gt.isNotBlank() && response.challenge.isNotBlank() && response.gtUserId.isNotBlank()) {
                SdkCaptchaResult.Ready(
                    CaptchaChallenge(
                        gt = response.gt,
                        challenge = response.challenge,
                        gtUserId = response.gtUserId,
                        captchaType = response.captchaType,
                    ),
                )
            } else {
                SdkCaptchaResult.Rejected(response.code, response.message.take(MAX_MESSAGE_LENGTH))
            }
        } catch (failure: IOException) {
            SdkCaptchaResult.NetworkFailure("验证码初始化网络失败：${describe(failure)}")
        } catch (failure: Throwable) {
            SdkCaptchaResult.ProtocolFailure("验证码响应无法解析：${describe(failure)}")
        }
    }

    private fun post(path: String, fields: LinkedHashMap<String, String>): String {
        val signed = signer.withSignature(fields, epochSeconds())
        val body = FormBody.Builder().apply {
            signed.forEach { (name, value) -> add(name, value) }
        }.build()
        val url = requireNotNull(endpoint.resolve(path)) { "Invalid SDK endpoint" }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("Empty response")
        }
    }

    /** 只带异常类名 + 简短原因，不把响应体写进日志（login 响应里有 uid / access_key）。 */
    private fun describe(failure: Throwable): String =
        (failure::class.simpleName ?: "Throwable") + ": " + failure.message.orEmpty().take(MAX_MESSAGE_LENGTH)

    private companion object {
        const val SUCCESS = 0
        const val CAPTCHA_REQUIRED = 200000
        const val RSA_PATH = "api/client/rsa"
        const val LOGIN_PATH = "api/client/login"
        const val CAPTCHA_PATH = "api/client/start_captcha"
        const val USER_AGENT = "Mozilla/5.0 BSGameSDK"
        const val MAX_MESSAGE_LENGTH = 200
    }
}
