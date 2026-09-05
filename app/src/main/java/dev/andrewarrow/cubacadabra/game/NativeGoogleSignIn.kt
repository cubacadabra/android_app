package dev.andrewarrow.cubacadabra.game

import android.app.Activity
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import dev.andrewarrow.cubacadabra.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NativeGoogleSignInService(context: Context) {
    private companion object {
        const val TAG = "NativeGoogleSignIn"
    }

    private val applicationContext = context.applicationContext
    private val clientId = applicationContext.getString(R.string.google_client_id)

    suspend fun signIn(activity: Activity): String {
        val credentialManager = CredentialManager.create(activity)
        val result = try {
            credentialManager.getCredential(
                context = activity,
                request = credentialRequest(filterByAuthorizedAccounts = true),
            )
        } catch (error: NoCredentialException) {
            Log.d(TAG, "No authorized Google credential; retrying with account selection", error)
            try {
                credentialManager.getCredential(
                    context = activity,
                    request = credentialRequest(filterByAuthorizedAccounts = false),
                )
            } catch (_: NoCredentialException) {
                Log.d(TAG, "No Google credential from account selection; retrying button flow")
                credentialManager.getCredential(
                    context = activity,
                    request = signInWithGoogleRequest(),
                )
            }
        } catch (error: GetCredentialCancellationException) {
            Log.w(
                TAG,
                "Credential Manager cancelled Google sign-in " +
                    "type=${error::class.java.name} message=${error.message}",
                error,
            )
            throw AppAuthException.Cancelled
        }

        val credential = result.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw AppAuthException.InvalidResponse()
        }

        return try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (error: GoogleIdTokenParsingException) {
            Log.w(TAG, "Google returned an invalid ID token credential", error)
            throw AppAuthException.InvalidResponse(error)
        }
    }

    suspend fun signOut(activity: Activity) {
        try {
            CredentialManager.create(activity).clearCredentialState(
                ClearCredentialStateRequest(),
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Could not clear Google credential state", error)
        }
    }

    private fun credentialRequest(filterByAuthorizedAccounts: Boolean): GetCredentialRequest {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setServerClientId(clientId)
            .build()
        return GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
    }

    private fun signInWithGoogleRequest(): GetCredentialRequest {
        val option = GetSignInWithGoogleOption.Builder(clientId)
            .build()
        return GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
    }
}

data class AppAuthUser(
    val id: String,
    val email: String?,
    val name: String,
    val dateOfBirth: String?,
    val username: String?,
)

data class AppAuthResult(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresIn: Int,
    val user: AppAuthUser,
    val browserHandoffCode: String?,
)

data class AppProfileUpdateResult(val user: AppAuthUser, val age: Int?)

sealed class AppProfileException : Exception() {
    data object Unauthorized : AppProfileException()
    data class Server(val statusCode: Int, val code: String?) : AppProfileException()
}

sealed class AppAuthException : Exception() {
    data object Cancelled : AppAuthException()
    class InvalidResponse(cause: Throwable? = null) : AppAuthException() {
        init {
            cause?.let { initCause(it) }
        }
    }
    data object Unavailable : AppAuthException()
    data class Server(val statusCode: Int) : AppAuthException()
}

private class AppTokenStore(context: Context) {
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

class AppAuthenticationService(context: Context) {
    private companion object {
        const val TAG = "AppAuthenticationService"
        const val MAX_RESPONSE_BYTES = 256 * 1024
    }

    private val tokenStore = AppTokenStore(context)

    suspend fun restore(): AppAuthResult? {
        val tokens = tokenStore.load() ?: return null
        return try {
            authenticatedResult(tokens.first)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            try {
                refresh(tokens.second)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                tokenStore.clear()
                null
            }
        }
    }

    suspend fun authenticateGoogle(credential: String): AppAuthResult =
        tokenRequest("auth/app/google", org.json.JSONObject().put("credential", credential))

