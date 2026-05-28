package xyz.melodylsp.codec.host;

import android.content.Context;

import xyz.melodylsp.codec.util.MLog;

/**
 * Builds the codec block (one {@code PreferenceCategory} containing
 * {@code Quality / SampleRate / Remember_Toggle}) using only the host classpath.
 *
 * <p>The category title is the merged {@code "蓝牙音质 · {codec_name}"}; the standalone
 * "current codec" item from previous iterations is gone — having both was redundant and
 * pushed the toggle below the screen. The {@link CodecController} updates the category title
 * directly when codec status changes.
 *
 * <p>We never touch {@code androidx.preference} types through compile-time symbols because
 * the host APK is R8-minified and the concrete class names ({@code PreferenceCategory},
 * {@code PreferenceFragmentCompat}, &c.) are gone — referencing them at compile time would
 * trigger {@link NoClassDefFoundError} the moment the JVM tries to verify our methods.</p>
 */
public final class CodecBlockBuilder {

    /** OPPO ships a customised category that pulls colour / spacing from the host theme. */
    private static final String MELODY_PREFERENCE_CATEGORY =
            "com.oplus.melody.common.widget.MelodyCOUIPreferenceCategory";
    private static final String COUI_PREFERENCE_CATEGORY =
            "com.coui.appcompat.preference.COUIPreferenceCategory";
    private static final String COUI_LIST_PREFERENCE =
            "com.coui.appcompat.preference.COUIListPreference";
    private static final String COUI_SWITCH_PREFERENCE =
            "com.coui.appcompat.preference.COUISwitchPreference";

    private static final String ANDX_PREFERENCE_CATEGORY = "androidx.preference.PreferenceCategory";
    private static final String ANDX_LIST_PREFERENCE = "androidx.preference.ListPreference";
    private static final String ANDX_SWITCH_PREFERENCE_COMPAT =
            "androidx.preference.SwitchPreferenceCompat";

    private CodecBlockBuilder() {
    }

    /**
     * Insert the codec block into {@code screen} at the position {@code order}. The returned
     * {@link CodecPreferences} bag still has a {@code codecDisplay} field — kept non-null and
     * pointing at the category itself so callers can call {@code PrefRef.setTitle} on it to
     * update the merged "{@code 蓝牙音质 · LHDC}" header without touching the rest of the
     * controller.
     */
    public static CodecPreferences buildAndInsert(Context context, Object screen, int order) {
        Object category = newOf(context,
                MELODY_PREFERENCE_CATEGORY, COUI_PREFERENCE_CATEGORY, ANDX_PREFERENCE_CATEGORY);
        if (category == null) {
            MLog.e("buildAndInsert: cannot resolve PreferenceCategory");
            return null;
        }
        PrefRef.setKey(category, "melody_codec_lsp_category");
        PrefRef.setTitle(category, Strings.CODEC_BLOCK_TITLE);
        PrefRef.setOrder(category, order);
        PrefRef.addPreference(screen, category);

        Object quality = newOf(context, COUI_LIST_PREFERENCE, ANDX_LIST_PREFERENCE);
        if (quality == null) {
            MLog.w("buildAndInsert: ListPreference unavailable, skipping quality option");
        } else {
            PrefRef.setKey(quality, "melody_codec_lsp_quality");
            PrefRef.setTitle(quality, Strings.QUALITY_OPTION_TITLE);
            PrefRef.setVisible(quality, false);
            PrefRef.setIconSpaceReserved(quality, false);
            PrefRef.setPersistent(quality, false);
            PrefRef.addPreference(category, quality);
        }

        Object sampleRate = newOf(context, COUI_LIST_PREFERENCE, ANDX_LIST_PREFERENCE);
        if (sampleRate != null) {
            PrefRef.setKey(sampleRate, "melody_codec_lsp_sample_rate");
            PrefRef.setTitle(sampleRate, Strings.SAMPLE_RATE_OPTION_TITLE);
            PrefRef.setVisible(sampleRate, false);
            PrefRef.setIconSpaceReserved(sampleRate, false);
            PrefRef.setPersistent(sampleRate, false);
            PrefRef.addPreference(category, sampleRate);
        }

        Object remember = newOf(context, COUI_SWITCH_PREFERENCE, ANDX_SWITCH_PREFERENCE_COMPAT);
        if (remember != null) {
            PrefRef.setKey(remember, "melody_codec_lsp_remember");
            PrefRef.setTitle(remember, Strings.REMEMBER_TOGGLE_TITLE);
            PrefRef.setSummary(remember, Strings.REMEMBER_TOGGLE_SUMMARY);
            PrefRef.setIconSpaceReserved(remember, false);
            PrefRef.setPersistent(remember, false);
            PrefRef.addPreference(category, remember);
        }

        MLog.event("codec_block.inserted", "order", order);
        // codecDisplay is the category itself: mutating its title via PrefRef.setTitle gives us
        // the inline "蓝牙音质 · LHDC" header refresh.
        return new CodecPreferences(category, /* codecDisplay= */ category, quality, sampleRate, remember);
    }

    /**
     * Try class names in order, preferring the {@code (Context, AttributeSet)} constructor
     * which lets COUI / androidx pull default style attributes from the host theme (visible
     * effect: dark-mode tints + correct margins). Falls through to the single-arg constructor
     * when no AttributeSet ctor exists.
     */
    private static Object newOf(Context context, String... classNames) {
        ClassLoader cl = context.getClassLoader();
        for (String name : classNames) {
            Class<?> cls = PrefRef.load(cl, name);
            if (cls == null) continue;
            Object instance = PrefRef.newInstanceWithAttrs(cls, context);
            if (instance != null) return instance;
            MLog.w("newOf: " + name + " present but cannot construct");
        }
        MLog.w("newOf: none of " + java.util.Arrays.toString(classNames) + " resolvable");
        return null;
    }
}
