# ADR 0012: Cross-instance identity safety on sync paths

**Status:** Accepted
**Date:** 2026-05-25

## Context

A prior fix closed a write-side cross-instance identity defect (provider_id
stamping). A live run of the GitHub flow against staging NATS, with the GitLab
flow also active, surfaced two further structural defects in the same family.

### Defect 1 — Cross-provider login collision on read paths

`OrganizationRepository.findByLoginIgnoreCase(String login)` was unscoped: it
returned an `Optional<Organization>` based on login alone. In a deployment that
hosts both a GitHub org `HephaestusTest` (provider_id=1, github.com) **and** a
GitLab group `hephaestustest` (provider_id=3, gitlab.lrz.de), case-insensitive
match returned **both** rows, and Spring Data's `Optional` wrapper crashed with
`IncorrectResultSizeDataAccessException: Query did not return a unique result:
2 results were returned`.

Observed in the live run as soon as the GitHub workspace was activated:

```text
Failed to sync projects: scopeId=2, orgLogin=HephaestusTest,
  error=NonUniqueResultException - Query did not return a unique result: 2 results
Failed to sync teams: scopeId=2, orgLogin=HephaestusTest,
  error=NonUniqueResultException - Query did not return a unique result: 2 results
```

Every call site of the unscoped method is in a context where the kind is
statically known (inside `integration/scm/github/` or `integration/scm/gitlab/`); the
boundary is pinned by `IntegrationSubjectBoundariesTest`. The unscoped method
itself was the footgun: it cannot enforce the invariant the call site needs.

### Defect 2 — Transient auth failures silently deleted user-configured RTMs

`GitHubRepositorySyncService.syncRepository(...)` returned `Optional.empty()`
for **multiple** failure modes:

1. GraphQL `repository` field is `null` → repo definitively does not exist on GitHub
2. Installation token mint failed → can't even ask GitHub (auth glitch)
3. Transport error → couldn't reach GitHub
4. Rate limit exhausted with no retry budget
5. Generic exception classification miss

The caller in `GithubDataSyncService` collapsed all of these into "repo
not on GitHub" and called `syncTargetProvider.removeSyncTarget(syncTarget.id())`
— **permanently deleting the user-configured `repository_to_monitor` row**.

In our live run with the ephemeral RSA fallback (no real GitHub App credentials
configured), this fired on every sync attempt. Both
`HephaestusTest/MaxTestRepo` and `HephaestusTest/practice-validation` RTMs
were deleted within seconds of workspace activation. A production deployment
with a real-but-momentarily-broken GitHub App (rotated key, GitHub outage,
JWT clock skew) would lose its entire monitoring configuration the same way.

## Decision

### For defect 1 — make every org-by-login lookup provider-scoped

Removed `findByLoginIgnoreCase(String login)` from `OrganizationRepository`.
Replaced with two provider-scoped variants:

- `findByLoginIgnoreCaseAndProviderId(String login, Long providerId)` — for
  per-instance disambiguation (used today only when the provider id is already
  in hand for other reasons; the only authoritative form for multi-instance
  GitLab in a multi-tenant SaaS deployment).
- `findByLoginIgnoreCaseAndProvider_Type(String login, GitProviderType type)`
  — for kind-scoped code paths (anything under `integration/scm/github/` or
  `integration/scm/gitlab/`). This is the workhorse: kind is known statically at
  these call sites, and the implementation is a Spring Data derived-query, no
  custom JPQL needed.

Every call site was migrated to the kind-scoped variant. The unscoped method
declaration is gone — the compiler now enforces the invariant.

The `findByLoginIgnoreCaseAndProvider_Type` variant is sufficient as long as no
two different instances of the same provider type host a colliding login. In a
multi-tenant SaaS deployment where the same group/org name can exist on
`gitlab.com` and `gitlab.lrz.de`, switch the call site to the providerId
variant (the providerId comes from `workspace.serverUrl` via the server-URL
resolver).

### For defect 2 — distinguish definitive 404 from transient inability to ask

Added `RepositoryNotFoundOnGitProviderException` to
`integration/scm/domain/common/exception/`. `GitHubRepositorySyncService` now throws this
exception in the **one** code path where GitHub definitively responded
"repository does not exist" (GraphQL response valid, `repository` field null).
Every other failure mode still returns `Optional.empty()` — the **transient**
signal.

`GithubDataSyncService` now:

