package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.BaseGitHubProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubPullRequestDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unified processor for GitHub pull requests.
 * <p>
 * This service handles the conversion of GitHubPullRequestDTO to PullRequest
 * entities,
 * persists them, and publishes appropriate domain events. It's used by both
 * the GraphQL sync service and webhook handlers.
 * <p>
 * <b>Design Principles:</b>
 * <ul>
 * <li>Single processing path for all data sources (sync and webhooks)</li>
 * <li>Idempotent operations via upsert pattern</li>
 * <li>Domain events published for reactive feature development</li>
 * <li>Works exclusively with DTOs for complete field coverage</li>
 * </ul>
 */
@Service
public class GitHubPullRequestProcessor extends BaseGitHubProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequestProcessor.class);

    private final PullRequestRepository pullRequestRepository;
    private final IssueRepository issueRepository;
    private final CommitRepository commitRepository;
    private final CommitAuthorResolver commitAuthorResolver;
    private final ApplicationEventPublisher eventPublisher;

    public GitHubPullRequestProcessor(
            PullRequestRepository pullRequestRepository,
            IssueRepository issueRepository,
            CommitRepository commitRepository,
            CommitAuthorResolver commitAuthorResolver,
            LabelRepository labelRepository,
            MilestoneRepository milestoneRepository,
            UserRepository userRepository,
            GitHubUserProcessor gitHubUserProcessor,
            ApplicationEventPublisher eventPublisher) {
        super(userRepository, labelRepository, milestoneRepository, gitHubUserProcessor);
        this.pullRequestRepository = pullRequestRepository;
        this.issueRepository = issueRepository;
        this.commitRepository = commitRepository;
        this.commitAuthorResolver = commitAuthorResolver;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Process a GitHub pull request DTO and persist it as a PullRequest entity.
     * Publishes appropriate domain events based on what changed.
     * <p>
     * Uses atomic upsert to prevent race conditions when concurrent threads
     * (e.g., multiple NATS consumers or webhook handlers) process the same PR.
     * <p>
     * Uses (repository_id, issue_type, number) as the canonical uniqueness constraint.
     * Lookup is by (repository_id, number) via the PullRequest typed query.
     *
     * @return the processed PullRequest, or null if the repository context is missing
     */
    @Transactional
    @Nullable
    public PullRequest process(GitHubPullRequestDTO dto, ProcessingContext context) {
        return processInternal(dto, context, true);
    }

    /**
     * Internal method that handles pull request processing.
     *
     * @param emitLifecycleOnCreate whether to emit lifecycle events (PullRequestClosed/Merged)
     *                              for PRs that arrive already in a terminal state. Set to false
     *                              when called from processClosed() which emits its own events.
     */
    @Nullable
    private PullRequest processInternal(
            GitHubPullRequestDTO dto, ProcessingContext context, boolean emitLifecycleOnCreate) {
        Repository repository = context.repository();
        if (repository == null || repository.getId() == null) {
            log.warn("Skipped pull request processing: reason=missingRepository, prNumber={}", dto.number());
            return null;
        }

        // Check for valid native ID - required for provider-scoped upsert
        Long dbId = dto.getDatabaseId();
        if (dbId == null) {
            log.warn("Skipped pull request processing: reason=missingDatabaseId, prNumber={}", dto.number());
            return null;
        }

        // Check if this is an update (for event publishing purposes)
        Optional<PullRequest> existingOpt =
                pullRequestRepository.findByRepositoryIdAndNumber(repository.getId(), dto.number());
        boolean isNew = existingOpt.isEmpty();

        // Detect issue_type mismatch: entity exists as ISSUE but we're processing it as a PR.
        // This happens when a comment webhook creates a stub ISSUE before the PR webhook arrives.
        // Must correct the discriminator BEFORE the upsert, because ON CONFLICT matches on
        // (repository_id, issue_type, number) — a mismatched issue_type would cause an INSERT
        // instead of an UPDATE, violating the (provider_id, native_id) constraint.
        boolean promotedFromIssue = false;
        if (isNew) {
            Optional<Issue> existingIssue =
                    issueRepository.findByRepositoryIdAndNumber(repository.getId(), dto.number());
            if (existingIssue.isPresent()) {
                issueRepository.correctDiscriminator(repository.getId(), dto.number(), "ISSUE", "PULL_REQUEST");
                // Don't re-fetch: the promoted row has NULL PR-specific columns (additions, etc.)
                // which would crash Hibernate on primitive int fields. The upsert below will
                // populate all fields correctly.
                promotedFromIssue = true;
                isNew = false;
                log.info(
                        "Corrected issue_type from ISSUE to PULL_REQUEST: repositoryId={}, number={}",
                        repository.getId(),
                        dto.number());
            }
        }

        // Skip update if existing data is newer (prevents stale webhooks from overwriting).
        // Skip stale check for promotions — the entity needs all PR fields populated by the upsert.
        if (!isNew && !promotedFromIssue) {
            PullRequest existing = existingOpt.get();
            if (existing.getUpdatedAt() != null
                    && dto.updatedAt() != null
                    && !dto.updatedAt().isAfter(existing.getUpdatedAt())) {
                log.debug(
                        "Skipped stale PR update: prId={}, existingUpdatedAt={}, dtoUpdatedAt={}",
                        existing.getId(),
                        existing.getUpdatedAt(),
                        dto.updatedAt());
                return existing;
            }
        }

        Long providerId = Objects.requireNonNull(context.providerId(), "Pull request processing requires a provider");
        User author = dto.author() != null ? findOrCreateUser(dto.author(), providerId) : null;
        User mergedBy = dto.mergedBy() != null ? findOrCreateUser(dto.mergedBy(), providerId) : null;
        Milestone milestone = dto.milestone() != null ? findOrCreateMilestone(dto.milestone(), repository) : null;

        // Extract branch info
        String headRefName = dto.head() != null ? dto.head().ref() : null;
        String headRefOid = dto.head() != null ? dto.head().sha() : null;
        String baseRefName = dto.base() != null ? dto.base().ref() : null;
        String baseRefOid = dto.base() != null ? dto.base().sha() : null;

        // Use atomic upsert to handle concurrent inserts
        Instant now = Instant.now();
        pullRequestRepository.upsertCore(
                dbId,
                providerId,
                dto.number(),
                Objects.requireNonNullElse(sanitize(dto.title()), ""),
                sanitize(dto.body()),
                convertState(dto.state()).name(),
                null, // stateReason not used for PRs
                dto.htmlUrl(),
                dto.locked(),
                dto.closedAt(),
                dto.commentsCount(),
                now,
                dto.createdAt(),
                dto.updatedAt(),
                author != null ? author.getId() : null,
                repository.getId(),
                milestone != null ? milestone.getId() : null,
                dto.mergedAt(),
                dto.isDraft(),
                dto.isMerged(),
                dto.commits(),
                dto.additions(),
                dto.deletions(),
                dto.changedFiles(),
                dto.reviewDecision() != null ? dto.reviewDecision().name() : null,
                dto.mergeStateStatus() != null ? dto.mergeStateStatus().name() : null,
                dto.isMergeable(),
                headRefName,
                baseRefName,
                headRefOid,
                baseRefOid,
                mergedBy != null ? mergedBy.getId() : null,
                null // mergeCommitSha: not written by this path; upsertMergeCommit upserts and links the merge commit
                );

        // Fetch the PR to get a managed entity and handle relationships
        PullRequest pr = pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), dto.number())
                .orElseThrow(() -> new IllegalStateException(
                        "PullRequest not found after upsert: repositoryId=" + repository.getId()
                                + ", number="
                                + dto.number()));

        // Handle ManyToMany relationships (labels, assignees, requestedReviewers)
        boolean relationshipsChanged = updateRelationships(dto, pr, repository, providerId);

        // Save relationship changes
        if (relationshipsChanged) {
            pr = pullRequestRepository.save(pr);
        }

        // Upsert merge commit if present (from GraphQL data — zero extra rate limit cost)
        upsertMergeCommit(dto, repository, context);

        // Publish events
        // Promotions (ISSUE→PR) are treated as new for event purposes: existingOpt is empty
        // because the entity was an Issue, not a PullRequest, so computeChangedFields would crash.
        if (isNew || promotedFromIssue) {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestCreated(
                    ScmEventPayload.PullRequestData.from(pr), EventContext.from(context)));
            log.debug("Created pull request: prId={}, prNumber={}", pr.getId(), dto.number());

            // Emit lifecycle events for PRs that arrived already in a terminal state during sync.
            // Skipped when called from processClosed() which emits its own events.
            if (emitLifecycleOnCreate && (pr.getState() == Issue.State.CLOSED || pr.getState() == Issue.State.MERGED)) {
                ScmEventPayload.PullRequestData prData = ScmEventPayload.PullRequestData.from(pr);
                EventContext eventContext = EventContext.from(context);
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestClosed(prData, pr.isMerged(), eventContext));
                if (pr.isMerged()) {
                    eventPublisher.publishEvent(new ScmDomainEvent.PullRequestMerged(prData, eventContext));
                }
                log.debug(
                        "Emitted lifecycle events for already-terminal PR: prId={}, merged={}",
                        pr.getId(),
                        pr.isMerged());
            }
        } else {
            Set<String> changedFields = computeChangedFields(existingOpt.get(), pr);
            if (!changedFields.isEmpty() || relationshipsChanged) {
                if (relationshipsChanged) {
                    changedFields.add("relationships");
                }
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestUpdated(
                        ScmEventPayload.PullRequestData.from(pr), changedFields, EventContext.from(context)));
                log.debug("Updated pull request: prId={}, changedFields={}", pr.getId(), changedFields);
            }
        }

        return pr;
    }

    /**
     * Updates ManyToMany relationships that can't be handled by the atomic upsert.
     *
     * @return true if any relationships were changed
     */
    private boolean updateRelationships(
            GitHubPullRequestDTO dto, PullRequest pr, Repository repository, Long providerId) {
        boolean assigneesChanged =
                updateAssignees(Objects.requireNonNullElse(dto.assignees(), List.of()), pr.getAssignees(), providerId);
        boolean labelsChanged =
                updateLabels(Objects.requireNonNullElse(dto.labels(), List.of()), pr.getLabels(), repository);
        boolean reviewersChanged = updateRequestedReviewers(
                Objects.requireNonNullElse(dto.requestedReviewers(), List.of()),
                pr.getRequestedReviewers(),
                providerId);
        return assigneesChanged || labelsChanged || reviewersChanged;
    }

    /**
     * Computes which scalar fields changed between the old and new PR state.
     * Relationship changes (labels, assignees, reviewers) are tracked separately.
     */
    private Set<String> computeChangedFields(PullRequest oldPr, PullRequest newPr) {
        Set<String> changedFields = new HashSet<>();

        if (!Objects.equals(oldPr.getTitle(), newPr.getTitle())) {
            changedFields.add("title");
        }
        if (!Objects.equals(oldPr.getBody(), newPr.getBody())) {
            changedFields.add("body");
        }
        if (oldPr.getState() != newPr.getState()) {
            changedFields.add("state");
        }
        if (oldPr.isDraft() != newPr.isDraft()) {
            changedFields.add("draft");
        }
        if (oldPr.isMerged() != newPr.isMerged()) {
            changedFields.add("merged");
        }
        if (!Objects.equals(oldPr.getMergedAt(), newPr.getMergedAt())) {
            changedFields.add("mergedAt");
        }
        if (oldPr.getAdditions() != newPr.getAdditions()) {
            changedFields.add("additions");
        }
        if (oldPr.getDeletions() != newPr.getDeletions()) {
            changedFields.add("deletions");
        }
        if (oldPr.getCommits() != newPr.getCommits()) {
            changedFields.add("commits");
        }
        if (!Objects.equals(oldPr.getHeadRefOid(), newPr.getHeadRefOid())) {
            changedFields.add("headRefOid");
        }

        return changedFields;
    }

    /**
     * Process a closed event.
     */
    @Transactional
    @Nullable
    public PullRequest processClosed(GitHubPullRequestDTO dto, ProcessingContext context) {
        PullRequest pr = processInternal(dto, context, false);
        if (pr == null) {
            return null;
        }
        boolean wasMerged = dto.isMerged();
        ScmEventPayload.PullRequestData prData = ScmEventPayload.PullRequestData.from(pr);
        EventContext eventContext = EventContext.from(context);

        eventPublisher.publishEvent(new ScmDomainEvent.PullRequestClosed(prData, wasMerged, eventContext));

        if (wasMerged) {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestMerged(prData, eventContext));
            log.info("Merged pull request: prId={}, prNumber={}", pr.getId(), pr.getNumber());
        } else {
            log.debug("Closed pull request: prId={}, merged=false", pr.getId());
        }

        return pr;
    }

    /**
     * Process a reopened event.
     */
    @Transactional
    @Nullable
    public PullRequest processReopened(GitHubPullRequestDTO dto, ProcessingContext context) {
        PullRequest pr = processInternal(dto, context, true);
        if (pr == null) {
            return null;
        }
        eventPublisher.publishEvent(new ScmDomainEvent.PullRequestReopened(
                ScmEventPayload.PullRequestData.from(pr), EventContext.from(context)));
        log.debug("Reopened pull request: prId={}", pr.getId());
        return pr;
    }

    /**
     * Process a ready_for_review event.
     */
    @Transactional
    @Nullable
    public PullRequest processReadyForReview(GitHubPullRequestDTO dto, ProcessingContext context) {
        PullRequest pr = processInternal(dto, context, true);
        if (pr == null) {
            return null;
        }
        eventPublisher.publishEvent(new ScmDomainEvent.PullRequestReady(
                ScmEventPayload.PullRequestData.from(pr), EventContext.from(context)));
        log.debug("Marked pull request ready for review: prId={}", pr.getId());
        return pr;
    }

    /**
     * Process a converted_to_draft event.
     */
    @Transactional
    @Nullable
    public PullRequest processConvertedToDraft(GitHubPullRequestDTO dto, ProcessingContext context) {
        PullRequest pr = processInternal(dto, context, true);
        if (pr == null) {
            return null;
        }
        eventPublisher.publishEvent(new ScmDomainEvent.PullRequestDrafted(
                ScmEventPayload.PullRequestData.from(pr), EventContext.from(context)));
        log.debug("Converted pull request to draft: prId={}", pr.getId());
        return pr;
    }

    /**
     * Process a synchronize event (new commits pushed).
     */
    @Transactional
    @Nullable
    public PullRequest processSynchronize(GitHubPullRequestDTO dto, ProcessingContext context) {
        PullRequest pr = processInternal(dto, context, true);
        if (pr == null) {
            return null;
        }
        eventPublisher.publishEvent(new ScmDomainEvent.PullRequestSynchronized(
                ScmEventPayload.PullRequestData.from(pr), EventContext.from(context)));
        log.debug("Synchronized pull request: prId={}", pr.getId());
        return pr;
    }

    /**
     * Process a labeled event.
     */
    @Transactional
    @Nullable
    public PullRequest processLabeled(GitHubPullRequestDTO dto, GitHubLabelDTO labelDto, ProcessingContext context) {
        PullRequest pr = processInternal(dto, context, true);
        if (pr == null) {
            return null;
        }
        Label label = findOrCreateLabel(
                labelDto, Objects.requireNonNull(context.repository(), "Label event requires a repository"));
        if (label != null) {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestLabeled(
                    ScmEventPayload.PullRequestData.from(pr),
                    ScmEventPayload.LabelData.from(label),
                    EventContext.from(context)));
            log.debug("Labeled pull request: prId={}, labelName={}", pr.getId(), label.getName());
        }
        return pr;
    }

    /**
     * Process an unlabeled event.
     */
    @Transactional
    @Nullable
    public PullRequest processUnlabeled(GitHubPullRequestDTO dto, GitHubLabelDTO labelDto, ProcessingContext context) {
        PullRequest pr = processInternal(dto, context, true);
        if (pr == null) {
            return null;
        }
        Label label = findOrCreateLabel(
                labelDto, Objects.requireNonNull(context.repository(), "Label event requires a repository"));
        if (label != null) {
            eventPublisher.publishEvent(new ScmDomainEvent.PullRequestUnlabeled(
                    ScmEventPayload.PullRequestData.from(pr),
                    ScmEventPayload.LabelData.from(label),
                    EventContext.from(context)));
            log.debug("Unlabeled pull request: prId={}, labelName={}", pr.getId(), label.getName());
        }
        return pr;
    }

    private Issue.State convertState(@Nullable String state) {
        if (state == null) {
            log.warn(
                    "PR state is null, defaulting to OPEN. This may indicate missing data in webhook or GraphQL response.");
            return Issue.State.OPEN;
        }
        return switch (state.toUpperCase()) {
            case "OPEN" -> Issue.State.OPEN;
            case "CLOSED" -> Issue.State.CLOSED;
            case "MERGED" -> Issue.State.MERGED;
            default -> {
                log.warn("Unknown PR state '{}', defaulting to OPEN", state);
                yield Issue.State.OPEN;
            }
        };
    }

    /**
     * Upserts the merge commit when GraphQL data provides full merge commit metadata.
     * This piggybacks on data already fetched in the PR query (flat fields on Commit type)
     * so it costs zero additional rate limit points.
     * <p>
     * After upserting the commit, links it to the PR in the commit_pull_request join table
     * so that the association is established immediately (not deferred to enrichment).
     */
    private void upsertMergeCommit(GitHubPullRequestDTO dto, Repository repository, ProcessingContext context) {
        var info = dto.mergeCommitInfo();
        if (info == null || info.sha() == null) {
            return;
        }

        boolean isNew = !commitRepository.existsByShaAndRepositoryId(info.sha(), repository.getId());

        Long providerId = repository.getProvider().getId();
        Long authorId = commitAuthorResolver.resolveByLogin(info.authorLogin(), providerId);
        Long committerId = commitAuthorResolver.resolveByLogin(info.committerLogin(), providerId);

        String htmlUrl = "https://github.com/" + repository.getNameWithOwner() + "/commit/" + info.sha();

        // Defense-in-depth: git_commit.message is NOT NULL; default to empty string
        String message = info.message() != null ? info.message() : "";

        commitRepository.upsertCommit(
                info.sha(),
                message,
                info.messageBody(),
                htmlUrl,
                info.authoredDate(),
                info.committedDate(),
                info.additions(),
                info.deletions(),
                info.changedFiles(),
                Instant.now(),
                repository.getId(),
                authorId,
                committerId,
                info.authorEmail(),
                info.committerEmail(),
                null);

        var commitOpt = commitRepository.findByShaAndRepositoryId(info.sha(), repository.getId());
        if (commitOpt.isPresent()) {
            commitRepository.linkCommitToPullRequests(
                    commitOpt.get().getId(), repository.getId(), List.of(dto.number()));
        }

        // Publish CommitCreated event for newly created merge commits
        if (isNew && commitOpt.isPresent()) {
            eventPublisher.publishEvent(new ScmDomainEvent.CommitCreated(
                    ScmEventPayload.CommitData.from(commitOpt.get()), EventContext.from(context)));
        }

        log.debug(
                "Upserted merge commit: sha={}, repository={}, isNew={}",
                info.sha(),
                repository.getNameWithOwner(),
                isNew);
    }
}
