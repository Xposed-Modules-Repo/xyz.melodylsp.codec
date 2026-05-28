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
 * <p>Visual fidelity: COUI Preferences pull their layoutResource from a styleable attribute
 * resolved against the host {@code preferenceTheme}. Programmatically calling
 * {@code new COUIPreferenceCategory(ctx)} from a non-themed context lands on the framework
 * default layout — visible as a centred grey title with no leading inset (the screenshot the
 * user reported). To dodge this we clone the {@code layoutResource} / {@code widgetLayout}
 * from a sibling Preference of the same kind that already exists on the screen, so our items
 * inherit the host's exact visual style.</p>
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
     * Insert the codec block into {@code container} at the position {@code order}.
     *
     * <p>{@code wrapInCategory} controls whether to wrap the items in a
     * {@code PreferenceCategory}. DetailMain wants the wrapper because the surrounding screen
     * paints PreferenceCategory groups as visually distinct white cards. OneSpace does not —
     * the OneSpace bottom-sheet renders extra padding above each Category which throws off
     * the layout (the user reports a too-large gap between the existing "通用设置" card and
     * the codec block); flattening the items as siblings of the existing top-level
     * Preferences lets them inherit the same visual treatment.</p>
     *
     * <p>The returned {@link CodecPreferences} bag still has a {@code codecDisplay} field —
     * for the wrapped variant it points at the category, for the flat variant it points at
     * the first non-null Preference (quality / sampleRate / remember in that order). Either
     * way {@code PrefRef.setTitle} on it will refresh the merged "{@code 蓝牙音质 · LHDC}"
     * header. The flat variant uses the first Preference's title as the header.</p>
     */
    public static CodecPreferences buildAndInsert(
            Context context, Object container, int order, boolean wrapInCategory) {
        Object styleSource = container;
        Object categoryTemplate = findFirstOfType(styleSource, "PreferenceCategory");
        Object listTemplate = findFirstOfType(styleSource, "ListPreference");
        Object switchTemplate = findFirstOfType(styleSource, "SwitchPreference");
        if (switchTemplate == null) switchTemplate = findFirstOfType(styleSource, "Preference");

        Object category = null;
        Object insertionParent = container;
        int firstChildOrder = order;
        if (wrapInCategory) {
            category = newOf(context,
                    MELODY_PREFERENCE_CATEGORY, COUI_PREFERENCE_CATEGORY, ANDX_PREFERENCE_CATEGORY);
            if (category == null) {
                MLog.e("buildAndInsert: cannot resolve PreferenceCategory");
                return null;
            }
            cloneVisualStyleFrom(category, categoryTemplate);
            PrefRef.setKey(category, "melody_codec_lsp_category");
            PrefRef.setTitle(category, Strings.CODEC_BLOCK_TITLE);
            PrefRef.setOrder(category, order);
            PrefRef.addPreference(container, category);
            insertionParent = category;
            firstChildOrder = 0; // children of a Category are positioned by their own order.
        }

        Object quality = newOf(context, COUI_LIST_PREFERENCE, ANDX_LIST_PREFERENCE);
        if (quality == null) {
            MLog.w("buildAndInsert: ListPreference unavailable, skipping quality option");
        } else {
            cloneVisualStyleFrom(quality, listTemplate);
            PrefRef.setKey(quality, "melody_codec_lsp_quality");
            PrefRef.setTitle(quality, Strings.QUALITY_OPTION_TITLE);
            PrefRef.setVisible(quality, false);
            PrefRef.setIconSpaceReserved(quality, false);
            PrefRef.setPersistent(quality, false);
            PrefRef.setOrder(quality, firstChildOrder);
            PrefRef.addPreference(insertionParent, quality);
        }

        Object sampleRate = newOf(context, COUI_LIST_PREFERENCE, ANDX_LIST_PREFERENCE);
        if (sampleRate != null) {
            cloneVisualStyleFrom(sampleRate, listTemplate);
            PrefRef.setKey(sampleRate, "melody_codec_lsp_sample_rate");
            PrefRef.setTitle(sampleRate, Strings.SAMPLE_RATE_OPTION_TITLE);
            PrefRef.setVisible(sampleRate, false);
            PrefRef.setIconSpaceReserved(sampleRate, false);
            PrefRef.setPersistent(sampleRate, false);
            PrefRef.setOrder(sampleRate, firstChildOrder + 1);
            PrefRef.addPreference(insertionParent, sampleRate);
        }

        Object remember = newOf(context, COUI_SWITCH_PREFERENCE, ANDX_SWITCH_PREFERENCE_COMPAT);
        if (remember != null) {
            cloneVisualStyleFrom(remember, switchTemplate);
            PrefRef.setKey(remember, "melody_codec_lsp_remember");
            PrefRef.setTitle(remember, Strings.REMEMBER_TOGGLE_TITLE);
            PrefRef.setSummary(remember, Strings.REMEMBER_TOGGLE_SUMMARY);
            PrefRef.setIconSpaceReserved(remember, false);
            PrefRef.setPersistent(remember, false);
            PrefRef.setOrder(remember, firstChildOrder + 2);
            PrefRef.addPreference(insertionParent, remember);
        }

        // codecDisplay holds whichever Preference the controller will mutate to refresh the
        // "蓝牙音质 · LHDC" header. With a category wrapper it's the category itself; in the
        // flat layout it's the first child.
        Object codecDisplay;
        if (category != null) {
            codecDisplay = category;
        } else if (quality != null) {
            codecDisplay = quality;
        } else if (sampleRate != null) {
            codecDisplay = sampleRate;
        } else {
            codecDisplay = remember;
        }
        MLog.event("codec_block.inserted", "order", order, "wrapped", wrapInCategory);
        return new CodecPreferences(category, codecDisplay, quality, sampleRate, remember);
    }

    /** Convenience overload retaining wrap-in-category behaviour for older callers. */
    public static CodecPreferences buildAndInsert(Context context, Object container, int order) {
        return buildAndInsert(context, container, order, /* wrapInCategory= */ true);
    }

    /** Copy {@code layoutResource} and {@code widgetLayoutResource} from {@code from} to {@code to}. */
    private static void cloneVisualStyleFrom(Object to, Object from) {
        if (to == null || from == null) return;
        int layout = PrefRef.getLayoutResource(from);
        if (layout != 0) PrefRef.setLayoutResource(to, layout);
        int widget = PrefRef.getWidgetLayoutResource(from);
        if (widget != 0) PrefRef.setWidgetLayoutResource(to, widget);
    }

    /**
     * Walk the children of {@code container} (recursively into nested PreferenceGroup
     * subclasses) looking for the first Preference whose runtime class name ends with
     * {@code suffix}. Used for visual style cloning. Returns {@code null} if none found.
     */
    private static Object findFirstOfType(Object container, String suffix) {
        if (container == null) return null;
        int count = PrefRef.getPreferenceCount(container);
        // Phase 1: shallow scan — if we find a sibling at depth 0 that is the closest visual
        // match (same hierarchy as the items we're about to insert) prefer it.
        for (int i = 0; i < count; i++) {
            Object pref = PrefRef.getPreference(container, i);
            if (pref == null) continue;
            String name = pref.getClass().getName();
            if (matchesSuffix(name, suffix)) return pref;
        }
        // Phase 2: descend into nested groups.
        for (int i = 0; i < count; i++) {
            Object pref = PrefRef.getPreference(container, i);
            if (pref == null) continue;
            // Only descend if the child is a group — otherwise getPreferenceCount is undefined.
            int childCount = PrefRef.getPreferenceCount(pref);
            if (childCount > 0) {
                Object found = findFirstOfType(pref, suffix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean matchesSuffix(String className, String suffix) {
        if (className == null) return false;
        // ClassName.endsWith covers both "androidx.preference.PreferenceCategory" and
        // OPPO's "com.coui...COUIPreferenceCategory" / "MelodyCOUIPreferenceCategory".
        if (className.endsWith("." + suffix)) return true;
        if (className.endsWith(suffix)) return true;
        // Match COUIListPreference for "ListPreference" suffix; SwitchPreferenceCompat for
        // "SwitchPreference" suffix.
        if (suffix.equals("ListPreference") && className.contains("ListPreference")) return true;
        if (suffix.equals("SwitchPreference") && className.contains("SwitchPreference")) return true;
        if (suffix.equals("PreferenceCategory") && className.contains("PreferenceCategory")) return true;
        return false;
    }

    /**
     * Try class names in order, preferring the {@code (Context, AttributeSet)} constructor
     * which lets COUI / androidx pull default style attributes from the host theme. Falls
     * through to the single-arg constructor when no AttributeSet ctor exists.
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
