#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /path/to/超级小爱_8.0.30.4121.apk" >&2
    exit 2
fi

APK=$1
EXPECTED=2e816d825ff61fd593e1d317c59987a064690172a164e4f22e112f69092c7d5f

if [ ! -f "$APK" ]; then
    echo "Target APK not found: $APK" >&2
    exit 1
fi

ACTUAL=$(shasum -a 256 "$APK" | awk '{print $1}')
if [ "$ACTUAL" != "$EXPECTED" ]; then
    echo "SHA-256 mismatch: $ACTUAL" >&2
    exit 1
fi

DEX_STRINGS=$(unzip -p "$APK" 'classes*.dex' | strings)
for MARKER in 'Ll8/w1;' 'syncConfigAndDiscoverIfNeeded' 'reloadConfig' 'personal_mcp_servers.json'; do
    if ! printf '%s\n' "$DEX_STRINGS" | rg -F -q "$MARKER"; then
        echo "Missing target marker: $MARKER" >&2
        exit 1
    fi
done

echo "Target APK hash and MCP hook markers verified."
