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
| `major` | must act first | required new chart value, removed or renamed chart value, destructive/manual migration, dropped API — state the action + add a `.migration` fragment |

**Until 1.0.0 is out, never pick `major`**.
While the version is 0.x or a 1.0.0 release candidate, a `major` still means the 1.0 release.
A late one could make 2.0.0 the first release after 1.0.0 by mistake.

CI refuses it, except the changeset `release-1-0-0`.
The [release management guide](https://docs.hephaestus.build/contributor/release-management#cut-100) plans that changeset.
Use `minor` for a breaking change instead.
Thus, a `minor` before 1.0.0 does *not* guarantee that no operator action is necessary.

**From 1.0.0 on, a breaking change picks `major`**.
CI accepts it from the first stable 1.0.0 version, with **Operators:** and a `.migration` fragment.
The API compatibility gate then lets the break land.

If the operator must act, state the action (`**Operators:** …`).
Add `.migration/<changeset-slug>.md` exactly as a `major` would.
The fragment contains the complete `#### 🔴 …` migration-guide entry.
Never edit `MIGRATION.md`.
The version process assembles and consumes fragments in filename order.

A PR that removes or renames a chart value must name the old value in backticks, such as `` `config.gitlab.workspaceCreation` ``, in its changeset or fragment.
CI compares the chart's schema with the base, because the chart refuses a value that its schema lacks.

Example:

```md
---
"hephaestus": minor
---

Workspace activity keeps its time range when you switch workspaces.
```

Full flow and rules: [release management guide](https://docs.hephaestus.build/contributor/release-management).
