package dev.andrewarrow.cubacadabra.game

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
data class BuildBlock(val id: String, val x: Float, val y: Float, val z: Float, val rotation: Int, val shape: String, val color: String)
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

class WorldSocketClient(context: Context, private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "WorldSocketClient"
        private const val SEND_INTERVAL_MS = 83L
        private const val EPSILON = 0.01f

        private fun defaultUsername(playerId: String) =
            "Android Player ${playerId.takeLast(4).uppercase()}"
    }

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val preferences = context.getSharedPreferences("cubacadabra", Context.MODE_PRIVATE)
    var playerId = preferences.getString("player-id", null) ?: "android-${UUID.randomUUID()}".also {
        preferences.edit().putString("player-id", it).apply()
    }
    private set
    var username: String = preferences.getString("username", null)
        ?.takeIf { it.isNotBlank() }
        ?: defaultUsername(playerId)
        private set
    private var pendingUsername: String? = null
    private var hidden = false
    private var socket: WebSocket? = null
    private var worldId: String? = null
    private var gameId = "first-game"
    private var stopped = true
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var lastSentAt = 0L
    private var lastMove: SentMove? = null
    private var accessToken: String? = null

    var onStateChange: (WorldConnectionState) -> Unit = {}
    var onPresence: (PresenceEvent) -> Unit = {}
    var onSession: (SessionEvent) -> Unit = {}
    var onMovement: (MovementEvent) -> Unit = {}
    var onUsername: (UsernameEvent) -> Unit = {}
    var onExperience: (ExperienceEvent) -> Unit = {}

    fun connect(nextWorldId: String) {
        val normalized = nextWorldId.trim()
        if (normalized.isEmpty() || (!stopped && normalized == worldId && socket != null)) return
        Log.d(TAG, "connect world=$normalized")
        closeSocket()
        worldId = normalized
        stopped = false
        reconnectAttempt = 0
        lastMove = null
        openSocket()
    }

    fun disconnect() {
        stopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        closeSocket()
        onStateChange(WorldConnectionState.DISCONNECTED)
    }

    fun setGameID(nextGameID: String) {
        val normalized = nextGameID.trim()
        if (normalized.isNotEmpty()) gameId = normalized
    }

    fun sendMove(position: Vec3, yaw: Float, moving: Boolean, sprinting: Boolean) {
        val current = socket ?: return
        val move = SentMove(position, yaw, moving, sprinting)
        if (lastMove != null && !move.changedFrom(lastMove!!)) return
        val now = System.currentTimeMillis()
        if (now - lastSentAt < SEND_INTERVAL_MS) return
        val payload = JSONObject().apply {
            put("type", "move")
            put("x", position.x); put("y", position.y); put("z", position.z)
            put("yaw", yaw); put("moving", moving); put("sprinting", sprinting)
        }
        if (current.send(payload.toString())) {
            lastSentAt = now
            lastMove = move
        }
    }

    private fun openSocket() {
        val id = worldId ?: return
        if (stopped) return
        notifyState(if (reconnectAttempt == 0) WorldConnectionState.CONNECTING else WorldConnectionState.RECONNECTING)
        val url = ClientConfiguration.backendUrl.trimEnd('/') + "/world/$id?client=android&game=$gameId"
        val request = Request.Builder().url(url).apply {
            accessToken?.let { header("Authorization", "Bearer $it") }
        }.build()
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            if (webSocket != socket || stopped) return
            Log.d(TAG, "socket open world=$worldId")
            reconnectAttempt = 0
            notifyState(WorldConnectionState.CONNECTED)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket != socket || stopped) return
            scope.launch {
                if (webSocket != socket || stopped) return@launch
                handle(JSONObject(text), webSocket)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            if (webSocket != socket || stopped) return
            Log.w(TAG, "socket failure world=$worldId message=${t.message}", t)
            socket = null
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket != socket || stopped) return
            Log.d(TAG, "socket closed world=$worldId code=$code reason=$reason")
            socket = null
            scheduleReconnect()
        }
    }

    private fun handle(event: JSONObject, source: WebSocket) {
        if (source != socket || stopped) return
        val type = event.optString("type")
        if (type == "session_identity") {
            event.optString("id").takeIf { it.isNotBlank() }?.let { playerId = it }
            val sessionUsername = event.optString("username").takeIf { it.isNotBlank() }
            val loggedIn = event.optBoolean("loggedIn", event.optBoolean("authenticated"))
            if (!loggedIn && sessionUsername != null) username = sessionUsername
            val pending = pendingUsername
            if (pending != null
                && (!event.optBoolean("hasUsername") || sessionUsername != pending)
            ) sendUsername(pending, source)
            sendVisibility(source)
            onSession(SessionEvent(
                playerId = playerId,
                username = sessionUsername,
                hasUsername = event.optBoolean("hasUsername"),
                loggedIn = loggedIn,
                authenticated = event.optBoolean("authenticated"),
                appearance = event.optJSONObject("appearance"),
            ))
            return
        }
        if (type == "username_updated" || type == "username_error") {
            val updated = event.optString("username").takeIf { it.isNotBlank() }
            if (type == "username_updated" && updated != null) {
                username = updated
                pendingUsername = updated
                preferences.edit().putString("username", updated).apply()
            } else if (type == "username_error") {
                pendingUsername = null
            }
            onUsername(UsernameEvent(type, updated, event.optString("code").takeIf { it.isNotBlank() }))
            return
        }
        if (type == "experience_state" || type == "experience_launch") {
            val blocks = buildList {
                val values = event.optJSONArray("blocks") ?: return@buildList
                for (index in 0 until values.length()) {
                    val block = values.optJSONObject(index) ?: continue
                    add(BuildBlock(
                        id = block.optString("id"),
                        x = block.optDouble("x").toFloat(),
                        y = block.optDouble("y").toFloat(),
                        z = block.optDouble("z").toFloat(),
                        rotation = block.optInt("rotation", 0),
                        shape = block.optString("shape", "cube"),
                        color = block.optString("color", "coral"),
                    ))
                }
            }
            val playerIds = buildList {
                val values = event.optJSONArray("playerIds") ?: return@buildList
                for (index in 0 until values.length()) values.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
            onExperience(ExperienceEvent(
                type = type,
                kind = event.optString("kind").takeIf { it.isNotBlank() },
                phase = event.optString("phase").takeIf { it.isNotBlank() },
                prompt = event.optString("prompt").takeIf { it.isNotBlank() },
                sessionWorldId = event.optString("sessionWorldId").takeIf { it.isNotBlank() },
                playerIds = playerIds,
                startsAt = (event.optJSONObject("launch") ?: event).optLong("startsAt").takeIf {
                    (event.optJSONObject("launch") ?: event).has("startsAt")
                        && !(event.optJSONObject("launch") ?: event).isNull("startsAt")
                },
                serverNow = event.optLong("serverNow").takeIf { event.has("serverNow") },
                blocks = blocks,
            ))
            return
        }
        val id = event.optString("id")
        if (id.isEmpty()) return
        if (type == "move") {
            onMovement(MovementEvent(id, RemotePlayer(
                position = Vec3(event.optDouble("x").toFloat(), event.optDouble("y").toFloat(), event.optDouble("z").toFloat()),
                yaw = event.optDouble("yaw").toFloat(),
                moving = event.optBoolean("moving"),
                sprinting = event.optBoolean("sprinting"),
                generation = event.optInt("generation", 0),
                motionSequence = event.optLong("motionSequence", 0L),
            ), isSelf = id == playerId, corrected = event.optBoolean("corrected"),
                generation = event.optInt("generation", 0),
                motionSequence = event.optLong("motionSequence", 0L)))
        } else if (type == "player_join" || type == "player_leave" || type == "player_name" || type == "appearance") {
            onPresence(
                PresenceEvent(
                    type = type,
                    playerId = id,
                    username = event.optString("username").takeIf { it.isNotBlank() },
                    userId = event.optString("user_id").takeIf { it.isNotBlank() },
                    generation = event.optInt("generation", 0),
                    appearance = event.optJSONObject("appearance"),
                ),
            )
        }
    }

    fun setUsername(value: String) {
        val normalized = value.trim().replace(Regex("\\s+"), " ")
        if (normalized.length !in 2..24 || !normalized.matches(Regex("[A-Za-z0-9 _-]+"))) {
            onUsername(UsernameEvent("username_error", null, "invalid_username"))
            return
        }
        pendingUsername = normalized
        socket?.let { sendUsername(normalized, it) }
    }

    fun setAccessToken(nextAccessToken: String?) {
        if (accessToken == nextAccessToken) return
        accessToken = nextAccessToken
        if (!stopped && worldId != null) {
            reconnectAttempt = 0
            closeSocket()
            openSocket()
        }
    }

    fun adoptUsername(nextUsername: String) {
        val normalized = nextUsername.trim()
        if (normalized.isEmpty()) return
        username = normalized
        pendingUsername = normalized
        preferences.edit().putString("username", normalized).apply()
    }

    /** Clears the signed-in identity before opening the next guest socket. */
    fun resetForGuest() {
        val reconnectAsGuest = !stopped && worldId != null
        reconnectJob?.cancel()
        reconnectJob = null
        closeSocket()
        accessToken = null
        playerId = "android-${UUID.randomUUID()}"
        username = defaultUsername(playerId)
        pendingUsername = null
        preferences.edit()
            .putString("player-id", playerId)
            .remove("username")
            .apply()
        if (reconnectAsGuest) {
            reconnectAttempt = 0
            stopped = false
            openSocket()
        }
    }

    fun clearPendingUsername() {
        pendingUsername = null
    }

    private fun sendUsername(value: String, webSocket: WebSocket?) {
        if (stopped || webSocket == null) return
        webSocket.send(JSONObject().apply {
            put("type", "set_username")
            put("username", value)
        }.toString())
    }

    fun setHidden(nextHidden: Boolean) {
        if (hidden == nextHidden) return
        hidden = nextHidden
        socket?.let(::sendVisibility)
    }

    fun sendExperience(type: String, payload: JSONObject = JSONObject()) {
        val current = socket ?: run {
            Log.w(TAG, "experience send dropped: no socket type=$type world=$worldId stopped=$stopped")
            return
        }
        val message = JSONObject(payload.toString()).apply { put("type", type) }
        val sent = current.send(message.toString())
        Log.d(TAG, "experience send type=$type world=$worldId sent=$sent payload=$message")
    }

    private fun sendVisibility(webSocket: WebSocket) {
        if (stopped) return
        webSocket.send(JSONObject().apply {
            put("type", "set_hidden")
            put("hidden", hidden)
        }.toString())
    }

    private fun scheduleReconnect() {
        if (stopped) return
        reconnectAttempt++
        notifyState(WorldConnectionState.RECONNECTING)
        reconnectJob?.cancel()
        val waitMs = minOf(750L * (1L shl minOf(reconnectAttempt - 1, 3)), 8_000L)
        reconnectJob = scope.launch {
            delay(waitMs)
            if (!stopped) openSocket()
        }
    }

    private fun closeSocket() {
        socket?.cancel()
        socket = null
    }

    private fun notifyState(state: WorldConnectionState) {
        scope.launch { onStateChange(state) }
    }

    private data class SentMove(val position: Vec3, val yaw: Float, val moving: Boolean, val sprinting: Boolean) {
        fun changedFrom(previous: SentMove) = moving != previous.moving || sprinting != previous.sprinting ||
            abs(position.x - previous.position.x) > EPSILON || abs(position.y - previous.position.y) > EPSILON ||
            abs(position.z - previous.position.z) > EPSILON || abs(yaw - previous.yaw) > EPSILON
    }
}
