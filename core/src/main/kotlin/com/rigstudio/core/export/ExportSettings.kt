package com.rigstudio.core.export

import com.rigstudio.core.model.ViewKind

/**
 * Every safety limit for rendering/exporting, in one place.
 *
 * These exist so a user cannot ask for something that would exhaust memory on a phone: a
 * 1080p ARGB frame is 8.3 MB, and RigStudio renders frames one at a time (never buffering the
 * whole clip), but the *encoder* and the muxer still need sane totals.
 */
object ExportLimits {

    /** Shortest clip worth exporting. */
    const val MIN_DURATION_SECONDS = 0.5f

    /** Longest export. 30 s at 60 fps = 1800 frames, the hard ceiling below. */
    const val MAX_DURATION_SECONDS = 30f

    /** Absolute frame ceiling regardless of duration/fps combination. */
    const val MAX_TOTAL_FRAMES = 1800

    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 3f

    const val MIN_WIDTH = 480
    const val MAX_WIDTH = 1920
    const val MIN_HEIGHT = 270
    const val MAX_HEIGHT = 1080

    const val MIN_FPS = 12
    const val MAX_FPS = 60

    /** Refuse to start an export when free storage is below this. */
    const val MIN_FREE_BYTES_FOR_EXPORT = 120L * 1024 * 1024

    /** Rough per-frame budget used to warn before an export starts. */
    const val ESTIMATED_BYTES_PER_FRAME_H264 = 60_000L
    const val ESTIMATED_BYTES_PER_FRAME_PNG = 350_000L
}

/** Output pixel size. Only device-safe presets are offered — no arbitrary numbers. */
enum class ExportResolution(val label: String, val width: Int, val height: Int) {
    HD_720("720p (1280 × 720)", 1280, 720),
    FULL_HD_1080("1080p (1920 × 1080)", 1920, 1080),
    ;

    val aspect: Float get() = width.toFloat() / height.toFloat()
}

/** Output frame rate. */
enum class ExportFrameRate(val fps: Int) {
    FPS_24(24),
    FPS_30(30),
    FPS_60(60),
}

/** What the app writes. MP4 is the primary format; PNG frames are the alpha-capable option. */
enum class ExportFormat(val displayName: String, val extension: String, val mimeType: String) {
    /** H.264 in an MP4 container, encoded on device with MediaCodec + MediaMuxer. */
    MP4("MP4 video (H.264)", "mp4", "video/mp4"),

    /** One lossless PNG per frame, zipped. Keeps real transparency. */
    PNG_SEQUENCE("PNG sequence (alpha)", "zip", "application/zip"),
    ;

    val supportsTransparency: Boolean get() = this == PNG_SEQUENCE
}

/** One validation finding about an export request. */
data class ExportValidationIssue(val message: String, val blocking: Boolean)

/**
 * A fully specified export request. Value object, so the UI, the worker and the tests all speak
 * about exactly the same thing.
 */
