---
id: llm-cost-vocabulary
sidebar_position: 4
title: LLM cost vocabulary
description: The words the AI cost surfaces use for price, cost, spend, budget and cap — and which code owns each one.
---

# LLM cost vocabulary

Money surfaces have a specific risk.
Two screens can use one word for two different numbers, or two words for one number.
Nobody notices because both render correctly.
This page is the vocabulary the LLM cost and pricing surfaces enforce, written down so a citation can resolve to something.

Code cites these rules by number.
**Renumbering breaks those citations — append, do not reorder.** Where a rule has a single owner in code, that owner is named, and the rule belongs there rather than at each call site.

---

## Rule 1 — Price, cost, and spend are three different numbers

| Word | What it is | Where it appears | Formatter |
| --- | --- | --- | --- |
| **price** (or **rate**) | A published per-1M-token rate, as the provider lists it | `per1mInputUsd`, `per1mOutputUsd`, `per1mCacheReadUsd`, `per1mCacheWriteUsd` | `formatRateUsd` |
| **cost** | What one recorded thing cost — a call, an `llm_usage_event`, a job, a turn | `llm_usage_event.cost_usd` (`NUMERIC(18,6)`). No single item's cost is published on its own. The API exposes cost only as a total | `formatCostUsd` |
| **spend** | Cost summed over a window, usually a month, usually a workspace | `instanceTotalCostUsd`, `ownProviderTotalCostUsd` | `formatCostUsd` |
| **upper bound** | The most one call can cost at its frozen rates, from its request and its output limit. Held for a call whose usage is not measured ([Rule 11](#rule-11--an-unmeasured-call-counts-at-its-upper-bound)) | `llm_usage_event.cost_bound_usd`, `instanceUnmeasured.upperBoundUsd`, `ownProviderUnmeasured.upperBoundUsd` | `formatCostUsd`, always after "Up to" |

The wire deliberately has no `totalCostUsd` or `spentUsd`.
Spend is always published split by purse.
Rule 2 forbids merging the two. `spentUsd` exists only as a Java-internal accessor and must not be reintroduced as a field name.

A price is what you would be charged.
Cost and spend are what you *were* charged.
Never use one word for another number — in copy, in a field name, or in a test name. "Spend" is the word for the summed figure in user-facing copy. "cost" belongs to a single recorded item.

Prices are frozen per event: the ledger (`llm_usage_event`) stores the rates that were applied, so a price change never rewrites history.
See [ADR 0026 — one pricing authority](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0026-per-purpose-agent-bindings-and-llm-governance.md).

## Rule 2 — There are two caps, they are different people's money, and they are never summed

A workspace can spend under two independent caps:

| | **Shared-model budget** | **Provider cap** |
| --- | --- | --- |
| Whose money | The **host's** — the instance pays the provider | The **workspace's** — its own provider bills it directly |
| Who sets it | Instance admin | Workspace admin |
| Who can lift it | Instance admin only | The workspace admin themselves |
| Funding source | `UsageFundingSource.INSTANCE` | `UsageFundingSource.WORKSPACE` |

They pause **independently**: an exhausted shared-model budget must never stop work a workspace is paying for out of its own pocket.
Thus, there is no combined figure, combined meter, or "total spend" across the two.
A sum of the two would be a number nobody owes.

Every banner names *whose* cap tripped and routes to whoever can lift it.
Where both are paused, the provider cap comes first, because that is the one the reader can act on.

## Rule 3 — In user-facing copy the host's is a *budget*, the workspace's own is a *cap*

"Shared-model budget" and "provider cap" are the words that reach the screen.
This includes the meters' accessible names: "Shared-model budget used" and "Provider cap used by Acme".
The screen names each purse: **Shared models** for the host's money, and **Own provider** for the workspace's own.

The wire is not consistent with this and does not need to be.
Both caps are *written* through the same field, `monthlyBudgetUsd`.
The request's path carries which purse it governs.
Thus, the field name does not encode the purse a second time.
The caps are *read back* as `instanceMonthlyBudgetUsd` and `ownProviderMonthlyBudgetUsd`.

**The UI words are the contract.
The field names are history.** Do not rename copy to match a field.

## Rule 4 — Never render a pricing or budget enum

`PRICED` / `NO_CHARGE` / `UNPRICED` and `WITHIN` / `EXHAUSTED` / `UNVERIFIABLE` are internal states.
None of those words appears on screen.

For price, `webapp/src/lib/llm-pricing.ts#priceLabel` owns the word choice, and it varies by audience:

- `PRICED` → the number itself, never the word ("$0.075 input · $0.30 output / 1M tokens")
- `NO_CHARGE` → "No metered API cost"
- `UNPRICED` → "No price set" to an instance admin, "Price not set" to a workspace admin

The price radio (`PriceModeEditor`) takes its `NO_CHARGE` and `UNPRICED` option labels from that same function.
Thus, the selected option and its table label cannot drift.
Its `PRICED` option needs separate text: "Price per 1M tokens".
`priceLabel` renders a priced model as its numbers.
These numbers cannot label a radio.

The usage tables show a count of unavailable cost, `unpricedEventCount` on the wire.
A review attempt or a conversation turn with at least one unknown-cost row counts one time.
Synthetic capability probes add calls, tokens and cost, but no runs.

A test with unavailable cost still counts once in `unpricedEventCount`, so the unknown spend is visible.
A usage row of a model for practice scripts counts toward the run of its review.
The table by practice counts reviews instead, because its other count is **Reviews**.
`usage-utils.ts#UNPRICED_RUNS` owns the heading.

The alerts say "N runs have no price".
They name no purse, because the count covers both purses.

Two other surfaces answer a *different* question and correctly use different words.
The instance model table's readiness column says "Price missing".
It ranks one blocker against others: "Connection off", "Model off", and "No workspace access".
It does not name a pricing mode.
If you are adding a third phrasing for the same question `priceLabel` already answers, route it through `priceLabel` instead.

For budget state, the copy says what happens ("paused", "resumes"), not which enum constant produced it. `UNVERIFIABLE` pauses a **capped** purse exactly like `EXHAUSTED` — a cap you cannot verify is not a cap — and is a data-quality note on an uncapped one.

## Rule 5 — The formatter follows the noun, not the widget

`webapp/src/lib/money.ts` owns USD rendering, and the choice is not cosmetic:

- **`formatRateUsd`** — prices and per-unit rates. Up to four decimals: `$0.075 / 1M` is a real price.
  This is the one number an admin checks against their provider's price list. Rendering it with
  the spend formatter would print `$0.08`, and `$0.003` would become `<$0.01`. This has one lossy case.
  The API accepts eight decimals.
  The database stores eight decimals (`NUMERIC(18,8)`).
  Thus, a rate below `$0.0001 / 1M` renders rounded. If such a rate is ever priced, widen the formatter.
  Do not route it through a different formatter.
- **`formatCostUsd`** — anything actually spent. Use `$0` for nothing, not `$0.00`.
  `$0.00` hides the difference between "none" and "almost none".
  Use `<$0.01` for a nonzero amount too small for cents.
  Use plain cents otherwise.
- **`formatCapUsd`** — a cap someone typed, rendered the way they typed it: `$50`, not `$50.00`.
- **`formatAverageUsd`** — an average spend, such as **Avg per use**.
  One column has one number of decimals, which `averageFractionDigits` takes from all its averages, the total included.
  The [worked example](#worked-example) gives the decimal places and display thresholds.

`formatRateUsd`, `formatCostUsd` and `formatCapUsd` render an absent value as `—`.

### Worked example

The example model has these rates per 1M tokens:

- Input: **$2.50**.
- Output: **$10.00**.
- Cache read: **$0.25**.
- Cache write: unpriced.

One completed run reports 12 345 input, 3 210 output, 40 000 cache-read, and 0 cache-write tokens.
Each bucket uses `tokens ÷ 1 000 000 × rate`:

| Bucket      | Tokens | Rate / 1M |                                                                        Amount |
| ----------- | -----: | --------: | ----------------------------------------------------------------------------: |
| Input       | 12 345 |     $2.50 |                                              0.012345 × 2.50 = **$0.0308625** |
| Output      |  3 210 |    $10.00 |                                             0.003210 × 10.00 = **$0.0321000** |
| Cache read  | 40 000 |     $0.25 |                                              0.040000 × 0.25 = **$0.0100000** |
| Cache write |      0 |         — | 0 tokens, so **$0** — an unpriced bucket with no tokens is not a missing rate |
|             |        |   **raw** |                                                                **$0.0729625** |

The ledger column is `NUMERIC(18,6)`.
It rounds the total to six decimals with **banker's rounding**.
The seventh digit is exactly 5, and the sixth (`2`) is already even.
The stored amount is therefore **$0.072962**, not `$0.072963`.
Half-to-even rather than half-up rounding prevents a long sequence of amounts from drifting upward against you.

Two further limits apply:

- **A tiny paid call never stores $0.** One input token at $0.05 / 1M costs $0.00000005.
  Six-decimal rounding would make it zero.
  The ledger stores **$0.000001** instead to distinguish a paid call from a free call.
  The excess charge is less than a millionth of a dollar per call.
- **The UI rounds differently from the ledger.** Published *rates* show up to four decimals (`$0.075 / 1M`) for price-list checks.
  *Spend* appears as cents, with `<$0.01` for a non-zero amount below one cent.
  Each cell of an *average* column, such as **Avg per use**, has the same decimals.
  Thus, the decimal points line up.
  The column shows whole cents when no average in it is below one cent.
  If an average is below one cent, the column shows three decimals, such as `$0.004`.
  If an average is below a tenth of a cent, it shows four, such as `$0.0004`.
  Thus, a small unit cost stays visible.
  An average above zero but below `$0.00005` shows `<$0.0001`.
  No display is the stored number.

## Rule 6 — Client-side money arithmetic is display-only, and may never decide anything

Amounts are exact decimals on the server (`NUMERIC`, `BigDecimal`) and land in JavaScript as binary64.

**Money totals and cap verdicts are computed on the server and shipped as their own fields.
Read them, never re-derive them.** Adding rendered rows to produce a total trades an exact number for an approximate one.
It can only disagree with the figure printed above it.
The same goes for whether a purse is paused: `paused` is authoritative because it mirrors the live gate, and is not derivable from the verdict alone.

The client can do money arithmetic if nothing depends on the result:

- The euro display estimate multiplies by the FX rate.
- The meters compute a percentage.
- The breakdown tables divide a total by a run count.
- The burn-rate projection extrapolates this month's pace in the browser.

All four are rendering.
A meter that read 99.9997% while the gate was shut would be a wart.
A meter that *decided* would be a bug.

FX in particular is never an input to a budget, a price or the ledger.

Note what this means for anyone reading the API: no budget or cap payload carries a `remaining` field.
Meters print "X of Y" and derive the difference for display only.

## Rule 7 — Say what the bound is, not how it feels

Where an effect is not immediate, copy states the bound the system actually keeps rather than hedging.
Saving a cap says "New calls resume **within a minute**" because `ProxyBudgetGate` caches its verdict for 30 seconds.
"Resumes now" would be false.
"About a minute" would give no firm bound.
See [ADR 0027](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0027-dialog-lifetime-and-where-a-write-outcome-lands.md) for where the confirmation itself is allowed to appear.

## Rule 8 — A cap is monthly and it is not scoped to the month you are looking at

The usage page has a month stepper.
The caps do not move with it.
A cap is the workspace's current setting.
Thus, the editors are reachable on the current month only.
A past month shows what the cap *was* being judged against.
You cannot edit that cap from the past month.

---

## Rule 9 — Measurement verification is independent of price

`MeasurementVerification` records the source-selection decision for one whole token vector:

- `VERIFIED`: source selection accepted the vector. It does not certify that every provider call was observed.
- `UNVERIFIABLE`: source selection could not accept a measurement for the whole operation.
  Observed positive buckets and their provenance remain available, but cost is null and pricing state is `UNPRICED`.
  Incomparable runner and proxy totals have this status.
  Their buckets are never fused.
- `UNKNOWN`: no source-selection decision was recorded for historical or imported usage.
  Existing tokens, rates, and costs are retained. New recorder appends must supply one of the other two decisions.

A verified vector can still be `UNPRICED` when a required rate is missing.
The unverifiable-measurement metric counts measurement status, not missing prices.
Repricing requires `VERIFIED`, `UNPRICED`, and positive billable tokens in both enumeration and the atomic update.
A rate, including a free-model declaration, cannot establish measurement verification.

Historical `UNKNOWN` unpriced records stay excluded from repricing and continue to block capped purses.
Historical priced amounts remain in spend totals without gaining a verification claim.
Do not repair verification decisions with manual database edits.
This distinction does not establish complete call coverage.

## Rule 10 — Each model call is one ledger row

The proxy measures every model call, and each call has one `llm_usage_event` row ([agent runs](agent-runs#model-calls)).
The row freezes the model, the funding source and the rates of the call's model kind when the call opens. A call is dated by its start, so a call that ends after midnight on the 1st stays in the month it started in.
The runner's own usage report is not a billing source.

- A served call with reported usage is `VERIFIED`, and its cost follows from the frozen rates.
- A served call without reported usage is `UNVERIFIABLE` and `UNPRICED`, with `unreported_calls = 1`.
  So is a call cut off before an answer, and a call that the end of its claim settled.
  They are never billed as zero.
- A call that its provider answered with an error status costs nothing: it is `VERIFIED` with no tokens.
- A practice script call names its practice (`practice_id`). Its chat calls bill as the review's own calls (`REVIEW_JOB`).

The budget caps, the scan budget and the reports read the rows.
[Rule 11](#rule-11--an-unmeasured-call-counts-at-its-upper-bound) says what each row adds to a total.

A late completion replaces a settlement with the measurement at the initial price mode and frozen rates.
A known price applies at once.
An unknown price needs the repricing pass.
The repricing pass reads every month, oldest first, in batches of `LlmUsageRepricingSweeper.MAX_ROWS_PER_PASS`.

Rows from before each call had a row hold one review attempt, one practice script model kind of an attempt, or one turn attempt each. They keep their `total_calls`, they have no run, and they name no practice.

An erasure detaches every row of each selected job or turn from its source.
The rows of one source take one new source id together and lose their review. The rows keep their tokens, cost and date. A call that returns after the erasure measures its detached row and never names its source again.

`unpricedEventCount` follows the run-count definition in [Rule 4](#rule-4--never-render-a-pricing-or-budget-enum).
It is not a per-purse count and must not be used as one in budget banners.

## Rule 11 — An unmeasured call counts at its upper bound

Every generative model call carries an output limit ([AI providers](/admin/ai-providers#output-limit)).
When a call opens, its row stores its **upper bound** in `cost_bound_usd`: the most that the call can cost at its frozen rates.

- The input is the request's bytes. A token covers at least one byte, so the bytes bound the input tokens.
  Each byte is priced at the highest of the input, cache read and cache write rates.
- The output is the effective output limit of the call: the limit in the forwarded request.
  The proxy clamped that limit or wrote it ([AI providers](/admin/ai-providers#output-limit)).
  An embedding or reranking call writes no output.
- Tokens that a provider adds on its own side, such as a hidden system prompt, are not in the bound.
  Output that a server writes past a limit that it ignores is not in the bound either.
- A `NO_CHARGE` model bounds each call at zero.
- An `UNPRICED` model, a priced model without the input or output rate, and a priced reranking call have no bound.
  A reranking call bills search units, which no token rate bounds.
- A measurement that needs a rate that the frozen price does not have clears the bound.

A row adds its upper bound to every spend total while its call is open or the row has no price.
Otherwise it adds its cost.
`LlmUsageEventRepository.SPEND` owns this expression.
The monthly caps, the scan budget, the scan call guard, the batch budget and the mean cost per review use it.

A call opens only while its bound fits under its purse's cap: the spend total plus the call's own bound must not pass the cap (`ProxyBudgetGate#refusesHold`).
Thus, an open call holds its bound while it is at its provider, and a call whose usage was not measured keeps its bound.
Such a call does not make a purse or a review unverifiable.
Only a row with neither a price nor a bound does ([Rule 4](#rule-4--never-render-a-pricing-or-budget-enum)).

An upper bound is never shown as a cost.
Confirmed spend is the measured cost of the rows that do not count at a bound (`LlmUsageEventRepository.CONFIRMED`).
A report reads it in the same statement as the bounds, never as a spend total less the bounds.
The budget alert compares confirmed spend with the cap, so a bound never raises it.

The reports show the bound apart from the confirmed spend: `instanceUnmeasured` and `ownProviderUnmeasured` give the calls and their summed bound for each purse.
The screen says "Up to $X more from N calls whose usage was not measured".
A review's usage marks a cost that includes a bound with `includesUpperBound`, and the screen says "Up to $X".
The repricing pass never prices an `UNVERIFIABLE` row, so such a row keeps its bound.

Chat-completion calls multiply their output bound by the requested completion count `n`.
The proxy accepts explicit text input and client-run tools.
It refuses images, audio, files and provider-stored input because request bytes cannot bound their token counts.

## Where the words are enforced

| Concern | Owner |
| --- | --- |
| Price wording (rules 1, 4) | `webapp/src/lib/llm-pricing.ts` |
| USD rendering (rules 1, 5) | `webapp/src/lib/money.ts` |
| Which purse, and whether it pauses (rules 2, 4) | `LlmBudgetVerdict`, `UsageFundingSource`, `LlmBudgetService` |
| Measurement acceptance and repair eligibility (rule 9) | `MeasurementVerification`, `LlmUsageRecorder`, `LlmUsageRepricer`, `LlmUsageEventRepository` |
| One row per model call, settlement, late calls (rule 10) | `LlmUsageRecorder`, `ProxyCallLedger`, `AgentRuns` |
| The upper bound and what a row adds to a total (rule 11) | `OutputLimit`, `LlmPriceSnapshot#upperBound`, `LlmUsageEventRepository#SPEND`, `LlmUsageEventRepository#CONFIRMED`, `ProxyBudgetGate#refusesHold` |
| The in-flight bound behind rule 7's "within a minute" | [ADR 0026](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0026-per-purpose-agent-bindings-and-llm-governance.md) |
