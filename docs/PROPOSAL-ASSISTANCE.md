# Bounded Source-Grounded Proposal Assistance

This is a private operator action in the [Topic Workshop](TOPIC-BUILDER.md), not
automatic player-topic generation. The OpenAI Responses adapter is implemented,
but **live assistance is unconfigured and unverified**. This milestone used no
API key, paid account, live model request or provider spending. Manual authoring
and JSON import work without it. Test fixtures are only under `src/test/java`;
there is no fixture selector, canned success or fallback in the normal UI.

[Durable player games](DURABLE-GAMES.md) now recover separately from workshop
jobs/attempts. Player restart does not clear unexpired pinned games or grant
provider authorization. Keep `CLUELAB_ASSISTANCE_ENABLED=false` on owned local
server commands; this affects only that process, not the global environment.

## Launch and Operator Flow

With JDK 21/Maven 3.9.x, from the repository root:

```sh
mvn -o clean verify
mvn -o -q compile exec:java -Dexec.mainClass=main.TopicBuilderServer
```

Open **http://127.0.0.1:8766/** and use that process's private access code.
**Source-Grounded Proposal Assistance** displays **Live Assistance Unconfigured**
and disables its submit button. New Proposal Form, import, editing, validation
and export remain available. No key setup or external call occurs at startup.

After a future, explicitly authorized live configuration:

1. Select a managed captured source set and inspect its full saved context,
   attribution, revision uncertainty and displayed source/byte counts. Larger
   captures fail rather than being truncated or silently substituted.
2. Enter new pack/topic IDs, a new draft version, label/description, supported
   provisional difficulty and a maximum of 1-5 proposals. Inspect provider/model,
   limits, configured token rates and estimated budget, then explicitly confirm.
3. Submit once. Reconnect after a lost response; the saved UUID recovers that
   job, not a fresh provider call. Inspect reported usage/estimates and screening
   next to the exact support and full captured source. Fewer or zero proposals
   are valid outcomes; five is a maximum, not a promise of a playable game.
4. Explicitly select proposals to use, open the existing editor, correct wording,
   hints/aliases/support and retain provenance. New captures must be prepared
   separately, not attached to an old attempt. Importing generated work requires
   its original managed attempt in this workshop.
5. Explicitly validate/export an immutable draft. It has **zero eligible clues**.
   Use the separate source/editorial review, comparison, new reviewed version,
   dry-run plan, exact activation and manual player restart workflow. Generation
   cannot create reviews, resolve flags, mark human approval or activate content.

Source requests and player requests do not trigger assistance automatically.
An operator may deliberately attach an existing player request to a generation
job; that records actual preparation and then Awaiting Review, never Ready based
on a model result. Existing exact-target/live-index readiness gates remain.

## Future Configuration Without Rebuilding

Use the secure credential-setup workflow only when authorized. Do not paste a key
into chat, proposal JSON, browser storage or public assets. No key was created or
written in this pass. Configure the **operator server process environment**;
Java does not automatically load `.env` files. Restart the workshop to apply
configuration; application rebuild is unnecessary. Do not restart the player as
a side effect. Partial/invalid configuration leaves assistance unconfigured and
does not disable manual work.

| Variable | Required Meaning / Bound |
| --- | --- |
| `CLUELAB_ASSISTANCE_ENABLED` | Explicit `true`, only after authorization. Absent/other values disable live calls. |
| `OPENAI_API_KEY` | Server-only credential supplied through the secure workflow; never returned by the API. |
| `CLUELAB_ASSISTANCE_MODEL` | Explicit compatible model/snapshot supporting Responses structured output; no default or automatic substitution. |
| `CLUELAB_ASSISTANCE_INPUT_TOKENS` | Conservative input admission allowance, 4,096-65,536. |
| `CLUELAB_ASSISTANCE_OUTPUT_TOKENS` | API `max_output_tokens`, 256-8,192, including reasoning output where applicable. |
| `CLUELAB_ASSISTANCE_INPUT_RATE` | Positive operator-verified standard USD per million input tokens. |
| `CLUELAB_ASSISTANCE_OUTPUT_RATE` | Positive operator-verified standard USD per million output tokens. |
| `CLUELAB_ASSISTANCE_PRICING_DATE` | Actual `YYYY-MM-DD` date of the operator's rate check. |
| `CLUELAB_ASSISTANCE_ATTEMPT_USD` | Positive local estimated cap, at most USD 10; not spending authorization. |
| `CLUELAB_ASSISTANCE_WORKSPACE_USD` | Persistent local reservation cap, at least the per-attempt cap and at most USD 100. |

