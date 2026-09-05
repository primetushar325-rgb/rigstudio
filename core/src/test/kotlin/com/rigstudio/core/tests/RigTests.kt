package com.rigstudio.core.tests

import com.rigstudio.core.anim.AnimationLibrary
import com.rigstudio.core.geom.Affine
import com.rigstudio.core.geom.Vec2
import kotlin.math.abs
import com.rigstudio.core.harness.Assert
import com.rigstudio.core.harness.TestCase
import com.rigstudio.core.model.BoneIds
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape
import com.rigstudio.core.model.ViewKind
import com.rigstudio.core.render.Framing
import com.rigstudio.core.rig.BoneConstraint
import com.rigstudio.core.rig.BoneConstraints
import com.rigstudio.core.rig.BonePose
import com.rigstudio.core.rig.CharacterRig
import com.rigstudio.core.rig.ForwardKinematics
import com.rigstudio.core.rig.Pose
import com.rigstudio.core.rig.RigBuilder
import com.rigstudio.core.rig.RigOptions
import com.rigstudio.core.rig.ViewAssemblies
import com.rigstudio.core.rig.ZOrderResolver
import com.rigstudio.core.support.Fixtures
import com.rigstudio.core.template.CharacterSheetTemplate
import kotlin.math.abs

/**
 * Rig construction, forward kinematics, layering, mirroring and framing (spec §8, §9).
 *
 * These tests are the reason the puppet cannot silently fall apart: they assert the hierarchy,
 * that a parent rotation carries its children, that the character stands on the floor, and that
 * every clip stays inside the frame it is composed for.
 */
object RigTests {

    private fun jointOf(rig: CharacterRig, solution: com.rigstudio.core.rig.FkSolution, boneId: String) =
        solution.transformOf(boneId).transform(rig.bone(boneId)!!.joint)

    /**
     * Centre of a bone's artwork in world space.
     *
     * Flipped bones mirror about their own rectangle centre, so transforming the joint point
     * would measure the mirror rather than the limb; the artwork centre is the stable landmark.
     */
    private fun boneCentre(rig: CharacterRig, pose: Pose, boneId: String): Vec2 {
        val solution = ForwardKinematics.solve(rig, pose)
        val rect = rig.bone(boneId)!!.restRect
        return solution.transformOf(boneId).transform(Vec2(rect.centerX, rect.centerY))
    }

    private fun footCentre(rig: CharacterRig, pose: Pose): Vec2 = boneCentre(rig, pose, BoneIds.FOOT_R)

