# Workstream `harness`

Lane: `backend/src/main/kotlin/net/robog/clarity/harness/**`, its tests, `config/AgentConfig.kt`,
`application.yaml` agent section.

The harness stays independent of backend domain code. Goal: make it fit for one very long
lead thread plus helpers (see "Agent runtime" and "Context engineering" in `ARCHITECTURE.md`).

## Shape

- **One provider.** `OpenRouterModelGateway` (Chat Completions over the JDK HTTP client, streamed
  SSE, retries before the first byte, cancellation aborts the read) is the only `ModelGateway`.
  The direct Anthropic API, its Java SDK, server-side compaction, tool search, server tools,
  refusal fallbacks and thinking-signature replay are gone; Claude runs through OpenRouter as
  `anthropic/...`.
- **Messages model** (`Model.kt`): `Block.Text`, `ToolUse`, `Result` and `Raw` (opaque JSON,
  replayed byte-exact). `Raw` holds OpenRouter's `reasoning_details` (tagged with provider and
  model, replayed only to that model) and, in journals from before the move, Anthropic's own
  blocks; those are read at request time and never rewritten (a `compaction` block becomes its
  summary text and is a compaction point for `contextSince`; the rest is left out).
  Mid-conversation system messages (`Message.system`) and effort-only messages (`Message.effort`)
  keep their placement rules (`systemPlacementViolations`, `wireOrder`).
- **Request** (`ModelRequest`/`RunRequest`): system as a list of blocks (static prefix, then the
  constitution) with `cacheBreakpoint`, all tools (sorted), `effort`, `cache` (`CachePolicy`: TTL
  5m or 1h, automatic tail breakpoint, at most four breakpoints), `structuredOutput` for one-shot
  calls. Cache breakpoints are sent only for models that use them (`usesCacheBreakpoints`:
  `anthropic/*`), as OpenRouter `cache_control` on system and user text parts plus the top-level
  automatic one (OpenRouter prompt caching guide); `reasoning.effort` keeps `xhigh`/`max` for
  Claude and caps other models at `high`.
- **Compaction** is client-side only (`LeadAgent`): a `[clarity:summary]` user message appended to
  the journal; requests start at the latest one (`sinceSummary`, `contextSince`).
- **Journal + resume, pre-emption, single calls, usage** (cache read/write tokens, OpenRouter's
  reported cost as `reportedCostMicros`), `CacheMiss` events (only with a cache policy), the large
  result sink and input validation are as before.

## Tests

Scripted gateway tests (never a real model): system-message placement, journal checkpoint/resume
(incl. pending tool_use), interrupt during model call and during tools, cache-miss event, usage
accumulation, large result sink; request-body tests for the OpenRouter wire (tools, effort,
privacy routing, `cache_control` shapes and limits); `StoredAnthropicHistoryTest` with rows shaped
like stored Anthropic journal entries.
