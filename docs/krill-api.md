# Krill 中转接口与监控数据来源

本文依据 Superba 上游源码的固定版本 [`3ecc0db00f0b536ee070d6cb1c56e01566b3115a`](https://github.com/krill-lab/superba/tree/3ecc0db00f0b536ee070d6cb1c56e01566b3115a) 整理，是客户端使用方式的记录，不是服务端协议保证。末尾记录了实际只读联调结果；权限与统计归属仍需以服务端契约为准。

当前应用仅保留账号登录。下文 Key 兼容接口为早期研究存档，已不再由应用调用；运行时只使用官网账号接口和匿名线路探测。历史本地 Key 在升级启动时清理，现有账号会话保留。

## 线路与鉴权

三条内置线路的 origin 分别是国内优化 `https://api-slb.kbsfdxc.net`、海外 `https://api.kbsfdxc.net`、三网优化 CDN `https://api.kbsfdxc.com`。接口路径拼接到选定的 origin；账户及用量接口使用 `Authorization: Bearer <key>`。不要将 Key 写入日志、URL 或文档。[线路定义](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-core/src/catalog.rs#L26-L41)、[鉴权和接口概览](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-core/src/usage.rs#L1-L15)。

以下接口均为只读 GET；响应字段为原始 JSON 的关键字段，可能缺失。源码没有发现列出全部 Key 的调用，也不能证明返回数据按每个 Key 隔离：组织级用量及账单可能反映更大范围，不能将其标记为单 Key 精确消费。

## 账户与用量接口

| 接口 | 参数与响应关键字段 | 用途和注意事项 |
| --- | --- | --- |
| `/v1/user-auth-credential/whoami` | `chatgpt_user_id`、`chatgpt_account_id`、`chatgpt_plan_type`、`chatgpt_account_is_fedramp`；`email` 可选 | 身份和 Key 检查。源码指出 Krill 的 `chatgpt_plan_type` 是为 Codex 提供的常量，不能视为实际套餐。[解析](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/classify.rs#L271-L310) |
| `/v1/dashboard/billing/subscription` | `object`、`hard_limit_usd`、`soft_limit_usd`、`system_hard_limit_usd`、`access_until`、`has_payment_method`、`plan.title` / `id` | 限额以美元计；`access_until` 为 Unix 秒，0 表示无到期时间。实际套餐以此接口提供的 plan 为准，缺失则未知。`hard_limit_usd` 缺失时客户端可取 `system_hard_limit_usd`；不小于 100,000,000 美元视为 unlimited 标记。缺失时回退到 `/dashboard/billing/subscription`。[字段和解析](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/account.rs#L1-L23)、[限额回退](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/account.rs#L104-L138) |
| `/v1/dashboard/billing/usage` | `start_date=YYYY-MM-DD&end_date=YYYY-MM-DD`；`object`、`total_usage` | `total_usage` 是**美分**，除以 100 才是美元；日期为 UTC 左闭右开区间，源码说明最多 100 天。客户端查询当月 UTC 月初至明日零点，含今天；缺失时回退到 `/dashboard/billing/usage`。[协议](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/account.rs#L7-L20)、[日期计算](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/account.rs#L142-L162) |
| `/v1/organization/costs` | `start_time`、`end_time`（Unix 秒）、`bucket_width=1d`、`limit`，分页时 `page`；`data[].start_time`、`end_time`、`results[].amount.value`、`amount.currency`、`line_item`、`project_id`，以及 `has_more`、`next_page` | 日费用，`amount.value` 是美元；客户端只汇总 USD，忽略其他明确标明的币种。[响应](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/org.rs#L1-L10)、[币种处理](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/org.rs#L117-L134) |
| `/v1/organization/usage/completions` | 上述时间、桶宽、limit、page 参数，另加 `group_by=model`；`data[].results[]` 中有 `model`、`input_tokens`、`input_cached_tokens`、`output_tokens`、`num_model_requests`、`cost_usd` | 按模型汇总 token、请求及费用；`cost_usd` 是 Krill 扩展，单位美元。成本接口不可用时可按日合计此字段，但字段缺失不能当作零。[响应](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/org.rs#L1-L16)、[费用来源选择](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/org.rs#L168-L176) |

账单解析也接受数值字符串及 `data` 对象包装。两个 billing 路径各自只有在路径缺失时才尝试无 `/v1` 版本，usage 优先使用 subscription 成功的路径族。[解析](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/account.rs#L16-L20)、[请求和回退](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/fetch.rs#L160-L221)。

剩余额度是 `max(限额 − 当月费用, 0)` 的推导值，不是钱包余额；限额或当月费用缺失时，剩余额度也未知。[计算依据](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-core/src/usage.rs#L192-L197)。

组织接口查询以 UTC 天为单位，时间区间为 `[start_time, end_time)`；Superba 将 `limit` 设为查询天数，根据 `has_more` 和 `next_page` 传入 `page`。每个接口最多追踪 3 页、查询最多 31 天，这是 **Superba 客户端限制**，不是已证实的服务端限制；达到页数上限仍有后续页时结果可能不完整。[查询](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/fetch.rs#L355-L400)、[参数](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/fetch.rs#L419-L447)、[天数限制和区间](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-core/src/usage.rs#L275-L328)。源码称用量接口按用户限制为每小时 10 次请求；一次完整刷新要分别请求 costs 和 usage，分页的每次请求另计，不应理解为每小时可完整刷新 10 次。[限流说明](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/org.rs#L12-L16)、[分页计数](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/fetch.rs#L25-L28)。

Superba 对 HTTP 429 记录限流提示，仅解析 `Retry-After` 的整数秒形式；刷新遇限流或其他失败时，有旧缓存就返回旧数据并附提示，无缓存时如果另一端点成功则返回带提示的部分结果，否则返回错误。普通用量缓存有效期为 600 秒，手动刷新有 60 秒去抖；本文第一版的 30 分钟是更保守的目标，并非上游现有 TTL。[429 和 Retry-After](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/fetch.rs#L60-L62)、[结果和缓存](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/fetch.rs#L450-L499)、[上游缓存期限](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-core/src/usage.rs#L286-L294)。

## 可选能力探测

以下路径均相对于所选线路 origin；PAT 或 Key 鉴权按所选模式使用。`<base>` 是推理基址，例如 `/backend-api/codex` 或 API Key 模式的 `/codex/v1`，将它替换进下表后得到完整路径。这些探测不等价于实际推理可用。[端点定义](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-core/src/site.rs#L166-L178)、[基址说明](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/probe.rs#L265)。

| 方法与路径 | 探测 |
| --- | --- |
| `GET /v1/models` | 无鉴权线路探测，源码期望 401 |
| `GET /backend-api/wham/accounts/check` | PAT 账户检查 |
| `GET /backend-api/codex/models` | 鉴权模型列表 |
| `GET /codex/v1/models` | 备用路径的鉴权模型列表 |
| `POST <base>/responses`，即 `POST /backend-api/codex/responses` 或 `POST /codex/v1/responses` | 真实模型的 Responses 探测，可能计费 |
| `POST <base>/responses/compact`，即 `POST /backend-api/codex/responses/compact` 或 `POST /codex/v1/responses/compact` | 真实模型的 compact 探测，可能计费 |
| `GET <base>/responses`，即 `GET /backend-api/codex/responses` 或 `GET /codex/v1/responses` | WebSocket Upgrade 握手，成功即关闭 |
| `POST <base>/responses`，即 `POST /backend-api/codex/responses` 或 `POST /codex/v1/responses` | 使用普通、zstd 和伪装普通 JSON 的无效请求比较响应，探测 zstd |

Responses/compact 使用真实模型请求，可能消耗 token 和产生费用；WebSocket 握手和无效 zstd 请求在源码设计中不启动计费轮次，但不能据此保证所有服务端都不会收费；传输探测结果也可能未知。[请求体](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/responses.rs#L1-L43)、[传输探测](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-net/src/transports.rs#L1-L18)。

## 本地 router 与指标来源

Superba router 的 `/_superba/v1/` 是**本地管理接口**，不是上述远端账单接口。除 health 外均使用实例 admin token，见 [路由实现及鉴权](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-router/src/admin.rs#L44-L138)：

| 方法与完整路径 | 用途 |
| --- | --- |
| `GET /_superba/v1/health` | 存活检查，无鉴权 |
| `GET /_superba/v1/status` | router 状态 |
| `GET /_superba/v1/catalog` | 合并模型目录 |
| `POST /_superba/v1/catalog/test` | 测试模型 |
| `POST /_superba/v1/probe` | 探测各线路 |
| `POST /_superba/v1/reload` | 重载配置与密钥 |
| `POST /_superba/v1/shutdown` | 关闭 router |
| `GET /_superba/v1/requests?after_id=&limit=` | 最近请求记录 |
| `GET /_superba/v1/requests/active` | 进行中的请求 |
| `POST /_superba/v1/requests/clear` | 清除记录 |

逐请求记录只覆盖经过本地 router 的流量，不能代表账户所有历史请求。TTFT 等时间点来自本地 router 的单调时钟；TPS 按输出 token 除以首事件至结束时长计算，窗口不足 200 ms 或输出不足 10 token 时不可用。[时间和计算](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/crates/superba-core/src/requests.rs#L1143-L1185)。

| 监控指标 | 来源 | 限制 |
| --- | --- | --- |
| 账户身份 | whoami 的 email、账户与用户 ID | 可缺失；不证明 Key 隔离 |
| 套餐、限额、到期 | billing subscription | plan 或到期可缺失；unlimited 为标记 |
| 当月费用、剩余额度 | billing usage 的美分转美元；结合限额推导剩余 | UTC 当月；不是钱包余额 |
| 每日费用 | organization costs；不可用时 usage 的 `cost_usd` | 区分数据来源；缺失、非 USD 或截断可能造成不完整 |
| 模型用量排行 | organization usage completions，按 model 汇总 | 组织范围和 Key 隔离未证实；字段可能缺失 |
| 线路状态 | 对三条 origin 的请求/探测结果 | 只反映探测时的网络和接口状态 |
| 逐请求日志、TTFT、TPS | 本地 Superba router | 仅经过该 router 的流量；部分时间或 TPS 可能不可用 |

## 第一版范围

应用提供概览、用量、线路、设置四页，仅支持账号登录：默认邮箱验证码，保留密码与后续 TOTP。查询官网钱包、套餐、模型排行和北京时间日志统计，记住登录仅保存账号 JWT。Android Keystore AES-GCM 加密、Web localStorage；历史 Key 记录与旧加密别名会单独清理，不影响账号会话。旧 Key 账单客户端和专用测试已删除，只保留不含凭据的线路探测。

## 账号登录与联调记录

Superba 将已有的 Krill Key 当作 PAT 或 API Key 使用，并未实现 Krill 网页账号登录；其中“官方登录”指 ChatGPT。[认证方式说明](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/README.zh-CN.md#L33-L36)。本项目现已独立接入官网邮箱密码与 TOTP 登录；账号接口只使用 JWT，不能用 API Key 替代。

2026-11-08 使用用户授权的 Key 进行只读联调，未将真实 Key 或原始身份信息写入文件：whoami 与最近 7 天 usage/completions 返回 200；subscription（含备用路径）、billing usage、costs 返回 520；三线路 /v1/models 不带 Key 返回 401。用量结果包含请求数、输入/缓存/输出 Token、cost_usd，还出现 input_cache_write_tokens 等扩展字段；api_key_id 未提供有效值，因此仍无法证明按单 Key 隔离。OPTIONS 预检返回 204，允许 GET 与 Authorization；这次验证不保证未来所有线路和来源都同样可用。

## 官网接口排查：费用与订阅提示

用户授权检查已登录的 `https://www.krill-code.com/app/logs` 后，确认官网使用相对该 origin 的 `/api/...` 接口。公开的 [client-CP18vIxS.js](https://www.krill-code.com/assets/client-CP18vIxS.js) 从 `krill_jwt` 读取登录 JWT 并设置 Bearer，同名 cookie 也用于会话；不应复制浏览器登录态到应用配置或仓库。

| 官网请求 | 核心返回字段 | 验证情况 |
| --- | --- | --- |
| `GET /api/auth/me` | 用户资料、`credit_balance_usd` | 登录会话返回 200 |
| `GET /api/subscription` | `subscriptions[]`、`summary`、`credit_balance_usd`、`welfare_balance_usd` | 登录会话 200；仅带 API Key 且不带 cookie 时 401 |
| `GET /api/credits` | `balance_usd`、`welfare_balance_usd` | 登录会话 200；仅 API Key 时 401 |
| `POST /api/request-logs/stats` | `total_cost_usd`、请求成功/失败数、输入/输出/缓存/推理 Token、`overall_tps`、`trend` 等 | 登录会话及日期筛选返回 200 |
| `POST /api/request-logs/model-stats` | `items[].model`、`request_count`、`total_cost_usd`、`total_tokens` | 登录会话与日期筛选返回 200；账号模式排行来源 |
| `POST /api/request-logs` | `items`、`total`、`page` | 官网日志页已观察到该请求；参数契约来自公开前端 |
| `GET /api/tokens` | Key 的 ID、前缀、名称、撤销及配置字段 | Android 管理入口使用；实际列表响应的 `data` 为数组，无分页参数 |

日志及统计的筛选包括 `start_time`、`end_time`、`model`、`token_id`、`charge_type`、`method`，列表另带 `page`、`page_size`。官网日期组件按 UTC+8 构造不带时区的字符串，例如 `2026-11-08T00:00` 至 `2026-11-08T23:59:59`；App 的组织统计按 UTC 半开区间分桶，两者的“当天”不完全一致。契约见 [api-DGSdnat1.js](https://www.krill-code.com/assets/api-DGSdnat1.js)、[RequestLogsPage-BjsuDGOn.js](https://www.krill-code.com/assets/RequestLogsPage-BjsuDGOn.js)、[DateRangePicker-Ik74vWFD.js](https://www.krill-code.com/assets/DateRangePicker-Ik74vWFD.js)。

官网每份订阅包含 `plan.name`、`plan.billing_type`、起止时间、`quota` 及总额度字段。计费类型可能按日、周、月或请求数；赠金与现金钱包单独计算。字段名 `quota.daily_limit_usd` 在官网也被用于部分周/月窗口，不能仅凭名字推断周期。App 的旧账单“限额减月消费”只是一种推导值，不能替代这些套餐或钱包数据。见 [DashboardPage-B2R9hxwY.js](https://www.krill-code.com/assets/DashboardPage-B2R9hxwY.js)。

三线路复核中，海外和 CDN 的旧订阅接口返回 JSON HTTP 520，带 `Retry-After: 60`；国内线路返回 200、`application/octet-stream`，内容不是可解析的账单 JSON。因此“200”也不意味着支持该接口。响应不足以定位服务端内部的 520 根因，不能断言账户没有套餐或所有线路永久不支持。

客户端修正：订阅失败保留安全的 HTTP/格式原因；成功响应中未提供可选套餐名或到期时间时保留未知，不统一报“不完整”；`access_until=0` 单独表示未设置到期。独立费用接口失败但 `cost_usd` 足够时正常展示汇总费用，以中性说明标注来源并缓存，避免将成功回退误报为整份报告失败。只有真正缺失或截断的数据继续显示警告。

官网公开登录契约包含密码、邮箱验证码、TOTP、Google/GitHub/Linux Do 流程。当前实现默认邮箱验证码、密码和 TOTP，不调用第三方回调或接受条款接口；额外邮箱绑定与安全验证要求提示到官网处理。此前只读调查未导出网页登录 JWT。

## 账号模式实现约定

- `POST /api/auth/login` 发送 `{email,password}`；`data` 为 `{token,user}` 或 `{requires_totp:true,pending_token}`。
- 二次验证使用 `POST /api/auth/login/totp`，发送 `{pending_token,totp_code}`；动态验证码为 6 位数字。密码仅存在于当前登录表单，请求结束后清空。
- 邮箱验证码：先 `GET /api/public/auth-settings` 检查 `turnstile_enforced`；强制或无法确认时停止发送并引导官网。公开设置当前为 false，之后可能变化。
- `POST /api/auth/login/send-code` 只发送 `{email}`；`data.resend_after_seconds` 为服务端重发秒数。缺失或无效时按规范化邮箱在当前客户端保留申请记录，编辑邮箱或重建面板不解除同邮箱保护；可先检查邮箱或到官网重新申请。成功请求不等于确认邮件已经送达。发送使用互斥保护，重复点击不会并发发邮件。
- `POST /api/auth/login/verify` 发送 `{email,verification_code}`，响应同密码登录，可继续 TOTP。官网输入上限 6 位但未证明必须为 6 位数字，本客户端对去除首尾空白后 1～6 位的验证码提交给服务端校验。邮件码与 TOTP 使用不同字段和接口，均不持久化。
- 记住登录仅保存 JWT；恢复后先查询 `/api/auth/me`。收到 401 立即移除本应用保存的会话并清空旧账户数据，退出登录也执行本地清除；官网未提供公开 logout 包装，不影响其他客户端会话。
- 账号读请求使用 Bearer JWT，忽略浏览器 Cookie；每次查询先验证身份。账号统计缓存 5 分钟，按令牌、范围和北京时间日期隔离；手动刷新跳过未过期缓存，但不绕过限流冷却。可选接口失败保留已知数据并明确标为缓存或未知。
- `stats.trend[]` 每桶为 `bucket_start`、`request_count`、`total_cost_usd`、`total_tokens`。实测 `bucket_start` 以 UTC 的 `Z` 时间返回，北京时间零点为前一日 UTC 16 点，因此先转成北京时间再归日，不能截取原始日期。模型排行使用 `model-stats`，不下载所有日志或伪造细分输入/输出 Token；细分 Token 来自统计汇总。
- `request_count` 与 Credits 套餐保留各自单位，未知计费类型不推算美元额度。
- 套餐显示对齐官网：周剩余为 `max(daily_limit_usd - used_usd, 0)`，缺字段不当零；周套餐 `duration_days >= 14` 时额外显示“月额度”，其剩余直接读取 `total_remaining_usd`，不能用累计 `total_used_usd` 推算。美元/Credits 环图按剩余计算，请求次数按已用计算。
- 页面日期显示北京时间 UTC+8 并保留秒。周窗口从 `window_start_at` 到官网包含最后一秒的结束时刻：通常为 `window_reset_at - 1 秒`，最后一个窗口若等于套餐到期则保留到期时刻；不修改实际重置或到期数据。

官网账号 API 的 OPTIONS 实测返回 405 且没有跨域允许头，Web 版通过 `tools/account-web.mjs` 同源转发。仅白名单内认证设置、验证码申请与校验、密码/TOTP 登录、身份、钱包、套餐和两个统计接口被允许；固定上游 `https://www.krill-code.com`，不转发 Cookie/Set-Cookie，不跟随重定向、不记录凭据，请求上限 64 KiB、响应上限 2 MiB，15 秒超时。Android 直接连接官网 HTTPS。

当前验证：Android 35 项、Wasm 37 项、Node 代理 8 项测试通过；邮箱验证码、密码、TOTP、会话恢复及退出已用虚拟凭据验证。验证码相关界面还覆盖服务端倒计时、缺失重发时间、编辑邮箱不重复申请和强制人机验证停止发送。真实邮件送达、验证码校验和账号锁定对该入口的影响尚未验证，未自动申请真实验证码。

用户随后授权了一次真实密码登录测试。`POST /api/auth/login` 返回 HTTP 429、`data.type=rate_limit_error`，固定消息为 `account temporarily locked due to too many failed attempts`；没有 `Retry-After` 或解锁时间。此响应尚未验证密码对错或取得会话，未继续重复登录、发送验证码、重置密码或修改账号安全设置。文档不记录账号和密码。

客户端将已确认的锁定响应映射为明确中文提示；普通 429 无时间头时不显示“60 秒后重试”。内部默认 60 秒仅防重复请求，不是服务端解锁承诺；有有效 Retry-After 时标为服务端重试建议。密码、验证码发送和验证码校验分别保留本地冷却，避免客户端将一个入口的限制强加到另一入口；这不代表服务端允许绕过锁定。官网未公开锁定持续时间。

## Android Key 管理与本地保存

官网公开 [TokensPage](https://www.krill-code.com/assets/TokensPage-BexHU5PR.js) 与 [API 包装](https://www.krill-code.com/assets/api-BxSlaCyP.js) 定义以下个人账号接口，均使用现有账号 Bearer JWT，不使用 API Key 登录：

| 操作 | 请求 | 响应 `data` |
| --- | --- | --- |
| 列表 | `GET /api/tokens` | 数组，含 `id`、`name`、`token_prefix`、`created_at`、`last_used_at`、`revoked_at` |
| 创建 | `POST /api/tokens`，`{"name":"CI"}`，空名为 `null` | `{item, token}`，完整 `token` 仅返回一次 |
| 重命名 | `PUT /api/tokens/{id}`，`{"name":"CI"}` | 更新后的列表项 |
| 轮换 | `POST /api/tokens/{id}/rotate`，无正文 | `{item, token}`；旧值立即失效 |
| 撤销 | `DELETE /api/tokens/{id}` | 不依赖返回正文，撤销不可恢复 |
| 永久删除 | `DELETE /api/tokens/{id}/destroy` | 不依赖返回正文 |

在用户授权下，已通过官网表单创建一个测试 Key。创建响应为 HTTP 200、`{success, code, data:{item, token}}`，`item.id` 为正整数。实际默认 `allow_balance_fallback=true`、`ip_whitelist_disabled=false`；创建后的渠道及扣费设置可以不保存直接关闭。应用支持独立渠道编辑，余额扣费、消费限额及 IP 白名单仍保留现有配置；完整 Key、登录凭据及真实账号响应不写入仓库。

创建与轮换不自动重试；Android 同时关闭 OkHttp 连接重试，并将这两个 POST 的正文标为一次性，避免 `503 + Retry-After: 0` 再次发送。超时或响应无法确认时提示操作可能已生效，先刷新列表。新值先保存成功才提示“已加密保存在本机”，保存失败在当前会话保留待保存值并允许重试，避免重复创建；轮换未保存时禁止访问已失效的旧副本。查看和复制分别触发新的系统认证；取消不披露、不复制、不删数据，进入后台立即取消认证任务并清除明文。

Android 11+ 本地库按已验证 `/api/auth/me` 的用户 ID 与 Key ID 隔离。每条随机 AES-256-GCM 数据密钥由 Android Keystore RSA-OAEP 公钥封装，用户 ID 与 Key ID 作为附加认证数据；解包必须使用 `BiometricPrompt.CryptoObject` 认证过的私钥操作，支持强生物识别或设备锁屏凭据。不使用账号会话存储密钥，不提供无认证降级，不将 Key 保存到 Web。密钥库 preferences 排除云备份和设备迁移；退出仅关闭明文，保留各账号密文。

Web 不增加密钥页，代理也不开放 `/api/tokens` 路由。两端构建及 Android 47 项、Wasm 46 项、Node 8 项回归通过，包括真实 OkHttp 引擎的 503 不重放检查。Android 16 真机以虚拟 Key 验证密文保存、每次认证的 Keystore 约束、未认证解密被拒及一次系统认证后的实际解密往返；虚拟记录和临时自检入口均已移除，正式密钥列表与屏幕保护已验证。系统同时显示指纹和 PIN 入口，但未分别走完指纹及 PIN 两种确认路径，也未对真实 Key 执行轮换、撤销或删除。

## Key 渠道配置

- `GET /api/my/channels` 的 `data` 为数组，使用 `id`、`name`、`default_routed`、`billing_multiplier`；`GET /api/tokens/{id}/channels` 读取 `data.channel_ids`。保存只调用 `PUT /api/tokens/{id}/channels`，正文为 `{"provider_ids":[101,102]}`，不附带余额、IP、名称或限额字段。
- 新 Key 必须先完成本机加密保存，再显示默认渠道选择；卡片整行可多选，以颜色和“已选”标记显示选择状态，保留复选语义供无障碍使用。选择“保存并查看 Key”或“跳过并查看 Key”后直接调用系统认证，成功后展示刚创建的 Key；取消认证不显示，已有 Key 的渠道保存不触发查看。保存渠道失败保留草稿和新建流程，不重复创建 Key。已有 Key 返回空数组时按官网回显默认渠道；允许保存空数组，但不能把它标为“禁用所有渠道”。当前目录中不存在的已选 ID 会保留，避免覆盖未知配置。
- 新增勾选时按官网精确名称执行互斥：`国产模型官渠` 与 `阿里云直连`；`Max 官渠`、`Kiro 渠道`、`Max 官渠高可用`、`Anthropic 官key` 同组互斥。读取已有配置时不擅自修正冲突。离页或会话失效关闭编辑器，迟到响应不重新弹出。

## 公开渠道状态

官网 [渠道状态页](https://www.krill-code.com/status) 使用匿名 `GET /api/public/channel-status?hours=24`，返回 `data.channels` 和 `data.perf`，按 `channel_key` 关联。当前状态 `1` 为正常、`2` 为波动、`0` 为异常，其余为未知；有效 `fixed_status` 优先。历史 `ts` 无时区时按官网处理为 UTC，显示时转为 UTC+8。

线路页分别展示设备到接入线路的 HTTP 往返耗时，以及官网供应商渠道/模型状态。历史条汇总最近 24 小时，每小时取最差已知状态；缺样本为灰色。可选性能数据包括 `throughput_p50_tps`（缺失时回退 `throughput_avg_tps`）、`cache_rate` 及 `avg_image_time_ms`。`ttft_p99_ms` 在官网有分位数标签歧义，当前不展示；不推算未提供的可用率或错误率。

公开状态客户端不附带 JWT 或 Cookie，缓存 60 秒，限流遵守 `Retry-After`，失败保留旧结果与原获取时间。Web 同源代理只新增公开 GET 路由和精确 `?hours=24` 查询，拒绝额外、重复或其他参数；公开响应上限为 8 MiB，其余账号响应仍为 2 MiB，Key 管理路由仍关闭。

本轮验证：Android 61 项、Wasm 60 项及 Node 10 项回归通过，正式两端构建及安全审阅通过。真机读取已有 Key 渠道、整卡点选草稿后取消，未提交真实账号配置；官网公开状态实际加载 31 个模型，未登录 Web 也能通过同源代理读取，且请求不带认证头、fetch credentials 为 omit。虚拟 Key 的完整界面流程已覆盖“保存并查看”和“跳过并查看”，两者均经认证入口后直接显示新 Key，不重复创建或额外写入配置；还覆盖保存失败、取消认证、认证任务排队前离页及迟到回调。临时预览已删除，未额外创建真实 Key。
