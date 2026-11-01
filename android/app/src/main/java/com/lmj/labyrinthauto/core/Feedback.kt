package com.lmj.labyrinthauto.core

import android.content.Context
import android.os.Build
import com.lmj.labyrinthauto.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 反馈直通车（2026-11-01 用户方案）：**填昵称 + 内容 + 可选诊断包 → 直接开一个 Gitee issue**。
 *
 * 链路（三步，都是 Gitee API v5）：
 * ```
 * ① 找/建附件仓库的 feedback Release（`target_commitish=master`；仓库必须有提交）
 * ② 诊断包 multipart 传到该 Release 的附件（Issue 正文的附件 API 传不了，Release 有）
 * ③ 在发布仓库建 issue：POST /repos/{owner}/issues，repo 走**表单**
 * ```
 *
 * 三个容易踩的坑（2026-11-01 实测，改之前先看一眼）：
 *  * 建 issue 的路径里**没有** repo 段；写成 `/repos/{owner}/{repo}/issues` 会一直
 *    `404 {"message":"Not Found Project"}`；
 *  * issue 号是字符串（`IKS1NB`），不是数字；
 *  * 附件上传属于 `projects` 权限 —— 所以令牌是**反馈小号**的，它对发布仓库只有观察者权限
 *    （实测建 Release 会 403），诊断包只能落到小号自己的附件仓库。
 *
 * 令牌来自构建配置 [BuildConfig.GITEE_TOKEN]（`android/gitee.properties`，不入库）。
 * 随包分发 ⇒ 等于公开，所以刻意选用"对发布仓库零写权限"的小号（见 tools/issue.py 顶部说明）。
 */
object Feedback {

    private const val API = "https://gitee.com/api/v5"

    /** issue 提到哪个仓库（发布仓库，小号只是观察者） */
    private const val OWNER = "nioehwrs"
    private const val REPO = "labyrinth-auto-release"

    /** 诊断包落到小号自己的仓库（它 own ⇒ 能建 Release / 传附件） */
    private const val FB_OWNER = "the-oceans-gentle-giant"
    private const val FB_REPO = "labyrinth-auto-feedback"
    private const val FB_TAG = "feedback"

    private const val PREF = "feedback"
    private const val KEY_NICK = "nick"

