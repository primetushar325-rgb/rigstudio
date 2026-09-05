package com.rigstudio.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rigstudio.app.R
import com.rigstudio.app.editor.HomeNavigation
import com.rigstudio.app.editor.HomeViewModel
import com.rigstudio.app.editor.ProjectCard
import com.rigstudio.app.ui.components.BusyIndicator
import com.rigstudio.app.ui.components.EmptyState
import com.rigstudio.app.ui.components.RigPrimaryButton
import com.rigstudio.app.ui.components.RigSecondaryButton
import com.rigstudio.app.ui.components.RigTopBar
import com.rigstudio.app.ui.components.SectionCard
import com.rigstudio.app.ui.components.StatusPill
import com.rigstudio.app.ui.theme.RigColors
import java.text.DateFormat
import java.util.Date

/**
 * The character library: every character saved on this device, plus the three ways to get started
 * (import a sheet, open the blank template, build the bundled sample).
 *
 * There is no sign-in, no sync and no network state anywhere on this screen — the list is a
 * directory of app-private folders.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenCharacter: (String) -> Unit,
    onImportSheet: () -> Unit,
    onOpenTemplate: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<ProjectCard?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectCard?>(null) }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(state.navigation) {
        when (val navigation = viewModel.consumeNavigation()) {
            is HomeNavigation.Editor -> onOpenCharacter(navigation.projectId)
            HomeNavigation.Import -> onImportSheet()
            HomeNavigation.Template -> onOpenTemplate()
            null -> Unit
        }
    }

    Scaffold(
        containerColor = RigColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            RigTopBar(
                title = stringResource(R.string.home_title),
                subtitle = when {
                    state.loading -> "Reading library…"
                    state.cards.isEmpty() -> "Nothing saved on this device yet"
                    else -> "${state.cards.size} character${if (state.cards.size == 1) "" else "s"} · ${state.storageLabel} on device"
                },
            )
        },
        floatingActionButton = {
            if (!state.isEmpty) {
                FloatingActionButton(
                    onClick = onImportSheet,
                    containerColor = RigColors.Primary,
                    contentColor = RigColors.OnPrimary,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_import))
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.cards.isEmpty() -> BusyIndicator("Reading your characters…")
                state.isEmpty -> EmptyLibrary(onImportSheet, viewModel::createSampleCharacter, onOpenTemplate)
                else -> CharacterList(
                    cards = state.cards,
                    busy = state.busy,
                    busyMessage = state.busyMessage,
                    onOpen = onOpenCharacter,
                    onRename = { renameTarget = it },
                    onDelete = { deleteTarget = it },
                    onDuplicate = viewModel::duplicate,
                    onImport = onImportSheet,
                    onTemplate = onOpenTemplate,
                    onSample = viewModel::createSampleCharacter,
                )
            }
        }
    }

    renameTarget?.let { card ->
        RenameDialog(
            initial = card.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                viewModel.rename(card.id, name)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { card ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = RigColors.SurfaceRaised,
            titleContentColor = RigColors.TextPrimary,
            textContentColor = RigColors.TextSecondary,
            title = { Text(stringResource(R.string.delete_confirm_title, card.name)) },
            text = { Text(stringResource(R.string.delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(card.id)
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.delete), color = RigColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel), color = RigColors.TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit, onSample: () -> Unit, onTemplate: () -> Unit) {
    EmptyState(
        title = stringResource(R.string.home_empty_title),
        body = stringResource(R.string.home_empty_body),
    ) {
        RigPrimaryButton(stringResource(R.string.home_import), onImport, Modifier.fillMaxWidth())
        RigSecondaryButton(stringResource(R.string.home_sample), onSample, Modifier.fillMaxWidth())
        RigSecondaryButton(stringResource(R.string.home_template), onTemplate, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CharacterList(
    cards: List<ProjectCard>,
    busy: Boolean,
    busyMessage: String?,
    onOpen: (String) -> Unit,
    onRename: (ProjectCard) -> Unit,
    onDelete: (ProjectCard) -> Unit,
    onDuplicate: (String) -> Unit,
    onImport: () -> Unit,
    onTemplate: () -> Unit,
    onSample: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cards, key = { it.id }) { card ->
            CharacterCard(
                card = card,
                onOpen = { onOpen(card.id) },
                onRename = { onRename(card) },
                onDelete = { onDelete(card) },
                onDuplicate = { onDuplicate(card.id) },
            )
        }
        item {
            SectionCard(title = "Start something new") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RigPrimaryButton(stringResource(R.string.home_import), onImport, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RigSecondaryButton(
                            stringResource(R.string.home_sample),
                            onSample,
                            Modifier.weight(1f),
                        )
                        RigSecondaryButton(
                            stringResource(R.string.home_template),
                            onTemplate,
                            Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Everything is stored on this device. RigStudio never uploads artwork, " +
                            "never needs an account, and works in airplane mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RigColors.TextDisabled,
                    )
                }
            }
        }
        if (busy) {
            item { BusyIndicator(busyMessage) }
        }
    }
}

@Composable
private fun CharacterCard(
    card: ProjectCard,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        color = RigColors.Surface,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, RigColors.OutlineSoft),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(width = 74.dp, height = 92.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(RigColors.StageDefault),
                contentAlignment = Alignment.Center,
            ) {
                val thumbnail = card.thumbnail
                if (thumbnail != null && !thumbnail.isRecycled) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = card.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                    )
                } else {
                    Text(
                        text = card.name.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = RigColors.TextDisabled,
                    )
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = RigColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(card.viewsLabel, RigColors.Primary)
                    StatusPill(card.partsLabel, RigColors.Secondary)
                }
                Text(
                    text = "Updated ${formatDate(card.summary.updatedAtEpochMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = RigColors.TextDisabled,
                )
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More actions",
                        tint = RigColors.TextSecondary,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = RigColors.SurfaceRaised,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_open)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, null, tint = RigColors.TextSecondary) },
                        onClick = {
                            menuOpen = false
                            onOpen()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, null, tint = RigColors.TextSecondary) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_duplicate)) },
                        leadingIcon = { Icon(Icons.Filled.Add, null, tint = RigColors.TextSecondary) },
                        onClick = {
                            menuOpen = false
                            onDuplicate()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_delete), color = RigColors.Error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = RigColors.Error) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RigColors.SurfaceRaised,
        titleContentColor = RigColors.TextPrimary,
        textContentColor = RigColors.TextSecondary,
        title = { Text(stringResource(R.string.home_rename)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Character name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = RigColors.TextPrimary,
                        unfocusedTextColor = RigColors.TextPrimary,
                        focusedBorderColor = RigColors.Primary,
                        unfocusedBorderColor = RigColors.Outline,
                        focusedLabelColor = RigColors.Primary,
                        unfocusedLabelColor = RigColors.TextSecondary,
                        cursorColor = RigColors.Primary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Names are used for export file names too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RigColors.TextDisabled,
                    textAlign = TextAlign.Start,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.save), color = RigColors.Primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = RigColors.TextSecondary)
            }
        },
    )
}

private fun formatDate(epochMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))
