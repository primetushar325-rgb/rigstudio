package com.rigstudio.core.anim

import com.rigstudio.core.geom.Vec2
import com.rigstudio.core.model.BoneIds
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape
import com.rigstudio.core.model.ViewKind
import com.rigstudio.core.rig.BoneConstraints
import com.rigstudio.core.rig.BonePose
import com.rigstudio.core.rig.Pose

/**
 * One keyframe on one bone's track.
 *
 * [time] is normalised clip time (0..1) so a clip can be retimed by the speed slider without
 * touching its data. Rotation is degrees; offsets are fractions of the character height.
 * [easing] shapes the segment that *starts* at this keyframe.
 */
data class AnimationKeyframe(
    val time: Float,
    val rotationDeg: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val easing: Easing = Easing.SMOOTH,
) {
    val offset: Vec2 get() = Vec2(offsetX, offsetY)
}

/** All keyframes of one bone inside one clip. Keys must be sorted by ascending time. */
data class BoneTrack(
    val boneId: String,
    val keys: List<AnimationKeyframe>,
) {
    init {
        require(keys.isNotEmpty()) { "Track '$boneId' has no keyframes" }
    }
}

/** A facial sprite switch at a point in time (step interpolated — sprites never blend). */
data class MouthKeyframe(val time: Float, val shape: MouthShape)

/** An expression switch at a point in time (step interpolated). */
data class ExpressionKeyframe(val time: Float, val expression: Expression)

/** What a clip is for; the editor groups the strip by this. */
enum class ClipCategory(val displayName: String) {
    LOCOMOTION("Movement"),
    EMOTION("Emotion"),
    ACTION("Action"),
    IDLE("Idle"),
}

/**
 * A predefined animation.
 *
 * Tracks are keyed by **universal bone ids** only, never by anything character specific, so the
 * whole library plays on every imported character sheet and in every view. [requiredView] marks
 * the clips that need profile artwork; when a sheet has none, the editor disables them with a
 * clear message instead of pretending a front drawing is a side view.
 */
data class AnimationClip(
    val id: String,
    val name: String,
    /** Natural length of one cycle in seconds (before the speed multiplier). */
    val durationSeconds: Float,
    val loop: Boolean,
    val tracks: Map<String, BoneTrack>,
    /** Whole-body motion (sit height, sleep orientation, jump arc). */
    val rootTrack: BoneTrack? = null,
    val mouthTrack: List<MouthKeyframe> = emptyList(),
    val expressionTrack: List<ExpressionKeyframe> = emptyList(),
    val expression: Expression = Expression.NEUTRAL,
    val mouth: MouthShape = MouthShape.CLOSED,
    val category: ClipCategory = ClipCategory.ACTION,
    /** `null` = plays in any view; otherwise the view whose artwork the clip needs. */
    val requiredView: ViewKind? = null,
    val description: String = "",
) {
    init {
        require(durationSeconds > 0f) { "Clip '$id' needs a positive duration" }
    }

    val needsSideView: Boolean get() = requiredView == ViewKind.SIDE_LEFT || requiredView == ViewKind.SIDE_RIGHT

    val isSideClip: Boolean get() = needsSideView

    /**
     * Samples the clip at normalised time [t] (0..1) and returns a fully resolved, constraint
     * clamped [Pose]. This is the one and only place animation data becomes geometry input.
     */
    fun sample(t: Float): Pose {
        val time = if (loop) wrap(t) else t.coerceIn(0f, 1f)

        val bones = HashMap<String, BonePose>(tracks.size)
        for ((boneId, track) in tracks) {
            bones[boneId] = sampleTrack(track, time).clamped(boneId)
        }

        val root = rootTrack?.let { sampleTrack(it, time).clamped(BoneIds.ROOT) } ?: BonePose.REST

        return Pose(
            timeSeconds = time * durationSeconds,
            root = root,
            bones = bones,
            expression = sampleExpression(time),
            mouth = sampleMouth(time),
        )
    }

    /** Seconds-based sampling used by the playback clock and the exporter. */
    fun sampleAt(seconds: Float, speed: Float = 1f): Pose {
        val scaled = if (speed <= 0f) 0f else seconds * speed
        return sample(scaled / durationSeconds)
    }

    fun sampleExpression(time: Float): Expression {
        if (expressionTrack.isEmpty()) return expression
        var current = expression
        for (key in expressionTrack) {
            if (key.time <= time) current = key.expression else break
        }
        return current
    }

    fun sampleMouth(time: Float): MouthShape {
        if (mouthTrack.isEmpty()) return mouth
        var current = mouth
        for (key in mouthTrack) {
            if (key.time <= time) current = key.shape else break
        }
        return current
    }

    private fun wrap(t: Float): Float {
        val wrapped = t % 1f
        return if (wrapped < 0f) wrapped + 1f else wrapped
    }

    private data class Sampled(val rotationDeg: Float, val offset: Vec2, val scale: Float) {
        fun clamped(boneId: String): BonePose {
            val constraint = BoneConstraints.forBone(boneId)
            return BonePose(
                rotationDeg = constraint.clamp(rotationDeg),
                offset = offset,
                scale = scale,
            )
        }
    }

    private fun sampleTrack(track: BoneTrack, time: Float): Sampled {
        val keys = track.keys
        if (keys.size == 1) {
            val only = keys[0]
            return Sampled(only.rotationDeg, only.offset, only.scale)
        }

        val first = keys.first()
        val last = keys.last()

        if (time <= first.time) {
            if (!loop) return Sampled(first.rotationDeg, first.offset, first.scale)
            // Wrapping segment: last key -> first key across the loop boundary.
            val span = (1f - last.time) + first.time
            if (span <= 0f) return Sampled(first.rotationDeg, first.offset, first.scale)
            val u = ((1f - last.time) + time) / span
            return blend(last, first, u, last.easing)
        }
        if (time >= last.time) {
            if (!loop) return Sampled(last.rotationDeg, last.offset, last.scale)
            val span = (1f - last.time) + first.time
            if (span <= 0f) return Sampled(last.rotationDeg, last.offset, last.scale)
            val u = (time - last.time) / span
            return blend(last, first, u, last.easing)
        }

        for (i in 0 until keys.size - 1) {
            val a = keys[i]
            val b = keys[i + 1]
            if (time >= a.time && time <= b.time) {
                val span = b.time - a.time
                val u = if (span <= 0f) 0f else (time - a.time) / span
                return blend(a, b, u, a.easing)
            }
        }
        return Sampled(last.rotationDeg, last.offset, last.scale)
    }

    private fun blend(a: AnimationKeyframe, b: AnimationKeyframe, u: Float, easing: Easing): Sampled {
        val e = easing.apply(u)
        return Sampled(
            rotationDeg = a.rotationDeg + (b.rotationDeg - a.rotationDeg) * e,
            offset = a.offset.lerp(b.offset, e),
            scale = a.scale + (b.scale - a.scale) * e,
        )
    }

    /** Loop duration in seconds at a given speed multiplier — used by the timeline and exporter. */
    fun cycleDuration(speed: Float): Float =
        if (speed <= 0f) durationSeconds else durationSeconds / speed
}
