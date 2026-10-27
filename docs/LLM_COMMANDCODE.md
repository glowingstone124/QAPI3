# Command Code is deprecated

The root `enableCommandCode` option is ignored. Remove it and the legacy endpoint-free `providers.commandcode` block from the configuration. Configure `fallbackProviders` as documented in the [LLM provider setup](../README.md#llm-tool-calling) instead.

Existing explicit `commandcode` protocol support remains temporarily for migration; new configurations should use the supported Chat Completions, Responses, or Anthropic protocols.