```java
try {
    syncedRepository = repositorySyncService.syncRepository(scopeId, nameWithOwner, provider);
} catch (RepositoryNotFoundOnGitProviderException e) {
    // Definitive 404 → safe to drop the monitoring row
    syncTargetProvider.removeSyncTarget(syncTarget.id());
    return;
}
if (syncedRepository.isEmpty()) {
    // Transient failure (auth, transport, rate limit). Leave RTM in place,
    // retry on the next cycle.
    log.debug("Skipped sync (transient): reason=syncReturnedEmpty, ...");
    return;
}
```

The existing classification-based `removeSyncTarget` in `GithubDataSyncService`
(inside `case NOT_FOUND` of the exception classifier) is already correct — it
only fires on genuine 404 errors from the GraphQL response. The fix brings the
earlier code path up to the same safety bar.

The exception name uses "GitProvider" not "GitHub" so the same pattern can be
adopted by GitLab when an equivalent path is added there (today GitLab does
not preemptively delete RTMs from REST sync; if that changes the same
exception is reusable).

## Rejected alternatives

1. **Database-level `UNIQUE(login, provider_id)` constraint + compile-time
   `findByLogin*` removal.** Cleanest possible enforcement — but legacy GitHub-only
   rows exist with NULL `provider_id` and a NOT NULL backfill is a multi-hour
   migration on the production org/user tables. Compile-time method removal is what
   we shipped; the DB constraint is a follow-up once the backfill window is
   coordinated with operations.

2. **Cache the provider on a ThreadLocal at the webhook/sync entry point + read it
   in the repository.** Rejected: ThreadLocal-coupling for tenancy is precisely the
   pattern the existing `WorkspaceStatementInspector` warns against (ADR-0004). The
   provider must be an explicit parameter.

3. **`removeSyncTarget` always on `Optional.empty()`, with retry on a higher layer.**
   Rejected: silently deleting an RTM during a transient GitHub 5xx is the original
   defect; no amount of retry-at-higher-layer fixes the silent-delete window between
   the empty branch and the operator noticing the missing repo. (The shipped design
   instead removes only on the explicit `RepositoryNotFoundOnGitProviderException`,
   thrown at exactly one site after a 404 classification.)

## Consequences

**Positive:**

- A workspace whose GitHub App credentials are momentarily broken (rotated
  key, key not yet propagated, JWT clock skew, GitHub 500s) no longer loses
  its `repository_to_monitor` rows. The next sync cycle retries.
- A multi-tenant deployment where the same login exists on both GitHub and
  GitLab no longer crashes its scope sync on the read path.
- The compiler enforces the kind-scoping invariant: the unscoped method is
  gone, every future call site is forced to declare what provider it cares
  about.

**Negative:**

- The kind-scoped variant is still incorrect for multi-instance GitLab in a
  multi-tenant SaaS (two GitLab instances hosting the same login). For that
  case callers must switch to the providerId variant. This is a known
  follow-up — flagged at the relevant GitLab call sites in code.
- The new exception is checked-by-convention not by `throws` clause
  (`RuntimeException`). Static analysis can't catch a caller that forgets to
  handle it. The caller in `GithubDataSyncService` is the only one today; if
  the method gets new callers they must explicitly catch.

## Revisit trigger

- A deployment topology with multiple GitLab instances hosting overlapping
  group logins (e.g. `gitlab.com` and a self-hosted instance both having
  `acme`). At that point the kind-scoped variant in the GitLab paths needs to
  become the providerId variant.
- A new sync method that returns `Optional<Repository>` and a caller that
  treats empty as "definitely gone". The new exception type is the right
  signal — review the new path against this ADR.

## References

- Live run on 2026-05-25 against `staging.hephaestus.aet.cit.tum.de` NATS with
  workspace 2 = `hephaestustest-github`, installation_id=97640040
- Pre-fix log: `Failed to sync projects: ... NonUniqueResultException`
- Post-fix log: `Failed to sync projects: ... GitHub error minting installation
  token` (the expected auth error — no real GitHub App credentials in our
  test environment) and `Skipped sync (transient): reason=syncReturnedEmpty`
  with the RTM rows still present in `repository_to_monitor`
- `OrganizationRepository.findByLoginIgnoreCaseAndProvider_Type` — the new
  Spring Data derived-query method
- `RepositoryNotFoundOnGitProviderException` — the new definitive-404 signal
- ADR-0011 — predecessor in the same defect family
  (`integration_identity` not wired from sync)
