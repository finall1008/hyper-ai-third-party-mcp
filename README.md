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
其他版本会根据稳定 MCP 方法、协程签名和配置对象结构进行运行时探测，只有候选唯一时才安装对应 Hook。
文本配置、对象配置和在线重载会独立降级；无法可靠匹配任何配置读取路径时，模块拒绝安装 Hook 并保留宿主原行为。

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

模块同时覆盖文本配置读取和冷启动对象配置读取，并只对三个直接读取个人 MCP 配置的方法执行定点 deoptimize，以避免 ART 内联绕过配置 Hook；
不会对整个超级小爱进程做全局反优化。

服务器名称会作为工具前缀，必须使用字母、数字、点、下划线或连字符，且不能包含 `__`。

## 数据与安全边界

- 配置存储在 Xposed 框架的 Remote Preferences 中；目标进程只能读取。
- 配置只在宿主读取时内存合并，不会写入超级小爱的私有 `mcp_servers.json`。
- Request headers 可用于 Bearer/API Key。界面默认遮挡 header 值，模块日志只记录 header 数量。
- Root 和 Xposed 框架本身仍然能够访问这些凭据；不要把 Remote Preferences 当作硬件级密钥存储。
- 通用 OAuth、stdio、本地进程启动以及 MCP 子系统被彻底重写后的适配不在当前范围内。

## 日志与排查

```sh
adb logcat | rg 'XiaoAiMcpBridge|PersonalMcpManager|McpClient'
```

预期可看到 Hook 安装、服务器数量、reload 完成以及宿主 MCP 连接/发现日志，但不会出现请求头值。

可对目标 APK 做离线标记诊断（此脚本不再校验固定版本或哈希）：

```sh
./scripts/verify-target-apk.sh /path/to/超级小爱.apk
```

脚本通过只说明自动探测所依赖的稳定标记仍存在；最终能力以目标进程中的解析和 Hook 日志为准。
