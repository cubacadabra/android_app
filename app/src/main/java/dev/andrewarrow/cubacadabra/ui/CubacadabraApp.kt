package dev.andrewarrow.cubacadabra.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Log
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.andrewarrow.cubacadabra.app.AppUiState
import dev.andrewarrow.cubacadabra.app.AppViewModel
import dev.andrewarrow.cubacadabra.game.GameUiState
import dev.andrewarrow.cubacadabra.game.GameViewModel
import kotlinx.coroutines.isActive

@Composable
fun CubacadabraApp(model: GameViewModel = viewModel(), appModel: AppViewModel = viewModel()) {
    val appState by appModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(model, appModel) {
        appModel.start()
        appModel.gameSession.collect { model.applyAccountSession(it) }
    }
    LaunchedEffect(appState.authUser?.id) {
        if (appState.authUser?.id != null) appModel.loadBlockedUsers()
    }
    LaunchedEffect(appState.isRestoring) {
        if (!appState.isRestoring && !appState.isAuthenticated) model.load()
    }
    val state by model.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    LaunchedEffect(appState.isRestoring, state.isLoading, state.errorMessage, state.isMainMenu) {
        val orientation = if (appState.isRestoring || state.isLoading || state.errorMessage != null || state.isMainMenu) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        if (activity?.requestedOrientation != orientation) {
            activity?.requestedOrientation = orientation
        }
    }
    LaunchedEffect(state.isLoading, state.errorMessage, state.isMainMenu) {
        Log.d(
            "CubacadabraApp",
            "render mode loading=${state.isLoading} error=${state.errorMessage != null} mainMenu=${state.isMainMenu}",
        )
    }
    Box(Modifier.fillMaxSize()) {
        when {
            appState.isRestoring -> LoadingScreen()
            state.isMainMenu || state.isLoading || state.errorMessage != null -> MainMenuScreen(appModel, model)
            else -> GameScreen(state, model)
        }
        if (appState.loginDialogOpen) LoginDialog(appState, appModel)
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
                        modifier = Modifier.align(Alignment.TopStart).padding(top = 8.dp, start = 8.dp),
                    )
                }
            }
        }
        if (state.usernameEditorOpen) UsernameEditorDialog(state, model)
    }
}

@Composable
private fun LoginDialog(state: AppUiState, model: AppViewModel) {
    var emailMode by remember(state.loginDialogOpen) { mutableStateOf(false) }
    var email by remember { mutableStateOf("play-review@cubacadabra.com") }
    var password by remember { mutableStateOf("testing") }

    AlertDialog(
        onDismissRequest = model::dismissLoginDialog,
        title = { Text("Sign in to cubacadabra") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (emailMode) {
                    TextButton(
                        onClick = { emailMode = false },
                        enabled = !state.loginInProgress,
                    ) { Text("OTHER SIGN-IN OPTIONS") }
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        enabled = !state.loginInProgress,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !state.loginInProgress,
                    )
                    state.loginErrorMessage?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                } else {
                    Text("Choose how you want to continue.")
                    Button(
                        onClick = model::startGoogleSignIn,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !state.loginInProgress,
                    ) { Text("CONTINUE WITH GOOGLE", fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = { emailMode = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !state.loginInProgress,
                    ) { Text("USE EMAIL INSTEAD", fontWeight = FontWeight.Bold) }
                }
            }
        },
        confirmButton = {
            if (emailMode) {
                Button(
                    onClick = { model.signInWithEmail(email, password) },
                    enabled = !state.loginInProgress && email.isNotBlank() && password.isNotEmpty(),
                ) {
                    if (state.loginInProgress) CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    else Text("SIGN IN", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = model::dismissLoginDialog, enabled = !state.loginInProgress) { Text("CANCEL") }
        },
    )
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
        modifier.widthIn(max = 280.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .82f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Text(
            message,
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
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
            Text("RESTORING ACCOUNT", color = Color.White.copy(.76f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
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
