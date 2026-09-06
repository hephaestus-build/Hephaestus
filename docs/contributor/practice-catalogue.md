---
title: Practice catalog curation
description: How the catalog that ships with Hephaestus is written, reviewed and released.
---

# Practice catalog curation

The practice catalog turns defined engineering practices into review criteria. Product terminology
lives in the [practice feedback language guide](practice-feedback-language.md); this page covers how
to maintain the catalog.

## Sources and ownership

The effective catalog combines three scopes, and definitions only ever flow one way:

```mermaid
flowchart LR
    accTitle: The three scopes a practice definition passes through
    accDescr: Bundled defaults in the repository upgrade the instance catalog unless an administrator customized the entry. The instance catalog decides what workspaces may adopt. Adoption produces an independent workspace copy that no later change rewrites.
    Bundled[Bundled defaults<br/>default-catalog.json] -->|release upgrade,<br/>unless customized| Instance[Instance catalog<br/>bundled + sparse overrides]
    Instance -->|adoption,<br/>by an administrator| Workspace[Workspace practice<br/>independent copy]
```

| Scope | Owner | Stored as | Decides |
|---|---|---|---|
| Hephaestus defaults | repository maintainers | `default-catalog.json` + precompute scripts | the bundled definition and order |
| Instance catalog | instance administrators | sparse override rows | what workspaces may adopt |
| Workspace practices | workspace administrators | full database copies | reviews in one workspace |

All three scopes use the same definition fields. What differs is who owns the value:

- **Definition, binding, and review frame** — maintained in the repository, inherited or customized
  on the instance, then owned outright by a workspace once adopted.
- **Inclusion** — an instance decision only. Excluding an entry removes it from what workspaces may
  adopt and changes nothing that is already adopted.
- **Autonomy, review coverage, and delivery status** — workspace decisions only. A curator never sets
  any of them, and a practice Hephaestus cannot review is forced to `OFF`.
- **Group and order** — repository array order, overridable on the instance, then independent per
  workspace.

