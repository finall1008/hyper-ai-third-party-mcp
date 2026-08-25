# Changelog

## Unreleased

## 1.3.0 - 2026-08-25

- Add independently discovered Agent Trace hooks for public MiMo reasoning and tool payloads,
  including per-tool input/output expansion in the existing MiClaw thinking chain.
- Discover Agent Trace targets through the verified profile, DexKit hints, and full structural
  fallback across all supported XiaoAi 8.0+ versions.
- Patch only a private content-addressed copy of the host Stream RN bundle; incompatible bundle
  shapes fall back to the original host UI without affecting MCP or file-policy hooks.
- Add an independent, default-on Agent Trace switch. Disabling it skips reasoning, tool-detail,
  and RN bundle hooks without changing MCP or file-policy behavior; changes apply after restarting
  XiaoAi.

## 1.2.0 - 2026-08-24

- Add DexKit 2.2.0 as an on-demand candidate index between the verified profile and the existing
  full structural scan, while keeping reflection uniqueness checks authoritative.
- Resolve MCP marker, lifecycle, object-container, mutation, lockscreen, and confirmation class
  candidates in one short-lived DexKit session and independently fall back per capability.
- Log only candidate counts, resolver source, and elapsed time; do not persist DexKit results or
  claim compatibility with an untested XiaoAi build.
- Remove the obsolete release-time rejection of packaged `kotlin.coroutines` classes now that the
  Compose configuration UI legitimately depends on the Kotlin coroutine runtime.
- Migrate the configuration App to MIUIX Compose with native navigation, hosted dialogs,
  edge-to-edge insets, accessible bold text, and scroll-safe rule actions.
- Add user-controlled `/sdcard` directory rules for mutating existing files, lockscreen file
  access, background/timer mutation, and lockscreen recursive deletion.
- Resolve the file-storage and lockscreen policy targets with the same verified fast path plus
  unique structural discovery used for newer XiaoAi 8.0+ versions; each capability fails closed
  without disabling independently resolved MCP or file capabilities.
- Canonicalize every requested path and apply the most-specific user-configured directory rule,
  including explicit deny rules for narrower subdirectories.
- Add per-directory confirmation modes for asking every time, automatically authorizing only
  background/timer agents, or automatically authorizing all agents. Existing version 1 rules
  migrate to ask-every-time behavior.
- Discover and hook the host file-risk exemption independently, requiring every source and
  destination path to match the selected confirmation mode without enabling the host's global
  confirmation bypass.

## 1.1.1 - 2026-08-24

- Derive the host coroutine continuation type from the resolved MCP methods instead of loading a
  reflection class name that R8 could rewrite to a module-local test stub.
- Require the reload and catalog suspend methods to share the same parameter type and fail closed
  on ambiguous overloads or mismatched coroutine signatures.
- Remove the fake `kotlin.coroutines.Continuation` test class and add a regression test proving
  resolution does not perform a named Kotlin continuation lookup.
- Build the live-reload continuation and empty coroutine context from the host method interfaces,
  avoiding R8-rewritten lookups of Kotlin runtime implementation classes and suspended markers.
- Run unit tests and lint in the release job and reject minimized artifacts that contain a mapped
  module-local `kotlin.coroutines` runtime class.

## 1.1.0 - 2026-08-23

- Replace the exact XiaoAi versionCode gate with a minimum `8.0` versionName policy in both
  the configuration app and injected process.
- Keep `8.0.30.4121` as a verified fast-path profile and add safe structural discovery across
  base and split DEX files for relocated MCP classes and config readers.
- Install text, object, and live-reload capabilities independently, failing closed on ambiguous
  targets and degrading to the next startup when live reload cannot be resolved.
- Turn the target APK verifier into a version-independent compatibility marker diagnostic.

## 1.0.3

- Remove the runtime lookup of an optimized-away `static final` version field.
- Ignore package callbacks from XiaoAi auxiliary processes after detaching them.
- Merge module servers into both the text (`J()`) and object (`A()`) personal MCP config paths.
- Target-deoptimize `syncConfigAndDiscoverIfNeeded`, `reloadConfig`, and
  `loadCatalogAndRegister` so ART inlining cannot bypass the config hooks.
- Verified on a Xiaomi Android 17 device with LSPosed API 102:
  `didi-mcp` connected, discovered 13 tools, registered 13/13, appeared in the model's MCP list,
  and received an explicit `didi-mcp__taxi_estimate` tool call.

## 1.0.0

- Initial API 102 module, Remote Preferences configuration UI, HTTP/SSE support, and text config hook.
