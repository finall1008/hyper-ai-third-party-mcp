# Changelog

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
