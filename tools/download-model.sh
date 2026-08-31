#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLUGIN_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET="$PLUGIN_ROOT/src/android/assets/mobilefacenet.tflite"
URL="https://github.com/hugocornellier/face_detection_tflite/raw/refs/heads/main/assets/models/mobilefacenet.tflite"

curl -L --fail --retry 3 "$URL" -o "$TARGET"
SIZE="$(wc -c < "$TARGET" | tr -d ' ')"

if [ "$SIZE" -lt 4000000 ]; then
  rm -f "$TARGET"
  echo "Downloaded file is too small ($SIZE bytes)." >&2
  exit 1
fi

echo "OK: $TARGET ($SIZE bytes)"
