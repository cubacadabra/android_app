package dev.andrewarrow.cubacadabra.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.andrewarrow.cubacadabra.R
import dev.andrewarrow.cubacadabra.app.AppUiState
import dev.andrewarrow.cubacadabra.app.AppViewModel
import dev.andrewarrow.cubacadabra.game.AppMorphAsset
import dev.andrewarrow.cubacadabra.game.AppMorphPreset
import dev.andrewarrow.cubacadabra.game.AppAppearanceSnapshot
import dev.andrewarrow.cubacadabra.game.ClientConfiguration
import dev.andrewarrow.cubacadabra.game.GameViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.URL

private data class MorphAssetGroup(val kind: String, val assets: List<AppMorphAsset>)
private data class MorphEditorCategory(val id: String, val title: String, val symbol: String, val kind: String?)

@Composable
internal fun MorphSelectionScreen(
    state: AppUiState,
    model: AppViewModel,
    gameModel: GameViewModel,
    onBack: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var selectedKind by remember { mutableStateOf<String?>(null) }
    var categoryPage by remember { mutableStateOf(0) }
    var starterPage by remember { mutableStateOf(0) }
    var assetPage by remember { mutableStateOf(0) }
    val appearance = state.appearance
    val previewReady by gameModel.morphPreviewReady.collectAsStateWithLifecycle()
    val assetGroups = appearance.assets
        .filter { it.kind != "base" }
        .groupBy { it.kind }
        .map { (kind, assets) -> MorphAssetGroup(kind, assets.sortedBy { it.displayName }) }
        .sortedBy { categoryOrder(it.kind) }
    val activeGroup = assetGroups.firstOrNull { it.kind == selectedKind } ?: assetGroups.firstOrNull()
    val categories = listOf(MorphEditorCategory("starters", "Starters", "✦", null)) + assetGroups.map {
        MorphEditorCategory(it.kind, categoryName(it.kind), categorySymbol(it.kind), it.kind)
    }
    val previewName = appearance.presets.firstOrNull { it.id == appearance.draftPresetID }?.displayName ?: "Custom morph"

    BackHandler(onBack = onBack)

    LaunchedEffect(gameModel) {
        model.loadAppearanceCatalog()
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

    LaunchedEffect(appearance.draftLoadoutJSON, appearance.assets) {
        val ids = buildSet {
            appearance.draftBase?.let(::add)
            addAll(appearance.draftParts)
            appearance.draftFace?.let(::add)
        }
        val baseUrl = URL(ClientConfiguration.backendApiUrl.trimEnd('/') + "/")
        val packUrls = ids.mapNotNull { id ->
            val asset = appearance.assets.firstOrNull { it.id == id }
                ?: error("Morph preview catalog is missing asset $id")
            if (asset.kind == "face") return@mapNotNull null
            val path = asset.artifactURL
                ?: error("Morph asset $id has no schema-5 artifact")
            runCatching { URL(baseUrl, path) }
                .getOrElse { error("Morph asset $id has an invalid schema-5 artifact URL") }
        }
        gameModel.setMorphPreviewLoadout(model.draftMorphLoadoutJSON(), packUrls)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                MorphSaveBar(
                    enabled = appearance.draftCanSave && !appearance.isSaving,
                    saving = appearance.isSaving,
                    onSave = model::saveMorph,
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack, modifier = Modifier.size(44.dp)) { Text("‹", fontSize = 30.sp) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Edit Morph", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(previewName, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .62f), fontSize = 13.sp, maxLines = 1)
                    }
                    Spacer(Modifier.size(44.dp))
                }

                MorphModePicker(tab = tab, onSelect = { value ->
                    tab = value
                    if (value == 1 && selectedKind == null) selectedKind = assetGroups.firstOrNull()?.kind
                })

                MorphPreviewStage(gameModel, previewReady, Modifier.fillMaxWidth())

                Surface(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .07f)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(42.dp).height(5.dp).background(MaterialTheme.colorScheme.onSurface.copy(.22f), RoundedCornerShape(4.dp)))
                        }
                        MorphCategoryStrip(
                            categories = categories,
                            tab = tab,
                            activeKind = activeGroup?.kind,
                            page = categoryPage,
                            onPageChange = { categoryPage = it },
                            onSelect = { category ->
                                if (category.kind == null) tab = 0
                                else {
                                    tab = 1
                                    selectedKind = category.kind
                                    assetPage = 0
                                }
                            },
                        )
                        if (appearance.isLoading) {
                            Column(
                                Modifier.fillMaxWidth().heightIn(min = 190.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator()
                                Text("Loading morphs…", modifier = Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold)
                            }
                        } else if (tab == 0) {
                            MorphStarterList(appearance.presets, appearance.draftPresetID, starterPage, appearance.isSaving, { starterPage = it }, model::chooseMorphPreset)
                        } else {
                            MorphCustomizeList(activeGroup, appearance, assetPage, appearance.isSaving, { assetPage = it }, model::setMorphPart, model::clearMorphPart)
                        }
                        appearance.feedback?.let { feedback ->
                            Text(
                                feedback.message,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                                color = if (feedback.kind == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MorphModePicker(tab: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 500.dp).background(MaterialTheme.colorScheme.onBackground.copy(.07f), CircleShape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MorphModeButton("✦  Starters", tab == 0, Modifier.weight(1f)) { onSelect(0) }
        MorphModeButton("☷  Customize", tab == 1, Modifier.weight(1f)) { onSelect(1) }
    }
}

@Composable
private fun MorphModeButton(title: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.heightIn(min = 42.dp).clip(CircleShape).clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground.copy(.72f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun MorphPreviewStage(gameModel: GameViewModel, previewReady: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier.heightIn(min = 250.dp, max = 320.dp).clip(shape).background(
            Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(.30f), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.secondary.copy(.24f))),
        ).border(1.dp, MaterialTheme.colorScheme.onBackground.copy(.12f), shape),
    ) {
        Box(Modifier.fillMaxSize().padding(2.dp).clip(shape)) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .08f)))
            RustGameSurface(gameModel, avatarPreviewMode = true, modifier = Modifier.fillMaxSize().clip(shape))
            if (!previewReady) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Text("Building your morph…", modifier = Modifier.padding(top = 12.dp), color = Color.White.copy(.76f), fontWeight = FontWeight.SemiBold)
                }
            }
            Text("✦  LIVE PREVIEW", modifier = Modifier.align(Alignment.TopStart).padding(14.dp).background(Color.Black.copy(.30f), CircleShape).padding(horizontal = 12.dp, vertical = 8.dp), color = Color.White.copy(.86f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Drag to orbit · Pinch to zoom", color = Color.White.copy(.75f), fontSize = 10.sp, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("Walk" to "walk", "Jump" to "jump", "Turn" to "turn").forEach { (label, action) ->
                        TextButton(onClick = { gameModel.playMorphPreview(action) }, modifier = Modifier.heightIn(min = 40.dp), colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MorphCategoryStrip(categories: List<MorphEditorCategory>, tab: Int, activeKind: String?, page: Int, onPageChange: (Int) -> Unit, onSelect: (MorphEditorCategory) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        val pageSize = if (maxWidth >= 600.dp) 8 else 4
        val count = pageCount(categories.size, pageSize)
        val safePage = page.coerceIn(0, count - 1)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.Center) {
            MorphPagerButton("‹", safePage > 0, "Previous categories") { onPageChange(safePage - 1) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                slicePage(categories, safePage, pageSize).forEach { category ->
                    MorphCategoryButton(category, if (category.kind == null) tab == 0 else tab == 1 && activeKind == category.kind) { onSelect(category) }
                }
            }
            MorphPagerButton("›", safePage + 1 < count, "Next categories") { onPageChange(safePage + 1) }
        }
    }
}

@Composable
private fun MorphCategoryButton(category: MorphEditorCategory, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(64.dp).heightIn(min = 72.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(44.dp).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(.10f), CircleShape), contentAlignment = Alignment.Center) {
            Text(category.symbol, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(.76f), fontSize = 20.sp)
        }
        Text(category.title, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(.62f), fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun MorphStarterList(presets: List<AppMorphPreset>, selectedPresetID: String?, page: Int, saving: Boolean, onPageChange: (Int) -> Unit, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MorphSectionHeader("Starters", "Pick a base, then make it yours.")
        if (presets.isEmpty()) {
            Text("No starter morphs are available right now.", modifier = Modifier.padding(horizontal = 20.dp, vertical = 36.dp), color = MaterialTheme.colorScheme.onSurface.copy(.62f))
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val pageSize = if (maxWidth >= 600.dp) 4 else 2
                val count = pageCount(presets.size, pageSize)
                val safePage = page.coerceIn(0, count - 1)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.Center) {
                    MorphPagerButton("‹", safePage > 0, "Previous starters") { onPageChange(safePage - 1) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        slicePage(presets, safePage, pageSize).forEachIndexed { index, preset ->
                            MorphStarterTile(preset, safePage * pageSize + index, preset.id == selectedPresetID, saving) { onSelect(preset.id) }
                        }
                    }
                    MorphPagerButton("›", safePage + 1 < count, "Next starters") { onPageChange(safePage + 1) }
                }
            }
        }
    }
}

