package dev.andrewarrow.cubacadabra.game

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import java.lang.ref.WeakReference
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
data class RemotePlayerSummary(val id: String, val username: String, val playerId: String)
data class GameUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isMainMenu: Boolean = false,
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
    val isAuthenticated: Boolean = false,
    val authUser: AppAuthUser? = null,
    val selectedGameID: String = "first-game",
    val isSelectingGame: Boolean = false,
    val selectingGameID: String? = null,
    val gameSelectionError: String? = null,
    val profileUsernameSaving: Boolean = false,
    val profileUsernameMessage: String? = null,
    val profileUsernameMessageIsError: Boolean = false,
    val blockedPlayerIDs: Set<String> = emptySet(),
    val activePlayers: List<RemotePlayerSummary> = emptyList(),
)

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "GameViewModel"
    }

    private val preferences = application.getSharedPreferences("cubacadabra", 0)
    private val _state = MutableStateFlow(
        GameUiState(
            blockedPlayerIDs = preferences.getStringSet("blocked-player-ids", emptySet()).orEmpty(),
        ),
    )
    val state: StateFlow<GameUiState> = _state.asStateFlow()

    private val loader = GamePackageLoader(application)
    private val socket = WorldSocketClient(application, viewModelScope)
    private val authentication = AppAuthenticationService(application)
    private val googleSignIn = NativeGoogleSignInService(application)
    private var accessToken: String? = null
    private var activityReference: WeakReference<Activity>? = null
    private var isSigningIn = false
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
    private val remotePlayerNames = sortedMapOf<String, String>()
    private val remotePlayerUserIDs = sortedMapOf<String, String>()

    init {
        socket.onStateChange = { state -> update { copy(connectionState = state) } }
        socket.onPresence = ::handlePresence
        socket.onUsername = ::handleUsername
        socket.onSession = { event ->
            if (event.playerId == socket.playerId) {
                update {
                    copy(
                        isAuthenticated = event.loggedIn,
                        authUser = if (event.loggedIn) authUser else null,
                    )
                }
                if (!event.loggedIn && engine != 0L) NativeEngine.nativeSetAuthenticated(engine, false)
            }
        }
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
                val loaded = withContext(Dispatchers.IO) { loader.load("first-game") }
                val created = createEngine(loaded)
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
                authentication.restore()?.let(::applyAuthentication)
                connectWorld(worldId)
                viewModelScope.launch { loader.refreshPackage("first-game") }
            }.onFailure { error ->
                if (engine == 0L) update { copy(isLoading = false, errorMessage = error.message ?: "Unknown error") }
            }
        }
    }

    private fun createEngine(loaded: LoadedGamePackage): Long {
        val created = NativeEngine.nativeCreate()
        check(created != 0L) { "The Rust game engine could not be created." }
        try {
            check(NativeEngine.nativeLoad(created, loaded.manifest.toByteArray(), true)) {
                "The Rust game engine could not load the game manifest."
            }
            if (!NativeEngine.nativeLoad(created, loaded.script.toByteArray(), false)) {
                val details = String(NativeEngine.nativeScriptError(created), StandardCharsets.UTF_8).trim()
                throw GamePackageException(
                    if (details.isEmpty()) "The Luau game script could not be loaded."
                    else "The Luau game script could not be loaded: $details",
                )
            }
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
            NativeEngine.nativeSetUsername(created, socket.username.toByteArray())
            if (_state.value.isAuthenticated) NativeEngine.nativeSetAuthenticated(created, true)
            return created
        } catch (error: Throwable) {
            NativeEngine.nativeDestroy(created)
            throw error
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
        remotePlayerNames.clear()
        remotePlayerUserIDs.clear()
        update { copy(activePlayers = emptyList()) }
        lastFrameNanos = null
        load()
    }

    fun attachActivity(activity: Activity) {
        activityReference = WeakReference(activity)
    }

    fun detachActivity(activity: Activity) {
        if (activityReference?.get() === activity) activityReference = null
    }

    fun refreshAuthentication() {
        if (engine == 0L) return
        viewModelScope.launch {
            authentication.restore()?.let(::applyAuthentication) ?: clearAuthentication()
        }
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

    fun uiPointer(pointerId: Long, phase: Int, x: Float, y: Float): Boolean {
        val handled = engine != 0L && NativeEngine.nativeUiPointer(engine, pointerId, phase, x, y)
        if (phase != 1) {
            Log.d(TAG, "uiPointer id=$pointerId phase=$phase x=$x y=$y handled=$handled engine=$engine")
        }
        return handled
    }

    private fun handleUiEvents(currentEngine: Long) {
        while (NativeEngine.nativePollUiEvent(currentEngine)) {
            val rawEvent = String(NativeEngine.nativeUiEvent(currentEngine), StandardCharsets.UTF_8)
            val event = runCatching { JSONObject(rawEvent) }.getOrNull()
            if (event == null) {
                Log.e(TAG, "failed to decode native UI event raw=$rawEvent")
                continue
            }
            val action = event.optString("action")
            if (action.startsWith("build.") || action.startsWith("shared.")) {
                Log.d(TAG, "native UI event node=${event.optString("nodeId")} action=$action phase=${event.optString("phase")}")
            }
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
                "shared.about.open" -> if (uiEvent.phase == "activate") {
                    runCatching {
                        getApplication<Application>().startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://cubacadabra.com/about/")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    }.onFailure { error ->
                        Log.e(TAG, "could not open the cubacadabra About page", error)
                    }
                }
                "shared.sign_in" -> if (uiEvent.phase == "activate") beginSignIn()
                "shared.leave_game" -> if (uiEvent.phase == "activate") exitToMainMenu()
                "shared.sign_out" -> if (uiEvent.phase == "activate") signOut()
                "build.tool" -> if (uiEvent.phase == "activate") {
                    val tools = listOf("place", "rotate", "remove", "recolor")
                    val nextTool = tools[(tools.indexOf(_state.value.buildTool).coerceAtLeast(0) + 1) % tools.size]
                    Log.d(TAG, "cycling build tool ${_state.value.buildTool} -> $nextTool")
                    update { copy(buildTool = nextTool) }
                }
                "build.shape" -> if (uiEvent.phase == "activate") cycleBuildShape()
                "build.color" -> if (uiEvent.phase == "activate") cycleBuildColor()
                "build.use" -> if (uiEvent.phase == "activate") performBuildAction()
                "build.save" -> if (uiEvent.phase == "activate") saveBuild()
                "build.return" -> if (uiEvent.phase == "activate") returnToLobby()
                "build.place", "build.rotate", "build.remove", "build.recolor" -> if (uiEvent.phase == "activate") {
                    val tool = uiEvent.action.removePrefix("build.")
                    Log.d(TAG, "build tool button activated tool=$tool")
                    update { copy(buildTool = tool) }
                    performBuildAction()
                }
                else -> when {
                    uiEvent.phase == "activate" && uiEvent.action.startsWith("build.shape.") -> {
                        val shape = uiEvent.action.removePrefix("build.shape.")
                        Log.d(TAG, "shape selected ${_state.value.buildShape} -> $shape")
                        update { copy(buildShape = shape) }
                    }
                    uiEvent.phase == "activate" && uiEvent.action.startsWith("build.color.") -> {
                        val color = uiEvent.action.removePrefix("build.color.")
                        Log.d(TAG, "color selected ${_state.value.buildColor} -> $color")
                        update { copy(buildColor = color) }
                    }
                }
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

    fun saveProfileUsername(value: String) {
        val normalized = value.trim()
        if (normalized.length !in 2..24 || !normalized.matches(Regex("[A-Za-z0-9_-]+"))) {
            update {
                copy(
                    profileUsernameSaving = false,
                    profileUsernameMessage = "Use 2–24 letters, numbers, _ or -.",
                    profileUsernameMessageIsError = true,
                )
            }
            return
        }
        update { copy(profileUsernameSaving = true, profileUsernameMessage = null, profileUsernameMessageIsError = false) }
        viewModelScope.launch {
            runCatching { authentication.saveUsername(normalized) }
                .onSuccess { result ->
                    applyProfileUpdate(result)
                    update {
                        copy(
                            profileUsernameSaving = false,
                            profileUsernameMessage = "Username saved.",
                            profileUsernameMessageIsError = false,
                        )
                    }
                }
                .onFailure { error ->
                    val message = when (error) {
                        is AppProfileException.Server -> when (error.code) {
                            "username_taken" -> "That username is already in use. Try another."
                            "username_not_allowed" -> "That username isn’t available. Try another."
                            else -> "We couldn’t save your username. Please try again."
                        }
                        is AppProfileException.Unauthorized -> "Your sign-in has expired. Please sign in again."
                        else -> "We couldn’t save your username. Please try again."
                    }
                    update {
                        copy(
                            profileUsernameSaving = false,
                            profileUsernameMessage = message,
                            profileUsernameMessageIsError = true,
                        )
                    }
                }
        }
    }

    fun clearProfileUsernameMessage() {
        update { copy(profileUsernameMessage = null, profileUsernameMessageIsError = false) }
    }
    fun createRenderer(surface: android.view.Surface, width: Float, height: Float) {
        if (engine == 0L || renderer != 0L) return
        Log.d(TAG, "creating renderer surfaceValid=${surface.isValid} size=${width}x${height}")
        renderer = NativeEngine.nativeCreateRenderer(engine, surface, width, height)
        Log.d(TAG, "renderer created handle=$renderer")
        if (renderer == 0L && width > 0f && height > 0f) {
            update { copy(errorMessage = "The Android graphics renderer could not initialize.") }
        }
    }
    fun resizeRenderer(width: Float, height: Float) { if (renderer != 0L) NativeEngine.nativeResizeRenderer(renderer, width, height) }
    fun draw() { if (renderer != 0L && engine != 0L) NativeEngine.nativeDrawRenderer(renderer, engine) }
    fun destroyRenderer() {
        if (renderer != 0L) NativeEngine.nativeDestroyRenderer(renderer)
        renderer = 0
    }

    fun world(): WorldDefinition? = _state.value.packageData?.worldDefinition(_state.value.worldId)

    private fun handleExperience(event: ExperienceEvent) {
        Log.d(TAG, "experience event type=${event.type} kind=${event.kind} phase=${event.phase} blocks=${event.blocks.size}")
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
        val nextShape = shapes[(shapes.indexOf(_state.value.buildShape).coerceAtLeast(0) + 1) % shapes.size]
        Log.d(TAG, "cycling build shape ${_state.value.buildShape} -> $nextShape")
        update { copy(buildShape = nextShape) }
    }

    fun setBuildTool(tool: String) {
        if (tool in listOf("place", "rotate", "remove", "recolor")) update { copy(buildTool = tool) }
    }

    fun cycleBuildColor() {
        val colors = listOf("coral", "butter", "periwinkle", "ink", "paper")
        val nextColor = colors[(colors.indexOf(_state.value.buildColor).coerceAtLeast(0) + 1) % colors.size]
        Log.d(TAG, "cycling build color ${_state.value.buildColor} -> $nextColor")
        update { copy(buildColor = nextColor) }
    }

    fun performBuildAction() {
        val current = _state.value
        Log.d(TAG, "performBuildAction world=${current.worldId} phase=${current.buildPhase} tool=${current.buildTool} shape=${current.buildShape} color=${current.buildColor} blocks=${current.buildBlocks.size} hasFrame=${current.frame != null}")
        val frame = current.frame ?: run {
            Log.w(TAG, "build action ignored: no engine frame")
            return
        }
        if (current.worldId != "real-game") {
            Log.w(TAG, "build action ignored: world=${current.worldId}")
            return
        }
        val sizeY = when (current.buildShape) { "slab" -> .5f; else -> 1f }
        val target = JSONObject().apply {
            put("x", kotlin.math.round((frame.player.position.x + kotlin.math.sin(frame.cameraYaw) * 4) * 2) / 2)
            put("y", sizeY / 2)
            put("z", kotlin.math.round((frame.player.position.z - kotlin.math.cos(frame.cameraYaw) * 4) * 2) / 2)
            put("shape", current.buildShape)
            put("color", current.buildColor)
        }
        if (current.buildTool == "place") {
            Log.d(TAG, "sending place block=$target")
            socket.sendExperience("build_action", JSONObject().apply { put("action", "place"); put("block", target) })
            return
        }
        val nearest = current.buildBlocks.minByOrNull { block ->
            val dx = block.x - target.optDouble("x").toFloat(); val dy = block.y - target.optDouble("y").toFloat(); val dz = block.z - target.optDouble("z").toFloat()
            dx * dx + dy * dy + dz * dz
        } ?: run {
            Log.w(TAG, "build action ignored: no blocks available for tool=${current.buildTool}")
            return
        }
        val dx = nearest.x - target.optDouble("x").toFloat(); val dy = nearest.y - target.optDouble("y").toFloat(); val dz = nearest.z - target.optDouble("z").toFloat()
        if (dx * dx + dy * dy + dz * dz >= 4.41f) {
            Log.w(TAG, "build action ignored: nearest block=${nearest.id} is too far from target=$target")
            return
        }
        Log.d(TAG, "sending build action tool=${current.buildTool} block=${nearest.id}")
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

    private fun beginSignIn() {
        if (isSigningIn) {
            Log.d(TAG, "ignoring sign-in request because sign-in is already running")
            return
        }
        val activity = activityReference?.get() ?: run {
            Log.w(TAG, "native Google sign-in requested without an active Activity")
            return
        }
        Log.d(TAG, "starting Rust-triggered Google sign-in world=${_state.value.worldId} authenticated=${_state.value.isAuthenticated}")
        isSigningIn = true
        viewModelScope.launch {
            try {
                val credential = googleSignIn.signIn(activity)
                Log.d(TAG, "Google credential returned; exchanging credential with backend")
                val result = authentication.authenticateGoogle(credential)
                Log.d(TAG, "backend sign-in succeeded user=${result.user.id} username=${result.user.username}")
                applyAuthentication(result)
                Log.d(TAG, "authentication applied; requesting main-menu transition")
                exitToMainMenu()
            } catch (_: AppAuthException.Cancelled) {
                // The user dismissed the Google sign-in flow.
                Log.d(TAG, "Google sign-in was cancelled")
            } catch (error: Throwable) {
                Log.w(TAG, "native Google sign-in failed", error)
            } finally {
                isSigningIn = false
                Log.d(TAG, "Rust-triggered Google sign-in finished")
            }
        }
    }

    fun enterGame() {
        if (!_state.value.isMainMenu) {
            Log.d(TAG, "ignoring enter-game request because main menu is not visible")
            return
        }
        Log.d(TAG, "leaving main menu and entering world=${_state.value.worldId}")
        lastFrameNanos = null
        update { copy(isMainMenu = false) }
        connectWorld(_state.value.worldId)
    }

    fun selectGame(gameID: String) {
        if (GameCatalog.available.none { it.id == gameID }) return
        if (_state.value.isSelectingGame) return
        if (_state.value.selectedGameID == gameID && _state.value.packageData != null) {
            enterGame()
            return
        }

        update { copy(isSelectingGame = true, selectingGameID = gameID, gameSelectionError = null) }
        viewModelScope.launch {
            runCatching {
                val loaded = withContext(Dispatchers.IO) { loader.load(gameID) }
                val nextEngine = createEngine(loaded)
                if (renderer != 0L) NativeEngine.nativeDestroyRenderer(renderer)
                renderer = 0
                if (engine != 0L) NativeEngine.nativeDestroy(engine)
                engine = nextEngine
                socket.disconnect()
                connectedWorldId = null
                pendingSessionWorldId = null
                remotes.clear()
                remotePlayerNames.clear()
                remotePlayerUserIDs.clear()
                val worldID = loaded.packageData.startWorld
                socket.setHidden(worldID == "settings")
                update {
                    copy(
                        isMainMenu = true,
                        isSelectingGame = false,
                        selectingGameID = null,
                        selectedGameID = gameID,
                        packageData = loaded.packageData,
                        worldId = worldID,
                        frame = NativeEngine.nativeReadFrame(nextEngine).decodeFrame(),
                        activePlayers = emptyList(),
                        gameSelectionError = null,
                        buildPhase = "build",
                        buildPrompt = "",
                        buildBlocks = emptyList(),
                        lobbyLaunchStartsAt = null,
                    )
                }
                viewModelScope.launch { loader.refreshPackage(gameID) }
                enterGame()
            }.onFailure { error ->
                update {
                    copy(
                        isSelectingGame = false,
                        selectingGameID = null,
                        gameSelectionError = error.message ?: "That game is unavailable right now.",
                    )
                }
            }
        }
    }

    private fun exitToMainMenu() {
        val previousWorldId = _state.value.worldId
        forward = 0f
        strafe = 0f
        jumpQueued = false
        lastFrameNanos = null
        pendingSessionWorldId = null
        val lobbyIndex = _state.value.packageData?.runtimeWorldIds()?.indexOf("lobby") ?: -1
        val movedToLobby = lobbyIndex >= 0 && NativeEngine.nativeStartWorld(engine, lobbyIndex)
        setNativeBuildBlocks(emptyList())
        socket.disconnect()
        connectedWorldId = null
        update {
            copy(
                isMainMenu = true,
                worldId = "lobby",
                buildPhase = "build",
                buildPrompt = "",
                buildBlocks = emptyList(),
                lobbyLaunchStartsAt = null,
                sprinting = false,
            )
        }
        Log.d(TAG, "main-menu transition complete previousWorld=$previousWorldId movedToLobby=$movedToLobby isMainMenu=${_state.value.isMainMenu}")
    }

    private fun signOut() {
        if (isSigningIn) return
        authentication.clearTokens()
        clearAuthentication()
        activityReference?.get()?.let { activity ->
            viewModelScope.launch { googleSignIn.signOut(activity) }
        }
    }

    private fun applyAuthentication(result: AppAuthResult) {
        Log.d(TAG, "applying authentication user=${result.user.id} engineReady=${engine != 0L}")
        accessToken = result.accessToken
        update { copy(isAuthenticated = true, authUser = result.user) }
        if (engine != 0L) NativeEngine.nativeSetAuthenticated(engine, true)
        socket.setAccessToken(result.accessToken)
        result.user.username?.takeIf { it.isNotEmpty() }?.let { username ->
            socket.adoptUsername(username)
            if (engine != 0L) NativeEngine.nativeSetUsername(engine, username.toByteArray())
            update { copy(username = username) }
        }
        viewModelScope.launch { refreshBlockedPlayers() }
    }

    private fun applyProfileUpdate(result: AppProfileUpdateResult) {
        update { copy(authUser = result.user) }
        result.user.username?.takeIf { it.isNotEmpty() }?.let { username ->
            socket.adoptUsername(username)
            if (engine != 0L) NativeEngine.nativeSetUsername(engine, username.toByteArray())
            update { copy(username = username) }
        }
    }

    private fun clearAuthentication() {
        accessToken = null
        update { copy(isAuthenticated = false, authUser = null) }
        if (engine != 0L) NativeEngine.nativeSetAuthenticated(engine, false)
        socket.setAccessToken(null)
    }

    fun lobbyLaunchStatus(pad: LaunchPadDefinition, live: EnginePad?): String {
        if (!pad.enabled) return pad.availabilityLabel
        val startsAt = _state.value.lobbyLaunchStartsAt ?: return padStatus(live)
        val remaining = startsAt + _state.value.lobbyLaunchClockOffset - System.currentTimeMillis()
        return if (remaining > 0) String.format("%.1fs", remaining / 1000f) else "LAUNCHING"
    }

    private fun handlePresence(event: PresenceEvent) {
        val isSelf = event.playerId == socket.playerId
        if (!isSelf) {
            if (event.type == "player_leave") {
                remotes.remove(event.playerId)
                remotePlayerNames.remove(event.playerId)
                remotePlayerUserIDs.remove(event.playerId)
            } else {
                event.username?.let { remotePlayerNames[event.playerId] = it }
                event.userId?.let { remotePlayerUserIDs[event.playerId] = it }
            }
        }
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
        update { copy(presenceNotice = notice, activePlayers = activeRemotePlayers()) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(4_000)
            update { if (presenceNotice?.id == notice.id) copy(presenceNotice = null) else this }
        }
    }

    private fun activeRemotePlayers(): List<RemotePlayerSummary> = remotePlayerNames.keys
        .filter { playerId ->
            val userID = remotePlayerUserIDs[playerId]
            !blockedIDs().contains(userID ?: playerId)
        }
        .sorted()
        .map { playerId ->
            RemotePlayerSummary(
                id = remotePlayerUserIDs[playerId] ?: playerId,
                username = remotePlayerNames[playerId] ?: defaultPlayerLabel(playerId),
                playerId = playerId,
            )
        }

    fun blockPlayer(player: RemotePlayerSummary) {
        val targetID = player.id
        val current = _state.value.blockedPlayerIDs
        if (targetID in current || !_state.value.isAuthenticated) return
        update { copy(blockedPlayerIDs = current + targetID) }
        update { copy(activePlayers = activeRemotePlayers()) }
        persistBlockedIDs()
        viewModelScope.launch {
            runCatching { moderationService().blockPlayer(targetID) }
                .onFailure {
                    update {
                        copy(
                            blockedPlayerIDs = blockedPlayerIDs - targetID,
                            activePlayers = activeRemotePlayers(),
                        )
                    }
                    persistBlockedIDs()
                }
        }
    }

    fun unblockPlayer(playerID: String) {
        if (playerID !in _state.value.blockedPlayerIDs || !_state.value.isAuthenticated) return
        update { copy(blockedPlayerIDs = blockedPlayerIDs - playerID) }
        update { copy(activePlayers = activeRemotePlayers()) }
        persistBlockedIDs()
        viewModelScope.launch {
            runCatching { moderationService().unblockPlayer(playerID) }
                .onFailure {
                    update {
                        copy(
                            blockedPlayerIDs = blockedPlayerIDs + playerID,
                            activePlayers = activeRemotePlayers(),
                        )
                    }
                    persistBlockedIDs()
                }
        }
    }

    private fun refreshBlockedPlayers() {
        if (!_state.value.isAuthenticated) return
        viewModelScope.launch {
            runCatching { moderationService().fetchBlockedPlayerIds() }
                .onSuccess { ids ->
                    update { copy(blockedPlayerIDs = blockedPlayerIDs + ids, activePlayers = activeRemotePlayers()) }
                    persistBlockedIDs()
                }
        }
    }

    private fun moderationService() = ModerationService(socket.playerId, accessToken)

    private fun blockedIDs() = _state.value.blockedPlayerIDs

    private fun persistBlockedIDs() {
        preferences.edit().putStringSet("blocked-player-ids", _state.value.blockedPlayerIDs).apply()
    }

    private fun defaultPlayerLabel(playerID: String): String {
        val platform = when {
            playerID.startsWith("ios-") -> "iOS"
            playerID.startsWith("web-") -> "Web"
            playerID.startsWith("android-") -> "Android"
            else -> "Player"
        }
        return "$platform Player ${playerID.takeLast(4).uppercase()}"
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
