package com.rigstudio.core.anim

import com.rigstudio.core.model.BoneIds
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape
import com.rigstudio.core.model.ViewKind

/**
 * The built-in animation library.
 *
 * Authoring conventions (keep them, they are what makes the clips interchangeable):
 *  - angles are **degrees**, positive = clockwise on screen;
 *  - `_l` / `_r` are **screen** left / right of the assembled view;
 *  - offsets are fractions of the character height (view units), applied to the bone's joint;
 *  - whole-body movement (bob, sit height, sleep orientation, jump arc) lives on the **root**
 *    track, never on the torso, so torso limits stay biomechanical;
 *  - a looping clip must end on the values it started with.
 *
 * Every clip is validated against [com.rigstudio.core.rig.BoneConstraints] by the unit tests;
 * at runtime sampled poses are clamped as well, so nothing can dislocate the puppet.
 */
object AnimationLibrary {

    // --- authoring helpers -----------------------------------------------------------------

    private fun k(
        t: Float,
        deg: Float,
        dx: Float = 0f,
        dy: Float = 0f,
        easing: Easing = Easing.SMOOTH,
    ) = AnimationKeyframe(t, deg, dx, dy, 1f, easing)

    private fun track(boneId: String, vararg keys: AnimationKeyframe) =
        boneId to BoneTrack(boneId, keys.toList())

    private fun root(vararg keys: AnimationKeyframe) = BoneTrack(BoneIds.ROOT, keys.toList())

    private fun mouths(vararg pairs: Pair<Float, MouthShape>) =
        pairs.map { (t, shape) -> MouthKeyframe(t, shape) }

    private fun expressions(vararg pairs: Pair<Float, Expression>) =
        pairs.map { (t, expression) -> ExpressionKeyframe(t, expression) }

    /** A conversational mouth cycle: closed → vowels → closed, spread over 0..1. */
    private val TALK_MOUTH = mouths(
        0.00f to MouthShape.CLOSED,
        0.05f to MouthShape.A,
        0.11f to MouthShape.E,
        0.17f to MouthShape.CLOSED,
        0.23f to MouthShape.O,
        0.29f to MouthShape.A,
        0.35f to MouthShape.I,
        0.41f to MouthShape.CLOSED,
        0.49f to MouthShape.E,
        0.55f to MouthShape.U,
        0.61f to MouthShape.A,
        0.67f to MouthShape.CLOSED,
        0.75f to MouthShape.O,
        0.81f to MouthShape.E,
        0.87f to MouthShape.A,
        0.94f to MouthShape.CLOSED,
        1.00f to MouthShape.CLOSED,
    )

    /** A slower, calmer version of the same cycle for side-profile dialogue. */
    private val SIDE_TALK_MOUTH = mouths(
        0.00f to MouthShape.CLOSED,
        0.08f to MouthShape.A,
        0.18f to MouthShape.O,
        0.28f to MouthShape.CLOSED,
        0.38f to MouthShape.E,
        0.48f to MouthShape.A,
        0.58f to MouthShape.CLOSED,
        0.70f to MouthShape.I,
        0.80f to MouthShape.O,
        0.90f to MouthShape.CLOSED,
        1.00f to MouthShape.CLOSED,
    )

    private val BLINK = expressions(
        0.00f to Expression.NEUTRAL,
        0.42f to Expression.CLOSED,
        0.46f to Expression.NEUTRAL,
        0.88f to Expression.CLOSED,
        0.92f to Expression.NEUTRAL,
    )

    // ---------------------------------------------------------------------------------------
    // 1. IDLE — quiet breathing, micro weight shift, occasional blink.
    // ---------------------------------------------------------------------------------------
    val IDLE = AnimationClip(
        id = "idle",
        name = "Idle",
        durationSeconds = 2.6f,
        loop = true,
        category = ClipCategory.IDLE,
        description = "Breathing rest state with tiny arm sway and an occasional blink.",
        rootTrack = root(
            k(0.00f, 0f, 0f, 0f),
            k(0.50f, 0f, 0f, -0.006f),
            k(1.00f, 0f, 0f, 0f),
        ),
        tracks = mapOf(
            track(BoneIds.TORSO, k(0f, 0f), k(0.5f, 1.0f), k(1f, 0f)),
            track(BoneIds.HEAD, k(0f, 0f), k(0.35f, -1.5f), k(0.75f, 1.5f), k(1f, 0f)),
            track(BoneIds.UPPER_ARM_L, k(0f, 3f), k(0.5f, 6f), k(1f, 3f)),
            track(BoneIds.FOREARM_L, k(0f, 2f), k(0.5f, 5f), k(1f, 2f)),
            track(BoneIds.HAND_L, k(0f, 0f), k(0.5f, 3f), k(1f, 0f)),
            track(BoneIds.UPPER_ARM_R, k(0f, -3f), k(0.5f, -6f), k(1f, -3f)),
            track(BoneIds.FOREARM_R, k(0f, -2f), k(0.5f, -5f), k(1f, -2f)),
            track(BoneIds.HAND_R, k(0f, 0f), k(0.5f, -3f), k(1f, 0f)),
            track(BoneIds.THIGH_L, k(0f, 0f), k(1f, 0f)),
            track(BoneIds.THIGH_R, k(0f, 0f), k(1f, 0f)),
            track(BoneIds.SHIN_L, k(0f, 1f), k(0.5f, 2f), k(1f, 1f)),
            track(BoneIds.SHIN_R, k(0f, 1f), k(0.5f, 2f), k(1f, 1f)),
        ),
        expressionTrack = BLINK,
        mouth = MouthShape.CLOSED,
    )

    // ---------------------------------------------------------------------------------------
    // 2. STAND — the neutral reset pose; almost completely still.
    // ---------------------------------------------------------------------------------------
    val STAND = AnimationClip(
        id = "stand",
        name = "Stand",
        durationSeconds = 1.4f,
        loop = true,
        category = ClipCategory.IDLE,
        description = "Neutral standing pose. Also used as the reset pose.",
        tracks = BoneIds.ALL.associateWith { boneId ->
            BoneTrack(boneId, listOf(k(0f, 0f), k(1f, 0f)))
        },
        expressionTrack = expressions(
            0.00f to Expression.NEUTRAL,
            0.70f to Expression.CLOSED,
            0.74f to Expression.NEUTRAL,
        ),
        mouth = MouthShape.CLOSED,
    )

