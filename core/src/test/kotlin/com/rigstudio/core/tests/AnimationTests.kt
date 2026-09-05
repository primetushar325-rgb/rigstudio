package com.rigstudio.core.tests

import com.rigstudio.core.anim.AnimationClip
import com.rigstudio.core.anim.AnimationKeyframe
import com.rigstudio.core.anim.AnimationLibrary
import com.rigstudio.core.anim.BoneTrack
import com.rigstudio.core.anim.ClipCategory
import com.rigstudio.core.anim.Easing
import com.rigstudio.core.harness.Assert
import com.rigstudio.core.harness.TestCase
import com.rigstudio.core.model.BoneIds
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape
import com.rigstudio.core.model.ViewKind
import com.rigstudio.core.rig.BoneConstraint
import com.rigstudio.core.rig.BoneConstraintValidator
import com.rigstudio.core.rig.BoneConstraints
import com.rigstudio.core.rig.BonePose
import com.rigstudio.core.rig.ForwardKinematics
import com.rigstudio.core.rig.Pose
import com.rigstudio.core.support.Fixtures
import com.rigstudio.core.util.MathUtils
import kotlin.math.abs

/**
 * Animation data and sampling (spec §10–§14).
 *
 * The library is data, not code paths in the UI, so it can be checked exhaustively: every clip
 * must target real bones, stay inside the constraint table, loop seamlessly and produce the poses
 * its keyframes describe.
 */
object AnimationTests {

    private val REQUIRED_CLIPS = listOf(
        "idle", "stand", "walk", "run", "talk", "wave", "sit", "sleep", "jump",
        "walk_talk", "side_walk", "side_run", "side_talk", "look_back",
        "happy", "sad", "angry", "surprised",
    )

