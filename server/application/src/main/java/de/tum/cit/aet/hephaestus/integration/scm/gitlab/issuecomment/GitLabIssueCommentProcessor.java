package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.BaseGitLabProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.dto.GitLabNoteEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.dto.GitLabNoteEventDTO.EmbeddedIssue;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.dto.GitLabNoteEventDTO.EmbeddedMergeRequest;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.dto.GitLabNoteEventDTO.NoteAttributes;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Processes GitLab note webhooks and GraphQL sync data into {@link IssueComment} entities. */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabIssueCommentProcessor extends BaseGitLabProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitLabIssueCommentProcessor.class);

    private final IssueCommentRepository commentRepository;
    private final IssueRepository issueRepository;
    private final PullRequestRepository pullRequestRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GitLabIssueCommentProcessor(
            GitLabUserService gitLabUserService,
            IssueCommentRepository commentRepository,
            IssueRepository issueRepository,
            PullRequestRepository pullRequestRepository,
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
        this.commentRepository = commentRepository;
        this.issueRepository = issueRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    @Nullable
    public IssueComment processIssueNote(GitLabNoteEventDTO event, ProcessingContext context) {
        NoteAttributes attrs = event.objectAttributes();
        EmbeddedIssue embeddedIssue = event.issue();
        if (attrs == null || attrs.id() == null || embeddedIssue == null || embeddedIssue.iid() == null) {
            log.warn("Skipped issue note: reason=missingData");
            return null;
        }

        Issue parent = issueRepository
                .findByRepositoryIdAndNumber(
                        Objects.requireNonNull(context.repository()).getId(), embeddedIssue.iid())
                .orElse(null);

        if (parent == null) {
            parent = createMinimalIssueWithRetry(embeddedIssue, context);
            if (parent == null) {
                log.warn(
                        "Skipped issue note: reason=failedToCreateParent, issueIid={}, noteId={}",
                        embeddedIssue.iid(),
                        attrs.id());
                return null;
            }
        }

        User author = findOrCreateUser(event.user(), Objects.requireNonNull(context.providerId()));
        return processCommentInternal(attrs, parent, author, context);
    }

    @Transactional
    @Nullable
    public IssueComment processMergeRequestNote(GitLabNoteEventDTO event, ProcessingContext context) {
        NoteAttributes attrs = event.objectAttributes();
        EmbeddedMergeRequest embeddedMr = event.mergeRequest();
        if (attrs == null || attrs.id() == null || embeddedMr == null || embeddedMr.iid() == null) {
            log.warn("Skipped MR note: reason=missingData");
            return null;
        }

        // PullRequest extends Issue, so findByRepositoryIdAndNumber works
        PullRequest parent = pullRequestRepository
                .findByRepositoryIdAndNumber(
                        Objects.requireNonNull(context.repository()).getId(), embeddedMr.iid())
                .orElse(null);

        if (parent == null) {
            parent = createMinimalPullRequestWithRetry(embeddedMr, context);
            if (parent == null) {
                log.warn(
                        "Skipped MR note: reason=failedToCreateParent, mrIid={}, noteId={}",
                        embeddedMr.iid(),
                        attrs.id());
                return null;
            }
        }

        User author = findOrCreateUser(event.user(), Objects.requireNonNull(context.providerId()));
        return processCommentInternal(attrs, parent, author, context);
    }

    public record SyncNoteData(
            long id,
            @Nullable String body,
            @Nullable String url,
            @Nullable String authorGlobalId,
            @Nullable String authorUsername,
            @Nullable String authorName,
            @Nullable String authorAvatarUrl,
            @Nullable String authorWebUrl,
            @Nullable String createdAt,
            @Nullable String updatedAt) {}

    @Transactional
    @Nullable
    public IssueComment processFromSync(SyncNoteData data, Issue parent, Long providerId, Long scopeId) {
        if (parent == null) {
            log.warn("Skipped sync note: reason=nullParent, noteId={}", data.id());
            return null;
        }

        User author = findOrCreateUser(
                GitLabUserLookup.of(
                        data.authorGlobalId(),
                        data.authorUsername(),
                        data.authorName(),
                        data.authorAvatarUrl(),
                        data.authorWebUrl()),
                providerId);

        Optional<IssueComment> existingOpt = commentRepository.findByNativeIdAndProviderId(data.id(), providerId);
        boolean isNew = existingOpt.isEmpty();
        IssueComment comment = existingOpt.orElseGet(IssueComment::new);
        Set<String> changedFields = new HashSet<>();

        if (isNew) {
            comment.setNativeId(data.id());
            comment.setProvider(parent.getProvider());
        }

        String sanitizedBody = sanitize(data.body());
        if (sanitizedBody != null && !sanitizedBody.equals(comment.getBody())) {
            changedFields.add("body");
            comment.setBody(sanitizedBody);
        }

        if (data.url() != null && !data.url().equals(comment.getHtmlUrl())) {
            changedFields.add("htmlUrl");
            comment.setHtmlUrl(data.url());
        }

        if (isNew) {
            comment.setAuthorAssociation(AuthorAssociation.NONE);
        }
        comment.setIssue(parent);

        if (data.createdAt() != null) {
            comment.setCreatedAt(parseGitLabTimestamp(data.createdAt()));
        }
        // GitLab's Note.updated_at must be persisted VERBATIM. Never fall back to
        // createdAt when updatedAt is absent — that erases the edit history. If GitLab
        // returns a distinct value from what we stored previously, track the field so a
        // CommentUpdated event fires downstream (inline-feedback / profile views rely on
        // updatedAt divergence to detect edits).
        if (data.updatedAt() != null) {
            Instant newUpdatedAt = parseGitLabTimestamp(data.updatedAt());
            if (newUpdatedAt != null && !newUpdatedAt.equals(comment.getUpdatedAt())) {
                comment.setUpdatedAt(newUpdatedAt);
                if (!isNew) {
                    changedFields.add("updatedAt");
                }
            }
        }

        if (author != null && comment.getAuthor() == null) {
            comment.setAuthor(author);
            changedFields.add("author");
        }

        IssueComment saved = commentRepository.save(comment);
        Long issueId = parent.getId();

        if (isNew) {
            EventContext eventCtx = EventContext.forSync(
                    scopeId,
                    Objects.requireNonNull(RepositoryRef.from(parent.getRepository())),
                    IdentityProviderType.GITLAB);
            eventPublisher.publishEvent(
                    new ScmDomainEvent.CommentCreated(ScmEventPayload.CommentData.from(saved), issueId, eventCtx));
            log.debug("Created comment from sync: commentId={}, parentId={}", saved.getId(), issueId);
        } else if (!changedFields.isEmpty()) {
            EventContext eventCtx = EventContext.forSync(
                    scopeId,
                    Objects.requireNonNull(RepositoryRef.from(parent.getRepository())),
                    IdentityProviderType.GITLAB);
            eventPublisher.publishEvent(new ScmDomainEvent.CommentUpdated(
                    ScmEventPayload.CommentData.from(saved), issueId, changedFields, eventCtx));
            log.debug("Updated comment from sync: commentId={}, changed={}", saved.getId(), changedFields);
        }

        return saved;
    }

    private @Nullable IssueComment processCommentInternal(
            NoteAttributes attrs, Issue parent, @Nullable User author, ProcessingContext context) {
        Long issueId = parent.getId();
        Optional<IssueComment> existingOpt =
                commentRepository.findByNativeIdAndProviderId(attrs.id(), Objects.requireNonNull(context.providerId()));
        // A note id is unique on the instance, so a note already stored under another issue or merge request is not
        // this one's, whatever the payload says: it is never moved.
        if (existingOpt
                .map(IssueComment::getIssue)
                .filter(stored -> !stored.getId().equals(issueId))
                .isPresent()) {
            log.warn("Skipped note: reason=belongsToAnotherParent, noteId={}", attrs.id());
            return null;
        }
        boolean isNew = existingOpt.isEmpty();
        IssueComment comment = existingOpt.orElseGet(IssueComment::new);
        Set<String> changedFields = new HashSet<>();

        if (isNew) {
            comment.setNativeId(attrs.id());
            comment.setProvider(Objects.requireNonNull(context.provider()));
        }

        String sanitizedBody = sanitize(attrs.note());
        if (sanitizedBody != null && !sanitizedBody.equals(comment.getBody())) {
            changedFields.add("body");
            comment.setBody(sanitizedBody);
        }

        if (attrs.url() != null && !attrs.url().equals(comment.getHtmlUrl())) {
            changedFields.add("htmlUrl");
            comment.setHtmlUrl(attrs.url());
        }

        if (isNew) {
            comment.setAuthorAssociation(AuthorAssociation.NONE);
        }
        comment.setIssue(parent);

        if (attrs.createdAt() != null) {
            comment.setCreatedAt(parseGitLabTimestamp(attrs.createdAt()));
        }
        if (attrs.updatedAt() != null) {
            comment.setUpdatedAt(parseGitLabTimestamp(attrs.updatedAt()));
        }

        if (author != null && comment.getAuthor() == null) {
            comment.setAuthor(author);
            changedFields.add("author");
        }

        IssueComment saved = commentRepository.save(comment);

        if (isNew) {
            eventPublisher.publishEvent(new ScmDomainEvent.CommentCreated(
                    ScmEventPayload.CommentData.from(saved), issueId, EventContext.from(context)));
            log.debug("Created comment: commentId={}, parentId={}", saved.getId(), issueId);
        } else if (!changedFields.isEmpty()) {
            eventPublisher.publishEvent(new ScmDomainEvent.CommentUpdated(
                    ScmEventPayload.CommentData.from(saved), issueId, changedFields, EventContext.from(context)));
            log.debug("Updated comment: commentId={}, changedFields={}", saved.getId(), changedFields);
        }

        return saved;
    }

    @Nullable
    private Issue createMinimalIssueWithRetry(EmbeddedIssue dto, ProcessingContext context) {
        try {
            return createMinimalIssue(dto, context);
        } catch (DataIntegrityViolationException e) {
            log.debug("Concurrent issue creation, looking up: iid={}", dto.iid());
            return issueRepository
                    .findByRepositoryIdAndNumber(
                            Objects.requireNonNull(context.repository()).getId(), dto.iid())
                    .orElse(null);
        }
    }

    @Nullable
    private Issue createMinimalIssue(EmbeddedIssue dto, ProcessingContext context) {
        Repository repository = Objects.requireNonNull(context.repository());
        if (repository == null || dto.id() == null) return null;

        Issue issue = new Issue();
        issue.setNativeId(dto.id());
        issue.setProvider(Objects.requireNonNull(context.provider()));
        issue.setNumber(dto.iid());
        issue.setTitle(Objects.requireNonNullElse(sanitize(dto.title()), ""));
        issue.setBody(sanitize(dto.description()));
        issue.setState(convertIssueState(dto.state()));
        issue.setHtmlUrl(dto.url());
        issue.setCreatedAt(parseGitLabTimestamp(dto.createdAt()));
        issue.setUpdatedAt(parseGitLabTimestamp(dto.updatedAt()));
        issue.setRepository(repository);
        issue.setLastSyncAt(Instant.now());

        Issue saved = issueRepository.save(issue);
        log.info(
                "Created stub Issue from note webhook: issueId={}, iid={}, repo={}",
                saved.getId(),
                saved.getNumber(),
                repository.getNameWithOwner());
        return saved;
    }

    @Nullable
    private PullRequest createMinimalPullRequestWithRetry(EmbeddedMergeRequest dto, ProcessingContext context) {
        try {
            return createMinimalPullRequest(dto, context);
        } catch (DataIntegrityViolationException e) {
            log.debug("Concurrent MR creation, looking up: iid={}", dto.iid());
            return pullRequestRepository
                    .findByRepositoryIdAndNumber(
                            Objects.requireNonNull(context.repository()).getId(), dto.iid())
                    .orElse(null);
        }
    }

    @Nullable
    private PullRequest createMinimalPullRequest(EmbeddedMergeRequest dto, ProcessingContext context) {
        Repository repository = Objects.requireNonNull(context.repository());
        if (repository == null || dto.id() == null) return null;

        PullRequest pr = new PullRequest();
        pr.setNativeId(dto.id());
        pr.setProvider(Objects.requireNonNull(context.provider()));
        pr.setNumber(dto.iid());
        pr.setTitle(Objects.requireNonNullElse(sanitize(dto.title()), ""));
        pr.setBody(sanitize(dto.description()));
        Issue.State mappedState = convertMrState(dto.state());
        pr.setState(mappedState);
        pr.setHtmlUrl(dto.url());
        pr.setDraft(dto.draft());
        pr.setMerged(mappedState == Issue.State.MERGED);
        pr.setAdditions(0);
        pr.setDeletions(0);
        pr.setChangedFiles(0);
        pr.setCommits(0);
        pr.setHeadRefName(dto.sourceBranch());
        pr.setBaseRefName(dto.targetBranch());
        pr.setCreatedAt(parseGitLabTimestamp(dto.createdAt()));
        pr.setUpdatedAt(parseGitLabTimestamp(dto.updatedAt()));
        pr.setRepository(repository);
        pr.setLastSyncAt(Instant.now());

        PullRequest saved = pullRequestRepository.save(pr);
        log.info(
                "Created stub PullRequest from note webhook: prId={}, iid={}, repo={}",
                saved.getId(),
                saved.getNumber(),
                repository.getNameWithOwner());
        return saved;
    }

    private Issue.State convertIssueState(@Nullable String state) {
        if (state == null) return Issue.State.OPEN;
        return switch (state.toLowerCase()) {
            case "opened" -> Issue.State.OPEN;
            case "closed" -> Issue.State.CLOSED;
            default -> Issue.State.OPEN;
        };
    }

    private Issue.State convertMrState(@Nullable String state) {
        if (state == null) return Issue.State.OPEN;
        return switch (state.toLowerCase()) {
            case "opened" -> Issue.State.OPEN;
            case "closed" -> Issue.State.CLOSED;
            case "merged" -> Issue.State.MERGED;
            default -> Issue.State.OPEN;
        };
    }
}
