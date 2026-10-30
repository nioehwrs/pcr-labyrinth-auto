package com.lmj.labyrinthauto.core

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭证的**静态加密**存储（2026-10-30 用户："账密 / uid / access_key 都是本地明文保存，
 * root 情况下可能被其他方式获取"）。
 *
 * ## 挡什么、不挡什么（写清楚，免得产生错误的安全感）
 * * **挡得住**：数据副本**离开这台设备** —— `adb pull`、把 `Android/data` 整包拷给别人排查、
 *   云备份/换机迁移、TWRP 整盘镜像。密钥由 AndroidKeyStore 持有（不可导出，支持的设备上在
 *   TEE / StrongBox 里），密文拷走 = 一堆解不开的字节。
 * * **挡不住**：**同一台设备上的 root 主动攻击** —— keystore 的访问控制是「按 UID 放行」，
 *   root 可以 setuid 成本 app 的 UID（或 ptrace/frida 注入本进程），让"我们自己"去解密；
 *   也能直接 dump 内存里的明文。这由 Android 权限模型决定，客户端无解。
 *
 * 算法和代码本来就是公开的（APK 可反编译），安全性**只在密钥上** —— 所以绝不能把密钥写进代码。
 *
 * ## 用法与兼容
 * 值以 [PREFIX] 开头表示密文；不带前缀的当作**老版本明文**照常使用，并在第一次读到时
 * **原地升级**成密文（同一个 key 覆盖写）。降级回老版本会读不懂密文，表现为"凭证为空、
 * 需要重新填写" —— 这是刻意取舍（安全优先）。
 *
 * AAD 传"哪个文件哪个字段"（如 `lmj_accounts:list`），防止把密文挪到别的字段里复用。
 */
object SecureStore {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "lmj_vault_v1"
    private const val PREFIX = "enc:v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    @Volatile
    private var cachedKey: SecretKey? = null

    /** aad → (密文, 明文)：同一份密文不重复解（`Accounts.all()` 每帧都会被调用） */
    private val plainCache = HashMap<String, Pair<String, String>>()

    fun isEncrypted(stored: String?): Boolean = stored != null && stored.startsWith(PREFIX)

    /** 加密；keystore 不可用时返回 null（调用方决定怎么退化）。 */
    fun encrypt(plain: String, aad: String): String? {
        val k = key() ?: return null
        return runCatching {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.ENCRYPT_MODE, k)
            c.updateAAD(aad.toByteArray(Charsets.UTF_8))
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            val iv = c.iv
            val out = ByteArray(1 + iv.size + ct.size)
            out[0] = iv.size.toByte()
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
            PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        }.getOrNull()
    }

    /** 解密；明文直接原样返回；解不开返回 null。 */
    fun decrypt(stored: String, aad: String): String? {
        if (!isEncrypted(stored)) return stored
        plainCache[aad]?.let { if (it.first == stored) return it.second }
        val k = key() ?: return null
        val plain = runCatching {
            val buf = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val ivLen = buf[0].toInt()
            require(ivLen in 8..32) { "iv 长度异常" }
            val iv = buf.copyOfRange(1, 1 + ivLen)
            val ct = buf.copyOfRange(1 + ivLen, buf.size)
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
            c.updateAAD(aad.toByteArray(Charsets.UTF_8))
            String(c.doFinal(ct), Charsets.UTF_8)
        }.getOrNull() ?: return null
        plainCache[aad] = stored to plain
        return plain
    }

    /**
     * 写一个值（空串 = 删掉这个 key）。
     * 加密不可用时**退回明文**并记一行日志 —— 宁可弱一点，也不能让自动化因为存不进凭证而坏掉。
     */
    fun put(sp: SharedPreferences, key: String, plain: String, aad: String) {
        if (plain.isEmpty()) {
            sp.edit().remove(key).apply()
            return
        }
        val enc = encrypt(plain, aad)
        if (enc == null) {
            AppState.addLog("⚠ 凭证加密不可用（keystore 异常）→ 「$key」暂按明文保存")
            sp.edit().putString(key, plain).apply()
            return
        }
        sp.edit().putString(key, enc).apply()
        plainCache[aad] = enc to plain
    }

    /**
     * 读一个"可能明文（老版本）/密文"的值；读到明文就**原地升级**成密文。
     * @return null = 没有这个值，或密文解不开（会记日志，调用方别当成"新用户"去覆盖数据）
     */
    fun get(sp: SharedPreferences, key: String, aad: String): String? {
        val raw = sp.getString(key, null) ?: return null
        if (raw.isEmpty()) return raw
        if (!isEncrypted(raw)) {
            // 老版本明文：升级后照常返回（升级失败也照样能用）
            put(sp, key, raw, aad)
            return raw
        }
        val plain = decrypt(raw, aad)
        if (plain == null) {
            AppState.addLog("⚠ 凭证解密失败（$key）：可能系统安全设置变更 / 换过设备，请重新填写或重新抓取")
        }
        return plain
    }

    // ────────────────────────── 密钥 ──────────────────────────

    private fun key(): SecretKey? {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val r = runCatching { loadOrCreateKey() }
            val k = r.getOrNull()
            if (k == null) {
                val why = r.exceptionOrNull()
                AppState.addLog(
                    "⚠ 凭证加密不可用（keystore 生成密钥失败：${why?.javaClass?.simpleName ?: "?"}: " +
                        "${why?.message?.take(120) ?: "-"}）→ 暂按明文保存",
                )
            }
            cachedKey = k
            return k
        }
    }

    /**
     * 逐级降级建密钥 —— 有些 ROM / 模拟器的 keystore 支持不全（实测 MuMu 上
     * `setUnlockedDeviceRequired` 就会直接失败），所以从"最安全"一路退到"只要能加就行"：
     *   ① StrongBox（独立安全芯片）
     *   ② 仅锁屏保护（设备锁屏时解不开）
     *   ③ 朴素 AES-256-GCM
     * 前两种在支持的机型上更安全；退到第 ③ 种仍然是"密钥不可导出"的 keystore 密钥。
     */
    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        var last: Throwable? = null
        for (variant in KEY_VARIANTS) {
            try {
                gen.init(keySpec(variant))
                return gen.generateKey()
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("keystore 生成密钥失败")
    }

    private enum class KeyVariant { STRONG_BOX, UNLOCKED_ONLY, PLAIN }

    private fun keySpec(variant: KeyVariant): KeyGenParameterSpec {
        val b = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && variant != KeyVariant.PLAIN) {
            // 设备锁屏时 keystore 拒绝解密：手机被偷/丢在桌上时密文也用不了。
            // 自动化本来就必须解锁跑，所以对使用没有影响。
            b.setUnlockedDeviceRequired(true)
            if (variant == KeyVariant.STRONG_BOX) b.setIsStrongBoxBacked(true)
        }
        return b.build()
    }

    private val KEY_VARIANTS = listOf(KeyVariant.STRONG_BOX, KeyVariant.UNLOCKED_ONLY, KeyVariant.PLAIN)
}
