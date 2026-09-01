# Changelog

## Unreleased

## 1.7.0 - 2026-09-01

- 新增模块内 Agent Trace 页面，只记录升级后新产生的会话，并按 Turn 展示有效 System Prompt、
  UserInput、conversation、工具目录、reasoning、模型输出、重试、错误及完整工具调用生命周期。
- 新增受调用方 UID/包名限制的流式轨迹写入 Provider 和模块私有 SQLite 事件库；采集在有界后台队列中完成，
  不阻塞 Agent 回调，发生丢失时以 GAP 事件明确标记轨迹不完整。
- 新增默认 30 天或 100 会话的保留策略，天数和会话数可分别自定义或设为不限制；支持搜索、类型筛选、
  原始 JSON、逐块复制、删除单个会话和清空全部轨迹。
- 将 Agent Trace 作为模块底栏独立 Tab；详情页使用原生顶部删除 Action，事件卡支持整卡点击展开和折叠，
  原始 JSON 与复制操作统一为 MIUIX 图标按钮。
- 修复 Android 17 ART 优化接口调用点后可能绕过 executor Hook，以及 `Application.attach` 阶段
  Application Context 尚未就绪导致首批轨迹无法写入的问题；已在超级小爱 `8.2.3.1619` 实机验证。

## 1.6.0 - 2026-08-30

- 移除模块内的第三方 MCP 编辑、运行时配置注入、管理器捕获、定点反优化和在线重载；
  超级小爱 `8.2.3.1619` 起改用原生 MCP，旧版模块配置仅保留只读 JSON 导出迁移入口。
- 适配 `8.2.3.1619` 扩展后的文件风险确认签名，按风险上下文、continuation 和确认方法族的共同结构解析，
  不再依赖旧版固定参数个数；候选歧义时继续显示宿主确认框。
- 将锁屏 CLI 放行改为逐命令参数解析，要求绝对路径并拒绝未知选项、shell 组合语法、`find -exec`、
  不安全 `sed` 脚本等；正确识别 `rm -rv` 等递归短参数组合和 `cp`/`mv` 目标目录参数。
- 统一 `/storage/emulated/0` 与 `/sdcard` 规则并拒绝别名重复，消除等长路径规则的顺序歧义。
- 为 Agent 工具调用关联记录增加 512 条上限和 30 分钟 TTL，为初始化对象集合增加上限，
  RN 私有 bundle 缓存最多保留 3 个版本。
- 更新 `8.2.3.1619` 的 Agent Trace 已验证入口，优先 Hook 主界面实际调用的三参数 RN bundle 加载重载，
  恢复公开 reasoning、初始化推理标记和工具调用详情；同时兼容新版带来源参数的 reasoning 流处理方法，
  并保留旧版已验证入口作为回退。
- 发布版本升级为 `1.6.0`，同步更新兼容性、迁移说明和离线诊断；CI/Release Actions 固定到提交 SHA，
  发布前校验标签、APK 版本与 Changelog 条目一致。

## 1.5.0 - 2026-08-28

- 将 Prompt 补丁配置升级为版本 3，支持可预览、编辑和停用的 Agent Prompt、工具 Prompt 与记忆 Prompt 目标；
  版本 1/2 配置会保留原有规则并只追加本版本新增默认项。
- 修正内置 CLI 帮助与结果处理规则：参数化或写入命令先读取准确 help，工具外层成功不再等同于业务完成。
- 新增可覆盖默认策略的用户偏好优先级，并调整 MemoryGate：存在多个合理工具路由且长期偏好可能改变选择时检索完整记忆；
  安全、权限、确认和真实能力边界保持不可覆盖，DirectPath 行为保持不变。
- 新增工具说明与 ClawMemory 模板的独立 Hook 发现能力，按已验证目标、DexKit 和结构扫描逐项降级。

## 1.4.0 - 2026-08-26

- 新增可配置的模型首次可见输出超时，支持跟随宿主、自定义秒数和不限制三种模式；
  目标通过已验证配置、DexKit 和结构扫描发现，无法唯一匹配时保持宿主原有行为。
- 重构 MIUIX 配置 App，拆分首页与关于页面，统一大标题导航并重新组织设置分组；
  新增需要 Root 权限的超级小爱重启功能。
- 模块和 App 的显示名称改为“超级小爱增强”，以覆盖 MCP、Agent、Prompt、超时和文件权限等增强能力；
  application ID 保持不变。
- 新增面向所有 Agent 的独立 System Prompt 内存补丁功能，使用 Remote Preferences 保存精确查找替换规则；
  查找内容不是唯一匹配时跳过规则，不修改宿主 Prompt 文件。
- 为已验证的超级小爱 Prompt 内置普通且默认启用的可靠性规则，要求按工具查看 MCP 帮助、
  按数据依赖顺序执行步骤、完整遵守 JSON Schema 类型并分类处理失败；每条规则均可编辑、停用或删除。
- Prompt 文件读取目标依次通过已验证配置、DexKit 提示和唯一结构回退解析；配置变更后，
  在宿主能力可用时使 Prompt 缓存失效。
- 新增 MIUIX System Prompt 补丁配置页，可直接读取当前安装的超级小爱 APK 并显示完整 Prompt 差异；
  支持多个 Agent 和文件目标，保留未修改行，删除内容标红、新增内容标绿，无需启动宿主会话或刷新。
- 将版本 1 中的内置预设标记迁移为版本 2 的普通补丁条目。

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
