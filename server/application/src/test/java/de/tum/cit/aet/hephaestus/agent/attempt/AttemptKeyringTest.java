package de.tum.cit.aet.hephaestus.agent.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import de.tum.cit.aet.hephaestus.agent.config.ModelAssignmentLimits;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.integration.core.sync.MutableClock;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttemptKeyringTest extends BaseUnitTest {

    private static final UUID LEASE = UUID.fromString("ed34a2dc-7803-43e5-82c4-d2865087c41f");

    private final AttemptKeyFixtures.InMemoryStore store = new AttemptKeyFixtures.InMemoryStore();
    private final AttemptKeyring workerA = new AttemptKeyring(AttemptSigningKey.generate(), store);
    private final AttemptKeyring workerB = new AttemptKeyring(AttemptSigningKey.generate(), store);
    private final AttemptTokenIssuer issuerA = new AttemptTokenIssuer(workerA);
    private final AttemptTokenVerifier verifierB = new AttemptTokenVerifier(workerB, new SimpleMeterRegistry());

    @Test
    void shouldVerifyAReviewTokenOfAnotherWorkerOnThisWorker() {
        UUID review = UUID.randomUUID();
        String token = reviewToken(review);

        workerB.refresh();

        assertThat(verifierB.verify(token).id()).isEqualTo(review);
    }

    @Test
    void shouldVerifyATurnTokenOfAnotherWorkerOnThisWorker() {
        UUID turn = UUID.randomUUID();
        String token = issuerA.issueForTurn(
                turn,
                42L,
                1,
                LEASE,
                Instant.now().plusSeconds(300),
                Instant.now().plusSeconds(360));

        workerB.refresh();

        assertThat(verifierB.verify(token).id()).isEqualTo(turn);
    }

    /** A worker must not sign what the others cannot load: the first signature waits for the key's publication. */
    @Test
    void shouldPublishTheKeyBeforeItsFirstSignature() {
        assertThat(store.keys).isEmpty();

        reviewToken(UUID.randomUUID());

        assertThat(store.keys).hasSize(1);
    }

    /** A flood of forged kids costs the store one read per interval, however many requests it sends. */
    @Test
    void shouldReadTheStoreAtMostOncePerIntervalForForgedKids() {
        var clock = MutableClock.atFixedInstant();
        var replica = new AttemptKeyring(AttemptSigningKey.generate(), store, clock);
        var verifier = new AttemptTokenVerifier(replica, new SimpleMeterRegistry());
        replica.refresh();
        int reads = store.reads;
        AttemptSigningKey forger = AttemptSigningKey.generate();
        String forged = JWT.create()
                .withHeader(Map.of("kid", UUID.randomUUID().toString(), "typ", AttemptTokenIssuer.ATTEMPT_TOKEN_TYPE))
                .withIssuer(AttemptTokenIssuer.ISSUER)
                .withAudience(AttemptTokenIssuer.AUDIENCE)
                .sign(Algorithm.RSA256(forger.publicKey(), forger.privateKey()));

        for (int request = 0; request < 100; request++) {
            assertThatThrownBy(() -> verifier.verify(forged)).isInstanceOf(AttemptTokenInvalidException.class);
        }
        assertThat(store.reads).isEqualTo(reads + 1);

        clock.advance(AttemptKeyring.UNKNOWN_KID_RELOAD);
        for (int request = 0; request < 100; request++) {
            assertThatThrownBy(() -> verifier.verify(forged)).isInstanceOf(AttemptTokenInvalidException.class);
        }
        assertThat(store.reads).isEqualTo(reads + 2);
    }

    /**
     * A sandbox's call can reach any replica: a token of a replica that has just started verifies on another one before
     * that replica's next timed refresh.
     */
    @Test
    void shouldVerifyATokenOfAReplicaThatStartedAfterTheLastRefresh() {
        workerB.refresh();
        UUID review = UUID.randomUUID();

        String token = reviewToken(review);

        assertThat(verifierB.verify(token).id()).isEqualTo(review);
    }

    /** A published kid does not help a forger: the signature still has to match that worker's key. */
    @Test
    void shouldRefuseATokenThatNamesARealKidAndCarriesAnotherSignature() {
        reviewToken(UUID.randomUUID());
        workerB.refresh();
        String publishedKid = store.keys.keySet().iterator().next();
        AttemptSigningKey forger = AttemptSigningKey.generate();
        String forged = JWT.create()
                .withHeader(Map.of("kid", publishedKid, "typ", AttemptTokenIssuer.ATTEMPT_TOKEN_TYPE))
                .withIssuer(AttemptTokenIssuer.ISSUER)
                .withAudience(AttemptTokenIssuer.AUDIENCE)
                .sign(Algorithm.RSA256(forger.publicKey(), forger.privateKey()));

        assertThatThrownBy(() -> verifierB.verify(forged))
                .isInstanceOf(AttemptTokenInvalidException.class)
                .hasMessageContaining("verify failed");
    }

    @Test
    void shouldStopVerifyingAKeyThatExpiredFromTheStore() {
        String token = reviewToken(UUID.randomUUID());
        workerB.refresh();
        store.keys.clear();

        workerB.refresh();

        assertThatThrownBy(() -> verifierB.verify(token)).isInstanceOf(AttemptTokenInvalidException.class);
    }

    /** A worker's own tokens never depend on the store. */
    @Test
    void shouldKeepItsOwnKeyWhenTheStoreLostIt() {
        UUID review = UUID.randomUUID();
        String token = reviewToken(review);
        store.keys.clear();

        workerA.refresh();

        assertThat(new AttemptTokenVerifier(workerA, new SimpleMeterRegistry())
                        .verify(token)
                        .id())
                .isEqualTo(review);
    }

    /** A store outage must refuse no live attempt: the keys that this worker knows stay in use. */
    @Test
    void shouldKeepTheKnownKeysWhenALoadFails() {
        UUID review = UUID.randomUUID();
        String token = reviewToken(review);
        workerB.refresh();
        store.failing = true;

        assertThatThrownBy(workerB::refresh).isInstanceOf(IllegalStateException.class);

        assertThat(verifierB.verify(token).id()).isEqualTo(review);
    }

    /** A worker's gateway must not open with no keys: it would refuse a live attempt's token until its first refresh. */
    @Test
    void shouldPublishAndLoadTheKeysBeforeTheGatewayOpens() {
        reviewToken(UUID.randomUUID());
        var startingWorker = new AttemptKeyring(AttemptSigningKey.generate(), store);
        int published = store.keys.size();

        new AttemptTokenConfiguration.ScheduleConfiguration()
                .attemptKeyringStart(startingWorker, new SimpleMeterRegistry())
                .afterSingletonsInstantiated();

        assertThat(store.keys).hasSize(published + 1);
        assertThat(store.keys.keySet())
                .allSatisfy(kid -> assertThat(startingWorker.verifierFor(kid)).isNotNull());
    }

    /** A store that is down at start must not stop the worker: the failure is counted, and the timer tries again. */
    @Test
    void shouldCountAStartThatTheStoreRefusedAndKeepGoing() {
        store.failing = true;
        var meters = new SimpleMeterRegistry();

        new AttemptTokenConfiguration.ScheduleConfiguration()
                .attemptKeyringStart(workerB, meters)
                .afterSingletonsInstantiated();

        assertThat(meters.get(AgentMetrics.ATTEMPT_KEY_SYNC_FAILURES)
                        .tag("task", "refresh")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    /** The row must outlive every token that its key signed, or a live attempt loses its credential. */
    @Test
    void shouldKeepAKeyLongerThanAReviewAtItsLongestTimeout() {
        assertThat(AttemptKeyring.KEPT_FOR)
                .isGreaterThan(Duration.ofSeconds(ModelAssignmentLimits.MAX_TIMEOUT_SECONDS)
                        .plus(SandboxLayout.RESULT_UPLOAD_GRACE));
    }

    @Test
    void shouldIgnoreAnUnreadableRowAndKeepTheOthers() {
        UUID review = UUID.randomUUID();
        String token = reviewToken(review);
        store.keys.put("broken", new byte[] {1, 2, 3});

        workerB.refresh();

        assertThat(verifierB.verify(token).id()).isEqualTo(review);
    }

    private String reviewToken(UUID review) {
        Instant work = Instant.now().plusSeconds(300);
        return issuerA.issueForReview(review, 42L, 1, LEASE, work, work.plusSeconds(600));
    }
}
