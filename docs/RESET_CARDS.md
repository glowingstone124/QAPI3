# Account settings and Reset cards

All endpoints require `Authorization: Bearer <web login token>` and return JSON with `Cache-Control: no-store`. Account identity comes from the authenticated token, never a user-supplied UID for reads or redemption.

## Administration

Configure `adminUids` at the top level of the provider configuration file (`data/llm/providers.json` by default, or the path in `LLM_PROVIDERS_FILE`) as an array of QQ UIDs, e.g. `"adminUids": [123456, 789012]`. The default is empty: nobody can grant cards or reset all usage. The list is re-read with the hot-reloadable provider configuration, so no restart is needed. Kotshi displays the grant form only to these administrators; the API independently checks authorization.

`POST /qo/asking/v1/reset-cards/grant`

```json
{"request_id":"grant-20260920-001","count":1,"user_id":123456}
```

For all existing accounts:

```json
{"request_id":"grant-20260920-all","count":1,"all":true}
```

Provide exactly one target (`user_id` or `all: true`). Each recipient receives 1–100 cards. The all-account audience is the union of registered `users.uid` and existing AI quota identities (including QQ guests), captured when the grant runs; future accounts do not receive that grant. The operation is transactional, and returns `request_id`, `recipients`, `count`, `expires_at`.

Every new grant expires exactly 30 days (2,592,000 seconds) after issuance. All recipients in a batch share the same expiration. A retry returns the original expiration and never renews cards. Previously issued cards retain their original non-expiring validity; retries of legacy grants do not gain an expiration.

### Reset all weekly usage

`POST /qo/asking/v1/usage/reset` requires a web login belonging to `adminUids`.

```json
{"request_id":"reset-all-20260930-001"}
```

Sets every existing quota account's current weekly free `used` counter to zero, including overdraw, across Web, QQ, and Minecraft. No reset card is consumed. Paid Credits, card balances, historical weekly counters, request history, lifetime statistics, billing costs, and the scheduled weekly reset time are preserved. Accounts created after the audience snapshot are outside this operation.

Returns `request_id`, `period` (the weekly start date), `recipients` (accounts whose nonzero usage was cleared), and `restored_units` (the total free Units restored). Zero usage succeeds with zero counts. Stable request IDs make retries return the original result, even in a later week, without clearing subsequent usage. An ID used by another administrator is rejected.

Known pending Usage is reconciled and zombie pending requests are refunded using the same rules as card redemption. If any account still has an active or unresolved pending request, the reset returns 409 and no weekly counters are cleared by the reset. Reconciliation/refunds performed before that check remain effective. Retry after requests settle. Successful resets add an `admin_reset` audit ledger entry with zero card delta for each affected account; the card-specific `reset_history` continues to contain only grants and card redemption.

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

Consumes one card and sets the current weekly free `used` counter to zero, including any overdraw. Paid Credits, historical calls, aggregate billing costs, and the scheduled weekly reset time are preserved. Redemption uses the earliest-expiring valid batch first, then legacy non-expiring cards. At `expires_at` the unused cards in that batch cease to be available; no background job is required. Expiration never alters usage, Credits, or grant history.

An active reservation, or a pending reservation without complete Usage, blocks redemption until it settles, preventing late billing/refunds from corrupting the reset. Account settings and redemption automatically retry pending reservations that already have complete Usage. A pending reservation without Usage that is older than 30 minutes is treated as a zombie, refunded, and marked `refunded`; newer records still return 409. No card or already-zero weekly usage returns 409 without consuming a card. Returns `request_id` and `restored_units`.

Use a stable request ID when retrying a failed/timed-out operation. IDs must match `[A-Za-z0-9_-]{8,80}`. Repeating a successful ID returns the original result without another grant/reset; changing the grant payload with the same ID is rejected. Redemption IDs are scoped to the authenticated account. New intentional operations need new IDs.

## Settings and history

- `GET /qo/asking/v1/settings`: account identity, `quota` (`limit`, `used`, `remaining`, `reset_at`, `paid_credits`), `reset_cards` (currently usable cards only), `active_requests`, all-time `statistics`, last 20 `reset_history` events, and `can_grant_reset_cards`. New grant events include `expires_at`; legacy grant and other events have no expiry.
- `GET /qo/asking/v1/usage/history?page=0`: current user's recorded requests, 20 per page, descending request time. Returns `items`, `page`, `has_more`. Each item includes request ID, timestamp, mode, status, token counts and charged units. Pending requests may not yet have usage totals.

Timestamps are Unix seconds. Errors use `{"error":{"message":"..."}}`; 401 for unauthenticated, 403 for unauthorized administration, 400 for invalid input and 409 for unavailable redemption/reset.

Schema is created lazily alongside the existing quota schema: `ai_reset_balance` (legacy non-expiring cards), `ai_reset_grant`, `ai_reset_card_batch` (new expiring cards), `ai_reset_ledger`, `ai_usage_reset`. Existing grant tables gain a nullable `expires_at` column without changing legacy grants or balances. Account row locks serialize redemption and global resets with quota reservation/settlement. Grant batches and ledger rows retain audit and idempotency records.
