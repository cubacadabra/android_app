package dev.andrewarrow.cubacadabra.game

import android.content.Context
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

data class RemotePlayer(val position: Vec3, val yaw: Float, val moving: Boolean, val sprinting: Boolean)
data class PresenceEvent(val type: String, val playerId: String, val username: String? = null)
data class UsernameEvent(val type: String, val username: String?, val code: String?)
data class MovementEvent(val playerId: String, val player: RemotePlayer)

class WorldSocketClient(context: Context, private val scope: CoroutineScope) {
    companion object {
        private const val SEND_INTERVAL_MS = 83L
        private const val EPSILON = 0.01f
    }

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val preferences = context.getSharedPreferences("cubacadabra", Context.MODE_PRIVATE)
    private val playerId = preferences.getString("player-id", null) ?: "android-${UUID.randomUUID()}".also {
        preferences.edit().putString("player-id", it).apply()
    }
    var username: String = preferences.getString("username", null)
        ?.takeIf { it.isNotBlank() }
        ?: "Android Player ${playerId.takeLast(4).uppercase()}"
        private set
    private var pendingUsername = username
    private var socket: WebSocket? = null
    private var worldId: String? = null
    private var stopped = true
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var lastSentAt = 0L
    private var lastMove: SentMove? = null

    var onStateChange: (WorldConnectionState) -> Unit = {}
    var onPresence: (PresenceEvent) -> Unit = {}
    var onMovement: (MovementEvent) -> Unit = {}
    var onUsername: (UsernameEvent) -> Unit = {}

    fun connect(nextWorldId: String) {
        val normalized = nextWorldId.trim()
        if (normalized.isEmpty() || (!stopped && normalized == worldId && socket != null)) return
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
        val url = ClientConfiguration.backendUrl.trimEnd('/') + "/world/$id?player_id=$playerId"
        socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            if (webSocket != socket || stopped) return
            reconnectAttempt = 0
            notifyState(WorldConnectionState.CONNECTED)
            sendUsername(pendingUsername, webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket != socket || stopped) return
            scope.launch { handle(JSONObject(text)) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            if (webSocket != socket || stopped) return
            socket = null
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket != socket || stopped) return
            socket = null
            scheduleReconnect()
        }
    }

    private fun handle(event: JSONObject) {
        val type = event.optString("type")
        if (type == "username_updated" || type == "username_error") {
            val updated = event.optString("username").takeIf { it.isNotBlank() }
            if (type == "username_updated" && updated != null) {
                username = updated
                pendingUsername = updated
                preferences.edit().putString("username", updated).apply()
            } else if (type == "username_error") {
                pendingUsername = username
            }
            onUsername(UsernameEvent(type, updated, event.optString("code").takeIf { it.isNotBlank() }))
            return
        }
        val id = event.optString("id")
        if (id.isEmpty() || id == playerId) return
        if (type == "move") {
            onMovement(MovementEvent(id, RemotePlayer(
                position = Vec3(event.optDouble("x").toFloat(), event.optDouble("y").toFloat(), event.optDouble("z").toFloat()),
                yaw = event.optDouble("yaw").toFloat(),
                moving = event.optBoolean("moving"),
                sprinting = event.optBoolean("sprinting"),
            )))
        } else if (type == "player_join" || type == "player_leave" || type == "player_name") {
            onPresence(PresenceEvent(type, id, event.optString("username").takeIf { it.isNotBlank() }))
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

    private fun sendUsername(value: String, webSocket: WebSocket) {
        if (stopped) return
        webSocket.send(JSONObject().apply {
            put("type", "set_username")
            put("username", value)
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
