package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonEvidenceErasure;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceIdentityContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredCollectionErasures;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredDisconnects;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredMessageDeletions;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredSlackChannelErasures;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredSlackMessageChanges;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredWorkspacePurges;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnBackgroundWork;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingPrivacyJournal;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
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
        assertBlocked(
                manifest(
                        "{\"version\":5,\"recordDeletions\":[],\"slackMessageChanges\":[],\"slackChannelErasures\":[],\"workspacePurges\":[],\"disconnects\":[],\"collectionErasures\":[],\"messageDeletions\":[],\"restorePoint\":\"2026-01-01T00:00:00Z\",\"people\":[]}"));
    }

    @Test
    void shouldRefuseAnEarlierManifestThatCannotListEveryErasureKind(CapturedOutput output) throws Exception {
        assertBlocked(manifest(
                "{\"version\":1,\"restorePoint\":\"2026-01-01T00:00:00Z\",\"noLaterErasures\":true,\"people\":[]}"));
        // Version 2 cannot say that no developer deleted a conversation message.
        assertBlocked(
                manifest(
                        "{\"version\":2,\"workspacePurges\":[],\"disconnects\":[],\"restorePoint\":\"2026-01-01T00:00:00Z\",\"noLaterErasures\":true,\"people\":[]}"));
        // Version 3 cannot say that no author edited or deleted a stored Slack message in Slack.
        assertBlocked(
                manifest(
                        "{\"version\":3,\"workspacePurges\":[],\"disconnects\":[],\"collectionErasures\":[],\"messageDeletions\":[],\"restorePoint\":\"2026-01-01T00:00:00Z\",\"noLaterErasures\":true,\"people\":[]}"));
        assertThat(output).contains("MANIFEST_VERSION");
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", ""})
    void shouldRefuseToRecoverBesideBackgroundWork(String backgroundWork, CapturedOutput output) throws Exception {
        Path manifest = manifest("""
                {"version":5,"recordDeletions":[],"slackMessageChanges":[],"slackChannelErasures":[],"workspacePurges":[],"disconnects":[],"collectionErasures":[],"messageDeletions":[],"restorePoint":"2026-01-01T00:00:00Z",
                 "noLaterErasures":true,"people":[]}
                """);
        var processing = new AtomicBoolean();
        try (var context = context(manifest, processing, service, backgroundWork)) {
            assertThatThrownBy(context::refresh).hasStackTraceContaining("Erasure recovery is incomplete");
            assertThat(processing).isFalse();
        }
        assertThat(output).contains("BACKGROUND_WORK");
    }

    @Test
    void shouldTurnBackgroundWorkOffInTheRecoveryProfile() throws Exception {
        var sources = new YamlPropertySourceLoader()
                .load("erasure-recovery", new ClassPathResource("application-erasure-recovery.yml"));
        assertThat(sources)
                .singleElement()
                .satisfies(source -> assertThat(source.getProperty(ConditionalOnBackgroundWork.PROPERTY))
                        .isEqualTo(false));
    }

    @Test
    void shouldStartProcessingWhenTheOperatorDeclaresNoLaterErasures() throws Exception {
        Path manifest = manifest(
                "{\"version\":5,\"recordDeletions\":[],\"slackMessageChanges\":[],\"slackChannelErasures\":[],\"workspacePurges\":[],\"disconnects\":[],\"collectionErasures\":[],\"messageDeletions\":[],\"restorePoint\":\"2026-01-01T00:00:00Z\",\"noLaterErasures\":true,\"people\":[]}");
        var processing = new AtomicBoolean();
        try (var context = context(manifest, processing)) {
            context.refresh();
            assertThat(processing).isTrue();
        }
    }

    @Test
    void shouldExportAndEraseThePublicationForTheExactUnlinkedSlackSubject() {
        var subject = new PersonIdentity(19, "UMEMBER", "TRECOVERY");
        var person = new PersonScope(null, List.of(subject), List.of());
        UUID target = UUID.randomUUID();
        UUID unrelated = UUID.randomUUID();
        for (var publication : Map.of(target, subject, unrelated, new PersonIdentity(19, "UMEMBER", "TOTHER"))
                .entrySet()) {
            jdbc.update(
                    """
                    INSERT INTO event_publication(id, listener_id, event_type, publication_date, serialized_event)
                    VALUES (?, 'slack-withdrawal-test', 'SlackOptOutConfirmation', clock_timestamp(), ?)
                    """,
                    publication.getKey(),
                    mapper.writeValueAsString(Map.of("subjectIdentity", publication.getValue())));
        }
        var store = beans.getBean(CorePrivacyPersonDataCatalog.class).contributors().stream()
                .filter(contributor -> contributor.store().equals("event_publication"))
                .findFirst()
                .orElseThrow();
        var selection = store.select(person);
        assertThat(selection.rows())
                .singleElement()
                .satisfies(row -> assertThat(row.columns().get("id")).isEqualTo(target.toString()));
        assertThat(store.export(selection, person)).hasSize(1);
        assertThat(store.erase(selection, person)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                        "SELECT id FROM event_publication WHERE id IN (?,?)", UUID.class, target, unrelated))
                .containsExactly(unrelated);
    }

    @Test
    void shouldResumeVersionFourRecoveryWhenTheJournalBucketReturns() throws Exception {
        var operator = new Account("Journal recovery operator");
        operator.setAppRole(Account.AppRole.APP_ADMIN);
        var administratorId =
                Objects.requireNonNull(accounts.saveAndFlush(operator).getId());
        var accountId = Objects.requireNonNull(
                accounts.saveAndFlush(new Account("Restored person")).getId());
        var available = new AtomicBoolean();
        PrivacyJournalObjectStore bucket = new PrivacyJournalObjectStore() {
            @Override
            public boolean store(String key, byte[] bytes) {
                return available.get();
            }

            @Override
            public boolean verified() {
                return available.get();
            }
        };
        var shipping =
                new PrivacyJournalShipping(jdbc, mapper, Optional.of(bucket), beans.getBean(MeterRegistry.class));
        var journal = new DatabasePrivacyJournal(jdbc, mapper, Optional.of(resolver), shipping);
        var recoveryService = advisedService(beans.getBean(PersonDataRegistry.class), List.of(), journal, shipping);
        Path manifest = manifest(mapper.writeValueAsString(new PersonDataRecovery.Manifest(
                5,
                Instant.parse("2026-01-01T00:00:00Z"),
                false,
                administratorId,
                List.of(new PersonDataRecovery.Person(accountId, List.of(), false)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of())));
        var processing = new AtomicBoolean();
        try (var context = context(manifest, processing, recoveryService)) {
            assertThatThrownBy(context::refresh).hasStackTraceContaining("Erasure recovery is incomplete");
            assertThat(processing).isFalse();
        }
        assertThat(accounts.findById(accountId).orElseThrow().getStatus()).isEqualTo(Account.Status.DELETING);

        available.set(true);
        try (var context = context(manifest, processing, recoveryService)) {
            context.refresh();
            assertThat(processing).isTrue();
        }
        assertThat(accounts.findById(accountId).orElseThrow().getStatus()).isEqualTo(Account.Status.DELETED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM privacy_journal
                WHERE refs ->> 'accountId' = ? AND requires_shipping AND shipped_at IS NULL
                """, Long.class, accountId.toString())).isZero();
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
        // Save the journal fact outside the transaction, then restore the database state from before admission.
        var transactions = new TransactionTemplate(beans.getBean(PlatformTransactionManager.class));
        var recovery = Objects.requireNonNull(transactions.execute(status -> {
            var service = beans.getBean(PersonDataService.class);
            var id = service.preview(administratorId, personId, List.of())
                    .request()
                    .getId();
            service.requestErasure(id, administratorId, false);
            var refs = Objects.requireNonNull(beans.getBean(JdbcTemplate.class)
                    .queryForObject(
                            "SELECT refs::text FROM privacy_journal WHERE entry_id = ? AND kind = 'PERSON_ERASED'",
                            String.class,
                            id));
            status.setRollbackOnly();
            return mapper.readValue(refs, PersonDataRecovery.Person.class);
        }));
        assertThat(accounts.findById(personId).orElseThrow().getStatus()).isEqualTo(Account.Status.ACTIVE);
        Path manifest = manifest(mapper.writeValueAsString(new PersonDataRecovery.Manifest(
                5,
                Instant.parse("2026-01-01T00:00:00Z"),
                false,
                administratorId,
                List.of(recovery),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of())));
        var journal = new RecordingPrivacyJournal();
        var recordingService = advisedService(
                beans.getBean(PersonDataRegistry.class),
                beans.getBeanProvider(PersonEvidenceErasure.class)
                        .orderedStream()
                        .toList(),
                journal);
        var processing = new AtomicBoolean();
        try (var context = context(manifest, processing, recordingService)) {
            context.registerBean("erasureBeforeProcessing", Runnable.class, () -> () -> {
                Account erased = accounts.findById(personId).orElseThrow();
                assertThat(erased.getStatus()).isEqualTo(Account.Status.DELETED);
                assertThat(erased.getPrimaryEmail()).isNull();
                assertThat(erased.getDisplayName()).doesNotContain("Restored personal name");
            });
            context.refresh();
            assertThat(processing).isTrue();
        }
        assertThat(journal.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.kind()).isEqualTo(PrivacyJournalKind.PERSON_ERASED);
            assertThat(fact.refs()).containsEntry("accountId", personId).containsKey("identities");
        });
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
                {"version":5,"recordDeletions":[],"slackMessageChanges":[],"slackChannelErasures":[],"workspacePurges":[],"disconnects":[],"collectionErasures":[],"messageDeletions":[],"restorePoint":"2026-01-01T00:00:00Z","administratorAccountId":%d,
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
                {"version":5,"recordDeletions":[],"slackMessageChanges":[],"slackChannelErasures":[],"workspacePurges":[],"disconnects":[],"collectionErasures":[],"messageDeletions":[],"restorePoint":"2026-01-01T00:00:00Z","administratorAccountId":-1,
                 "people":[{"accountId":1,"identities":[]}]}
                """));
    }

    @Test
    void shouldRejectDisplayNamesAndHideManifestContentFromStartupFailures() throws Exception {
        assertBlocked(manifest("""
                {"version":5,"recordDeletions":[],"slackMessageChanges":[],"slackChannelErasures":[],"workspacePurges":[],"disconnects":[],"collectionErasures":[],"messageDeletions":[],"restorePoint":"2026-01-01T00:00:00Z","administratorAccountId":1,
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
                5,
                Instant.parse("2026-01-01T00:00:00Z"),
                false,
                administratorId,
                List.of(new PersonDataRecovery.Person(null, identities, false)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of())));
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
        // A person without an account still leaves a fact: the erasure by native identity alone.
        assertThat(jdbc.queryForList("""
                        SELECT refs::text FROM privacy_journal WHERE kind = ?
                            AND entry_id IN (SELECT id FROM person_data_request WHERE administrator_account_id = ?)
                        """, String.class, PrivacyJournalKind.PERSON_ERASED.name(), administratorId))
                .singleElement()
                .satisfies(refs -> {
                    var fact = mapper.readTree(refs);
                    assertThat(fact.size()).isEqualTo(2);
                    assertThat(fact.path("externalCopiesRemoved")).isEqualTo(mapper.readTree("false"));
                    assertThat(fact.path("identities").values())
                            .containsExactlyInAnyOrderElementsOf(
                                    mapper.<JsonNode>valueToTree(identities).values());
                });
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
        var advisedService = advisedService(
                new PersonDataRegistry(List.of(new RecoveryStores(List.of(failing)))), List.of(), (kind, refs) -> {});
        Path manifest = manifest("""
                {"version":5,"recordDeletions":[],"slackMessageChanges":[],"slackChannelErasures":[],"workspacePurges":[],"disconnects":[],"collectionErasures":[],"messageDeletions":[],"restorePoint":"2026-01-01T00:00:00Z","administratorAccountId":%d,
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

    /**
     * A service with its own stores, and a suppression that records into the journal, with the production transaction
     * advice on each operation.
     */
    private PersonDataService advisedService(
            PersonDataRegistry registry, List<PersonEvidenceErasure> evidence, PrivacyJournal journal) {
        return advisedService(registry, evidence, journal, beans.getBean(PrivacyJournalShipping.class));
    }

    private PersonDataService advisedService(
            PersonDataRegistry registry,
            List<PersonEvidenceErasure> evidence,
            PrivacyJournal journal,
            PrivacyJournalShipping shipping) {
        var unadvised = new PersonDataService(
                new PersonDataScopeResolver(resolver, evidence, new OwnerScope(jdbc)),
                registry,
                beans.getBean(PersonDataRequestRepository.class),
                new PersonDataErasureHold(
                        new IdentityRestrictionService(
                                jdbc,
                                beans.getBeanProvider(PersonSourceIdentityContributor.class)
                                        .orderedStream()
                                        .toList(),
                                beans.getBean(PersonDataCopyFence.class),
                                journal),
                        beans.getBean(PersonDataWriteFence.class),
                        beans.getBean(PersonDataCopyFence.class)),
                beans.getBean(IssuedJwtRepository.class),
                beans.getBean(PlatformTransactionManager.class),
                mapper,
                jdbc,
                beans.getBean(MeterRegistry.class),
                journal,
                shipping);
        var initialized = beans.getAutowireCapableBeanFactory()
                .initializeBean(unadvised, "recoveryService" + System.identityHashCode(unadvised));
        return PersonDataService.class.cast(initialized);
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
        return context(manifest, processing, recoveryService, "false");
    }

    private GenericApplicationContext context(
            Path manifest, AtomicBoolean processing, PersonDataService recoveryService, String backgroundWork) {
        var context = new GenericApplicationContext();
        var environment = new MockEnvironment()
                .withProperty(RuntimeRole.SERVER_PROPERTY, "true")
                .withProperty(ConditionalOnBackgroundWork.PROPERTY, backgroundWork);
        context.registerBean(
                PersonDataRecovery.class,
                () -> new PersonDataRecovery(
                        recoveryService,
                        resolver,
                        mapper,
                        environment,
                        beans.getBean(PersonDataRequestRepository.class),
                        beans.getBean(RestoredWorkspacePurges.class),
                        beans.getBean(RestoredDisconnects.class),
                        new RestoredOwnerContent(
                                jdbc, beans.getBean(RestoredMessageDeletions.class), (owner, ids) -> {}),
                        beans.getBean(RestoredCollectionErasures.class),
                        beans.getBean(RestoredSlackMessageChanges.class),
                        beans.getBean(RestoredSlackChannelErasures.class),
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
