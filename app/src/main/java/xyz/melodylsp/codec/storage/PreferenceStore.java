package xyz.melodylsp.codec.storage;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.TreeSet;

import xyz.melodylsp.codec.util.MLog;

/**
 * Per-host SharedPreferences for the per-MAC {@code Remember_Toggle} and value snapshot.
 *
 * <p>The module-wide master switch (default {@code true}, Requirement 12.2) is stored
 * separately in {@code module_prefs.xml} and exposed through libxposed
 * {@code XposedModule.getRemotePreferences("module_prefs")} so the module's own settings
 * Activity ({@code MasterSwitchActivity}) and the in-host hook callbacks see the same
 * value.</p>
 *
 * <p>Setting {@code Remember_Toggle=false} immediately removes the snapshot keys so the next
 * reconnect goes through the Android system default (Requirement 12.5 / 12.7).</p>
 */
public final class PreferenceStore {

    public static final String MELODY_PREFS = "melody_lsp_codec_prefs";

    public static final String KEY_ENABLED = "enabled";

    private static final String KEY_REMEMBER_SUFFIX = "_remember";
    private static final String KEY_CODEC_TYPE_SUFFIX = "_codec_type";
    private static final String KEY_SPECIFIC1_SUFFIX = "_specific1";
    private static final String KEY_SAMPLERATE_SUFFIX = "_samplerate";
    private static final int CODEC_TYPE_UNKNOWN = -1;

    private final Context hostContext;

    public PreferenceStore(Context hostContext) {
        this.hostContext = hostContext.getApplicationContext();
    }

    public boolean isRemembered(String mac) {
        if (mac == null) return false;
        return prefs().getBoolean(mac + KEY_REMEMBER_SUFFIX, false);
    }

    public void setRemembered(String mac, boolean remembered) {
        if (mac == null) return;
        SharedPreferences.Editor editor = prefs().edit();
        editor.putBoolean(mac + KEY_REMEMBER_SUFFIX, remembered);
        if (!remembered) {
            // Snapshot keys must vanish atomically with the toggle (Property 9).
            editor.remove(mac + KEY_CODEC_TYPE_SUFFIX);
            editor.remove(mac + KEY_SPECIFIC1_SUFFIX);
            editor.remove(mac + KEY_SAMPLERATE_SUFFIX);
        }
        if (!editor.commit()) {
            MLog.w("remember.set commit failed mac=" + redact(mac));
        }
        MLog.event("remember.set", "mac", redact(mac), "remembered", remembered);
        emitDiagnosticSnapshot("remember_set");
    }

    public RememberedValue readSnapshot(String mac) {
        if (mac == null) return null;
        SharedPreferences sp = prefs();
        if (!sp.getBoolean(mac + KEY_REMEMBER_SUFFIX, false)) return null;
        if (!sp.contains(mac + KEY_SPECIFIC1_SUFFIX)
                || !sp.contains(mac + KEY_SAMPLERATE_SUFFIX)) {
            return null;
        }
        int codecType = sp.getInt(mac + KEY_CODEC_TYPE_SUFFIX, CODEC_TYPE_UNKNOWN);
        long specific1 = sp.getLong(mac + KEY_SPECIFIC1_SUFFIX, -1L);
        int sampleRate = sp.getInt(mac + KEY_SAMPLERATE_SUFFIX, -1);
        return new RememberedValue(codecType, specific1, sampleRate);
    }

    public String[] rememberedMacs() {
        SharedPreferences sp = prefs();
        TreeSet<String> macs = rememberedMacs(sp.getAll());
        return macs.toArray(new String[0]);
    }

    public void writeSnapshot(String mac, int codecType, long codecSpecific1, int sampleRate) {
        if (mac == null) return;
        SharedPreferences sp = prefs();
        if (!sp.getBoolean(mac + KEY_REMEMBER_SUFFIX, false)) {
            MLog.w("writeSnapshot ignored, remember=false mac=" + redact(mac));
            return;
        }
        boolean committed = sp.edit()
                .putInt(mac + KEY_CODEC_TYPE_SUFFIX, codecType)
                .putLong(mac + KEY_SPECIFIC1_SUFFIX, codecSpecific1)
                .putInt(mac + KEY_SAMPLERATE_SUFFIX, sampleRate)
                .commit();
        if (!committed) {
            MLog.w("remember.write commit failed mac=" + redact(mac));
        }
        MLog.event("remember.write",
                "mac", redact(mac),
                "codec", codecType,
                "specific1", codecSpecific1,
                "rate", sampleRate);
        emitDiagnosticSnapshot("remember_write");
    }

    public void emitDiagnosticSnapshot(String reason) {
        SharedPreferences sp = prefs();
        TreeSet<String> macs = rememberedMacs(sp.getAll());
        String safeReason = reason != null && !reason.isEmpty() ? reason : "unknown";
        MLog.event("remember.snapshot.begin",
                "reason", safeReason,
                "count", macs.size());
        int index = 0;
        for (String mac : macs) {
            boolean remembered = sp.getBoolean(mac + KEY_REMEMBER_SUFFIX, false);
            boolean hasSnapshot = sp.contains(mac + KEY_SPECIFIC1_SUFFIX)
                    && sp.contains(mac + KEY_SAMPLERATE_SUFFIX);
            int codec = sp.getInt(mac + KEY_CODEC_TYPE_SUFFIX, CODEC_TYPE_UNKNOWN);
            long specific1 = sp.getLong(mac + KEY_SPECIFIC1_SUFFIX, -1L);
            int rate = sp.getInt(mac + KEY_SAMPLERATE_SUFFIX, -1);
            MLog.event("remember.snapshot.item",
                    "index", index++,
                    "mac", redact(mac),
                    "remembered", remembered,
                    "hasSnapshot", hasSnapshot,
                    "codec", codec,
                    "specific1", specific1,
                    "rate", rate);
        }
        MLog.event("remember.snapshot.end",
                "reason", safeReason,
                "count", macs.size());
    }

    private static TreeSet<String> rememberedMacs(Map<String, ?> all) {
        TreeSet<String> macs = new TreeSet<>();
        for (String key : all.keySet()) {
            if (key.endsWith(KEY_REMEMBER_SUFFIX)) {
                String mac = key.substring(0, key.length() - KEY_REMEMBER_SUFFIX.length());
                if (mac != null && !mac.isEmpty()) macs.add(mac);
            }
        }
        return macs;
    }

    private SharedPreferences prefs() {
        return hostContext.getSharedPreferences(MELODY_PREFS, preferencesMode());
    }

    @SuppressWarnings("deprecation")
    private static int preferencesMode() {
        return Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS;
    }

    private static String redact(String mac) {
        if (mac == null || mac.length() < 5) return "??";
        return mac.substring(0, 2) + "**" + mac.substring(mac.length() - 2);
    }

    /** Snapshot returned to ConnectionStateReplayer / UI on a remember=true MAC. */
    public static final class RememberedValue {
        public final int codecType;
        public final long codecSpecific1;
        public final int sampleRate;

        public RememberedValue(int codecType, long codecSpecific1, int sampleRate) {
            this.codecType = codecType;
            this.codecSpecific1 = codecSpecific1;
            this.sampleRate = sampleRate;
        }
    }
}
