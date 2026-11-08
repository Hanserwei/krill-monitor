# Krill Monitor

使用 Kotlin Multiplatform 和 Compose Multiplatform 开发的中转账户监控工具，支持 Android 与 Web（Kotlin/Wasm）。项目通过 Kotlin Toolchain 0.13.0 构建。

## 第一版功能

- 手动输入一个中转 Key，选择海外、国内优化或三网 CDN 线路。
- 查询账户邮箱、套餐、额度、当月消费和到期时间。
- 展示最近 7 / 30 天的每日费用、请求数，以及模型费用与 Token 用量排行。
- 不携带 Key 探测三条线路的 HTTP 状态与耗时。
- 用量查询缓存 30 分钟；对不可用字段、部分结果、限流和过期缓存给出明确提示。

Key 仅保留在当前会话内存中，不写入本地存储；关闭、刷新页面或清除 Key 后需要重新输入。应用不发送模型推理请求。

账单接口的统计范围由服务端决定，可能是整个账户，不能当作单个 Key 的精确消费。剩余额度由订阅额度减去当月消费计算，不代表现金钱包余额。日期按 UTC 展示，金额单位为 USD。

## 运行与验证

根目录的 `kotlin` / `kotlin.bat` 会下载固定版本的工具链，无需另行安装 Kotlin Toolchain。

```sh
# Android：需要 Android SDK，运行应用时需要设备或模拟器
./kotlin run -m androidApp

# Web：浏览器需支持 WebAssembly GC
./kotlin run -m webApp

# 构建两个平台
./kotlin build

# 核心测试使用模拟 HTTP 响应，不需要真实 Key
./kotlin test -m shared --platform android
./kotlin test -m shared --platform wasmJs
```

`./kotlin build` 成功后，Android 调试安装包位于 `build/tasks/_androidApp_buildAndroidDebug/gradle-project-debug.apk`。

当前验证覆盖 Android / Wasm 两端各 16 个测试，以及 Web 桌面和手机宽度下的模拟查询、缓存、清除 Key、鉴权失败和中文字体加载。尚未使用真实 Key 联调或进行 Android 真机测试。

Web 直连受中转站 CORS 策略约束。若服务器未允许网页来源和 Authorization 请求头，浏览器端无法自行绕过；需要中转站放行或使用 Android 版。

应用随附 Noto Sans CJK SC 中文字体（约 16 MB），避免 Web 中文显示依赖外部字体下载。[字体来源和 SIL OFL 许可](shared/composeResources/files/NotoSansCJK-LICENSE.txt)。

## 目录

- `shared/src/`：共享界面、HTTP 客户端、接口模型和展示函数。
- `shared/test/`：接口、缓存、日期、金额和展示边界测试。
- `androidApp/`：Android 入口、清单和应用资源。
- `webApp/`：Web 入口、HTML 和样式。
- `project.yaml` / 各模块 `module.yaml`：模块、平台与构建配置。
- `libs.versions.toml`：第三方依赖版本。

接口路径、鉴权、参数、字段、限流及数据来源保存在 [接口文档](docs/krill-api.md)。
