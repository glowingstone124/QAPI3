# Command Code provider

Set the root `enableCommandCode` flag to `true` to prefer Command Code for model presets listed in `providers.commandcode.models`. QAPI3 uses the CLI `/alpha/generate` endpoint internally, so this provider block needs no endpoint fields. When the flag is absent or `false`, the selected `defaultProvider` continues to serve requests.

The `providers.commandcode` block contains `tokenFile`, `contextWindow`, and `models`. Each model preset contains a `model` identifier and a `pricing` object; no endpoint URL is required. Keep deployment-specific provider settings in the server's local `data/llm/providers.json`.

`tokenFile` can contain a raw API key or the official CLI's JSON `auth.json` with an `apiKey` field. A `~/` prefix resolves against the process user's home directory. Each Command Code model requires the same `pricing` fields as other providers. The configured model preset must also exist in the default provider so fallback can resolve it.

If the Command Code token file is missing or empty, QAPI3 uses the default provider until a valid token becomes available on a configuration reload.

QAPI3 falls back after a connection exception, HTTP 5xx, or an upstream stream that ends before `finish`. It does not fall back after a client-visible text delta or after local tool execution, avoiding duplicate output and tool effects. HTTP 4xx does not trigger fallback. Fallback uses the default provider's model, context window, and pricing for settlement. Internal summaries continue using the configured `summary.provider`.

The `get_remain_balance` tool is not advertised to Command Code and cannot be executed from its tool calls. A request that falls back to the default API may use that tool under the default provider's normal rules.

Image input requires an OpenAI `image_url` content part containing a base64 `data:image/...;base64,...` URL. QAPI3 sends it as the official CLI's `{ "type":"image", "image":"data:image/...;base64,...", "mimeType":"image/..." }` part. HTTP image URLs are rejected by this adapter. A live red/blue/no-image check with `moonshotai/Kimi-K3` returned Red/Blue/NO_IMAGE. `deepseek/deepseek-v4.1-flash` is marked vision-capable by Command Code CLI 1.54.2, but the same check sometimes returned White or NO_IMAGE for an attached image; verify that model with your account before relying on its image answers.

This is an unofficial CLI protocol integration. It follows the locally inspected Command Code CLI 1.54.2 wire and may need updates if that protocol changes.
