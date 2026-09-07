package dev.andrewarrow.cubacadabra.game

import android.content.Context
import android.util.Log
import dev.andrewarrow.cubacadabra.BuildConfig
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
        const val CACHE_VERSION = "v4"
        val AUDIO_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")
        val AUDIO_PATH_PATTERN = Regex(
            "^assets/(?:[A-Za-z0-9_-][A-Za-z0-9._-]*/)*[A-Za-z0-9_-][A-Za-z0-9._-]*\\.wav$",
            RegexOption.IGNORE_CASE,
        )
    }

    private val applicationContext = context.applicationContext
    private val preferences = context.getSharedPreferences("game-package", Context.MODE_PRIVATE)
    private val maximumManifestBytes = 512 * 1024
    private val maximumScriptBytes = 512 * 1024

    suspend fun load(gameID: String = "first-game"): LoadedGamePackage = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) {
            // The Android build assembles the sibling game project into the
            // APK. Prefer that package during local development so source
            // edits are never hidden by an older cached package.
            return@withContext loadBundledPackage(gameID).also {
                Log.d(TAG, "package load game=$gameID selected=bundled-debug")
            }
        }
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
            audioBaseUrl = base,
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
                audioBaseUrl = base,
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
        val manifest = preferences.getString(manifestKey(gameID), null).orEmpty()
        val script = preferences.getString(scriptKey(gameID), null).orEmpty()
        if (manifest.isEmpty() || script.isEmpty()) return null
        return runCatching {
            makePackage(
                manifest.toByteArray(Charsets.UTF_8),
                script.toByteArray(Charsets.UTF_8),
                audioBaseUrl = remoteBaseUrl(gameID),
            )
        }.getOrNull()
    }

    private fun loadBundledPackage(gameID: String): LoadedGamePackage {
        val directory = if (gameID == "first-game") "game-package" else "game-package-$gameID"
        val manifestPath = "$directory/manifest.json"
        val scriptPath = "$directory/game.luau"
        val manifestBytes = applicationContext.assets.open(manifestPath).use { it.readBytes() }
        val scriptBytes = applicationContext.assets.open(scriptPath).use { it.readBytes() }
        return makePackage(manifestBytes, scriptBytes, bundledDirectory = directory)
    }

    private fun remoteBaseUrl(gameID: String): String {
        val base = ClientConfiguration.gameBaseUrl.trimEnd('/')
        val authorityStart = base.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val pathStart = base.indexOf('/', authorityStart)
        if (pathStart < 0) return "$base/$gameID/"
        val parentPathEnd = base.lastIndexOf('/')
        return "${base.substring(0, parentPathEnd)}/$gameID/"
    }

    private fun manifestKey(gameID: String) = "manifest.$CACHE_VERSION.$gameID"
    private fun scriptKey(gameID: String) = "script.$CACHE_VERSION.$gameID"

    private fun makePackage(
        manifestBytes: ByteArray,
        scriptBytes: ByteArray,
        audioBaseUrl: String? = null,
        bundledDirectory: String? = null,
    ): LoadedGamePackage {
        val manifest = decodeUtf8(manifestBytes)
        val script = decodeUtf8(scriptBytes)
        if (script.isEmpty()) throw GamePackageException("The Luau game script is empty.")
        val packageData = parsePackage(JSONObject(manifest))
        if (packageData.worldDefinition(packageData.initialWorld) == null) {
            throw GamePackageException("The game world \"${packageData.initialWorld}\" was not found.")
        }
        val audioAssets = normalizeAudioAssets(packageData.assets?.audio, audioBaseUrl, bundledDirectory)
        return LoadedGamePackage(packageData, manifest, script, audioAssets)
    }

    private fun normalizeAudioAssets(
        definitions: Map<String, GameAudioAssetDefinition>?,
        audioBaseUrl: String?,
        bundledDirectory: String?,
    ): Map<String, LoadedGameAudioAsset> = buildMap {
        definitions.orEmpty().forEach { (id, definition) ->
            if (!AUDIO_ID_PATTERN.matches(id) || !AUDIO_PATH_PATTERN.matches(definition.path)) {
                throw GamePackageException("The game audio asset \"$id\" is invalid.")
            }
            if (!definition.volume.isFinite() || definition.volume !in 0f..1f) {
                throw GamePackageException("The game audio asset \"$id\" is invalid.")
            }
            when {
                bundledDirectory != null -> put(
                    id,
                    LoadedGameAudioAsset(
                        volume = definition.volume,
                        bundledAssetPath = "$bundledDirectory/${definition.path}",
                    ),
                )
                audioBaseUrl != null -> {
                    val url = runCatching { URL(audioBaseUrl + definition.path) }.getOrNull()
                    if (url == null || url.protocol !in setOf("http", "https") || url.host.isNullOrEmpty()) {
                        throw GamePackageException("The game audio asset \"$id\" is invalid.")
                    }
                    put(id, LoadedGameAudioAsset(volume = definition.volume, url = url.toString()))
                }
                else -> throw GamePackageException("The game audio asset \"$id\" is invalid.")
            }
        }
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
