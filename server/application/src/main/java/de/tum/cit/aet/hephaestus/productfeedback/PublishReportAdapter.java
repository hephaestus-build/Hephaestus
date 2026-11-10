package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionEnded;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Carries out a maintainer's {@code /publish} on a private report ({@link ActionKind#PUBLISH_REPORT}) through
 * {@link FollowService#publish}. It runs in the report's filing lane, so it never overlaps a filing of the report.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Instance-level product feedback; it belongs to no workspace")
class PublishReportAdapter implements ActionAdapter {

    private static final String KEY_PREFIX = "publish_report:";
    private static final int MAX_ATTEMPTS = 5;

    record Payload(UUID feedbackId) {}

    private final FollowService follow;
    private final ObjectMapper mapper;

    PublishReportAdapter(FollowService follow, ObjectMapper mapper) {
        this.follow = follow;
        this.mapper = mapper;
    }

    /** One command, keyed by when it was given: a later {@code /publish} is a new command. */
    static ActionRequest request(ObjectMapper mapper, UUID feedbackId, Instant requestedAt, @Nullable Long accountId) {
        return new ActionRequest(
                ActionKind.PUBLISH_REPORT,
                null,
                FileIssueAdapter.lane(feedbackId),
                KEY_PREFIX + feedbackId + ":" + requestedAt.toEpochMilli(),
                mapper.valueToTree(new Payload(feedbackId)),
                accountId,
                MAX_ATTEMPTS,
                null);
    }

    @Override
    public ActionKind kind() {
        return ActionKind.PUBLISH_REPORT;
    }

    @Override
    public ActionResult execute(ActionAttempt attempt) {
        return follow.publish(
                attempt, mapper.treeToValue(attempt.payload(), Payload.class).feedbackId());
    }

    /** The key is {@code publish_report:<report>:<requested at>}, as {@link #request} writes it. */
    @Override
    public void ended(ActionEnded ended) {
        String rest = ended.idempotencyKey().substring(KEY_PREFIX.length());
        follow.publishEnded(UUID.fromString(rest.substring(0, rest.indexOf(':'))), ended.state());
    }
}
