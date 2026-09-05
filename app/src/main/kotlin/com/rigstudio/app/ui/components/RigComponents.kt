package com.rigstudio.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rigstudio.app.ui.theme.RigColors
import com.rigstudio.core.extract.SheetIssue
import com.rigstudio.core.extract.SheetIssueLevel

/**
 * The shared visual vocabulary of the app.
 *
 * Everything the screens are built from lives here, so "premium dark professional" is one decision
 * made once: the same card, the same chip, the same button, the same spacing on every screen.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RigTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = RigColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = RigColors.TextPrimary,
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = RigColors.Background,
            titleContentColor = RigColors.TextPrimary,
            navigationIconContentColor = RigColors.TextPrimary,
            actionIconContentColor = RigColors.TextSecondary,
        ),
    )
}

/** A titled panel. The workhorse of every screen. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = RigColors.Surface,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, RigColors.OutlineSoft),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = RigColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke(this)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Small caption above a control group. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = RigColors.TextSecondary,
        modifier = modifier,
    )
}

/** One selectable option. Chips are used for everything: views, clips, resolutions, frame rates. */
@Composable
fun RigChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    sublabel: String? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        label = {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sublabel != null) {
                    Text(
                        text = sublabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) RigColors.OnPrimary.copy(alpha = 0.75f) else RigColors.TextDisabled,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = RigColors.SurfaceRaised,
            labelColor = RigColors.TextPrimary,
            selectedContainerColor = RigColors.Primary,
            selectedLabelColor = RigColors.OnPrimary,
            disabledContainerColor = RigColors.Surface,
            disabledLabelColor = RigColors.TextDisabled,
        ),
        // A plain BorderStroke instead of FilterChipDefaults.filterChipBorder: the defaults
        // helper's parameter list shifted between Material3 1.2 and 1.3, and a chip border is
        // three lines of policy, not a reason to couple to an unstable signature.
        border = BorderStroke(
            width = 1.dp,
            color = when {
                selected -> RigColors.Primary
                enabled -> RigColors.Outline
                else -> RigColors.Outline.copy(alpha = 0.4f)
            },
        ),
    )
}

/** Horizontally scrollable chip strip — the animation and view selectors. */
@Composable
fun ChipStrip(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun RigPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = RigColors.Primary,
            contentColor = RigColors.OnPrimary,
            disabledContainerColor = RigColors.SurfaceVariant,
            disabledContentColor = RigColors.TextDisabled,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun RigSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = RigColors.TextPrimary),
        border = androidx.compose.foundation.BorderStroke(1.dp, RigColors.Outline),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun RigTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(contentColor = RigColors.Primary),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Small coloured pill for statuses ("READY", "WARNING", "1080p · 30 fps"). */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.14f),
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** Full-screen placeholder with a call to action. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(76.dp)
                .background(RigColors.SurfaceRaised, CircleShape)
                .border(1.dp, RigColors.Outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .background(RigColors.Primary.copy(alpha = 0.85f), RoundedCornerShape(8.dp)),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = RigColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = RigColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        if (actions != null) {
            Spacer(Modifier.height(24.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = actions,
            )
        }
    }
}

/** Validation findings, colour-coded by severity — errors first, then warnings, then notes. */
@Composable
fun IssueList(issues: List<SheetIssue>, modifier: Modifier = Modifier) {
    if (issues.isEmpty()) return
    val ordered = remember(issues) {
        issues.sortedBy { issue ->
            when (issue.level) {
                SheetIssueLevel.ERROR -> 0
                SheetIssueLevel.WARNING -> 1
                SheetIssueLevel.INFO -> 2
            }
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ordered.forEach { issue ->
            IssueRow(issue)
        }
    }
}

@Composable
private fun IssueRow(issue: SheetIssue) {
    val color = when (issue.level) {
        SheetIssueLevel.ERROR -> RigColors.Error
        SheetIssueLevel.WARNING -> RigColors.Tertiary
        SheetIssueLevel.INFO -> RigColors.Secondary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .background(color, CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = issue.level.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = issue.message,
                style = MaterialTheme.typography.bodySmall,
                color = RigColors.TextPrimary,
            )
        }
    }
}

/**
 * The timeline scrubber.
 *
 * Custom-drawn (not a Material slider) because it has to show three things at once: the cycle
 * length, the playhead, and — for looping clips — the wrap point. Tap and drag both scrub; the
 * caller keeps the clock authoritative.
 */
@Composable
fun ScrubberBar(
    normalizedTime: Float,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
    looping: Boolean = true,
    label: String? = null,
) {
    val trackColor = RigColors.SurfaceVariant
    val progressColor = RigColors.Primary
    val handleColor = RigColors.TextPrimary

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(looping) {
                    detectTapGestures { offset ->
                        onScrub((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(looping) {
                    var dragX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> dragX = offset.x },
                        onHorizontalDrag = { change, dragAmount ->
                            dragX = (dragX + dragAmount).coerceIn(0f, size.width.toFloat())
                            onScrub((dragX / size.width.toFloat()).coerceIn(0f, 1f))
                            change.consume()
                        },
                    )
                },
        ) {
            val trackHeight = 6.dp.toPx()
            val centerY = size.height / 2f
            val left = 0f
            val right = size.width
            val progress = (normalizedTime.coerceIn(0f, 1f)) * size.width

            drawLine(
                color = trackColor,
                start = Offset(left, centerY),
                end = Offset(right, centerY),
                strokeWidth = trackHeight,
                cap = StrokeCap.Round,
            )
            if (progress > 0f) {
                drawLine(
                    color = progressColor,
                    start = Offset(left, centerY),
                    end = Offset(progress, centerY),
                    strokeWidth = trackHeight,
                    cap = StrokeCap.Round,
                )
            }
            // Wrap marker: a loop restarts here, a one-shot ends here.
            drawLine(
                color = if (looping) RigColors.Secondary else RigColors.Tertiary,
                start = Offset(right - 2.dp.toPx(), centerY - 9.dp.toPx()),
                end = Offset(right - 2.dp.toPx(), centerY + 9.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = handleColor,
                radius = 7.dp.toPx(),
                center = Offset(progress, centerY),
            )
            drawCircle(
                color = progressColor,
                radius = 3.dp.toPx(),
                center = Offset(progress, centerY),
            )
        }
        if (label != null) {
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = RigColors.TextSecondary)
                Text(
                    text = if (looping) "loops" else "plays once",
                    style = MaterialTheme.typography.labelSmall,
                    color = RigColors.TextDisabled,
                )
            }
        }
    }
}

/** Centred spinner with a caption, used while importing, exporting or loading a character. */
@Composable
fun BusyIndicator(message: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(color = RigColors.Primary, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = RigColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A tappable row (used in dialogs and lists where a chip would be too small). */
@Composable
fun TappableRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = RigColors.TextPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = RigColors.TextSecondary)
            }
        }
        trailing?.invoke(this)
    }
}

/** Consistent vertical rhythm between panels. */
val PanelGap = 14.dp

@Composable
fun PanelSpacer(modifier: Modifier = Modifier) {
    Spacer(modifier.height(PanelGap))
}
