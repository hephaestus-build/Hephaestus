package de.tum.cit.aet.hephaestus.agent.adapter;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.resultBuilder;
import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.withoutSourceKinds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.CitedSourceAccess;
import de.tum.cit.aet.hephaestus.agent.handler.AdmittedResultFixtures;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultEvidenceQueryService;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class EvidenceDeliveryAuthorizationTest extends BaseUnitTest {
    @Test
    void keepsVerifiedDeliveryIndependentOfRuntimeJobRetention() {
        var sources = mock(ClasspathSourceCatalogRegistry.class);
        var access = mock(CitedSourceAccess.class);
        var authorization = new EvidenceDeliveryAuthorization(sources, access, evidence(mock(ProblemRepository.class)));
        var result = Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .developerId(2L)
                .evidence(AdmittedResultFixtures.evidence("docs.document.core"))
                .build();
        when(access.permitsAll(1, List.of(result.facts()), SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Set.of(result.getId()));
        when(sources.isSourceUsePermitted(any(), eq(SourceUsePurpose.CONVERSATIONAL_MENTORING)))
                .thenReturn(true);
        assertThat(authorization.permitsForNewDelivery(1, List.of(result), SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .containsExactly(result.getId());
        result = resultBuilder(result).evidenceVersion((short) 0).build();
        assertThat(authorization.permitsForNewDelivery(1, List.of(result), SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isEmpty();
        when(access.permitsAll(1, List.of(result.facts()), SourceUsePurpose.CONVERSATIONAL_MENTORING))
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
                mock(ClasspathSourceCatalogRegistry.class), access, evidence(mock(ProblemRepository.class)));
        var result = Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .evidenceVersion((short) 0)
                .build();
        when(access.permitsAllHistory(1, List.of(result.facts()), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
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
        var authorization = new EvidenceDeliveryAuthorization(sources, access, evidence(problems));
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
        stubProblems(problems, result.getId(), List.of(problem));
        when(access.permitsAll(1, List.of(result.facts()), purpose)).thenReturn(Set.of(result.getId()));
        when(sources.isSourceUsePermitted(any(), eq(purpose))).thenReturn(true);

        assertThat(authorization.permitsForNewDelivery(1, List.of(result), purpose))
                .containsExactly(result.getId());
        when(sources.isSourceUsePermitted(any(), eq(purpose))).thenReturn(false);
        assertThat(authorization.permitsForNewDelivery(1, List.of(result), purpose))
                .isEmpty();
        when(sources.isSourceUsePermitted(any(), eq(purpose))).thenReturn(true);
        assertThat(authorization.permitsForNewDelivery(1, List.of(result), purpose))
                .containsExactly(result.getId());
        when(access.permitsAll(1, List.of(result.facts()), purpose)).thenReturn(Set.of());
        assertThat(authorization.permitsForNewDelivery(1, List.of(result), purpose))
                .isEmpty();
    }

    /**
     * The proof a history row carries and whether a decision on it asks the source check. Unnamed sources and a result
     * that rests on no source are withheld before any source is asked.
     */
    record ProofCase(String name, String evidence, boolean problemCites, boolean asksSources) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> proofCases() {
        var cited = "{\"citations\":[{\"sourceKind\":\"scm.pull-request.diff\"}]}";
        var unnamed = "{\"citations\":[{\"sourceKind\":7}]}";
        var none = "{\"citations\":[],\"openQuestions\":[]}";
        var pathCheck = "{\"citations\":[],\"inapplicability\":{\"consulted\":[\"scm.pull-request.diff\"]}}";
        var unnamedConsulted = "{\"citations\":[],\"inapplicability\":{\"consulted\":[7]}}";
        var cases = List.of(
                new ProofCase("cited", cited, false, true),
                new ProofCase("cited, with a cited problem", cited, true, true),
                new ProofCase("unnamed citation", unnamed, false, false),
                new ProofCase("unnamed citation, with a cited problem", unnamed, true, false),
                new ProofCase("no source", none, false, false),
                new ProofCase("only a cited problem", none, true, true),
                new ProofCase("path check", pathCheck, false, true),
                new ProofCase("path check, with a cited problem", pathCheck, true, true),
                new ProofCase("unnamed consulted source", unnamedConsulted, false, false),
                new ProofCase("unnamed consulted source, with a cited problem", unnamedConsulted, true, false));
        return Stream.of(SourceUsePurpose.values())
                .flatMap(purpose -> cases.stream().map(proof -> Arguments.of(purpose, proof)));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("proofCases")
    void shouldDecideFromTheSourceKindsAndWithholdARowWithoutThem(SourceUsePurpose purpose, ProofCase proof) {
        var sources = mock(ClasspathSourceCatalogRegistry.class);
        var access = mock(CitedSourceAccess.class);
        var problems = mock(ProblemRepository.class);
        var authorization = new EvidenceDeliveryAuthorization(sources, access, evidence(problems));
        var full = Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .developerId(2L)
                .outcome(ResultOutcome.NOT_MET)
                .evidence(new JsonMapper().readTree(proof.evidence()))
                .build();
        when(access.permitsAll(eq(1L), anyCollection(), eq(purpose))).thenReturn(Set.of(full.getId()));
        stubProblems(
                problems,
                full.getId(),
                proof.problemCites()
                        ? List.of(Problem.builder()
                                .workspaceId(1)
                                .resultId(full.getId())
                                .citations(AdmittedResultFixtures.evidence("docs.document.core")
                                        .path("citations"))
                                .build())
                        : List.of());
        var facts = full.facts();

        // A history row that recorded no source kinds is withheld before any source is asked.
        assertThat(authorization.permitsAll(1, List.of(withoutSourceKinds(facts)), purpose))
                .isEmpty();
        if (!proof.asksSources()) {
            assertThat(authorization.permitsAll(1, List.of(facts), purpose)).isEmpty();
            verifyNoInteractions(sources);
            return;
        }
        for (boolean permitted : List.of(false, true)) {
            when(sources.isSourceUsePermitted(any(), eq(purpose))).thenReturn(permitted);
            assertThat(authorization.permitsAll(1, List.of(facts), purpose))
                    .isEqualTo(permitted ? Set.of(full.getId()) : Set.of());
        }
    }

    @ParameterizedTest
    @EnumSource(SourceUsePurpose.class)
    void shouldRejectDamagedProblemProofEvenWhenResultCitationsAreVerified(SourceUsePurpose purpose) {
        var sources = mock(ClasspathSourceCatalogRegistry.class);
        var access = mock(CitedSourceAccess.class);
        var problems = mock(ProblemRepository.class);
        var authorization = new EvidenceDeliveryAuthorization(sources, access, evidence(problems));
        var result = Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .outcome(ResultOutcome.NOT_MET)
                .evidence(AdmittedResultFixtures.evidence("scm.pull-request.core"))
                .build();
        // Every source is usable, so only the proof itself can refuse the delivery.
        when(sources.isSourceUsePermitted(any(), eq(purpose))).thenReturn(true);
        when(access.permitsAll(1, List.of(result.facts()), purpose)).thenReturn(Set.of(result.getId()));
        var intact = AdmittedResultFixtures.evidence("scm.pull-request.core").path("citations");
        stubProblems(problems, result.getId(), List.of(problem(result, intact)));
        assertThat(authorization.permitsForNewDelivery(1, List.of(result), purpose))
                .containsExactly(result.getId());

        var damaged = new JsonMapper()
                .convertValue(AdmittedResultFixtures.evidence("scm.pull-request.core"), ObjectNode.class);
        damaged.withObject("/citations/0/verification").put("quoteSha256", "0".repeat(64));
        stubProblems(problems, result.getId(), List.of(problem(result, damaged.path("citations"))));
        assertThat(authorization.permitsForNewDelivery(1, List.of(result), purpose))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(SourceUsePurpose.class)
    void shouldCheckEachCitedSourceForANewDeliveryNotOnlyTheRecordedKinds(SourceUsePurpose purpose) {
        var sources = mock(ClasspathSourceCatalogRegistry.class);
        var access = mock(CitedSourceAccess.class);
        var problems = mock(ProblemRepository.class);
        var authorization = new EvidenceDeliveryAuthorization(sources, access, evidence(problems));
        // Recorded kinds that disagree with the citations: only the citations decide a new delivery.
        var result = Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .outcome(ResultOutcome.NOT_MET)
                .evidence(AdmittedResultFixtures.evidence("scm.pull-request.core"))
                .recordedSourceKinds(new String[] {"docs.document.core"})
                .build();
        stubProblems(problems, result.getId(), List.of());
        when(access.permitsAll(1, List.of(result.facts()), purpose)).thenReturn(Set.of(result.getId()));
        when(sources.isSourceUsePermitted(new SourceKind("docs.document.core"), purpose))
                .thenReturn(true);

        assertThat(authorization.permitsAll(1, List.of(result.facts()), purpose))
                .containsExactly(result.getId());
        assertThat(authorization.permitsForNewDelivery(1, List.of(result), purpose))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(SourceUsePurpose.class)
    void shouldShowAResultThatOnlyConsultedUsableSourcesButNeverDeliverItAnew(SourceUsePurpose purpose) {
        var sources = mock(ClasspathSourceCatalogRegistry.class);
        var access = mock(CitedSourceAccess.class);
        var problems = mock(ProblemRepository.class);
        var authorization = new EvidenceDeliveryAuthorization(sources, access, evidence(problems));
        var result = Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .outcome(ResultOutcome.NOT_MET)
                .evidence(new JsonMapper()
                        .readTree("{\"citations\":[],\"search\":{\"consulted\":[\"scm.pull-request.diff\"]}}"))
                .build();
        stubProblems(problems, result.getId(), List.of());
        when(access.permitsAll(1, List.of(result.facts()), purpose)).thenReturn(Set.of(result.getId()));
        when(sources.isSourceUsePermitted(any(), eq(purpose))).thenReturn(true);

        // Every source it rests on is usable, so it is shown and may be reused; a new delivery needs citations.
        assertThat(authorization.permitsAll(1, List.of(result.facts()), purpose))
                .containsExactly(result.getId());
        assertThat(authorization.permitsForNewDelivery(1, List.of(result), purpose))
                .isEmpty();
    }

    private static Problem problem(Result result, JsonNode citations) {
        return Problem.builder()
                .workspaceId(1)
                .resultId(result.getId())
                .citations(citations)
                .build();
    }

    /**
     * The problems of the result, as both the full read and the citations-only read return them. Lenient: a check that
     * is not a new delivery reads only the citations.
     */
    private static void stubProblems(ProblemRepository problems, UUID resultId, List<Problem> rows) {
        lenient()
                .when(problems.findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(1, List.of(resultId)))
                .thenReturn(rows);
        lenient()
                .when(problems.findCitations(1, List.of(resultId)))
                .thenReturn(rows.stream()
                        .map(row -> new ProblemRepository.Citations(resultId, row.getCitations()))
                        .toList());
    }

    private static ResultEvidenceQueryService evidence(ProblemRepository problems) {
        return new ResultEvidenceQueryService(problems);
    }
}
