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
| `GET` | `/qo/download/status?id=<id>` | 公开 | 查询服务器状态，`id` 默认 `1`。 |
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

### 统一每日额度

Web、QQ Bot 和 Minecraft 三个入口都会在各自认证成功后归一到同一个 QQ UID，并共享该账户每天 50 轮的 LLM 额度。额度按 `Asia/Shanghai` 自然日重置，不能通过更换 token、IP 或调用入口绕过。

- 可选 Header：`X-Request-ID: <client-generated-id>`，同一 source 内重复提交相同 ID 不会重复扣除额度。
- `GET /qo/asking/v1/quota`：使用用户登录令牌查询统一额度，返回 `limit`、`used`、`remaining` 和 Unix 秒格式的 `reset_at`。
- LLM 响应包含 `X-RateLimit-Limit`、`X-RateLimit-Remaining`、`X-RateLimit-Reset`；额度耗尽时返回 HTTP `429` 和 `Retry-After`。
- Redis 不可用时额度受保护的 LLM 请求返回 HTTP `503`，不会 fail-open。
- 上游在接受请求前失败会退还预留额度；上游已经接受请求或开始流式输出后，即使客户端中断也计为一轮。

额度可通过 Spring property `qapi.llm.daily-limit` 调整，默认 `50`；时区可通过 `qapi.llm.quota-zone` 调整，默认 `Asia/Shanghai`。

### OpenAI Chat Completions

`POST /qo/asking/v1/chat/completions`

兼容 OpenAI Chat Completions 请求。常用字段：

```json
{
  "model": "fast",
  "stream": false,
  "messages": [
    {"role": "user", "content": "你好"}
  ]
}
```

模型别名：

- `fast`
- `thinking`
- provider JSON 中配置的真实模型名

`stream=false` 返回 JSON；`stream=true` 返回 SSE。非流式请求支持工具调用、群上下文、记忆、历史检索、RAG 和 Responses API。

### Bot 对话

`POST /qo/asking/v1/chat/completions/bot`

必需 Header：

- `X-QQ-UID: <uid>`
- 可选 `X-QQ-Group-ID: <group-id>`
- 可选 `X-QQ-Name: <name>`
- 节点认证：`Authorization` 或 `token`

Body 与 Chat Completions 相同。

### Minecraft 对话

`POST /qo/asking/v1/chat/completions/minecraft`

必需 Header：

- `X-Minecraft-Name`
- `X-Minecraft-Coordinate`
- `X-Minecraft-HP`
- 节点认证：`Authorization` 或 `token`

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
      "uid": 123,
      "name": "player",
      "content": "消息内容",
      "time": 1700000000000
    }
  ]
}
```

### 旧版 SSE

`POST /qo/asking/ask`

- 用户令牌：`Authorization` 或 `token`
- Query：可选 `model=fast|thinking`
- Body：原始文本 prompt
- 响应：`text/event-stream`
- 完成事件：`[DONE]`

### LLM 内置工具

非流式请求可调用：

- `get_server_status`
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
- `set_msg_emoji_like`：为群消息设置表情回应（贴一贴）。`emoji_id` 支持语义名或数字 ID，当前支持 `monkey_head`（128053，🐵）。需配置 `QBOT_ENDPOINT` 与 `QBOT_TOKEN` 指向 qbot。

### LLM 配置

provider 配置文件默认为 `data/llm/providers.json`，可由 `LLM_PROVIDERS_FILE` 覆盖；通过 `LLM_PROVIDER` 选择 provider。每个 provider 可显式配置：

- `chatCompletionsUrl`
- `responsesUrl`
- `token` 或 `tokenFile`
- `contextWindow`
- `models.<preset>`（任意预设名，至少需配置 `fast` 和 `thinking`）
- `summary.provider`
- `summary.model`
- `summary.contextWindow`
- `compact.enabled`
- `compact.triggerTurns`
- `compact.triggerPercent`
- `compact.keepTurns`
- `compact.maxSummaryChars`
- `responsesModels`

会话历史自动压缩由当前 provider 的 `compact` 对象控制：超过 `compact.triggerTurns`
（默认 `12`）或估算 token 达到 `compact.triggerPercent`（默认主窗口的 `70%`）时，较早轮次会使用
`summary.model` 生成滚动摘要，保留最新 `compact.keepTurns`（默认 `4`）轮原文。设置
`compact.enabled=false` 可关闭。

`responsesUrl` 按 JSON 原值使用，不会从 Chat URL 推导。

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
