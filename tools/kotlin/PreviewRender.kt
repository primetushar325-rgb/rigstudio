/*
 * Offline preview renderer: runs a character sheet through the real engine - extraction, rig
 * build, forward kinematics, draw-list composition - and rasterises actual animation frames on
 * the JVM, with no Android SDK involved.
 *
 *   usage: PreviewRender <sheet.png> <outDir> [framesPerClip] [frameWidth]
 *
 * It exists because the unit tests prove each stage in isolation; this proves them joined up:
 * every playable clip in every available view must produce non-empty, framed frames from a real
 * sheet PNG. It writes one filmstrip PNG per (view, clip) plus a contact sheet, and exits non-zero
 * if any frame comes out empty - the offline equivalent of "the character disappeared on screen".
 *
 * The blitter is deliberately naive (nearest neighbour, straight alpha): it mirrors what
 * app/.../render/PuppetPainter.kt does with Canvas matrices, from the same PuppetDraw list, so a
 * frame rendered here is composed identically to a frame in the app or the MP4 export.
 */

import com.rigstudio.core.anim.AnimationLibrary
import com.rigstudio.core.extract.ArrayPixelSurface
import com.rigstudio.core.extract.SheetImageMeta
import com.rigstudio.core.extract.SheetProcessor
import com.rigstudio.core.geom.Affine
import com.rigstudio.core.model.ViewKind
import com.rigstudio.core.render.Framing
import com.rigstudio.core.render.PuppetComposer
import com.rigstudio.core.render.PuppetDraw
import com.rigstudio.core.rig.CharacterRig
import com.rigstudio.core.rig.RigBuilder
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.system.exitProcess

private const val BACKGROUND = 0xFF12161F.toInt()

private class FrameBuffer(val width: Int, val height: Int) {
    val pixels = IntArray(width * height) { BACKGROUND }

    fun clear() = pixels.fill(BACKGROUND)

    fun inkCount(): Int {
        var count = 0
        for (pixel in pixels) {
            if (pixel != BACKGROUND) count++
        }
        return count
    }
}

