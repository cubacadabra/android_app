package dev.andrewarrow.cubacadabra.game

import org.json.JSONArray
import org.json.JSONObject

internal fun parsePackage(json: JSONObject): GamePackage {
    val worlds = buildMap {
        val values = json.optJSONObject("worlds") ?: JSONObject()
        values.keys().forEach { id -> put(id, parseWorld(values.getJSONObject(id))) }
    }
    return GamePackage(
        startWorld = json.getString("startWorld"),
        lobby = json.optBoolean("lobby", true),
        launch = json.getJSONObject("launch").let { LaunchRoute(it.getString("destinationWorld"), it.optBoolean("authoritative", false)) },
        assets = parseAssets(json.optJSONObject("assets")),
        scene = parseScene(json.getJSONObject("scene")),
        palette = jsonObjectMap(json.optJSONObject("palette")),
        world = parseWorldSettings(json.optJSONObject("world")),
        launchPads = parsePads(json.optJSONArray("launchPads")),
        blocks = parseBlocks(json.optJSONArray("blocks")),
        worlds = worlds,
    )
}

private fun parseAssets(json: JSONObject?): GameAssets? {
    if (json == null) return null
    val audio = if (!json.has("audio")) {
        null
    } else {
        if (json.isNull("audio")) throw GamePackageException("The game manifest assets.audio must be an object.")
        val audioValue = json.getJSONObject("audio")
        buildMap<String, GameAudioAssetDefinition> {
            audioValue.keys().forEach { id ->
                val definition = audioValue.getJSONObject(id)
                put(id, GameAudioAssetDefinition(
                    path = definition.getString("path"),
                    volume = definition.optDouble("volume", 1.0).toFloat(),
                ))
            }
        }
    }
    val images = if (!json.has("images")) {
        null
    } else {
        if (json.isNull("images")) throw GamePackageException("The game manifest assets.images must be an object.")
        val imageValue = json.getJSONObject("images")
        buildMap<String, GameImageAssetDefinition> {
            imageValue.keys().forEach { id ->
                put(id, GameImageAssetDefinition(imageValue.getJSONObject(id).getString("path")))
            }
        }
    }
    return GameAssets(audio = audio, images = images)
}

private fun parseWorld(json: JSONObject): WorldDefinition = WorldDefinition(
    scene = json.optJSONObject("scene")?.let(::parseScene),
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

private fun JSONArray?.floatList(default: List<Float> = emptyList()): List<Float> {
    val array = this ?: return default
    return (0 until array.length())
        .map { array.optDouble(it, Double.NaN).toFloat() }
        .takeUnless { it.any { value -> value.isNaN() } }
        ?: default
}
