package de.tum.cit.aet.hephaestus.core.auth;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.RevocationAwareJwtDecoder;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * What bounds an impersonation, over the wire and against a real database: the absolute ceiling
 * survives a rotation unchanged, the rotation itself is where the impersonation ends, and the
 * operator's own session bounds both.
 */
@Sql(scripts = "/db/auth-event-sequence.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ImpersonationLifecycleIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AuthEventRepository authEventRepository;

    @Autowired
    private HephaestusJwtIssuer jwtIssuer;

    @Autowired
    private JwtPrincipalFactory principalFactory;

    @Autowired
    private IssuedJwtRepository issuedJwts;

    @Autowired
    private RevocationAwareJwtDecoder jwtDecoder;

    @Test
    void aRotationWithinTheTimeBoxKeepsEveryDeadlineItStartedWith() {
        Account operator = persist("Operator", Account.AppRole.APP_ADMIN);
        Account target = persist("Target", Account.AppRole.USER);
        Instant sessionCeiling = Instant.now().plus(Duration.ofHours(12)).truncatedTo(ChronoUnit.SECONDS);

        String impersonationToken = beginImpersonation(operatorToken(operator, sessionCeiling), id(target));
        Jwt claims = jwtDecoder.decode(impersonationToken);
        assertThat(claims.getSubject()).isEqualTo(String.valueOf(id(target)));
        assertThat(claims.getClaimAsMap("act")).containsEntry("sub", String.valueOf(id(operator)));
        long impExp = requireNonNull(claims.<Long>getClaim("imp_exp"));
        assertThat(claims.<Long>getClaim("session_exp")).isEqualTo(sessionCeiling.getEpochSecond());

        Jwt rotated = jwtDecoder.decode(refresh(impersonationToken));
        assertThat(rotated.getSubject()).isEqualTo(String.valueOf(id(target)));
        assertThat(rotated.getClaimAsMap("act")).containsEntry("sub", String.valueOf(id(operator)));
        assertThat(rotated.<Long>getClaim("imp_exp"))
                .as("imp_exp is absolute: a rotation re-caps at it rather than extending it")
                .isEqualTo(impExp);
        assertThat(rotated.<Long>getClaim("session_exp")).isEqualTo(sessionCeiling.getEpochSecond());

        // The wire deadline and the database's active-token check must agree to the second, or a token
        // the browser has already stopped sending would still read as active.
        Instant wireExpiry = requireNonNull(rotated.getExpiresAt());
        assertThat(wireExpiry).isBeforeOrEqualTo(Instant.ofEpochSecond(impExp));
        UUID jti = UUID.fromString(rotated.getId());
        assertThat(issuedJwts.findActive(jti, wireExpiry.minusMillis(1))).isPresent();
        assertThat(issuedJwts.findActive(jti, wireExpiry)).isEmpty();
    }

    @Test
    void aRotationNearTheCeilingReturnsTheOperatorToTheirOwnSession() {
        Account operator = persist("Operator", Account.AppRole.APP_ADMIN);
        Account target = persist("Target", Account.AppRole.USER);
        Instant sessionCeiling = Instant.now().plus(Duration.ofHours(12)).truncatedTo(ChronoUnit.SECONDS);
        // Inside the exit skew: a token minted at this ceiling would be born expired, so the rotation
        // must end the impersonation instead of renewing it.
        Instant nearCeiling = Instant.now().plus(Duration.ofSeconds(45)).truncatedTo(ChronoUnit.SECONDS);
        String impersonationToken = jwtIssuer
                .issue(principalFactory.forAccount(target), id(operator), nearCeiling, sessionCeiling, null)
                .value();

        Jwt exited = jwtDecoder.decode(refresh(impersonationToken));
        assertThat(exited.getSubject()).isEqualTo(String.valueOf(id(operator)));
        assertThat(exited.hasClaim("act")).isFalse();
        assertThat(exited.hasClaim("imp_exp")).isFalse();
        assertThat(exited.<Long>getClaim("session_exp")).isEqualTo(sessionCeiling.getEpochSecond());

        assertThat(impersonationEndsFor(target)).singleElement().satisfies(end -> {
            assertThat(end.getActingAccountId()).isEqualTo(id(operator));
            assertThat(end.getDetails()).contains("EXPIRED");
        });
    }

    @Test
    void promotingTheTargetMidSessionEndsTheImpersonationOnTheNextRotation() {
        Account operator = persist("Operator", Account.AppRole.APP_ADMIN);
        Account target = persist("Target", Account.AppRole.USER);
        Instant sessionCeiling = Instant.now().plus(Duration.ofHours(12));
        String impersonationToken = beginImpersonation(operatorToken(operator, sessionCeiling), id(target));

        target.setAppRole(Account.AppRole.APP_ADMIN);
        accountRepository.save(target);

        Jwt exited = jwtDecoder.decode(refresh(impersonationToken));
        assertThat(exited.getSubject()).isEqualTo(String.valueOf(id(operator)));
        assertThat(exited.hasClaim("act"))
                .as("begin refuses admin-to-admin impersonation; a rotation is not a way around it")
                .isFalse();

        assertThat(impersonationEndsFor(target))
                .singleElement()
                .satisfies(end -> assertThat(end.getDetails()).contains("TARGET_PROMOTED"));
    }

    /**
     * A token that carries no absolute ceiling predates the ceiling. Returning from it would hand back
     * an operator session with nothing bounding it, so it is refused rather than renewed — the same
     * fail-safe the rotation applies.
     */
    @Test
    void anImpersonationWithNoSessionCeilingIsRefusedRatherThanReturnedFrom() {
        Account operator = persist("Operator", Account.AppRole.APP_ADMIN);
        Account target = persist("Target", Account.AppRole.USER);
        String uncappedImpersonation = jwtIssuer
                .issue(
                        principalFactory.forAccount(target),
                        id(operator),
                        Instant.now().plus(Duration.ofMinutes(30)),
                        null,
                        null)
                .value();

        webTestClient
                .post()
                .uri("/auth/impersonate:exit")
                .headers(h -> h.setBearerAuth(uncappedImpersonation))
                .exchange()
                .expectStatus()
                .isUnauthorized();

        assertThat(impersonationEndsFor(target)).isEmpty();
    }

    private List<AuthEvent> impersonationEndsFor(Account target) {
        return authEventRepository.findByAccountSince(id(target), Instant.now().minus(1, ChronoUnit.HOURS)).stream()
                .filter(e -> e.getEventType() == AuthEvent.EventType.IMPERSONATION_END)
                .toList();
    }

    private String operatorToken(Account operator, Instant sessionExpiresAt) {
        return jwtIssuer
                .issue(principalFactory.forAccount(operator), null, null, sessionExpiresAt, null)
                .value();
    }

    private Account persist(String name, Account.AppRole role) {
        Account account = new Account(name);
        account.setAppRole(role);
        account.setStatus(Account.Status.ACTIVE);
        return accountRepository.save(account);
    }

    private static long id(Account account) {
        return requireNonNull(account.getId());
    }

    private String beginImpersonation(String operatorToken, long targetAccountId) {
        var result = webTestClient
                .post()
                .uri("/auth/impersonate")
                .headers(h -> h.setBearerAuth(operatorToken))
                .bodyValue(Map.of("targetAccountId", targetAccountId, "reason", "integration-test"))
                .exchange()
                .expectStatus()
                .isNoContent()
                .returnResult(Void.class);
        ResponseCookie cookie = result.getResponseCookies().getFirst(AuthProperties.DEFAULT_COOKIE_NAME);
        assertThat(cookie)
                .as("impersonate must Set-Cookie the impersonation token")
                .isNotNull();
        return cookie.getValue();
    }

    private String refresh(String token) {
        var result = webTestClient
                .post()
                .uri("/auth/refresh")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isNoContent()
                .returnResult(Void.class);
        ResponseCookie rotated = result.getResponseCookies().getFirst(AuthProperties.DEFAULT_COOKIE_NAME);
        assertThat(rotated).as("refresh must Set-Cookie a rotated token").isNotNull();
        return rotated.getValue();
    }
}
