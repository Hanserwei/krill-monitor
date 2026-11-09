# Krill Monitor

Krill Monitor 是使用 Kotlin Multiplatform 和 Compose Multiplatform 开发的 Android / Web 账户监控工具，是独立第三方客户端，并非 Krill 官方产品。

## 功能

| 功能 | Android | Web |
| --- | --- | --- |
| 邮箱验证码或密码登录、TOTP 二次验证 | ✓ | ✓ |
| 钱包、赠金及多份套餐 | ✓ | ✓ |
| 最近 7 / 30 天费用、请求、失败数、Token 和模型排行 | ✓ | ✓ |
| 匿名线路探测与公开渠道状态 | ✓ | ✓ |
| API Key 创建、重命名、轮换、撤销、永久删除与渠道配置 | ✓ | 密钥入口引导使用 Android 或 [官网](https://www.krill-code.com/)；未启用浏览器密钥库 |

线路页匿名探测三条线路的 `/v1/models`，并展示官网公开状态，包括按渠道分组的模型、最近 24 小时历史和可用的性能数据；公开请求不携带账号 JWT，不发送模型推理请求。Web 延迟包含浏览器到本机同源代理、再到线路上游的往返开销；Android 直接探测线路。缺失的性能数据显示为未知。线路延迟不等于模型首 Token 时间（TTFT）。统计日期使用北京时间 UTC+8。

Android Key 管理支持渠道多选、默认配置、倍率及互斥规则。新建 Key 加密保存后，可先保存渠道配置或跳过，再通过系统认证查看；每次查看或复制都需要强生物识别或设备 PIN 等系统认证。官网已有 Key 若未在本机保存副本，无法找回完整值。

## 下载与安装

从 [GitHub Releases](https://github.com/Hanserwei/krill-monitor/releases/latest) 下载正式签名 APK。Android 应用最低支持 Android 7.0（API 24）；本地 Key 库需要 Android 11（API 30）及以上，并设置安全锁屏。发行文件附带 SHA256 校验值和签名证书 SHA256 指纹。

正式版与已安装的调试版签名不同，不能直接覆盖升级。卸载会删除本地 Key 库；请先妥善保留需要的 Key，不要自动卸载调试版。之后使用相同正式签名且更高 versionCode 的版本可以正常升级。

## 本地运行与构建

需要 JDK 25、Android SDK 37（平台包名 `platforms;android-37.0`）和 Build Tools 37.0.0；Web 本地代理需要 Node.js 26.13.0 LTS，版本由 `.node-version` 固定。使用 fnm 时可在项目目录运行 `fnm install` 和 `fnm use`。根目录的 `kotlin` / `kotlin.bat` 使用固定的 Kotlin Toolchain 0.13.0，并自动下载对应 SDK 包。

```sh
# Android：连接设备或启动模拟器
./kotlin run -m androidApp

# 构建两端；启动 Web 同源代理后访问 http://127.0.0.1:8080
./kotlin build
node tools/account-web.mjs 8080

# 本地未签名的 Android release 构建
./kotlin build -m androidApp --variant release

# 测试使用模拟响应，不需要真实账号或验证码
./kotlin test -m shared --platform android
./kotlin test -m shared --platform wasmJs
node --test tools/account-web.test.mjs tools/release-apk.test.mjs
```

Android 调试 APK 位于 `build/tasks/_androidApp_buildAndroidDebug/gradle-project-debug.apk`。官网未开放 Web 所需的跨域访问；随附 Node 服务提供同源代理，默认仅监听本机。普通 `./kotlin run -m webApp` 不包含账号代理。Android 直接访问官网 HTTPS。

## 正式签名与发布

签名密钥不得提交仓库；在用户工作区外安全保存 PKCS#12 密钥库，固定别名为 `krill-monitor`，密钥库密码和密钥密码相同。设置 `ANDROID_HOME`、`ANDROID_KEYSTORE_PATH` 和 `ANDROID_KEYSTORE_PASSWORD` 后运行：

```sh
./tools/release-apk.sh v0.2.2
```

脚本在 `dist/` 生成 `krill-monitor-0.2.2.apk`、`SHA256SUMS` 和 `ANDROID-SIGNING-CERT.txt`。保管好密钥库及密码，后续版本必须使用相同签名才能覆盖升级。

GitHub Actions 对推送 `master`、Pull Request 和标签运行检查。发布前在仓库 Secrets 配置 `ANDROID_KEYSTORE_BASE64`（PKCS#12 文件的 Base64）和 `ANDROID_KEYSTORE_PASSWORD`；推送 `v*` 标签后，检查通过的发布流程签名 APK，使用 `docs/releases/<标签>.md` 创建 GitHub Release。发布新版本前应更新版本号、versionCode 和对应发行说明。

## 数据与限制

“记住登录”只保存账号会话令牌，不保存密码或验证码。Android 使用 Keystore 加密并排除备份与设备迁移；Web 使用同源脚本可读取的 localStorage。退出登录只清除本应用会话。Android Key 密文按官网用户隔离，不参与备份或设备迁移；卸载应用、移除安全锁屏或重置安全密钥可能导致本地副本不可恢复。

账号数据缓存 5 分钟，公开渠道状态缓存 60 秒；手动刷新仍遵守服务端 429 限流。第三方 OAuth、企业工作区、白名单、消费限额以及余额回退配置编辑暂未实现。需要官网完成人机验证、邮箱绑定或条款确认时，请在官网处理。

[接口研究和历史联调记录](docs/krill-api.md)记录数据来源和接口限制，不能视为服务端协议保证。

## 许可证

本项目代码按 [MIT License](LICENSE) 授权，第三方组件遵循各自许可证，详见 [第三方声明](THIRD_PARTY_NOTICES.md)。随附中文字体的完整 [SIL OFL 许可](shared/composeResources/files/NotoSansCJK-LICENSE.txt)保留在原处。项目内设计的 K 标识按本项目 MIT 许可使用；Krill 及其他服务商商标的权利属于各自权利人。
