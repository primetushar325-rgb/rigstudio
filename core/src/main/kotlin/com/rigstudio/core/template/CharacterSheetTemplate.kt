package com.rigstudio.core.template

import com.rigstudio.core.geom.IntRect
import com.rigstudio.core.geom.Vec2
import com.rigstudio.core.model.BoneIds
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape
import com.rigstudio.core.model.SlotKind
import com.rigstudio.core.model.ViewKind

/**
 * One fixed slot of the RigStudio Character Sheet.
 *
 * A slot is *pure data*: where the artwork lives on the 2048x2048 sheet, where its joint
 * (pivot) sits inside that rectangle, which universal bone it feeds, and how it is layered.
 * Nothing here is derived from the pixels — that is precisely what makes rigging
 * deterministic, offline and instant (no AI, no segmentation, no user interaction).
 */
data class SheetSlot(
    /** Stable id, e.g. `front_upper_arm_l`. Never renamed once shipped. */
    val id: String,
    /** Human readable label drawn on the blank template. */
    val label: String,
    /** Region label drawn on the blank template ("FRONT BODY", "FACE", …). */
    val group: String,
    val view: ViewKind,
    val kind: SlotKind,
    /** Rectangle in sheet pixels (origin = top-left of the sheet). */
    val rect: IntRect,
    /** Joint position inside [rect], normalised 0..1 (x right, y down). */
    val pivot: Vec2,
    /** Universal bone this slot feeds; `null` for facial sprites. */
    val boneId: String?,
    /** Expression for `EYE` slots, mouth shape for `MOUTH` slots, else `null`. */
    val expression: Expression? = null,
    val mouthShape: MouthShape? = null,
    /**
     * Required slots must contain artwork for the sheet to be usable.
     * Only the front body core is mandatory — hands, feet, face, side and back are optional
     * and degrade gracefully.
     */
    val required: Boolean = false,
) {
    /** Joint position in absolute sheet pixels. */
    val pivotPixelX: Int get() = rect.x + Math.round(pivot.x * rect.width)
    val pivotPixelY: Int get() = rect.y + Math.round(pivot.y * rect.height)

    val isFace: Boolean get() = kind != SlotKind.BODY
}

/**
 * THE RigStudio Character Sheet Template.
 *
 * Default sheet: **2048 x 2048, transparent RGBA PNG**. Every coordinate below is fixed and
 * public so a user can draw artwork in any external painting app and hand RigStudio exactly
 * one PNG.
 *
 * Layout of the sheet (all rectangles verified non-overlapping by [selfCheck]):
 *
 * ```
 *  x:  64 ───────────────────── 1504   1536 ────────── 1984
 *  y:  64   FRONT BODY (14 slots)        FACE (16 slots: 5 eyes + 11 mouths)
 *     880   BACK VIEW (12 slots)         │
 *    1184   BACK FEET (2 slots, x 480+)  │
 *    1464   SIDE LEFT (8 slots)  │  SIDE RIGHT (8 slots)
 *    1736   side left hand/foot  │  side right hand/foot
 *    2000
 * ```
 *
 * ### Deviation from the V3 coordinate listing (documented on purpose)
 * The V3 spec lists `front_upper_arm_r` at `y = 272`, which overlaps `front_upper_arm_l`
 * (`y = 64 … 448`) in the same 192px column. Two overlapping slots cannot both be read
 * without one stealing the other's pixels, so the shipped template puts the right upper arm
 * at `y = 480` — the same row as its neighbour `front_forearm_r` (`y = 480`), which is what
 * the spec's own right-arm row implies. Every other coordinate matches the spec exactly.
 * [selfCheck] enforces the no-overlap invariant so this can never regress silently.
 */
object CharacterSheetTemplate {

    /**
     * Layout version. Bumped whenever a slot moves or is added, so a saved project can tell that
     * its sheet predates the current layout instead of silently extracting the wrong pixels.
     */
    const val VERSION = 1

    const val SHEET_WIDTH = 2048
    const val SHEET_HEIGHT = 2048

    /** Artwork safe area: slots never touch the sheet edge. */
    const val SAFE_MARGIN = 48

    /** Minimum spacing kept between neighbouring slots. */
    const val GUTTER = 16

