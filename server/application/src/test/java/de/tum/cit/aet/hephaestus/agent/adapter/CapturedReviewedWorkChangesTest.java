package de.tum.cit.aet.hephaestus.agent.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.context.ReviewedWork;
import de.tum.cit.aet.hephaestus.agent.context.ReviewedWorkFixtures;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkChanges;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkChanges.PullRequestRevision;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class CapturedReviewedWorkChangesTest extends BaseUnitTest {
    private static final UUID RUN = UUID.randomUUID();
    private static final String HEAD = "1".repeat(40);
    private final AgentJobRepository jobs = mock(AgentJobRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ArtifactSourceCatalogRegistry catalogs = mock(ArtifactSourceCatalogRegistry.class);
    private final CapturedReviewedWorkChanges changes = new CapturedReviewedWorkChanges(
            jobs, mapper, catalogs, mock(PullRequestRepository.class), mock(IssueRepository.class));

    @BeforeEach
    void permitCapturedSources() {
        lenient().when(catalogs.isSourceUsePermitted(any(), any(), any())).thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "body", "head"})
    void shouldAdmitOnlyAKnownMaterialDifferenceWhenOneCapturedFieldChanged(String field) {
        PullRequest current = current();
        switch (field) {
            case "title" -> current.setTitle("Repaired title");
            case "body" -> current.setBody("Repaired description");
            case "head" -> current.setHeadRefOid("2".repeat(40));
            default -> throw new IllegalArgumentException(field);
        }
        stored(capture(42, HEAD));
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).containsExactly(RUN);
    }

    @Test
    void shouldRefuseARepairWhenTheCapturedFieldsMatchDespiteDifferentTimesOrMetadata() {
        stored(capture(42, HEAD));
        PullRequest current = current();
        current.setUpdatedAt(Instant.now());
        current.setCommentsCount(12);
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "{}", "not json", "{\"head\":\"invalid\"}"})
    void shouldRefuseUnknownCaptureProvenanceWhenTheStoredIdentityIsAbsentOrMalformed(@Nullable String json) {
        stored(json);
        PullRequest current = current();
        current.setBody("A real repair");
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).isEmpty();
    }

    @Test
    void shouldRefuseAnotherArtifactsCaptureEvenWhenTheTextChanged() {
        stored(capture(43, HEAD));
        PullRequest current = current();
        current.setBody("A real repair");
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).isEmpty();
    }

    @Test
    void shouldNotInferAPushFromAnUnpinnedCaptureButStillRecognizeChangedText() {
        stored(capture(42, null));
        PullRequest current = current();
        current.setHeadRefOid("2".repeat(40));
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).isEmpty();
        current.setBody("A real repair");
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).containsExactly(RUN);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "unknown"})
    void shouldNotInferAChangeWhenTheCurrentHeadIsUnknown(@Nullable String head) {
        stored(capture(42, HEAD));
        PullRequest current = current();
        current.setHeadRefOid(head);
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).isEmpty();
    }

    @Test
    void shouldNotReadAnyCapturesWhenNoAuthorizedNegativeRunsExist() {
        assertThat(changes.materiallyChanged(7, Set.of(), revision(current()))).isEmpty();
        verifyNoInteractions(jobs);
    }

    @Test
    void shouldRefuseRunsNotReturnedByTheWorkspaceScopedLookup() {
        when(jobs.findCapturedReviewedWork(7, Set.of(RUN))).thenReturn(List.of());
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current())))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "head"})
    void shouldNotUseACapturedSourceWhenItsContractDeniesAutomatedReview(String source) {
        stored(capture(42, HEAD));
        PullRequest current = current();
        var denied = source.equals("text") ? PullRequestContentSource.CORE : PullRequestContentSource.DIFF;
        when(catalogs.isSourceUsePermitted(
                        new SourceContractVersion("1.3.0"), denied, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .thenReturn(false);
        if (source.equals("text")) current.setBody("Repaired description");
        else current.setHeadRefOid("2".repeat(40));
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"invalid", "99.0.0"})
    void shouldRefuseUnknownSourceContractsWhenTheWorkChanged(@Nullable String version) {
        var row = mock(AgentJobRepository.CapturedReviewedWorkRow.class);
        when(row.getReviewedWork()).thenReturn(capture(42, HEAD));
        when(row.getContractVersion()).thenReturn(version);
        when(jobs.findCapturedReviewedWork(7, Set.of(RUN))).thenReturn(List.of(row));
        if ("99.0.0".equals(version)) {
            when(catalogs.isSourceUsePermitted(any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("Unknown contract"));
        }
        PullRequest current = current();
        current.setBody("Repaired description");
        assertThat(changes.materiallyChanged(7, Set.of(RUN), revision(current))).isEmpty();
    }

    private void captured(String reviewedWork, JobFolderIndex manifest) {
        captured(reviewedWork, mapper.writeValueAsString(manifest));
    }

    private void captured(String reviewedWork, String manifestJson) {
        var row = mock(AgentJobRepository.CapturedReviewedWorkRow.class);
        lenient().when(row.getReviewedWork()).thenReturn(reviewedWork);
        lenient().when(row.getManifest()).thenReturn(manifestJson);
        lenient().when(row.getContractVersion()).thenReturn("1.3.0");
        lenient().when(row.getId()).thenReturn(RUN);
        when(jobs.findCapturedReviewedWork(7, Set.of(RUN))).thenReturn(List.of(row));
    }

    @Test
    void shouldDeliverAgainstTheIdentityAnAuthorizedPinnedCaptureRecorded() {
        captured(capture(42, HEAD), ReviewedWorkFixtures.pullRequestManifest(Instant.EPOCH, "Body", HEAD));

        // The base is the pinned change's own, from the authorized manifest, not from the job's admission metadata.
        assertThat(changes.deliverableCapture(7, RUN, 42))
                .contains(new ReviewedWorkChanges.CapturedIdentity(
                        HEAD,
                        ReviewedWorkFixtures.BASE,
                        ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, "Title", "Body")));
    }

    @Test
    void shouldDeliverAgainstTheSameIdentityAfterAdmissionRetiresTheManifestArtifacts() {
        // The shape AgentJobRepository.discardRetiredArtifactInventory leaves once observations are admitted:
        // the folder's artifacts and refusals and every source's artifacts emptied, each source's state and facts kept.
        ObjectNode retired = mapper.valueToTree(ReviewedWorkFixtures.pullRequestManifest(Instant.EPOCH, "Body", HEAD));
        retired.putArray("artifacts");
        retired.putArray("refusals");
        for (var source : retired.get("sources")) {
            ((ObjectNode) source).putArray("artifacts");
        }
        captured(capture(42, HEAD), mapper.writeValueAsString(retired));

        assertThat(changes.deliverableCapture(7, RUN, 42))
                .contains(new ReviewedWorkChanges.CapturedIdentity(
                        HEAD,
                        ReviewedWorkFixtures.BASE,
                        ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, "Title", "Body")));
        // The whole manifest still refuses a non-empty source without files: retirement is read, never re-admitted.
        assertThatThrownBy(() -> mapper.readValue(mapper.writeValueAsString(retired), JobFolderIndex.class))
                .isInstanceOf(JacksonException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Non-empty source must contain at least one artifact");
    }

    private ObjectNode retired(JobFolderIndex manifest) {
        ObjectNode retired = mapper.valueToTree(manifest);
        retired.putArray("artifacts");
        retired.putArray("refusals");
        for (var source : retired.get("sources")) {
            ((ObjectNode) source).putArray("artifacts");
        }
        return retired;
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"moved head", "other work", "duplicate source", "no sources", "unknown state", "bad digest"})
    void shouldNotDeliverAgainstARetiredManifestThatDoesNotProveItsIdentity(String retirement) {
        ObjectNode manifest = retired(ReviewedWorkFixtures.pullRequestManifest(Instant.EPOCH, "Body", HEAD));
        String reviewedWork = capture(42, HEAD);
        switch (retirement) {
            case "moved head" -> reviewedWork = capture(42, "2".repeat(40));
            case "other work" -> reviewedWork = capture(43, HEAD);
            case "duplicate source" ->
                ((ArrayNode) manifest.get("sources"))
                        .add(manifest.get("sources").get(1).deepCopy());
            case "no sources" -> manifest.putArray("sources");
            case "unknown state" ->
                ((ObjectNode) manifest.get("sources").get(1).get("state")).put("availability", "RECOVERED");
            case "bad digest" -> manifest.put("catalogDigest", "not-a-digest");
            default -> throw new IllegalArgumentException(retirement);
        }
        captured(reviewedWork, mapper.writeValueAsString(manifest));

        assertThat(changes.deliverableCapture(7, RUN, 42)).isEmpty();
    }

    @Test
    void shouldNotDeliverAgainstARetiredManifestWhoseContractNoLongerPermitsDelivery() {
        captured(
                capture(42, HEAD),
                mapper.writeValueAsString(
                        retired(ReviewedWorkFixtures.pullRequestManifest(Instant.EPOCH, "Body", HEAD))));
        when(catalogs.isSourceUsePermitted(
                        new SourceContractVersion("1.3.0"),
                        PullRequestContentSource.DIFF,
                        SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(false);

        assertThat(changes.deliverableCapture(7, RUN, 42)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "issue manifest",
                "unpinned manifest",
                "other pinned head",
                "other work",
                "no head",
                "malformed range"
            })
    void shouldNotDeliverAgainstACaptureThatDoesNotProveItsIdentity(String capture) {
        switch (capture) {
            case "malformed range" -> {
                // A pinned change whose base is not a Git object id proves neither its base nor its head.
                JobFolderIndex pinned = ReviewedWorkFixtures.pullRequestManifest(Instant.EPOCH, "Body", HEAD);
                captured(
                        capture(42, HEAD),
                        new JobFolderIndex(
                                pinned.contractVersion(),
                                "0".repeat(64),
                                ArtifactKinds.PULL_REQUEST.value(),
                                pinned.capturedAt(),
                                pinned.sources().stream()
                                        .map(source -> source.kind().equals(PullRequestContentSource.DIFF)
                                                ? new SourceCapture(
                                                        source.kind(),
                                                        new SourceCaptureState.Available(
                                                                SourceContentState.NON_EMPTY,
                                                                SourceCompleteness.COMPLETE,
                                                                new SourceCaptureFacts(
                                                                        Instant.EPOCH, null, null, "base:" + HEAD)),
                                                        source.artifacts())
                                                : source)
                                        .toList()));
            }
            case "issue manifest" -> {
                // Pull request sources, pinned at the captured head, under another artifact kind.
                JobFolderIndex pinned = ReviewedWorkFixtures.pullRequestManifest(Instant.EPOCH, "Body", HEAD);
                captured(
                        capture(42, HEAD),
                        new JobFolderIndex(
                                pinned.contractVersion(),
                                "0".repeat(64),
                                ArtifactKinds.ISSUE.value(),
                                pinned.capturedAt(),
                                pinned.sources()));
            }
            case "unpinned manifest" ->
                captured(capture(42, HEAD), ReviewedWorkFixtures.pullRequestManifest(Instant.EPOCH, "Body", null));
            case "other pinned head" ->
                captured(
                        capture(42, HEAD),
                        ReviewedWorkFixtures.pullRequestManifest(Instant.EPOCH, "Body", "2".repeat(40)));
            case "other work" ->
                captured(capture(43, HEAD), ReviewedWorkFixtures.pullRequestManifest(Instant.EPOCH, "Body", HEAD));
            case "no head" ->
                captured(capture(42, null), ReviewedWorkFixtures.pullRequestManifest(Instant.EPOCH, "Body", HEAD));
            default -> throw new IllegalArgumentException(capture);
        }

        assertThat(changes.deliverableCapture(7, RUN, 42)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"core", "diff"})
    void shouldNotDeliverAgainstASourceItsContractDeniesFeedbackDelivery(String source) {
        captured(capture(42, HEAD), ReviewedWorkFixtures.pullRequestManifest(Instant.EPOCH, "Body", HEAD));
        when(catalogs.isSourceUsePermitted(
                        new SourceContractVersion("1.3.0"),
                        source.equals("core") ? PullRequestContentSource.CORE : PullRequestContentSource.DIFF,
                        SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(false);

        assertThat(changes.deliverableCapture(7, RUN, 42)).isEmpty();
    }

    @Test
    void shouldNotDeliverAgainstARunTheWorkspaceScopedLookupDidNotReturn() {
        when(jobs.findCapturedReviewedWork(7, Set.of(RUN))).thenReturn(List.of());

        assertThat(changes.deliverableCapture(7, RUN, 42)).isEmpty();
    }

    private String capture(long id, @Nullable String head) {
        return mapper.writeValueAsString(new ReviewedWork(
                ArtifactKinds.PULL_REQUEST.value(),
                id,
                ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, "Title", "Body"),
                head,
                Instant.EPOCH));
    }

    private void stored(@Nullable String json) {
        var row = mock(AgentJobRepository.CapturedReviewedWorkRow.class);
        when(row.getReviewedWork()).thenReturn(json);
        lenient().when(row.getContractVersion()).thenReturn("1.3.0");
        lenient().when(row.getId()).thenReturn(RUN);
        when(jobs.findCapturedReviewedWork(7, Set.of(RUN))).thenReturn(List.of(row));
    }

    private static PullRequestRevision revision(PullRequest current) {
        return new PullRequestRevision(current.getId(), current.getHeadRefOid(), current.getTitle(), current.getBody());
    }

    private static PullRequest current() {
        PullRequest current = new PullRequest();
        current.setId(42L);
        current.setTitle("Title");
        current.setBody("Body");
        current.setHeadRefOid(HEAD);
        return current;
    }
}
