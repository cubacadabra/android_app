package dev.andrewarrow.cubacadabra.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.andrewarrow.cubacadabra.game.CubeCatalogService
import dev.andrewarrow.cubacadabra.game.GameCatalog
import dev.andrewarrow.cubacadabra.game.GameCatalogEntry
import dev.andrewarrow.cubacadabra.game.GameUiState
import dev.andrewarrow.cubacadabra.app.AppUiState
import dev.andrewarrow.cubacadabra.app.AppViewModel
import dev.andrewarrow.cubacadabra.game.GameViewModel
import dev.andrewarrow.cubacadabra.game.RemotePlayerSummary
import kotlinx.coroutines.CancellationException

@Composable
internal fun MainMenuScreen(appModel: AppViewModel, model: GameViewModel) {
    val appState by appModel.state.collectAsStateWithLifecycle()
    var destination by remember(appState.authUser?.id) { mutableStateOf(HomeDestination.Home) }
    BackHandler(enabled = destination != HomeDestination.Home) {
        if (destination == HomeDestination.More) model.clearGameSelectionError()
        destination = HomeDestination.Home
    }

    when (destination) {
        HomeDestination.Home -> {
            val gameState by model.state.collectAsStateWithLifecycle()
            HomeMenu(
                gameState,
                appState,
                onSelectGame = model::selectGame,
                onSignIn = {
                    model.exitToMainMenu()
                    appModel.requestSignIn()
                },
                onSignOut = appModel::signOut,
                onUsername = { destination = HomeDestination.Username },
            onMorph = {
                destination = HomeDestination.Morph
            },
                onSafety = { destination = HomeDestination.Safety },
                onMore = {
                    model.exitToMainMenu()
                    model.clearGameSelectionError()
                    destination = HomeDestination.More
                },
            )
        }
        HomeDestination.More -> {
            val gameState by model.state.collectAsStateWithLifecycle()
            MoreCubesScreen(gameState, model::selectGame)
        }
        HomeDestination.Username -> ProfileUsernameScreen(appState, appModel)
        HomeDestination.Morph -> MorphSelectionScreen(appState, appModel)
        HomeDestination.Safety -> {
            val gameState by model.state.collectAsStateWithLifecycle()
            SafetyCenterScreen(gameState, model)
        }
    }
}

private enum class HomeDestination { Home, More, Username, Morph, Safety }

@Composable
private fun HomeMenu(
    state: GameUiState,
    appState: AppUiState,
    onSelectGame: (GameCatalogEntry) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onUsername: () -> Unit,
    onMorph: () -> Unit,
    onSafety: () -> Unit,
    onMore: () -> Unit,
) {
    var showingLogoutConfirmation by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp)
                .widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("CUBACADABRA", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.1.sp)
                Spacer(Modifier.weight(1f))
                Text("●", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Your cubes",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (state.packageData?.lobbyEnabled == false) {
                        "Choose a game to play together."
                    } else {
                        "Choose a game to enter its lobby."
                    },
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = .68f),
                    fontSize = 17.sp,
                )
            }
            Text("CUBES", modifier = Modifier.padding(top = 38.dp, bottom = 10.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onBackground.copy(.62f))
            MenuGroup {
                GameCatalog.available.forEachIndexed { index, game ->
                    GameMenuRow(
                        symbol = if (index == 0) "01" else "02",
                        title = game.title,
                        subtitle = game.subtitle,
                        loading = state.selectingGameID == game.catalogID,
                        enabled = !state.isSelectingGame,
                        onClick = { onSelectGame(game) },
                    )
                    if (index < GameCatalog.available.lastIndex) MenuDivider()
                }
                MenuDivider()
                MenuRow("•••", "More", "Browse uploaded cubes", onMore)
            }
            (state.gameSelectionError ?: state.errorMessage)?.let { error ->
                Text(error, modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }
            Text("ACCOUNT", modifier = Modifier.padding(top = 36.dp, bottom = 10.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onBackground.copy(.62f))
            if (appState.isAuthenticated) MenuGroup {
                MenuRow("@", "Change your username", appState.authUser?.username ?: "Player", onUsername)
                MenuDivider()
                MenuRow("♙", "Choose your morph", MorphOption.fromBodyID(appState.authUser?.bodyID).label, onMorph)
                MenuDivider()
                MenuRow("!", "Block or unblock players", "Players & safety", onSafety)
            }
            if (appState.isAuthenticated) {
                LogoutMenuRow(onClick = { showingLogoutConfirmation = true })
            } else {
                MenuGroup { MenuRow("@", "Sign in", "Manage your account", onSignIn) }
            }
        }
    }

    if (showingLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = { showingLogoutConfirmation = false },
            title = { Text("Log out of cubacadabra?") },
            text = { Text("You can sign in again whenever you are ready.") },
            confirmButton = {
                Button(
                    onClick = {
                        showingLogoutConfirmation = false
                        onSignOut()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("LOG OUT") }
            },
            dismissButton = {
                TextButton(onClick = { showingLogoutConfirmation = false }) { Text("CANCEL") }
            },
        )
    }
}

@Composable
private fun MenuGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = .055f),
    ) { Column { content() } }
}

@Composable
private fun GameMenuRow(
    symbol: String,
    title: String,
    subtitle: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp)
            .heightIn(min = 76.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            symbol,
            modifier = Modifier.width(32.dp),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(.68f))
        }
        if (loading) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
        else Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
    }
}