    // ---------------------------------------------------------------------------------------
    // 3. WALK — contralateral arm swing, knee lift on the trailing leg, bob at mid-stance.
    // ---------------------------------------------------------------------------------------
    val WALK = AnimationClip(
        id = "walk",
        name = "Walk",
        durationSeconds = 1.0f,
        loop = true,
        category = ClipCategory.LOCOMOTION,
        description = "One full step cycle: alternating legs, opposite arm swing, subtle bob.",
        rootTrack = root(
            k(0.00f, 0f, 0f, 0f),
            k(0.25f, 0f, 0f, -0.012f),
            k(0.50f, 0f, 0f, 0f),
            k(0.75f, 0f, 0f, -0.012f),
            k(1.00f, 0f, 0f, 0f),
        ),
        tracks = mapOf(
            track(
                BoneIds.TORSO,
                k(0.00f, 1.5f), k(0.25f, 2.5f), k(0.50f, 1.5f), k(0.75f, 2.5f), k(1.00f, 1.5f),
            ),
            track(
                BoneIds.HEAD,
                k(0.00f, -1.5f), k(0.25f, -3f), k(0.50f, -1.5f), k(0.75f, -3f), k(1.00f, -1.5f),
            ),
            track(
                BoneIds.THIGH_L,
                k(0.00f, -26f), k(0.25f, -6f), k(0.50f, 22f), k(0.75f, 4f), k(1.00f, -26f),
            ),
            track(
                BoneIds.SHIN_L,
                k(0.00f, 8f), k(0.15f, 26f), k(0.35f, 6f), k(0.50f, 2f), k(0.70f, 42f), k(1.00f, 8f),
            ),
            track(
                BoneIds.FOOT_L,
                k(0.00f, 8f), k(0.25f, 0f), k(0.50f, -12f), k(0.75f, 6f), k(1.00f, 8f),
            ),
            track(
                BoneIds.THIGH_R,
                k(0.00f, 22f), k(0.25f, 4f), k(0.50f, -26f), k(0.75f, -6f), k(1.00f, 22f),
            ),
            track(
                BoneIds.SHIN_R,
                k(0.00f, 2f), k(0.20f, 42f), k(0.50f, 8f), k(0.65f, 26f), k(0.85f, 6f), k(1.00f, 2f),
            ),
            track(
                BoneIds.FOOT_R,
                k(0.00f, -12f), k(0.25f, 6f), k(0.50f, 8f), k(0.75f, 0f), k(1.00f, -12f),
            ),
            track(BoneIds.UPPER_ARM_L, k(0.00f, 24f), k(0.50f, -24f), k(1.00f, 24f)),
            track(BoneIds.FOREARM_L, k(0.00f, 14f), k(0.50f, 22f), k(1.00f, 14f)),
            track(BoneIds.HAND_L, k(0.00f, 4f), k(0.50f, 8f), k(1.00f, 4f)),
            track(BoneIds.UPPER_ARM_R, k(0.00f, -24f), k(0.50f, 24f), k(1.00f, -24f)),
            track(BoneIds.FOREARM_R, k(0.00f, -22f), k(0.50f, -14f), k(1.00f, -22f)),
            track(BoneIds.HAND_R, k(0.00f, -4f), k(0.50f, -8f), k(1.00f, -4f)),
        ),
        mouth = MouthShape.CLOSED,
    )

    // ---------------------------------------------------------------------------------------
    // 4. RUN — faster cycle, forward lean, real airborne phase, big arm swing.
    // ---------------------------------------------------------------------------------------
    val RUN = AnimationClip(
        id = "run",
        name = "Run",
        durationSeconds = 0.62f,
        loop = true,
        category = ClipCategory.LOCOMOTION,
        description = "Fast cycle with forward lean, airborne bob and heavy arm swing.",
        rootTrack = root(
            k(0.00f, 0f, 0f, -0.010f),
            k(0.15f, 0f, 0f, 0.012f),
            k(0.35f, 0f, 0f, -0.030f),
            k(0.50f, 0f, 0f, -0.010f),
            k(0.65f, 0f, 0f, 0.012f),
            k(0.85f, 0f, 0f, -0.030f),
            k(1.00f, 0f, 0f, -0.010f),
        ),
        tracks = mapOf(
            track(BoneIds.TORSO, k(0f, 8f), k(0.5f, 9f), k(1f, 8f)),
            track(BoneIds.HEAD, k(0.00f, -8f), k(0.50f, -10f), k(1.00f, -8f)),
            track(
                BoneIds.THIGH_L,
                k(0.00f, -48f), k(0.25f, -8f), k(0.50f, 38f), k(0.75f, 6f), k(1.00f, -48f),
            ),
            track(
                BoneIds.SHIN_L,
                k(0.00f, 26f), k(0.18f, 70f), k(0.40f, 8f), k(0.55f, 4f), k(0.78f, 96f), k(1.00f, 26f),
            ),
            track(BoneIds.FOOT_L, k(0.00f, 12f), k(0.50f, -18f), k(1.00f, 12f)),
            track(
                BoneIds.THIGH_R,
                k(0.00f, 38f), k(0.25f, 6f), k(0.50f, -48f), k(0.75f, -8f), k(1.00f, 38f),
            ),
            track(
                BoneIds.SHIN_R,
                k(0.00f, 4f), k(0.28f, 96f), k(0.50f, 26f), k(0.68f, 70f), k(0.90f, 8f), k(1.00f, 4f),
            ),
            track(BoneIds.FOOT_R, k(0.00f, -18f), k(0.50f, 12f), k(1.00f, -18f)),
            track(BoneIds.UPPER_ARM_L, k(0.00f, 52f), k(0.50f, -46f), k(1.00f, 52f)),
            track(BoneIds.FOREARM_L, k(0.00f, 62f), k(0.50f, 78f), k(1.00f, 62f)),
            track(BoneIds.HAND_L, k(0.00f, 10f), k(0.50f, 16f), k(1.00f, 10f)),
            track(BoneIds.UPPER_ARM_R, k(0.00f, -46f), k(0.50f, 52f), k(1.00f, -46f)),
            track(BoneIds.FOREARM_R, k(0.00f, -78f), k(0.50f, -62f), k(1.00f, -78f)),
            track(BoneIds.HAND_R, k(0.00f, -16f), k(0.50f, -10f), k(1.00f, -16f)),
        ),
        mouth = MouthShape.CLOSED,
    )

