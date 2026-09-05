package com.rigstudio.app.export

import com.rigstudio.core.export.ExportFormat
import com.rigstudio.core.export.ExportSettings
import com.rigstudio.core.model.ViewKind
import java.io.File

/** What the exporter was asked to produce, resolved against a project and a rig view. */
data class ExportRequest(
    val projectId: String,
    val characterName: String,
    val settings: ExportSettings,
) {
    val clipId: String get() = settings.clipId
    val view: ViewKind get() = settings.view
    val format: ExportFormat get() = settings.format
    val width: Int get() = settings.width
    val height: Int get() = settings.height
    val fps: Int get() = settings.frameRate.fps
    val frameCount: Int get() = settings.frameCount
}

/** Which phase the export is in, and how far through it we are. */
data class ExportProgress(
    val phase: ExportPhase,
    val framesDone: Int = 0,
    val framesTotal: Int = 0,
    val message: String = "",
) {
    val fraction: Float
        get() = when {
            phase == ExportPhase.DONE -> 1f
            phase == ExportPhase.FAILED -> 1f
            framesTotal <= 0 -> phase.baseFraction
            else -> phase.baseFraction + (1f - phase.baseFraction) * (framesDone.toFloat() / framesTotal)
        }
}

enum class ExportPhase(val baseFraction: Float) {
    PREPARING(0f),
    RENDERING(0.05f),
    ENCODING(0.10f),
    AUDIO(0.90f),
    FINALISING(0.95f),
    VALIDATING(0.98f),
    DONE(1f),
    FAILED(1f),
}

/** One post-export check and its outcome (spec §26: validate before offering Save/Share/Open). */
data class ExportCheck(val label: String, val detail: String, val passed: Boolean)

/**
 * Outcome of an export.
 *
 * [file] is only usable when [succeeded]; a failed export always explains itself in plain
 * language ([message]) and lists the checks that failed ([checks]).
 */
data class ExportResult(
    val succeeded: Boolean,
    val file: File? = null,
    val bytes: Long = 0,
    val durationSeconds: Float = 0f,
    val message: String? = null,
    val checks: List<ExportCheck> = emptyList(),
    val format: ExportFormat = ExportFormat.MP4,
) {
    val allChecksPassed: Boolean get() = checks.all { it.passed }

    val summary: String
        get() = when {
            !succeeded -> message ?: "Export failed."
            file == null -> message ?: "Export failed."
            else -> "${file.name} · ${formatBytes(bytes)} · ${"%.2f".format(durationSeconds)} s"
        }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024f)
            else -> "$bytes B"
        }

        fun failure(message: String, checks: List<ExportCheck> = emptyList()) = ExportResult(
            succeeded = false,
            message = message,
            checks = checks,
        )
    }
}
