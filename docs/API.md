# QAPI3 API Reference

本文档覆盖仓库中 Controller 映射的全部 HTTP 端点，包括客户端接口、服务器节点接口和运维接口。

## 约定

- Base URL：`<server-root>`
- 默认请求和响应类型：`application/json`
- 时间戳：Unix 毫秒；个别历史接口也接受 Unix 秒。
- `Authorization` 支持 `Bearer <token>`；部分旧接口使用 `Token` 或 `token`。
- 用户令牌来自 `/qo/game/login`，有效期通常为 7 天。
- 节点令牌用于 Minecraft/代理/内部服务器接口，不等同于用户令牌。
- 以下标记：
  - **公开**：不需要认证或由业务自行校验。
  - **用户令牌**：`Authorization` 或 `token`。
  - **节点令牌**：`Token`、`Authorization` 或接口指定的节点认证方式。
  - **管理员/内部**：仅服务端、Webhook 或运维使用。

## 响应和错误

不同历史接口返回格式不完全一致，常见格式包括：

```json
{"code":0,"message":"ok"}
```

```json
{"result":true}
```

认证失败通常返回 HTTP `401`；参数错误通常返回 `400`；不存在资源通常返回 `404`。客户端应同时检查 HTTP 状态码和响应 JSON。

---

## 1. 健康检查和基础信息

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| `GET` | `/` | 公开 | 返回 build、在线服务器数、SQL、Redis、代理数。 |
| `GET` | `/app/latest` | 公开 | 返回客户端版本和停服标志。 |
| `GET` | `/qo/time` | 公开 | 返回服务器当前 Unix 毫秒时间戳。 |
| `GET` | `/qo/alive/download` | 公开 | 查询主服务器存活状态，返回 `{"stat":...}`。 |
| `GET` | `/qo/download/status?id=<id>` | 公开 | 查询服务器状态（JSON），`id` 默认 `1`；若请求头带 `Accept: text/event-stream` 则自动升级为 SSE 实时流。 |
| `GET` | `/qo/download/status/stream?id=<id>` | 公开 | 服务器状态 SSE 实时流（Server-Sent Events），`id` 默认 `1`。连接即推当前快照，节点有状态上传时立即广播，并每 15 秒发送心跳注释（`: keep-alive`）。支持可选 `event=<name>`。 |
| `GET` | `/qo/stream/status?id=<id>` | 公开 | `/qo/download/status/stream` 的简短别名。 |
| `GET` | `/qo/download/stats` | 公开 | 返回 `stat.json` 统计数组。 |
| `GET` | `/qo/download/statpic` | 公开 | 返回 `image/png` 统计图。 |
| `GET` | `/attac` | 公开/内部 | 简单请求计数探针。 |

## 2. 账户、登录和玩家查询

### 登录和注册

| 方法 | 路径 | 认证 | 请求 |
|---|---|---|---|
| `POST` | `/qo/game/login` | 公开 | JSON：`username`、`password`、可选 `ip`、`web`。 |
| `POST` | `/qo/upload/registry` | 公开 | JSON：`name`、`uid`、`password`、`verificationMethod`、`verificationToken`。 |
| `POST` | `/qo/upload/confirmation` | 节点令牌 | JSON：`token`、`uid`、`task`；`task=0` 注册，`task=1` 修改密码。 |
| `POST` | `/qo/upload/password` | 公开 | JSON：`uid`、`password`。 |
| `POST` | `/qo/upload/loginattempt?auth=<auth>` | 内部 | 原始登录记录 JSON。 |
| `POST` | `/qo/upload/explevel?token=<token>&lvl=<lvl>&username=<name>` | 生存服节点 | 更新玩家经验等级。 |

登录成功示例：

```json
{"result":true,"token":"<login-token>"}
```

### 账户和卡片

