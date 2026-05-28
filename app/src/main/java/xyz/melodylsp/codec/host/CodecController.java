package xyz.melodylsp.codec.host;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.bt.BluetoothCodecReflect;
import xyz.melodylsp.codec.label.CodecLabelTable;
import xyz.melodylsp.codec.storage.PreferenceStore;
import xyz.melodylsp.codec.util.MLog;

/**
 * Single host-side state owner. Each Surface attaches its {@link CodecPreferences} bag, the
 * controller refreshes UI state from {@code BluetoothA2dp.getCodecStatus} and routes write
 * intents through {@link CodecBridgeClient}.
 *
 * <p>Every {@code androidx.preference} access goes through {@link PrefRef} reflection because
 * the host APK is R8-minified.</p>
 */
public final class CodecController {

    private static final String ACTION_CODEC_CONFIG_CHANGED =
            "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED";
    private static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";

    private final Context context;
    private final BluetoothCodecReflect reflect;
    private final CodecBridgeClient bridge;
    private final PreferenceStore prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConnectionStateReplayer replayer;
    private final Map<Object, Subscription> subscriptions = new HashMap<>();
    private final AtomicReference<CodecSnapshot> lastSnapshot = new AtomicReference<>();

    public CodecController(
            Context context,
            BluetoothCodecReflect reflect,
            CodecBridgeClient bridge,
            PreferenceStore prefs) {
        this.context = context.getApplicationContext();
        this.reflect = reflect;
        this.bridge = bridge;
        this.prefs = prefs;
        this.replayer = new ConnectionStateReplayer(this.context, bridge, prefs);
        this.replayer.start();
        this.bridge.addSnapshotListener(this::onPushedSnapshot);
    }

    /**
     * Bind a {@link CodecPreferences} bag to a fragment lifecycle. {@code fragment} is the host
     * Fragment instance; we reflectively call {@code getLifecycle().addObserver(...)} so we
     * never compile-time-reference {@code LifecycleOwner}.
     */
    public void attach(String mac, CodecPreferences pref, Object fragment) {
        Subscription sub = new Subscription(mac, pref);
        subscriptions.put(fragment, sub);

        wireListeners(sub);
        sub.registerReceiver();
        mainHandler.post(() -> refreshSnapshot(sub));

        // Best-effort lifecycle observation: when we can resolve a Lifecycle we add a hand-rolled
        // observer that mirrors STARTED/STOPPED via reflection. Failures degrade silently — we
        // already eagerly registered the receiver above.
        try {
            Method getLifecycle = fragment.getClass().getMethod("getLifecycle");
            Object lifecycle = getLifecycle.invoke(fragment);
            ClassLoader cl = fragment.getClass().getClassLoader();
            Class<?> observerCls = Class.forName("androidx.lifecycle.LifecycleEventObserver", false, cl);
            Object observer = Proxy.newProxyInstance(cl, new Class[]{observerCls},
                    (proxy, method, args) -> {
                        if ("onStateChanged".equals(method.getName()) && args != null && args.length == 2) {
                            Object event = args[1];
                            String eventName = event != null ? event.toString() : "";
                            if ("ON_DESTROY".equals(eventName)) {
                                subscriptions.remove(fragment);
                                sub.unregisterReceiver();
                            }
                        }
                        return null;
                    });
            Method add = lifecycle.getClass().getMethod("addObserver",
                    Class.forName("androidx.lifecycle.LifecycleObserver", false, cl));
            add.invoke(lifecycle, observer);
        } catch (Throwable t) {
            MLog.w("attach lifecycle observer failed; receiver will outlive fragment", t);
        }
    }

