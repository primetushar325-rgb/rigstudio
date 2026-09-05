package com.rigstudio.app.editor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rigstudio.app.RigStudioApplication
import com.rigstudio.app.data.LoadedCharacter
import com.rigstudio.app.data.ProjectStore
import com.rigstudio.app.export.AudioSource
import com.rigstudio.app.export.ExportPhase
import com.rigstudio.app.export.ExportProgress
import com.rigstudio.app.export.ExportRequest
import com.rigstudio.app.export.ExportResult
import com.rigstudio.core.anim.AnimationClip
import com.rigstudio.core.anim.AnimationLibrary
import com.rigstudio.core.export.ExportFormat
import com.rigstudio.core.export.ExportFrameRate
import com.rigstudio.core.export.ExportLimits
import com.rigstudio.core.export.ExportResolution
import com.rigstudio.core.export.ExportSettings
import com.rigstudio.core.export.ExportValidationIssue
import com.rigstudio.core.model.ViewKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ExportUiState(
    val projectId: String = "",
    val characterName: String = "",
    val loaded: Boolean = false,
    val settings: ExportSettings = ExportSettings.DEFAULT,
    val clips: List<AnimationClip> = emptyList(),
    val views: List<ViewKind> = listOf(ViewKind.FRONT),
    val audioUri: Uri? = null,
    val audioLabel: String? = null,
    val running: Boolean = false,
    val progress: ExportProgress? = null,
    val result: ExportResult? = null,
    val message: String? = null,
    val freeBytes: Long = 0L,
    val cancelled: Boolean = false,
) {
    val issues: List<ExportValidationIssue> get() = settings.validate()
    val blockingMessage: String? get() = settings.blockingMessage
    val canExport: Boolean get() = loaded && !running && blockingMessage == null

    val fraction: Float get() = progress?.fraction ?: 0f
    val progressLabel: String
        get() = progress?.message?.takeIf { it.isNotBlank() } ?: progress?.phase?.name ?: "Ready"

    val estimateLabel: String get() = "≈ ${ExportResult.formatBytes(settings.estimatedBytes)}"
    val frameLabel: String get() = "${settings.frameCount} frames · ${settings.width}×${settings.height} · ${settings.frameRate.fps} fps"
    val hasAudio: Boolean get() = audioUri != null
}

/**
 * The export screen: settings → run → validate → save/share/open.
 *
 * Everything heavy happens in [com.rigstudio.app.export.ExportRunner]; this class only owns state,
 * cancellation and the Android intents that hand the finished file to another app. Nothing here
 * touches the network — an export works in airplane mode because MediaCodec and MediaMuxer are on
 * the device.
 */
class ExportViewModel(private val app: RigStudioApplication) : ViewModel() {

    private val store: ProjectStore = app.store

    private var character: LoadedCharacter? = null
    private var exportJob: Job? = null

    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    private val _finished = MutableStateFlow(false)

