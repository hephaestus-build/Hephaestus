package de.tum.cit.aet.hephaestus.core.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventData;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AuthAuditControllerIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AuthEventRepository authEventRepository;

    @Autowired
    private HephaestusJwtIssuer jwtIssuer;

    @Test
    void plainUserIsForbidden() {
        Account user = persist("Plain User", Account.AppRole.USER);

        webTestClient
                .get()
                .uri("/admin/audit")
                .headers(h -> h.setBearerAuth(tokenFor(user)))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void adminSeesEventsNewestFirstWithAttribution() {
        Account admin = persist("Keeper Admin", Account.AppRole.APP_ADMIN);
        Account target = persist("Target", Account.AppRole.USER);
        // Older login, then a newer role change (acted by the admin on the target).
        seed(1L, AuthEvent.EventType.LOGIN, Instant.parse("2026-06-01T10:00:00Z"), persistedId(target.getId()), null);
        seed(
                2L,
                AuthEvent.EventType.APP_ROLE_CHANGED,
                Instant.parse("2026-06-02T10:00:00Z"),
                persistedId(target.getId()),
                persistedId(admin.getId()));

        webTestClient
                .get()
                .uri("/admin/audit")
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(2)
                // Newest first: the role change leads.
                .jsonPath("$.content[0].eventType")
                .isEqualTo("APP_ROLE_CHANGED")
                .jsonPath("$.content[0].accountId")
                .isEqualTo(persistedId(target.getId()))
                .jsonPath("$.content[0].actingAccountId")
                .isEqualTo(persistedId(admin.getId()))
                .jsonPath("$.content[1].eventType")
                .isEqualTo("LOGIN");
    }

    @Test
    void eventTypeFilterNarrowsResults() {
        Account admin = persist("Keeper Admin", Account.AppRole.APP_ADMIN);
        seed(1L, AuthEvent.EventType.LOGIN, Instant.parse("2026-06-01T10:00:00Z"), persistedId(admin.getId()), null);
        seed(
                2L,
                AuthEvent.EventType.APP_ROLE_CHANGED,
                Instant.parse("2026-06-02T10:00:00Z"),
                persistedId(admin.getId()),
                null);

        webTestClient
                .get()
                .uri(builder -> builder.path("/admin/audit")
                        .queryParam("eventType", "APP_ROLE_CHANGED")
                        .build())
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].eventType")
                .isEqualTo("APP_ROLE_CHANGED");
    }

    @Test
    void eventTypeFilterAcceptsSeveralValuesAtOnce() {
        Account admin = persist("Keeper Admin", Account.AppRole.APP_ADMIN);
        seed(1L, AuthEvent.EventType.LOGIN, Instant.parse("2026-06-01T10:00:00Z"), persistedId(admin.getId()), null);
        seed(2L, AuthEvent.EventType.LOGOUT, Instant.parse("2026-06-02T10:00:00Z"), persistedId(admin.getId()), null);
        seed(
                3L,
                AuthEvent.EventType.APP_ROLE_CHANGED,
                Instant.parse("2026-06-03T10:00:00Z"),
                persistedId(admin.getId()),
                null);

        // Two values on one dimension is a disjunction, not a last-one-wins overwrite. The
        // unfiltered
        // case pins the other half of the predicate: a null list must not degrade into `IN ()`,
        // which
        // Hibernate cannot bind — a break no single-value test would surface.
        webTestClient
                .get()
                .uri(builder -> builder.path("/admin/audit")
                        .queryParam("eventType", "LOGIN")
                        .queryParam("eventType", "LOGOUT")
                        .build())
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(2)
                .jsonPath("$.content[0].eventType")
                .isEqualTo("LOGOUT")
                .jsonPath("$.content[1].eventType")
                .isEqualTo("LOGIN");
    }

    @Test
    void resolvesAccountAndActorToHumanIdentities() {
        Account admin = persist("Keeper Admin", Account.AppRole.APP_ADMIN);
        Account target = persist("Target User", Account.AppRole.USER);
        seed(
                1L,
                AuthEvent.EventType.APP_ROLE_CHANGED,
                Instant.parse("2026-06-02T10:00:00Z"),
                persistedId(target.getId()),
                persistedId(admin.getId()));

        webTestClient
                .get()
                .uri("/admin/audit")
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].account.displayName")
                .isEqualTo("Target User")
                .jsonPath("$.content[0].actor.displayName")
                .isEqualTo("Keeper Admin");
    }

    @Test
    void resultFilterNarrowsToFailuresWithReason() {
        Account admin = persist("Keeper Admin", Account.AppRole.APP_ADMIN);
        seed(1L, AuthEvent.EventType.LOGIN, Instant.parse("2026-06-01T10:00:00Z"), persistedId(admin.getId()), null);
        seedFailure(2L, AuthEvent.EventType.LOGIN_FAILED, Instant.parse("2026-06-02T10:00:00Z"), "Email not verified");

        webTestClient
                .get()
                .uri(builder -> builder.path("/admin/audit")
                        .queryParam("result", "FAILURE")
                        .build())
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].result")
                .isEqualTo("FAILURE")
                .jsonPath("$.content[0].failureReason")
                .isEqualTo("Email not verified");
    }

    @Test
    void timeRangeFilterNarrowsToWindow() {
        Account admin = persist("Keeper Admin", Account.AppRole.APP_ADMIN);
        seed(1L, AuthEvent.EventType.LOGIN, Instant.parse("2026-06-01T10:00:00Z"), persistedId(admin.getId()), null);
        seed(2L, AuthEvent.EventType.LOGIN, Instant.parse("2026-06-05T10:00:00Z"), persistedId(admin.getId()), null);

        webTestClient
                .get()
                .uri(builder -> builder.path("/admin/audit")
                        .queryParam("from", "2026-06-04T00:00:00Z")
                        .queryParam("to", "2026-06-06T00:00:00Z")
                        .build())
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].occurredAt")
                .value(v -> assertThat((String) v).startsWith("2026-06-05"));
    }

    @Test
    void actorFilterReconstructsImpersonationSession() {
        Account admin = persist("Keeper Admin", Account.AppRole.APP_ADMIN);
        Account target = persist("Target", Account.AppRole.USER);
        // One self-login (no actor) + one acted-by-admin event.
        seed(1L, AuthEvent.EventType.LOGIN, Instant.parse("2026-06-01T10:00:00Z"), persistedId(target.getId()), null);
        seed(
                2L,
                AuthEvent.EventType.IMPERSONATION_BEGIN,
                Instant.parse("2026-06-02T10:00:00Z"),
                persistedId(target.getId()),
                persistedId(admin.getId()));

        webTestClient
                .get()
                .uri(builder -> builder.path("/admin/audit")
                        .queryParam("actingAccountId", persistedId(admin.getId()))
                        .build())
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].eventType")
                .isEqualTo("IMPERSONATION_BEGIN");
    }

    @Test
    void adminExportsCsvWithHeaderAndRows() {
        Account admin = persist("Keeper Admin", Account.AppRole.APP_ADMIN);
        seed(1L, AuthEvent.EventType.LOGIN, Instant.parse("2026-06-01T10:00:00Z"), persistedId(admin.getId()), null);

        String csv = webTestClient
                .get()
                .uri(builder -> builder.path("/admin/audit/export").build())
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .value("Content-Type", ct -> assertThat(ct).contains("text/csv"))
                .expectHeader()
                .value("Content-Disposition", cd -> assertThat(cd).contains("audit-log.csv"))
                .expectHeader()
                .valueEquals("X-Left-Out-Count", "0")
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(csv).isNotNull();
        assertThat(csv).startsWith("occurred_at_utc,event_type,result");
        assertThat(csv).contains("LOGIN").contains("Keeper Admin");
    }

    @Test
    void csvExportNeutralizesFormulaInjectionAndEscapesEmbeddedQuotes() {
        Account admin = persist("Keeper Admin", Account.AppRole.APP_ADMIN);
        // A failure reason that is BOTH a spreadsheet formula (leading '=') and carries an embedded
        // quote.
        seedFailure(
                1L,
                AuthEvent.EventType.LOGIN_FAILED,
                Instant.parse("2026-06-01T10:00:00Z"),
                "=cmd|' /C calc'!A1 \"x\"");

        String csv = webTestClient
                .get()
                .uri(builder -> builder.path("/admin/audit/export").build())
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(csv).isNotNull();
        // Formula trigger neutralized: the cell is quoted and the leading '=' is prefixed with an
        // apostrophe.
        assertThat(csv).contains("\"'=cmd");
        // RFC-4180: the embedded double-quote is doubled.
        assertThat(csv).contains("\"\"x\"\"");
    }

    @Test
    void pageSizeIsClampedToTheMaximum() {
        // MAX_PAGE_SIZE caps a malicious/typo'd size so it can't scan a whole monthly partition.
        Account admin = persist("Keeper Admin", Account.AppRole.APP_ADMIN);
        Instant start = Instant.parse("2026-06-01T10:00:00Z");
        for (long id = 1; id <= 201; id++) {
            seed(id, AuthEvent.EventType.LOGIN, start.plusSeconds(id), persistedId(admin.getId()), null);
        }

        webTestClient
                .get()
                .uri(builder -> builder.path("/admin/audit")
                        .queryParam("size", "100000")
                        .build())
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(200)
                .jsonPath("$.nextCursor")
                .exists();
    }

    @Test
    void pagesWalkTheLogOnceWhileNewEventsArrive() {
        Account admin = persist("Keeper Admin", Account.AppRole.APP_ADMIN);
        Long actor = persistedId(admin.getId());
        Instant tied = Instant.parse("2026-06-02T10:00:00Z");
        seed(1L, AuthEvent.EventType.LOGIN, Instant.parse("2026-06-01T10:00:00Z"), actor, null);
        seed(2L, AuthEvent.EventType.LOGIN, tied, actor, null);
        seed(3L, AuthEvent.EventType.LOGOUT, tied, actor, null);

        var first = auditPage(admin, null);
        assertThat(first.at("/content")
                        .valueStream()
                        .map(e -> e.get("eventType").asString()))
                .containsExactly("LOGOUT", "LOGIN");
        String cursor = first.get("nextCursor").asString();

        // An offset read would now repeat the event that the new one pushed across the page edge.
        seed(4L, AuthEvent.EventType.LOGIN, Instant.parse("2026-06-03T10:00:00Z"), actor, null);

        var second = auditPage(admin, cursor);
        assertThat(second.at("/content")
                        .valueStream()
                        .map(e -> e.get("occurredAt").asString()))
                .containsExactly("2026-06-01T10:00:00Z");
        assertThat(second.has("nextCursor")).isFalse();
    }

    @Test
    void countAnswersForTheFilterAndAMalformedCursorIsRefused() {
        Account admin = persist("Keeper Admin", Account.AppRole.APP_ADMIN);
        Long actor = persistedId(admin.getId());
        seed(1L, AuthEvent.EventType.LOGIN, Instant.parse("2026-06-01T10:00:00Z"), actor, null);
        seed(2L, AuthEvent.EventType.LOGOUT, Instant.parse("2026-06-02T10:00:00Z"), actor, null);

        webTestClient
                .get()
                .uri(builder -> builder.path("/admin/audit/count")
                        .queryParam("eventType", "LOGOUT")
                        .build())
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1);

        webTestClient
                .get()
                .uri(builder -> builder.path("/admin/audit")
                        .queryParam("cursor", "not-a-cursor")
                        .build())
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    private JsonNode auditPage(Account admin, @Nullable String cursor) {
        String body = webTestClient
                .get()
                .uri(builder -> {
                    builder.path("/admin/audit").queryParam("size", "2");
                    return (cursor == null ? builder : builder.queryParam("cursor", cursor)).build();
                })
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        return new JsonMapper().readTree(body);
    }

    @Test
    void plainUserCannotExport() {
        Account user = persist("Plain User", Account.AppRole.USER);

        webTestClient
                .get()
                .uri("/admin/audit/export")
                .headers(h -> h.setBearerAuth(tokenFor(user)))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    private void seedFailure(long id, AuthEvent.EventType type, Instant occurredAt, String failureReason) {
        AuthEventData data = new AuthEventData(
                type, AuthEvent.Result.FAILURE, null, null, null, null, failureReason, null, null, null, null, false);
        authEventRepository.save(AuthEvent.create(data, id, occurredAt, "127.0.0.1", "test-agent"));
    }

    private void seed(
            long id,
            AuthEvent.EventType type,
            Instant occurredAt,
            @Nullable Long accountId,
            @Nullable Long actingAccountId) {
        AuthEventData data = new AuthEventData(
                type,
                AuthEvent.Result.SUCCESS,
                accountId,
                null,
                null,
                actingAccountId,
                null,
                null,
                null,
                null,
                null,
                false);
        authEventRepository.save(AuthEvent.create(data, id, occurredAt, "127.0.0.1", "test-agent"));
    }

    private Account persist(String displayName, Account.AppRole role) {
        Account account = new Account(displayName);
        account.setAppRole(role);
        account.setStatus(Account.Status.ACTIVE);
        return accountRepository.save(account);
    }

    private String tokenFor(Account account) {
        return jwtIssuer
                .issue(Objects.requireNonNull(account.getId()), TokenConstraints.session(null, Instant.now()), null)
                .value();
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }
}