@Composable
private fun MorphStarterTile(preset: AppMorphPreset, index: Int, selected: Boolean, saving: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clickable(enabled = !saving, onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(158.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.onSurface.copy(if (selected) .14f else .075f)).border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(.08f), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            MorphThumbnail(preset.thumbnail, index, Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp))
            if (selected) Text("✓", modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(27.dp).background(MaterialTheme.colorScheme.primary, CircleShape), color = MaterialTheme.colorScheme.onPrimary, textAlign = TextAlign.Center, fontWeight = FontWeight.Black)
        }
        Text(preset.displayName, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun MorphCustomizeList(group: MorphAssetGroup?, appearance: AppAppearanceSnapshot, page: Int, saving: Boolean, onPageChange: (Int) -> Unit, onSet: (String) -> Unit, onClear: (String) -> Unit) {
    if (group == null) {
        Text("Customization options are unavailable right now.", modifier = Modifier.padding(horizontal = 20.dp, vertical = 36.dp), color = MaterialTheme.colorScheme.onSurface.copy(.62f))
        return
    }
    val options = listOf<AppMorphAsset?>(null) + group.assets
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MorphSectionHeader(categoryName(group.kind), "Choose the look that feels right.")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val pageSize = if (maxWidth >= 600.dp) 5 else 2
            val count = pageCount(options.size, pageSize)
            val safePage = page.coerceIn(0, count - 1)
            val selectedID = selectedAssetID(group.kind, group.assets, appearance)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.Center) {
                MorphPagerButton("‹", safePage > 0, "Previous ${categoryName(group.kind)} options") { onPageChange(safePage - 1) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    slicePage(options, safePage, pageSize).forEach { asset ->
                        if (asset == null) MorphNoneTile(selectedID == null, saving || selectedID == null) { selectedID?.let(onClear) }
                        else MorphAssetTile(asset, group.kind, asset.id == selectedID, saving) { onSet(asset.id) }
                    }
                }
                MorphPagerButton("›", safePage + 1 < count, "Next ${categoryName(group.kind)} options") { onPageChange(safePage + 1) }
            }
        }
    }
}

