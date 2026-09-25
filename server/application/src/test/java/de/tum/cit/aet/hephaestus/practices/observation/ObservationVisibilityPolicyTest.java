package de.tum.cit.aet.hephaestus.practices.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ObservationVisibilityPolicyTest extends BaseUnitTest {

    /**
     * Currentness is the policy's own conjunct; the rest of the answer is whatever evidence authorization
     * says, passed through unchanged in both directions.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void answersWhatEvidenceAuthorizationAnswersForACurrentObservation(boolean authorized) {
        EvidenceAuthorization authorization = mock(EvidenceAuthorization.class);
        Observation observation = observation("fingerprint", "fingerprint");
        when(authorization.permitsAll(7L, List.of(observation), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(authorized ? Set.of(observation.getId()) : Set.<UUID>of());

        Set<UUID> permitted = new ObservationVisibilityPolicy(authorization)
                .permitsAll(7L, List.of(observation), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);

        assertThat(permitted.contains(observation.getId())).isEqualTo(authorized);
        verify(authorization).permitsAll(7L, List.of(observation), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
    }

    /**
     * The currentness conjunct stays ahead of the authorization one: a stale claim is refused without an
     * evidence read, so it can never be admitted by an authorization answer given about the batch it was in.
     */
    @Test
    void authorizesOnlyTheCurrentObservationsOfABatch() {
        EvidenceAuthorization authorization = mock(EvidenceAuthorization.class);
        Observation current = observation("fingerprint", "fingerprint");
        Observation stale = observation("old", "current");
        when(authorization.permitsAll(7L, List.of(current), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(Set.of(current.getId()));

        assertThat(new ObservationVisibilityPolicy(authorization)
                        .permitsAll(7L, List.of(current, stale), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .containsExactly(current.getId());
    }

    @Test
    void asksNothingOfEvidenceAuthorizationWhenEveryObservationIsStale() {
        EvidenceAuthorization authorization = mock(EvidenceAuthorization.class);

        assertThat(new ObservationVisibilityPolicy(authorization)
                        .permitsAll(
                                7L,
                                List.of(observation("old", "current")),
                                SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isEmpty();
        verifyNoInteractions(authorization);
    }

    /**
     * The card keeps a claim whose rules the practice has since changed — closed is something the developer
     * may see — and drops only the one whose rules cannot be verified, which never reaches authorization.
     */
    @Test
    void shouldKeepStaleEvidenceAndDropUnverifiableEvidenceWhenFilteringWhatIsShown() {
        EvidenceAuthorization authorization = mock(EvidenceAuthorization.class);
        Observation current = observation("fingerprint", "fingerprint");
        Observation stale = observation("old", "current");
        Observation unverifiable = observation(null, "current");
        when(authorization.permitsAll(7L, List.of(current, stale), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(Set.of(current.getId(), stale.getId()));

        assertThat(new ObservationVisibilityPolicy(authorization)
                        .permitsShown(
                                7L, List.of(current, stale, unverifiable), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .containsExactlyInAnyOrder(current.getId(), stale.getId());
    }

    @Test
    void shouldAskNothingOfEvidenceAuthorizationWhenNothingShownCanBeVerified() {
        EvidenceAuthorization authorization = mock(EvidenceAuthorization.class);

        assertThat(new ObservationVisibilityPolicy(authorization)
                        .permitsShown(
                                7L, List.of(observation(null, "current")), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isEmpty();
        verifyNoInteractions(authorization);
    }

    @Test
    void shouldHideShownEvidenceWhenEvidenceAuthorizationRefusesIt() {
        EvidenceAuthorization authorization = mock(EvidenceAuthorization.class);
        Observation current = observation("fingerprint", "fingerprint");
        when(authorization.permitsAll(7L, List.of(current), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(Set.of());

        assertThat(new ObservationVisibilityPolicy(authorization)
                        .permitsShown(7L, List.of(current), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isEmpty();
    }

    @Test
    void shouldNotAuthorizeSupersededIssueFeedbackForNewDelivery() {
        EvidenceAuthorization authorization = mock(EvidenceAuthorization.class);
        Observation historical = historicalObservation();

        assertThat(new ObservationVisibilityPolicy(authorization)
                        .permitsForNewDelivery(7L, List.of(historical), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isEmpty();
        verifyNoInteractions(authorization);
    }

    @Test
    void shouldShowAuthorizedHistoricalObservationsInReviewRunHistory() {
        EvidenceAuthorization authorization = mock(EvidenceAuthorization.class);
        Observation historical = historicalObservation();
        when(authorization.permitsAll(7L, List.of(historical), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(Set.of(historical.getId()));

        assertThat(new ObservationVisibilityPolicy(authorization)
                        .permitsHistory(7L, List.of(historical), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .containsExactly(historical.getId());
    }

    private static Observation observation(@Nullable String evaluatedFingerprint, @Nullable String currentFingerprint) {
        PracticeRevision evaluated = mock(PracticeRevision.class);
        PracticeRevision current = mock(PracticeRevision.class);
        Practice practice = mock(Practice.class);
        when(evaluated.getReviewRuleFingerprint()).thenReturn(evaluatedFingerprint);
        when(current.getReviewRuleFingerprint()).thenReturn(currentFingerprint);
        when(practice.getCurrentRevision()).thenReturn(current);
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(UUID.randomUUID())
                .practice(practice)
                .practiceRevision(evaluated)
                .build();
    }

    private static Observation historicalObservation() {
        return Observation.builder()
                .id(UUID.randomUUID())
                .supersededAt(Instant.now())
                .build();
    }
}