    private void wireListeners(Subscription sub) {
        ClassLoader cl = context.getClassLoader();
        // The host APK is R8-minified and the inner interface
        // androidx.preference.Preference$OnPreferenceChangeListener has been renamed. Probe for
        // it by walking the Preference class for 1-arg setters whose only parameter is an
        // interface declaring a single boolean method (Preference, Object) -> boolean.
        Class<?> changeListenerCls = resolveChangeListenerInterface(cl, sub.prefs.qualityOption);
        if (changeListenerCls == null) {
            MLog.w("OnPreferenceChangeListener interface not resolvable; UI is read-only");
            return;
        }
        Object qualityListener = Proxy.newProxyInstance(cl, new Class[]{changeListenerCls},
                (proxy, method, args) -> {
                    if (args == null || args.length < 2) return false;
                    return handleQualityChange(sub, args[1]);
                });
        Object sampleListener = Proxy.newProxyInstance(cl, new Class[]{changeListenerCls},
                (proxy, method, args) -> {
                    if (args == null || args.length < 2) return false;
                    return handleSampleRateChange(sub, args[1]);
                });
        Object rememberListener = Proxy.newProxyInstance(cl, new Class[]{changeListenerCls},
                (proxy, method, args) -> {
                    if (args == null || args.length < 2) return false;
                    return handleRememberChange(sub, args[1]);
                });

        invokeSetChangeListener(sub.prefs.qualityOption, qualityListener, changeListenerCls);
        invokeSetChangeListener(sub.prefs.sampleRateOption, sampleListener, changeListenerCls);
        invokeSetChangeListener(sub.prefs.rememberToggle, rememberListener, changeListenerCls);
    }

