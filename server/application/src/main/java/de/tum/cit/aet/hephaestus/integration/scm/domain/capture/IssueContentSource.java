package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCaptureContext.OUTPUT_PREFIX;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueProgress;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository.StoredComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.IssueEvidenceVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

// Missing, tombstoned, or changed issues make capture unavailable; empty content could create false positives.
@Component
@ConditionalOnServerRole
public class IssueContentSource {

    public static final SourceKind CORE = new SourceKind("scm.issue.core");
    public static final SourceKind COMMENTS = new SourceKind("scm.issue.comments");

    public Set<SourceKind> sourceKinds() {
        return Set.of(CORE, COMMENTS);
    }

    public SourceKind sourceKindFor(String path) {
        var recordSource = ScmCitableTexts.recordSource(path);
        if (recordSource != null && sourceKinds().contains(recordSource)) return recordSource;
        return path.endsWith("comments.json") ? COMMENTS : CORE;
    }

    private static final Logger log = LoggerFactory.getLogger(IssueContentSource.class);

    private final ObjectMapper objectMapper;

    private final IssueRepository issueRepository;
    private final IssueCommentRepository issueCommentRepository;
    private final IssueEvidenceVersion revisions;

    public IssueContentSource(
            ObjectMapper objectMapper,
            IssueRepository issueRepository,
            IssueCommentRepository issueCommentRepository,
            IssueEvidenceVersion revisions) {
        this.objectMapper = objectMapper;
        this.issueRepository = issueRepository;
        this.issueCommentRepository = issueCommentRepository;
        this.revisions = revisions;
    }

