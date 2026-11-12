package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore.Arrival;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore.Claimed;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ProviderEventStoreIntegrationTest extends BaseIntegrationTest {

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private ProviderEventStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        databaseTestUtils.cleanDatabase();
    }

    private static Arrival arrival(String subject, String delivery) {
        return new Arrival(
                null,
                "github",
                subject,
                delivery,
                Map.of("X-GitHub-Event", "issues"),
                ("{\"delivery\":\"" + delivery + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shouldStoreADeliveryOnceWhenTheProviderSendsItAgain() {
        assertThat(store.store(List.of(arrival("github.?.?.installation", "d-1"))))
                .isEqualTo(1);
        assertThat(store.store(List.of(arrival("github.?.?.installation", "d-1"))))
                .isZero();

        Claimed claimed = store.claim(LEASE).orElseThrow();
        assertThat(claimed.workspaceId()).isNull();
        assertThat(claimed.event().subject()).isEqualTo("github.?.?.installation");
        assertThat(claimed.event().header("X-GitHub-Event")).isEqualTo("issues");
        assertThat(new String(claimed.event().payload(), StandardCharsets.UTF_8))
                .contains("d-1");
        assertThat(claimed.attempt()).isEqualTo(1);
        assertThat(store.claim(LEASE)).isEmpty();
    }

    @Test
    void shouldSerializeEveryInstallationControlEventEvenWhenItsSubjectNamesAnOrganization() {
        store.store(List.of(
                arrival("github.?.?.installation", "global-install"),
                arrival("github.?.?.installation_repositories", "global-repositories"),
                arrival("github.org.?.installation_target", "global-target")));

        Claimed first = store.claim(LEASE).orElseThrow();
        assertThat(store.claim(LEASE)).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(DISTINCT scope_key) FROM provider_event WHERE delivery_key LIKE ?",
                        Integer.class,
                        "global-%"))
                .isEqualTo(1);
        assertThat(store.complete(first.id(), first.leaseEpoch())).isTrue();
        assertThat(store.claim(LEASE)).isPresent();
    }

    @Test
    void shouldKeepTheOrderOfAScopeAndRunOtherScopesBesideIt() {
        store.store(List.of(
                arrival("github.?.?.installation", "a-1"),
                arrival("github.?.?.installation_repositories", "a-2"),
                arrival("github.?.?.other", "a-3")));
        jdbc.update("UPDATE provider_event SET subject = 'github.org.?.organization', "
                + "scope_key = 'installation:github.org.?' WHERE delivery_key = 'a-3'");

        Claimed first = store.claim(LEASE).orElseThrow();
        Claimed other = store.claim(LEASE).orElseThrow();
        assertThat(delivery(first)).isEqualTo("a-1");
        assertThat(delivery(other)).isEqualTo("a-3");
        // The second event of the first scope waits until the first ends.
        assertThat(store.claim(LEASE)).isEmpty();

        assertThat(store.complete(first.id(), first.leaseEpoch())).isTrue();
        assertThat(delivery(store.claim(LEASE).orElseThrow())).isEqualTo("a-2");
    }

    @Test
    void shouldLeaseOnlyTheHeadOfEachScopeWhenClaimersRace() throws Exception {
        for (int index = 0; index < 4; index++) {
            store.store(List.of(arrival("github.?.?.installation", "x-" + index)));
            store.store(List.of(arrival("github.?.?.installation", "y-" + index)));
        }
        jdbc.update("UPDATE provider_event SET subject = 'github.org.?.organization', "
                + "scope_key = 'installation:github.org.?' WHERE delivery_key LIKE 'y-%'");

        int claimers = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<Claimed>>> claims = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(claimers)) {
            for (int index = 0; index < claimers; index++) {
                claims.add(pool.submit(() -> {
                    start.await();
                    return store.claim(LEASE);
                }));
            }
            start.countDown();
            List<String> leased = new ArrayList<>();
            for (Future<Optional<Claimed>> claim : claims) {
                claim.get(30, TimeUnit.SECONDS)
                        .map(ProviderEventStoreIntegrationTest::delivery)
                        .ifPresent(leased::add);
            }
            assertThat(leased).containsExactlyInAnyOrder("x-0", "y-0");
        }
    }

    @Test
    void shouldHoldItsScopeWhileAFailedEventWaitsForItsRetry() {
        store.store(List.of(
                arrival("github.?.?.installation", "r-1"), arrival("github.?.?.installation_repositories", "r-2")));

        Claimed failed = store.claim(LEASE).orElseThrow();
        assertThat(store.retry(failed.id(), failed.leaseEpoch(), Duration.ofHours(1), true))
                .isTrue();

        assertThat(store.claim(LEASE)).isEmpty();
        jdbc.update("UPDATE provider_event SET available_at = now() WHERE id = ?", failed.id());
        Claimed again = store.claim(LEASE).orElseThrow();
        assertThat(again.id()).isEqualTo(failed.id());
        assertThat(again.attempt()).isEqualTo(2);
    }

    @Test
    void shouldRefuseTheOutcomeOfALeaseThatWasTakenBack() {
        store.store(List.of(arrival("github.?.?.installation", "f-1")));
        Claimed stale = store.claim(LEASE).orElseThrow();
        jdbc.update("UPDATE provider_event SET leased_until = now() - interval '1 second' WHERE id = ?", stale.id());

        assertThat(store.reapExpired(5)).isEqualTo(1);
        Claimed current = store.claim(LEASE).orElseThrow();

        assertThat(store.complete(stale.id(), stale.leaseEpoch())).isFalse();
        assertThat(store.complete(current.id(), current.leaseEpoch())).isTrue();
    }

    @Test
    void shouldEndAnEventThatUsedItsAttemptsAsADeadLetterWhenItsLeaseExpires() {
        store.store(List.of(arrival("github.?.?.installation", "x-1")));
        Claimed claimed = store.claim(LEASE).orElseThrow();
        jdbc.update("UPDATE provider_event SET leased_until = now() - interval '1 second' WHERE id = ?", claimed.id());

        assertThat(store.reapExpired(1)).isEqualTo(1);

        assertThat(store.deadLetters(null, 0, 10))
                .singleElement()
                .satisfies(dead -> assertThat(dead.errorClass()).isEqualTo("LeaseExpired"));
    }

    @Test
    void shouldReplayADeadLetterWithItsPayloadAndKeepItsCommittedSteps() {
        store.store(List.of(arrival("github.?.?.installation", "p-1")));
        Claimed claimed = store.claim(LEASE).orElseThrow();
        assertThat(store.claimStep(claimed.id(), claimed.leaseEpoch(), "handler.github.installation"))
                .isTrue();
        assertThat(store.dead(claimed.id(), claimed.leaseEpoch(), "java.lang.IllegalStateException"))
                .isTrue();

        Optional<ProviderEventStore.DeadLetter> replayed = store.replay(null, claimed.id(), 42L);

        assertThat(replayed).hasValueSatisfying(dead -> {
            assertThat(dead.errorClass()).isEqualTo("java.lang.IllegalStateException");
            assertThat(dead.replayedBy()).isEqualTo(42L);
        });
        assertThat(store.replay(null, claimed.id(), 42L)).isEmpty();
        Claimed again = store.claim(LEASE).orElseThrow();
        assertThat(again.id()).isEqualTo(claimed.id());
        assertThat(again.attempt()).isEqualTo(1);
        assertThat(delivery(again)).isEqualTo("p-1");
        assertThat(store.hasStep(again.id(), "handler.github.installation")).isTrue();
        assertThat(store.claimStep(again.id(), again.leaseEpoch(), "handler.github.installation"))
                .isFalse();
    }

    @Test
    void shouldDeleteHandledEventsAfterTheirWindowAndKeepTheOpenOnes() {
        store.store(List.of(arrival("github.?.?.installation", "t-1"), arrival("github.?.?.other", "t-2")));
        jdbc.update("UPDATE provider_event SET scope_key = 'installation:github.?.?.t2' WHERE delivery_key = 't-2'");
        Claimed done = store.claim(LEASE).orElseThrow();
        store.complete(done.id(), done.leaseEpoch());

        Instant later = Instant.now().plus(Duration.ofDays(1));
        assertThat(store.deleteEnded(later, Instant.EPOCH)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_event", Long.class))
                .isEqualTo(1L);
    }

    private static String delivery(Claimed claimed) {
        String payload = new String(claimed.event().payload(), StandardCharsets.UTF_8);
        return payload.substring(payload.indexOf(":\"") + 2, payload.lastIndexOf('"'));
    }
}
