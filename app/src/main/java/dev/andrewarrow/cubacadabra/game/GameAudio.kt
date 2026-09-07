package dev.andrewarrow.cubacadabra.game

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

data class EngineAudioCommand(
    val type: String,
    val id: String,
    val volume: Float?,
)

/** Plays game-owned, overlapping one-shot sounds without exposing audio objects to Luau. */
class GameAudio(context: Context) {
    private companion object {
        const val TAG = "GameAudio"
    }

    private val applicationContext = context.applicationContext
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val activePlayers = mutableSetOf<MediaPlayer>()
    private var assets: Map<String, LoadedGameAudioAsset> = emptyMap()

    fun configure(assets: Map<String, LoadedGameAudioAsset>) {
        stopAll()
        this.assets = assets
    }

    fun play(command: EngineAudioCommand): Boolean {
        if (command.type != "play") return false
        val asset = assets[command.id] ?: run {
            Log.w(TAG, "Game requested unknown audio asset: ${command.id}")
            return false
        }

        val player = MediaPlayer()
        activePlayers += player
        player.setAudioAttributes(audioAttributes)
        val volume = asset.volume * clampVolume(command.volume ?: 1f)
        player.setVolume(volume, volume)
        player.setOnCompletionListener { release(player) }
        player.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "Audio playback failed for ${command.id}: what=$what extra=$extra")
            release(player)
            true
        }
        player.setOnPreparedListener {
            if (activePlayers.contains(player)) {
                runCatching { it.start() }.onFailure { error ->
                    Log.w(TAG, "Audio playback could not start for ${command.id}", error)
                    release(player)
                }
            }
        }

        try {
            val bundledAssetPath = asset.bundledAssetPath
            if (bundledAssetPath != null) {
                applicationContext.assets.openFd(bundledAssetPath).use { descriptor ->
                    player.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                }
            } else {
                player.setDataSource(asset.url ?: throw IllegalArgumentException("Audio asset has no source"))
            }
            player.prepareAsync()
            return true
        } catch (error: Throwable) {
            Log.w(TAG, "Audio asset could not be prepared for ${command.id}", error)
            release(player)
            return false
        }
    }

    fun stopAll() {
        activePlayers.toList().forEach(::release)
    }

    private fun release(player: MediaPlayer) {
        if (!activePlayers.remove(player)) return
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    private fun clampVolume(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
}
