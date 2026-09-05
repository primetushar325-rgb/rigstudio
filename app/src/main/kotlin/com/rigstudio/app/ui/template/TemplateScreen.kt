package com.rigstudio.app.ui.template

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rigstudio.app.R
import com.rigstudio.app.editor.TemplateUiState
import com.rigstudio.app.editor.TemplateViewModel
import com.rigstudio.app.ui.components.BusyIndicator
import com.rigstudio.app.ui.components.RigPrimaryButton
import com.rigstudio.app.ui.components.RigTopBar
import com.rigstudio.app.ui.components.SectionCard
import com.rigstudio.app.ui.components.StatusPill
import com.rigstudio.app.ui.theme.RigColors

/**
 * The bundled blank character sheet.
 *
 * RigStudio draws this template itself ([com.rigstudio.app.art.TemplateArt]) — there is no
 * third-party artwork anywhere in the app. Saving it produces a 2048×2048 transparent PNG the user
 * can open in any painting app, draw into the labelled slots, and import straight back.
 */
@Composable
fun TemplateScreen(
    viewModel: TemplateViewModel,
    onBack: () -> Unit,
    onImport: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        if (uri != null) viewModel.saveTo(uri)
    }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = RigColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            RigTopBar(
                title = stringResource(R.string.template_title),
                subtitle = "${state.sizeLabel} · ${state.countsLabel}",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PreviewCard(state)
            GuidanceCard(state)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RigPrimaryButton(
                    text = stringResource(R.string.template_save),
                    onClick = { saver.launch(DEFAULT_FILE_NAME) },
                    enabled = !state.saving && !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (state.saving) "Writing PNG…" else
                        "Saved at exactly ${state.sheetWidth}×${state.sheetHeight} with a transparent background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RigColors.TextDisabled,
                )
                Surface(
                    color = RigColors.SurfaceRaised,
                    shape = MaterialTheme.shapes.small,
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Already drew one?",
                                style = MaterialTheme.typography.titleSmall,
                                color = RigColors.TextPrimary,
                            )
                            Text(
                                text = "Import a finished sheet and rig it now.",
                                style = MaterialTheme.typography.bodySmall,
                                color = RigColors.TextSecondary,
                            )
                        }
                        Text(
                            text = "Import",
                            style = MaterialTheme.typography.labelLarge,
                            color = RigColors.Primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(state: TemplateUiState) {
    var zoom by remember { mutableFloatStateOf(1f) }
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 6f)
    }

    SectionCard(title = "Blank sheet") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("PNG · RGBA", RigColors.Primary)
            StatusPill(state.sizeLabel, RigColors.Secondary)
            StatusPill("layout v${state.version}", RigColors.Tertiary)
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(RigColors.StageDefault)
                .transformable(state = transformState),
            contentAlignment = Alignment.Center,
        ) {
            val preview = state.preview
            when {
                state.loading && preview == null -> BusyIndicator("Drawing the template…")
                preview != null && !preview.isRecycled -> Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "Blank RigStudio character sheet",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                        },
                )
                else -> Text(
                    text = "Preview unavailable on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RigColors.TextSecondary,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (zoom > 1f) "Pinch to zoom · %.1f×".format(zoom) else "Pinch to zoom into any slot",
            style = MaterialTheme.typography.labelSmall,
            color = RigColors.TextDisabled,
        )
    }
}

@Composable
private fun GuidanceCard(state: TemplateUiState) {
    SectionCard(title = "How to draw into it") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GuidanceRow(
                index = "1",
                title = "Save the template",
                body = "You get a ${state.sheetWidth}×${state.sheetHeight} transparent PNG. Open it in any " +
                    "painting app that keeps layers and transparency.",
            )
            GuidanceRow(
                index = "2",
                title = "Draw inside the labelled slots",
                body = "Every part has a fixed rectangle and a pivot mark. Keep your artwork inside its " +
                    "slot: RigStudio reads those exact coordinates and nothing else. Guide lines are " +
                    "drawn outside the slots, so they are never picked up as artwork.",
            )
            GuidanceRow(
                index = "3",
                title = "Fill the required slots",
                body = "Slots outlined in teal (${state.requiredCount} of ${state.slotCount}) must contain artwork: " +
                    "head, torso, both thighs and both shins. Everything else — hands, feet, faces, " +
                    "profiles, back — is optional and only produces a note.",
            )
            GuidanceRow(
                index = "4",
                title = "Export as PNG and import",
                body = "Save without flattening, then import the file. RigStudio validates, extracts, " +
                    "trims, rigs and saves the character — offline, in under a second.",
            )
        }
    }
}

@Composable
private fun GuidanceRow(index: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .height(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(RigColors.SurfaceVariant)
                .padding(horizontal = 9.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(index, style = MaterialTheme.typography.labelLarge, color = RigColors.Primary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = RigColors.TextPrimary)
            Spacer(Modifier.height(3.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = RigColors.TextSecondary)
        }
    }
}

private const val DEFAULT_FILE_NAME = "rigstudio-character-sheet.png"
