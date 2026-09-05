package com.rigstudio.app.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rigstudio.app.RigStudioApplication
import com.rigstudio.app.pipeline.ImportAnalysis
import com.rigstudio.app.pipeline.ImportStage
import com.rigstudio.app.pipeline.SheetImporter
import com.rigstudio.app.pipeline.label
import com.rigstudio.app.pipeline.progressFraction
import com.rigstudio.app.render.downscaleTo
import com.rigstudio.core.extract.SheetImageMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-shot navigation from the import screen. */
sealed interface ImportNavigation {
    data class Editor(val projectId: String) : ImportNavigation
    data object Library : ImportNavigation
    data object Template : ImportNavigation
}

data class ImportUiState(
    val sheetUri: Uri? = null,
    val sheetLabel: String? = null,
    val meta: SheetImageMeta? = null,
    val preview: Bitmap? = null,
    val analysis: ImportAnalysis? = null,
    val analyzing: Boolean = false,
    val importing: Boolean = false,
    val stage: ImportStage = ImportStage.READING_FILE,
    val progress: Float = 0f,
    val characterName: String = "",
    val mirrorSideView: Boolean = false,
    val message: String? = null,
    val navigation: ImportNavigation? = null,
) {
    val stageLabel: String get() = stage.label

    val hasSheet: Boolean get() = sheetUri != null && analysis != null

    /** Mirroring is only offered when the left profile is complete and the right one is empty. */
    val mirrorAvailable: Boolean get() = analysis?.canMirrorSideView == true

    val canImport: Boolean get() = hasSheet && analysis?.isRiggable == true && !importing && !analyzing

    val dimensionLabel: String
        get() = meta?.let { "${it.width} × ${it.height} px" } ?: "—"

    val sizeLabel: String
        get() = meta?.let { HomeUiState.formatBytes(it.byteCount.coerceAtLeast(0L)) } ?: "—"

    val fillLabel: String
        get() = analysis?.let { "${it.filledSlots} of ${it.totalSlots} slots drawn" } ?: ""
}

/**
 * Drives the import screen: pick a sheet → analyse it → name it → build and save the character.
 *
 * The picked 2048×2048 PNG is decoded **once** and held here until the import consumes it (or the
 * user picks another sheet / leaves the screen), so analyse and import never pay for two decodes of
 * a 16 MB bitmap.
 */
class ImportViewModel(private val app: RigStudioApplication) : ViewModel() {

    private val importer: SheetImporter = app.importer

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** Full-size sheet pixels, owned by this view model and recycled when no longer needed. */
    private var pickedBitmap: Bitmap? = null

    fun onSheetPicked(uri: Uri) {
        releaseSheet()
        viewModelScope.launch {
            _state.update { it.copy(analyzing = true, sheetUri = uri, analysis = null, preview = null, message = null) }
            try {
                val meta = importer.readMetadata(uri)
                val bitmap = withContext(Dispatchers.IO) { importer.decode(uri) }
                if (bitmap == null) {
                    _state.update {
                        it.copy(
                            analyzing = false,
                            meta = meta,
                            message = SheetImporter.UNREADABLE_IMAGE,
                        )
                    }
                    return@launch
                }
                pickedBitmap = bitmap
                val preview = withContext(Dispatchers.IO) { bitmap.downscaleTo(PREVIEW_PX) }
                val analysis = importer.analyze(bitmap, meta)
                _state.update {
                    it.copy(
                        analyzing = false,
                        meta = meta,
                        preview = preview,
                        analysis = analysis,
                        sheetLabel = uri.lastPathSegment ?: "character sheet",
                        characterName = it.characterName.ifBlank { suggestedName() },
                        mirrorSideView = false,
                        message = analysis.headline?.takeIf { message -> analysis.errors.isNotEmpty() },
                    )
                }
            } catch (error: Throwable) {
                releaseSheet()
                _state.update { it.copy(analyzing = false, message = messageFor(error)) }
            }
        }
    }

    fun onNameChanged(name: String) {
        _state.update { it.copy(characterName = name) }
    }

    fun onMirrorSideViewChanged(enabled: Boolean) {
        _state.update { it.copy(mirrorSideView = enabled) }
    }

    fun importNow() {
        val bitmap = pickedBitmap
        val meta = _state.value.meta
        if (bitmap == null || meta == null) {
            _state.update { it.copy(message = "Pick a character sheet first.") }
            return
        }
        val analysis = _state.value.analysis
        if (analysis != null && !analysis.isRiggable) {
            _state.update {
                it.copy(message = analysis.headline ?: "This sheet is missing required front-body parts.")
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(importing = true, progress = 0f, message = null) }
            try {
                val outcome = importer.importBitmap(
                    bitmap = bitmap,
                    meta = meta,
                    name = _state.value.characterName,
                    mirrorSideView = _state.value.mirrorSideView,
                    onStage = { stage ->
                        _state.update { it.copy(stage = stage, progress = stage.progressFraction) }
                    },
                )
                pickedBitmap = null // consumed by the importer, which always recycles it
                val projectId = outcome.character?.project?.id
                if (projectId != null) {
                    _state.update {
                        it.copy(
                            importing = false,
                            progress = 1f,
                            stage = ImportStage.DONE,
                            preview = null,
                            analysis = null,
                            meta = null,
                            sheetUri = null,
                            navigation = ImportNavigation.Editor(projectId),
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            importing = false,
                            stage = ImportStage.FAILED,
                            message = outcome.failureMessage ?: "This sheet could not be rigged.",
                        )
                    }
                }
            } catch (error: Throwable) {
                pickedBitmap = null
                _state.update { it.copy(importing = false, stage = ImportStage.FAILED, message = messageFor(error)) }
            }
        }
    }

    /** Builds the bundled sample character — the fastest way to see the whole pipeline work. */
    fun importSampleCharacter() {
        viewModelScope.launch {
            _state.update { it.copy(importing = true, progress = 0.2f, message = null) }
            try {
                val outcome = importer.createSampleCharacter()
                val projectId = outcome.character?.project?.id
                if (projectId != null) {
                    _state.update {
                        it.copy(importing = false, progress = 1f, navigation = ImportNavigation.Editor(projectId))
                    }
                } else {
                    _state.update {
                        it.copy(importing = false, message = outcome.failureMessage ?: "The sample could not be built.")
                    }
                }
            } catch (error: Throwable) {
                _state.update { it.copy(importing = false, message = messageFor(error)) }
            }
        }
    }

    fun backToLibrary() {
        _state.update { it.copy(navigation = ImportNavigation.Library) }
    }

    fun openTemplate() {
        _state.update { it.copy(navigation = ImportNavigation.Template) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun consumeNavigation(): ImportNavigation? {
        val current = _state.value.navigation ?: return null
        _state.update { it.copy(navigation = null) }
        return current
    }

    /** Drops the picked sheet (and its pixels) without importing. */
    fun clearSheet() {
        releaseSheet()
        _state.update {
            ImportUiState(characterName = it.characterName)
        }
    }

    private fun releaseSheet() {
        pickedBitmap?.takeIf { !it.isRecycled }?.recycle()
        pickedBitmap = null
    }

    private fun suggestedName(): String = "Character ${(app.store.list().size + 1)}"

    private fun messageFor(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: "That sheet could not be read."

    override fun onCleared() {
        releaseSheet()
        super.onCleared()
    }

    companion object {
        private const val PREVIEW_PX = 640

        fun factory(app: RigStudioApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ImportViewModel(app) as T
            }
    }
}