| 方法 | 路径 | 认证 | 参数/请求体 |
|---|---|---|---|
| `GET` | `/qo/authorization/account` | 用户令牌 | 返回当前账户信息。 |
| `GET` | `/qo/authorization/account/kotshi` | 用户令牌 | 返回 Kotshi 查询开关、共享额度、当日 Kotshi 使用汇总和最近调用记录。 |
| `PATCH` | `/qo/authorization/account/kotshi` | 用户令牌 | JSON：`{"kotshi_query_enabled":true|false}`；更新是否允许 Kotshi 按玩家名查询当前账户资料。 |
| `POST` | `/qo/authorization/account/frozen?uid=<uid>` | 管理/内部 | 冻结账户。 |
| `GET` | `/qo/authorization/account/card?profileUuid=<uuid>` | 公开 | 查询指定玩家卡片。 |
| `POST` | `/qo/authorization/account/card/custom` | 用户令牌 | `Mapping.CardProfile` JSON。 |
| `GET` | `/qo/authorization/cards/obtained` | 用户令牌 | 当前用户已获得卡片。 |
| `GET` | `/qo/authorization/cards/info?id=<id>` | 公开 | 查询卡片详情。 |
| `GET` | `/qo/authorization/cards/all` | 公开 | 所有卡片。 |
| `GET` | `/qo/authorization/avatars/all` | 公开 | 所有头像。 |
| `GET` | `/qo/authorization/fortune` | 用户令牌 | 查询账户运势。 |
| `POST` | `/qo/authorization/auto-login` | 节点令牌 | JSON：`username`、`ip`；检查是否可自动登录。 |

### 玩家和注册信息

| 方法 | 路径 | 认证 | 参数 |
|---|---|---|---|
| `GET` | `/qo/download/registry?name=<name>` | 公开 | 按 Minecraft 用户名查询注册信息；`last_login` 为玩家最后上线的 Unix 毫秒时间戳，未记录时为 `0` 或 `null`。 |
| `GET` | `/qo/kotshi/player?name=<name>` | 公开 | Kotshi 专用玩家查询入口；按账户的 `kotshi_query_enabled` 设置决定是否返回资料，关闭时返回 `403`。 |
| `POST` | `/qo/player-statistics/upload` | 生存服节点 | 上传玩家累计统计快照：移动距离、伤害、击杀与鞘翅飞行时间。挖掘、放置直接复用排行榜累计数据。 |

`/qo/download/registry` 的响应包含 `statistics` 对象：`distance_cm`、`damage_dealt`（Minecraft 原始值，10 为 1 点伤害）、`mob_kills`、`blocks_mined`、`blocks_placed` 和 `elytra_flight_ticks`。
| `GET` | `/qo/download/name?qq=<qq>` | 公开 | 按 QQ UID 查询注册信息。 |
| `GET` | `/qo/download/avatar?name=<name>` | 公开 | 查询头像 URL；Minecraft 头像命中本地缓存时返回本服务图片地址。 |
| `GET` | `/qo/download/avatar/image?name=<name>` | 公开 | 读取本地缓存的 Minecraft PNG 头像；special 头像使用 `key=<cache-key>`。 |
| `GET` | `/qo/download/getgametime?username=<name>` | 公开 | 查询玩家累计游戏时间。 |
| `GET` | `/qo/download/logingreeting?username=<name>` | 公开 | 返回玩家时间和在线玩家列表。 |

### 关联账户

| 方法 | 路径 | 认证 | 参数/请求体 |
|---|---|---|---|
| `GET` | `/qo/authorization/affiliated/query` | 用户令牌 | 查询关联账户。 |
| `POST` | `/qo/authorization/affiliated/add` | 用户令牌 | 原始 JSON 关联账户数据。 |
| `DELETE` | `/qo/authorization/affiliated/remove?name=<name>` | 用户令牌 | 删除关联账户。 |

## 3. IP 白名单

| 方法 | 路径 | 认证 | 参数 |
|---|---|---|---|
| `GET` | `/qo/download/ip?ip=<ip>` | 公开 | 检查 IP 是否属于中国大陆。 |
| `GET` | `/qo/download/ip/whitelisted?ip=<ip>` | 公开 | 查询 IP 是否在白名单。 |
| `GET` | `/qo/authorization/ip/query` | 用户令牌 | 查询当前用户白名单。 |
| `GET` | `/qo/authorization/ip/add?ip=<ip>` | 用户令牌 | 添加 IP；每个用户最多 5 个。 |
| `DELETE` | `/qo/authorization/ip/remove?ip=<ip>` | 用户令牌 | 删除 IP。 |

