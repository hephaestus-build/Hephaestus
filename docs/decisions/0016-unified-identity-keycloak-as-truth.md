# ADR 0016: Unified identity — SCM `User` is the authoritative person row, Keycloak `sub` is the persisted join key

**Status:** Superseded by [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md)
**Date:** 2026-05-27
**Supersedes:** [ADR 0011](0011-integration-identity-not-wired-from-sync.md)

## Context

ADR 0011 introduced a "three-layer identity model" — `integration.core.connection.Connection` (Layer 1), `integration.identity.HephaestusUser` (Layer 2), `integration.identity.IntegrationIdentity` (Layer 3) — and immediately admitted that the Layer 2 / Layer 3 wiring never landed: neither sync path nor any OAuth callback populated `integration_identity`, both tables stayed at row-count zero in every environment, and the only consumer of the table (the pre-workspace install-bind surface) was itself deleted when AC#6 was dropped from #1198 on 2026-05-26.

A review of the shipped state confirmed the consequences:

- `HephaestusUser`, `IntegrationIdentity`, `IntegrationIdentityRepository`, `HephaestusUserRepository`, `UserDirectory`, and `JpaUserDirectory` have **zero callers** across `server/src`. The Javadoc on `HephaestusUser` references a phantom `IdentityLinkingService` that does not exist.
- The actual authorization join key is `preferred_username → User.login` (`WorkspaceContextFilter`, `SecurityUtils.getCurrentUserLogin`). Keycloak `sub` — the stable IdP-side identifier — is never persisted server-side. A user rename in Keycloak silently breaks authorization.
- The Liquibase changeset for `hephaestus_user` + `integration_identity` is in an unmerged branch (#1198), so endorsed Liquibase guidance is to remove the create steps rather than ship "create then drop" history.

Shipping infrastructure that does not run is a tax on every future reader. The audit recommended deletion plus a corrective seed for the model we actually need.

## Decision drivers

- **Correctness of the auth model.** `login` is mutable on every vendor. `sub` is mutable in Keycloak only via deliberate user-realm operations; `sub` is the documented stable join key. We need it persisted server-side or we cannot defend against username renames at all.
- **Truthful code.** Zero-caller classes that pretend to be load-bearing damage every future engineer's mental model of the system. ADR 0011's "we built it, we didn't wire it" was honest; keeping the classes anyway is not.
- **Minimal blast radius.** The follow-up that flips `WorkspaceContextFilter` from `login`-lookup to `keycloak_subject`-lookup must be revertible. Seeding the column now (this PR) and flipping the lookup later (separate PR) keeps the two changes independently reversible.
- **No cross-workspace identity universe today.** The original Layer-2 motivation was "one real person across many Hephaestus workspaces." We have no surface that needs this — every workspace gets its own SCM `User` rows synced from vendor APIs. If we add multi-workspace identity later, we can introduce it as a self-FK on `User` or as a sibling table, designed against the concrete surface that needs it.

## Considered options

1. **Delete the integration.identity layer; persist `keycloak_subject` on SCM `User`; switch the lookup later.** Pragmatic and reversible: deletion clears the misleading code, the new column is seeded immediately from every authenticated upsert, and the load-bearing lookup change ships as its own small PR with a clean revert.
2. **Keep the integration.identity layer; wire sync to actually populate it.** Closes the "no rows ever" gap but builds on a model whose only consumer (the install-bind surface) was deleted. We would be hardening unused infrastructure for a hypothetical future surface — exactly the failure mode this audit is correcting.
3. **Defer everything; ship #1198 with the dead layer intact.** Preserves the audit trail in code but pays the comprehension tax forever, and leaves `keycloak_subject` unpersisted — the actual production bug the audit found.

## Decision

**Option 1**, executed in two stages:

- **Stage A (this PR):**
  - Delete `HephaestusUser`, `HephaestusUserRepository`, `IntegrationIdentity`, `IntegrationIdentityRepository`, `UserDirectory`, `JpaUserDirectory` and the now-empty `integration.identity` package.
  - Remove the `hephaestus_user` + `integration_identity` table creates from the unmerged `1780313973588_changelog.xml` changelog (endorsed Liquibase practice for changes that have not yet shipped to any environment).
  - Delete the dead webapp helper `getGitProviderId()` from `keycloak.ts` + `AuthContext.tsx`.
  - Add `User.keycloakSubject` (column + partial unique index `uq_user_keycloak_subject WHERE keycloak_subject IS NOT NULL`).
  - Wire `AuthenticatedGitProviderUserService.upsertUser` to seed `keycloak_subject` from the JWT `sub` claim on every authenticated upsert. Sync paths (`GitLabIssueProcessor.findOrCreateUser` etc.) explicitly do NOT populate this column — they upsert vendor users we have never authenticated.
  - This ADR replaces ADR 0011 in the live decision set. ADR 0011 stays in the tree as a historical record of "we tried, we deferred, then we deleted."
- **Stage B (follow-up PR — bigger than originally framed):**
  - Replace the auth-time identity resolver. Concretely, `getCurrentUser()` on `UserRepository` (and the underlying lookups in `SecurityUtils.getCurrentUserLogin` + `WorkspaceContextFilter`) become a `findByKeycloakSubject(sub)` call with a transitional `findByLogin(preferred_username)` fallback for users whose row has not yet been touched by an authenticated request after Stage A shipped.
  - Twelve+ call sites of `getCurrentUser()` / `getCurrentUserLogin()` automatically flip through the resolver change — they don't need per-site edits, but each is in the auth path and needs its own behavioural test (e.g. `WorkspaceMembershipController` self-vs-other, `PracticeFindingService` ownership, `MentorChatService` thread auth).
  - Critically: keep **vendor-login lookups** (`UserRepository.findByLogin` from sync paths like `CommitAuthorResolver`, `GitHubLifecycleListener`, `GitLabCommitMergeRequestLinker`, `WorkspaceProvisioningService.findByLogin("admin")`) on login — they are *not* auth-path; they match vendor records that have never had a JWT.
  - Frontend: `isCurrentUser(login)` (`webapp/src/integrations/auth/keycloak.ts`) compares against `preferred_username`. Stage B should flip it to compare against the server's authenticated-user response identity, or delete the helper entirely.
  - Race + double-provider: the partial unique index `uq_user_keycloak_subject` will reject two concurrent first-time authenticated upserts that resolve to the same Keycloak subject across two providers (the deleted three-layer model handled this implicitly). Stage A's `setKeycloakSubjectIfChanged` catches the unique-violation only via Spring's generic `DataIntegrityViolationException`; Stage B must either (a) translate the violation into a successful no-op, or (b) introduce a per-Keycloak-account row that the per-provider `User` rows reference.
  - Once every active user has a populated `keycloak_subject`, drop the `login`-fallback branch.

The partial unique index is the correct uniqueness contract for the seeded column: only authenticated upserts ever set `keycloak_subject`, while the many synced-only rows (bots, organizations, contributors observed only via webhooks) stay NULL and stay outside the constraint.

## Consequences

**Positive:**

- The server gains the stable IdP identifier it should have had all along. Keycloak username renames stop silently breaking authorization once Stage B lands.
- ~600 lines of dead code disappear. Every future reader of `integration/` sees the model we actually run, not the model we partly tried.
- The Liquibase delta for #1198 is smaller and more honest.

**Negative:**

- Stage A only seeds the column. Authorization still goes through `login` until Stage B lands; the stable-id property is dormant. Honest scoping of Stage B: a single resolver flip transitively re-routes ~12 services (workspace, mentor, practices, findings) and needs a behavioural test per auth-path service — call that a medium PR, not a small one.
- The webapp still surfaces `UserProfile.githubId` / `UserProfile.gitlabId` and resolves the avatar via raw provider ID; ADR-driven "sub is the stable join key" only becomes a frontend reality after the avatar + linked-accounts code routes through the server's authenticated-user response. Stage B should close that loop too.
- Anyone holding a branch off #1198 with a dependency on `IntegrationIdentity` will need to rebase. There are zero such branches today.
- If we later add genuine multi-workspace identity-aggregation, we will need a new design pass. That is the right time to do it — against a concrete surface, not as speculative scaffolding.

## Revisit trigger

- A multi-tenant SaaS deployment where the same real person is expected to act across multiple Hephaestus workspaces, AND we need server-side identity-aggregation (cross-workspace mention resolution, unified preferences, etc.). At that point design the right shape against the actual surface; do not resurrect the three-layer model from memory.
- Keycloak migration off `sub` as the stable claim. Treat as a Keycloak upgrade question first; the column is `VARCHAR(128)` and easy to migrate.

## References

- ADR 0011 — `integration_identity is OAuth-fed, not sync-fed (today)` — historical predecessor.
- ADR 0015 — `unified integration framework` — the Layer 1 (`Connection`) half of the original three-layer model, which IS load-bearing and stays.
- Liquibase docs on editing unmerged changesets vs. shipping forward-only — endorsed practice for branch-local cleanup.
- `WorkspaceContextFilter`, `SecurityUtils` — current `preferred_username → User.login` lookup that Stage B will flip.
- `AuthenticatedGitProviderUserService.upsertUser` — the seed point for `keycloak_subject` populated in this PR.
- `1780313973588_changelog.xml` — the new `keycloak_subject` column + partial unique index.

## Update — 2026-09-17

Status corrected to superseded. [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md) removed
Keycloak, so nothing in § Decision beyond the Stage A deletions is what the code does:

- `User.keycloak_subject` and `uq_user_keycloak_subject` do not exist in
  `server/application/src/main/resources/db/changelog/0000000000000_baseline_v0_77_4.sql`; the only
  `keycloak` reference left in `server/` is `NoKeycloakImportTest`, which forbids the import.
- The Hephaestus principal is `core.auth.domain.Account`; the stable join key is
  `identity_link (provider_id, subject)` (`core.auth.domain.IdentityLink`,
  `IdentityLinkRepository.findActiveByProviderSubject`). Stage B never shipped as described.
- The SCM `User` (`integration.scm.domain.user.User`) remains the attribution row and, contrary to
  ADR 0017's data-model split, is still what `workspace_membership.user_id` references —
  [ADR 0019](0019-workspace-membership-keyed-on-account.md) (Proposed) owns that gap.
- `webapp/src/integrations/auth/keycloak.ts` is gone; the `1780313973588_changelog.xml` this ADR edited
  is archived at `docs/db/archive/v0.77.4/changelog/`.
