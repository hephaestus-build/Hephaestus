package de.tum.cit.aet.hephaestus.agent.adapter;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.resultBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.CitedSourceAccess;
import de.tum.cit.aet.hephaestus.agent.handler.AdmittedResultFixtures;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class EvidenceDeliveryAuthorizationTest extends BaseUnitTest {
    @Test
    void keepsVerifiedDeliveryIndependentOfRuntimeJobRetention() {
        var sources = mock(ClasspathSourceCatalogRegistry.class);
        var access = mock(CitedSourceAccess.class);
        var authorization = new EvidenceDeliveryAuthorization(sources, access, mock(ProblemRepository.class));
        var result = Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .developerId(2L)
                .evidence(AdmittedResultFixtures.evidence("docs.document.core"))
                .build();
        when(access.permitsAll(1, List.of(result), SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Set.of(result.getId()));
        when(sources.isSourceUsePermitted(any(), eq(SourceUsePurpose.CONVERSATIONAL_MENTORING)))
                .thenReturn(true);
        assertThat(authorization.permitsForNewDelivery(1, List.of(result), SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .containsExactly(result.getId());
        result = resultBuilder(result).evidenceVersion((short) 0).build();
        assertThat(authorization.permitsForNewDelivery(1, List.of(result), SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isEmpty();
        when(access.permitsAll(1, List.of(result), SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Set.of());
        assertThat(authorization.permits(1, result, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isFalse();
        assertThat(authorization.permits(9, result, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isFalse();
    }

    @Test
    void shouldReadMigratedWordsWithoutCertifyingThemForNewUse() {
        var access = mock(CitedSourceAccess.class);
        var authorization = new EvidenceDeliveryAuthorization(
                mock(ClasspathSourceCatalogRegistry.class), access, mock(ProblemRepository.class));
        var result = Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .evidenceVersion((short) 0)
                .build();
        when(access.permitsAllHistory(1, List.of(result), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .thenReturn(Set.of(result.getId()));
        assertThat(authorization.permits(1, result, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isTrue();
        assertThat(authorization.permitsForNewDelivery(1, List.of(result), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isEmpty();
        assertThat(authorization.permits(1, result, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .isFalse();
        assertThat(authorization.permits(1, result, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isFalse();
        assertThat(authorization.permits(2, result, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isFalse();
    }

    @ParameterizedTest
    @EnumSource(SourceUsePurpose.class)
    void shouldAuthorizeProblemOnlyProofWhenEveryCitationAndLiveAccessPermitUse(SourceUsePurpose purpose) {
        var sources = mock(ClasspathSourceCatalogRegistry.class);
        var access = mock(CitedSourceAccess.class);
        var problems = mock(ProblemRepository.class);
        var authorization = new EvidenceDeliveryAuthorization(sources, access, problems);
        var result = Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .developerId(2L)
                .outcome(ResultOutcome.NOT_MET)
                .evidence(new JsonMapper().readTree("{\"citations\":[],\"openQuestions\":[]}"))
                .build();
        var problem = Problem.builder()
                .workspaceId(1)
                .resultId(result.getId())
                .citations(
                        AdmittedResultFixtures.evidence("scm.pull-request.core").path("citations"))
                .build();
        when(problems.findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(1, List.of(result.getId())))
                .thenReturn(List.of(problem));
        when(access.permitsAll(1, List.of(result), purpose)).thenReturn(Set.of(result.getId()));
        when(sources.isSourceUsePermitted(any(), eq(purpose))).thenReturn(true);

        assertThat(authorization.permitsForNewDelivery(1, List.of(result), purpose))
                .containsExactly(result.getId());
        when(sources.isSourceUsePermitted(any(), eq(purpose))).thenReturn(false);
        assertThat(authorization.permitsForNewDelivery(1, List.of(result), purpose))
                .isEmpty();
        when(sources.isSourceUsePermitted(any(), eq(purpose))).thenReturn(true);
        assertThat(authorization.permitsForNewDelivery(1, List.of(result), purpose))
                .containsExactly(result.getId());
        when(access.permitsAll(1, List.of(result), purpose)).thenReturn(Set.of());
        assertThat(authorization.permitsForNewDelivery(1, List.of(result), purpose))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(SourceUsePurpose.class)
    void shouldRejectDamagedProblemProofEvenWhenResultCitationsAreVerified(SourceUsePurpose purpose) {
        var sources = mock(ClasspathSourceCatalogRegistry.class);
        var access = mock(CitedSourceAccess.class);
        var problems = mock(ProblemRepository.class);
        var authorization = new EvidenceDeliveryAuthorization(sources, access, problems);
        var result = Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .outcome(ResultOutcome.NOT_MET)
                .evidence(AdmittedResultFixtures.evidence("scm.pull-request.core"))
                .build();
        var evidence = new JsonMapper()
                .convertValue(AdmittedResultFixtures.evidence("scm.pull-request.core"), ObjectNode.class);
        evidence.withObject("/citations/0/verification").put("quoteSha256", "0".repeat(64));
        when(problems.findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(1, List.of(result.getId())))
                .thenReturn(List.of(Problem.builder()
                        .workspaceId(1)
                        .resultId(result.getId())
                        .citations(evidence.path("citations"))
                        .build()));
        when(access.permitsAll(1, List.of(result), purpose)).thenReturn(Set.of(result.getId()));

        assertThat(authorization.permitsForNewDelivery(1, List.of(result), purpose))
                .isEmpty();
    }
}
