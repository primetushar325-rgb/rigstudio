package com.rigstudio.app.art

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.rigstudio.core.geom.IntRect
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape
import com.rigstudio.core.template.CharacterSheetTemplate
import com.rigstudio.core.template.SheetSlot

/**
 * The bundled **sample character**: original placeholder artwork painted programmatically into
 * every slot of the standard template (spec §6).
 *
 * Why it exists:
 *  - it lets anybody exercise the full pipeline (import → rig → animate → export MP4) without
 *    drawing a single line first;
 *  - it is a living regression fixture — because it is generated from the same
 *    [CharacterSheetTemplate] the extractor reads, a coordinate change that breaks extraction
 *    breaks the sample character immediately and visibly;
 *  - it contains no third-party artwork, tracing or derived assets: every shape below is drawn
 *    from primitives in this file.
 *
 * The character is a simple teal-suited figure. Faces are drawn as separate eye and mouth sprites
 * (never baked into the head), profiles are drawn as true side shapes, and the back view has no
 * face — exactly the rules the real importer enforces.
 */
object SampleCharacterArt {

    // --- palette (original, flat colours; no gradients needed at sprite size) -----------------
    private val SKIN = Color.parseColor("#F0C39A")
    private val SKIN_SHADE = Color.parseColor("#D9A87F")
    private val HAIR = Color.parseColor("#33304F")
    private val SUIT = Color.parseColor("#3FBFAE")
    private val SUIT_DARK = Color.parseColor("#2A8C80")
    private val TROUSERS = Color.parseColor("#2E3A55")
    private val TROUSERS_DARK = Color.parseColor("#222C42")
    private val SHOE = Color.parseColor("#1B2233")
    private val SHOE_SOLE = Color.parseColor("#E8EDF5")
    private val EYE_WHITE = Color.parseColor("#FBFDFF")
    private val EYE_IRIS = Color.parseColor("#26314A")
    private val MOUTH = Color.parseColor("#7A3B45")
    private val MOUTH_DARK = Color.parseColor("#4E2229")
    private val TEETH = Color.parseColor("#FFFDF7")

