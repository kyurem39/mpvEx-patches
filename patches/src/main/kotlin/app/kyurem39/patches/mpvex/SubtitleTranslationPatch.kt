package app.kyurem39.patches.mpvex

import app.kyurem39.patches.shared.Constants.COMPATIBILITY_MPVEX
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch

private const val MORPHE_HOOK = "Lapp/kyurem39/extension/MorpheHook;"
private const val MORPHE_SUBTITLES = "Lapp/kyurem39/extension/MorpheSubtitles;"

private const val MAIN_ACTIVITY = "Lapp/marlboroadvance/mpvex/MainActivity;"
private const val SUBTITLE_OPS = "Lapp/marlboroadvance/mpvex/utils/media/SubtitleOps;"
private const val PLAYER_VIEW_MODEL = "Lapp/marlboroadvance/mpvex/ui/player/PlayerViewModel;"

/**
 * Translates the external subtitles the app loads into the language chosen in the patch sheet.
 *
 * <p>The sheet itself belongs to [vietnamesePatch], which also owns the injected extension. This
 * patch only adds the two call sites that start a translation: the automatic local-subtitle scan
 * and the manual subtitle picker. It depends on [vietnamesePatch] so the settings always exist.
 */
@Suppress("unused")
val subtitleTranslationPatch = bytecodePatch(
    name = "Subtitle translation",
    description = "Translates external subtitle files into a language you choose. The on/off " +
        "switch and the target language live in the kyurem39's patches settings panel.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MPVEX)

    dependsOn(vietnamesePatch)

    execute {
        // Marks the subtitle section of the shared settings sheet as available.
        val mainOnCreate = mutableClassDefBy(MAIN_ACTIVITY).methods.firstOrNull { method ->
            method.name == "onCreate" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].toString() == "Landroid/os/Bundle;" &&
                method.returnType == "V"
        } ?: throw PatchException("$MAIN_ACTIVITY has no onCreate(Bundle).")

        mainOnCreate.addInstructions(
            0,
            """
                invoke-static {}, $MORPHE_HOOK->enableSubtitleTranslation()V
            """,
        )

        // The automatic loader scans the video's directory. The helper repeats that scan and
        // translates the first matching subtitle, which is the one the app selects.
        val autoload = mutableClassDefBy(SUBTITLE_OPS).methods.firstOrNull { method ->
            method.name == "autoloadLocalSubtitles" &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0].toString() == "Ljava/lang/String;" &&
                method.parameterTypes[1].toString() == "Ljava/lang/String;"
        } ?: throw PatchException("$SUBTITLE_OPS has no autoloadLocalSubtitles(String, String).")

        autoload.addInstructions(
            0,
            """
                invoke-static {p0, p1}, $MORPHE_SUBTITLES->onAutoload(Ljava/lang/String;Ljava/lang/String;)V
            """,
        )

        // The manual picker adds one subtitle at a time through the view model.
        val addSubtitle = mutableClassDefBy(PLAYER_VIEW_MODEL).methods.firstOrNull { method ->
            method.name == "addSubtitle" &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0].toString() == "Landroid/net/Uri;" &&
                method.parameterTypes[1].toString() == "Z" &&
                method.parameterTypes[2].toString() == "Z"
        } ?: throw PatchException(
            "$PLAYER_VIEW_MODEL has no addSubtitle(Uri, boolean, boolean).",
        )

        addSubtitle.addInstructions(
            0,
            """
                invoke-static {p1}, $MORPHE_SUBTITLES->onSubtitleUri(Landroid/net/Uri;)V
            """,
        )
    }
}
