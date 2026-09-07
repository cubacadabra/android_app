package dev.andrewarrow.cubacadabra.game

import org.json.JSONObject

enum class WorldConnectionState(val label: String) {
    CONNECTING("CONNECTING"), CONNECTED("CLOUD LIVE"), RECONNECTING("RECONNECTING"), DISCONNECTED("OFFLINE")
}

data class RemotePlayer(
    val position: Vec3,
    val yaw: Float,
    val moving: Boolean,
    val sprinting: Boolean,
    val generation: Int = 0,
    val motionSequence: Long = 0L,
)

data class PresenceEvent(
    val type: String,
    val playerId: String,
    val username: String? = null,
    val userId: String? = null,
    val generation: Int = 0,
    val appearance: JSONObject? = null,
)

data class SessionEvent(
    val playerId: String,
    val username: String?,
    val hasUsername: Boolean,
    val loggedIn: Boolean,
    val authenticated: Boolean,
    val appearance: JSONObject? = null,
)

data class UsernameEvent(val type: String, val username: String?, val code: String?)

data class MovementEvent(
    val playerId: String,
    val player: RemotePlayer,
    val isSelf: Boolean = false,
    val corrected: Boolean = false,
    val generation: Int = 0,
    val motionSequence: Long = 0L,
)

data class BuildBlock(
    val id: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val rotation: Int,
    val shape: String,
    val color: String,
)

data class ExperienceEvent(
    val type: String,
    val kind: String? = null,
    val phase: String? = null,
    val prompt: String? = null,
    val sessionWorldId: String? = null,
    val playerIds: List<String> = emptyList(),
    val startsAt: Long? = null,
    val serverNow: Long? = null,
    val blocks: List<BuildBlock> = emptyList(),
)
