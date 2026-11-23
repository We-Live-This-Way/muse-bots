package com.welivethisway.bots.api

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Token storage: values are AES-GCM encrypted with a key that lives in the
 * Android Keystore and never leaves it; only the ciphertext is kept in
 * SharedPreferences. No tokens in plain prefs, code, or logs.
 */
object SecureStore {
    private const val PREFS = "wltw_bots_secure"
    private const val ALIAS = "wltw_bots_key"
    const val KEY_RAKAZO_TOKEN = "rakazo_token"
    const val KEY_RAKAZO_SPACE = "rakazo_space"
    const val KEY_MAUSBOT_TOKEN = "mausbot_token"
    const val KEY_HUB_KEY = "hub_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    fun put(ctx: Context, k: String, value: String): Boolean {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(iv.size + ct.size).apply {
                System.arraycopy(iv, 0, this, 0, iv.size)
                System.arraycopy(ct, 0, this, iv.size, ct.size)
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(k, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun get(ctx: Context, k: String): String? {
        return try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(k, null)
                ?: return null
            val packed = Base64.decode(raw, Base64.NO_WRAP)
            if (packed.size < 13) return null
            val iv = packed.copyOfRange(0, 12)
            val ct = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun remove(ctx: Context, k: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(k).apply()
    }
}
