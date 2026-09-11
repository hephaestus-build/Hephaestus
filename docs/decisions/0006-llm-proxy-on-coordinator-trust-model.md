# ADR 0006: LLM proxy stays on the coordinator (BYO trust model)

**Status:** Accepted (amended 2026-07-20, 2026-07-28 and 2026-09-03 — see the updates below); placement superseded by [ADR 0041](0041-compose-1x-kubernetes-2.md)
**Date:** 2026-05-20
**Authors:** Server foundations epic (#1097)

## Context

Sandboxed agent containers running inside Hephaestus call out to LLM providers
(Anthropic, OpenAI). The credentials needed (API keys, OAuth tokens) MUST NOT leak
into the sandboxed code — that would let any user-submitted code exfiltrate the
keys.

The existing `LlmProxyController` is the implementation: agent containers connect
to a host-local proxy, the proxy injects the real API key, the upstream call goes
out. The question is which JVM hosts the proxy when the worker is split out:

- **Coordinator-hosted (server JVM)** — workers send LLM traffic over HTTPS back to
  the server, which holds the credentials. Matches the modern BYO-runner pattern
  (Buildkite Vault OIDC, Anthropic Claude Code git proxy, Temporal payload codec,
  Fly macaroons).
- **Worker-hosted (worker JVM)** — workers proxy locally; LLM credentials live on
  the worker.

An earlier recommendation said "move to worker" — that recommendation was wrong
for the BYO model and we'd have shipped a credential isolation breach.

## Decision drivers

- Future BYO-runner pattern: course operators may run their own worker pods. We
  cannot trust those pods with our LLM API key.
- Industry research is decisive: every modern execution platform that handles
  untrusted code keeps secrets on the coordinator, not the runner.
- Today's single-JVM topology can use either model — we want option value for the
  future, with the safe default today.

## Considered options

1. **Capability flag (`hephaestus.sandbox.llm-proxy.enabled`); default true on
   server; off-by-default on worker** — preserves both modes for future
   deployment-time choice.
2. **Hardcode on coordinator** — simpler, but loses the future managed-mode
   worker hosting option (where we control the worker and can localhost the proxy
   for lower latency).
3. **Hardcode on worker** — would commit to the wrong default for the BYO case.

## Decision

Option 1. Both `LlmProxyController` and `LlmProxySecurityConfig` carry
`@ConditionalOnProperty(hephaestus.sandbox.llm-proxy.enabled, matchIfMissing=true)`.
Default true everywhere, including worker — meaning today's single-JVM deploys keep
working unchanged.

When the worker is split:

- **BYO trust model (default)**: server pod keeps `llm-proxy.enabled=true`; worker
  pod sets it to false; agent containers route LLM calls back to the server URL.
  Server holds credentials.
- **Managed-mode (future opt-in)**: server pod sets `llm-proxy.enabled=false`;
  worker pod keeps it true; agent containers hit localhost worker proxy. Worker
  holds credentials (acceptable when we operate both server and worker).

## Consequences

- Credential-isolation breach risk closed: no unconditional `@RestController`
  serving `/internal/llm/**`.
- Future BYO-runner epic (managed + BYO modes) gets the right default for free.
- An out-of-band ArchUnit rule (next-epic) can enforce that `LlmProxyController`
  lives in the worker package and never gets cross-referenced from a non-worker
  module.
- LLM call latency in the BYO case includes a server round-trip — accepted
  trade-off for credential safety. Worker → server roundtrip is HTTPS over the
  cluster network; sub-millisecond in typical deployments.

## Revisit trigger

A specific scaling need that requires worker-local LLM proxy (sub-millisecond
hot-path latency on the LLM call) AND the operator owns both worker and server (no
BYO trust concern); or the BYO-runner auth model introduces per-runner key
provisioning that makes worker-local credentials safe to ship.

## Update — 2026-07-20 (issue #1368)

**The capability flag chosen as option 1 no longer exists.** `hephaestus.sandbox.llm-proxy.enabled`
was deleted; `LlmProxyController` and `LlmProxySecurityConfig` now carry
`@ConditionalOnProperty(name = RuntimeRole.WORKER_PROPERTY, matchIfMissing = true)`. The proxy runs
wherever the worker/sandbox capability runs, and there is no switch that moves it independently of
that capability. ADR 0005's 2026-07-20 / 07-21 / 07-22 updates track the gate through the same
period; `MIGRATION.md` § "LLM provider configuration moved from env vars to the admin console" is the
operator-facing removal notice.

**What this supersedes in the Decision section.** The two named deployment modes are no longer
selectable. The sandbox is handed the proxy URL of the JVM that launched it — by default the address
of the launching host on the job network (`DockerSandboxAdapter`, `DockerInteractiveSandboxAdapter`),
overridable per job via `NetworkPolicy.llmProxyUrl`. A dedicated worker pod therefore hosts its own
proxy and resolves upstream keys from the catalog itself; the application-server replica does the
same for interactive mentor sandboxes.

**What this does *not* change, and what it costs.** The invariant the ADR was written to protect —
*the credential never enters the sandbox* — is now structural rather than configurable, which is
stronger. What was given up is the option value the Decision drivers bought: a **BYO worker pod
operated by a course, under this shape, holds catalog credentials**, because it hosts the proxy and
reads the encrypted key. The original driver ("we cannot trust those pods with our LLM API key") is
therefore unmet today, not refuted. That is a live constraint on the BYO-runner epic, not a settled
question — see the amended revisit trigger below.

## Update — 2026-07-28 (issue #1400)

This update extends the ADR's scope. The original decision answers *which JVM holds the credential*.
It says nothing about *where the credential comes from* — at the time, an env var. Provider
configuration has since moved into a governed server-side catalog, and the proxy is that catalog's
enforcement point, so the two decisions can no longer be read apart.

### Why a catalog, and why "provider" was the wrong unit

"Provider" was overloaded to mean a vendor, an HTTP API shape, an authentication scheme, and a
pricing source. That does not fit OpenAI-compatible gateways: the same Chat Completions or Responses
API can be hosted by OpenAI, Azure OpenAI v1, or a self-hosted service with different endpoint and
authentication details. Compatibility is an API property, not a vendor taxonomy — vLLM's
[OpenAI-compatible server](https://github.com/vllm-project/vllm/blob/main/docs/serving/online_serving/openai_compatible_server.md)
implements both surfaces while documenting endpoint-specific differences. The catalog therefore
stores the exact wire API and an opaque upstream model ID instead of a growing provider enum.

Provider responses report token usage, not a trustworthy dollar cost. OpenAI's
[Responses API](https://developers.openai.com/api/reference/resources/responses/methods/create)
reports input, cached-input, output and reasoning-token detail; its
[Chat Completions API](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
reports the corresponding prompt/completion usage. Reasoning tokens are included in output tokens, so
pricing them again would double-charge. Published prices are separate from the response contract and
may differ for another compatible host.

### One catalog, two exact wire APIs

The catalog supports only OpenAI Chat Completions (`POST /chat/completions`) and OpenAI Responses
(`POST /responses`). Vendor names are form presets, not persisted runtime types. Azure OpenAI is
represented only as a create-time Azure v1 preset: its base URL and API-key authentication persist as
the same generic OpenAI-compatible connection, following Microsoft's
[v1 API guidance](https://learn.microsoft.com/azure/ai-foundry/openai/api-version-lifecycle).

An instance or workspace connection owns an immutable base URL, wire API, authentication mode and
generated slug. Authentication is limited to `BEARER` and `API_KEY`; arbitrary header names and value
prefixes are not configuration. Rotating or clearing the encrypted key, renaming, enabling and
disabling are lifecycle operations; changing routing identity means creating a new connection and
rebinding.

A model owns an immutable upstream model identifier plus the capability envelope the agent runtime
needs. Models and connections start inactive. A model activates only when its connection is active
and its price is explicitly `PRICED` or `NO_CHARGE`. An instance model is either public or granted to
named workspaces; workspace-owned models stay tenant-scoped.

### The proxy as enforcement point

The sandbox receives only a short-lived, job/turn-scoped bearer token. The proxy resolves the
encrypted upstream key from the live catalog on every call, so rotation and revocation take effect
without a secret in a job snapshot, container environment, ledger or log.

The proxy is not a general-purpose forwarder. It accepts only the exact POST path selected by the
token's catalog binding, rejects query strings and unsupported hosted tools, replaces the request
body's model with the authorized immutable upstream model, strips inbound credentials, and derives
the one permitted upstream authentication header from the stored authentication mode. It also removes
`service_tier`, which OpenAI documents as selecting processing with different pricing and
performance — that would invalidate the catalog's one declared price tuple. A sandbox cannot use a
valid token to reach a different model, price tier or endpoint.

All provider base URLs pass the shared egress policy at configuration time and at connect time: public
HTTPS (except the explicit local-development loopback switch), no userinfo/query/fragment components,
no non-public address ranges, and an optional instance-admin hostname allowlist — the allowlist-first
posture of the
[OWASP SSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html).

### Admission and accounting

Jobs revalidate the live model, connection, tenant/grant, protocol and price while claiming the queue
row, before a sandbox starts; mentor turns do the same before creating a thread or message. The
complete applicable price tuple is frozen at that point, and completion never looks up a new price or
trusts a runner-reported model name.

The usage ledger is written in the same database transaction as the terminal job attempt or mentor
message. Its deterministic identity includes source type, source id and attempt number, so a real job
retry records separate spend without a database retry billing twice. Unexpected accounting failure
rolls back the terminal write rather than silently losing cost; budget alerts run after commit.
Prices are operator-declared from the applicable contract or hosting cost — Hephaestus does not
scrape a vendor pricing page and does not treat an absent cost as zero. `NO_CHARGE` is an explicit
audited declaration.

### Consequences of this update

- Upstream secrets stay outside sandboxes and outside immutable job snapshots.
- Instance admins get one shared-model and price-governance flow; workspace admins see only models
  they can use and, when enabled, their own connections.
- Disabling a connection or model, or removing a grant, blocks the next admission and the next proxy
  call.
- Repricing cannot change the recorded cost of work already admitted.
- The schema carries no Azure API versions, vendor adapters, arbitrary auth headers, model-level
  protocol overrides, or unused modality/cache-control knobs.
- A model with no known price cannot be activated by accident; historical unverifiable usage stays
  visible instead of being reported as free.
- Any JVM that hosts the proxy now needs database access and the row-encryption key. This is what
  makes the BYO-worker gap named in the 2026-07-20 update concrete.

### Considered and rejected for this update

- **Keys in worker or sandbox configuration** — violates the sandbox trust boundary this ADR exists
  to hold. (This is the original option 3, re-rejected on the same grounds.)
- **A catch-all provider proxy** — turns a scoped LLM credential into a general upstream capability.
- **Completion-time price lookup** — makes historical cost change when an administrator reprices.
- **Provider-supplied dollar cost** — not part of the OpenAI usage response, and not portable across
  compatible endpoints.
- **Azure-specific protocol and `api-version` columns** — obsolete for the Azure v1 surface, and a
  second runtime path with no distinct agent requirement behind it.
- **An accounting outbox or hard reservation system** — unnecessary for same-PostgreSQL terminal
  transactions, and outside the eventually-consistent budget semantics the issue asked for.
- **Delegating accounting to an external gateway** — gateways such as LiteLLM expose
  [key/user budgets](https://docs.litellm.ai/docs/proxy/users), but that adds another stateful control
  plane and cannot atomically join a Hephaestus job/mentor outcome to its workspace and retry attempt.
  The application-owned ledger stays authoritative even when the configured endpoint happens to be a
  gateway.

### Revisit trigger (replaces the original)

The original trigger — a latency need plus operator ownership of both ends — is spent: the proxy is
already sandbox-local wherever the sandbox runs. The live trigger is the other direction. **A worker
pod Hephaestus does not operate** (the BYO-runner epic, or a course running its own runner) forces a
decision this ADR has not made: either the proxy moves back to the coordinator for those pods and
they lose local Docker-network addressing, or BYO runners get per-runner credentials that are safe to
ship. Do not start that epic assuming the 2026-05-20 default still protects it.

## Update — 2026-09-03 (issue #1719)

[ADR 0041](0041-compose-1x-kubernetes-2.md) supersedes the **Decision** above, and with it the
2026-07-28 update's Revisit trigger. Considered option 3, “hardcode on worker”, rejected here on
2026-05-20, is now the chosen path: the proxy is capability 1 of four on the owning worker's Sandbox
Gateway. The rejection was sound for its
premise — a worker Hephaestus does not operate — and ADR 0041 removes that premise by withdrawing the
BYO remote-worker purpose. “BYO” is now a governed provider-key lookup behind that proxy. The
credential-isolation driver in the Context above is unchanged and still binding: the sandbox receives
no provider credential and can reach only the gateway.
