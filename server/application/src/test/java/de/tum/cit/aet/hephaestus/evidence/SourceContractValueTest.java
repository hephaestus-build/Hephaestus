package de.tum.cit.aet.hephaestus.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class SourceContractValueTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void shouldSerializeSourceIdentifiersAsStrings() throws Exception {
        SourceKind kind = new SourceKind("scm.pull-request.diff");

        String json = objectMapper.writeValueAsString(kind);

        assertThat(json).isEqualTo("\"scm.pull-request.diff\"");
        assertThat(objectMapper.readValue(json, SourceKind.class)).isEqualTo(kind);
    }

    @Test
    void shouldRejectInvalidIdentifiersAndVersions() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceKind("SCM_DIFF"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceContractVersion("1"));
    }

    @Test
    void shouldRoundTripCaptureFactsWithUnknownWatermark() throws Exception {
        Instant capturedAt = Instant.parse("2026-08-03T10:00:00Z");
        SourceCaptureFacts unknownWatermark = new SourceCaptureFacts(capturedAt, null, null, null);
        SourceCaptureFacts observedAtCapture = new SourceCaptureFacts(capturedAt, null, capturedAt, null);

        String json = objectMapper.writeValueAsString(unknownWatermark);

        assertThat(objectMapper.readValue(json, SourceCaptureFacts.class)).isEqualTo(unknownWatermark);
        assertThat(json).contains("\"sourceEffectiveAt\":null", "\"observedAt\":null", "\"immutableIdentity\":null");
        assertThat(unknownWatermark).isNotEqualTo(observedAtCapture);
    }

    @Test
    void shouldForbidArtifactsOnUnavailableCapture() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SourceCapture(
                        new SourceKind("scm.pull-request.diff"),
                        new SourceCaptureState.Unavailable(SourceAbsenceReason.NO_WORKING_COPY),
                        List.of(new SourceArtifact("inputs/context/diff.patch", "text/x-diff", "a".repeat(64), 1))));
    }

    @Test
    void shouldRejectLegacyContextIndexesAsArtifactSourceManifests() {
        String legacy = """
            {"schemaVersion":1,"entries":[]}
            """;

        assertThatThrownBy(() -> objectMapper.readValue(legacy, ArtifactSourceManifest.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void aMirrorCannotPinAnIdentityAndALossyDerivationCannotReportComplete() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> source(SourceAuthority.SYNCHRONIZED_MIRROR, IdentityMode.PINNED_IDENTITY, false))
                .withMessageContaining("can pin an identity");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> source(SourceAuthority.LOSSY_DERIVATION, IdentityMode.NOT_APPLICABLE, true))
                .withMessageContaining("cannot report COMPLETE");

        assertThat(source(SourceAuthority.UPSTREAM_SNAPSHOT, IdentityMode.PINNED_IDENTITY, true))
                .isNotNull();
        assertThat(source(SourceAuthority.LOSSY_DERIVATION, IdentityMode.NOT_APPLICABLE, false))
                .isNotNull();
    }

    /**
     * A demand the source can never meet is a refusal written as a requirement: every review that
     * required the source would be refused forever, for a reason no operator could act on.
     */
    @Test
    void aSourceCannotDemandAQualityItCanNeverReport() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> source(RequiredCaptureQuality.COMPLETE, false, true))
                .withMessageContaining("cannot report COMPLETE cannot demand it");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> source(RequiredCaptureQuality.COMPLETE_AND_NON_EMPTY, true, false))
                .withMessageContaining("cannot demand non-emptiness");

        assertThat(source(RequiredCaptureQuality.ANY_CAPTURE, false, false)).isNotNull();
        assertThat(source(RequiredCaptureQuality.COMPLETE_AND_NON_EMPTY, true, true))
                .isNotNull();
    }

    private static ArtifactSourceContract source(
            RequiredCaptureQuality requiredQuality, boolean supportsComplete, boolean supportsEmpty) {
        return new ArtifactSourceContract(
                new SourceKind("scm.example.source"),
                "Example",
                "An example source.",
                "Everything in scope.",
                Set.of("scm.pull_request"),
                true,
                supportsComplete ? SourceAuthority.SYNCHRONIZED_MIRROR : SourceAuthority.LOSSY_DERIVATION,
                new IdentityPolicy(IdentityMode.NOT_APPLICABLE),
                new CompletenessPolicy(supportsComplete, true, supportsEmpty),
                requiredQuality,
                PrivacyClass.INTERNAL,
                Set.of(SourceAbsenceState.NOT_COLLECTED),
                RetentionPolicy.AGENT_EVIDENCE_RETENTION,
                ErasurePolicy.WORKSPACE_AND_PERSON_ERASURE,
                Set.of("use-example"));
    }

    private static ArtifactSourceContract source(
            SourceAuthority authority, IdentityMode freshness, boolean supportsComplete) {
        return new ArtifactSourceContract(
                new SourceKind("scm.example.source"),
                "Example",
                "An example source.",
                "Everything in scope.",
                Set.of("scm.pull_request"),
                true,
                authority,
                new IdentityPolicy(freshness),
                new CompletenessPolicy(supportsComplete, true, true),
                RequiredCaptureQuality.ANY_CAPTURE,
                PrivacyClass.INTERNAL,
                Set.of(SourceAbsenceState.NOT_COLLECTED),
                RetentionPolicy.AGENT_EVIDENCE_RETENTION,
                ErasurePolicy.WORKSPACE_AND_PERSON_ERASURE,
                Set.of("use-example"));
    }

    @Test
    void shouldRejectEmptyManifestAndReadinessCollections() {
        Instant now = Instant.parse("2026-08-03T10:00:00Z");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ArtifactSourceManifest(
                        new SourceContractVersion("1.1.0"), "a".repeat(64), "scm.pull_request", now, List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new AutomatedReviewReadinessDecision("review-quality", now, true, List.of(), List.of()));
        assertThat(new AutomatedReviewReadinessDecision(
                                "no-automated-review",
                                now,
                                false,
                                List.of(AutomatedReviewReadinessReason.NO_AUTOMATED_REVIEW),
                                List.of())
                        .sourceChecks())
                .isEmpty();
    }

    @Test
    void shouldEnforceAssessmentReasonCodeSemantics() {
        Instant now = Instant.parse("2026-08-03T10:00:00Z");
        SourceKind kind = new SourceKind("scm.pull-request.diff");
        SourceContractVersion version = new SourceContractVersion("1.1.0");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SourceReadinessCheck(
                        kind, version, now, now, true, List.of(SourceReadinessReason.SOURCE_INCOMPLETE)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SourceReadinessCheck(
                        kind,
                        version,
                        now,
                        now,
                        false,
                        List.of(SourceReadinessReason.SOURCE_INCOMPLETE, SourceReadinessReason.SOURCE_INCOMPLETE)));
    }

    @Test
    void shouldRejectNonCanonicalArtifactPaths() {
        for (String path : List.of("foo/..", "foo/../bar", "./foo", "foo\\bar")) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SourceArtifact(path, "application/json", "a".repeat(64), 1));
        }
    }

    @Test
    void shouldRejectDuplicateArtifactPathsInACapture() {
        SourceArtifact first = new SourceArtifact("context.json", "application/json", "a".repeat(64), 1);
        SourceArtifact second = new SourceArtifact("context.json", "application/json", "b".repeat(64), 2);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SourceCapture(
                        new SourceKind("scm.pull-request.core"),
                        new SourceCaptureState.Available(
                                SourceContentState.NON_EMPTY,
                                SourceCompleteness.COMPLETE,
                                new SourceCaptureFacts(Instant.parse("2026-08-03T10:00:00Z"), null, null, null)),
                        List.of(first, second)));
    }
}
