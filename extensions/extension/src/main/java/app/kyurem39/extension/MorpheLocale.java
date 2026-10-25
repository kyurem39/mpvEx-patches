package app.kyurem39.extension;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

/**
 * Applies the Vietnamese locale to an activity, or clears a previously applied one.
 *
 * <p>{@code applyOverrideConfiguration} is used instead of replacing the base context, because the
 * override keeps the theme the activity was created with. Should it be refused (it can only be
 * called before the activity touches its resources), the resources are updated in place instead,
 * which still localizes everything that is read after this point.
 *
 * <p>This class is called from injected {@code onCreate} methods, so it is written in Java to stay
 * independent of the app's shrunk Kotlin runtime.
 */
public final class MorpheLocale {
    private static final String VIETNAMESE_LANGUAGE = "vi";

    private MorpheLocale() {}

    public static void overrideConfiguration(Activity activity) {
        MorpheHook.track(activity);

        Context baseContext = activity.getBaseContext();
        if (baseContext == null) return;

        Resources base = baseContext.getResources();
        Configuration configuration = new Configuration(base.getConfiguration());

        if (MorphePrefs.isVietnameseEnabled(activity)) {
            configuration.setLocale(MorphePrefs.VIETNAMESE);
        } else if (VIETNAMESE_LANGUAGE.equals(
            configuration.getLocales().get(0).getLanguage()
        )) {
            // The system is already Vietnamese, so there is nothing to override.
            return;
        }

        // The override is applied even when the feature is off. Recreating an activity reuses the
        // resources of its previous instance, so leaving the old override untouched would keep the
        // previous language. Applying the base configuration resets it.

        try {
            activity.applyOverrideConfiguration(configuration);
            return;
        } catch (RuntimeException refused) {
            // The activity already touched its resources; fall back to updating them in place.
        }

        try {
            activity.getResources().updateConfiguration(
                configuration, activity.getResources().getDisplayMetrics()
            );
        } catch (RuntimeException ignored) {
            // Nothing else can be done before the activity is created.
        }
    }
}