    @Transactional(readOnly = true)
    public CaptureContribution capture(ScmCaptureContext request, Set<SourceKind> selectedKinds) {
        ScmCaptureContext capture = request;
        var metadata = capture.work();
        long issueId = metadata.sourceId();
        // TYPE(i)=Issue finder: a target_type=ISSUE job must resolve to an Issue, never a
        // PullRequest
        // (both share the single inheritance table + id space).
        Issue issue = issueRepository.findByIdWithRepository(issueId).orElse(null);
        if (issue == null || issue.getDeletedAt() != null) {
            return CaptureContribution.unavailable(selectedKinds, SourceAbsenceReason.NOT_FOUND);
        }
        // Source absence codes belong to a versioned contract. A stale admission uses NOT_FOUND.
        if (!capture.pinned().version().equals(revisions.of(ScmEventPayload.IssueData.from(issue))))
            return CaptureContribution.unavailable(selectedKinds, SourceAbsenceReason.NOT_FOUND);
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<SourceKind, SourceCompleteness> completeness = new HashMap<>();
        Map<SourceKind, Instant> observedAt = new HashMap<>();
        Map<SourceKind, SourceContentState> contentStates = new HashMap<>();
        Map<SourceKind, List<String>> limitations = new HashMap<>();

        if (selectedKinds.contains(CORE)) {
            IssueRepository.ChildRollup children =
                    (issue.getSubIssuesTotal() == null || issue.getSubIssuesCompleted() == null)
                                    && issue.getId() != null
                            ? issueRepository.countChildrenByParentIssueId(issue.getId(), Issue.State.CLOSED)
                            : null;
            ObjectNode meta = workRecord(issue, metadata, children);
            writeJson(files, "metadata.json", meta);
            // The description as written, for quoting; metadata.json carries it escaped, for
            // programs.
            files.put(
                    OUTPUT_PREFIX + "description.md",
                    (issue.getBody() == null ? "" : issue.getBody()).getBytes(StandardCharsets.UTF_8));
            completeness.put(CORE, SourceCompleteness.COMPLETE);
            if (issue.getLastSyncAt() != null) {
                observedAt.put(CORE, issue.getLastSyncAt());
            }
        }

        int commentCount = 0;
        if (selectedKinds.contains(COMMENTS)) {
            CommentCapture commentCapture = reviewedComments(issueId, issue);
            List<StoredComment> ordered = commentCapture.comments();
            ArrayNode commentsArr = objectMapper.createArrayNode();
            for (StoredComment c : ordered) {
                ObjectNode cn = objectMapper.createObjectNode();
                cn.put("native_id", c.getNativeId());
                cn.put("author", c.getAuthorLogin());
                cn.put("author_id", c.getAuthorNativeId());
                if (c.getAuthorType() == User.Type.BOT) {
                    cn.put("bot", true);
                }
                cn.put("created_at", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
                cn.put("updated_at", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null);
                cn.put("body", c.getBody());
                commentsArr.add(cn);
            }
            commentCount = ordered.size();
            if (!commentCapture.complete()) {
                limitations.put(
                        COMMENTS,
                        List.of(
                                issue.getCommentsCount() == null
                                        ? ScmCapturedWork.COMMENT_COUNT_UNKNOWN
                                        : ScmCapturedWork.COMMENTS_NOT_READ_WHOLE));
            }
            writeJson(files, "comments.json", commentsArr);
            completeness.put(
                    COMMENTS, commentCapture.complete() ? SourceCompleteness.COMPLETE : SourceCompleteness.PARTIAL);
            contentStates.put(COMMENTS, ordered.isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY);
        }

        log.info(
                "Issue context built: issueId={}, number={}, comments={}, jobId={}",
                issueId,
                issue.getNumber(),
                commentCount,
                capture.jobId());
        return new CaptureContribution(
                files,
                completeness,
                Map.of(),
                observedAt,
                Map.of(),
                contentStates,
                Map.of(),
                Map.of(),
                null,
                limitations,
                List.of(),
                List.of(),
                ScmCitableTexts.files(files, this::sourceKindFor, objectMapper));
    }

    // Missing counts cannot prove coverage. A larger provider count proves missing comments;
    // a smaller count does not prove the mirror is current.
    private CommentCapture reviewedComments(long issueId, Issue issue) {
        long mirrored = issueCommentRepository.countByIssueId(issueId);
        Integer expected = issue.getCommentsCount();
        return new CommentCapture(revisions.reviewedComments(issueId), expected != null && expected <= mirrored);
    }

    private record CommentCapture(List<StoredComment> comments, boolean complete) {}

    private void writeJson(Map<String, byte[]> files, String name, Object node) {
        try {
            files.put(
                    OUTPUT_PREFIX + name,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
        } catch (JacksonException e) {
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED, "Failed to serialize " + name + ": " + e.getMessage(), e);
        }
    }

    /**
     * The issue's work record, {@code context/metadata.json}, as the practice runner reads it
     * ({@code WORK_RECORDS["scm.issue"]} in {@code lib/contract.ts}). {@code children} is the
     * stored sub-issue rollup, read only when the provider reported no count.
     */
    public static ObjectNode workRecord(
            Issue issue, ScmCapturedWork metadata, IssueRepository.@Nullable ChildRollup children) {
        String repoFullName = metadata.repositoryPath();
        ObjectNode meta = JsonNodeFactory.instance.objectNode();
        meta.put("issue_number", issue.getNumber());
        meta.put("title", issue.getTitle());
        meta.put("body", issue.getBody() != null ? issue.getBody() : "");
        meta.put("state", issue.getState() != null ? issue.getState().name() : "UNKNOWN");
        meta.put(
                "state_reason",
                issue.getStateReason() != null ? issue.getStateReason().name() : null);
        meta.put("html_url", issue.getHtmlUrl());
        meta.put("repository_full_name", repoFullName);
        // Keep the admitted repository identity; a moved mirror cannot supply its provider.
        meta.put("repository_id", metadata.repositoryId());
        var repository = issue.getRepository();
        if (repository != null && Objects.equals(repository.getId(), metadata.repositoryId()))
            meta.put("provider_id", repository.getProvider().getId());
        meta.put("author", issue.getAuthor() != null ? issue.getAuthor().getLogin() : null);
        meta.put("author_id", issue.getAuthor() != null ? issue.getAuthor().getNativeId() : null);
        meta.put(
                "issue_type",
                issue.getIssueType() != null ? issue.getIssueType().getName() : null);
        meta.put("is_locked", issue.isLocked());
        var progress = IssueProgress.of(issue, children);
        meta.put("sub_issues_total", progress.total());
        meta.put("sub_issues_completed", progress.completed());
        if (progress.source() != null) meta.put("sub_issues_source", progress.source());
        meta.put(
                "milestone", issue.getMilestone() != null ? issue.getMilestone().getTitle() : null);
        meta.put("closed_at", issue.getClosedAt() != null ? issue.getClosedAt().toString() : null);
        ArrayNode labels = meta.putArray("labels");
        issue.getLabels().stream().map(l -> l.getName()).sorted().forEach(labels::add);
        ArrayNode assignees = meta.putArray("assignees");
        issue.getAssignees().stream()
                .map(u -> u.getLogin())
                .filter(Objects::nonNull)
                .sorted()
                .forEach(assignees::add);
        return meta;
    }
}
