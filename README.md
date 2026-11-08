# Krill Monitor

使用 Kotlin Multiplatform 和 Compose Multiplatform 开发的中转账户监控工具，支持 Android 与 Web（Kotlin/Wasm）。项目通过 Kotlin Toolchain 0.13.0 构建。

## 功能

- 账号模式：默认邮箱验证码登录，保留密码入口，支持后续 TOTP 二次验证；查询钱包、赠金及多份套餐。
- 概览、用量、线路、设置四页独立展示，手机底部图标导航，宽屏侧栏。
- 最近 7 / 30 天费用、请求数、模型排行；账号模式另显示失败请求、缓存写入与推理 Token。
- 海洋主题与原创 K 标识；费用趋势可点选日期，模型费用占比、套餐额度环及线路延迟条形图。
- 登录、验证码操作使用 Android 系统 Toast / Web 浮层提示；重发状态、数据缺失等必要说明继续保留。
- 线路探测始终匿名，应用不发送模型推理请求。

“记住登录”只保存账号会话令牌，不保存密码或验证码；会话失效时清除本地记录并要求重新登录。Android 用 Keystore AES-GCM 加密保存，并排除备份与设备迁移；Web 使用同源脚本可读的 localStorage。退出登录只清除本应用会话，不影响官网登录状态。升级时自动删除历史 Key 记录，保留现有账号会话。

钱包、赠金和套餐额度分别展示，统计日期与页面时间均使用北京时间 UTC+8。美元和 Credits 环图显示剩余比例，请求次数环图显示已用比例；周套餐保留本周额度，多周套餐显示官网的月额度，金额单位为 USD，未知值显示 —。月剩余使用官网有效额度，不以累计消耗反推。

账号查询缓存 5 分钟并验证身份；手动“刷新”重新请求数据，仍遵守服务端限流。未提供的数据和过期缓存保留提示。

## 运行与验证

根目录的 `kotlin` / `kotlin.bat` 会下载固定版本的工具链，无需另行安装 Kotlin Toolchain。

```sh
# Android：需要 Android SDK，运行应用时需要设备或模拟器
./kotlin run -m androidApp

# Web 账号模式：官网未开放 CORS，需同源转发（Node.js 22+）
./kotlin build
node tools/account-web.mjs 8080
# 打开 http://127.0.0.1:8080

# 构建两个平台
./kotlin build

# 核心测试使用模拟响应，不需要真实账号或验证码
./kotlin test -m shared --platform android
./kotlin test -m shared --platform wasmJs
node --test tools/account-web.test.mjs
```

`./kotlin build` 成功后，Android 调试安装包位于 `build/tasks/_androidApp_buildAndroidDebug/gradle-project-debug.apk`。

账号专用版本已通过 Android 29 项、Wasm 31 项、Node 代理 8 项测试；旧 Key 清理覆盖并存、仅旧 Key、重复清理及失败后仍恢复账号。无线 ADB 真机更新确认账号会话密文保持不变、设置页无 Key 入口。图表、Logo 和 Toast 保留，未触发真实验证码发送。真实字段与权限排查见[接口文档](docs/krill-api.md#官网接口排查费用与订阅提示)。

Android 直接访问固定官网 HTTPS；Web 通过随附服务同源转发白名单内的账号接口，不转发 Cookie、不跟随重定向、不记录请求正文。服务默认只监听本机，部署到其他设备访问时需在可信 HTTPS 反向代理中配置同样的转发规则。普通 `./kotlin run -m webApp` 不包含账号代理。

在“设置 → 账号登录 → 邮箱验证码”输入邮箱，点击“获取验证码”，再提交邮件中的验证码。重发倒计时取服务端返回值；未提供时间时不编造秒数。若官网强制人机验证，则提示在官网处理，不自动绕过。第三方 OAuth、企业工作区暂未接入；邮箱绑定或条款要求也需在官网完成。

应用随附 Noto Sans CJK SC 中文字体（约 16 MB），避免 Web 中文显示依赖外部字体下载。[字体来源和 SIL OFL 许可](shared/composeResources/files/NotoSansCJK-LICENSE.txt)。

## 目录

- `shared/src/`：共享界面、HTTP 客户端、接口模型和展示函数。
- `shared/test/`：接口、缓存、日期、金额和展示边界测试。
- `androidApp/`：Android 入口、清单和应用资源。
- `webApp/`：Web 入口、HTML 和样式。
- `project.yaml` / 各模块 `module.yaml`：模块、平台与构建配置。
- `libs.versions.toml`：第三方依赖版本。

接口路径、鉴权、参数、字段、限流及数据来源保存在 [接口文档](docs/krill-api.md)。
