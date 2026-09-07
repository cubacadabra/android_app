package dev.andrewarrow.cubacadabra.game

data class Vec3(val x: Float, val y: Float, val z: Float)

data class EnginePlayer(
    val position: Vec3,
    val yaw: Float,
    val moving: Boolean,
    val sprinting: Boolean,
)

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
    val loginDialogOpen: Boolean = false,
    val loginInProgress: Boolean = false,
    val loginErrorMessage: String? = null,
    val selectedGameID: String = "first-game",
    val isSelectingGame: Boolean = false,
    val selectingGameID: String? = null,
    val gameSelectionError: String? = null,
    val profileUsernameSaving: Boolean = false,
    val profileUsernameMessage: String? = null,
    val profileUsernameMessageIsError: Boolean = false,
    val morphSaving: Boolean = false,
    val morphMessage: String? = null,
    val morphMessageIsError: Boolean = false,
    val blockedPlayerIDs: Set<String> = emptySet(),
    val activePlayers: List<RemotePlayerSummary> = emptyList(),
)
