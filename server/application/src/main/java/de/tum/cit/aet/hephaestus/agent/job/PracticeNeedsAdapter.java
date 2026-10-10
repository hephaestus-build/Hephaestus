package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobPrecomputeRunRepository.LatestRunRow;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePrecomputeSummaries;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePrecomputeSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeAsOfDTO;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeModelPurpose;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeNeedDTO;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * What each practice's precompute script needs. A script declares its models only when it runs, so the needs
 * come from the newest run that reported them, and only while that run's script is still the practice's
 * script. Whether a need is met comes from today's routing, so binding a model shows at once.
 */
@Service
@RequiredArgsConstructor
class PracticeNeedsAdapter implements PracticePrecomputeSummaries {

    private static final TypeReference<List<StoredModelUse>> MODELS = new TypeReference<>() {};

    private final AgentJobPrecomputeRunRepository runs;
    private final MemberAiRoutingAdapter routing;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<PracticePrecomputeSummaryDTO> latestCurrent(long workspaceId) {
        Map<PrecomputeModelPurpose, List<DataHandlingTier>> unserved = new EnumMap<>(PrecomputeModelPurpose.class);
        return runs.findLatestRunPerPracticeByWorkspaceId(workspaceId).stream()
                .map(row -> summary(
                        row,
                        purpose -> unserved.computeIfAbsent(
                                purpose, p -> routing.unservedTiers(workspaceId, AgentPurpose.valueOf(p.name())))))
                .toList();
    }

    private PracticePrecomputeSummaryDTO summary(
            LatestRunRow row, Function<PrecomputeModelPurpose, List<DataHandlingTier>> unserved) {
        UUID jobId = row.getJobId();
        Instant finishedAt = row.getFinishedAt();
        String models = row.getModels();
        if (jobId == null || finishedAt == null || models == null) {
            return new PracticePrecomputeSummaryDTO(
                    row.getPracticeSlug(), row.getPracticeName(), null, false, List.of());
        }
        if (!row.getCurrent()) {
            return new PracticePrecomputeSummaryDTO(
                    row.getPracticeSlug(), row.getPracticeName(), null, true, List.of());
        }
        List<PrecomputeNeedDTO> needs = objectMapper.readValue(models, MODELS).stream()
                .map(use -> new PrecomputeNeedDTO(use.purpose(), use.need(), unserved.apply(use.purpose())))
                .toList();
        return new PracticePrecomputeSummaryDTO(
                row.getPracticeSlug(), row.getPracticeName(), new PrecomputeAsOfDTO(jobId, finishedAt), false, needs);
    }
}
