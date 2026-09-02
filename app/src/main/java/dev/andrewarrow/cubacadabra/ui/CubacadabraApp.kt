package dev.andrewarrow.cubacadabra.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.andrewarrow.cubacadabra.game.EnginePad
import dev.andrewarrow.cubacadabra.game.GameUiState
import dev.andrewarrow.cubacadabra.game.GameViewModel
import dev.andrewarrow.cubacadabra.game.WorldConnectionState
import kotlinx.coroutines.isActive

@Composable
fun CubacadabraApp(model: GameViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        when {
            state.isLoading -> LoadingScreen()
            state.errorMessage != null -> ErrorScreen(state.errorMessage, model::retry)
            else -> GameScreen(state, model)
        }
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

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    model.lookBy(pan.x, pan.y)
                    model.zoomBy(zoom)
                }
            }
            .pointerInput(state.settingsRoomState, state.usernameEditorOpen) {
                detectTapGestures { model.requestUsernameEdit() }
            },
    ) {
        RustGameSurface(model)
        if (state.worldId != "settings") GameAtmosphere(state.worldId != "lobby")
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            if (state.worldId != "settings") {
                GameHeader(state)
                state.presenceNotice?.let { notice ->
                    PresenceNotice(notice.message)
                }
            }
            Spacer(Modifier.weight(1f))
            GameControls(model, state.sprinting)
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
            shape = RoundedCornerShape(22.dp),
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
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        model.createRenderer(holder.surface, width.toFloat(), height.toFloat())
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        if (width > 0 && height > 0) {
                            model.createRenderer(holder.surface, width.toFloat(), height.toFloat())
                            model.resizeRenderer(width.toFloat(), height.toFloat())
                        }
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) = model.destroyRenderer()
                })
            }
        },
    )
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
private fun GameHeader(state: GameUiState) {
    val world = state.packageData?.worldDefinition(state.worldId)
    val frame = state.frame
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = .32f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(world?.scene?.eyebrow?.uppercase() ?: "CUBACADABRA", color = Color.White.copy(.64f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                    Text(world?.scene?.title ?: "First Game", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                    Text(world?.scene?.description.orEmpty(), color = Color.White.copy(.72f), fontSize = 14.sp, maxLines = 2)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (state.worldId == "lobby") "LOBBY" else "SESSION", color = Color.White.copy(.64f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text("${(frame?.remotePlayers ?: 0) + 1} PLAYERS", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(connectionColor(state.connectionState)))
                        Text(state.connectionState.label, color = Color.White.copy(.68f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .9.sp)
                    }
                }
            }
            if (state.worldId == "lobby") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    world?.launchPads?.forEachIndexed { index, pad ->
                        val live = frame?.pads?.getOrNull(index)
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(.09f)).padding(10.dp)) {
                            Text(pad.code, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                            Text(padStatus(live), color = Color.White.copy(.78f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresenceNotice(message: String) {
    Surface(Modifier.fillMaxWidth().padding(top = 10.dp), color = Color.White.copy(.90f), shape = RoundedCornerShape(22.dp)) {
        Text(message, Modifier.padding(horizontal = 14.dp, vertical = 12.dp), color = Color(0xFF173F43), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}

@Composable
private fun GameControls(model: GameViewModel, sprinting: Boolean) {
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Joystick(model)
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GameButton("JUMP") { model.jump() }
            GameButton(if (sprinting) "RUNNING" else "RUN", active = sprinting) { model.toggleSprinting() }
        }
    }
}

@Composable
private fun Joystick(model: GameViewModel) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.size(120.dp).pointerInput(Unit) {
            detectDragGestures(
                onDrag = { change, drag ->
                    change.consume()
                    offset += drag
                    val limit = 38f
                    val x = offset.x.coerceIn(-limit, limit)
                    val y = offset.y.coerceIn(-limit, limit)
                    model.setMove(x / limit, -y / limit)
                },
                onDragEnd = { offset = Offset.Zero; model.setMove(0f, 0f) },
                onDragCancel = { offset = Offset.Zero; model.setMove(0f, 0f) },
            )
        }, contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(108.dp).clip(CircleShape).background(Color.Black.copy(.30f)))
        Box(Modifier.size(48.dp).offset { IntOffset(offset.x.toInt(), offset.y.toInt()) }.clip(CircleShape).background(Color.White.copy(.84f)))
    }
}

@Composable
private fun GameButton(label: String, active: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(44.dp),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(if (active) .42f else .20f), contentColor = Color.White),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
    ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp) }
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
            GameButton("TRY AGAIN", onClick = retry)
        }
    }
}

private fun connectionColor(state: WorldConnectionState) = when (state) {
    WorldConnectionState.CONNECTED -> Color(0xFF8FE0A8)
    WorldConnectionState.CONNECTING, WorldConnectionState.RECONNECTING -> Color(0xFFF2C764)
    WorldConnectionState.DISCONNECTED -> Color(0xFFE98268)
}

private fun padStatus(pad: EnginePad?) = when {
    pad == null -> "READY"
    pad.seconds > 0f -> "${pad.occupants} · ${pad.seconds.toInt()}s"
    else -> "${pad.occupants} READY"
}
