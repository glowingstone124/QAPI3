# QAPI3 Public API (Client-Facing)

> This document covers **public client** endpoints only. Internal/server-to-server endpoints (e.g. `/qo/upload/status`, `/qo/online`, `/hooks/*`) are intentionally omitted.

## Base
- Base URL: `<server-root>`
- Content-Type: `application/json` unless noted
- Image response: `image/png` for `/qo/download/statpic`

## Authentication
### Login Token (User)
- Obtain via `GET /qo/game/login`.
- Preferred header:
  - `Authorization: Bearer <token>`
- Backward-compatible header:
  - `token: <token>`

Token validity is **7 days** from issuance (see `Login.insertInto`, TTL = 604800000 ms).

### Node/Server Tokens (Internal)
Used by server-to-server endpoints (not documented here).

---

## Health & Meta
### `GET /`
Returns service summary.

Response (example):
```json
{
  "code": 0,
  "build": "<version>",
  "online": "2 server(s)",
  "sql": true,
  "redis": true,
  "proxies": 1
}
```

### `GET /app/latest`
Returns the latest client version info.

Response:
```json
{
  "version": 9,
  "die": false
}
```

### `GET /qo/time`
Returns server timestamp (milliseconds since epoch).

Response:
```json
{
  "code": 0,
  "message": "1700000000000"
}
```

---

## Server Status & Stats
### `GET /qo/download/status`
Query the latest status. Optional query param `id` (default `1`).

Response (example):
```json
{
  "totalcount": 0,
  "mspt_3s": 2,
  "code": 0,
  "players": [],
  "game_time": 0,
  "mspt": 2.562659,
  "recent60": [],
  "onlinecount": 0,
  "timestamp": 1727272149198
}
```

### `GET /qo/download/stats`
Returns JSON array from `stat.json` (server-maintained).

Response: JSON array of objects with fields: `title`, `date`, `author`, `summary`.

### `GET /qo/download/statpic`
Returns a PNG image (`output.png`).

---

## Messaging
### `GET /qo/msglist/download`
Returns the latest chat/message list.

Response (example):
```json
{
  "messages": ["..."],
  "empty": false
}
```

### `GET /qo/webmsg/download`
Returns web messages list (structure depends on server state).

### `POST /qo/authorization/message/upload`
Upload a web message (requires login token).

Headers:
- `Authorization: Bearer <token>` (or `token: <token>`)

Body:
```json
{
  "message": "hello",
  "timestamp": 1700000000000
}
```

Response:
```json
{
  "code": 0,
  "result": "ok"
}
```

---

## Account & Login
### `GET /qo/game/login`
Parameters:
- `username` (string, required)
- `password` (string, required)
- `ip` (string, optional)
- `web` (boolean, optional)

Response:
```json
{
  "result": true,
  "token": "<login-token>"
}
```

### `POST /qo/upload/registry`
Register a new user.

Query params:
- `name` (string)
- `uid` (long)
- `password` (string)
- `score` (int)

Response:
```json
{
  "code": 0,
  "message": "Success!"
}
```

### `GET /qo/upload/confirmation`
Confirm registration or password update.

Query params:
- `token` (string)
- `uid` (long)
- `task` (int)
  - `0` = registration confirm
  - `1` = password update confirm

Response:
```json
{
  "result": true
}
```

### `POST /qo/upload/password`
Request password update.

Query params:
- `uid` (long)
- `password` (string)

Response:
```json
{
  "code": 0,
  "message": "请求已提交。"
}
```

### `GET /qo/authorization/account`
Get account info (requires login token).

Headers:
- `Authorization: Bearer <token>` (or `token: <token>`)

Response (example):
```json
{
  "username": "player",
  "uid": 123,
  "playtime": 100,
  "profile_id": "...",
  "invite_cnt": 0,
  "logins": [
    {"user": "player", "date": 1700000000000, "success": true}
  ]
}
```

### `GET /qo/authorization/templogin`
Query latest login IP **for the same user** (requires login token).

Query params:
- `name` (string)

Headers:
- `Authorization: Bearer <token>` (or `token: <token>`)

Response:
```json
{
  "ok": true,
  "ip": "1.2.3.4"
}
```

---

## Registry & Player Lookup
### `GET /qo/download/registry`
Query user registry by name.

Query params:
- `name` (string)