    suspend fun createBrowserHandoffCode(): String {
        val tokens = tokenStore.load() ?: throw AppAuthException.Unavailable
        val response = request("auth/browser/authorize", "POST", null, tokens.first)
        if (response.statusCode != 200) throw AppAuthException.Server(response.statusCode)
        return runCatching { org.json.JSONObject(response.body).getString("browser_code") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: throw AppAuthException.InvalidResponse()
    }

    suspend fun saveUsername(username: String): AppProfileUpdateResult {
        val accessToken = tokenStore.load()?.first ?: throw AppProfileException.Unauthorized
        val response = request(
            "auth/username",
            "POST",
            org.json.JSONObject().put("username", username),
            accessToken,
        )
        if (response.statusCode !in 200..299) {
            val code = runCatching { org.json.JSONObject(response.body).optString("error").takeIf { it.isNotEmpty() } }
                .getOrNull()
            throw AppProfileException.Server(response.statusCode, code)
        }
        return runCatching {
            val json = org.json.JSONObject(response.body)
            AppProfileUpdateResult(
                user = parseUser(json.getJSONObject("user")),
                age = if (json.has("age") && !json.isNull("age")) json.getInt("age") else null,
            )
        }.getOrElse { throw AppAuthException.InvalidResponse(it) }
    }

    fun clearTokens() = tokenStore.clear()

    private suspend fun authenticatedResult(accessToken: String): AppAuthResult {
        val response = request("auth/me", "GET", null, accessToken)
        if (response.statusCode != 200) throw AppAuthException.Server(response.statusCode)
        return AppAuthResult(accessToken, "", 0, parseUser(org.json.JSONObject(response.body).getJSONObject("user")), null)
    }

    private suspend fun refresh(refreshToken: String): AppAuthResult =
        tokenRequest("auth/app/refresh", org.json.JSONObject().put("refresh_token", refreshToken))

    private suspend fun tokenRequest(path: String, body: org.json.JSONObject): AppAuthResult {
        val response = request(path, "POST", body, null)
        if (response.statusCode != 200) throw AppAuthException.Server(response.statusCode)
        val json = try {
            org.json.JSONObject(response.body)
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "Invalid auth response path=$path status=${response.statusCode} " +
                    "bytes=${response.body.toByteArray(Charsets.UTF_8).size}",
                error,
            )
            throw AppAuthException.InvalidResponse(error)
        }
        val result = try {
            AppAuthResult(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                accessTokenExpiresIn = json.optInt("expires_in", 0),
                user = parseUser(json.getJSONObject("user")),
                browserHandoffCode = json.optString("browser_code").takeIf { it.isNotEmpty() },
            )
        } catch (error: Throwable) {
            val keys = json.keys().asSequence().toList().sorted().joinToString(",")
            Log.w(TAG, "Invalid auth response path=$path status=${response.statusCode} keys=$keys", error)
            throw AppAuthException.InvalidResponse(error)
        }
        try {
            tokenStore.save(result.accessToken, result.refreshToken)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not persist auth tokens after successful response path=$path", error)
            throw AppAuthException.InvalidResponse(error)
        }
        return result
    }

    private fun parseUser(json: org.json.JSONObject) = AppAuthUser(
        id = json.getString("id"),
        email = json.optString("email").takeIf { it.isNotEmpty() },
        name = json.getString("name"),
        dateOfBirth = json.optString("dob").takeIf { it.isNotEmpty() },
        username = json.optString("username").takeIf { it.isNotEmpty() },
    )

    private suspend fun request(
        path: String,
        method: String,
        body: org.json.JSONObject?,
        accessToken: String?,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val connection = (java.net.URL(ClientConfiguration.backendApiUrl.trimEnd('/') + "/" + path)
            .openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                requestMethod = method
                setRequestProperty("Accept", "application/json")
                accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
        try {
            body?.let { connection.outputStream.use { stream -> stream.write(it.toString().toByteArray(Charsets.UTF_8)) } }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (bytes.size > MAX_RESPONSE_BYTES) throw AppAuthException.InvalidResponse()
            HttpResponse(statusCode, String(bytes, Charsets.UTF_8))
        } catch (error: AppAuthException) {
            throw error
        } catch (_: java.io.IOException) {
            throw AppAuthException.Unavailable
        } finally {
            connection.disconnect()
        }
    }

    private data class HttpResponse(val statusCode: Int, val body: String)
}
