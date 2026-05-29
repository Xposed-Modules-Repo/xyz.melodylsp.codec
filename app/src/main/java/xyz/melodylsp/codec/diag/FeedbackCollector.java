package xyz.melodylsp.codec.diag;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import xyz.melodylsp.codec.BuildConfig;

public final class FeedbackCollector {

    private static final String[] PACKAGES = {
            BuildConfig.APPLICATION_ID,
            "com.oplus.melody",
            "com.android.bluetooth",
            "com.oplus.wirelesssettings"
    };

    private FeedbackCollector() {
    }

    public static String collect(Context context) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        String name = "HeadsetAudioHelper-feedback-" + stamp + ".zip";
        OutputTarget target = openTarget(context, name);
        try {
            ZipOutputStream zip = new ZipOutputStream(target.stream);
            write(zip, "summary.txt", buildSummary(context));
            write(zip, "diagnostics.txt", buildDiagnostics(context));
            write(zip, "logcat.txt", collectLogcat());
            write(zip, "module-prop.txt", readResource(context,
                    "META-INF/xposed/module.prop"));
            write(zip, "scope-list.txt", readResource(context,
                    "META-INF/xposed/scope.list"));
            zip.close();
            target.finish(context);
            return target.displayPath;
        } catch (Throwable t) {
            target.abort(context);
            throw t;
        }
    }

    private static OutputTarget openTarget(Context context, String name) throws Exception {
        File root = Environment.getExternalStorageDirectory();
        File file = new File(root, name);
        try {
            return new OutputTarget(new FileOutputStream(file), file.getAbsolutePath(), null);
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                OutputStream out = resolver.openOutputStream(uri);
                if (out != null) {
                    return new OutputTarget(out,
                            "/storage/emulated/0/Download/" + name, uri);
                }
            }
        }
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        File fallback = new File(dir, name);
        return new OutputTarget(new FileOutputStream(fallback),
                fallback.getAbsolutePath(), null);
    }

    private static String buildSummary(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Headset Audio Helper feedback\n");
        sb.append("Generated: ").append(new Date()).append('\n');
        sb.append("Module: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n\n");
        sb.append("Device\n");
        sb.append("Brand: ").append(Build.BRAND).append('\n');
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append('\n');
        sb.append("Model: ").append(Build.MODEL).append('\n');
        sb.append("Device: ").append(Build.DEVICE).append('\n');
        sb.append("Product: ").append(Build.PRODUCT).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("Build: ").append(Build.DISPLAY).append('\n');
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n\n");
        sb.append("Packages\n");
        for (String pkg : PACKAGES) {
            sb.append(pkg).append(": ").append(packageVersion(context, pkg)).append('\n');
        }
        sb.append('\n');
        SharedPreferences modulePrefs = context.getSharedPreferences(
                "module_prefs", Context.MODE_PRIVATE);
        sb.append("Module enabled: ").append(modulePrefs.getBoolean("enabled", true)).append('\n');
        return sb.toString();
    }

    private static String buildDiagnostics(Context context) {
        SharedPreferences sp = context.getSharedPreferences(
                DiagnosticEvents.PREFS, Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        sb.append("Status\n");
        appendStatus(sb, sp, "scope.host", "Melody scope");
        appendStatus(sb, sp, "host.controller", "Melody controller");
        appendStatus(sb, sp, "hook.host", "Host hooks");
        appendStatus(sb, sp, "inject.detail", "DetailMain injection");
        appendStatus(sb, sp, "inject.onespace", "OneSpace injection");
        appendStatus(sb, sp, "scope.bluetooth", "Bluetooth scope");
        appendStatus(sb, sp, "bridge.codec", "A2DP bridge");
        appendStatus(sb, sp, "bridge.le.bt", "LE Audio bluetooth bridge");
        appendStatus(sb, sp, "scope.wirelesssettings", "WirelessSettings scope");
        appendStatus(sb, sp, "bridge.le.ws", "LE Audio wirelesssettings bridge");
        appendStatus(sb, sp, "last.warning", "Last warning");
        appendStatus(sb, sp, "last.error", "Last error");
        sb.append("\nRaw SharedPreferences\n");
        for (Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        sb.append("\nEvent ring\n");
        sb.append(sp.getString(DiagnosticEvents.KEY_EVENTS, ""));
        sb.append('\n');
        return sb.toString();
    }

    private static void appendStatus(
            StringBuilder sb, SharedPreferences sp, String key, String label) {
        sb.append(label).append(": ")
                .append(DiagnosticEvents.status(sp, key))
                .append(" @ ")
                .append(DiagnosticEvents.formatTime(DiagnosticEvents.time(sp, key)));
        String detail = DiagnosticEvents.detail(sp, key);
        if (detail != null && !detail.isEmpty()) {
            sb.append(" | ").append(detail);
        }
        sb.append('\n');
    }

    private static String collectLogcat() {
        String direct = runCommand(new String[]{"logcat", "-d", "-s", "MelodyCodecLsp:V"});
        if (direct.trim().length() > 80) return direct;
        String rooted = runCommand(new String[]{"su", "-c", "logcat -d -s MelodyCodecLsp:V"});
        if (!rooted.trim().isEmpty()) {
            return "direct logcat was empty; su fallback used\n\n" + rooted;
        }
        return "logcat unavailable from module app. Please also attach LSPosed module logs.\n\n"
                + direct;
    }

    private static String runCommand(String[] command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(command);
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            String out = readStream(process.getInputStream());
            String err = readStream(process.getErrorStream());
            if (!finished) {
                process.destroy();
                return out + "\n(command timed out)\n" + err;
            }
            return out + (err.isEmpty() ? "" : "\n--- stderr ---\n" + err);
        } catch (Throwable t) {
            return "command failed: " + t + '\n';
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String readStream(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String readResource(Context context, String name) {
        try {
            InputStream in = context.getClassLoader().getResourceAsStream(name);
            if (in == null) return "missing: " + name + '\n';
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return "read failed: " + t + '\n';
        }
    }

    private static String packageVersion(Context context, String pkg) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
            long code = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
            return String.valueOf(info.versionName) + " (" + code + ")";
        } catch (Throwable t) {
            return "not installed / unreadable";
        }
    }

    private static void write(ZipOutputStream zip, String name, String content) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static final class OutputTarget {
        final OutputStream stream;
        final String displayPath;
        final Uri mediaUri;

        OutputTarget(OutputStream stream, String displayPath, Uri mediaUri) {
            this.stream = stream;
            this.displayPath = displayPath;
            this.mediaUri = mediaUri;
        }

        void finish(Context context) {
            if (mediaUri == null || Build.VERSION.SDK_INT < 29) return;
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(mediaUri, values, null, null);
        }

        void abort(Context context) {
            try {
                stream.close();
            } catch (Throwable ignored) {
            }
            if (mediaUri != null) {
                try {
                    context.getContentResolver().delete(mediaUri, null, null);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