    val cases: List<TestCase> = listOf(
        TestCase("a complete sheet builds all four views") {
            val built = Fixtures.buildRigs()
            Assert.equals(
                listOf(ViewKind.FRONT, ViewKind.SIDE_LEFT, ViewKind.SIDE_RIGHT, ViewKind.BACK),
                built.availableViews,
                "available views",
            )
            Assert.equals(14, built.requireFront().bones.size, "front bone count")
        },
        TestCase("every rig passes its own self check") {
            for ((view, rig) in Fixtures.buildRigs().rigs) {
                Assert.equals(emptyList(), rig.selfCheck(), "$view rig self check")
                Assert.that(rig.topologicalOrder.size == rig.bones.size) { "$view rig order incomplete" }
            }
        },
        TestCase("front rig contains every required body part with artwork") {
            val rig = Fixtures.rig()
            for (boneId in BoneIds.ALL) {
                val bone = rig.bone(boneId)
                Assert.that(bone != null) { "front rig is missing bone '$boneId'" }
                Assert.that(bone!!.hasArtwork) { "bone '$boneId' has no sprite" }
                val sprite = bone.sprite!!
                Assert.that(sprite.width > 0 && sprite.height > 0) { "bone '$boneId' sprite is empty" }
            }
        },
        TestCase("optional parts the user did not draw are simply absent") {
            val rig = Fixtures.rig(include = Fixtures.minimalInclude())
            Assert.equals(10, rig.bones.size, "bones without hands and feet")
            Assert.equals(null, rig.bone(BoneIds.HAND_L), "no left hand")
            Assert.equals(null, rig.bone(BoneIds.FOOT_R), "no right foot")
            Assert.that(rig.bone(BoneIds.TORSO) != null) { "torso must exist" }
            // The rig must still solve and still be drawable.
            val solution = ForwardKinematics.solve(rig, AnimationLibrary.WALK.sample(0.25f))
            Assert.equals(10, solution.draws.size, "draw list matches available parts")
            Assert.that(!solution.bounds.isEmpty()) { "posed bounds must not be empty" }
        },
        TestCase("parents come before children in the solve order") {
            val rig = Fixtures.rig()
            val order = rig.topologicalOrder.map { it.id }
            for (bone in rig.bones) {
                val parent = bone.parentId ?: continue
                Assert.that(order.indexOf(parent) < order.indexOf(bone.id)) {
                    "$parent must be solved before ${bone.id}"
                }
            }
        },
        TestCase("the assembled character stands upright with feet on the floor") {
            val rig = Fixtures.rig()
            val bounds = rig.restBounds
            Assert.close(1.0f, bounds.height, 0.06f, "character height in view units")
            Assert.close(1.0f, bounds.bottom, 0.06f, "feet must reach the floor line")
            Assert.close(0.0f, bounds.top, 0.06f, "head must reach the top of the view space")
            Assert.close(0.5f, bounds.centerX, 0.05f, "character must be centred")
            Assert.that(bounds.width < bounds.height) { "a standing character is taller than wide" }
        },
        TestCase("joints line up along the skeleton") {
            val rig = Fixtures.rig()
            fun jointY(boneId: String) = rig.bone(boneId)!!.joint.y
            fun jointX(boneId: String) = rig.bone(boneId)!!.joint.x

            Assert.that(jointY(BoneIds.HEAD) < jointY(BoneIds.TORSO)) { "neck above chest" }
            Assert.that(jointY(BoneIds.TORSO) < jointY(BoneIds.THIGH_L)) { "chest above hip" }
            Assert.that(jointY(BoneIds.THIGH_L) < jointY(BoneIds.SHIN_L)) { "hip above knee" }
            Assert.that(jointY(BoneIds.SHIN_L) < jointY(BoneIds.FOOT_L)) { "knee above ankle" }
            Assert.that(jointY(BoneIds.TORSO) < jointY(BoneIds.UPPER_ARM_L)) { "chest above shoulder" }
            Assert.that(jointY(BoneIds.UPPER_ARM_L) < jointY(BoneIds.FOREARM_L)) { "shoulder above elbow" }
            Assert.that(jointY(BoneIds.FOREARM_L) < jointY(BoneIds.HAND_L)) { "elbow above wrist" }

            // Symmetry about the centre line.
            Assert.close(jointX(BoneIds.THIGH_L) - 0.5f, -(jointX(BoneIds.THIGH_R) - 0.5f), 1e-4f, "hips mirror")
            Assert.close(jointX(BoneIds.UPPER_ARM_L) - 0.5f, -(jointX(BoneIds.UPPER_ARM_R) - 0.5f), 1e-4f, "shoulders mirror")
            Assert.close(0.5f, jointX(BoneIds.TORSO), 1e-4f, "torso is centred")
            Assert.close(0.5f, jointX(BoneIds.HEAD), 1e-4f, "head is centred")
        },
        TestCase("face sprites are attached to the head only where they belong") {
            val front = Fixtures.rig(ViewKind.FRONT)
            Assert.equals(5, front.faceSet.eyes.size, "front eyes")
            Assert.equals(11, front.faceSet.mouths.size, "front mouths")

            val back = Fixtures.rig(ViewKind.BACK)
            Assert.equals(0, back.faceSet.eyes.size, "a back view must never show a face")
            Assert.equals(0, back.faceSet.mouths.size, "a back view must never show a mouth")

            val side = Fixtures.rig(ViewKind.SIDE_LEFT)
            Assert.that(side.faceSet.hasMouths) { "profile talk needs mouth sprites" }
        },
        TestCase("missing face sprites degrade instead of failing") {
            val rig = Fixtures.rig(include = Fixtures.minimalInclude())
            Assert.that(!rig.faceSet.hasEyes && !rig.faceSet.hasMouths) { "no face artwork" }
            Assert.equals(null, rig.faceSet.eyeSprite(Expression.HAPPY), "no eyes to swap")
            Assert.equals(null, rig.faceSet.mouthSprite(MouthShape.A), "no mouths to swap")
            // With artwork present, fallbacks resolve to the closest available sprite.
            val withFace = Fixtures.rig(include = { it.required || it.id == "eye_open" })
            Assert.that(withFace.faceSet.eyeSprite(Expression.ANGRY) != null) {
                "an unavailable expression must fall back to the neutral eyes"
            }
            Assert.equals(null, withFace.faceSet.mouthSprite(MouthShape.O), "no mouths drawn at all")
        },
        TestCase("forward kinematics is deterministic") {
            val rig = Fixtures.rig()
            val pose = AnimationLibrary.WALK.sample(0.37f)
            val first = ForwardKinematics.solve(rig, pose)
            val second = ForwardKinematics.solve(rig, pose)
            for (boneId in first.transforms.keys) {
                Assert.equals(first.transforms[boneId], second.transforms[boneId], "$boneId transform must be stable")
            }
            Assert.equals(first.bounds, second.bounds, "bounds must be stable")
        },
        TestCase("rotating a parent carries its children") {
            val rig = Fixtures.rig()
            val rest = ForwardKinematics.solve(rig, Pose.REST)
            val bent = ForwardKinematics.solve(
                rig,
                Pose(bones = mapOf(BoneIds.THIGH_L to BonePose(rotationDeg = 35f))),
            )

            val thighMoved = jointOf(rig, bent, BoneIds.SHIN_L) != jointOf(rig, rest, BoneIds.SHIN_L)
            Assert.that(thighMoved) { "knee must follow the thigh" }
            Assert.that(jointOf(rig, bent, BoneIds.FOOT_L) != jointOf(rig, rest, BoneIds.FOOT_L)) {
                "ankle must follow the thigh"
            }
            // The other leg and the arms are untouched.
            Assert.equals(jointOf(rig, rest, BoneIds.SHIN_R), jointOf(rig, bent, BoneIds.SHIN_R), "right knee is static")
            Assert.equals(jointOf(rig, rest, BoneIds.HAND_L), jointOf(rig, bent, BoneIds.HAND_L), "left hand is static")
            // The thigh's own joint never moves: it is the pivot.
            Assert.close(jointOf(rig, rest, BoneIds.THIGH_L).x, jointOf(rig, bent, BoneIds.THIGH_L).x, 1e-4f, "hip x is fixed")
            Assert.close(jointOf(rig, rest, BoneIds.THIGH_L).y, jointOf(rig, bent, BoneIds.THIGH_L).y, 1e-4f, "hip y is fixed")
        },
        TestCase("root motion moves the whole character") {
            val rig = Fixtures.rig()
            val rest = ForwardKinematics.solve(rig, Pose.REST)
            val lowered = ForwardKinematics.solve(rig, Pose(root = BonePose(offset = Vec2(0f, 0.15f))))
            Assert.close(
                0.15f,
                jointOf(rig, lowered, BoneIds.HEAD).y - jointOf(rig, rest, BoneIds.HEAD).y,
                1e-3f,
                "head drops by the root offset",
            )
            Assert.close(
                0.15f,
                jointOf(rig, lowered, BoneIds.FOOT_L).y - jointOf(rig, rest, BoneIds.FOOT_L).y,
                1e-3f,
                "feet drop by the root offset",
            )

            val laid = ForwardKinematics.solve(rig, Pose(root = BonePose(rotationDeg = -90f, offset = Vec2(0.03f, 0.30f))))
            Assert.that(laid.bounds.width > laid.bounds.height * 1.4f) {
                "a -90° root rotation must lay the character down (bounds ${laid.bounds})"
            }
        },
        TestCase("the sleep clip really lies the character down") {
            val rig = Fixtures.rig()
            val sleeping = ForwardKinematics.solve(rig, AnimationLibrary.SLEEP.sample(0.5f))
            val standing = ForwardKinematics.solve(rig, AnimationLibrary.STAND.sample(0.5f))
            Assert.that(sleeping.bounds.width > sleeping.bounds.height) { "sleeping bounds must be wide" }
            Assert.that(standing.bounds.height > standing.bounds.width) { "standing bounds must be tall" }
            Assert.that(sleeping.bounds.height < standing.bounds.height * 0.7f) {
                "a lying character occupies far less vertical space"
            }
        },
        TestCase("z-order puts the forward leg and leading arm on top") {
            val rig = Fixtures.rig()
            val leftForward = ZOrderResolver.resolve(
                rig,
                Pose(bones = mapOf(BoneIds.THIGH_L to BonePose(-30f), BoneIds.THIGH_R to BonePose(20f))),
            )
            Assert.that(leftForward[BoneIds.THIGH_L]!! > leftForward[BoneIds.THIGH_R]!!) {
                "left leg leads: ${leftForward[BoneIds.THIGH_L]} vs ${leftForward[BoneIds.THIGH_R]}"
            }

            val rightForward = ZOrderResolver.resolve(
                rig,
                Pose(bones = mapOf(BoneIds.THIGH_L to BonePose(20f), BoneIds.THIGH_R to BonePose(-30f))),
            )
            Assert.that(rightForward[BoneIds.THIGH_R]!! > rightForward[BoneIds.THIGH_L]!!) {
                "right leg leads"
            }

            val armLead = ZOrderResolver.resolve(
                rig,
                Pose(bones = mapOf(BoneIds.UPPER_ARM_L to BonePose(20f), BoneIds.UPPER_ARM_R to BonePose(-25f))),
            )
            Assert.that(armLead[BoneIds.UPPER_ARM_R]!! > armLead[BoneIds.UPPER_ARM_L]!!) {
                "the arm swinging forward draws on top"
            }

            // Legs stay behind the torso in front view; the head is always last.
            val torsoZ = rig.bone(BoneIds.TORSO)!!.z
            Assert.that(leftForward.values.max() <= torsoZ + 40) { "z stays in the authored range" }
            Assert.that(leftForward[BoneIds.THIGH_L]!! < torsoZ) { "legs stay behind the torso" }
            Assert.that(leftForward[BoneIds.HEAD] == null || leftForward[BoneIds.HEAD]!! > torsoZ) {
                "head draws above the torso"
            }
        },
        TestCase("profile views keep their authored near/far order") {
            val side = Fixtures.rig(ViewKind.SIDE_LEFT)
            Assert.equals(emptyMap(), ZOrderResolver.resolve(side, AnimationLibrary.SIDE_WALK.sample(0.3f)))
            val near = side.bone(BoneIds.THIGH_R)!!
            val far = side.bone(BoneIds.THIGH_L)!!
            Assert.that(near.z > side.bone(BoneIds.TORSO)!!.z) { "near leg draws in front of the torso" }
            Assert.that(far.z < side.bone(BoneIds.TORSO)!!.z) { "far leg draws behind the torso" }
            Assert.that(far.depthShade < near.depthShade) { "far limbs are shaded for depth" }
            // Both limbs share the single profile sprite the user drew.
            Assert.equals(near.sprite!!.slotId, far.sprite!!.slotId, "near and far share one drawing")
            Assert.equals("side_left_thigh", near.sprite!!.slotId)
        },
        TestCase("profile rigs draw the whole body in draw order") {
            val side = Fixtures.rig(ViewKind.SIDE_LEFT)
            val solution = ForwardKinematics.solve(side, AnimationLibrary.SIDE_WALK.sample(0.2f))
            Assert.equals(14, solution.draws.size, "profile rigs use both limbs")
            val zs = solution.draws.map { it.z }
            Assert.equals(zs.sorted(), zs, "draw list must be sorted by z")
            Assert.equals(BoneIds.HEAD, solution.draws.last().bone.id, "head draws last")
        },
        TestCase("mirroring the side view flips artwork and rotation semantics") {
            val built = Fixtures.buildRigs(
                include = Fixtures.sideLeftOnlyInclude(),
                options = RigOptions(mirrorSideView = true),
            )
            val mirrored = built.rigFor(ViewKind.SIDE_RIGHT)
            Assert.that(mirrored != null) { "mirror option must synthesise the right profile" }
            Assert.equals(ViewKind.SIDE_LEFT, mirrored!!.mirroredFrom, "records where the art came from")
            Assert.that(mirrored.bones.all { it.flipX }) { "every mirrored part is drawn flipped" }
            Assert.that(mirrored.bones.all { it.sprite!!.slotId.startsWith("side_left_") }) {
                "mirrored rig reuses the left artwork"
            }
            // Constraints are mirrored with the artwork.
            val thigh = mirrored.bone(BoneIds.THIGH_R)!!
            Assert.equals(BoneConstraints.forBone(BoneIds.THIGH_R).mirrored(), thigh.constraint)
            Assert.equals(BoneConstraint(-60f, 125f), thigh.constraint, "mirrored thigh limits")

            // The same signed angle must swing the mirrored limb the other way on screen —
            // that is what keeps clip data view agnostic.
            val plain = Fixtures.rig(ViewKind.SIDE_LEFT)
            val pose = Pose(bones = mapOf(BoneIds.SHIN_R to BonePose(rotationDeg = 40f)))
            val plainFoot = footCentre(plain, pose)
            val plainRest = footCentre(plain, Pose.REST)
            val mirroredFoot = footCentre(mirrored, pose)
            val mirroredRest = footCentre(mirrored, Pose.REST)
            // Rest geometry is an exact mirror image, including the offset ankle joint.
            Assert.close(1f - plainRest.x, mirroredRest.x, 1e-4f, "the mirrored profile is a true mirror image")
            Assert.close(plainRest.y, mirroredRest.y, 1e-4f, "mirroring never moves a joint vertically")
            val plainDelta = plainFoot.x - plainRest.x
            val mirroredDelta = mirroredFoot.x - mirroredRest.x
            Assert.that(abs(plainDelta) > 0.01f) { "knee flexion must move the foot ($plainDelta)" }
            Assert.that(plainDelta * mirroredDelta < 0f) {
                "mirrored profile must swing the opposite way " +
                    "(plain=$plainDelta mirrored=$mirroredDelta)"
            }
            Assert.close(abs(plainDelta), abs(mirroredDelta), 1e-4f, "the swing keeps its size")
            Assert.close(
                1f - plainFoot.x, mirroredFoot.x, 1e-4f,
                "the posed mirror is the mirror of the posed original",
            )
            // Knee flexion lifts the foot as it swings.
            Assert.that(plainFoot.y < plainRest.y) { "flexion lifts the heel" }
            Assert.that(abs(plainFoot.y - mirroredFoot.y) < 1e-4f) { "both profiles lift identically" }
        },
        TestCase("mirroring is not offered without side artwork") {
            val built = Fixtures.buildRigs(
                include = Fixtures.minimalInclude(),
                options = RigOptions(mirrorSideView = true),
            )
            Assert.equals(null, built.rigFor(ViewKind.SIDE_RIGHT), "nothing to mirror")
            Assert.equals(listOf(ViewKind.FRONT), built.availableViews)
        },
        TestCase("mirroring a pose swaps limbs and negates rotations") {
            val pose = Pose(
                root = BonePose(rotationDeg = 4f, offset = Vec2(0.02f, -0.01f)),
                bones = mapOf(
                    BoneIds.THIGH_L to BonePose(rotationDeg = -26f, offset = Vec2(0.01f, 0f)),
                    BoneIds.THIGH_R to BonePose(rotationDeg = 22f),
                    BoneIds.TORSO to BonePose(rotationDeg = 3f),
                ),
            )
            val mirrored = pose.mirrored()
            Assert.close(26f, mirrored.rotationOf(BoneIds.THIGH_R), 1e-4f, "left thigh becomes right thigh, negated")
            Assert.close(-22f, mirrored.rotationOf(BoneIds.THIGH_L), 1e-4f, "right thigh becomes left thigh, negated")
            Assert.close(-3f, mirrored.rotationOf(BoneIds.TORSO), 1e-4f, "the torso keeps its id but negates")
            Assert.close(-4f, mirrored.root.rotationDeg, 1e-4f, "root rotation negates")
            Assert.close(-0.02f, mirrored.root.offset.x, 1e-4f, "root offset x negates")
            Assert.close(-0.01f, mirrored.root.offset.y, 1e-4f, "root offset y is preserved")
            Assert.equals(pose, mirrored.mirrored(), "mirroring twice restores the pose")
        },
        TestCase("the camera frames every clip inside the output") {
            val rig = Fixtures.rig()
            for (clip in AnimationLibrary.ALL.filterNot { it.needsSideView }) {
                val camera = Framing.forClip(rig, clip, aspect = 16f / 9f)
                Assert.that(camera.scale > 0.05f) { "${clip.id}: camera scale must be usable" }
                var worstLeft = Float.MAX_VALUE
                var worstRight = -Float.MAX_VALUE
                var worstTop = Float.MAX_VALUE
                var worstBottom = -Float.MAX_VALUE
                for (i in 0..64) {
                    val posed = ForwardKinematics.solve(rig, clip.sample(i / 64f), camera.transform)
                    worstLeft = minOf(worstLeft, posed.bounds.left)
                    worstRight = maxOf(worstRight, posed.bounds.right)
                    worstTop = minOf(worstTop, posed.bounds.top)
                    worstBottom = maxOf(worstBottom, posed.bounds.bottom)
                }
                Assert.that(worstLeft >= -0.02f) { "${clip.id}: content escapes the left edge ($worstLeft)" }
                Assert.that(worstRight <= camera.aspect + 0.02f) {
                    "${clip.id}: content escapes the right edge ($worstRight > ${camera.aspect})"
                }
                Assert.that(worstTop >= -0.02f) { "${clip.id}: content escapes the top edge ($worstTop)" }
                Assert.that(worstBottom <= 1.02f) { "${clip.id}: content escapes the bottom edge ($worstBottom)" }

                // The character should fill most of the frame — not a postage stamp. A clip that
                // changes body orientation (sleep: standing to lying) unions a tall pose with a
                // wide one, so it cannot fill either axis; everything else must.
                val changesOrientation =
                    clip.rootTrack?.keys?.any { abs(it.rotationDeg) > 45f } == true
                val verticalFill = worstBottom - worstTop
                val horizontalFill = (worstRight - worstLeft) / camera.aspect
                Assert.inRange(
                    maxOf(verticalFill, horizontalFill),
                    if (changesOrientation) 0.30f else 0.55f,
                    1.05f,
                    "${clip.id}: frame fill (v=$verticalFill h=$horizontalFill)",
                )
            }
        },
        TestCase("the same camera works for every output resolution") {
            val rig = Fixtures.rig()
            val clip = AnimationLibrary.WALK
            val hd = Framing.forPixels(rig, clip, 1280, 720)
            val fullHd = Framing.forPixels(rig, clip, 1920, 1080)
            Assert.close(1080f / 720f, fullHd.transform.a / hd.transform.a, 1e-3f, "scale ratio matches resolution ratio")
            val posedHd = ForwardKinematics.solve(rig, clip.sample(0.3f), hd.transform).bounds
            val posedFull = ForwardKinematics.solve(rig, clip.sample(0.3f), fullHd.transform).bounds
            Assert.close(posedHd.height * 1.5f, posedFull.height, 0.5f, "composition is resolution independent")
            Assert.close(posedHd.centerX * 1.5f, posedFull.centerX, 0.5f, "horizontal framing matches")
        },
        TestCase("view assemblies are internally consistent") {
            for (assembly in ViewAssemblies.ALL + ViewAssemblies.SIDE_RIGHT_MIRRORED) {
                val ids = assembly.bones.map { it.boneId }
                Assert.equals(ids.distinct().size, ids.size, "${assembly.view}: duplicate bones")
                for (part in assembly.bones) {
                    Assert.that(BoneIds.isKnown(part.boneId)) { "${assembly.view}: unknown bone ${part.boneId}" }
                    Assert.that(part.targetHeight > 0.02f) { "${assembly.view}: ${part.boneId} too small" }
                    Assert.that(part.slotId in CharacterSheetTemplate.SLOTS.map { it.id }) {
                        "${assembly.view}: ${part.boneId} references unknown slot ${part.slotId}"
                    }
                    Assert.inRange(part.depthShade, 0.5f, 1f, "${assembly.view}: shade")
                }
                Assert.that(assembly.bone(BoneIds.TORSO) != null) { "${assembly.view} needs a torso" }
                Assert.that(assembly.bone(BoneIds.HEAD) != null) { "${assembly.view} needs a head" }
            }
            // The back view reuses the front geometry with back artwork.
            val front = ViewAssemblies.FRONT
            val back = ViewAssemblies.BACK
            for (part in front.bones) {
                val backPart = back.bone(part.boneId)!!
                Assert.equals(part.joint, backPart.joint, "back view keeps front joints for ${part.boneId}")
                Assert.equals(part.targetHeight, backPart.targetHeight, "back view keeps proportions")
                Assert.that(backPart.slotId.startsWith("back_")) { "back view must use back artwork" }
            }
        },
        TestCase("rigs can be rebuilt from a saved sprite manifest") {
            val built = Fixtures.buildRigs()
            val assets = built.sprites.mapValues { (_, sprite) ->
                com.rigstudio.core.rig.SpriteAsset(
                    slotId = sprite.slotId,
                    width = sprite.width,
                    height = sprite.height,
                    pivot = sprite.pivot,
                    coverage = sprite.coverage,
                    sourceRect = sprite.sourceRect,
                    contentRect = sprite.contentRect,
                )
            }
            val rebuilt = RigBuilder.buildFromAssets(assets, built.availability)
            Assert.equals(built.rigs.keys, rebuilt.keys, "same views are rebuilt")
            for ((view, rig) in rebuilt) {
                val original = built.rigFor(view)!!
                Assert.equals(original.bones.map { it.id }, rig.bones.map { it.id }, "$view bones match")
                Assert.equals(
                    original.bones.map { it.joint },
                    rig.bones.map { it.joint },
                    "$view joints match",
                )
            }
        },
        TestCase("transform maths behaves") {
            val identity = Affine.IDENTITY
            Assert.equals(identity, identity.multiply(identity))
            val rotated = Affine.rotationAbout(Math.toRadians(90.0).toFloat(), Vec2(0f, 0f))
            val point = rotated.transform(1f, 0f)
            Assert.close(0f, point.x, 1e-4f, "90° clockwise maps +x to +y")
            Assert.close(1f, point.y, 1e-4f, "90° clockwise maps +x to +y")
            val mirrored = Affine.mirrorAbout(0.5f)
            Assert.close(0f, mirrored.transform(1f, 0f).x, 1e-4f, "mirror about x=0.5")
            Assert.that(mirrored.isMirrored()) { "mirror must flip handedness" }
            Assert.that(!identity.isMirrored()) { "identity keeps handedness" }
            Assert.close(2f, Affine.scaling(2f).scaleMagnitude(), 1e-4f, "scale magnitude")
            Assert.that(abs(Affine.translation(3f, 4f).tx - 3f) < 1e-6f) { "translation" }
        },
    )
}
