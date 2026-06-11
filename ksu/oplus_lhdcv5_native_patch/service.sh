#!/system/bin/sh

INFO='/data/adb/modules/oplus_lhdcv5_native_patch/patch-info.txt'

log -t OPlusLHDCV5Patch 'native patch module active'
if [ -f "${INFO}" ]; then
  while IFS= read -r line; do
    log -t OPlusLHDCV5Patch "${line}"
  done < "${INFO}"
fi
