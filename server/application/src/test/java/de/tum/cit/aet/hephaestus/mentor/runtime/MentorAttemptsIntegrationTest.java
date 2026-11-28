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
import de.tum.cit.aet.hephaestus.mentor.runtime.MentorAttempt.WorkClass;
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
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The attempt claims of R2-DESIGN.md (T11, T13b, Own): claims, reclaims and the account's lease limits. */
class MentorAttemptsIntegrationTest extends BaseIntegrationTest {
    private static final Duration LEASE = Duration.ofMinutes(2);

    @Autowired
    private MentorSourceRepository rows;

    @Autowired
    private MentorAttemptRepository attemptRows;

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
    private MentorIntake intake;
    private MentorAttempts attempts;
    private long account;

    @BeforeEach
    void setUp() {
        account = linkedAccount();
        MentorAuthorization authorization = mock(MentorAuthorization.class);
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
        intake = new MentorIntake(
                rows,
                fence,
                owners,
                new StaticListableBeanFactory(Map.of("authorization", authorization))
                        .getBeanProvider(MentorAuthorization.class));
        attempts = new MentorAttempts(attemptRows, fence, owners);
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

    /** T13b: a lapsed lease is taken again under a new epoch and token, so its old holder's claim no longer matches. */
    @Test
    void shouldFailTheOldHolderWhenAReclaimTakesALapsedLease() {
        UUID request = ask(message());
        AttemptLease first = claimOne(WorkClass.INTERACTIVE);
        lapse(first);

        AttemptLease second = claimOne(WorkClass.INTERACTIVE);
        assertThat(second.attemptId()).isEqualTo(first.attemptId());
        assertThat(second.leaseEpoch()).isEqualTo(2);
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(attempt(request))
                .containsEntry("claims", 2)
                .containsEntry("lease_epoch", 2L)
                .containsEntry("lease_token", second.leaseToken());
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

    private void lapse(AttemptLease lease) {
        owner.update(
                "UPDATE mentor_attempt SET leased_until = clock_timestamp() - interval '1 second' WHERE id = ?",
                lease.attemptId());
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
