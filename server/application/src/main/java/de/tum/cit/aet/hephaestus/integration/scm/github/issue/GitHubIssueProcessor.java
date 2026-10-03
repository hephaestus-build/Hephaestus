package de.tum.cit.aet.hephaestus.integration.scm.github.issue;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.BaseGitHubProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueTypeDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuetype.GitHubIssueTypeSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * Unified processor for GitHub issues, converting GitHubIssueDTO to Issue entities and
 * persisting them. Used by both the GraphQL sync service and webhook handlers.
 * <p>
 * Some sync operations (dependency sync, sub-issue sync) need "stub" issues for referential
 * integrity — e.g., a blocking issue that exists in the org but hasn't been synced yet. Use
 * {@link #processStub} for these; stubs carry minimal data, don't trigger domain events, and
 * are hydrated later by the full issue sync or webhook.
 */
@Service
public class GitHubIssueProcessor extends BaseGitHubProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitHubIssueProcessor.class);

    private final IssueRepository issueRepository;
    private final OrganizationRepository organizationRepository;
    private final GitHubIssueTypeSyncService issueTypeSyncService;
    private final ApplicationEventPublisher eventPublisher;

    public GitHubIssueProcessor(
            IssueRepository issueRepository,
            LabelRepository labelRepository,
            MilestoneRepository milestoneRepository,
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            GitHubIssueTypeSyncService issueTypeSyncService,
            GitHubUserProcessor gitHubUserProcessor,
            ApplicationEventPublisher eventPublisher) {
        super(userRepository, labelRepository, milestoneRepository, gitHubUserProcessor);
        this.issueRepository = issueRepository;
        this.organizationRepository = organizationRepository;
        this.issueTypeSyncService = issueTypeSyncService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Process a GitHub issue DTO and persist it as an Issue entity.
     * Publishes appropriate domain events based on what changed.
     * <p>
     * Use this method for real issue data from webhooks or full sync operations.
     * For creating placeholder issues (e.g., for dependency relationships),
     * use {@link #processStub} instead.
     */
    @Transactional
    public @Nullable Issue process(GitHubIssueDTO dto, ProcessingContext context) {
        return processInternal(dto, context, true, true);
    }

    /**
     * Process a GitHub issue DTO as a "stub" entity for referential integrity. Does NOT publish
     * domain events. Used for placeholder issues needed for relationships (blocking issues,
     * parent issues) or when comment webhooks arrive before the issue/PR webhook; the stub is
     * later hydrated with full data by the real issue webhook or scheduled sync.
     *
     * @param dto     the issue DTO (may have incomplete data)
     * @param context the processing context
     * @return the created or updated Issue entity, or null if creation failed
     */
    @Transactional
    public @Nullable Issue processStub(GitHubIssueDTO dto, ProcessingContext context) {
        return processInternal(dto, context, false, false);
    }

    /**
     * Uses atomic upsert to prevent race conditions when concurrent threads (e.g., multiple NATS
     * consumers or webhook handlers) process the same issue.
     *
     * @param dto           the issue DTO
     * @param context       the processing context
     * @param publishEvents whether to publish domain events (false for stubs)
     * @param emitDerivedEvents whether to derive update and close events from the mirror; an explicit
     *                          close publishes its own lifecycle event
     * @return the created or updated Issue entity
     */
    private @Nullable Issue processInternal(
            GitHubIssueDTO dto, ProcessingContext context, boolean publishEvents, boolean emitDerivedEvents) {
        // Use getDatabaseId() which falls back to id for webhook payloads
        Long dbId = dto.getDatabaseId();
        if (dbId == null) {
            log.warn("Skipped issue processing: reason=missingDatabaseId, issueNumber={}", dto.number());
            return null;
        }

        Repository repository = Objects.requireNonNull(context.repository(), "Issue processing requires a repository");
        Long providerId = Objects.requireNonNull(context.providerId(), "Issue processing requires a provider");

        // Compare by natural key (repository_id, number), not dbId — the same issue can arrive
        // with different provider IDs from different sources.
        Optional<Issue> existingOpt = issueRepository.findByRepositoryIdAndNumber(repository.getId(), dto.number());
        boolean isNew = existingOpt.isEmpty();

        // Resolve related entities BEFORE the upsert
        User author = dto.author() != null ? findOrCreateUser(dto.author(), providerId) : null;
        Milestone milestone = dto.milestone() != null ? findOrCreateMilestone(dto.milestone(), repository) : null;
        IssueType issueType = null;
        if (dto.issueType() != null && repository.getOrganization() != null) {
            issueType = findOrCreateIssueType(
                    dto.issueType(), repository.getOrganization().getLogin());
        }

        // Atomic upsert prevents races between concurrent inserts; ON CONFLICT
        // (repository_id, issue_type, number) DO UPDATE.
        Instant now = Instant.now();
        issueRepository.upsertCore(
                dbId,
                providerId,
                dto.number(),
                Objects.requireNonNullElse(sanitize(dto.title()), ""),
                sanitize(dto.body()),
                Objects.requireNonNull(convertState(dto.state()), "Issue state is required")
                        .name(),
                stateReasonName(dto.stateReason()),
                dto.htmlUrl(),
                dto.locked(),
                dto.closedAt(),
                dto.commentsCount(), // int primitive, no null check needed
                now,
                dto.createdAt(),
                dto.updatedAt(),
                author != null ? author.getId() : null,
                repository.getId(),
                milestone != null ? milestone.getId() : null,
                issueType != null ? issueType.getId() : null,
                null, // parentIssueId - handled separately by dependency sync
                null, // subIssuesTotal - populated by sub-issue sync, not from DTO
                null, // subIssuesCompleted - populated by sub-issue sync, not from DTO
                null // subIssuesPercentCompleted - populated by sub-issue sync, not from DTO
                );

        // Re-fetch to get a JPA-managed entity for relationship handling below.
        Issue issue = issueRepository
                .findByRepositoryIdAndNumber(repository.getId(), dto.number())
                .orElseThrow(() -> new IllegalStateException("Issue not found after upsert: repositoryId="
                        + repository.getId() + ", number=" + dto.number()));

        // ManyToMany relationships (labels, assignees) can't be handled by the native upsert.
        boolean relationshipsChanged = updateRelationships(dto, issue, repository, providerId);

        if (relationshipsChanged) {
            issue = issueRepository.save(issue);
        }

        if (publishEvents) {
            if (isNew) {
                eventPublisher.publishEvent(new ScmDomainEvent.IssueCreated(
                        ScmEventPayload.IssueData.from(issue), EventContext.from(context)));
                log.debug("Created issue: issueId={}, issueNumber={}", dbId, dto.number());

                // Emit lifecycle events for issues that arrived already closed during sync.
                // Skipped when called from processClosed() which emits its own IssueClosed event.
                if (emitDerivedEvents && issue.getState() == Issue.State.CLOSED) {
                    String stateReason = dto.stateReason() != null ? dto.stateReason() : "completed";
                    eventPublisher.publishEvent(new ScmDomainEvent.IssueClosed(
                            ScmEventPayload.IssueData.from(issue), stateReason, EventContext.from(context)));
                    log.debug("Emitted IssueClosed for already-closed issue: issueId={}", dbId);
                }
            } else if (emitDerivedEvents) {
                Set<String> changedFields = computeChangedFields(existingOpt.get(), issue);
                if (!changedFields.isEmpty() || relationshipsChanged) {
                    if (relationshipsChanged) {
                        changedFields.add("relationships");
                    }
                    eventPublisher.publishEvent(new ScmDomainEvent.IssueUpdated(
                            ScmEventPayload.IssueData.from(issue), changedFields, EventContext.from(context)));
                    log.debug("Updated issue: issueId={}, changedFields={}", dbId, changedFields);
                }
            }
        } else if (isNew) {
            log.debug("Created stub issue (no event): issueId={}, issueNumber={}", dbId, dto.number());
        }

        return issue;
    }

    /**
     * Updates ManyToMany relationships (labels, assignees) that can't be handled by the atomic upsert.
     *
     * @return true if any relationships were changed
     */
    private boolean updateRelationships(GitHubIssueDTO dto, Issue issue, Repository repository, Long providerId) {
        boolean assigneesChanged = updateAssignees(
                Objects.requireNonNullElse(dto.assignees(), List.of()), issue.getAssignees(), providerId);
        boolean labelsChanged =
                updateLabels(Objects.requireNonNullElse(dto.labels(), List.of()), issue.getLabels(), repository);
        return assigneesChanged || labelsChanged;
    }

    /**
     * What this delivery moved, in the field vocabulary {@code ScmDomainEvent.IssueUpdated} carries.
     * Reports every field the mirror tracks rather than only the review-relevant ones: which of them are
     * worth a review is the review pipeline's decision, and which are worth a cache eviction is the
     * mentor's, so narrowing here would silently answer both questions at once.
     */
    private Set<String> computeChangedFields(Issue oldIssue, Issue newIssue) {
        Set<String> changedFields = new HashSet<>();

        if (!Objects.equals(oldIssue.getTitle(), newIssue.getTitle())) {
            changedFields.add("title");
        }
        if (!Objects.equals(oldIssue.getBody(), newIssue.getBody())) {
            changedFields.add("body");
        }
        if (oldIssue.getState() != newIssue.getState()) {
            changedFields.add("state");
        }
        if (!Objects.equals(oldIssue.getStateReason(), newIssue.getStateReason())) {
            changedFields.add("stateReason");
        }
        if (oldIssue.getCommentsCount() != newIssue.getCommentsCount()) {
            changedFields.add("commentsCount");
        }
        if (oldIssue.isLocked() != newIssue.isLocked()) {
            changedFields.add("locked");
        }
        if (!Objects.equals(oldIssue.getClosedAt(), newIssue.getClosedAt())) {
            changedFields.add("closedAt");
        }
        if (!Objects.equals(oldIssue.getMilestone(), newIssue.getMilestone())) {
            changedFields.add("milestone");
        }
        if (!Objects.equals(oldIssue.getIssueType(), newIssue.getIssueType())) {
            changedFields.add("issueType");
        }

        return changedFields;
    }

    @Transactional
    public @Nullable Issue processTyped(
            GitHubIssueDTO issueDto, GitHubIssueTypeDTO typeDto, @Nullable String orgLogin, ProcessingContext context) {
        Issue issue = processInternal(issueDto, context, true, true);
        if (issue == null) return null;

        if (typeDto != null && orgLogin != null) {
            IssueType issueType = findOrCreateIssueType(typeDto, orgLogin);
            if (issueType != null) {
                issue.setIssueType(issueType);
                issue = issueRepository.save(issue);
                eventPublisher.publishEvent(new ScmDomainEvent.IssueTyped(
                        ScmEventPayload.IssueData.from(issue),
                        ScmEventPayload.IssueTypeData.from(issueType),
                        EventContext.from(context)));
                log.debug("Updated issue type: issueId={}, issueTypeName={}", issue.getId(), issueType.getName());
            }
        }

        return issue;
    }

    @Transactional
    public @Nullable Issue processUntyped(GitHubIssueDTO issueDto, ProcessingContext context) {
        Issue issue = processInternal(issueDto, context, true, true);
        if (issue == null) return null;

        IssueType previousType = issue.getIssueType();
        if (previousType != null) {
            issue.setIssueType(null);
            issue = issueRepository.save(issue);
            eventPublisher.publishEvent(new ScmDomainEvent.IssueUntyped(
                    ScmEventPayload.IssueData.from(issue),
                    ScmEventPayload.IssueTypeData.from(previousType),
                    EventContext.from(context)));
            log.debug("Removed issue type: issueId={}, previousTypeName={}", issue.getId(), previousType.getName());
        }

        return issue;
    }

    @Transactional
    public @Nullable Issue processClosed(GitHubIssueDTO issueDto, ProcessingContext context) {
        Issue issue = processInternal(issueDto, context, true, false);
        if (issue == null) return null;
        String stateReason = issueDto.stateReason() != null ? issueDto.stateReason() : "completed";
        eventPublisher.publishEvent(new ScmDomainEvent.IssueClosed(
                ScmEventPayload.IssueData.from(issue), stateReason, EventContext.from(context)));
        log.debug("Closed issue: issueId={}, stateReason={}", issue.getId(), stateReason);
        return issue;
    }

    @Transactional
    public @Nullable Issue processReopened(GitHubIssueDTO issueDto, ProcessingContext context) {
        Issue issue = processInternal(issueDto, context, true, true);
        if (issue == null) return null;
        eventPublisher.publishEvent(
                new ScmDomainEvent.IssueReopened(ScmEventPayload.IssueData.from(issue), EventContext.from(context)));
        log.debug("Reopened issue: issueId={}", issue.getId());
        return issue;
    }

    @Transactional
    public @Nullable Issue processLabeled(GitHubIssueDTO issueDto, GitHubLabelDTO labelDto, ProcessingContext context) {
        Issue issue = processInternal(issueDto, context, true, true);
        if (issue == null) return null;
        Label label = findOrCreateLabel(
                labelDto, Objects.requireNonNull(context.repository(), "Issue event requires a repository"));
        if (label != null) {
            eventPublisher.publishEvent(new ScmDomainEvent.IssueLabeled(
                    ScmEventPayload.IssueData.from(issue),
                    ScmEventPayload.LabelData.from(label),
                    EventContext.from(context)));
            log.debug("Labeled issue: issueId={}, labelName={}", issue.getId(), label.getName());
        }
        return issue;
    }

    @Transactional
    public @Nullable Issue processUnlabeled(
            GitHubIssueDTO issueDto, GitHubLabelDTO labelDto, ProcessingContext context) {
        Issue issue = processInternal(issueDto, context, true, true);
        if (issue == null) return null;
        Label label = findOrCreateLabel(
                labelDto, Objects.requireNonNull(context.repository(), "Issue event requires a repository"));
        if (label != null) {
            eventPublisher.publishEvent(new ScmDomainEvent.IssueUnlabeled(
                    ScmEventPayload.IssueData.from(issue),
                    ScmEventPayload.LabelData.from(label),
                    EventContext.from(context)));
            log.debug("Unlabeled issue: issueId={}, labelName={}", issue.getId(), label.getName());
        }
        return issue;
    }

    /**
     * Process a {@code transferred} event: the issue has moved to another repository and no longer
     * exists in this one.
     *
     * <p>GitHub delivers {@code transferred} to the SOURCE repository, and the payload's issue is the
     * source-side issue. Routing it to {@link #process} would re-upsert the issue into the repository
     * it had just left, creating a permanent phantom instead of removing one. A transfer is not a
     * delete event, but from this repository's perspective the outcome is identical: the issue is gone.
     *
     * <p>Tombstoned rather than deleted, matching the {@code RECONCILIATION} sweep: the issue's
     * feedback/observation rows reference it by bare id with no FK, so removing the row would orphan
     * them. The sweep would eventually catch this anyway (a transferred issue vanishes from the
     * source repository's upstream listing); handling the event makes it immediate.
     */
    @Transactional
    public void processTransferred(GitHubIssueDTO issueDto, ProcessingContext context) {
        issueRepository
                .findByRepositoryIdAndNumber(
                        Objects.requireNonNull(context.repository(), "Issue event requires a repository")
                                .getId(),
                        issueDto.number())
                .ifPresent(issue -> {
                    if (issue.getDeletedAt() != null) {
                        return;
                    }
                    issue.setDeletedAt(Instant.now());
                    issueRepository.save(issue);
                    log.info(
                            "Tombstoned transferred issue: issueId={}, number={}, repoId={}",
                            issue.getId(),
                            issueDto.number(),
                            Objects.requireNonNull(context.repository(), "Issue event requires a repository")
                                    .getId());
                });
    }

    @Transactional
    public void processDeleted(GitHubIssueDTO issueDto, ProcessingContext context) {
        // With synthetic PKs, we cannot use deleteById(nativeId) because the PK is
        // auto-generated and differs from the native provider ID. Look up by natural key instead.
        issueRepository
                .findByRepositoryIdAndNumber(
                        Objects.requireNonNull(context.repository(), "Issue event requires a repository")
                                .getId(),
                        issueDto.number())
                .ifPresent(issue -> {
                    Long syntheticId = issue.getId();
                    issueRepository.delete(issue);
                    eventPublisher.publishEvent(
                            new ScmDomainEvent.IssueDeleted(syntheticId, EventContext.from(context)));
                    log.info("Deleted issue: issueId={}, number={}", syntheticId, issueDto.number());
                });
    }

    /**
     * Issues cannot be MERGED - only PRs can. This only handles OPEN/CLOSED.
     */
    private Issue.@Nullable State convertState(@Nullable String state) {
        if (state == null) {
            return Issue.State.OPEN;
        }
        return switch (state.toUpperCase(Locale.ROOT)) {
            case "CLOSED" -> Issue.State.CLOSED;
            default -> Issue.State.OPEN;
        };
    }

    /**
     * Returns UNKNOWN for unrecognized values, rather than null or throwing, to preserve data
     * integrity.
     */
    private static Issue.@Nullable StateReason convertStateReason(@Nullable String stateReason) {
        if (stateReason == null) {
            return null;
        }
        return switch (stateReason.toUpperCase(Locale.ROOT)) {
            case "COMPLETED" -> Issue.StateReason.COMPLETED;
            case "DUPLICATE" -> Issue.StateReason.DUPLICATE;
            case "REOPENED" -> Issue.StateReason.REOPENED;
            case "NOT_PLANNED" -> Issue.StateReason.NOT_PLANNED;
            default -> Issue.StateReason.UNKNOWN;
        };
    }

    @Nullable
    private IssueType findOrCreateIssueType(GitHubIssueTypeDTO dto, String orgLogin) {
        if (dto == null || dto.nodeId() == null) {
            return null;
        }

        return issueTypeSyncService.findByNodeId(dto.nodeId()).orElseGet(() -> {
            var orgOpt =
                    organizationRepository.findByLoginIgnoreCaseAndProvider_Type(orgLogin, IdentityProviderType.GITHUB);
            if (orgOpt.isEmpty()) {
                log.warn(
                        "Skipped issue type creation: reason=orgNotFound, issueTypeName={}, orgLogin={}",
                        dto.name(),
                        orgLogin);
                return null;
            }
            return issueTypeSyncService.findOrCreateFromWebhook(
                    Objects.requireNonNull(dto.nodeId()),
                    Objects.requireNonNull(dto.name()),
                    dto.description(),
                    dto.color(),
                    dto.isEnabled() != null ? dto.isEnabled() : true,
                    orgOpt.get());
        });
    }

    private static @Nullable String stateReasonName(@Nullable String stateReason) {
        Issue.StateReason converted = convertStateReason(stateReason);
        return converted == null ? null : converted.name();
    }
}
