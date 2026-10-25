# Workstream `harness`

Lane: `backend/src/main/kotlin/net/robog/clarity/harness/**`, its tests, `config/AgentConfig.kt`,
`application.yaml` agent section, the anthropic-java version in the catalog.

The harness stays independent of backend domain code. Goal: make it fit for one very long
lead thread plus helpers (see "Agent runtime" in `ARCHITECTURE.md`).

## Build

1. **SDK check.** Verify which features `com.anthropic:anthropic-java` (catalog version) exposes:
   beta messages with `betas`, compaction (`compact-2026-01-12`), context management,
   tool search tools (`tool_search_tool_bm25_20251119`) and `defer_loading`, `cache_control`
   with `ttl`, top-level automatic caching, mid-conversation `system` role messages,
   `fallbacks: "default"` (`server-side-fallback-2026-07-01`), `output_config.effort`,
   `thinking.display`, structured outputs. Upgrade the SDK if a newer release on Maven Central
   adds what is missing. Use `javap`/`jar tf` on the jar to find names; where a field is not
   typed, use the SDK's additional-body-properties escape hatch. Record findings in the
   `AnthropicModelGateway` KDoc.
2. **Messages model.** Extend `Model.kt` so the history can hold everything needed, appended
   verbatim: compaction blocks, server tool use/results (tool search, web search/fetch),
   thinking blocks, and **mid-conversation system messages** (`Message.system(text)`; placement
   rule: after a user message or before an assistant turn). Keep blocks opaque where we don't
   inspect them (store raw JSON) so replay is byte-exact.
3. **Request features** on `ModelRequest`/`RunRequest`: `deferredTools` (sent with
   `defer_loading: true` plus the BM25 tool search tool; never all deferred), `serverTools`
   (web search/fetch `_20260209`), `cache` policy (explicit breakpoint on the last static system
   block + top-level auto caching; TTL 5m or 1h per role), system as a list of blocks
   (static prefix block(s) then the constitution block), `compaction` on/off with trigger,
   `fallbacks`, `effort`, `thinkingDisplay`, `structuredOutput` (JSON schema) for single calls.
4. **Model roles.** `AgentConfig`: roles `lead`, `helper`, `guard`, `triage` → model, effort,
   max tokens, cache TTL, compaction. Defaults per `ARCHITECTURE.md`. Env overrides
   `CLARITY_AGENT_<ROLE>_MODEL` / `_EFFORT`. Keep the old `CLARITY_AGENT_MODEL` as the lead default.
5. **Journal + resume.** An interface `RunJournal { suspend fun append(runId, message); suspend fun load(runId): List<Message> }`
   (in-memory impl here; the Postgres impl is `lead`'s). `Harness.run` checkpoints each assistant
   message and each tool-result message; `Harness.resume(runId, request)` continues from the
   journal (if the last message is an assistant tool_use without results, re-run those tools).
6. **Pre-emption.** `Harness.run` accepts an `interrupts: Flow<List<Message>>` (or a channel):
   when an interrupt arrives during a model call, cancel the call, discard the partial response,
   and continue with the interrupt messages appended; during tool execution, wait for running
   tools, append their results, then the interrupt content in the same user message.
7. **Single calls.** `ModelGateway.complete(...)` for one-shot structured calls (guard, triage,
   summaries) with structured output.
8. **Usage.** `Usage` carries cache read/write tokens (5m/1h split if present); `HarnessEvent`
   gets a `CacheMiss` warning event when a turn after the first reads 0 cached tokens.
9. **Tool results**: an optional `ToolResultSink` that, for results larger than N chars,
   stores the full text (callback) and returns a truncated text + reference. The sandbox path
   is wired later by `lead`.
10. **Eager input streaming** on client tools when streaming, with validation of parsed input
    against the schema before invoking (the `typedTool` decoder already validates; make sure a
    truncated/invalid input returns an error result to the model).

Remove the `REQUIRE_APPROVAL`/`ApprovalRequired` path only if nothing else uses it after
your change; governance will gate external tools inside the tools themselves (they call an
`ActionGate` and return "held for review" results), so the harness needs no approval logic.
Keep `HIDDEN`.

## Tests

Scripted gateway tests for: compaction block round-trip, system-message placement, deferred
tool definitions in the request, journal checkpoint/resume (incl. pending tool_use), interrupt
during model call and during tools, cache-miss event, usage accumulation, large result sink.
A gateway test that the SDK request JSON contains the expected beta fields (serialize the params).
