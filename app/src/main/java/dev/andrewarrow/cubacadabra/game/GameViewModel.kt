package dev.andrewarrow.cubacadabra.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.andrewarrow.cubacadabra.nativebridge.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

data class Vec3(val x: Float, val y: Float, val z: Float)
data class EnginePlayer(val position: Vec3, val yaw: Float, val moving: Boolean, val sprinting: Boolean)
data class EnginePad(val occupants: Int, val seconds: Float, val phase: Int)
data class EngineFrame(
    val player: EnginePlayer,
    val agents: Int,
    val remotePlayers: Int,
    val pads: List<EnginePad>,
    val activeWorldIndex: Int,
)

data class PresenceNotice(val message: String, val joined: Boolean, val id: Long = System.nanoTime())
data class GameUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val packageData: GamePackage? = null,
    val worldId: String = "lobby",
    val frame: EngineFrame? = null,
    val connectionState: WorldConnectionState = WorldConnectionState.DISCONNECTED,
    val presenceNotice: PresenceNotice? = null,
    val username: String = "",
    val usernameStatus: String = "Choose a name other players can find you by.",
    val settingsRoomState: Int = 0,
    val usernameEditorOpen: Boolean = false,
    val sprinting: Boolean = false,
)

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(GameUiState())
    val state: StateFlow<GameUiState> = _state.asStateFlow()

    private val loader = GamePackageLoader()
    private val socket = WorldSocketClient(application, viewModelScope)
    private var engine: Long = 0
    private var renderer: Long = 0
    private var lastFrameNanos: Long? = null
    private var forward = 0f
    private var strafe = 0f
    private var jumpQueued = false
    private var lookX = 0f
    private var lookY = 0f
    private var zoomDelta = 0f
    private var connectedWorldId: String? = null
    private val remotes = sortedMapOf<String, RemotePlayer>()

    init {
        socket.onStateChange = { state -> update { copy(connectionState = state) } }
        socket.onPresence = ::handlePresence
        socket.onUsername = ::handleUsername
        socket.onMovement = { event -> remotes[event.playerId] = event.player }
        load()
    }

    fun load() {
        if (engine != 0L) return
        update { copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                val loaded = withContext(Dispatchers.IO) { loader.load() }
                val created = NativeEngine.nativeCreate()
                check(created != 0L) { "The Rust game engine could not be created." }
                try {
                    check(NativeEngine.nativeLoad(created, loaded.manifest.toByteArray(), true)) {
                        "The Rust game engine could not load the game manifest."
                    }
                    check(NativeEngine.nativeLoad(created, loaded.script.toByteArray(), false)) {
                        "The Luau game script could not be loaded."
                    }
                } catch (error: Throwable) {
                    NativeEngine.nativeDestroy(created)
                    throw error
                }
                engine = created
                val worldId = loaded.packageData.startWorld
                NativeEngine.nativeSetUsername(created, socket.username.toByteArray())
                socket.setHidden(worldId == "settings")
                update {
                    copy(isLoading = false, packageData = loaded.packageData, worldId = worldId,
                        username = socket.username,
                        frame = NativeEngine.nativeReadFrame(created).decodeFrame())
                }
                connectWorld(worldId)
            }.onFailure { error ->
                if (engine == 0L) update { copy(isLoading = false, errorMessage = error.message ?: "Unknown error") }
            }
        }
    }

    fun retry() {
        socket.disconnect()
        connectedWorldId = null
        if (renderer != 0L) NativeEngine.nativeDestroyRenderer(renderer)
        renderer = 0
        if (engine != 0L) NativeEngine.nativeDestroy(engine)
        engine = 0
        remotes.clear()
        lastFrameNanos = null
        load()
    }

    fun tick(frameTimeNanos: Long) {
        val currentEngine = engine
        if (currentEngine == 0L || _state.value.isLoading) return
        val previous = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        if (previous == null) return
        val delta = min((frameTimeNanos - previous) / 1_000_000_000f, 0.05f).coerceAtLeast(0f)
        val visibleRemotes = if (_state.value.worldId == "settings") emptyList() else remotes.values
        val flatRemotes = visibleRemotes.flatMap { listOf(it.position.x, it.position.y, it.position.z, it.yaw,
            if (it.moving) 1f else 0f, if (it.sprinting) 1f else 0f) }.toFloatArray()
        NativeEngine.nativeSetRemotePlayers(currentEngine, flatRemotes)
        val settingsOpen = _state.value.usernameEditorOpen
        NativeEngine.nativeSetInput(currentEngine, if (settingsOpen) 0f else forward, if (settingsOpen) 0f else strafe,
            if (settingsOpen) false else _state.value.sprinting, if (settingsOpen) false else jumpQueued,
            if (settingsOpen) 0f else lookX, if (settingsOpen) 0f else lookY, if (settingsOpen) 0f else zoomDelta)
        jumpQueued = false; lookX = 0f; lookY = 0f; zoomDelta = 0f
        NativeEngine.nativeStep(currentEngine, delta)
        val nextFrame = NativeEngine.nativeReadFrame(currentEngine).decodeFrame()
        updateSettingsRoomState(NativeEngine.nativeSettingsRoomState(currentEngine))
        val packageData = _state.value.packageData
        val nextWorld = packageData?.runtimeWorldIds()?.getOrNull(nextFrame.activeWorldIndex)
        if (nextWorld != null && nextWorld != _state.value.worldId) {
            forward = 0f; strafe = 0f
            socket.setHidden(nextWorld == "settings")
            update { copy(worldId = nextWorld, sprinting = false) }
            connectWorld(nextWorld)
        }
        update { copy(frame = nextFrame) }
        if (_state.value.worldId != "settings") {
            socket.sendMove(nextFrame.player.position, nextFrame.player.yaw, nextFrame.player.moving, nextFrame.player.sprinting)
        }
    }

    fun setMove(strafe: Float, forward: Float) {
        if (_state.value.usernameEditorOpen) return
        this.strafe = strafe; this.forward = forward
    }
    fun jump() { if (!_state.value.usernameEditorOpen) jumpQueued = true }
    fun toggleSprinting() {
        if (!_state.value.usernameEditorOpen) update { copy(sprinting = !sprinting) }
    }
    fun lookBy(dx: Float, dy: Float) {
        if (!_state.value.usernameEditorOpen) {
            lookX += dx
            lookY += dy
        }
    }
    fun zoomBy(scale: Float) {
        if (!_state.value.usernameEditorOpen) zoomDelta -= (scale - 1f) * 8f
    }
    fun requestUsernameEdit() {
        if (_state.value.settingsRoomState != 2 || _state.value.usernameEditorOpen) return
        forward = 0f; strafe = 0f; jumpQueued = false
        update { copy(usernameEditorOpen = true, usernameStatus = "Choose a unique name using 2–24 characters.") }
    }
    fun cancelUsernameEdit() {
        forward = 0f; strafe = 0f; jumpQueued = false
        update { copy(usernameEditorOpen = false) }
    }
    fun saveUsername(value: String) {
        val normalized = value.trim().replace(Regex("\\s+"), " ")
        if (normalized.length !in 2..24) {
            update { copy(usernameStatus = "Use 2–24 characters.") }
            return
        }
        update { copy(usernameStatus = "Checking that name…") }
        socket.setUsername(normalized)
    }
    fun createRenderer(surface: android.view.Surface, width: Float, height: Float) {
        if (engine != 0L && renderer == 0L) renderer = NativeEngine.nativeCreateRenderer(engine, surface, width, height)
    }
    fun resizeRenderer(width: Float, height: Float) { if (renderer != 0L) NativeEngine.nativeResizeRenderer(renderer, width, height) }
    fun draw() { if (renderer != 0L && engine != 0L) NativeEngine.nativeDrawRenderer(renderer, engine) }
    fun destroyRenderer() {
        if (renderer != 0L) NativeEngine.nativeDestroyRenderer(renderer)
        renderer = 0
    }

    fun world(): WorldDefinition? = _state.value.packageData?.worldDefinition(_state.value.worldId)

    private fun handlePresence(event: PresenceEvent) {
        if (event.type == "player_leave") remotes.remove(event.playerId)
        val fallback = when {
            event.playerId.startsWith("ios-") -> "iOS"
            event.playerId.startsWith("web-") -> "Web"
            else -> "Player"
        } + " Player " + event.playerId.takeLast(4).uppercase()
        val label = event.username ?: fallback
        val joined = event.type != "player_leave"
        val action = when (event.type) {
            "player_join" -> "joined the world"
            "player_name" -> "is now in the lobby"
            else -> "left the world"
        }
        val notice = PresenceNotice("$label $action", joined)
        update { copy(presenceNotice = notice) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(4_000)
            update { if (presenceNotice?.id == notice.id) copy(presenceNotice = null) else this }
        }
    }

    private fun handleUsername(event: UsernameEvent) {
        if (event.type == "username_updated" && event.username != null) {
            if (engine != 0L) NativeEngine.nativeSetUsername(engine, event.username.toByteArray())
            update { copy(username = event.username, usernameEditorOpen = false) }
        } else if (event.type == "username_error") {
            update { copy(usernameStatus = if (event.code == "username_taken") "That name is already in use. Try another." else "That name could not be saved. Try again.") }
        }
    }

    private fun updateSettingsRoomState(roomState: Int) {
        update { copy(settingsRoomState = roomState, usernameEditorOpen = usernameEditorOpen && roomState != 0) }
    }

    private fun connectWorld(visualWorldId: String) {
        val networkWorldId = if (visualWorldId == "settings") "lobby" else visualWorldId
        if (networkWorldId == connectedWorldId) return
        connectedWorldId = networkWorldId
        remotes.clear()
        if (engine != 0L) NativeEngine.nativeSetRemotePlayers(engine, floatArrayOf())
        socket.connect(networkWorldId)
    }

    private fun update(transform: GameUiState.() -> GameUiState) { _state.value = transform(_state.value) }

    override fun onCleared() {
        socket.disconnect()
        if (renderer != 0L) NativeEngine.nativeDestroyRenderer(renderer)
        if (engine != 0L) NativeEngine.nativeDestroy(engine)
        super.onCleared()
    }
}

private fun FloatArray.decodeFrame(): EngineFrame {
    val snapshotLength = NativeEngine.nativeSnapshotLength()
    val metadata = snapshotLength
    val padCount = getOrElse(metadata + 3) { 0f }.toInt()
    val pads = (0 until padCount).map { index ->
        val offset = metadata + 8 + index * 3
        EnginePad(getOrElse(offset) { 0f }.toInt(), getOrElse(offset + 1) { 0f }, getOrElse(offset + 2) { 0f }.toInt())
    }
    return EngineFrame(
        player = EnginePlayer(Vec3(this[0], this[1], this[2]), this[3], this[6] > 0.5f, this[7] > 0.5f),
        agents = getOrElse(metadata) { 0f }.toInt(),
        remotePlayers = getOrElse(metadata + 2) { 0f }.toInt(),
        pads = pads,
        activeWorldIndex = getOrElse(metadata + 4) { 0f }.toInt(),
    )
}
