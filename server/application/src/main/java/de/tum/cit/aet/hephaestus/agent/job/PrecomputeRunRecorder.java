package de.tum.cit.aet.hephaestus.agent.job;

import static de.tum.cit.aet.hephaestus.core.TransactionCallbacks.afterCommit;

import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser.PrecomputeRunReport;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeModelUseDTO;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeRunStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Records what each staged precompute script did in one attempt, from the runner's
 * {@link SandboxLayout#PRECOMPUTE_REPORT_FILE}. A script is staged when the job admitted its practice at a
 * revision that holds a precompute script.
 */
@Component
@ConditionalOnWorkerRole
@RequiredArgsConstructor
class PrecomputeRunRecorder {

    private final PiResultParser parser;
    private final AgentJobPrecomputeRunRepository runs;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Runs inside the attempt's terminal transaction, after the attempt kept its ownership fence, so each attempt
     * writes its runs once and a requeued attempt writes none. The counter moves only when the rows commit.
     *
     * @param report the collected report; {@code null} when the runner wrote none, which records nothing
     * @param slots the models that this attempt froze for its precompute scripts, by kind. Each claim replaces the
     *     job's snapshot, and the attempt still holds its ownership fence here, so the snapshot is this attempt's.
     */
    void record(AgentJob job, byte @Nullable [] report, Map<ModelKind, FrozenModel> slots, Instant finishedAt) {
        if (report == null) return;
        Map<String, Long> admitted = admittedRevisions(job.getEvidenceSnapshot());
        if (admitted.isEmpty()) return;
        long workspaceId = job.getWorkspace().getId();
        Set<Long> withScript = runs.findPrecomputeRevisionIdsByWorkspaceId(workspaceId, admitted.values());
        Map<String, Long> staged = new HashMap<>();
        admitted.forEach((slug, revisionId) -> {
            if (withScript.contains(revisionId)) staged.put(slug, revisionId);
        });
        if (staged.isEmpty()) return;

        List<PrecomputeRunReport> reported = parser.parsePrecomputeReport(report, staged.keySet());
        runs.saveAll(reported.stream()
                .map(run -> new AgentJobPrecomputeRun(
                        job,
                        job.getRetryCount(),
                        run.practiceSlug(),
                        staged.get(run.practiceSlug()),
                        run.status(),
                        run.leads(),
                        stored(run.models(), slots),
                        finishedAt,
                        run.error(),
                        run.durationMs()))
                .toList());
        afterCommit(() -> reported.forEach(run -> scripts(run.status()).increment()));
    }

    private @Nullable JsonNode stored(@Nullable List<PrecomputeModelUseDTO> models, Map<ModelKind, FrozenModel> slots) {
        return models == null
                ? null
                : objectMapper.valueToTree(models.stream()
                        .map(use -> {
                            FrozenModel slot = slots.get(
                                    AgentPurpose.valueOf(use.purpose().name()).kind());
                            return StoredModelUse.of(use, slot == null ? null : slot.dataHandlingTier());
                        })
                        .toList());
    }

    private Counter scripts(PrecomputeRunStatus status) {
        return meterRegistry.counter(
                AgentMetrics.AGENT_REVIEW_PRECOMPUTE_SCRIPTS,
                Tags.of("status", status.name().toLowerCase(Locale.ROOT)));
    }

    /** The admitted practices by slug, as the job's evidence snapshot recorded them when it was prepared. */
    private static Map<String, Long> admittedRevisions(@Nullable JsonNode snapshot) {
        JsonNode practices = snapshot == null ? null : snapshot.get("practices");
        if (practices == null || !practices.isArray()) return Map.of();
        Map<String, Long> admitted = new HashMap<>();
        for (JsonNode practice : practices) {
            JsonNode slug = practice.path("slug");
            JsonNode revisionId = practice.path("revisionId");
            if (slug.isString() && revisionId.isIntegralNumber()) {
                admitted.put(slug.asString(), revisionId.asLong());
            }
        }
        return admitted;
    }
}
