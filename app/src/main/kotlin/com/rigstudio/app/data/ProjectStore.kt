package com.rigstudio.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.rigstudio.app.render.downscaleTo
import com.rigstudio.app.render.ThumbnailRenderer
import com.rigstudio.app.render.toBitmap
import com.rigstudio.core.extract.ExtractedSprite
import com.rigstudio.core.model.CharacterProject
import com.rigstudio.core.model.ProjectCodec
import com.rigstudio.core.model.SpriteManifestEntry
import com.rigstudio.core.model.ViewKind
import com.rigstudio.core.rig.CharacterRig
import com.rigstudio.core.rig.RigBuilder
import com.rigstudio.core.rig.ViewAvailability
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** One row in the character list: everything the home screen needs without opening the project. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val updatedAtEpochMillis: Long,
    val lastOpenedAtEpochMillis: Long,
    val views: List<ViewKind>,
    val spriteCount: Int,
    val thumbnailFile: File?,
) {
    val viewLabel: String
        get() = views.joinToString(" · ") { it.displayName }.ifBlank { "Front" }
}

/** A project plus the rigs rebuilt from its saved sprite manifest — no re-extraction needed. */
class LoadedCharacter(
    val project: CharacterProject,
    val rigs: Map<ViewKind, CharacterRig>,
    val directory: File,
) {
    fun rigFor(view: ViewKind): CharacterRig? = rigs[view]

    val availableViews: List<ViewKind> get() = rigs.keys.toList()

    val hasProfileArtwork: Boolean get() = project.hasProfileArtwork
}

/** Thrown when a project cannot be read; the UI shows the message and offers a retry. */
class ProjectStoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Local storage for characters.
 *
 * Layout — everything inside the app's private files directory, so no runtime permission is ever
 * needed and nothing is reachable by other apps:
 *
 * ```
 * files/projects/<id>/
 *   project.json      manifest: identity, availability, last editor state, sprite index
 *   sheet.png         the imported 2048² character sheet (kept for re-extraction)
 *   thumb.png         front-view thumbnail for the project list
 *   sprites/<slot>.png  trimmed artwork per part, exactly as extracted
 *   exports/          finished MP4 / PNG-frame exports (shared via FileProvider)
 * ```
 *
 * Writes are atomic (temp file + rename) so an interrupted save can never leave a half-written
 * `project.json` behind — a character either exists intact or does not exist.
 */
class ProjectStore(private val context: Context) {

    val root: File get() = File(context.filesDir, "projects")

    /** Decoded sprite artwork, capped so a long editing session cannot grow without bound. */
    private val spriteCache = object : LruCache<String, Bitmap>(maxCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun ensureLayout() {
        root.mkdirs()
    }

    fun directoryFor(projectId: String): File = File(root, projectId)

    fun spritesDir(projectId: String): File = File(directoryFor(projectId), "sprites")

    fun exportsDir(projectId: String): File = File(directoryFor(projectId), "exports").apply { mkdirs() }

    fun sheetFile(projectId: String): File = File(directoryFor(projectId), "sheet.png")

    fun thumbnailFile(projectId: String): File = File(directoryFor(projectId), "thumb.png")

    fun spriteFile(projectId: String, slotId: String): File = File(spritesDir(projectId), "$slotId.png")

    // --- listing -----------------------------------------------------------------------------

    /** Every saved character, newest first. Unreadable projects are skipped, not fatal. */
    fun list(): List<ProjectSummary> {
        ensureLayout()
        val dirs = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val project = readProject(dir) ?: return@mapNotNull null
            val thumb = File(dir, project.thumbnailFileName ?: "")
            ProjectSummary(
                id = project.id,
                name = project.name,
                updatedAtEpochMillis = project.updatedAtEpochMillis,
                lastOpenedAtEpochMillis = project.lastOpenedAtEpochMillis,
                views = project.availableViews,
                spriteCount = project.sprites.size,
                thumbnailFile = if (project.thumbnailFileName != null && thumb.isFile) thumb else null,
            )
        }.sortedByDescending { it.lastOpenedAtEpochMillis.coerceAtLeast(it.updatedAtEpochMillis) }
    }

