package de.tum.cit.aet.hephaestus.agent.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.CitedSourceAccess;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.result.ResultClaimWriter;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidence;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
        when(access.permits(eq(stored.getWorkspaceId()), eq(stored.getId()), any()))
                .thenReturn(true);

        for (var purpose : SourceUsePurpose.values())
            assertThat(authorization.permitsForNewDelivery(stored.getWorkspaceId(), List.of(stored), purpose))
                    .containsExactly(stored.getId());
    }
}
