---
title: End-to-end testing
description: Running the live practice-review path end to end locally, and the Playwright browser suite.
---

# Live practice-review E2E

This setup runs the complete local path:

- Workspace creation and SCM sync.
- Job execution in a Kubernetes sandbox.
- LLM calls through the application proxy.
- Result persistence.
- Feedback delivery to a PR or MR.

The opt-in `e2e` profile enables these local-only capabilities:

| Affordance | Property |
| --- | --- |
| Passwordless dev sign-in | `hephaestus.auth.dev-login-enabled=true` |
| Plain-http cookies (drops `Secure` + `__Host-`) | `hephaestus.auth.cookie-secure=false` |
| Self-service GitLab/GitHub workspace creation | `hephaestus.workspace.creation-policy=SELF_SERVICE` |
| Persistable Connection credentials (PATs) | `hephaestus.security.encryption-key` (a fixed local dev key) |
| Dev review trigger | `hephaestus.dev.trigger-enabled=true` |
| Agent runtime | `hephaestus.agent.enabled=true`, unless `AGENT_ENABLED=false` |

`vp run dev:server:e2e` starts PostgreSQL before activating the profile.

Startup permits `cookie-secure=false` only with `e2e` active and `prod` absent.
The auth issuer must use HTTP, and the cookie name must not have the `__Host-` prefix.
All other profiles require Secure cookies.

Do not expose this profile outside a trusted development machine: it enables passwordless app-admin
login. The setup script accepts only loopback application and database URLs. The sandboxes reach the
server through its sandbox gateway, so enforce the boundary with the host firewall or an isolated
development network.

## Local SCM simulation

For an isolated simulator, set `hephaestus.e2e.scm-origin` (environment variable
`HEPHAESTUS_E2E_SCM_ORIGIN`) to its exact `http://127.0.0.1:<port>` origin, without a trailing
slash. Startup rejects this setting unless `e2e` is active and `prod` is absent. Workspace
validation and GitLab preflight allow only that origin. Other destinations retain the normal
HTTPS and private-address restrictions.

GitLab workspace creation accepts only
`GITLAB_DEFAULT_SERVER_URL`, so set it to the same origin, and the creating account needs an
identity link on that origin. Preflight clients do not follow redirects and cannot send requests to a
different origin.

A Git fetch to that origin runs through JGit in the application's own process.
Thus, it reaches the simulator exactly as the provider API calls do.
The simulator must serve Git smart HTTP on the same loopback origin. This does not enable a GitHub simulator or supply
missing provider APIs or historical replay.

## Setup

```bash
# Terminal 1
vp run dev:server:e2e

# Terminal 2
vp run dev:webapp

# Terminal 3: read secrets without placing them in shell history.
read -rsp "SCM PAT: " E2E_GITLAB_PAT && echo && export E2E_GITLAB_PAT
read -rsp "LLM key: " E2E_LLM_KEY && echo && export E2E_LLM_KEY
export E2E_LLM_PRICING_MODE=PRICED
export E2E_LLM_INPUT_USD="$YOUR_CONTRACT_INPUT_RATE_PER_1M"
export E2E_LLM_OUTPUT_USD="$YOUR_CONTRACT_OUTPUT_RATE_PER_1M"
vp run dev:e2e:setup \
  --account-login group/subgroup \
  --repo group/subgroup/project \
  --llm-base-url https://llm.example/v1 \
  --model example-model
```

The script is idempotent. It creates or validates the workspace and connection, configures the model,
disables the review cooldown, and leaves three focused practices active. It requires an explicit cost
declaration: use `PRICED` with contract rates, or `NO_CHARGE` with `E2E_LLM_PRICE_NOTE`. Set
`E2E_PR_ID` to select a specific internal artifact ID. Otherwise, the script selects the newest MR or PR in the monitored repository. For GitLab, use the narrowest suitable group or subgroup because initial sync
covers every project below that path.

