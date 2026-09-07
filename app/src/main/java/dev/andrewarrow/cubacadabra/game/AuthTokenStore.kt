package dev.andrewarrow.cubacadabra.game

import android.content.Context
import android.util.Base64

internal class AppTokenStore(context: Context) {
    private companion object {
        const val PREFERENCES = "cubacadabra.auth"
        const val TOKEN_KEY = "encrypted_tokens"
        const val KEY_ALIAS = "cubacadabra.app.auth"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): Pair<String, String>? = runCatching {
        val encoded = preferences.getString(TOKEN_KEY, null) ?: return null
        val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
        require(encrypted.size > GCM_IV_BYTES)
        val iv = encrypted.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = encrypted.copyOfRange(GCM_IV_BYTES, encrypted.size)
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION).apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val json = org.json.JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        json.getString("access_token") to json.getString("refresh_token")
    }.getOrNull()

    fun save(accessToken: String, refreshToken: String) {
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION).apply {
            // Android Keystore requires GCM encryption to generate its own IV.
            init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        }
        val iv = cipher.iv
        val json = org.json.JSONObject().apply {
            put("access_token", accessToken)
            put("refresh_token", refreshToken)
        }
        val encrypted = iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        preferences.edit().putString(TOKEN_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun clear() {
        preferences.edit().remove(TOKEN_KEY).apply()
    }

    private fun key(): java.security.Key {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val generator = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