IP 添加/删除常见返回码：`0` 成功，`1` 令牌无效，`2` 超出数量限制，`3` IP 不在白名单，`4` IP 格式无效。

## 4. 消息和留言

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| `POST` | `/qo/msglist/upload` | 节点/消息源 | 上传原始消息数据。 |
| `GET` | `/qo/msglist/download` | 节点令牌 | 返回完整消息列表；`Authorization` 中可使用 `Bearer`。 |
| `GET` | `/qo/msglist/public` | 公开 | 返回公开消息列表。 |
| `GET` | `/qo/webmsg/download` | 节点令牌 | 返回 Web 消息。 |
| `POST` | `/qo/authorization/message/upload` | 用户令牌 | 上传 Web 消息原始 JSON。 |
| `POST` | `/qo/leavemessage/upload?from=<from>&to=<to>&message=<message>` | 公开/业务校验 | 创建留言。 |
| `GET` | `/qo/leavemessage/get?receiver=<receiver>` | 节点令牌 | 查询接收者留言。 |

## 5. 排行榜、状态和游戏数据

### 排行榜

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| `GET` | `/qo/rankings?limit=<n>` | 公开 | 新版综合排行榜，`limit` 默认 50，最大 100。 |
| `GET` | `/qo/destroy/download` | 公开 | 挖掘排行榜。 |
| `GET` | `/qo/place/download` | 公开 | 放置排行榜。 |
| `GET` | `/qo/playtime/download` | 公开 | 在线时长排行榜。 |
| `POST` | `/qo/destroy/upload` | 节点令牌 | 上传挖掘统计。 |
| `POST` | `/qo/place/upload` | 节点令牌 | 上传放置统计。 |

综合排行榜示例：

```json
{
  "generatedAt": 1700000000000,
  "rankings": {
    "destroy": [{"rank":1,"name":"Steve","value":1200,"unit":"blocks"}],
    "place": [{"rank":1,"name":"Alex","value":900,"unit":"blocks"}],
    "playtime": [{"rank":1,"name":"Steve","value":3600,"unit":"minutes"}]
  }
}
```

### Minecraft 服务器上报

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| `POST` | `/qo/alive/upload` | 节点令牌 | JSON：`timestamp`、`stat`；上报存活状态。 |
| `POST` | `/qo/upload/status` | 节点令牌 | 上传服务器状态原始 JSON。 |
| `POST` | `/qo/online?name=<name>&ip=<ip>` | 节点令牌 | 玩家上线，`ip` 可省略。 |
| `POST` | `/qo/offline?name=<name>` | 节点令牌 | 玩家下线。 |
| `POST` | `/qo/upload/gametimerecord?name=<name>&time=<minutes>` | 节点令牌 | 上报游戏时间。 |

## 6. 地铁和交通

### 地铁旧接口

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| `GET` | `/qo/metro/download` | 公开 | 下载地铁 JSON。 |
| `POST` | `/qo/metro/upload` | 节点令牌 | 上传地铁原始 JSON。 |

### 交通查询接口

| 方法 | 路径 | 参数 |
|---|---|---|
| `GET` | `/qo/transportation/station/id?id=<id>` | 按站点 ID 查询。 |
| `GET` | `/qo/transportation/station/all` | 查询所有站点。 |
| `GET` | `/qo/transportation/station/name?name=<name>` | 按名称查询站点。 |
| `GET` | `/qo/transportation/line/id?id=<id>` | 查询线路站点。 |
| `GET` | `/qo/transportation/line/detail?id=<id>` | 查询线路详情。 |
| `GET` | `/qo/transportation/line/name?name=<name>` | 按名称查询线路。 |
| `GET` | `/qo/transportation/dimension/all` | 查询所有维度枚举。 |
| `POST` | `/qo/transportation/calculate` | 计算路线，见下方 JSON。 |

路线请求：

```json
{
  "start": "station-id-or-name",
  "end": "station-id-or-name",
  "banned_dims": ["NETHER"],
  "banned_types": ["WALK"],
  "exclude_dims": [],
  "exclude_types": []
}
```

`banned_*` 和 `exclude_*` 会合并。维度常用值为 `OVERWORLD`、`NETHER`、`THE_END`；交通类型由服务端 `LineType` 枚举定义。

