---
name: fix-ci
description: |
  Read every failing check in one pass, fix in dependency order, push once.
  Use when CI is failing, tests are broken, GitHub Actions report errors, or PR checks show red.
disable-model-invocation: true
allowed-tools:
  - Bash(gh *)
  - Bash(git *)
  - Bash(vp *)
  - Bash(./gradlew *)
  - Read
  - Grep
  - Glob
metadata:
  source: internal
  version: "2.0.0"
---

# Fix CI

Diagnose every failing check in one pass, then push once. Fixing 2 of 5 failures burns a push cycle
and a full CI run.

## 1. Wait for the run to finish

```bash
PAGER=cat gh pr view --json number,statusCheckRollup --jq '{
  pr: .number,
  failed: [.statusCheckRollup[] | select(.conclusion == "FAILURE") | .name],
  pending: [.statusCheckRollup[] | select(.status == "IN_PROGRESS" or .status == "QUEUED") | .name]
}'
```

Anything pending means you do not yet know the failure set.

## 2. Read the job summaries before the logs

The `Quality` legs and the `App Server: Generated artifacts` job write a table to
`$GITHUB_STEP_SUMMARY` naming the fixing command per failed check, and emit the same text as
`::error::` annotations. That table is the prescription. Logs are for what it cannot express — a
test assertion, a type error.

```bash
RUN_ID=$(PAGER=cat gh run list --branch "$(git branch --show-current)" --limit 1 \
  --json databaseId,conclusion --jq '[.[] | select(.conclusion == "failure")][0].databaseId')
PAGER=cat gh api "repos/{owner}/{repo}/actions/runs/$RUN_ID/jobs" \
  --jq '[.jobs[] | select(.conclusion == "failure" and (.name | test("CI Status|all-ci") | not)) | {id, name}]'
```

Then per failed job id:

```bash
PAGER=cat gh api "repos/{owner}/{repo}/actions/jobs/$JOB_ID/logs" 2>&1 | tail -80
```

Read every failure before changing anything.

## 3. Fix in dependency order

Formatting, then lint, then types, then behaviour — an earlier fix routinely erases a later failure.
Each leg's annotation names its own command; this table is only what the annotation cannot tell you.

| Failure | What it actually means |
|---|---|
| `routeTree.gen.ts is stale` | A Vite build writes it. `vp run build:webapp`, then commit the file. |
| `Brand assets are stale` | `Webapp: Stories` runs `export:assets` after `test:storybook`, so the job goes red after a clean pass line. Run `vp run --filter webapp export:assets` and commit `webapp/brand`, `webapp/public`, `docs/images/readme`, `docs/static/img` and `docker/compose.proxy.yaml`. |
| `App Server: Generated artifacts` | OpenAPI out of sync → `vp run generate:api`; schema drift → `vp run db:draft-changelog`; ERD outdated → `vp run db:generate-erd-docs`. |
| `Changelog immutability guard` (Security → `Dependencies, secrets, and policy`) | A changelog that reached `main` was edited, renamed or deleted, or a `master.xml` `<include>` was not appended at the end. Fix forward with a new changeset; never edit the released file. |
| `Verify changesets` | The PR touches shipped code with no `.changeset/*.md`. `/land-pr` step 7 has the rules. |
| `Tooling and Docs` red on a docs-only PR | Expected: `docs/**` is in the tooling path filter, where `docs:lint` and `gate:diagrams` run. |

## 4. Reproduce locally before pushing

```bash
vp run format
vp run check
```

`check` runs the `quality` group in `vite.config.ts`. CI additionally runs tests, builds, images
and the Docker-backed artifact gates; green `check` with red CI means one of those.

Server tiers: `vp run test:server:unit`, `vp run test:server:architecture`,
`vp run test:server:integration`; `server/AGENTS.md` § Build traps and § Test tiers have the rest.

## 5. Commit and push once

```bash
git add -A
git commit -m "fix(<scope>): resolve ci failures"
git push
PAGER=cat gh pr checks --watch
```

If the same check fails twice with the same diagnosis, re-read the log rather than retrying the fix.
