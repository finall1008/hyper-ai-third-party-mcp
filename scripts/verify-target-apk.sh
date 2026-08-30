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

DEX_STRINGS_FILE=$(mktemp "${TMPDIR:-/tmp}/xiaoai-dex-strings.XXXXXX")
trap 'rm -f "$DEX_STRINGS_FILE"' EXIT HUP INT TERM
unzip -p "$APK" 'classes*.dex' | strings > "$DEX_STRINGS_FILE"

echo "MCP injection is retired; native MCP markers are informational only."
if rg -F -q 'personal_mcp_servers.json' "$DEX_STRINGS_FILE"; then
    echo "Found native MCP marker: personal_mcp_servers.json"
else
    echo "Native MCP marker not found; use XiaoAi 8.2.3.1619 or newer for third-party MCP." >&2
fi

for MARKER in \
    'isExternalUserAsset' \
    'isCommandNameAllowed' \
    'checkToolRisk'; do
    if rg -F -q "$MARKER" "$DEX_STRINGS_FILE"; then
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
    if rg -F -q "$MARKER" "$DEX_STRINGS_FILE"; then
        echo "Found Agent Trace marker: $MARKER"
    else
        echo "Agent Trace marker not found: $MARKER; that capability may degrade independently." >&2
    fi
done

for MARKER in \
    'tool_selection_rules.md' \
    'custom_prompt.md' \
    'invalidateMemoryCache'; do
    if rg -F -q "$MARKER" "$DEX_STRINGS_FILE"; then
        echo "Found System Prompt patch marker: $MARKER"
    else
        echo "System Prompt patch marker not found: $MARKER; that capability may degrade independently." >&2
    fi
done

for MARKER in \
    'loadFromAssets' \
    'loadSplit' \
    'prompts/tools/' \
    'prompts/clawmemory/' \
    '===== systemPrompt ====='; do
    if rg -F -q "$MARKER" "$DEX_STRINGS_FILE"; then
        echo "Found auxiliary Prompt patch marker: $MARKER"
    else
        echo "Auxiliary Prompt patch marker not found: $MARKER; tool or memory Prompt patching may degrade independently." >&2
    fi
done

for MARKER in \
    'getFirstVisibleOutputTimeoutMs$runtime' \
    'LLM first-visible-output timeout after '; do
    if rg -F -q "$MARKER" "$DEX_STRINGS_FILE"; then
        echo "Found first-output timeout discovery marker: $MARKER"
    else
        echo "First-output timeout discovery marker not found: $MARKER; that capability may degrade independently." >&2
    fi
done

echo "Marker scan complete. Runtime structural resolution is still authoritative."
