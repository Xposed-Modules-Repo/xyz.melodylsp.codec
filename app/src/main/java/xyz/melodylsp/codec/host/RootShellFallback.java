package xyz.melodylsp.codec.host;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.label.CodecLabelTable;
import xyz.melodylsp.codec.util.MLog;

/**
 * Last-resort write path that runs as the {@code root} user via {@code su}.
 *
 * <p>This kicks in after the host-side reflection ({@link
 * xyz.melodylsp.codec.bt.BluetoothCodecReflect#setCodec}) and the system-bridge AIDL call
 * ({@link CodecBridgeClient}) have both refused the write — typically because
 * {@code com.oplus.melody:fg} is missing {@code BLUETOOTH_PRIVILEGED} and SELinux blocks the
 * AIDL hop. The user explicitly authorises {@code xyz.melodylsp.codec} in their root manager,
 * so we can just shell out.</p>
 *
 * <p>Strategy: write the developer-options keys via {@code cmd settings put global …}
 * (which the root shell can do unconditionally, no {@code WRITE_SECURE_SETTINGS} needed),
 * then nudge the A2DP stack to renegotiate the codec by toggling A2DP off/on through the
 * profile manager. This mirrors what the developer-options menu does internally when the user
 * picks a different codec spec1 / sample rate.</p>
 *
 * <p>Each invocation lazily probes for {@code su}; absence is cached so subsequent attempts
 * skip straight to "no usable root" rather than spawning processes that cannot resolve the
 * binary.</p>
 */
public final class RootShellFallback {

    private static final long PROCESS_TIMEOUT_MS = 5_000L;

    private final AtomicBoolean rootProbed = new AtomicBoolean(false);
    private volatile boolean rootAvailable;

    /**
     * Best-effort apply via {@code su}. Returns true when the shell exited 0 and at least one
     * of the developer-options writes was issued. False on any failure (no {@code su},
     * non-zero exit, timeout).
     */
    public boolean apply(CodecRequest req) {
        if (!ensureRoot()) {
            return false;
        }
        List<String> commands = buildCommands(req);
        if (commands.isEmpty()) {
            MLog.w("RootShellFallback: nothing to write for request " + req);
            return false;
        }
        boolean ok = runAsRoot(commands);
        if (ok) {
            MLog.event("root.shell.write",
                    "codec", req.codecType,
                    "specific1", req.codecSpecific1,
                    "rate", req.sampleRate);
        }
        return ok;
    }

    private List<String> buildCommands(CodecRequest req) {
        java.util.ArrayList<String> cmds = new java.util.ArrayList<>(4);

        // LDAC playback quality maps {1000,1001,1002} → {0,1,2}.
        if (req.codecType == CodecLabelTable.CODEC_LDAC) {
            int idx = mapLdacQualityToIndex(req.codecSpecific1);
            if (idx >= 0) {
                cmds.add("cmd settings put global "
                        + SettingsGlobalFallback.KEY_LDAC_QUALITY + " " + idx);
            }
        }
        // LHDC variants do not have a global quality key in AOSP; codec selection is owned by
        // the OPPO vendor stack. We still write the sample-rate global so renegotiation happens.

        int rateIdx = mapSampleRateToIndex(req.sampleRate);
        if (rateIdx >= 0) {
            cmds.add("cmd settings put global "
                    + SettingsGlobalFallback.KEY_SAMPLE_RATE + " " + rateIdx);
        }

        // Force the A2DP stack to renegotiate by toggling the profile off and back on. This
        // is what the dev-options menu does internally when the user picks a value.
        if (!cmds.isEmpty()) {
            cmds.add("cmd bluetooth_manager disable");
            cmds.add("sleep 1");
            cmds.add("cmd bluetooth_manager enable");
        }
        return cmds;
    }

    private boolean ensureRoot() {
        if (rootProbed.compareAndSet(false, true)) {
            rootAvailable = probeRoot();
            MLog.event("root.probe", "available", rootAvailable);
        }
        return rootAvailable;
    }

    /** Run {@code id -u} via su; root is "available" iff that returns 0. */
    private static boolean probeRoot() {
        return runAsRoot(java.util.Collections.singletonList("id -u"));
    }

    /** Pipe each command (newline-terminated) into {@code su}, then {@code exit}. */
    private static boolean runAsRoot(List<String> commands) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
        } catch (IOException e) {
            MLog.w("su not present or unable to spawn", e);
            return false;
        }
        try (DataOutputStream out = new DataOutputStream(process.getOutputStream())) {
            for (String c : commands) {
                out.writeBytes(c + "\n");
            }
            out.writeBytes("exit\n");
            out.flush();
        } catch (IOException e) {
            MLog.w("writing to su stdin failed", e);
            destroyQuietly(process);
            return false;
        }
        try {
            boolean done = process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!done) {
                MLog.w("su timed out after " + PROCESS_TIMEOUT_MS + "ms");
                destroyQuietly(process);
                return false;
            }
            int exit = process.exitValue();
            String stderr = readAll(process.getErrorStream());
            if (exit != 0) {
                MLog.w("su exited " + exit + "; stderr=" + stderr);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyQuietly(process);
            return false;
        }
    }

    private static String readAll(java.io.InputStream stream) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException ignored) {
        }
        return sb.toString();
    }

    private static void destroyQuietly(Process process) {
        try {
            process.destroy();
        } catch (Throwable ignored) {
        }
    }

    private static int mapLdacQualityToIndex(long codecSpecific1) {
        if (codecSpecific1 == CodecLabelTable.LDAC_QUALITY_HIGH) return 0;
        if (codecSpecific1 == CodecLabelTable.LDAC_QUALITY_MID) return 1;
        if (codecSpecific1 == CodecLabelTable.LDAC_QUALITY_LOW) return 2;
        return -1;
    }

    private static int mapSampleRateToIndex(int sampleRateBit) {
        switch (sampleRateBit) {
            case 1 << 0: return 1;
            case 1 << 1: return 2;
            case 1 << 2: return 3;
            case 1 << 3: return 4;
            default: return -1;
        }
    }
}