Response (example):
```json
{
  "qq": 1294915648,
  "code": 0,
  "frozen": false,
  "online": false,
  "economy": 0,
  "playtime": 1712
}
```

### `GET /qo/download/name`
Query user registry by QQ.

Query params:
- `qq` (long)

Response:
```json
{
  "code": 0,
  "username": "player",
  "economy": 0,
  "playtime": 0,
  "profile_id": "..."
}
```

### `GET /qo/download/avatar`
Resolve a player's avatar URL.

Query params:
- `name` (string)

Response:
```json
{
  "url": "https://...",
  "name": "player",
  "special": false
}
```

---

## IP Utilities
### `GET /qo/download/ip`
Check whether IP is in CN.

Query params:
- `ip` (string)

Response:
```json
true
```

### `GET /qo/download/ip/whitelisted`
Check whether IP is whitelisted.

Query params:
- `ip` (string)

Response: JSON string from `IPWhitelistServices.whitelistedWrapper`.

### `GET /qo/authorization/ip/query`
Get your IP whitelist records (requires login token).

### `GET /qo/authorization/ip/add`
Add IP to whitelist (requires login token).

Query params:
- `ip` (string)

---

## Player Activity
### `GET /qo/download/getgametime`
Query playtime by username.

Query params:
- `username` (string)

Response:
```json
{
  "name": "player",
  "time": 1234
}
```

### `GET /qo/download/logingreeting`
Get greeting payload with online players list.

Query params:
- `username` (string)

Response:
```json
{
  "time": { "name": "player", "time": 1234 },
  "online": [
    {"id": 1, "players": ["a", "b"]}
  ]
}
```

---

## Leave Messages
### `POST /qo/leavemessage/upload`
Store a leave message.

Query params:
- `from` (string)
- `to` (string)
- `message` (string)

Response:
```json
{
  "code": 0
}
```

### `GET /qo/leavemessage/get`
Fetch leave messages for a receiver.

Query params:
- `receiver` (string)

Response:
```json
[]
```

---

## LLM (Streaming)
### `POST /qo/asking/ask`
Streams LLM response via SSE.

Headers:
- `Authorization: Bearer <token>` (or `token: <token>`)

Body: raw text prompt.

Events:
- `data: <chunk>`
- `event: end` with `data: [DONE]`
- `event: error` on failure

---

## Cards & Profiles
### `GET /qo/authorization/account/card`
Get card info by `profileUuid` (public).

### `POST /qo/authorization/account/card/custom`
Update your card profile (requires login token).

Body: `Mapping.CardProfile` JSON.

### `GET /qo/authorization/cards/obtained`
Get your obtained cards (requires login token).

### `GET /qo/authorization/cards/info`
Get card info by `id`.

### `GET /qo/authorization/cards/all`
Get all cards.

### `GET /qo/authorization/avatars/all`
Get all avatars.

---

## Affiliated Accounts
### `GET /qo/authorization/affiliated/query`
Get affiliated accounts (requires login token).

### `POST /qo/authorization/affiliated/add`
Add an affiliated account (requires login token).

---

## Metro
### `GET /qo/metro/download`
Download metro JSON.

---

## Elite Weapons
### `GET /qo/elite/download`
Query elite weapons for a user.

Query params:
- `username` (string)

### `GET /qo/elite/create`
Create elite weapon.

Query params:
- `owner` (string)
- `type` (string)
- `description` (string)
- `name` (string)

Response:
```json
{
  "result": true,
  "uuid": "..."
}
```

### `POST /qo/elite/add`
Add elite weapon progress.

Query params:
- `type` ("dmg" | "kill")
- `requester` (string)
- `uuid` (string)
- `amount` (int)

### `GET /qo/elite/query`
Query elite weapon by UUID.

---

## Error Conventions (Common)
Some endpoints use a shared format:
```json
{
  "code": 0,
  "message": "..."
}
```
But many endpoints return custom JSON; clients should parse per-endpoint.

---

## Excluded Internal Endpoints
Examples (not public client APIs):
- `/qo/alive/upload`, `/qo/upload/status`, `/qo/online`, `/qo/offline`
- `/qo/msglist/upload`, `/qo/advancement/upload`, `/qo/metro/upload`
- `/qo/upload/gametimerecord`, `/qo/upload/loginattempt`, `/qo/upload/explevel`
- `/hooks/*`, `/kuma/upload`, `/qo/combatzone/*`, `/qo/proxies/*`
