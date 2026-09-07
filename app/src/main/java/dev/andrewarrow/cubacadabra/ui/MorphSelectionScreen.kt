package dev.andrewarrow.cubacadabra.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.andrewarrow.cubacadabra.R
import dev.andrewarrow.cubacadabra.game.GameUiState
import dev.andrewarrow.cubacadabra.game.GameViewModel

internal data class MorphOption(
    val bodyID: String,
    val label: String,
    @DrawableRes val imageResource: Int,
) {
    companion object {
        val available = listOf(
            MorphOption("cuba:person.v1", "Boy", R.drawable.player_boy_001),
            MorphOption("cuba:person-girl.v1", "Girl", R.drawable.player_girl_001),
            MorphOption("cuba:person-nb.v1", "Nonbinary", R.drawable.player_nb_001),
        )

        fun fromBodyID(bodyID: String?): MorphOption =
            available.firstOrNull { it.bodyID == bodyID } ?: available.first()
    }
}

@Composable
internal fun MorphSelectionScreen(state: GameUiState, model: GameViewModel) {
    var selectedBodyID by remember { mutableStateOf(MorphOption.fromBodyID(state.authUser?.bodyID).bodyID) }

    LaunchedEffect(state.authUser?.bodyID) {
        selectedBodyID = MorphOption.fromBodyID(state.authUser?.bodyID).bodyID
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp)
                .widthIn(max = 620.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text(
                "CHOOSE YOUR MORPH",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = .62f),
            )
            Text(
                "Choose how you appear in a game.",
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = .78f),
            )

            MorphGrid(
                selectedBodyID = selectedBodyID,
                enabled = !state.morphSaving,
                onSelect = {
                    selectedBodyID = it
                    model.clearMorphMessage()
                },
            )

            state.morphMessage?.let { message ->
                Text(
                    message,
                    color = if (state.morphMessageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Button(
                onClick = { model.saveMorph(selectedBodyID) },
                enabled = !state.morphSaving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (state.morphSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp).height(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("SAVE MORPH", fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                }
            }
        }
    }
}

@Composable
private fun MorphGrid(
    selectedBodyID: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        val columnCount = when {
            maxWidth >= 520.dp -> 3
            maxWidth >= 300.dp -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MorphOption.available.chunked(columnCount).forEach { rowOptions ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowOptions.forEach { option ->
                        MorphCard(
                            option = option,
                            selected = option.bodyID == selectedBodyID,
                            enabled = enabled,
                            onSelect = onSelect,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columnCount - rowOptions.size) {
                        Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MorphCard(
    option: MorphOption,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
    }
    Surface(
        modifier = modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, borderColor, shape)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { onSelect(option.bodyID) },
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) .11f else .055f),
    ) {
        Column(
            Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(15.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f),
            ) {
                Image(
                    painter = painterResource(option.imageResource),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(190.dp).padding(horizontal = 8.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Text(
                if (selected) "${option.label}  ✓" else option.label,
                modifier = Modifier.heightIn(min = 24.dp),
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
