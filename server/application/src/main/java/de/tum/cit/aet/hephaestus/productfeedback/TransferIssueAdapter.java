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
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssueUpdates;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackUpdatedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Moves a reporter's public issue to the private repository ({@link ActionKind#TRANSFER_ISSUE}). The reporter's request
 * carries it out at once ({@link IssueTransfers}); after an unclear answer, the executor finishes it. Its end moves the
 * filing, whichever of the two ends it.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Instance-level product feedback; it belongs to no workspace")
class TransferIssueAdapter implements ActionAdapter {
    private static final Logger log = LoggerFactory.getLogger(TransferIssueAdapter.class);

    private static final int MAX_ATTEMPTS = 5;

    /**
     * One move of one issue.
     *
     * @param marker the marker the issue carries: the report's own, or its promotion marker once it was published, so
     *     a lookup never finds the closed private issue that a publication left behind
     * @param since a time before the issue's last update, so a lookup by its marker finds it
     * @param requestedAt when the reporter asked; each request is its own move
     */
    record Payload(
            UUID feedbackId,
            long accountId,
            String marker,
            Instant requestedAt,
            ProductFeedbackIssues.Repository to,
            long number,
            String nodeId,
            String url,
            Instant since) {}

    /** The moved issue and where it is now, as {@code readback_ref}: an ended action keeps no payload. */
    record Ref(long number, String nodeId, String url, ProductFeedbackIssues.Repository to) {}

    private final ProductFeedbackIssues issues;
    private final ProductFeedbackIssueUpdates updates;
    private final ProductFeedbackFilingRepository filings;
    private final ProductFeedbackRepository feedback;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;
    private final Clock clock;

    TransferIssueAdapter(
            ProductFeedbackIssues issues,
            ProductFeedbackIssueUpdates updates,
            ProductFeedbackFilingRepository filings,
            ProductFeedbackRepository feedback,
            ApplicationEventPublisher events,
            ObjectMapper mapper,
            Clock clock) {
        this.issues = issues;
        this.updates = updates;
        this.filings = filings;
        this.feedback = feedback;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** The move of one issue node; the same issue never moves twice at once. */
    static ActionRequest request(ObjectMapper mapper, Payload payload) {
        return new ActionRequest(
                ActionKind.TRANSFER_ISSUE,
                null,
                FileIssueAdapter.lane(payload.feedbackId()),
                "transfer_issue:" + payload.feedbackId() + ":" + payload.nodeId() + ":"
                        + payload.requestedAt().toEpochMilli(),
                mapper.valueToTree(payload),
                payload.accountId(),
                MAX_ATTEMPTS,
                null,
                null);
    }

    @Override
    public ActionKind kind() {
        return ActionKind.TRANSFER_ISSUE;
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
            if (attempt.writeStarted()) {
                // An earlier attempt may have moved it: the issue keeps its marker in the private repository.
                var moved = issues.findByMarker(payload.to(), payload.marker(), payload.since());
                if (moved.isPresent()) return done(moved.get(), payload.to());
            }
            if (!attempt.fence().beginWrite()) return new ActionResult.Abandoned();
            fenced = true;
            return done(
                    updates.transfer(
                            new ProductFeedbackIssues.Issue(payload.number(), payload.nodeId(), payload.url()),
                            payload.to()),
                    payload.to());
        } catch (ProductFeedbackIssues.Refused refused) {
            return new ActionResult.Failed(ActionStopReason.REJECTED);
        } catch (RuntimeException unclear) {
            return fenced || attempt.writeStarted()
                    ? new ActionResult.Reread(ActionExecutor.backoff(attempt.attempt()))
                    : new ActionResult.Retry(ActionExecutor.backoff(attempt.attempt()));
        }
    }

    private ActionResult done(ProductFeedbackIssues.Issue issue, ProductFeedbackIssues.Repository to) {
        return new ActionResult.Done(
                mapper.writeValueAsString(new Ref(issue.number(), issue.nodeId(), issue.url(), to)));
    }

    /** A done move takes the filing to the private repository, in the transaction that ended the action. */
    @Override
    public void ended(ActionEnded ended) {
        String ref = ended.readbackRef();
        if (ended.state() != ActionState.DONE || ref == null) return;
        Ref moved = mapper.readValue(ref, Ref.class);
        // The key is transfer_issue:<report>:<issue node>:<requested at>.
        String rest = ended.idempotencyKey().substring("transfer_issue:".length());
        UUID id = UUID.fromString(rest.substring(0, rest.indexOf(':')));
        String fromNode = rest.substring(rest.indexOf(':') + 1, rest.lastIndexOf(':'));
        var current = filings.lock(id)
                .filter(filing -> filing.getState() == FilingState.FILED
                        && filing.getRoute() == FilingRoute.PUBLIC
                        && fromNode.equals(filing.getIssueNodeId()));
        if (current.isEmpty())
            log.info("Product feedback move ended, but its filing changed meanwhile: feedbackId={}", id);
        current.ifPresent(filing -> {
            Instant now = clock.instant();
            filing.apply(FilingLifecycle.Event.ROUTE_CHANGED_ON_GITHUB);
            filing.setRoute(FilingRoute.PRIVATE);
            filing.setReason(FilingReason.OPTED_OUT);
            filing.setAllowPublic(false);
            filing.setRepository(moved.to().fullName());
            filing.setRepositoryId(moved.to().id());
            filing.setRepositoryNodeId(moved.to().nodeId());
            filing.setInstallationId(moved.to().installationId());
            filing.setIssueNumber(moved.number());
            filing.setIssueNodeId(moved.nodeId());
            filing.setIssueUrl(moved.url());
            filing.setUpdatedAt(now);
            feedback.findById(id)
                    .ifPresent(report -> events.publishEvent(new ProductFeedbackUpdatedEvent(
                            id,
                            report.getAccountId(),
                            ProductFeedbackUpdatedEvent.Kind.MADE_PRIVATE,
                            null,
                            false,
                            null,
                            now)));
        });
    }
}