Neither step silently rewrites a customized instance definition or an existing workspace practice.
The badge tables an administrator reads for both scopes live in the
[Practice Catalog admin guide](https://docs.hephaestus.build/admin/practice-catalog).

| Stakeholder                | Primary task                                                                                                               | Deliberately not their task                      |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| Practice author            | Define the habit, guidance, and responsible mentoring support                                                              | Authorize collection or certify review accuracy  |
| Instance administrator     | Curate the library workspaces may adopt from                                                                               | Rewrite existing workspace practices             |
| Workspace administrator    | Adapt practices, set the workspace default autonomy and override it per group or practice, and scope which work is reviewed | Authorize a new data source for the instance     |
| Instance operator          | Approve source purposes, privacy, retention, and erasure coverage                                                          | Decide that connected evidence proves a practice |
| Developer, peer, or mentor | Use observations and available human context in a review                                                                   | Supply hidden context to Hephaestus implicitly   |

## Authoring experience

The practice editor follows the decisions an author can make confidently:

1. **Practice** — give one observable habit a short, action-oriented name, choose the work it
   applies to, and optionally group it in a group.
2. **Review guidance** — describe what to look for, why it matters, and one concrete example.
3. **How Hephaestus can help** — choose AI-supported mentoring, human review, or guidance only.

The generated identifier, the occasion a review runs on, and the optional static-analysis script are
under **Technical settings**. A new practice starts with a binding that fits the selected work type.
Authors only change that default when the practice genuinely needs a different occasion. This keeps
runtime plumbing out of the common path without hiding it from expert authors. A practice has exactly one
occasion: reading different evidence at a different moment is a second practice, which is what the shipped
catalogue does, and asking for a review by hand is not an occasion to choose at all.
The definition-options API supplies the signals a practice on that work type may bind to, the evidence
a new binding starts with, and the sources it may read, so the editor and runtime cannot silently
disagree about what is bindable. The practice never states its artifact kind: it is read off the
signals' shared prefix, which is what stops a declared kind and its triggers from drifting apart.

Write **What to look for** as a review boundary, not as a personality or a score. Define one
observable habit, the signals that demonstrate it, and the cases where a reviewer should stay
silent. Do not require intent, private context, runtime behavior, or any other fact outside the
selected work and evidence boundary.

## Choose how Hephaestus can help

The practice form starts with one product choice instead of separate model and evidence-sufficiency
settings:

- **AI-supported mentoring** lets Hephaestus review connected work after every required source passes.
- **Human review needed** records that connected work is not enough. Hephaestus skips the practice,
  while a developer, peer, or mentor may still review it from context the system does not collect.
  It still names its occasion — that is where its artifact kind comes from, and saying what a
  practice is about was never the same claim as asking Hephaestus to act on it — but it cannot define
  a static-analysis script and its autonomy is forced to `OFF`.
- **Guidance only** keeps the criteria and guidance without configuring Hephaestus to review it.

The binding starts with the recommended evidence for its work type. Most authors should keep it.
**Customize evidence** reveals each source's display name, privacy class, the capture quality its
contract demands, and whether it can be captured whole — so an author knows whether an `EXHAUSTIVE`
stance is available — along with the practice's known limitations. How strictly a source must be
captured is not a per-practice choice: it is stated once in the source contract, which every shipped
practice already agreed with. Selecting a source never authorizes collection; instance governance and
workspace integrations remain separate gates.

### Example: explain what changed and why

| Field                    | Definition                                                                                                      |
| ------------------------ | --------------------------------------------------------------------------------------------------------------- |
| Name                     | Explain what changed and why                                                                                    |
| Review this kind of work | Pull or merge request                                                                                           |
| What to look for         | Look for a description that explains the behavior change and why. Stay silent for automated dependency updates. |
| Why it matters           | Reviewers can judge a change faster when they understand its purpose.                                           |
| What good looks like     | “This changes retry behavior so temporary network failures no longer end the sync.”                             |
| Hephaestus support       | AI-supported mentoring with the recommended bindings and evidence                                               |

The author does not choose source-contract identifiers or runtime states in this common path. If the
required pull-request details or diff are missing, or captured less completely than their contract
demands, Hephaestus skips the practice instead of inventing an observation. A more contextual practice,
such as whether a developer understood a trade-off discussed privately with a mentor, should use
**Human review needed** and name that missing context.

The instance tables store only decisions that differ from the bundled catalog: a customized
definition, inclusion policy, accepted bundled digest, or position. No override row means the
bundled definition and order apply. See
[ADR 0028](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0028-source-synced-practice-catalog.md)
for the architectural decision.

## Workspace adoption lifecycle

- **Slug identifies a workspace practice.** It participates in provenance and observation recurrence.
  Changing a slug requires an explicit remapping strategy; changing a display name does not.
- **Definitions and order are independent.** Reordering does not create a definition override or an
  audit event. **Use Hephaestus order** removes custom positions.
- **Automatic installation is keyed on the installation record, not on age.** At startup, a workspace
  with no `practice_catalog_installation` row receives the whole effective catalogue once. Recording
  that row is what makes a workspace start empty, and only workspaces created through the workspace
  API record it today — see [#1362](https://github.com/hephaestus-build/Hephaestus/issues/1362).
- **Adoption is deliberate.** Workspace administrators can show the instance library alongside their
  workspace configuration, inspect a complete effective definition, and add either one practice or all
  available practices in a group as independent copies. Group adoption is one transaction. Both flows
  fail when the reviewed definition or resulting workspace configuration has changed.
- **Group removal is explicit.** Administrators choose whether a group's practices move to Unassigned or
  are deleted with the group. The library can restore a removed catalog group by moving its matching,
  unassigned workspace copies back without replacing local edits. Practices deliberately placed in a
  different group are never moved implicitly.
- **Adoption does not authorize automatic sending.** A reviewable practice starts at `HUMAN_APPROVAL`
  (**Review before sending**), while a practice Hephaestus cannot review remains `OFF`. Moving to **Send
  automatically** is a separate administrator decision; validation evidence does not make that
  authorization implicitly.
- **Provenance is descriptive, not referential.** Matched workspace copies retain the source slug and
  comparison fingerprint without a foreign key. A bundled source may have no database row and may
  disappear in a later release.
- **Unmatched migrated copies remain unlinked.** Backfill links only definitions that still match the
  bundled review rules or group details.
- **Exclusion controls adoption availability.** Excluding a group also removes its practices from the
  adoption catalogue; existing workspace copies do not change.

Array order in `default-catalog.json` is the bundled order. Until an administrator reorders a list,
bundled entries continue to follow repository order and instance-created entries append. The first
deliberate reorder records the complete affected list. Moving a practice to another group is a
definition change and is audited.

## Release behavior

Each effective entry is resolved from the running bundled definition and any instance override:

| Existing instance state                  | Effective result after upgrade | Admin-page state                     |
| ---------------------------------------- | ------------------------------ | ------------------------------------ |
| Default not customized                   | new bundled definition         | no badge                             |
| Customized; bundled definition unchanged | saved customization            | **Customized on this instance**      |
| Customized; bundled definition changed   | saved customization            | **Hephaestus update available**      |
| Instance-created entry                   | saved definition               | **No Hephaestus default**            |
| Uncustomized default removed             | entry disappears               | —                                    |
| Customized default removed               | saved customization            | **Removed from Hephaestus defaults** |

An update never replaces a customization silently. Administrators can inspect the complete bundled
definition and whether applying it changes review rules, guidance, or group appearance. Applying an
update removes only the definition customization; inclusion policy and custom order remain. Keeping
the saved version records the bundled digest that was reviewed.

Git versions bundled defaults. Content-derived ETags reject concurrent writes based on stale content,
and the configuration audit records definition and inclusion changes. There is no separate catalog
revision counter or materialized copy of every bundled definition.

## Workspace drift

Catalog-installed or successfully matched workspace copies retain their source slug and the
comparison fingerprint captured at installation. The workspace UI derives drift by comparing:

1. the definition currently used by the workspace;
2. the definition copied originally; and
3. the current effective instance definition.

The ordinary matching state has no badge. Exceptions say **Customized for this workspace**,
**Instance catalog changed**, or **Not in the current instance catalog**. Drift is informational and
never rewrites the workspace.

A practice comparison covers the inputs that affect review behavior: slug, name, bindings (their
signals, draft handling, and evidence needs with stances), criteria, precompute script, the
automated-review policy, and group. The artifact kind is not compared separately — every signal name
carries it, so digesting it too would only give a rename two places to be recorded. **Why it matters**
and **What good looks like** are guidance and do not affect review-rule drift. A group comparison
covers name, description, icon, and color; position is excluded.

## Turning a practice down

Autonomy is a workspace decision, not a catalog one: a practice's `autonomy` and the workspace's review
scope both live outside the curated catalogue, and a curator never sets either. The
[practice review glossary](./practice-review-glossary.mdx) defines both in full — the three autonomy states, what each
one does, and the refusal reasons an out-of-scope or autonomy-`OFF` artifact records.

What matters for curation is only this: move a noisy but still meaningful practice to `HUMAN_APPROVAL` so
measurement continues under supervised release. Use `OFF` when the practice itself should not run; neither
operational choice changes the curated definition.

## Selecting a practice

A practice must:

1. identify who is expected to review the habit and from which context;
2. distinguish evidence from inference and avoid claims that the code is correct;
3. define applicability and the evidence required to report it;
4. define false-positive exclusions and proportionate severity;
5. use language useful to the developer doing the work; and
6. cite research, a standard, or an explicitly identified practitioner norm.

Evidence is declared per binding against the versioned
[artifact-source contract](./artifact-source-contract) and the canonical
[practice review glossary](./practice-review-glossary.mdx). Each entry names a source and a stance —
`REQUIRED`, `EXHAUSTIVE`, or `CONTEXTUAL` — and the practice's policy adds conservative skipping and
known limitations. Requirements must not infer availability from a missing file. What an author declares
is a declaration and nothing more — the product validates no policy independently, and every shipped
policy carries the single status `AUTHOR_DECLARED` to say so. Requirements also say nothing about whether
a developer, peer, or human mentor can review the practice outside the governed integrations.

The review-rule fingerprint uses an explicit scheme prefix, bumped whenever its *inputs* change rather
than the rules, so a stored fingerprint is never compared against one computed from a different set of
facts. Each scheme retains its original meaning, so two schemes never compare equal by accident. Bump the
prefix in the same change that alters the input set.

Architecture-wide qualities cannot be inferred from one change. A practice may review an observable
act, such as recording a decision, without turning it into a claim about the whole system.

Classify evidence accurately:

| Classification                 | Requirement                                                          |
| ------------------------------ | -------------------------------------------------------------------- |
| Peer-reviewed evidence         | an empirical study directly supports the claimed relationship        |
| Standard or canonical guidance | a recognized standard or established engineering guide recommends it |
| Practitioner norm              | a community convention with no controlled-outcome claim              |

Record exact sources in the proposal or pull request that changes the practice. Do not present a
standard as an experiment or a convention as a proven outcome.

## Changing bundled defaults

1. State the user problem and supported reviewed work.
2. Cite and classify the evidence.
3. Draft applicability, signals, exclusions, the binding's evidence, and severity.
4. Confirm every source applies to the binding's artifact kind and that its governance decision permits
   the product purpose, audience, processor egress, and retention. A new source follows the
   [artifact-source governance gate](../admin/dsms/artifact-source-governance).
5. Update `server/application/src/main/resources/practices/default-catalog.json`; its adjacent JSON Schema provides
   editor completion and CI validation, and Git history is the bundled version history. Declare the one
   occasion as `on` — a bare signal name is shorthand for a binding on that signal reading the
   artifact kind's default evidence. Reference any precompute script explicitly; a script must be named
   after the practice slug, and an unreferenced one fails validation.
6. Add or update focused automated-review tests, including required-source skipping and valid-empty evidence.
7. Review the admin presentation and a representative piece of delivered feedback.

Create workspace-specific practices through the admin UI or API so validation, ordering, revisions,
and audit behavior remain intact.
