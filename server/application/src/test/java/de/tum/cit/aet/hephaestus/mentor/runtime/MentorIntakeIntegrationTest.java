package de.tum.cit.aet.hephaestus.mentor.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.mentor.spi.Admission;
import de.tum.cit.aet.hephaestus.mentor.spi.Audience;
import de.tum.cit.aet.hephaestus.mentor.spi.AuthorityScope;
import de.tum.cit.aet.hephaestus.mentor.spi.Binding;
import de.tum.cit.aet.hephaestus.mentor.spi.IngressEnvelope;
import de.tum.cit.aet.hephaestus.mentor.spi.IngressKind;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorAuthorization;
import de.tum.cit.aet.hephaestus.mentor.spi.MessageRef;
import de.tum.cit.aet.hephaestus.mentor.spi.Producer;
import de.tum.cit.aet.hephaestus.mentor.spi.ProviderEventKey;
import de.tum.cit.aet.hephaestus.mentor.spi.RequestRef;
import de.tum.cit.aet.hephaestus.mentor.spi.ScopeStamp;
import de.tum.cit.aet.hephaestus.mentor.spi.Transport;
import de.tum.cit.aet.hephaestus.mentor.spi.VerifiedActor;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The intake state table of RUNTIME-DESIGN.md (T1, T2, T4, T5, T10, T18, T19) and the canonical-message cases. */
class MentorIntakeIntegrationTest extends BaseIntegrationTest {
    private static final String FIRST = "1700000000.000100";
    private static final String EDITED = "1700000005.000200";

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MentorSourceRepository rows;

    @Autowired
    private PersonDataWriteFence fence;

    @Autowired
    private OwnerScope owners;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    /** The server bean, with no {@link MentorAuthorization} in the context. */
    @Autowired
    private MentorIntake unauthorized;

    private final JdbcTemplate owner = PostgreSQLTestContainer.ownerJdbc();
    private final long binding = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
    private final long workspace = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
    private MentorIntake intake;
    private long account;

    @BeforeEach
    void setUp() {
        account = linkedAccount();
        MentorAuthorization authorization = mock(MentorAuthorization.class);
        when(authorization.captureSlack(any(), any(), any())).thenAnswer(call -> {
            VerifiedActor actor = call.getArgument(0);
            Binding where = call.getArgument(1);
            return Optional.ofNullable(actor.linkedAccountId())
                    .map(linked -> new ScopeStamp(
                            linked,
                            3,
                            5,
                            7,
                            new AuthorityScope.Slack(
                                    where.workspaceId(), 11, where.connectionId(), 13, actor.nativeActorId())));
        });
        intake = new MentorIntake(
                rows,
                fence,
                owners,
                new StaticListableBeanFactory(Map.of("authorization", authorization))
                        .getBeanProvider(MentorAuthorization.class));
    }

