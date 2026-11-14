package com.onecaramel.laizysink.security

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.Executor
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM vault backed by AndroidKeyStore. Credential values are only returned to local callers. */
class ZeroTrustKeychain(context: Context) {
    private val preferences = context.getSharedPreferences("laizysink_vault", Context.MODE_PRIVATE)

    fun save(domain: String, username: String, password: CharArray) {
        try {
            preferences.edit().putString("$domain.username", encrypt(username.toByteArray())).putString("$domain.password", encrypt(String(password).toByteArray())).apply()
        } finally {
            password.fill('\u0000')
        }
    }

    fun maskedUsername(domain: String): String? = decrypt(preferences.getString("$domain.username", null))?.toString(Charsets.UTF_8)?.let { value ->
        val at = value.indexOf('@')
        if (at < 1) "••••" else "••••" + value.substring(at)
    }

    fun withCredentials(domain: String, inject: (String, CharArray) -> Unit): Boolean {
        val username = decrypt(preferences.getString("$domain.username", null))?.toString(Charsets.UTF_8) ?: return false
        val password = decrypt(preferences.getString("$domain.password", null))?.toString(Charsets.UTF_8) ?: return false
        val chars = password.toCharArray()
        return try { inject(username, chars); true } finally { chars.fill('\u0000') }
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain)
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String?): ByteArray? = try {
        val payload = encoded?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            doFinal(ciphertext)
        }
    } catch (_: Exception) { null }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    fun authenticate(activity: Activity, onAuthorized: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity)
            .setTitle("Unlock Laizysink Keychain")
            .setSubtitle("Credentials stay on this device")
            .setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> }
            .build()
        prompt.authenticate(CancellationSignal(), activity.mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) = onAuthorized()
        })
    }

    private companion object { const val KEY_ALIAS = "laizysink_credentials_v1" }
}

