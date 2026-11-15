package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ResultEvidenceQueryServiceTest extends BaseUnitTest {
    private final JsonMapper json = new JsonMapper();
    private final ProblemRepository problems = mock(ProblemRepository.class);
    private final ResultEvidenceQueryService query = new ResultEvidenceQueryService(problems);

    @Test
    void shouldReturnEachResultsOwnAndProblemCitationsWhenResultsHaveProblems() {
        var withProblem = result("{\"citations\":[{\"path\":\"a\"}],\"openQuestions\":[]}");
        var withoutProblem = result("{\"citations\":[{\"path\":\"b\"}],\"openQuestions\":[]}");
        var problem = Problem.builder()
                .workspaceId(1)
                .resultId(withProblem.getId())
                .citations(json.readTree("[{\"path\":\"c\"}]"))
                .build();
        when(problems.findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(
                        1, List.of(withProblem.getId(), withoutProblem.getId())))
                .thenReturn(List.of(problem));

        var citations = query.citations(1, List.of(withProblem, withoutProblem));

        assertThat(citations.get(withProblem.getId()))
                .containsExactly(json.readTree("{\"path\":\"a\"}"), json.readTree("{\"path\":\"c\"}"));
        assertThat(citations.get(withoutProblem.getId())).containsExactly(json.readTree("{\"path\":\"b\"}"));
    }

    @Test
    void shouldReadNothingWhenNoResultHasAnId() {
        var unsaved = Result.builder().workspaceId(1).build();

        assertThat(query.citations(1, List.of(unsaved))).isEmpty();
        assertThat(query.citations(1, List.of())).isEmpty();
        verifyNoInteractions(problems);
    }

    private Result result(String evidence) {
        return Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .evidence(json.readTree(evidence))
                .build();
    }
}