    const val GROUP_FRONT = "FRONT BODY"
    const val GROUP_FACE_EYES = "FACE — EYES"
    const val GROUP_FACE_MOUTHS = "FACE — MOUTHS"
    const val GROUP_SIDE_LEFT = "SIDE VIEW — FACING LEFT"
    const val GROUP_SIDE_RIGHT = "SIDE VIEW — FACING RIGHT"
    const val GROUP_BACK = "BACK VIEW (OPTIONAL)"

    // ---------------------------------------------------------------------------------------
    // FRONT BODY — coordinates fixed by the V3 specification.
    // Convention: `_l` / `_r` are SCREEN left / right as you look at the sheet.
    // ---------------------------------------------------------------------------------------
    private val FRONT: List<SheetSlot> = listOf(
        SheetSlot(
            id = "front_head", label = "HEAD", group = GROUP_FRONT, view = ViewKind.FRONT,
            kind = SlotKind.BODY, rect = IntRect(64, 64, 384, 384), pivot = Vec2(0.50f, 0.90f),
            boneId = BoneIds.HEAD, required = true,
        ),
        SheetSlot(
            id = "front_torso", label = "TORSO", group = GROUP_FRONT, view = ViewKind.FRONT,
            kind = SlotKind.BODY, rect = IntRect(480, 64, 384, 640), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.TORSO, required = true,
        ),
        SheetSlot(
            id = "front_upper_arm_l", label = "UPPER ARM L", group = GROUP_FRONT,
            view = ViewKind.FRONT, kind = SlotKind.BODY,
            rect = IntRect(896, 64, 192, 384), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.UPPER_ARM_L, required = true,
        ),
        SheetSlot(
            id = "front_forearm_l", label = "FOREARM L", group = GROUP_FRONT,
            view = ViewKind.FRONT, kind = SlotKind.BODY,
            rect = IntRect(1104, 64, 192, 384), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.FOREARM_L, required = true,
        ),
        SheetSlot(
            id = "front_hand_l", label = "HAND L", group = GROUP_FRONT, view = ViewKind.FRONT,
            kind = SlotKind.BODY, rect = IntRect(1312, 64, 192, 192), pivot = Vec2(0.50f, 0.10f),
            boneId = BoneIds.HAND_L,
        ),
        // See the class doc: y = 480 (spec listing said 272, which overlaps the left arm).
        SheetSlot(
            id = "front_upper_arm_r", label = "UPPER ARM R", group = GROUP_FRONT,
            view = ViewKind.FRONT, kind = SlotKind.BODY,
            rect = IntRect(896, 480, 192, 384), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.UPPER_ARM_R, required = true,
        ),
        SheetSlot(
            id = "front_forearm_r", label = "FOREARM R", group = GROUP_FRONT,
            view = ViewKind.FRONT, kind = SlotKind.BODY,
            rect = IntRect(1104, 480, 192, 384), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.FOREARM_R, required = true,
        ),
        SheetSlot(
            id = "front_hand_r", label = "HAND R", group = GROUP_FRONT, view = ViewKind.FRONT,
            kind = SlotKind.BODY, rect = IntRect(1312, 272, 192, 192), pivot = Vec2(0.50f, 0.10f),
            boneId = BoneIds.HAND_R,
        ),
        SheetSlot(
            id = "front_thigh_l", label = "THIGH L", group = GROUP_FRONT, view = ViewKind.FRONT,
            kind = SlotKind.BODY, rect = IntRect(64, 512, 192, 448), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.THIGH_L, required = true,
        ),
        SheetSlot(
            id = "front_shin_l", label = "SHIN L", group = GROUP_FRONT, view = ViewKind.FRONT,
            kind = SlotKind.BODY, rect = IntRect(272, 512, 192, 448), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.SHIN_L, required = true,
        ),
        SheetSlot(
            id = "front_foot_l", label = "FOOT L", group = GROUP_FRONT, view = ViewKind.FRONT,
            kind = SlotKind.BODY, rect = IntRect(480, 752, 256, 192), pivot = Vec2(0.85f, 0.50f),
            boneId = BoneIds.FOOT_L,
        ),
        SheetSlot(
            id = "front_thigh_r", label = "THIGH R", group = GROUP_FRONT, view = ViewKind.FRONT,
            kind = SlotKind.BODY, rect = IntRect(64, 992, 192, 448), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.THIGH_R, required = true,
        ),
        SheetSlot(
            id = "front_shin_r", label = "SHIN R", group = GROUP_FRONT, view = ViewKind.FRONT,
            kind = SlotKind.BODY, rect = IntRect(272, 992, 192, 448), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.SHIN_R, required = true,
        ),
        SheetSlot(
            id = "front_foot_r", label = "FOOT R", group = GROUP_FRONT, view = ViewKind.FRONT,
            kind = SlotKind.BODY, rect = IntRect(480, 960, 256, 192), pivot = Vec2(0.85f, 0.50f),
            boneId = BoneIds.FOOT_R,
        ),
    )

