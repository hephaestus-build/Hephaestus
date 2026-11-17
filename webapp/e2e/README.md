# Webapp E2E (Playwright)

These tests drive the SPA against the real backend using the local dev login. See
[the contributor guide](../../docs/contributor/e2e-testing.mdx) for the live practice-review setup.

## Run locally

Start the backend with the profiles used by the packaged CI suite:

```bash
vp run dev:compose:e2e
node scripts/run-gradlew.ts :application:bootRun -Pprofiles=e2e,playwright
```

Create the test account and seed its workspace:

```bash
curl -fsS -X POST http://localhost:8080/api/auth/dev-login \
  -H 'content-type: application/json' \
  -d '{"username":"e2e","admin":true}' >/dev/null
docker compose -f server/compose.yaml exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U root -d hephaestus < webapp/e2e/seed.sql
```

Run the suite:

```bash
vp run --filter webapp test:e2e
```

The suite runs against the packaged server, which serves the web app and the API on one origin, so it tests the artifact that ships.
Build the web app (`vp run build:webapp`) before you package or start the server, then start it with the `e2e,playwright` profiles.
The suite reads the server's origin from `E2E_BASE_URL` (default `http://localhost:8080`).

Provider-backed integration checks are opt-in because they require configured live workspaces:

```bash
E2E_LIVE_USERNAME=e2e E2E_GITHUB_WORKSPACE=github-workspace \
  E2E_GITLAB_WORKSPACE=gitlab-workspace LIVE_INTEGRATION_E2E=true \
  vp run --filter webapp test:e2e sync-observability.live.spec.ts
```

Set `E2E_MUTATE_LIVE_INTEGRATIONS=true` as well to run the provider-sync mutation test.

`conversation.spec.ts` drives the Hephaestus page with no model: the packaged-server suite has no sandbox runtime
(`hephaestus.agent.enabled=false`), so a question stays queued. The CI proof that a seeded member's question, in the app
or in Slack, gets its answer is the k3d turn drill, not this suite
([End-to-end testing](../../docs/contributor/e2e-testing.mdx#conversation-turns)).
`conversation.live.spec.ts` checks that Hephaestus answers from a real model. It needs a server with the agent runtime on
(the `e2e` profile without `playwright`) and its sandboxes in a k3d cluster, as `vp run dev` starts them. It is skipped unless
`E2E_LLM_BASE_URL`, `E2E_LLM_KEY` and `E2E_MODEL` are set. It reads and provisions the model with the same
code as `scripts/e2e-setup.ts` (`scripts/lib/e2e-llm.ts`), so `E2E_LLM_PROTOCOL` (default
`openai-responses`) and `E2E_LLM_AUTH_MODE` (default `BEARER`) apply too:

```bash
E2E_LLM_BASE_URL=https://llm.example/v1 E2E_LLM_KEY=... E2E_MODEL=... \
  vp run --filter webapp test:e2e conversation.live.spec.ts
```

The live spec keeps one instance connection, `e2e-live-llm`. Each run sets its key again, so a rotated key
takes effect. If its base URL, protocol or auth mode differ from the variables, the run stops and says so,
because the server cannot change them. Delete the connection to switch providers. The spec binds the model to
Hephaestus in the `e2e` workspace and binds the seeded model again when it ends.

`accessibility.spec.ts` is the integrated half of the accessibility evaluation: axe on every route in both
colour schemes, a Tab walk, reflow, menus, dialogs and three keyboard-only processes. It needs only the
`e2e` account and runs on any browser project; `docs/contributor/accessibility-audit-plan.mdx` says what it
leaves to a person with a screen reader.

## Journey budgets

`journeys.spec.ts` holds the journeys that make up most use to what they cost: a cold load of the practice profile, Ask Hephaestus and the feedback that waits for approval, and the step from the practice profile to Ask Hephaestus.
It needs no server: like the user-guide screenshots, it answers the API from the synthetic workspace in `screenshots/workspace.ts`, and each answer waits 100 ms.
For each journey it records the API reads, the round trips the reader waits for before the page shows its own content, and the landmarks that layout shifts move without the reader's input.
The round trips come from Playwright's request timings: a read that starts only after another answered counts one more.
`journey-budgets.ts` states the expected values exactly.
A change that adds a read, a round trip or a moved landmark fails and prints the new value.
Update the budget in the same change, so the review sees the cost.
A saving fails in the same way, so the budget comes down with it.

## Smooth navigation

`smooth-navigation.spec.ts` checks that moving around the app keeps the reader where they are.
Like the journeys, it needs no server and answers the API from the synthetic workspace.
It counts document loads while links inside the app open pages, and while the browser goes back and forward: only the first load may load the document.
It also checks that a tab on Review settings keeps the scroll position.
The lint rule `hephaestus/same-page-search-keeps-scroll` guards the same rule in the source.

## Recovery from a failed start

`boot-recovery.spec.ts` blocks React's chunk, so the app cannot start.
The page reloads once, and then it says that it could not load.
After the block ends, the page's Reload button loads the app.

## Reading the result

`public-activity.spec.ts` uses the isolated `e2e-public` workspace in `seed.sql`.
It reads real public activity from the packaged server, including private-repository and hidden-person exclusions.
It covers drawer history and sign-in return at desktop, plus one touch viewport.
The seed refreshes repository visibility confirmations; load it before each run.
It uses synthetic GitHub records and needs no provider token.

The coverage reporter prints actual test outcomes and lists every selected live-provider check and
skipped test with its skip reason. CI also appends this report to the job summary. A skipped check
is not a pass; the ordinary packaged-server suite does not prove that live GitHub or GitLab
integrations are healthy. A filtered run explicitly says when no live-provider checks were selected.
The reporter does not enable tests, install credentials, or opt into mutations.
