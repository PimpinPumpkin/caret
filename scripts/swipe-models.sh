#!/bin/sh
# Downloads the FUTO Swipe models (FUTO Model Weights License 1.0, see swipe/models/LICENSE.md)
# into swipe/models, and with "install" copies them into the debug app on a device.
#   scripts/swipe-models.sh              download only
#   scripts/swipe-models.sh install      download, then copy to the device in ANDROID_SERIAL
set -e
cd "$(dirname "$0")/.."
BASE=https://huggingface.co/futo-org/futo-swipe/resolve/main
OUT=swipe/models
for f in LICENSE.md honorable_sturgeon/metadata.json honorable_sturgeon/model_fp32.pte \
         magic_macaw/metadata.json magic_macaw/model_fp32.pte \
         hungry_jellyfish/metadata.json hungry_jellyfish/context_lm.pte hungry_jellyfish/vocab.txt; do
    mkdir -p "$OUT/$(dirname "$f")"
    [ -s "$OUT/$f" ] || curl -fsSL -o "$OUT/$f" "$BASE/$f"
done
if [ "$1" = install ]; then
    PKG=${CARET_PACKAGE:-app.caret.debug}
    adb shell rm -rf /data/local/tmp/swipe_models
    adb push "$OUT" /data/local/tmp/swipe_models >/dev/null
    adb shell "run-as $PKG sh -c 'mkdir -p files && rm -rf files/swipe_models && cp -r /data/local/tmp/swipe_models files/'"
    adb shell am force-stop "$PKG"
    echo "models installed for $PKG"
fi