## 7. 注册验证

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| `GET` | `/qo/registration/verification-methods` | 公开 | 查询可用注册验证方式。 |
| `POST` | `/qo/registration/quiz/session` | 公开 | JSON：`name`、`uid`；创建答题会话。 |
| `POST` | `/qo/registration/quiz/submit` | 公开 | JSON：`sessionId`、`name`、`uid`、`answers`；提交答题。 |
| `POST` | `/qo/registration/minecraft/session` | 公开 | JSON：`name`、`uid`；创建 Minecraft 测试会话。 |
| `POST` | `/qo/registration/minecraft/claim` | Chamber 节点 | JSON：`name`；领取测试会话。 |
| `POST` | `/qo/registration/minecraft/status` | 公开 | JSON：`sessionId`、`name`、`uid`；查询测试状态。 |
| `POST` | `/qo/registration/minecraft/result` | Chamber 节点 | JSON：`sessionId`、`name`、`passed`；提交测试结果。 |

## 8. 交通扩展、武器和库存

### 精英武器

| 方法 | 路径 | 认证 | 参数 |
|---|---|---|---|
| `GET` | `/qo/elite/download?username=<username>` | 公开 | 查询用户武器。 |
| `POST` | `/qo/elite/create?owner=<owner>&type=<type>&description=<description>&name=<name>` | 节点令牌 | 创建武器。 |
| `POST` | `/qo/elite/batch?requester=<requester>&uuid=<uuid>&damage=<n>&kills=<n>` | 节点令牌 | 批量增加伤害/击杀统计。 |
| `GET` | `/qo/elite/query?uuid=<uuid>` | 公开 | 按 UUID 查询武器。 |

### 背包查看请求

| 方法 | 路径 | 认证 | 参数 |
|---|---|---|---|
| `GET`/`POST` | `/qo/inventory/request?name=<owner>&from=<viewer>` | 生存服节点 | 创建查看背包请求。 |
| `GET` | `/qo/inventory/query?secrets=<secret>` | 生存服节点 | 查询请求是否批准。 |
| `POST` | `/qo/inventory/consume?secret=<secret>` | 生存服节点 | 消费已批准请求。 |
| `GET`/`POST` | `/qo/inventory/validate?secret=<secret>` | FULL 权限 | 批准背包查看请求；也接受 `key` 或 `auth`。 |

### 飞行、战区和代理

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| `POST` | `/qo/flight/upload` | 生存服节点 | 上传飞行记录。Header：`auth`。 |
| `GET` | `/qo/flight/download` | 公开 | 查询活动飞行记录。 |
| `GET` | `/qo/combatzone/download` | 公开 | 下载战区数据。 |
| `POST` | `/qo/combatzone/upload` | 生存服节点 | Body 为战区原始数据，参数 `token`。 |
| `POST` | `/qo/proxies/accept` | 代理 | Body 为代理心跳令牌。 |
| `GET` | `/qo/proxies/status` | 公开/内部 | 查询代理状态。 |
| `GET` | `/qo/proxies/query?token=<token>` | 公开/内部 | 查询代理名称。 |
| `POST` | `/kuma/upload` | Kuma 内部 | 上传 Kuma 监控消息。 |

## 9. Advancement 和 Fallen 活动

### Advancement

| 方法 | 路径 | 认证 | 请求 |
|---|---|---|---|
| `POST` | `/qo/advancement/upload` | QO 节点 | Header `Token`；JSON：`player`、`advancement`。 |
| `GET` | `/qo/advancement/all` | 公开 | 所有 advancement。 |
| `GET` | `/qo/advancement/completed?name=<name>` | 公开 | 玩家已完成 advancement。 |

### Fallen 阵营

| 方法 | 路径 | 认证 | 请求 |
|---|---|---|---|
| `GET` | `/qo/authorization/fallen/team` | 用户令牌 | 查询当前用户阵营。 |
| `POST` | `/qo/authorization/fallen/team` | 用户令牌 | Body 为阵营选择数据；只能选择一次。 |
| `GET` | `/qo/fallen/team?username=<name>` | QO 节点 | 查询指定玩家阵营。 |
| `GET` | `/qo/fallen/status` | 公开 | 查询活动状态。 |
| `POST` | `/qo/fallen/status` | 生存服节点 | Body 为活动状态快照。 |

