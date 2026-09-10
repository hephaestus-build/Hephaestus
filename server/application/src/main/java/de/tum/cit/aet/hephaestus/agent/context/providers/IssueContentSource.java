package de.tum.cit.aet.hephaestus.agent.context.providers;

import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireLong;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceLimits;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceSource;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewContextBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materialises the ISSUE detection context under {@code inputs/context/} — the no-diff counterpart of
 * {@link PullRequestContentSource}:
 * <ul>
 *   <li>{@code metadata.json} — issue metadata (state, labels, assignees, milestone, sub-issue rollup)</li>
 *   <li>{@code comments.json} — the ordered discussion thread</li>
 *   <li>{@code issue_summary.md} — a single AI-readable rendering of the issue + thread</li>
 * </ul>
 *
 * <p>An issue that is missing, tombstoned, or changed since its review was admitted makes the source
 * unavailable rather than empty (prevents hollow positives). Runs read-only transactionally so the
 * lazy collections (labels, assignees, comments) load within the same tx.
 */
@Component
public class IssueContentSource implements EvidenceSource, ReviewContextBuilder {

    private static final SourceKind CORE = new SourceKind("scm.issue.core");
    private static final SourceKind COMMENTS = new SourceKind("scm.issue.comments");

    /** Checked by the integration framework against every descriptor that calls itself reviewable. */
    @Override
    public ArtifactKind artifactKind() {
        return ScmSignals.ISSUE;
    }

    @Override
    public Set<SourceKind> sourceKinds() {
        return Set.of(CORE, COMMENTS);
    }

    @Override
    public SourceKind sourceKindFor(String path) {
        return path.endsWith("comments.json") ? COMMENTS : CORE;
    }

    private static final Logger log = LoggerFactory.getLogger(IssueContentSource.class);

    /** Cap the thread included in context; most recent kept on truncation. */
    static final int MAX_COMMENTS = EvidenceLimits.MAX_ITEMS_PER_SOURCE;

    private final ObjectMapper objectMapper;
    private final IssueRepository issueRepository;
    private final IssueCommentRepository issueCommentRepository;

    public IssueContentSource(
            ObjectMapper objectMapper, IssueRepository issueRepository, IssueCommentRepository issueCommentRepository) {
        this.objectMapper = objectMapper;
        this.issueRepository = issueRepository;
        this.issueCommentRepository = issueCommentRepository;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof ContextRequest.IssueReviewRequest;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        files.putAll(captureSelected(request, sourceKinds()).files());
    }

    @Override
    @Transactional(readOnly = true)
    public void contributeSelected(ContextRequest request, Set<SourceKind> selectedKinds, Map<String, byte[]> files) {
        files.putAll(captureSelected(request, selectedKinds).files());
    }