    // ---------------------------------------------------------------------------------------
    // FACE — right hand column, 2 columns x 8 rows of 208 x 144 cells.
    // Each eye slot holds BOTH eyes as one sprite; each mouth slot holds one mouth shape.
    // Face sprites are centre-anchored onto the head, so their pivot is (0.5, 0.5).
    // ---------------------------------------------------------------------------------------
    private const val FACE_COL_A = 1536
    private const val FACE_COL_B = 1776
    private const val FACE_W = 208

    // 140 px tall with a 176 px pitch leaves a 36 px band between rows: enough for a legible label
    // that still clears both outlines, so no guide ink ever has to sit inside a face slot.
    private const val FACE_H = 140
    private val FACE_ROWS = intArrayOf(64, 240, 416, 592, 768, 944, 1120, 1296)

    private fun eye(id: String, label: String, col: Int, row: Int, expression: Expression) =
        SheetSlot(
            id = id, label = label, group = GROUP_FACE_EYES, view = ViewKind.FRONT,
            kind = SlotKind.EYE, rect = IntRect(col, FACE_ROWS[row], FACE_W, FACE_H),
            pivot = Vec2(0.5f, 0.5f), boneId = null, expression = expression,
        )

    private fun mouth(id: String, label: String, col: Int, row: Int, shape: MouthShape) =
        SheetSlot(
            id = id, label = label, group = GROUP_FACE_MOUTHS, view = ViewKind.FRONT,
            kind = SlotKind.MOUTH, rect = IntRect(col, FACE_ROWS[row], FACE_W, FACE_H),
            pivot = Vec2(0.5f, 0.5f), boneId = null, mouthShape = shape,
        )

    private val FACE: List<SheetSlot> = listOf(
        eye("eye_open", "EYES OPEN", FACE_COL_A, 0, Expression.NEUTRAL),
        eye("eye_closed", "EYES CLOSED", FACE_COL_B, 0, Expression.CLOSED),
        eye("eye_happy", "EYES HAPPY", FACE_COL_A, 1, Expression.HAPPY),
        eye("eye_sad", "EYES SAD", FACE_COL_B, 1, Expression.SAD),
        eye("eye_angry", "EYES ANGRY", FACE_COL_A, 2, Expression.ANGRY),
        mouth("mouth_normal", "MOUTH NORMAL", FACE_COL_B, 2, MouthShape.NORMAL),
        mouth("mouth_closed", "MOUTH CLOSED", FACE_COL_A, 3, MouthShape.CLOSED),
        mouth("mouth_A", "MOUTH A", FACE_COL_B, 3, MouthShape.A),
        mouth("mouth_E", "MOUTH E", FACE_COL_A, 4, MouthShape.E),
        mouth("mouth_I", "MOUTH I", FACE_COL_B, 4, MouthShape.I),
        mouth("mouth_O", "MOUTH O", FACE_COL_A, 5, MouthShape.O),
        mouth("mouth_U", "MOUTH U", FACE_COL_B, 5, MouthShape.U),
        mouth("mouth_smile", "MOUTH SMILE", FACE_COL_A, 6, MouthShape.SMILE),
        mouth("mouth_sad", "MOUTH SAD", FACE_COL_B, 6, MouthShape.SAD),
        mouth("mouth_surprised", "MOUTH SURPRISED", FACE_COL_A, 7, MouthShape.SURPRISED),
        mouth("mouth_angry", "MOUTH ANGRY", FACE_COL_B, 7, MouthShape.ANGRY),
    )

    // ---------------------------------------------------------------------------------------
    // SIDE VIEWS — bottom band. A profile character is drawn once per facing direction.
    // Side sheets carry a SINGLE arm/leg each; the rig binds that sprite to both the near and
    // the far bone (see ViewAssembly.sideLeft / sideRight) so a profile walk still has
    // opposite-phase limbs. RigStudio never fakes a profile out of front artwork.
    // ---------------------------------------------------------------------------------------
    private const val SIDE_ROW_A = 1464
    private const val SIDE_ROW_B = 1736

