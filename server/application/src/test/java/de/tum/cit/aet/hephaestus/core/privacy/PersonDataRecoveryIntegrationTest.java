package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(OutputCaptureExtension.class)
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

    @Autowired
    private ApplicationContext beans;

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

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "ERASING"})
    void shouldResumeAnInterruptedErasureBeforeProcessingStarts(String state) throws Exception {
        var administrator = new Account("Recovery administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        long personId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Interrupted person")).getId());
        var request = service.preview(administratorId, personId, List.of()).request();
        service.requestErasure(request.getId(), administratorId, false);
        jdbc.update(
                "UPDATE person_data_request SET state=?,failure_code='STORE_ERASURE_FAILED' WHERE id=?",
                state,
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

    @Test
    void shouldReapplyNativeIdentitySuppressionForAllAdaptersBeforeProcessingStarts() throws Exception {
        var administrator = new Account("Recovery administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        var inventory = beans.getBean(IdentityProviderRepository.class);
        List<PersonDataRecovery.NativeIdentity> identities = List.of(
                new PersonDataRecovery.NativeIdentity("GITHUB", "https://github.com", "424242", null),
                new PersonDataRecovery.NativeIdentity("GITLAB", "https://recovery-gitlab.example.test", "434343", null),
                new PersonDataRecovery.NativeIdentity("SLACK", "https://slack.com", "URECOVERY", "TRECOVERY"),
                new PersonDataRecovery.NativeIdentity(
                        "OUTLINE",
                        "https://recovery-outline.example.test",
                        "00000000-0000-4000-8000-000000000042",
                        null));
        for (var identity : identities) {
            var type = IdentityProviderType.valueOf(identity.providerType());
            if (inventory.findByTypeAndServerUrl(type, identity.serverUrl()).isEmpty()) {
                inventory.saveAndFlush(new IdentityProvider(type, identity.serverUrl()));
            }
        }
        Path manifest = manifest(mapper.writeValueAsString(new PersonDataRecovery.Manifest(
                1,
                Instant.parse("2026-01-01T00:00:00Z"),
                false,
                administratorId,
                List.of(new PersonDataRecovery.Person(null, identities, false)))));
        var processing = new AtomicBoolean();
        try (var context = context(manifest, processing)) {
            context.refresh();
            assertThat(processing).isTrue();
        }
        var suppression = beans.getBean(PersonProcessingSuppression.class);
        for (var identity : identities) {
            long providerId = Objects.requireNonNull(inventory
                    .findByTypeAndServerUrl(IdentityProviderType.valueOf(identity.providerType()), identity.serverUrl())
                    .orElseThrow()
                    .getId());
            assertThat(suppression.isSuppressed(providerId, identity.subject(), identity.teamId()))
                    .isTrue();
        }
    }

    @Test
    void shouldKeepProcessingStoppedAndHidePrivateInputWhenAStoreFails(CapturedOutput output) throws Exception {
        var administrator = new Account("Recovery administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        long administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(administrator).getId());
        long personId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Store failure person")).getId());
        var failing =
                new JdbcPersonDataStore(
                        beans.getBean(NamedParameterJdbcTemplate.class),
                        mapper,
                        "recovery_failing_store",
                        "account",
                        "t.id=:account",
                        "id",
                        "id",
                        "display_name='erased'",
                        0) {
                    @Override
                    public long erase(PersonDataSelection selection, PersonScope person) {
                        throw new IllegalStateException("private-display-name-canary");
                    }
                };
        var recoveryService = new PersonDataService(
                resolver,
                new PersonDataRegistry(List.of(new RecoveryStores(List.of(failing)))),
                List.of(),
                beans.getBean(PersonDataRequestRepository.class),
                beans.getBean(IdentityRestrictionService.class),
                beans.getBean(PersonDataWriteFence.class),
                beans.getBean(PersonDataCopyFence.class),
                beans.getBean(IssuedJwtRepository.class),
                beans.getBean(PlatformTransactionManager.class),
                mapper,
                jdbc,
                beans.getBean(MeterRegistry.class));
        // Fault injection still needs the production transaction advice on each service operation.
        var initialized = beans.getAutowireCapableBeanFactory().initializeBean(recoveryService, "recoveryFaultService");
        var advisedService = PersonDataService.class.cast(initialized);
        Path manifest = manifest("""
                {"version":1,"restorePoint":"2026-01-01T00:00:00Z","administratorAccountId":%d,
                 "people":[{"accountId":%d,"identities":[]}]}
                """.formatted(administratorId, personId));
        var processing = new AtomicBoolean();
        try (var context = context(manifest, processing, advisedService)) {
            assertThatThrownBy(context::refresh).hasStackTraceContaining("ERASURE_INCOMPLETE");
            assertThat(processing).isFalse();
        }
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM person_data_request WHERE scope_json::jsonb->>'accountId'=?",
                        String.class,
                        Long.toString(personId)))
                .isEqualTo("FAILED");
        assertThat(output).contains("ERASURE_INCOMPLETE").doesNotContain("private-display-name-canary");
    }

    @PersonDataStores({"recovery_failing_store"})
    private record RecoveryStores(List<PersonDataContributor> contributors) implements PersonDataCatalog {}

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
        return context(manifest, processing, service);
    }

    private GenericApplicationContext context(
            Path manifest, AtomicBoolean processing, PersonDataService recoveryService) {
        var context = new GenericApplicationContext();
        var environment = new MockEnvironment()
                .withProperty(RuntimeRole.SERVER_PROPERTY, "true")
                .withProperty(RuntimeRole.WORKER_PROPERTY, "false")
                .withProperty(RuntimeRole.WEBHOOK_PROPERTY, "false");
        context.registerBean(
                PersonDataRecovery.class,
                () -> new PersonDataRecovery(
                        recoveryService,
                        resolver,
                        mapper,
                        environment,
                        beans.getBean(PersonDataRequestRepository.class),
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
