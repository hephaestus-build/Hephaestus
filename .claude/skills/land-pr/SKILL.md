---
name: land-pr
description: |
  Validate, regenerate, branch, commit, push, and open the PR.
  Runs the local quality gate first, so a remote build cannot fail on something a local gate catches.
disable-model-invocation: true
allowed-tools:
  - Bash(gh *)
  - Bash(git *)
  - Bash(vp *)
  - Bash(./gradlew *)
  - Read
  - Write
  - Grep
  - Glob
metadata:
  source: internal
  version: "2.0.0"
---

# Land PR

Follow the [writing standard](../../../docs/contributor/simplified-technical-english.md) for all prose.

## 1. See what changed

```bash
git status --short
git diff --name-only HEAD
```

Nothing staged or modified means nothing to land.

## 2. Know which CI legs your diff triggers

`detect-changes` in `.github/workflows/cicd.yml` holds the path filters.
Read it instead of guessing.
Two path groups need attention:

- `docs/**`, `scripts/**`, and the root lint, format, and tsconfig files select the `Tooling and Docs` leg.
  They do not select the App Server leg.
- `package.json` or `pnpm-lock.yaml` select every source leg.

A pull request runs only the fast tier. The merge queue runs the integration shards, the database
tests, the drills and the browser suites. To run them before you queue a change, add the `ci:full`
label ([Two verification tiers](../../../docs/contributor/ci-cd.mdx#two-verification-tiers)).

Reconcile changed dependency inputs before local gates.
See [Dependency installation](../../../docs/contributor/local-verification.mdx#dependency-installation)
for the native install procedure.

## 3. Format, then check

```bash
vp run format
vp run check
```

`check` is the complete local quality gate: every gate in the `quality` group in `vite.config.ts`,
and every one also runs in CI. CI also runs service tests, builds, images,
security checks, and workflow-specific gates. Formatting must never be the reason a remote build
fails.

## 4. Regenerate what your change invalidated

Generated artifacts are never hand-edited, and regeneration is destructive — it empties the target
directory first, so stash local edits.

```bash
vp run generate:api          # controllers or DTOs changed: rewrites openapi.yaml AND webapp/src/api
vp run db:draft-changelog    # entities changed (needs Docker); writes and wires the changelog, then prune it
vp run db:generate-erd-docs  # after pruning a changelog
```

`generate:api:specs` packages the server and boots the executable JAR on ports
it allocates itself.
Thus, no ports need to be freed.
Root `AGENTS.md` § Command caveats covers the `HEPHAESTUS_APPLICATION_JAR` shortcut for a JAR you already built.

## 5. Run the tests your diff can break

```bash
vp run test:webapp
vp run test:server:unit
```

For handwritten Java or Spring test configuration changes, run `vp run test:server:architecture`.
See `server/AGENTS.md` § Test tiers for other affected tiers.

For documentation changes, run `vp run docs:build`.

## 6. Re-run format + check

Regeneration produces unformatted output.
Run step 3 again.
Both commands must pass on the final tree.

## 7. Changeset

A PR touching `server/`, `webapp/` or `docker/` needs a `.changeset/*.md` or `verify-changesets`
fails it.

```bash
vp exec changeset          # user-facing: pick the bump, write the summary in the operator's voice
vp exec changeset --empty  # no user-facing effect; say why in the body
```

`vp exec changeset` is interactive.
With no TTY, hand-write `.changeset/<slug>.md`.
`.changeset/README.md` owns the rules:

- The voice and version bump.
- Pre-1.0 `minor` with `**Operators:**` and a `.migration/<slug>.md` fragment.
- Never change `MIGRATION.md`.

Changing `db/changelog/` without changing `.changeset/` is always wrong.

## 8. Branch, commit, push

Read the whole diff against the goal of the task first.
Remove what the goal does not need.

```bash
git status --short
git fetch origin main
git diff origin/main...HEAD --stat    # committed work, against the merge base
git diff --stat                      # uncommitted work
```

Stage only the paths of this task.
Changes in the worktree that you did not make belong to the maintainer, so never use `git add -A`.

```bash
git branch --show-current    # if main, branch first
git switch -c <type>/<description>
git add <path>...
git commit -m "<type>(<scope>): <description>"
git push -u origin HEAD
```

Types and scopes are enumerated in `commitlint.config.ts`, which is what validates the PR title —
read it there instead of in a copy.
Do not put `!` in the title.
The changeset, not the header, carries pre-1.0 breaking changes.

## 9. Open the PR

```bash
PAGER=cat gh pr view --json number,url
```

If that reports that the current branch has no pull request, write the body in `tmp/pr-body.md`. Git ignores `tmp/`.
Use the sections of `.github/PULL_REQUEST_TEMPLATE.md` and delete the ones that do not apply.

The title says what changes for the reader, not which mechanism changed.
The squash merge makes it the commit message.

```text
Bad:  fix(webapp): add useDeferredValue to FeedbackList
Good: fix(webapp): feedback list no longer jumps while it loads
```

The body starts with the problem in one or two sentences, in the words of the person who has it.
Then it explains the fix and how you verified it.
Do not start with a list of changed files.

```text
Bad:  Moved the filter state into a search param, added useSearchState, updated three stories.
Good: Reloading the practice list reset its filters, so a shared link showed a different list.
      The filters are now part of the URL.
```

Before you create the PR, check the title and the body for secrets, tokens, and private details.
Add no model, agent, or tool attribution: `Validate PR` fails a body that has one.
Open it ready for review. A draft is for unfinished work.

```bash
PAGER=cat gh pr create --base main --title "<type>(<scope>): <description>" --body-file tmp/pr-body.md
```

If GraphQL is rate-limited, `gh pr create` fails. The REST limit is separate:

```bash
PAGER=cat gh api repos/{owner}/{repo}/pulls -f base=main -f head="$(git branch --show-current)" \
  -f title="<type>(<scope>): <description>" -F body=@tmp/pr-body.md --jq .html_url
```

## 10. Upload visual evidence

For a UI change, save PR-only evidence under the ignored `tmp/` directory and inspect it for
secrets, personal data, and unrelated content. Give each image alt text that describes the visible
state. For a video, describe the behavior that the video shows in the PR body.

```bash
mkdir -p tmp
gh pr edit --attach './tmp/before.png#Settings before the change' --attach './tmp/after.png#Settings after the change'
```

An upload can add earlier files before a later file fails. Inspect the PR before retrying, then
attach only the missing files.

## 11. Verify

```bash
PAGER=cat gh pr view --json url,title -q '"PR: \(.title)\nURL: \(.url)"'
```

Open the URL and check that every attachment renders, describes the intended state, and contains no
sensitive or unrelated content.

## 12. Hand off

Hand off to `/watch-pr`, which watches the PR until it is ready to merge.
