package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeCoverageLedger;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewRunFactsRow;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class ReviewRunLookupAdapter implements ReviewRunLookup {

    private final AgentJobRepository repository;
    private final ReviewRunTargets targets;

    /** A list: a provider can be switched off, and then its runs carry no feedback address. */
    private final List<SummaryChannel> summaryChannels;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Target> findTargets(long workspaceId, Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        return targets.of(workspaceId, repository.findReviewRunTargets(workspaceId, jobIds));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ReviewRunFacts> findFacts(long workspaceId, Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        List<ReviewRunFactsRow> rows = repository.findReviewRunFacts(workspaceId, jobIds);
        Map<UUID, Target> named = targets.of(workspaceId, rows);
        return rows.stream().collect(Collectors.toUnmodifiableMap(ReviewRunFactsRow::getId, row -> {
            Target target = Objects.requireNonNull(named.get(row.getId()));
            return new ReviewRunFacts(
                    target,
                    AgentJobReviewRunStates.of(row.getStatus()),
                    row.getTriggerMode(),
                    PracticeCoverageLedger.from(row.getOutput()).evaluated(),
                    feedbackUrl(row, target));
        }));
    }

    /** The summary comment on the work's own page, addressed by the channel that posted it; null otherwise. */
    private @Nullable String feedbackUrl(ReviewRunFactsRow row, Target target) {
        String commentId = row.getDeliveryCommentId();
        String workUrl = target.url();
        IntegrationKind provider = row.getIntegrationKind();
        if (commentId == null || workUrl == null || provider == null) {
            return null;
        }
        return summaryChannels.stream()
                .filter(channel -> channel.kind() == provider)
                .findFirst()
                .map(channel -> channel.summaryCommentUrl(workUrl, commentId))
                .orElse(null);
    }
}
