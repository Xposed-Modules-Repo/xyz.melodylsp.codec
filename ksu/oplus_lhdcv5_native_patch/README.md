# OPlus LHDC V5 Native Bitrate Patch

This is an optional KernelSU / Magisk-compatible native patch module for OPlus /
ColorOS Bluetooth stacks that ignore fixed LHDC V5 target bitrates such as
900 / 1000 kbps.

It is separate from the LSPosed APK. The APK provides the UI and codec write
paths; this module only patches the system Bluetooth JNI library overlay when a
known LHDC V5 byte pattern is found.

## How It Works

- Reads the current `/system/lib64/libbluetooth_jni.so` during module install.
- Refuses to patch unless the expected original byte pattern appears exactly
  once.
- Copies the current system library into the module overlay path and changes the
  4-byte branch at the detected offset.
- Does not ship any device-specific `libbluetooth_jni.so` binary.
- Writes install details to `patch-info.txt`, including device properties,
  original SHA-256, patch offset, patched SHA-256, and status.

## Package

Create the flashable zip from this directory:

```bash
cd ksu/oplus_lhdcv5_native_patch
zip -r ../../OPlus-LHDCV5-Native-Patch-0.3-dynamic-test.zip .
```

Make sure the zip entries use `/` separators, for example
`META-INF/com/google/android/updater-script`. Avoid packagers that create entries
such as `META-INF\com\google\android\updater-script`; those install but show up
as malformed paths in KernelSU / Magisk managers.

## Install Notes

- Reboot after installing or removing the module.
- If the target library layout is unsupported, installation aborts before writing
  the overlay file.
- After boot, check logcat tag `OPlusLHDCV5Patch` or
  `/data/adb/modules/oplus_lhdcv5_native_patch/patch-info.txt`.
