#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /path/to/超级小爱.apk" >&2
    exit 2
fi

APK=$1

if [ ! -f "$APK" ]; then
    echo "Target APK not found: $APK" >&2
    exit 1
fi

DEX_STRINGS=$(unzip -p "$APK" 'classes*.dex' | strings)
for MARKER in \
    'syncConfigAndDiscoverIfNeeded' \
    'reloadConfig' \
    'loadCatalogAndRegister' \
    'personal_mcp_servers.json'; do
    if ! printf '%s\n' "$DEX_STRINGS" | rg -F -q "$MARKER"; then
        echo "Missing compatibility marker: $MARKER" >&2
        exit 1
    fi
    echo "Found compatibility marker: $MARKER"
done

if printf '%s\n' "$DEX_STRINGS" | rg -F -q 'Ll8/w1;'; then
    echo "Found verified-profile manager marker: Ll8/w1;"
else
    echo "Verified-profile manager marker not found; runtime structural discovery is required."
fi

for MARKER in \
    'isExternalUserAsset' \
    'isCommandNameAllowed' \
    'checkToolRisk'; do
    if printf '%s\n' "$DEX_STRINGS" | rg -F -q "$MARKER"; then
        echo "Found file-policy discovery marker: $MARKER"
    else
        echo "File-policy discovery marker not found: $MARKER; that capability may degrade independently." >&2
    fi
done

for MARKER in \
    'reasoning_delta' \
    'reasoningContent' \
    'tool_call_id' \
    'tool_done' \
    'ToolCallItem' \
    'MICLAW_THINKING_CHAIN' \
    'loadScript' \
    '__reactNativeBundleEndSuccess__'; do
    if printf '%s\n' "$DEX_STRINGS" | rg -F -q "$MARKER"; then
        echo "Found Agent Trace marker: $MARKER"
    else
        echo "Agent Trace marker not found: $MARKER; that capability may degrade independently." >&2
    fi
done

echo "Compatibility markers found. Runtime structural resolution is still required."