    // ---------------------------------------------------------------------------------------
    // 5. TALK — conversational head movement, small hand gestures, deterministic mouth cycle.
    // ---------------------------------------------------------------------------------------
    val TALK = AnimationClip(
        id = "talk",
        name = "Talk",
        durationSeconds = 2.2f,
        loop = true,
        category = ClipCategory.ACTION,
        description = "Head movement and small conversational gestures with mouth cycling.",
        tracks = mapOf(
            track(
                BoneIds.TORSO,
                k(0.00f, 0f), k(0.30f, 1.5f), k(0.60f, -1.5f), k(1.00f, 0f),
            ),
            track(
                BoneIds.HEAD,
                k(0.00f, 0f), k(0.12f, -6f), k(0.26f, 3f), k(0.40f, -5f),
                k(0.55f, 4f), k(0.70f, -6f), k(0.85f, 2f), k(1.00f, 0f),
            ),
            // Negative on the left / positive on the right folds the arms in towards the body,
            // which is what conversational gestures look like head-on.
            track(
                BoneIds.UPPER_ARM_L,
                k(0.00f, -5f), k(0.25f, -13f), k(0.45f, -7f), k(0.70f, -15f), k(1.00f, -5f),
            ),
            track(
                BoneIds.FOREARM_L,
                k(0.00f, -22f), k(0.25f, -42f), k(0.45f, -28f), k(0.70f, -46f), k(1.00f, -22f),
            ),
            track(BoneIds.HAND_L, k(0.00f, 0f), k(0.30f, -14f), k(0.60f, 10f), k(1.00f, 0f)),
            track(
                BoneIds.UPPER_ARM_R,
                k(0.00f, 5f), k(0.30f, 14f), k(0.55f, 8f), k(0.80f, 12f), k(1.00f, 5f),
            ),
            track(
                BoneIds.FOREARM_R,
                k(0.00f, 20f), k(0.30f, 44f), k(0.55f, 26f), k(0.80f, 40f), k(1.00f, 20f),
            ),
            track(BoneIds.HAND_R, k(0.00f, 0f), k(0.35f, 14f), k(0.65f, -10f), k(1.00f, 0f)),
            track(BoneIds.THIGH_L, k(0f, 0f), k(1f, 0f)),
            track(BoneIds.THIGH_R, k(0f, 0f), k(1f, 0f)),
        ),
        mouthTrack = TALK_MOUTH,
        mouth = MouthShape.CLOSED,
    )

    // ---------------------------------------------------------------------------------------
    // 6. WAVE — screen-right arm raised, hand oscillates, weight shifts to the other leg.
    // ---------------------------------------------------------------------------------------
    val WAVE = AnimationClip(
        id = "wave",
        name = "Wave",
        durationSeconds = 1.8f,
        loop = true,
        category = ClipCategory.ACTION,
        description = "Raises the right arm and waves, with a small weight shift.",
        rootTrack = root(
            k(0.00f, 0f, 0f, 0f),
            k(0.30f, 0f, 0f, -0.004f),
            k(0.80f, 0f, 0f, -0.004f),
            k(1.00f, 0f, 0f, 0f),
        ),
        tracks = mapOf(
            track(BoneIds.TORSO, k(0.00f, 0f), k(0.30f, -2f), k(0.80f, -2f), k(1.00f, 0f)),
            track(BoneIds.HEAD, k(0.00f, 0f), k(0.25f, 5f), k(0.75f, 5f), k(1.00f, 0f)),
            track(
                BoneIds.UPPER_ARM_R,
                k(0.00f, 0f), k(0.18f, -125f, easing = Easing.EASE_OUT_BACK),
                k(0.85f, -125f), k(1.00f, 0f),
            ),
            track(
                BoneIds.FOREARM_R,
                k(0.00f, 0f), k(0.20f, -35f), k(0.34f, 18f), k(0.48f, -35f),
                k(0.62f, 18f), k(0.76f, -35f), k(0.88f, -10f), k(1.00f, 0f),
            ),
            track(
                BoneIds.HAND_R,
                k(0.00f, 0f), k(0.22f, -18f), k(0.36f, 16f), k(0.50f, -18f),
                k(0.64f, 16f), k(0.78f, -18f), k(1.00f, 0f),
            ),
            track(BoneIds.UPPER_ARM_L, k(0.00f, 0f), k(0.50f, 6f), k(1.00f, 0f)),
            track(BoneIds.FOREARM_L, k(0.00f, 0f), k(0.50f, 8f), k(1.00f, 0f)),
            track(BoneIds.THIGH_R, k(0.00f, 0f), k(0.30f, -3f), k(0.80f, -3f), k(1.00f, 0f)),
            track(BoneIds.THIGH_L, k(0.00f, 0f), k(0.30f, 2f), k(0.80f, 2f), k(1.00f, 0f)),
        ),
        expressionTrack = expressions(
            0.00f to Expression.NEUTRAL,
            0.16f to Expression.HAPPY,
            0.90f to Expression.NEUTRAL,
        ),
        mouthTrack = mouths(
            0.00f to MouthShape.CLOSED,
            0.16f to MouthShape.SMILE,
            0.90f to MouthShape.CLOSED,
        ),
    )

    // ---------------------------------------------------------------------------------------
    // 7. SIT — settle onto an invisible chair, then breathe there.
    // ---------------------------------------------------------------------------------------
    val SIT = AnimationClip(
        id = "sit",
        name = "Sit",
        durationSeconds = 3.0f,
        loop = true,
        category = ClipCategory.ACTION,
        description = "Hips drop, thighs and knees bend to a seated pose, then settle.",
        rootTrack = root(
            k(0.00f, 0f, 0f, 0.150f, easing = Easing.EASE_OUT),
            k(0.50f, 0f, 0f, 0.156f),
            k(1.00f, 0f, 0f, 0.150f),
        ),
        tracks = mapOf(
            track(BoneIds.TORSO, k(0.00f, 0f), k(0.50f, 1.5f), k(1.00f, 0f)),
            track(BoneIds.HEAD, k(0.00f, 0f), k(0.50f, -2f), k(1.00f, 0f)),
            track(BoneIds.THIGH_L, k(0f, -82f, easing = Easing.EASE_OUT), k(1f, -82f)),
            track(BoneIds.SHIN_L, k(0f, 80f, easing = Easing.EASE_OUT), k(1f, 80f)),
            track(BoneIds.FOOT_L, k(0f, 4f), k(1f, 4f)),
            track(BoneIds.THIGH_R, k(0f, -78f, easing = Easing.EASE_OUT), k(1f, -78f)),
            track(BoneIds.SHIN_R, k(0f, 76f, easing = Easing.EASE_OUT), k(1f, 76f)),
            track(BoneIds.FOOT_R, k(0f, 4f), k(1f, 4f)),
            track(BoneIds.UPPER_ARM_L, k(0.00f, -20f), k(0.50f, -23f), k(1.00f, -20f)),
            track(BoneIds.FOREARM_L, k(0.00f, -44f), k(0.50f, -41f), k(1.00f, -44f)),
            track(BoneIds.UPPER_ARM_R, k(0.00f, 18f), k(0.50f, 21f), k(1.00f, 18f)),
            track(BoneIds.FOREARM_R, k(0.00f, 48f), k(0.50f, 45f), k(1.00f, 48f)),
        ),
        expressionTrack = expressions(0.00f to Expression.NEUTRAL),
        mouth = MouthShape.CLOSED,
    )