    /**
     * Discover the {@code OnPreferenceChangeListener} interface class by walking
     * {@code androidx.preference.Preference} for a 1-arg setter whose parameter is an
     * interface with a single abstract method that returns boolean.
     */
    private Class<?> resolveChangeListenerInterface(ClassLoader cl, Object prefSample) {
        Class<?> prefBase = PrefRef.load(cl, "androidx.preference.Preference");
        if (prefBase == null) return null;
        Class<?> sampleCls = prefSample != null ? prefSample.getClass() : prefBase;
        Class<?> cls = sampleCls;
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (!p.isInterface()) continue;
                Method[] ifaceMethods = p.getDeclaredMethods();
                if (ifaceMethods.length != 1) continue;
                Method only = ifaceMethods[0];
                if (only.getReturnType() != boolean.class) continue;
                if (only.getParameterCount() != 2) continue;
                // First parameter should be Preference (or a subclass), second any Object.
                if (!prefBase.isAssignableFrom(only.getParameterTypes()[0])) continue;
                return p;
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static void invokeSetChangeListener(Object pref, Object listener, Class<?> ifaceCls) {
        if (pref == null || listener == null) return;
        try {
            Method m = findUnaryAcceptingType(pref.getClass(), ifaceCls);
            if (m != null) {
                m.setAccessible(true);
                m.invoke(pref, listener);
            }
        } catch (Throwable t) {
            MLog.w("setOnPreferenceChangeListener failed", t);
        }
    }

    /** Find a 1-arg method (any name, void return) accepting EXACTLY {@code paramType}. */
    private static Method findUnaryAcceptingType(Class<?> startCls, Class<?> paramType) {
        Class<?> cls = startCls;
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.isSynthetic() || m.isBridge()) continue;
                Class<?> p = m.getParameterTypes()[0];
                // Must match exactly. Using isAssignableFrom() in either direction picks up
                // unrelated setters (e.g. setSummaryProvider, setOnPreferenceClickListener) that
                // happen to take *some* interface, then ListPreference.onSetInitialValue tries to
                // (String) cast our Proxy and crashes with ClassCastException.
                if (p == paramType) return m;
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private boolean handleQualityChange(Subscription sub, Object value) {
        if (!(value instanceof CharSequence)) return false;
        CodecSnapshot snapshot = lastSnapshot.get();
        if (snapshot == null) return false;
        long specific1;
        try {
            specific1 = Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return false;
        }
        CodecRequest req = CodecRequest.fromActive(snapshot)
                .withSpecific1(specific1)
                .build();
        applyWrite(sub, req);
        return true;
    }

    private boolean handleSampleRateChange(Subscription sub, Object value) {
        if (!(value instanceof CharSequence)) return false;
        CodecSnapshot snapshot = lastSnapshot.get();
        if (snapshot == null) return false;
        int rateBit = decodeStoredSampleRate(value.toString(), snapshot.selectableSampleRateMask);
        if (rateBit < 0) return false;
        CodecRequest req = CodecRequest.fromActive(snapshot)
                .withSampleRate(rateBit)
                .build();
        applyWrite(sub, req);
        return true;
    }

    private boolean handleRememberChange(Subscription sub, Object value) {
        if (!(value instanceof Boolean)) return false;
        boolean enabled = (Boolean) value;
        prefs.setRemembered(sub.mac, enabled);
        if (enabled) {
            CodecSnapshot s = lastSnapshot.get();
            if (s != null && s.mac != null && s.mac.equals(sub.mac)) {
                prefs.writeSnapshot(sub.mac, s.activeCodecSpecific1, s.activeSampleRate);
            }
        }
        return true;
    }

    private void applyWrite(Subscription sub, CodecRequest request) {
        bridge.setCodec(request).whenComplete((result, ex) -> mainHandler.post(() -> {
            if (ex != null) {
                MLog.e("setCodec future failed", ex);
                Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
                refreshSnapshot(sub);
                return;
            }
            switch (result.outcome) {
                case CONFIRMED:
                    if (result.path == WriteResult.Path.SETTINGS_GLOBAL) {
                        Toast.makeText(context, Strings.BANNER_VIA_SETTINGS, Toast.LENGTH_LONG).show();
                    }
                    if (prefs.isRemembered(sub.mac)) {
                        prefs.writeSnapshot(sub.mac, request.codecSpecific1, request.sampleRate);
                    }
                    refreshSnapshot(sub);
                    break;
                case TIMEOUT_ROLLED_BACK:
                    Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
                    if (result.rollbackSnapshot != null) {
                        publish(result.rollbackSnapshot, sub);
                    } else {
                        refreshSnapshot(sub);
                    }
                    break;
                case FAILED:
                default:
                    Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
                    refreshSnapshot(sub);
                    break;
            }
        }));
    }

    private void refreshSnapshot(Subscription sub) {
        Thread worker = new Thread(() -> {
            CodecSnapshot snapshot;
            try {
                snapshot = reflect.readStatus(sub.mac);
            } catch (BluetoothCodecReflect.BluetoothCodecReflectException e) {
                MLog.w("refreshSnapshot reflect failed", e);
                snapshot = bridge.getStatus(sub.mac);
            } catch (Throwable t) {
                MLog.e("refreshSnapshot threw", t);
                snapshot = null;
            }
            CodecSnapshot finalSnapshot = snapshot;
            mainHandler.post(() -> publish(finalSnapshot, sub));
        }, "MelodyCodecLsp-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    private void publish(CodecSnapshot snapshot, Subscription sub) {
        if (snapshot != null) {
            lastSnapshot.set(snapshot);
        }
        if (snapshot == null) {
            CodecSnapshot stale = lastSnapshot.get();
            if (stale != null) {
                renderSnapshot(stale, sub, /* fromCache= */ true);
            } else {
                renderUnknown(sub);
            }
            return;
        }
        renderSnapshot(snapshot, sub, /* fromCache= */ false);
    }

    /** Called when {@link CodecBridgeClient} forwards a system-side push event. */
    private void onPushedSnapshot(CodecSnapshot snapshot) {
        if (snapshot == null || snapshot.mac == null) return;
        mainHandler.post(() -> {
            lastSnapshot.set(snapshot);
            for (Subscription sub : subscriptions.values()) {
                if (snapshot.mac.equals(sub.mac)) {
                    renderSnapshot(snapshot, sub, /* fromCache= */ false);
                }
            }
        });
    }

    private void renderUnknown(Subscription sub) {
        // codecDisplay points at the category itself; mutating its title gives us the merged
        // "蓝牙音质 · LHDC" header without needing a separate Preference row.
        PrefRef.setTitle(sub.prefs.category, Strings.CODEC_BLOCK_TITLE);
        PrefRef.setVisible(sub.prefs.qualityOption, false);
        PrefRef.setVisible(sub.prefs.sampleRateOption, false);
        PrefRef.setChecked(sub.prefs.rememberToggle, prefs.isRemembered(sub.mac));
    }

    private void renderSnapshot(CodecSnapshot snapshot, Subscription sub, boolean fromCache) {
        String codecName = CodecLabelTable.codecLabel(context, snapshot.activeCodecType);
        String header;
        if (fromCache) {
            String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
            header = Strings.CODEC_BLOCK_TITLE + " · " + codecName + "  ("
                    + String.format(Strings.FRESHNESS_LABEL_FORMAT, stamp) + ")";
        } else {
            header = Strings.CODEC_BLOCK_TITLE + " · " + codecName;
        }
        PrefRef.setTitle(sub.prefs.category, header);

        renderQuality(snapshot, sub);
        renderSampleRate(snapshot, sub);
        PrefRef.setChecked(sub.prefs.rememberToggle, prefs.isRemembered(sub.mac));
    }

    private void renderQuality(CodecSnapshot snapshot, Subscription sub) {
        long[] selectable = snapshot.selectableCodecSpecific1;
        Object q = sub.prefs.qualityOption;
        if (q == null) return;
        // Hide Quality option for codecs that do not expose quality steps (e.g. SBC default).
        if (!CodecLabelTable.isQualityCapable(snapshot.activeCodecType)) {
            PrefRef.setVisible(q, false);
            return;
        }
        // AOSP getCodecStatus reports an empty selectableCodecSpecific1 for vendor codecs
        // whose capability set was not registered with the system codec manager — which is
        // every OPPO Vendor LHDC variant we know about. Fall back to the protocol-defined
        // step list (LDAC: 1000/1001/1002, LHDC: V1/V2/V3/V5) so the user still sees the
        // dropdown. The bridge writes the selected value through to A2DP, and the kernel
        // accepts it as long as the bits are valid for that vendor codec.
        if (selectable == null || selectable.length == 0) {
            selectable = CodecLabelTable.qualityFallback(snapshot.activeCodecType);
        }
        if (selectable.length == 0) {
            PrefRef.setVisible(q, false);
            return;
        }
        CharSequence[] entries = new CharSequence[selectable.length];
        CharSequence[] values = new CharSequence[selectable.length];
        for (int i = 0; i < selectable.length; i++) {
            entries[i] = CodecLabelTable.qualityLabel(context, snapshot.activeCodecType, selectable[i]);
            values[i] = String.valueOf(selectable[i]);
        }
        PrefRef.setEntries(q, entries);
        PrefRef.setEntryValues(q, values);
        String currentValue = String.valueOf(snapshot.activeCodecSpecific1);
        if (!Arrays.asList(values).contains(currentValue)) {
            PrefRef.setSummary(q, String.format(Strings.QUALITY_UNKNOWN_VALUE_FORMAT, currentValue));
        } else {
            PrefRef.setValue(q, currentValue);
            PrefRef.setSummary(q, CodecLabelTable.qualityLabel(
                    context, snapshot.activeCodecType, snapshot.activeCodecSpecific1));
        }
        PrefRef.setVisible(q, true);
    }

    private void renderSampleRate(CodecSnapshot snapshot, Subscription sub) {
        int rateMask = snapshot.selectableSampleRateMask;
        // AOSP also leaves selectableSampleRateMask = 0 for vendor codecs. Fall back to the
        // sample rates the protocol allows for that codec family. LHDC V5 is the only codec
        // exposed by this build that goes beyond 96 kHz, so the fallback list is union'd
        // with the active rate bit so we never hide a working option.
        if (rateMask == 0 || CodecSnapshot.decodeSampleRateBits(rateMask).length == 0) {
            rateMask = sampleRateFallbackMask(snapshot.activeCodecType, snapshot.activeSampleRate);
        }
        int[] rates = CodecSnapshot.decodeSampleRateBits(rateMask);
        Object r = sub.prefs.sampleRateOption;
        if (r == null) return;
        if (rates.length == 0) {
            PrefRef.setVisible(r, false);
            return;
        }
        List<CharSequence> entryList = new ArrayList<>(rates.length);
        List<CharSequence> valueList = new ArrayList<>(rates.length);
        for (int rate : rates) {
            entryList.add(CodecLabelTable.sampleRateLabel(rate));
            valueList.add(String.valueOf(rate));
        }
        PrefRef.setEntries(r, entryList.toArray(new CharSequence[0]));
        PrefRef.setEntryValues(r, valueList.toArray(new CharSequence[0]));
        int activeHz = sampleRateBitToHz(snapshot.activeSampleRate);
        if (activeHz > 0) {
            PrefRef.setValue(r, String.valueOf(activeHz));
            PrefRef.setSummary(r, CodecLabelTable.sampleRateLabel(activeHz));
        }
        PrefRef.setVisible(r, true);
    }

    /**
     * Returns the protocol-defined sample-rate mask for {@code codecType} so we can populate
     * the dropdown when AOSP failed to enumerate it. The mask is union'd with the active
     * sample-rate bit so the dropdown always at least includes the rate the device is using
     * right now (otherwise tapping it would dismiss the dialog and re-open showing nothing).
     */
    private static int sampleRateFallbackMask(int codecType, int activeRateBit) {
        int mask = activeRateBit;
        // Bits — see CodecSnapshot.decodeSampleRateBits.
        final int B44_1 = 1, B48 = 2, B88_2 = 4, B96 = 8, B176_4 = 16, B192 = 32;
        if (codecType == CodecLabelTable.CODEC_LDAC) {
            mask |= B44_1 | B48 | B88_2 | B96;
        } else if (CodecLabelTable.isLhdc(codecType)) {
            // LHDC V1/V2/V3 top out at 96 kHz, V5 adds 192 kHz. We do not know the version
            // from rate-mask alone, so include the entire set the protocol can carry; the
            // bridge will reject anything the kernel does not actually accept.
            mask |= B44_1 | B48 | B88_2 | B96 | B192;
        } else {
            // Generic SBC / AAC / aptX cover at least 44.1k and 48k.
            mask |= B44_1 | B48;
        }
        return mask;
    }

    private static int sampleRateBitToHz(int bit) {
        int[] decoded = CodecSnapshot.decodeSampleRateBits(bit);
        if (decoded.length != 1 || decoded[0] <= 0) return -1;
        return decoded[0];
    }

    private static int decodeStoredSampleRate(String storedValue, int selectableMask) {
        int hz;
        try {
            hz = Integer.parseInt(storedValue);
        } catch (NumberFormatException e) {
            return -1;
        }
        // If the platform did not report a selectable mask (vendor codec quirk), accept any
        // standard-rate value the user picked. The kernel will reject impossible writes, the
        // bridge will then time out and we will roll back. This is symmetric with the
        // renderSampleRate fallback that populates the dropdown the same way.
        if (selectableMask == 0) {
            int[] knownBits = {0x1, 0x2, 0x4, 0x8, 0x10, 0x20};
            int[] knownHz = {44100, 48000, 88200, 96000, 176400, 192000};
            for (int i = 0; i < knownHz.length; i++) {
                if (knownHz[i] == hz) return knownBits[i];
            }
            return -1;
        }
        int[] supported = CodecSnapshot.decodeSampleRateBits(selectableMask);
        for (int v : supported) {
            if (v == hz) {
                for (int b = 0; b < 31; b++) {
                    int bit = 1 << b;
                    if ((selectableMask & bit) == 0) continue;
                    int[] decoded = CodecSnapshot.decodeSampleRateBits(bit);
                    if (decoded.length == 1 && decoded[0] == hz) return bit;
                }
            }
        }
        return -1;
    }

    /** Per-attach state. */
    private final class Subscription {
        final String mac;
        final CodecPreferences prefs;
        BroadcastReceiver receiver;

        Subscription(String mac, CodecPreferences prefs) {
            this.mac = mac;
            this.prefs = prefs;
        }

        void registerReceiver() {
            if (receiver != null) return;
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    if (ACTION_CODEC_CONFIG_CHANGED.equals(action)
                            || ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                        refreshSnapshot(Subscription.this);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_CODEC_CONFIG_CHANGED);
            filter.addAction(ACTION_CONNECTION_STATE_CHANGED);
            try {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } catch (Throwable t) {
                context.registerReceiver(receiver, filter);
            }
        }

        void unregisterReceiver() {
            if (receiver == null) return;
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiver = null;
        }
    }
}
