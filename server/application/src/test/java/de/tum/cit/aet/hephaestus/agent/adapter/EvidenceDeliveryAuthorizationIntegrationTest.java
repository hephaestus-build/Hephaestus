package de.tum.cit.aet.hephaestus.agent.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.CitedSourceAccess;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.result.ResultClaimWriter;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.InapplicabilityWarrant;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidence;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

class EvidenceDeliveryAuthorizationIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    ResultClaimWriter claims;

    @Autowired
    ResultRepository results;

    @Autowired
    EvidenceDeliveryAuthorization authorization;

    @MockitoBean
    CitedSourceAccess access;

    @MockitoSpyBean
    ClasspathSourceCatalogRegistry catalog;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Transactional
    void shouldAuthorizeStoredProblemProofWithOrWithoutResultCitations(boolean ownCitations) {
        var scenario = data.scenario();
        var pending =
                admissions.pending(scenario, Instant.now(), scenario.account().getId());
        var candidate = admissions.candidate(pending, ResultOutcome.NOT_MET);
        var result = claims.write(
                scenario.workspace().getId(),
                pending.review(),
                new ResultCandidate(
                        candidate.practiceId(),
                        candidate.practiceVersionId(),
                        candidate.itemId(),
                        candidate.itemVersion(),
                        candidate.developerId(),
                        candidate.outcome(),
                        candidate.summary(),
                        candidate.explanation(),
                        new ResultEvidence(
                                ownCitations ? candidate.evidence().citations() : List.of(), null, null, List.of()),
                        candidate.example(),
                        candidate.coverage(),
                        candidate.problems()),
                Instant.now());
        var stored = results.findByIdAndWorkspaceId(result.getId(), result.getWorkspaceId())
                .orElseThrow();
        when(access.permitsAll(eq(stored.getWorkspaceId()), eq(List.of(stored.facts())), any()))
                .thenReturn(Set.of(stored.getId()));

        for (var purpose : SourceUsePurpose.values())
            assertThat(authorization.permitsForNewDelivery(stored.getWorkspaceId(), List.of(stored), purpose))
                    .containsExactly(stored.getId());
    }

    /**
     * A Does not apply that a practice's path check proved cites nothing: it rests on the source that the check read.
     * Its words are shown while that source may be used, and withheld only when the source check really refuses it.
     */
    @Test
    @Transactional
    void shouldShowAPathCheckDoesNotApplyThatCitesNothingUnlessItsCheckedSourceIsRefused() {
        var scenario = data.scenario();
        var pending =
                admissions.pending(scenario, Instant.now(), scenario.account().getId());
        var candidate = admissions.candidate(pending, ResultOutcome.NOT_MET);
        var source = new SourceKind("scm.pull-request.diff");
        // The candidate that the host seals when no Applies when condition matched the changed paths.
        var result = claims.write(
                scenario.workspace().getId(),
                pending.review(),
                new ResultCandidate(
                        candidate.practiceId(),
                        candidate.practiceVersionId(),
                        candidate.itemId(),
                        candidate.itemVersion(),
                        candidate.developerId(),
                        ResultOutcome.DOES_NOT_APPLY,
                        "This practice does not apply",
                        "No changed path is under docs/.",
                        new ResultEvidence(
                                List.of(),
                                null,
                                new InapplicabilityWarrant(
                                        List.of(source),
                                        "At least one Applies when condition must match",
                                        "No changed path is under docs/.",
                                        null),
                                List.of()),
                        null,
                        new HostCoverage(true, List.of(), 0, 0, 0, List.of()),
                        List.of()),
                Instant.now());
        var stored = results.findByIdAndWorkspaceId(result.getId(), result.getWorkspaceId())
                .orElseThrow();
        when(access.permitsAll(eq(stored.getWorkspaceId()), anyCollection(), any()))
                .thenReturn(Set.of(stored.getId()));
        var purpose = SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY;
        doReturn(true).when(catalog).isSourceUsePermitted(source, purpose);

        assertThat(Objects.requireNonNull(stored.getEvidence()).path("citations"))
                .isEmpty();
        assertThat(authorization.permitsAll(stored.getWorkspaceId(), List.of(stored.facts()), purpose))
                .containsExactly(stored.getId());

        doReturn(false).when(catalog).isSourceUsePermitted(source, purpose);
        assertThat(authorization.permitsAll(stored.getWorkspaceId(), List.of(stored.facts()), purpose))
                .isEmpty();
    }

    @Test
    @Transactional
    void shouldWithholdACitedResultOnlyWhenItsCitedSourceIsRefused() {
        var scenario = data.scenario();
        var pending =
                admissions.pending(scenario, Instant.now(), scenario.account().getId());
        var result = claims.write(
                scenario.workspace().getId(),
                pending.review(),
                admissions.candidate(pending, ResultOutcome.NOT_MET),
                Instant.now());
        var stored = results.findByIdAndWorkspaceId(result.getId(), result.getWorkspaceId())
                .orElseThrow();
        when(access.permitsAll(eq(stored.getWorkspaceId()), anyCollection(), any()))
                .thenReturn(Set.of(stored.getId()));
        var purpose = SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY;
        var source = new SourceKind("scm.pull-request.diff");

        doReturn(true).when(catalog).isSourceUsePermitted(source, purpose);
        assertThat(authorization.permitsAll(stored.getWorkspaceId(), List.of(stored.facts()), purpose))
                .containsExactly(stored.getId());
        doReturn(false).when(catalog).isSourceUsePermitted(source, purpose);
        assertThat(authorization.permitsAll(stored.getWorkspaceId(), List.of(stored.facts()), purpose))
                .isEmpty();
    }
}
