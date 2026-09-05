package dev.andrewarrow.cubacadabra.ui

import android.content.Context
import android.graphics.PointF
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.withFrameNanos
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.andrewarrow.cubacadabra.game.GameUiState
import dev.andrewarrow.cubacadabra.game.GameCatalog
import dev.andrewarrow.cubacadabra.game.RemotePlayerSummary
import dev.andrewarrow.cubacadabra.game.GameViewModel
import kotlinx.coroutines.isActive

@Composable
fun CubacadabraApp(model: GameViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isLoading, state.errorMessage, state.isMainMenu) {
        Log.d(
            "CubacadabraApp",
            "render mode loading=${state.isLoading} error=${state.errorMessage != null} mainMenu=${state.isMainMenu}",
        )
    }
    Box(Modifier.fillMaxSize()) {
        when {
            state.isLoading -> LoadingScreen()
            state.errorMessage != null -> ErrorScreen(state.errorMessage, model::retry)
            state.isMainMenu -> MainMenuScreen(model)
            else -> GameScreen(state, model)
        }
    }
}

@Composable
private fun MainMenuScreen(model: GameViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var destination by remember { mutableStateOf(HomeDestination.Home) }
    BackHandler(enabled = destination != HomeDestination.Home) { destination = HomeDestination.Home }

    when (destination) {
        HomeDestination.Home -> HomeMenu(state, model, onUsername = { destination = HomeDestination.Username }, onSafety = { destination = HomeDestination.Safety })
        HomeDestination.Username -> ProfileUsernameScreen(state, model)
        HomeDestination.Safety -> SafetyCenterScreen(state, model)
    }
}

private enum class HomeDestination { Home, Username, Safety }

@Composable
private fun HomeMenu(
    state: GameUiState,
    model: GameViewModel,
    onUsername: () -> Unit,
    onSafety: () -> Unit,
) {
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
                    "Choose a game to enter its lobby.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = .68f),
                    fontSize = 17.sp,
                )
            }
            Text("CUBES", modifier = Modifier.padding(top = 38.dp, bottom = 10.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onBackground.copy(.62f))
            MenuGroup {
                GameCatalog.available.forEachIndexed { index, game ->
                    GameMenuRow(
                        gameID = game.id,
                        title = game.title,
                        subtitle = game.subtitle,
                        loading = state.selectingGameID == game.id,
                        enabled = !state.isSelectingGame,
                        onClick = { model.selectGame(game.id) },
                    )
                    if (index < GameCatalog.available.lastIndex) MenuDivider()
                }
            }
            state.gameSelectionError?.let { error ->
                Text(error, modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }
            Text("ACCOUNT", modifier = Modifier.padding(top = 36.dp, bottom = 10.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onBackground.copy(.62f))
            MenuGroup {
                MenuRow("@", "Change your username", state.username.ifBlank { "Player" }, onUsername)
                MenuDivider()
                MenuRow("!", "Block or unblock players", "Players & safety", onSafety)
            }
        }
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
    gameID: String,
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
        Text(if (gameID == "second-game") "02" else "01", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(.68f))
        }
        if (loading) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
        else Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
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
private fun MenuDivider() {
    androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 62.dp), color = MaterialTheme.colorScheme.onSurface.copy(.10f))
}

@Composable
private fun ProfileUsernameScreen(state: GameUiState, model: GameViewModel) {
    var draft by remember(state.username, state.authUser?.username) {
        mutableStateOf(state.authUser?.username ?: state.username)
    }
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
            value = draft,
            onValueChange = { draft = it; model.clearProfileUsernameMessage() },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            label = { Text("Your username") },
            singleLine = true,
        )
        Text("Use 2–24 letters, numbers, _ or -.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(.68f))
        state.profileUsernameMessage?.let { message ->
            Text(message, color = if (state.profileUsernameMessageIsError) MaterialTheme.colorScheme.error else Color(0xFF3E8B63), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = { model.saveProfileUsername(draft) },
            enabled = !state.profileUsernameSaving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            if (state.profileUsernameSaving) CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
            else Text("SAVE USERNAME", fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
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

@Composable
private fun GameScreen(state: GameUiState, model: GameViewModel) {
    LaunchedEffect(model) {
        var previous = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (previous != 0L) model.tick(now)
                previous = now
                model.draw()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        RustGameSurface(model)
        if (state.worldId != "settings") GameAtmosphere(state.worldId != "lobby")
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            if (state.worldId != "settings") {
                state.presenceNotice?.let { notice ->
                    PresenceNotice(
                        message = notice.message,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
                    )
                }
            }
        }
        if (state.usernameEditorOpen) UsernameEditorDialog(state, model)
    }
}

@Composable
private fun UsernameEditorDialog(state: GameUiState, model: GameViewModel) {
    var draft by remember(state.username) { mutableStateOf(state.username) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)).padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.fillMaxWidth().widthIn(max = 430.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .97f),
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("PLAYER IDENTITY", color = MaterialTheme.colorScheme.onSurface.copy(.62f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Edit username", color = MaterialTheme.colorScheme.onSurface, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    label = { Text("Player name") },
                    singleLine = true,
                )
                Text(state.usernameStatus, color = MaterialTheme.colorScheme.onSurface.copy(.65f), fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = model::cancelUsernameEdit, colors = ButtonDefaults.textButtonColors()) { Text("CANCEL") }
                    Button(onClick = { model.saveUsername(draft) }, modifier = Modifier.padding(start = 9.dp)) { Text("SAVE NAME") }
                }
            }
        }
    }
}

