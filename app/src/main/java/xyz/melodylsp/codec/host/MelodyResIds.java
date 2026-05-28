package xyz.melodylsp.codec.host;

/**
 * Centralised host-side Preference key constants used to anchor injection.
 *
 * <p>Keys are plain string literals copied from the decompiled XML so they can be passed
 * directly to {@code findPreference(String)} on a Preference screen / category. R8
 * preserves these literals because the host APK itself reads them.</p>
 */
public final class MelodyResIds {

    public static final String KEY_NOISE_MENU_CATEGORY = "pref_noise_menu_category";
    public static final String KEY_MORE_SETTING_CATEGORY = "pref_more_setting_category";

    private MelodyResIds() {
    }
}