    @Test
    void shouldOpenRequestVersionOneWithItsAudienceAndScopeWhenALinkedPersonAsksANewQuestion() {
        String message = message();
        RequestRef request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST));

        assertThat(request.version()).isEqualTo(1);
        assertThat(owner.queryForMap("SELECT * FROM mentor_request WHERE id = ?", request.requestId()))
                .containsEntry("account_id", account)
                .containsEntry("state", "OPEN")
                .containsEntry("audience_container_id", "D" + binding)
                .containsEntry("scope_authority", "SLACK")
                .containsEntry("scope_binding_id", binding)
                .containsEntry("scope_reader_epoch", 5L);
        assertThat(source(message)).containsEntry("producer", "MENTOR").containsEntry("state", "LIVE");
        assertThat(event("e1")).containsEntry("disposition", "OPENED").containsEntry("request_id", request.requestId());
    }

    @Test
    void shouldAnswerDuplicateAndWriteNothingWhenTheSameProviderEventArrivesTwice() {
        String message = message();
        var first = admit(IngressKind.MESSAGE, "e1", message, FIRST);

        assertThat(admit(IngressKind.MESSAGE, "e1", message, FIRST))
                .isEqualTo(new Admission.Duplicate(MentorIntake.eventId(new ProviderEventKey(binding, "e1"))));
        assertThat(requests(message)).containsExactly(opened(first).requestId());
    }

    @Test
    void shouldAdmitOnceWhenTwoDeliveriesOfOneEventRace() {
        String message = message();
        var barrier = new CyclicBarrier(2);
        List<CompletableFuture<Admission>> racers = List.of(1, 2).stream()
                .map(racer -> CompletableFuture.supplyAsync(() -> {
                    await(barrier);
                    return admit(IngressKind.MESSAGE, "e1", message, FIRST);
                }))
                .toList();
        List<Admission> results = racers.stream().map(CompletableFuture::join).toList();

        assertThat(results).filteredOn(Admission.Duplicate.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(Admission.Admitted.class::isInstance).hasSize(1);
        assertThat(requests(message)).hasSize(1);
    }

    @Test
    void shouldRecordASecondEventIdForTheSameMessageWithoutASecondRequest() {
        String message = message();
        RequestRef request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST));

        assertThat(admit(IngressKind.MESSAGE, "e2", message, FIRST)).isEqualTo(admitted("e2", null));
        assertThat(event("e2")).containsEntry("disposition", "RECORDED").containsEntry("reason", "ALREADY_OPENED");
        assertThat(requests(message)).containsExactly(request.requestId());
    }

    @Test
    void shouldRaiseTheVersionOnlyForANewerRevisionWhenTheRequestIsOpen() {
        String message = message();
        UUID request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).requestId();

        assertThat(admit(IngressKind.MESSAGE_EDITED, "e2", message, EDITED))
                .isEqualTo(admitted("e2", new RequestRef(request, 2)));
        assertThat(admit(IngressKind.MESSAGE_EDITED, "e3", message, FIRST)).isEqualTo(admitted("e3", null));
        assertThat(event("e3")).containsEntry("reason", "STALE_REVISION");
        assertThat(source(message)).containsEntry("revision", EDITED);
        assertThat(column(request, "version")).isEqualTo(2);
    }

    @Test
    void shouldRecordAnEditAndKeepTheRequestWhenTheRequestIsSettled() {
        String message = message();
        UUID request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).requestId();
        owner.update("UPDATE mentor_request SET state = 'ANSWERED' WHERE id = ?", request);

        assertThat(admit(IngressKind.MESSAGE_EDITED, "e2", message, EDITED)).isEqualTo(admitted("e2", null));
        assertThat(event("e2")).containsEntry("reason", "REQUEST_SETTLED");
        assertThat(column(request, "version")).isEqualTo(1);
        assertThat(column(request, "state")).isEqualTo("ANSWERED");
    }

    @Test
    void shouldStopTheRequestAndKeepATombstoneWhenTheQuestionIsDeleted() {
        String message = message();
        UUID request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).requestId();

        assertThat(admit(IngressKind.MESSAGE_DELETED, "e2", message, EDITED))
                .isEqualTo(admitted("e2", new RequestRef(request, 1)));
        assertThat(column(request, "state")).isEqualTo("STOPPED");
        assertThat(source(message)).containsEntry("state", "DELETED");
        assertThat(event("e2")).containsEntry("disposition", "FENCED");
    }

    @Test
    void shouldOpenNoRequestWhenALateCopyOfTheCreateArrivesAfterTheDelete() {
        String message = message();
        UUID request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).requestId();
        admit(IngressKind.MESSAGE_DELETED, "e2", message, EDITED);

        assertThat(admit(IngressKind.MESSAGE, "e3", message, FIRST)).isEqualTo(admitted("e3", null));
        assertThat(event("e3")).containsEntry("reason", "SOURCE_DELETED");
        assertThat(requests(message)).containsExactly(request);
        assertThat(column(request, "state")).isEqualTo("STOPPED");
    }

    @Test
    void shouldWriteNothingForAnEditThatArrivesBeforeTheCreate() {
        String message = message();
        assertThat(admit(IngressKind.MESSAGE_EDITED, "e1", message, EDITED)).isEqualTo(admitted("e1", null));
        assertThat(admit(IngressKind.MESSAGE_DELETED, "e2", message, EDITED)).isEqualTo(admitted("e2", null));
        assertThat(owner.queryForList("SELECT id FROM mentor_source WHERE native_message_id = ?", UUID.class, message))
                .isEmpty();

        assertThat(opened(admit(IngressKind.MESSAGE, "e3", message, FIRST)).version())
                .isEqualTo(1);
        assertThat(source(message)).containsEntry("revision", FIRST);
    }

    @Test
    void shouldRecordAndRefuseAMutationWhoseActorIsNotTheMessagesOwner() {
        String message = message();
        UUID request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).requestId();
        long stranger = linkedAccount();
        var edit = envelope(
                IngressKind.MESSAGE_EDITED, "e2", message, EDITED, new VerifiedActor("U" + stranger, stranger));

        assertThat(inTransaction(() -> intake.admit(edit, Producer.MENTOR))).isEqualTo(admitted("e2", null));
        assertThat(event("e2")).containsEntry("account_id", account).containsEntry("reason", "AUTHOR_MISMATCH");
        assertThat(column(request, "version")).isEqualTo(1);
        assertThat(source(message)).containsEntry("revision", FIRST);
    }

    /** T19 and bot posts: neither an unlinked person nor a bot or the app itself has an account to own a row. */
    @ParameterizedTest
    @ValueSource(strings = {"U-unlinked", "B-bot"})
    void shouldWriteNoMentorRowWhenTheActorHasNoLinkedAccount(String actor) {
        String message = message();
        var envelope = envelope(IngressKind.MESSAGE, "e1", message, FIRST, new VerifiedActor(actor, null));

        assertThat(inTransaction(() -> intake.admit(envelope, Producer.MENTOR))).isEqualTo(admitted("e1", null));
        assertThat(owner.queryForList("SELECT id FROM mentor_event WHERE binding_id = ?", UUID.class, binding))
                .isEmpty();
        assertThat(owner.queryForList("SELECT id FROM mentor_source WHERE native_message_id = ?", UUID.class, message))
                .isEmpty();
    }

    @Test
    void shouldAnswerLegacyAndWriteNoRowWhenNoRouteIsCutOver() {
        String message = message();
        var envelope = envelope(IngressKind.MESSAGE, "e1", message, FIRST, actor());

        assertThat(intake.route(envelope.binding(), envelope.audience())).isEqualTo(Producer.LEGACY);
        assertThat(inTransaction(() -> intake.admit(envelope))).isEqualTo(admitted("e1", null));
        assertThat(owner.queryForList("SELECT id FROM mentor_source WHERE native_message_id = ?", UUID.class, message))
                .isEmpty();
    }

    @Test
    void shouldStillReviseAMentorPinnedSourceWhenTheRouteIsLegacyAgain() {
        String message = message();
        UUID request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).requestId();
        var edit = envelope(IngressKind.MESSAGE_EDITED, "e2", message, EDITED, actor());

        assertThat(inTransaction(() -> intake.admit(edit))).isEqualTo(admitted("e2", new RequestRef(request, 2)));
    }

    /**
     * Two replicas: one holds the source, as a mutation or a workspace purge does, and the other's intake fails at once
     * instead of waiting. The receive transaction rolls back, and the provider's retry admits the event once.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldFailTheReceiveAndAdmitOnceOnRetryWhenAnotherReplicaHoldsTheSource(boolean purge) throws Exception {
        String message = message();
        UUID request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).requestId();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var replica =
                CompletableFuture.runAsync(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    if (purge) {
                        jdbcClient
                                .sql("SELECT public.mentor_erase_workspace(?)")
                                .param(workspace)
                                .query(Integer.class)
                                .single();
                    } else {
                        owners.ofJob(account);
                        jdbcClient
                                .sql("SELECT id FROM mentor_source WHERE native_message_id = ? FOR UPDATE")
                                .param(message)
                                .query(UUID.class)
                                .single();
                    }
                    held.countDown();
                    await(release);
                }));
        held.await();

        assertThatThrownBy(() -> admit(IngressKind.MESSAGE_EDITED, "e2", message, EDITED))
                .isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(owner.queryForList(
                        "SELECT id FROM mentor_event WHERE binding_id = ? AND native_event_id = 'e2'",
                        UUID.class,
                        binding))
                .isEmpty();
        release.countDown();
        replica.join();

        if (purge) {
            assertThat(admit(IngressKind.MESSAGE_EDITED, "e2", message, EDITED)).isEqualTo(admitted("e2", null));
            assertThat(owner.queryForList("SELECT id FROM mentor_request WHERE id = ?", UUID.class, request))
                    .isEmpty();
        } else {
            assertThat(admit(IngressKind.MESSAGE_EDITED, "e2", message, EDITED))
                    .isEqualTo(admitted("e2", new RequestRef(request, 2)));
            assertThat(event("e2")).containsEntry("disposition", "REVISED");
        }
    }

    @Test
    void shouldAdmitOnRetryWhenTheFirstReceiveTransactionRolledBack() {
        String message = message();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            intake.admit(envelope(IngressKind.MESSAGE, "e1", message, FIRST, actor()), Producer.MENTOR);
            status.setRollbackOnly();
        });
        assertThat(owner.queryForList("SELECT id FROM mentor_source WHERE native_message_id = ?", UUID.class, message))
                .isEmpty();

        assertThat(opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).version())
                .isEqualTo(1);
    }

    @Test
    void shouldRecordTheQuestionWithoutARequestWhenNoAuthorizationIsWired() {
        String message = message();
        var envelope = envelope(IngressKind.MESSAGE, "e1", message, FIRST, actor());

        assertThat(inTransaction(() -> unauthorized.admit(envelope, Producer.MENTOR)))
                .isEqualTo(admitted("e1", null));
        assertThat(event("e1")).containsEntry("reason", "UNAUTHORIZED");
        assertThat(requests(message)).isEmpty();
    }

    private Admission admit(IngressKind kind, String event, String message, String revision) {
        var envelope = envelope(kind, event, message, revision, actor());
        return inTransaction(() -> intake.admit(envelope, Producer.MENTOR));
    }

    private IngressEnvelope envelope(
            IngressKind kind, String event, String message, String revision, VerifiedActor by) {
        Instant now = Instant.now();
        return new IngressEnvelope(
                kind,
                new ProviderEventKey(binding, event),
                by,
                new Binding(workspace, binding, "T" + binding),
                new Audience(Transport.SLACK, binding, "D" + binding, null),
                new MessageRef(message, revision),
                "provider-event:" + event.substring(1),
                now,
                now);
    }

    private VerifiedActor actor() {
        return new VerifiedActor("U" + account, account);
    }

    private Admission inTransaction(Supplier<Admission> work) {
        return Objects.requireNonNull(new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    private Admission admitted(String event, @Nullable RequestRef request) {
        return new Admission.Admitted(MentorIntake.eventId(new ProviderEventKey(binding, event)), request);
    }

    private static RequestRef opened(Admission admission) {
        assertThat(admission).isInstanceOf(Admission.Admitted.class);
        return Objects.requireNonNull(((Admission.Admitted) admission).request());
    }

    private Map<String, @Nullable Object> source(String message) {
        return owner.queryForMap("SELECT * FROM mentor_source WHERE native_message_id = ?", message);
    }

    private Map<String, @Nullable Object> event(String event) {
        return owner.queryForMap(
                "SELECT * FROM mentor_event WHERE binding_id = ? AND native_event_id = ?", binding, event);
    }

    private List<@Nullable UUID> requests(String message) {
        return owner.queryForList("""
                SELECT r.id FROM mentor_request r JOIN mentor_source s ON s.id = r.source_id
                WHERE s.native_message_id = ?
                """, UUID.class, message);
    }

    private @Nullable Object column(UUID request, String column) {
        return owner.queryForMap("SELECT * FROM mentor_request WHERE id = ?", request)
                .get(column);
    }

    /** The original ts of a message. */
    private static String message() {
        var random = ThreadLocalRandom.current();
        return random.nextLong(1_000_000_000L, 9_999_999_999L) + "." + random.nextInt(100_000, 999_999);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception interrupted) {
            throw new IllegalStateException(interrupted);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** The write fence admits an account with a linked identity only. */
    private long linkedAccount() {
        Account saved = accounts.saveAndFlush(new Account("Mentor asker"));
        IdentityProvider slack = providers
                .findByTypeAndServerUrl(IdentityProviderType.SLACK, "https://slack.com")
                .orElseGet(() -> providers.save(new IdentityProvider(IdentityProviderType.SLACK, "https://slack.com")));
        Identity identity = new Identity();
        identity.setAccount(saved);
        identity.setProviderId(Objects.requireNonNull(slack.getId()));
        identity.setProviderUserKey("mentor-asker-" + saved.getId());
        identities.saveAndFlush(identity);
        return Objects.requireNonNull(saved.getId());
    }
}
