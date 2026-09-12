package dev.andrewarrow.cubacadabra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.andrewarrow.cubacadabra.app.AppUiState
import dev.andrewarrow.cubacadabra.app.AppViewModel
import dev.andrewarrow.cubacadabra.game.AppMorphAsset
import dev.andrewarrow.cubacadabra.game.ClientConfiguration
import dev.andrewarrow.cubacadabra.game.GameViewModel
import kotlinx.coroutines.isActive
import java.net.URL

@Composable
internal fun MorphSelectionScreen(state: AppUiState, model: AppViewModel, gameModel: GameViewModel) {
    var tab by remember { mutableStateOf(0) }
    LaunchedEffect(gameModel) {
        model.beginMorphEdit()
        var previous = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (previous != 0L) gameModel.tickMorphPreview(now)
                previous = now
                gameModel.draw(avatarPreviewMode = true)
            }
        }
    }
    val appearance = state.appearance
    val previewReady by gameModel.morphPreviewReady.collectAsStateWithLifecycle()
    LaunchedEffect(appearance.draftBase, appearance.draftParts, appearance.draftFace, appearance.draftRenderJSON, appearance.assets) {
        val ids = mutableSetOf<String>().apply {
            appearance.draftBase?.let { add(it) }
            appearance.draftParts.forEach { add(it) }
            appearance.draftFace?.let { add(it) }
        }
        val baseURL = URL(ClientConfiguration.backendApiUrl.trimEnd('/') + "/")
        val packURLs = appearance.assets
            .filter { it.id in ids }
            .mapNotNull { asset -> asset.artifactURL?.let { path -> runCatching { URL(baseURL, path) }.getOrNull() } }
        gameModel.setMorphPreviewAppearance(model.draftAppearanceJSON(), packURLs)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("CHOOSE YOUR MORPH", style = MaterialTheme.typography.labelLarge)
            Text("Choose a starter, then customize the details.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = .75f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { tab = 0 }, modifier = Modifier.weight(1f)) { Text("STARTERS") }
                OutlinedButton(onClick = { tab = 1 }, modifier = Modifier.weight(1f)) { Text("CUSTOMIZE") }
            }
            if (appearance.isLoading) CircularProgressIndicator()
            else if (tab == 0) {
                appearance.presets.forEach { preset ->
                    OutlinedButton(onClick = { model.chooseMorphPreset(preset.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)) {
                        val selected = preset.id == appearance.draftPresetID
                        Column(Modifier.fillMaxWidth()) { Text(preset.displayName); Text(if (selected) "Selected" else "Ready to play", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            } else {
                appearance.assets.filter { it.kind != "base" }.groupBy { it.kind }.toSortedMap().forEach { (kind, assets) ->
                    MorphPartMenu(kind, assets, appearance, model)
                }
            }
            Text("PREVIEW", style = MaterialTheme.typography.labelLarge)
            if (!previewReady) {
                CircularProgressIndicator(modifier = Modifier.padding(vertical = 80.dp))
            } else {
                RustGameSurface(gameModel, avatarPreviewMode = true)
                    .fillMaxWidth()
                    .heightIn(min = 260.dp)
            }
            Text(appearance.presets.firstOrNull { it.id == appearance.draftPresetID }?.displayName ?: "Custom morph", style = MaterialTheme.typography.labelSmall)
            Text("Your morph is ready to try.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = .75f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("walk", "jump", "turn").forEach { action -> OutlinedButton(onClick = { gameModel.playMorphPreview(action) }) { Text(action.replaceFirstChar { it.uppercase() }) } }
            }
            appearance.feedback?.let { Text(it.message, color = if (it.kind == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            Button(onClick = model::saveMorph, enabled = appearance.draftCanSave && !appearance.isSaving, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { if (appearance.isSaving) CircularProgressIndicator() else Text("SAVE MORPH") }
        }
    }
}

@Composable
private fun MorphPartMenu(kind: String, assets: List<AppMorphAsset>, appearance: dev.andrewarrow.cubacadabra.game.AppAppearanceSnapshot, model: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val selected = assets.firstOrNull { appearance.draftParts.contains(it.id) }?.displayName ?: "None"
    Column {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("${kind.replace('-', ' ').replaceFirstChar { it.uppercase() }}: $selected") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("None") }, onClick = { appearance.draftParts.firstOrNull { id -> assets.any { it.id == id } }?.let(model::clearMorphPart); expanded = false })
            assets.forEach { asset -> androidx.compose.material3.DropdownMenuItem(text = { Text(asset.displayName) }, onClick = { model.setMorphPart(asset.id); expanded = false }) }
        }
    }
}
