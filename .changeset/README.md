# Changesets

A **changeset** here is a release note: a `.changeset/*.md` file that becomes `CHANGELOG.md` and drives
the version bump. (Not a Liquibase `<changeSet>` — a schema change needs both.)

Every PR that changes shipped code ships a changeset.
Shipped code is anything under `server/`, `webapp/`, `docker/`, or `extension/`, except tests and in-tree docs.
CI (`verify-changesets`) enforces this. The Chrome extension is versioned with the release, like the webapp.

```bash
vp exec changeset          # write one (pick the bump, describe the change)
vp exec changeset --empty  # no user-facing effect — then write why in the file body (non-interactive)
```

The summary enters the changelog **verbatim**, in the operator/user's voice.
Start with what they can now do, or the symptom a fix removes.
Do not use class, hook, or file names.
Do not add agent-attribution trailers.

Use one changeset per user-visible change.
If you are unsure, add one.

`vp exec changeset` is interactive.
If no TTY is available, as with agents or CI, write `.changeset/<slug>.md` by hand in the format below.
This is the only permitted file to write by hand.
Never change `CHANGELOG.md` directly.

**Bump = the operator's upgrade cost:**

| Bump | Operator upgrade | Examples |
| --- | --- | --- |
| `patch` | no action | bug fix, internal change, additive auto-applied migration |
| `minor` | no action | New capability. Note any new *optional* env var / flag in the summary. |
| `major` | must act first | required new env var, removed/renamed config, destructive/manual migration, dropped API — state the action + add a `.migration` fragment |

**Pre-1.0 (now): never pick `major`.**
It would produce 1.0.0, and CI rejects it.
Use `minor` for breaking changes instead.
Thus, a pre-1.0 `minor` does *not* guarantee that no operator action is necessary.

If the operator must act, state the action (`**Operators:** …`).
Add `.migration/<changeset-slug>.md` exactly as a `major` would.
The fragment contains the complete `#### 🔴 …` migration-guide entry.
Never edit `MIGRATION.md`.
The version process assembles and consumes fragments in filename order.

Example:

```md
---
"hephaestus": minor
---

Workspace activity keeps its time range when you switch workspaces.
```

Full flow and rules: [release management guide](https://docs.hephaestus.build/contributor/release-management).