@Composable
private fun MoreCubesScreen(state: GameUiState, onSelectGame: (GameCatalogEntry) -> Unit) {
    val service = remember { CubeCatalogService() }
    var cubes by remember { mutableStateOf(emptyList<GameCatalogEntry>()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        isLoading = true
        loadError = null
        try {
            cubes = service.firstPage()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            loadError = "We couldn’t load the cubes. Please try again."
        } finally {
            isLoading = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp)
                .widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                "MORE",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(.62f),
            )
            Text(
                "Browse uploaded cubes",
                modifier = Modifier.padding(top = 10.dp, bottom = 22.dp),
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(.78f),
            )
            when {
                isLoading -> Row(
                    modifier = Modifier.padding(vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Text("Loading cubes…", color = MaterialTheme.colorScheme.onBackground.copy(.68f))
                }
                loadError != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                    TextButton(onClick = { reloadKey += 1 }) {
                        Text("TRY AGAIN", fontWeight = FontWeight.Bold)
                    }
                }
                cubes.isEmpty() -> Text(
                    "No uploaded cubes yet.",
                    modifier = Modifier.padding(vertical = 18.dp),
                    color = MaterialTheme.colorScheme.onBackground.copy(.68f),
                )
                else -> MenuGroup {
                    cubes.forEachIndexed { index, cube ->
                        GameMenuRow(
                            symbol = "◇",
                            title = cube.title,
                            subtitle = cube.subtitle,
                            loading = state.selectingGameID == cube.catalogID,
                            enabled = !state.isSelectingGame,
                            onClick = { onSelectGame(cube) },
                        )
                        if (index < cubes.lastIndex) MenuDivider()
                    }
                }
            }
            state.gameSelectionError?.let { error ->
                Text(
                    error,
                    modifier = Modifier.padding(top = 10.dp),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun MenuRow(symbol: String, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .heightIn(min = 60.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(symbol, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.width(32.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(.68f))
        }
        Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
    }
}

@Composable
private fun LogoutMenuRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 22.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("↪", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.width(32.dp))
        Text("Log out", color = MaterialTheme.colorScheme.error, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MenuDivider() {
    androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 62.dp), color = MaterialTheme.colorScheme.onSurface.copy(.10f))
}

@Composable
private fun ProfileUsernameScreen(state: AppUiState, model: AppViewModel) {
    val profile = state.profileUsername
    val focusRequester = remember { FocusRequester() }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp)
            .widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Text("USERNAME", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onBackground.copy(.62f))
        Text("Choose a name other players can find you by.", fontSize = 17.sp, color = MaterialTheme.colorScheme.onBackground.copy(.78f))
        OutlinedTextField(
            value = profile.usernameDraft,
            onValueChange = model::changeProfileUsername,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            label = { Text("Your username") },
            singleLine = true,
        )
        Text("Use 2–24 letters, numbers, _ or -.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(.68f))
        profile.usernameFeedback?.let { feedback ->
            Text(feedback.message, color = if (feedback.kind == "error") MaterialTheme.colorScheme.error else Color(0xFF3E8B63), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = { model.saveProfileUsername() },
            enabled = profile.usernameCanSave,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            if (profile.usernameIsSaving) CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
            else Text("SAVE USERNAME", fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        }
    }
    LaunchedEffect(Unit) { model.beginProfileUsernameEdit(); focusRequester.requestFocus() }
}

@Composable
private fun SafetyCenterScreen(state: GameUiState, model: GameViewModel) {
    var blockTarget by remember { mutableStateOf<RemotePlayerSummary?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp)
            .widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("PLAYERS & SAFETY", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onBackground.copy(.62f))
        Text("Block another player or manage people you have blocked.", fontSize = 17.sp, color = MaterialTheme.colorScheme.onBackground.copy(.78f))
        Text("PLAYERS HERE", modifier = Modifier.padding(top = 18.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onBackground.copy(.62f))
        MenuGroup {
            if (state.activePlayers.isEmpty()) {
                Text("No other players are visible right now.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurface.copy(.68f))
            } else {
                state.activePlayers.forEachIndexed { index, player ->
                    PlayerSafetyRow(player) { blockTarget = player }
                    if (index < state.activePlayers.lastIndex) MenuDivider()
                }
            }
        }
        Text("BLOCKED ON THIS DEVICE", modifier = Modifier.padding(top = 22.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onBackground.copy(.62f))
        MenuGroup {
            if (state.blockedPlayerIDs.isEmpty()) {
                Text("No blocked players.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurface.copy(.68f))
            } else {
                state.blockedPlayerIDs.sorted().forEachIndexed { index, playerID ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Player ${playerID.takeLast(4).uppercase()}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Button(onClick = { model.unblockPlayer(playerID) }) { Text("UNBLOCK", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                    if (index < state.blockedPlayerIDs.size - 1) MenuDivider()
                }
            }
        }
    }
    blockTarget?.let { player ->
        AlertDialog(
            onDismissRequest = { blockTarget = null },
            title = { Text("Block ${player.username}?") },
            text = { Text("You will no longer see this player or their presence. You can unblock them later.") },
            confirmButton = {
                Button(onClick = { model.blockPlayer(player); blockTarget = null }) { Text("BLOCK") }
            },
            dismissButton = {
                TextButton(onClick = { blockTarget = null }) { Text("CANCEL") }
            },
        )
    }
}

@Composable
private fun PlayerSafetyRow(player: RemotePlayerSummary, block: (RemotePlayerSummary) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(player.username, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("In this world", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(.68f))
        }
        Button(onClick = { block(player) }) { Text("BLOCK", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
    }
}
