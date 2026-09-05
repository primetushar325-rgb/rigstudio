package com.rigstudio.core.tests

import com.rigstudio.core.geom.IntRect
import com.rigstudio.core.harness.Assert
import com.rigstudio.core.harness.TestCase
import com.rigstudio.core.model.BoneIds
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape
import com.rigstudio.core.model.SlotKind
import com.rigstudio.core.model.ViewKind
import com.rigstudio.core.template.CharacterSheetTemplate

/**
 * Template invariants (spec §4–§7, §16).
 *
 * The sheet layout is the contract with every user's artwork, so these tests pin the exact
 * coordinates and prove the table can never silently become ambiguous.
 */
object TemplateTests {

    private val template = CharacterSheetTemplate

    /** The V3 coordinate listing, transcribed so any drift is caught. */
    private val SPEC_FRONT_RECTS = mapOf(
        "front_head" to IntRect(64, 64, 384, 384),
        "front_torso" to IntRect(480, 64, 384, 640),
        "front_upper_arm_l" to IntRect(896, 64, 192, 384),
        "front_forearm_l" to IntRect(1104, 64, 192, 384),
        "front_hand_l" to IntRect(1312, 64, 192, 192),
        // Documented deviation: the spec listing says y=272, which overlaps front_upper_arm_l
        // (y=64..448) in the same column. Two overlapping slots would read each other's pixels,
        // so the shipped template uses the spec's own right-arm row (y=480, like forearm_r).
        "front_upper_arm_r" to IntRect(896, 480, 192, 384),
        "front_forearm_r" to IntRect(1104, 480, 192, 384),
        "front_hand_r" to IntRect(1312, 272, 192, 192),
        "front_thigh_l" to IntRect(64, 512, 192, 448),
        "front_shin_l" to IntRect(272, 512, 192, 448),
        "front_foot_l" to IntRect(480, 752, 256, 192),
        "front_thigh_r" to IntRect(64, 992, 192, 448),
        "front_shin_r" to IntRect(272, 992, 192, 448),
        "front_foot_r" to IntRect(480, 960, 256, 192),
    )