@Composable
private fun MorphNoneTile(selected: Boolean, disabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(108.dp).clickable(enabled = !disabled, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(if (selected) .14f else .075f)).border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(.08f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Text("⊘", fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurface.copy(.58f)) }
        Text("None", color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
    }
}

@Composable
private fun MorphAssetTile(asset: AppMorphAsset, kind: String, selected: Boolean, saving: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(108.dp).clickable(enabled = !saving, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(if (selected) .14f else .075f)).border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(.08f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            MorphThumbnail(asset.thumbnail, 0, Modifier.fillMaxSize().padding(10.dp), kind)
            if (selected) Text("✓", modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape), color = MaterialTheme.colorScheme.onPrimary, textAlign = TextAlign.Center, fontSize = 12.sp, fontWeight = FontWeight.Black)
        }
        Text(asset.displayName, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 2)
    }
}

@Composable
private fun MorphSectionHeader(title: String, detail: String) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(detail, color = MaterialTheme.colorScheme.onSurface.copy(.62f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MorphPagerButton(symbol: String, enabled: Boolean, description: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(width = 30.dp, height = 48.dp)) { Text(symbol, color = if (enabled) MaterialTheme.colorScheme.onSurface.copy(.78f) else MaterialTheme.colorScheme.onSurface.copy(.20f), fontSize = 26.sp) }
}

@Composable
private fun MorphSaveBar(enabled: Boolean, saving: Boolean, onSave: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .97f), tonalElevation = 5.dp, modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
        Button(onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 56.dp), colors = ButtonDefaults.buttonColors(disabledContainerColor = MaterialTheme.colorScheme.primary.copy(.28f)), shape = RoundedCornerShape(18.dp)) {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            else Text("SAVE MORPH", fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
        }
    }
}

