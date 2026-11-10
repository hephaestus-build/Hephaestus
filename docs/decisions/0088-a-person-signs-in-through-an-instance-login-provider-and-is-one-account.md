# ADR 0088: A person signs in through an instance login provider and is one account in every workspace

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md), [ADR 0019](0019-workspace-membership-keyed-on-account.md)

**Builds on:** [ADR 0056](0056-one-practice-model-for-items-and-scans.md), [ADR 0070](0070-1-0-is-a-cutover-release.md)

**Relates to:** [ADR 0048](0048-installed-clients-sign-in-with-a-pkce-handoff.md), [ADR 0053](0053-workspace-addresses-are-a-presentation-origin.md), [ADR 0018](0018-pg-partman-for-auth-event-partitioning.md), [ADR 0010](0010-outbound-oauth-state-handrolled.md)

## Context

ADR 0017 removed Keycloak. It then collected seven amendments. The first one replaced the login model of its own Decision.
Its Decision still names `identity_link`, `workspace_membership`, a `scope` claim and an ArchUnit rule that does not exist.
ADR 0019 still says Proposed and "not implemented", but the practice model cutover ([ADR 0056](0056-one-practice-model-for-items-and-scans.md)) shipped it.
ADR 0016 is superseded, and its last update still names the SCM `user` as the membership key.

This ADR states sign-in and identity at 1.0 as rules.

[Auth architecture](../auth-architecture.md) owns the mechanics, and [the auth glossary](../auth-glossary.md) owns the terms and the claims.

1.0 is a cutover ([ADR 0070](0070-1-0-is-a-cutover-release.md)).
A rule that main does not satisfy yet says "1.0 target" and names the pull request that delivers it.

## Decision drivers

- One principal for each person, in every workspace and behind every provider.
- An attacker who controls an email address must not get an account.
- A revocation takes effect on every replica without a cross-replica protocol.
- No identity server to run beside Hephaestus.
- Each irreversible step has a reason that a reviewer can check.

## Considered options

1. **Keep Keycloak, but thin.** It keeps a container, a realm export and a second concept of a user.
2. **Spring Authorization Server.** Hephaestus becomes a full OAuth server. It adds endpoints that no first-party client needs, and the browser still needs a cookie wrapper.
3. **Spring Session JDBC with an opaque cookie.** Every request already reads `issued_jwt`, so the state is server-side either way. It costs a rewrite of the session tests and of the installed-client path.
4. **Spring Security `oauth2Login` and our own cookie JWT, revoked in PostgreSQL (chosen).** We own an issuer, a key set and a rotation contract.

## Decision

1. **Sign-in uses instance login providers.** Each provider is one `login_provider` row for each `(type, base_url)`. GitHub and GitLab can sign in. Slack and Outline only link to a signed-in account. A workspace `Connection` is a data source, never a sign-in.
   Enforced by: `AuthBeginControllerLinkTest#shouldRejectLoginModeWhenTheProviderIsLinkOnly`, `HephaestusAuthSuccessHandlerTest#linkOnlyProviderLoginRedirectsToAuthErrorWithoutCookie`, `LoginProviderServiceTest#deleteRefusesTheLastSignInProviderEvenWhenALinkOnlyProviderRemains` and `uq_login_provider_type_base_url`.
2. **Hephaestus is its own issuer.** There is no Keycloak and no Spring Authorization Server. The public keys are at `/.well-known/jwks.json`. There is no OIDC discovery document.
   Enforced by: `NoKeycloakImportTest`. Spring Authorization Server: not enforced. A new Gradle dependency shows in the lockfile in review.
