# 超级小爱 MCP Bridge

这是一个严格使用 Modern libxposed API 102 的 LSPosed 模块，为超级小爱
`8.0` 及以上版本注入第三方 Streamable HTTP 或 SSE MCP 服务器。它不会修改或重签名超级小爱 APK。

## 兼容性

- 目标包：`com.miui.voiceassist`
- 最低目标版本：`8.0`（按 `versionName` 主版本判断）
- 已验证参考版本：`8.0.30.4121`（versionCode `508000030`）
- Xposed API：`minApiVersion=102`、`targetApiVersion=102`
- 不兼容经典 LSPosed 1.9.x，也不支持 stdio MCP

配置 App 和注入端都会拒绝低于 `8.0` 或版本名无法识别的目标。参考版本优先使用已验证签名；
其他 `8.0+` 版本会根据稳定方法、协程签名和对象结构进行运行时探测，只有候选唯一时才安装对应 Hook。
MCP 文本配置、对象配置、在线重载、System Prompt 补丁、既有文件删改、锁屏文件访问和文件风险确认会独立发现、独立降级；
某项无法可靠匹配时不会按版本号禁用其他已匹配能力，也不会改变宿主原有限制。

已验证类名全部匹配时直接走快速路径，不加载 DexKit。快速路径缺失时，模块会创建一次
DexKit 2.2.0 解析会话，使用 `personal_mcp_servers.json`、稳定方法族和调用元数据缩小候选范围，
关闭解析会话后再由现有反射规则校验唯一性。DexKit 不可用、无结果或结果不唯一时会回退到
完整 DEX 结构扫描；没有真实目标 APK 和运行日志前，不会仅凭辅助发现宣称兼容某个新版本。

## 构建

需要 JDK 21、Android SDK 37 和 Build Tools 36.0.0：

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 使用

1. 安装模块 APK，在支持最终 API 102 的 LSPosed 运行时中启用模块。
2. 作用域只选择“超级小爱”（`com.miui.voiceassist`），然后结束并重新启动超级小爱进程。
3. 打开模块 App，确认框架 API 可用；参考版本显示绿色，其他 `8.0+` 版本显示等待运行时探测的黄色状态。
4. 添加 MCP 服务器；`http` 表示 Streamable HTTP，`sse` 表示旧式 SSE。
5. 保存后，运行中的超级小爱会注销旧工具并在线重新发现服务器；若超级小爱未运行，下次启动时自动生效。

### System Prompt 补丁

模块 App 的“System Prompt 补丁”会在超级小爱读取 Prompt 文件时执行内存中的精确文本替换，
不会覆盖或写入超级小爱的私有 Prompt 文件。首次使用时会从仓库中的
`app/src/main/resources/prompt/default_prompt_patches.json` 加载并启用一组普通规则：

- MCP provider help 后，调用具体工具前继续查看该工具的 help 和最新 schema；
- `help → call`、`lookup → action` 等存在数据依赖的步骤串行执行；
- 完整遵守 JSON Schema，包括把 schema 声明为 string 的经纬度传为 JSON 字符串；
- 区分参数、权限、网络、临时服务等错误，不因两次失败就断言能力永久不可用。

默认规则没有独立开关，会直接显示在规则列表中，可与后来添加的规则一样逐条编辑、停用或删除。
规则可指定精确 Agent ID 或 `*`、Prompt 文件名、查找原文及替换内容。查找原文必须恰好出现一次；
零匹配或多匹配会跳过该条规则并写入不含正文的诊断日志。所有规则按列表顺序执行，替换内容留空可删除原文。
页面直接读取当前已安装超级小爱 APK 中的 `assets/agents/<agent-id>/<file-name>` 并用同一个补丁引擎本地模拟。无需启动超级小爱或新建会话；
若规则涉及多个 Agent/Prompt 文件，预览会先列出全部目标。完整文件中未修改行正常显示，删除行原位标红，新增行原位标绿。配置保存后模块会尝试清除宿主 Prompt 内存缓存，通常从下一次新会话生效；
若目标版本无法定位缓存刷新接口，需重启超级小爱。

该功能只处理宿主通过通用 Prompt 文件解析器加载的文本，不声称覆盖所有运行时硬编码或动态生成的 system context。

