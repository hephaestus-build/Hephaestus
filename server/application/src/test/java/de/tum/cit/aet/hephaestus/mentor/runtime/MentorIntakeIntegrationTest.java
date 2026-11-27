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
import java.sql.DriverManager;
import java.time.Duration;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
    /** When set, the authorization port captures no stamp, as for a revoked binding or grant. */
    private volatile boolean deny;

    @BeforeEach
    void setUp() {
        account = linkedAccount();
        MentorAuthorization authorization = mock(MentorAuthorization.class);
        when(authorization.captureSlack(any(), any(), any())).thenAnswer(call -> {
            if (deny) return Optional.empty();
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
        assertThat(owner.queryForMap("SELECT * FROM mentor_request WHERE id = ?", request.requestId()))
                .containsEntry("admitted_revision", FIRST)
                .containsEntry("admitted_source_ref", "provider-event:1")
                .containsEntry("delivery_generation", 0L);
        assertThat(owner.queryForList("SELECT account_id FROM mentor_owner WHERE account_id = ?", Long.class, account))
                .containsExactly(account);
    }

    @Test
    void shouldRefuseAProviderEventThatClaimsToBeASealedReviewBeforeAnyWrite() {
        String message = message();

        assertThatThrownBy(() -> admit(IngressKind.REVIEW_SEALED, "e1", message, FIRST))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM mentor_source WHERE native_message_id = ?", Integer.class, message))
                .isZero();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM mentor_event WHERE binding_id = ? AND native_event_id = 'e1'",
                        Integer.class,
                        binding))
                .isZero();
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
        assertThat(column(request, "end_reason")).isEqualTo("SOURCE_DELETED");
        assertThat(column(request, "delivery_generation")).isEqualTo(1L);
        assertThat(source(message)).containsEntry("state", "DELETED");
        assertThat(event("e2")).containsEntry("disposition", "FENCED");
    }

    /** S1: a Stop of an open or a running request stops it and fences its delivery in the receive transaction. */
    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "RUNNING"})
    void shouldStopALiveRequestAndFenceItsDeliveryWhenItsOwnerStopsIt(String state) {
        String message = message();
        UUID request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).requestId();
        owner.update("UPDATE mentor_request SET state = ? WHERE id = ?", state, request);

        assertThat(admit(IngressKind.CONTROL_STOP, "e2", message, FIRST))
                .isEqualTo(admitted("e2", new RequestRef(request, 1)));
        assertThat(owner.queryForMap("SELECT * FROM mentor_request WHERE id = ?", request))
                .containsEntry("state", "STOPPED")
                .containsEntry("end_reason", "STOPPED")
                .containsEntry("delivery_generation", 1L)
                .containsEntry("delivery_fence", "STOPPED");
        assertThat(event("e2")).containsEntry("disposition", "FENCED");
    }

    /** S2: completion won the terminal transition; the Stop keeps its answer but still fences its unsent delivery. */
    @Test
    void shouldKeepTheAnswerAndFenceItsDeliveryWhenTheOwnerStopsASettledRequest() {
        String message = message();
        UUID request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).requestId();
        owner.update("UPDATE mentor_request SET state = 'ANSWERED' WHERE id = ?", request);

        assertThat(admit(IngressKind.CONTROL_STOP, "e2", message, FIRST))
                .isEqualTo(admitted("e2", new RequestRef(request, 1)));
        assertThat(owner.queryForMap("SELECT * FROM mentor_request WHERE id = ?", request))
                .containsEntry("state", "ANSWERED")
                .containsEntry("delivery_generation", 1L)
                .containsEntry("delivery_fence", "STOPPED");
        assertThat(column(request, "end_reason")).isNull();
        assertThat(event("e2")).containsEntry("disposition", "FENCED");
    }

    /** S3: the person who stops must own the question; any of their verified identities may. */
    @Test
    void shouldRefuseAStopByAnotherAccountAndApplyOneByTheOwnerFromAnotherIdentity() {
        String message = message();
        UUID request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).requestId();
        long stranger = linkedAccount();

        var foreign =
                envelope(IngressKind.CONTROL_STOP, "e2", message, FIRST, new VerifiedActor("U" + stranger, stranger));
        assertThat(inTransaction(() -> intake.admit(foreign, Producer.MENTOR))).isEqualTo(admitted("e2", null));
        assertThat(event("e2")).containsEntry("reason", "NOT_OWNER");
        assertThat(column(request, "state")).isEqualTo("OPEN");
        assertThat(column(request, "delivery_generation")).isEqualTo(0L);

        var own = envelope(IngressKind.CONTROL_STOP, "e3", message, FIRST, new VerifiedActor("U-second", account));
        assertThat(inTransaction(() -> intake.admit(own, Producer.MENTOR)))
                .isEqualTo(admitted("e3", new RequestRef(request, 1)));
        assertThat(column(request, "state")).isEqualTo("STOPPED");
    }

    /**
     * S4: another replica holds the owner row or the request. The Stop fails the receive transaction, bounded by its
     * {@code lock_timeout}, and writes nothing; the provider's retry after the release applies it once.
     */
    @ParameterizedTest
    @ValueSource(strings = {"mentor_owner", "mentor_request"})
    void shouldFailTheReceiveAndStopOnceOnRetryWhenAnotherReplicaHoldsTheOwnerOrTheRequest(String held)
            throws Exception {
        String message = message();
        UUID request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).requestId();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var replica =
                CompletableFuture.runAsync(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    owners.ofJob(account);
                    jdbcClient
                            .sql("SELECT 1 FROM " + held + " WHERE account_id = ? FOR UPDATE")
                            .param(account)
                            .query(Integer.class)
                            .list();
                    locked.countDown();
                    await(release);
                }));
        locked.await();

        var stop = envelope(IngressKind.CONTROL_STOP, "e2", message, FIRST, actor());
        assertThatThrownBy(() -> inTransaction(() -> {
                    jdbcClient.sql("SET LOCAL lock_timeout = '200ms'").update();
                    return intake.admit(stop, Producer.MENTOR);
                }))
                .isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(owner.queryForList(
                        "SELECT id FROM mentor_event WHERE binding_id = ? AND native_event_id = 'e2'",
                        UUID.class,
                        binding))
                .isEmpty();
        release.countDown();
        replica.join();

        assertThat(inTransaction(() -> intake.admit(stop, Producer.MENTOR)))
                .isEqualTo(admitted("e2", new RequestRef(request, 1)));
        assertThat(inTransaction(() -> intake.admit(stop, Producer.MENTOR))).isInstanceOf(Admission.Duplicate.class);
        assertThat(column(request, "delivery_generation")).isEqualTo(1L);
    }

    /**
     * S5a: an edit that authority no longer admits keeps the admitted revision, so no attempt answers the new words,
     * and fences the delivery of the old ones. The request stays live: its attempt fails on the revoked authority.
     */
    @Test
    void shouldKeepTheAdmittedRevisionAndFenceTheDeliveryWhenAnUnauthorizedEditChangesALiveQuestion() {
        String message = message();
        UUID request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).requestId();
        deny = true;

        assertThat(admit(IngressKind.MESSAGE_EDITED, "e2", message, EDITED)).isEqualTo(admitted("e2", null));
        assertThat(event("e2")).containsEntry("reason", "UNAUTHORIZED");
        assertThat(source(message)).containsEntry("revision", EDITED);
        assertThat(owner.queryForMap("SELECT * FROM mentor_request WHERE id = ?", request))
                .containsEntry("state", "OPEN")
                .containsEntry("version", 1)
                .containsEntry("admitted_revision", FIRST)
                .containsEntry("admitted_source_ref", "provider-event:1")
                .containsEntry("delivery_generation", 1L)
                .containsEntry("delivery_fence", "SOURCE_EDITED");
    }

    @Test
    void shouldMoveTheAdmittedRevisionWithTheVersionWhenAnAuthorizedEditChangesALiveQuestion() {
        String message = message();
        UUID request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).requestId();

        assertThat(admit(IngressKind.MESSAGE_EDITED, "e2", message, EDITED))
                .isEqualTo(admitted("e2", new RequestRef(request, 2)));
        assertThat(owner.queryForMap("SELECT * FROM mentor_request WHERE id = ?", request))
                .containsEntry("admitted_revision", EDITED)
                .containsEntry("admitted_source_ref", "provider-event:2")
                .containsEntry("delivery_generation", 0L);
    }

    /** S5b: an edit of a settled request changes nothing, so its answer still delivers; a delete fences it. */
    @Test
    void shouldLeaveTheDeliveryOfASettledRequestToAnEditAndFenceItOnADelete() {
        String message = message();
        UUID request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).requestId();
        owner.update("UPDATE mentor_request SET state = 'ANSWERED' WHERE id = ?", request);

        assertThat(admit(IngressKind.MESSAGE_EDITED, "e2", message, EDITED)).isEqualTo(admitted("e2", null));
        assertThat(source(message)).containsEntry("revision", EDITED);
        assertThat(column(request, "delivery_generation")).isEqualTo(0L);

        assertThat(admit(IngressKind.MESSAGE_DELETED, "e3", message, EDITED))
                .isEqualTo(admitted("e3", new RequestRef(request, 1)));
        assertThat(owner.queryForMap("SELECT * FROM mentor_request WHERE id = ?", request))
                .containsEntry("state", "ANSWERED")
                .containsEntry("delivery_generation", 1L)
                .containsEntry("delivery_fence", "SOURCE_DELETED");
    }

    /** S6: the owner row has no workspace, so a purge leaves it, and the account's next question opens normally. */
    @Test
    void shouldKeepTheOwnerRowAndOpenTheNextQuestionAfterAWorkspacePurge() {
        String message = message();
        UUID request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST)).requestId();
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> jdbcClient
                        .sql("SELECT public.mentor_erase_workspace(?)")
                        .param(workspace)
                        .query(Integer.class)
                        .single());

        assertThat(owner.queryForList("SELECT id FROM mentor_request WHERE id = ?", UUID.class, request))
                .isEmpty();
        assertThat(owner.queryForList("SELECT account_id FROM mentor_owner WHERE account_id = ?", Long.class, account))
                .containsExactly(account);
        assertThat(opened(admit(IngressKind.MESSAGE, "e2", message(), FIRST)).version())
                .isEqualTo(1);
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
        assertThat(owner.queryForList("SELECT id FROM mentor_source WHERE native_message_id = ?", UUID.class, message))
                .isEmpty();

        assertThat(opened(admit(IngressKind.MESSAGE, "e2", message, FIRST)).version())
                .isEqualTo(1);
        assertThat(source(message)).containsEntry("revision", FIRST);
    }

    /** Slack can deliver a delete before its create, so the delete leaves the tombstone that refuses the create. */
    @Test
    void shouldOpenNoRequestWhenTheDeleteArrivesBeforeTheCreate() {
        String message = message();
        assertThat(admit(IngressKind.MESSAGE_DELETED, "e1", message, EDITED)).isEqualTo(admitted("e1", null));
        assertThat(source(message))
                .containsEntry("state", "DELETED")
                .containsEntry("producer", "MENTOR")
                .containsEntry("account_id", account);

        assertThat(admit(IngressKind.MESSAGE, "e2", message, FIRST)).isEqualTo(admitted("e2", null));
        assertThat(event("e2")).containsEntry("reason", "SOURCE_DELETED");
        assertThat(requests(message)).isEmpty();
    }

    /** A create and a delete with no source yet: the second insert waits on the first one's source key, in either order. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldEndWithOneDeletedSourceInEitherCommitOrderWhenACreateAndADeleteRace(boolean deleteFirst)
            throws Exception {
        String message = message();
        IngressKind first = deleteFirst ? IngressKind.MESSAGE_DELETED : IngressKind.MESSAGE;
        IngressKind second = deleteFirst ? IngressKind.MESSAGE : IngressKind.MESSAGE_DELETED;
        var admitted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var holder = new AtomicInteger();
        var winner = CompletableFuture.supplyAsync(() -> inTransaction(() -> {
            holder.set(Objects.requireNonNull(jdbcClient
                    .sql("SELECT pg_backend_pid()")
                    .query(Integer.class)
                    .single()));
            Admission result = intake.admit(envelope(first, "e1", message, FIRST, actor()), Producer.MENTOR);
            admitted.countDown();
            await(commit);
            return result;
        }));
        assertThat(admitted.await(30, TimeUnit.SECONDS)).isTrue();
        var loser = CompletableFuture.supplyAsync(() -> admit(second, "e2", message, FIRST));
        try {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .until(() -> loser.isDone() || waitingOn(holder.get()) > 0);
            assertThat(loser.isDone()).isFalse();
            assertThat(waitingOn(holder.get())).isPositive();
        } finally {
            // A failed assertion must not leave the winner's transaction, and its locks, open.
            commit.countDown();
        }
        Admission one = winner.get(30, TimeUnit.SECONDS);
        Admission two = loser.get(30, TimeUnit.SECONDS);

        assertThat(owner.queryForList(
                        "SELECT state FROM mentor_source WHERE native_message_id = ?", String.class, message))
                .containsExactly("DELETED");
        if (deleteFirst) {
            assertThat(two).isEqualTo(admitted("e2", null));
            assertThat(event("e2")).containsEntry("reason", "SOURCE_DELETED");
            assertThat(requests(message)).isEmpty();
        } else {
            UUID request = opened(one).requestId();
            assertThat(two).isEqualTo(admitted("e2", new RequestRef(request, 1)));
            assertThat(requests(message)).containsExactly(request);
            assertThat(column(request, "state")).isEqualTo("STOPPED");
        }
    }

    /**
     * An unanswered source (its first create was not authorized) meets an authorized create and a delete. The one that
     * holds the source fails the other at once, and the other's retry sees what the first committed: the delete stops
     * the request that the create opened, or the create finds the tombstone.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldLeaveNoOpenRequestInEitherOrderWhenACreateAndADeleteMeetAnUnansweredSource(boolean deleteFirst)
            throws Exception {
        String message = message();
        var refused = envelope(IngressKind.MESSAGE, "e1", message, FIRST, actor());
        assertThat(inTransaction(() -> unauthorized.admit(refused, Producer.MENTOR)))
                .isEqualTo(admitted("e1", null));
        IngressKind first = deleteFirst ? IngressKind.MESSAGE_DELETED : IngressKind.MESSAGE;
        IngressKind second = deleteFirst ? IngressKind.MESSAGE : IngressKind.MESSAGE_DELETED;
        var admitted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var winner = CompletableFuture.supplyAsync(() -> inTransaction(() -> {
            Admission result = intake.admit(envelope(first, "e2", message, FIRST, actor()), Producer.MENTOR);
            admitted.countDown();
            await(commit);
            return result;
        }));
        assertThat(admitted.await(30, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> admit(second, "e3", message, FIRST))
                    .isInstanceOf(PessimisticLockingFailureException.class);
        } finally {
            commit.countDown();
        }
        Admission one = winner.get(30, TimeUnit.SECONDS);
        Admission retry = admit(second, "e3", message, FIRST);

        assertThat(source(message)).containsEntry("state", "DELETED");
        if (deleteFirst) {
            assertThat(retry).isEqualTo(admitted("e3", null));
            assertThat(event("e3")).containsEntry("reason", "SOURCE_DELETED");
            assertThat(requests(message)).isEmpty();
        } else {
            UUID request = opened(one).requestId();
            assertThat(retry).isEqualTo(admitted("e3", new RequestRef(request, 1)));
            assertThat(column(request, "state")).isEqualTo("STOPPED");
        }
    }

    /**
     * The delete's source statement takes its snapshot, a second create then commits, and only after that does the
     * delete lock the source row. A test-only restrictive policy holds the delete inside that one statement. Intake reads
     * the request after the lock, so the delete stops the request that the create opened (SONNET-V5433-delta2).
     */
    @Test
    void shouldStopTheRequestWhenADeleteLocksTheSourceAfterASecondCreateCommitted() throws Exception {
        String message = message();
        var refused = envelope(IngressKind.MESSAGE, "e1", message, FIRST, actor());
        assertThat(inTransaction(() -> unauthorized.admit(refused, Producer.MENTOR)))
                .isEqualTo(admitted("e1", null));
        var root = PostgreSQLTestContainer.administrator();
        var admin = new JdbcTemplate(new DriverManagerDataSource(root.jdbcUrl(), root.username(), root.password()));
        admin.execute("CREATE FUNCTION public.mentor_probe_gate() RETURNS boolean LANGUAGE plpgsql VOLATILE AS $$"
                + " BEGIN IF current_setting('mentor.probe', true) = 'on' THEN"
                + " PERFORM pg_advisory_xact_lock_shared(54330001); END IF; RETURN true; END $$");
        admin.execute("CREATE POLICY mentor_probe ON public.mentor_source AS RESTRICTIVE FOR SELECT"
                + " TO hephaestus_app USING (public.mentor_probe_gate())");
        try (var gate = DriverManager.getConnection(root.jdbcUrl(), root.username(), root.password());
                var statement = gate.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(54330001)");
            var delete = CompletableFuture.supplyAsync(() -> inTransaction(() -> {
                jdbcClient
                        .sql("SELECT set_config('mentor.probe', 'on', true)")
                        .query(String.class)
                        .single();
                return intake.admit(
                        envelope(IngressKind.MESSAGE_DELETED, "e3", message, FIRST, actor()), Producer.MENTOR);
            }));
            String waiting = "SELECT a.query FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid"
                    + " WHERE NOT l.granted AND l.locktype = 'advisory' AND l.objid = 54330001";
            try {
                Awaitility.await()
                        .atMost(Duration.ofSeconds(20))
                        .until(() -> delete.isDone()
                                || !admin.queryForList(waiting, String.class).isEmpty());
                assertThat(delete.isDone()).isFalse();
                assertThat(admin.queryForList(waiting, String.class))
                        .singleElement()
                        .asString()
                        .contains("FOR UPDATE OF s NOWAIT");

                UUID request =
                        opened(admit(IngressKind.MESSAGE, "e2", message, FIRST)).requestId();
                statement.execute("SELECT pg_advisory_unlock(54330001)");

                assertThat(delete.get(30, TimeUnit.SECONDS)).isEqualTo(admitted("e3", new RequestRef(request, 1)));
                assertThat(source(message)).containsEntry("state", "DELETED");
                assertThat(column(request, "state")).isEqualTo("STOPPED");
            } finally {
                statement.execute("SELECT pg_advisory_unlock_all()");
            }
        } finally {
            admin.execute("DROP POLICY mentor_probe ON public.mentor_source");
            admin.execute("DROP FUNCTION public.mentor_probe_gate()");
        }
    }

    @Test
    void shouldWriteNoRowForADeleteOnALegacyRoute() {
        String message = message();
        var delete = envelope(IngressKind.MESSAGE_DELETED, "e1", message, EDITED, actor());

        assertThat(inTransaction(() -> intake.admit(delete))).isEqualTo(admitted("e1", null));
        assertThat(owner.queryForList("SELECT id FROM mentor_source WHERE native_message_id = ?", UUID.class, message))
                .isEmpty();
        assertThat(owner.queryForList("SELECT id FROM mentor_event WHERE binding_id = ?", UUID.class, binding))
                .isEmpty();
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

    /** A tombstone alone refuses a late create: the source never had a request to answer it as a duplicate. */
    @Test
    void shouldOpenNoRequestWhenALateCreateArrivesForADeletedSourceThatNeverHadARequest() {
        String message = message();
        var first = envelope(IngressKind.MESSAGE, "e1", message, FIRST, actor());
        assertThat(inTransaction(() -> unauthorized.admit(first, Producer.MENTOR)))
                .isEqualTo(admitted("e1", null));
        assertThat(admit(IngressKind.MESSAGE_DELETED, "e2", message, EDITED)).isEqualTo(admitted("e2", null));

        assertThat(admit(IngressKind.MESSAGE, "e3", message, FIRST)).isEqualTo(admitted("e3", null));
        assertThat(event("e3")).containsEntry("reason", "SOURCE_DELETED");
        assertThat(requests(message)).isEmpty();
    }

    /** A held source fails a new question's receive at once: never Duplicate, never UNAUTHORIZED. */
    @Test
    void shouldFailTheReceiveWhenANewEventMeetsAHeldSource() throws Exception {
        String message = message();
        RequestRef request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var replica =
                CompletableFuture.runAsync(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    owners.ofJob(account);
                    jdbcClient
                            .sql("SELECT id FROM mentor_source WHERE native_message_id = ? FOR UPDATE")
                            .param(message)
                            .query(UUID.class)
                            .single();
                    held.countDown();
                    await(release);
                }));
        held.await();

        assertThatThrownBy(() -> admit(IngressKind.MESSAGE, "e2", message, FIRST))
                .isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(owner.queryForList(
                        "SELECT id FROM mentor_event WHERE binding_id = ? AND native_event_id = 'e2'",
                        UUID.class,
                        binding))
                .isEmpty();
        release.countDown();
        replica.join();
        assertThat(requests(message)).containsExactly(request.requestId());
    }

    /**
     * Intake reads a source's request only under the source lock, so a second create finds the first one's request.
     * The per-source key stays as the backstop: a second request opened directly raises on it and fails the receive, and
     * a retry records the event with exactly one request.
     */
    @Test
    void shouldKeepOneRequestWhenASecondRequestIsOpenedForTheSameSource() {
        String message = message();
        RequestRef request = opened(admit(IngressKind.MESSAGE, "e1", message, FIRST));
        UUID source = Objects.requireNonNull(
                owner.queryForObject("SELECT id FROM mentor_source WHERE native_message_id = ?", UUID.class, message));
        Instant now = Instant.now();
        var second = new MentorSourceRepository.OpenedRequest(
                UUID.randomUUID(),
                account,
                source,
                "SLACK",
                binding,
                "D" + binding,
                null,
                3,
                5,
                7,
                "PERSONAL",
                null,
                null,
                null,
                null,
                null,
                now,
                now.plus(MentorIntake.ANSWER_DEADLINE),
                FIRST,
                "provider-event:1");

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    owners.ofJob(account);
                    rows.openRequest(second);
                }))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(admit(IngressKind.MESSAGE, "e2", message, FIRST)).isEqualTo(admitted("e2", null));
        assertThat(event("e2")).containsEntry("reason", "ALREADY_OPENED");
        assertThat(requests(message)).containsExactly(request.requestId());
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

    /** The sessions that wait for a lock of this test's winner, by its backend: the loser of a forced-order race. */
    private int waitingOn(int holder) {
        return Objects.requireNonNull(owner.queryForObject(
                "SELECT count(DISTINCT l.pid) FROM pg_locks l WHERE NOT l.granted AND ? = ANY (pg_blocking_pids(l.pid))",
                Integer.class,
                holder));
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
