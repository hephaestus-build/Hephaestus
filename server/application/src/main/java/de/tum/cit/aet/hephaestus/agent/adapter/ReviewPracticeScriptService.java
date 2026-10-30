package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.job.ReviewRuntimeRepository;
import de.tum.cit.aet.hephaestus.agent.job.ScriptRun;
import de.tum.cit.aet.hephaestus.agent.job.ScriptRunRepository;
import de.tum.cit.aet.hephaestus.agent.job.ScriptUsage;
import de.tum.cit.aet.hephaestus.agent.job.ScriptUsageRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeScriptModelDTO;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeScriptRunDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewScriptQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptKind;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptNoResultCause;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptRunStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptStopReason;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewPracticeScriptService implements ReviewScriptQuery {
    private final ScriptUsageRepository usage;
    private final ScriptRunRepository runs;
    private final ReviewService reviews;
    private final ReviewRuntimeRepository runtime;

    @Override
    public Map<UUID, List<PracticeScriptRunDTO>> forReviews(long workspaceId, Collection<UUID> reviewIds) {
        var pinnedByReview = reviews.findPinned(workspaceId, reviewIds);
        if (pinnedByReview.isEmpty()) return Map.of();
        var reviewOfJob = runtime.jobsOf(workspaceId, pinnedByReview.keySet()).stream()
                .collect(Collectors.toMap(
                        ReviewRuntimeRepository.JobOfReview::jobId, ReviewRuntimeRepository.JobOfReview::reviewId));
        if (reviewOfJob.isEmpty()) return Map.of();
        var byRun = usage.findByWorkspaceIdAndJobIdIn(workspaceId, reviewOfJob.keySet()).stream()
                .collect(Collectors.groupingBy(row -> new ScriptRun.Id(
                        row.getId().getJobId(),
                        row.getId().getAttempt(),
                        row.getId().getPracticeId())));
        Map<UUID, List<PracticeScriptRunDTO>> byReview = new HashMap<>();
        runs.findByWorkspaceIdAndJobIdIn(workspaceId, reviewOfJob.keySet()).stream()
                .sorted(Comparator.comparing(ScriptRun::getFinishedAt)
                        .thenComparingInt(run -> run.getId().getAttempt())
                        .thenComparingLong(run -> run.getId().getPracticeId()))
                .forEach(run -> {
                    var reviewId =
                            Objects.requireNonNull(reviewOfJob.get(run.getId().getJobId()));
                    byReview.computeIfAbsent(reviewId, ignored -> new ArrayList<>())
                            .add(present(run, Objects.requireNonNull(pinnedByReview.get(reviewId)), byRun));
                });
        return byReview;
    }

    private PracticeScriptRunDTO present(
            ScriptRun run, List<PinnedPractice> practices, Map<ScriptRun.Id, List<ScriptUsage>> byRun) {
        var practice = practices.stream()
                .filter(pinned -> pinned.practiceId() == run.getId().getPracticeId()
                        && pinned.practiceVersionId() == run.getPracticeVersionId())
                .findFirst();
        List<PracticeScriptModelDTO> models = byRun.getOrDefault(run.getId(), List.of()).stream()
                .map(usage -> new PracticeScriptModelDTO(
                        usage.modelKind(),
                        modelId(practice.orElse(null), usage.modelKind()),
                        usage.getDataHandlingTier(),
                        usage.getCalls(),
                        usage.getInputTokens(),
                        usage.getOutputTokens(),
                        usage.getCacheReadTokens(),
                        usage.getCacheWriteTokens()))
                .toList();
        JsonNode sessions = reviewSessions(run);
        return new PracticeScriptRunDTO(
                run.getId().getJobId(),
                run.getId().getAttempt(),
                Long.toString(run.getId().getPracticeId()),
                Long.toString(run.getPracticeVersionId()),
                run.getStatus(),
                run.getScriptKind(),
                run.getStopReason(),
                noResultCause(run, sessions),
                run.getFinishedAt(),
                run.getDurationMs() == null ? null : run.getDurationMs().longValue(),
                run.getError(),
                models,
                sessions == null ? null : sessions.path("modelCalls").asInt(0));
    }

    /** The runner's review-session counts, as the recorder stored them; null for a run recorded without them. */
    private static @Nullable JsonNode reviewSessions(ScriptRun run) {
        JsonNode calls = run.getCalls();
        JsonNode sessions = calls == null ? null : calls.get(ScriptRun.REVIEW_SESSIONS);
        return sessions != null && sessions.isObject() ? sessions : null;
    }

    /**
     * A built-in only calls the review, so its OFF_FORMAT can only be a review session that ended without an accepted
     * result. A counted schema refusal proves the wrong format, so the stop reason then stands alone. A custom script
     * can also fail on its own direct model after its review, so the review-session counts prove nothing about it.
     */
    static @Nullable ScriptNoResultCause noResultCause(ScriptRun run, @Nullable JsonNode sessions) {
        if (run.getScriptKind() != ScriptKind.BUILTIN
                || run.getStatus() != ScriptRunStatus.FAILED
                || run.getStopReason() != ScriptStopReason.OFF_FORMAT) {
            return null;
        }
        if (sessions != null && sessions.path("evidenceRefusals").asInt(0) > 0) {
            return ScriptNoResultCause.EVIDENCE_NOT_READ;
        }
        if (sessions != null && sessions.path("formatRefusals").asInt(0) > 0) {
            return null;
        }
        return ScriptNoResultCause.SESSION_ENDED;
    }

    private static @Nullable String modelId(@Nullable PinnedPractice practice, ModelKind kind) {
        if (practice == null) return null;
        String slot = kind.name().toLowerCase(Locale.ROOT);
        return practice.method().models().stream()
                .filter(model -> model.slot().equals(slot))
                .map(model -> model.model() == null ? null : model.model().modelId())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
