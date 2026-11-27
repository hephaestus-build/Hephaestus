package de.tum.cit.aet.hephaestus.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExport;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExportRepository;
import de.tum.cit.aet.hephaestus.core.auth.export.ExportBundleAssembler;
import de.tum.cit.aet.hephaestus.core.auth.export.ExportGenerationWorker;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PersonalGoalIntegrationTest extends RealAuthIntegrationTest {
    private static final String GOAL = "/user/personal-goal";
    private static final String WORDS = "Give more specific review feedback.";
    private static final String EDIT = "Explain the reason for one review comment.";

    @Autowired
    private WebTestClient client;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private HephaestusJwtIssuer issuer;

    @Autowired
    private AccountExportRepository exports;

    @Autowired
    private ExportGenerationWorker exporter;

    @MockitoSpyBean
    private ExportBundleAssembler assembler;

    @Autowired
    private PersonalGoalRepository goals;

    @Autowired
    private OwnerScope owners;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldSaveEditAndForgetWithoutAWorkspaceAndRejectEarlierRevisions() {
        var owner = signup();
        String token = token(owner);
        assertThat(read(token)).isEqualTo(new PersonalGoalDTO(0, null));
        save(token, 0, WORDS)
                .expectStatus()
                .isOk()
                .expectBody(PersonalGoalDTO.class)
                .isEqualTo(new PersonalGoalDTO(1, WORDS));
        save(token, 1, EDIT)
                .expectStatus()
                .isOk()
                .expectBody(PersonalGoalDTO.class)
                .isEqualTo(new PersonalGoalDTO(2, EDIT));
        long other = signup();
        assertThat(read(token(other))).isEqualTo(new PersonalGoalDTO(0, null));
        var tx = new TransactionTemplate(transactionManager);
        var denied = tx.execute(status -> {
            owners.ofJob(other);
            return goals.findById(owner);
        });
        assertThat(denied).isEmpty();
        client.get().uri(GOAL).exchange().expectStatus().isUnauthorized().expectBody(Void.class);
        client.delete()
                .uri(GOAL + "?revision=2")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PersonalGoalDTO.class)
                .isEqualTo(new PersonalGoalDTO(3, null));
        save(token, 0, WORDS).expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody(Void.class);
        assertThat(read(token)).isEqualTo(new PersonalGoalDTO(3, null));
    }

    @Test
    void shouldKeepTheGoalFromInstanceAdminAndUserView() {
        long owner = signup();
        String ownerToken = token(owner);
        save(ownerToken, 0, WORDS).expectStatus().isOk().expectBody(Void.class);
        var administrator = new Account("Instance administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        String adminToken =
                token(Objects.requireNonNull(accounts.save(administrator).getId()));

        assertThat(read(adminToken)).isEqualTo(new PersonalGoalDTO(0, null));
        client.get()
                .uri(GOAL)
                .headers(headers -> {
                    headers.setBearerAuth(adminToken);
                    headers.set(UserViewContextHolder.WORKSPACE_HEADER, "test-project");
                    headers.set(UserViewContextHolder.ACCOUNT_HEADER, Long.toString(owner));
                    headers.set(UserViewContextHolder.REASON_HEADER, "Check the test account");
                })
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(read(ownerToken)).isEqualTo(new PersonalGoalDTO(1, WORDS));
    }

    @Test
    void shouldAcceptOneOfTwoConcurrentSavesOfTheSameEmptyRevision() throws Exception {
        String token = token(signup());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> saveStatus(token, WORDS));
            var second = executor.submit(() -> saveStatus(token, EDIT));
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(read(token).revision()).isOne();
    }

    @Test
    void shouldExpireOnlyTheOwnersReadyExportWhenTheGoalChanges() {
        long owner = signup();
        long other = signup();
        String ownerToken = token(owner);
        save(ownerToken, 0, WORDS).expectStatus().isOk().expectBody(Void.class);
        save(token(other), 0, "Write one example in a review.")
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        long ownerExport = seedExport(owner);
        long otherExport = seedExport(other);
        exporter.generate(ownerExport, owner);
        exporter.generate(otherExport, other);
        var ownerPayload = Objects.requireNonNull(exportOf(owner, ownerExport).getPayload());
        var otherPayload = Objects.requireNonNull(exportOf(other, otherExport).getPayload());
        assertThat(new String(ownerPayload, StandardCharsets.UTF_8)).contains(WORDS);
        assertThat(new String(otherPayload, StandardCharsets.UTF_8)).doesNotContain(WORDS);

        save(ownerToken, 1, EDIT).expectStatus().isOk().expectBody(Void.class);

        var expired = exportOf(owner, ownerExport);
        assertThat(expired.getStatus()).isEqualTo(AccountExport.Status.EXPIRED);
        assertThat(expired.getPayload()).isNull();
        var kept = exportOf(other, otherExport);
        assertThat(kept.getStatus()).isEqualTo(AccountExport.Status.READY);
        assertThat(kept.getPayload()).isEqualTo(otherPayload);
    }

    @Test
    void shouldReadTheGoalAgainBeforeAnInFlightExportPublishes() throws Exception {
        long owner = signup();
        String token = token(owner);
        save(token, 0, WORDS).expectStatus().isOk().expectBody(Void.class);
        long export = seedExport(owner);
        var assembled = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(call -> {
                    var bundle = call.callRealMethod();
                    assembled.countDown();
                    assertThat(resume.await(30, TimeUnit.SECONDS)).isTrue();
                    return bundle;
                })
                .when(assembler)
                .assemble(owner);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var generating = executor.submit(() -> exporter.generate(export, owner));
            try {
                assertThat(assembled.await(30, TimeUnit.SECONDS)).isTrue();
                client.delete()
                        .uri(GOAL + "?revision=1")
                        .headers(headers -> headers.setBearerAuth(token))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Void.class);
            } finally {
                resume.countDown();
            }
            generating.get(30, TimeUnit.SECONDS);
        }
        assertThat(new String(Objects.requireNonNull(exportOf(owner, export).getPayload()), StandardCharsets.UTF_8))
                .doesNotContain(WORDS);
    }

    private int saveStatus(String token, String words) {
        return save(token, 0, words)
                .expectBody(Void.class)
                .returnResult()
                .getStatus()
                .value();
    }

    private WebTestClient.ResponseSpec save(String token, long revision, String words) {
        return client.put()
                .uri(GOAL)
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(new SavePersonalGoalDTO(revision, words))
                .exchange();
    }

    private PersonalGoalDTO read(String token) {
        return Objects.requireNonNull(client.get()
                .uri(GOAL)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PersonalGoalDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private long signup() {
        return Objects.requireNonNull(
                accounts.save(new Account("Goal developer")).getId());
    }

    private String token(long account) {
        return issuer.issue(account, TokenConstraints.session(null, Instant.now()), null)
                .value();
    }

    private long seedExport(long owner) {
        return Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
            owners.ofJob(owner);
            return exports.save(new AccountExport(owner)).getId();
        }));
    }

    private AccountExport exportOf(long owner, long export) {
        return Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
            owners.ofJob(owner);
            return exports.findById(export).orElseThrow();
        }));
    }
}
