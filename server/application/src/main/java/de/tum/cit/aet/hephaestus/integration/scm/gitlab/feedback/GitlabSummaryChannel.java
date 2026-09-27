package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitlabMrResolver.GRAPHQL_TIMEOUT;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackNotSentException;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabBackwardPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitlabMrResolver.MrCoordinates;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

/**
 * GitLab adapter for {@link SummaryChannel}. Posts a single MR-level note via the
 * {@code CreateMergeRequestNote} GraphQL mutation.
 *
 * <p>{@link SummaryChannel.FeedbackTarget#subjectExternalId} convention for GitLab is
 * {@code "project/full/path!iid"}; the channel resolves the MR global gid via
 * {@link GitlabMrResolver} before issuing the mutation.
 *
 * <p>Defensively backtick-escapes GitLab slash commands ({@code /approve}, {@code /merge},
 * {@code /close}, etc.) so untrusted content can't accidentally execute an action, even though the
 * agent layer already sanitises in {@code PullRequestCommentPoster.sanitize} — a caller bypassing
 * that path must not be able to trigger a GitLab action.
 */
@Component
@OutboundEgressGateway
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitlabSummaryChannel implements SummaryChannel {

    private static final Logger log = LoggerFactory.getLogger(GitlabSummaryChannel.class);

    /**
     * Matches GitLab slash commands at line start. Mirrors {@code PullRequestCommentPoster.GITLAB_SLASH_COMMAND}
     * — duplicated, not shared, so this adapter is safe on its own regardless of the agent layer.
     */
    static final Pattern GITLAB_SLASH_COMMAND = Pattern.compile(
            "^(\\s*/(?:approve|merge|close|reopen|assign|unassign|label|unlabel|lock|unlock|"
                    + "milestone|estimate|spend|award|subscribe|unsubscribe|todo|done|wip|draft|ready|"
                    + "due|remove_due_date|weight|epic|copy_metadata|move|confidential|shrug|tableflip)\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private final GitLabGraphQlClientProvider gitLabProvider;
    private final GitlabMrResolver mrResolver;
    private final OutboundEgressGuard egressGuard;

    public GitlabSummaryChannel(
            GitLabGraphQlClientProvider gitLabProvider, GitlabMrResolver mrResolver, OutboundEgressGuard egressGuard) {
        this.gitLabProvider = gitLabProvider;
        this.mrResolver = mrResolver;
        this.egressGuard = egressGuard;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public String formatPullRequestSubjectId(String repoFullName, int prNumber) {
        if (repoFullName == null || repoFullName.isBlank()) {
            throw new IllegalArgumentException("repoFullName is required");
        }
        return repoFullName + "!" + prNumber;
    }

    @Override
    public SummaryHandle postSummary(FeedbackTarget target, FeedbackContent content) {
        long scopeId = target.ref().workspaceId();
        String noteableGid;
        try {
            noteableGid = resolveNoteable(scopeId, target.subjectExternalId());
        } catch (RuntimeException e) {
            throw new FeedbackNotSentException("GitLab note not sent: " + e.getMessage(), e);
        }
        String body = escapeSlashCommands(content.externalBody());

        ClientGraphQlResponse response;
        try {
            egressGuard.requireDeliveryAllowed("gitlab.post-summary");
            response = gitLabProvider
                    .forScope(scopeId)
                    .documentName("CreateMergeRequestNote")
                    .variable("noteableId", noteableGid)
                    .variable("body", body)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);
        } catch (OutboundEgressSuppressedException e) {
            throw e;
        } catch (RuntimeException e) {
            // A transport/timeout error must surface as the channel's typed exception (consistent with
            // updateSummary) so PullRequestCommentPoster's catch(FeedbackDeliveryException) wraps it uniformly.
            throw new FeedbackDeliveryException("createNote transport error: " + e.getMessage(), e);
        }

        if (response == null) {
            throw new FeedbackDeliveryException("Null response from createNote mutation");
        }

        // Surface TOP-LEVEL GraphQL errors with their real reason — createNote returns no payload at all when
        // the instance is read-only, the gid is unresolvable, or permission is denied.
        List<String> topLevelErrors = Objects.requireNonNull(response).getErrors().stream()
                .map(e -> e.getMessage())
                .filter(Objects::nonNull)
                .toList();
        if (!topLevelErrors.isEmpty()) {
            throw new FeedbackDeliveryException("GitLab createNote failed: " + topLevelErrors);
        }

        List<String> mutationErrors =
                Objects.requireNonNull(response).field("createNote.errors").getValue();
        if (mutationErrors != null && !mutationErrors.isEmpty()) {
            throw new FeedbackDeliveryException("GitLab createNote failed: " + mutationErrors);
        }

        String noteId =
                Objects.requireNonNull(response).field("createNote.note.id").getValue();
        if (noteId == null) {
            throw new FeedbackDeliveryException("No note ID in createNote response");
        }
        log.info("Posted GitLab note: workspaceId={}, noteableGid={}, noteId={}", scopeId, noteableGid, noteId);
        return new SummaryHandle(noteId, response.field("createNote.note.url").getValue());
    }

    /**
     * The subject is a merge request ({@code path!iid}) or an issue ({@code path#iid}); both post via the same
     * generic createNote mutation, and only the noteable gid resolution differs.
     */
    private String resolveNoteable(long scopeId, String subject) {
        if (gitLabProvider.isRateLimitCritical(scopeId)) {
            throw new FeedbackDeliveryException("GitLab rate limit critical for scope " + scopeId);
        }
        if (isIssueSubject(subject)) {
            MrCoordinates issue = GitlabMrResolver.parseIssueSubjectExternalId(subject);
            return mrResolver.resolveIssueGid(scopeId, issue.projectPath(), issue.iid());
        }
        MrCoordinates mr = GitlabMrResolver.parseSubjectExternalId(subject);
        return mrResolver.resolve(scopeId, mr.projectPath(), mr.iid()).globalId();
    }

    /**
     * Edit an existing MR/issue note in place via the {@code updateNote} mutation. No noteable resolution is
     * needed — the note's own global id ({@code externalId}, e.g. {@code gid://gitlab/Note/123}) addresses it
     * directly. Returns a typed {@link UpdateOutcome}: a confirmed-deleted note is {@code GONE} (caller
     * re-posts); a rate-limit / transport / unknown error is {@code TRANSIENT} (caller keeps the prior summary,
     * does not re-post, so a flaky update never double-posts). Only a blank external id throws
     * {@link FeedbackDeliveryException}.
     */
    @Override
    public UpdateOutcome updateSummary(FeedbackTarget target, String externalId, FeedbackContent content) {
        long scopeId = target.ref().workspaceId();
        if (externalId == null || externalId.isBlank()) {
            throw new FeedbackDeliveryException("Cannot edit a GitLab note in place: external note id is missing");
        }
        if (gitLabProvider.isRateLimitCritical(scopeId)) {
            return UpdateOutcome.transientFailure("GitLab rate limit critical for scope " + scopeId);
        }
        String body = escapeSlashCommands(content.externalBody());

        ClientGraphQlResponse response;
        try {
            egressGuard.requireDeliveryAllowed("gitlab.update-summary");
            response = gitLabProvider
                    .forScope(scopeId)
                    .documentName("UpdateNote")
                    .variable("id", externalId)
                    .variable("body", body)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);
        } catch (OutboundEgressSuppressedException e) {
            throw e;
        } catch (RuntimeException e) {
            return UpdateOutcome.transientFailure("updateNote transport error: " + e.getMessage());
        }

        if (response == null) {
            return UpdateOutcome.transientFailure("Null response from updateNote mutation");
        }

        // A DELETED note surfaces as a TOP-LEVEL GraphQL error (the global id resolves to nothing), NOT a
        // mutation-payload error — GitLab returns no `updateNote` object at all. Left unchecked, that falls
        // through to the no-id branch below and is mis-read as TRANSIENT, so an orphaned summary never re-posts.
        List<String> topLevelErrors = Objects.requireNonNull(response).getErrors().stream()
                .map(e -> e.getMessage())
                .filter(Objects::nonNull)
                .toList();
        if (!topLevelErrors.isEmpty()) {
            return looksGone(topLevelErrors)
                    ? UpdateOutcome.gone("GitLab updateNote (top-level): " + topLevelErrors)
                    : UpdateOutcome.transientFailure("GitLab updateNote top-level errors: " + topLevelErrors);
        }

        List<String> mutationErrors =
                Objects.requireNonNull(response).field("updateNote.errors").getValue();
        if (mutationErrors != null && !mutationErrors.isEmpty()) {
            return looksGone(mutationErrors)
                    ? UpdateOutcome.gone("GitLab updateNote: " + mutationErrors)
                    : UpdateOutcome.transientFailure("GitLab updateNote failed: " + mutationErrors);
        }

        String noteId =
                Objects.requireNonNull(response).field("updateNote.note.id").getValue();
        if (noteId == null) {
            // The mutation neither confirmed gone nor returned an id — treat as transient, don't double-post.
            return UpdateOutcome.transientFailure("No note id in updateNote response");
        }
        log.info("Edited GitLab note in place: workspaceId={}, noteId={}", scopeId, noteId);
        return UpdateOutcome.edited(
                new SummaryHandle(noteId, response.field("updateNote.note.url").getValue()));
    }

    /** GitLab caps a connection page at 100. */
    private static final int EXISTING_SUMMARY_SEARCH_PAGE_SIZE = 100;

    /** Our cap, not GitLab's: an unscanned tail answers {@code UNKNOWN}, so recovery retries rather than reposts. */
    private static final int EXISTING_SUMMARY_SEARCH_PAGE_BUDGET = 3;

    /**
     * Scans this MR/issue's notes for one whose body contains {@code marker}, walking the connection backwards
     * from its newest end — the summary a crashed delivery already posted is the newest note.
     *
     * <p>Reads the noteable's <em>flat</em> {@code notes} connection rather than {@code discussions { notes }}:
     * the nested form pages notes at a fixed size inside each discussion with no cursor of its own, so an
     * over-long thread would hide notes no cursor can reach.
     */
    @Override
    public ExistingSummaryLookup findExistingSummary(FeedbackTarget target, String marker) {
        if (marker == null || marker.isBlank()) {
            return ExistingSummaryLookup.unknown();
        }
        long scopeId = target.ref().workspaceId();
        if (gitLabProvider.isRateLimitCritical(scopeId)) {
            return ExistingSummaryLookup.unknown();
        }
        String subject = target.subjectExternalId();
        String documentName;
        String notesPath;
        MrCoordinates coordinates;
        if (isIssueSubject(subject)) {
            documentName = "GetIssueNotesNewest";
            notesPath = "project.issue.notes";
            coordinates = GitlabMrResolver.parseIssueSubjectExternalId(subject);
        } else {
            documentName = "GetMergeRequestNotesNewest";
            notesPath = "project.mergeRequest.notes";
            coordinates = GitlabMrResolver.parseSubjectExternalId(subject);
        }

        String cursor = null;
        for (int page = 0; page < EXISTING_SUMMARY_SEARCH_PAGE_BUDGET; page++) {
            try {
                ClientGraphQlResponse response = gitLabProvider
                        .forScope(scopeId)
                        .documentName(documentName)
                        .variable("fullPath", coordinates.projectPath())
                        .variable("iid", String.valueOf(coordinates.iid()))
                        .variable("last", EXISTING_SUMMARY_SEARCH_PAGE_SIZE)
                        .variable("before", cursor)
                        .execute()
                        .block(GRAPHQL_TIMEOUT);
                if (response == null
                        || !Objects.requireNonNull(response).getErrors().isEmpty()) {
                    return ExistingSummaryLookup.unknown();
                }

                List<Map<String, Object>> notes = Objects.requireNonNull(response)
                        .field(notesPath + ".nodes")
                        .getValue();
                if (notes == null) {
                    return ExistingSummaryLookup.unknown();
                }
                for (Map<String, Object> note : notes) {
                    String noteId = (String) note.get("id");
                    String body = (String) note.get("body");
                    if (noteId != null && body != null && body.contains(marker)) {
                        return ExistingSummaryLookup.found(new SummaryHandle(noteId, (String) note.get("url")));
                    }
                }

                GitLabBackwardPageInfo pageInfo = Objects.requireNonNull(response)
                        .field(notesPath + ".pageInfo")
                        .toEntity(GitLabBackwardPageInfo.class);
                if (pageInfo == null) {
                    return ExistingSummaryLookup.unknown();
                }
                if (!pageInfo.hasPreviousPage()) {
                    return ExistingSummaryLookup.absent();
                }
                cursor = pageInfo.startCursor();
                if (cursor == null || cursor.isBlank()) {
                    return ExistingSummaryLookup.unknown();
                }
            } catch (RuntimeException e) {
                log.debug(
                        "Existing-summary dedup lookup failed (treated as unknown, not absent): scopeId={}, error={}",
                        scopeId,
                        e.getMessage());
                return ExistingSummaryLookup.unknown();
            }
        }
        return ExistingSummaryLookup.unknown();
    }

    /** An issue subject is {@code "project/path#iid"}; a merge request is {@code "project/path!iid"}. */
    private static boolean isIssueSubject(String subjectExternalId) {
        return subjectExternalId != null && subjectExternalId.lastIndexOf('#') > subjectExternalId.lastIndexOf('!');
    }

    /** Conservative NOT_FOUND heuristic: GitLab signals a deleted note only via a free-text mutation error. */
    private static boolean looksGone(List<String> errors) {
        return errors.stream()
                .filter(Objects::nonNull)
                .map(e -> e.toLowerCase(Locale.ROOT))
                .anyMatch(e -> e.contains("not found")
                        || e.contains("does not exist")
                        || e.contains("could not be found")
                        || e.contains("couldn't be found"));
    }

    static String escapeSlashCommands(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        return GITLAB_SLASH_COMMAND.matcher(body).replaceAll("`$1`");
    }
}
