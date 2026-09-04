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
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import kotlin.math.min

private data class EngineUiEvent(
    val nodeId: String,
    val action: String,
    val phase: String,
    val value: Float?,
    val x: Float?,
    val y: Float?,
)

private data class UiViewport(
    val width: Float,
    val height: Float,
    val scale: Float,
    val safeTop: Float,
    val safeRight: Float,
    val safeBottom: Float,
    val safeLeft: Float,
)

data class Vec3(val x: Float, val y: Float, val z: Float)
data class EnginePlayer(val position: Vec3, val yaw: Float, val moving: Boolean, val sprinting: Boolean)
data class EnginePad(val occupants: Int, val seconds: Float, val phase: Int)
data class EngineFrame(
    val player: EnginePlayer,
    val agents: Int,
    val remotePlayers: Int,
    val pads: List<EnginePad>,
    val activeWorldIndex: Int,
    val cameraYaw: Float,
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
    val buildPrompt: String = "",
    val buildPhase: String = "build",
    val buildBlocks: List<BuildBlock> = emptyList(),
    val buildTool: String = "place",
    val buildShape: String = "cube",
    val buildColor: String = "coral",
    val lobbyLaunchStartsAt: Long? = null,
    val lobbyLaunchClockOffset: Long = 0L,
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
    private var uiViewport: UiViewport? = null
    private var connectedWorldId: String? = null
    private var pendingSessionWorldId: String? = null
    private val remotes = sortedMapOf<String, RemotePlayer>()

    init {
        socket.onStateChange = { state -> update { copy(connectionState = state) } }
        socket.onPresence = ::handlePresence
        socket.onUsername = ::handleUsername
        socket.onMovement = { event -> viewModelScope.launch(Dispatchers.Main.immediate) {
            if (event.isSelf) {
                if (event.corrected && engine != 0L) {
                    NativeEngine.nativeReconcilePlayer(
                        engine,
                        event.player.position.x,
                        event.player.position.y,
                        event.player.position.z,
                        event.player.yaw,
                    )
                }
            } else {
                remotes[event.playerId] = event.player
            }
        } }
        socket.onExperience = ::handleExperience
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
                uiViewport?.let { viewport ->
                    NativeEngine.nativeSetUiViewport(
                        created,
                        viewport.width,
                        viewport.height,
                        viewport.scale,
                        viewport.safeTop,
                        viewport.safeRight,
                        viewport.safeBottom,
                        viewport.safeLeft,
                    )
                }
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
        handleUiEvents(currentEngine)
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

    fun setUiViewport(
        width: Float,
        height: Float,
        scale: Float,
        safeTop: Float,
        safeRight: Float,
        safeBottom: Float,
        safeLeft: Float,
    ) {
        val viewport = UiViewport(width, height, scale, safeTop, safeRight, safeBottom, safeLeft)
        uiViewport = viewport
        if (engine != 0L) {
            NativeEngine.nativeSetUiViewport(
                engine,
                viewport.width,
                viewport.height,
                viewport.scale,
                viewport.safeTop,
                viewport.safeRight,
                viewport.safeBottom,
                viewport.safeLeft,
            )
        }
    }

    fun uiPointer(pointerId: Long, phase: Int, x: Float, y: Float): Boolean =
        engine != 0L && NativeEngine.nativeUiPointer(engine, pointerId, phase, x, y)

    private fun handleUiEvents(currentEngine: Long) {
        while (NativeEngine.nativePollUiEvent(currentEngine)) {
            val event = runCatching {
                JSONObject(String(NativeEngine.nativeUiEvent(currentEngine), StandardCharsets.UTF_8))
            }.getOrNull() ?: continue
            val uiEvent = EngineUiEvent(
                nodeId = event.optString("nodeId"),
                action = event.optString("action"),
                phase = event.optString("phase"),
                value = event.optDouble("value").takeIf { event.has("value") && !event.isNull("value") }?.toFloat(),
                x = event.optDouble("x").takeIf { event.has("x") && !event.isNull("x") }?.toFloat(),
                y = event.optDouble("y").takeIf { event.has("y") && !event.isNull("y") }?.toFloat(),
            )
            when (uiEvent.action) {
                "player.move" -> setMove(strafe = uiEvent.x ?: 0f, forward = -(uiEvent.y ?: 0f))
                "player.jump" -> if (uiEvent.phase == "activate") jump()
                "player.run" -> if (uiEvent.phase == "activate") toggleSprinting()
                "build.tool" -> if (uiEvent.phase == "activate") {
                    val tools = listOf("place", "rotate", "remove", "recolor")
                    update { copy(buildTool = tools[(tools.indexOf(buildTool).coerceAtLeast(0) + 1) % tools.size]) }
                }
                "build.shape" -> if (uiEvent.phase == "activate") cycleBuildShape()
                "build.color" -> if (uiEvent.phase == "activate") cycleBuildColor()
                "build.use" -> if (uiEvent.phase == "activate") performBuildAction()
                "build.save" -> if (uiEvent.phase == "activate") saveBuild()
                "build.return" -> if (uiEvent.phase == "activate") returnToLobby()
                "build.place", "build.rotate", "build.remove", "build.recolor" ->
                    update { copy(buildTool = uiEvent.action.removePrefix("build.")) }
                else -> Unit
            }
        }
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

    private fun handleExperience(event: ExperienceEvent) {
        if (event.type == "experience_launch" && event.playerIds.contains(socket.playerId)) {
            val session = event.sessionWorldId ?: return
            val index = _state.value.packageData?.runtimeWorldIds()?.indexOf("real-game") ?: -1
            if (index >= 0 && NativeEngine.nativeStartWorld(engine, index)) {
                pendingSessionWorldId = session
                update { copy(worldId = "real-game", buildPhase = "build", buildPrompt = "", buildBlocks = emptyList()) }
                connectWorld("real-game")
            }
            return
        }
        if (event.type == "experience_state" && event.kind == "lobby") {
            val offset = System.currentTimeMillis() - (event.serverNow ?: System.currentTimeMillis())
            update { copy(lobbyLaunchStartsAt = event.startsAt, lobbyLaunchClockOffset = offset) }
            return
        }
        if (event.type != "experience_state" || event.kind != "build") return
        update { copy(buildPhase = event.phase ?: "build", buildPrompt = event.prompt ?: "Build together.", buildBlocks = event.blocks) }
        setNativeBuildBlocks(event.blocks)
    }

    private fun setNativeBuildBlocks(blocks: List<BuildBlock>) {
        if (engine == 0L) return
        NativeEngine.nativeSetBuildBlockCount(engine, blocks.size)
        blocks.forEachIndexed { index, block ->
            val size = when (block.shape) {
                "beam" -> floatArrayOf(3f, 1f, 1f)
                "slab" -> floatArrayOf(2f, .5f, 2f)
                else -> floatArrayOf(1f, 1f, 1f)
            }
            val color = mapOf("coral" to 0xed725b, "butter" to 0xf2c764, "periwinkle" to 0x7898dc, "ink" to 0x264b4b, "paper" to 0xf6f1e7)[block.color] ?: 0xed725b
            NativeEngine.nativeSetBuildBlock(engine, index, block.x, block.y, block.z, size[0], size[1], size[2], color, block.rotation)
        }
    }

    fun cycleBuildShape() {
        val shapes = listOf("cube", "beam", "slab")
        update { copy(buildShape = shapes[(shapes.indexOf(buildShape).coerceAtLeast(0) + 1) % shapes.size]) }
    }

    fun setBuildTool(tool: String) {
        if (tool in listOf("place", "rotate", "remove", "recolor")) update { copy(buildTool = tool) }
    }

    fun cycleBuildColor() {
        val colors = listOf("coral", "butter", "periwinkle", "ink", "paper")
        update { copy(buildColor = colors[(colors.indexOf(buildColor).coerceAtLeast(0) + 1) % colors.size]) }
    }

    fun performBuildAction() {
        val current = _state.value
        val frame = current.frame ?: return
        if (current.worldId != "real-game" || current.buildPhase != "build") return
        val sizeY = when (current.buildShape) { "slab" -> .5f; else -> 1f }
        val target = JSONObject().apply {
            put("x", kotlin.math.round((frame.player.position.x + kotlin.math.sin(frame.cameraYaw) * 4) * 2) / 2)
            put("y", sizeY / 2)
            put("z", kotlin.math.round((frame.player.position.z - kotlin.math.cos(frame.cameraYaw) * 4) * 2) / 2)
            put("shape", current.buildShape)
            put("color", current.buildColor)
        }
        if (current.buildTool == "place") {
            socket.sendExperience("build_action", JSONObject().apply { put("action", "place"); put("block", target) })
            return
        }
        val nearest = current.buildBlocks.minByOrNull { block ->
            val dx = block.x - target.optDouble("x").toFloat(); val dy = block.y - target.optDouble("y").toFloat(); val dz = block.z - target.optDouble("z").toFloat()
            dx * dx + dy * dy + dz * dz
        } ?: return
        val dx = nearest.x - target.optDouble("x").toFloat(); val dy = nearest.y - target.optDouble("y").toFloat(); val dz = nearest.z - target.optDouble("z").toFloat()
        if (dx * dx + dy * dy + dz * dz >= 4.41f) return
        socket.sendExperience("build_action", JSONObject().apply {
            put("action", current.buildTool); put("id", nearest.id)
            if (current.buildTool == "recolor") put("color", current.buildColor)
        })
    }

    fun saveBuild() = socket.sendExperience("build_save")

    fun returnToLobby() {
        pendingSessionWorldId = null
        val index = _state.value.packageData?.runtimeWorldIds()?.indexOf("lobby") ?: -1
        if (index >= 0 && NativeEngine.nativeStartWorld(engine, index)) {
            update { copy(worldId = "lobby", buildPhase = "build", buildPrompt = "", buildBlocks = emptyList(), lobbyLaunchStartsAt = null) }
            setNativeBuildBlocks(emptyList())
            connectWorld("lobby")
        }
    }

    fun lobbyLaunchStatus(pad: LaunchPadDefinition, live: EnginePad?): String {
        if (!pad.enabled) return pad.availabilityLabel
        val startsAt = _state.value.lobbyLaunchStartsAt ?: return padStatus(live)
        val remaining = startsAt + _state.value.lobbyLaunchClockOffset - System.currentTimeMillis()
        return if (remaining > 0) String.format("%.1fs", remaining / 1000f) else "LAUNCHING"
    }

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
        val networkWorldId = when {
            visualWorldId == "settings" -> "lobby"
            visualWorldId == "real-game" && pendingSessionWorldId != null -> pendingSessionWorldId!!
            else -> visualWorldId
        }
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
        cameraYaw = getOrElse(metadata + 5) { 0f },
    )
}

private fun padStatus(pad: EnginePad?) = when {
    pad == null -> "READY"
    pad.seconds > 0f -> "${pad.occupants} · ${pad.seconds.toInt()}s"
    else -> "${pad.occupants} READY"
}
