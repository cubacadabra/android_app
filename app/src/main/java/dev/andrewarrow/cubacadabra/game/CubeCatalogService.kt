package dev.andrewarrow.cubacadabra.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class CubeCatalogService {
    private companion object {
        const val MAXIMUM_RESPONSE_BYTES = 512 * 1024
        val GAME_ID_PATTERN = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    }

    suspend fun firstPage(pageSize: Int = 20): List<GameCatalogEntry> = withContext(Dispatchers.IO) {
        val backendUrl = URL(ClientConfiguration.backendApiUrl.trimEnd('/') + "/")
        val requestUrl = URL(backendUrl, "cubes?page=1&page_size=$pageSize")
        val connection = (requestUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }

        connection.connect()
        try {
            if (connection.responseCode !in 200..299) {
                throw CubeCatalogException.HttpFailure(connection.responseCode)
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size > MAXIMUM_RESPONSE_BYTES) throw CubeCatalogException.InvalidResponse()

            val cubes = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("cubes")
            List(cubes.length()) { index ->
                val cube = cubes.getJSONObject(index)
                cube.getInt("id")
                cube.getInt("fileCount")
                val cubeID = cube.getString("cubeId")
                val version = cube.getString("version")
                val displayName = cube.getString("displayName")
                val packagePath = cube.getString("packagePath")
                val packageUrl = URL(backendUrl, packagePath)

                if (!GAME_ID_PATTERN.matches(cubeID) ||
                    !packagePath.startsWith("/cubes/") ||
                    packageUrl.protocol != backendUrl.protocol ||
                    !packageUrl.host.equals(backendUrl.host, ignoreCase = true) ||
                    packageUrl.port != backendUrl.port ||
                    !packageUrl.path.startsWith("/cubes/")
                ) {
                    throw CubeCatalogException.InvalidResponse()
                }

                GameCatalogEntry(
                    id = cubeID,
                    title = displayName,
                    subtitle = "$cubeID · v$version",
                    version = version,
                    packageBaseUrl = packageUrl.toString(),
                )
            }
        } catch (error: CubeCatalogException) {
            throw error
        } catch (_: Exception) {
            throw CubeCatalogException.InvalidResponse()
        } finally {
            connection.disconnect()
        }
    }
}

sealed class CubeCatalogException(message: String) : Exception(message) {
    class HttpFailure(statusCode: Int) : CubeCatalogException(
        "The cube catalog server returned HTTP $statusCode.",
    )

    class InvalidResponse : CubeCatalogException("The cube catalog response was invalid.")
}
