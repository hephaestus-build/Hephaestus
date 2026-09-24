package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueTypeRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.BaseGitLabProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookLabel;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.dto.GitLabIssueEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processor for GitLab issues.
 * <p>
 * Handles conversion of GitLab issue data (from webhooks and GraphQL sync) to Issue entities.
 * Follows the same patterns as {@link de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueProcessor}.
 * <p>
 * Confidential issues are skipped entirely (not stored in the database).
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabIssueProcessor extends BaseGitLabProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitLabIssueProcessor.class);

    private final IssueRepository issueRepository;
    private final MilestoneRepository milestoneRepository;
    private final IssueTypeRepository issueTypeRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GitLabIssueProcessor(
            GitLabUserService gitLabUserService,
            IssueRepository issueRepository,
            MilestoneRepository milestoneRepository,
            IssueTypeRepository issueTypeRepository,
            UserRepository userRepository,
            LabelRepository labelRepository,
            RepositoryRepository repositoryRepository,
            ScopeIdResolver scopeIdResolver,
            RepositoryScopeFilter repositoryScopeFilter,
            GitLabProperties gitLabProperties,
            ApplicationEventPublisher eventPublisher) {
        super(
                gitLabUserService,
                userRepository,
                labelRepository,
                repositoryRepository,
                scopeIdResolver,
                repositoryScopeFilter,
                gitLabProperties);
        this.issueRepository = issueRepository;
        this.milestoneRepository = milestoneRepository;
        this.issueTypeRepository = issueTypeRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Process a GitLab issue webhook event.
     */
    @Transactional
    @Nullable
    public Issue process(GitLabIssueEventDTO event, ProcessingContext context) {
        return processInternal(event, context);
    }

    @Nullable
    private Issue processInternal(GitLabIssueEventDTO event, ProcessingContext context) {
        var attrs = event.objectAttributes();
        if (attrs == null || attrs.id() == null || attrs.iid() == null) {
            log.warn("Skipped issue event with missing object attributes or identifiers");
            return null;
        }
        if (event.isConfidential()) {
            log.debug("Skipped confidential issue: iid={}", attrs.iid());
            return null;
        }

        User author = resolveWebhookAuthor(event, Objects.requireNonNull(context.providerId()));
        Long providerId = Objects.requireNonNull(
                Objects.requireNonNull(context.repository()).getProvider().getId());
        Long milestoneId = resolveWebhookMilestoneId(attrs.milestoneId(), providerId);
        Issue issue = upsertIssue(
                attrs.id(),
                attrs.iid(),
                attrs.title(),
                attrs.description(),
                attrs.state(),
                attrs.url(),
                attrs.createdAt(),
                attrs.updatedAt(),
                attrs.closedAt(),
                author,
                milestoneId,
                Objects.requireNonNull(context.repository()),
                context);

        if (issue == null) return null;

        // Update relationships
        boolean changed = updateLabels(event.labels(), issue.getLabels(), Objects.requireNonNull(context.repository()));
        changed |=
                updateAssignees(event.assignees(), issue.getAssignees(), Objects.requireNonNull(context.providerId()));
        if (changed) {
            issue = issueRepository.save(issue);
        }

        return issue;
    }

    /**
     * Tombstones an already-mirrored <em>public</em> snapshot of an issue that has flipped to
     * confidential. GitLab keeps confidential issues server-side and its webhooks stop carrying their
     * content, but a copy we ingested while the issue was still public would otherwise linger — a stale,
     * now-restricted public copy of the data. {@link #process} correctly refuses to (re)store a
     * confidential issue; this closes the complementary gap by removing any row stored before the flip.
     *
     * <p>Uses the same soft-delete tombstone the upstream-deletion sweep uses (sets {@code deletedAt}, so
     * the row drops out of every {@code deletedAt IS NULL} live query). Idempotent: a no-op when nothing
     * is stored or the row is already tombstoned.
     *
     * @return {@code true} if a live row was tombstoned by this call
     */
    @Transactional
    public boolean purgeConfidential(GitLabIssueEventDTO event, ProcessingContext context) {
        var attrs = event.objectAttributes();
        if (attrs == null || attrs.iid() == null) {
            return false;
        }
        int number = attrs.iid();
        long repositoryId = Objects.requireNonNull(context.repository()).getId();
        // Issue-typed tombstone: a confidential-issue IID must never touch a live merge request that
        // happens to share the IID — GitLab issues and MRs are separate per-project namespaces.
        int tombstoned =
                issueRepository.tombstoneIssuesByRepositoryIdAndNumbers(repositoryId, List.of(number), Instant.now());
        if (tombstoned > 0) {
            log.info(
                    "Tombstoned public snapshot of now-confidential issue: repositoryId={}, iid={}",
                    repositoryId,
                    number);
        }
        return tombstoned > 0;
    }

    /**
     * Handles an {@code action=update} issue event. Persists the issue, publishes one
     * {@link ScmDomainEvent.IssueUpdated} carrying what the webhook's {@code changes} diff says moved,
     * then one {@link ScmDomainEvent.IssueLabeled} per newly-added label — GitLab has no native
     * "labeled" action, so this is how the activity feed reaches parity with GitHub. An update that
     * moved only attributes the shared domain has no field name for (due date, weight, time tracking)
     * publishes nothing: the mirror is refreshed either way, and an event with an empty diff would tell
     * every consumer that something changed while naming nothing.
     */
    @Transactional
    @Nullable
    public Issue processUpdated(GitLabIssueEventDTO event, ProcessingContext context) {
        List<GitLabWebhookLabel> addedLabels = event.addedLabels();
        Set<String> changedFields = event.changedFields();
        Issue issue = processInternal(event, context);
        if (issue == null) {
            return issue;
        }
        if (!changedFields.isEmpty()) {
            eventPublisher.publishEvent(new ScmDomainEvent.IssueUpdated(
                    ScmEventPayload.IssueData.from(issue), changedFields, actorContext(event, context)));
        }
        for (GitLabWebhookLabel labelDto : addedLabels) {
            Label label = findOrCreateLabel(labelDto, Objects.requireNonNull(context.repository()));
            if (label != null) {
                eventPublisher.publishEvent(new ScmDomainEvent.IssueLabeled(
                        ScmEventPayload.IssueData.from(issue),
                        ScmEventPayload.LabelData.from(label),
                        EventContext.from(context)));
            }
        }
        return issue;
    }

    /**
     * Label data extracted from GraphQL response for sync processing.
     */
    public record SyncLabelData(
            @Nullable String globalId,
            @Nullable String title,
            @Nullable String color) {}

    /**
     * Assignee data extracted from GraphQL response for sync processing.
     */
    public record SyncAssigneeData(
            @Nullable String globalId,
            @Nullable String username,
            @Nullable String name,
            @Nullable String avatarUrl,
            @Nullable String webUrl) {}

    /**
     * All data needed to sync a single GitLab issue from GraphQL.
     */
    public record SyncIssueData(
            @Nullable String globalId,
            @Nullable String iid,
            @Nullable String title,
            @Nullable String description,
            @Nullable String state,
            boolean confidential,
            @Nullable String webUrl,
            @Nullable String createdAt,
            @Nullable String updatedAt,
            @Nullable String closedAt,
            @Nullable String authorGlobalId,
            @Nullable String authorUsername,
            @Nullable String authorName,
            @Nullable String authorAvatarUrl,
            @Nullable String authorWebUrl,
            int commentsCount,
            @Nullable List<SyncLabelData> syncLabels,
            @Nullable List<SyncAssigneeData> syncAssignees,
            @Nullable Integer milestoneIid,
            @Nullable String typeName,
            @Nullable String closedAsDuplicateOfGid) {}

    /**
     * Process a GitLab issue from GraphQL sync.
     * <p>
     * Labels and assignees are resolved and persisted within this method's
     * transaction boundary to avoid detached-entity issues.
     */
    @Transactional
    @Nullable
    public Issue processFromSync(SyncIssueData data, Repository repository, @Nullable Long scopeId) {
        if (data.globalId() == null || data.iid() == null || data.title() == null || data.state() == null) {
            log.warn("Skipped issue processing: reason=missingRequiredData");
            return null;
        }
        if (data.confidential()) {
            log.debug("Skipped confidential issue from sync: iid={}", data.iid());
            return null;
        }

        long nativeId;
        try {
            nativeId = GitLabSyncConstants.extractNumericId(data.globalId());
        } catch (IllegalArgumentException e) {
            log.warn("Skipped issue processing: reason=invalidGlobalId, gid={}", data.globalId());
            return null;
        }

        int issueNumber;
        try {
            issueNumber = Integer.parseInt(data.iid());
        } catch (NumberFormatException e) {
            log.warn("Skipped issue processing: reason=invalidIid, iid={}", data.iid());
            return null;
        }

        Long providerId = Objects.requireNonNull(repository.getProvider().getId());

        // Check if existing
        Optional<Issue> existingOpt = issueRepository.findByRepositoryIdAndNumber(repository.getId(), issueNumber);
        boolean isNew = existingOpt.isEmpty();
        ScmEventPayload.@Nullable IssueData previous =
                existingOpt.map(ScmEventPayload.IssueData::from).orElse(null);

        // Resolve author
        User author = findOrCreateUser(
                GitLabUserLookup.of(
                        data.authorGlobalId(),
                        data.authorUsername(),
                        data.authorName(),
                        data.authorAvatarUrl(),
                        data.authorWebUrl()),
                providerId);

        // State mapping
        Issue.State issueState = convertState(data.state());

        // Resolve milestone by iid + repository (milestones are synced before issues)
        Long milestoneId = null;
        if (data.milestoneIid() != null) {
            milestoneId = milestoneRepository
                    .findByNumberAndRepositoryId(data.milestoneIid(), repository.getId())
                    .map(Milestone::getId)
                    .orElse(null);
        }

        String stateReason = resolveStateReason(issueState, data.closedAsDuplicateOfGid());
        String issueTypeId = resolveIssueTypeId(data.typeName(), repository);

        Instant now = Instant.now();
        issueRepository.upsertCore(
                nativeId,
                providerId,
                issueNumber,
                Objects.requireNonNullElse(sanitize(data.title()), ""),
                sanitize(data.description()),
                issueState.name(),
                stateReason,
                data.webUrl(),
                null, // locked — not available in GitLab API, null lets COALESCE preserve existing or default
                parseGitLabTimestamp(data.closedAt()),
                data.commentsCount(),
                now,
                parseGitLabTimestamp(data.createdAt()),
                parseGitLabTimestamp(data.updatedAt()),
                author != null ? author.getId() : null,
                repository.getId(),
                milestoneId,
                issueTypeId,
                null, // parentIssueId
                null, // subIssuesTotal
                null, // subIssuesCompleted
                null // subIssuesPercentCompleted
                );

        Issue issue = issueRepository
                .findByRepositoryIdAndNumber(repository.getId(), issueNumber)
                .orElse(null);

        if (issue == null) {
            return null;
        }

        issue.setProvider(repository.getProvider());

        // Resolve and persist labels/assignees within this transaction
        boolean changed = updateSyncLabels(data.syncLabels(), issue.getLabels(), repository);
        changed |= updateSyncAssignees(data.syncAssignees(), issue.getAssignees(), providerId);
        if (changed) {
            issue = issueRepository.save(issue);
        }

        ProcessingContext ctx = ProcessingContext.forSync(scopeId, repository);
        if (isNew) {
            eventPublisher.publishEvent(
                    new ScmDomainEvent.IssueCreated(ScmEventPayload.IssueData.from(issue), EventContext.from(ctx)));
            log.debug("Created issue from sync: issueId={}, iid={}", nativeId, data.iid());
        } else if (previous != null && issueState == Issue.State.OPEN) {
            ScmEventPayload.IssueData current = ScmEventPayload.IssueData.from(issue);
            Set<String> changedFields = changedReviewFields(previous, current);
            if (!changedFields.isEmpty()) {
                eventPublisher.publishEvent(
                        new ScmDomainEvent.IssueUpdated(current, changedFields, EventContext.from(ctx)));
            }
        }

        // Emit the close event for CLOSED issues on every sync, not just on create, so a sync backfills
        // ISSUE_CLOSED activity for issues that were already closed when first ingested. The activity_event
        // (workspace_id, event_key) unique constraint dedupes, so replaying an existing close is a safe no-op.
        if (issueState == Issue.State.CLOSED) {
            eventPublisher.publishEvent(new ScmDomainEvent.IssueClosed(
                    ScmEventPayload.IssueData.from(issue),
                    stateReason != null ? stateReason : "completed",
                    EventContext.from(ctx)));
        }

        return issue;
    }

    private static Set<String> changedReviewFields(
            ScmEventPayload.IssueData previous, ScmEventPayload.IssueData current) {
        Set<String> changed = new HashSet<>();
        if (!previous.title().equals(current.title())) changed.add("title");
        if (!Objects.equals(previous.body(), current.body())) changed.add("body");
        if (previous.state() != current.state()) changed.add("state");
        if (!Objects.equals(previous.stateReason(), current.stateReason())) changed.add("stateReason");
        if (!Objects.equals(previous.issueType(), current.issueType())) changed.add("issueType");
        if (!Objects.equals(previous.milestone(), current.milestone())) changed.add("milestone");
        if (!new HashSet<>(previous.labels()).equals(new HashSet<>(current.labels()))
                || !new HashSet<>(previous.assignees()).equals(new HashSet<>(current.assignees()))) {
            changed.add("relationships");
        }
        return changed;
    }

    @Transactional
    @Nullable
    public Issue processClosed(GitLabIssueEventDTO event, ProcessingContext context) {
        Issue issue = processInternal(event, context);
        if (issue != null) {
            eventPublisher.publishEvent(new ScmDomainEvent.IssueClosed(
                    ScmEventPayload.IssueData.from(issue), "completed", EventContext.from(context)));
            log.debug("Closed issue: issueId={}", issue.getId());
        }
        return issue;
    }

    @Transactional
    @Nullable
    public Issue processReopened(GitLabIssueEventDTO event, ProcessingContext context) {
        Issue issue = processInternal(event, context);
        if (issue != null) {
            ScmEventPayload.IssueData issueData = ScmEventPayload.IssueData.from(issue);
            eventPublisher.publishEvent(
                    new ScmDomainEvent.IssueUpdated(issueData, Set.of("state"), actorContext(event, context)));
            eventPublisher.publishEvent(new ScmDomainEvent.IssueReopened(issueData, EventContext.from(context)));
            log.debug("Reopened issue: issueId={}", issue.getId());
        }
        return issue;
    }

    // Private helpers

    private EventContext actorContext(GitLabIssueEventDTO event, ProcessingContext context) {
        User actor = event.user() != null && context.providerId() != null
                ? findOrCreateUser(event.user(), context.providerId())
                : null;
        return EventContext.from(context.withActorUserId(actor != null ? actor.getId() : null));
    }

    /**
     * Resolves the issue author from a webhook event.
     * <p>
     * GitLab webhook {@code user} is the <em>actor</em> (who triggered the event),
     * not necessarily the issue author. The real author ID is in
     * {@code object_attributes.author_id}.
     * <p>
     * Strategy:
     * <ol>
     *   <li>If the actor's ID matches {@code author_id}, use the actor DTO
     *       (has full profile data) to upsert the user.</li>
     *   <li>Otherwise look up the author by native ID from the database
     *       (works when the author was previously synced).</li>
     *   <li>If not found, return {@code null} — the {@code COALESCE} in the
     *       upsert SQL will preserve any existing author.</li>
     * </ol>
     */
    @Nullable
    private User resolveWebhookAuthor(GitLabIssueEventDTO event, Long providerId) {
        var attrs = event.objectAttributes();
        if (attrs == null) {
            return null;
        }
        Long authorId = attrs.authorId();
        if (authorId == null) {
            return null;
        }

        // If the actor IS the author, we have full profile data — upsert them
        if (event.user() != null && authorId.equals(event.user().id())) {
            return findOrCreateUser(event.user(), providerId);
        }

        // Otherwise, try to find the author by native ID (previously synced)
        return userRepository.findByNativeIdAndProviderId(authorId, providerId).orElse(null);
    }

    @Nullable
    private Long resolveWebhookMilestoneId(@Nullable Long gitlabMilestoneId, Long providerId) {
        if (gitlabMilestoneId == null) {
            return null;
        }
        return milestoneRepository
                .findByNativeIdAndProviderId(gitlabMilestoneId, providerId)
                .map(Milestone::getId)
                .orElse(null);
    }

    @Nullable
    private Issue upsertIssue(
            Long rawId,
            Integer iid,
            @Nullable String title,
            @Nullable String description,
            @Nullable String state,
            @Nullable String htmlUrl,
            @Nullable String createdAt,
            @Nullable String updatedAt,
            @Nullable String closedAt,
            @Nullable User author,
            @Nullable Long milestoneId,
            Repository repository,
            ProcessingContext context) {
        if (rawId == null || iid == null) {
            log.warn("Skipped issue processing: reason=missingIdOrIid");
            return null;
        }

        long nativeId = rawId;
        int issueNumber = iid;
        Long providerId = Objects.requireNonNull(repository.getProvider().getId());

        Optional<Issue> existingOpt = issueRepository.findByRepositoryIdAndNumber(repository.getId(), issueNumber);
        boolean isNew = existingOpt.isEmpty();

        Issue.State issueState = convertState(state);

        Instant now = Instant.now();
        issueRepository.upsertCore(
                nativeId,
                providerId,
                issueNumber,
                Objects.requireNonNullElse(sanitize(title), ""),
                sanitize(description),
                issueState.name(),
                null, // stateReason
                htmlUrl,
                null, // locked — not in webhook, null lets COALESCE preserve existing or default
                parseGitLabTimestamp(closedAt),
                null, // commentsCount — not in webhook, null lets COALESCE preserve existing or default
                now,
                parseGitLabTimestamp(createdAt),
                parseGitLabTimestamp(updatedAt),
                author != null ? author.getId() : null,
                repository.getId(),
                milestoneId,
                null,
                null,
                null,
                null,
                null);

        Issue issue = issueRepository
                .findByRepositoryIdAndNumber(repository.getId(), issueNumber)
                .orElse(null);

        if (issue != null) {
            issue.setProvider(repository.getProvider());

            if (isNew) {
                eventPublisher.publishEvent(new ScmDomainEvent.IssueCreated(
                        ScmEventPayload.IssueData.from(issue), EventContext.from(context)));
                log.debug("Created issue: nativeId={}, iid={}", nativeId, issueNumber);
            }
        }

        return issue;
    }

    /**
     * Maps GitLab issue state string to Issue.State enum.
     */
    private static Issue.State convertState(@Nullable String state) {
        if (state == null) {
            return Issue.State.OPEN;
        }
        return switch (state.toLowerCase()) {
            case "opened" -> Issue.State.OPEN;
            case "closed" -> Issue.State.CLOSED;
            default -> {
                log.warn("Unknown GitLab issue state '{}', defaulting to OPEN", state);
                yield Issue.State.OPEN;
            }
        };
    }

    /**
     * Updates labels from GraphQL sync data within the current transaction.
     */
    private boolean updateSyncLabels(
            @Nullable List<SyncLabelData> syncLabels, Collection<Label> currentLabels, Repository repository) {
        if (syncLabels == null) {
            return false;
        }

        Set<Label> newLabels = new HashSet<>();
        for (SyncLabelData data : syncLabels) {
            Label label = findOrCreateLabel(data.title(), data.color(), repository);
            if (label != null) {
                newLabels.add(label);
            }
        }

        if (!new HashSet<>(currentLabels).equals(newLabels)) {
            currentLabels.clear();
            currentLabels.addAll(newLabels);
            return true;
        }
        return false;
    }

    /**
     * Maps a GitLab issue state + {@code closedAsDuplicateOf} marker to the
     * denormalized {@code issue.state_reason} column.
     * <p>
     * Returns {@code null} for open issues and for closed issues that cannot be
     * classified. The upsert COALESCE preserves any previously set reason.
     */
    @Nullable
    private static String resolveStateReason(Issue.State state, @Nullable String closedAsDuplicateOfGid) {
        if (state != Issue.State.CLOSED) {
            return null;
        }
        if (closedAsDuplicateOfGid != null && !closedAsDuplicateOfGid.isBlank()) {
            return "DUPLICATE";
        }
        return "COMPLETED";
    }

    /**
     * Resolves the GitLab {@code Issue.type} enum ({@code ISSUE}, {@code TASK}, …)
     * to an {@code issue_type_id} FK for the repository's owning organization.
     * <p>
     * GitLab's GraphQL enum uses {@code SCREAMING_SNAKE_CASE}; the {@code IssueType}
     * name column stores the human-readable form ({@code "Test Case"}). This method
     * humanises the enum ({@code TEST_CASE} → {@code "Test Case"}) before a
     * case-insensitive lookup. Returns {@code null} when the repository has no
     * organization or when no matching issue type exists — the upsert COALESCE
     * preserves any previously set FK.
     */
    @Nullable
    private String resolveIssueTypeId(@Nullable String typeName, Repository repository) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        String humanised = humaniseTypeName(typeName);
        Organization organization = repository.getOrganization();
        if (organization == null || organization.getId() == null) {
            return null;
        }
        Optional<IssueType> orgScoped =
                issueTypeRepository.findByOrganizationIdAndNameIgnoreCase(organization.getId(), humanised);
        if (orgScoped.isPresent()) {
            return orgScoped.get().getId();
        }
        // Subgroups become their own Organization rows but share provider-global
        // issue_type primary keys (GitLab GraphQL global IDs), so a provider-scoped
        // name lookup yields the exact same row. Without this fallback, issues
        // synced from subgroups would resolve to null because the subgroup org
        // never had its own issue_type seed rows materialised. Resolve the provider
        // id via a JPQL subquery on organizationId — touching the lazy Organization
        // proxy here raised LazyInitializationException when the Repository outlived
        // its original Hibernate session.
        return issueTypeRepository
                .findFirstByOrganizationProviderAndNameIgnoreCase(organization.getId(), humanised)
                .map(IssueType::getId)
                .orElse(null);
    }

    /**
     * Converts a GitLab GraphQL {@code IssueType} enum value ({@code TEST_CASE})
     * to the human-readable form stored in {@code issue_type.name} ({@code "Test Case"}).
     */
    private static String humaniseTypeName(String enumValue) {
        String[] parts = enumValue.split("_");
        StringBuilder sb = new StringBuilder(enumValue.length());
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (i > 0) sb.append(' ');
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            if (parts[i].length() > 1) {
                sb.append(parts[i].substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    /**
     * Updates assignees from GraphQL sync data within the current transaction.
     */
    private boolean updateSyncAssignees(
            @Nullable List<SyncAssigneeData> syncAssignees, Set<User> currentAssignees, Long providerId) {
        if (syncAssignees == null) {
            return false;
        }

        Set<User> newAssignees = new HashSet<>();
        for (SyncAssigneeData data : syncAssignees) {
            User user = findOrCreateUser(
                    GitLabUserLookup.of(data.globalId(), data.username(), data.name(), data.avatarUrl(), data.webUrl()),
                    providerId);
            if (user != null) {
                newAssignees.add(user);
            }
        }

        if (!currentAssignees.equals(newAssignees)) {
            currentAssignees.clear();
            currentAssignees.addAll(newAssignees);
            return true;
        }
        return false;
    }
}