data class ExportSettings(
    val clipId: String,
    val view: ViewKind = ViewKind.FRONT,
    val format: ExportFormat = ExportFormat.MP4,
    val resolution: ExportResolution = ExportResolution.FULL_HD_1080,
    val frameRate: ExportFrameRate = ExportFrameRate.FPS_30,
    val durationSeconds: Float = 3f,
    val speed: Float = 1f,
    /** Ignored when [transparentBackground] is true and the format supports alpha. */
    val backgroundArgb: Int = DEFAULT_BACKGROUND,
    val transparentBackground: Boolean = false,
    /** Optional local audio file muxed into the MP4 (AAC passthrough). */
    val audioPath: String? = null,
) {

    val framePlan: FramePlan get() = FramePlan.of(durationSeconds, frameRate.fps)

    val frameCount: Int get() = framePlan.frameCount

    val width: Int get() = resolution.width
    val height: Int get() = resolution.height

    val effectiveBackground: Int?
        get() = if (transparentBackground && format.supportsTransparency) null else backgroundArgb

    /** Rough output size estimate, used for the "not enough storage" pre-flight check. */
    val estimatedBytes: Long
        get() = when (format) {
            ExportFormat.MP4 -> frameCount * ExportLimits.ESTIMATED_BYTES_PER_FRAME_H264
            ExportFormat.PNG_SEQUENCE -> frameCount * ExportLimits.ESTIMATED_BYTES_PER_FRAME_PNG
        }

    /**
     * Rejects unsafe or contradictory settings **before** any rendering starts, with messages
     * that can be shown verbatim.
     */
    fun validate(): List<ExportValidationIssue> {
        val issues = mutableListOf<ExportValidationIssue>()

        if (durationSeconds < ExportLimits.MIN_DURATION_SECONDS) {
            issues += ExportValidationIssue(
                "Duration must be at least ${ExportLimits.MIN_DURATION_SECONDS} seconds.",
                blocking = true,
            )
        }
        if (durationSeconds > ExportLimits.MAX_DURATION_SECONDS) {
            issues += ExportValidationIssue(
                "Duration must be ${ExportLimits.MAX_DURATION_SECONDS} seconds or less.",
                blocking = true,
            )
        }
        if (speed < ExportLimits.MIN_SPEED || speed > ExportLimits.MAX_SPEED) {
            issues += ExportValidationIssue(
                "Speed must be between ${ExportLimits.MIN_SPEED}× and ${ExportLimits.MAX_SPEED}×.",
                blocking = true,
            )
        }
        if (frameRate.fps < ExportLimits.MIN_FPS || frameRate.fps > ExportLimits.MAX_FPS) {
            issues += ExportValidationIssue(
                "Frame rate must be between ${ExportLimits.MIN_FPS} and ${ExportLimits.MAX_FPS} fps.",
                blocking = true,
            )
        }
        if (width < ExportLimits.MIN_WIDTH || width > ExportLimits.MAX_WIDTH) {
            issues += ExportValidationIssue(
                "Width must be between ${ExportLimits.MIN_WIDTH} and ${ExportLimits.MAX_WIDTH} pixels.",
                blocking = true,
            )
        }
        if (height < ExportLimits.MIN_HEIGHT || height > ExportLimits.MAX_HEIGHT) {
            issues += ExportValidationIssue(
                "Height must be between ${ExportLimits.MIN_HEIGHT} and ${ExportLimits.MAX_HEIGHT} pixels.",
                blocking = true,
            )
        }
        val frames = FramePlan.of(durationSeconds, frameRate.fps).frameCount
        if (frames > ExportLimits.MAX_TOTAL_FRAMES) {
            issues += ExportValidationIssue(
                "That combination needs $frames frames; the limit is " +
                    "${ExportLimits.MAX_TOTAL_FRAMES}. Lower the duration or the frame rate.",
                blocking = true,
            )
        }
        if (transparentBackground && !format.supportsTransparency) {
            // H.264 in MP4 has no alpha channel. Say so instead of silently exporting black.
            issues += ExportValidationIssue(
                "MP4 (H.264) cannot store transparency. Choose a background colour, or export a " +
                    "PNG sequence to keep the alpha channel.",
                blocking = true,
            )
        }
        if (audioPath != null && format != ExportFormat.MP4) {
            issues += ExportValidationIssue(
                "Audio can only be added to an MP4 export.",
                blocking = true,
            )
        }
        if (clipId.isBlank()) {
            issues += ExportValidationIssue("Choose an animation to export.", blocking = true)
        }
        return issues
    }

    val isValid: Boolean get() = validate().none { it.blocking }

    /** The first blocking message, for dialogs and snackbars. */
    val blockingMessage: String? get() = validate().firstOrNull { it.blocking }?.message

    companion object {
        /** Editor default backdrop: a deep neutral that reads as "pro tool", not black. */
        const val DEFAULT_BACKGROUND = 0xFF12161C.toInt()

        val DEFAULT = ExportSettings(clipId = "idle")
    }
}