    private val json = "application/json;charset=UTF-8".toMediaType()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(600, TimeUnit.SECONDS)   // 诊断包可能有几 MB
            .build()
    }

    /** 这个包是否带反馈令牌（构建时缺 gitee.properties 就为 false） */
    fun configured(): Boolean = BuildConfig.GITEE_TOKEN.isNotBlank()

    /** 昵称：任意一次提交后就记住，下次不用重填 */
    fun loadNick(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_NICK, "").orEmpty()

    fun saveNick(ctx: Context, nick: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_NICK, nick.trim()).apply()
    }

    class Failed(message: String) : Exception(message)

    data class Sent(
        /** issue 的网页地址（给用户看/复制） */
        val issueUrl: String,
        /** 诊断包在附件仓库的下载地址（没附件时为 null） */
        val attachUrl: String?,
    )

    /**
     * 提交一条反馈。
     *
     * @param nick    昵称（必填，调用方已校验）
     * @param content 反馈内容（必填，调用方已校验）
     * @param zip     诊断包；null = 不带附件
     * @param onStep  进度回调（"上传诊断包…" / "创建 issue…"），在主线程之外调用，调用方自己切回来
     */
    suspend fun submit(
        ctx: Context,
        nick: String,
        content: String,
        zip: File?,
        onStep: (String) -> Unit = {},
    ): Sent = withContext(Dispatchers.IO) {
        val token = BuildConfig.GITEE_TOKEN
        if (token.isBlank()) throw Failed("这个版本没有内置反馈通道（构建时缺少 gitee.properties）")

        var attachUrl: String? = null
        if (zip != null) {
            onStep("上传诊断包（${(zip.length() + 1023) / 1024} KB）…")
            val rid = ensureFeedbackRelease(token)
            attachUrl = uploadAttach(token, rid, zip)
        }

        onStep("创建 issue…")
        val body = buildBody(ctx, nick, content, zip?.name, attachUrl)
        val title = buildTitle(nick, content)

        val form = FormBody.Builder()
            .add("access_token", token)
            .add("repo", REPO)
            .add("title", title)
            .add("body", body)
            .build()
        val req = Request.Builder()
            .url("$API/repos/$OWNER/issues")
            .post(form)
            .build()
        val text = exec(req, "创建 issue")
        val url = runCatching { org.json.JSONObject(text).optString("html_url") }.getOrDefault("")
        Sent(issueUrl = url.ifBlank { "https://gitee.com/$OWNER/$REPO/issues" }, attachUrl = attachUrl)
    }

    // ────────────────────────── 内部：附件仓库 ──────────────────────────

    /** 找 `feedback` Release，没有就建（空仓库建不了 tag ⇒ 提示换仓库，不静默失败） */
    private fun ensureFeedbackRelease(token: String): Long {
        val listReq = Request.Builder()
            .url("$API/repos/$FB_OWNER/$FB_REPO/releases?access_token=$token&per_page=100")
            .get().build()
        val arr = runCatching { JSONArray(exec(listReq, "读取附件仓库")) }.getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (r.optString("tag_name") == FB_TAG) return r.optLong("id")
        }
        val form = FormBody.Builder()
            .add("access_token", token)
            .add("tag_name", FB_TAG)
            .add("name", "反馈附件（诊断包）")
            .add("body", "用户反馈用的诊断包：由 App「反馈」直接上传。仅作文件箱，不是发行版。")
            .add("target_commitish", "master")
            .build()
        val req = Request.Builder().url("$API/repos/$FB_OWNER/$FB_REPO/releases").post(form).build()
        val text = exec(req, "创建附件 Release")
        val id = runCatching { org.json.JSONObject(text).optLong("id") }.getOrDefault(0L)
        if (id <= 0L) throw Failed("附件仓库没准备好（需要仓库里至少有一个提交才能建 Release）")
        return id
    }

    private fun uploadAttach(token: String, releaseId: Long, zip: File): String {
        // 文件名加时间戳：同名会被顶掉，且便于以后按时间清理
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = zip.name.removeSuffix(".zip") + "_$stamp.zip"
        val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("access_token", token)
            .addFormDataPart(
                "file", name,
                zip.asRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        val req = Request.Builder()
            .url("$API/repos/$FB_OWNER/$FB_REPO/releases/$releaseId/attach_files")
            .post(mp).build()
        val text = exec(req, "上传诊断包")
        val o = runCatching { org.json.JSONObject(text) }.getOrNull()
        return o?.optString("browser_download_url").orEmpty().ifBlank {
            "https://gitee.com/$FB_OWNER/$FB_REPO/releases/download/$FB_TAG/$name"
        }
    }

    // ────────────────────────── 内部：正文 / 请求 ──────────────────────────

    private fun buildTitle(nick: String, content: String): String {
        val one = content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val brief = if (one.length > 40) one.take(40) + "…" else one
        return "[反馈] $nick · $brief"
    }

    private fun buildBody(
        ctx: Context,
        nick: String,
        content: String,
        zipName: String?,
        attachUrl: String?,
    ): String {
        val ver = runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
                @Suppress("DEPRECATION") pi.versionCode.toLong()
            }
            "${pi.versionName ?: "?"} ($code)"
        }.getOrDefault("?")
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val dev = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · " +
            (Build.SUPPORTED_ABIS.firstOrNull() ?: "?")

        val sb = StringBuilder()
        sb.appendLine("### 反馈")
        sb.appendLine("- 昵称：$nick")
        sb.appendLine("- 版本：$ver")
        sb.appendLine("- 时间：$time")
        sb.appendLine("- 设备：$dev")
        sb.appendLine()
        sb.appendLine("### 内容")
        sb.appendLine()
        sb.appendLine(content.trim())
        sb.appendLine()
        sb.appendLine("### 诊断包")
        if (attachUrl == null) {
            sb.appendLine("未附带。")
        } else {
            sb.appendLine("- [$zipName]($attachUrl)")
        }
        return sb.toString()
    }

    private fun exec(req: Request, what: String): String {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val brief = text.replace(Regex("\\s+"), " ").take(160)
                throw Failed("$what 失败：HTTP ${resp.code} $brief")
            }
            return text
        }
    }
}