3. **The `account` is the principal.** The `sub` claim is the account id. Roster sync can create an unclaimed account, and sign-in claims it, as [ADR 0056](0056-one-practice-model-for-items-and-scans.md) decides.
4. **An `identity` row is the join key, never email.** It keys an account to `(provider_id, provider_user_key, team_key)`. An enabled key is unique. Email is contact data only. A link needs a signed-in account and a recent sign-in.
   Enforced by: `uk_identity_provider_key`, `AccountProvisioningIntegrationTest#twoIdentitiesSharingOneEmailResolveToSeparateAccounts` and `AuthBeginControllerLinkTest#link_withStaleSignIn_asksToConfirmAccessAndWritesNoIntent`. No ArchUnit rule bans an email lookup. `AccountRepository.findByPrimaryEmail` serves dev login, which the `prod` profile refuses (`DevLoginServiceTest#enabledUnderProd_failsClosedAtConstruction`). Review catches a new caller.
5. **The browser holds one `__Host-HEPHAESTUS_AT` cookie with an ES256 JWT.** The cookie is HttpOnly, Secure and `SameSite=Lax`. There is no HTTP session. A cookie request that changes state needs the CSRF token. [Session deadlines](../admin/configuration-readiness.mdx#session-deadlines) give the lifetimes.
   Enforced by: `CookieAuthenticationIntegrationTest#shouldRejectCookieAuthenticatedMutationWhenCsrfTokenIsMissing`, `CsrfProtectionIntegrationTest` and `AuthPropertiesTest#shouldRejectTheHostPrefixWhenCookiesAreInsecure`.
6. **Every token has an `issued_jwt` row.** The decoder checks the `jti` on every request and fails closed when PostgreSQL fails. It refuses an `act` claim. [The rotation contract](../auth-architecture.md#rotation-contract) owns renewal.
   Enforced by: `RevocationAwareJwtDecoderTest#rejectsRevokedJti`, `#failsClosedWhenRevocationStoreErrors`, `#shouldRejectADelegatedTokenWhenItsSignatureIsStillValid` and `#rejectsAlgNoneToken`.
7. **The claims are the ones in [the glossary claim table](../auth-glossary.md#jwt-claim-shape).** `HephaestusJwtIssuer` emits them. Only `core.auth.jwt` reads the raw token value.
   Not enforced. No test pins the claim set, and no ArchUnit rule restricts `Jwt.getTokenValue()`. Review compares a new claim with the table.
8. **One OAuth2 authorization request for each browser.** It is in the AES-GCM-sealed `__Host-OAUTH_STATE` cookie for 10 minutes, with an S256 PKCE verifier. A new sign-in replaces it. Production refuses to start without the key.
   Enforced by: `CookieOAuth2AuthorizationRequestRepositoryTest#rejectsTamperedCiphertext`, `AuthSecurityConfigTest#pkceResolverEmitsCodeChallengeForConfidentialGithubClient` and `#blankKeyInProdFailsClosed`.
9. **Installed clients** sign in through a PKCE handoff, as [ADR 0048](0048-installed-clients-sign-in-with-a-pkce-handoff.md) decides.
10. **Workspace addresses** are a presentation origin, as [ADR 0053](0053-workspace-addresses-are-a-presentation-origin.md) decides.
11. **Membership is keyed on the account.** The key is `membership_pkey (account_id, workspace_id)`, with a foreign key to `account`. An account with no provider identity can be a member. The role is read from the database on each request. An instance admin who is not a member enters as ADMIN, never as OWNER, and the entry is audited.
    Enforced by: `WorkspaceContextFilterIntegrationTest#shouldAdmitMemberWhenSignedInAccountHasNoLinkedIdentity`, `#instanceSuperAdminWithoutMembershipIsElevatedToAdmin` and `PasskeyLifecycleIntegrationTest#shouldRequireInstanceElevationProofWithoutGrantingWorkspaceOwnership`.
12. **No auto-seeding. A membership is created explicitly.** No visit and no first sign-in creates a membership.
    1.0 target: on main, `MembershipAutoSeeder` is on in the base configuration and off only in `prod`. [#4111](https://github.com/hephaestus-build/Hephaestus/pull/4111) deletes it.
    Enforced by: `WorkspaceContextFilterIntegrationTest#autoSeedDisabledDoesNotGrantAdminToFirstVisitorOfEmptyMembershipWorkspace`. After #4111, the seeder does not exist.
13. **Admin work needs a passkey.** In production, instance administration needs one by default. A workspace owner can require one for workspace administration. Each challenge is a single-use PostgreSQL row. Recovery replaces credentials and grants no admin access. [Passkeys](../admin/passkeys.mdx) owns the procedure.
    Enforced by: `PasskeyLifecycleIntegrationTest#shouldDenyOAuthOnlyInstanceAdminWhileKeepingEnrollmentAvailable`, `#shouldConsumeEnrollmentChallengeEvenWhenCredentialIsInvalid`, `#shouldRestrictRecoveryAndConsumeCodesOnce` and `AdminPasskeyScopeArchitectureTest`.
14. **High-risk instance-admin actions and identity links need a recent sign-in.** The window is `hephaestus.auth.step-up-max-age`. A refusal is a 403 with `step_up_required`. [The recent sign-in gate](../contributor/instance-admin.md#recent-sign-in-gate) has the threat model.
    1.0 target: a change to or from OWNER also asks for it in [#4083](https://github.com/hephaestus-build/Hephaestus/pull/4083).
    Enforced by: `RecentSignInByDefaultArchTest` and `StepUpGateIntegrationTest#aStaleAdminIsAskedToConfirmAccessAndTheRoleIsUnchanged`.
15. **The last active instance admin stays.** Nobody demotes the last one or themselves. A demotion revokes the sessions of that account.
    Enforced by: `AccountAdminRoleIntegrationTest#concurrentDemotionsCannotDrainTheRoleToZero`, `#cannotRevokeYourOwnAdminEvenWhenAnotherAdminExists` and `AccountServiceTest#demotingAnotherAdminSucceedsWhileMoreAdminsRemain`.
16. **Nobody acts as another account.** An instance admin reads a member's private pages through [read-only user views](../contributor/instance-admin.md#read-only-user-views). Each view is audited.
    Enforced by: rule 6 and `UserViewIntegrationTest#shouldRefuseToDeleteTheMessageAndRecordNothingWhenAnAdminViewsTheUser`.
17. **Deleting your account erases you.** The request revokes every session at once and sets `DELETING`. After `hephaestus.auth.delete-cooldown` (48 hours), `AccountPurger` runs `PersonDataService.eraseForAccount`. This is the full person erasure: results, feedback, memberships and identities go. The `account` row stays as a `DELETED` tombstone. There is no self-service undo.
    Enforced by: `AccountServiceTest#softDeleteMarksDeletingRevokesAllSessionsAndAuditsAccountDeleted`, `AccountHardDeleteSweeperIntegrationTest#sweepNow_pastCooldown_purgesChildrenClearsPiiAndFlipsToDeleted` and `AccountErasureIntegrationTest#shouldEraseAccountChoicesAndDerivedProofBeforeMembershipWhenTheAccountDeletesItself`.
18. **The `auth_event` log** is partitioned as [ADR 0018](0018-pg-partman-for-auth-event-partitioning.md) decides.

## What changed, and why

- **Instance login providers, not workspace `Connection` rows.** ADR 0017 chose `OIDC_LOGIN_*` connections and reversed it in its first update. Sign-in is an instance concern, and a workspace connection is a data source.
- **Claims follow the gates, not a strict OIDC subset.** The recent sign-in gate reads `auth_time`. The passkey gate reads `passkey_time`. Installed clients need `sid` (ADR 0048). Roles are in `roles`, and there is no `scope` claim.
- **One authorization request for each browser,** not "a multi-flight LRU cap of 4" (`CookieOAuth2AuthorizationRequestRepository`).
- **Membership is keyed on the account** (ADR 0019, shipped with ADR 0056). The tables are `membership` and `identity`, not `workspace_membership` and `identity_link`. ADR 0019's nullable `external_actor_id` did not ship. `hidden`, `review_selected` and `left_at` are on the account-keyed row.
- **Account deletion runs the full person erasure.** The update of 2026-09-17 said the sweeper leaves memberships. Main removes memberships, results and feedback.
- **Passkeys and a recent sign-in gate** replace the deferral of local MFA and the rejection of step-up.
- **Impersonation and the `act` claim are gone** (update of 2026-09-11). The 30-day Keycloak revert window and the NATS revocation push are gone too.
- **The email ban and the token-value ban are review rules.** ADR 0017 and the glossary called them ArchUnit rules, but no such rule exists.

## Consequences

- Good: one account for each person across providers and workspaces. A member needs no provider identity.
- Good: a matching email never merges two accounts, which closes the nOAuth class of takeover.
- Good: a revocation holds on every replica within database visibility lag. No cache can keep a revoked token alive.
- Good: no identity server to deploy, upgrade or back up.
- Bad: we own a JWT issuer, a signing key set and a rotation contract. The JWT is not stateless, because every request reads `issued_jwt`.
- Bad: the cookie JWT is signed, not encrypted. It carries the login and the given name in clear text.
- Bad: no inactivity bound. The access token lives 24 hours and renews. The session ends after 7 days.
- Bad: one passkey verification covers non-sensitive admin reads for the rest of the session.
- Bad: account deletion and "sign out other sessions" need no recent sign-in.
- Bad: a deletion has no undo during the cooldown. Erasure waits while feedback copies on a provider await an operator's confirmation of removal.

## Confirmation

Each rule above names its test, constraint or gate.
Rules 2, 4 and 7 have parts that no test checks. Review catches them, as each rule says.

## Revisit trigger

- The browser session needs more state than `issued_jwt` holds, or the clear-text claims must go. Then price Spring Session JDBC again (option 3).
- A third-party client needs to sign in to Hephaestus. Then mount Spring Authorization Server as a second issuer.
- An operator needs sign-in through SAML or another provider that has no stable subject.

## Sources

- OpenID Foundation, [OpenID Connect Core § 5.7](https://openid.net/specs/openid-connect-core-1_0.html#ClaimStability): only `sub` and `iss` together are a stable identifier. Email "MUST NOT be used" as one. This is rule 4.
- IETF, [RFC 10017 § 6.1](https://www.rfc-editor.org/rfc/rfc10017.html): with a BFF, "there are no tokens available to extract from the browser". The cookie "MUST" be `Secure` and `HttpOnly`. Main differs on two points: `SameSite=Lax` where the RFC says `Strict` "SHOULD", and contents that are signed but not encrypted. No decision chose either point, so the verification file lists them as gaps.
- OWASP, [JSON Web Token Cheat Sheet § Not using JWTs](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_Cheat_Sheet.html): with a deny list, user sessions are no longer completely stateless. It says "consider using a plain session system". This is why option 3 stays open (Revisit trigger).

## Already priced and declined

Each line links the section that priced the option.

- [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md#considered-options): Spring Authorization Server, Spring Session JDBC with opaque sessions, and Keycloak kept thin.
- [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md#decision): sign-in through `OIDC_LOGIN_*` workspace connections (reversed by its first update).
- [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md#admin--account-management-security-control-decisions-2026-review): a self-service undo of deletion through a reopened sign-in, and a "last active" time in the session list.
- [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md#admin--account-management-security-control-decisions-2026-review): an RFC 9470 401 challenge for step-up.
- [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md#update--2026-09-11): impersonation through the `act` claim, and Spring Security's `SwitchUserFilter`.
- [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md#update--2026-09-24): separate user-view endpoints, and a view session on the server.
- [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md#admin-passkey-protection): servlet sessions for passkey challenges, and a synthetic provider identity for passkeys.
- [ADR 0048](0048-installed-clients-sign-in-with-a-pkce-handoff.md#considered-options): the options for installed clients.
- [ADR 0019](0019-workspace-membership-keyed-on-account.md#context): membership keyed on the SCM `user`, and an auto-heal that makes the first visitor ADMIN.
- [ADR 0016](0016-unified-identity-keycloak-as-truth.md#considered-options): a wired `integration.identity` layer, and `keycloak_subject` on the SCM `user`.