## 10. LLM API

### 认证

- 用户聊天接口：用户登录令牌。
- Bot 接口：节点令牌。
- 推荐 Header：`Authorization: Bearer <token>`。
- 兼容旧 Header：`token: <token>`。

### Weekly Limits 与 Paid Credits

Web、QQ Bot 和 Minecraft 在认证后归一到同一个 QQ UID，共享 Weekly Units 与永久 Paid Credits。QQ 身份默认每周 30 Units、1 并发、3 RPM；注册账户每周 90 Units、2 并发、6 RPM。注册提升同一身份的上限，保留本周已用额度。每周一北京时间 00:00 切换周账本，无定时扫描、不累积。

- 前端只选择 `fast / thinking`。请求 body 的 `model` 字段生效；兼容 query 参数。具体 provider、model 和计价由服务端配置。
- `GET /qo/asking/v1/quota` 返回 `limit`、`used`、`remaining`、Unix 秒 `reset_at`、`paid_credits`、`period: weekly`。
- `X-Request-ID` 在同一 QQ 身份的所有入口之间防重复，即使跨周也不能重用。
- 请求按照配置的人民币计价预留；获得所有工具轮次的真实 Usage 后，按 `max(1, ceil(actualCostCny / 0.005))` 结算。优先 Weekly，不足部分使用 Paid Credits，同一请求可跨池扣费。
- 成功响应包含 `quota`，其中 `charged_units` 表示本次实际消耗。流式结算事件在 `[DONE]` 前发送。流式响应头是预留时的快照，最终余额以结算事件或额度查询为准。
- HTTP 429 `weekly_quota_exceeded` 表示额度或免费补贴不足；`rate_limited` 表示并发/RPM 限制。数据库不可用返回 503，阻止未记账的调用。
- 请求失败、流提前结束、客户端取消会退款。成功后缺失真实 Usage 或结算失败，预留转为 `pending`，保留余额并等待对账。
- `GET /qo/authorization/account/kotshi` 提供账户额度和 Web 调用记录。玩家资料的隐私开关与原接口保持兼容。

配置项为 `qapi.llm.weekly-limit`（90）、`qapi.llm.guest-weekly-limit`（30），周界限使用北京时间。数据库表自动按需创建，旧 Redis 每日计数不迁移为 Paid Credits。

爱发电：

- `POST /qo/asking/v1/credits/purchase`：Bearer 登录令牌，body `{"amount":5}`、`10` 或 `20`。分别获得 350、800、1800 Credits。服务端创建购买意向，返回 `purchase_intent`、`checkout_url`、`credits`。
- `POST /hooks/afdian`：固定公开回调端点。先对 `data.sign` 做 RSA/SHA256 验签，再通过 `query-order` 查询官方订单；实际 SKU、数量、金额、成功状态、`custom_order_id` 全部以查询结果为准。
- 连通性通知和普通赞助回调直接确认收达，不充值。已验签但官方暂时查不到的商品订单持久化到 `ai_afdian_pending_order` 后返回 `ec:200`，后台重试核验；持久化失败仍返回 503。查不到订单不会发放 Credits。
- `POST /qo/asking/v1/credits/reconcile`：Bearer 登录令牌，重试本人的已知 Usage 待结算记录，返回 `settled` 数量。Usage 缺失和进程崩溃留下的预留需要运营核对，不自动猜测收费或退款。
- `UNIQUE(provider,out_trade_no)` 与 `UNIQUE(intent_id)` 确保重复通知、重复订单和一个购买意向的多次支付不会重复充值。

完整配置及预算说明见 [AI billing](AI_BILLING.md)。

### OpenAI Chat Completions

`POST /qo/asking/v1/chat/completions`

兼容 OpenAI Chat Completions 请求。常用字段：

```json
{
  "model": "fast",
  "stream": false,
  "enable-markdown": false,
  "reasoning_effort": "none",
  "messages": [
    {"role": "user", "content": "你好"}
  ]
}
```

模型别名：

- `fast`
- `thinking`