    val cases: List<TestCase> = listOf(
        TestCase("the library ships all eighteen required animations") {
            Assert.equals(18, AnimationLibrary.ALL.size, "clip count")
            val ids = AnimationLibrary.ALL.map { it.id }
            Assert.equals(REQUIRED_CLIPS.sorted(), ids.sorted(), "clip ids")
            Assert.equals(ids.distinct().size, ids.size, "clip ids must be unique")
            for (clip in AnimationLibrary.ALL) {
                Assert.that(clip.name.isNotBlank()) { "${clip.id} needs a display name" }
                Assert.that(clip.durationSeconds > 0f) { "${clip.id} needs a duration" }
                Assert.that(clip.tracks.isNotEmpty() || clip.rootTrack != null) {
                    "${clip.id} animates nothing"
                }
            }
        },
        TestCase("clips can be looked up by id") {
            Assert.equals("walk", AnimationLibrary.byId("walk")?.id)
            Assert.equals("idle", AnimationLibrary.byIdOrIdle("does_not_exist").id, "unknown id falls back to idle")
            Assert.equals(null, AnimationLibrary.byId("does_not_exist"))
        },
        TestCase("every track targets a universal bone id") {
            for (clip in AnimationLibrary.ALL) {
                val tracks = clip.tracks.values + listOfNotNull(clip.rootTrack)
                for (track in tracks) {
                    Assert.that(BoneIds.isKnown(track.boneId)) {
                        "${clip.id}: track '${track.boneId}' is not a universal bone id"
                    }
                    Assert.that(track.keys.isNotEmpty()) { "${clip.id}/${track.boneId}: no keyframes" }
                }
                Assert.equals(clip.tracks.size, clip.tracks.keys.distinct().size, "${clip.id}: duplicate tracks")
            }
        },
        TestCase("no clip uses character specific bone ids") {
            for (clip in AnimationLibrary.ALL) {
                for (boneId in clip.tracks.keys) {
                    Assert.that(!boneId.startsWith("front_") && !boneId.startsWith("side_") &&
                        !boneId.startsWith("back_")) {
                        "${clip.id}: '$boneId' looks like a slot id, not a bone id"
                    }
                }
            }
        },
        TestCase("keyframe times are normalised and ordered") {
            for (clip in AnimationLibrary.ALL) {
                val tracks = clip.tracks.values + listOfNotNull(clip.rootTrack)
                for (track in tracks) {
                    var previous = -1f
                    for (key in track.keys) {
                        Assert.inRange(key.time, 0f, 1f, "${clip.id}/${track.boneId} key time")
                        Assert.that(key.time >= previous) {
                            "${clip.id}/${track.boneId}: keyframes out of order at ${key.time}"
                        }
                        previous = key.time
                        Assert.that(MathUtils.isFinite(key.rotationDeg)) { "${clip.id}: NaN rotation" }
                        Assert.that(abs(key.offsetX) <= 1f && abs(key.offsetY) <= 1f) {
                            "${clip.id}/${track.boneId}: offset ${key.offsetX},${key.offsetY} is not a height fraction"
                        }
                        Assert.that(key.scale > 0f) { "${clip.id}: non positive scale" }
                    }
                }
            }
        },
        TestCase("every clip respects the bone constraint table") {
            val violations = BoneConstraintValidator.validateAll(AnimationLibrary.ALL)
            Assert.equals(emptyList(), violations.map { "${it.clipId}/${it.boneId}: ${it.detail}" })
        },
        TestCase("the validator catches a broken clip") {
            val broken = AnimationClip(
                id = "broken",
                name = "Broken",
                durationSeconds = 1f,
                loop = true,
                tracks = mapOf(
                    "not_a_bone" to BoneTrack("not_a_bone", listOf(AnimationKeyframe(0f, 0f))),
                    BoneIds.THIGH_L to BoneTrack(
                        BoneIds.THIGH_L,
                        listOf(AnimationKeyframe(0f, -400f), AnimationKeyframe(1f, -400f)),
                    ),
                    BoneIds.HEAD to BoneTrack(
                        BoneIds.HEAD,
                        listOf(AnimationKeyframe(0.8f, 0f), AnimationKeyframe(0.2f, 5f)),
                    ),
                    BoneIds.TORSO to BoneTrack(
                        BoneIds.TORSO,
                        listOf(AnimationKeyframe(-0.5f, 0f), AnimationKeyframe(1.5f, 0f)),
                    ),
                ),
            )
            val violations = BoneConstraintValidator.validate(broken)
            val kinds = violations.map { it.kind }.toSet()
            Assert.contains(kinds, BoneConstraintValidator.ViolationKind.UNKNOWN_BONE, "unknown bone detected")
            Assert.contains(
                kinds, BoneConstraintValidator.ViolationKind.ROTATION_OUT_OF_RANGE,
                "out of range rotation detected",
            )
            Assert.contains(kinds, BoneConstraintValidator.ViolationKind.KEYFRAME_ORDER, "unordered keys detected")
            Assert.contains(
                kinds, BoneConstraintValidator.ViolationKind.KEYFRAME_TIME_OUT_OF_RANGE,
                "out of range key times detected",
            )
            Assert.that(violations.all { it.clipId == "broken" }) { "violations name their clip" }
        },
        TestCase("sampling clamps rotations that exceed the limits") {
            val wild = AnimationClip(
                id = "wild",
                name = "Wild",
                durationSeconds = 1f,
                loop = true,
                tracks = mapOf(
                    BoneIds.THIGH_L to BoneTrack(
                        BoneIds.THIGH_L,
                        listOf(AnimationKeyframe(0f, -900f), AnimationKeyframe(1f, -900f)),
                    ),
                ),
            )
            val pose = wild.sample(0.5f)
            Assert.close(
                BoneConstraints.forBone(BoneIds.THIGH_L).minRotationDeg,
                pose.rotationOf(BoneIds.THIGH_L),
                1e-3f,
                "runtime clamping keeps the bone inside its limit",
            )
        },
        TestCase("clampPose is a runtime safety net") {
            val rig = Fixtures.rig()
            val unsafe = Pose(
                root = BonePose(rotationDeg = 400f),
                bones = mapOf(
                    BoneIds.THIGH_L to BonePose(rotationDeg = -999f),
                    BoneIds.HEAD to BonePose(rotationDeg = 10f),
                ),
            )
            val clamped = BoneConstraintValidator.clampPose(unsafe, rig)
            Assert.close(BoneConstraints.ROOT.maxRotationDeg, clamped.root.rotationDeg, 1e-3f, "root clamped")
            Assert.close(
                BoneConstraints.forBone(BoneIds.THIGH_L).minRotationDeg,
                clamped.rotationOf(BoneIds.THIGH_L), 1e-3f, "thigh clamped",
            )
            Assert.close(10f, clamped.rotationOf(BoneIds.HEAD), 1e-3f, "a legal rotation is untouched")
            val legal = Pose(bones = mapOf(BoneIds.HEAD to BonePose(3f), BoneIds.THIGH_R to BonePose(-10f)))
            Assert.equals(legal, BoneConstraintValidator.clampPose(legal, rig), "clamping a legal pose is a no-op")
        },
        TestCase("constraint table is sane and mirror aware") {
            for ((boneId, constraint) in BoneConstraints.all()) {
                Assert.that(constraint.minRotationDeg <= constraint.maxRotationDeg) { "$boneId limits inverted" }
                Assert.that(abs(constraint.maxRotationDeg) <= 360f) { "$boneId limits are absurd" }
                Assert.close(0f, constraint.clamp(0f), 1e-6f, "$boneId must allow the rest pose")
            }
            val thigh = BoneConstraints.forBone(BoneIds.THIGH_L)
            Assert.equals(BoneConstraint(-60f, 125f), thigh.mirrored(), "mirroring negates and swaps limits")
            Assert.equals(thigh, thigh.mirrored().mirrored(), "mirroring twice restores limits")
            Assert.equals(BoneConstraints.ROOT, BoneConstraints.forBone(BoneIds.ROOT), "root has its own limits")
        },
        TestCase("linear interpolation is exact between two keys") {
            // UPPER_ARM_L is used because its ±165° limit does not clamp the probe values; the
            // head is capped at ±35° and would hide the interpolation maths behind the clamp.
            val clip = singleTrackClip(Easing.LINEAR, 0f, 90f)
            Assert.close(0f, clip.sample(0f).rotationOf(BoneIds.UPPER_ARM_L), 1e-4f)
            Assert.close(22.5f, clip.sample(0.25f).rotationOf(BoneIds.UPPER_ARM_L), 1e-3f)
            Assert.close(45f, clip.sample(0.5f).rotationOf(BoneIds.UPPER_ARM_L), 1e-3f)
            Assert.close(90f, clip.sample(1f).rotationOf(BoneIds.UPPER_ARM_L), 1e-3f)
        },
        TestCase("smoothstep easing bends the same segment") {
            val clip = singleTrackClip(Easing.SMOOTH, 0f, 90f)
            Assert.close(45f, clip.sample(0.5f).rotationOf(BoneIds.UPPER_ARM_L), 1e-3f, "smoothstep(0.5) = 0.5")
            val quarter = clip.sample(0.25f).rotationOf(BoneIds.UPPER_ARM_L)
            Assert.close(90f * 0.15625f, quarter, 1e-2f, "smoothstep(0.25) = 0.15625")
            Assert.that(quarter < 22.5f) { "smoothstep must start slower than linear" }
            val threeQuarters = clip.sample(0.75f).rotationOf(BoneIds.UPPER_ARM_L)
            Assert.that(threeQuarters > 67.5f) { "smoothstep must finish faster than linear" }
        },
        TestCase("easing curves are well behaved") {
            for (easing in Easing.entries) {
                Assert.close(0f, easing.apply(0f), 1e-4f, "$easing starts at 0")
                Assert.close(1f, easing.apply(1f), 1e-3f, "$easing ends at 1")
                Assert.inRange(easing.apply(0.5f), 0f, 1.2f, "$easing midpoint")
                Assert.close(easing.apply(-1f), easing.apply(0f), 1e-6f, "$easing clamps below 0")
                Assert.close(easing.apply(2f), easing.apply(1f), 1e-6f, "$easing clamps above 1")
            }
            val back = Easing.EASE_OUT_BACK
            Assert.that((0..100).any { back.apply(it / 100f) > 1f }) {
                "EASE_OUT_BACK must overshoot to create follow-through"
            }
            Assert.close(0.5f, Easing.SINUSOIDAL.apply(0.5f), 1e-4f, "sinusoidal midpoint")
            Assert.close(0f, Easing.pulse(0f), 1e-4f, "pulse starts at zero")
            Assert.close(1f, Easing.pulse(0.5f), 1e-4f, "pulse peaks in the middle")
            Assert.close(0f, Easing.pulse(1f), 1e-4f, "pulse ends at zero")
            Assert.that(abs(Easing.wobble(0f)) < 1e-4f) { "wobble starts at zero" }
            Assert.that(abs(Easing.wobble(1f)) < abs(Easing.wobble(0.2f)) + 1e-3f) { "wobble damps out" }

            val bezier = Easing.cubicBezier(0.42f, 0f, 0.58f, 1f)
            Assert.close(0f, bezier(0f), 1e-3f, "bezier starts at 0")
            Assert.close(1f, bezier(1f), 1e-3f, "bezier ends at 1")
            Assert.close(0.5f, bezier(0.5f), 2e-2f, "symmetric bezier midpoint")
        },
        TestCase("offsets and scale interpolate too") {
            val clip = AnimationClip(
                id = "offsets",
                name = "Offsets",
                durationSeconds = 1f,
                loop = false,
                rootTrack = BoneTrack(
                    BoneIds.ROOT,
                    listOf(
                        AnimationKeyframe(0f, 0f, 0f, 0f, 1f, Easing.LINEAR),
                        AnimationKeyframe(1f, 0f, 0.2f, -0.4f, 2f, Easing.LINEAR),
                    ),
                ),
                tracks = emptyMap(),
            )
            val pose = clip.sample(0.5f)
            Assert.close(0.1f, pose.root.offset.x, 1e-4f, "offset x interpolates")
            Assert.close(-0.2f, pose.root.offset.y, 1e-4f, "offset y interpolates")
            Assert.close(1.5f, pose.root.scale, 1e-4f, "scale interpolates")
        },
        TestCase("non looping clips hold their last keyframe") {
            val clip = singleTrackClip(Easing.LINEAR, 0f, 30f)
            Assert.close(30f, clip.sample(1f).rotationOf(BoneIds.UPPER_ARM_L), 1e-4f)
            Assert.close(
                30f, clip.sample(5f).rotationOf(BoneIds.UPPER_ARM_L), 1e-4f,
                "clamped beyond the end: a finished one-shot holds its last pose",
            )
            Assert.close(
                0f, clip.sample(-2f).rotationOf(BoneIds.UPPER_ARM_L), 1e-4f,
                "clamped before the start",
            )
            Assert.close(
                clip.durationSeconds, clip.sample(5f).timeSeconds, 1e-4f,
                "the reported time is clamped to the clip length",
            )
        },
        TestCase("looping clips wrap seamlessly") {
            for (clip in AnimationLibrary.ALL.filter { it.loop }) {
                val start = clip.sample(0f)
                val almostEnd = clip.sample(0.999f)
                for (boneId in clip.tracks.keys) {
                    val delta = abs(start.rotationOf(boneId) - almostEnd.rotationOf(boneId))
                    Assert.that(delta < 4f) {
                        "${clip.id}/$boneId does not loop smoothly (jump of ${delta}°)"
                    }
                }
                val rootDelta = abs(start.root.offset.y - almostEnd.root.offset.y)
                Assert.that(rootDelta < 0.01f) { "${clip.id} root does not loop smoothly ($rootDelta)" }
                // The wrap itself must not throw or produce NaN.
                val wrapped = clip.sample(1.25f)
                Assert.that(wrapped.bones.values.all { MathUtils.isFinite(it.rotationDeg) }) {
                    "${clip.id}: wrapped sample is not finite"
                }
            }
        },
        TestCase("sampling is deterministic and time based") {
            val clip = AnimationLibrary.WALK
            val a = clip.sample(0.31f)
            val b = clip.sample(0.31f)
            Assert.equals(a, b, "identical input must give identical output")
            Assert.close(0.31f * clip.durationSeconds, a.timeSeconds, 1e-4f, "pose reports its time")

            val slowed = clip.sampleAt(0.5f, speed = 0.5f)
            val direct = clip.sample((0.5f * 0.5f) / clip.durationSeconds)
            Assert.equals(direct.rotationOf(BoneIds.THIGH_L), slowed.rotationOf(BoneIds.THIGH_L), "speed scaling")
            Assert.close(2f, clip.cycleDuration(0.5f), 1e-4f, "half speed doubles the cycle")
            Assert.close(clip.durationSeconds, clip.cycleDuration(0f), 1e-4f, "zero speed falls back safely")
        },
        TestCase("walk alternates legs and swings arms against them") {
            val clip = AnimationLibrary.WALK
            val first = clip.sample(0f)
            val half = clip.sample(0.5f)
            Assert.that(first.rotationOf(BoneIds.THIGH_L) * first.rotationOf(BoneIds.THIGH_R) < 0f) {
                "legs must be in opposite phases at t=0"
            }
            // Half a cycle later the same leg is on the opposite side of the body. The authored
            // stride is deliberately asymmetric (-26° back, +22° forward) for a natural gait, so
            // this checks the phase flip rather than exact symmetry.
            Assert.that(first.rotationOf(BoneIds.THIGH_L) * half.rotationOf(BoneIds.THIGH_L) < 0f) {
                "legs must swap phase at half a cycle"
            }
            Assert.close(
                abs(first.rotationOf(BoneIds.THIGH_L)),
                abs(half.rotationOf(BoneIds.THIGH_L)),
                8f,
                "the stride stays balanced",
            )
            Assert.that(first.rotationOf(BoneIds.THIGH_L) * first.rotationOf(BoneIds.UPPER_ARM_L) < 0f) {
                "the arm opposes the leg on the same side"
            }
            Assert.that(first.rotationOf(BoneIds.SHIN_L) >= 0f) { "the knee only bends one way" }
            // Vertical bob exists (secondary motion), and it is small.
            val bob = (0..20).map { clip.sample(it / 20f).root.offset.y }
            Assert.that(bob.max() - bob.min() > 0.005f) { "walk needs a vertical bob" }
            Assert.that(bob.max() - bob.min() < 0.06f) { "the bob must stay subtle" }
        },
        TestCase("run is faster and bigger than walk") {
            val walk = AnimationLibrary.WALK
            val run = AnimationLibrary.RUN
            Assert.that(run.durationSeconds < walk.durationSeconds) { "run cycles faster" }
            fun amplitude(clip: AnimationClip, boneId: String): Float {
                val values = (0..40).map { clip.sample(it / 40f).rotationOf(boneId) }
                return values.max() - values.min()
            }
            Assert.that(
                amplitude(run, BoneIds.THIGH_L) > amplitude(walk, BoneIds.THIGH_L),
            ) { "run strides further" }
            Assert.that(
                amplitude(run, BoneIds.UPPER_ARM_L) > amplitude(walk, BoneIds.UPPER_ARM_L),
            ) { "run swings the arms more" }
            Assert.that(abs(run.sample(0.3f).root.offset.y) > 0f || run.rootTrack != null) {
                "run needs airborne body motion"
            }
        },
        TestCase("idle is subtle and stand is nearly still") {
            fun maxAbs(clip: AnimationClip, boneId: String) =
                (0..40).map { abs(clip.sample(it / 40f).rotationOf(boneId)) }.max()

            Assert.that(maxAbs(AnimationLibrary.IDLE, BoneIds.TORSO) < 3f) { "idle breathing stays tiny" }
            Assert.that(maxAbs(AnimationLibrary.IDLE, BoneIds.HEAD) < 3f) { "idle head movement stays tiny" }
            Assert.that(maxAbs(AnimationLibrary.IDLE, BoneIds.THIGH_L) < 1f) { "idle legs stay put" }

            for (boneId in BoneIds.ALL) {
                Assert.that(maxAbs(AnimationLibrary.STAND, boneId) < 0.001f) {
                    "stand must be still on $boneId"
                }
            }
            Assert.equals(BoneIds.ALL.size, AnimationLibrary.STAND.tracks.size, "stand keys every bone")
        },
        TestCase("talk cycles mouth sprites without audio") {
            val clip = AnimationLibrary.TALK
            Assert.that(clip.mouthTrack.size >= 8) { "talk needs a real mouth cycle" }
            val shapes = (0..60).map { clip.sample(it / 60f).mouth }.distinct()
            Assert.that(shapes.size >= 4) { "mouth must change shape: $shapes" }
            Assert.contains(shapes, MouthShape.CLOSED, "a talk cycle returns to closed")
            Assert.contains(shapes, MouthShape.A, "vowel A is used")
            Assert.contains(shapes, MouthShape.E, "vowel E is used")
            Assert.contains(shapes, MouthShape.O, "vowel O is used")
            // Mouth switching is a step, never a blend: consecutive samples differ only at keys.
            val early = clip.sample(0.055f).mouth
            val late = clip.sample(0.099f).mouth
            Assert.equals(early, late, "mouth shapes hold between keys")
            Assert.equals(MouthShape.A, clip.sample(0.06f).mouth, "the A key takes effect")
        },
        TestCase("side talk drives the profile face and head") {
            val clip = AnimationLibrary.SIDE_TALK
            Assert.that(clip.needsSideView) { "side talk needs profile artwork" }
            Assert.equals(ViewKind.SIDE_LEFT, clip.requiredView)
            Assert.that(clip.mouthTrack.isNotEmpty()) { "side talk cycles mouths" }
            val headMovement = (0..40).map { clip.sample(it / 40f).rotationOf(BoneIds.HEAD) }
            Assert.that(headMovement.max() - headMovement.min() > 3f) { "side talk moves the head" }
            Assert.that(headMovement.max() - headMovement.min() < 20f) { "side talk stays subtle" }
        },
        TestCase("sleep closes the eyes and wave smiles") {
            for (i in 0..10) {
                Assert.equals(Expression.CLOSED, AnimationLibrary.SLEEP.sample(i / 10f).expression, "sleep keeps eyes closed")
            }
            val wave = AnimationLibrary.WAVE
            Assert.equals(Expression.NEUTRAL, wave.sample(0f).expression, "wave starts neutral")
            Assert.equals(Expression.HAPPY, wave.sample(0.5f).expression, "wave is happy mid gesture")
            Assert.equals(MouthShape.SMILE, wave.sample(0.5f).mouth, "wave smiles")
            Assert.equals(Expression.ANGRY, AnimationLibrary.ANGRY.sample(0.5f).expression)
            Assert.equals(Expression.SAD, AnimationLibrary.SAD.sample(0.5f).expression)
            Assert.equals(Expression.HAPPY, AnimationLibrary.HAPPY.sample(0.5f).expression)
            Assert.equals(MouthShape.SURPRISED, AnimationLibrary.SURPRISED.sample(0.2f).mouth)
        },
        TestCase("idle blinks") {
            val idle = AnimationLibrary.IDLE
            Assert.equals(Expression.CLOSED, idle.sample(0.43f).expression, "eyes close mid blink")
            Assert.equals(Expression.NEUTRAL, idle.sample(0.50f).expression, "eyes reopen")
            Assert.equals(Expression.NEUTRAL, idle.sample(0f).expression)
        },
        TestCase("side clips are the only ones needing profile artwork") {
            val profileClips = AnimationLibrary.profileClips
            Assert.equals(3, profileClips.size, "side walk, side run and side talk")
            Assert.equals(
                listOf("side_run", "side_talk", "side_walk"),
                profileClips.map { it.id }.sorted(),
            )
            for (clip in AnimationLibrary.ALL.filterNot { it.needsSideView }) {
                Assert.equals(null, clip.requiredView, "${clip.id} should play in any view")
            }
        },
        TestCase("the view filter enables and disables clips correctly") {
            val noProfile = AnimationLibrary.playableIn(ViewKind.FRONT, hasProfileArtwork = false)
            Assert.that(noProfile.none { it.needsSideView }) { "profile clips need profile artwork" }
            Assert.equals(15, noProfile.size, "fifteen clips play without profile artwork")

            val frontWithProfile = AnimationLibrary.playableIn(ViewKind.FRONT, hasProfileArtwork = true)
            Assert.equals(15, frontWithProfile.size, "profile clips only appear in a profile view")

            val side = AnimationLibrary.playableIn(ViewKind.SIDE_LEFT, hasProfileArtwork = true)
            Assert.equals(18, side.size, "every clip plays in a profile view")
            Assert.contains(side.map { it.id }, "side_walk")

            val back = AnimationLibrary.playableIn(ViewKind.BACK, hasProfileArtwork = false)
            Assert.equals(15, back.size, "the back view plays the view-agnostic clips")
        },
        TestCase("clips are grouped for the editor") {
            val categories = AnimationLibrary.ALL.map { it.category }.toSet()
            Assert.contains(categories, ClipCategory.LOCOMOTION)
            Assert.contains(categories, ClipCategory.EMOTION)
            Assert.contains(categories, ClipCategory.ACTION)
            Assert.contains(categories, ClipCategory.IDLE)
            Assert.equals(
                listOf("run", "side_run", "side_walk", "walk", "walk_talk"),
                AnimationLibrary.ALL.filter { it.category == ClipCategory.LOCOMOTION }.map { it.id }.sorted(),
            )
            Assert.equals(
                listOf("angry", "happy", "sad", "surprised"),
                AnimationLibrary.ALL.filter { it.category == ClipCategory.EMOTION }.map { it.id }.sorted(),
            )
            Assert.that(AnimationLibrary.ALL.all { it.description.isNotBlank() }) {
                "every clip needs a description for the UI"
            }
        },
        TestCase("poses expose every tracked bone and nothing else") {
            val clip = AnimationLibrary.RUN
            val pose = clip.sample(0.42f)
            Assert.equals(clip.tracks.keys, pose.bones.keys, "pose covers exactly the animated bones")
            Assert.that(pose.bones.values.all { MathUtils.isFinite(it.rotationDeg) }) { "no NaN rotations" }
            Assert.equals(0f, pose.rotationOf("a_bone_that_is_not_tracked"), "untracked bones rest at zero")
            Assert.equals(BonePose.REST, pose.poseOf("a_bone_that_is_not_tracked"))
        },
        TestCase("every clip can be posed on a real rig") {
            val front = Fixtures.rig(ViewKind.FRONT)
            val side = Fixtures.rig(ViewKind.SIDE_LEFT)
            for (clip in AnimationLibrary.ALL) {
                val rig = if (clip.needsSideView) side else front
                for (i in 0..16) {
                    val pose = clip.sample(i / 16f)
                    val solution = ForwardKinematics.solve(rig, pose)
                    Assert.that(solution.draws.isNotEmpty()) { "${clip.id}: nothing to draw at $i" }
                    Assert.that(!solution.bounds.isEmpty()) { "${clip.id}: empty bounds at $i" }
                    Assert.that(
                        solution.bounds.width < 10f && solution.bounds.height < 10f,
                    ) { "${clip.id}: bounds exploded (${solution.bounds})" }
                }
            }
        },
    )

    private fun singleTrackClip(easing: Easing, from: Float, to: Float) = AnimationClip(
        id = "probe",
        name = "Probe",
        durationSeconds = 1f,
        // Non looping so t=1 samples the final key instead of wrapping back to the first.
        loop = false,
        tracks = mapOf(
            BoneIds.UPPER_ARM_L to BoneTrack(
                BoneIds.UPPER_ARM_L,
                listOf(
                    AnimationKeyframe(0f, from, easing = easing),
                    AnimationKeyframe(1f, to, easing = easing),
                ),
            ),
        ),
    )
}