    fun readProject(directory: File): CharacterProject? {
        val file = File(directory, PROJECT_FILE)
        if (!file.isFile) return null
        return try {
            ProjectCodec.decode(file.readText(Charsets.UTF_8))
        } catch (error: Throwable) {
            null // a corrupt manifest simply does not appear in the list
        }
    }

    // --- loading -----------------------------------------------------------------------------

    /**
     * Opens a character and rebuilds its rigs from the saved manifest.
     *
     * Rebuilding from measurements (not pixels) is what makes reopening instant and identical to
     * the first import: the same manifest always produces the same rig, on any device.
     */
    fun load(projectId: String): LoadedCharacter {
        val dir = directoryFor(projectId)
        val project = readProject(dir)
            ?: throw ProjectStoreException("This character could not be opened. Its file is missing or damaged.")
        val rigs = RigBuilder.buildFromAssets(
            project.spriteAssets(),
            ViewAvailability.from(project.availableViews, project.mirroredSideView),
        )
        if (rigs.isEmpty()) {
            throw ProjectStoreException(
                "This character has no usable parts. Re-import the character sheet to rebuild it.",
            )
        }
        return LoadedCharacter(project, rigs, dir)
    }

    /** Decoded artwork for one slot, or null when that part was not drawn. Cached. */
    fun spriteBitmap(project: CharacterProject, slotId: String): Bitmap? {
        val key = "${project.id}/$slotId"
        spriteCache.get(key)?.let { return it }
        val entry = project.sprites.firstOrNull { it.slotId == slotId } ?: return null
        val file = File(directoryFor(project.id), entry.fileName)
        if (!file.isFile) return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        spriteCache.put(key, bitmap)
        return bitmap
    }

    /** Bitmap lookup suitable for [com.rigstudio.app.render.PuppetPainter.paint]. */
    fun bitmapResolver(project: CharacterProject): (String) -> Bitmap? = { slotId ->
        spriteBitmap(project, slotId)
    }

