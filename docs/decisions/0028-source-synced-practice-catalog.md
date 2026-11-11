# ADR 0028: Source-synced practice catalog with sparse instance overrides

**Status:** Accepted
**Date:** 2026-08-02
**Authors:** Felix T.J. Dietrich

## Context

Hephaestus ships practice definitions in the repository, while instance administrators need to
customize what new workspaces receive. Persisting a complete second catalog would create two owners
for the same definitions and require every release to reconcile them.

Workspace practices cannot serve as the instance catalog. They are tenant-owned runtime copies with
workspace lifecycle and revision rules; assigning global policy to a synthetic workspace would break
those boundaries.

## Decision drivers

- Repository changes to untouched defaults must reach instances without an administrator replaying
  them manually.
- Instance customizations must survive upgrades and never be overwritten silently.
- Existing workspace practices must remain independent.
- The model must preserve provenance without foreign keys to definitions that may not have database
  rows or may disappear.
- Concurrent administrators must not overwrite one another.

## Considered options

- **Materialize the complete catalog in database tables.** Rejected because it duplicates the bundled
  definitions and needs synchronization, conflict, and revision machinery.
- **Store the catalog in a reserved workspace.** Rejected because it weakens tenant ownership and
  couples instance policy to workspace lifecycle.
- **Compose bundled defaults with sparse instance overrides.** Chosen because each layer owns one kind
  of decision and absence has a useful meaning.

## Decision

The effective instance catalog is computed from:

1. bundled defaults versioned in Git; and
2. sparse instance overrides keyed by durable slug.

An override row may hold a custom definition, inclusion policy, the accepted bundled definition, or custom
position. No row means the bundled definition and order apply. Updating Hephaestus therefore updates
untouched entries automatically; customized entries retain their saved definition until an
administrator applies or acknowledges the new default.

Git is the history of bundled definitions. The configuration-audit ledger records definition and
inclusion changes. Content-derived ETags protect writes even when an untouched entry has no database
row.

New workspaces receive the effective catalog once. Their copies retain a source slug and comparison
fingerprint, not a foreign key to an override. Later instance changes never rewrite them.

## Consequences

- The instance database normally stores fewer rows than the effective catalog contains.
- Removing an untouched bundled entry removes it from the effective catalog; a customized one remains
  until the administrator resolves it.
- Instance-created slugs share the bundled namespace. If a later release introduces the same slug, the
  saved definition remains effective and the bundled definition appears as an update.
- Ordering is independent of definitions and can return to bundled order by deleting custom positions.
- Catalog history and diff views are not stored separately. Git retains bundled history. The audit
  ledger records audited definition and inclusion changes but hashes criteria and scripts, so complete
  historical custom definitions are not retained.

## Confirmation

`CatalogEntryServiceIntegrationTest` fails if a changed bundled default overwrites a customized entry or a stale precondition writes.
`CatalogAdminControllerIntegrationTest#shouldRejectAStaleWriteWithoutDisclosingTheCurrentETag` checks the ETag on writes.
Review catches a foreign key from a workspace practice to an override row.

## Revisit trigger

Revisit this decision only if the product needs catalog history independent of both releases and
administrator actions, or if catalogs become exchangeable between instances.
