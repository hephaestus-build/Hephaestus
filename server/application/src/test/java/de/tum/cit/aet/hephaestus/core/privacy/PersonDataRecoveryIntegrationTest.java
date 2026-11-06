package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

class PersonDataRecoveryIntegrationTest extends BaseIntegrationTest {
    @TempDir
    Path directory;

    @Autowired
    private PersonDataService service;

    @Autowired
    private PersonIdentityResolver resolver;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldKeepProcessingStoppedWhenTheRecoveryManifestIsMissing() {
        assertBlocked(directory.resolve("missing.json"));
    }

    @Test
    void shouldKeepProcessingStoppedWhenAnEmptyManifestDoesNotDeclareNoLaterErasures() throws Exception {
        assertBlocked(manifest("{\"version\":1,\"restorePoint\":\"2026-01-01T00:00:00Z\",\"people\":[]}"));
    }

    @Test
    void shouldStartProcessingWhenTheOperatorDeclaresNoLaterErasures() throws Exception {
        Path manifest = manifest(
                "{\"version\":1,\"restorePoint\":\"2026-01-01T00:00:00Z\",\"noLaterErasures\":true,\"people\":[]}");
        var processing = new AtomicBoolean();
        try (var context = context(manifest, processing)) {
            context.refresh();
            assertThat(processing).isTrue();
        }
    }

    @Test
    void shouldReapplyAnErasureLostByARestoreBeforeProcessingStarts() throws Exception {
        var administrator = new Account("Recovery administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        var person = new Account("Restored personal name");
        person.setPrimaryEmail("restored-person@example.test");
        long personId = Objects.requireNonNull(accounts.saveAndFlush(person).getId());
        Path manifest = manifest("""
                {"version":1,"restorePoint":"2026-01-01T00:00:00Z","administratorAccountId":%d,
                 "people":[{"accountId":%d,"identities":[]}]}
                """.formatted(administratorId, personId));
        var processing = new AtomicBoolean();
        try (var context = context(manifest, processing)) {
            context.registerBean("erasureBeforeProcessing", Runnable.class, () -> () -> {
                Account erased = accounts.findById(personId).orElseThrow();
                assertThat(erased.getStatus()).isEqualTo(Account.Status.DELETED);
                assertThat(erased.getPrimaryEmail()).isNull();
                assertThat(erased.getDisplayName()).doesNotContain("Restored personal name");
            });
            context.refresh();
            assertThat(processing).isTrue();
        }
        // The same recovery configuration is safe after a restart.
        try (var context = context(manifest, new AtomicBoolean())) {
            context.refresh();
        }
    }

    @Test
    void shouldResumeAnInterruptedErasureBeforeProcessingStarts() throws Exception {
        var administrator = new Account("Recovery administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        long personId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Interrupted person")).getId());
        var request = service.preview(administratorId, personId, List.of()).request();
        service.requestErasure(request.getId(), administratorId, false);
        jdbc.update(
                "UPDATE person_data_request SET state='FAILED',failure_code='STORE_ERASURE_FAILED' WHERE id=?",
                request.getId());
        Path manifest = manifest("""
                {"version":1,"restorePoint":"2026-01-01T00:00:00Z","administratorAccountId":%d,
                 "people":[{"accountId":%d,"identities":[]}]}
                """.formatted(administratorId, personId));
        var processing = new AtomicBoolean();
        try (var context = context(manifest, processing)) {
            context.registerBean(
                    "resumedBeforeProcessing",
                    Runnable.class,
                    () -> () -> assertThat(
                                    service.get(request.getId()).request().getState())
                            .isEqualTo(PersonDataRequest.State.COMPLETE));
            context.refresh();
            assertThat(processing).isTrue();
        }
    }

    @Test
    void shouldKeepProcessingStoppedWhenReapplicationCannotComplete() throws Exception {
        assertBlocked(manifest("""
                {"version":1,"restorePoint":"2026-01-01T00:00:00Z","administratorAccountId":-1,
                 "people":[{"accountId":1,"identities":[]}]}
                """));
    }

    @Test
    void shouldRejectDisplayNamesAndHideManifestContentFromStartupFailures() throws Exception {
        assertBlocked(manifest("""
                {"version":1,"restorePoint":"2026-01-01T00:00:00Z","administratorAccountId":1,
                 "people":[{"identities":[{"providerType":"GITHUB","serverUrl":"https://github.com",
                 "subject":"private-display-name-canary"}]}]}
                """));
    }

    private Path manifest(String content) throws Exception {
        return Files.writeString(directory.resolve("recovery.json"), content);
    }

    private void assertBlocked(Path manifest) {
        var processing = new AtomicBoolean();
        try (var context = context(manifest, processing)) {
            assertThatThrownBy(context::refresh)
                    .hasStackTraceContaining("Erasure recovery is incomplete")
                    .satisfies(failure -> {
                        var trace = new StringWriter();
                        failure.printStackTrace(new PrintWriter(trace));
                        assertThat(trace.toString()).doesNotContain("private-display-name-canary");
                    });
            assertThat(processing).isFalse();
        }
    }

    private GenericApplicationContext context(Path manifest, AtomicBoolean processing) {
        var context = new GenericApplicationContext();
        var environment = new MockEnvironment()
                .withProperty(RuntimeRole.SERVER_PROPERTY, "true")
                .withProperty(RuntimeRole.WORKER_PROPERTY, "false")
                .withProperty(RuntimeRole.WEBHOOK_PROPERTY, "false");
        context.registerBean(
                PersonDataRecovery.class,
                () -> new PersonDataRecovery(
                        service,
                        resolver,
                        mapper,
                        environment,
                        jdbc,
                        new PersonDataRecoveryProperties(manifest.toString())));
        context.registerBean("processing", SmartLifecycle.class, () -> new SmartLifecycle() {
            @Override
            public void start() {
                context.getBeansOfType(Runnable.class).values().forEach(Runnable::run);
                processing.set(true);
            }

            @Override
            public void stop() {
                processing.set(false);
            }

            @Override
            public boolean isRunning() {
                return processing.get();
            }

            @Override
            public int getPhase() {
                return 0;
            }
        });
        return context;
    }
}