    /** One-shot "leave this screen" signal. */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    /**
     * @param seed settings carried over from the editor (same clip, view, speed and background).
     */
    fun load(projectId: String, seed: ExportSettings?) {
        val current = _state.value
        if (current.loaded && current.projectId == projectId) {
            // Same character, new intent from the editor: adopt the seed instead of reloading.
            if (seed != null) applySeed(seed, current)
            return
        }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { store.load(projectId) } }
            val loadedCharacter = loaded.getOrNull()
            if (loadedCharacter == null) {
                _state.update {
                    it.copy(message = loaded.exceptionOrNull()?.message ?: "This character could not be opened.")
                }
                return@launch
            }
            character = loadedCharacter
            val project = loadedCharacter.project
            val views = loadedCharacter.availableViews.ifEmpty { listOf(ViewKind.FRONT) }
            val view = (seed?.view ?: project.lastView).takeIf { it in views } ?: ViewKind.FRONT
            val clips = AnimationLibrary.playableIn(view, loadedCharacter.hasProfileArtwork)
            val clipId = (seed?.clipId ?: project.lastClipId).takeIf { id -> id in clips.map { it.id } }
                ?: clips.firstOrNull()?.id
                ?: AnimationLibrary.IDLE.id
            val settings = seedSettings(seed, view, clipId)
            _state.update {
                it.copy(
                    projectId = projectId,
                    characterName = project.name,
                    loaded = true,
                    views = views,
                    clips = clips,
                    settings = settings,
                    freeBytes = freeBytes(),
                )
            }
        }
    }

    /** Carries the editor's current clip, view, speed and backdrop into the export form. */
    private fun applySeed(seed: ExportSettings, current: ExportUiState) {
        val view = seed.view.takeIf { it in current.views } ?: current.settings.view
        val clipId = seed.clipId.takeIf { id -> id in current.clips.map { it.id } } ?: current.settings.clipId
        applySettings { seedSettings(seed, view, clipId) }
        _state.update { it.copy(result = null, progress = null, message = null) }
    }

    private fun seedSettings(seed: ExportSettings?, view: ViewKind, clipId: String): ExportSettings =
        (seed ?: ExportSettings.DEFAULT).copy(
            view = view,
            clipId = clipId,
            durationSeconds = (seed?.durationSeconds ?: defaultDurationFor(clipId))
                .coerceIn(ExportLimits.MIN_DURATION_SECONDS, ExportLimits.MAX_DURATION_SECONDS),
            // MP4 cannot hold alpha; never start with a request that is blocked by design.
            transparentBackground = false,
            audioPath = null,
        )

    // --- settings -----------------------------------------------------------------------------

    fun selectClip(clipId: String) {
        val clip = AnimationLibrary.byId(clipId) ?: return
        val current = _state.value
        val loadedCharacter = character ?: return
        var view = current.settings.view
        val required = clip.requiredView
        if (required != null && required in current.views) view = required
        if (clip.needsSideView && !loadedCharacter.hasProfileArtwork) {
            _state.update { it.copy(message = EditorState.SIDE_VIEW_MISSING) }
            return
        }
        val clips = AnimationLibrary.playableIn(view, loadedCharacter.hasProfileArtwork)
        applySettings {
            it.copy(
                view = view,
                clipId = clipId,
                durationSeconds = current.settings.durationSeconds
                    .coerceAtLeast(clip.durationSeconds)
                    .coerceAtMost(ExportLimits.MAX_DURATION_SECONDS),
            )
        }
        _state.update { it.copy(clips = clips) }
    }

    fun selectView(view: ViewKind) {
        if (view !in _state.value.views) {
            _state.update {
                it.copy(
                    message = if (view == ViewKind.BACK) {
                        EditorState.BACK_VIEW_MISSING
                    } else {
                        EditorState.SIDE_VIEW_MISSING
                    },
                )
            }
            return
        }
        val loadedCharacter = character ?: return
        val clips = AnimationLibrary.playableIn(view, loadedCharacter.hasProfileArtwork)
        val clipId = _state.value.settings.clipId.takeIf { id -> id in clips.map { it.id } }
            ?: clips.firstOrNull()?.id ?: AnimationLibrary.IDLE.id
        applySettings { it.copy(view = view, clipId = clipId) }
        _state.update { it.copy(clips = clips) }
    }

    fun setResolution(resolution: ExportResolution) = applySettings { it.copy(resolution = resolution) }

    fun setFrameRate(frameRate: ExportFrameRate) = applySettings { it.copy(frameRate = frameRate) }

    fun setFormat(format: ExportFormat) = applySettings {
        it.copy(
            format = format,
            // Transparency is only real in a PNG sequence; switching to MP4 must not silently
            // export a black backdrop.
            transparentBackground = it.transparentBackground && format.supportsTransparency,
            audioPath = if (format == ExportFormat.MP4) it.audioPath else null,
        )
    }

    fun setDuration(seconds: Float) = applySettings {
        it.copy(durationSeconds = seconds.coerceIn(ExportLimits.MIN_DURATION_SECONDS, ExportLimits.MAX_DURATION_SECONDS))
    }

    fun setSpeed(speed: Float) = applySettings {
        it.copy(speed = speed.coerceIn(ExportLimits.MIN_SPEED, ExportLimits.MAX_SPEED))
    }

    fun setBackgroundArgb(argb: Int) = applySettings {
        it.copy(backgroundArgb = argb, transparentBackground = false)
    }

    fun setTransparentBackground(transparent: Boolean) = applySettings {
        it.copy(transparentBackground = transparent && it.format.supportsTransparency)
    }

    fun setOneLoop() {
        val clip = AnimationLibrary.byId(_state.value.settings.clipId) ?: return
        applySettings { it.copy(durationSeconds = clip.durationSeconds) }
    }

    // --- audio --------------------------------------------------------------------------------

    fun onAudioPicked(uri: Uri) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    AudioSource.open(app, uri)?.use { source ->
                        source.durationMicros to source.mimeType
                    }
                }
            }
            val info = outcome.getOrNull()
            if (info == null) {
                _state.update {
                    it.copy(
                        audioUri = null,
                        audioLabel = null,
                        message = outcome.exceptionOrNull()?.message
                            ?: "That file has no audio track RigStudio can put in an MP4 (AAC only).",
                    )
                }
                return@launch
            }
            val seconds = info.first / 1_000_000f
            applySettings { it.copy(audioPath = uri.toString(), format = ExportFormat.MP4) }
            _state.update {
                it.copy(
                    audioUri = uri,
                    audioLabel = "%.1f s audio track".format(seconds),
                    message = null,
                )
            }
        }
    }

    fun clearAudio() {
        applySettings { it.copy(audioPath = null) }
        _state.update { it.copy(audioUri = null, audioLabel = null) }
    }

    // --- run ----------------------------------------------------------------------------------

    fun startExport() {
        val loadedCharacter = character
        val current = _state.value
        if (loadedCharacter == null || current.running) return
        val blocking = current.blockingMessage
        if (blocking != null) {
            _state.update { it.copy(message = blocking) }
            return
        }

        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    running = true,
                    cancelled = false,
                    result = null,
                    message = null,
                    progress = ExportProgress(ExportPhase.PREPARING, message = "Preparing…"),
                )
            }
            val request = ExportRequest(
                projectId = current.projectId,
                characterName = current.characterName,
                settings = current.settings,
            )
            val result = app.exportRunner.export(request, loadedCharacter) { progress ->
                _state.update { it.copy(progress = progress) }
            }
            _state.update {
                it.copy(
                    running = false,
                    progress = null,
                    result = result,
                    message = if (result.succeeded) null else result.message,
                    freeBytes = freeBytes(),
                )
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _state.update { it.copy(running = false, cancelled = true, progress = null, message = "Export cancelled.") }
    }

    /** Hands the finished file to another app. Returns false when nothing can open it. */
    fun share(): Boolean {
        val file = _state.value.result?.takeIf { it.succeeded }?.file ?: return false
        return try {
            val uri = app.exportRunner.shareUri(file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = _state.value.result?.format?.mimeType ?: "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.startActivity(Intent.createChooser(intent, "Share export").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (error: ActivityNotFoundException) {
            _state.update { it.copy(message = "No app on this device can share a video file.") }
            false
        } catch (error: Throwable) {
            _state.update { it.copy(message = error.message ?: "Sharing failed.") }
            false
        }
    }

    /** Opens the exported file in whichever app handles it (gallery, player, files). */
    fun openResult(): Boolean {
        val result = _state.value.result?.takeIf { it.succeeded } ?: return false
        val file = result.file ?: return false
        return try {
            val uri = app.exportRunner.shareUri(file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, result.format.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
            true
        } catch (error: ActivityNotFoundException) {
            _state.update {
                it.copy(message = "No app on this device can open ${result.format.extension.uppercase()} files.")
            }
            false
        } catch (error: Throwable) {
            _state.update { it.copy(message = error.message ?: "Could not open the file.") }
            false
        }
    }

    /** Copies the finished file to a user-chosen location (Documents UI / SAF). */
    fun saveTo(destination: Uri) {
        val file = _state.value.result?.takeIf { it.succeeded }?.file ?: return
        viewModelScope.launch {
            _state.update { it.copy(running = true, message = null) }
            val copied = app.exportRunner.copyTo(destination, file)
            _state.update {
                it.copy(
                    running = false,
                    message = copied.fold(
                        onSuccess = { bytes -> "Saved ${ExportResult.formatBytes(bytes)} to your files." },
                        onFailure = { error -> error.message ?: "That location could not be written to." },
                    ),
                )
            }
        }
    }

    /** The exported file's own folder, for "Show in files" style affordances. */
    fun resultFile(): File? = _state.value.result?.file

    fun reset() {
        _state.update { it.copy(result = null, progress = null, message = null, cancelled = false) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun backToEditor() {
        _finished.value = true
    }

    fun consumeFinished(): Boolean {
        if (!_finished.value) return false
        _finished.value = false
        return true
    }

    private fun applySettings(transform: (ExportSettings) -> ExportSettings) {
        _state.update { it.copy(settings = transform(it.settings)) }
    }

    private fun freeBytes(): Long = try {
        store.root.usableSpace
    } catch (error: Throwable) {
        0L
    }

    private fun defaultDurationFor(clipId: String): Float {
        val clip = AnimationLibrary.byId(clipId) ?: return 3f
        // One loop for looping clips; the full length for one-shots, so a first export is sensible.
        return if (clip.loop) clip.durationSeconds * 2f else clip.durationSeconds
    }

    override fun onCleared() {
        exportJob?.cancel()
        character = null
        super.onCleared()
    }

    companion object {
        fun factory(app: RigStudioApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ExportViewModel(app) as T
            }
    }
}
