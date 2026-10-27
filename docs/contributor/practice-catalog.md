---
title: Practice catalog curation
description: How the catalog that ships with Hephaestus is written, reviewed and released.
---

# Practice catalog curation

The catalog supplies practices and practice groups that workspaces can adopt.
Instance entries and workspace practices have separate authority and lifetimes.
The [product vocabulary](./practice-feedback-language.md#practices) owns their terms.

## Sources and ownership

Bundled resources declare immutable item kind beside their authored definition.
A local practice entry also needs an explicit kind.
Do not infer kind from its first event. A scan-only or guidance-only practice can have no event.

`catalog_entry` owns an instance entry's definition, base, order and exclusion state.
A workspace practice owns its current version, group, order and feedback mode.
`practice_version.definition` is the only authored definition.
Database-generated name and review digest are read-only projections.

## Authoring experience

The editor separates Review on, When, About, Reviewed by and Applies when.
The [operator author guide](/admin/writing-practices) owns the procedure.
The same definition shape serves both admin consoles.
Neither console inserts scope-specific fields into shared definition components.

A definition contains reviewOn, appliesWhen, about, reads, reviewedBy, criteria, script, scan, lineNotes and guidance.
Reads retains its source contract and source requirements.
Reviewed by retains the human explanation and limits.
About can be null when no developer is judged.
The [review pipeline](./practice-review-pipeline.mdx#select-a-review) owns applicability checks and capture refusal behavior.

## Workspace adoption lifecycle

Adoption copies the selected instance definition into a new workspace practice version.
The workspace retains the exact catalog base used for comparison.
Later catalog changes are proposals rather than implicit changes to a local practice.

Accepting selected offered fields creates a new version.
Declining advances the catalog base while retaining local text.
It does not hide every future offer.
Reset replaces local customization with the current catalog definition.

Include and exclude control whether workspaces can adopt an entry.
Neither action rewrites existing workspace definitions or past results.

## Release behavior

Proposals compare current, base and offered definitions.
Each changed field identifies local changes, offered changes and conflicts.
Writes require the returned concurrency token.
A stale comparison cannot overwrite newer customization.

Instance release acceptance uses the instance proposal, without a workspace version identifier.

## Review identity

The review digest includes applicability, About, reads, Reviewed by, criteria, script and scan.
It excludes name, guidance, reviewOn, lineNotes and group organization.
Name or group changes do not make an old result a different standard.
A scheduling change changes the full definition but not what the same evidence establishes.

The database generates the digest from its canonical JSONB projection.
Known-field presence distinguishes an unknown historical role from explicit About null.
Java and TypeScript do not maintain separate canonical digest implementations.
Reuse also requires the exact practice version and frozen model-slot method.

## Changing bundled defaults

Keep stable slugs. A slug identifies a practice rather than its displayed name.
Supply complete positive criteria and supported evidence requirements.
A human-only definition has its reason. Guidance-only definitions run no automated script.
A scan form declares include, exclude and context selections explicitly.

Bundled scan context includes all repository files to retain cross-file evidence dependencies.

### Accept a bundled change

A bundled change ships only after its mechanical checks pass.
This rule applies to every proposed change, from a person or from an AI assistant.

Mechanical checks prove the contract.
CI runs them before each change merges.
They include these checks:

- The catalog loader and the definition validator accept every bundled definition.
- `ScmCapturedApplicabilityTest` has fixtures for each bundled Applies when gate. Its fixtures cover when the gate applies, does not apply, and cannot decide.
- Each script with a table for each language has a fixture for each language in the table. Add a matching fixture when you add or change a language row. A file in another language does not show that the practice does not apply.
- Each automated practice about an issue or a pull request has a case in `criteria-cases.json`. `PracticeCriteriaCaseStagingIntegrationTest` stages each case through the export path.
- Each bundled practice about a reviewer captures their identity and inline comments from its declared sources. It also captures their review decision with its body.
- Each bundled practice about a chat thread or a document captures its whole body from its declared source.
- A practice that people review is not captured, so it has no captured witness.

A passing check does not prove a judgment.
[ADR 0050](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0050-one-practice-standard-one-outcome.md) records that structural validation does not prove an assessment accurate.
Judgment evidence is a set of held-out cases with references that a person adjudicated.
Record it in the pull request, apart from the CI results.
A maintainer accepts it before the change ships.

A change to a field in the [review digest](#review-identity) needs this judgment evidence.
A change to name, guidance or group does not.
The expected outcomes in `criteria-cases.json` help during development.
They are not held-out evidence, and a passing staging test does not make them so.

### Bundled visuals and guides

Guidance owns whyItMatters, whatGoodLooksLike, goingWell, visual and guide.
Going well text is stored for every practice, including custom practices.
There is no catalog-only lookup for that sentence.
The [visuals guide](./practice-visuals.md) owns validated SVG and guide content.

### Practice scripts

A null script uses the built-in script.
Custom scripts use the core author entry point and a domain kit where they need domain evidence.
The [runner guide](./practice-script-runner.mdx) owns the strict contract.
The [operator guide](/admin/practice-scripts) owns the rewrite procedure for older scripts.

#### Bundled script upgrade

Append new active versions when source or script contracts change.
Do not rewrite pinned historical definitions.
Unchanged bundled copies receive the audited catalog update.
Changed copies retain their text until an admin accepts the offer or rewrites them.
The [operator migration guide](https://github.com/hephaestus-build/Hephaestus/blob/main/MIGRATION.md) owns the script preflight and cutover procedure.

An invalid script cannot fall back silently.
