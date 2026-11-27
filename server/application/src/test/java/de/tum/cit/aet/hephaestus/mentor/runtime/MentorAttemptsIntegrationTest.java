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
import de.tum.cit.aet.hephaestus.mentor.runtime.AttemptCredential.PayerKind;
import de.tum.cit.aet.hephaestus.mentor.runtime.MentorAttempt.WorkClass;
import de.tum.cit.aet.hephaestus.mentor.spi.Admission;
import de.tum.cit.aet.hephaestus.mentor.spi.Audience;
import de.tum.cit.aet.hephaestus.mentor.spi.AuthorityScope;
import de.tum.cit.aet.hephaestus.mentor.spi.Binding;
import de.tum.cit.aet.hephaestus.mentor.spi.IngressEnvelope;
import de.tum.cit.aet.hephaestus.mentor.spi.IngressKind;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorAuthorization;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorSourceEvidence;
import de.tum.cit.aet.hephaestus.mentor.spi.MessageRef;
import de.tum.cit.aet.hephaestus.mentor.spi.Producer;
import de.tum.cit.aet.hephaestus.mentor.spi.ProviderEventKey;
import de.tum.cit.aet.hephaestus.mentor.spi.ScopeStamp;
import de.tum.cit.aet.hephaestus.mentor.spi.Transport;
import de.tum.cit.aet.hephaestus.mentor.spi.VerifiedActor;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The attempt leases of R2-DESIGN.md (T11, T13b, Own, Fence, Renew): claims, reclaims, renewals and the dispatch fence. */
class MentorAttemptsIntegrationTest extends BaseIntegrationTest {
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final long MODEL = 7;
    private static final long CONNECTION = 9;

    @Autowired
    private MentorSourceRepository rows;

    @Autowired
    private MentorAttemptRepository attemptRows;

    /** The Slack adapter that the server wires for the mentor's store evidence. */
    @Autowired
    private MentorSourceEvidence sourceEvidence;

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

    private final JdbcTemplate owner = PostgreSQLTestContainer.ownerJdbc();
    private final long binding = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
    private final long workspace = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
    private MentorAuthorization authorization;
    private MentorIntake intake;
    private MentorAttempts attempts;
    private long account;
    /** What the authorization port answers for a stored stamp; false is a revoke. */
    private volatile boolean authorized = true;

    @BeforeEach
    void setUp() {
        account = linkedAccount();
        authorization = mock(MentorAuthorization.class);
        when(authorization.captureSlack(any(), any(), any())).thenAnswer(call -> {
            VerifiedActor actor = call.getArgument(0);
            Binding where = call.getArgument(1);
            return Optional.of(new ScopeStamp(
                    Objects.requireNonNull(actor.linkedAccountId()),
                    3,
                    5,
                    7,
                    new AuthorityScope.Slack(
                            where.workspaceId(), 11, where.connectionId(), 13, actor.nativeActorId())));
        });
        when(authorization.isCurrent(any(), any())).thenAnswer(call -> authorized);
        var provider = new StaticListableBeanFactory(Map.of("authorization", authorization))
                .getBeanProvider(MentorAuthorization.class);
        intake = new MentorIntake(rows, fence, owners, provider);
        attempts = new MentorAttempts(attemptRows, fence, owners, provider, store());
    }

    /** The Slack store's evidence port as the server wires it, for this test's own fence. */
    private ObjectProvider<MentorSourceEvidence> store() {
        return new StaticListableBeanFactory(Map.of("evidence", sourceEvidence))
                .getBeanProvider(MentorSourceEvidence.class);
    }

