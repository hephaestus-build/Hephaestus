package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionEnded;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionVariants;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Scrubs the GitHub issue of one erased report ({@link ActionKind#CLEANUP_ISSUE}) through {@link CleanupService}. The
 * action holds no person data: its record names the repository and the issue.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Instance-level product feedback; it belongs to no workspace")
class CleanupIssueAdapter implements ActionAdapter {

    private static final String KEY_PREFIX = "cleanup:";

    /** Enough attempts at the longest backoff to last until the record expires and a maintainer takes over. */
    private static final int MAX_ATTEMPTS = 1_000;

    record Payload(UUID recordId) {}

    private final CleanupService cleanup;
    private final ObjectMapper mapper;

    CleanupIssueAdapter(CleanupService cleanup, ObjectMapper mapper) {
        this.cleanup = cleanup;
        this.mapper = mapper;
    }

    static ActionRequest request(ObjectMapper mapper, UUID recordId, Instant until) {
        return new ActionRequest(
                ActionKind.CLEANUP_ISSUE,
                null,
                KEY_PREFIX + recordId,
                KEY_PREFIX + recordId,
                mapper.valueToTree(new Payload(recordId)),
                null,
                MAX_ATTEMPTS,
                until,
                null);
    }

    @Override
    public ActionKind kind() {
        return ActionKind.CLEANUP_ISSUE;
    }

    @Override
    public ActionVariants variants() {
        return ActionVariants.one(Payload.class);
    }

    @Override
    public ActionResult execute(ActionAttempt attempt) {
        return cleanup.clean(
                attempt, mapper.treeToValue(attempt.payload(), Payload.class).recordId());
    }

    @Override
    public void ended(ActionEnded ended) {
        cleanup.ended(UUID.fromString(ended.idempotencyKey().substring(KEY_PREFIX.length())), ended.state());
    }
}