    /** Paints a complete 2048² character sheet and returns it (caller owns the bitmap). */
    fun renderSheet(): Bitmap {
        val bitmap = Bitmap.createBitmap(
            CharacterSheetTemplate.SHEET_WIDTH,
            CharacterSheetTemplate.SHEET_HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        for (slot in CharacterSheetTemplate.SLOTS) {
            drawSlot(canvas, slot)
        }
        return bitmap
    }

    private fun drawSlot(canvas: Canvas, slot: SheetSlot) {
        // Everything is clipped to the slot, and inset, so no artwork can bleed into a neighbour
        // or touch an edge (touching an edge means the part might be cropped in a real drawing).
        val area = inset(slot.rect, slot)
        canvas.save()
        canvas.clipRect(
            slot.rect.x.toFloat(),
            slot.rect.y.toFloat(),
            slot.rect.right.toFloat(),
            slot.rect.bottom.toFloat(),
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Expression and mouth shape are only set on face slots, so this doubles as the
        // body-vs-face dispatch without a second (non smart-castable) check.
        val expression = slot.expression
        val mouthShape = slot.mouthShape
        when {
            expression != null -> drawEyes(canvas, area, expression, paint)
            mouthShape != null -> drawMouth(canvas, area, mouthShape, paint)
            else -> drawBody(canvas, slot, area, paint)
        }
        canvas.restore()
    }

    /** Artwork inset per slot kind: small parts need proportionally more breathing room. */
    private fun inset(rect: IntRect, slot: SheetSlot): RectF {
        val fraction = when {
            slot.isFace -> 0.10f
            slot.rect.width <= 128 -> 0.14f
            else -> 0.08f
        }
        val dx = rect.width * fraction
        val dy = rect.height * fraction
        return RectF(rect.x + dx, rect.y + dy, rect.right - dx, rect.bottom - dy)
    }

    // --- body parts ---------------------------------------------------------------------------

    private fun drawBody(canvas: Canvas, slot: SheetSlot, area: RectF, paint: Paint) {
        val profile = slot.view.name.startsWith("SIDE")
        val facingRight = slot.view.name == "SIDE_RIGHT"
        val back = slot.view.name == "BACK"
        when {
            slot.id.endsWith("head") -> drawHead(canvas, area, paint, profile, facingRight, back)
            slot.id.endsWith("torso") -> drawTorso(canvas, area, paint, profile, facingRight, back)
            slot.id.contains("upper_arm") -> drawLimb(canvas, area, paint, SUIT, SUIT_DARK, profile, facingRight)
            slot.id.contains("forearm") -> drawLimb(canvas, area, paint, SKIN, SKIN_SHADE, profile, facingRight)
            slot.id.contains("hand") -> drawHand(canvas, area, paint, profile)
            slot.id.contains("thigh") -> drawLimb(canvas, area, paint, TROUSERS, TROUSERS_DARK, profile, facingRight)
            slot.id.contains("shin") -> drawLimb(canvas, area, paint, TROUSERS, TROUSERS_DARK, profile, facingRight)
            slot.id.endsWith("foot_l") || slot.id.endsWith("foot_r") ||
                slot.id.endsWith("_foot") -> drawFoot(canvas, area, paint, profile, facingRight, back)
            else -> Unit // unknown slot: leave it empty rather than inventing artwork
        }
    }

    private fun drawHead(
        canvas: Canvas,
        area: RectF,
        paint: Paint,
        profile: Boolean,
        facingRight: Boolean,
        back: Boolean,
    ) {
        if (profile) {
            // Skull plus a nose on the facing side; no facial features (those are separate slots).
            val skull = RectF(area)
            val noseWidth = skull.width * 0.16f
            if (facingRight) skull.right -= noseWidth else skull.left += noseWidth
            paint.color = SKIN
            canvas.drawRoundRect(skull, skull.width * 0.42f, skull.height * 0.42f, paint)
            val nose = Path().apply {
                val tipX = if (facingRight) area.right else area.left
                val baseX = if (facingRight) skull.right else skull.left
                val topY = skull.top + skull.height * 0.42f
                val bottomY = skull.top + skull.height * 0.62f
                moveTo(baseX, topY)
                lineTo(tipX, (topY + bottomY) * 0.5f)
                lineTo(baseX, bottomY)
                close()
            }
            canvas.drawPath(nose, paint)
            // Jaw shade, then hair over the top and back of the skull.
            paint.color = SKIN_SHADE
            canvas.drawRoundRect(
                RectF(
                    skull.left,
                    skull.top + skull.height * 0.78f,
                    skull.right,
                    skull.bottom,
                ),
                skull.width * 0.3f,
                skull.height * 0.2f,
                paint,
            )
            paint.color = HAIR
            val hair = RectF(
                skull.left - skull.width * 0.02f,
                skull.top - skull.height * 0.02f,
                skull.right + skull.width * 0.02f,
                skull.top + skull.height * 0.38f,
            )
            canvas.drawRoundRect(hair, skull.width * 0.4f, skull.height * 0.34f, paint)
            return
        }

        paint.color = SKIN
        canvas.drawRoundRect(area, area.width * 0.40f, area.height * 0.40f, paint)
        // Ears (front and back views both have them; the back view keeps the hair covering more).
        paint.color = SKIN_SHADE
        val earHeight = area.height * 0.18f
        val earWidth = area.width * 0.07f
        val earTop = area.top + area.height * 0.44f
        canvas.drawRoundRect(RectF(area.left - earWidth * 0.4f, earTop, area.left + earWidth, earTop + earHeight), earWidth, earWidth, paint)
        canvas.drawRoundRect(RectF(area.right - earWidth, earTop, area.right + earWidth * 0.4f, earTop + earHeight), earWidth, earWidth, paint)
        // Hair: a cap over the top of the head, deeper at the back for the back view.
        paint.color = HAIR
        val hairBottom = area.top + area.height * if (back) 0.62f else 0.34f
        val hair = RectF(area.left, area.top, area.right, hairBottom)
        canvas.drawRoundRect(hair, area.width * 0.40f, area.height * 0.36f, paint)
        if (!back) {
            // Fringe: a soft curve across the forehead.
            val fringe = Path().apply {
                moveTo(area.left, area.top + area.height * 0.30f)
                quadTo(
                    area.centerX(),
                    area.top + area.height * 0.44f,
                    area.right,
                    area.top + area.height * 0.26f,
                )
                lineTo(area.right, area.top)
                lineTo(area.left, area.top)
                close()
            }
            canvas.drawPath(fringe, paint)
        }
        // Neck stub at the bottom, so the head meets the torso without a gap.
        paint.color = SKIN_SHADE
        val neckWidth = area.width * 0.30f
        canvas.drawRect(
            area.centerX() - neckWidth * 0.5f,
            area.bottom - area.height * 0.06f,
            area.centerX() + neckWidth * 0.5f,
            area.bottom,
            paint,
        )
    }

    private fun drawTorso(
        canvas: Canvas,
        area: RectF,
        paint: Paint,
        profile: Boolean,
        facingRight: Boolean,
        back: Boolean,
    ) {
        paint.color = SUIT
        val body = Path().apply {
            val shoulderInset = area.width * if (profile) 0.10f else 0.06f
            val hipInset = area.width * if (profile) 0.16f else 0.14f
            moveTo(area.left + shoulderInset, area.top)
            lineTo(area.right - shoulderInset, area.top)
            quadTo(area.right, area.top + area.height * 0.12f, area.right - hipInset * 0.4f, area.top + area.height * 0.45f)
            lineTo(area.right - hipInset, area.bottom)
            lineTo(area.left + hipInset, area.bottom)
            lineTo(area.left + hipInset * 0.4f, area.top + area.height * 0.45f)
            quadTo(area.left, area.top + area.height * 0.12f, area.left + shoulderInset, area.top)
            close()
        }
        canvas.drawPath(body, paint)

        // Shading down the side away from the camera gives the flat shape some volume.
        paint.color = SUIT_DARK
        val shadeWidth = area.width * 0.18f
        val shade = if (profile && facingRight) {
            RectF(area.left, area.top, area.left + shadeWidth, area.bottom)
        } else {
            RectF(area.right - shadeWidth, area.top, area.right, area.bottom)
        }
        canvas.save()
        canvas.clipPath(body)
        canvas.drawRect(shade, paint)
        canvas.restore()

        if (!profile && !back) {
            // Collar and a centre seam: front-only details.
            paint.color = SUIT_DARK
            val collar = Path().apply {
                moveTo(area.centerX() - area.width * 0.18f, area.top)
                lineTo(area.centerX(), area.top + area.height * 0.16f)
                lineTo(area.centerX() + area.width * 0.18f, area.top)
                lineTo(area.centerX() + area.width * 0.10f, area.top)
                lineTo(area.centerX(), area.top + area.height * 0.09f)
                lineTo(area.centerX() - area.width * 0.10f, area.top)
                close()
            }
            canvas.drawPath(collar, paint)
            paint.color = SUIT_DARK
            canvas.drawRect(
                area.centerX() - area.width * 0.012f,
                area.top + area.height * 0.14f,
                area.centerX() + area.width * 0.012f,
                area.bottom - area.height * 0.04f,
                paint,
            )
        }
        if (back) {
            // A back yoke reads as "seen from behind" without adding invented detail.
            paint.color = SUIT_DARK
            canvas.save()
            canvas.clipPath(body)
            canvas.drawRect(
                area.left,
                area.top,
                area.right,
                area.top + area.height * 0.18f,
                paint,
            )
            canvas.restore()
        }
    }

    /** Arms and legs: a capsule with a shade strip and a joint cap at the pivot end. */
    private fun drawLimb(
        canvas: Canvas,
        area: RectF,
        paint: Paint,
        colour: Int,
        shade: Int,
        profile: Boolean,
        facingRight: Boolean,
    ) {
        paint.color = colour
        val radius = minOf(area.width, area.height) * 0.5f
        canvas.drawRoundRect(area, radius, radius, paint)

        paint.color = shade
        canvas.save()
        canvas.clipRect(area)
        val shadeWidth = area.width * 0.26f
        val shadeRect = if (profile && facingRight) {
            RectF(area.left, area.top, area.left + shadeWidth, area.bottom)
        } else {
            RectF(area.right - shadeWidth, area.top, area.right, area.bottom)
        }
        canvas.drawRect(shadeRect, paint)
        canvas.restore()

        // Joint cap at the top: the pivot is at 8% down for limbs, so a lighter cap there makes
        // the joint read clearly in the sample and in the editor.
        paint.color = shade
        val capHeight = area.height * 0.10f
        canvas.drawRoundRect(
            RectF(area.left, area.top, area.right, area.top + capHeight),
            radius * 0.9f,
            radius * 0.9f,
            paint,
        )
    }

    private fun drawHand(canvas: Canvas, area: RectF, paint: Paint, profile: Boolean) {
        paint.color = SKIN
        val width = area.width * if (profile) 0.72f else 0.86f
        val hand = RectF(
            area.centerX() - width * 0.5f,
            area.top + area.height * 0.06f,
            area.centerX() + width * 0.5f,
            area.bottom,
        )
        canvas.drawRoundRect(hand, hand.width * 0.46f, hand.height * 0.40f, paint)
        // Thumb, on the side that faces the body.
        paint.color = SKIN_SHADE
        val thumb = RectF(
            if (profile) hand.left - hand.width * 0.10f else hand.left - hand.width * 0.16f,
            hand.top + hand.height * 0.22f,
            hand.left + hand.width * 0.30f,
            hand.top + hand.height * 0.62f,
        )
        canvas.drawRoundRect(thumb, thumb.width * 0.5f, thumb.height * 0.5f, paint)
    }

    private fun drawFoot(
        canvas: Canvas,
        area: RectF,
        paint: Paint,
        profile: Boolean,
        facingRight: Boolean,
        back: Boolean,
    ) {
        paint.color = SHOE
        if (profile) {
            // Toe points the way the character faces; the ankle rises at the back.
            val toeLeft = if (facingRight) area.right - area.width * 0.62f else area.left
            val toeRight = if (facingRight) area.right else area.left + area.width * 0.62f
            val toe = RectF(toeLeft, area.centerY(), toeRight, area.bottom)
            canvas.drawRoundRect(toe, toe.height * 0.45f, toe.height * 0.45f, paint)
            val ankleX = if (facingRight) area.left else area.right - area.width * 0.40f
            val ankle = RectF(
                ankleX,
                area.top,
                ankleX + area.width * 0.40f,
                area.bottom,
            )
            canvas.drawRoundRect(ankle, ankle.width * 0.34f, ankle.width * 0.34f, paint)
        } else {
            val shoe = RectF(area.left, area.top + area.height * 0.18f, area.right, area.bottom)
            canvas.drawRoundRect(shoe, shoe.height * 0.42f, shoe.height * 0.42f, paint)
            if (!back) {
                // Vamp opening, so a front shoe is not just a rounded block.
                paint.color = SHOE_SOLE
                canvas.drawRoundRect(
                    RectF(
                        shoe.left + shoe.width * 0.28f,
                        shoe.top - shoe.height * 0.06f,
                        shoe.right - shoe.width * 0.28f,
                        shoe.top + shoe.height * 0.34f,
                    ),
                    shoe.width * 0.2f,
                    shoe.height * 0.2f,
                    paint,
                )
                paint.color = SHOE
            }
        }
        // Sole: a light strip along the bottom of every shoe.
        paint.color = SHOE_SOLE
        val soleHeight = area.height * 0.14f
        canvas.drawRoundRect(
            RectF(area.left, area.bottom - soleHeight, area.right, area.bottom),
            soleHeight * 0.5f,
            soleHeight * 0.5f,
            paint,
        )
    }

    // --- face sprites -------------------------------------------------------------------------

    /**
     * Both eyes in one sprite: the face anchors place a single eye sprite across the head, so the
     * sprite itself carries the pair (which is what keeps expressions a sprite swap, never a
     * procedural deformation).
     */
    private fun drawEyes(canvas: Canvas, area: RectF, expression: Expression, paint: Paint) {
        val eyeWidth = area.width * 0.34f
        val eyeHeight = area.height * 0.62f
        val centres = listOf(area.left + area.width * 0.28f, area.left + area.width * 0.72f)
        val cy = area.centerY()

        when (expression) {
            Expression.NEUTRAL -> for (cx in centres) {
                paint.color = EYE_WHITE
                canvas.drawOval(RectF(cx - eyeWidth / 2, cy - eyeHeight / 2, cx + eyeWidth / 2, cy + eyeHeight / 2), paint)
                paint.color = EYE_IRIS
                val iris = eyeWidth * 0.44f
                canvas.drawCircle(cx, cy + eyeHeight * 0.04f, iris / 2, paint)
                paint.color = EYE_WHITE
                canvas.drawCircle(cx - iris * 0.16f, cy - iris * 0.10f, iris * 0.16f, paint)
            }

            Expression.CLOSED -> for (cx in centres) {
                paint.color = EYE_IRIS
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = eyeHeight * 0.14f
                paint.strokeCap = Paint.Cap.ROUND
                val lid = RectF(cx - eyeWidth / 2, cy - eyeHeight * 0.3f, cx + eyeWidth / 2, cy + eyeHeight * 0.5f)
                canvas.drawArc(lid, 20f, 140f, false, paint)
                paint.style = Paint.Style.FILL
            }

            Expression.HAPPY -> for (cx in centres) {
                paint.color = EYE_IRIS
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = eyeHeight * 0.16f
                paint.strokeCap = Paint.Cap.ROUND
                val lid = RectF(cx - eyeWidth / 2, cy - eyeHeight * 0.4f, cx + eyeWidth / 2, cy + eyeHeight * 0.4f)
                canvas.drawArc(lid, 200f, 140f, false, paint)
                paint.style = Paint.Style.FILL
            }

            Expression.SAD -> for (cx in centres) {
                paint.color = EYE_WHITE
                canvas.drawOval(RectF(cx - eyeWidth / 2, cy - eyeHeight * 0.3f, cx + eyeWidth / 2, cy + eyeHeight / 2), paint)
                paint.color = EYE_IRIS
                canvas.drawCircle(cx, cy + eyeHeight * 0.14f, eyeWidth * 0.20f, paint)
                // Drooping brow.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = eyeHeight * 0.13f
                paint.strokeCap = Paint.Cap.ROUND
                val inner = if (cx < area.centerX()) cx + eyeWidth * 0.5f else cx - eyeWidth * 0.5f
                val outer = if (cx < area.centerX()) cx - eyeWidth * 0.5f else cx + eyeWidth * 0.5f
                canvas.drawLine(outer, cy - eyeHeight * 0.52f, inner, cy - eyeHeight * 0.34f, paint)
                paint.style = Paint.Style.FILL
            }

            Expression.ANGRY -> for (cx in centres) {
                paint.color = EYE_WHITE
                canvas.drawOval(RectF(cx - eyeWidth / 2, cy - eyeHeight * 0.24f, cx + eyeWidth / 2, cy + eyeHeight / 2), paint)
                paint.color = EYE_IRIS
                canvas.drawCircle(cx, cy + eyeHeight * 0.16f, eyeWidth * 0.20f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = eyeHeight * 0.16f
                paint.strokeCap = Paint.Cap.ROUND
                val inner = if (cx < area.centerX()) cx + eyeWidth * 0.52f else cx - eyeWidth * 0.52f
                val outer = if (cx < area.centerX()) cx - eyeWidth * 0.52f else cx + eyeWidth * 0.52f
                canvas.drawLine(outer, cy - eyeHeight * 0.56f, inner, cy - eyeHeight * 0.22f, paint)
                paint.style = Paint.Style.FILL
            }
        }
    }

    private fun drawMouth(canvas: Canvas, area: RectF, shape: MouthShape, paint: Paint) {
        val cx = area.centerX()
        val cy = area.centerY()
        val w = area.width
        val h = area.height
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        when (shape) {
            MouthShape.CLOSED, MouthShape.I -> {
                paint.color = MOUTH_DARK
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = h * if (shape == MouthShape.I) 0.16f else 0.13f
                val half = w * if (shape == MouthShape.I) 0.26f else 0.24f
                canvas.drawLine(cx - half, cy, cx + half, cy, paint)
                paint.style = Paint.Style.FILL
            }

            MouthShape.NORMAL -> {
                paint.color = MOUTH
                canvas.drawRoundRect(
                    RectF(cx - w * 0.22f, cy - h * 0.10f, cx + w * 0.22f, cy + h * 0.14f),
                    h * 0.12f, h * 0.12f, paint,
                )
            }

            MouthShape.A -> openMouth(canvas, cx, cy, w * 0.30f, h * 0.44f, paint)
            MouthShape.E -> openMouth(canvas, cx, cy, w * 0.40f, h * 0.26f, paint)
            MouthShape.O -> openMouth(canvas, cx, cy, w * 0.26f, h * 0.40f, paint)
            MouthShape.U -> openMouth(canvas, cx, cy, w * 0.16f, h * 0.26f, paint)
            MouthShape.SURPRISED -> openMouth(canvas, cx, cy, w * 0.18f, h * 0.32f, paint)

            MouthShape.SMILE -> {
                paint.color = MOUTH_DARK
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = h * 0.14f
                canvas.drawArc(
                    RectF(cx - w * 0.30f, cy - h * 0.30f, cx + w * 0.30f, cy + h * 0.34f),
                    10f, 160f, false, paint,
                )
                paint.style = Paint.Style.FILL
            }

            MouthShape.SAD -> {
                paint.color = MOUTH_DARK
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = h * 0.14f
                canvas.drawArc(
                    RectF(cx - w * 0.28f, cy - h * 0.10f, cx + w * 0.28f, cy + h * 0.54f),
                    190f, 160f, false, paint,
                )
                paint.style = Paint.Style.FILL
            }

            MouthShape.ANGRY -> {
                paint.color = MOUTH_DARK
                val path = Path().apply {
                    moveTo(cx - w * 0.28f, cy - h * 0.06f)
                    lineTo(cx - w * 0.10f, cy + h * 0.14f)
                    lineTo(cx + w * 0.08f, cy - h * 0.10f)
                    lineTo(cx + w * 0.28f, cy + h * 0.10f)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = h * 0.15f
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
            }
        }
    }

    /** An open mouth: dark cavity plus a strip of teeth along the top. */
    private fun openMouth(canvas: Canvas, cx: Float, cy: Float, halfWidth: Float, halfHeight: Float, paint: Paint) {
        paint.color = MOUTH_DARK
        val cavity = RectF(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
        canvas.drawOval(cavity, paint)
        paint.color = TEETH
        canvas.save()
        canvas.clipRect(cavity)
        canvas.drawRect(
            RectF(cavity.left, cavity.top, cavity.right, cavity.top + cavity.height * 0.28f),
            paint,
        )
        canvas.restore()
        paint.color = MOUTH
        canvas.save()
        canvas.clipRect(cavity)
        canvas.drawOval(
            RectF(
                cavity.left + cavity.width * 0.22f,
                cavity.bottom - cavity.height * 0.34f,
                cavity.right - cavity.width * 0.22f,
                cavity.bottom + cavity.height * 0.1f,
            ),
            paint,
        )
        canvas.restore()
    }
}
