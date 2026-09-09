package dev.andrewarrow.cubacadabra.game

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    suspend fun authenticateEmail(email: String, password: String): AppAuthResult =
        tokenRequest(
            "auth/app/email",
            org.json.JSONObject().put("email", email).put("password", password),
        )

    suspend fun createBrowserHandoffCode(): String {
        val tokens = tokenStore.load() ?: throw AppAuthException.Unavailable
        val response = request("auth/browser/authorize", "POST", null, tokens.first)
        if (response.statusCode != 200) throw AppAuthException.Server(response.statusCode)
        return runCatching { org.json.JSONObject(response.body).getString("browser_code") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: throw AppAuthException.InvalidResponse()
    }

    // Called while draining effects on the main thread, before launching IO.
    fun appAccessToken(): String? = tokenStore.load()?.first

    suspend fun performAppRequest(effect: AppHttpEffect, accessToken: String): HttpResponse =
        requestRaw(effect.path, effect.method, effect.body, accessToken)

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
        bodyID = json.optString("body_id").takeIf { it.isNotEmpty() },
    )

    private suspend fun request(
        path: String,
        method: String,
        body: org.json.JSONObject?,
        accessToken: String?,
    ): HttpResponse = requestRaw(path, method, body?.toString(), accessToken)

    private suspend fun requestRaw(path: String, method: String, body: String?, accessToken: String?): HttpResponse = withContext(Dispatchers.IO) {
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
            body?.let { connection.outputStream.use { stream -> stream.write(it.toByteArray(Charsets.UTF_8)) } }
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

    data class HttpResponse(val statusCode: Int, val body: String)
}
