/**
 * Auth — Hephaestus-native authentication and identity.
 *
 * <p>Replaces the prior Keycloak federating-IdP setup with Spring Security 7 native auth:
 * {@code oauth2Login} federates to upstream IdPs (the instance-scoped {@code login_provider}
 * rows — GitHub, GitLab.com, self-hosted GitLab — see
 * {@link de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider}); on success we mint our own
 * short-lived ES256 cookie-JWT via Spring's {@code NimbusJwtEncoder} + a DB-backed {@code JWKSource}.
 *
 * <h2>Ubiquitous language</h2>
 * <ul>
 *   <li><b>{@link de.tum.cit.aet.hephaestus.core.auth.domain.Account Account}</b> — the
 *       Hephaestus-native principal. One row per human who has signed in. Holds
 *       {@code app_role}, contact email, preferences. <em>Not</em> the same as the
 *       git-provider actor mirror ({@code integration.scm.domain.user.User}).</li>
 *   <li><b>{@link de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink IdentityLink}</b>
 *       — federated-login association per Issue #1200's spec; includes {@code team_id}
 *       for future Slack identities. Unique on {@code (git_provider_id, subject, team_id)}.
 *       Lookup is <em>always</em> by {@code (provider, subject)}, never email (nOAuth defense).
 *       Mirrors to the SCM actor via the optional {@code external_actor_id} FK.</li>
 *   <li><b>{@link de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent AuthEvent}</b>
 *       — append-only authentication and privileged-access event. Monthly RANGE-partitioned on
 *       {@code occurred_at}, managed by pg_partman (run_maintenance_proc via AuthEventPartitionMaintenance)
 *       (create-ahead + 12-month retention) on stock Postgres — no {@code pg_partman}.</li>
 * </ul>
 *
 * <h2>JWT format</h2>
 * Claims: {@code iss}, {@code sub}, {@code aud}, {@code jti}, {@code iat}, {@code exp},
 * {@code preferred_username} (standard OIDC), {@code roles} (flat string array,
 * Hephaestus-specific — read by the authority converter), {@code given_name} (when known),
 * and session deadlines. Signed ES256 with a DB-backed
 * {@code JWKSource}; the public keys are published at {@code /.well-known/jwks.json}. See
 * {@code HephaestusJwtIssuer} for the authoritative shape.
 *
 * <h2>Module boundaries</h2>
 * <ul>
 *   <li>Cross-module consumers reach {@code core.auth} only through the {@code core.auth.spi}
 *       named interface ({@code auth-spi}) — never {@code domain}. {@code workspace},
 *       {@code notification}, and {@code integration.*} all depend on {@code spi} bean ports
 *       ({@code AccountRoleQuery}, {@code AccountIdentityQuery}, {@code AccountPreferencesQuery},
 *       {@code AccountWorkspaceMembershipQuery}).</li>
 *   <li>{@code core.auth} does <em>not</em> depend on {@code workspace} or {@code integration.*}
 *       directly. Login {@code ClientRegistration}s are built from the {@code core.auth.provider}
 *       store and exposed via the {@code IdentityProviderCatalog}/{@code GitProviderRegistry} SPI
 *       ports implemented in {@code integration.identity.connect}.</li>
 *   <li>ArchUnit ({@code NoKeycloakImportTest}) forbids any {@code org.keycloak.*} import.
 *       {@code com.auth0:java-jwt} is intentionally retained (worker-control-channel + GitHub App
 *       token JWTs) and is NOT banned.</li>
 * </ul>
 *
 * <p>See ADR 0017 for the full rationale.
 *
 * <p><b>Modulith:</b> {@code core.auth} is part of the {@code core} application module (like
 * {@code core.security}, {@code core.tenancy}); it is not a nested module. Cross-module
 * consumers reach it only through the {@code core.auth.spi} named interface ({@code auth-spi}).
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.core.auth;
