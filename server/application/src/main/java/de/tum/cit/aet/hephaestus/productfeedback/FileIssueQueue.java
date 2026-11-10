package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Hands a report to the outbox as one {@code file_issue} action, in the caller's transaction. */
@Component
@RequiredArgsConstructor
class FileIssueQueue {
    private final AgentActions actions;
    private final ObjectMapper mapper;

    void enqueue(FileIssueAdapter.Payload payload, @Nullable Long accountId) {
        actions.enqueue(FileIssueAdapter.request(mapper, payload, accountId));
    }

    /** Queues the scrub of one erased report's issue, due after {@code delay}; it gives up at {@code until}. */
    void cleanup(UUID recordId, Duration delay, Instant until) {
        actions.enqueueNew(CleanupIssueAdapter.request(mapper, recordId, until), delay);
    }

    /** Queues a {@code /publish} command, in the report's lane, after its filing. */
    void publish(UUID feedbackId, Instant requestedAt, @Nullable Long accountId) {
        actions.enqueue(PublishReportAdapter.request(mapper, feedbackId, requestedAt, accountId));
    }
}
