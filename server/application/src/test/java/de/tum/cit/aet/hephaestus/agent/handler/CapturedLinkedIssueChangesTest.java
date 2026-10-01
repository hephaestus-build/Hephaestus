package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.CapturedReviewedWorkChanges;
import de.tum.cit.aet.hephaestus.agent.context.ReviewedWork;
import de.tum.cit.aet.hephaestus.agent.context.providers.LinkedWorkItemContentSource;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkChanges.ObservationEvidence;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class CapturedLinkedIssueChangesTest extends BaseUnitTest {
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID OBSERVATION = UUID.randomUUID();
    private static final SourceKind LINKED = new SourceKind("scm.linked-work-items");
    private static final SourceContractVersion CONTRACT = new SourceContractVersion("1.3.0");
    private static final String PATH = SandboxLayout.CONTEXT_PREFIX + "linked_work_items/18.md";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentJobRepository jobs = mock(AgentJobRepository.class);
    private final PullRequestRepository pullRequests = mock(PullRequestRepository.class);
    private final ArtifactSourceCatalogRegistry catalogs = mock(ArtifactSourceCatalogRegistry.class);
    private final CapturedReviewedWorkChanges changes =
            new CapturedReviewedWorkChanges(jobs, mapper, catalogs, pullRequests, mock(IssueRepository.class));

    @Test
    void shouldSelectOnlyTheObservationWhoseOwnVerifiedClosingIssueChanged() {
        var citations = citations(PATH, LINKED.value(), digest(issue("- [ ] Confirm repair")));
        var unrelated =
                citations(SandboxLayout.CONTEXT_PREFIX + "linked_work_items/19.md", LINKED.value(), "a".repeat(64));
        UUID other = UUID.randomUUID();
        capture(42, true);
        when(pullRequests.findClosingIssuesById(42L)).thenReturn(List.of(issue("- [x] Confirm repair")));

        assertThat(changes.materiallyChangedLinkedIssues(
                        7,
                        List.of(
                                new ObservationEvidence(OBSERVATION, RUN, citations),
                                new ObservationEvidence(other, RUN, unrelated)),
                        42))
                .containsExactly(OBSERVATION);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "unchanged",
                "unverified",
                "job",
                "attempt",
                "mixed",
                "fractional",
                "negative",
                "missing",
                "digest",
                "source",
                "path"
            })
    void shouldRefuseUnchangedOrUnverifiableEvidence(String refusal) {
        Issue current = issue("- [x] Confirm repair");
        var citations = citations(
                refusal.equals("path") ? SandboxLayout.CONTEXT_PREFIX + "linked_work_items/19.md" : PATH,
                refusal.equals("source") ? "scm.pull-request.core" : LINKED.value(),
                digest(issue("- [ ] Confirm repair")));
        ObjectNode verification = (ObjectNode) citations.get(0).path("verification");
        switch (refusal) {
            case "unchanged" -> current.setBody("- [ ] Confirm repair");
            case "unverified" -> ((ObjectNode) citations.get(0)).remove("verification");
            case "job" -> verification.put("jobId", UUID.randomUUID().toString());
            case "attempt" -> verification.put("attempt", 1);
            case "mixed" -> {
                var other = (ObjectNode) citations.get(0).deepCopy();
                ((ObjectNode) other.path("verification")).put("attempt", 1);
                citations.add(other);
            }
            case "fractional" -> verification.put("attempt", 0.5);
            case "negative" -> verification.put("attempt", -1);
            case "missing" -> verification.remove("attempt");
            case "digest" -> verification.put("artifactSha256", "unknown");
            default -> {}
        }
        capture(42, true);
        when(pullRequests.findClosingIssuesById(42L)).thenReturn(List.of(current));
        assertThat(changes.materiallyChangedLinkedIssues(
                        7, List.of(new ObservationEvidence(OBSERVATION, RUN, citations)), 42))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"work", "policy", "workspace"})
    void shouldRefuseUnboundOrUnpermittedCaptures(String refusal) {
        var citations = citations(PATH, LINKED.value(), digest(issue("- [ ] Confirm repair")));
        when(pullRequests.findClosingIssuesById(42L)).thenReturn(List.of(issue("- [x] Confirm repair")));
        if (refusal.equals("workspace")) {
            when(jobs.findCapturedReviewedWork(7, Set.of(RUN))).thenReturn(List.of());
        } else {
            capture(refusal.equals("work") ? 43 : 42, !refusal.equals("policy"));
        }
        assertThat(changes.materiallyChangedLinkedIssues(
                        7, List.of(new ObservationEvidence(OBSERVATION, RUN, citations)), 42))
                .isEmpty();
    }

    @Test
    void shouldNotReadCapturesWhenTheClosingIssueIsUnavailable() {
        Issue deleted = issue("- [x] Confirm repair");
        deleted.setDeletedAt(Instant.now());
        when(pullRequests.findClosingIssuesById(42L)).thenReturn(List.of(deleted));
        assertThat(changes.materiallyChangedLinkedIssues(
                        7, List.of(new ObservationEvidence(OBSERVATION, RUN, mapper.createArrayNode())), 42))
                .isEmpty();
        verifyNoInteractions(jobs, catalogs);
    }

    private void capture(long artifactId, boolean permitted) {
        var row = mock(AgentJobRepository.CapturedReviewedWorkRow.class);
        when(row.getId()).thenReturn(RUN);
        when(row.getContractVersion()).thenReturn(CONTRACT.value());
        when(row.getReviewedWork())
                .thenReturn(mapper.writeValueAsString(new ReviewedWork(
                        ArtifactKinds.PULL_REQUEST.value(),
                        artifactId,
                        ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, "Title", "Closes #18"),
                        null,
                        Instant.now())));
        when(jobs.findCapturedReviewedWork(7, Set.of(RUN))).thenReturn(List.of(row));
        if (artifactId == 42) {
            when(catalogs.isSourceUsePermitted(CONTRACT, LINKED, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                    .thenReturn(permitted);
        }
    }

    private ArrayNode citations(String path, String sourceKind, String digest) {
        var citations = mapper.createArrayNode();
        var citation = citations
                .addObject()
                .put("sourceKind", sourceKind)
                .put("artifactPath", path)
                .put("path", path)
                .put("startLine", 1)
                .put("endLine", 1)
                .put("quote", "- [ ] Confirm repair");
        var job = new AgentJob();
        job.setId(RUN);
        CitationVerification.record(citation, job, digest, CitationVerification.quoteDigest("- [ ] Confirm repair"));
        return citations;
    }

    private static String digest(Issue issue) {
        return ProvenanceDigest.sha256Hex(LinkedWorkItemContentSource.asText(issue));
    }

    private static Issue issue(String body) {
        var issue = new Issue();
        issue.setNumber(18);
        issue.setTitle("Acceptance criteria");
        issue.setBody(body);
        issue.setState(Issue.State.CLOSED);
        return issue;
    }
}
