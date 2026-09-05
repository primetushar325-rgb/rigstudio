package com.rigstudio.app.ui.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rigstudio.app.R
import com.rigstudio.app.editor.EDITOR_BACKGROUND_PRESETS
import com.rigstudio.app.editor.ExportUiState
import com.rigstudio.app.editor.ExportViewModel
import com.rigstudio.app.export.ExportResult
import com.rigstudio.app.ui.components.ChipStrip
import com.rigstudio.app.ui.components.FieldLabel
import com.rigstudio.app.ui.components.IssueList
import com.rigstudio.app.ui.components.RigChip
import com.rigstudio.app.ui.components.RigPrimaryButton
import com.rigstudio.app.ui.components.RigSecondaryButton
import com.rigstudio.app.ui.components.RigTextButton
import com.rigstudio.app.ui.components.RigTopBar
import com.rigstudio.app.ui.components.SectionCard
import com.rigstudio.app.ui.components.StatusPill
import com.rigstudio.app.ui.theme.RigColors
import com.rigstudio.core.anim.AnimationLibrary
import com.rigstudio.core.anim.ClipCategory
import com.rigstudio.core.export.ExportFormat
import com.rigstudio.core.export.ExportFrameRate
import com.rigstudio.core.export.ExportLimits
import com.rigstudio.core.export.ExportResolution
import com.rigstudio.core.export.ExportSettings
import com.rigstudio.core.extract.SheetIssue
import com.rigstudio.core.extract.SheetIssueLevel
import com.rigstudio.core.model.ViewKind
import kotlin.math.roundToInt

/**
 * Export: choose exactly what to render, watch it render, then see the file **validated** before
 * RigStudio offers to save, share or open it.
 *
 * Encoding happens on device with MediaCodec + MediaMuxer (H.264 in MP4). There is no FFmpeg, no
 * screen recording and no network — the export works in airplane mode.
 */
