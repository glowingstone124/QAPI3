# Account settings and Reset cards

All endpoints require `Authorization: Bearer <web login token>` and return JSON with `Cache-Control: no-store`. Account identity comes from the authenticated token, never a user-supplied UID for reads or redemption.

## Administration

Configure `adminUids` at the top level of the provider configuration file (`data/llm/providers.json` by default, or the path in `LLM_PROVIDERS_FILE`) as an array of QQ UIDs, e.g. `"adminUids": [123456, 789012]`. The default is empty: nobody can grant cards. The list is re-read with the hot-reloadable provider configuration, so no restart is needed. Kotshi displays the grant form only to these administrators; the API independently checks authorization.

`POST /qo/asking/v1/reset-cards/grant`

```json
{"request_id":"grant-20260920-001","count":1,"user_id":123456}
```

For all existing accounts:

```json
{"request_id":"grant-20260920-all","count":1,"all":true}
```

Provide exactly one target (`user_id` or `all: true`). Each recipient receives 1–100 cards. The all-account audience is the union of registered `users.uid` and existing AI quota identities (including QQ guests), captured when the grant runs; future accounts do not receive that grant. The operation is transactional, and returns `request_id`, `recipients`, `count`.

## QQ 群命令

qbot 在受支持的群（`INTERACTIVE_GROUP_IDS`，见 qbot 配置）中收到 `.reset` 时调用本端点，为发送者消耗一张 reset 卡。

`POST /qo/asking/v1/reset-cards/bot`

- Header：`Authorization: Bearer <bot token>`（与 `/v1/chat/completions/bot` 相同的服务端令牌）、`X-QQ-UID`（发送者 QQ 号）、可选 `X-QQ-Name`。
- Body：`{"request_id":"..."}`。qbot 用 `reset-<group_id>-<user_id>-<message_id>` 生成请求标识，同一消息重复投递时幂等；标识不符合 `[A-Za-z0-9_-]{8,80}` 返回 400。
- 身份以 `X-QQ-UID` 为准，与 Web/QQ/Minecraft 入口共用同一份周额度；被封禁用户返回 403。
- 成功返回 `request_id` 与 `restored_units`；无卡、本周零用量、有进行中或待结算请求返回 409 且不扣卡，qbot 把 `error.message` 原文回复到群内。

## Redemption

`POST /qo/asking/v1/reset-cards/use`

```json
{"request_id":"use-20260920-001"}
```

Consumes one card and sets the current weekly free `used` counter to zero, including any overdraw. Paid Credits, historical calls, aggregate billing costs, and the scheduled weekly reset time are preserved. Cards do not expire automatically.

An active or pending reservation blocks redemption until it settles, preventing late billing/refunds from corrupting the reset. No card or already-zero weekly usage returns 409 without consuming a card. Returns `request_id` and `restored_units`.

Use a stable request ID when retrying a failed/timed-out operation. IDs must match `[A-Za-z0-9_-]{8,80}`. Repeating a successful ID returns the original result without another grant/reset; changing the grant payload with the same ID is rejected. Redemption IDs are scoped to the authenticated account. New intentional operations need new IDs.

## Settings and history

- `GET /qo/asking/v1/settings`: account identity, `quota` (`limit`, `used`, `remaining`, `reset_at`, `paid_credits`), `reset_cards`, `active_requests`, all-time `statistics`, last 20 `reset_history` events, and `can_grant_reset_cards`.
- `GET /qo/asking/v1/usage/history?page=0`: current user's recorded requests, 20 per page, descending request time. Returns `items`, `page`, `has_more`. Each item includes request ID, timestamp, mode, status, token counts and charged units. Pending requests may not yet have usage totals.

Timestamps are Unix seconds. Errors use `{"error":{"message":"..."}}`; 401 for unauthenticated, 403 for unauthorized grants, 400 for invalid input and 409 for unavailable redemption.

Schema is created lazily alongside the existing quota schema: `ai_reset_balance`, `ai_reset_grant`, `ai_reset_ledger`. Account row locks serialize redemption with quota reservation/settlement. Grant batches and ledger rows retain audit and idempotency records.