@Composable
private fun MorphThumbnail(path: String?, fallbackIndex: Int, modifier: Modifier = Modifier, kind: String? = null) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { runCatching { thumbnailURL(path)?.openStream()?.use { stream -> BitmapFactory.decodeStream(stream) }?.asImageBitmap() }.getOrNull() }
    }
    if (bitmap != null) {
        Image(bitmap!!, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
    } else if (path != null) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary) }
    } else if (kind == null) {
        val resource = when (fallbackIndex % 3) { 1 -> R.drawable.player_boy_001; 2 -> R.drawable.player_girl_001; else -> R.drawable.player_nb_001 }
        Image(painterResource(resource), contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) { Text(categorySymbol(kind), color = MaterialTheme.colorScheme.primary.copy(.75f), fontSize = 30.sp) }
    }
}

private fun selectedAssetID(kind: String, assets: List<AppMorphAsset>, appearance: AppAppearanceSnapshot): String? {
    if (kind.equals("face", true) || kind.equals("identity", true)) return appearance.draftFace
    val selected = appearance.draftParts.toSet()
    return assets.firstOrNull { it.id in selected }?.id
}

private fun thumbnailURL(path: String?): URL? {
    if (path.isNullOrBlank()) return null
    val absolute = runCatching { URL(path) }.getOrNull()
    if (absolute != null && absolute.protocol in setOf("http", "https")) return absolute
    val base = runCatching { URL(ClientConfiguration.backendApiUrl.trimEnd('/') + "/") }.getOrNull() ?: return null
    return runCatching { URL(base, path) }.getOrNull()
}

private fun categoryName(kind: String): String = when (kind.lowercase()) {
    "body", "body_type" -> "Body"
    "clothes", "clothing", "outfit" -> "Clothing"
    "hair", "hairstyle" -> "Hair"
    "face", "identity" -> "Face"
    "accessory", "accessories", "equipment" -> "Accessories"
    else -> kind.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }
}

private fun categorySymbol(kind: String): String = when (kind.lowercase()) {
    "body", "body_type" -> "♙"
    "clothes", "clothing", "outfit" -> "▱"
    "hair", "hairstyle" -> "⌁"
    "face", "identity" -> "◉"
    "accessory", "accessories", "equipment" -> "◇"
    else -> "▦"
}

private fun categoryOrder(kind: String): String = when (kind.lowercase()) {
    "body", "body_type" -> "0"
    "clothes", "clothing", "outfit" -> "1"
    "hair", "hairstyle" -> "2"
    "face", "identity" -> "3"
    "accessory", "accessories", "equipment" -> "4"
    else -> "9-$kind"
}

private fun pageCount(itemCount: Int, pageSize: Int): Int = maxOf((itemCount + pageSize - 1) / pageSize, 1)

private fun <T> slicePage(items: List<T>, page: Int, pageSize: Int): List<T> {
    if (items.isEmpty()) return emptyList()
    val safePage = page.coerceIn(0, pageCount(items.size, pageSize) - 1)
    val start = safePage * pageSize
    return items.subList(start, minOf(start + pageSize, items.size))
}
