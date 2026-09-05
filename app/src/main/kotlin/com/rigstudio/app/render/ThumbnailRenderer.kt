package com.rigstudio.app.render

import android.graphics.Bitmap
import com.rigstudio.core.geom.Affine
import com.rigstudio.core.render.Framing
import com.rigstudio.core.render.PuppetComposer
import com.rigstudio.core.rig.CharacterRig
import com.rigstudio.core.rig.Pose

/**
 * Renders a character into a small bitmap: project thumbnails, the "Character Ready" card and
 * the sample-character preview.
 *
 * It uses the same [Framing] and [PuppetComposer] as the editor and the exporter, so a thumbnail
 * is a faithful miniature rather than a second, drifting renderer.
 */
object ThumbnailRenderer {

    private val painter = PuppetPainter()

    fun render(
        rig: CharacterRig,
        bitmaps: (String) -> Bitmap?,
        size: Int,
        pose: Pose = Pose.REST,
        background: StageBackground = StageBackground.Transparent,
        aspect: Float = 1f,
    ): Bitmap {
        val width = if (aspect >= 1f) size else Math.round(size * aspect).coerceAtLeast(1)
        val height = if (aspect >= 1f) Math.round(size / aspect).coerceAtLeast(1) else size
        val camera = Framing.fromBounds(rig.restBounds, aspect)
        val transform = Affine.scaling(height.toFloat()).multiply(camera.transform)
        val draws = PuppetComposer.compose(rig, pose, transform)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        painter.paintInto(bitmap, draws, bitmaps, background)
        return bitmap
    }
}
