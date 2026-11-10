package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptRunStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PracticeScriptRunRecorderTest extends BaseUnitTest {
    @Test
    void shouldAttributeTheReportToTheFrozenPracticeAndVersion() {
        var runs = mock(ScriptRunRepository.class);
        var assignments = mock(ReviewJobAssignments.class);
        var job = RuntimeReviewTestData.job();
        var assignment = RuntimeReviewTestData.assignment(5L, null);
        when(assignments.admissible(job)).thenReturn(List.of(assignment));
        var recorded = new ArrayList<ScriptRun>();
        doAnswer(invocation -> {
                    Iterable<ScriptRun> rows = invocation.getArgument(0);
                    rows.forEach(recorded::add);
                    return rows;
                })
                .when(runs)
                .saveAll(any());
        var mapper = new JsonMapper();
        var meters = new SimpleMeterRegistry();
        byte[] report = """
            {"schemaVersion":3,"truncated":false,"practices":[
             {"practiceSlug":"clear-names","program":{"kind":"builtin","digest":null},"status":"ok","stopReason":null,
              "calls":{"review":1},"coverageReached":{"reads":0,"truncatedReads":0,"failedReads":0},"durationMs":1}]}
            """.getBytes(StandardCharsets.UTF_8);
        new PracticeScriptRunRecorder(new PiResultParser(mapper, meters), runs, meters, mapper, assignments)
                .record(job, report, Instant.EPOCH);
        assertThat(recorded).hasSize(1);
        var run = recorded.getFirst();
        assertThat(run.getId().getPracticeId()).isEqualTo(31);
        assertThat(run.getPracticeVersionId()).isEqualTo(32);
        assertThat(run.getWorkspaceId()).isEqualTo(1);
        assertThat(run.getId().getAttempt()).isZero();
        assertThat(run.getStatus()).isEqualTo(ScriptRunStatus.OK);
    }

    @Test
    void shouldKeepTheRunnersReviewSessionCountsBesideTheHostCallsWhenTheReportNamesThem() {
        var runs = mock(ScriptRunRepository.class);
        var assignments = mock(ReviewJobAssignments.class);
        var job = RuntimeReviewTestData.job();
        when(assignments.admissible(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(5L, null)));
        var recorded = new ArrayList<ScriptRun>();
        doAnswer(invocation -> {
                    Iterable<ScriptRun> rows = invocation.getArgument(0);
                    rows.forEach(recorded::add);
                    return rows;
                })
                .when(runs)
                .saveAll(any());
        var mapper = new JsonMapper();
        var meters = new SimpleMeterRegistry();
        byte[] report = """
            {"schemaVersion":3,"truncated":false,"practices":[
             {"practiceSlug":"clear-names","program":{"kind":"builtin","digest":null},"status":"failed","stopReason":"off-format",
              "calls":{"review":1},
              "reviewSessions":{"modelCalls":6,"evidenceRefusals":4,"formatRefusals":1},
              "coverageReached":{"reads":0,"truncatedReads":0,"failedReads":0},"durationMs":1}]}
            """.getBytes(StandardCharsets.UTF_8);
        new PracticeScriptRunRecorder(new PiResultParser(mapper, meters), runs, meters, mapper, assignments)
                .record(job, report, Instant.EPOCH);

        assertThat(recorded).hasSize(1);
        assertThat(recorded.getFirst().getCalls()).isEqualTo(mapper.readTree("""
                    {"review":1,
                     "reviewSessions":{"modelCalls":6,"evidenceRefusals":4,"formatRefusals":1}}
                    """));
    }

    @Test
    void shouldRecordNothingWhenThereIsNoHostReport() {
        var runs = mock(ScriptRunRepository.class);
        var assignments = mock(ReviewJobAssignments.class);
        var mapper = new JsonMapper();
        var meters = new SimpleMeterRegistry();
        new PracticeScriptRunRecorder(new PiResultParser(mapper, meters), runs, meters, mapper, assignments)
                .record(RuntimeReviewTestData.job(), null, Instant.EPOCH);
        verifyNoInteractions(runs, assignments);
    }
}