`stream=false` 返回 JSON；`stream=true` 返回 SSE。每个预设通过 provider `models.<preset>.protocol` 显式选择 `responses`、`chat-completions` 或 `anthropic`。Responses 路径把 `reasoning_effort` 转为 `reasoning.effort`；Anthropic 路径按模型 `thinkingMode` 转为 thinking budget 或 `output_config.effort`，并保持对外 Chat Completion 格式。三个协议的交互请求均启用上游 Web Search；Anthropic 支持 `pause_turn` 续接、搜索与本地工具混用，并返回来源链接。上游需要支持对应搜索功能且账户已启用；Anthropic 搜索失败会返回错误，不会悄悄移除搜索继续请求。工具调用、群上下文、记忆、历史检索和 RAG 在 Responses 路径中均可用；工具轮次会完整回传上游 output item，以保留模型需要的 reasoning 上下文。

`reasoning_effort` 支持 `none`、`low`、`medium`、`high`、`xhigh`、`max`，也可传 Responses 形式的 `{"reasoning":{"effort":"high"}}`，两者不可同时出现。后端按档位决定最终强度：Fast 关闭，Thinking 默认 medium，显式 high/max 提升到 high，不按请求内容分类。QQ Bot 入口始终关闭思考，管理员切换档位也不改变。

流式 SSE 会先发送若干 `data:` JSON 状态帧（`object: "kotshi.status"`），例如
`{"phase":"analyzing","label":"正在分析问题…"}`、
`{"phase":"query","label":"正在查询玩家资料…"}` 或
`{"phase":"web_search","label":"正在进行 Web 搜索…"}`。状态帧只包含高层阶段与固定文案，
不包含工具参数或隐藏思维内容；客户端应忽略无法识别的状态帧并继续处理标准 Chat Completions delta。

`enable-markdown` 为可选布尔值，缺省或 `false` 时保持现有纯文本输出规则；设置为 `true` 时允许模型使用标准 Markdown。该字段只控制输出提示，不会透传给上游 provider。

Web 流式请求会校验 `Origin`，允许来源由 `qapi.llm.web-allowed-origin-patterns` 配置；默认允许 `https://*.qoriginal.vip` 和本地开发端口。SSE 响应包含 `X-Accel-Buffering: no` 与 `Cache-Control: no-cache, no-transform`，部署反向代理时必须保持流式转发且不得缓冲。

### Bot 对话

`POST /qo/asking/v1/chat/completions/bot`

必需 Header：

- `X-QQ-UID: <qquid>`（跨 QQ、Kotshi Web 与已绑定 Minecraft 身份的唯一用户标识）
- 可选 `X-QQ-Group-ID: <group-id>`
- 可选 `X-QQ-Name: <name>`
- 节点认证：`Authorization` 或 `token`

Body 与 Chat Completions 相同。QQ Bot 固定使用 `none`，不能由用户请求开启思考。

### Minecraft 对话

`POST /qo/asking/v1/chat/completions/minecraft`

必需 Header：

- `X-Minecraft-Name`
- `X-Minecraft-Coordinate`
- `X-Minecraft-HP`
- 节点认证：`Authorization` 或 `token`

Minecraft 请求遵循 Fast / Thinking 档位策略，显式 high/max 参数可提升到 high。

### 历史消息归档

`POST /qo/asking/v1/chat/history`

必需 Header：

- `X-QQ-Group-ID`
- 节点认证：`Authorization` 或 `token`

Body：

```json
{
  "messages": [
    {
      "sourceId": "message-id",
      "qquid": 123,
      "name": "player",
      "content": "消息内容",
      "time": 1700000000000
    }
  ]
}
```

`uid` 仍作为旧 qbot 的兼容字段接受；新客户端应发送 `qquid`。归档消息由后台 summary 任务增量生成群摘要和人物画像，无需等待 Bot 对话请求。

### 旧版 SSE

`POST /qo/asking/ask`

- 用户令牌：`Authorization` 或 `token`
- Query：可选 `model=fast|thinking`
- Body：原始文本 prompt
- 响应：`text/event-stream`
- 完成事件：`[DONE]`

### LLM 内置工具

Responses 路径的非流式与 SSE 请求均可调用：

