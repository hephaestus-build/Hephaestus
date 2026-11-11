package de.tum.cit.aet.hephaestus.core.auth.jwt;

import java.util.Set;

/**
 * The data {@link HephaestusJwtIssuer} bakes into a Hephaestus access token. Built by
 * {@link JwtPrincipalFactory} from an {@code Account} and its feature flags.
 *
 * @param accountId Hephaestus account id → {@code sub}
 * @param roles     app roles ({@code "app_admin"} when app-admin, plus enabled feature-flag
 *                  keys) → {@code roles}, read by the authority converter and every
 *                  {@code @PreAuthorize(hasAuthority(...))}
 */
public record JwtPrincipal(Long accountId, Set<String> roles) {}
