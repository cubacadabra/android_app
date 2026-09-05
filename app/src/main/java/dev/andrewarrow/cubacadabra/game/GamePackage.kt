package dev.andrewarrow.cubacadabra.game

import android.content.Context
import android.util.Log
import dev.andrewarrow.cubacadabra.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.net.HttpURLConnection
import java.net.URL

data class GamePackage(
    val startWorld: String,
    val launch: LaunchRoute,
    val scene: SceneDefinition,
    val palette: Map<String, String>,
    val world: WorldSettings,
    val launchPads: List<LaunchPadDefinition>,
    val blocks: List<BlockDefinition>,
    val worlds: Map<String, WorldDefinition>,
) {
    fun worldDefinition(id: String): WorldDefinition? = if (id == "lobby") {
        WorldDefinition(scene, palette, world, launchPads, blocks)
    } else {
        worlds[id]
    }

    fun runtimeWorldIds(): List<String> = listOf("lobby") + worlds.keys.sorted()
}

data class GameCatalogEntry(
    val id: String,
    val title: String,
    val subtitle: String,
)

object GameCatalog {
    val available = listOf(
        GameCatalogEntry("first-game", "First Game", "Build together in the clearing"),
        GameCatalogEntry("second-game", "Second Game", "Drop signals in the relay yard"),
    )
}

data class LaunchRoute(val destinationWorld: String, val authoritative: Boolean = false)
data class SceneDefinition(val eyebrow: String, val title: String, val description: String, val maxPlayers: Int)
data class WorldSettings(
    val groundSize: Float,
    val gridSize: Float,
    val gridDivisions: Int,
    val spawn: List<Float>,
    val showSpawnPad: Boolean,
)
data class LaunchPadDefinition(
    val id: String,
    val code: String,
    val label: String,
    val position: List<Float>,
    val color: String,
    val radius: Float,
    val countdown: Float,
    val enabled: Boolean = true,
    val availabilityLabel: String = "COMING SOON",
)
data class BlockDefinition(val position: List<Float>, val size: List<Float>, val color: String, val outline: Boolean)
data class WorldDefinition(
    val scene: SceneDefinition,
    val palette: Map<String, String>,
    val world: WorldSettings,
    val launchPads: List<LaunchPadDefinition>,
    val blocks: List<BlockDefinition>,
)

data class LoadedGamePackage(val packageData: GamePackage, val manifest: String, val script: String)

class GamePackageException(message: String) : Exception(message)

object ClientConfiguration {
    val gameBaseUrl: String get() = BuildConfig.CUBACADABRA_GAME_BASE_URL
    val backendUrl: String get() = BuildConfig.CUBACADABRA_BACKEND_URL
    val backendApiUrl: String
        get() = backendUrl
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
}

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
        if (packageData.worldDefinition(packageData.startWorld) == null) {
            throw GamePackageException("The game world \"${packageData.startWorld}\" was not found.")
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

private fun parsePackage(json: JSONObject): GamePackage {
    val worlds = buildMap {
        val values = json.optJSONObject("worlds") ?: JSONObject()
        values.keys().forEach { id -> put(id, parseWorld(values.getJSONObject(id))) }
    }
    return GamePackage(
        startWorld = json.getString("startWorld"),
        launch = json.getJSONObject("launch").let { LaunchRoute(it.getString("destinationWorld"), it.optBoolean("authoritative", false)) },
        scene = parseScene(json.getJSONObject("scene")),
        palette = jsonObjectMap(json.optJSONObject("palette")),
        world = parseWorldSettings(json.optJSONObject("world")),
        launchPads = parsePads(json.optJSONArray("launchPads")),
        blocks = parseBlocks(json.optJSONArray("blocks")),
        worlds = worlds,
    )
}

private fun parseWorld(json: JSONObject): WorldDefinition = WorldDefinition(
    scene = parseScene(json.getJSONObject("scene")),
    palette = jsonObjectMap(json.optJSONObject("palette")),
    world = parseWorldSettings(json.optJSONObject("world")),
    launchPads = parsePads(json.optJSONArray("launchPads")),
    blocks = parseBlocks(json.optJSONArray("blocks")),
)

private fun parseScene(json: JSONObject) = SceneDefinition(
    eyebrow = json.optString("eyebrow", "cubacadabra"),
    title = json.optString("title", "First Game"),
    description = json.optString("description", ""),
    maxPlayers = json.optInt("maxPlayers", 18),
)

private fun parseWorldSettings(json: JSONObject?): WorldSettings {
    val value = json ?: JSONObject()
    return WorldSettings(
        groundSize = value.optDouble("groundSize", 120.0).toFloat(),
        gridSize = value.optDouble("gridSize", 112.0).toFloat(),
        gridDivisions = value.optInt("gridDivisions", 28),
        spawn = value.optJSONArray("spawn").floatList(default = listOf(0f, 0f, 0f)),
        showSpawnPad = value.optBoolean("showSpawnPad", true),
    )
}

private fun parsePads(array: JSONArray?): List<LaunchPadDefinition> = buildList {
    if (array == null) return@buildList
    for (index in 0 until array.length()) {
        val value = array.getJSONObject(index)
        add(LaunchPadDefinition(
            id = value.getString("id"),
            code = value.getString("code"),
            label = value.getString("label"),
            position = value.getJSONArray("position").floatList(),
            color = value.getString("color"),
            radius = value.optDouble("radius", 2.7).toFloat(),
            countdown = value.optDouble("countdown", 8.0).toFloat(),
            enabled = value.optBoolean("enabled", true),
            availabilityLabel = value.optString("availabilityLabel", "COMING SOON"),
        ))
    }
}

private fun parseBlocks(array: JSONArray?): List<BlockDefinition> = buildList {
    if (array == null) return@buildList
    for (index in 0 until array.length()) {
        val value = array.getJSONObject(index)
        add(BlockDefinition(
            position = value.getJSONArray("position").floatList(),
            size = value.getJSONArray("size").floatList(),
            color = value.getString("color"),
            outline = value.optBoolean("outline", true),
        ))
    }
}

private fun jsonObjectMap(json: JSONObject?): Map<String, String> = buildMap {
    json ?: return@buildMap
    json.keys().forEach { key -> put(key, json.getString(key)) }
}

private fun JSONArray.floatList(default: List<Float> = emptyList()): List<Float> =
    (0 until length()).map { optDouble(it, Double.NaN).toFloat() }.takeUnless { it.any { value -> value.isNaN() } } ?: default
