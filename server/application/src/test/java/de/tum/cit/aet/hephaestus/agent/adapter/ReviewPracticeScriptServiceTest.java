package de.tum.cit.aet.hephaestus.agent.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.ReviewRuntimeRepository;
import de.tum.cit.aet.hephaestus.agent.job.ScriptRun;
import de.tum.cit.aet.hephaestus.agent.job.ScriptRunRepository;
import de.tum.cit.aet.hephaestus.agent.job.ScriptUsageRepository;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeScriptRunDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptKind;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptNoResultCause;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptRunStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReviewPracticeScriptServiceTest extends BaseUnitTest {

    private static final long WORKSPACE = 7L;
    private static final UUID REVIEW = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final String HOST_CALLS = "\"review\":1,\"chat\":0,\"decision\":0,\"embedding\":0,\"reranking\":0";

    private static JsonNode calls(@Nullable String reviewSessions) {
        return calls(HOST_CALLS, reviewSessions);
    }

    private static JsonNode calls(String hostCalls, @Nullable String reviewSessions) {
        return new JsonMapper()
                .readTree("{" + hostCalls + (reviewSessions == null ? "" : ",\"reviewSessions\":" + reviewSessions)
                        + "}");
    }

    private static ScriptRun run(long practiceId, ScriptKind kind, ScriptRunStatus status, JsonNode calls) {
        return new ScriptRun(
                JOB,
                0,
                practiceId,
                WORKSPACE,
                practiceId + 100,
                status,
                kind,
                status == ScriptRunStatus.OK ? null : ScriptStopReason.OFF_FORMAT,
                calls,
                null,
                Instant.EPOCH.plusSeconds(practiceId),
                null,
                1000);
    }

    @Test
    void shouldTellABuiltInsEvidenceRefusalFromAFormatFailureAndLeaveUnrecordedCallsUnknown() {
        var runs = mock(ScriptRunRepository.class);
        var usage = mock(ScriptUsageRepository.class);
        var reviews = mock(ReviewService.class);
        var runtime = mock(ReviewRuntimeRepository.class);
        when(reviews.findPinned(eq(WORKSPACE), anyCollection())).thenReturn(Map.of(REVIEW, List.of()));
        when(runtime.jobsOf(eq(WORKSPACE), anyCollection()))
                .thenReturn(List.of(new ReviewRuntimeRepository.JobOfReview(JOB, REVIEW)));
        when(usage.findByWorkspaceIdAndJobIdIn(eq(WORKSPACE), anyCollection())).thenReturn(List.of());
        when(runs.findByWorkspaceIdAndJobIdIn(eq(WORKSPACE), anyCollection()))
                .thenReturn(List.of(
                        // Six model calls, four evidence refusals and one schema refusal, then a loop stop.
                        run(
                                1,
                                ScriptKind.BUILTIN,
                                ScriptRunStatus.FAILED,
                                calls("{\"modelCalls\":6,\"evidenceRefusals\":4,\"formatRefusals\":1}")),
                        // Recorded before the runner counted review sessions.
                        run(2, ScriptKind.BUILTIN, ScriptRunStatus.FAILED, calls(null)),
                        run(
                                3,
                                ScriptKind.CUSTOM,
                                ScriptRunStatus.FAILED,
                                calls("{\"modelCalls\":3,\"evidenceRefusals\":0,\"formatRefusals\":2}")),
                        run(
                                4,
                                ScriptKind.BUILTIN,
                                ScriptRunStatus.FAILED,
                                calls("{\"modelCalls\":2,\"evidenceRefusals\":0,\"formatRefusals\":0}")),
                        run(
                                5,
                                ScriptKind.BUILTIN,
                                ScriptRunStatus.OK,
                                calls("{\"modelCalls\":0,\"evidenceRefusals\":0,\"formatRefusals\":0}")),
                        // Its review was refused once, then accepted; its own decision model then answered off-format.
                        run(
                                6,
                                ScriptKind.CUSTOM,
                                ScriptRunStatus.FAILED,
                                calls(
                                        "\"review\":1,\"chat\":0,\"decision\":1,\"embedding\":0,\"reranking\":0",
                                        "{\"modelCalls\":3,\"evidenceRefusals\":1,\"formatRefusals\":0}"))));

        List<PracticeScriptRunDTO> mapped =
                new ReviewPracticeScriptService(usage, runs, reviews, runtime).forReview(WORKSPACE, REVIEW);

        assertThat(mapped)
                .extracting(
                        PracticeScriptRunDTO::practiceId,
                        PracticeScriptRunDTO::noResultCause,
                        PracticeScriptRunDTO::reviewModelCalls,
                        PracticeScriptRunDTO::models)
                .containsExactly(
                        tuple("1", ScriptNoResultCause.EVIDENCE_NOT_READ, 6, List.of()),
                        tuple("2", ScriptNoResultCause.SESSION_ENDED, null, List.of()),
                        tuple("3", null, 3, List.of()),
                        tuple("4", ScriptNoResultCause.SESSION_ENDED, 2, List.of()),
                        tuple("5", null, 0, List.of()),
                        // Only the built-in's contract proves where an OFF_FORMAT came from.
                        tuple("6", null, 3, List.of()));
    }
}
