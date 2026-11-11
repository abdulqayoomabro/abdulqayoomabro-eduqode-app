package com.eduqode.app.offline

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Who is logged in to the app, and their app token.
 * The token is encrypted with a key kept in the Android Keystore (never leaves the phone's secure hardware
 * where available). Only one student is "active" at a time; their offline courses are the ones shown.
 */
class AuthStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("eduqode_auth", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALIAS = "eduqode_app_token_key"
    }

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    val userId: Long get() = prefs.getLong("user_id", 0)
    val userLogin: String get() = prefs.getString("user_login", "") ?: ""
    val userName: String get() = prefs.getString("user_name", "") ?: ""
    val isLoggedIn: Boolean get() = userId > 0 && token != null

    /** Set when the server rejected the token; cleared on the next successful login. */
    var needsLogin: Boolean
        get() = prefs.getBoolean("needs_login", false)
        set(v) = prefs.edit().putBoolean("needs_login", v).apply()

    val token: String?
        get() {
            val enc = prefs.getString("token", null) ?: return null
            return runCatching { decrypt(enc) }.getOrNull()
        }

    fun save(token: String, userId: Long, login: String, name: String) {
        prefs.edit()
            .putString("token", encrypt(token))
            .putLong("user_id", userId)
            .putString("user_login", login)
            .putString("user_name", name)
            .putBoolean("needs_login", false)
            .apply()
    }

    /** Logs the app out. Downloaded files stay on the phone but are locked until the same student logs in again. */
    fun clear() {
        prefs.edit().remove("token").remove("user_id").remove("user_login").remove("user_name").apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (ivB64, dataB64) = stored.split(":", limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
