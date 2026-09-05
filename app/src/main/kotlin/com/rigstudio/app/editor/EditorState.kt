package com.rigstudio.app.editor

import com.rigstudio.app.render.StageBackground
import com.rigstudio.core.anim.AnimationLibrary
import com.rigstudio.core.anim.AnimationClip
import com.rigstudio.core.export.ExportSettings
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape
import com.rigstudio.core.model.ViewKind

/**
 * Everything the editor screen renders, as one immutable snapshot.
 *
 * Kept deliberately small and comparable: heavy objects (the rig, sprite bitmaps, the prepared
 * camera) live in the `StageSource` the view model publishes separately, because Compose should
 * never diff a 2048×2048 bitmap to decide whether a label changed.
 */
data class EditorState(
    val projectId: String = "",
    val characterName: String = "",
    val loaded: Boolean = false,
    val loading: Boolean = false,

    /** Views this sheet actually has artwork for. Anything else is shown disabled. */
    val views: List<ViewKind> = listOf(ViewKind.FRONT),
    val view: ViewKind = ViewKind.FRONT,
    val mirroredSideView: Boolean = false,

    /** Clips playable in [view]; side clips are only offered when profile artwork exists. */
    val clips: List<AnimationClip> = emptyList(),
    val clip: AnimationClip? = null,

    val expressions: List<Expression> = emptyList(),
    val mouthShapes: List<MouthShape> = emptyList(),
    val expressionOverride: Expression? = null,
    val mouthOverride: MouthShape? = null,

    val background: StageBackground = StageBackground.Solid(ExportSettings.DEFAULT_BACKGROUND),
    val showChecker: Boolean = true,
    val speed: Float = 1f,

    val playing: Boolean = false,
    val normalizedTime: Float = 0f,
    val looping: Boolean = true,

    /** Non-blocking findings from import, re-surfaced when the character is reopened. */
    val notes: List<String> = emptyList(),
    val message: String? = null,
) {

    val cycleSeconds: Float get() = (clip?.durationSeconds ?: 1f) / speed

    /** Wall-clock position of the playhead, for the timeline readout. */
    val timeSeconds: Float get() = normalizedTime * cycleSeconds

    val hasProfileArtwork: Boolean
        get() = ViewKind.SIDE_LEFT in views || ViewKind.SIDE_RIGHT in views

    fun isViewAvailable(view: ViewKind): Boolean = view in views

    /**
     * Every clip in the library paired with the reason it cannot play on this character, so the
     * animation strip can show disabled entries with an explanation instead of hiding them.
     */
    fun unavailableClips(): List<UnavailableClip> {
        val playable = clips.map { it.id }.toSet()
        return AnimationLibrary.ALL.filterNot { it.id in playable }
            .map { clip -> UnavailableClip(clip, unavailableReason(clip)) }
    }

    private fun unavailableReason(clip: AnimationClip): String {
        // requiredView is a core property from another module, so it cannot be smart cast.
        val required = clip.requiredView
        return when {
            clip.needsSideView && !hasProfileArtwork -> SIDE_VIEW_MISSING
            required == ViewKind.BACK && ViewKind.BACK !in views -> BACK_VIEW_MISSING
            required != null && required !in views ->
                "${required.displayName} view has no artwork in this character."
            else -> "Not available in the ${view.displayName} view."
        }
    }

    companion object {
        val DEFAULT = EditorState()

        /** Exact wording required by the spec when profile artwork is missing. */
        const val SIDE_VIEW_MISSING = "Side View Assets Not Found"

        const val BACK_VIEW_MISSING = "Back View Assets Not Found"
    }
}

/** A clip that cannot play on the current character, with the sentence to show the user. */
data class UnavailableClip(val clip: AnimationClip, val reason: String)

/**
 * A named background preset for the editor's background picker.
 *
 * The picker is the only place background colours are chosen; export reuses the same value so the
 * rendered file matches the preview.
 */
data class BackgroundPreset(val label: String, val argb: Int)

/** Background presets offered in the editor, deepest first. */
val EDITOR_BACKGROUND_PRESETS: List<BackgroundPreset> = listOf(
    BackgroundPreset("Studio dark", 0xFF12161C.toInt()),
    BackgroundPreset("Stage grey", 0xFF2A3040.toInt()),
    BackgroundPreset("Cool blue", 0xFF1B2B44.toInt()),
    BackgroundPreset("Warm sand", 0xFFE8DCC8.toInt()),
    BackgroundPreset("White", 0xFFFFFFFF.toInt()),
    BackgroundPreset("Black", 0xFF000000.toInt()),
)
