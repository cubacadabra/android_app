package dev.andrewarrow.cubacadabra.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ModerationService(
    private val playerId: String,
    private val accessToken: String?,
) {
    suspend fun fetchBlockedPlayerIds(): List<String> = request("moderation/blocks", "GET")
        .let { response ->
            if (response.statusCode !in 200..299) throw ModerationException(response.statusCode)
            val ids = JSONObject(response.body).optJSONArray("user_ids") ?: return@let emptyList()
            (0 until ids.length()).mapNotNull { ids.optString(it).takeIf(String::isNotBlank) }
        }

    suspend fun blockPlayer(userId: String) {
        val response = request(
            "moderation/blocks",
            "POST",
            JSONObject().put("user_id", userId),
        )
        if (response.statusCode !in 200..299) throw ModerationException(response.statusCode)
    }

    suspend fun unblockPlayer(userId: String) {
        val response = request("moderation/blocks/${java.net.URLEncoder.encode(userId, "UTF-8")}", "DELETE")
        if (response.statusCode !in 200..299) throw ModerationException(response.statusCode)
    }

    private suspend fun request(path: String, method: String, body: JSONObject? = null): Response = withContext(Dispatchers.IO) {
        val connection = (URL(ClientConfiguration.backendApiUrl.trimEnd('/') + "/" + path)
            .openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                requestMethod = method
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Cubacadabra-Player-ID", playerId)
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
            Response(statusCode, stream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private data class Response(val statusCode: Int, val body: String)
}

class ModerationException(val statusCode: Int) : Exception()
