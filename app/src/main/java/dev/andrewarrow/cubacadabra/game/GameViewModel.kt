package dev.andrewarrow.cubacadabra.game

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.andrewarrow.cubacadabra.app.AccountGameSession
import dev.andrewarrow.cubacadabra.nativebridge.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

private data class RemotePlayerState(
    val username: String,
    val player: RemotePlayer,
    val appearance: JSONObject? = null,
)

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "GameViewModel"
    }

    private var accountSession = AccountGameSession()
    private var serverAppearance: JSONObject? = null
    var onAccountRequested: (() -> Unit)? = null
    var onSignOutRequested: (() -> Unit)? = null
    var onSessionRejected: ((Long) -> Unit)? = null
    private val _state = MutableStateFlow(GameUiState())
    val state: StateFlow<GameUiState> = _state.asStateFlow()

    private val loader = GamePackageLoader(application)
    private val gameAudio = GameAudio(application)
    private val socket = WorldSocketClient(application, viewModelScope)
    private var engine: Long = 0
    private var gameLoadGeneration = 0L
    private var renderer: Long = 0
    private var packageImageAtlas: GameImageAtlas? = null
    private var packageMorphPacks: List<ByteArray> = emptyList()
    private var lobbyEnabled = true
    private var lastFrameNanos: Long? = null
    private var forward = 0f
    private var strafe = 0f
    private var jumpQueued = false
    private var climbing = false
    private var lookX = 0f
    private var lookY = 0f
    private var zoomDelta = 0f
    private var uiViewport: UiViewport? = null
    private var clientTransportConnected = false
    private val remotes = sortedMapOf<String, RemotePlayerState>()
    private val remotePlayerNames = sortedMapOf<String, String>()
    private val remotePlayerUserIDs = sortedMapOf<String, String>()

    init {
        socket.onStateChange = { state ->
            if (state == WorldConnectionState.CONNECTED && !clientTransportConnected) {
                clientTransportConnected = true
                if (engine != 0L) NativeEngine.nativeTransportConnected(engine)
            } else if (state != WorldConnectionState.CONNECTED && clientTransportConnected) {
                clientTransportConnected = false
                if (engine != 0L) NativeEngine.nativeTransportDisconnected(engine)
            }
            update { copy(connectionState = state) }
        }
        socket.onPresence = ::handlePresence
        socket.onUsername = ::handleUsername
        socket.onSession = { event ->
            if (event.playerId == socket.playerId) {
                if (event.hasUsername || !event.loggedIn) {
                    event.username?.takeIf { it.isNotBlank() }?.let { username ->
                        if (event.hasUsername) socket.adoptUsername(username)
                        if (engine != 0L) NativeEngine.nativeSetUsername(engine, username.toByteArray())
                        update { copy(username = username) }
                    }
                }
                event.appearance?.let(::applyServerAppearance)
                if (!event.loggedIn && accountSession.accountID != null) {
                    onSessionRejected?.invoke(accountSession.sessionID)
                }
            }
        }
        socket.onMovement = { event -> viewModelScope.launch(Dispatchers.Main.immediate) {
            if (!event.isSelf) {
                val previous = remotes[event.playerId]
                remotes[event.playerId] = RemotePlayerState(
                    username = previous?.username ?: remotePlayerNames[event.playerId]
                        ?: defaultPlayerLabel(event.playerId),
                    player = event.player.copy(
                        generation = if (event.generation == 0) previous?.player?.generation ?: 0 else event.generation,
                        motionSequence = event.motionSequence,
                    ),
                    appearance = previous?.appearance,
                )
            }
        } }
        socket.onExperience = ::handleExperience
        socket.onGameMessage = {}
        socket.onRawMessage = { data ->
            if (engine != 0L) NativeEngine.nativeReceiveTransportMessage(engine, data)
        }
    }

    fun applyAccountSession(session: AccountGameSession) {
        val previous = accountSession
        if (previous == session) return
        accountSession = session
        if (accountSession.blockedUserIDs != _state.value.blockedPlayerIDs) {
            update { copy(blockedPlayerIDs = session.blockedUserIDs) }
            update { copy(activePlayers = activeRemotePlayers()) }
            if (engine != 0L) {
                NativeEngine.nativeSetIgnoredPlayerIds(engine, JSONArray(session.blockedUserIDs.toList()).toString().toByteArray(StandardCharsets.UTF_8))
            }
        }
        if (previous.accountID != session.accountID) {
            gameLoadGeneration += 1
            update { copy(isLoading = false, isSelectingGame = false, selectingGameID = null) }
            exitToMainMenu()
            socket.disconnect()
            socket.resetForGuest()
            serverAppearance = null
            update { copy(username = socket.username) }
        }
        socket.setAccessToken(session.accessToken)
        session.username?.takeIf { it.isNotEmpty() }?.let {
            socket.adoptUsername(it)
            update { copy(username = it) }
        }
        if (engine != 0L) {
            NativeEngine.nativeSetUsername(engine, _state.value.username.toByteArray(Charsets.UTF_8))
            NativeEngine.nativeSetAuthenticated(engine, session.accountID != null)
            if (previous.bodyID != session.bodyID) applyAccountAppearance(engine)
        }
    }

    private fun applyAccountAppearance(targetEngine: Long) {
        val body = accountSession.bodyID ?: return
        val appearance = serverAppearance?.let { JSONObject(it.toString()) } ?: JSONObject().put("version", 1)
        appearance.put("body", body)
        appearance.put("revision", NativeEngine.nativeAppearanceRevision(targetEngine).toLong() + 1L)
        NativeEngine.nativeSetLocalAppearance(targetEngine, appearance.toString().toByteArray(Charsets.UTF_8))
    }

    fun load() {
        if (engine != 0L) return
        val generation = ++gameLoadGeneration
        update { copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                val loaded = withContext(Dispatchers.IO) { loader.load("first-game") }
                if (generation != gameLoadGeneration) return@launch
                val created = createEngine(loaded)
                gameAudio.configure(loaded.audioAssets)
                socket.setWorldConfigs(loaded.packageData.runtimeWorldIds().associateWith { id ->
                    loaded.packageData.worldDefinition(id)?.server
                }.filterValues { it != null }.mapValues { it.value!! })
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
                val initialFrame = NativeEngine.nativeReadFrame(created).decodeFrame()
                val worldId = loaded.packageData.runtimeWorldIds()
                    .getOrNull(initialFrame.activeWorldIndex)
                    ?: loaded.packageData.initialWorld
                lobbyEnabled = loaded.packageData.lobbyEnabled && worldId == "lobby"
                NativeEngine.nativeSetUsername(created, socket.username.toByteArray())
                socket.setHidden(worldId == "settings")
                update {
                    copy(isLoading = false, packageData = loaded.packageData, worldId = worldId,
                        username = socket.username,
                        frame = initialFrame)
                }
                if (!_state.value.isMainMenu) connectWorld(worldId)
                viewModelScope.launch { loader.refreshPackage("first-game") }
            }.onFailure { error ->
                if (generation == gameLoadGeneration && engine == 0L) update { copy(isLoading = false, errorMessage = error.message ?: "Unknown error") }
            }
        }
    }

    private fun createEngine(loaded: LoadedGamePackage): Long {
        packageImageAtlas = GameImageAtlasBuilder.make(loaded.imageAssets)
        packageMorphPacks = loaded.morphPacks.map { it.data }
        val created = NativeEngine.nativeCreate(
            loaded.manifest.toByteArray(StandardCharsets.UTF_8),
            loaded.script.toByteArray(StandardCharsets.UTF_8),
        )
        check(created != 0L) { "The Rust game engine could not be created." }
        try {
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
            NativeEngine.nativeSetAuthenticated(created, accountSession.accountID != null)
            applyAccountAppearance(created)
            return created
        } catch (error: Throwable) {
            NativeEngine.nativeDestroy(created)
            throw error
        }
    }

    fun retry() {
        socket.disconnect()
        gameAudio.stopAll()
        clientTransportConnected = false
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

    fun tick(frameTimeNanos: Long) {
        val currentEngine = engine
        if (currentEngine == 0L || _state.value.isLoading) return
        val previous = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        if (previous == null) return
        val delta = min((frameTimeNanos - previous) / 1_000_000_000f, 0.05f).coerceAtLeast(0f)
        dispatchClientActions(currentEngine)
        val settingsOpen = _state.value.usernameEditorOpen
        NativeEngine.nativeSetInput(currentEngine, if (settingsOpen) 0f else forward, if (settingsOpen) 0f else strafe,
            if (settingsOpen) false else _state.value.sprinting, if (settingsOpen) false else jumpQueued,
            if (settingsOpen) false else climbing,
            if (settingsOpen) 0f else lookX, if (settingsOpen) 0f else lookY, if (settingsOpen) 0f else zoomDelta)
        jumpQueued = false; lookX = 0f; lookY = 0f; zoomDelta = 0f
        NativeEngine.nativeStep(currentEngine, delta)
        dispatchClientActions(currentEngine)
        flushAudioMessages(currentEngine)
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
            socket.sendMove(
                nextFrame.player.position,
                nextFrame.player.yaw,
                nextFrame.player.moving,
                nextFrame.player.sprinting,
                NativeEngine.nativePlayerRespawnEventId(currentEngine),
            )
        }
    }

    private fun dispatchClientActions(currentEngine: Long) {
        val ignoredIDs = JSONArray(blockedIDs().toList()).toString().toByteArray(StandardCharsets.UTF_8)
        NativeEngine.nativeSetIgnoredPlayerIds(currentEngine, ignoredIDs)
        while (true) {
            val action = NativeEngine.nativePollClientAction(currentEngine) ?: break
            if (action.isEmpty()) continue
            val payload = String(action, 1, action.size - 1, StandardCharsets.UTF_8)
            when (action[0].toInt()) {
                1 -> {
                    remotes.clear()
                    remotePlayerNames.clear()
                    remotePlayerUserIDs.clear()
                    update { copy(activePlayers = emptyList()) }
                    socket.connect(payload)
                }
                2 -> socket.sendRawText(payload)
            }
        }
    }

    private fun flushAudioMessages(currentEngine: Long) {
        while (true) {
            val data = NativeEngine.nativePollAudioMessage(currentEngine) ?: break
            runCatching {
                val command = JSONObject(String(data, StandardCharsets.UTF_8))
                gameAudio.play(
                    EngineAudioCommand(
                        type = command.getString("type"),
                        id = command.getString("id"),
                        volume = command.optDouble("volume", 1.0).toFloat(),
                    ),
                )
            }.onFailure { error ->
                Log.w(TAG, "Discarding malformed Rust audio command", error)
            }
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
                "player.climb" -> if (uiEvent.phase == "activate") climbing = !climbing
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
                "shared.sign_in" -> if (uiEvent.phase == "activate") onAccountRequested?.invoke()
                "shared.leave_game" -> if (uiEvent.phase == "activate") exitToMainMenu()
                "shared.sign_out" -> if (uiEvent.phase == "activate") onSignOutRequested?.invoke()
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

    fun createRenderer(surface: android.view.Surface, width: Float, height: Float) {
        if (engine == 0L || renderer != 0L) return
        Log.d(TAG, "creating renderer surfaceValid=${surface.isValid} size=${width}x${height}")
        renderer = NativeEngine.nativeCreateRenderer(engine, surface, width, height)
        Log.d(TAG, "renderer created handle=$renderer")
        if (renderer != 0L) {
            packageImageAtlas?.let { atlas ->
                val uploaded = NativeEngine.nativeSetPackageImageAtlas(
                    renderer,
                    atlas.width,
                    atlas.height,
                    atlas.pixels,
                    atlas.regionsJson.toByteArray(StandardCharsets.UTF_8),
                )
                if (!uploaded) Log.e(TAG, "package image atlas upload failed")
            }
            packageMorphPacks.forEach { pack ->
                if (!NativeEngine.nativeRegisterMorphPack(renderer, pack)) {
                    Log.e(TAG, "morph pack upload failed")
                }
            }
        }
        if (renderer == 0L && width > 0f && height > 0f) {
            update { copy(errorMessage = "The Android graphics renderer could not initialize.") }
        }
    }
    fun resizeRenderer(width: Float, height: Float) { if (renderer != 0L) NativeEngine.nativeResizeRenderer(renderer, width, height) }
    fun setAvatarPreviewMode(enabled: Boolean) {
        if (renderer != 0L) NativeEngine.nativeSetAvatarPreviewMode(renderer, enabled)
    }
    fun draw() { if (renderer != 0L && engine != 0L) NativeEngine.nativeDrawRenderer(renderer, engine) }
    fun destroyRenderer() {
        if (renderer != 0L) NativeEngine.nativeDestroyRenderer(renderer)
        renderer = 0
    }

    fun world(): WorldDefinition? = _state.value.packageData?.worldDefinition(_state.value.worldId)

    private fun handleExperience(event: ExperienceEvent) {
        Log.d(TAG, "experience event type=${event.type} kind=${event.kind} phase=${event.phase} blocks=${event.blocks.size}")
        if (event.type == "experience_launch" && event.playerIds.contains(socket.playerId)) {
            update { copy(buildPhase = "build", buildPrompt = "", buildBlocks = emptyList()) }
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
        if (!lobbyEnabled) return
        val index = _state.value.packageData?.runtimeWorldIds()?.indexOf("lobby") ?: -1
        if (index >= 0 && NativeEngine.nativeStartWorld(engine, index)) {
            update { copy(worldId = "lobby", buildPhase = "build", buildPrompt = "", buildBlocks = emptyList(), lobbyLaunchStartsAt = null) }
            setNativeBuildBlocks(emptyList())
            connectWorld("lobby")
        }
    }

    fun enterGame() {
        if (engine == 0L) return
        if (!_state.value.isMainMenu) {
            Log.d(TAG, "ignoring enter-game request because main menu is not visible")
            return
        }
        Log.d(TAG, "leaving main menu and entering world=${_state.value.worldId}")
        lastFrameNanos = null
        update { copy(isMainMenu = false) }
        NativeEngine.nativeRequestTransport(engine)
        connectWorld(_state.value.worldId)
    }

    fun pauseGame() {
        gameAudio.stopAll()
        lastFrameNanos = null
        forward = 0f
        strafe = 0f
        jumpQueued = false
        lookX = 0f
        lookY = 0f
        zoomDelta = 0f
    }

    fun selectGame(game: GameCatalogEntry) {
        if (game.packageBaseUrl == null && GameCatalog.available.none { it.id == game.id }) return
        if (_state.value.isSelectingGame) return
        if (_state.value.selectedGameCatalogID == game.catalogID && _state.value.packageData != null) {
            enterGame()
            return
        }

        val generation = ++gameLoadGeneration
        update { copy(isMainMenu = true, isLoading = false, isSelectingGame = true, selectingGameID = game.catalogID, gameSelectionError = null) }
        viewModelScope.launch {
            runCatching {
                val loaded = withContext(Dispatchers.IO) {
                    loader.load(game.id, packageBaseUrl = game.packageBaseUrl)
                }
                if (generation != gameLoadGeneration) return@launch
                val nextEngine = createEngine(loaded)
                gameAudio.configure(loaded.audioAssets)
                if (renderer != 0L) NativeEngine.nativeDestroyRenderer(renderer)
                renderer = 0
                if (engine != 0L) NativeEngine.nativeDestroy(engine)
                engine = nextEngine
                socket.disconnect()
                socket.setGameID(game.id)
                socket.setWorldConfigs(loaded.packageData.runtimeWorldIds().associateWith { id ->
                    loaded.packageData.worldDefinition(id)?.server
                }.filterValues { it != null }.mapValues { it.value!! })
                clientTransportConnected = false
                remotes.clear()
                remotePlayerNames.clear()
                remotePlayerUserIDs.clear()
                val initialFrame = NativeEngine.nativeReadFrame(nextEngine).decodeFrame()
                val worldID = loaded.packageData.runtimeWorldIds()
                    .getOrNull(initialFrame.activeWorldIndex)
                    ?: loaded.packageData.initialWorld
                lobbyEnabled = loaded.packageData.lobbyEnabled && worldID == "lobby"
                socket.setHidden(worldID == "settings")
                update {
                    copy(
                        isMainMenu = true,
                        isLoading = false,
                        errorMessage = null,
                        isSelectingGame = false,
                        selectingGameID = null,
                        selectedGameID = game.id,
                        selectedGameCatalogID = game.catalogID,
                        packageData = loaded.packageData,
                        worldId = worldID,
                        frame = initialFrame,
                        activePlayers = emptyList(),
                        gameSelectionError = null,
                        buildPhase = "build",
                        buildPrompt = "",
                        buildBlocks = emptyList(),
                        lobbyLaunchStartsAt = null,
                    )
                }
                if (game.packageBaseUrl == null) {
                    viewModelScope.launch { loader.refreshPackage(game.id) }
                }
                enterGame()
            }.onFailure { error ->
                if (generation != gameLoadGeneration) return@launch
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

    fun clearGameSelectionError() {
        update { copy(gameSelectionError = null) }
    }

    fun exitToMainMenu() {
        val previousWorldId = _state.value.worldId
        forward = 0f
        strafe = 0f
        jumpQueued = false
        lastFrameNanos = null
        gameAudio.stopAll()
        val packageData = _state.value.packageData
        val returnWorld = if (lobbyEnabled) "lobby" else packageData?.initialWorld
        val returnIndex = returnWorld?.let { packageData?.runtimeWorldIds()?.indexOf(it) } ?: -1
        val movedToReturnWorld = engine != 0L && returnIndex >= 0 && NativeEngine.nativeStartWorld(engine, returnIndex)
        setNativeBuildBlocks(emptyList())
        socket.disconnect()
        update {
            copy(
                isMainMenu = true,
                worldId = returnWorld ?: "lobby",
                buildPhase = "build",
                buildPrompt = "",
                buildBlocks = emptyList(),
                lobbyLaunchStartsAt = null,
                sprinting = false,
            )
        }
        Log.d(TAG, "main-menu transition complete previousWorld=$previousWorldId movedToWorld=$movedToReturnWorld isMainMenu=${_state.value.isMainMenu}")
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
                val username = event.username ?: remotePlayerNames[event.playerId]
                    ?: defaultPlayerLabel(event.playerId)
                if (event.type == "player_join") {
                    remotes[event.playerId] = RemotePlayerState(
                        username = username,
                        player = RemotePlayer(
                            position = Vec3(0f, 0f, 0f),
                            yaw = 0f,
                            moving = false,
                            sprinting = false,
                            generation = event.generation,
                        ),
                        appearance = event.appearance,
                    )
                } else if (event.type == "appearance") {
                    remotes[event.playerId]?.let { current ->
                        remotes[event.playerId] = current.copy(appearance = event.appearance)
                    }
                } else if (event.type == "player_name") {
                    remotes[event.playerId]?.let { current ->
                        remotes[event.playerId] = current.copy(username = username)
                    }
                }
            }
        }
        val label = event.username ?: defaultPlayerLabel(event.playerId)
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

    private fun blockedIDs() = _state.value.blockedPlayerIDs

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

    private fun applyServerAppearance(serverAppearance: JSONObject) {
        this.serverAppearance = JSONObject(serverAppearance.toString())
        val currentEngine = engine
        if (currentEngine == 0L) return
        val appearance = JSONObject(serverAppearance.toString())
        val serverRevision = appearance.optLong("revision", 0L).coerceAtLeast(0L)
        val localRevision = NativeEngine.nativeAppearanceRevision(currentEngine).toLong()
        appearance.put("revision", maxOf(serverRevision, localRevision + 1L))
        NativeEngine.nativeSetLocalAppearance(
            currentEngine,
            appearance.toString().toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun updateSettingsRoomState(roomState: Int) {
        update { copy(settingsRoomState = roomState, usernameEditorOpen = usernameEditorOpen && roomState != 0) }
    }

    private fun connectWorld(visualWorldId: String) {
        val activeWorldId = _state.value.packageData?.runtimeWorldIds()
            ?.getOrNull(NativeEngine.nativeReadFrame(engine).decodeFrame().activeWorldIndex)
        if (activeWorldId == visualWorldId) dispatchClientActions(engine)
    }

    private fun update(transform: GameUiState.() -> GameUiState) { _state.value = transform(_state.value) }

    override fun onCleared() {
        socket.disconnect()
        gameAudio.stopAll()
        if (renderer != 0L) NativeEngine.nativeDestroyRenderer(renderer)
        if (engine != 0L) NativeEngine.nativeDestroy(engine)
        super.onCleared()
    }
}