    private fun sideSlots(prefix: String, facing: ViewKind, group: String, baseX: Int): List<SheetSlot> {
        val c0 = baseX
        val c1 = baseX + 240
        val c2 = baseX + 480
        val c3 = baseX + 720
        return listOf(
            SheetSlot(
                id = "${prefix}_head", label = "SIDE HEAD", group = group, view = facing,
                kind = SlotKind.BODY, rect = IntRect(c0, SIDE_ROW_A, 224, 224),
                pivot = Vec2(0.50f, 0.90f), boneId = BoneIds.HEAD,
            ),
            SheetSlot(
                id = "${prefix}_torso", label = "SIDE TORSO", group = group, view = facing,
                kind = SlotKind.BODY, rect = IntRect(c1, SIDE_ROW_A, 208, 264),
                pivot = Vec2(0.50f, 0.08f), boneId = BoneIds.TORSO,
            ),
            SheetSlot(
                id = "${prefix}_thigh", label = "SIDE THIGH", group = group, view = facing,
                kind = SlotKind.BODY, rect = IntRect(c2, SIDE_ROW_A, 104, 264),
                pivot = Vec2(0.50f, 0.08f), boneId = BoneIds.THIGH_R,
            ),
            SheetSlot(
                id = "${prefix}_shin", label = "SIDE SHIN", group = group, view = facing,
                kind = SlotKind.BODY, rect = IntRect(c2 + 112, SIDE_ROW_A, 104, 264),
                pivot = Vec2(0.50f, 0.08f), boneId = BoneIds.SHIN_R,
            ),
            SheetSlot(
                id = "${prefix}_upper_arm", label = "SIDE UPPER ARM", group = group, view = facing,
                kind = SlotKind.BODY, rect = IntRect(c3, SIDE_ROW_A, 104, 264),
                pivot = Vec2(0.50f, 0.08f), boneId = BoneIds.UPPER_ARM_R,
            ),
            SheetSlot(
                id = "${prefix}_forearm", label = "SIDE FOREARM", group = group, view = facing,
                kind = SlotKind.BODY, rect = IntRect(c3 + 112, SIDE_ROW_A, 104, 264),
                pivot = Vec2(0.50f, 0.08f), boneId = BoneIds.FOREARM_R,
            ),
            SheetSlot(
                id = "${prefix}_hand", label = "SIDE HAND", group = group, view = facing,
                kind = SlotKind.BODY, rect = IntRect(c0, SIDE_ROW_B, 112, 112),
                pivot = Vec2(0.50f, 0.10f), boneId = BoneIds.HAND_R,
            ),
            SheetSlot(
                id = "${prefix}_foot", label = "SIDE FOOT", group = group, view = facing,
                kind = SlotKind.BODY, rect = IntRect(c0 + 136, SIDE_ROW_B, 176, 112),
                pivot = Vec2(0.85f, 0.50f), boneId = BoneIds.FOOT_R,
            ),
        )
    }

    private val SIDE_LEFT: List<SheetSlot> = sideSlots("side_left", ViewKind.SIDE_LEFT, GROUP_SIDE_LEFT, 64)
    private val SIDE_RIGHT: List<SheetSlot> = sideSlots("side_right", ViewKind.SIDE_RIGHT, GROUP_SIDE_RIGHT, 1056)

