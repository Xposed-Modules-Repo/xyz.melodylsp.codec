package xyz.melodylsp.codec.host;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import xyz.melodylsp.codec.MelodyCodecLspEntry;
import xyz.melodylsp.codec.bt.BluetoothCodecReflect;
import xyz.melodylsp.codec.storage.PreferenceStore;
import xyz.melodylsp.codec.util.MLog;

/**
 * Installs all host-side hooks via the modern libxposed API. Each hook lambda is wrapped in
 * try / catch so that a single failure cannot cascade into a host crash (Requirement 9.5 /
 * Property 4 / 6).
 *
 * <p>None of the {@code androidx.preference} types are referenced through compile-time
 * symbols. The host App ships androidx.preference but the names are minified, so referencing
 * them at compile time triggers {@link NoClassDefFoundError} at runtime. Everything goes
 * through {@link PrefRef} reflection.</p>
 */
public final class HostHookInstaller {

    private static final String CLASS_HIGH_AUDIO =
            "com.oplus.melody.ui.component.detail.highaudio.HighAudioPreferenceFragment";
    private static final String CLASS_ONE_SPACE_FRAGMENT = "com.oplus.melody.onespace.d";
    private static final String FIELD_HIGH_AUDIO_MAC = "f27613b";
    private static final String FIELD_ONE_SPACE_MAC = "f17198C";

    private final MelodyCodecLspEntry module;
    private final ClassLoader classLoader;
    private CodecController controller;

    public HostHookInstaller(MelodyCodecLspEntry module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        hookApplicationOnCreate();
        hookHighAudio();
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

    private void hookHighAudio() {
        Class<?> fragCls = loadHostClass(CLASS_HIGH_AUDIO);
        if (fragCls == null) {
            MLog.w("HighAudioPreferenceFragment class not found");
            return;
        }
        // Hook onViewCreated(View, Bundle) which runs after onCreate -> onCreatePreferences ->
        // onCreateView, so the PreferenceScreen is guaranteed to be attached. We deliberately
        // avoid hooking onCreate / onCreatePreferences because both their method names can be
        // R8-renamed inside the host APK.
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

    private void insertIntoHighAudio(Object fragment) {
        if (controller == null) {
            MLog.w("HighAudio insertion skipped: controller not ready");
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
        MLog.event("highaudio.injected", "mac_len", mac.length(), "order", order);
    }

    private void insertIntoOneSpace(Object fragment) {
        if (controller == null) {
            MLog.w("OneSpace insertion skipped: controller not ready");
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

    private static String resolveHighAudioMac(Object fragment) {
        try {
            // The host stores the MAC on the parent fragment (HighAudioDetailFragment.f27613b).
            Method getParent = fragment.getClass().getMethod("getParentFragment");
            Object parent = getParent.invoke(fragment);
            if (parent == null) return null;
            return readField(parent, FIELD_HIGH_AUDIO_MAC);
        } catch (Throwable t) {
            MLog.w("resolveHighAudioMac failed", t);
            return null;
        }
    }

    private static String resolveOneSpaceMac(Object fragment) {
        try {
            return readField(fragment, FIELD_ONE_SPACE_MAC);
        } catch (Throwable t) {
            MLog.w("resolveOneSpaceMac failed", t);
            return null;
        }
    }

    private static String readField(Object target, String fieldName) throws ReflectiveOperationException {
        Class<?> cls = target.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object value = f.get(target);
                return value != null ? value.toString() : null;
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private Class<?> loadHostClass(String name) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable t) {
            return null;
        }
    }
}
