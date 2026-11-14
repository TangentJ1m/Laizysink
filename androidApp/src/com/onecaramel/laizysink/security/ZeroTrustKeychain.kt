package com.onecaramel.laizysink.security

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM vault backed by AndroidKeyStore. Credential values are only returned to local callers. */
class ZeroTrustKeychain(context: Context) {
    private val preferences = context.getSharedPreferences("laizysink_vault", Context.MODE_PRIVATE)

    fun save(domain: String, username: String, password: CharArray) {
        try {
            preferences.edit()
                .putString("$domain.username", encrypt(username.toByteArray(Charsets.UTF_8)))
                .putString("$domain.password", encrypt(password))
                .apply()
        } finally {
            password.fill('\u0000')
        }
    }

    fun maskedUsername(domain: String): String? {
        val bytes = decrypt(preferences.getString("$domain.username", null)) ?: return null
        return try {
            val value = bytes.toString(Charsets.UTF_8)
            val at = value.indexOf('@')
            if (at < 1) "••••" else "••••" + value.substring(at)
        } finally {
            bytes.fill(0)
        }
    }

    fun withCredentials(domain: String, inject: (String, CharArray) -> Unit): Boolean {
        val usernameBytes = decrypt(preferences.getString("$domain.username", null)) ?: return false
        val passwordBytes = decrypt(preferences.getString("$domain.password", null)) ?: run {
            usernameBytes.fill(0)
            return false
        }
        val username = usernameBytes.toString(Charsets.UTF_8)
        val passwordChars = try {
            decodeChars(passwordBytes)
        } catch (_: Exception) {
            usernameBytes.fill(0)
            passwordBytes.fill(0)
            return false
        }
        usernameBytes.fill(0)
        passwordBytes.fill(0)
        return try {
            inject(username, passwordChars)
            true
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun encrypt(plain: ByteArray): String {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(plain)
            Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        } finally {
            plain.fill(0)
        }
    }

    private fun encrypt(chars: CharArray): String {
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        return try {
            encrypt(bytes)
        } finally {
            bytes.fill(0)
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    private fun decodeChars(bytes: ByteArray): CharArray {
        val decoded = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        val chars = CharArray(decoded.remaining())
        decoded.get(chars)
        if (decoded.hasArray()) decoded.array().fill('\u0000')
        return chars
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

    fun authenticate(activity: Activity, onResult: (Boolean) -> Unit): CancellationSignal? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onResult(false)
            return null
        }
        val cancellationSignal = CancellationSignal()
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity)
            .setTitle("Unlock Laizysink Keychain")
            .setSubtitle("Credentials stay on this device")
            .setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> }
            .build()
        return try {
            prompt.authenticate(cancellationSignal, activity.mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) = onResult(true)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
            })
            cancellationSignal
        } catch (_: Exception) {
            onResult(false)
            null
        }
    }

    private companion object { const val KEY_ALIAS = "laizysink_credentials_v1" }
}

