package de.tum.cit.aet.hephaestus.integration.core.framework;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.ProofReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.VisibilityClass;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class ResourceReadersTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    private static final Duration MAX_AGE = Duration.ofDays(2);

    private static String proof(ResourceReaders readers) {
        return readers.getVisibilityClass() + " " + readers.getProofState() + " " + readers.getProofReason();
    }

    @Test
    void shouldWaitForItsReadersUntilTheProviderAnswers() {
        var readers = new ResourceReaders();

        assertThat(proof(readers)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
        assertThat(readers.trustedClass(NOW, MAX_AGE)).isEqualTo(VisibilityClass.UNPROVEN);
    }

    @Test
    void shouldOpenAResourceThatTheProviderConfirmedPublic() {
        var readers = new ResourceReaders();

        assertThat(readers.confirmedPublic(readers.getProofGeneration(), NOW)).isTrue();

        assertThat(proof(readers)).isEqualTo("WORKSPACE PROVEN PUBLIC_ON_PROVIDER");
        assertThat(readers.trustedClass(NOW.plus(Duration.ofHours(47)), MAX_AGE))
                .isEqualTo(VisibilityClass.WORKSPACE);
    }

    @Test
    void shouldStopTrustingAProofThatNoAnswerRenewedForTooLong() {
        var readers = new ResourceReaders();
        readers.confirmedPublic(readers.getProofGeneration(), NOW);

        assertThat(readers.trustedClass(NOW.plus(MAX_AGE), MAX_AGE))
                .as("an expired proof counts as unproven, also while its stored class still says public")
                .isEqualTo(VisibilityClass.UNPROVEN);
        assertThat(readers.getVisibilityClass()).isEqualTo(VisibilityClass.WORKSPACE);
    }

    /** Two reads overlap; the one that started first answers last. Either order ends narrow. */
    @Test
    void shouldKeepANarrowingAnswerThatArrivesBeforeAnOverlappingWideningOne() {
        var readers = new ResourceReaders();
        long first = readers.getProofGeneration();
        long second = readers.getProofGeneration();

        readers.confirmedNotPublic();
        boolean widened = readers.confirmedPublic(second, NOW);

        assertThat(widened).isFalse();
        assertThat(proof(readers)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void shouldNarrowAfterAnOverlappingWideningAnswerThatArrivedFirst() {
        var readers = new ResourceReaders();
        long started = readers.getProofGeneration();

        readers.confirmedPublic(started, NOW);
        readers.confirmedNotPublic();

        assertThat(proof(readers)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
    }

    /**
     * The order of answers decides, not the clocks of the replicas that applied them: a widening answer stamped by a
     * clock that runs ahead does not undo a narrowing answer that applied after its read started.
     */
    @Test
    void shouldIgnoreTheReplicasClocksWhenOrderingAnswers() {
        var readers = new ResourceReaders();
        long started = readers.getProofGeneration();
        readers.unavailable();

        boolean widened = readers.confirmedPublic(started, NOW.plus(Duration.ofDays(365)));

        assertThat(widened).isFalse();
        assertThat(proof(readers)).isEqualTo("UNPROVEN FAILED RESOURCE_UNAVAILABLE");
        assertThat(readers.trustedClass(NOW, MAX_AGE)).isEqualTo(VisibilityClass.UNPROVEN);
    }

    @Test
    void shouldLetAReadThatStartedAfterTheLastAnswerWiden() {
        var readers = new ResourceReaders();
        readers.unavailable();

        assertThat(readers.confirmedPublic(readers.getProofGeneration(), NOW)).isTrue();
        assertThat(proof(readers)).isEqualTo("WORKSPACE PROVEN PUBLIC_ON_PROVIDER");
    }

    @Test
    void shouldForgetTheProofWhenTheMonitorNamesAnotherResource() {
        var readers = new ResourceReaders();
        readers.confirmedPublic(readers.getProofGeneration(), NOW);
        long readOfTheOldResource = readers.getProofGeneration();

        readers.identityChanged();

        assertThat(proof(readers)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
        assertThat(readers.confirmedPublic(readOfTheOldResource, NOW))
                .as("a read that began before the change was about the old resource")
                .isFalse();
    }

    @Test
    void shouldNotTrustAProofStampedByAClockThatRunsAhead() {
        var readers = new ResourceReaders();
        readers.confirmedPublic(readers.getProofGeneration(), NOW.plusSeconds(30));

        assertThat(readers.trustedClass(NOW, MAX_AGE)).isEqualTo(VisibilityClass.UNPROVEN);
        assertThat(readers.trustedClass(NOW.plusSeconds(30), MAX_AGE)).isEqualTo(VisibilityClass.WORKSPACE);
    }

    @Test
    void shouldRestrictAResourceWhoseReadersTheProviderListed() {
        var readers = new ResourceReaders();

        assertThat(readers.readersListed(readers.getProofGeneration(), NOW)).isTrue();

        assertThat(proof(readers)).isEqualTo("RESTRICTED PROVEN READERS_LISTED");
        assertThat(readers.trustedClass(NOW, MAX_AGE)).isEqualTo(VisibilityClass.RESTRICTED);
    }

    @Test
    void shouldNotApplyAListingThatOverlappedAnotherAnswer() {
        var readers = new ResourceReaders();
        long started = readers.getProofGeneration();
        readers.readersListed(readers.getProofGeneration(), NOW);

        assertThat(readers.readersListed(started, NOW)).isFalse();
    }

    /** A metadata read that shows the repository private says nothing new about its readers. */
    @Test
    void shouldKeepAListingWhenTheProviderShowsTheResourceNotPublic() {
        var readers = new ResourceReaders();
        readers.readersListed(readers.getProofGeneration(), NOW);
        long publicReadStarted = readers.getProofGeneration();

        readers.confirmedNotPublic();

        assertThat(proof(readers)).isEqualTo("RESTRICTED PROVEN READERS_LISTED");
        assertThat(readers.confirmedPublic(publicReadStarted, NOW))
                .as("a public answer from a read that overlapped the not-public one does not apply")
                .isFalse();
    }

    @Test
    void shouldLetAResourceThatTheProviderShowsAgainWaitForItsReaders() {
        var readers = new ResourceReaders();
        readers.unavailable();

        readers.confirmedNotPublic();

        assertThat(proof(readers)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
    }

    @Test
    void shouldStopTrustingTheReadersWhenAListingStops() {
        var readers = new ResourceReaders();
        readers.readersListed(readers.getProofGeneration(), NOW);

        readers.listingStopped(ProofReason.RATE_LIMITED);

        assertThat(proof(readers)).isEqualTo("RESTRICTED PENDING RATE_LIMITED");
        assertThat(readers.trustedClass(NOW, MAX_AGE)).isEqualTo(VisibilityClass.UNPROVEN);
    }

    @Test
    void shouldRefuseAStoppedListingWithAReasonThatIsNotAStop() {
        var readers = new ResourceReaders();

        assertThatThrownBy(() -> readers.listingStopped(ProofReason.READERS_LISTED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldNeverProveAResourceWhoseReadersCannotBeListed() {
        var readers = new ResourceReaders();
        readers.readersListed(readers.getProofGeneration(), NOW);

        readers.readersNotListable();

        assertThat(proof(readers)).isEqualTo("UNPROVEN FAILED READERS_NOT_LISTABLE");
        assertThat(readers.trustedClass(NOW, MAX_AGE)).isEqualTo(VisibilityClass.UNPROVEN);
    }

    /** Operators read how long a proof waits; an answer that keeps the class and the proof state keeps that time. */
    @Test
    void shouldStampWhenTheProofStateChangesButNotAcrossPendingReasons() {
        var readers = new ResourceReaders();
        readers.listingStopped(ProofReason.RATE_LIMITED);
        // The proof began to wait a minute ago.
        Instant waitingSince = Instant.now().minusSeconds(60);
        ReflectionTestUtils.setField(readers, "proofChangedAt", waitingSince);

        readers.listingStopped(ProofReason.RATE_LIMITED);
        readers.listingStopped(ProofReason.LISTING_INCOMPLETE);
        assertThat(readers.getProofChangedAt())
                .as("a proof that moves from one pending reason to another keeps waiting")
                .isEqualTo(waitingSince);

        readers.readersListed(readers.getProofGeneration(), NOW);
        assertThat(readers.getProofChangedAt()).isAfter(waitingSince);
    }

    @Test
    void shouldEndAListingsProofWhenTheProviderRemovesAccessItCannotAttribute() {
        var readers = new ResourceReaders();
        readers.readersListed(readers.getProofGeneration(), NOW);
        long listingStarted = readers.getProofGeneration();

        readers.readersInvalidated();

        assertThat(proof(readers)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
        assertThat(readers.readersListed(listingStarted, NOW))
                .as("a listing that began before the removal does not apply")
                .isFalse();
    }

    @Test
    void shouldKeepAPublicProofWhenATeamLosesAccess() {
        var readers = new ResourceReaders();
        readers.confirmedPublic(readers.getProofGeneration(), NOW);

        readers.readersInvalidated();

        assertThat(proof(readers)).isEqualTo("WORKSPACE PROVEN PUBLIC_ON_PROVIDER");
    }
}