    val cases: List<TestCase> = listOf(
        TestCase("template passes its own self check") {
            val problems = template.selfCheck()
            Assert.equals(emptyList(), problems, "template self check")
        },
        TestCase("sheet is 2048x2048 with 60 slots") {
            Assert.equals(2048, template.SHEET_WIDTH)
            Assert.equals(2048, template.SHEET_HEIGHT)
            Assert.equals(60, template.SLOTS.size, "slot count")
        },
        TestCase("slot ids are unique") {
            Assert.equals(template.SLOTS.size, template.SLOTS.map { it.id }.distinct().size)
        },
        TestCase("front body rects match the specification") {
            for ((id, expected) in SPEC_FRONT_RECTS) {
                val slot = template.requireSlot(id)
                Assert.equals(expected, slot.rect, "$id rect")
            }
        },
        TestCase("front pivots match the specification") {
            Assert.close(0.50f, template.requireSlot("front_head").pivot.x)
            Assert.close(0.90f, template.requireSlot("front_head").pivot.y, message = "head pivot is the neck")
            Assert.close(0.08f, template.requireSlot("front_torso").pivot.y, message = "torso pivot is the chest")
            Assert.close(0.08f, template.requireSlot("front_upper_arm_l").pivot.y, message = "shoulder")
            Assert.close(0.08f, template.requireSlot("front_thigh_l").pivot.y, message = "hip")
            Assert.close(0.10f, template.requireSlot("front_hand_l").pivot.y, message = "wrist")
            Assert.close(0.85f, template.requireSlot("front_foot_l").pivot.x, message = "ankle")
            Assert.close(0.50f, template.requireSlot("front_foot_l").pivot.y)
        },
        TestCase("all fourteen universal bone ids exist in the front view") {
            for (boneId in BoneIds.ALL) {
                val slot = template.slotForBone(ViewKind.FRONT, boneId)
                Assert.that(slot != null) { "front view is missing a slot for bone '$boneId'" }
                Assert.equals(ViewKind.FRONT, slot!!.view)
                Assert.equals(SlotKind.BODY, slot.kind)
            }
            Assert.equals(14, BoneIds.ALL.size, "universal bone count")
        },
        TestCase("bone hierarchy is complete and acyclic") {
            Assert.equals(14, BoneIds.PARENTS.size)
            Assert.equals(null, BoneIds.PARENTS[BoneIds.TORSO], "torso is the top of the chain")
            Assert.equals(BoneIds.TORSO, BoneIds.PARENTS[BoneIds.HEAD])
            Assert.equals(BoneIds.TORSO, BoneIds.PARENTS[BoneIds.UPPER_ARM_L])
            Assert.equals(BoneIds.UPPER_ARM_L, BoneIds.PARENTS[BoneIds.FOREARM_L])
            Assert.equals(BoneIds.FOREARM_L, BoneIds.PARENTS[BoneIds.HAND_L])
            Assert.equals(BoneIds.TORSO, BoneIds.PARENTS[BoneIds.THIGH_R])
            Assert.equals(BoneIds.THIGH_R, BoneIds.PARENTS[BoneIds.SHIN_R])
            Assert.equals(BoneIds.SHIN_R, BoneIds.PARENTS[BoneIds.FOOT_R])

            // Walking the parent chain must terminate for every bone (no cycles).
            for (boneId in BoneIds.ALL) {
                var current: String? = boneId
                var steps = 0
                while (current != null) {
                    current = BoneIds.PARENTS[current]
                    steps++
                    Assert.that(steps <= BoneIds.ALL.size) { "parent chain cycle at '$boneId'" }
                }
            }
        },
        TestCase("mirror pairs map every limb both ways") {
            for (boneId in BoneIds.ALL) {
                val mirrored = BoneIds.mirrorOf(boneId)
                Assert.that(BoneIds.isKnown(mirrored)) { "mirror of '$boneId' is unknown" }
                Assert.equals(boneId, BoneIds.mirrorOf(mirrored), "mirroring twice must be identity")
            }
            Assert.equals(BoneIds.UPPER_ARM_R, BoneIds.mirrorOf(BoneIds.UPPER_ARM_L))
            Assert.equals(BoneIds.FOOT_L, BoneIds.mirrorOf(BoneIds.FOOT_R))
            Assert.equals(BoneIds.HEAD, BoneIds.mirrorOf(BoneIds.HEAD), "head has no mirror partner")
        },
        TestCase("face slots cover every expression and mouth shape exactly once") {
            Assert.equals(5, template.eyeSlots.size)
            Assert.equals(11, template.mouthSlots.size)
            for (expression in Expression.entries) {
                val slots = template.eyeSlots.filter { it.expression == expression }
                Assert.equals(1, slots.size, "eye slots for $expression")
                Assert.equals(SlotKind.EYE, slots[0].kind)
                Assert.that(slots[0].id.startsWith("eye_")) { "eye slot id ${slots[0].id}" }
            }
            for (shape in MouthShape.entries) {
                val slots = template.mouthSlots.filter { it.mouthShape == shape }
                Assert.equals(1, slots.size, "mouth slots for $shape")
                Assert.equals(shape.slotId, slots[0].id, "mouth slot id must match the shape")
            }
        },
        TestCase("face slots never carry a bone id") {
            for (slot in template.eyeSlots + template.mouthSlots) {
                Assert.equals(null, slot.boneId, "${slot.id} must not bind a bone")
                Assert.equals(ViewKind.FRONT, slot.view)
            }
        },
        TestCase("side views declare exactly the specified slot ids") {
            val expected = listOf("head", "torso", "upper_arm", "forearm", "hand", "thigh", "shin", "foot")
            for (prefix in listOf("side_left", "side_right")) {
                val ids = template.SLOTS.filter { it.id.startsWith("${prefix}_") }.map { it.id }
                Assert.equals(expected.map { "${prefix}_$it" }.sorted(), ids.sorted(), "$prefix slots")
            }
            Assert.equals(ViewKind.SIDE_LEFT, template.requireSlot("side_left_head").view)
            Assert.equals(ViewKind.SIDE_RIGHT, template.requireSlot("side_right_foot").view)
        },
        TestCase("back view declares exactly the specified slot ids") {
            val expected = listOf(
                "back_head", "back_torso",
                "back_upper_arm_l", "back_forearm_l", "back_hand_l",
                "back_upper_arm_r", "back_forearm_r", "back_hand_r",
                "back_thigh_l", "back_shin_l", "back_foot_l",
                "back_thigh_r", "back_shin_r", "back_foot_r",
            )
            val ids = template.slotsFor(ViewKind.BACK).map { it.id }
            Assert.equals(expected.sorted(), ids.sorted(), "back view slots")
        },
        TestCase("only front body core parts are mandatory") {
            val required = template.requiredSlots.map { it.id }.sorted()
            val expected = listOf(
                "front_head", "front_torso",
                "front_upper_arm_l", "front_forearm_l",
                "front_upper_arm_r", "front_forearm_r",
                "front_thigh_l", "front_shin_l",
                "front_thigh_r", "front_shin_r",
            ).sorted()
            Assert.equals(expected, required, "required slots")
            Assert.that(template.SLOTS.none { it.required && it.view != ViewKind.FRONT }) {
                "optional views must never be required"
            }
        },
        TestCase("every slot stays inside the sheet with a safe margin") {
            for (slot in template.SLOTS) {
                Assert.that(slot.rect.x >= 0 && slot.rect.y >= 0) { "${slot.id} starts outside the sheet" }
                Assert.that(slot.rect.right <= template.SHEET_WIDTH) { "${slot.id} overflows right edge" }
                Assert.that(slot.rect.bottom <= template.SHEET_HEIGHT) { "${slot.id} overflows bottom edge" }
                Assert.that(slot.rect.width >= 32 && slot.rect.height >= 32) {
                    "${slot.id} is too small to draw into (${slot.rect})"
                }
            }
        },
        TestCase("no two slots overlap") {
            val slots = template.SLOTS
            for (i in slots.indices) {
                for (j in i + 1 until slots.size) {
                    val overlap = slots[i].rect.intersection(slots[j].rect)
                    Assert.that(overlap.isEmpty()) {
                        "${slots[i].id} and ${slots[j].id} overlap by ${overlap.width}x${overlap.height}"
                    }
                }
            }
        },
        TestCase("pivot pixels land inside their own slot") {
            for (slot in template.SLOTS) {
                Assert.that(slot.rect.contains(slot.pivotPixelX, slot.pivotPixelY)) {
                    "${slot.id}: pivot pixel (${slot.pivotPixelX},${slot.pivotPixelY}) outside ${slot.rect}"
                }
            }
        },
    )
}
