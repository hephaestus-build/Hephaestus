package de.tum.cit.aet.hephaestus.agent.context.providers;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceSource;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(210)
public class WorkspaceInventoryContentSource implements EvidenceSource {

    private static final SourceKind KIND = new SourceKind("workspace.project-inventory");

    @Override
    public Set<SourceKind> sourceKinds() {
        return Set.of(KIND);
    }

    @Override
    public SourceKind sourceKindFor(String path) {
        return KIND;
    }

    private static final Logger log = LoggerFactory.getLogger(WorkspaceInventoryContentSource.class);

    static final String OUTPUT_FILE = OUTPUT_PREFIX + "project_inventory.json";

    static final int MAX_PER_TYPE = 200;

    static final int MAX_REPOS_SCANNED = 25;

    private final ObjectMapper objectMapper;
    private final IssueRepository issueRepository;
    private final PullRequestRepository pullRequestRepository;
    private final RepositoryRepository repositoryRepository;

    public WorkspaceInventoryContentSource(
            ObjectMapper objectMapper,
            IssueRepository issueRepository,
            PullRequestRepository pullRequestRepository,
            RepositoryRepository repositoryRepository) {
        this.objectMapper = objectMapper;
        this.issueRepository = issueRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.repositoryRepository = repositoryRepository;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return (request instanceof ContextRequest.PracticeReviewRequest
                || request instanceof ContextRequest.IssueReviewRequest
                || request instanceof ContextRequest.ConversationReviewRequest);
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        try {
            if (request instanceof ContextRequest.ConversationReviewRequest conversation) {
                contributeWorkspaceWide(conversation.job(), files);
            } else {
                contributeRepositoryScoped(request, files);
            }
        } catch (Exception e) {
            throw new EvidenceCollectionException("Workspace-inventory collection failed", e);
        }
    }

