package xyz.melodylsp.codec.host;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Reflection-only handle to a {@code androidx.preference.Preference} (or any subclass) instance
 * living inside the host classpath. The host App ships androidx.preference but R8-minified
 * (e.g. {@code PreferenceFragmentCompat} → {@code androidx.preference.g}). Importing the
 * compile-time symbol from the module APK causes a {@link NoClassDefFoundError} at runtime
 * because the literal class name is gone — so we keep everything dynamic.
 *
 * <p>Each helper method below resolves the target {@link Method} once and caches it; failures
 * are swallowed and logged so a host APK that strips a particular setter never crashes the
 * panel.</p>
 */
public final class PrefRef {

    /** Loads {@code className} from the host classloader. Returns {@code null} on failure. */
    public static Class<?> load(ClassLoader cl, String className) {
        try {
            return Class.forName(className, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** New instance via {@code (Context)} constructor. Returns {@code null} on failure. */
    public static Object newInstance(Class<?> cls, Context context) {
        try {
            Constructor<?> ctor = cls.getConstructor(Context.class);
            return ctor.newInstance(context);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setKey(Object pref, String key) {
        invokeVoid(pref, "setKey", new Class[]{CharSequence.class}, new Object[]{key});
    }

    public static void setTitle(Object pref, CharSequence title) {
        invokeVoid(pref, "setTitle", new Class[]{CharSequence.class}, new Object[]{title});
    }

    public static void setSummary(Object pref, CharSequence summary) {
        invokeVoid(pref, "setSummary", new Class[]{CharSequence.class}, new Object[]{summary});
    }

    public static void setOrder(Object pref, int order) {
        invokeVoid(pref, "setOrder", new Class[]{int.class}, new Object[]{order});
    }

    public static void setVisible(Object pref, boolean visible) {
        invokeVoid(pref, "setVisible", new Class[]{boolean.class}, new Object[]{visible});
    }

    public static void setSelectable(Object pref, boolean selectable) {
        invokeVoid(pref, "setSelectable", new Class[]{boolean.class}, new Object[]{selectable});
    }

    public static void setIconSpaceReserved(Object pref, boolean reserved) {
        invokeVoid(pref, "setIconSpaceReserved", new Class[]{boolean.class}, new Object[]{reserved});
    }

    public static void setPersistent(Object pref, boolean persistent) {
        invokeVoid(pref, "setPersistent", new Class[]{boolean.class}, new Object[]{persistent});
    }

    public static void setEnabled(Object pref, boolean enabled) {
        invokeVoid(pref, "setEnabled", new Class[]{boolean.class}, new Object[]{enabled});
    }

    public static void setChecked(Object pref, boolean checked) {
        invokeVoid(pref, "setChecked", new Class[]{boolean.class}, new Object[]{checked});
    }

    public static int getOrder(Object pref) {
        Object r = invoke(pref, "getOrder", new Class[0], new Object[0]);
        return r instanceof Integer ? (Integer) r : -1;
    }

    public static String getKey(Object pref) {
        Object r = invoke(pref, "getKey", new Class[0], new Object[0]);
        return r != null ? r.toString() : null;
    }

    /** Calls {@code addPreference(Preference)} on the given screen / category. */
    public static void addPreference(Object container, Object pref) {
        if (container == null || pref == null) return;
        Class<?> prefCls = load(container.getClass().getClassLoader(), "androidx.preference.Preference");
        if (prefCls == null) {
            xyz.melodylsp.codec.util.MLog.w("addPreference: Preference class missing");
            return;
        }
        invokeVoid(container, "addPreference", new Class[]{prefCls}, new Object[]{pref});
    }

    /**
     * Calls {@code findPreference(CharSequence)} on a screen / fragment / category.
     *
     * <p>The host APK is R8-minified so the method has been renamed (e.g. {@code findPreference}
     * → {@code d}). We probe a small set of plausible names; the first one that returns
     * non-null wins. This is intentionally tolerant — a future host version may map the name
     * differently.</p>
     */
    public static Object findPreference(Object container, CharSequence key) {
        if (container == null) return null;
        Object r = invoke(container, "findPreference",
                new Class[]{CharSequence.class}, new Object[]{key});
        if (r != null) return r;
        // Fallback: scan for any single-arg method taking CharSequence, returning Object.
        Class<?> cls = container.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params[0] != CharSequence.class && params[0] != String.class) continue;
                if (m.getReturnType() == void.class) continue;
                if (m.getReturnType().isPrimitive()) continue;
                try {
                    m.setAccessible(true);
                    Object out = m.invoke(container,
                            params[0] == String.class ? key.toString() : key);
                    if (out != null && looksLikePreference(out)) return out;
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * Heuristic check: an object is "Preference-like" if it has both a {@code setKey} and a
     * {@code getKey} accessor (any parameter / return type).
     */
    private static boolean looksLikePreference(Object obj) {
        boolean hasSet = false, hasGet = false;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!hasGet && m.getName().equals("getKey") && m.getParameterCount() == 0) hasGet = true;
                if (!hasSet && m.getName().equals("setKey") && m.getParameterCount() == 1) hasSet = true;
                if (hasGet && hasSet) return true;
            }
            cls = cls.getSuperclass();
        }
        return hasGet || hasSet;
    }

    /** Calls {@code getPreferenceScreen()} on a fragment, with PreferenceManager fallback. */
    public static Object getPreferenceScreen(Object fragment) {
        Object screen = invoke(fragment, "getPreferenceScreen", new Class[0], new Object[0]);
        if (screen != null) return screen;
        Object pm = invoke(fragment, "getPreferenceManager", new Class[0], new Object[0]);
        if (pm != null) {
            Object via = invoke(pm, "getPreferenceScreen", new Class[0], new Object[0]);
            if (via != null) return via;
        }
        // Last resort: walk fields recursively up to depth 2 and pick anything whose type is
        // androidx.preference.PreferenceScreen. The class name itself is preserved by R8 even
        // when method / field names are minified.
        return findPreferenceScreenInFields(fragment, 2);
    }

    private static Object findPreferenceScreenInFields(Object root, int maxDepth) {
        if (root == null || maxDepth < 0) return null;
        Class<?> cls = root.getClass();
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                Class<?> ft = f.getType();
                String name = ft.getName();
                if ("androidx.preference.PreferenceScreen".equals(name)) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(root);
                        if (v != null) return v;
                    } catch (Throwable ignored) {
                    }
                }
                if (name.endsWith("$PreferenceScreen") || name.endsWith(".PreferenceScreen")) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(root);
                        if (v != null) return v;
                    } catch (Throwable ignored) {
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        // Recurse: walk into any non-primitive field once and probe the same way.
        if (maxDepth == 0) return null;
        cls = root.getClass();
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft == String.class || ft.isArray()) continue;
                if (ft.getName().startsWith("java.")) continue;
                if (ft.getName().startsWith("android.")) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(root);
                    if (v == null) continue;
                    Object found = findPreferenceScreenInFields(v, maxDepth - 1);
                    if (found != null) return found;
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /** Calls {@code getPreferenceCount()} on a screen / category. */
    public static int getPreferenceCount(Object container) {
        Object r = invoke(container, "getPreferenceCount", new Class[0], new Object[0]);
        return r instanceof Integer ? (Integer) r : 0;
    }

    /** Calls {@link androidx.preference.ListPreference#setEntries(CharSequence[])}. */
    public static void setEntries(Object listPref, CharSequence[] entries) {
        invokeVoid(listPref, "setEntries", new Class[]{CharSequence[].class}, new Object[]{entries});
    }

    /** Calls {@link androidx.preference.ListPreference#setEntryValues(CharSequence[])}. */
    public static void setEntryValues(Object listPref, CharSequence[] values) {
        invokeVoid(listPref, "setEntryValues",
                new Class[]{CharSequence[].class}, new Object[]{values});
    }

    /** Calls {@link androidx.preference.ListPreference#setValue(String)}. */
    public static void setValue(Object listPref, String value) {
        invokeVoid(listPref, "setValue", new Class[]{String.class}, new Object[]{value});
    }

    private static Object invoke(Object target, String name, Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        try {
            Method m = findMethod(target.getClass(), name, paramTypes);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable t) {
            xyz.melodylsp.codec.util.MLog.w("PrefRef." + name + " failed: " + t.getMessage());
            return null;
        }
    }

    private static void invokeVoid(Object target, String name, Class<?>[] paramTypes, Object[] args) {
        invoke(target, name, paramTypes, args);
    }

    private static Method findMethod(Class<?> startCls, String name, Class<?>[] paramTypes) {
        Class<?> cls = startCls;
        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    private PrefRef() {
    }
}
