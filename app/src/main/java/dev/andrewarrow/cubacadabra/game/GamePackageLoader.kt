package dev.andrewarrow.cubacadabra.game

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class GamePackageLoader(context: Context) {
    private companion object {
        const val TAG = "GamePackageLoader"
        // The generated Luau package format changed with the Build Together
        // UI. Keep the old cache from overriding the corrected bundle after
        // an app update, matching the iOS loader's versioned cache keys.
        const val CACHED_MANIFEST_KEY = "manifest.v2"
        const val CACHED_SCRIPT_KEY = "script.v2"
    }

    private val applicationContext = context.applicationContext
    private val preferences = context.getSharedPreferences("game-package", Context.MODE_PRIVATE)
    private val maximumManifestBytes = 512 * 1024
    private val maximumScriptBytes = 512 * 1024

    suspend fun load(gameID: String = "first-game"): LoadedGamePackage = withContext(Dispatchers.IO) {
        val cached = cachedPackage(gameID)
        if (cached != null) {
            Log.d(TAG, "package load game=$gameID selected=cached")
            return@withContext cached
        }
        val bundled = runCatching { loadBundledPackage(gameID) }.getOrNull()
        if (bundled != null) {
            Log.d(TAG, "package load game=$gameID selected=bundled")
            return@withContext bundled
        }
        val base = remoteBaseUrl(gameID)
        val downloaded = makePackage(
            fetch(URL(base + "manifest.json"), maximumManifestBytes),
            fetch(URL(base + "game.luau"), maximumScriptBytes),
        )
        preferences.edit()
            .putString(manifestKey(gameID), downloaded.manifest)
            .putString(scriptKey(gameID), downloaded.script)
            .apply()
        Log.d(TAG, "package load game=$gameID selected=remote")
        downloaded
    }

    suspend fun refreshPackage(gameID: String = "first-game") = withContext(Dispatchers.IO) {
        runCatching {
            val base = remoteBaseUrl(gameID)
            val downloadedPackage = makePackage(
                fetch(URL(base + "manifest.json"), maximumManifestBytes),
                fetch(URL(base + "game.luau"), maximumScriptBytes),
            )
            preferences.edit()
                .putString(manifestKey(gameID), downloadedPackage.manifest)
                .putString(scriptKey(gameID), downloadedPackage.script)
                .apply()
        }.onSuccess {
            Log.d(TAG, "package refresh succeeded")
        }.onFailure {
            Log.w(TAG, "package refresh failed: ${it.message}")
        }
    }

    private fun cachedPackage(gameID: String): LoadedGamePackage? {
        val manifest = preferences.getString(manifestKey(gameID), null)
            ?: if (gameID == "first-game") preferences.getString(CACHED_MANIFEST_KEY, null).orEmpty() else ""
        val script = preferences.getString(scriptKey(gameID), null)
            ?: if (gameID == "first-game") preferences.getString(CACHED_SCRIPT_KEY, null).orEmpty() else ""
        if (manifest.isEmpty() || script.isEmpty()) return null
        return runCatching {
            makePackage(manifest.toByteArray(Charsets.UTF_8), script.toByteArray(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun loadBundledPackage(gameID: String): LoadedGamePackage {
        val directory = if (gameID == "first-game") "game-package" else "game-package-$gameID"
        val manifestPath = "$directory/manifest.json"
        val scriptPath = "$directory/game.luau"
        val manifestBytes = applicationContext.assets.open(manifestPath).use { it.readBytes() }
        val scriptBytes = applicationContext.assets.open(scriptPath).use { it.readBytes() }
        return makePackage(manifestBytes, scriptBytes)
    }

    private fun remoteBaseUrl(gameID: String): String {
        val base = ClientConfiguration.gameBaseUrl.trimEnd('/')
        val authorityStart = base.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val pathStart = base.indexOf('/', authorityStart)
        if (pathStart < 0) return "$base/$gameID/"
        val parentPathEnd = base.lastIndexOf('/')
        return "${base.substring(0, parentPathEnd)}/$gameID/"
    }

    private fun manifestKey(gameID: String) = "manifest.v3.$gameID"
    private fun scriptKey(gameID: String) = "script.v3.$gameID"

    private fun makePackage(manifestBytes: ByteArray, scriptBytes: ByteArray): LoadedGamePackage {
        val manifest = decodeUtf8(manifestBytes)
        val script = decodeUtf8(scriptBytes)
        if (script.isEmpty()) throw GamePackageException("The Luau game script is empty.")
        val packageData = parsePackage(JSONObject(manifest))
        if (packageData.worldDefinition(packageData.initialWorld) == null) {
            throw GamePackageException("The game world \"${packageData.initialWorld}\" was not found.")
        }
        return LoadedGamePackage(packageData, manifest, script)
    }

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun fetch(url: URL, maximumBytes: Int): ByteArray {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "GET"
        }
        connection.connect()
        try {
            if (connection.responseCode !in 200..299) throw GamePackageException("The game package server returned HTTP ${connection.responseCode}.")
            return connection.inputStream.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size > maximumBytes) throw GamePackageException("The game package file is too large.")
                bytes
            }
        } finally {
            connection.disconnect()
        }
    }
}
