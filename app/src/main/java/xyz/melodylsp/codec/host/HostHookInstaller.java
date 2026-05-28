package xyz.melodylsp.codec.host;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import xyz.melodylsp.codec.MelodyCodecLspEntry;
import xyz.melodylsp.codec.bt.BluetoothCodecReflect;
import xyz.melodylsp.codec.storage.PreferenceStore;
import xyz.melodylsp.codec.util.MLog;

/**
 * Installs all host-side hooks via the modern libxposed API. Each hook lambda is wrapped in
 * try / catch so that a single failure cannot cascade into a host crash (Requirement 9.5 /
 * Property 4 / 6).
 *
 * <p>Surface anchors:</p>
 * <ul>
 *   <li>{@code DetailMainActivity#onResume()} — top-level "Hi-Res 模式" panel. The fragment
 *       hosting the Preference list is R8-renamed; we locate it by scanning the activity's
 *       FragmentManager for a Preference-Fragment-shaped instance whose PreferenceScreen
 *       contains the {@code HiQualityAudioItem} key (the simple class name set by the host
 *       MoreSetting machinery is preserved because it is read via reflection at runtime).</li>
 *   <li>{@code OneSpaceListFragment#onViewCreated} — the {@link com.coui.appcompat.panel.COUIBottomSheetDialog}-hosted
 *       panel; class name is preserved.</li>
 *   <li>{@code HighAudioPreferenceFragment#onViewCreated} — kept for completeness even though
 *       the screen is not normally reachable on this build.</li>
 * </ul>
 *
 * <p>None of the {@code androidx.preference} types are referenced through compile-time
 * symbols. The host App ships androidx.preference but the names are minified, so referencing
 * them at compile time triggers {@link NoClassDefFoundError} at runtime. Everything goes
 * through {@link PrefRef} reflection.</p>
 */
public final class HostHookInstaller {

    private static final String CLASS_DETAIL_MAIN_ACTIVITY =
            "com.oplus.melody.ui.component.detail.DetailMainActivity";
    private static final String CLASS_HIGH_AUDIO =
            "com.oplus.melody.ui.component.detail.highaudio.HighAudioPreferenceFragment";
    private static final String CLASS_ONE_SPACE_FRAGMENT = "com.oplus.melody.onespace.d";

    /** {@code PreferenceCategory.setKey(cls.getSimpleName())} stamps this on the Hi-Res item. */
    private static final String KEY_HIRES_ITEM = "HiQualityAudioItem";

    private final MelodyCodecLspEntry module;
    private final ClassLoader classLoader;
    private final Set<Object> attachedFragments =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private CodecController controller;

    public HostHookInstaller(MelodyCodecLspEntry module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        hookApplicationOnCreate();
        hookHighAudio();
        hookDetailMain();
        hookOneSpace();
    }

