package com.rigstudio.core.model

/**
 * The four camera directions a character sheet can supply artwork for.
 *
 * RigStudio never *invents* a view: a side or back view only exists when the user
 * actually drew those slots. Front is the only mandatory view.
 */
enum class ViewKind(val displayName: String) {
    FRONT("Front"),
    SIDE_LEFT("Side Left"),
    SIDE_RIGHT("Side Right"),
    BACK("Back"),
}

/** What a sheet slot contains — body artwork or a facial sprite. */
enum class SlotKind { BODY, EYE, MOUTH }

/**
 * Facial expression states. Each maps to exactly one `eye_*` slot on the sheet, so an
 * expression change is a sprite swap — never a procedural deformation of the artwork.
 */
enum class Expression(val displayName: String, val eyeSlotId: String) {
    NEUTRAL("Neutral", "eye_open"),
    CLOSED("Closed", "eye_closed"),
    HAPPY("Happy", "eye_happy"),
    SAD("Sad", "eye_sad"),
    ANGRY("Angry", "eye_angry"),
    ;

    companion object {
        fun fromEyeSlotId(id: String): Expression? = entries.firstOrNull { it.eyeSlotId == id }
    }
}

/**
 * Mouth shapes for deterministic (audio-free) lip movement.
 *
 * The five vowels plus closed/smile/sad/surprised/angry are the classic animation
 * mouth chart; each maps to exactly one `mouth_*` slot on the character sheet.
 */
enum class MouthShape(val displayName: String, val slotId: String) {
    NORMAL("Normal", "mouth_normal"),
    CLOSED("Closed", "mouth_closed"),
    A("A", "mouth_A"),
    E("E", "mouth_E"),
    I("I", "mouth_I"),
    O("O", "mouth_O"),
    U("U", "mouth_U"),
    SMILE("Smile", "mouth_smile"),
    SAD("Sad", "mouth_sad"),
    SURPRISED("Surprised", "mouth_surprised"),
    ANGRY("Angry", "mouth_angry"),
    ;

    companion object {
        fun fromSlotId(id: String): MouthShape? = entries.firstOrNull { it.slotId == id }
    }
}
