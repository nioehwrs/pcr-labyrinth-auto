package com.lmj.labyrinthauto.core

import android.content.Context
import com.lmj.labyrinthauto.net.bilibili.GameServer
import com.lmj.labyrinthauto.reroll.RerollSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * 账号（2026-10-28 用户："改造一下黎明界自动化的账号部分，现在只能固定输入一个，
 * 应该改造成可切换的 —— 每个选项可以输入名称、选服务器；B 服输账密，
 * 渠道服就只提供一个抓取 uid / access key 的按钮"）。
 *
 * 字段语义（与渠道服协议对齐，见 [GameServer] 注释）：
 *  * **B 服**：`loginId` = B 站账号，`secret` = B 站密码 —— 走 Bilibili SDK 换 (uid, access_key)；
 *  * **渠道服**：`loginId` = uid，`secret` = access_key —— **直通**游戏服 `tool/sdk_login`，不经过任何 SDK。
 *
 * 存储：SharedPreferences 里一段 JSON（账号通常个位数，不值得上 Room）。
 * 另外把**选中账号**的凭证镜像进 [RerollSettings] 的 `account/password`，
 * 这样现有的刷开局 / 一键黎明界链路（都是按这两个值取）不用改。
 */
data class Account(
    val id: Long,
    val name: String,
    val server: GameServer,
    val loginId: String = "",
    val secret: String = "",
    /**
     * 渠道服 `tool/sdk_login` 的 `channel_id`（MuMu=65）。抓取时由游戏自己给的参数带回来 ——
     * 各渠道不同、不能猜，所以跟着账号存；B 服固定 "1"，留空即可。
     */
    val channelId: String = "",
) {
    val isChannel: Boolean get() = server.isChannelServer

    /** 列表右侧那一行概要 */
    val summary: String
        get() = when {
            isChannel && loginId.isBlank() -> "未抓取"
            isChannel -> "uid $loginId"
            loginId.isBlank() -> "未填账号"
            else -> loginId
        }

    /** 这一条能不能直接拿去用（B 服要账密；渠道服要 uid + access_key） */
    val ready: Boolean get() = loginId.isNotBlank() && secret.isNotBlank()
}

object Accounts {

    private const val PREF = "lmj_accounts"
    private const val KEY_LIST = "list"
    private const val KEY_SELECTED = "selected"
    private const val KEY_NEXT_ID = "nextId"

    /** 加密的 AAD：把密文绑死在"这个文件这个字段"上，防止挪用到别处复用 */
    private const val AAD_LIST = "$PREF:$KEY_LIST"

    /** 全部账号（按创建顺序） */
    fun all(ctx: Context): List<Account> {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        // ★2026-10-30：整段账号表加密存（[SecureStore]）。老版本的明文会在第一次读时原地升级成密文。
        val raw = SecureStore.get(sp, KEY_LIST, AAD_LIST)
        if (raw.isNullOrBlank()) {
            // 有数据但解不开（keystore 出问题 / 系统安全设置变更）⇒ **别当成"没账号"**：
            // 一旦走进 migrate() 再落盘，原来的密文就被覆盖、凭证彻底丢了。
            if (sp.contains(KEY_LIST)) {
                AppState.addLog("⚠ 账号表读不出来（解密失败）→ 本次按空处理，不会覆盖已存数据")
                return emptyList()
            }
            return migrate(ctx)
        }
        val out = ArrayList<Account>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val server = GameServer.fromStorageId(o.optString("server")) ?: continue
                out.add(
                    Account(
                        id = o.optLong("id", 0L),
                        name = o.optString("name"),
                        server = server,
                        loginId = o.optString("loginId"),
                        secret = o.optString("secret"),
                        channelId = o.optString("channelId"),
                    ),
                )
            }
        }
        return out
    }

    fun selected(ctx: Context): Account? {
        val list = all(ctx)
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val id = sp.getLong(KEY_SELECTED, -1L)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    /** 切换选中账号：顺手把凭证镜像给旧的刷开局链路 */
    fun select(ctx: Context, id: Long) {
        val list = all(ctx)
        val a = list.firstOrNull { it.id == id } ?: return
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SELECTED, id)
            .apply()
        mirror(ctx, a)
    }

    /** 新增或更新一条；若它是当前选中账号，同步镜像并落 latest.json 那一层不需要的凭证 */
    fun save(ctx: Context, account: Account): Account {
        val list = all(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == account.id }
        val clean = account.copy(
            name = account.name.trim().ifBlank { "账号 ${account.id}" },
            // 渠道服的 access_key 用户常带着空格粘贴进来（与 limingjie-assistant 的处理一致）
            loginId = account.loginId.trim(),
            secret = if (account.isChannel) account.secret.trim() else account.secret,
        )
        if (idx >= 0) list[idx] = clean else list.add(clean)
        write(ctx, list)
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (sp.getLong(KEY_SELECTED, -1L) == clean.id) mirror(ctx, clean)
        return clean
    }

    fun delete(ctx: Context, id: Long) {
        val list = all(ctx).filterNot { it.id == id }
        write(ctx, list)
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (sp.getLong(KEY_SELECTED, -1L) == id) {
            val next = list.firstOrNull()
            if (next == null) {
                sp.edit().remove(KEY_SELECTED).apply()
                mirror(ctx, null)
            } else {
                select(ctx, next.id)
            }
        }
    }

    /** 新账号（**已分配 id**，未落盘；用户点保存时再 [save]） */
    fun create(ctx: Context, server: GameServer): Account {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val id = sp.getLong(KEY_NEXT_ID, 1L).coerceAtLeast(all(ctx).maxOfOrNull { it.id + 1 } ?: 1L)
        sp.edit().putLong(KEY_NEXT_ID, id + 1).apply()
        return Account(id = id, name = "账号 $id", server = server)
    }

    /** 默认服务器：跟当前选中账号走；没有账号时按 B 服 */
    fun defaultServer(ctx: Context): GameServer = selected(ctx)?.server ?: GameServer.CN_BILIBILI

    // ────────────────────────── 内部 ──────────────────────────

    private fun write(ctx: Context, list: List<Account>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("name", a.name)
                    .put("server", a.server.storageId)
                    .put("loginId", a.loginId)
                    .put("secret", a.secret)
                    .put("channelId", a.channelId),
            )
        }
        SecureStore.put(
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE),
            KEY_LIST,
            arr.toString(),
            AAD_LIST,
        )
    }

    /**
     * 老用户迁移（只有一份 B 站账密，存在 RerollSettings 里）：
     * 第一次读账号表时把它变成「账号 1 · 国服 Bilibili」，选中它 —— 升级后行为完全不变。
     */
    private fun migrate(ctx: Context): List<Account> {
        val (account, password) = RerollSettings.loadCredentials(ctx)
        if (account.isBlank() && password.isBlank()) return emptyList()
        val a = Account(
            id = 1L,
            name = "B 站账号",
            server = GameServer.CN_BILIBILI,
            loginId = account,
            secret = password,
        )
        write(ctx, listOf(a))
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SELECTED, a.id)
            .putLong(KEY_NEXT_ID, 2L)
            .apply()
        return listOf(a)
    }

    private fun mirror(ctx: Context, a: Account?) {
        RerollSettings.saveCredentials(ctx, a?.loginId.orEmpty(), a?.secret.orEmpty())
    }
}
