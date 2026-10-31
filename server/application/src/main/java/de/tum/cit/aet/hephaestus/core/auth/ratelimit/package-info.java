/**
 * Auth rate limiting (ADR 0017 hardening) — Bucket4j token-bucket filter for the hot auth
 * endpoints, plus its PostgreSQL-backed (cluster-shared) storage wiring.
 *
 * <p>Exposed as a named interface because the root {@code SecurityConfig} registers the
 * {@link de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitFilter} on the resource-server
 * filter chain (the oauth2Login chain in {@code core.auth.config} wires it intra-module).
 */
@org.springframework.modulith.NamedInterface("auth-ratelimit")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.core.auth.ratelimit;