    // ---------------------------------------------------------------------------------------
    // 8. SLEEP — the whole rig is laid down by the ROOT, then breathes slowly.
    //    (Root motion, not a torso rotation: the spine stays inside its limits.)
    // ---------------------------------------------------------------------------------------
    val SLEEP = AnimationClip(
        id = "sleep",
        name = "Sleep",
        durationSeconds = 4.0f,
        loop = true,
        category = ClipCategory.ACTION,
        description = "Lies the character down and slows everything to a sleeping rhythm.",
        rootTrack = root(
            k(0.00f, -90f, 0.030f, 0.300f, easing = Easing.EASE_IN_OUT_CUBIC),
            k(0.50f, -88.5f, 0.030f, 0.306f),
            k(1.00f, -90f, 0.030f, 0.300f),
        ),
        tracks = mapOf(
            track(BoneIds.TORSO, k(0.00f, 0f), k(0.50f, 1.5f), k(1.00f, 0f)),
            track(BoneIds.HEAD, k(0.00f, 8f), k(0.50f, 11f), k(1.00f, 8f)),
            track(BoneIds.THIGH_L, k(0f, 18f), k(1f, 18f)),
            track(BoneIds.SHIN_L, k(0f, -30f), k(1f, -30f)),
            track(BoneIds.FOOT_L, k(0f, 6f), k(1f, 6f)),
            track(BoneIds.THIGH_R, k(0f, 10f), k(1f, 10f)),
            track(BoneIds.SHIN_R, k(0f, -18f), k(1f, -18f)),
            track(BoneIds.FOOT_R, k(0f, 6f), k(1f, 6f)),
            track(BoneIds.UPPER_ARM_L, k(0.00f, -26f), k(0.50f, -23f), k(1.00f, -26f)),
            track(BoneIds.FOREARM_L, k(0.00f, -22f), k(0.50f, -18f), k(1.00f, -22f)),
            track(BoneIds.UPPER_ARM_R, k(0.00f, 24f), k(0.50f, 27f), k(1.00f, 24f)),
            track(BoneIds.FOREARM_R, k(0.00f, 18f), k(0.50f, 22f), k(1.00f, 18f)),
        ),
        expressionTrack = expressions(0.00f to Expression.CLOSED),
        expression = Expression.CLOSED,
        mouth = MouthShape.CLOSED,
    )

    // ---------------------------------------------------------------------------------------
    // 9. JUMP — crouch, launch, airborne tuck, landing, recovery.
    // ---------------------------------------------------------------------------------------
    val JUMP = AnimationClip(
        id = "jump",
        name = "Jump",
        durationSeconds = 1.2f,
        loop = true,
        category = ClipCategory.ACTION,
        description = "Crouch, launch, airborne pose, landing and recovery.",
        rootTrack = root(
            k(0.00f, 0f, 0f, 0f),
            k(0.18f, 0f, 0f, 0.060f, easing = Easing.EASE_IN),
            k(0.34f, 0f, 0f, -0.150f, easing = Easing.EASE_OUT),
            k(0.52f, 0f, 0f, -0.210f),
            k(0.72f, 0f, 0f, -0.060f, easing = Easing.EASE_IN),
            k(0.85f, 0f, 0f, 0.070f, easing = Easing.EASE_OUT),
            k(1.00f, 0f, 0f, 0f),
        ),
        tracks = mapOf(
            track(
                BoneIds.TORSO,
                k(0.00f, 0f), k(0.18f, 10f), k(0.34f, -6f), k(0.52f, 0f),
                k(0.72f, 6f), k(0.85f, 12f), k(1.00f, 0f),
            ),
            track(
                BoneIds.THIGH_L,
                k(0.00f, 0f), k(0.18f, -70f), k(0.34f, -10f), k(0.52f, -34f),
                k(0.85f, -70f), k(1.00f, 0f),
            ),
            track(
                BoneIds.SHIN_L,
                k(0.00f, 0f), k(0.18f, 74f), k(0.34f, 8f), k(0.52f, 46f),
                k(0.85f, 74f), k(1.00f, 0f),
            ),
            track(
                BoneIds.THIGH_R,
                k(0.00f, 0f), k(0.18f, -66f), k(0.34f, -8f), k(0.52f, -28f),
                k(0.85f, -66f), k(1.00f, 0f),
            ),
            track(
                BoneIds.SHIN_R,
                k(0.00f, 0f), k(0.18f, 70f), k(0.34f, 6f), k(0.52f, 40f),
                k(0.85f, 70f), k(1.00f, 0f),
            ),
            track(BoneIds.FOOT_L, k(0.00f, 0f), k(0.34f, -18f), k(0.85f, 12f), k(1.00f, 0f)),
            track(BoneIds.FOOT_R, k(0.00f, 0f), k(0.34f, -18f), k(0.85f, 12f), k(1.00f, 0f)),
            track(
                BoneIds.UPPER_ARM_L,
                k(0.00f, 0f), k(0.18f, 40f), k(0.34f, -120f, easing = Easing.EASE_OUT),
                k(0.52f, -140f), k(0.85f, 40f), k(1.00f, 0f),
            ),
            track(
                BoneIds.UPPER_ARM_R,
                k(0.00f, 0f), k(0.18f, -40f), k(0.34f, 120f, easing = Easing.EASE_OUT),
                k(0.52f, 140f), k(0.85f, -40f), k(1.00f, 0f),
            ),
            track(BoneIds.FOREARM_L, k(0.00f, 0f), k(0.34f, 30f), k(0.52f, 40f), k(1.00f, 0f)),
            track(BoneIds.FOREARM_R, k(0.00f, 0f), k(0.34f, -30f), k(0.52f, -40f), k(1.00f, 0f)),
            track(BoneIds.HEAD, k(0.00f, 0f), k(0.34f, -8f), k(0.85f, 8f), k(1.00f, 0f)),
        ),
        mouthTrack = mouths(
            0.00f to MouthShape.CLOSED,
            0.30f to MouthShape.SURPRISED,
            0.78f to MouthShape.CLOSED,
            1.00f to MouthShape.CLOSED,
        ),
    )

