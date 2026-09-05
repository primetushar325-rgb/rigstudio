package com.rigstudio.app.ui.import

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rigstudio.app.R
import com.rigstudio.app.editor.ImportNavigation
import com.rigstudio.app.editor.ImportViewModel
import com.rigstudio.app.ui.components.BusyIndicator
import com.rigstudio.app.ui.components.FieldLabel
import com.rigstudio.app.ui.components.IssueList
import com.rigstudio.app.ui.components.PanelSpacer
import com.rigstudio.app.ui.components.RigPrimaryButton
import com.rigstudio.app.ui.components.RigSecondaryButton
import com.rigstudio.app.ui.components.RigTextButton
import com.rigstudio.app.ui.components.RigTopBar
import com.rigstudio.app.ui.components.SectionCard
import com.rigstudio.app.ui.components.StatusPill
import com.rigstudio.app.ui.theme.RigColors
import com.rigstudio.core.template.CharacterSheetTemplate

/**
 * Import: pick a finished character sheet, see exactly what RigStudio found in it, then build and
 * save the character.
 *
 * The screen answers three questions before anything is written to disk — *is the sheet the right
 * size*, *which slots actually hold artwork*, and *can the right-facing profile be mirrored* — so a
 * failed import is never a surprise.
 */
@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    onCharacterReady: (String) -> Unit,
    onBack: () -> Unit,
    onOpenTemplate: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onSheetPicked(uri)
    }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(state.navigation) {
        when (val navigation = viewModel.consumeNavigation()) {
            is ImportNavigation.Editor -> onCharacterReady(navigation.projectId)
            ImportNavigation.Library -> onBack()
            ImportNavigation.Template -> onOpenTemplate()
            null -> Unit
        }
    }

    Scaffold(
        containerColor = RigColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            RigTopBar(
                title = stringResource(R.string.import_title),
                subtitle = when {
                    state.importing -> state.stageLabel
                    state.analyzing -> "Reading the sheet…"
                    state.analysis != null -> "${state.fillLabel} · ${state.dimensionLabel}"
                    else -> "${CharacterSheetTemplate.SHEET_WIDTH}×${CharacterSheetTemplate.SHEET_HEIGHT} transparent PNG"
                },
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
        ) {
            if (state.importing) {
                ImportProgressCard(state.stageLabel, state.progress)
                PanelSpacer()
            }

            SheetPickerCard(
                hasSheet = state.hasSheet,
                analyzing = state.analyzing,
                preview = state.preview,
                onPick = { picker.launch(arrayOf("image/png", "image/*")) },
                onClear = viewModel::clearSheet,
            )
            PanelSpacer()

            val analysis = state.analysis
            if (analysis != null) {
                FindingsCard(analysis, state.dimensionLabel, state.sizeLabel)
                PanelSpacer()

                MirrorCard(
                    available = state.mirrorAvailable,
                    checked = state.mirrorSideView,
                    onCheckedChange = viewModel::onMirrorSideViewChanged,
                )
                PanelSpacer()
            }

            NameCard(
                name = state.characterName,
                enabled = state.hasSheet && !state.importing,
                onNameChange = viewModel::onNameChanged,
            )
            PanelSpacer()

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RigPrimaryButton(
                    text = stringResource(R.string.import_open_editor),
                    onClick = viewModel::importNow,
                    enabled = state.canImport,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.hasSheet && analysis != null && !analysis.isRiggable) {
                    Text(
                        text = analysis.headline
                            ?: "Fix the errors above and pick the sheet again — required front parts must contain artwork.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RigColors.Error,
                    )
                }
                RigSecondaryButton(
                    text = stringResource(R.string.home_sample),
                    onClick = viewModel::importSampleCharacter,
                    enabled = !state.importing && !state.analyzing,
                    modifier = Modifier.fillMaxWidth(),
                )
                RigTextButton(
                    text = stringResource(R.string.home_template),
                    onClick = onOpenTemplate,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun ImportProgressCard(label: String, progress: Float) {
    SectionCard(title = "Building your character") {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = RigColors.Primary,
            trackColor = RigColors.SurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = RigColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Slots are read from fixed coordinates, trimmed, pivoted and rigged. " +
                "No analysis, no network — this is deterministic.",
            style = MaterialTheme.typography.bodySmall,
            color = RigColors.TextDisabled,
        )
    }
}

@Composable
private fun SheetPickerCard(
    hasSheet: Boolean,
    analyzing: Boolean,
    preview: Bitmap?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    SectionCard(
        title = "1 · Character sheet",
        trailing = {
            if (hasSheet) {
                RigTextButton("Clear", onClear)
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(RigColors.StageDefault),
            contentAlignment = Alignment.Center,
        ) {
            when {
                analyzing -> BusyIndicator("Decoding 2048 × 2048 pixels…")
                preview != null && !preview.isRecycled -> Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "Picked character sheet",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                )
                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .background(RigColors.SurfaceVariant, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, null, tint = RigColors.Primary)
                    }
                    Text(
                        text = stringResource(R.string.import_pick_png),
                        style = MaterialTheme.typography.titleSmall,
                        color = RigColors.TextPrimary,
                    )
                    Text(
                        text = "Drawn in any painting app, saved as a transparent PNG.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RigColors.TextSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        RigPrimaryButton(
            text = if (hasSheet) "Choose a different sheet" else "Choose sheet PNG",
            onClick = onPick,
            enabled = !analyzing,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FindingsCard(
    analysis: com.rigstudio.app.pipeline.ImportAnalysis,
    dimensionLabel: String,
    sizeLabel: String,
) {
    SectionCard(title = "2 · What RigStudio found") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                text = if (analysis.isRiggable) "Ready to rig" else "Not riggable",
                color = if (analysis.isRiggable) RigColors.Primary else RigColors.Error,
            )
            StatusPill(dimensionLabel, RigColors.Secondary)
            StatusPill(sizeLabel, RigColors.Secondary)
        }
        Spacer(Modifier.height(14.dp))

        FieldLabel("Slots with artwork")
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { analysis.fillRatio },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = if (analysis.isRiggable) RigColors.Primary else RigColors.Tertiary,
            trackColor = RigColors.SurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${analysis.filledSlots} of ${analysis.totalSlots} slots · views found: ${analysis.viewLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = RigColors.TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${analysis.availableExpressions.size} expressions · " +
                "${analysis.availableMouthShapes.size} mouth shapes",
            style = MaterialTheme.typography.bodySmall,
            color = RigColors.TextSecondary,
        )

        if (analysis.issues.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            FieldLabel("Findings")
            Spacer(Modifier.height(8.dp))
            IssueList(analysis.issues)
        }
    }
}

@Composable
private fun MirrorCard(available: Boolean, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SectionCard(title = "3 · Side views") {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Mirror the left profile to build the right one",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (available) RigColors.TextPrimary else RigColors.TextDisabled,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (available) {
                        "You drew the left-facing profile but not the right-facing one. Mirroring is a " +
                            "geometry flip of your own artwork — nothing is invented."
                    } else {
                        "Offered only when the left profile is complete and the right profile is empty. " +
                            "Without profile artwork, side animations stay disabled and the front view " +
                            "still works."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = RigColors.TextSecondary,
                )
            }
            Switch(
                checked = checked && available,
                onCheckedChange = onCheckedChange,
                enabled = available,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = RigColors.OnPrimary,
                    checkedTrackColor = RigColors.Primary,
                    uncheckedThumbColor = RigColors.TextSecondary,
                    uncheckedTrackColor = RigColors.SurfaceVariant,
                    uncheckedBorderColor = RigColors.Outline,
                ),
            )
        }
    }
}

@Composable
private fun NameCard(name: String, enabled: Boolean, onNameChange: (String) -> Unit) {
    SectionCard(title = "4 · Name") {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            enabled = enabled,
            singleLine = true,
            label = { Text("Character name") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = RigColors.TextPrimary,
                unfocusedTextColor = RigColors.TextPrimary,
                disabledTextColor = RigColors.TextDisabled,
                focusedBorderColor = RigColors.Primary,
                unfocusedBorderColor = RigColors.Outline,
                focusedLabelColor = RigColors.Primary,
                unfocusedLabelColor = RigColors.TextSecondary,
                cursorColor = RigColors.Primary,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Used for the project and for export file names. Stored on this device only.",
            style = MaterialTheme.typography.bodySmall,
            color = RigColors.TextDisabled,
        )
    }
}
