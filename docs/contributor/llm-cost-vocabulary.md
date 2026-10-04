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
| Funding source | `FundingSource.INSTANCE` | `FundingSource.WORKSPACE` |

They pause **independently**: an exhausted shared-model budget must never stop work a workspace is paying for out of its own pocket.
Thus, there is no combined figure, combined meter, or "total spend" across the two.
A sum of the two would be a number nobody owes.

Every banner names *whose* cap tripped and routes to whoever can lift it.
Where both are paused, the provider cap comes first, because that is the one the reader can act on.

## Rule 3 — In user-facing copy the host's is a *budget*, the workspace's own is a *cap*

"Shared-model budget" and "provider cap" are the words that reach the screen.
This includes the meters' accessible names: "Shared-model budget used" and "Provider cap used by Acme".

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

`—` is the rendering for absent in all three.

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

## Where the words are enforced

| Concern | Owner |
| --- | --- |
| Price wording (rules 1, 4) | `webapp/src/lib/llm-pricing.ts` |
| USD rendering (rules 1, 5) | `webapp/src/lib/money.ts` |
| Which purse, and whether it pauses (rules 2, 4) | `LlmBudgetVerdict`, `FundingSource`, `LlmBudgetService` |
| The in-flight bound behind rule 7's "within a minute" | [ADR 0026](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0026-per-purpose-agent-bindings-and-llm-governance.md) |
