package app.kyurem39.patches.mpvex

import app.kyurem39.patches.shared.Constants.COMPATIBILITY_MPVEX
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch

private const val PLAYER_ACTIVITY = "Lapp/marlboroadvance/mpvex/ui/player/PlayerActivity;"
private const val AUDIO_FOCUS_LISTENER = "Landroid/media/AudioManager\$OnAudioFocusChangeListener;"

private object RequestAudioFocusFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    custom = { method, classDef ->
        classDef.type == PLAYER_ACTIVITY && method.name == "requestAudioFocus"
    },
)

private object FocusChangeFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I"),
    strings = listOf("Audio focus request failed"),
    custom = { _, classDef ->
        classDef.interfaces.contains(AUDIO_FOCUS_LISTENER)
    },
)

/**
 * Prevents mpvEx from requesting audio focus or interrupting audio from other applications.
 */
@Suppress("unused")
val independentAudioPatch = bytecodePatch(
    name = "Independent audio",
    description = "Prevents mpvEx from requesting audio focus or reacting to audio focus changes.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MPVEX)

    execute {
        val requestMatch = RequestAudioFocusFingerprint.match()
        requestMatch.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )

        val playerActivity = requestMatch.classDef
        val abandonMethod = playerActivity.methods.firstOrNull { method ->
            method.name == "abandonAudioFocus" &&
                method.parameterTypes.isEmpty() &&
                method.returnType == "V"
        } ?: throw PatchException("PlayerActivity has no abandonAudioFocus()V")

        abandonMethod.addInstructions(
            0,
            """
                return-void
            """,
        )

        val focusChangeMatch = FocusChangeFingerprint.match()
        focusChangeMatch.method.addInstructions(
            0,
            """
                return-void
            """,
        )
    }
}