Other useful options are `--provider`, `--server-url`, and `--app-url`. The model connection uses
the Responses API. Use `E2E_LLM_PROTOCOL=openai-completions` for an endpoint that serves Chat
Completions alone, or `E2E_LLM_AUTH_MODE=API_KEY` when required by the provider.
The script reuses existing resources only when their immutable SCM and model-routing fields match.

## Running the review

The setup output identifies the workspace and, when one has synced, the selected artifact. Use those
IDs with the dev trigger:

```bash
JWT="$(curl -fsS -i -X POST http://localhost:8080/auth/dev-login \
  -H 'content-type: application/json' \
  -d '{"username":"e2e","admin":true}' \
  | sed -nE 's/.*(__Host-)?HEPHAESTUS_AT=([^;]+).*/\2/p' | tr -d '\r')"
test -n "$JWT"
curl -fsS -X POST -H "Authorization: Bearer $JWT" \
  "http://localhost:8080/api/dev/trigger-review?prId=<id>&workspaceId=<id>"
```

To test webhook ingestion too, set the canonical API issuer origin to the trusted tunnel address.
Configure webhook and routing secrets of at least 32 characters, then expose the receiver at that origin.
The receiver paths do not include the API prefix. GitLab group-hook registration
requires the appropriate group role and license.

The agent runs in a Kubernetes sandbox (`ghcr.io/hephaestus-build/agent-pi`) and calls the LLM through
the in-app proxy, so provider keys never enter the sandbox.

A practice review runs in a review sandbox of the k3d cluster.
A host-run server, as `vp run dev` starts it, creates its sandboxes there.
They reach it through its sandbox gateway.

Hephaestus posts feedback back to the MR.
The workspace's **Practices → Practice reviews** view shows the results behind it.

Live runner JUnit tests call the upstream provider directly. They do not cover application proxying,
budget admission, durable usage accounting, sandbox execution, or SCM delivery.

## Browser tests (Playwright)

`webapp/e2e/` holds a `@playwright/test` harness (`vp run --filter webapp test:e2e`) that drives the
SPA over plain http through the dev-login — see the `README.md` in that directory. It uses the same
`cookie-secure=false` + `XSRF-TOKEN` wiring this page relies on.

## Conversation turns

A turn runs in a conversation sandbox, so it needs the sandbox runtime.
The packaged browser suite has none (`hephaestus.agent.enabled=false`): a question stays queued there.
Three tests cover a turn:

| Test | Where it runs | What it proves |
| --- | --- | --- |
| `kubernetes-cluster.ts turn-roll`, the k3d turn drill | The drills job of the merge queue and of the nightly run | A seeded member's question in the app runs in a conversation sandbox through a server roll. The turn ends `DONE` on its first attempt, and its answer holds the output of each `bash` call. Then a signed Slack message from the member's linked Slack identity reaches a turn in the same sandbox. The turn ends `DONE`, the Slack fake receives the answer as `chat.postMessage` in the member's DM, and the answer holds the reply's `ts`. |
| `conversation.live.spec.ts` | By hand, with a real model | The page shows a real model's answer, and a stop request ends a turn. |
| `conversation.spec.ts` | The packaged browser suite | The page around a turn: the queue, a thread, a deleted question, a rating. |

The drill reads the database. No CI test shows an answered turn in the page.

The drill uses two stand-ins in the pod network, and it runs the product's own paths:

- The capacity benchmark's fake model provider (`scripts/capacity-provider.ts`) answers the model calls.
- `scripts/slack-fake.ts` is a Slack Web API. The server installs the app through its own OAuth callback against it, and it takes the posted messages.
  The server reaches it through `hephaestus.integration.slack.api-base`.
- The Events API request is signed with a signing secret that the drill generates for each run in the release's Secret.
  The fake issues a new bot token each time it starts.

## Caveats

- `Plan & scope issues` is evaluated in PR context. Use a PR or MR.
- **Delivery needs a PR row with `author_id`** — synced MRs have it. A hand-seeded PR may not.
- The sandbox runtime needs a Kubernetes cluster with the agent-sandbox controller. `vp run dev` creates one with k3d.
