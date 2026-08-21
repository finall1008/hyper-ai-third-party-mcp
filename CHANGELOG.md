# Changelog

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