    @Override
    @Transactional(readOnly = true)
    public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
        return captureSelected(request, selectedKinds);
    }

    private EvidenceContribution captureSelected(ContextRequest request, Set<SourceKind> selectedKinds) {
        AgentJob job = ((ContextRequest.IssueReviewRequest) request).job();
        var metadata = job.getMetadata();
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) {
            throw new JobPreparationException("Job has no metadata: jobId=" + job.getId());
        }
        long issueId = requireLong(metadata, "issue_id");
        // TYPE(i)=Issue finder: a target_type=ISSUE job must resolve to an Issue, never a PullRequest
        // (both share the single inheritance table + id space).
        Issue issue = issueRepository.findByIdWithRepository(issueId).orElse(null);
        if (issue == null || issue.getDeletedAt() != null) {
            return EvidenceContribution.unavailable(selectedKinds, SourceAbsenceReason.NOT_FOUND);
        }
        if (ScmSignals.ISSUE_UPDATED.value().equals(metadata.path("signal").asString())) {
            String admittedRevision =
                    metadata.path(AgentJob.SIGNAL_REVISION_METADATA_KEY).asString("");
            String currentRevision = ScmSignals.issueUpdatedRevision(ScmEventPayload.IssueData.from(issue))
                    .value();
            // A keyed job with no admission revision predates this fence and cannot be checked against
            // it; refuse the same way a mismatch does rather than guess which snapshot it meant.
            // NOT_FOUND rather than a dedicated code: reasonCode is a published artifact-source
            // contract vocabulary, frozen per version once shipped (immutability check on the 1.0.0
            // directory), so a new value needs its own contract version, not a quiet addition.
            if (admittedRevision.isBlank() || !currentRevision.equals(admittedRevision)) {
                return EvidenceContribution.unavailable(selectedKinds, SourceAbsenceReason.NOT_FOUND);
            }
        }
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        Map<SourceKind, SourceCompleteness> completeness = new java.util.HashMap<>();
        Map<SourceKind, java.time.Instant> observedAt = new java.util.HashMap<>();
        Map<SourceKind, SourceContentState> contentStates = new java.util.HashMap<>();

        if (selectedKinds.contains(CORE)) {
            String repoFullName =
                    issue.getRepository() != null ? issue.getRepository().getNameWithOwner() : "";
            ObjectNode meta = objectMapper.createObjectNode();
            meta.put("issue_number", issue.getNumber());
            meta.put("title", issue.getTitle());
            meta.put("body", issue.getBody() != null ? issue.getBody() : "");
            meta.put("state", issue.getState() != null ? issue.getState().name() : "UNKNOWN");
            meta.put(
                    "state_reason",
                    issue.getStateReason() != null ? issue.getStateReason().name() : null);
            meta.put("html_url", issue.getHtmlUrl());
            meta.put("repository_full_name", repoFullName);
            meta.put("author", issue.getAuthor() != null ? issue.getAuthor().getLogin() : null);
            meta.put(
                    "issue_type",
                    issue.getIssueType() != null ? issue.getIssueType().getName() : null);
            meta.put("is_locked", issue.isLocked());
            meta.put("comments_count", issue.getCommentsCount());
            meta.put("sub_issues_total", issue.getSubIssuesTotal());
            meta.put("sub_issues_completed", issue.getSubIssuesCompleted());
            meta.put(
                    "milestone",
                    issue.getMilestone() != null ? issue.getMilestone().getTitle() : null);
            meta.put(
                    "closed_at",
                    issue.getClosedAt() != null ? issue.getClosedAt().toString() : null);
            ArrayNode labels = meta.putArray("labels");
            issue.getLabels().stream().map(l -> l.getName()).sorted().forEach(labels::add);
            ArrayNode assignees = meta.putArray("assignees");
            issue.getAssignees().stream()
                    .map(u -> u.getLogin())
                    .filter(Objects::nonNull)
                    .sorted()
                    .forEach(assignees::add);
            writeJson(files, "metadata.json", meta);
            StringBuilder md = new StringBuilder(512);
            md.append("# Issue #")
                    .append(issue.getNumber())
                    .append(" — ")
                    .append(issue.getTitle())
                    .append("\n\n");
            md.append("- **State:** ").append(issue.getState());
            if (issue.getStateReason() != null)
                md.append(" (").append(issue.getStateReason()).append(")");
            md.append("\n");
            md.append("- **Repository:** ").append(repoFullName).append("\n");
            if (issue.getIssueType() != null) {
                md.append("- **Type:** ").append(issue.getIssueType().getName()).append("\n");
            }
            if (!issue.getLabels().isEmpty()) {
                md.append("- **Labels:** ")
                        .append(String.join(
                                ", ",
                                issue.getLabels().stream()
                                        .map(l -> l.getName())
                                        .sorted()
                                        .toList()))
                        .append("\n");
            }
            if (issue.getSubIssuesTotal() != null && issue.getSubIssuesTotal() > 0) {
                md.append("- **Sub-issues:** ")
                        .append(issue.getSubIssuesCompleted() != null ? issue.getSubIssuesCompleted() : 0)
                        .append("/")
                        .append(issue.getSubIssuesTotal())
                        .append(" completed\n");
            }
            md.append("\n## Description\n\n")
                    .append(issue.getBody() != null ? issue.getBody() : "_(empty)_")
                    .append("\n");
            files.put(OUTPUT_PREFIX + "issue_summary.md", md.toString().getBytes(StandardCharsets.UTF_8));
            completeness.put(CORE, SourceCompleteness.COMPLETE);
            if (issue.getLastSyncAt() != null) {
                observedAt.put(CORE, issue.getLastSyncAt());
            }
        }

        int commentCount = 0;
        if (selectedKinds.contains(COMMENTS)) {
            CommentCapture commentCapture = recentComments(issueId);
            List<IssueComment> ordered = commentCapture.comments();
            ArrayNode commentsArr = objectMapper.createArrayNode();
            for (IssueComment c : ordered) {
                ObjectNode cn = objectMapper.createObjectNode();
                cn.put("author", c.getAuthor() != null ? c.getAuthor().getLogin() : null);
                cn.put("created_at", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
                cn.put("body", c.getBody() != null ? c.getBody() : "");
                commentsArr.add(cn);
            }
            commentCount = ordered.size();
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
                job.getId());
        return new EvidenceContribution(files, completeness, Map.of(), observedAt, Map.of(), contentStates);
    }

    private CommentCapture recentComments(long issueId) {
        List<IssueComment> comments = new ArrayList<>(
                issueCommentRepository.findRecentByIssueIdWithAuthor(issueId, PageRequest.of(0, MAX_COMMENTS + 1)));
        if (comments.size() > MAX_COMMENTS + 1) {
            comments = new ArrayList<>(comments.subList(0, MAX_COMMENTS + 1));
        }
        boolean complete = comments.size() <= MAX_COMMENTS;
        if (!complete) comments.remove(comments.size() - 1);
        comments.sort(
                Comparator.comparing(IssueComment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return new CommentCapture(List.copyOf(comments), complete);
    }

    private record CommentCapture(List<IssueComment> comments, boolean complete) {}

    private void writeJson(Map<String, byte[]> files, String name, Object node) {
        try {
            files.put(OUTPUT_PREFIX + name, objectMapper.writeValueAsBytes(node));
        } catch (Exception e) {
            throw new JobPreparationException("Failed to serialize " + name + ": " + e.getMessage(), e);
        }
    }
}