No model or production prices are hardcoded. Inspect current official
[Responses request fields](https://developers.openai.com/api/reference/resources/responses/methods/create),
[structured-output constraints/refusals](https://developers.openai.com/api/docs/guides/structured-outputs),
[error handling](https://developers.openai.com/api/docs/guides/error-codes) and
[pricing](https://developers.openai.com/api/docs/pricing) before enabling.
Documentation inspected on 2026-11-06; the very large reference page's full open
failed, while its indexed request-field reference and structured-output guide
were available. A live model/schema/authentication smoke test is still required.
`store: false` is requested; it is not a claim of zero provider retention or
rights clearance. Inspect applicable provider data/terms and source rights first.

## Limits and Spending Semantics

One workshop worker, one active operation, at most 32 durable jobs. Each attempt
has **one Responses POST**, no tools/background execution, no redirect following
and **zero application-level automatic retries**, including 429/5xx. The JDK
HTTP/1.1 adapter is tested against lost POST responses without resending. One
explicit retry per original attempt chain is allowed, with a new UUID, original
managed attempt ID, same capture/difficulty/count and duplicate-cost acknowledgement.
Preparing that retry restores the original capture, level, count and draft
metadata without restoring spending consent. The server rechecks the chain and
budgets at submission, including when reopening an older historical outcome.
No API-side idempotency guarantee is assumed.

Input: 1-12 captured sources, at most 16,384 UTF-8 bytes each / 32,768 total.
The source-only serialized request plus 1,024 units of overhead must fit the
configured input allowance. This is a conservative byte-based token admission
bound, **not a measured tokenizer count**. Unexpected reported token excess is
recorded and rejected, not silently accepted or retried; it cannot undo a charge.
Output: configured API token cap, 128 KiB response/body/parser bound, at most five
clues, eight aliases per clue, 1,000-character wording, 400-character hint and
1,200 UTF-16-unit exact quote. Whole attempt/provider timeout is 60 seconds with
10-second connection timeout and cancellation checks during waiting. Local
immutable receipt writes can finish after cancellation to retain a received reply.

Before dispatch, reserve `(inputAdmission * inputRate + maxOutput * outputRate)
/ 1,000,000`. It must fit the per-attempt cap and the sum of retained reservations
must fit the workspace cap. Reservations are **not automatically refunded** for
failure, cancellation, cheap usage or unknown outcomes. They survive restart and
rate/model changes. This is intentionally conservative, not an exact invoice or
an account-wide provider spending limit. Use separate provider project controls
before any future live pilot. Existing scan/storage limits may fail earlier.

If reported input/output counts exist, the report calculates a standard-rate
estimate using that attempt's recorded rates/date. Missing usage stays null,
never zero. The bounded raw response retains other available provider fields.
Cache discounts, taxes, tiers, billing adjustments and price changes mean the
estimate is not a bill. Synthetic fixture counts/rates/estimates are never live
usage, actual costs or claims about current model pricing.

## Attempts, Cancellation and Recovery

All files are private under the existing owner-only, ignored job directory.

| Durable Record | Meaning |
| --- | --- |
| `request.json` | Operator's immutable retry payload, managed capture and requested draft metadata. No credential. |
| `attempt/intent.json` | Attempt/chain identity, template, capture/input hashes, settings, source/input bounds and reserved estimate. |
| `attempt/capture.json`, `attempt/payload.json` | Exact capture and source-only provider payload; no answer keys, review data or credential. |
| `dispatch/marker.json` | Persisted **before** the potentially billable call. Not proof the provider received it. |
| `received/response.json` | Bounded raw reply, HTTP status, request ID and receipt time, saved before parsing. |
| `artifact/*` | Original private proposals, capture and validation/provenance report; immutable. |
| `rejection/report.json`, `outcome/result.json` | Validation/usage diagnostics and final job outcome. |

An exact retry UUID returns the existing job; changed payload conflicts. Reload
and lost HTTP responses recover server work. Restart never dispatches an unfinished
attempt automatically. A recorded response can be finalized locally without a
provider call; an existing artifact is returned unchanged. A dispatch marker
without a received reply becomes **Uncertain**, even if the process might have
stopped before transmission. Inspect it before any deliberate retry.

Cancellation before dispatch makes no call. After dispatch, local cancellation
cannot promise provider cancellation or no charge. An unreceived reply remains
Uncertain. A received reply can retain usage while the job stays Cancelled;
late output/restart must not publish new proposals when cancellation precedes
artifact commit. An earlier immutable commit can win that race and is never
deleted by cancellation. Neither cancellation
nor model output changes catalog/game state or reopens a closed player request.

Matching uncertain attempts block a fresh hidden dispatch. One acknowledged
retry is available; an exhausted/ambiguous chain requires manual inspection and
manual authoring or deliberate stopped-server workspace maintenance, not unlimited
retry loops. Archive receipts privately before changing/resetting a workspace;
never delete live locks or treat a reset as proof that old provider charges did
not occur. Atomic filesystem replacement is not a power-loss durability guarantee.

## Evidence and Approval Boundaries

The request whitelists captured page IDs, canonical titles, source text and text
hashes, plus count/difficulty. It does not read packs, accepted answers, review
decisions, player guesses or TriviaQA material. Provider/model, template version,
captured revisions/unknowns and exact input/response hashes remain traceable.
Source text and output are untrusted: fixed endpoint, no browser paths/URLs,
commands, tools, status changes or credential access. UI uses inert text rendering.

Structured replies must use selected canonical article-title answers, exact unique
support from that article and supported difficulty/type. Server-derived UTF-16
ranges reject fabricated, repeated/ambiguous or surrogate-splitting passages.
Repeated fact IDs/support are rejected rather than padding the batch. Invalid
aliases/references/types/fields are rejected; answer-revealing wording/hints,
shared/unverified aliases and markup-like text are review flags. Every surviving
proposal also retains an explicit **unverified premises/context/editorial** flag.
These heuristics cannot prove entailment, detect every repeated fact, establish
factual truth or calibrate difficulty. Model self-review cannot clear them.

`authoring.method: provider-assisted` retains immutable server provenance and
original flags through selection/edit/export. The workshop verifies its managed
attempt/capture; equivalent decimal JSON formatting is accepted, but real
provenance changes fail. The canonical server record, not browser numeric
formatting, is embedded in the export. Historical operator-supplied artifacts
reconstruct unchanged. Private provenance is never indexed; all captured sources,
not an answer-specific shortlist, remain the opponent's searchable scope.

## Offline Verification and Next Step

Baseline rerun: **237 Java and seven Node tests passed**. Final clean verification:
**269 Java tests (32 new) and seven Node tests passed**, zero failures/errors/skips.
Exact
commands and browser identities are in
[the dated evidence](engineering/2026-11-06-proposal-assistance-verification.json).
Fixture/loopback tests cover structured and malformed replies, fabricated titles/
references, multiline Unicode/UTF-16 boundaries, duplicates, aliases/hints,
unsafe text, bounds/budgets, concurrency, cancellation, explicit retries,
unknown outcomes and received-response/restart recovery. Existing game, pack,
activation, player-request and frozen-challenge checks remain in the suite.

The browser rehearsal is **synthetic provider QA over genuine saved source
snapshots**, not live generation or human playtesting. It includes normal-mode
Unconfigured/manual editing; fixture-only generation, lost responses/restart,
safe rendering/editing/selection, zero-eligible immutable drafts, a blocked blank
review, separately recorded assistant source-context review, isolated activation,
a complete game/reload/duplicate/replay check, 390px touch and 320px workshop
layout, and exact isolated rollback. The generated candidate is not in the normal
catalog. No new retrieval benchmark/accuracy measurement is claimed.

**Live verification: not performed. Actual provider calls: zero.** The next local
step is a manual authoring/review rehearsal with durable game receipts. A future,
separately authorized small live pilot remains deferred: secure credential setup, explicit model
and checked token prices, tight approved budgets, one manual batch, then compare
actual request/usage/error behavior and inspect source-grounded output before
further authoring. Human approval/playtesting/calibration remain deferred at
0/60 starter and 0/5 preserved Jovian clues. Public deployment, unrestricted
generation and automatic publication remain separate release gates.