    // ---------------------------------------------------------------------------------------
    // BACK VIEW — optional. Authored at roughly half the front scale; the assembler scales
    // every part to the same assembled proportions, so the smaller cells cost resolution only.
    // ---------------------------------------------------------------------------------------
    private val BACK: List<SheetSlot> = listOf(
        SheetSlot(
            id = "back_torso", label = "BACK TORSO", group = GROUP_BACK, view = ViewKind.BACK,
            kind = SlotKind.BODY, rect = IntRect(752, 880, 176, 288), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.TORSO,
        ),
        SheetSlot(
            id = "back_head", label = "BACK HEAD", group = GROUP_BACK, view = ViewKind.BACK,
            kind = SlotKind.BODY, rect = IntRect(944, 880, 176, 176), pivot = Vec2(0.50f, 0.90f),
            boneId = BoneIds.HEAD,
        ),
        SheetSlot(
            id = "back_thigh_l", label = "BACK THIGH L", group = GROUP_BACK, view = ViewKind.BACK,
            kind = SlotKind.BODY, rect = IntRect(1136, 880, 88, 264), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.THIGH_L,
        ),
        SheetSlot(
            id = "back_shin_l", label = "BACK SHIN L", group = GROUP_BACK, view = ViewKind.BACK,
            kind = SlotKind.BODY, rect = IntRect(1240, 880, 88, 264), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.SHIN_L,
        ),
        SheetSlot(
            id = "back_thigh_r", label = "BACK THIGH R", group = GROUP_BACK, view = ViewKind.BACK,
            kind = SlotKind.BODY, rect = IntRect(1344, 880, 88, 264), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.THIGH_R,
        ),
        SheetSlot(
            id = "back_shin_r", label = "BACK SHIN R", group = GROUP_BACK, view = ViewKind.BACK,
            kind = SlotKind.BODY, rect = IntRect(752, 1184, 88, 256), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.SHIN_R,
        ),
        SheetSlot(
            id = "back_upper_arm_l", label = "BACK UPPER ARM L", group = GROUP_BACK,
            view = ViewKind.BACK, kind = SlotKind.BODY,
            rect = IntRect(856, 1184, 88, 208), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.UPPER_ARM_L,
        ),
        SheetSlot(
            id = "back_forearm_l", label = "BACK FOREARM L", group = GROUP_BACK,
            view = ViewKind.BACK, kind = SlotKind.BODY,
            rect = IntRect(960, 1184, 88, 208), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.FOREARM_L,
        ),
        SheetSlot(
            id = "back_hand_l", label = "BACK HAND L", group = GROUP_BACK, view = ViewKind.BACK,
            kind = SlotKind.BODY, rect = IntRect(1064, 1184, 88, 88), pivot = Vec2(0.50f, 0.10f),
            boneId = BoneIds.HAND_L,
        ),
        SheetSlot(
            id = "back_upper_arm_r", label = "BACK UPPER ARM R", group = GROUP_BACK,
            view = ViewKind.BACK, kind = SlotKind.BODY,
            rect = IntRect(1168, 1184, 88, 208), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.UPPER_ARM_R,
        ),
        SheetSlot(
            id = "back_forearm_r", label = "BACK FOREARM R", group = GROUP_BACK,
            view = ViewKind.BACK, kind = SlotKind.BODY,
            rect = IntRect(1272, 1184, 88, 208), pivot = Vec2(0.50f, 0.08f),
            boneId = BoneIds.FOREARM_R,
        ),
        SheetSlot(
            id = "back_hand_r", label = "BACK HAND R", group = GROUP_BACK, view = ViewKind.BACK,
            kind = SlotKind.BODY, rect = IntRect(1376, 1184, 88, 88), pivot = Vec2(0.50f, 0.10f),
            boneId = BoneIds.HAND_R,
        ),
        SheetSlot(
            id = "back_foot_l", label = "BACK FOOT L", group = GROUP_BACK, view = ViewKind.BACK,
            kind = SlotKind.BODY, rect = IntRect(480, 1184, 120, 88), pivot = Vec2(0.85f, 0.50f),
            boneId = BoneIds.FOOT_L,
        ),
        SheetSlot(
            id = "back_foot_r", label = "BACK FOOT R", group = GROUP_BACK, view = ViewKind.BACK,
            kind = SlotKind.BODY, rect = IntRect(616, 1184, 120, 88), pivot = Vec2(0.85f, 0.50f),
            boneId = BoneIds.FOOT_R,
        ),
    )

    /**
     * Free (unassigned) areas of the sheet. The template renderer prints the drawing
     * instructions into them; importing ignores them entirely.
     */
    val NOTES_AREAS: List<IntRect> = listOf(
        IntRect(1312, 480, 176, 368),  // right of the right forearm, below the right hand
        IntRect(480, 1288, 256, 152),  // below the back feet
    )

    /** All 60 slots, grouped in drawing order. */
    val SLOTS: List<SheetSlot> = FRONT + FACE + SIDE_LEFT + SIDE_RIGHT + BACK

    private val BY_ID: Map<String, SheetSlot> = SLOTS.associateBy { it.id }

    fun slot(id: String): SheetSlot? = BY_ID[id]

    fun requireSlot(id: String): SheetSlot =
        BY_ID[id] ?: error("Unknown character sheet slot '$id'")

