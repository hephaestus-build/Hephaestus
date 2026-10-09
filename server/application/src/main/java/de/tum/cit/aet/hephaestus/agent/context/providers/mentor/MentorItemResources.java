package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The on-demand context resources: one item a context file names by its {@code resource}, read now for the developer.
 * Each owner checks that the item is the developer's to read and answers {@code NOT_FOUND} otherwise. The Heph host
 * mirrors their key patterns in {@code heph-host-protocol.ts}.
 */
@Component
@RequiredArgsConstructor
public class MentorItemResources {

    private final MergeReadinessContentSource mergeReadiness;
    private final ObservationHistoryContentSource observationHistory;
    private final ReviewAttemptsContentSource reviewAttempts;
    private final IssueDetailSource issues;

    /** The item {@code path} names, or empty when it names none. */
    public Optional<JsonNode> read(long workspaceId, long developerId, String path) {
        Optional<Long> pullRequest = MergeReadinessContentSource.artifactIdOf(path);
        if (pullRequest.isPresent()) {
            return Optional.of(mergeReadiness.inspect(workspaceId, developerId, pullRequest.get()));
        }
        Optional<Long> issue = IssueDetailSource.artifactIdOf(path);
        if (issue.isPresent()) {
            return Optional.of(issues.inspect(workspaceId, developerId, issue.get()));
        }
        return ObservationHistoryContentSource.observationIdOf(path)
                .<JsonNode>map(id -> observationHistory.inspect(workspaceId, developerId, id))
                .or(() -> ReviewAttemptsContentSource.workOf(path)
                        .map(work -> reviewAttempts.inspect(workspaceId, developerId, work)));
    }
}