### 文件访问扩权

在模块 App 的“文件访问权限”中添加一个或多个目录规则，然后打开总开关。每条规则可分别授权：

- 删除、修改、移动或覆盖目录内的既有文件；
- 锁屏时读取文件；
- 锁屏时新建、删改或移动文件；
- 后台或定时 Agent 删改既有文件；
- 锁屏命令递归删除目录。

每条规则还可选择文件删改的操作确认策略：

- “每次询问”保留宿主确认框；
- “仅后台/定时自动允许”只让已授权后台 Agent 无交互执行，前台仍询问；
- “所有 Agent 自动允许”对前台和后台都跳过该目录的文件确认。

自动允许只影响文件编辑、删除和移动等文件风险类别；短信、电话、邮件等其他宿主授权不受影响。
操作涉及多个路径时，每个外部源路径和目标路径都必须命中允许自动确认的规则。
从旧版规则升级时默认保持“每次询问”，不会自动改变已有授权。

规则只接受 `/sdcard` 或等价的 `/storage/emulated/0` 绝对路径。运行时使用规范化真实路径和最长目录匹配，
因此可以用更具体的子目录规则覆盖上层规则，包括用一条不授予任何能力的子目录规则显式拒绝。
不存在、非目录、越界或无法规范化的路径不会获得扩权。
规则保存后由目标进程在每次权限判定时读取，通常无需重启超级小爱。

模块同时覆盖文本配置读取和冷启动对象配置读取，并只对三个直接读取个人 MCP 配置的方法执行定点 deoptimize，以避免 ART 内联绕过配置 Hook；
不会对整个超级小爱进程做全局反优化。

服务器名称会作为工具前缀，必须使用字母、数字、点、下划线或连字符，且不能包含 `__`。

## 数据与安全边界

- 配置存储在 Xposed 框架的 Remote Preferences 中；目标进程只能读取。
- 配置只在宿主读取时内存合并，不会写入超级小爱的私有 `mcp_servers.json`。
- Prompt 补丁只修改宿主读取结果，不会写入 `prompt.md`、`tool_selection_rules.md` 或其他宿主 Prompt 文件。
- Request headers 可用于 Bearer/API Key。界面默认遮挡 header 值，模块日志只记录 header 数量。
- Root 和 Xposed 框架本身仍然能够访问这些凭据；不要把 Remote Preferences 当作硬件级密钥存储。
- 文件扩权不会额外绕过 Android/Linux 权限、Scoped Storage 或文件系统挂载限制；用户规则允许但宿主进程本身无法访问的路径仍会由系统拒绝。
- 模块不使用宿主的全局 `osbot_bypass_tool_confirm` 开关；目录确认策略只放行匹配路径的文件风险操作。
- 通用 OAuth、stdio、本地进程启动以及 MCP 子系统被彻底重写后的适配不在当前范围内。

## 日志与排查

```sh
adb logcat | rg 'XiaoAiMcpBridge|PersonalMcpManager|McpClient'
```

预期可看到 Hook 安装、服务器数量、reload 完成、Agent Trace 能力解析以及宿主 MCP
连接/发现日志，但不会出现请求头值或 Agent 工具输入输出正文。

Agent Trace 沿用超级小爱现有的“思考过程”卡片：公开 reasoning 会保持原顺序，工具摘要
可以继续展开查看完整输入、输出、错误、耗时和元数据。Trace 目标同样按已验证 profile、
DexKit 定向发现和完整 DEX 结构扫描顺序解析；新版 `8.0+` 不会仅因版本号被拒绝。
模块 App 中的“Agent 完整轨迹”开关默认开启；关闭后不会安装 reasoning、工具详情或 RN bundle
相关 Hook，且不影响 MCP 与文件策略。切换后需要结束并重新启动超级小爱进程才会生效。

可对目标 APK 做离线标记诊断（此脚本不再校验固定版本或哈希）：

```sh
./scripts/verify-target-apk.sh /path/to/超级小爱.apk
```

脚本通过只说明自动探测所依赖的稳定标记仍存在；文件策略或 Prompt 标记缺失时会单独警告，不会否定 MCP 能力。
最终能力以目标进程中的解析和 Hook 日志为准。
