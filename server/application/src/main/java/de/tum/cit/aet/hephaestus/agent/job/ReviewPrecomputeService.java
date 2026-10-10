package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobPrecomputeRunRepository.PracticeNameRow;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeRunDTO;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What each practice's precompute script did in the review's current attempt, beside the calls to its decision,
 * embedding and reranking models that the proxy counted. The runner's report says what the script did. The proxy's
 * rows say what it called. Neither is spend: the LLM ledger owns spend.
 *
 * <p>Only the attempt's terminal write records runs, so an attempt that has not ended shows none. An earlier attempt is
 * never shown in its place: its scripts did not produce the review's result.
 */
@Service
@RequiredArgsConstructor
class ReviewPrecomputeService {

    private final AgentJobRepository jobs;
    private final AgentJobPrecomputeRunRepository runs;

    @Transactional(readOnly = true)
    public List<ReviewPrecomputeDTO> currentAttempt(long workspaceId, UUID jobId) {
        int attempt = jobs.findByIdAndWorkspaceId(jobId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("AgentJob", jobId.toString()))
                .getRetryCount();
        List<AgentJobPrecomputeRun> current = runs.findByWorkspaceIdAndJobIdAndAttempt(workspaceId, jobId, attempt);
        if (current.isEmpty()) return List.of();

        Map<String, Map<ModelKind, AgentJobPrecomputeUsage>> usage = new HashMap<>();
        for (AgentJobPrecomputeUsage row : runs.findUsageByWorkspaceIdAndJobIdAndAttempt(workspaceId, jobId, attempt)) {
            usage.computeIfAbsent(row.getId().getPracticeSlug(), slug -> new EnumMap<>(ModelKind.class))
                    .put(row.modelKind(), row);
        }
        Map<String, String> names = runs
                .findPracticeNamesByWorkspaceId(
                        workspaceId,
                        current.stream()
                                .map(AgentJobPrecomputeRun::practiceSlug)
                                .toList())
                .stream()
                .collect(Collectors.toMap(PracticeNameRow::getSlug, PracticeNameRow::getName));
        return current.stream()
                .map(run -> dto(run, names.get(run.practiceSlug()), usage.getOrDefault(run.practiceSlug(), Map.of())))
                .sorted(Comparator.comparing(
                                ReviewPrecomputeDTO::practiceName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(ReviewPrecomputeDTO::practiceSlug))
                .toList();
    }

    /**
     * The models that the script declared or called, in the order of their purposes. A model that the script called
     * has the tier that its calls recorded, so a run that did not finish still shows it. A model that it only
     * declared has the tier that the run recorded. Either is absent when the attempt had no model for the purpose.
     */
    private static ReviewPrecomputeDTO dto(
            AgentJobPrecomputeRun run, @Nullable String practiceName, Map<ModelKind, AgentJobPrecomputeUsage> usage) {
        List<StoredModelUse> declared = ReviewOutcomeLookupAdapter.storedModelUses(run.getModels());
        List<ReviewPrecomputeModelDTO> models = new ArrayList<>();
        for (AgentPurpose purpose : AgentPurpose.precompute()) {
            AgentJobPrecomputeUsage counted = usage.get(purpose.kind());
            StoredModelUse use = declared.stream()
                    .filter(candidate -> candidate.purpose().name().equals(purpose.name()))
                    .findFirst()
                    .orElse(null);
            if (counted != null) {
                models.add(new ReviewPrecomputeModelDTO(
                        purpose,
                        counted.getDataHandlingTier(),
                        counted.getCalls(),
                        counted.getInputTokens(),
                        counted.getOutputTokens()));
            } else if (use != null) {
                models.add(new ReviewPrecomputeModelDTO(purpose, use.tier(), 0, 0, 0));
            }
        }
        return new ReviewPrecomputeDTO(
                run.practiceSlug(),
                practiceName,
                new PrecomputeRunDTO(
                        run.getStatus(),
                        run.getLeads(),
                        declared.stream().map(StoredModelUse::toDto).toList()),
                List.copyOf(models),
                run.getError(),
                run.getDurationMs());
    }
}