    // ---------------------------------------------------------------------------------------
    // 10. WALK + TALK — locomotion and dialogue at the same time.
    // ---------------------------------------------------------------------------------------
    val WALK_TALK = AnimationClip(
        id = "walk_talk",
        name = "Walk + Talk",
        durationSeconds = 1.1f,
        loop = true,
        category = ClipCategory.LOCOMOTION,
        description = "Walking cycle with conversational head movement and mouth cycling.",
        rootTrack = root(
            k(0.00f, 0f, 0f, 0f),
            k(0.25f, 0f, 0f, -0.010f),
            k(0.50f, 0f, 0f, 0f),
            k(0.75f, 0f, 0f, -0.010f),
            k(1.00f, 0f, 0f, 0f),
        ),
        tracks = mapOf(
            track(BoneIds.TORSO, k(0.00f, 1.5f), k(0.30f, 2.5f), k(0.60f, 1.0f), k(1.00f, 1.5f)),
            track(
                BoneIds.HEAD,
                k(0.00f, -1.5f), k(0.15f, -5f), k(0.35f, 2f), k(0.55f, -4f),
                k(0.75f, 1f), k(1.00f, -1.5f),
            ),
            track(
                BoneIds.THIGH_L,
                k(0.00f, -24f), k(0.25f, -5f), k(0.50f, 20f), k(0.75f, 3f), k(1.00f, -24f),
            ),
            track(
                BoneIds.SHIN_L,
                k(0.00f, 8f), k(0.15f, 24f), k(0.35f, 6f), k(0.50f, 2f), k(0.70f, 40f), k(1.00f, 8f),
            ),
            track(BoneIds.FOOT_L, k(0.00f, 8f), k(0.25f, 0f), k(0.50f, -12f), k(1.00f, 8f)),
            track(
                BoneIds.THIGH_R,
                k(0.00f, 20f), k(0.25f, 3f), k(0.50f, -24f), k(0.75f, -5f), k(1.00f, 20f),
            ),
            track(
                BoneIds.SHIN_R,
                k(0.00f, 2f), k(0.20f, 40f), k(0.50f, 8f), k(0.65f, 24f), k(0.85f, 6f), k(1.00f, 2f),
            ),
            track(BoneIds.FOOT_R, k(0.00f, -12f), k(0.25f, 6f), k(0.50f, 8f), k(1.00f, -12f)),
            // Talking arms: smaller swing than a plain walk, elbows folded in.
            track(BoneIds.UPPER_ARM_L, k(0.00f, 10f), k(0.30f, -12f), k(0.60f, -6f), k(1.00f, 10f)),
            track(BoneIds.FOREARM_L, k(0.00f, -26f), k(0.30f, -40f), k(0.60f, -30f), k(1.00f, -26f)),
            track(BoneIds.HAND_L, k(0.00f, 0f), k(0.35f, -12f), k(0.70f, 8f), k(1.00f, 0f)),
            track(BoneIds.UPPER_ARM_R, k(0.00f, -10f), k(0.35f, 12f), k(0.65f, 6f), k(1.00f, -10f)),
            track(BoneIds.FOREARM_R, k(0.00f, 26f), k(0.35f, 42f), k(0.65f, 30f), k(1.00f, 26f)),
            track(BoneIds.HAND_R, k(0.00f, 0f), k(0.40f, 12f), k(0.75f, -8f), k(1.00f, 0f)),
        ),
        mouthTrack = TALK_MOUTH,
        mouth = MouthShape.CLOSED,
    )

    // ---------------------------------------------------------------------------------------
    // 11. SIDE WALK — a genuine profile cycle, driven by profile artwork.
    // ---------------------------------------------------------------------------------------
    val SIDE_WALK = AnimationClip(
        id = "side_walk",
        name = "Side Walk",
        durationSeconds = 0.9f,
        loop = true,
        category = ClipCategory.LOCOMOTION,
        requiredView = ViewKind.SIDE_LEFT,
        description = "Profile walk cycle. Needs side-view artwork in the character sheet.",
        rootTrack = root(
            k(0.00f, 0f, 0f, 0f),
            k(0.25f, 0f, 0f, -0.012f),
            k(0.50f, 0f, 0f, 0f),
            k(0.75f, 0f, 0f, -0.012f),
            k(1.00f, 0f, 0f, 0f),
        ),
        tracks = mapOf(
            track(BoneIds.TORSO, k(0.00f, 4f), k(0.50f, 6f), k(1.00f, 4f)),
            track(BoneIds.HEAD, k(0.00f, -2f), k(0.50f, -4f), k(1.00f, -2f)),
            track(
                BoneIds.THIGH_R,
                k(0.00f, -34f), k(0.25f, -5f), k(0.50f, 30f), k(0.75f, -8f), k(1.00f, -34f),
            ),
            track(
                BoneIds.SHIN_R,
                k(0.00f, 20f), k(0.25f, 5f), k(0.50f, 10f), k(0.70f, 60f), k(0.90f, 26f), k(1.00f, 20f),
            ),
            track(BoneIds.FOOT_R, k(0.00f, -10f), k(0.30f, 6f), k(0.60f, -6f), k(1.00f, -10f)),
            track(
                BoneIds.THIGH_L,
                k(0.00f, 30f), k(0.25f, -8f), k(0.50f, -34f), k(0.75f, -5f), k(1.00f, 30f),
            ),
            track(
                BoneIds.SHIN_L,
                k(0.00f, 10f), k(0.20f, 60f), k(0.45f, 20f), k(0.70f, 5f), k(0.85f, 45f), k(1.00f, 10f),
            ),
            track(BoneIds.FOOT_L, k(0.00f, 6f), k(0.35f, -10f), k(0.65f, 4f), k(1.00f, 6f)),
            track(BoneIds.UPPER_ARM_R, k(0.00f, 26f), k(0.50f, -26f), k(1.00f, 26f)),
            track(BoneIds.FOREARM_R, k(0.00f, 30f), k(0.50f, 18f), k(1.00f, 30f)),
            track(BoneIds.HAND_R, k(0.00f, 4f), k(0.50f, -2f), k(1.00f, 4f)),
            track(BoneIds.UPPER_ARM_L, k(0.00f, -26f), k(0.50f, 26f), k(1.00f, -26f)),
            track(BoneIds.FOREARM_L, k(0.00f, 18f), k(0.50f, 30f), k(1.00f, 18f)),
            track(BoneIds.HAND_L, k(0.00f, -2f), k(0.50f, 4f), k(1.00f, -2f)),
        ),
        mouth = MouthShape.CLOSED,
    )

