package com.rigstudio.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.rigstudio.app.art.SampleCharacterArt
import com.rigstudio.app.data.LoadedCharacter
import com.rigstudio.app.data.ProjectStore
import com.rigstudio.app.render.BitmapPixelSurface
import com.rigstudio.app.render.StageBackground
import com.rigstudio.app.render.ThumbnailRenderer
import com.rigstudio.app.render.toBitmap
import com.rigstudio.core.extract.SheetImageMeta
import com.rigstudio.core.extract.SheetIssue
import com.rigstudio.core.extract.SheetIssueLevel
import com.rigstudio.core.extract.SheetProcessResult
import com.rigstudio.core.extract.SheetProcessor
import com.rigstudio.core.model.CharacterProject
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape
import com.rigstudio.core.model.ViewKind
import com.rigstudio.core.rig.RigBuildResult
import com.rigstudio.core.rig.RigBuilder
import com.rigstudio.core.rig.RigOptions
import com.rigstudio.core.template.CharacterSheetTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Which step of the import pipeline is running — shown verbatim in the progress UI. */
enum class ImportStage {
    READING_FILE,
    VALIDATING,
    EXTRACTING,
    RIGGING,
    SAVING,
    DONE,
    FAILED,
}

/** How far along the pipeline a stage is, so the import bar reflects real work. */
val ImportStage.progressFraction: Float
    get() = when (this) {
        ImportStage.READING_FILE -> 0.10f
        ImportStage.VALIDATING -> 0.35f
        ImportStage.EXTRACTING -> 0.60f
        ImportStage.RIGGING -> 0.80f
        ImportStage.SAVING -> 0.92f
        ImportStage.DONE -> 1f
        ImportStage.FAILED -> 1f
    }

/** Short human label for a stage — shown verbatim in the import progress UI. */
val ImportStage.label: String
    get() = when (this) {
        ImportStage.READING_FILE -> "Reading the character sheet"
        ImportStage.VALIDATING -> "Validating slots and artwork"
        ImportStage.EXTRACTING -> "Extracting and trimming parts"
        ImportStage.RIGGING -> "Building the rig"
        ImportStage.SAVING -> "Saving the character"
        ImportStage.DONE -> "Character ready"
        ImportStage.FAILED -> "Import failed"
    }

/** Everything the import screen needs to explain what happened. */
data class ImportOutcome(
    val stage: ImportStage,
    val character: LoadedCharacter? = null,
    val issues: List<SheetIssue> = emptyList(),
    val filledSlots: Int = 0,
    val totalSlots: Int = CharacterSheetTemplate.SLOTS.size,
    val availableViews: List<ViewKind> = emptyList(),
    val mirrorOffered: Boolean = false,
    val failureMessage: String? = null,
) {
    val errors: List<SheetIssue> get() = issues.filter { it.level == SheetIssueLevel.ERROR }
    val warnings: List<SheetIssue> get() = issues.filter { it.level == SheetIssueLevel.WARNING }
    val infos: List<SheetIssue> get() = issues.filter { it.level == SheetIssueLevel.INFO }
    val isRiggable: Boolean get() = character != null
    val progress: Float get() = stage.progressFraction

    val stageLabel: String get() = stage.label
}

/**
 * What a sheet contains, decided **before** anything is written to disk.
 *
 * The import screen shows this so the user can fix a sheet (or accept the mirror offer) instead of
 * discovering a problem after the character was already saved.
 */
data class ImportAnalysis(
    val meta: SheetImageMeta,
    val issues: List<SheetIssue>,
    val filledSlots: Int,
    val availableViews: List<ViewKind>,
    val canMirrorSideView: Boolean,
    val isRiggable: Boolean,
    val availableExpressions: List<Expression>,
    val availableMouthShapes: List<MouthShape>,
    val headline: String?,
    val totalSlots: Int = CharacterSheetTemplate.SLOTS.size,
) {
    val errors: List<SheetIssue> get() = issues.filter { it.level == SheetIssueLevel.ERROR }
    val warnings: List<SheetIssue> get() = issues.filter { it.level == SheetIssueLevel.WARNING }
    val infos: List<SheetIssue> get() = issues.filter { it.level == SheetIssueLevel.INFO }

    /** Fraction of the sheet's slots that hold artwork — the import screen's main progress bar. */
    val fillRatio: Float get() = if (totalSlots <= 0) 0f else filledSlots.toFloat() / totalSlots

    val viewLabel: String
        get() = availableViews.joinToString(" · ") { it.displayName }.ifBlank { "none" }

    companion object {
        fun failure(meta: SheetImageMeta, message: String) = ImportAnalysis(
            meta = meta,
            issues = listOf(SheetIssue(SheetIssueLevel.ERROR, message)),
            filledSlots = 0,
            availableViews = emptyList(),
            canMirrorSideView = false,
            isRiggable = false,
            availableExpressions = emptyList(),
            availableMouthShapes = emptyList(),
            headline = message,
        )
    }
}