@Composable
private fun RustGameSurface(model: GameViewModel) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context -> InteractiveGameSurface(context, model) },
    )
}

private class InteractiveGameSurface(
    context: Context,
    private val model: GameViewModel,
) : SurfaceView(context), SurfaceHolder.Callback {
    private val density = resources.displayMetrics.density.coerceAtLeast(.1f)
    private var safeInsets = Insets.NONE
    private val uiPointers = mutableSetOf<Int>()
    private val cameraTouches = linkedMapOf<Int, PointF>()
    private var previousPinchDistance: Float? = null
    private var cameraTouchMoved = false

    init {
        isFocusable = true
        isClickable = true
        holder.addCallback(this)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            safeInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            updateUiViewport()
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        model.createRenderer(holder.surface, width.toFloat(), height.toFloat())
        updateUiViewport()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            model.createRenderer(holder.surface, width.toFloat(), height.toFloat())
            model.resizeRenderer(width.toFloat(), height.toFloat())
            updateUiViewport()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = model.destroyRenderer()

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateUiViewport()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                beginPointer(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    movePointer(event.getPointerId(index), event.getX(index), event.getY(index))
                }
                updatePinchDistance()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                finishPointer(event.getPointerId(index), event.getX(index), event.getY(index), 2, true)
                updatePinchDistance()
            }
            MotionEvent.ACTION_CANCEL -> {
                val ids = (uiPointers + cameraTouches.keys).toList()
                ids.forEach { pointerId ->
                    val point = cameraTouches[pointerId]
                    finishPointer(pointerId, (point?.x ?: 0f) * density, (point?.y ?: 0f) * density, 3, false)
                }
                updatePinchDistance()
            }
        }
        return true
    }

    private fun beginPointer(pointerId: Int, rawX: Float, rawY: Float) {
        val id = pointerId.toLong() + 1L
        val x = rawX / density
        val y = rawY / density
        if (model.uiPointer(id, 0, x, y)) {
            uiPointers += pointerId
        } else {
            cameraTouches[pointerId] = PointF(x, y)
        }
        updatePinchDistance()
    }

    private fun movePointer(pointerId: Int, rawX: Float, rawY: Float) {
        val id = pointerId.toLong() + 1L
        val x = rawX / density
        val y = rawY / density
        if (pointerId in uiPointers) {
            model.uiPointer(id, 1, x, y)
        } else if (cameraTouches[pointerId] != null) {
            val previous = cameraTouches[pointerId]!!
            if (cameraTouches.size == 1) {
                model.lookBy(x - previous.x, y - previous.y)
                cameraTouchMoved = true
            }
            cameraTouches[pointerId] = PointF(x, y)
        }
    }

    private fun finishPointer(pointerId: Int, rawX: Float, rawY: Float, phase: Int, allowWorldTap: Boolean) {
        val id = pointerId.toLong() + 1L
        val x = rawX / density
        val y = rawY / density
        val wasCameraInteraction = cameraTouches.isNotEmpty()
        if (uiPointers.remove(pointerId)) {
            model.uiPointer(id, phase, x, y)
        } else {
            cameraTouches.remove(pointerId)
        }
        if (cameraTouches.isEmpty()) {
            if (allowWorldTap && wasCameraInteraction && !cameraTouchMoved) model.requestUsernameEdit()
            cameraTouchMoved = false
        }
    }

    private fun updatePinchDistance() {
        if (cameraTouches.size < 2) {
            previousPinchDistance = null
            return
        }
        val points = cameraTouches.values.take(2)
        val dx = points[0].x - points[1].x
        val dy = points[0].y - points[1].y
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        previousPinchDistance?.let { previous ->
            model.zoomBy((1f + (distance - previous) / 100f).coerceAtLeast(.1f))
            cameraTouchMoved = true
        }
        previousPinchDistance = distance
    }

    private fun updateUiViewport() {
        if (width <= 0 || height <= 0) return
        model.setUiViewport(
            width = width / density,
            height = height / density,
            scale = density,
            safeTop = safeInsets.top / density,
            safeRight = safeInsets.right / density,
            safeBottom = safeInsets.bottom / density,
            safeLeft = safeInsets.left / density,
        )
    }
}

@Composable
private fun GameAtmosphere(isSession: Boolean) {
    Box(
        Modifier.fillMaxSize().alpha(if (isSession) .85f else 1f).background(
            Brush.verticalGradient(
                listOf(
                    Color(0xFF173B43).copy(alpha = if (isSession) .32f else .18f),
                    Color.Transparent,
                    Color(0xFF132E30).copy(alpha = if (isSession) .30f else .22f),
                ),
            ),
        ),
    )
}

@Composable
private fun PresenceNotice(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth().widthIn(max = 390.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        tonalElevation = 4.dp,
    ) {
        Text(
            message,
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(Color(0xFF193034)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(color = Color.White)
            Text("LOADING FIRST GAME", color = Color.White.copy(.76f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        }
    }
}

@Composable
private fun ErrorScreen(message: String?, retry: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF193034)).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("GAME UNAVAILABLE", color = Color.White.copy(.62f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text("Couldn’t load the first game.", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(message.orEmpty(), color = Color.White.copy(.72f), fontSize = 15.sp)
            Button(
                onClick = retry,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(.20f), contentColor = Color.White),
            ) { Text("TRY AGAIN", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp) }
        }
    }
}