    // ---------------------------------------------------------------------------------------
    // 12. SIDE RUN — faster profile cycle with a longer stride and an airborne phase.
    // ---------------------------------------------------------------------------------------
    val SIDE_RUN = AnimationClip(
        id = "side_run",
        name = "Side Run",
        durationSeconds = 0.55f,
        loop = true,
        category = ClipCategory.LOCOMOTION,
        requiredView = ViewKind.SIDE_LEFT,
        description = "Profile run: longer stride, higher knee lift, airborne phase.",
        rootTrack = root(
            k(0.00f, 0f, 0f, -0.008f),
            k(0.18f, 0f, 0f, 0.014f),
            k(0.38f, 0f, 0f, -0.028f),
            k(0.50f, 0f, 0f, -0.008f),
            k(0.68f, 0f, 0f, 0.014f),
            k(0.88f, 0f, 0f, -0.028f),
            k(1.00f, 0f, 0f, -0.008f),
        ),
        tracks = mapOf(
            track(BoneIds.TORSO, k(0.00f, 10f), k(0.50f, 12f), k(1.00f, 10f)),
            track(BoneIds.HEAD, k(0.00f, -8f), k(0.50f, -10f), k(1.00f, -8f)),
            track(
                BoneIds.THIGH_R,
                k(0.00f, -55f), k(0.25f, -8f), k(0.50f, 42f), k(0.75f, -4f), k(1.00f, -55f),
            ),
            track(
                BoneIds.SHIN_R,
                k(0.00f, 30f), k(0.22f, 8f), k(0.45f, 20f), k(0.68f, 100f), k(0.88f, 45f), k(1.00f, 30f),
            ),
            track(BoneIds.FOOT_R, k(0.00f, -14f), k(0.35f, 10f), k(0.65f, -10f), k(1.00f, -14f)),
            track(
                BoneIds.THIGH_L,
                k(0.00f, 42f), k(0.25f, -4f), k(0.50f, -55f), k(0.75f, -8f), k(1.00f, 42f),
            ),
            track(
                BoneIds.SHIN_L,
                k(0.00f, 20f), k(0.18f, 100f), k(0.42f, 30f), k(0.62f, 8f), k(0.85f, 45f), k(1.00f, 20f),
            ),
            track(BoneIds.FOOT_L, k(0.00f, 10f), k(0.30f, -14f), k(0.70f, 8f), k(1.00f, 10f)),
            track(BoneIds.UPPER_ARM_R, k(0.00f, 48f), k(0.50f, -44f), k(1.00f, 48f)),
            track(BoneIds.FOREARM_R, k(0.00f, 60f), k(0.50f, 40f), k(1.00f, 60f)),
            track(BoneIds.UPPER_ARM_L, k(0.00f, -44f), k(0.50f, 48f), k(1.00f, -44f)),
            track(BoneIds.FOREARM_L, k(0.00f, 40f), k(0.50f, 60f), k(1.00f, 40f)),
        ),
        mouth = MouthShape.CLOSED,
    )

    // ---------------------------------------------------------------------------------------
    // 13. SIDE TALK — profile dialogue: subtle head movement plus mouth sprite switching.
    // ---------------------------------------------------------------------------------------
    val SIDE_TALK = AnimationClip(
        id = "side_talk",
        name = "Side Talk",
        durationSeconds = 2.4f,
        loop = true,
        category = ClipCategory.ACTION,
        requiredView = ViewKind.SIDE_LEFT,
        description = "Profile conversation. Uses side facial sprites when the sheet has them.",
        tracks = mapOf(
            track(BoneIds.TORSO, k(0.00f, 2f), k(0.35f, 3.5f), k(0.70f, 1.5f), k(1.00f, 2f)),
            track(
                BoneIds.HEAD,
                k(0.00f, -2f), k(0.14f, -7f), k(0.30f, 0f), k(0.46f, -6f),
                k(0.62f, 1f), k(0.78f, -5f), k(1.00f, -2f),
            ),
            track(BoneIds.UPPER_ARM_R, k(0.00f, 4f), k(0.40f, -8f), k(0.75f, 2f), k(1.00f, 4f)),
            track(BoneIds.FOREARM_R, k(0.00f, 14f), k(0.40f, 30f), k(0.75f, 18f), k(1.00f, 14f)),
            track(BoneIds.HAND_R, k(0.00f, 0f), k(0.45f, -10f), k(0.80f, 6f), k(1.00f, 0f)),
            track(BoneIds.UPPER_ARM_L, k(0.00f, -3f), k(0.50f, 5f), k(1.00f, -3f)),
            track(BoneIds.FOREARM_L, k(0.00f, 10f), k(0.50f, 18f), k(1.00f, 10f)),
            track(BoneIds.THIGH_R, k(0f, 0f), k(1f, 0f)),
            track(BoneIds.THIGH_L, k(0f, 0f), k(1f, 0f)),
            track(BoneIds.SHIN_R, k(0f, 2f), k(1f, 2f)),
            track(BoneIds.SHIN_L, k(0f, 2f), k(1f, 2f)),
        ),
        mouthTrack = SIDE_TALK_MOUTH,
        mouth = MouthShape.CLOSED,
    )

    // ---------------------------------------------------------------------------------------
    // 14. LOOK BACK — torso twists, head turns further, then everything settles back.
    // ---------------------------------------------------------------------------------------
    val LOOK_BACK = AnimationClip(
        id = "look_back",
        name = "Look Back",
        durationSeconds = 2.6f,
        loop = true,
        category = ClipCategory.ACTION,
        description = "Turns to look over the shoulder and returns to front.",
        tracks = mapOf(
            track(
                BoneIds.TORSO,
                k(0.00f, 0f), k(0.22f, -14f, easing = Easing.EASE_OUT),
                k(0.62f, -14f), k(0.86f, 0f, easing = Easing.EASE_IN_OUT_CUBIC), k(1.00f, 0f),
            ),
            track(
                BoneIds.HEAD,
                k(0.00f, 0f), k(0.18f, -20f, easing = Easing.EASE_OUT),
                k(0.28f, -34f), k(0.60f, -34f),
                k(0.84f, 4f, easing = Easing.EASE_OUT_BACK), k(1.00f, 0f),
            ),
            track(BoneIds.UPPER_ARM_L, k(0.00f, 0f), k(0.25f, 12f), k(0.65f, 12f), k(1.00f, 0f)),
            track(BoneIds.FOREARM_L, k(0.00f, 0f), k(0.25f, 18f), k(0.65f, 18f), k(1.00f, 0f)),
            track(BoneIds.UPPER_ARM_R, k(0.00f, 0f), k(0.25f, -16f), k(0.65f, -16f), k(1.00f, 0f)),
            track(BoneIds.FOREARM_R, k(0.00f, 0f), k(0.25f, -22f), k(0.65f, -22f), k(1.00f, 0f)),
            track(BoneIds.THIGH_L, k(0f, 0f), k(1f, 0f)),
            track(BoneIds.THIGH_R, k(0f, 0f), k(1f, 0f)),
        ),
        expressionTrack = expressions(
            0.00f to Expression.NEUTRAL,
            0.26f to Expression.ANGRY,
            0.62f to Expression.NEUTRAL,
        ),
        mouthTrack = mouths(
            0.00f to MouthShape.CLOSED,
            0.26f to MouthShape.NORMAL,
            0.66f to MouthShape.CLOSED,
            1.00f to MouthShape.CLOSED,
        ),
    )