    /** T11: after a crash that followed intake, the queued attempt is leased once, however many replicas try. */
    @Test
    void shouldLeaseTheQueuedAttemptOnceWhenTwoReplicasClaimAfterIntake() {
        UUID request = ask(message());
        var barrier = new CyclicBarrier(2);
        List<CompletableFuture<List<AttemptLease>>> replicas = List.of(1, 2).stream()
                .map(replica -> CompletableFuture.supplyAsync(() -> {
                    await(barrier);
                    return claim(WorkClass.INTERACTIVE, 1);
                }))
                .toList();

        assertThat(replicas.stream().mapToInt(replica -> replica.join().size()).sum())
                .isEqualTo(1);
        assertThat(attempt(request))
                .containsEntry("state", "LEASED")
                .containsEntry("claims", 1)
                .containsEntry("lease_epoch", 1L);
        assertThat(requestState(request)).isEqualTo("RUNNING");
    }

    /** T13b: a lapsed lease is taken again under a new epoch and token, and its old holder can neither renew nor call. */
    @Test
    void shouldFailTheOldHolderWhenAReclaimTakesALapsedLease() {
        UUID request = ask(message());
        AttemptLease first = claimOne(WorkClass.INTERACTIVE);
        prepare(first);
        lapse(first);

        AttemptLease second = claimOne(WorkClass.INTERACTIVE);
        assertThat(second.attemptId()).isEqualTo(first.attemptId());
        assertThat(second.leaseEpoch()).isEqualTo(2);
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        observe(second);

        assertThat(renew(first, LEASE)).isFalse();
        assertThat(current(credential(first))).isFalse();
        assertThat(current(credential(second))).isTrue();
        assertThat(attempt(request)).containsEntry("claims", 2);
    }

    @Test
    void shouldEndReclaimsWhenTheAttemptHasHadAllItsClaims() {
        ask(message());
        for (int claim = 1; claim <= MentorAttempt.MAX_CLAIMS; claim++) lapse(claimOne(WorkClass.INTERACTIVE));

        assertThat(claim(WorkClass.INTERACTIVE, 1)).isEmpty();
    }

