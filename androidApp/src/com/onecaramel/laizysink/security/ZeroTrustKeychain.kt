package com.onecaramel.laizysink.security

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Outcome of a local vault operation. No outcome contains credential material. */
enum class VaultResult {
    AUTHORIZED,
    SAVED,
    NO_MATCH,
    CANCELLED,
    KEY_INVALIDATED,
    INVALID_DOMAIN,
    FAILED,
}

/** AES-GCM vault backed by a non-exportable Android Keystore key. */
class ZeroTrustKeychain(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val keyLock = Any()

    /**
     * Returns a display-only identity hint. The account name itself remains encrypted; only an
     * email's domain suffix (or a generic mask) is stored outside the ciphertext.
     */
    fun maskedUsername(domain: String): String? {
        val normalizedDomain = normalizeDomain(domain) ?: return null
        if (preferences.getString(recordKey(normalizedDomain), null) == null) return null
        return preferences.getString(previewKey(normalizedDomain), MASKED_IDENTITY)
    }

    /**
     * Requires an HTTPS origin whose host exactly matches the expected domain after canonicalizing
     * IDNs, case, a trailing dot, and a leading `www.`. Subdomains do not inherit credentials.
     */
    fun matchesDomain(expectedDomain: String, url: String): Boolean {
        val expected = normalizeDomain(expectedDomain) ?: return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null || (uri.port != -1 && uri.port != HTTPS_PORT)) return false
        val actual = normalizeDomain(uri.host ?: return false) ?: return false
        return actual == expected
    }

    /**
     * Prompts for biometrics using the actual decrypt Cipher as its CryptoObject. The temporary
     * password array is provided only to [use] and is cleared as soon as that synchronous callback
     * returns. The callback should only schedule local injection; its Boolean reports that
     * scheduling succeeded, not that the page accepted the fields.
     *
     * Call while [activity] is foregrounded. The method marshals prompt creation and all results to
     * the main thread. The CryptoObject and decrypted bytes never leave this class.
     */
    fun authenticateAndUseCredentials(
        activity: Activity,
        domain: String,
        use: (username: String, password: CharArray) -> Boolean,
        onResult: (VaultResult) -> Unit,
    ): CancellationSignal? {
        val result = ResultOnce(activity, onResult)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            result.complete(VaultResult.FAILED)
            return null
        }
        val normalizedDomain = normalizeDomain(domain)
        if (normalizedDomain == null) {
            result.complete(VaultResult.INVALID_DOMAIN)
            return null
        }

        val encodedRecord = preferences.getString(recordKey(normalizedDomain), null)
        if (encodedRecord == null) {
            result.complete(VaultResult.NO_MATCH)
            return null
        }

        val pending = try {
            prepareDecryption(normalizedDomain, encodedRecord)
        } catch (_: VaultKeyInvalidatedException) {
            discardInvalidatedVault()
            result.complete(VaultResult.KEY_INVALIDATED)
            return null
        } catch (_: KeyPermanentlyInvalidatedException) {
            discardInvalidatedVault()
            result.complete(VaultResult.KEY_INVALIDATED)
            return null
        } catch (_: Exception) {
            result.complete(VaultResult.FAILED)
            return null
        }

        return launchBiometricCipher(
            activity = activity,
            cipher = pending.cipher,
            onCancelled = { pending.ciphertext.fill(0) },
            onResult = result,
        ) { authorizedCipher ->
            val plaintext = try {
                authorizedCipher.doFinal(pending.ciphertext)
            } catch (_: KeyPermanentlyInvalidatedException) {
                discardInvalidatedVault()
                return@launchBiometricCipher VaultResult.KEY_INVALIDATED
            } catch (_: Exception) {
                return@launchBiometricCipher VaultResult.FAILED
            } finally {
                pending.ciphertext.fill(0)
            }

            val credentials = try {
                decodeCredentials(plaintext)
            } catch (_: Exception) {
                null
            } finally {
                plaintext.fill(0)
            } ?: return@launchBiometricCipher VaultResult.FAILED

            try {
                if (use(credentials.username, credentials.password)) {
                    VaultResult.AUTHORIZED
                } else {
                    VaultResult.FAILED
                }
            } catch (_: Exception) {
                VaultResult.FAILED
            } finally {
                credentials.password.fill('\u0000')
            }
        }
    }

    /**
     * Saves credentials only when the caller explicitly opts in. Encryption is a biometric-bound
     * AES-GCM operation; the supplied password array is cleared before the prompt is shown.
     */
    fun saveWithBiometrics(
        activity: Activity,
        domain: String,
        username: String,
        password: CharArray,
        onResult: (VaultResult) -> Unit,
    ): CancellationSignal? {
        val result = ResultOnce(activity, onResult)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            password.fill('\u0000')
            result.complete(VaultResult.FAILED)
            return null
        }
        val normalizedDomain = normalizeDomain(domain)
        if (normalizedDomain == null || username.isBlank() || password.isEmpty()) {
            password.fill('\u0000')
            result.complete(if (normalizedDomain == null) VaultResult.INVALID_DOMAIN else VaultResult.FAILED)
            return null
        }
        val preview = maskedIdentity(username)

        val plaintext = try {
            encodeCredentials(username, password)
        } catch (_: Exception) {
            result.complete(VaultResult.FAILED)
            return null
        } finally {
            password.fill('\u0000')
        }

        val cipher = try {
            val key = secretKey(createIfMissing = true)
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
                updateDomainAad(normalizedDomain)
            }
        } catch (_: VaultKeyInvalidatedException) {
            plaintext.fill(0)
            discardInvalidatedVault()
            result.complete(VaultResult.KEY_INVALIDATED)
            return null
        } catch (_: KeyPermanentlyInvalidatedException) {
            plaintext.fill(0)
            discardInvalidatedVault()
            result.complete(VaultResult.KEY_INVALIDATED)
            return null
        } catch (_: Exception) {
            plaintext.fill(0)
            result.complete(VaultResult.FAILED)
            return null
        }

        return launchBiometricCipher(
            activity = activity,
            cipher = cipher,
            onCancelled = { plaintext.fill(0) },
            onResult = result,
        ) { authorizedCipher ->
            val encrypted = try {
                authorizedCipher.doFinal(plaintext)
            } catch (_: KeyPermanentlyInvalidatedException) {
                discardInvalidatedVault()
                return@launchBiometricCipher VaultResult.KEY_INVALIDATED
            } catch (_: Exception) {
                return@launchBiometricCipher VaultResult.FAILED
            } finally {
                plaintext.fill(0)
            }

            val iv = authorizedCipher.iv ?: run {
                encrypted.fill(0)
                return@launchBiometricCipher VaultResult.FAILED
            }
            val envelope = iv + encrypted
            encrypted.fill(0)
            val encodedRecord = try {
                Base64.encodeToString(envelope, Base64.NO_WRAP)
            } finally {
                iv.fill(0)
                envelope.fill(0)
            }

            val stored = preferences.edit()
                .putString(recordKey(normalizedDomain), encodedRecord)
                .putString(previewKey(normalizedDomain), preview)
                .commit()
            if (stored) VaultResult.SAVED else VaultResult.FAILED
        }
    }

    private fun prepareDecryption(domain: String, encodedRecord: String): PendingDecryption {
        val payload = Base64.decode(encodedRecord, Base64.NO_WRAP)
        if (payload.size < IV_SIZE + GCM_TAG_BYTES) {
            payload.fill(0)
            throw IllegalArgumentException("Invalid vault record")
        }

        val iv = payload.copyOfRange(0, IV_SIZE)
        val ciphertext = payload.copyOfRange(IV_SIZE, payload.size)
        payload.fill(0)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(createIfMissing = false), GCMParameterSpec(GCM_TAG_BITS, iv))
                updateDomainAad(domain)
            }
            return PendingDecryption(cipher, ciphertext)
        } catch (error: Exception) {
            ciphertext.fill(0)
            throw error
        } finally {
            iv.fill(0)
        }
    }

    private fun decodeCredentials(plaintext: ByteArray): DecodedCredentials {
        val input = ByteBuffer.wrap(plaintext)
        if (input.remaining() < LENGTH_PREFIX_BYTES) throw IllegalArgumentException("Invalid vault payload")

        val usernameLength = input.int
        if (usernameLength <= 0 || usernameLength > MAX_FIELD_BYTES || input.remaining() < usernameLength + Int.SIZE_BYTES) {
            throw IllegalArgumentException("Invalid vault payload")
        }
        val usernameBytes = ByteArray(usernameLength)
        input.get(usernameBytes)

        val passwordLength = input.int
        if (passwordLength <= 0 || passwordLength > MAX_FIELD_BYTES || input.remaining() != passwordLength) {
            usernameBytes.fill(0)
            throw IllegalArgumentException("Invalid vault payload")
        }
        val passwordBytes = ByteArray(passwordLength)
        input.get(passwordBytes)

        return try {
            DecodedCredentials(decodeString(usernameBytes), decodeChars(passwordBytes))
        } finally {
            usernameBytes.fill(0)
            passwordBytes.fill(0)
        }
    }

    private fun encodeCredentials(username: String, password: CharArray): ByteArray {
        val usernameBytes = username.toByteArray(Charsets.UTF_8)
        var passwordBytes = ByteArray(0)
        var encodedPassword: ByteBuffer? = null
        return try {
            val encoded = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(password))
            encodedPassword = encoded
            passwordBytes = ByteArray(encoded.remaining())
            encoded.get(passwordBytes)
            if (usernameBytes.isEmpty() || usernameBytes.size > MAX_FIELD_BYTES || passwordBytes.isEmpty() || passwordBytes.size > MAX_FIELD_BYTES) {
                throw IllegalArgumentException("Credential is too long or empty")
            }
            ByteBuffer.allocate(LENGTH_PREFIX_BYTES + usernameBytes.size + passwordBytes.size)
                .putInt(usernameBytes.size)
                .put(usernameBytes)
                .putInt(passwordBytes.size)
                .put(passwordBytes)
                .array()
        } finally {
            usernameBytes.fill(0)
            passwordBytes.fill(0)
            encodedPassword?.takeIf { it.hasArray() }?.array()?.fill('\u0000')
        }
    }

    private fun decodeString(bytes: ByteArray): String {
        val decoded = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        val chars = CharArray(decoded.remaining())
        decoded.get(chars)
        return try {
            String(chars)
        } finally {
            chars.fill('\u0000')
            if (decoded.hasArray()) decoded.array().fill('\u0000')
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

    private fun Cipher.updateDomainAad(domain: String) {
        val aad = domain.toByteArray(Charsets.UTF_8)
        try {
            updateAAD(aad, 0, aad.size)
        } finally {
            aad.fill(0)
        }
    }

    private fun secretKey(createIfMissing: Boolean): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }

        if (!createIfMissing) throw VaultKeyInvalidatedException()
        if (hasStoredRecords()) throw VaultKeyInvalidatedException()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        generator.init(builder.build())
        generator.generateKey()
    }

    @SuppressLint("NewApi")
    private fun launchBiometricCipher(
        activity: Activity,
        cipher: Cipher,
        onCancelled: () -> Unit,
        onResult: ResultOnce,
        useAuthorizedCipher: (Cipher) -> VaultResult,
    ): CancellationSignal {
        val cancellationSignal = CancellationSignal()
        val cleanup = AtomicBoolean(false)
        fun cleanPendingMaterial() {
            if (cleanup.compareAndSet(false, true)) onCancelled()
        }
        cancellationSignal.setOnCancelListener {
            cleanPendingMaterial()
            onResult.complete(VaultResult.CANCELLED)
        }

        val launch = Runnable {
            if (cancellationSignal.isCanceled) {
                onResult.complete(VaultResult.CANCELLED)
                return@Runnable
            }
            if (activity.isFinishing || activity.isDestroyed) {
                cleanPendingMaterial()
                onResult.complete(VaultResult.FAILED)
                return@Runnable
            }

            try {
                val promptBuilder = android.hardware.biometrics.BiometricPrompt.Builder(activity)
                    .setTitle("Unlock Laizysink Keychain")
                    .setSubtitle("Credentials stay on this device")
                    .setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> cancellationSignal.cancel() }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    promptBuilder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                }
                val prompt = promptBuilder.build()
                val cryptoObject = android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher)
                prompt.authenticate(
                    cryptoObject,
                    cancellationSignal,
                    activity.mainExecutor,
                    object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            authenticationResult: android.hardware.biometrics.BiometricPrompt.AuthenticationResult,
                        ) {
                            if (cancellationSignal.isCanceled) {
                                cleanPendingMaterial()
                                onResult.complete(VaultResult.CANCELLED)
                                return
                            }
                            val authorizedCipher = authenticationResult.cryptoObject?.cipher
                            if (authorizedCipher == null) {
                                cleanPendingMaterial()
                                onResult.complete(VaultResult.FAILED)
                                return
                            }
                            val outcome = try {
                                useAuthorizedCipher(authorizedCipher)
                            } catch (_: KeyPermanentlyInvalidatedException) {
                                discardInvalidatedVault()
                                VaultResult.KEY_INVALIDATED
                            } catch (_: Exception) {
                                VaultResult.FAILED
                            }
                            cleanPendingMaterial()
                            onResult.complete(outcome)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            cleanPendingMaterial()
                            onResult.complete(if (isCancellationError(errorCode)) VaultResult.CANCELLED else VaultResult.FAILED)
                        }
                    },
                )
            } catch (_: KeyPermanentlyInvalidatedException) {
                cleanPendingMaterial()
                discardInvalidatedVault()
                onResult.complete(VaultResult.KEY_INVALIDATED)
            } catch (_: Exception) {
                cleanPendingMaterial()
                onResult.complete(VaultResult.FAILED)
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) launch.run() else activity.runOnUiThread(launch)
        return cancellationSignal
    }

    private fun normalizeDomain(value: String): String? {
        val host = value.trim().removeSuffix(".").lowercase(Locale.ROOT)
        if (host.isEmpty() || host.any { it.isWhitespace() } || host.any { it in ":/@?#" }) return null
        val canonical = runCatching { java.net.IDN.toASCII(host, java.net.IDN.USE_STD3_ASCII_RULES) }
            .getOrNull()
            ?.removePrefix("www.")
            ?: return null
        if (canonical.isEmpty() || canonical.length > MAX_DOMAIN_LENGTH) return null
        val labels = canonical.split('.')
        if (labels.any { label ->
                label.isEmpty() || label.length > MAX_LABEL_LENGTH ||
                    label.first() == '-' || label.last() == '-' ||
                    label.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-') }
            }) return null
        return canonical
    }

    private fun maskedIdentity(username: String): String {
        val emailDomain = username.substringAfter('@', missingDelimiterValue = "")
        return if (emailDomain.isBlank()) MASKED_IDENTITY else "$MASKED_IDENTITY@$emailDomain"
    }

    private fun hasStoredRecords(): Boolean = preferences.all.keys.any { it.startsWith(RECORD_PREFIX) }

    private fun discardInvalidatedVault() {
        synchronized(keyLock) {
            preferences.edit().clear().commit()
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun recordKey(domain: String) = "$RECORD_PREFIX$domain"

    private fun previewKey(domain: String) = "$PREVIEW_PREFIX$domain"

    private fun isCancellationError(errorCode: Int): Boolean =
        errorCode == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED ||
            errorCode == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
            errorCode == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON

    private class ResultOnce(private val activity: Activity, private val callback: (VaultResult) -> Unit) {
        private val delivered = AtomicBoolean(false)

        fun complete(result: VaultResult) {
            val invoke = Runnable {
                if (delivered.compareAndSet(false, true)) callback(result)
            }
            if (Looper.myLooper() == Looper.getMainLooper()) invoke.run() else activity.runOnUiThread(invoke)
        }
    }

    private data class PendingDecryption(val cipher: Cipher, val ciphertext: ByteArray)

    private data class DecodedCredentials(val username: String, val password: CharArray)

    private class VaultKeyInvalidatedException : Exception()

    private companion object {
        const val PREFERENCES_NAME = "laizysink_vault"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "laizysink_credentials_v2_biometric"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val RECORD_PREFIX = "record."
        const val PREVIEW_PREFIX = "preview."
        const val MASKED_IDENTITY = "••••"
        const val HTTPS_PORT = 443
        const val IV_SIZE = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / Byte.SIZE_BITS
        const val LENGTH_PREFIX_BYTES = 2 * Int.SIZE_BYTES
        const val MAX_FIELD_BYTES = 16 * 1024
        const val MAX_DOMAIN_LENGTH = 253
        const val MAX_LABEL_LENGTH = 63
    }
}
