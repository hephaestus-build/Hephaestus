package de.tum.cit.aet.hephaestus.core.auth.stepup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The recent-sign-in gate over the wire, against the real security chain — a genuine Hephaestus JWT
 * carrying (or missing) {@code auth_time}, decoded by the production decoder, converted into the
 * authorization-code factor authority the authorization rule reads.
 *
 * <p>The bearer path is deliberate: an API client that never loads the SPA must be refused identically,
 * so the gate cannot be a property of the dialog.
 */
@Sql(scripts = "/db/auth-event-sequence.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StepUpGateIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private HephaestusJwtIssuer jwtIssuer;

    @Autowired
    private JwtPrincipalFactory principalFactory;

    @Autowired
    private AuthEventRepository authEventRepository;

    @Autowired
    private AuthProperties authProperties;

    @Test
    void aStaleAdminIsAskedToConfirmAccessAndTheRoleIsUnchanged() {
        Account admin = persistAdmin("Stale Sam");
        Account victim = persistUser("Unchanged Ursula");
        long victimId = persistedId(victim.getId());

        webTestClient
                .patch()
                .uri("/admin/users/{id}", victimId)
                .headers(h -> h.setBearerAuth(tokenFor(admin, staleSignIn())))
                .bodyValue(Map.of("appRole", "APP_ADMIN"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo(StepUpRequiredException.CODE)
                .jsonPath("$.maxAgeSeconds")
                .isEqualTo(authProperties.stepUpMaxAge().toSeconds());

        assertThat(accountRepository.findById(victimId))
                .get()
                .extracting(Account::getAppRole)
                .isEqualTo(Account.AppRole.USER);
    }

    @Test
    void aRefusalIsRecordedAgainstTheAdminWhoAttemptedIt() {
        Account admin = persistAdmin("Audited Ada");
        Account victim = persistUser("Untouched Uma");
        long adminId = persistedId(admin.getId());

        webTestClient
                .patch()
                .uri("/admin/users/{id}", persistedId(victim.getId()))
                .headers(h -> h.setBearerAuth(tokenFor(admin, staleSignIn())))
                .bodyValue(Map.of("appRole", "APP_ADMIN"))
                .exchange()
                .expectStatus()
                .isForbidden();

        assertThat(authEventRepository.findByAccountSince(adminId, Instant.now().minus(Duration.ofMinutes(5))))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo(AuthEvent.EventType.APP_ROLE_CHANGED);
                    assertThat(event.getResult()).isEqualTo(AuthEvent.Result.FAILURE);
                    assertThat(event.getAccountId()).isEqualTo(adminId);
                    assertThat(event.getFailureReason()).isEqualTo(StepUpRequiredException.CODE);
                });
    }

    /** A session with no {@code auth_time} at all is not a session that signed in a moment ago. */
    @Test
    void aSessionWithoutARecordedSignInIsRefused() {
        Account admin = persistAdmin("Claimless Cleo");
        Account victim = persistUser("Safe Sofia");

        webTestClient
                .patch()
                .uri("/admin/users/{id}", persistedId(victim.getId()))
                .headers(h -> h.setBearerAuth(tokenFor(admin, null)))
                .bodyValue(Map.of("appRole", "APP_ADMIN"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo(StepUpRequiredException.CODE);
    }

    @Test
    void aFreshAdminChangesTheRole() {
        Account admin = persistAdmin("Fresh Fran");
        Account victim = persistUser("Promoted Pia");
        long victimId = persistedId(victim.getId());

        webTestClient
                .patch()
                .uri("/admin/users/{id}", victimId)
                .headers(h -> h.setBearerAuth(tokenFor(admin, Instant.now())))
                .bodyValue(Map.of("appRole", "APP_ADMIN"))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(accountRepository.findById(victimId))
                .get()
                .extracting(Account::getAppRole)
                .isEqualTo(Account.AppRole.APP_ADMIN);
    }

    /** An admin surface with a recorded exemption stays reachable on the same stale session. */
    @Test
    void anExemptAdminReadIsUnaffectedByAStaleSignIn() {
        Account admin = persistAdmin("Reading Rita");

        webTestClient
                .get()
                .uri("/admin/users")
                .headers(h -> h.setBearerAuth(tokenFor(admin, staleSignIn())))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private Instant staleSignIn() {
        return Instant.now().minus(authProperties.stepUpMaxAge()).minus(Duration.ofMinutes(1));
    }

    private String tokenFor(Account account, @Nullable Instant authTime) {
        return jwtIssuer
                .issue(principalFactory.forAccount(account), TokenConstraints.session(null, authTime), null)
                .value();
    }

    private Account persistAdmin(String displayName) {
        Account account = new Account(displayName);
        account.setAppRole(Account.AppRole.APP_ADMIN);
        account.setStatus(Account.Status.ACTIVE);
        return accountRepository.save(account);
    }

    private Account persistUser(String displayName) {
        Account account = new Account(displayName);
        account.setAppRole(Account.AppRole.USER);
        account.setStatus(Account.Status.ACTIVE);
        return accountRepository.save(account);
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }
}
