package de.tum.cit.aet.hephaestus.agent.context.providers;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceLimits;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceSource;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The issues a pull request refers to by number, as this repository stores them.
 *
 * <p>The server's part is the lookup: which numbers the description, the branch name and the commit
 * subjects mention, and what this repository knows about each. How a reference is worded — a closing
 * keyword, a bare mention, where in the text it sits — is read by the review from the same
 * description, branch and commits it has in front of it.
 */
@Component
@Order(200)
public class LinkedWorkItemContentSource implements EvidenceSource {

    private static final SourceKind KIND = new SourceKind("scm.linked-work-items");

    @Override
    public Set<SourceKind> sourceKinds() {
        return Set.of(KIND);
    }

    @Override
    public SourceKind sourceKindFor(String path) {
        return KIND;
    }

    private static final Logger log = LoggerFactory.getLogger(LinkedWorkItemContentSource.class);

    static final String OUTPUT_FILE = OUTPUT_PREFIX + "linked_work_items.json";

    static final int MAX_ITEMS = EvidenceLimits.MAX_ITEMS_PER_SOURCE;

    /**
     * {@code #N}. The trailing boundary {@code (?![\w]|\.[0-9])} rejects what looks like a reference but
     * is not: a hex colour ({@code #1a2b}), a unit ({@code #42px}), a version ({@code #1.2}). A sentence
     * period after the number is still a reference.
     */
    private static final Pattern NUMBER_REF = Pattern.compile("#(\\d+)(?![\\w]|\\.[0-9])");

    /** An issue number opening a branch-slug segment: {@code 18-foo}, {@code feat/18-foo}. */
    private static final Pattern BRANCH_REF = Pattern.compile("(?:^|/)(\\d{1,7})-");

    private final ObjectMapper objectMapper;
    private final PullRequestRepository pullRequestRepository;
    private final IssueRepository issueRepository;
    private final GitRepositoryManager gitRepositoryManager;
    private final ReviewRepositoryPreparer repositoryPreparer;

    public LinkedWorkItemContentSource(
            ObjectMapper objectMapper,
            PullRequestRepository pullRequestRepository,
            IssueRepository issueRepository,
            GitRepositoryManager gitRepositoryManager,
            ReviewRepositoryPreparer repositoryPreparer) {
        this.objectMapper = objectMapper;
        this.pullRequestRepository = pullRequestRepository;
        this.issueRepository = issueRepository;
        this.gitRepositoryManager = gitRepositoryManager;
        this.repositoryPreparer = repositoryPreparer;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof ContextRequest.PracticeReviewRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        files.putAll(capture(request, Set.of(KIND)).files());
    }

    @Override
    public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
        if (!selectedKinds.contains(KIND) || !(request instanceof ContextRequest.PracticeReviewRequest pr)) {
            return new EvidenceContribution(Map.of(), Map.of());
        }
        try {
            AgentJob job = pr.job();
            ReviewRepositoryPreparer.PreparedReview prepared = null;
            if (gitRepositoryManager.isEnabled()) {
                prepared = pr.preparation().prepare(repositoryPreparer, job);
            } else {
                repositoryPreparer.authorize(job);
            }
            JsonNode m = job.getMetadata();
            if (m == null || m.isNull() || m.isMissingNode()) {
                throw new EvidenceCollectionException("Linked-work-item job metadata is missing", null);
            }
            Long repositoryId = MetaJson.optLong(m, "repository_id");
            Long pullRequestId = MetaJson.optLong(m, "pull_request_id");
            if (repositoryId == null) {
                throw new EvidenceCollectionException("Linked-work-item repository id is missing", null);
            }
            PullRequest pullRequest = pullRequestId == null
                    ? null
                    : pullRequestRepository
                            .findByIdWithAllForGate(pullRequestId)
                            .orElse(null);

            Set<Integer> numbers = new LinkedHashSet<>();
            collect(NUMBER_REF, pullRequest == null ? null : pullRequest.getBody(), numbers);
            collect(
                    BRANCH_REF,
                    firstNonBlank(
                            MetaJson.optString(m, "source_branch"),
                            pullRequest == null ? null : pullRequest.getHeadRefName()),
                    numbers);
            if (prepared != null) {
                gitRepositoryManager.forEachCommitSubject(
                        prepared.key(),
                        prepared.target(),
                        prepared.head(),
                        subject -> collect(NUMBER_REF, subject, numbers));
            }

            ArrayNode items = objectMapper.createArrayNode();
            List<Integer> unresolved = new ArrayList<>();
            int examined = 0;
            for (int number : numbers) {
                if (examined++ >= MAX_ITEMS) break;
                Optional<Issue> resolved = issueRepository.findByRepositoryIdAndNumber(repositoryId, number);
                if (resolved.isEmpty()) {
                    // Found, and pointing at an issue this repository does not mirror: another repository
                    // or an external tracker.
                    unresolved.add(number);
                    continue;
                }
                items.add(toItem(resolved.get()));
            }

            ObjectNode root = objectMapper.createObjectNode();
            root.set("workItems", items);
            ArrayNode unresolvedRefs = root.putArray("unresolvedReferences");
            unresolved.forEach(unresolvedRefs::add);
            root.put("truncated", numbers.size() > MAX_ITEMS);

            Map<String, byte[]> files = Map.of(OUTPUT_FILE, objectMapper.writeValueAsBytes(root));
            log.info("Linked work items: wrote {} item(s), unresolved={}", items.size(), unresolved.size());
            return new EvidenceContribution(
                    files,
                    // Always partial: a number scan cannot establish there is no link the work never mentioned.
                    Map.of(KIND, SourceCompleteness.PARTIAL),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(KIND, items.isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY));
        } catch (EvidenceCollectionException e) {
            throw e;
        } catch (Exception e) {
            throw new EvidenceCollectionException("Linked-work-item collection failed", e);
        }
    }

    private ObjectNode toItem(Issue issue) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("number", issue.getNumber());
        node.put("title", issue.getTitle());
        if (issue.getState() != null) node.put("state", issue.getState().name());
        node.put("url", issue.getHtmlUrl());
        node.put("body", issue.getBody());
        ArrayNode labels = node.putArray("labels");
        Set<Label> labelSet = issue.getLabels();
        if (labelSet != null) {
            for (Label label : labelSet) {
                if (label != null && label.getName() != null) labels.add(label.getName());
            }
        }
        if (issue.getSubIssuesTotal() != null) node.put("subIssuesTotal", issue.getSubIssuesTotal());
        if (issue.getSubIssuesCompleted() != null) node.put("subIssuesCompleted", issue.getSubIssuesCompleted());
        return node;
    }

    private static void collect(Pattern pattern, @Nullable String text, Set<Integer> numbers) {
        if (text == null || text.isBlank()) return;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            try {
                long value = Long.parseLong(matcher.group(1));
                if (value > 0 && value <= Integer.MAX_VALUE) numbers.add((int) value);
            } catch (NumberFormatException ignored) {
                // Longer than any issue number: not a reference.
            }
        }
    }

    private static @Nullable String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }
}
