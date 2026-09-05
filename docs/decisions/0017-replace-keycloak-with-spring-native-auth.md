# ADR 0017: Replace Keycloak with Spring-native auth (BFF cookie-JWT + `Connection`-backed workspace IdPs)

**Status:** Accepted
**Date:** 2026-05-28
**Authors:** Felix T.J. Dietrich
**Supersedes (Stage A):** [ADR 0016](0016-unified-identity-keycloak-as-truth.md)
**Builds on:** [ADR 0010](0010-outbound-oauth-state-handrolled.md), [ADR 0014](0014-per-row-aes-gcm-aad-binding.md), [ADR 0015](0015-unified-integration-framework.md)

> **Update (Stage B-2, post-merge).** The login model below was revised during PR review. Login no
> longer rides per-workspace `Connection` rows of `kind=OIDC_LOGIN_*`; those kinds, the `IDENTITY`
> family, and `OidcLoginConfig` were **removed**. Sign-in is now driven by an **instance-scoped
> `login_provider` table** (`core.auth.provider`) — one OAuth app **per SCM instance** (a
> `UNIQUE(type, base_url)` constraint enforces it), env-seeded on first boot and managed at runtime by
> an instance admin (`/admin/login-providers`). This cleanly separates **authentication** (instance
> login providers) from a **workspace's SCM data source** (a per-workspace `Connection` + group
> token/PAT) — mirroring how tools like CodeRabbit wire self-hosted GitLab. Workspace creation is
> additionally gated by `hephaestus.workspace.creation-policy` (`ADMIN_ONLY` default | `SELF_SERVICE`).
> Read the `kind=OIDC_LOGIN_*` / `OidcLoginConfig` references below as historical.

## Context

Keycloak 26 currently sits in the `server/compose.yaml` as a federating IdP between Hephaestus and the upstream identity providers we actually use (GitHub.com, gitlab.lrz.de). Of the ~30 Keycloak features available, the codebase uses five: federated login via `oauth2Login`-equivalent flows, two protocol mappers (`github_id`, `gitlab_id`), two realm roles (`admin`, `mentor_access`), one identity-link / unlink flow (`kc_action=idp_link`), and the admin client for ~5 SDK calls. We pay for it with one extra container in compose, a parallel concept of "user" between Keycloak and our DB, dev-loop cost, realm-JSON drift, and the `keycloak-admin-client` dependency in the server pom + `keycloak-js` in the webapp.

ADR 0016 (Stage A) already added `User.keycloak_subject` and explicitly anticipated Stage B: "If we add multi-workspace identity later, we can introduce it as a self-FK on User or as a sibling table." This ADR is Stage B — but with a clean break: we do not migrate to a richer-Keycloak-model, we delete Keycloak entirely and replace it with Spring Security 7 native primitives.

## Decision drivers

- **Single owner of identity.** The current Keycloak ↔ Hephaestus "two parallel concepts of user" is the largest source of mental friction in the codebase. After this PR there is one model, one schema, one debugger.
- **Net complexity must go down.** We remove external complexity (a container, an SDK, a realm export, an `idp_link` action UX) and add internal complexity (our JWT issuer, JWK rotation, per-request revocation checks). The trade is correct because internal complexity is debuggable in our language, our tests, our IDE.
- **Future fit.** Per-workspace self-hosted GitLab / GHE OAuth apps + Issue #1200 (Sign-in-with-Slack as secondary identity) fit Spring Security's `ClientRegistrationRepository` model better than Keycloak's per-realm IdP brokering. PR #1306's `Connection` aggregate is the natural home — one row per (workspace, kind, instance_key), sealed JSONB config, AAD-bound AES-GCM credentials.
- **No new external service.** No Spring Authorization Server, no Spring Session JDBC, no Redis. Stateless cookie-JWT issued by Hephaestus, revoked via a per-request `issued_jwt` check (negative-cached) — no cross-pod protocol needed.
- **Standards alignment.** OIDC ID-Token claim shape (`iss`, `sub`, `aud`, `iat`, `exp`, `jti`, `scope`, `act` for impersonation per RFC 8693). Public signing keys published at `/.well-known/jwks.json`; a full `/.well-known/openid-configuration` discovery document is deferred until a relying party needs it. If Issue #1200 ever needs third-party clients, Spring Authorization Server can be mounted as a second issuer alongside — zero resource-server changes required.

## Considered options

