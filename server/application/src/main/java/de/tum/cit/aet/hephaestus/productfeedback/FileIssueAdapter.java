package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionEnded;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.action.ActionVariants;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Files one product-feedback issue on GitHub ({@link ActionKind#FILE_ISSUE}). Each issue body carries the report's id
 * as its marker, so an attempt whose write may have reached GitHub finds that issue before it writes again, and a
 * report never becomes two issues.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Instance-level product feedback; it belongs to no workspace")
class FileIssueAdapter implements ActionAdapter {

    private static final String KEY_PREFIX = "file_issue:";

    /**
     * One hand-over of a report.
     *
     * @param generation the report's hand-over; an end of an older one changes nothing
     * @param since the earliest time an issue of this report can have been created
     * @param issue what to file; {@code null} only looks for an earlier generation's issue
     */
    record Payload(
            UUID feedbackId,
            int generation,
            ProductFeedbackIssues.Repository repository,
            Instant since,
            @Nullable IssueText issue) {}

    record IssueText(String title, String body, @Nullable String type) {}

    /** The issue as {@code readback_ref}. */
    record Ref(long number, String nodeId, String url) {}

    private final ProductFeedbackIssues issues;
    private final FilingService filing;
    private final ObjectMapper mapper;

    FileIssueAdapter(ProductFeedbackIssues issues, FilingService filing, ObjectMapper mapper) {
        this.issues = issues;
        this.filing = filing;
        this.mapper = mapper;
    }

    /** The lane of every GitHub write about one report. */
    static String lane(UUID feedbackId) {
        return KEY_PREFIX + feedbackId;
    }

    /** The action of one hand-over. Hand-overs of one report run in order, in one lane. */
    static ActionRequest request(ObjectMapper mapper, Payload payload, @Nullable Long accountId) {
        return new ActionRequest(
                ActionKind.FILE_ISSUE,
                null,
                lane(payload.feedbackId()),
                KEY_PREFIX + payload.feedbackId() + ":" + payload.generation(),
                mapper.valueToTree(payload),
                accountId,
                FilingService.MAX_ATTEMPTS,
                null);
    }

    @Override
    public ActionKind kind() {
        return ActionKind.FILE_ISSUE;
    }

    @Override
    public ActionVariants variants() {
        return ActionVariants.one(Payload.class);
    }

    @Override
    public ActionResult execute(ActionAttempt attempt) {
        Payload payload = mapper.treeToValue(attempt.payload(), Payload.class);
        boolean fenced = false;
        try {
            IssueText issue = payload.issue();
            if (issue == null || attempt.writeStarted()) {
                Optional<ProductFeedbackIssues.Issue> found = issues.findByMarker(
                        payload.repository(), payload.feedbackId().toString(), payload.since());
                if (found.isPresent()) return done(found.get());
                if (issue == null) return new ActionResult.Cancelled(ActionStopReason.ABSENT);
            }
            ProductFeedbackIssues.Repository repository = payload.repository();
            if (repository.isPrivate() && !issues.isPrivate(repository).orElse(false)) {
                return new ActionResult.Failed(ActionStopReason.RECIPIENT_UNAVAILABLE);
            }
            String type = issue.type();
            if (type != null && !issues.issueTypes(repository).contains(type)) type = null;
            // An erasure, a purge or a newer hand-over may have taken the report since this action was enqueued.
            if (!filing.current(payload.feedbackId(), payload.generation()))
                return new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
            if (!attempt.fence().beginWrite()) return new ActionResult.Abandoned();
            fenced = true;
            return done(issues.create(repository, issue.title(), issue.body(), type));
        } catch (ProductFeedbackIssues.Refused refused) {
            return new ActionResult.Failed(ActionStopReason.REJECTED);
        } catch (RuntimeException unclear) {
            // GitHub may hold the issue: the next attempt looks for it by its marker before it writes again.
            return fenced || attempt.writeStarted()
                    ? new ActionResult.Reread(ActionExecutor.backoff(attempt.attempt()))
                    : new ActionResult.Retry(ActionExecutor.backoff(attempt.attempt()));
        }
    }

    private ActionResult done(ProductFeedbackIssues.Issue issue) {
        return new ActionResult.Done(mapper.writeValueAsString(new Ref(issue.number(), issue.nodeId(), issue.url())));
    }

    /** The key is {@code file_issue:<report>:<generation>}, as {@link #request} writes it. */
    @Override
    public void ended(ActionEnded ended) {
        String key = ended.idempotencyKey().substring(KEY_PREFIX.length());
        int split = key.lastIndexOf(':');
        String ref = ended.readbackRef();
        filing.ended(
                UUID.fromString(key.substring(0, split)),
                Integer.parseInt(key.substring(split + 1)),
                ended,
                ended.state() == ActionState.DONE && ref != null ? mapper.readValue(ref, Ref.class) : null);
    }
}