    fun thumbnailBitmap(project: CharacterProject): Bitmap? {
        val name = project.thumbnailFileName ?: return null
        val file = File(directoryFor(project.id), name)
        if (!file.isFile) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    // --- writing -----------------------------------------------------------------------------

    /**
     * Persists a freshly imported character: manifest, original sheet, every extracted sprite and
     * a thumbnail. Returns the loaded character ready for the editor.
     */
    fun saveImported(
        project: CharacterProject,
        sheet: Bitmap?,
        sprites: Map<String, ExtractedSprite>,
        thumbnail: Bitmap?,
    ): LoadedCharacter {
        val dir = directoryFor(project.id)
        if (!dir.exists() && !dir.mkdirs()) {
            throw ProjectStoreException("Could not create a folder for this character.")
        }
        val spritesDir = spritesDir(project.id).apply { mkdirs() }

        sheet?.let { writePng(it, sheetFile(project.id)) }
        thumbnail?.let { writePng(it, thumbnailFile(project.id)) }

        val manifest = ArrayList<SpriteManifestEntry>(sprites.size)
        for ((slotId, sprite) in sprites) {
            if (sprite.isBlank()) continue
            val file = File(spritesDir, "$slotId.png")
            writePng(sprite.toBitmap(), file)
            manifest += SpriteManifestEntry(
                slotId = slotId,
                fileName = "sprites/$slotId.png",
                width = sprite.width,
                height = sprite.height,
                pivotX = sprite.pivot.x,
                pivotY = sprite.pivot.y,
                coverage = sprite.coverage,
                sourceRect = sprite.sourceRect,
                contentRect = sprite.contentRect,
            )
        }

        val withManifest = project.copy(
            sprites = manifest.sortedBy { it.slotId },
            thumbnailFileName = if (thumbnail != null) THUMBNAIL_NAME else null,
        )
        writeProject(withManifest)

        val rigs = RigBuilder.buildFromAssets(
            withManifest.spriteAssets(),
            ViewAvailability.from(withManifest.availableViews, withManifest.mirroredSideView),
        )
        return LoadedCharacter(withManifest, rigs, dir)
    }

    /** Writes `project.json` atomically. */
    fun writeProject(project: CharacterProject) {
        val dir = directoryFor(project.id).apply { mkdirs() }
        val target = File(dir, PROJECT_FILE)
        val temp = File(dir, "$PROJECT_FILE.tmp")
        temp.writeText(ProjectCodec.encode(project), Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            throw ProjectStoreException("Could not update this character's file.")
        }
        if (!temp.renameTo(target)) {
            throw ProjectStoreException("Could not save this character.")
        }
    }

    fun rename(projectId: String, newName: String, nowEpochMillis: Long): CharacterProject {
        val project = requireProject(projectId)
        val updated = project.renamed(newName.trim().ifBlank { project.name }, nowEpochMillis)
        writeProject(updated)
        return updated
    }

    fun touch(projectId: String, nowEpochMillis: Long): CharacterProject {
        val project = requireProject(projectId)
        val updated = project.touched(nowEpochMillis)
        writeProject(updated)
        return updated
    }

    /** Full copy of a character under a new id, including sheet, sprites and thumbnail. */
    fun duplicate(projectId: String, newId: String, newName: String, nowEpochMillis: Long): CharacterProject {
        val source = requireProject(projectId)
        val from = directoryFor(projectId)
        val to = directoryFor(newId)
        if (!to.exists() && !to.mkdirs()) {
            throw ProjectStoreException("Could not create the duplicate.")
        }
        from.copyRecursively(to, overwrite = true) { file, error ->
            throw ProjectStoreException("Could not copy ${file.name}: ${error.message}")
        }
        val copy = source.duplicated(newId, newName, nowEpochMillis)
        writeProject(copy)
        return copy
    }

    fun delete(projectId: String) {
        val dir = directoryFor(projectId)
        if (dir.exists()) dir.deleteRecursively()
        evictProject(projectId)
    }

    fun requireProject(projectId: String): CharacterProject =
        readProject(directoryFor(projectId))
            ?: throw ProjectStoreException("This character no longer exists.")

    private fun evictProject(projectId: String) {
        val prefix = "$projectId/"
        for (key in spriteCache.snapshot().keys) {
            if (key.startsWith(prefix)) spriteCache.remove(key)
        }
    }

    private fun writePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw ProjectStoreException("Could not write ${file.name}.")
            }
        }
    }

    private fun maxCacheBytes(): Int {
        val heap = Runtime.getRuntime().maxMemory()
        // Sprites are small and reused every frame; a quarter of the heap is generous without
        // risking an OOM during a 1080p export.
        return (heap / 4).toInt().coerceIn(4 * 1024 * 1024, 96 * 1024 * 1024)
    }

    /** Builds a small front-view thumbnail for the project list. */
    fun makeThumbnail(character: LoadedCharacter, size: Int = THUMBNAIL_SIZE): Bitmap? {
        val rig = character.rigFor(ViewKind.FRONT) ?: character.rigs.values.firstOrNull() ?: return null
        return ThumbnailRenderer.render(
            rig = rig,
            bitmaps = bitmapResolver(character.project),
            size = size,
        )
    }

    companion object {
        const val PROJECT_FILE = "project.json"
        const val SHEET_NAME = "sheet.png"
        const val THUMBNAIL_NAME = "thumb.png"
        const val THUMBNAIL_SIZE = 320

        fun newProjectId(nowEpochMillis: Long): String =
            "char_%d_%04d".format(nowEpochMillis, (0..9999).random())

        /** Scales a decoded sheet bitmap down for preview use only (never for extraction). */
        fun previewBitmap(bitmap: Bitmap, maxSize: Int = 1024): Bitmap = bitmap.downscaleTo(maxSize)
    }
}
