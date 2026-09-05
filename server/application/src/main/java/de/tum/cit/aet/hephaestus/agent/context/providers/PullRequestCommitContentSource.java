package de.tum.cit.aet.hephaestus.agent.context.providers;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceSource;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The commits a pull request carries, as the mirror linked them through {@code commit_pull_request}: subject,
 * body, timestamps, size and how each was made. Per-commit diffs are not staged.
 */
@Component
@Order(220)
public class PullRequestCommitContentSource implements EvidenceSource {

    private static final SourceKind KIND = new SourceKind("scm.pull-request.commits");

    @Override
    public Set<SourceKind> sourceKinds() {
        return Set.of(KIND);
    }

    @Override
    public SourceKind sourceKindFor(String path) {
        return KIND;
    }

    private static final Logger log = LoggerFactory.getLogger(PullRequestCommitContentSource.class);

    static final String FILE_NAME = "commits.json";

    /** Commits staged per pull request; a branch carrying more is reported PARTIAL, never silently cut. */
    static final int MAX_COMMITS = 200;

    private final ObjectMapper objectMapper;
    private final CommitRepository commitRepository;
    private final PullRequestRepository pullRequestRepository;

    public PullRequestCommitContentSource(
            ObjectMapper objectMapper, CommitRepository commitRepository, PullRequestRepository pullRequestRepository) {
        this.objectMapper = objectMapper;
        this.commitRepository = commitRepository;
        this.pullRequestRepository = pullRequestRepository;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof ContextRequest.PracticeReviewRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    /**
     * Derives completeness/emptiness from the payload itself rather than the default: the file is
     * always written, even with zero commits, so the default's file-presence check would report
     * NON_EMPTY on an empty result and COMPLETE past the truncation cap.
     */
    @Override
    public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
        if (!selectedKinds.contains(KIND) || !(request instanceof ContextRequest.PracticeReviewRequest review)) {
            return new EvidenceContribution(Map.of(), Map.of());
        }
        var metadata = review.job().getMetadata();
        // A missing key is a malformed job; failing loud avoids silently telling the model the pull
        // request carries no commits, which no pull request does.
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) {
            throw new EvidenceCollectionException("Commit collection has no job metadata", null);
        }
        Long pullRequestId = MetaJson.optLong(metadata, "pull_request_id");
        if (pullRequestId == null) {
            throw new EvidenceCollectionException("Commit collection has no pull_request_id", null);
        }
        if (!pullRequestRepository.existsByIdAndDeletedAtIsNull(pullRequestId)) {
            return EvidenceContribution.unavailable(selectedKinds, SourceAbsenceReason.NOT_FOUND);
        }
        ObjectNode root = collect(pullRequestId);
        boolean truncated = root.path("truncated").asBoolean(false);
        return new EvidenceContribution(
                Map.of(OUTPUT_PREFIX + FILE_NAME, objectMapper.writeValueAsBytes(root)),
                Map.of(KIND, truncated ? SourceCompleteness.PARTIAL : SourceCompleteness.COMPLETE),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(KIND, root.path("commits").isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY));
    }

    @Override
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        contributeSelected(request, sourceKinds(), files);
    }

    @Override
    public void contributeSelected(ContextRequest request, Set<SourceKind> selectedKinds, Map<String, byte[]> files) {
        files.putAll(capture(request, selectedKinds).files());
    }

    private ObjectNode collect(long pullRequestId) {
        try {
            List<Commit> commits = new ArrayList<>(
                    commitRepository.findByAssociatedPullRequestId(pullRequestId, PageRequest.of(0, MAX_COMMITS + 1)));
            boolean truncated = commits.size() > MAX_COMMITS;
            if (truncated) commits.remove(commits.size() - 1);

            ArrayNode commitArray = objectMapper.createArrayNode();
            for (Commit c : commits) {
                commitArray.add(toCommit(c));
            }

            ObjectNode root = objectMapper.createObjectNode();
            root.set("commits", commitArray);
            root.put("count", commitArray.size());
            root.put("truncated", truncated);
            log.info(
                    "PullRequestCommits: prId={} emitted={} truncated={}",
                    pullRequestId,
                    commitArray.size(),
                    truncated);
            return root;
        } catch (Exception e) {
            throw new EvidenceCollectionException("Commit collection failed", e);
        }
    }

    private ObjectNode toCommit(Commit c) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sha", c.getSha());
        node.put("subject", subjectOf(c));
        String body = bodyOf(c);
        if (body != null) {
            node.put("body", body);
        }
        node.put("authoredAt", c.getAuthoredAt().toString());
        node.put("committedAt", c.getCommittedAt().toString());
        node.put("additions", c.getAdditions());
        node.put("deletions", c.getDeletions());
        node.put("changedFiles", c.getChangedFiles());
        // Enrichment facts; absent until the provider has been asked, and then omitted rather than
        // written as null so a missing key reads as "not known" and not as "false" or "zero".
        if (c.getAuthoredByCommitter() != null) {
            node.put("authoredByCommitter", c.getAuthoredByCommitter());
        }
        if (c.getCommittedViaWeb() != null) {
            node.put("committedViaWeb", c.getCommittedViaWeb());
        }
        if (c.getParentCount() != null) {
            node.put("parentCount", c.getParentCount());
        }
        return node;
    }

    /**
     * The stored message is the subject on every sync path, but the column admits a newline, so the
     * subject is taken as the first line rather than trusted to be one.
     */
    private static String subjectOf(Commit c) {
        String message = c.getMessage();
        int newline = message.indexOf('\n');
        return (newline < 0 ? message : message.substring(0, newline)).strip();
    }

    /** Whatever the message held after its first line, followed by the stored body; null when neither exists. */
    private static @Nullable String bodyOf(Commit c) {
        String message = c.getMessage();
        int newline = message.indexOf('\n');
        StringBuilder body = new StringBuilder();
        if (newline >= 0) {
            body.append(message.substring(newline + 1).strip());
        }
        String storedBody = c.getMessageBody();
        if (storedBody != null && !storedBody.isBlank()) {
            if (!body.isEmpty()) {
                body.append("\n\n");
            }
            body.append(storedBody.strip());
        }
        return body.isEmpty() ? null : body.toString();
    }
}