    @Test
    void shouldRefuseAClaimWhoseLeaseWouldAlreadyBeOver() {
        assertThatThrownBy(() -> attempts.claim(account, WorkClass.INTERACTIVE, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A claim with room left never takes a lease that still runs. */
    @Test
    void shouldSkipALiveLeaseWhenTheAccountHasRoomForAnother() {
        UUID request = ask(message());
        AttemptLease held = claimOne(WorkClass.INTERACTIVE);

        assertThat(claim(WorkClass.INTERACTIVE, 2)).isEmpty();
        assertThat(attempt(request))
                .containsEntry("claims", 1)
                .containsEntry("lease_epoch", held.leaseEpoch())
                .containsEntry("lease_token", held.leaseToken());
    }

    /** A newer version replaces the older attempt: the next claim ends it unless a lease still holds it. */
    @Test
    void shouldEndAReplacedAttemptAsStaleWhenTheNextClaimRunsUnlessALeaseHoldsIt() {
        String lapsed = message();
        String held = message();
        String queued = message();
        UUID lapsedRequest = ask(lapsed);
        UUID heldRequest = ask(held);
        claim(WorkClass.INTERACTIVE, 2).stream()
                .filter(lease -> lease.requestId().equals(lapsedRequest))
                .forEach(this::lapse);
        UUID queuedRequest = ask(queued);
        for (String message : List.of(lapsed, held, queued)) {
            admit(IngressKind.MESSAGE_EDITED, "x" + message, message, "1700000000.000200");
        }

        assertThat(claim(WorkClass.INTERACTIVE, 1))
                .singleElement()
                .extracting(AttemptLease::requestVersion)
                .isEqualTo(2);
        for (UUID replaced : List.of(lapsedRequest, queuedRequest)) {
            assertThat(attempt(replaced, 1))
                    .containsEntry("state", "ENDED")
                    .containsEntry("end_reason", "VERSION_STALE");
            assertThat(attempt(replaced, 1).get("ended_at")).isNotNull();
        }
        assertThat(attempt(heldRequest, 1)).containsEntry("state", "LEASED");
        assertThat(attempt(heldRequest, 1).get("end_reason")).isNull();
    }

    /** Own: an account runs at most two leases, and background work never takes the one a question needs. */
    @Test
    void shouldKeepALeaseForAQuestionWhenBackgroundWorkIsDue() {
        List<UUID> requests = List.of(ask(message()), ask(message()), ask(message()), ask(message()));
        for (UUID background : requests.subList(0, 3)) {
            owner.update("UPDATE mentor_attempt SET work_class = 'BACKGROUND' WHERE request_id = ?", background);
        }

        assertThat(claim(WorkClass.BACKGROUND, 5)).hasSize(1);
        assertThat(claim(WorkClass.INTERACTIVE, 5))
                .extracting(AttemptLease::requestId)
                .containsExactly(requests.get(3));
        assertThat(claim(WorkClass.BACKGROUND, 5)).isEmpty();
    }

    /** Own: two replicas that claim for one account at once never run more than its two leases. */
    @Test
    void shouldNeverLeaseMoreThanTheAccountsLimitWhenTwoReplicasClaimAtOnce() {
        for (int question = 0; question < 4; question++) ask(message());
        var barrier = new CyclicBarrier(2);
        List<CompletableFuture<List<AttemptLease>>> replicas = List.of(1, 2).stream()
                .map(replica -> CompletableFuture.supplyAsync(() -> {
                    await(barrier);
                    return claim(WorkClass.INTERACTIVE, 2);
                }))
                .toList();
        replicas.forEach(CompletableFuture::join);

        assertThat(claim(WorkClass.INTERACTIVE, 2)).isEmpty();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM mentor_attempt WHERE account_id = ? AND state = 'LEASED'",
                        Long.class,
                        account))
                .isEqualTo((long) MentorAttempts.OWNER_LEASES);
    }

    /** Fence: the credential must match the held claim and its frozen route, and the request's authority still holds. */
    @Test
    void shouldAdmitADispatchOnlyForTheHeldClaimOnItsFrozenRoute() {
        ask(message());
        AttemptLease lease = claimOne(WorkClass.INTERACTIVE);
        prepare(lease);
        observe(lease);
        AttemptCredential held = credential(lease);
        assertThat(current(held)).isTrue();

        List<Function<AttemptCredential, AttemptCredential>> wrong = List.of(
                c -> new AttemptCredential(
                        linkedAccount(),
                        c.attemptId(),
                        c.leaseEpoch(),
                        c.leaseToken(),
                        MODEL,
                        CONNECTION,
                        c.payer(),
                        c.payerWorkspaceId()),
                c -> new AttemptCredential(
                        c.accountId(),
                        c.attemptId(),
                        c.leaseEpoch() + 1,
                        c.leaseToken(),
                        MODEL,
                        CONNECTION,
                        c.payer(),
                        c.payerWorkspaceId()),
                c -> new AttemptCredential(
                        c.accountId(),
                        c.attemptId(),
                        c.leaseEpoch(),
                        UUID.randomUUID(),
                        MODEL,
                        CONNECTION,
                        c.payer(),
                        c.payerWorkspaceId()),
                c -> new AttemptCredential(
                        c.accountId(),
                        c.attemptId(),
                        c.leaseEpoch(),
                        c.leaseToken(),
                        MODEL + 1,
                        CONNECTION,
                        c.payer(),
                        c.payerWorkspaceId()),
                c -> new AttemptCredential(
                        c.accountId(),
                        c.attemptId(),
                        c.leaseEpoch(),
                        c.leaseToken(),
                        MODEL,
                        CONNECTION + 1,
                        c.payer(),
                        c.payerWorkspaceId()),
                c -> new AttemptCredential(
                        c.accountId(),
                        c.attemptId(),
                        c.leaseEpoch(),
                        c.leaseToken(),
                        MODEL,
                        CONNECTION,
                        PayerKind.PERSONAL_PURSE,
                        null),
                c -> new AttemptCredential(
                        c.accountId(),
                        c.attemptId(),
                        c.leaseEpoch(),
                        c.leaseToken(),
                        MODEL,
                        CONNECTION,
                        c.payer(),
                        workspace + 1));
        for (var change : wrong) assertThat(current(change.apply(held))).isFalse();
    }

    @Test
    void shouldRefuseADispatchWhenTheLeaseTheRequestOrTheObservationNoLongerHolds() {
        String message = message();
        ask(message);
        AttemptLease lease = claimOne(WorkClass.INTERACTIVE);
        prepare(lease);
        AttemptCredential held = credential(lease);

        assertThat(current(held)).as("no observation bound").isFalse();
        observe(lease);
        assertThat(current(held)).isTrue();
        authorized = false;
        assertThat(current(held)).as("revoked").isFalse();
        authorized = true;
        owner.update(
                "UPDATE mentor_attempt SET observed_at = clock_timestamp() - interval '2 minutes' WHERE id = ?",
                lease.attemptId());
        assertThat(current(held)).as("stale observation").isFalse();
        owner.update(
                "UPDATE mentor_attempt SET observed_epoch = observed_epoch - 1, observed_at = clock_timestamp()"
                        + " WHERE id = ?",
                lease.attemptId());
        assertThat(current(held)).as("observation of another epoch").isFalse();
        observe(lease);
        owner.update(
                "UPDATE mentor_attempt SET deadline = clock_timestamp() - interval '1 second' WHERE id = ?",
                lease.attemptId());
        assertThat(current(held)).as("past the work deadline").isFalse();
        owner.update(
                "UPDATE mentor_attempt SET deadline = clock_timestamp() + interval '5 minutes' WHERE id = ?",
                lease.attemptId());
        assertThat(current(held)).isTrue();
        owner.update(
                "UPDATE mentor_request SET deadline = clock_timestamp() - interval '1 second' WHERE id = ?",
                lease.requestId());
        assertThat(current(held)).as("past the request deadline").isFalse();
        owner.update(
                "UPDATE mentor_request SET deadline = clock_timestamp() + interval '1 hour' WHERE id = ?",
                lease.requestId());
        lapse(lease);
        assertThat(current(held)).as("the lease lapsed").isFalse();
        owner.update(
                "UPDATE mentor_attempt SET leased_until = clock_timestamp() + interval '2 minutes' WHERE id = ?",
                lease.attemptId());
        assertThat(current(held)).isTrue();
        var unwired = new MentorAttempts(
                attemptRows,
                fence,
                owners,
                new StaticListableBeanFactory(Map.of()).getBeanProvider(MentorAuthorization.class),
                store());
        assertThat(new TransactionTemplate(transactions).execute(status -> unwired.current(held)))
                .as("no authorization port")
                .isFalse();

        admit(IngressKind.MESSAGE_EDITED, "e9", message, "1700000009.000100");
        assertThat(current(held)).as("the version moved").isFalse();
        assertThat(renew(lease, LEASE)).as("the version moved").isFalse();
    }

    /**
     * A delete that intake never admitted, because its retries ran out while the request was held, still refuses: the
     * Slack store's tombstone is read before each dispatch, and an unwired store port refuses too.
     */
    @Test
    void shouldRefuseADispatchWhenTheSlackStoreShowsTheQuestionDeleted() {
        String message = message();
        ask(message);
        AttemptLease lease = claimOne(WorkClass.INTERACTIVE);
        prepare(lease);
        observe(lease);
        AttemptCredential held = credential(lease);
        assertThat(current(held))
                .as("the store has no row: no tombstone to refuse on")
                .isTrue();

        var storeless = new MentorAttempts(
                attemptRows,
                fence,
                owners,
                new StaticListableBeanFactory(Map.of("authorization", authorization))
                        .getBeanProvider(MentorAuthorization.class),
                new StaticListableBeanFactory(Map.of()).getBeanProvider(MentorSourceEvidence.class));
        assertThat(new TransactionTemplate(transactions).execute(status -> storeless.current(held)))
                .as("no store port")
                .isFalse();

        tombstoneInSlackStore(message);
        assertThat(current(held)).as("the Slack store tombstoned the question").isFalse();
        assertThat(owner.queryForObject(
                        "SELECT state FROM mentor_source WHERE native_message_id = ?", String.class, message))
                .as("intake never saw the delete")
                .isEqualTo("LIVE");
    }

    /**
     * A DM is never in the Slack store, so a deleted DM has no tombstone, and intake may never have seen its delete.
     * The fresh observation, which reads the message from Slack, is then the only witness: without one bound at this
     * epoch and at most 60 seconds old, a DM question gets no dispatch.
     */
    @Test
    void shouldRefuseADispatchForADirectMessageWithoutAFreshObservation() {
        String message = message();
        ask(message);
        AttemptLease lease = claimOne(WorkClass.INTERACTIVE);
        prepare(lease);
        AttemptCredential held = credential(lease);
        assertThat(owner.queryForObject("""
                        SELECT r.audience_container_id = 'D' || r.audience_connection_id AND r.audience_thread_root IS NULL
                            AND s.state = 'LIVE'
                        FROM mentor_request r JOIN mentor_source s ON s.id = r.source_id WHERE r.id = ?
                        """, Boolean.class, lease.requestId()))
                .as("a live DM question at the top level")
                .isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM slack_message WHERE slack_ts = ?", Long.class, message))
                .as("no Slack store row for the DM")
                .isZero();

        assertThat(current(held)).as("no observation").isFalse();
        observe(lease);
        owner.update(
                "UPDATE mentor_attempt SET observed_at = clock_timestamp() - interval '61 seconds' WHERE id = ?",
                lease.attemptId());
        assertThat(current(held)).as("an observation older than 60 seconds").isFalse();
        observe(lease);
        assertThat(current(held)).as("a fresh observation of the DM").isTrue();
    }

    @Test
    void shouldRefuseADispatchAndARenewWhenTheOwnerStopsTheRequest() {
        String message = message();
        ask(message);
        AttemptLease lease = claimOne(WorkClass.INTERACTIVE);
        prepare(lease);
        observe(lease);

        admit(IngressKind.CONTROL_STOP, "e8", message, "1700000000.000100");
        assertThat(current(credential(lease))).isFalse();
        assertThat(renew(lease, LEASE)).isFalse();
    }

    /** Renew: a lease never runs past the attempt's work deadline, and a lapsed lease cannot be renewed. */
    @Test
    void shouldCapARenewAtTheWorkDeadlineAndRefuseItAfterTheLeaseLapsed() {
        UUID request = ask(message());
        AttemptLease lease = claimOne(WorkClass.INTERACTIVE);
        owner.update(
                "UPDATE mentor_attempt SET deadline = clock_timestamp() + interval '5 seconds' WHERE id = ?",
                lease.attemptId());

        assertThat(renew(lease, Duration.ofMinutes(10))).isTrue();
        assertThat(owner.queryForObject(
                        "SELECT leased_until <= deadline FROM mentor_attempt WHERE id = ?",
                        Boolean.class,
                        lease.attemptId()))
                .isTrue();
        lapse(lease);
        assertThat(renew(lease, LEASE)).isFalse();
        assertThat(requestState(request)).isEqualTo("RUNNING");
    }

    private UUID ask(String message) {
        var admission = admit(IngressKind.MESSAGE, "e" + message, message, "1700000000.000100");
        return Objects.requireNonNull(((Admission.Admitted) admission).request())
                .requestId();
    }

    private Admission admit(IngressKind kind, String event, String message, String revision) {
        Instant now = Instant.now();
        var envelope = new IngressEnvelope(
                kind,
                new ProviderEventKey(binding, event),
                new VerifiedActor("U" + account, account),
                new Binding(workspace, binding, "T" + binding),
                new Audience(Transport.SLACK, binding, "D" + binding, null),
                new MessageRef(message, revision),
                "provider-event:1",
                now,
                now);
        return inTransaction(() -> intake.admit(envelope, Producer.MENTOR));
    }

    /** A claim in its own transaction, as each scheduler pass makes it. */
    private List<AttemptLease> claim(WorkClass workClass, int limit) {
        return Objects.requireNonNull(new TransactionTemplate(transactions)
                .execute(status -> attempts.claim(account, workClass, limit, LEASE)));
    }

    private AttemptLease claimOne(WorkClass workClass) {
        var leases = claim(workClass, 1);
        assertThat(leases).hasSize(1);
        return leases.getFirst();
    }

    /** What prepare (R2a4) freezes: the payer, model and connection of the route. */
    private void prepare(AttemptLease lease) {
        owner.update("""
                UPDATE mentor_attempt SET route_payer = 'WORKSPACE', route_workspace_id = ?, route_model_id = ?,
                    route_connection_id = ? WHERE id = ?
                """, workspace, MODEL, CONNECTION, lease.attemptId());
    }

    /** What bind (R2a4) stores: an observation taken for this lease epoch, now. */
    private void observe(AttemptLease lease) {
        owner.update("""
                UPDATE mentor_attempt SET observed_epoch = ?, observed_at = clock_timestamp(), observed_reader_epoch = 5,
                    observed_readers = ARRAY['U' || account_id] WHERE id = ?
                """, lease.leaseEpoch(), lease.attemptId());
    }

    /** What the Slack store writes for a delete that its own ingest admitted; the workspace row is not needed. */
    private void tombstoneInSlackStore(String message) {
        owner.execute((ConnectionCallback<Void>) connection -> {
            try (var role = connection.createStatement();
                    var insert = connection.prepareStatement("""
                            INSERT INTO slack_message (workspace_id, slack_team_id, slack_channel_id, slack_ts, text,
                                deleted_at, ingested_at)
                            VALUES (?, ?, ?, ?, NULL, clock_timestamp(), clock_timestamp())
                            """)) {
                role.execute("SET session_replication_role = 'replica'");
                try {
                    insert.setLong(1, workspace);
                    insert.setString(2, "T" + binding);
                    insert.setString(3, "D" + binding);
                    insert.setString(4, message);
                    insert.executeUpdate();
                } finally {
                    role.execute("SET session_replication_role = 'origin'");
                }
            }
            return null;
        });
    }

    private void lapse(AttemptLease lease) {
        owner.update(
                "UPDATE mentor_attempt SET leased_until = clock_timestamp() - interval '1 second' WHERE id = ?",
                lease.attemptId());
    }

    private AttemptCredential credential(AttemptLease lease) {
        return new AttemptCredential(
                lease.accountId(),
                lease.attemptId(),
                lease.leaseEpoch(),
                lease.leaseToken(),
                MODEL,
                CONNECTION,
                PayerKind.WORKSPACE,
                workspace);
    }

    private boolean renew(AttemptLease lease, Duration duration) {
        return Objects.requireNonNull(
                new TransactionTemplate(transactions).execute(status -> attempts.renew(lease, duration)));
    }

    private boolean current(AttemptCredential credential) {
        return Objects.requireNonNull(
                new TransactionTemplate(transactions).execute(status -> attempts.current(credential)));
    }

    private Map<String, @Nullable Object> attempt(UUID request) {
        return owner.queryForMap("SELECT * FROM mentor_attempt WHERE request_id = ?", request);
    }

    private Map<String, @Nullable Object> attempt(UUID request, int version) {
        return owner.queryForMap(
                "SELECT * FROM mentor_attempt WHERE request_id = ? AND request_version = ?", request, version);
    }

    private @Nullable String requestState(UUID request) {
        return owner.queryForObject("SELECT state FROM mentor_request WHERE id = ?", String.class, request);
    }

    private Admission inTransaction(Supplier<Admission> work) {
        return Objects.requireNonNull(new TransactionTemplate(transactions).execute(status -> work.get()));
    }

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