    // ---------------------------------------------------------------------------------------
    // 15. HAPPY — both arms up, two little hops, big smile.
    // ---------------------------------------------------------------------------------------
    val HAPPY = AnimationClip(
        id = "happy",
        name = "Happy",
        durationSeconds = 1.6f,
        loop = true,
        category = ClipCategory.EMOTION,
        description = "Arms up, two small hops and a smile.",
        rootTrack = root(
            k(0.00f, 0f, 0f, 0f),
            k(0.18f, 0f, 0f, -0.045f, easing = Easing.EASE_OUT),
            k(0.34f, 0f, 0f, 0f, easing = Easing.EASE_IN),
            k(0.56f, 0f, 0f, -0.045f, easing = Easing.EASE_OUT),
            k(0.74f, 0f, 0f, 0f, easing = Easing.EASE_IN),
            k(1.00f, 0f, 0f, 0f),
        ),
        tracks = mapOf(
            track(BoneIds.TORSO, k(0.00f, 0f), k(0.20f, -3f), k(0.58f, 3f), k(1.00f, 0f)),
            track(BoneIds.HEAD, k(0.00f, 0f), k(0.22f, -7f), k(0.60f, 7f), k(1.00f, 0f)),
            track(
                BoneIds.UPPER_ARM_L,
                k(0.00f, 8f), k(0.16f, 132f, easing = Easing.EASE_OUT_BACK),
                k(0.50f, 118f), k(0.84f, 132f), k(1.00f, 8f),
            ),
            track(
                BoneIds.UPPER_ARM_R,
                k(0.00f, -8f), k(0.16f, -132f, easing = Easing.EASE_OUT_BACK),
                k(0.50f, -118f), k(0.84f, -132f), k(1.00f, -8f),
            ),
            track(BoneIds.FOREARM_L, k(0.00f, 6f), k(0.20f, 34f), k(0.55f, 22f), k(1.00f, 6f)),
            track(BoneIds.FOREARM_R, k(0.00f, -6f), k(0.20f, -34f), k(0.55f, -22f), k(1.00f, -6f)),
            track(BoneIds.HAND_L, k(0.00f, 0f), k(0.25f, 18f), k(0.60f, -12f), k(1.00f, 0f)),
            track(BoneIds.HAND_R, k(0.00f, 0f), k(0.25f, -18f), k(0.60f, 12f), k(1.00f, 0f)),
            track(BoneIds.THIGH_L, k(0.00f, 0f), k(0.20f, -14f), k(0.58f, -10f), k(1.00f, 0f)),
            track(BoneIds.SHIN_L, k(0.00f, 2f), k(0.20f, 26f), k(0.58f, 18f), k(1.00f, 2f)),
            track(BoneIds.THIGH_R, k(0.00f, 0f), k(0.22f, -12f), k(0.60f, -14f), k(1.00f, 0f)),
            track(BoneIds.SHIN_R, k(0.00f, 2f), k(0.22f, 22f), k(0.60f, 26f), k(1.00f, 2f)),
            track(BoneIds.FOOT_L, k(0.00f, 0f), k(0.20f, 12f), k(1.00f, 0f)),
            track(BoneIds.FOOT_R, k(0.00f, 0f), k(0.22f, 12f), k(1.00f, 0f)),
        ),
        expressionTrack = expressions(0.00f to Expression.HAPPY),
        expression = Expression.HAPPY,
        mouthTrack = mouths(0.00f to MouthShape.SMILE, 1.00f to MouthShape.SMILE),
        mouth = MouthShape.SMILE,
    )

    // ---------------------------------------------------------------------------------------
    // 16. SAD — slumped torso, dropped head, arms hanging in, slow timing.
    // ---------------------------------------------------------------------------------------
    val SAD = AnimationClip(
        id = "sad",
        name = "Sad",
        durationSeconds = 3.2f,
        loop = true,
        category = ClipCategory.EMOTION,
        description = "Shoulders drop, head lowers, everything moves slowly.",
        rootTrack = root(
            k(0.00f, 0f, 0f, 0.012f, easing = Easing.EASE_IN_OUT_CUBIC),
            k(0.50f, 0f, 0f, 0.018f),
            k(1.00f, 0f, 0f, 0.012f),
        ),
        tracks = mapOf(
            track(BoneIds.TORSO, k(0.00f, 7f), k(0.50f, 9f), k(1.00f, 7f)),
            track(BoneIds.HEAD, k(0.00f, 12f), k(0.45f, 15f), k(1.00f, 12f)),
            track(BoneIds.UPPER_ARM_L, k(0.00f, 9f), k(0.50f, 12f), k(1.00f, 9f)),
            track(BoneIds.FOREARM_L, k(0.00f, 16f), k(0.50f, 20f), k(1.00f, 16f)),
            track(BoneIds.HAND_L, k(0f, 4f), k(1f, 4f)),
            track(BoneIds.UPPER_ARM_R, k(0.00f, -9f), k(0.50f, -12f), k(1.00f, -9f)),
            track(BoneIds.FOREARM_R, k(0.00f, -16f), k(0.50f, -20f), k(1.00f, -16f)),
            track(BoneIds.HAND_R, k(0f, -4f), k(1f, -4f)),
            track(BoneIds.THIGH_L, k(0.00f, 3f), k(0.50f, 4f), k(1.00f, 3f)),
            track(BoneIds.SHIN_L, k(0.00f, 6f), k(0.50f, 8f), k(1.00f, 6f)),
            track(BoneIds.THIGH_R, k(0.00f, 3f), k(0.50f, 4f), k(1.00f, 3f)),
            track(BoneIds.SHIN_R, k(0.00f, 6f), k(0.50f, 8f), k(1.00f, 6f)),
            track(BoneIds.FOOT_L, k(0f, 0f), k(1f, 0f)),
            track(BoneIds.FOOT_R, k(0f, 0f), k(1f, 0f)),
        ),
        expressionTrack = expressions(0.00f to Expression.SAD),
        expression = Expression.SAD,
        mouthTrack = mouths(0.00f to MouthShape.SAD, 1.00f to MouthShape.SAD),
        mouth = MouthShape.SAD,
    )

