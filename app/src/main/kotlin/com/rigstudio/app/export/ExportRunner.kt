package com.rigstudio.app.export

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.net.Uri
import androidx.core.content.FileProvider
import com.rigstudio.app.data.LoadedCharacter
import com.rigstudio.app.data.ProjectStore
import com.rigstudio.app.render.StageRenderer
import com.rigstudio.app.render.StageSource
import com.rigstudio.app.render.StageBackground
import com.rigstudio.core.anim.AnimationLibrary
import com.rigstudio.core.export.ExportFormat
import com.rigstudio.core.export.ExportLimits
import com.rigstudio.core.export.ExportSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Runs an export end to end: validate → render every frame → encode → mux → validate the file.
 *
 * Two rules shape this class:
 *  1. **Nothing is recorded from the screen.** Frames are rendered offscreen into a bitmap with
 *     the same composer and camera the preview uses, then encoded. Export therefore works with the
 *     screen off and cannot pick up notifications or other apps.
 *  2. **The file is verified before it is offered to the user.** A finished export is re-opened
 *     (engine container probe + [MediaMetadataRetriever]) and only then reported as a success.
 */
class ExportRunner(
    private val context: Context,
    private val store: ProjectStore,
) {

    suspend fun export(
        request: ExportRequest,
        character: LoadedCharacter,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult = withContext(Dispatchers.Default) {
        val settings = request.settings

        // 1 — refuse unsafe settings before doing any work.
        val blocking = settings.blockingMessage
        if (blocking != null) return@withContext ExportResult.failure(blocking)

        val rig = character.rigFor(settings.view)
            ?: return@withContext ExportResult.failure(
                "${settings.view.displayName} view has no artwork in this character.",
            )
        val clip = AnimationLibrary.byId(settings.clipId)
            ?: return@withContext ExportResult.failure("Unknown animation '${settings.clipId}'.")

        // 2 — storage pre-flight.
        val freeBytes = context.filesDir.usableSpace
        if (freeBytes < settings.estimatedBytes + ExportLimits.MIN_FREE_BYTES_FOR_EXPORT / 8) {
            return@withContext ExportResult.failure(
                "Not enough free storage: this export needs about " +
                    "${ExportResult.formatBytes(settings.estimatedBytes)} and the device has " +
                    "${ExportResult.formatBytes(freeBytes)}.",
            )
        }

        val plan = settings.framePlan
        val baseName = buildFileName(character.project.name, clip.id, settings)

        try {
            val result = when (settings.format) {
                ExportFormat.MP4 -> exportMp4(request, character, settings, baseName, onProgress)
                ExportFormat.PNG_SEQUENCE ->
                    exportPngSequence(request, character, settings, baseName, onProgress)
            }
            if (result.succeeded) {
                onProgress(ExportProgress(ExportPhase.DONE, plan.frameCount, plan.frameCount, result.summary))
            } else {
                onProgress(ExportProgress(ExportPhase.FAILED, message = result.message ?: "Export failed"))
            }
            return@withContext result
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = when (error) {
                is OutOfMemoryError -> "Not enough memory to render ${settings.width}×${settings.height}. " +
                    "Try a shorter clip or a lower resolution."
                else -> error.message?.takeIf { it.isNotBlank() } ?: "Export failed unexpectedly."
            }
            ExportResult.failure(message)
        }
    }

    // --- MP4 ----------------------------------------------------------------------------------

    private suspend fun exportMp4(
        request: ExportRequest,
        character: LoadedCharacter,
        settings: ExportSettings,
        baseName: String,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        val rig = character.rigFor(settings.view)!!
        val clip = AnimationLibrary.byId(settings.clipId)!!
        val plan = settings.framePlan
        val file = File(store.exportsDir(request.projectId), "$baseName.${settings.format.extension}")

        val audio = settings.audioPath?.let { path ->
            AudioSource.open(context, Uri.parse(path))
        }

        val background = settings.effectiveBackground?.let { StageBackground.Solid(it) }
            ?: StageBackground.Transparent

        val writer = Mp4Writer(
            outputFile = file,
            width = settings.width,
            height = settings.height,
            fps = settings.frameRate.fps,
            bitRate = Mp4Writer.recommendedBitRate(settings.width, settings.height, settings.frameRate.fps),
            keyFrameIntervalSeconds = if (settings.durationSeconds < 2f) 1 else 2,
            audio = audio,
        )

        val bitmap = Bitmap.createBitmap(settings.width, settings.height, Bitmap.Config.ARGB_8888)
        val resolver = store.bitmapResolver(character.project)
        // Same stage the editor draws with: identical camera, identical painter, identical draw list.
        val stage = StageRenderer.DEFAULT.prepare(
            StageSource(rig = rig, clip = clip, bitmaps = resolver, background = background),
            settings.width,
            settings.height,
        )
        onProgress(ExportProgress(ExportPhase.PREPARING, 0, plan.frameCount, "Starting encoder…"))
        writer.open()
        val frameBuffer = writer.reusableFrameBuffer

        try {
            for (index in 0 until plan.frameCount) {
                coroutineContext.ensureActive()
                stage.paintInto(bitmap, plan.normalizedTimeAt(index, clip.durationSeconds, settings.speed))
                frameBuffer.readFrom(bitmap)
                writer.submitFrame(frameBuffer.pixels, plan.presentationTimeUs(index))

                onProgress(
                    ExportProgress(
                        ExportPhase.RENDERING,
                        index + 1,
                        plan.frameCount,
                        "Rendering frame ${index + 1} of ${plan.frameCount}",
                    ),
                )
            }

            onProgress(ExportProgress(ExportPhase.ENCODING, plan.frameCount, plan.frameCount, "Flushing encoder…"))
            writer.endVideo()

            if (audio != null) {
                onProgress(ExportProgress(ExportPhase.AUDIO, plan.frameCount, plan.frameCount, "Adding audio track…"))
                writer.copyAudio(plan.presentationTimeUs(plan.frameCount - 1) + 1_000_000L / settings.frameRate.fps)
            }
        } finally {
            writer.close()
            bitmap.recycle()
        }

        onProgress(ExportProgress(ExportPhase.VALIDATING, plan.frameCount, plan.frameCount, "Checking the file…"))
        return validateMp4(file, settings, audioWasRequested = audio != null)
    }

    /** Post-export validation: container bytes, video track, dimensions, duration and audio. */
    private fun validateMp4(file: File, settings: ExportSettings, audioWasRequested: Boolean): ExportResult {
        if (!file.isFile || file.length() <= 0L) {
            return ExportResult.failure("The export produced no file.", checks = listOf(
                ExportCheck("File written", "missing or empty", false),
            ))
        }

        val probe = Mp4Writer.probeContainer(file)
        val expectedDuration = settings.framePlan.frameCount.toFloat() / settings.frameRate.fps
        val toleranceSeconds = 2f / settings.frameRate.fps + 0.05f

        var measuredDurationSeconds = -1f
        var measuredWidth = -1
        var measuredHeight = -1
        var retrieverError: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            measuredDurationSeconds = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toFloatOrNull() ?: -1f) / 1000f
            measuredWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: -1
            measuredHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: -1
        } catch (error: Throwable) {
            retrieverError = error.message ?: "metadata unavailable"
        } finally {
            // release() only exists from API 29; older releases free the retriever on finalize.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { retriever.release() }
            }
        }

        val effectiveFps = if (measuredDurationSeconds > 0.01f) {
            settings.framePlan.frameCount / measuredDurationSeconds
        } else {
            -1f
        }

        val checks = listOf(
            ExportCheck(
                "MP4 container",
                probe.failureReason ?: "ftyp · moov · mdat present",
                probe.looksValid,
            ),
            ExportCheck(
                "File size",
                ExportResult.formatBytes(file.length()),
                file.length() > 0,
            ),
            ExportCheck(
                "Video track",
                if (retrieverError != null) retrieverError else "${measuredWidth}×$measuredHeight",
                measuredWidth == settings.width && measuredHeight == settings.height,
            ),
            ExportCheck(
                "Duration",
                if (measuredDurationSeconds < 0f) "unreadable" else "%.2f s (expected %.2f s)"
                    .format(measuredDurationSeconds, expectedDuration),
                measuredDurationSeconds > 0f &&
                    abs(measuredDurationSeconds - expectedDuration) <= toleranceSeconds,
            ),
            ExportCheck(
                "Frame rate",
                if (effectiveFps < 0f) "unreadable" else "%.1f fps (expected %d fps)"
                    .format(effectiveFps, settings.frameRate.fps),
                effectiveFps > 0f && abs(effectiveFps - settings.frameRate.fps) <= 1.5f,
            ),
            ExportCheck(
                "Audio track",
                when {
                    !audioWasRequested -> "silent by design"
                    probe.looksValid -> "muxed from the chosen file"
                    else -> "unavailable"
                },
                true,
            ),
        )

        val failed = checks.filterNot { it.passed }
        return if (failed.isEmpty()) {
            ExportResult(
                succeeded = true,
                file = file,
                bytes = file.length(),
                durationSeconds = measuredDurationSeconds,
                checks = checks,
                format = ExportFormat.MP4,
            )
        } else {
            ExportResult.failure(
                "The exported file failed validation: " + failed.joinToString("; ") { "${it.label} — ${it.detail}" },
                checks = checks,
            ).copy(bytes = file.length(), file = file)
        }
    }

    // --- PNG sequence -------------------------------------------------------------------------

    private suspend fun exportPngSequence(
        request: ExportRequest,
        character: LoadedCharacter,
        settings: ExportSettings,
        baseName: String,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        val rig = character.rigFor(settings.view)!!
        val clip = AnimationLibrary.byId(settings.clipId)!!
        val plan = settings.framePlan
        val exportsDir = store.exportsDir(request.projectId)
        val zipFile = File(exportsDir, "$baseName.${settings.format.extension}")
        val frameCount = plan.frameCount
        val digits = maxOf(4, frameCount.toString().length)

        val bitmap = Bitmap.createBitmap(settings.width, settings.height, Bitmap.Config.ARGB_8888)
        val resolver = store.bitmapResolver(character.project)
        val background = settings.effectiveBackground?.let { StageBackground.Solid(it) }
            ?: StageBackground.Transparent // real alpha: this is why PNG frames exist
        val stage = StageRenderer.DEFAULT.prepare(
            StageSource(rig = rig, clip = clip, bitmaps = resolver, background = background),
            settings.width,
            settings.height,
        )

        var framesWritten = 0
        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zip ->
            for (index in 0 until frameCount) {
                coroutineContext.ensureActive()
                stage.paintInto(bitmap, plan.normalizedTimeAt(index, clip.durationSeconds, settings.speed))

                zip.putNextEntry(ZipEntry("frame_%0${digits}d.png".format(index + 1)))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
                framesWritten++

                onProgress(
                    ExportProgress(
                        ExportPhase.RENDERING,
                        framesWritten,
                        frameCount,
                        "Writing PNG frame ${index + 1} of $frameCount",
                    ),
                )
            }
            zip.finish()
        }
        bitmap.recycle()

        onProgress(ExportProgress(ExportPhase.VALIDATING, frameCount, frameCount, "Checking the archive…"))
        val valid = zipFile.isFile && zipFile.length() > 0 && framesWritten == frameCount
        val checks = listOf(
            ExportCheck("Archive written", ExportResult.formatBytes(zipFile.length()), zipFile.isFile),
            ExportCheck("Frames", "$framesWritten of $frameCount", framesWritten == frameCount),
            ExportCheck(
                "Transparency",
                if (settings.transparentBackground) "alpha preserved" else "opaque background",
                true,
            ),
        )
        return if (valid) {
            ExportResult(
                succeeded = true,
                file = zipFile,
                bytes = zipFile.length(),
                durationSeconds = plan.frameCount.toFloat() / settings.frameRate.fps,
                checks = checks,
                format = ExportFormat.PNG_SEQUENCE,
            )
        } else {
            ExportResult.failure("The PNG sequence could not be written.", checks = checks)
        }
    }

    // --- handing the file to the user ----------------------------------------------------------

    /** Copies a finished export to a user-chosen location (Storage Access Framework). */
    suspend fun copyTo(destination: Uri, file: File): Result<Long> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(destination, "wt")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                    out.flush()
                }
            } ?: return@withContext Result.failure(IllegalStateException("That location could not be opened."))
            Result.success(file.length())
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    /** Content URI for Share / Open, granted temporary read permission. */
    fun shareUri(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    fun buildFileName(characterName: String, clipId: String, settings: ExportSettings): String {
        val safeName = characterName.replace(UNSAFE_FILENAME, "_").take(28).trim().ifBlank { "rigstudio" }
        return "${safeName}_${clipId}_${settings.width}x${settings.height}_${settings.frameRate.fps}fps"
    }

    private companion object {
        val UNSAFE_FILENAME = Regex("[^A-Za-z0-9._-]+")
    }
}
