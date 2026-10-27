package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.metrics.ReviewRefusalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.spi.FoundBy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Fans out one exact provider mirror event to explicitly monitored workspace items")
@RequiredArgsConstructor
@Slf4j
public class ScmItemEventRecorder {
    private final ItemIndexService writer;
    private final ScmItemService items;
    private final ScmWorkCapture capture;
    private final ScmRepositoryAccess access;
    private final RepositoryRepository repositories;
    private final ScmItemSourceQuery queries;
    private final MeterRegistry meters;
    private final IssueRepository issues;
    private final IssueEvidenceVersion issueVersions;
    private final ItemEventService events;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPullRequest(ScmDomainEvent.PullRequestEvent event) {
        pinUnresolved(event.pullRequest().repository().id(), event.pullRequest().id());
        ItemEventName name = switch (event) {
            case ScmDomainEvent.PullRequestCreated ignored -> ScmItemEvents.PULL_REQUEST_OPENED;
            case ScmDomainEvent.PullRequestReady ignored -> ScmItemEvents.PULL_REQUEST_READY;
            case ScmDomainEvent.PullRequestSynchronized ignored -> ScmItemEvents.PULL_REQUEST_SYNCHRONIZED;
            case ScmDomainEvent.PullRequestMerged ignored -> ScmItemEvents.PULL_REQUEST_MERGED;
            case ScmDomainEvent.PullRequestClosed closed ->
                closed.wasMerged() ? null : ScmItemEvents.PULL_REQUEST_CLOSED;
            case ScmDomainEvent.PullRequestUpdated updated ->
                Collections.disjoint(updated.changedFields(), ScmItemEvents.LINKED_PULL_REQUEST_MATERIAL_FIELDS)
                        ? null
                        : ScmItemEvents.PULL_REQUEST_EDITED;
            default -> null;
        };
        if (name != null)
            record(
                    event.pullRequest().repository().id(),
                    event.pullRequest().id(),
                    ScmItemEvents.PULL_REQUEST,
                    null,
                    name,
                    event.context());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onIssue(ScmDomainEvent.IssueEvent event) {
        if (event instanceof ScmDomainEvent.IssueDeleted) return;
        var issue = Objects.requireNonNull(event.issue());
        if (issue.isPullRequest()) return;
        ItemEventName name = switch (event) {
            case ScmDomainEvent.IssueCreated ignored -> ScmItemEvents.ISSUE_OPENED;
            case ScmDomainEvent.IssueClosed ignored -> ScmItemEvents.ISSUE_CLOSED;
            case ScmDomainEvent.IssueUpdated updated ->
                Collections.disjoint(updated.changedFields(), ScmItemEvents.REVIEWABLE_ISSUE_FIELDS)
                        ? null
                        : ScmItemEvents.ISSUE_UPDATED;
            case ScmDomainEvent.IssueReopened ignored -> ScmItemEvents.ISSUE_UPDATED;
            default -> null;
        };
        if (name != null) record(issue.repository().id(), issue.id(), ScmItemEvents.ISSUE, null, name, event.context());
        if (event instanceof ScmDomainEvent.IssueClosed
                || event instanceof ScmDomainEvent.IssueReopened
                || (event instanceof ScmDomainEvent.IssueUpdated updated
                        && updated.changedFields().contains("state"))) {
            var parent = issues.findByIdWithRepository(issue.id())
                    .map(current -> current.getParentIssue())
                    .orElse(null);
            if (parent != null)
                record(
                        Objects.requireNonNull(parent.getRepository()).getId(),
                        parent.getId(),
                        ScmItemEvents.ISSUE,
                        null,
                        ScmItemEvents.ISSUE_UPDATED,
                        event.context());
        }
        if (event instanceof ScmDomainEvent.IssueUpdated updated
                && !Collections.disjoint(updated.changedFields(), ScmItemEvents.LINKED_ISSUE_MATERIAL_FIELDS)) {
            var parents = queries.closingPullRequests(issue.id());
            for (var parent : parents) {
                pinUnresolved(parent.getRepositoryId(), parent.getSourceId());
                record(
                        parent.getRepositoryId(),
                        parent.getSourceId(),
                        ScmItemEvents.PULL_REQUEST,
                        null,
                        ScmItemEvents.PULL_REQUEST_LINKED_ISSUE_UPDATED,
                        event.context());
            }
        }
    }

    // The publisher still holds the mirror until synchronous consumers capture its final version.
    @EventListener
    @Transactional
    public void onDeleted(ScmDomainEvent.IssueDeleted event) {
        var issue = issues.findByIdWithRepository(event.issueId()).orElseThrow();
        var version = issueVersions.of(ScmEventPayload.IssueData.from(issue));
        for (var item : queries.deletedItems(ScmItemEvents.ISSUE.value(), event.issueId())) {
            if (writer.remove(
                    item.getWorkspaceId(), item.getItemId(), event.context().occurredAt())) {
                writer.record(new ItemEventInput(
                        item.getWorkspaceId(),
                        item.getItemId(),
                        ScmItemEvents.ISSUE_DELETED,
                        version.value(),
                        event.context().occurredAt(),
                        event.context().isWebhook() ? FoundBy.WEBHOOK : FoundBy.SYNC,
                        event.context().actorUserId(),
                        null));
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onReview(ScmDomainEvent.ReviewSubmitted event) {
        var review = event.review();
        if (review.repositoryId() != null)
            record(
                    review.repositoryId(),
                    review.id(),
                    ScmItemEvents.PULL_REQUEST_REVIEW,
                    review.pullRequestId(),
                    ScmItemEvents.PULL_REQUEST_REVIEW_SUBMITTED,
                    event.context());
    }

    private void record(
            long repositoryId,
            long sourceId,
            ItemKind kind,
            @Nullable Long parentSourceId,
            ItemEventName name,
            EventContext context) {
        repositories.claimForIndex(repositoryId);
        var repository = repositories.findById(repositoryId).orElseThrow();
        List<Long> workspaces = queries.monitoredWorkspaces(repository.getNameWithOwner());
        for (long workspace : workspaces) {
            try {
                access.authorize(workspace, repository);
            } catch (ItemCaptureException denied) {
                if (denied.code() == ItemCaptureCode.ITEM_NOT_VISIBLE) continue;
                throw denied;
            }
            ItemRef holder =
                    writer.upsert(new ItemRegistration(workspace, ScmItemEvents.REPOSITORY, repositoryId, null, null));
            writer.restore(workspace, holder.itemId());
            Long parent = holder.itemId();
            if (parentSourceId != null)
                parent = writer.upsert(new ItemRegistration(
                                workspace, ScmItemEvents.PULL_REQUEST, parentSourceId, holder.itemId(), null))
                        .itemId();
            ItemRef item = writer.upsert(new ItemRegistration(workspace, kind, sourceId, parent, null));
            writer.restore(workspace, item.itemId());
            // Serializes every event write on the item: an unresolved occasion has no unique key to settle a race.
            writer.lock(workspace, item.itemId());
            ItemVersion version;
            FoundBy foundBy = context.isWebhook() ? FoundBy.WEBHOOK : FoundBy.SYNC;
            try {
                version = items.pin(item, null).version();
            } catch (ItemCaptureException unavailable) {
                if (unavailable.code() != ItemCaptureCode.VERSION_UNAVAILABLE
                        && unavailable.code() != ItemCaptureCode.SOURCE_NOT_READY) throw unavailable;
                var fence = unavailable.code() == ItemCaptureCode.VERSION_UNAVAILABLE ? fence(item) : null;
                if (fence == null) {
                    // A partial provider read can update the mirror without proving an event version.
                    ReviewRefusalRecorder.record(
                            meters, "submission", unavailable.code().name());
                    continue;
                }
                // GitLab stores the diff base after the hook; the occasion waits for it while its fence holds.
                writer.record(new ItemEventInput(
                        workspace,
                        item.itemId(),
                        name,
                        null,
                        fence,
                        context.occurredAt(),
                        foundBy,
                        context.actorUserId(),
                        null,
                        items.retain(item, null, foundBy),
                        context.sourceKey()));
                continue;
            }
            // The occasion keeps the work as this transaction stores it, before its review is admitted later.
            writer.record(new ItemEventInput(
                    workspace,
                    item.itemId(),
                    name,
                    version.value(),
                    null,
                    context.occurredAt(),
                    foundBy,
                    context.actorUserId(),
                    null,
                    items.retain(item, version, foundBy),
                    context.sourceKey()));
        }
    }

    /**
     * Pins the occasions this pull request recorded before its version was known, once the mirror can prove one. The
     * fence and the version are read in this transaction under the item lock, so an equal fence means the version
     * differs from the occasion's only by the base. An occasion whose fence changed is withheld: the change is its own
     * occasion, and the old one is never pinned to later work.
     */
    private void pinUnresolved(long repositoryId, long pullRequestId) {
        repositories.claimForIndex(repositoryId);
        var repository = repositories.findById(repositoryId).orElseThrow();
        for (long workspace : queries.monitoredWorkspaces(repository.getNameWithOwner())) {
            var item = writer.findSource(workspace, ScmItemEvents.PULL_REQUEST.value(), pullRequestId)
                    .orElse(null);
            if (item == null || events.unresolved(workspace, item.itemId()).isEmpty()) continue;
            writer.lock(workspace, item.itemId());
            String fence;
            ItemVersion version = null;
            try {
                fence = items.pendingFence(item).orElseThrow();
                version = items.pin(item, null).version();
            } catch (ItemCaptureException unavailable) {
                if (unavailable.code() == ItemCaptureCode.ITEM_REMOVED) {
                    withhold(workspace, item.itemId(), ReviewRequestReason.ITEM_GONE);
                    continue;
                }
                if (unavailable.code() != ItemCaptureCode.VERSION_UNAVAILABLE
                        && unavailable.code() != ItemCaptureCode.SOURCE_NOT_READY
                        && unavailable.code() != ItemCaptureCode.ITEM_NOT_VISIBLE) throw unavailable;
                fence = fence(item);
                if (fence == null) continue;
            }
            for (var event : events.unresolved(workspace, item.itemId())) {
                if (!event.pendingFence().equals(fence))
                    events.decide(
                            workspace,
                            event.id(),
                            event.state(),
                            ItemEventState.WITHHELD,
                            ReviewRequestReason.VERSION_SUPERSEDED);
                else if (version != null) {
                    String completed;
                    try {
                        completed = capture.complete(events.pendingEvidence(workspace, event.id()), version);
                    } catch (ItemCaptureException invalid) {
                        if (invalid.code() != ItemCaptureCode.CAPTURE_FAILED) throw invalid;
                        // A bad retained record is local to its occasion, not a failed shared provider read.
                        ReviewRefusalRecorder.record(
                                meters, "submission", invalid.code().name());
                        log.warn(
                                "Pending occasion evidence could not be completed: workspaceId={}, itemId={}, eventId={}, reason={}",
                                workspace,
                                item.itemId(),
                                event.id(),
                                invalid.code());
                        continue;
                    }
                    // The equal fence proves the retained words, head and target unchanged; only the base is new. The
                    // state and roles stay those the occasion retained, also when they changed since.
                    events.pin(workspace, item.itemId(), event, version.value(), completed);
                }
            }
        }
    }

    private void withhold(long workspace, long itemId, ReviewRequestReason reason) {
        for (var event : events.unresolved(workspace, itemId))
            events.decide(workspace, event.id(), event.state(), ItemEventState.WITHHELD, reason);
    }

    /** The item's pending fence, or null while the source cannot compose one (no head stored, or not visible). */
    private @Nullable String fence(ItemRef item) {
        try {
            return items.pendingFence(item).orElse(null);
        } catch (ItemCaptureException unavailable) {
            if (unavailable.code() == ItemCaptureCode.VERSION_UNAVAILABLE
                    || unavailable.code() == ItemCaptureCode.SOURCE_NOT_READY
                    || unavailable.code() == ItemCaptureCode.ITEM_NOT_VISIBLE) return null;
            throw unavailable;
        }
    }
}