    val bodySlots: List<SheetSlot> get() = SLOTS.filter { it.kind == SlotKind.BODY }
    val eyeSlots: List<SheetSlot> get() = SLOTS.filter { it.kind == SlotKind.EYE }
    val mouthSlots: List<SheetSlot> get() = SLOTS.filter { it.kind == SlotKind.MOUTH }
    val requiredSlots: List<SheetSlot> get() = SLOTS.filter { it.required }

    fun slotsFor(view: ViewKind): List<SheetSlot> = SLOTS.filter { it.view == view }

    /** The single slot that supplies [boneId] in [view] (side views share one sprite per limb). */
    fun slotForBone(view: ViewKind, boneId: String): SheetSlot? =
        SLOTS.firstOrNull { it.view == view && it.boneId == boneId }

    val sheetRect: IntRect get() = IntRect(0, 0, SHEET_WIDTH, SHEET_HEIGHT)

    /**
     * Verifies the template against itself. Returns a list of human readable problems;
     * an empty list means the sheet layout is valid. Unit tests assert this is empty, so a
     * bad coordinate edit fails the build instead of silently corrupting somebody's character.
     */
    fun selfCheck(): List<String> {
        val problems = mutableListOf<String>()

        if (SLOTS.map { it.id }.distinct().size != SLOTS.size) {
            problems += "Duplicate slot ids present"
        }

        for (slot in SLOTS) {
            if (slot.rect.isEmpty()) problems += "${slot.id}: empty rect"
            if (slot.rect.x < 0 || slot.rect.y < 0 ||
                slot.rect.right > SHEET_WIDTH || slot.rect.bottom > SHEET_HEIGHT
            ) {
                problems += "${slot.id}: rect ${slot.rect} outside the ${SHEET_WIDTH}x$SHEET_HEIGHT sheet"
            }
            if (slot.pivot.x !in 0f..1f || slot.pivot.y !in 0f..1f) {
                problems += "${slot.id}: pivot ${slot.pivot} outside 0..1"
            }
            val bone = slot.boneId
            if (slot.kind == SlotKind.BODY) {
                if (bone == null || !BoneIds.isKnown(bone)) {
                    problems += "${slot.id}: body slot without a valid universal bone id"
                }
            } else if (bone != null) {
                problems += "${slot.id}: face slot must not carry a bone id"
            }
            if (slot.kind == SlotKind.EYE && slot.expression == null) {
                problems += "${slot.id}: eye slot without an expression"
            }
            if (slot.kind == SlotKind.MOUTH && slot.mouthShape == null) {
                problems += "${slot.id}: mouth slot without a mouth shape"
            }
        }

        // Slots must never overlap: an overlap means two slots would read the same pixels.
        for (i in SLOTS.indices) {
            for (j in i + 1 until SLOTS.size) {
                val a = SLOTS[i]
                val b = SLOTS[j]
                if (a.rect.intersects(b.rect)) {
                    problems += "Slots overlap: ${a.id} ${a.rect} vs ${b.id} ${b.rect}"
                }
            }
        }

        // Every expression and mouth shape must be reachable from exactly one slot.
        for (expression in Expression.entries) {
            val count = eyeSlots.count { it.expression == expression }
            if (count != 1) problems += "Expression $expression has $count eye slots (expected 1)"
        }
        for (shape in MouthShape.entries) {
            val count = mouthSlots.count { it.mouthShape == shape }
            if (count != 1) problems += "Mouth shape $shape has $count slots (expected 1)"
        }

        // Every bone must be supplied by the front view (front is the mandatory view).
        for (boneId in BoneIds.ALL) {
            if (slotForBone(ViewKind.FRONT, boneId) == null) {
                problems += "Front view has no slot for bone '$boneId'"
            }
        }

        // Side views use a single sprite per limb pair; both views must be complete or absent.
        for (view in listOf(ViewKind.SIDE_LEFT, ViewKind.SIDE_RIGHT)) {
            val ids = slotsFor(view).map { it.id }
            if (ids.size != 8) problems += "$view must declare exactly 8 slots (found ${ids.size})"
        }

        // Instruction areas must not sit on top of artwork slots.
        for (area in NOTES_AREAS) {
            for (slot in SLOTS) {
                if (area.intersects(slot.rect)) {
                    problems += "Notes area $area overlaps slot ${slot.id}"
                }
            }
        }

        return problems
    }
}
