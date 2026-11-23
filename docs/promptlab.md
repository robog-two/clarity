# Prompt lab

A local tool for tuning the voice, tone and readability of what the lead sends through `send_to_user`.
Extract real moments from the live database, write the message you wish it had sent, then replay the
same moments against the prompts as they are in the code and compare.

The code is in `backend/src/test/kotlin/net/robog/clarity/backend/promptlab/` (the test source set, because
it reuses `LeadRig` to build the real lead toolbox). It holds no data.

## Where the data lives

Everything extracted is in `~/clarity-promptlab/` (mode 700, files 600), outside the repo. It holds the
person's private messages: never commit it or copy it into the repo. Override with `CLARITY_PROMPTLAB_HOME`.

```
~/clarity-promptlab/
  scenarios.md                 the one file to fill in
  scenarios/NN-type-MMDD/input.json   run id, seq, time, constitution version and document, the context, the original
  runs/<timestamp>/report.md   side by side: yours, original, replays
  runs/<timestamp>/raw.json    the raw answers, token usage and request sizes
```

## Extract

Reads the production database read-only through `podman exec -i clarity_postgres_1 psql` (every session sets
`default_transaction_read_only`, and only `select` statements are accepted by the tool). Needs the podman
container running on this host.

```
flock /tmp/clarity-gradle.lock ./gradlew :backend:promptlabExtract
```

Options, passed with `--args="..."`:

- `--list` print every `send_to_user` moment (seq, local time, type, trigger) and stop
- `--days N` how far back to look (default 16)
- `--seqs 5942,6075,...` extract exactly these journal seqs, in this order, instead of the default mix
- `--force` replace an existing `scenarios.md` and scenario folders (the old file is kept as `scenarios.<time>.bak.md`).
  Without it the tool refuses, so written answers are never lost by accident.

The default mix is 20 scenarios, spread over time and one per moment: 5 replies, 2 light chat, 3 helper results,
2 check-ins, 2 look-forward, 1 finding, 1 held or declined, 1 unfinished task, 3 session messages. The type is
guessed from what triggered the call (the person's words, a nudge, a helper result, a scheduled review, a held action
ruling) and from `kind` and `sessionId`. Types that have too few real examples are printed as `MISSING`.

Each scenario is cut just before the assistant message that holds `send_to_user`, using the context since the latest
compaction point (`contextSince`), with the constitution version that was live then.

## Fill in

Open `scenarios.md`. Each scenario shows what the lead was given (the person's words or operator update in full, the
earlier exchanges condensed, tool results cut to 300 characters; the system prompt is left out) and an empty block:

```
<!-- yours:07 -->
write the message you wish it had sent
<!-- /yours -->
```

The original sits in a collapsed "What it sent" section below so it does not bias you. Blocks left empty are skipped.

## Replay

```
flock /tmp/clarity-gradle.lock ./gradlew :backend:promptlabReplay
flock /tmp/clarity-gradle.lock ./gradlew :backend:promptlabReplay --args="--only 3,7,12 --runs 5"
```

For each scenario it rebuilds the lead's request from the code as it is now: `AgentPrompts.LEAD_SYSTEM` plus the
constitution block and the mail accounts line (`leadSystemBlocks`), all of the lead's tools (`AgentToolbox.leadTools`),
the journal context through the harness' own request conversion, and the live lead role settings (model
`deepseek/deepseek-v4.1-flash-1017`, effort and max tokens from `AgentRoles`). It samples until `--runs` answers called
`send_to_user` (at most 3 x `--runs` calls) and runs no tools. So an edit to a prompt or a tool description shows up on the next replay.

Options: `--only 3,7,12` a subset, `--runs N` answers that sent per scenario (default 3; the report's Sampling line says how many samples it took and which tools the others called instead), `--model ID` another model, `--dry` build the
requests and print system chars, tool count, message count and request size without calling anything.

The report shows, for each scenario, yours (from `scenarios.md`), the original, and each replay, with word counts. A
replay is the text of its `send_to_user` cards (text, the choices prompt and options, kind and urgency). A "Sampling" line per scenario counts the samples, e.g. "3 sent, 2 looked up first: list_calendar_events (5 samples)".
The replay stops after one step, so a model that wants a lookup first counts as not sent.
The path of `report.md` is printed at the end. Cost is about a tenth of a cent per call.

The OpenRouter key is read at runtime from `OPENROUTER_API_KEY`, else the file in `OPENROUTER_API_KEY_FILE`, else
`~/podman-compose/clarity-main/deploy/secrets/openrouter_api_key`. It is never printed or written.

## Fixed text refresh

Server-injected fixed text is frozen inside stored journal messages: session playbooks, the nudge, helper, held and action
notices, job instructions, the inbox and clock headers, the notes index headers, and the `send_to_user` result sentences.
Before building a request, replay swaps each known frozen string for what the current code renders for it
(`FixedTextSwaps.kt`, old text frozen in `FixedTextOld.kt`), so edits to `Playbooks`, `InboxItem`, the review and outing job
text and the like show up on the next replay. Only exact matches are replaced, and the person's words and the data inside
notices are never touched. Each scenario logs `refresh: N matched, M changed`; a replay of unedited code changes only text
that was written by an older version than the code. `--no-refresh` replays the frozen text.

## Limits

- Fixed text that is not in `FixedTextSwaps.kt` is replayed as written: tool results made inside tool bodies (journal saved,
  helper started, plan saved, nudge set, note saved and similar), the compaction summary, and any notice a new scenario holds
  that has no swap yet. The system prompt, the constitution block, tool definitions and request conversion follow the code.
- The replay is one step. A scenario where the original made tool calls before sending keeps those calls and results in
  its context, but a replay that wants more tools first ends without a message.
- Tests: `flock /tmp/clarity-gradle.lock ./gradlew :backend:test --tests '*PromptLabCoreTest'` (pure core, no database).
