package com.rigstudio.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rigstudio.app.RigStudioApplication
import com.rigstudio.app.editor.EditorViewModel
import com.rigstudio.app.editor.ExportViewModel
import com.rigstudio.app.editor.HomeViewModel
import com.rigstudio.app.editor.ImportViewModel
import com.rigstudio.app.editor.TemplateViewModel
import com.rigstudio.app.ui.editor.EditorScreen
import com.rigstudio.app.ui.export.ExportScreen
import com.rigstudio.app.ui.home.HomeScreen
import com.rigstudio.app.ui.import.ImportScreen
import com.rigstudio.app.ui.template.TemplateScreen
import com.rigstudio.core.export.ExportSettings

/** Where the app can be. Five screens, one back stack, no deep links and no cloud. */
sealed interface Route {
    data object Home : Route
    data object Import : Route
    data object Template : Route
    data class Editor(val projectId: String) : Route
    data class Export(val projectId: String, val seed: ExportSettings?) : Route
}

/**
 * Navigation is a plain remembered back stack.
 *
 * A navigation library would add a dependency and a graph for five screens that never deep-link;
 * this is the same behaviour in ~40 lines, and every transition is explicit and greppable. The
 * stack is process-memory only: if Android kills the process, the user lands back on the library,
 * which still lists every saved character.
 */
@Composable
fun RigStudioApp(initialSheetUri: Uri? = null) {
    val app = LocalContext.current.applicationContext as RigStudioApplication
    val backStack = remember {
        mutableStateListOf<Route>(Route.Home).apply {
            // "Open with RigStudio" on a character sheet lands straight on the import screen.
            if (initialSheetUri != null) add(Route.Import)
        }
    }

    fun push(route: Route) {
        backStack.add(route)
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** Back to the library, dropping everything above it (used after a successful import). */
    fun popToHome() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    BackHandler(enabled = backStack.size > 1) { pop() }

    when (val route = backStack.last()) {
        Route.Home -> {
            val homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(app))
            // The library is a directory listing: refresh whenever it becomes visible again.
            LaunchedEffect(Unit) { homeViewModel.refresh() }
            HomeScreen(
                viewModel = homeViewModel,
                onOpenCharacter = { projectId -> push(Route.Editor(projectId)) },
                onImportSheet = { push(Route.Import) },
                onOpenTemplate = { push(Route.Template) },
            )
        }

        Route.Import -> {
            val importViewModel: ImportViewModel = viewModel(factory = ImportViewModel.factory(app))
            LaunchedEffect(initialSheetUri) {
                initialSheetUri?.let(importViewModel::onSheetPicked)
            }
            ImportScreen(
                viewModel = importViewModel,
                onCharacterReady = { projectId ->
                    popToHome()
                    push(Route.Editor(projectId))
                },
                onBack = ::pop,
                onOpenTemplate = { push(Route.Template) },
            )
        }

        Route.Template -> {
            val templateViewModel: TemplateViewModel = viewModel(factory = TemplateViewModel.factory(app))
            TemplateScreen(
                viewModel = templateViewModel,
                onBack = ::pop,
                onImport = { push(Route.Import) },
            )
        }

        is Route.Editor -> {
            val editorViewModel: EditorViewModel = viewModel(factory = EditorViewModel.factory(app))
            EditorScreen(
                viewModel = editorViewModel,
                projectId = route.projectId,
                onBack = ::pop,
                onExport = { push(Route.Export(route.projectId, editorViewModel.exportSeed())) },
                onOpenTemplate = { push(Route.Template) },
            )
        }

        is Route.Export -> {
            val exportViewModel: ExportViewModel = viewModel(factory = ExportViewModel.factory(app))
            ExportScreen(
                viewModel = exportViewModel,
                projectId = route.projectId,
                seed = route.seed,
                onBack = ::pop,
            )
        }
    }
}
