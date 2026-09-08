# ADR 0019: Workspace membership is keyed on `Account`, not the SCM `User`

**Status:** Accepted
**Date:** 2026-06-09
**Authors:** Hephaestus maintainers
**Builds on:** [ADR 0004](0004-sql-layer-tenancy-via-statement-inspector.md), [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md)

## Context

An account is the person signing in. An SCM user is a provider-specific projection used for work
attribution. Using the SCM user as the workspace authorization key prevents organizational accounts
without GitHub or GitLab from joining and makes authorization change when an attribution link changes.
A provider username, email or cached actor reference is not evidence of account ownership.

## Decision

Keep the two facts separate:

- `workspace_account_membership` holds account access, with a unique `(workspace_id, account_id)`
  constraint and foreign keys to the workspace and account. It carries role, source and suspension.
- The existing `workspace_membership` table retains SCM contributor facts: leaderboard visibility,
  league points and provider membership. Its legacy role is not consulted for account authorization.

This additive split avoids changing the key or losing contributor history in a released table.
Account IDs cross the authentication module boundary through its existing identity-query SPI; the
workspace module does not need to expose authentication entities.

Accounts without an SCM link have no SCM attribution surface. No synthetic actor is created to grant
access. The first eligible verified linked actor is selected independently of the account's role.
Workspace creation grants ownership to the verified creator account. Instance-admin elevation gives
ADMIN access, not ownership, without creating a membership row.

Manual role changes and suspension lock the workspace, recheck current caller authority, and preserve
the final active owner. Ordinary SCM synchronization cannot override manual or migrated access, undo
suspension, or grant ownership. Its account targets are resolved by provider and immutable subject.
An incomplete snapshot is not an empty membership set.

## Migration and operations

The forward migration adds the account table and retains the old contributor table. It backfills only
active accounts with non-disabled links matching the contributor's provider ID and native subject.
Multiple linked contributors collapse to the strongest existing role, marked `MIGRATED`. Unlinked
contributors keep their historical facts but receive no account access. No email or username matching
is performed.

A workspace with an existing owner must have at least one verified active owner account before the
backfill can proceed. A Liquibase precondition stops the migration otherwise, rather than silently
orphaning the workspace or inventing an owner. Real PostgreSQL migration tests cover successful
backfill and this refusal, separately from Hibernate-created test schemas.

All runtime roles must upgrade together while stopped. Rolling back requires restoring the complete
pre-upgrade database with the previous release; a binary-only rollback reintroduces the old access
rules. The release's account-membership migration guide owns the operator steps and HTTP changes.

## Consequences

Organizational sign-in and workspace access are independent. An owner may grant account access
without requiring source control. Linking or unlinking an SCM identity changes attribution, not a
manual membership. Provider-derived access has explicit provenance and cannot silently replace a
manual grant. Account deletion must transfer final ownership before proceeding.

The additive migration retains an intentionally legacy contributor table name. Renaming or dropping
that table is not necessary for the authorization split and is not part of this decision.