1. **Replace Keycloak with Spring Security 7 native auth (this ADR).** `oauth2Login` federates upstream, custom `AuthSuccessHandler` mints a short-lived ES256 cookie-JWT via Spring's `NimbusJwtEncoder` + DB-backed `JWKSource`. Workspace-scoped IdPs ride `Connection` rows of new `kind=OIDC_LOGIN_*`. Revocation via the `issued_jwt` table (per-request, negative-cached check). No Keycloak.

2. **Replace Keycloak with Spring Authorization Server.** Hephaestus becomes its own OAuth 2.1 / OIDC server. SAS handles `/oauth2/authorize`, `/token`, `/jwks`, federation to upstream. Cleaner long-term *if* we ever need third-party OAuth clients — but SAS docs explicitly treat the "BFF for own SPA" case as a legacy edge ([SAS issue #297](https://github.com/spring-projects/spring-authorization-server/issues/297)). SAS adds ~6 endpoints we don't need and forces a custom cookie wrapper around `/token` anyway. Net code we own is ~the same; attack surface is larger.

3. **Replace Keycloak with Spring Session JDBC + opaque sessions.** Reverses [ADR 0010](0010-outbound-oauth-state-handrolled.md)'s stateless posture, requires migrating ~2106 existing tests (most assume mock-JWT-decoder), and is dominated by Option 1 on every axis except "one fewer custom JWT issuer."

4. **Keep Keycloak but thin.** Use it only as an OIDC adapter, delete the protocol-mapper / `idp_link` mental load. Net: still one container, still two concepts of "user," still operational overhead. Not worth it.

## Decision

**Option 1**, implemented as a single PR stacked on PR #1306 (`principal-engineer-production-challenge`). Sixteen commits, each independently compiling, CI-gated as a stack. Key landings:

- **New module** `de.tum.cit.aet.hephaestus.core.auth.*` — Account, IdentityLink, AccountFeature, AuthEvent, IssuedJwt, JwtSigningKey + the JWT issuer (Spring's `NimbusJwtEncoder` + DB-backed `JWKSource`), revocation-aware decoder, OAuth success/failure handlers, impersonation, `/user` + `/auth/*` controllers, GDPR export + delete.
- **Connection extension** in `integration.core.connection` — new `IntegrationKind.OIDC_LOGIN_GITHUB`, `OIDC_LOGIN_GITLAB`, new `IntegrationFamily.IDENTITY`, new sealed `OidcLoginConfig` permit, new `OAuthClientSecret` credential bundle variant. Reuses the existing `CredentialBundleConverter` (AAD-bound AES-256-GCM per ADR 0014) for workspace OAuth-app secrets.
- **Data-model split.** `gitprovider.user.User` (a documented-as-such denormalized git-provider cache) is renamed to `gitprovider.actor.ExternalActor`; bots / orgs live there exclusively. The Hephaestus-native principal is `core.auth.domain.Account`. The federated-login association is `core.auth.domain.IdentityLink` per Issue #1200's spec (incl. `team_id`). `workspace_membership.user_id → account_id`.
- **JWT format = strict OIDC subset.** No proprietary claims. `iss=https://hephaestus.aet.cit.tum.de`, `sub=<account_id>`, `scope` encodes `app_role` + active feature flags space-delimited. `act` (RFC 8693) carries impersonator id. Public keys at `/.well-known/jwks.json`; full OIDC discovery deferred until a relying party needs it.
- **Stateless multi-pod.** Custom `CookieOAuth2AuthorizationRequestRepository` (AEAD-signed cookie binding workspace_id + returnTo + nonce + PKCE verifier, multi-flight LRU cap of 4). Spring's default would require a session — incompatible with the existing STATELESS filter chains.
- **Revocation.** Logout / refresh / sign-out-everywhere / admin-revoke set `issued_jwt.revoked_at`; `RevocationAwareJwtDecoder` does an indexed `jti` lookup on every request, so a revocation takes effect on every pod within DB visibility lag — no cross-pod protocol needed. The cache is a *negative* cache (REVOKED verdicts only, monotonic) that merely sheds replay load. Cross-pod NATS push (`auth.jwt.revoked`) is a tracked follow-up, not shipped.
- **Account linking — explicit re-login only.** Never by email. nOAuth (Descope 2023) defense at the lookup level: `IdentityLinkRepository.findByProviderAndSubject(...)` is the only path; `findByEmail` is forbidden by ArchUnit in any code reachable from a controller.
- **Impersonation.** `act`-claim JWT reissuance (no `SwitchUserFilter` — there is no session) + required `reason` + `ImpersonationGuard` write-block at HTTP layer. Audit row per begin/exit captures `(account_id=target, acting_account_id=impersonator)`.
- **GDPR.** 48-hour soft-delete cooldown → hard-delete cascades through `identity_link`, `oauth_authorized_client`, `account_feature`, `workspace_membership`, `issued_jwt`. `ExternalActor` is **pseudonymized**, not deleted (Art. 17(3) legitimate-interest — preserves PR-authorship history on other users' work). Art. 20 async export. Art. 30 monthly RANGE-partitioned `auth_event` log (12-month retention, managed by `pg_partman` — see [ADR 0018](0018-pg-partman-for-auth-event-partitioning.md)).
- **No Spring Session, no Redis, no SAS.** Discipline-of-no.

This ADR supersedes ADR 0016 Stage A only on the column placement and lookup mechanism. `User.keycloak_subject` is dropped; the lookup join key becomes `(identity_link.git_provider_id, identity_link.subject)`.

## Consequences

**Positive**

- Net production complexity decreases: one container, one realm JSON, one SDK, one JS library deleted. New code is all in-tree, in our language, debuggable via our existing tools.
- Single ubiquitous-language story for identity (Account / IdentityLink / ExternalActor / AuthEvent — see [`docs/auth-glossary.md`](../auth-glossary.md)).
- Per-workspace OAuth providers land on the established `Connection` aggregate — zero parallel infrastructure.
- nOAuth-class account-takeover ruled out structurally (lookup never touches email).
- GDPR posture strictly improves (audit log, cascade-delete, pseudonymize-on-Art-17, async export).
- Stateless-chain posture from ADR 0010 preserved end-to-end.

**Negative**

- We now own a JWT issuer, JWK rotation, and revocation propagation. Mitigation: implementation is a thin wrapper over Spring's `NimbusJwtEncoder` + DB-backed `JWKSource`; we are not hand-rolling crypto.
- Migration carries a data-shape change (`account` + `identity_link` populated from existing `user` rows). Idempotent Liquibase customChange + pre-migration assertion guards against orphans.
- Contributors must configure real GitHub/GitLab OAuth credentials for local dev. Same friction as Keycloak's realm-JSON seed users today; documented in `docs/contributor/local-development.mdx`.

**Reversible escape hatch**

- The cutover commit is identified by hash and intentionally `git revert`-able for 30 days post-launch. The revert restores the inline Keycloak compose service blocks and the resource-server chain in one step.

## Out of scope (named follow-up issues)

1. `act` claim propagation through NATS / async workers (HTTP write-block + ArchUnit guard is sufficient for v1).
2. Full service-principal auth path (`account.type='SERVICE'`; schema column shipped, behavior is its own design).
3. Synthetic deep-health endpoint with sandbox OAuth round-trip.
4. WebAuthn / passkeys (Spring Security 7 native; data model supports it via synthetic `git_provider`).
5. Full OpenTelemetry instrumentation.
6. OpenFeature SDK + flagd.
7. Spring Data Envers row-history on auth tables.
8. Transactional email + email verification + magic-link.
9. Master-key rotation game-day drill.
10. Art. 33/34 breach-notification runbook.
11. Turnstile / bot-traffic shaping.
12. SCIM 2.0 / SAML.

## Admin & account-management security-control decisions (2026 review)

These are settled, evidence-backed decisions — not open follow-ups. Each was pressure-tested against 2026 OSS/industry practice.

- **Last-admin / self-demotion lockout guard — IMPLEMENTED, race-safe.** `AccountService.adminSetRole` refuses (409) demoting the last `APP_ADMIN` or demoting yourself. The count uses `findByAppRoleAndStatusForUpdate(APP_ADMIN, ACTIVE)` — selecting the **entity** under `@Lock(PESSIMISTIC_WRITE)` so Hibernate actually emits `FOR UPDATE` (a scalar `SELECT a.id` would not lock — Hibernate [HHH-2676](https://hibernate.atlassian.net/browse/HHH-2676)), mirroring the proven `unlinkIdentity` guard. Filtering on `ACTIVE` ensures a `SUSPENDED`/`DELETING` admin (who cannot sign in) is not miscounted as the safety net. Concurrency is proven against real Postgres in `AccountAdminRoleIntegrationTest` (two simultaneous demotions → exactly one 409, role never drained to zero; the test fails if the lock is removed). Matches GitHub's "maintain ownership continuity"; Keycloak still lacks this guard ([keycloak#20015](https://github.com/keycloak/keycloak/issues/20015)).
- **Step-up / re-authentication on privileged role change — SUPERSEDED (2026-09) by a local recent-sign-in gate.** The assessment below still holds on assurance: our auth is OIDC-only with no local credential, so the gate is **not** a second factor, `prompt=login` would mostly re-assert the same IdP SSO session, and **OWASP ASVS 4.0 §3.7.1** is satisfied by a valid session alone. What changed is the price: Spring Security 7 carries the sign-in time as a `FactorGrantedAuthority` and compares it declaratively, so bounding the blast radius of a hijacked admin session costs one annotation per handler rather than a bespoke mechanism — and at that price a control that is cheap, reversible and auditable is worth having even where it is not required. High-risk instance-admin actions and identity linking now require a sign-in within `hephaestus.auth.step-up-max-age`; the refusal is a 403 carrying a `step_up_required` code rather than the **RFC 9470** `401` challenge, because the caller is a same-origin cookie SPA that would read a 401 as "signed out". The original citations still apply to what the gate is worth: ([ASVS 4.0 V3](https://github.com/OWASP/ASVS/blob/master/4.0/en/0x12-V3-Session-management.md), [NIST 800-63B](https://pages.nist.gov/800-63-3/sp800-63b.html), [GitHub sudo mode](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/sudo-mode), [RFC 9470](https://datatracker.ietf.org/doc/html/rfc9470)). Threat model and enforcement: [Instance administration](../contributor/instance-admin.md#recent-sign-in-gate).
- **Account deletion — no self-serve undo, by design.** Flow is type-to-confirm + `X-Confirm-Delete`=own id + immediate session revocation + status `DELETING` + 48h backend cooldown before `AccountHardDeleteSweeper` purges. Login is closed for non-`ACTIVE` accounts. We deliberately do **not** offer a self-serve "reactivate" screen: that would require re-opening the authentication gate for accounts mid-erasure — a one-directional security regression (takeover / coercion-reversal surface) for a rare, support-recoverable inconvenience. This matches GitHub's *actual* behavior (deletion is not self-serve reversible; recovery is a discretionary support action — [GitHub deletion](https://docs.github.com/en/account-and-profile/setting-up-and-managing-your-personal-account-on-github/managing-your-personal-account/deleting-your-personal-account)) and is more lenient than Stripe (irreversible — [Stripe](https://support.stripe.com/questions/close-a-stripe-account)). The 48h window remains backend/support-reversible. Any future self-serve undo must be an out-of-band, single-use, time-boxed email link — never a reopened login gate.
- **Session list "last-active" timestamp — DELIBERATELY OMITTED.** **OWASP ASVS 5.0 §7.5.2** requires only that users can *view and terminate* active sessions; the list already exceeds it (device, IP, issued-at, expires-at, current-session badge, per-session revoke, sign-out-everywhere). Auth is stateless JWT (`issued_jwt`); a last-active field means a throttled write on the authenticated hot path — write-amplification for a UX nicety (Google shows it; it is not a security control). If product later wants it, build it async/best-effort off the request path. ([ASVS 5.0 V7](https://github.com/OWASP/ASVS/tree/v5.0.0/5.0/en)).
- **Cookie-consent withdrawal — global footer link.** Withdrawal is a "Cookie preferences" control in the global footer (rendered for signed-in and signed-out visitors) that re-opens the consent banner and re-disables Sentry until a fresh decision. This is the **EDPB Cookie Banner Taskforce**-endorsed pattern ("a link placed on a visible and standardized place such as the footer"), satisfying GDPR Art. 7(3) "as easy to withdraw as to give." ([EDPB Cookie Banner Taskforce report](https://www.edpb.europa.eu/system/files/2023-01/edpb_20230118_report_cookie_banner_taskforce_en.pdf)).

## References

- [draft-ietf-oauth-browser-based-apps](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-browser-based-apps) — BFF posture for SPAs.
- [RFC 9700](https://datatracker.ietf.org/doc/rfc9700/) — OAuth 2.0 Security Best Current Practice.
- [RFC 9457](https://datatracker.ietf.org/doc/rfc9457/) — Problem Details for HTTP APIs.
- [RFC 8693](https://datatracker.ietf.org/doc/rfc8693/) — `act` claim for impersonation.
- [Spring Security PR #9208](https://github.com/spring-projects/spring-security/pull/9208) — `NimbusJwtEncoder` for self-issued JWTs.
- [Spring Authorization Server SPA guidance (issue #297)](https://github.com/spring-projects/spring-authorization-server/issues/297) — explicit framing that the BFF-for-own-SPA case is the legacy edge for SAS.
- [Descope — nOAuth (2023)](https://www.descope.com/blog/post/noauth) — account-takeover via mutable email claim; the threat model this ADR's `(provider, subject)` lookup discipline blocks.
The scope list, dependency pins and PR composition this decision was executed against are recorded in
the pull request that implemented it, not in a separate plan.
