package de.tum.cit.aet.hephaestus.agent.context;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ReviewedWorkTest extends BaseUnitTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-09-30T00:05:00Z");
    private static final String HEAD = "b".repeat(40);
    private static final String METADATA = SandboxLayout.CONTEXT_PREFIX + "metadata.json";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void shouldIdentifyWhatThePullRequestCaptureStagedAndPinned() {
        byte[] manifest =
                mapper.writeValueAsBytes(ReviewedWorkFixtures.pullRequestManifest(CAPTURED_AT, "No issue link", HEAD));
        var staged = Map.of(
                SandboxLayout.MANIFEST_PATH,
                manifest,
                METADATA,
                ReviewedWorkFixtures.metadata(mapper, "MR !2", "No issue link", HEAD));

        ReviewedWork work = ReviewedWork.captured(manifest, staged, 42, mapper).orElseThrow();

        assertThat(work.artifactKind()).isEqualTo(ArtifactKinds.PULL_REQUEST.value());
        assertThat(work.artifactId()).isEqualTo(42);
        assertThat(work.head()).isEqualTo(HEAD);
        assertThat(work.capturedAt()).isEqualTo(CAPTURED_AT);
        assertThat(work.titleAndDescriptionRevision())
                .isEqualTo(ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, "MR !2", "No issue link"))
                .isNotEqualTo(ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, "MR !2", "Closes #12"));
    }

    @Test
    void shouldRefuseACaptureWhoseMetadataNamesAnotherCommitThanThePinnedChange() {
        byte[] manifest = mapper.writeValueAsBytes(ReviewedWorkFixtures.pullRequestManifest(CAPTURED_AT, "body", HEAD));
        var staged = Map.of(METADATA, ReviewedWorkFixtures.metadata(mapper, "MR !2", "body", "c".repeat(40)));

        assertThatThrownBy(() -> ReviewedWork.captured(manifest, staged, 42, mapper))
                .isInstanceOf(JobPreparationException.class);
    }

    @Test
    void shouldIdentifyNothingWithoutStagedCoreValues() {
        byte[] manifest = mapper.writeValueAsBytes(ReviewedWorkFixtures.issueManifest(CAPTURED_AT, "body"));

        assertThat(ReviewedWork.captured(manifest, Map.of(), 7, mapper)).isEmpty();
        assertThat(ReviewedWork.captured(manifest, Map.of(METADATA, "{\"body\":\"x\"}".getBytes(UTF_8)), 7, mapper))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"artifactKind\":\"scm.issue\",\"artifactId\":7,\"titleAndDescriptionRevision\":null,"
                        + "\"capturedAt\":\"2026-09-30T00:05:00Z\"}",
                "{\"artifactKind\":\"scm.issue\",\"artifactId\":7,\"titleAndDescriptionRevision\":\"sha~abc\","
                        + "\"capturedAt\":\"2026-09-30T00:05:00Z\"}",
                "{\"artifactKind\":\"scm.issue\",\"artifactId\":7,\"titleAndDescriptionRevision\":\"dig~00000000000000000000000000000000\"}",
                "{\"artifactKind\":\"scm.issue\",\"titleAndDescriptionRevision\":\"dig~00000000000000000000000000000000\","
                        + "\"capturedAt\":\"2026-09-30T00:05:00Z\"}"
            })
    void shouldNotReadAMalformedStoredIdentityAsOne(String stored) {
        assertThatThrownBy(() -> mapper.readValue(stored, ReviewedWork.class)).isInstanceOf(JacksonException.class);
    }

    private static final Instant ADMITTED_AT = Instant.parse("2026-09-30T00:01:00Z");

    private final ArtifactSourceCatalogRegistry reviewable = catalogs(true, true);

    private static ArtifactSourceCatalogRegistry catalogs(boolean core, boolean diff) {
        ArtifactSourceCatalogRegistry catalogs = mock(ArtifactSourceCatalogRegistry.class);
        lenient()
                .when(catalogs.isSourceUsePermitted(
                        any(), eq(PullRequestContentSource.CORE), eq(SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW)))
                .thenReturn(core);
        lenient()
                .when(catalogs.isSourceUsePermitted(
                        any(), eq(PullRequestContentSource.DIFF), eq(SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW)))
                .thenReturn(diff);
        return catalogs;
    }

    /** The metadata a pull request capture stages from the admitted job: its fields and its admission basis. */
    private ObjectNode staging(@Nullable String body) {
        ObjectNode staging = mapper.createObjectNode()
                .put("title", "MR !2")
                .put("body", body)
                .put("commit_sha", HEAD);
        ObjectNode basis = staging.putObject("basis");
        basis.putArray("admission_fields")
                .add("pr_number")
                .add("commit_sha")
                .add("subject_role")
                .add("title")
                .add("body");
        basis.put("admitted_at", ADMITTED_AT.toString());
        return staging;
    }

    private ObjectNode admitted(@Nullable String body) {
        return mapper.createObjectNode()
                .put("title", "MR !2")
                .put("body", body)
                .put("commit_sha", HEAD)
                .put("pr_number", 2);
    }

    private ReviewedWork.@Nullable RetainedBasis proof(
            ObjectNode staging,
            @Nullable Instant admittedAt,
            @Nullable ObjectNode admitted,
            @Nullable ArtifactSourceCatalogRegistry catalogs) {
        byte[] manifest =
                mapper.writeValueAsBytes(ReviewedWorkFixtures.pullRequestManifest(CAPTURED_AT, "No issue link", HEAD));
        return ReviewedWork.captured(
                        manifest,
                        Map.of(METADATA, mapper.writeValueAsBytes(staging)),
                        42,
                        mapper,
                        new ReviewedWork.AdmissionBasis(admittedAt, admitted),
                        catalogs)
                .orElseThrow()
                .retainedBasis();
    }

    @Test
    void shouldMarkACaptureOfExactlyTheAdmittedWorkAtItsAdmission() {
        assertThat(proof(staging("No issue link"), ADMITTED_AT, admitted("No issue link"), reviewable))
                .isEqualTo(ReviewedWork.RetainedBasis.ADMISSION);
        assertThat(proof(staging(null), ADMITTED_AT, admitted(null), reviewable))
                .isEqualTo(ReviewedWork.RetainedBasis.ADMISSION);
    }

    static Stream<Arguments> unprovedCaptures() {
        return Stream.of(
                Arguments.of("no basis", (Consumer<ObjectNode>) staging -> staging.remove("basis")),
                Arguments.of("fields not an array", (Consumer<ObjectNode>)
                        staging -> ((ObjectNode) staging.get("basis")).put("admission_fields", "title,body")),
                Arguments.of("a field not text", (Consumer<ObjectNode>)
                        staging -> ((ArrayNode) staging.get("basis").get("admission_fields")).add(3)),
                Arguments.of("body not among the fields", (Consumer<ObjectNode>)
                        staging -> ((ObjectNode) staging.get("basis"))
                                .putArray("admission_fields")
                                .add("title")
                                .add("commit_sha")),
                Arguments.of("no admission instant", (Consumer<ObjectNode>)
                        staging -> ((ObjectNode) staging.get("basis")).remove("admitted_at")),
                Arguments.of("malformed admission instant", (Consumer<ObjectNode>)
                        staging -> ((ObjectNode) staging.get("basis")).put("admitted_at", "yesterday")),
                Arguments.of("another admission instant", (Consumer<ObjectNode>)
                        staging -> ((ObjectNode) staging.get("basis"))
                                .put("admitted_at", ADMITTED_AT.plusMillis(1).toString())),
                Arguments.of("another title", (Consumer<ObjectNode>) staging -> staging.put("title", "MR !3")),
                Arguments.of("another description", (Consumer<ObjectNode>) staging -> staging.put("body", "Closes #1")),
                Arguments.of("an empty description for a missing one", (Consumer<ObjectNode>)
                        staging -> staging.put("body", "")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unprovedCaptures")
    void shouldNotMarkACaptureWhoseBasisDoesNotProveTheAdmission(String name, Consumer<ObjectNode> change) {
        ObjectNode staging = staging(null);
        change.accept(staging);

        assertThat(proof(staging, ADMITTED_AT, admitted(null), reviewable)).isNull();
    }

    @Test
    void shouldNotMarkACaptureAgainstAnAdmissionItCannotRead() {
        assertThat(proof(staging("b"), null, admitted("b"), reviewable)).isNull();
        assertThat(proof(staging("b"), ADMITTED_AT, null, reviewable)).isNull();
        assertThat(proof(staging("b"), ADMITTED_AT, admitted("b").put("title", 2), reviewable))
                .isNull();
        assertThat(proof(staging("b"), ADMITTED_AT, admitted("b").put("body", 2), reviewable))
                .isNull();
        assertThat(proof(staging("b"), ADMITTED_AT, admitted("b").put("commit_sha", "c".repeat(40)), reviewable))
                .isNull();
        ObjectNode withoutBody = admitted("b");
        withoutBody.remove("body");
        assertThat(proof(staging("b"), ADMITTED_AT, withoutBody, reviewable)).isNull();
    }

    @Test
    void shouldNotMarkACaptureAnAutomatedReviewMayNotUse() {
        assertThat(proof(staging("b"), ADMITTED_AT, admitted("b"), null)).isNull();
        assertThat(proof(staging("b"), ADMITTED_AT, admitted("b"), catalogs(false, true)))
                .isNull();
        assertThat(proof(staging("b"), ADMITTED_AT, admitted("b"), catalogs(true, false)))
                .isNull();
    }

    @Test
    void shouldNotMarkACaptureWithoutAPinnedChange() {
        byte[] manifest = mapper.writeValueAsBytes(ReviewedWorkFixtures.pullRequestManifest(CAPTURED_AT, "b", null));

        ReviewedWork work = ReviewedWork.captured(
                        manifest,
                        Map.of(METADATA, mapper.writeValueAsBytes(staging("b"))),
                        42,
                        mapper,
                        new ReviewedWork.AdmissionBasis(ADMITTED_AT, admitted("b")),
                        reviewable)
                .orElseThrow();

        assertThat(work.head()).isNull();
        assertThat(work.retainedBasis()).isNull();
    }

    @Test
    void shouldNotMarkAnIssueCapture() {
        byte[] manifest = mapper.writeValueAsBytes(ReviewedWorkFixtures.issueManifest(CAPTURED_AT, "b"));

        ReviewedWork work = ReviewedWork.captured(
                        manifest,
                        Map.of(METADATA, mapper.writeValueAsBytes(staging("b"))),
                        7,
                        mapper,
                        new ReviewedWork.AdmissionBasis(ADMITTED_AT, admitted("b")),
                        reviewable)
                .orElseThrow();

        assertThat(work.retainedBasis()).isNull();
    }

    @Test
    void shouldReadAnUnknownStoredMarkerAsNoMarker() {
        ReviewedWork stored = mapper.readValue(
                "{\"artifactKind\":\"" + ArtifactKinds.PULL_REQUEST.value() + "\",\"artifactId\":42,"
                        + "\"titleAndDescriptionRevision\":\""
                        + ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, "t", null)
                        + "\",\"head\":\"" + HEAD + "\",\"capturedAt\":\"2026-09-30T00:05:00Z\","
                        + "\"retainedBasis\":\"COMPLETION\"}",
                ReviewedWork.class);

        assertThat(stored.retainedBasis()).isNull();
        assertThat(mapper.valueToTree(stored).has("retainedBasis")).isFalse();
    }

    @Test
    void shouldFrameAMissingDescriptionAsEachCaptureStagesIt() {
        assertThat(ReviewedWork.revision(ArtifactKinds.ISSUE, "Plan", null))
                .isEqualTo(ReviewedWork.revision(ArtifactKinds.ISSUE, "Plan", ""));
        assertThat(ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, "Plan", null))
                .isNotEqualTo(ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, "Plan", ""));
    }
}
