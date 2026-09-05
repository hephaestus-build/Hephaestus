---
title: End-to-end testing
description: Running the live practice-review path end to end locally, and the Playwright browser suite.
---

# Live practice-review E2E

This setup runs the complete local path: workspace creation, SCM sync, job execution in Docker, LLM
calls through the application proxy, observation persistence, and feedback delivery to a PR or MR.

The opt-in `e2e` profile enables these local-only capabilities:

| Affordance | Property |
| --- | --- |
| Passwordless dev sign-in | `hephaestus.auth.dev-login-enabled=true` |
| Plain-http cookies (drops `Secure` + `__Host-`) | `hephaestus.auth.cookie-secure=false` |
| Self-service GitLab/GitHub workspace creation | `hephaestus.workspace.creation-policy=SELF_SERVICE` + `hephaestus.features.flags.gitlab-workspace-creation=true` |
| Persistable Connection credentials (PATs) | `hephaestus.security.encryption-key` (a fixed local dev key) |
| Dev review trigger | `hephaestus.dev.trigger-enabled=true` |
| PostgreSQL job executor | `hephaestus.agent.enabled=true` |

`vp run dev:server:e2e` starts Postgres and NATS before activating the profile.

Do not expose this profile outside a trusted development machine: it enables passwordless app-admin
login. The setup script accepts only loopback application and database URLs. The application server
still needs to be reachable from Docker through `host.docker.internal`, so enforce the boundary with
the host firewall or an isolated development network.

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
`E2E_PR_ID` to select a specific internal artifact ID; otherwise the newest MR or PR in the monitored
repository is selected. For GitLab, use the narrowest suitable group or subgroup because initial sync
covers every project below that path.

Other useful options are `--provider`, `--server-url`, and `--app-url`. Use
`E2E_LLM_PROTOCOL=openai-responses` or `E2E_LLM_AUTH_MODE=API_KEY` when required by the provider.
Existing resources are reused only when their immutable SCM and model-routing fields match.

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

To test webhook ingestion too, configure `hephaestus.webhook.external-url` and a secret of at least 32
characters, then expose the webhook receiver through a trusted tunnel. GitLab group-hook registration
requires the appropriate group role and license.

The agent runs in a Docker sandbox (`ghcr.io/hephaestus-build/agent-pi`) and calls the LLM through
the in-app proxy, so provider keys never enter the sandbox. Host-run E2E uses a non-internal Docker
network (`allowInternet=true`) so the sandbox can reach that proxy through `host.docker.internal`.
Feedback is posted back to the MR, and the observations behind it are shown under the workspace's **Practices → Practice reviews** view.

Live runner JUnit tests call the upstream provider directly. They do not cover application proxying,
budget admission, durable usage accounting, sandbox execution, or SCM delivery.

## Browser tests (Playwright)

`webapp/e2e/` holds a `@playwright/test` harness (`vp run --filter webapp test:e2e`) that drives the
SPA over plain http via the dev-login — see the `README.md` in that directory. It uses the same
`cookie-secure=false` + `XSRF-TOKEN` wiring this page relies on.

## Caveats

- `Plan & scope issues` is evaluated in PR context; use a PR or MR.
- **Delivery needs a PR row with `author_id`** — synced MRs have it; a hand-seeded PR may not.
- The sandbox runtime needs `/var/run/docker.sock`.
