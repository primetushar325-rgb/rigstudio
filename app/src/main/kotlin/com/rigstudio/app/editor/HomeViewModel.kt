package com.rigstudio.app.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rigstudio.app.RigStudioApplication
import com.rigstudio.app.data.ProjectStore
import com.rigstudio.app.data.ProjectStoreException
import com.rigstudio.app.data.ProjectSummary
import com.rigstudio.app.render.downscaleTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One-shot navigation request from the character library. Consumed by the navigator. */
sealed interface HomeNavigation {
    data class Editor(val projectId: String) : HomeNavigation
    data object Import : HomeNavigation
    data object Template : HomeNavigation
}

/** A library row: the summary plus its decoded thumbnail, ready for Compose. */
data class ProjectCard(
    val summary: ProjectSummary,
    val thumbnail: Bitmap?,
) {
    val id: String get() = summary.id
    val name: String get() = summary.name
    val viewsLabel: String get() = summary.viewLabel
    val partsLabel: String get() = "${summary.spriteCount} part" + if (summary.spriteCount == 1) "" else "s"
}

data class HomeUiState(
    val cards: List<ProjectCard> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val busyMessage: String? = null,
    val message: String? = null,
    val navigation: HomeNavigation? = null,
    /** Total bytes the character library occupies in app-private storage. */
    val storageBytes: Long = 0L,
) {
    val storageLabel: String get() = formatBytes(storageBytes)
    val isEmpty: Boolean get() = !loading && cards.isEmpty()

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes <= 0L -> "0 MB"
            bytes < 1024L * 1024L -> "%.0f KB".format(bytes / 1024f)
            bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
            else -> "%.2f GB".format(bytes / (1024f * 1024f * 1024f))
        }
    }
}

/**
 * The character library: list, open, rename, duplicate, delete — plus the two zero-friction entry
 * points (bundled sample character, blank template).
 *
 * Every character lives in app-private storage and survives restarts. There are no accounts, no
 * cloud sync and no network access anywhere in this class, which is what makes the airplane-mode
 * tests pass.
 */
class HomeViewModel(private val app: RigStudioApplication) : ViewModel() {

    private val store: ProjectStore = app.store

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads the library from disk. Called on start and whenever the screen becomes visible. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val cards = withContext(Dispatchers.IO) { loadCards() }
            val bytes = withContext(Dispatchers.IO) { storageBytes() }
            _state.update { it.copy(cards = cards, storageBytes = bytes, loading = false) }
        }
    }

    fun open(projectId: String) {
        _state.update { it.copy(navigation = HomeNavigation.Editor(projectId)) }
    }

    fun newCharacterFromSheet() {
        _state.update { it.copy(navigation = HomeNavigation.Import) }
    }

    fun viewTemplate() {
        _state.update { it.copy(navigation = HomeNavigation.Template) }
    }

    /** Builds the bundled sample character through the real import pipeline. */
    fun createSampleCharacter() {
        runBusy("Building the sample character…") {
            val outcome = app.importer.createSampleCharacter()
            val id = outcome.character?.project?.id
            if (id == null) {
                _state.update {
                    it.copy(message = outcome.failureMessage ?: "The sample character could not be built.")
                }
            } else {
                _state.update { it.copy(navigation = HomeNavigation.Editor(id)) }
            }
        }
    }

    fun rename(projectId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(message = "A character needs a name.") }
            return
        }
        runBusy("Renaming…") {
            withContext(Dispatchers.IO) { store.rename(projectId, trimmed, System.currentTimeMillis()) }
            _state.update { it.copy(message = "Renamed to “$trimmed”.") }
        }
    }

    fun duplicate(projectId: String) {
        runBusy("Duplicating…") {
            val now = System.currentTimeMillis()
            val source = withContext(Dispatchers.IO) { store.requireProject(projectId) }
            val copyName = uniqueCopyName(source.name)
            withContext(Dispatchers.IO) {
                store.duplicate(projectId, ProjectStore.newProjectId(now), copyName, now)
            }
            _state.update { it.copy(message = "Duplicated as “$copyName”.") }
        }
    }

    fun delete(projectId: String) {
        runBusy("Deleting…") {
            val name = withContext(Dispatchers.IO) {
                runCatching { store.requireProject(projectId).name }.getOrDefault("this character")
            }
            withContext(Dispatchers.IO) { store.delete(projectId) }
            _state.update { it.copy(message = "Deleted “$name”. It cannot be undone.") }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun consumeNavigation(): HomeNavigation? {
        val current = _state.value.navigation ?: return null
        _state.update { it.copy(navigation = null) }
        return current
    }

    // --- internals ---------------------------------------------------------------------------

    private fun runBusy(message: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyMessage = message) }
            try {
                block()
            } catch (error: Throwable) {
                _state.update { it.copy(message = messageFor(error)) }
            } finally {
                val cards = withContext(Dispatchers.IO) { loadCards() }
                val bytes = withContext(Dispatchers.IO) { storageBytes() }
                _state.update { it.copy(busy = false, busyMessage = null, cards = cards, storageBytes = bytes) }
            }
        }
    }

    private fun loadCards(): List<ProjectCard> = store.list()
        .sortedByDescending { it.lastOpenedAtEpochMillis }
        .map { summary -> ProjectCard(summary, loadThumbnail(summary.thumbnailFile)) }

    private fun loadThumbnail(file: File?): Bitmap? {
        val target = file ?: return null
        if (!target.isFile) return null
        return try {
            BitmapFactory.decodeFile(target.absolutePath)?.downscaleTo(THUMBNAIL_PX)
        } catch (error: Throwable) {
            null // a missing thumbnail must never hide the character
        }
    }

    private fun storageBytes(): Long = try {
        store.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (error: Throwable) {
        0L
    }

    private fun uniqueCopyName(base: String): String {
        val existing = store.list().map { it.name }.toSet()
        var candidate = "$base (copy)"
        var index = 2
        while (candidate in existing) {
            candidate = "$base (copy $index)"
            index++
        }
        return candidate
    }

    private fun messageFor(error: Throwable): String = when (error) {
        is ProjectStoreException -> error.message ?: "That did not work."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "That did not work."
    }

    companion object {
        private const val THUMBNAIL_PX = 256

        fun factory(app: RigStudioApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(app) as T
            }
    }
}
