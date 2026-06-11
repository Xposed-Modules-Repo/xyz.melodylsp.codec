SKIPMOUNT=false
PROPFILE=true
POSTFSDATA=false
LATESTARTSERVICE=true

TARGET='/system/lib64/libbluetooth_jni.so'
OUT="${MODPATH}/system/lib64/libbluetooth_jni.so"
INFO="${MODPATH}/patch-info.txt"
OLD_MODULE='/data/adb/modules/oplus_lhdcv5_native_patch_pjz110_1608301'
HEX_FILE="${MODPATH}/target.hex"
PATCHED_HEX_FILE="${MODPATH}/patched.hex"
ORIGINAL_HEX='1f0900f1a2080054e83d80529b008052'
PATCHED_HEX='1f0900f145000014e83d80529b008052'
PATCH_BYTES='\105\000\000\024'

cleanup_hex() {
  rm -f "${HEX_FILE}" "${PATCHED_HEX_FILE}"
}
trap cleanup_hex EXIT

ui_print '- OPlus LHDC V5 native bitrate patch'
ui_print '- Mode: install-time patch from current system library'

if [ -d "${OLD_MODULE}" ] && [ "${MODPATH}" != "${OLD_MODULE}" ]; then
  touch "${OLD_MODULE}/remove" 2>/dev/null
  ui_print '- Marked old build-specific module for removal'
fi

if [ ! -f "${TARGET}" ]; then
  abort "! Missing target library: ${TARGET}"
fi

hex_dump() {
  od -An -tx1 -v "$1" | tr -d ' \n' | tr 'A-F' 'a-f'
}

find_pattern_index() {
  awk -v needle="$2" '{ print index($0, needle); exit }' "$1"
}

count_pattern() {
  awk -v needle="$2" '{
    haystack = $0
    count = 0
    pos = 1
    while (1) {
      hit = index(substr(haystack, pos), needle)
      if (hit == 0) break
      count++
      pos += hit + length(needle) - 1
    }
    print count
    exit
  }' "$1"
}

fix_output_perm() {
  if type set_perm >/dev/null 2>&1; then
    set_perm "${OUT}" 0 0 0644
  else
    chmod 0644 "${OUT}"
  fi
}

ORIGINAL_SHA="$(sha256sum "${TARGET}" | awk '{print $1}')"
hex_dump "${TARGET}" > "${HEX_FILE}"
if [ ! -s "${HEX_FILE}" ]; then
  abort "! Failed to read target library as hex"
fi

ORIGINAL_COUNT="$(count_pattern "${HEX_FILE}" "${ORIGINAL_HEX}")"
PATCHED_COUNT="$(count_pattern "${HEX_FILE}" "${PATCHED_HEX}")"

mkdir -p "$(dirname "${OUT}")" || abort "! Failed to create module lib directory"
cp -f "${TARGET}" "${OUT}" || abort "! Failed to copy target library"
fix_output_perm

{
  echo "product=$(getprop ro.product.device)"
  echo "model=$(getprop ro.product.model)"
  echo "build=$(getprop ro.build.display.id)"
  echo "target=${TARGET}"
  echo "original_sha256=${ORIGINAL_SHA}"
} > "${INFO}"

if [ "${PATCHED_COUNT}" = "1" ] && [ "${ORIGINAL_COUNT}" = "0" ]; then
  ui_print '- Target library already contains the LHDC V5 branch patch'
  echo "status=already_patched" >> "${INFO}"
  echo "patched_sha256=$(sha256sum "${OUT}" | awk '{print $1}')" >> "${INFO}"
  exit 0
fi

if [ "${ORIGINAL_COUNT}" != "1" ]; then
  ui_print "! Original pattern count: ${ORIGINAL_COUNT}"
  ui_print "! Patched pattern count: ${PATCHED_COUNT}"
  abort "! Unsupported libbluetooth_jni.so layout; refusing to patch"
fi

INDEX="$(find_pattern_index "${HEX_FILE}" "${ORIGINAL_HEX}")"
if [ "${INDEX}" -le 0 ]; then
  abort "! Failed to locate LHDC V5 branch pattern"
fi

PATCH_OFFSET=$(( (INDEX - 1) / 2 + 4 ))
ui_print "- Patch offset: 0x$(printf '%x' "${PATCH_OFFSET}")"
printf "${PATCH_BYTES}" | dd of="${OUT}" bs=1 seek="${PATCH_OFFSET}" conv=notrunc 2>/dev/null \
  || abort "! Failed to write patch bytes"

hex_dump "${OUT}" > "${PATCHED_HEX_FILE}"
if [ "$(count_pattern "${PATCHED_HEX_FILE}" "${PATCHED_HEX}")" != "1" ]; then
  abort "! Patch verification failed"
fi

PATCHED_SHA="$(sha256sum "${OUT}" | awk '{print $1}')"
{
  echo "status=patched"
  echo "offset_dec=${PATCH_OFFSET}"
  echo "offset_hex=0x$(printf '%x' "${PATCH_OFFSET}")"
  echo "patched_sha256=${PATCHED_SHA}"
} >> "${INFO}"

ui_print '- Patch verified'