    // ---------------------------------------------------------------------------------------
    // 17. ANGRY — forward lean, head down, fists up, short tremor at the peak.
    // ---------------------------------------------------------------------------------------
    val ANGRY = AnimationClip(
        id = "angry",
        name = "Angry",
        durationSeconds = 1.8f,
        loop = true,
        category = ClipCategory.EMOTION,
        description = "Leans in, drops the head and shakes with a short tremor.",
        tracks = mapOf(
            track(
                BoneIds.TORSO,
                k(0.00f, 0f), k(0.18f, -8f, easing = Easing.EASE_IN),
                k(0.46f, -9f), k(0.52f, -7.5f), k(0.58f, -9.5f), k(0.64f, -7.5f),
                k(0.70f, -9f), k(0.88f, 0f, easing = Easing.EASE_OUT), k(1.00f, 0f),
            ),
            track(
                BoneIds.HEAD,
                k(0.00f, 0f), k(0.20f, -12f, easing = Easing.EASE_IN),
                k(0.50f, -10f), k(0.56f, -13f), k(0.62f, -10f), k(0.68f, -13f),
                k(0.86f, 0f), k(1.00f, 0f),
            ),
            track(BoneIds.UPPER_ARM_L, k(0.00f, 4f), k(0.22f, -22f), k(0.70f, -22f), k(1.00f, 4f)),
            track(BoneIds.FOREARM_L, k(0.00f, 6f), k(0.22f, -58f), k(0.70f, -58f), k(1.00f, 6f)),
            track(BoneIds.HAND_L, k(0.00f, 0f), k(0.24f, -22f), k(0.70f, -22f), k(1.00f, 0f)),
            track(BoneIds.UPPER_ARM_R, k(0.00f, -4f), k(0.22f, 22f), k(0.70f, 22f), k(1.00f, -4f)),
            track(BoneIds.FOREARM_R, k(0.00f, -6f), k(0.22f, 58f), k(0.70f, 58f), k(1.00f, -6f)),
            track(BoneIds.HAND_R, k(0.00f, 0f), k(0.24f, 22f), k(0.70f, 22f), k(1.00f, 0f)),
            track(BoneIds.THIGH_L, k(0.00f, 0f), k(0.22f, 6f), k(0.70f, 6f), k(1.00f, 0f)),
            track(BoneIds.SHIN_L, k(0.00f, 2f), k(0.22f, 12f), k(0.70f, 12f), k(1.00f, 2f)),
            track(BoneIds.THIGH_R, k(0.00f, 0f), k(0.22f, 6f), k(0.70f, 6f), k(1.00f, 0f)),
            track(BoneIds.SHIN_R, k(0.00f, 2f), k(0.22f, 12f), k(0.70f, 12f), k(1.00f, 2f)),
        ),
        expressionTrack = expressions(0.00f to Expression.ANGRY),
        expression = Expression.ANGRY,
        mouthTrack = mouths(0.00f to MouthShape.ANGRY, 1.00f to MouthShape.ANGRY),
        mouth = MouthShape.ANGRY,
    )

    // ---------------------------------------------------------------------------------------
    // 18. SURPRISED — snap back, arms out, then settle with an overshoot.
    // ---------------------------------------------------------------------------------------
    val SURPRISED = AnimationClip(
        id = "surprised",
        name = "Surprised",
        durationSeconds = 1.4f,
        loop = true,
        category = ClipCategory.EMOTION,
        description = "Sharp recoil with arms out, then an overshooting settle.",
        rootTrack = root(
            k(0.00f, 0f, 0f, 0f),
            k(0.12f, 0f, 0f, -0.030f, easing = Easing.EASE_OUT),
            k(0.44f, 0f, 0f, -0.010f),
            k(1.00f, 0f, 0f, 0f, easing = Easing.EASE_IN_OUT_CUBIC),
        ),
        tracks = mapOf(
            track(
                BoneIds.TORSO,
                k(0.00f, 0f), k(0.12f, -6f, easing = Easing.EASE_OUT),
                k(0.50f, -2f), k(1.00f, 0f, easing = Easing.EASE_OUT_BACK),
            ),
            track(
                BoneIds.HEAD,
                k(0.00f, 0f), k(0.10f, -12f, easing = Easing.EASE_OUT),
                k(0.46f, -6f), k(1.00f, 0f, easing = Easing.EASE_OUT_BACK),
            ),
            track(
                BoneIds.UPPER_ARM_L,
                k(0.00f, 0f), k(0.14f, 58f, easing = Easing.EASE_OUT),
                k(0.52f, 40f), k(1.00f, 0f, easing = Easing.EASE_OUT_BACK),
            ),
            track(
                BoneIds.UPPER_ARM_R,
                k(0.00f, 0f), k(0.14f, -58f, easing = Easing.EASE_OUT),
                k(0.52f, -40f), k(1.00f, 0f, easing = Easing.EASE_OUT_BACK),
            ),
            track(BoneIds.FOREARM_L, k(0.00f, 0f), k(0.16f, 26f), k(0.55f, 16f), k(1.00f, 0f)),
            track(BoneIds.FOREARM_R, k(0.00f, 0f), k(0.16f, -26f), k(0.55f, -16f), k(1.00f, 0f)),
            track(BoneIds.HAND_L, k(0.00f, 0f), k(0.18f, 14f), k(1.00f, 0f)),
            track(BoneIds.HAND_R, k(0.00f, 0f), k(0.18f, -14f), k(1.00f, 0f)),
            track(BoneIds.THIGH_L, k(0.00f, 0f), k(0.14f, -8f), k(1.00f, 0f)),
            track(BoneIds.SHIN_L, k(0.00f, 2f), k(0.14f, 14f), k(1.00f, 2f)),
            track(BoneIds.THIGH_R, k(0.00f, 0f), k(0.14f, -8f), k(1.00f, 0f)),
            track(BoneIds.SHIN_R, k(0.00f, 2f), k(0.14f, 14f), k(1.00f, 2f)),
        ),
        expressionTrack = expressions(
            0.00f to Expression.NEUTRAL,
            0.10f to Expression.ANGRY,
            0.55f to Expression.NEUTRAL,
        ),
        mouthTrack = mouths(
            0.00f to MouthShape.CLOSED,
            0.10f to MouthShape.SURPRISED,
            0.62f to MouthShape.O,
            0.86f to MouthShape.CLOSED,
            1.00f to MouthShape.CLOSED,
        ),
    )

    /** Every clip, in editor display order. */
    val ALL: List<AnimationClip> = listOf(
        IDLE, STAND, WALK, RUN, WALK_TALK,
        SIDE_WALK, SIDE_RUN, SIDE_TALK,
        TALK, WAVE, LOOK_BACK,
        HAPPY, SAD, ANGRY, SURPRISED,
        SIT, SLEEP, JUMP,
    )

    private val BY_ID: Map<String, AnimationClip> = ALL.associateBy { it.id }

    fun byId(id: String): AnimationClip? = BY_ID[id]

    fun byIdOrIdle(id: String): AnimationClip = BY_ID[id] ?: IDLE

    /** Clips that can play in [view]: view-agnostic clips plus that view's profile clips. */
    fun playableIn(view: ViewKind, hasProfileArtwork: Boolean): List<AnimationClip> = ALL.filter { clip ->
        when {
            clip.requiredView == null -> true
            !hasProfileArtwork -> false
            // Profile clips play in whichever side view the character actually has.
            clip.requiredView == ViewKind.SIDE_LEFT || clip.requiredView == ViewKind.SIDE_RIGHT ->
                view == ViewKind.SIDE_LEFT || view == ViewKind.SIDE_RIGHT
            clip.requiredView == view -> true
            else -> false
        }
    }

    /** Clips that need profile artwork; disabled with a message when the sheet has none. */
    val profileClips: List<AnimationClip> get() = ALL.filter { it.needsSideView }
}