@Composable
fun ExportScreen(
    viewModel: ExportViewModel,
    projectId: String,
    seed: ExportSettings?,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onAudioPicked(uri)
    }
    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4"),
    ) { uri ->
        if (uri != null) viewModel.saveTo(uri)
    }

    LaunchedEffect(projectId) { viewModel.load(projectId, seed) }

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
                title = stringResource(R.string.export_title),
                subtitle = state.characterName.ifBlank { "Preparing…" },
                onBack = onBack,
            )
        },
        bottomBar = {
            if (!state.running) {
                Surface(
                    color = RigColors.Surface,
                    border = BorderStroke(1.dp, RigColors.OutlineSoft),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        val blocking = state.blockingMessage
                        if (blocking != null) {
                            Text(
                                text = blocking,
                                style = MaterialTheme.typography.bodySmall,
                                color = RigColors.Error,
                                modifier = Modifier.padding(bottom = 10.dp),
                            )
                        }
                        RigPrimaryButton(
                            text = stringResource(R.string.export_start),
                            onClick = viewModel::startExport,
                            enabled = state.canExport,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = state.frameLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = RigColors.TextDisabled,
                            )
                            Text(
                                text = state.estimateLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = RigColors.TextDisabled,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.running || state.progress != null) {
                ProgressCard(state, viewModel::cancelExport)
            }

            state.result?.let { result ->
                ResultCard(
                    result = result,
                    onSave = {
                        val fileName = result.file?.name ?: "rigstudio-export.mp4"
                        savePicker.launch(fileName)
                    },
                    onShare = { viewModel.share() },
                    onOpen = { viewModel.openResult() },
                    onDismiss = viewModel::reset,
                )
            }

            ClipAndViewCard(
                state = state,
                onClip = viewModel::selectClip,
                onView = viewModel::selectView,
            )

            FormatCard(
                format = state.settings.format,
                onFormat = viewModel::setFormat,
            )

            QualityCard(
                resolution = state.settings.resolution,
                frameRate = state.settings.frameRate,
                onResolution = viewModel::setResolution,
                onFrameRate = viewModel::setFrameRate,
            )

            DurationCard(
                duration = state.settings.durationSeconds,
                speed = state.settings.speed,
                clipName = AnimationLibrary.byId(state.settings.clipId)?.name ?: "clip",
                loopLength = AnimationLibrary.byId(state.settings.clipId)?.durationSeconds ?: 1f,
                onDuration = viewModel::setDuration,
                onSpeed = viewModel::setSpeed,
                onOneLoop = viewModel::setOneLoop,
            )

            BackgroundCard(
                transparent = state.settings.transparentBackground,
                argb = state.settings.backgroundArgb,
                format = state.settings.format,
                onPreset = viewModel::setBackgroundArgb,
                onTransparent = viewModel::setTransparentBackground,
            )

            AudioCard(
                hasAudio = state.hasAudio,
                label = state.audioLabel,
                format = state.settings.format,
                onPick = { audioPicker.launch(arrayOf("audio/*")) },
                onClear = viewModel::clearAudio,
            )

            PreFlightCard(state)
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun ProgressCard(state: ExportUiState, onCancel: () -> Unit) {
    SectionCard(title = "Rendering") {
        LinearProgressIndicator(
            progress = { state.fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = RigColors.Primary,
            trackColor = RigColors.SurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = state.progressLabel,
            style = MaterialTheme.typography.titleSmall,
            color = RigColors.TextPrimary,
        )
        val progress = state.progress
        if (progress != null && progress.framesTotal > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${progress.framesDone} / ${progress.framesTotal} frames",
                style = MaterialTheme.typography.bodySmall,
                color = RigColors.TextSecondary,
            )
        }
        Spacer(Modifier.height(12.dp))
        RigSecondaryButton("Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ResultCard(
    result: ExportResult,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    SectionCard(
        title = if (result.succeeded) stringResource(R.string.export_success) else stringResource(R.string.export_failed),
        trailing = {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = RigColors.TextSecondary)
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                text = if (result.succeeded && result.allChecksPassed) "VALIDATED" else
                    if (result.succeeded) "CHECK WARNINGS" else "FAILED",
                color = when {
                    result.succeeded && result.allChecksPassed -> RigColors.Primary
                    result.succeeded -> RigColors.Tertiary
                    else -> RigColors.Error
                },
            )
            StatusPill(result.format.displayName, RigColors.Secondary)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = result.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = RigColors.TextPrimary,
            fontWeight = FontWeight.Medium,
        )
        result.message?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = RigColors.TextSecondary)
        }

        if (result.checks.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            FieldLabel("Post-export validation")
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                result.checks.forEach { check ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(
                                    if (check.passed) RigColors.Primary.copy(alpha = 0.18f)
                                    else RigColors.Error.copy(alpha = 0.18f),
                                )
                                .border(
                                    1.dp,
                                    if (check.passed) RigColors.Primary else RigColors.Error,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (check.passed) Icons.Filled.Check else Icons.Filled.Close,
                                contentDescription = null,
                                tint = if (check.passed) RigColors.Primary else RigColors.Error,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = check.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = RigColors.TextPrimary,
                            )
                            Text(
                                text = check.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = RigColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        }

        if (result.succeeded) {
            Spacer(Modifier.height(16.dp))
            RigPrimaryButton(stringResource(R.string.share), onShare, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RigSecondaryButton(stringResource(R.string.open), onOpen, Modifier.weight(1f))
                RigSecondaryButton("Save to…", onSave, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The file also stays in the character's own export folder on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = RigColors.TextDisabled,
            )
        }
    }
}

@Composable
private fun ClipAndViewCard(
    state: ExportUiState,
    onClip: (String) -> Unit,
    onView: (ViewKind) -> Unit,
) {
    SectionCard(title = "Animation & view") {
        FieldLabel(stringResource(R.string.editor_view))
        Spacer(Modifier.height(6.dp))
        ChipStrip {
            ViewKind.entries.forEach { view ->
                RigChip(
                    label = view.displayName,
                    selected = view == state.settings.view,
                    enabled = view in state.views,
                    onClick = { onView(view) },
                    sublabel = if (view in state.views) null else "not drawn",
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        val grouped = state.clips.groupBy { it.category }
        ClipCategory.entries.forEach { category ->
            val clips = grouped[category] ?: return@forEach
            FieldLabel(category.displayName)
            Spacer(Modifier.height(6.dp))
            ChipStrip {
                clips.forEach { clip ->
                    RigChip(
                        label = clip.name,
                        selected = clip.id == state.settings.clipId,
                        onClick = { onClip(clip.id) },
                        sublabel = "%.1fs".format(clip.durationSeconds),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun FormatCard(format: ExportFormat, onFormat: (ExportFormat) -> Unit) {
    SectionCard(title = stringResource(R.string.export_format)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ExportFormat.entries.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (option == format) RigColors.Primary.copy(alpha = 0.12f) else RigColors.SurfaceRaised)
                        .border(
                            1.dp,
                            if (option == format) RigColors.Primary else RigColors.OutlineSoft,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { onFormat(option) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = option.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            color = RigColors.TextPrimary,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = when (option) {
                                ExportFormat.MP4 ->
                                    "H.264 encoded on device. The file every platform plays. No alpha channel."
                                ExportFormat.PNG_SEQUENCE ->
                                    "One lossless PNG per frame, zipped. Keeps real transparency for compositing."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = RigColors.TextSecondary,
                        )
                    }
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .border(
                                2.dp,
                                if (option == format) RigColors.Primary else RigColors.Outline,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (option == format) {
                            Box(
                                Modifier.size(11.dp).clip(CircleShape).background(RigColors.Primary),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityCard(
    resolution: ExportResolution,
    frameRate: ExportFrameRate,
    onResolution: (ExportResolution) -> Unit,
    onFrameRate: (ExportFrameRate) -> Unit,
) {
    SectionCard(title = "Resolution & frame rate") {
        FieldLabel(stringResource(R.string.export_resolution))
        Spacer(Modifier.height(6.dp))
        ChipStrip {
            ExportResolution.entries.forEach { option ->
                RigChip(
                    label = "${option.height}p",
                    selected = option == resolution,
                    onClick = { onResolution(option) },
                    sublabel = "${option.width}×${option.height}",
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        FieldLabel(stringResource(R.string.export_frame_rate))
        Spacer(Modifier.height(6.dp))
        ChipStrip {
            ExportFrameRate.entries.forEach { option ->
                RigChip(
                    label = "${option.fps} fps",
                    selected = option == frameRate,
                    onClick = { onFrameRate(option) },
                )
            }
        }
    }
}

@Composable
private fun DurationCard(
    duration: Float,
    speed: Float,
    clipName: String,
    loopLength: Float,
    onDuration: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
    onOneLoop: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.export_duration),
        trailing = { RigTextButton("One loop", onOneLoop) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%.1f s".format(duration),
                style = MaterialTheme.typography.titleMedium,
                color = RigColors.Primary,
                modifier = Modifier.width(64.dp),
            )
            Slider(
                value = duration,
                onValueChange = onDuration,
                valueRange = ExportLimits.MIN_DURATION_SECONDS..ExportLimits.MAX_DURATION_SECONDS,
                steps = (ExportLimits.MAX_DURATION_SECONDS / 0.5f).roundToInt() - 1,
                colors = SliderDefaults.colors(
                    thumbColor = RigColors.Primary,
                    activeTrackColor = RigColors.Primary,
                    inactiveTrackColor = RigColors.SurfaceVariant,
                ),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "$clipName is %.2f s per cycle — this export holds about %.1f cycles at 1×."
                .format(loopLength, duration / loopLength),
            style = MaterialTheme.typography.bodySmall,
            color = RigColors.TextSecondary,
        )
        Spacer(Modifier.height(14.dp))
        FieldLabel(stringResource(R.string.editor_speed))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%.2f×".format(speed),
                style = MaterialTheme.typography.titleMedium,
                color = RigColors.Primary,
                modifier = Modifier.width(64.dp),
            )
            Slider(
                value = speed,
                onValueChange = onSpeed,
                valueRange = ExportLimits.MIN_SPEED..ExportLimits.MAX_SPEED,
                steps = ((ExportLimits.MAX_SPEED - ExportLimits.MIN_SPEED) / 0.25f).roundToInt() - 1,
                colors = SliderDefaults.colors(
                    thumbColor = RigColors.Primary,
                    activeTrackColor = RigColors.Primary,
                    inactiveTrackColor = RigColors.SurfaceVariant,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BackgroundCard(
    transparent: Boolean,
    argb: Int,
    format: ExportFormat,
    onPreset: (Int) -> Unit,
    onTransparent: (Boolean) -> Unit,
) {
    SectionCard(title = stringResource(R.string.editor_background)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Transparent background",
                    style = MaterialTheme.typography.titleSmall,
                    color = RigColors.TextPrimary,
                )
                Text(
                    text = if (format.supportsTransparency) {
                        "PNG frames keep the real alpha channel."
                    } else {
                        "MP4 (H.264) has no alpha channel — pick a colour instead."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = RigColors.TextSecondary,
                )
            }
            Switch(
                checked = transparent && format.supportsTransparency,
                onCheckedChange = onTransparent,
                enabled = format.supportsTransparency,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = RigColors.OnPrimary,
                    checkedTrackColor = RigColors.Primary,
                    uncheckedThumbColor = RigColors.TextSecondary,
                    uncheckedTrackColor = RigColors.SurfaceVariant,
                    uncheckedBorderColor = RigColors.Outline,
                ),
            )
        }
        Spacer(Modifier.height(14.dp))
        FieldLabel("Backdrop colour")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EDITOR_BACKGROUND_PRESETS.forEach { preset ->
                val selected = !transparent && argb == preset.argb
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(preset.argb))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) RigColors.Primary else RigColors.Outline,
                            shape = CircleShape,
                        )
                        .clickable { onPreset(preset.argb) },
                )
            }
        }
    }
}

@Composable
private fun AudioCard(
    hasAudio: Boolean,
    label: String?,
    format: ExportFormat,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.export_audio),
        trailing = {
            if (hasAudio) RigTextButton("Remove", onClear)
        },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(RigColors.SurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (hasAudio) Icons.Filled.Check else Icons.Filled.Add,
                    contentDescription = null,
                    tint = if (hasAudio) RigColors.Primary else RigColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = label ?: stringResource(R.string.export_audio_none),
                    style = MaterialTheme.typography.titleSmall,
                    color = RigColors.TextPrimary,
                )
                Text(
                    text = if (format == ExportFormat.MP4) {
                        "Copied straight from a local AAC file — never transcoded, never generated."
                    } else {
                        "Audio needs an MP4 export."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = RigColors.TextSecondary,
                )
            }
            RigSecondaryButton(
                text = if (hasAudio) "Replace" else "Choose",
                onClick = onPick,
                enabled = format == ExportFormat.MP4,
                modifier = Modifier.width(104.dp).height(42.dp),
            )
        }
    }
}

@Composable
private fun PreFlightCard(state: ExportUiState) {
    val issues = state.issues
    SectionCard(title = "Pre-flight") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PreFlightRow("Output", state.frameLabel, true)
            PreFlightRow("Estimated size", state.estimateLabel, true)
            PreFlightRow(
                label = "Free space",
                value = ExportResult.formatBytes(state.freeBytes),
                ok = state.freeBytes > state.settings.estimatedBytes + ExportLimits.MIN_FREE_BYTES_FOR_EXPORT / 8,
            )
            PreFlightRow(
                label = "Transparency",
                value = when {
                    state.settings.transparentBackground && state.settings.format.supportsTransparency ->
                        "Real alpha in PNG frames"
                    else -> "Opaque backdrop (MP4 has no alpha)"
                },
                ok = true,
            )
            PreFlightRow(
                label = "Audio",
                value = state.audioLabel ?: "Silent",
                ok = true,
            )
        }
        if (issues.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            IssueList(
                issues.map { issue ->
                    SheetIssue(
                        level = if (issue.blocking) SheetIssueLevel.ERROR else SheetIssueLevel.WARNING,
                        message = issue.message,
                    )
                },
            )
        }
    }
}

@Composable
private fun PreFlightRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = RigColors.TextSecondary,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) RigColors.TextPrimary else RigColors.Tertiary,
            modifier = Modifier.weight(1f),
        )
    }
}
