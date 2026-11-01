package de.tum.cit.aet.hephaestus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionInUseException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionSlugConflictException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelInUseException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelSlugConflictException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelUpstreamIdConflictException;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobStateConflictException;
import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class AgentControllerAdviceTest extends BaseUnitTest {

    private final AgentControllerAdvice advice = new AgentControllerAdvice();

    @Test
    void jobStateConflictUsesSurfaceNeutralProblemDetail() {
        var problem =
                advice.handleAgentJobStateConflict(new ReviewJobStateConflictException("Job is already COMPLETED"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Agent job state conflict");
        assertThat(problem.getDetail()).isEqualTo("Job is already COMPLETED");
    }

    @Test
    void aBlankMessageFallsBackToAGenericDetail() {
        // ProblemDetail.detail must never be blank: the UI renders it verbatim.
        var problem = advice.handleAgentJobStateConflict(new ReviewJobStateConflictException(" "));

        assertThat(problem.getDetail()).isEqualTo("The server could not process this request.");
    }

    /**
     * Without a {@code type} RFC 7807 collapses all six 409s to {@code about:blank}, leaving clients to
     * string-match English titles. The URIs are API surface, so a rename must be a deliberate act.
     */
    @Test
    void everyConflictCarriesItsOwnStableTypeUri() {
        List<ProblemDetail> problems = List.of(
                advice.handleAgentJobStateConflict(new ReviewJobStateConflictException("x")),
                advice.handleConnectionInUse(new LlmConnectionInUseException(1L)),
                advice.handleModelInUse(new LlmModelInUseException(1L)),
                advice.handleConnectionSlugConflict(new LlmConnectionSlugConflictException("slug")),
                advice.handleModelSlugConflict(new LlmModelSlugConflictException(1L, "slug")),
                advice.handleModelUpstreamIdConflict(new LlmModelUpstreamIdConflictException(1L, "gpt-x")));

        assertThat(problems)
                .allSatisfy(problem -> assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value()))
                .extracting(ProblemDetail::getType)
                .containsExactly(
                        ProblemType.AGENT_JOB_STATE_CONFLICT.uri(),
                        ProblemType.LLM_CONNECTION_IN_USE.uri(),
                        ProblemType.LLM_MODEL_IN_USE.uri(),
                        ProblemType.LLM_CONNECTION_SLUG_CONFLICT.uri(),
                        ProblemType.LLM_MODEL_SLUG_CONFLICT.uri(),
                        ProblemType.LLM_MODEL_UPSTREAM_ID_CONFLICT.uri());
    }
}
