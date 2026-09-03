package dev.andrewarrow.cubacadabra.game

import dev.andrewarrow.cubacadabra.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    val gameBaseUrl: String
        get() = if (BuildConfig.DEBUG) {
            BuildConfig.CUBACADABRA_GAME_BASE_URL
        } else {
            "https://cubacadabra.com/games/first-game/"
        }

    val backendUrl: String
        get() = if (BuildConfig.DEBUG) BuildConfig.CUBACADABRA_BACKEND_URL
        else "wss://cubacadabra.andrew-f97.workers.dev"
}

class GamePackageLoader {
    suspend fun load(): LoadedGamePackage = withContext(Dispatchers.IO) {
        val base = ClientConfiguration.gameBaseUrl.trimEnd('/') + "/"
        val manifestBytes = fetch(URL(base + "manifest.json"))
        val scriptBytes = fetch(URL(base + "game.luau"))
        val manifest = manifestBytes.toString(Charsets.UTF_8)
        val script = scriptBytes.toString(Charsets.UTF_8)
        val packageData = parsePackage(JSONObject(manifest))
        if (packageData.worldDefinition(packageData.startWorld) == null) {
            throw GamePackageException("The game world \"${packageData.startWorld}\" was not found.")
        }
        LoadedGamePackage(packageData, manifest, script)
    }

    private fun fetch(url: URL): ByteArray {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "GET"
        }
        connection.connect()
        try {
            if (connection.responseCode !in 200..299) throw GamePackageException("The game package server returned HTTP ${connection.responseCode}.")
            return connection.inputStream.use { stream -> stream.readBytes() }
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
