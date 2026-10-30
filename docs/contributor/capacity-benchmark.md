---
title: Measure node capacity
description: Run isolated course workloads, record their resource use, and distinguish measured capacity from assumptions.
page_type: how-to
---

Use this procedure to measure the current chart and images on an isolated release.
The workload uses typed TypeScript for conversations and the existing [k6 scenarios](https://github.com/hephaestus-build/Hephaestus/tree/main/load-tests).
A passing request count alone does not qualify a node.

## 1. Prepare an isolated window

Agree on a window with the operator.
Use synthetic accounts, workspaces and repositories only.
Record the release, image digests, node capacity, allocatable memory, reserves and other workloads.
Set a memory floor that leaves at least 1 GiB plus the eviction reserve.
Stop new load before available memory crosses that floor.

The test-only fixture uses namespace `capacity-bench-20261030` and a separate sandbox namespace.
It must have its own PostgreSQL, NATS and generated credentials.
Do not copy sessions, provider keys or application data from another release.
Block public routes and external delivery.
Retain the private paths that the application needs: DNS, database, broker, sandbox gateway and Kubernetes API.

## 2. Prepare the replay

Generate the sensitivity inputs:

```bash
node scripts/capacity-provider.ts --grid > tmp/capacity-grid.json
```

Each case labels its provenance as assumed.
The grid varies first-token time, output size and rate limiting.
Its input size and stream speed are also assumptions.
To use measured replay, supply paired samples with `ttftMs`, `durationMs`, `inputTokens`, `outputTokens` and `status`.
Keep the source and provenance in the document.
Do not reconstruct individual call distributions from attempt totals or independent percentiles.

Render the isolated provider:

```bash
node scripts/capacity-fixture.ts --replay tmp/replay.json --out tmp/provider.json
```

The provider never forwards a request.
It records timestamps and synthetic correlation markers, without request bodies or credentials.
It supports OpenAI chat completions and a bounded synthetic read-tool path.
It is not a judge of practices or a complete review/composition fixture.
Use `--tool-mode none` for a controlled single-call experiment.
Keep that result separate from the default mixed-read case.
Synthetic markers in conversation history can refer to an earlier turn.
Validate every provider-to-turn timestamp join before you use it.
The sweep sets `CAPACITY_RUN_LABEL` on the fake and includes stable user/turn keys in each question.
Replay samples use that key and its call ordinal, so request UUIDs and arrival order do not change the sample.
Use the logged `replayKey` and `callOrdinal` for phase joins in these runs.

## 3. Install the test release

Use the chart's small profiles and the same image digests as the system to compare.
The chart retains its normal value validation.
The test-only Helm post-renderer lives under `scripts/capacity-bench/`.
It refuses another Helm namespace, removes public routes and Internet paths, and substitutes the e2e profiles.
It copies all `application-prod.yml` properties explicitly, except the listed synthetic auth settings.
Record its output and all auth-only exceptions.
Keep JVM resources, pool settings, timeouts, caches and logging equal to the compared release.
The renderer restores the base values for every non-auth e2e property.
A contract test covers the complete e2e property list.
Production-only startup guards do not run in e2e; this fixture does not qualify those guards.

Use the test values in `scripts/capacity-bench/values.yaml`.
Supply verified release image digests and replace its documentation API address with the qualified single IPv4 `/32`.
Export that same address as `BENCH_KUBERNETES_API_CIDR`, and set `HELM_NAMESPACE=capacity-bench-20261030` for Helm and the renderer.
Use that environment variable to select the namespace; do not pass Helm's `-n` or `--namespace` flag.
Provision this release's credentials and PostgreSQL TLS from the [Kubernetes install procedure](/admin/kubernetes).
Never use the existing installation's credentials.
The renderer gives the three bench priority classes values below 1000 and `preemptionPolicy: Never`.
If an earlier fixture created different classes, stop all its Pods before you replace those owned classes.

Helm 4 uses a `postrenderer/v1` plugin.
From the repository root, set `HELM_PLUGINS="$PWD/scripts/capacity-bench"` for the command.
The supplied `helm-plugin/plugin.yaml` resolves its entrypoint beside that plugin directory.
See the [Helm plugin guide](https://docs.helm.sh/docs/plugins/migrate/).
Do not install this plugin in the operator's normal plugin directory.

Run the driver in the server Pod and use loopback for e2e sign-in.
The fixture seeder creates synthetic sessions through the supported endpoint and completes the notice.

1. Run `capacity-bench-seed.ts` with `CAPACITY_SEED_MODE=sessions` and `CAPACITY_USERS` set to the cohort size.
2. Run `scripts/capacity-bench/seed-identities.sql` with `psql -v ON_ERROR_STOP=1` inside `bench-postgresql-0` in `capacity-bench-20261030`.
   It refuses a database with non-synthetic workspaces or accounts before it writes anything.
   It creates only the synthetic workspace and loopback identity provider, then links the synthetic memberships and identities.
3. Run the seeder with `CAPACITY_SEED_MODE=models` to configure the fake model.

A linked identity is required by the normal person-data write fence.
The model and its binding must have the same declared privacy tier.
Keep the session file private in the driver's ephemeral volume.
The seeder configures both conversation and review purposes to the isolated fake.
It uses no real provider credential.

## 4. Run conversations first

Copy the harness and the verified k6 binary into the driver container under `/bench`.
Use the same k6 version as `scripts/load-test.ts`.
Record its artifact checksum.
Run a smoke before a recorded cohort.

The stream observer creates no questions:

```bash
node /bench/scripts/capacity-stream-observer.ts --seconds 1200
```

The first stream body byte can be a snapshot or heartbeat.
Use the first nonempty answer text for user-visible first-token time.
Keep both observations.

Run S3 after the operator confirms the quiet window:

```bash
node /bench/scripts/capacity-bench-run.ts --label s3-first --mode recorded \
  --quiet-window /bench/quiet-window.json
```

S3 offers forty independent session arrivals in one second.
Set `COURSE_USERS` to change the cohort to 80 or 120 users.
Each session asks three turns with think time.
The initial batch does not wait for a completed session before another user arrives.
Set `COURSE_CHAT_RATE` for a separate sustained open-arrival test.
Exact completed counts and refused/dropped arrivals must reconcile.
An empty completion rate must fail the run.
Keep failed and interrupted evidence.

### Sweep turn admission

Use `COURSE_SSE=1` for the typed TypeScript driver.
It opens one event subscription per identity and waits for terminal turn events without polling.
It records arrival scheduling, request send, first turn event, first answer text and completion.
The existing polling driver remains a diagnostic path; its extra HTTP requests affect CPU measurements.

Set the worker's admission and its chart-derived sandbox quota together in the isolated release.
Keep sandbox requests and limits unchanged.
For each limit, use 40, 80 and 120 users with three turns and 30-second think time.
Set the fake to 3-second first token, 500 output tokens and the recorded stream speed.

Create an operator state file with `state` and `source` fields.
Set `state` to `CLEAR` only after the operator explicitly releases the window.
Set it to `QUIET` when the operator requests a stop.
Record the message or decision in `source`.
Set `stopAt` to a future UTC timestamp that leaves enough time to restore the operator's replicas.
The step checks this file and the merge queue before load and every 30 seconds.
It also detects changes to main or the shared deployment generations.

```bash
node scripts/capacity-bench-step.ts --label admission-16-users-40 \
  --admission 16 --users 40 --operator-state tmp/operator-state.json
```

The sampler stops the driver below 1 GiB plus a 256 MiB eviction reserve.
An unexpected sampler exit also stops the load.
Use `scripts/capacity-bench-stop.ts` inside the driver container to cancel its synthetic turns through the owner API.
The step verifies idle synthetic owners before load, then resets their conversations in the isolated database.
After load, it verifies owner cleanup again and saves checksums for every receipt.
The SSE driver uses snapshot recovery reads when the server drops notifications; it does not poll periodically.
Export receipts before you stop the server Pod: the driver volume is ephemeral.
Keep interrupted runs separate from completed capacity results.

The additional course presets describe review admission and publication loads.
Direct review requests bypass provider-event selection.
The existing synthetic webhook event measures publication, not downstream provider sync or duplicate-ID replay.
Do not use these paths to claim end-to-end review capacity without the corresponding synthetic corpus and delivery receipts.

## 5. Collect and interpret

Use `scripts/kubernetes-footprint.ts` for synchronized node and container counters.
It records charged memory, process RSS, cache, swap, CPU counters, pressure and OOM counters.
Use `--bench-postgres` to include connection and lock-wait counts from the isolated database.
Each sample is also written to JSONL so an interruption does not lose the trace.
Process RSS can count shared pages more than once.
Use cgroup charged memory for the budget and keep RSS as a separate observation.
An absent kernel lifetime peak remains missing; sampled peaks can miss short events.
Capture PostgreSQL connections/lock waits and NATS lag alongside these samples.

Correlate request send, queue/claim, first provider call, provider token/end and turn end.
Use matching tool logs to attribute tool spans.
Gaps between provider calls contain non-model work, including scheduling and transport.
Use interval unions when spans overlap.
Do not add or subtract independently computed percentiles.

Increase offered load until a latency objective, the memory floor or a real provider budget fails.
Name the binding resource at each step.
For steady state, check Little's law with means over identical work boundaries.
For a growing queue or finite burst, report backlog slope and drain instead.
Run the two-hour soak only after the steady-state workload passes.
Qualify the final artifact again when runtime or resource settings change.
Restore every changed setting and release the operator's window when the run ends.

## Check that it worked

Check that the expected users and turns completed, with no unexplained refusals or missing arrivals.
Check that the resource trace covers the whole load and drain interval.
Check that the operator state and merge queue stayed clear.
Check that no synthetic turn remains active after cancellation or failure.
Keep failed, interrupted and contaminated runs; do not use them as qualified capacity.
