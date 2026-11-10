package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;

public final class PracticeScriptRunRecorderFixtures {

    private PracticeScriptRunRecorderFixtures() {}

    /** Call inside the transaction that would end the attempt. */
    public static void record(
            PiResultParser parser,
            ScriptRunRepository runs,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper,
            ReviewJobAssignments assignments,
            ReviewJob job,
            byte[] report,
            Instant finishedAt) {
        new PracticeScriptRunRecorder(parser, runs, meterRegistry, objectMapper, assignments)
                .record(job, report, finishedAt);
    }
}