- `get_server_status`
- `get_current_date`：查询当前日期和时间，可选传入 IANA 时区，默认使用 `Asia/Shanghai`。
- `get_player_rankings`
- `query_metro_lines`
- `search_minecraft_knowledge`
- `search_chat_history`
- `add_memory`
- `search_memory`
- `forget_memory`
- `get_member_profile`
- `upsert_member_profile`
- `forget_member_profile_field`
- `get_remain_balance`：查询当前 LLM API 账户的 token 余额。
- `get_user_quota`：查询当前用户的每周 Units、Paid Credits、每周上限、重置时间及账户类型（QO 绑定用户/游客），若为游客还会附带加入 QO 获取更多额度的提示。
- `set_msg_emoji_like`：为群消息设置表情回应（贴一贴）。`emoji_id` 支持语义名或数字 ID，当前支持 `monkey_head`（128053，🐵）。需配置 `QBOT_ENDPOINT` 与 `QBOT_TOKEN` 指向 qbot。

### LLM 配置

provider 配置文件默认为 `data/llm/providers.json`，可由 `LLM_PROVIDERS_FILE` 覆盖；通过 `LLM_PROVIDER` 选择 provider。每个 provider 必须声明三个端点，每个模型必须显式配置名称和协议：

- `chatCompletionsUrl`
- `responsesUrl`
- `anthropicUrl`（兼容拼写 `antrophicUrl`）
- `token` 或 `tokenFile`
- `contextWindow`
- `models.<preset>.model`（上游模型名，任意预设名，至少需配置 `fast` 和 `thinking`）
- `models.<preset>.protocol`（`responses` / `chat-completions` / `anthropic`，无默认值；兼容 `antrophic`）
- `models.<preset>.thinkingMode`（Anthropic 可选：`enabled` / `adaptive` / `disabled`，默认 `enabled`）
- `balanceUrl`（可选）
- `summary.provider`
- `summary.model`
- `summary.contextWindow`
- `compact.enabled`
- `compact.triggerTurns`
- `compact.triggerPercent`
- `compact.keepTurns`
- `compact.maxSummaryChars`

会话历史自动压缩由当前 provider 的 `compact` 对象控制：超过 `compact.triggerTurns`
（默认 `12`）或估算 token 达到 `compact.triggerPercent`（默认主窗口的 `70%`）时，较早轮次会使用
`summary.model` 生成滚动摘要，保留最新 `compact.keepTurns`（默认 `4`）轮原文。设置
`compact.enabled=false` 可关闭。

三个端点均按 JSON 原值使用，不会相互推导。不可用端点填写字符串 `"unavaliable"`（也接受 `"unavailable"`）；缺少端点或模型选择不可用端点均视为非法配置。旧的字符串模型配置和 `responsesModels` 必须迁移为模型对象。配置加载时校验所有 provider；非法热更新保留上一个有效快照。

`summary.model` 必须引用对应 provider 中已声明的预设或模型名，摘要按该模型配置的协议调用；未指定时使用 `fast`。摘要和会话压缩不启用联网搜索或本地工具。

## 11. GitHub Webhook

`POST /hooks/accept`

- Header：`X-Hub-Signature-256: sha256=<hmac>`
- Body：GitHub webhook 原始 JSON
- Secret：`GITHUB_WEBHOOK_SECRET` 或 `qapi.github.webhook-secret`
- 成功：HTTP `204`
- Secret 未配置：HTTP `503`
- 签名错误：HTTP `401`

## 12. 其他内部端点

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/qo/upload/status` | 节点状态上报。 |
| `POST` | `/qo/upload/loginattempt` | 登录日志上报。 |
| `POST` | `/qo/upload/explevel` | 经验等级更新。 |
| `POST` | `/qo/upload/gametimerecord` | 游戏时间上报。 |
| `POST` | `/qo/online` | 玩家上线事件。 |
| `POST` | `/qo/offline` | 玩家下线事件。 |
| `POST` | `/qo/msglist/upload` | 消息节点上报。 |
| `POST` | `/qo/metro/upload` | 地铁数据上传。 |
| `POST` | `/qo/advancement/upload` | Advancement 上报。 |
| `POST` | `/qo/flight/upload` | 飞行记录上报。 |
| `POST` | `/hooks/accept` | GitHub webhook。 |

`/error` 为 Spring/WebFlux 错误处理入口，不建议客户端直接调用。
