package com.rigstudio.core.tests

import com.rigstudio.core.anim.AnimationLibrary
import com.rigstudio.core.geom.Affine
import com.rigstudio.core.geom.Vec2
import com.rigstudio.core.harness.Assert
import com.rigstudio.core.harness.TestCase
import com.rigstudio.core.model.BoneIds
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape
import com.rigstudio.core.model.ViewKind
import com.rigstudio.core.render.Framing
import com.rigstudio.core.render.PuppetComposer
import com.rigstudio.core.render.PuppetDraw
import com.rigstudio.core.render.PuppetPart
import com.rigstudio.core.rig.BonePose
import com.rigstudio.core.rig.CharacterRig
import com.rigstudio.core.rig.RigBuilder
import com.rigstudio.core.rig.ViewAvailability
import com.rigstudio.core.rig.Pose
import com.rigstudio.core.support.Fixtures
import com.rigstudio.core.util.MathUtils
import kotlin.math.abs

/**
 * Draw-list composition (spec §16 z-order, §11 face system, §24 export parity).
 *
 * [PuppetComposer] is the single place that decides what gets drawn, where and in which order, so
 * the preview and the MP4 encoder cannot disagree. These tests pin that decision down.
 */
object RenderTests {

    private fun draw(draws: List<PuppetDraw>, slotId: String) = draws.first { it.slotId == slotId }

    private fun part(draws: List<PuppetDraw>, part: PuppetPart) = draws.filter { it.part == part }

    private fun faceOnly(include: (com.rigstudio.core.template.SheetSlot) -> Boolean) =
        Fixtures.rig(ViewKind.FRONT, include = include)

