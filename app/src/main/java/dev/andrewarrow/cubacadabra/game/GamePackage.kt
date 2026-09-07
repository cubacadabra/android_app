package dev.andrewarrow.cubacadabra.game

import dev.andrewarrow.cubacadabra.BuildConfig

data class GamePackage(
    val startWorld: String,
    val lobby: Boolean,
    val launch: LaunchRoute,
    val assets: GameAssets?,
    val scene: SceneDefinition,
    val palette: Map<String, String>,
    val world: WorldSettings,
    val launchPads: List<LaunchPadDefinition>,
    val blocks: List<BlockDefinition>,
    val worlds: Map<String, WorldDefinition>,
) {
    val lobbyEnabled: Boolean get() = lobby

    val initialWorld: String
        get() = if (lobbyEnabled || startWorld != "lobby") startWorld else launch.destinationWorld

    fun worldDefinition(id: String): WorldDefinition? = if (id == "lobby") {
        WorldDefinition(scene, palette, world, launchPads, blocks)
    } else {
        worlds[id]
    }

    fun runtimeWorldIds(): List<String> = listOf("lobby") + worlds.keys.sorted()
}

data class GameAssets(val audio: Map<String, GameAudioAssetDefinition>?)

data class GameAudioAssetDefinition(val path: String, val volume: Float)

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
    val groundSize: Float = 120f,
    val gridSize: Float = 112f,
    val gridDivisions: Int = 28,
    val spawn: List<Float> = listOf(0f, 0f, 0f),
    val showSpawnPad: Boolean = true,
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
    val scene: SceneDefinition? = null,
    val palette: Map<String, String> = emptyMap(),
    val world: WorldSettings = WorldSettings(),
    val launchPads: List<LaunchPadDefinition> = emptyList(),
    val blocks: List<BlockDefinition> = emptyList(),
)

data class LoadedGamePackage(
    val packageData: GamePackage,
    val manifest: String,
    val script: String,
    val audioAssets: Map<String, LoadedGameAudioAsset>,
)

data class LoadedGameAudioAsset(
    val volume: Float,
    val bundledAssetPath: String? = null,
    val url: String? = null,
)

class GamePackageException(message: String) : Exception(message)

object ClientConfiguration {
    val gameBaseUrl: String get() = BuildConfig.CUBACADABRA_GAME_BASE_URL
    val backendUrl: String get() = BuildConfig.CUBACADABRA_BACKEND_URL
    val backendApiUrl: String
        get() = backendUrl
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
}