    @Override
    public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
        EvidenceContribution captured = EvidenceSource.super.capture(request, selectedKinds);
        byte[] inventory = captured.files().get(OUTPUT_FILE);
        if (!selectedKinds.contains(KIND) || inventory == null) {
            return captured;
        }
        try {
            JsonNode listed = objectMapper.readTree(inventory);
            boolean empty = listed.path("issues").isEmpty()
                    && listed.path("pullRequests").isEmpty();
            return new EvidenceContribution(
                    captured.files(),
                    captured.completeness(),
                    captured.immutableIdentities(),
                    captured.observedAt(),
                    captured.sourceEffectiveAt(),
                    Map.of(KIND, empty ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY));
        } catch (Exception exception) {
            throw new IllegalStateException("Serialized workspace inventory could not be read", exception);
        }
    }

    /** PR/issue-review path: one named {@code repository_id}, focal artifact excluded from its own listing. */
    private void contributeRepositoryScoped(ContextRequest request, Map<String, byte[]> files) {
        AgentJob job = jobOf(request);
        if (job == null) {
            return;
        }
        JsonNode m = job.getMetadata();
        Long repositoryId = m == null ? null : MetaJson.optLong(m, "repository_id");
        // Without the focal repository the inventory cannot say what else exists, and an empty
        // inventory reads as "this workspace tracks no work" — the inverse of the truth.
        if (repositoryId == null) {
            throw new EvidenceCollectionException("Workspace-inventory collection has no repository_id", null);
        }

        // Identify the focal artifact so the agent can tell "what else exists" from "the one under review".
        String focalType = (request instanceof ContextRequest.IssueReviewRequest
                        ? ArtifactKinds.ISSUE
                        : ArtifactKinds.PULL_REQUEST)
                .value();
        Integer focalNumber = m == null
                ? null
                : MetaJson.optInteger(m, focalType.equals(ArtifactKinds.ISSUE.value()) ? "issue_number" : "pr_number");

        PageRequest cap = PageRequest.of(0, MAX_PER_TYPE);
        List<Issue> issues = issueRepository.findIssueInventoryByRepositoryId(repositoryId, cap);
        List<PullRequest> pullRequests =
                pullRequestRepository.findPullRequestInventoryByRepositoryId(repositoryId, cap);

        ObjectNode root = objectMapper.createObjectNode();
        String repositoryName = m == null ? null : MetaJson.optString(m, "repository_full_name");
        if (repositoryName != null) {
            root.put("repository", repositoryName);
        }
        ObjectNode focal = root.putObject("focal");
        focal.put("type", focalType);
        if (focalNumber != null) {
            focal.put("number", focalNumber);
        }

        // Whether the project labels its issues at all, and with what: a practice about triage judges an
        // unlabelled issue against the convention the project actually keeps, never against one it has not.
        ObjectNode labels = root.putObject("labelScheme");
        labels.put("issuesTotal", issueRepository.countIssuesByRepositoryId(repositoryId));
        labels.put("issuesLabelled", issueRepository.countLabelledIssuesByRepositoryId(repositoryId));
        ArrayNode inUse = labels.putArray("labelsInUse");
        for (String name : issueRepository.findLabelNamesInUseByRepositoryId(repositoryId)) {
            inUse.add(name);
        }

        ArrayNode issuesArr = root.putArray("issues");
        int issuesEmitted = emit(issuesArr, issues, false);
        ArrayNode prsArr = root.putArray("pullRequests");
        int prsEmitted = emit(prsArr, pullRequests, true);
        // Conservative upper bound: a listing of exactly MAX_PER_TYPE rows reports truncated=true even
        // when it happens to be exhaustive (page size == count). This only ever over-claims non-exhaustive,
        // never the dangerous direction (the contract is that absence-of-match must not prove uniqueness).
        boolean truncated = issues.size() >= MAX_PER_TYPE || pullRequests.size() >= MAX_PER_TYPE;
        root.put("truncated", truncated);

        files.put(OUTPUT_FILE, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        log.info(
                "Project inventory: {} issue(s) + {} PR(s), truncated={}, repoId={}",
                issuesEmitted,
                prsEmitted,
                truncated,
                repositoryId);
    }

    /**
     * Conversation-review path: no single repository to scope to, so the inventory is aggregated across
     * every repository the job's workspace monitors. There is no focal artifact: a conversation is about a
     * person, not an issue or a pull request.
     */
    private void contributeWorkspaceWide(AgentJob job, Map<String, byte[]> files) {
        if (job.getWorkspace() == null) {
            return;
        }
        long workspaceId = job.getWorkspace().getId();
        List<Repository> repos = repositoryRepository.findAllByWorkspaceMonitors(workspaceId);
        boolean repoCapHit = repos.size() > MAX_REPOS_SCANNED;
        List<Repository> scanned = repos.size() > MAX_REPOS_SCANNED ? repos.subList(0, MAX_REPOS_SCANNED) : repos;

        PageRequest cap = PageRequest.of(0, MAX_PER_TYPE);
        List<Issue> issues = new ArrayList<>();
        List<PullRequest> pullRequests = new ArrayList<>();
        for (Repository repo : scanned) {
            if (issues.size() < MAX_PER_TYPE) {
                issues.addAll(issueRepository.findIssueInventoryByRepositoryId(repo.getId(), cap));
            }
            if (pullRequests.size() < MAX_PER_TYPE) {
                pullRequests.addAll(pullRequestRepository.findPullRequestInventoryByRepositoryId(repo.getId(), cap));
            }
        }
        // Same conservative-truncation contract as the repository-scoped path, evaluated before the
        // merged lists are capped down to MAX_PER_TYPE below.
        boolean perTypeTruncated = issues.size() >= MAX_PER_TYPE || pullRequests.size() >= MAX_PER_TYPE;
        if (issues.size() > MAX_PER_TYPE) {
            issues = issues.subList(0, MAX_PER_TYPE);
        }
        if (pullRequests.size() > MAX_PER_TYPE) {
            pullRequests = pullRequests.subList(0, MAX_PER_TYPE);
        }

        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode repoNames = root.putArray("repositories");
        // Only the scanned repositories were searched. Listing the others would show a repository
        // name with no work items beside it, which reads as an empty repository rather than an
        // unsearched one.
        for (Repository repo : scanned) {
            if (repo.getNameWithOwner() != null) {
                repoNames.add(repo.getNameWithOwner());
            }
        }
        ObjectNode focal = root.putObject("focal");
        focal.put("type", ArtifactKinds.CONVERSATION_THREAD.value());

        ArrayNode issuesArr = root.putArray("issues");
        int issuesEmitted = emit(issuesArr, issues, false);
        ArrayNode prsArr = root.putArray("pullRequests");
        int prsEmitted = emit(prsArr, pullRequests, true);
        boolean truncated = perTypeTruncated || repoCapHit;
        root.put("truncated", truncated);

        files.put(OUTPUT_FILE, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        log.info(
                "Workspace-wide project inventory: {} issue(s) + {} PR(s) across {} repo(s), truncated={}, workspaceId={}",
                issuesEmitted,
                prsEmitted,
                repos.size(),
                truncated,
                workspaceId);
    }

    /** Append each artifact as a compact node; returns how many were emitted. */
    private int emit(ArrayNode out, List<? extends Issue> items, boolean isPullRequest) {
        int emitted = 0;
        for (Issue item : items) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("number", item.getNumber());
            node.put("title", item.getTitle());
            if (item.getState() != null) {
                node.put("state", item.getState().name());
            }
            if (item.getAuthor() != null && item.getAuthor().getLogin() != null) {
                // Omit (not null) when the SCM account is a deleted/ghost user with no login, mirroring
                // the milestone/url branches below — keeps the "field absent, never JSON null" convention.
                node.put("author", item.getAuthor().getLogin());
            }
            // Milestone (title only) is the cross-artifact lifecycle anchor several practices reason about —
            // "do these siblings share the focal milestone?". Cheap ManyToOne, JOIN FETCHed in the query.
            if (item.getMilestone() != null) {
                node.put("milestone", item.getMilestone().getTitle());
            }
            if (item.getHtmlUrl() != null) {
                node.put("url", item.getHtmlUrl());
            }
            if (isPullRequest && item instanceof PullRequest pr) {
                node.put("isDraft", pr.isDraft());
            }
            out.add(node);
            emitted++;
        }
        return emitted;
    }

    private static @Nullable AgentJob jobOf(ContextRequest request) {
        if (request instanceof ContextRequest.PracticeReviewRequest pr) {
            return pr.job();
        }
        if (request instanceof ContextRequest.IssueReviewRequest ir) {
            return ir.job();
        }
        return null;
    }
}
