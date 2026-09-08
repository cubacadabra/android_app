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
    val version: String? = null,
    val packageBaseUrl: String? = null,
) {
    val catalogID: String
        get() = packageBaseUrl?.let { "remote-$it" } ?: id
}

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
    val version: GamePackageVersion?,
)

data class GamePackageVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: String?,
) : Comparable<GamePackageVersion> {
    override fun compareTo(other: GamePackageVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        return when {
            prerelease == null && other.prerelease == null -> 0
            prerelease == null -> 1
            other.prerelease == null -> -1
            else -> prerelease.compareTo(other.prerelease)
        }
    }

    companion object {
        fun parse(source: String?): GamePackageVersion? {
            if (source.isNullOrEmpty()) return null
            val withoutBuild = source.substringBefore('+')
            val core = withoutBuild.substringBefore('-').split('.')
            if (core.size != 3) return null
            val major = core[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val minor = core[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val patch = core[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val prerelease = withoutBuild
                .substringAfter('-', missingDelimiterValue = "")
                .ifEmpty { null }
            return GamePackageVersion(major, minor, patch, prerelease)
        }
    }
}

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
