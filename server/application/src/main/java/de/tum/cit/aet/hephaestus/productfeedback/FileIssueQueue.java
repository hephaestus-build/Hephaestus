package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.action.AgentActions;
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
}
