# QAPI3

## Become a sponsor

Sponsor me on afdian [link](https://afdian.com/a/glowingstone124)

## Fast, SQL-safe Api.
This program contains these features:
- Minecraft Server validation: User can easily bind their account to api,
- Chat Sync between several Minecraft Servers(WIP): This feature allows users to chat across servers.
- Minecraft Server Status Query: This api will automatically show latest server status.(Need Plugin-side support)

## Future RoadMaps
More codes in Kotlin

DIY player card with QCommunity WEB

Add Discord support
## Guides
### To install:
    
simply just run gradle build and that's all. Don't forget to add configurations like MySQL server and Redis!

I recommend you to just run program in docker and redirect port to nginx, etc.

To add components, sub servers, please add node object in `nodes.json`

```JSON
[
  {
    "name": "QQ",
    "id": 0,
    "role": "SERVER",
    "token": "123456"
  },
  {
    "name": "QO",
    "id": 1,
    "role": "SERVER",
    "token": "123456"
  }
]
```

and then they can post messages, add new features, etc...

### Database configuration

Database access is managed by Spring Data R2DBC. Connection settings continue
to come from `data/sql/info.json`:

```json
{
  "url": "jdbc:mysql://db.example:3306/qapi",
  "username": "qapi",
  "password": "change-me"
}
```

The existing `jdbc:mysql:` URL is converted to `r2dbc:mysql:` when the pool is
created. The original pool defaults (initial/minimum idle 5, maximum 100,
3-second acquisition timeout, and 60-second eviction interval) are retained;
do not configure a JDBC `DataSource` for request handling.

### API Endpoint

GET `/qo/download/status` -> 
``` JSON
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

GET `/qo/msglist/download` -> 
```JSON
{
    "messages": [
        "服务状态更新：\nService SortMC 的状态为 null\n最新的heartbeat状态为： 1 延迟 77ms",
        "[QO] 玩家Lplayer加入了服务器。",
        "[QO] 玩家Lplayer退出了服务器，本次游玩时间 4分钟",
        "[QO] 玩家Lplayer加入了服务器。",
        "服务状态更新：\nService QAPI origin 的状态为 null\n最新的heartbeat状态为： 0 延迟 0ms",
        "[QQ] <CHJWOS_|2859972822>:wow",
        "[QQ] <CHJWOS_|2859972822>:https://www.mcmod.cn/class/14114.html",
        "[QQ] <glowingstone124|1294915648>:[CQ:reply,id=986310598][CQ:at,qq=2859972822,name=CHJWOS_] fabric mod自定义gui的话",
        "[QQ] <glowingstone124|1294915648>:是不是要拿opengl嗯写",
        "[QQ] <CHJWOS_|2859972822>:高版本可能是有原版帮你写好了的",
        "[QQ] <CHJWOS_|2859972822>:不是原版风格的就自己写",
        "[QQ] <glowingstone124|1294915648>:我不想要原版",
        "[QQ] <glowingstone124|1294915648>:我想写个现代化ui",
        "[QQ] <CHJWOS_|2859972822>:矩形啥的应该是已经内置了",
        "[QQ] <CHJWOS_|2859972822>:如果有模糊啥的",
        "[QQ] <CHJWOS_|2859972822>:可能1.20.5往上也有",
        "[QQ] <glowingstone124|1294915648>:行",
        "[QQ] <glowingstone124|1294915648>:动画呢",
        "[QQ] <glowingstone124|1294915648>:是不是要自己搓",
        "[QQ] <CHJWOS_|2859972822>:是了",
        "[QQ] <東雪蓮Official|3125265713>:累了",
        "[QQ] <東雪蓮Official|3125265713>:今天操场上测了下速",
        "[QQ] <東雪蓮Official|3125265713>:23圈",
        "[QQ] <東雪蓮Official|3125265713>:一圈400",
        "[QQ] <東雪蓮Official|3125265713>:15分钟",
        "[QQ] <東雪蓮Official|3125265713>:每小时大约36km",
        "[QQ] <東雪蓮Official|3125265713>:[CQ:image,file=6EF6BBF5A4EC9B58B3754A1E7836C689.jpg,subType=1,url=https://multimedia.nt.qq.com.cn/download?appid=1407&amp;fileid=CgozMTI1MjY1NzEzEhT-HH8woFCrx7llrTrMAfDy9YbiQBiutiMg_woo9s29x5beiAMyBHByb2RQgL2jAQ&amp;spec=0&amp;rkey=CAMSKMa3OFokB_TlE5oz_MZGn_1PxOOLL_sQeAG7OFPt_2onFxvUsjDhYv0,file_size=580398]",
        "[QQ] <CHJWOS_|2859972822>:哇啊",
        "[QQ] <CHJWOS_|2859972822>:全世界的人都在教我怎么sampler2D传入图片",
        "[QQ] <CHJWOS_|2859972822>:我想要传入当前帧画面的教程啊",
        "[QQ] <glowingstone124|1294915648>:那你把这一帧变成bytemap"
    ],
    "empty": false
}
```

GET `/qo/download/registry?name=glowingstone124` -> 
```JSON
{
    "qq": 1294915648,
    "code": 0,
    "frozen": false,
    "online": false,
    "economy": 0,
    "playtime": 1712,
    "last_login": 1787558400000
}
```

### LLM Tool Calling

The OpenAI-compatible non-stream chat endpoint can execute built-in tools before returning the final assistant message.

- `get_server_status`: query Minecraft server status and player counts.
- `get_player_rankings`: query mining, placement, and cumulative playtime leaderboards.
- `query_metro_lines`: search metro lines, stations, sections, and signal coordinates.
- `search_minecraft_knowledge`: search the configured RAG knowledge base for Minecraft/QO information.
- `add_memory`: create or update a structured per-group long-term memory.
- `search_memory`: query structured memories for the current group.
- `forget_memory`: delete structured memories only when explicitly requested.
- `get_member_profile`: read the current user's persistent QQ-uid profile.
- `upsert_member_profile`: create or update a confirmed identity, preference, summary, or group nickname under the current user's QQ uid.
- `forget_member_profile_field`: delete a profile field when the current user explicitly asks to forget it.

Structured memories are stored in the automatically created MySQL `llm_memories` table. A memory is uniquely identified by `group_id + subject + memory_key`, so multiple facts about the same subject can coexist. On the first startup after upgrading, legacy `data/llm/rag/<groupId>/memory.txt` and `data/llm/rag/groups/<groupId>/memory.txt` files are imported once; completion is recorded in `llm_memory_migrations`. Legacy files are retained for rollback but are excluded from RAG after migration.

Member profiles are stored separately in `llm_member_profiles` and `llm_member_profile_fields`. QQ `uid` is the global unique identity and receives a stable generated `profile_id`; `group_nickname` is scoped by group. Durable profile facts must be created through the explicit `/remember content` protocol. Only explicitly persisted facts belonging to the current sender are injected; other participants contribute identity metadata only.

Group context is incrementally converted into a multi-member fact and dialogue-relation summary with the provider's configured `summary.model`. Raw group messages are archived but are not automatically included in the main prompt; the model retrieves a small relevant subset with `search_chat_history` when exact wording or unresolved references require it. Summaries are policy-versioned so older summaries are rebuilt after isolation-policy changes. Boolean environment variables accept only `true` and `false`.

Related environment variables:

- `LLM_SYSTEM_PROMPT`: fixed system prompt text. When set, it takes precedence over the prompt file.
- `LLM_SYSTEM_PROMPT_FILE`: system prompt file. Linux inotify events, atomic replacements, Docker bind mounts, and Kubernetes ConfigMap/Secret-style replacements are reloaded without restarting the API; invalid or blank updates keep the previous valid prompt.
- `LLM_QO_GROUP_ID`: QQ group allowed to access QO server knowledge and server tools. If omitted, QO RAG and server tools remain unavailable.
- `LLM_BLOCKED_QQ_UIDS`: comma- or space-separated QQ uids denied before any LLM request. If omitted, no user is blocked by this rule.
- `LLM_ULTRA_BRIEF_QQ_UIDS`: comma- or space-separated QQ uids that receive one-sentence replies unless safety or factual clarification requires more.
- `LLM_STRIP_EMOJI`: set to `true` to remove emoji from upstream answers during output sanitization. Tool-call markup and emoticons are always removed.
- `LLM_GROUP_SUMMARY_ENABLED`: enable per-group rolling fact summaries, default `true`. When disabled, raw history is still archived for explicit search but is not automatically injected.
- `LLM_GROUP_SUMMARY_DIR`: persistent rolling-summary directory, default `data/llm/summaries`.
- `LLM_GROUP_SUMMARY_MAX_CHARS`: maximum persisted summary characters per group, default `5000`.
- `LLM_GROUP_SUMMARY_TIMEOUT_MS`: maximum time spent updating a summary; on timeout, the previous safe summary is retained and raw history is not injected, default `15000`.
- `LLM_HISTORY_TTL_MS`: in-memory conversation lifetime, default `1800000` (30 minutes).
- `LLM_MEMORY_CONTEXT_MAX_ITEMS`: maximum relevant memories injected into a request, default `10`.
- `LLM_MEMORY_CONTEXT_MAX_CHARS`: maximum memory context characters, default `6000`.
- `LLM_MEMBER_PROFILE_CONTEXT_MAX_ITEMS`: maximum qbot high-activity member profiles injected into one request, default `50`.
- `LLM_MEMBER_PROFILE_CONTEXT_MAX_FACTS`: maximum self-declared facts accepted from each member profile, default `16`.
- `LLM_MEMBER_PROFILE_CONTEXT_MAX_CHARS`: maximum total qbot member-profile context characters, default `20000`.

QQ group messages are archived in the `llm_chat_history` table through `POST /qo/asking/v1/chat/history`. The bot endpoint also backfills its sliding `group_context`, using stable source IDs and `INSERT IGNORE` for idempotency. The LLM can retrieve older, group-scoped records with the `search_chat_history` tool; results never cross group boundaries.
- `LLM_TOOLS_ENABLED`: enable built-in tools, default `true`.
- `LLM_WEB_SEARCH_ENABLED`: enable DeepSeek server-side web search for non-stream `deepseek-v4-flash` requests, default `true`.
- `LLM_PROVIDERS_FILE`: provider configuration JSON path, default `data/llm/providers.json`. The file is watched and the configuration (including referenced token files) is periodically reloaded; invalid updates keep the last valid provider.
- `LLM_PROVIDER`: selected provider name. If omitted, the JSON `defaultProvider` is used and may be changed by hot-reloading the provider file. When set, this environment override remains fixed until restart.
- `LLM_RESPONSES_API_URL`: legacy fallback Responses API endpoint. Provider JSON should use an explicit `responsesUrl`.
- `LLM_RESPONSES_MODELS`: legacy comma-separated Responses model aliases. Provider JSON should use `responsesModels`.
- `LLM_TOOL_MAX_ROUNDS`: maximum tool-call loops per request, default `3`.
- `LLM_TOOL_METRO_MAX_RESULTS`: maximum metro search results returned to the model, default `12`.

Provider configuration example (`data/llm/providers.json`):

```json
{
  "defaultProvider": "deepseek",
  "providers": {
    "deepseek": {
      "chatCompletionsUrl": "https://api.deepseek.com/v1/chat/completions",
      "responsesUrl": "https://api.deepseek.com/v1/responses",
      "tokenFile": "LLMAPITOKEN",
      "contextWindow": 524288,
      "models": {
        "fast": "deepseek-v4-flash",
        "thinking": "deepseek-v4-pro",
        "quality": "deepseek-v4-pro"
      },
      "summary": {
        "provider": "another-provider",
        "model": "fast",
        "contextWindow": 32768
      },
      "compact": {
        "enabled": true,
        "triggerTurns": 12,
        "triggerPercent": 70,
        "keepTurns": 4,
        "maxSummaryChars": 8000
      },
      "responsesModels": ["fast"]
    },
    "another-provider": {
      "chatCompletionsUrl": "https://example.com/v1/chat/completions",
      "responsesUrl": "https://example.com/v1/responses",
      "tokenFile": "data/llm/another-provider.token",
      "contextWindow": 524288,
      "models": {
        "fast": "provider-fast-model",
        "thinking": "provider-thinking-model"
      },
      "summary": {
        "model": "provider-summary-model",
        "contextWindow": 32768
      },
      "compact": {
        "enabled": true,
        "triggerTurns": 12,
        "triggerPercent": 70,
        "keepTurns": 4,
        "maxSummaryChars": 8000
      },
      "responsesModels": ["fast", "thinking"]
    }
  }
}
```

`responsesUrl` is always used as written and is never derived from or truncated from
`chatCompletionsUrl`. Set `LLM_PROVIDER=another-provider` to switch providers.
`responsesModels` controls which model aliases use the Responses API; `LLM_WEB_SEARCH_ENABLED`
only controls whether the Responses request includes web search.

`contextWindow` is the main model's context-window size in tokens (default `524288`). The API keeps the
system prompt and newest user messages, then drops the oldest history when the estimated
input would exceed that window. `models` accepts arbitrary preset names; request the `quality`
preset with `?model=quality`. `summary.provider` may reference any configured provider, while
`summary.model` may be any preset of that provider or a provider model name. `summary.contextWindow`
independently limits the summary request. Summary settings default to the selected provider's
`fast` model and the main `contextWindow`.
Conversation autocompact uses the same summary configuration. Its provider-local `compact`
object defaults to `enabled=true`, `triggerTurns=12`, `triggerPercent=70`, `keepTurns=4`, and
`maxSummaryChars=8000`. It replaces older turns with one rolling summary once raw history exceeds
the turn or token threshold, while keeping the newest configured turns.

Each upstream LLM request logs its source, provider name, resolved model, and API type. Provider
reloads are logged as `[LLM] reloaded provider old -> new`; tokens and request bodies are not part
of this status log. Full request-body logging remains separately controlled by `LLM_DEBUG_PROMPT`.

## For Contributors

When integrating this project with GoCi, please notice there are some flags can be use.

[SKIP CI]: when pushing a commit which description contains this, GoCi will automatically skip build this commit.