    val cases: List<TestCase> = listOf(
        TestCase("every drawn part appears once, in layer order") {
            val rig = Fixtures.rig()
            val draws = PuppetComposer.compose(rig, Pose.REST)
            val body = part(draws, PuppetPart.BODY)
            Assert.equals(rig.bones.count { it.hasArtwork }, body.size, "one draw per part with artwork")
            Assert.equals(2, draws.size - body.size, "a complete sheet adds eyes and a mouth")
            Assert.equals(body.size, body.map { it.slotId }.distinct().size, "no duplicate sprites")
            val z = draws.map { it.z }
            Assert.equals(z.sorted(), z, "draws are ordered back to front")
            Assert.equals(
                rig.bones.filter { it.hasArtwork }.map { it.sprite!!.slotId }.sorted(),
                body.map { it.slotId }.sorted(),
                "the body draw list is exactly the rig's artwork",
            )
        },
        TestCase("parts the user did not draw are simply absent") {
            val rig = Fixtures.rig(include = Fixtures.minimalInclude())
            val draws = PuppetComposer.compose(rig, Pose.REST)
            Assert.equals(rig.bones.count { it.hasArtwork }, draws.size, "only drawn parts appear")
            Assert.that(draws.none { it.slotId.contains("hand") }) { "no hands were drawn" }
            Assert.that(draws.none { it.slotId.contains("foot") }) { "no feet were drawn" }
            Assert.that(draws.any { it.slotId == "front_torso" }) { "the torso is there" }
            Assert.that(part(draws, PuppetPart.EYES).isEmpty()) { "a minimal sheet has no face" }
        },
        TestCase("the face swaps with the pose, never deforms") {
            val rig = Fixtures.rig()
            val happy = PuppetComposer.compose(rig, Pose(expression = Expression.HAPPY, mouth = MouthShape.SMILE))
            Assert.equals("eye_happy", part(happy, PuppetPart.EYES).single().slotId, "happy eyes")
            Assert.equals("mouth_smile", part(happy, PuppetPart.MOUTH).single().slotId, "smiling mouth")

            val sad = PuppetComposer.compose(rig, Pose(expression = Expression.SAD, mouth = MouthShape.SAD))
            Assert.equals("eye_sad", part(sad, PuppetPart.EYES).single().slotId)
            Assert.equals("mouth_sad", part(sad, PuppetPart.MOUTH).single().slotId)

            for (expression in Expression.entries) {
                val draws = PuppetComposer.compose(rig, Pose(expression = expression))
                Assert.equals(
                    expression.eyeSlotId, part(draws, PuppetPart.EYES).single().slotId,
                    "$expression must select its own eye sprite",
                )
            }
            for (shape in MouthShape.entries) {
                val draws = PuppetComposer.compose(rig, Pose(mouth = shape))
                Assert.equals(
                    shape.slotId, part(draws, PuppetPart.MOUTH).single().slotId,
                    "$shape must select its own mouth sprite",
                )
            }
        },
        TestCase("a character drawn without expressions falls back gracefully") {
            val eyesOnly = faceOnly { it.required || it.id == "eye_open" }
            val draws = PuppetComposer.compose(eyesOnly, Pose(expression = Expression.ANGRY))
            Assert.equals("eye_open", part(draws, PuppetPart.EYES).single().slotId, "falls back to the only eyes drawn")
            Assert.equals(0, part(draws, PuppetPart.MOUTH).size, "no mouths drawn, no mouth drawn")

            val mouthsOnly = faceOnly { it.required || it.id == "mouth_closed" }
            val mouthDraws = PuppetComposer.compose(mouthsOnly, Pose(mouth = MouthShape.O))
            Assert.equals("mouth_closed", part(mouthDraws, PuppetPart.MOUTH).single().slotId)
            Assert.equals(0, part(mouthDraws, PuppetPart.EYES).size, "no eyes drawn, no eyes drawn")
        },
        TestCase("the face rides the head transform") {
            val rig = Fixtures.rig()
            val rest = PuppetComposer.compose(rig, Pose.REST)
            val restEyes = part(rest, PuppetPart.EYES).single()

            val turned = PuppetComposer.compose(rig, Pose(bones = mapOf(BoneIds.HEAD to BonePose(20f))))
            val turnedEyes = part(turned, PuppetPart.EYES).single()

            // Expected: the eye centre rotated about the head joint by the same 20 degrees.
            val head = rig.bone(BoneIds.HEAD)!!
            val angle = MathUtils.degToRad(20f)
            val rotated = Affine.rotationAbout(angle, head.joint).transform(restEyes.centre)
            Assert.close(rotated.x, turnedEyes.centre.x, 1e-4f, "eyes follow the head horizontally")
            Assert.close(rotated.y, turnedEyes.centre.y, 1e-4f, "eyes follow the head vertically")
            Assert.that(abs(turnedEyes.centre.x - restEyes.centre.x) > 1e-3f) { "the head turn is visible" }

            // The face is anchored inside the head, so it stays inside the head artwork.
            val headDraw = draw(turned, "front_head")
            val headCorners = listOf(
                headDraw.world.transform(headDraw.restRect.left, headDraw.restRect.top),
                headDraw.world.transform(headDraw.restRect.right, headDraw.restRect.top),
                headDraw.world.transform(headDraw.restRect.left, headDraw.restRect.bottom),
                headDraw.world.transform(headDraw.restRect.right, headDraw.restRect.bottom),
            )
            Assert.inRange(turnedEyes.centre.x, headCorners.minOf { it.x }, headCorners.maxOf { it.x }, "eye x inside head")
            Assert.inRange(turnedEyes.centre.y, headCorners.minOf { it.y }, headCorners.maxOf { it.y }, "eye y inside head")
        },
        TestCase("the face is layered above the head and below nothing") {
            val rig = Fixtures.rig()
            val draws = PuppetComposer.compose(rig, Pose.REST)
            val headIndex = draws.indexOfFirst { it.slotId == "front_head" }
            val eyeIndex = draws.indexOfFirst { it.part == PuppetPart.EYES }
            val mouthIndex = draws.indexOfFirst { it.part == PuppetPart.MOUTH }
            Assert.that(headIndex >= 0 && eyeIndex > headIndex) { "eyes draw after the head" }
            Assert.that(mouthIndex > eyeIndex) { "the mouth draws after the eyes" }
            Assert.equals(PuppetComposer.EYES_Z, draws[eyeIndex].z)
            Assert.equals(PuppetComposer.MOUTH_Z, draws[mouthIndex].z)
            Assert.that(PuppetComposer.EYES_Z > draw(draws, "front_head").z) { "face layers clear the head" }
        },
        TestCase("a back view never draws a face") {
            val back = Fixtures.rig(ViewKind.BACK)
            val draws = PuppetComposer.compose(back, Pose(expression = Expression.HAPPY, mouth = MouthShape.SMILE))
            Assert.that(draws.isNotEmpty()) { "the back view still draws the body" }
            Assert.equals(0, part(draws, PuppetPart.EYES).size, "no eyes on the back of a head")
            Assert.equals(0, part(draws, PuppetPart.MOUTH).size, "no mouth on the back of a head")
            Assert.that(draws.all { it.slotId.startsWith("back_") }) { "only back artwork is used" }
        },
        TestCase("profile faces sit towards the facing edge") {
            val left = Fixtures.rig(ViewKind.SIDE_LEFT)
            val leftEyes = part(PuppetComposer.compose(left, Pose.REST), PuppetPart.EYES).single()
            val leftHead = draw(PuppetComposer.compose(left, Pose.REST), "side_left_head")
            Assert.that(
                leftEyes.centre.x < leftHead.centre.x,
            ) { "a left-facing profile puts the eye towards the left edge (${leftEyes.centre.x} vs ${leftHead.centre.x})" }

            val right = Fixtures.rig(ViewKind.SIDE_RIGHT)
            val rightDraws = PuppetComposer.compose(right, Pose.REST)
            val rightEyes = part(rightDraws, PuppetPart.EYES).single()
            val rightHead = draw(rightDraws, "side_right_head")
            Assert.that(
                rightEyes.centre.x > rightHead.centre.x,
            ) { "a right-facing profile puts the eye towards the right edge" }
            Assert.that(!leftEyes.world.isMirrored()) { "authored profile artwork is never flipped" }
            Assert.that(!rightEyes.world.isMirrored()) { "both profiles are drawn as the user drew them" }
        },
        TestCase("a mirrored profile mirrors the face with the artwork") {
            val plain = Fixtures.rig(ViewKind.SIDE_LEFT)
            val mirrored = Fixtures.rig(
                ViewKind.SIDE_RIGHT,
                include = Fixtures.sideLeftOnlyInclude(),
                options = com.rigstudio.core.rig.RigOptions(mirrorSideView = true),
            )
            Assert.equals(ViewKind.SIDE_LEFT, mirrored.mirroredFrom, "records the artwork source")

            val plainEyes = part(PuppetComposer.compose(plain, Pose.REST), PuppetPart.EYES).single()
            val mirroredEyes = part(PuppetComposer.compose(mirrored, Pose.REST), PuppetPart.EYES).single()
            Assert.equals(plainEyes.slotId, mirroredEyes.slotId, "the same eye sprite is reused")
            Assert.close(1f - plainEyes.centre.x, mirroredEyes.centre.x, 1e-4f, "the face lands mirrored")
            Assert.close(plainEyes.centre.y, mirroredEyes.centre.y, 1e-4f, "mirroring never moves the face vertically")
            Assert.that(!plainEyes.world.isMirrored() && mirroredEyes.world.isMirrored()) {
                "only the derived profile flips its pixels"
            }
        },
        TestCase("far limbs in a profile are shaded behind the body") {
            val rig = Fixtures.rig(ViewKind.SIDE_LEFT)
            val draws = PuppetComposer.compose(rig, Pose.REST)
            val shaded = draws.filter { it.shade < 1f }
            Assert.equals(6, shaded.size, "one far arm, forearm, hand, thigh, shin and foot")
            // Near and far bones share a slot id in a profile, so exactly one layer per limb
            // (six limbs) carries the shade.
            Assert.equals(
                listOf(
                    "side_left_foot", "side_left_forearm", "side_left_hand",
                    "side_left_shin", "side_left_thigh", "side_left_upper_arm",
                ),
                shaded.map { it.slotId }.sorted(),
                "the shaded layers are the far limbs",
            )
            Assert.that(draws.filter { it.shade >= 1f }.isNotEmpty()) { "near limbs stay full colour" }

            val front = PuppetComposer.compose(Fixtures.rig(), Pose.REST)
            Assert.that(front.all { it.shade == 1f }) { "a front view has no depth shading" }
        },
        TestCase("the camera transform maps the character into output pixels") {
            val rig = Fixtures.rig()
            val width = 1920
            val height = 1080
            for (clip in AnimationLibrary.ALL.filterNot { it.needsSideView }) {
                val camera = Framing.forPixels(rig, clip, width, height)
                for (i in 0..12) {
                    val draws = PuppetComposer.compose(rig, clip.sample(i / 12f), camera.transform)
                    Assert.that(draws.isNotEmpty()) { "${clip.id}: nothing to draw" }
                    for (d in draws) {
                        val centre = d.centre
                        Assert.inRange(centre.x, -2f, width + 2f, "${clip.id}/${d.slotId} x")
                        Assert.inRange(centre.y, -2f, height + 2f, "${clip.id}/${d.slotId} y")
                    }
                }
            }
        },
        TestCase("preview and export use the same composition") {
            val rig = Fixtures.rig()
            val clip = AnimationLibrary.WALK
            val pose = clip.sample(0.37f)

            // A 16:9 preview at 1280x720 and an export at 1920x1080 differ only by scale.
            val preview = Framing.forPixels(rig, clip, 1280, 720)
            val export = Framing.forPixels(rig, clip, 1920, 1080)
            val previewDraws = PuppetComposer.compose(rig, pose, preview.transform)
            val exportDraws = PuppetComposer.compose(rig, pose, export.transform)

            Assert.equals(previewDraws.map { it.slotId }, exportDraws.map { it.slotId }, "same parts")
            Assert.equals(previewDraws.map { it.z }, exportDraws.map { it.z }, "same layering")
            val ratio = 1920f / 1280f
            for ((a, b) in previewDraws.zip(exportDraws)) {
                Assert.close(a.centre.x * ratio, b.centre.x, 1e-2f, "${a.slotId} x scales with resolution")
                Assert.close(a.centre.y * ratio, b.centre.y, 1e-2f, "${a.slotId} y scales with resolution")
            }
        },
        TestCase("composition is deterministic and allocation-stable") {
            val rig = Fixtures.rig()
            val pose = AnimationLibrary.RUN.sample(0.42f)
            val first = PuppetComposer.compose(rig, pose)
            val second = PuppetComposer.compose(rig, pose)
            Assert.equals(first, second, "the same input must produce the same draw list")
            Assert.equals(first.size, second.size)
        },
        TestCase("a rig built from almost nothing composes just what exists") {
            val assets = RigBuilder.assetsOf(Fixtures.process())
                .filterKeys { it == "front_torso" || it == "front_head" }
            Assert.equals(2, assets.size, "fixture sanity")
            val rig = RigBuilder.buildFromAssets(
                assets,
                ViewAvailability.from(listOf(ViewKind.FRONT), mirroredSideView = false),
            ).getValue(ViewKind.FRONT)
            Assert.equals(2, rig.bones.size, "undrawn parts are absent, not empty")
            val draws = PuppetComposer.compose(rig, Pose(expression = Expression.HAPPY, mouth = MouthShape.SMILE))
            Assert.equals(listOf("front_head", "front_torso"), draws.map { it.slotId }.sorted())
            Assert.equals(0, rig.selfCheck().size, "a two part rig is still valid: ${rig.selfCheck()}")
        },
        TestCase("sleep and jump stay inside the frame they are composed for") {
            val rig = Fixtures.rig()
            for (clipId in listOf("sleep", "jump", "sit", "wave")) {
                val clip = AnimationLibrary.byId(clipId)!!
                val camera = Framing.forPixels(rig, clip, 1920, 1080)
                for (i in 0..20) {
                    val draws = PuppetComposer.compose(rig, clip.sample(i / 20f), camera.transform)
                    var left = Float.MAX_VALUE
                    var right = -Float.MAX_VALUE
                    var top = Float.MAX_VALUE
                    var bottom = -Float.MAX_VALUE
                    for (d in draws) {
                        val c = d.centre
                        left = minOf(left, c.x); right = maxOf(right, c.x)
                        top = minOf(top, c.y); bottom = maxOf(bottom, c.y)
                    }
                    Assert.that(left >= -40f && right <= 1960f) { "$clipId escapes horizontally at $i" }
                    Assert.that(top >= -40f && bottom <= 1120f) { "$clipId escapes vertically at $i" }
                }
            }
        },
        TestCase("root motion moves the whole draw list together") {
            val rig = Fixtures.rig()
            val rest = PuppetComposer.compose(rig, Pose.REST)
            val lifted = PuppetComposer.compose(rig, Pose(root = BonePose(offset = Vec2(0f, -0.1f))))
            Assert.equals(rest.size, lifted.size, "the part count does not change")
            for ((a, b) in rest.zip(lifted)) {
                Assert.equals(a.slotId, b.slotId)
                Assert.close(a.centre.x, b.centre.x, 1e-4f, "${a.slotId} does not drift sideways")
                Assert.that(b.centre.y < a.centre.y) { "${a.slotId} must rise with the root" }
            }
        },
    )
}