    private void hookApplicationOnCreate() {
        try {
            Method onCreate = Application.class.getMethod("onCreate");
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    bootstrapController((Application) chain.getThisObject());
                } catch (Throwable t) {
                    MLog.e("bootstrapController failed", t);
                }
                return result;
            });
        } catch (Throwable t) {
            MLog.e("hookApplicationOnCreate failed", t);
        }
    }

    private synchronized void bootstrapController(Application app) {
        if (controller != null) return;

        String hostVersion = "?";
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            hostVersion = info.versionName != null ? info.versionName : "?";
        } catch (Throwable ignored) {
        }
        MLog.setHostVersion(hostVersion);

        PreferenceStore prefs = new PreferenceStore(app);
        BluetoothCodecReflect reflect = new BluetoothCodecReflect(app);
        SettingsGlobalFallback fallback = new SettingsGlobalFallback(app);
        CodecBridgeClient bridge = new CodecBridgeClient(app, reflect, fallback);
        controller = new CodecController(app, reflect, bridge, prefs);

        MLog.event("controller.ready", "version", hostVersion);
    }

    /**
     * DetailMainActivity is registered in the Manifest, so its FQN survives R8 minification.
     * Hook {@code onResume} (called every time the panel comes back to the foreground) and
     * scan the activity's fragments for the Preference-Fragment-shaped child whose
     * PreferenceScreen contains the {@code HiQualityAudioItem} category.
     */
    private void hookDetailMain() {
        Class<?> activityCls = loadHostClass(CLASS_DETAIL_MAIN_ACTIVITY);
        if (activityCls == null) {
            MLog.w("DetailMainActivity class not found");
            return;
        }
        Method onResume = findMethodIgnoringDeclared(activityCls, "onResume");
        if (onResume == null) {
            MLog.w("DetailMainActivity.onResume not found");
            return;
        }
        module.hook(onResume).intercept(chain -> {
            Object result = chain.proceed();
            Object activity = chain.getThisObject();
            scheduleDetailMainScan(activity, /* attempt= */ 0);
            return result;
        });
    }

    private void scheduleDetailMainScan(Object activity, int attempt) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (tryInjectDetailMain(activity)) return;
            } catch (Throwable t) {
                MLog.e("DetailMain scan failed", t);
                return;
            }
            // Hi-Res items are populated asynchronously off WhitelistConfig — give it up to
            // ~5 s to show up before giving up for this onResume cycle.
            if (attempt < 6) {
                scheduleDetailMainScan(activity, attempt + 1);
            } else {
                MLog.w("DetailMain anchor not found after 6 retries");
            }
        }, attempt == 0 ? 400L : 800L);
    }

    private boolean tryInjectDetailMain(Object activity) {
        if (controller == null) return false;
        if (!(activity instanceof Activity)) return false;
        List<Object> fragments = collectAllFragments((Activity) activity);
        for (Object fragment : fragments) {
            if (attachedFragments.contains(fragment)) return true;
            Object screen = PrefRef.getPreferenceScreen(fragment);
            if (screen == null) continue;
            Object hiresCategory = PrefRef.findPreference(screen, KEY_HIRES_ITEM);
            if (hiresCategory == null) continue;
            Context context = resolveContext(fragment);
            if (context == null) continue;
            // If we already injected on this PreferenceScreen, mark and stop.
            if (PrefRef.findPreference(screen, "melody_codec_lsp_category") != null) {
                attachedFragments.add(fragment);
                return true;
            }
            int hiresOrder = PrefRef.getOrder(hiresCategory);
            int targetOrder = hiresOrder + 1;
            // Push the categories below Hi-Res down by one to leave a slot.
            shiftPreferenceOrders(screen, targetOrder, +1);

            String mac = resolveMacFromActivityIntent(fragment);
            if (mac == null) {
                MLog.w("DetailMain mac unresolved; skip");
                return false;
            }
            CodecPreferences prefs = CodecBlockBuilder.buildAndInsert(context, screen, targetOrder);
            if (prefs == null) return false;
            controller.attach(mac, prefs, fragment);
            attachedFragments.add(fragment);
            MLog.event("detailmain.injected", "mac_len", mac.length(),
                    "order", targetOrder, "fragment", fragment.getClass().getName());
            return true;
        }
        return false;
    }

    /** Shifts the {@code order} of every Preference at index >= {@code threshold} by {@code delta}. */
    private static void shiftPreferenceOrders(Object screen, int threshold, int delta) {
        int count = PrefRef.getPreferenceCount(screen);
        for (int i = 0; i < count; i++) {
            Object pref = PrefRef.getPreference(screen, i);
            if (pref == null) continue;
            int order = PrefRef.getOrder(pref);
            if (order >= threshold) {
                PrefRef.setOrder(pref, order + delta);
            }
        }
    }

    /**
     * Walks the activity's FragmentManager (FragmentActivity / AppCompatActivity) and returns
     * every fragment recursively, including child fragments. Class lookups go through
     * reflection because the support library types are also minified inside the host APK.
     */
    private static List<Object> collectAllFragments(Activity activity) {
        List<Object> out = new ArrayList<>();
        try {
            Method getFm = findMethodIgnoringDeclared(activity.getClass(), "getSupportFragmentManager");
            if (getFm == null) return out;
            Object fm = getFm.invoke(activity);
            walkFragmentManager(fm, out, /* depth= */ 0);
        } catch (Throwable t) {
            MLog.w("collectAllFragments failed", t);
        }
        return out;
    }

    private static void walkFragmentManager(Object fm, List<Object> out, int depth) {
        if (fm == null || depth > 4) return;
        try {
            Method getFragments = findMethodIgnoringDeclared(fm.getClass(), "getFragments");
            if (getFragments == null) return;
            Object listObj = getFragments.invoke(fm);
            if (!(listObj instanceof Collection)) return;
            for (Object f : (Collection<?>) listObj) {
                if (f == null) continue;
                out.add(f);
                Method getChildFm =
                        findMethodIgnoringDeclared(f.getClass(), "getChildFragmentManager");
                if (getChildFm != null) {
                    try {
                        walkFragmentManager(getChildFm.invoke(f), out, depth + 1);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            MLog.w("walkFragmentManager failed", t);
        }
    }

    private void hookHighAudio() {
        Class<?> fragCls = loadHostClass(CLASS_HIGH_AUDIO);
        if (fragCls == null) {
            MLog.w("HighAudioPreferenceFragment class not found");
            return;
        }
        Method onViewCreated = findOnViewCreated(fragCls);
        if (onViewCreated == null) {
            MLog.w("HighAudioPreferenceFragment.onViewCreated(View,Bundle) not found");
            return;
        }
        module.hook(onViewCreated).intercept(chain -> {
            Object result = chain.proceed();
            try {
                insertIntoHighAudio(chain.getThisObject());
            } catch (Throwable t) {
                MLog.e("HighAudio insertion failed", t);
            }
            return result;
        });
    }

    private void hookOneSpace() {
        Class<?> fragCls = loadHostClass(CLASS_ONE_SPACE_FRAGMENT);
        if (fragCls == null) {
            MLog.w("OneSpaceListFragment class not found");
            return;
        }
        Method onViewCreated = findOnViewCreated(fragCls);
        if (onViewCreated == null) {
            MLog.w("OneSpaceListFragment.onViewCreated(View,Bundle) not found");
            return;
        }
        module.hook(onViewCreated).intercept(chain -> {
            Object result = chain.proceed();
            try {
                insertIntoOneSpace(chain.getThisObject());
            } catch (Throwable t) {
                MLog.e("OneSpace insertion failed", t);
            }
            return result;
        });
    }

    /**
     * Walks the class hierarchy and returns the first method named {@code onViewCreated} that
     * accepts {@code (View, Bundle)}. Doing so avoids depending on the exact declaring class —
     * the host fragment subclass may or may not override the method.
     */
    private Method findOnViewCreated(Class<?> startCls) {
        Class<?> viewCls;
        try {
            viewCls = Class.forName("android.view.View", false, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
        Class<?> cls = startCls;
        while (cls != null && cls != Object.class) {
            try {
                Method m = cls.getDeclaredMethod("onViewCreated", viewCls, Bundle.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    /** Walk the class hierarchy looking for a no-arg method by name. */
    private static Method findMethodIgnoringDeclared(Class<?> startCls, String name) {
        Class<?> cls = startCls;
        while (cls != null && cls != Object.class) {
            try {
                Method m = cls.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    private void insertIntoHighAudio(Object fragment) {
        if (controller == null || attachedFragments.contains(fragment)) {
            return;
        }
        Context context = resolveContext(fragment);
        if (context == null) {
            MLog.w("HighAudio insertion skipped: no context");
            return;
        }
        Object screen = PrefRef.getPreferenceScreen(fragment);
        if (screen == null) {
            MLog.w("HighAudio screen is null");
            return;
        }
        Object hires = PrefRef.findPreference(screen, MelodyResIds.KEY_HIRES_SWITCH_CATEGORY);
        int order = hires != null ? PrefRef.getOrder(hires) + 1 : PrefRef.getPreferenceCount(screen);
        String mac = resolveHighAudioMac(fragment);
        if (mac == null) {
            MLog.w("HighAudio mac unresolved; skip");
            return;
        }
        CodecPreferences prefs = CodecBlockBuilder.buildAndInsert(context, screen, order);
        if (prefs == null) return;
        controller.attach(mac, prefs, fragment);
        attachedFragments.add(fragment);
        MLog.event("highaudio.injected", "mac_len", mac.length(), "order", order);
    }

    private void insertIntoOneSpace(Object fragment) {
        if (controller == null || attachedFragments.contains(fragment)) {
            return;
        }
        Context context = resolveContext(fragment);
        if (context == null) {
            MLog.w("OneSpace insertion skipped: no context");
            return;
        }
        Object screen = PrefRef.getPreferenceScreen(fragment);
        if (screen == null) {
            MLog.w("OneSpace screen is null");
            return;
        }
        Object noiseMenu = PrefRef.findPreference(screen, MelodyResIds.KEY_NOISE_MENU_CATEGORY);
        Object moreSetting = PrefRef.findPreference(screen, MelodyResIds.KEY_MORE_SETTING_CATEGORY);

        int targetOrder;
        if (noiseMenu != null && moreSetting != null) {
            int low = Math.min(PrefRef.getOrder(noiseMenu), PrefRef.getOrder(moreSetting));
            int high = Math.max(PrefRef.getOrder(noiseMenu), PrefRef.getOrder(moreSetting));
            targetOrder = (low + high) / 2;
            if (targetOrder == low) targetOrder = low + 1;
        } else if (moreSetting != null) {
            targetOrder = Math.max(0, PrefRef.getOrder(moreSetting) - 1);
        } else {
            targetOrder = PrefRef.getPreferenceCount(screen);
        }
        if (moreSetting != null && targetOrder >= PrefRef.getOrder(moreSetting)) {
            PrefRef.setOrder(moreSetting, targetOrder + 1);
        }

        String mac = resolveOneSpaceMac(fragment);
        if (mac == null) {
            MLog.w("OneSpace mac unresolved; skip");
            return;
        }
        CodecPreferences prefs = CodecBlockBuilder.buildAndInsert(context, screen, targetOrder);
        if (prefs == null) return;
        controller.attach(mac, prefs, fragment);
        attachedFragments.add(fragment);
        MLog.event("onespace.injected", "mac_len", mac.length(), "order", targetOrder);
    }

    /** Calls {@code Fragment.requireContext()} via reflection. */
    private static Context resolveContext(Object fragment) {
        try {
            Method m = fragment.getClass().getMethod("requireContext");
            Object ctx = m.invoke(fragment);
            return ctx instanceof Context ? (Context) ctx : null;
        } catch (Throwable ignored) {
        }
        try {
            Method m = fragment.getClass().getMethod("getContext");
            Object ctx = m.invoke(fragment);
            return ctx instanceof Context ? (Context) ctx : null;
        } catch (Throwable t) {
            MLog.w("resolveContext failed", t);
            return null;
        }
    }

    /**
     * Resolves the MAC address that the OneSpace / HighAudio fragment is bound to.
     *
     * <p>The host stores the MAC in the parent Activity's Intent under
     * {@code device_mac_info} — a plain string literal that survives R8 minification. Field
     * names are R8-hashed and change between builds, so we no longer try to read them.</p>
     */
    private static String resolveMacFromActivityIntent(Object fragment) {
        try {
            Method getActivity = fragment.getClass().getMethod("getActivity");
            Object activity = getActivity.invoke(fragment);
            if (activity == null) return null;
            Method getIntent = activity.getClass().getMethod("getIntent");
            Object intent = getIntent.invoke(activity);
            if (intent == null) return null;
            Method getStringExtra = intent.getClass().getMethod("getStringExtra", String.class);
            Object value = getStringExtra.invoke(intent, "device_mac_info");
            if (value == null) return null;
            String mac = value.toString();
            return mac.isEmpty() ? null : mac;
        } catch (Throwable t) {
            MLog.w("resolveMacFromActivityIntent failed", t);
            return null;
        }
    }

    private static String resolveHighAudioMac(Object fragment) {
        // First try the parent fragment's intent (HighAudioPreferenceFragment is hosted by
        // HighAudioDetailFragment). Both share the same activity intent so the result is the
        // same as querying the fragment directly, but the parent path matches the host's own
        // MAC plumbing.
        try {
            Method getParent = fragment.getClass().getMethod("getParentFragment");
            Object parent = getParent.invoke(fragment);
            if (parent != null) {
                String mac = resolveMacFromActivityIntent(parent);
                if (mac != null) return mac;
            }
        } catch (Throwable ignored) {
        }
        return resolveMacFromActivityIntent(fragment);
    }

    private static String resolveOneSpaceMac(Object fragment) {
        return resolveMacFromActivityIntent(fragment);
    }

    private Class<?> loadHostClass(String name) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable t) {
            return null;
        }
    }
}