/**
 * The import flow: **validate → extract → trim → rig → save → "Character Ready"** (spec §5).
 *
 * Nothing here is adaptive. The template knows where every part lives, so the pipeline reads
 * fixed rectangles, trims transparent margins, maps pivots and builds the rig — deterministically,
 * offline, in well under a second on a modern phone.
 *
 * Only the mandatory front parts can fail the import. Everything else (hands, feet, face, profile
 * and back artwork) produces a warning or an informational note, and the character is still built.
 */
class SheetImporter(
    private val context: Context,
    private val store: ProjectStore,
) {

    /**
     * @param mirrorSideView when true and the sheet has left-profile artwork but no right-profile
     *   artwork, the right-facing view is derived by mirroring — never invented from a front view.
     */
    suspend fun import(
        uri: Uri,
        name: String?,
        mirrorSideView: Boolean,
        onStage: (ImportStage) -> Unit = {},
    ): ImportOutcome = withContext(Dispatchers.IO) {
        try {
            onStage(ImportStage.READING_FILE)
            val meta = readMeta(uri)
            val sizeProblem = sizeMismatch(meta)
            if (sizeProblem != null) {
                return@withContext ImportOutcome(stage = ImportStage.FAILED, failureMessage = sizeProblem)
            }

            val bitmap = decodeSheet(uri)
                ?: return@withContext ImportOutcome(
                    stage = ImportStage.FAILED,
                    failureMessage = UNREADABLE_IMAGE,
                )

            importBitmap(bitmap, meta, name, mirrorSideView, onStage)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            onStage(ImportStage.FAILED)
            ImportOutcome(stage = ImportStage.FAILED, failureMessage = humanMessage(error))
        }
    }

    /**
     * Reads a picked sheet without saving anything: the same validate → extract → rig pipeline,
     * stopped one step before disk. The caller keeps the bitmap and hands it to [importBitmap] so a
     * 2048×2048 PNG is only ever decoded once per import.
     */
    suspend fun analyze(bitmap: Bitmap, meta: SheetImageMeta): ImportAnalysis =
        withContext(Dispatchers.IO) {
            val sizeProblem = sizeMismatch(meta)
            if (sizeProblem != null) return@withContext ImportAnalysis.failure(meta, sizeProblem)
            try {
                val processed = SheetProcessor().process(BitmapPixelSurface(bitmap), meta)
                val built = RigBuilder.build(processed)
                ImportAnalysis(
                    meta = meta,
                    issues = built.report.issues,
                    filledSlots = built.report.filledSlotIds.size,
                    availableViews = built.availableViews,
                    canMirrorSideView = built.report.canMirrorSideView,
                    isRiggable = built.isRigged,
                    availableExpressions = built.report.availableExpressions,
                    availableMouthShapes = built.report.availableMouthShapes,
                    headline = built.report.headlineMessage,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ImportAnalysis.failure(meta, humanMessage(error))
            }
        }

    /** Reads a picked sheet's metadata (size, mime, byte count) without decoding pixels. */
    suspend fun readMetadata(uri: Uri): SheetImageMeta = withContext(Dispatchers.IO) { readMeta(uri) }

    /** Decodes a picked sheet, or null when the file is not a readable image. */
    suspend fun decode(uri: Uri): Bitmap? = withContext(Dispatchers.IO) { decodeSheet(uri) }

    /**
     * Rigs and saves an already-decoded sheet. Always consumes [bitmap] (recycled on every path),
     * so callers must not touch it afterwards.
     */
    suspend fun importBitmap(
        bitmap: Bitmap,
        meta: SheetImageMeta,
        name: String?,
        mirrorSideView: Boolean,
        onStage: (ImportStage) -> Unit = {},
    ): ImportOutcome = withContext(Dispatchers.IO) {
        try {
            val sizeProblem = sizeMismatch(meta)
            if (sizeProblem != null) {
                bitmap.recycle()
                return@withContext ImportOutcome(stage = ImportStage.FAILED, failureMessage = sizeProblem)
            }

            onStage(ImportStage.VALIDATING)
            onStage(ImportStage.EXTRACTING)
            // One pass: every slot is read exactly once, trimmed and validated.
            val processed = SheetProcessor().process(BitmapPixelSurface(bitmap), meta)

            onStage(ImportStage.RIGGING)
            val built = RigBuilder.build(processed, RigOptions(mirrorSideView = mirrorSideView))
            if (!built.isRigged) {
                bitmap.recycle()
                return@withContext ImportOutcome(
                    stage = ImportStage.FAILED,
                    issues = built.report.issues,
                    filledSlots = built.report.filledSlotIds.size,
                    failureMessage = built.report.headlineMessage
                        ?: "This sheet is missing the required front-body parts.",
                )
            }

            onStage(ImportStage.SAVING)
            val now = System.currentTimeMillis()
            val displayName = (name ?: defaultName()).trim().ifBlank { defaultName() }
            val project = CharacterProject(
                id = ProjectStore.newProjectId(now),
                name = displayName,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                lastOpenedAtEpochMillis = now,
                sheetFileName = ProjectStore.SHEET_NAME,
                sheetWidth = meta.width,
                sheetHeight = meta.height,
                thumbnailFileName = ProjectStore.THUMBNAIL_NAME,
                sprites = emptyList(), // filled in by saveImported from the extracted sprites
                availableViews = built.availableViews,
                mirroredSideView = built.availability.mirroredSideView,
                availableExpressions = built.report.availableExpressions,
                availableMouthShapes = built.report.availableMouthShapes,
                notes = built.report.issues
                    .filter { it.level != SheetIssueLevel.ERROR }
                    .map { it.message },
                lastClipId = "idle",
                lastView = ViewKind.FRONT,
                lastBackgroundArgb = StageBackground.DEFAULT_BACKGROUND_ARGB,
                lastSpeed = 1f,
                templateVersion = CharacterSheetTemplate.VERSION,
            )

            val resolver = inMemoryResolver(processed)
            val thumbnail = built.rigFor(ViewKind.FRONT)?.let { rig ->
                ThumbnailRenderer.render(
                    rig = rig,
                    bitmaps = resolver,
                    size = ProjectStore.THUMBNAIL_SIZE,
                )
            }

            val saved = store.saveImported(
                project = project,
                sheet = bitmap,
                sprites = processed.sprites.filterValues { !it.isBlank() },
                thumbnail = thumbnail,
            )
            thumbnail?.recycle()
            bitmap.recycle()

            onStage(ImportStage.DONE)
            ImportOutcome(
                stage = ImportStage.DONE,
                character = saved,
                issues = built.report.issues,
                filledSlots = built.report.filledSlotIds.size,
                availableViews = saved.availableViews,
                mirrorOffered = built.report.canMirrorSideView,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            bitmap.recycle()
            throw cancelled
        } catch (error: Throwable) {
            onStage(ImportStage.FAILED)
            ImportOutcome(
                stage = ImportStage.FAILED,
                failureMessage = humanMessage(error),
            )
        }
    }

    /** Null when the sheet is the right size, otherwise the message to show. */
    private fun sizeMismatch(meta: SheetImageMeta): String? =
        if (meta.width == CharacterSheetTemplate.SHEET_WIDTH &&
            meta.height == CharacterSheetTemplate.SHEET_HEIGHT
        ) {
            null
        } else {
            "This image is ${meta.width}×${meta.height} pixels. RigStudio needs a " +
                "${CharacterSheetTemplate.SHEET_WIDTH}×${CharacterSheetTemplate.SHEET_HEIGHT} " +
                "PNG character sheet."
        }

    /**
     * Builds the bundled sample character: original placeholder artwork drawn straight into the
     * template's slots, then run through the very same pipeline. It exists so the app can be
     * tested end to end (rig, animate, export) without the user drawing anything first.
     */
    suspend fun createSampleCharacter(name: String = "Sample Character"): ImportOutcome =
        withContext(Dispatchers.IO) {
            try {
                val sheet = SampleCharacterArt.renderSheet()
                val processed = SheetProcessor().process(
                    BitmapPixelSurface(sheet),
                    SheetImageMeta(sheet.width, sheet.height, true, "image/png", -1),
                )
                val built = RigBuilder.build(processed)
                if (!built.isRigged) {
                    sheet.recycle()
                    return@withContext ImportOutcome(
                        stage = ImportStage.FAILED,
                        issues = built.report.issues,
                        failureMessage = "The bundled sample character failed its own self-check. " +
                            "Please report this: ${built.report.headlineMessage}",
                    )
                }
                val now = System.currentTimeMillis()
                val project = CharacterProject(
                    id = ProjectStore.newProjectId(now),
                    name = name,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    lastOpenedAtEpochMillis = now,
                    sheetFileName = ProjectStore.SHEET_NAME,
                    sheetWidth = sheet.width,
                    sheetHeight = sheet.height,
                    thumbnailFileName = ProjectStore.THUMBNAIL_NAME,
                    sprites = emptyList(),
                    availableViews = built.availableViews,
                    mirroredSideView = built.availability.mirroredSideView,
                    availableExpressions = built.report.availableExpressions,
                    availableMouthShapes = built.report.availableMouthShapes,
                    notes = listOf("Bundled sample character — placeholder artwork, freely editable."),
                    lastClipId = "idle",
                    lastView = ViewKind.FRONT,
                    lastBackgroundArgb = StageBackground.DEFAULT_BACKGROUND_ARGB,
                    lastSpeed = 1f,
                )
                val resolver = inMemoryResolver(processed)
                val thumbnail = built.rigFor(ViewKind.FRONT)?.let {
                    ThumbnailRenderer.render(it, resolver, ProjectStore.THUMBNAIL_SIZE)
                }
                val saved = store.saveImported(project, sheet, processed.sprites, thumbnail)
                thumbnail?.recycle()
                sheet.recycle()
                ImportOutcome(
                    stage = ImportStage.DONE,
                    character = saved,
                    issues = built.report.issues,
                    filledSlots = built.report.filledSlotIds.size,
                    availableViews = saved.availableViews,
                )
            } catch (error: Throwable) {
                ImportOutcome(stage = ImportStage.FAILED, failureMessage = humanMessage(error))
            }
        }

    // --- helpers -----------------------------------------------------------------------------

    private fun readMeta(uri: Uri): SheetImageMeta {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        val mimeType = try {
            context.contentResolver.getType(uri)
        } catch (error: Throwable) {
            null
        }
        val byteCount = try {
            openStream(uri)?.use { stream ->
                var total = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                }
                total
            } ?: -1L
        } catch (error: Throwable) {
            -1L
        }
        // The decoder's own mime answer beats the resolver's (which can be null for
        // content:// documents). Only the container decides alpha here: every lossless format
        // we accept carries an alpha channel and JPEG physically cannot. Whether that channel
        // actually holds transparency is settled empirically by the core validator.
        val resolvedMime = options.outMimeType ?: mimeType
        return SheetImageMeta(
            width = options.outWidth,
            height = options.outHeight,
            hasAlpha = resolvedMime != null && !resolvedMime.startsWith("image/jpe", ignoreCase = true),
            mimeType = resolvedMime,
            byteCount = byteCount,
        )
    }

    private fun decodeSheet(uri: Uri): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
            inSampleSize = 1 // exact pixels: extraction reads fixed coordinates
        }
        return openStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun openStream(uri: Uri) = try {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).takeIf(File::isFile)?.inputStream() }
                ?: context.contentResolver.openInputStream(uri)
        } else {
            context.contentResolver.openInputStream(uri)
        }
    } catch (error: Throwable) {
        null
    }

    /** Resolves freshly extracted sprites without touching disk (used for the thumbnail). */
    private fun inMemoryResolver(processed: SheetProcessResult): (String) -> Bitmap? {
        val cache = HashMap<String, Bitmap?>(processed.sprites.size)
        return { slotId ->
            if (cache.containsKey(slotId)) {
                cache[slotId]
            } else {
                processed.sprites[slotId]?.takeIf { !it.isBlank() }?.toBitmap().also { cache[slotId] = it }
            }
        }
    }

    private fun defaultName(): String {
        val existing = store.list().size + 1
        return "Character $existing"
    }

    companion object {
        /** Shown when a picked file is not a decodable image. */
        const val UNREADABLE_IMAGE =
            "This file could not be read as an image. Please export your character sheet as a PNG."
    }

    private fun humanMessage(error: Throwable): String = when (error) {
        is OutOfMemoryError -> "Not enough memory to open this sheet. Close other apps and try again."
        is java.io.FileNotFoundException -> "That file could not be found. It may have been moved or deleted."
        is SecurityException -> "RigStudio is not allowed to read that file."
        else -> error.message?.takeIf { it.isNotBlank() }
            ?: "Something went wrong while importing this sheet."
    }
}