/** Nearest-neighbour inverse map of one sprite through `matrix` (view space -> pixels). */
private fun blit(buffer: FrameBuffer, draw: PuppetDraw, matrix: Affine, pixels: IntArray, sw: Int, sh: Int) {
    val rect = draw.restRect
    val corners = listOf(
        matrix.transform(rect.left, rect.top),
        matrix.transform(rect.right, rect.top),
        matrix.transform(rect.left, rect.bottom),
        matrix.transform(rect.right, rect.bottom),
    )
    val minX = max(0, (corners.minOf { it.x }).toInt())
    val maxX = min(buffer.width - 1, (corners.maxOf { it.x }).toInt() + 1)
    val minY = max(0, (corners.minOf { it.y }).toInt())
    val maxY = min(buffer.height - 1, (corners.maxOf { it.y }).toInt() + 1)
    if (minX > maxX || minY > maxY) return

    val det = matrix.a * matrix.d - matrix.c * matrix.b
    if (kotlin.math.abs(det) < 1e-6f) return
    val shade = draw.shade

    for (y in minY..maxY) {
        for (x in minX..maxX) {
            val px = x + 0.5f
            val py = y + 0.5f
            val dx = px - matrix.tx
            val dy = py - matrix.ty
            // Inverse of the 2x2 linear part: view-space point under this pixel.
            val vx = (matrix.d * dx - matrix.c * dy) / det
            val vy = (-matrix.b * dx + matrix.a * dy) / det
            if (vx < rect.left || vx >= rect.right || vy < rect.top || vy >= rect.bottom) continue
            val u = (vx - rect.left) / rect.width
            val v = (vy - rect.top) / rect.height
            val sx = min(sw - 1, max(0, (u * sw).toInt()))
            val sy = min(sh - 1, max(0, (v * sh).toInt()))
            val src = pixels[sy * sw + sx]
            val alpha = (src ushr 24) and 0xFF
            if (alpha < 10) continue
            val r = (((src shr 16) and 0xFF) * shade).toInt()
            val g = (((src shr 8) and 0xFF) * shade).toInt()
            val b = ((src and 0xFF) * shade).toInt()
            val index = y * buffer.width + x
            if (alpha >= 250) {
                buffer.pixels[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            } else {
                // Straight-alpha source-over against whatever is already in the frame.
                val dst = buffer.pixels[index]
                val sa = alpha / 255f
                val da = 1f - sa
                val or = (r * sa + ((dst shr 16) and 0xFF) * da).toInt()
                val og = (g * sa + ((dst shr 8) and 0xFF) * da).toInt()
                val ob = (b * sa + (dst and 0xFF) * da).toInt()
                buffer.pixels[index] = (0xFF shl 24) or (or shl 16) or (og shl 8) or ob
            }
        }
    }
}

private fun write(buffer: FrameBuffer, file: File) {
    val image = java.awt.image.BufferedImage(buffer.width, buffer.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, buffer.width, buffer.height, buffer.pixels, 0, buffer.width)
    ImageIO.write(image, "png", file)
}

fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: PreviewRender <sheet.png> <outDir> [framesPerClip] [frameWidth]")
        exitProcess(2)
    }
    val sheetFile = File(args[0])
    val outDir = File(args[1]).apply { mkdirs() }
    val framesPerClip = args.getOrNull(2)?.toInt() ?: 6
    val frameWidth = args.getOrNull(3)?.toInt() ?: 480
    val frameHeight = frameWidth * 9 / 16

    val sheet = ImageIO.read(sheetFile) ?: run {
        System.err.println("${sheetFile.path}: not a readable PNG")
        exitProcess(2)
    }
    val width = sheet.width
    val height = sheet.height
    val sheetPixels = IntArray(width * height)
    sheet.getRGB(0, 0, width, height, sheetPixels, 0, width)

    val processed = SheetProcessor().process(
        ArrayPixelSurface(width, height, sheetPixels, hasAlphaChannel = true),
        SheetImageMeta(width, height, true, "image/png"),
    )
    val built = RigBuilder.build(processed)
    if (!built.report.isRiggable) {
        System.err.println("sheet is not riggable: ${built.report.headlineMessage}")
        exitProcess(1)
    }
    println("sheet ${sheetFile.name}: views=${built.availableViews.joinToString(",") { it.name }} " +
        "sprites=${built.sprites.size}")

    val contactCells = ArrayList<Triple<String, FrameBuffer, Int>>()
    var failures = 0
    var strips = 0

    for (view in built.availableViews) {
        val rig: CharacterRig = built.rigs[view]!!
        val clips = AnimationLibrary.playableIn(view, hasProfileArtwork = true)
        for (clip in clips) {
            val camera = Framing.forPixels(rig, clip, frameWidth, frameHeight)
            val matrix = camera.transform
            val strip = FrameBuffer(frameWidth * framesPerClip, frameHeight)
            var minInk = Int.MAX_VALUE
            var maxInk = 0
            for (frame in 0 until framesPerClip) {
                val t = frame.toFloat() / framesPerClip
                val pose = clip.sample(t)
                val draws = PuppetComposer.compose(rig, pose)
                val buffer = FrameBuffer(frameWidth, frameHeight)
                for (draw in draws) {
                    val sprite = built.sprites[draw.slotId] ?: continue
                    blit(buffer, draw, matrix.multiply(draw.world), sprite.pixels, sprite.width, sprite.height)
                }
                val ink = buffer.inkCount()
                minInk = min(minInk, ink)
                maxInk = max(maxInk, ink)
                System.arraycopy(buffer.pixels, 0, strip.pixels, frame * frameWidth, frameWidth)
                for (row in 1 until frameHeight) {
                    System.arraycopy(
                        buffer.pixels, row * frameWidth,
                        strip.pixels, row * strip.width + frame * frameWidth,
                        frameWidth,
                    )
                }
                if (frame == framesPerClip / 2) {
                    contactCells += Triple("${view.name.take(4)}/${clip.id}", buffer, ink)
                }
                if (ink == 0) {
                    System.err.println("EMPTY FRAME: ${view.name}/${clip.id} t=$t")
                    failures++
                }
            }
            val file = File(outDir, "filmstrip_${view.name.lowercase()}_${clip.id}.png")
            write(strip, file)
            strips++
            println("  ${view.name.padEnd(10)} ${clip.id.padEnd(12)} frames=$framesPerClip " +
                "ink=${minInk}..${maxInk} -> ${file.name}")
        }
    }

    // Contact sheet: mid frame of every rendered (view, clip) pair, downscaled 2x by skipping.
    val columns = 3
    val cellWidth = frameWidth / 2
    val cellHeight = frameHeight / 2
    val rows = (contactCells.size + columns - 1) / columns
    val contact = FrameBuffer(columns * cellWidth, rows * cellHeight)
    contactCells.forEachIndexed { index, cell ->
        val cx = (index % columns) * cellWidth
        val cy = (index / columns) * cellHeight
        for (y in 0 until cellHeight) {
            for (x in 0 until cellWidth) {
                contact.pixels[(cy + y) * contact.width + cx + x] =
                    cell.second.pixels[(y * 2) * cell.second.width + (x * 2)]
            }
        }
    }
    val contactFile = File(outDir, "contact-sheet.png")
    write(contact, contactFile)
    println("contact sheet: ${contactCells.size} cells -> ${contactFile.path}")

    if (failures > 0) {
        System.err.println("PreviewRender FAILED: $failures empty frames")
        exitProcess(1)
    }
    println("PreviewRender OK: $strips filmstrips, ${contactCells.size} clips rendered, no empty frames")
}
