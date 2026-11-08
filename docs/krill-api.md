# Krill 中转接口与监控数据来源

本文依据 Superba 上游源码的固定版本 [`3ecc0db00f0b536ee070d6cb1c56e01566b3115a`](https://github.com/krill-lab/superba/tree/3ecc0db00f0b536ee070d6cb1c56e01566b3115a) 整理，是客户端使用方式的记录，不是服务端协议保证。末尾记录了实际只读联调结果；权限与统计归属仍需以服务端契约为准。

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

应用提供概览、用量、线路、设置四页，支持单 Key、三线路、账户/额度/到期、每日费用、模型用量排行和线路状态。Key 可选记住：Android 用 Keystore AES-GCM 加密，Web 使用同源脚本可读的 localStorage；清除会删除本地记录。用量缓存 30 分钟；缺失、失败、限流或过期会明确提示，不把未知数据显示为 0。逐请求日志和能力探测不纳入第一版。

## 账号登录与联调记录

Superba 将已有的 Krill Key 当作 PAT 或 API Key 使用，并未实现 Krill 网页账号登录、OAuth 授权或账号换取 Key；其中“官方登录”指 ChatGPT，并非 Krill。当前监控接口仍需 Bearer Key，不能凭网页登录会话直接替代。[认证方式说明](https://github.com/krill-lab/superba/blob/3ecc0db00f0b536ee070d6cb1c56e01566b3115a/README.zh-CN.md#L33-L36)。这不证明服务端没有其他登录接口，只说明本项目尚无可验证的授权流程。

2026-11-08 使用用户授权的 Key 进行只读联调，未将真实 Key 或原始身份信息写入文件：whoami 与最近 7 天 usage/completions 返回 200；subscription（含备用路径）、billing usage、costs 返回 520；三线路 /v1/models 不带 Key 返回 401。用量结果包含请求数、输入/缓存/输出 Token、cost_usd，还出现 input_cache_write_tokens 等扩展字段；api_key_id 未提供有效值，因此仍无法证明按单 Key 隔离。OPTIONS 预检返回 204，允许 GET 与 Authorization；这次验证不保证未来所有线路和来源都同样可用。
