package com.github.kr328.clash.service.easytier

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.github.kr328.clash.service.PreferenceProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 加密的敏感字段存储（AES-256/GCM）。
 *
 * 密文（Base64，非明文）存于跨进程 PreferenceProvider 支撑的 SharedPreferences：
 * UI 进程写入、:background 进程读取，两者一致（普通 MODE_PRIVATE 文件跨进程不可靠）。
 * 密钥不出安全硬件。解密失败（重装/密钥轮换）返回 null，由 UI 提示重新填写，不静默清空。
 */
object SecretStore {
    private const val KEYSTORE_ALIAS = "easyclashmaster"
    private const val IV_LEN = 12
    private const val TAG_LEN = 128

    const val KEY_NETWORK_SECRET = "et.network_secret"
    const val KEY_NORMAL_PASS = "et.socks5.normal.password"
    const val KEY_WARP_PASS = "et.socks5.warp.password"

    private fun prefs(context: Context): SharedPreferences =
        PreferenceProvider.createSharedPreferencesFromContext(context)

    private fun key(): SecretKey? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generator.generateKey()
        } catch (e: Exception) {
            null
        }
    }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    fun encrypt(context: Context, key: String, plain: String) {
        val secretKey = key() ?: throw IllegalStateException("Android Keystore unavailable on this device")
        val sp = prefs(context)
        if (plain.isEmpty()) {
            sp.edit().remove(key).commit()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        sp.edit().putString(key, blob).commit()
    }

    fun decrypt(context: Context, key: String): String? {
        val secretKey = key() ?: return null
        val blob = prefs(context).getString(key, null) ?: return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            require(raw.size > IV_LEN) { "ciphertext too short" }
            val iv = raw.copyOfRange(0, IV_LEN)
            val ct = raw.copyOfRange(IV_LEN, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LEN, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context, key: String) {
        prefs(context).edit().remove(key).commit()
    }
}
