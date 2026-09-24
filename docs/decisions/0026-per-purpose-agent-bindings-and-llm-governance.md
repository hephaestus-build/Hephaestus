# ADR 0026: Per-purpose agent bindings and governed OpenAI-compatible LLM catalog

**Status:** Accepted (amended 2026-07-26 — named-agent-config model deleted)
**Date:** 2026-07-24
**Authors:** Felix T.J. Dietrich
**Builds on:** [ADR 0006](0006-llm-proxy-on-coordinator-trust-model.md) (in-app LLM proxy as the sole credential path), [ADR 0025](0025-agent-job-queue-on-postgresql.md) (PostgreSQL agent job queue)

> **Amendment (2026-07-26):** the transitional `agent_config` mirror described below is gone. The
> table, the `practice_config_id` / `mentor_config_id` scalar pointers on `Workspace`, and the
> write-through `sync` are deleted; `WorkspaceAgentBinding` is the sole `ModelBindingSource` and the
> practice-review settings page reads bindings directly. Text below that describes the mirror as
> present is retained as the record of the decision, not as a description of the current code.

## Context

Two problems sat on top of the LLM configuration surface (#1368):

1. **The wrong abstraction for "what model runs what."** `agent_config` was a *named execution
   profile* (model + timeout + concurrency + internet). The thing that actually decided which model
   ran practice reviews versus the mentor was two scalar FK columns on `Workspace`
   (`practice_config_id`, `mentor_config_id`). So the object an admin created was not the object that
   bound a purpose, and the flow was scattered across three pages under several names. Worse,
   `AgentJobService` fanned out to *every* enabled config when a workspace had no explicit binding —
   submitting one detection job per config for the same event, at N× the cost and N× the feedback.

2. **Credentials, base URL, and price lived on the wrong owner.** They were per-workspace
   `agent_config` fields. Making `base_url` workspace-editable turns the in-app proxy (which attaches
   the server-held key, per ADR 0006) into a workspace-admin-controlled SSRF + credential-exfil
   primitive; making price workspace-editable lets a capped workspace set the numerator of its own
   cap. Security forces these up to instance ownership.

Alongside this, the monthly budget cap (#1368) had correctness gaps: over-cap queued jobs were
cancelled although the UI promised they would resume; the proxy was ungated in flight; a crashed job
recorded zero cost; and a blind `WARN`/`BLOCK` policy knob let unpriced spend evade a cap.

## Decision drivers

- The object an admin creates should be the object that decides behaviour. A configuration that
  needs a second, separate pointer to mean anything is a shape that invites the fan-out bug.
- Ownership must follow blast radius, not convenience: whoever can edit a base URL or a price can
  redirect a server-held credential or move the numerator of their own cap.
- A cap must be enforceable while a run is in flight, not only between runs — the ledger a gate
  reads gains nothing until the run ends.
- Two parties' money must never be conflated, in either direction: an exhausted host budget must not
  stop work a workspace pays for, and a workspace's own cap must not draw on the host's.
- Prefer a structural invariant (a table boundary, a DB CHECK) to a WHERE-clause promise, because the
  cross-tenant leak class recurs whenever isolation depends on remembering a predicate.

## Considered options

1. **Keep `agent_config`, fix the fan-out** — make the pointer mandatory and default it on write.
   Smallest change, but leaves the credential/base-URL/price ownership problem untouched and keeps
   two objects (profile + pointer) where one would do.
2. **One catalog table with a nullable `workspace_id`** — instance rows have NULL, workspace rows
   carry a tenant. Fewer tables, but every read of it becomes a query that leaks instance keys the
   moment a predicate is forgotten, and the credential blast radius stops being visible in the
   schema.
3. **Two-scope catalog plus a per-purpose binding row** — instance and workspace catalogs as separate
   tables, and one `workspace_agent_binding` row per (workspace, purpose) that names exactly one
   model and its execution limits.
4. **Per-purpose binding, but enforce the cap only at submit/claim** — simplest gate, no proxy
   involvement. Rejected: it bounds when a run *starts*, not what a running job spends, so one
   admitted run can spend arbitrarily far past the cap.

## Decision

Option 3, with the in-flight proxy gate from the rejection of option 4.

**A two-scope catalog, owned where security requires.** Instance administrators own an
`app_admin`-scoped catalog (`llm_connection` → `llm_model`, with pricing and per-workspace sharing
grants) registered GLOBAL in the tenancy layer; workspaces may, when the instance allows, own a
tenant-scoped BYO catalog (`workspace_llm_connection` → `workspace_llm_model`). The two catalogs stay
separate tables — a merged table with a nullable `workspace_id` would reopen the cross-tenant leak
class and blur the credential blast radius. Model availability (enabled + connection enabled +
supported protocol + visibility/grant) is decided in exactly one place, `LlmModelResolver`.

**Per-purpose bindings replace named configs.** A new `workspace_agent_binding` row binds exactly one
purpose (`PRACTICE_REVIEW` | `MENTOR`) of a workspace to exactly one catalog model plus execution
limits — `UNIQUE(workspace_id, purpose)`, a DB CHECK that exactly one of the instance/workspace model
is set, and a tenancy-safe composite FK to workspace models. No row means the purpose is off; there
is no implicit fan-out. The object the admin configures *is* the purpose→model binding. Detection's
three job types (pull-request, issue, conversation review) share one binding; a future job type that
needs its own model is a new purpose value, not a schema change.

**One resolve/admit seam.** A `ModelBindingSource` interface (instance/workspace model + workspace +
enabled + limits) is implemented by the binding, so the
resolver and admission service work through one shape without branching. Admission row-locks the binding by
`(workspace, purpose)` before freezing its price, exactly as it locked a config by id.

**One pricing authority.** Cost is derived from the admission-frozen `LlmPriceSnapshot` for both
detection and mentor; the `llm_usage_event` ledger freezes the applied per-1M rates per event as the
authoritative price history. The legacy per-1k `model_pricing` table is retired.

**A budget cap that is honest.** The monthly cap holds queued detection jobs (re-eligible via
`available_at`) instead of cancelling them on the spot; the hold is bounded — `AgentJobExecutor`'s
`BUDGET_HOLD_MAX_JOB_AGE` cancels a job that is still over cap seven days after it was queued, because a
month-old review is noise and an unbounded hold loops forever. The proxy refuses new calls once a
workspace is over cap,
a crashed job bills the calls it made from proxy-accumulated counters, and enforcement is structural:
a capped workspace with an unverifiable month is paused (a cap you cannot verify is not a cap) while
an uncapped workspace is never paused — the `WARN`/`BLOCK` knob is removed.

### How tight the cap is: the in-flight bound

The submit and claim gates (`AgentJobService.submit`, `AgentJobExecutor`) only decide whether a run may
*start*. Once it is running it can make many upstream calls, and the ledger those gates read gains
nothing until the run ends. So the gate that bounds a run in progress is `ProxyBudgetGate`, on the
proxy's forward path — and what makes it a *bound* rather than a delay is that it judges recorded
spend **plus the calling execution's own consumed-but-unrecorded spend**
(`ProxyRouting.BilledAttempt#spentUsd`, priced with the rates frozen onto the attempt at admission).
An execution's spend-so-far reaches the gate from wherever it accrues — an agent job's own row, a
mentor turn's `MentorTurnMeter` — and both buffered and streamed calls feed it (`ProxyStreamUsageTap`
reads the usage frame off an SSE stream as it passes).

For every agent-job attempt and every mentor turn:

> An execution is refused as soon as its OWN completed calls have consumed the headroom the ledger
> last showed. So it can overshoot the cap by at most the calls it had already dispatched when that
> last forward was admitted — one call for a sequential runner or Pi's agent loop — never by the whole
> run.

The in-flight term is what makes that hold. Keyed on the ledger alone, every check during a run would
see zero of that run's spend, and one admitted job or turn could make unlimited calls against an
exhausted cap. `ProxyBudgetGateTest` pins the claim.

**The two purses are judged apart.** The instance backstop and a workspace's BYO self-cap
(`FundingSource.INSTANCE` / `WORKSPACE`) are never summed, so an exhausted host budget cannot 429 calls
the workspace pays for through its own provider — the two pause independently. An attempt whose funding
source is unknown has its in-flight spend charged to both, matching how an unattributable call is
judged against both caps.

**The verdict is cached; the attempt's own spend is not.** The month-window `SUM` is cached per
workspace for 30s so the proxy does not run it on every forward; the execution's own spend is read
fresh on every request at no cost, because authenticating the token has already loaded the row (a job)
or the meter (a mentor turn). Staleness therefore only affects spend by *other* executions and cap
changes: a workspace that crosses the cap starts being blocked within one TTL, and one whose budget is
raised unblocks within one TTL. Shrinking the TTL would not tighten the bound above — it is the fresh
term that produces it — and would put a month-window `SUM` back on the hot path, so it stays fixed. The
gate never kills a call already streaming; it acts only pre-forward.

**What the bound does not cover.** Every call site that states the bound inherits this list:

- **Concurrency.** Each execution is bounded on its own, so N running concurrently for one workspace
  can together reach N times the cap before any of them stops. For jobs N is the workspace's
  `maxConcurrentJobs` — an operator-set number, not an open end; for mentor turns it is the number of
  developers chatting at once.
- **Calls the provider reports no usage for.** A streamed call whose provider rejects
  `stream_options.include_usage` (retried without it, counted as
  `llm.proxy.stream.usage.unsupported`), or any response with no usage block, contributes nothing to
  the in-flight term. That execution is bounded only by the ledger term.
- **A crashed worker's mentor turn, until the reaper runs.** Its spend is on the `chat_message` row but
  not yet in the ledger, so the gate lets the workspace keep spending against headroom that is already
  gone. `MentorInFlightReaper` bills the calls the proxy recorded once its window elapses (defaulting to
  the maximum configurable turn timeout plus ten minutes, which also floors explicit overrides). That is reaper latency, not a lost charge.

## Consequences

- The runtime resolves a model only from a binding. `agent_config` and the scalar pointers were
  demoted to a transitional write-through mirror and have **since been deleted** (see the amendment
  note at the top of this ADR); `WorkspaceAgentBinding` is now the sole `ModelBindingSource`.
- **Operators:** a workspace that relied on the implicit fan-out must explicitly assign a detection
  model; the instance `WARN`/`BLOCK` "usage without a known price" setting is gone. Nothing to
  migrate — the setting was never persisted, so no column is created or dropped for it; enforcement
  is structural instead.
- Credential isolation is structural, not a WHERE-clause promise: a workspace-scoped query can never
  select an instance key, and the proxy resolves the URL and key together from the live row so a
  repointed host can never be paired with a rotated key.
- The cost of the two-table split is duplicated shape: connection/model/price exist twice, and every
  new field on one has to be considered for the other. `ModelBindingSource` and `LlmModelResolver`
  absorb that at the read seam, but the write seams stay doubled.
- Detection's three job types share one binding, so a deployment that wants a cheaper model for issue
  review than for pull-request review cannot express it until a new purpose value is added.

## Revisit trigger

A third funding source appears (for example a per-team purse, or a workspace reselling capacity), so
that "the two purses" stops being two and the never-summed rule needs restating as an N-purse rule;
or a purpose needs more than one model (fallback chains, per-job-type routing), at which point
`UNIQUE(workspace_id, purpose)` is the constraint that has to give; or provider contracts beyond the
OpenAI Chat Completions and Responses shapes become load-bearing, which would move protocol out of
the connection row and into its own negotiation.
