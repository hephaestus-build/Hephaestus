package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.conversation.ConversationSourceLiveness;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.fabric.ContentAddressedStore;
import de.tum.cit.aet.hephaestus.integration.scm.ReviewTargetQuery;
import de.tum.cit.aet.hephaestus.practices.EvidenceStance;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeDetectionCompletedEvent;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class PracticeDetectionDeliveryServiceTest extends BaseUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository practiceRevisionRepository;

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private ReviewTargetQuery reviewTargets;

    @Mock
    private ConversationSourceLiveness conversationSourceLiveness;

    @Mock
    private de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection documentProjection;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ContentAddressedStore cas;

    @Mock
    private de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry sourceCatalogs;

    @Captor
    private ArgumentCaptor<PracticeDetectionCompletedEvent> eventCaptor;

    private PracticeDetectionDeliveryService service;

    private Practice testPractice;
    private AgentJob testJob;

    @BeforeEach
    void setUp() {
        service = new PracticeDetectionDeliveryService(
                practiceRevisionRepository,
                observationRepository,
                reviewTargets,
                conversationSourceLiveness,
                documentProjection,
                eventPublisher,
                objectMapper,
                cas,
                sourceCatalogs);

        lenient().when(sourceCatalogs.isSourceUsePermitted(any(), any(), any())).thenReturn(true);

        Workspace workspace = new Workspace();
        ReflectionTestUtils.setField(workspace, "id", 1L);

        testPractice = new Practice();
        ReflectionTestUtils.setField(testPractice, "id", 10L);
        testPractice.setSlug("pr-description-quality");
        testPractice.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST));
        testPractice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
        testPractice.setWorkspace(workspace);

        testJob = new AgentJob();
        ReflectionTestUtils.setField(testJob, "id", UUID.randomUUID());
        testJob.setWorkspace(workspace);
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("pull_request_id", 456L);
        metadata.put("repository_id", 123L);
        metadata.put("repository_full_name", "owner/repo");
        metadata.put("pr_number", 42);
        testJob.setMetadata(metadata);
        ObjectNode snapshot = objectMapper.createObjectNode();
        var sources =
                snapshot.putObject("manifest").put("contractVersion", "1.0.0").putArray("sources");
        var source = sources.addObject().put("kind", "scm.pull-request.diff");
        source.putObject("state").put("availability", "AVAILABLE").put("content", "NON_EMPTY");
        source.putArray("artifacts")
                .addObject()
                .put("path", "inputs/context/diff.patch")
                .put("sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        var coreSource = sources.addObject().put("kind", "scm.pull-request.core");
        coreSource.putObject("state").put("availability", "AVAILABLE").put("content", "NON_EMPTY");
        coreSource
                .putArray("artifacts")
                .addObject()
                .put("path", "inputs/context/pull_request.json")
                .put("sha256", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        snapshot.putArray("practices")
                .addObject()
                .put("slug", "pr-description-quality")
                .put("revisionId", 11L);
        testJob.setEvidenceSnapshot(snapshot);

        PracticeRevision revision = org.mockito.Mockito.mock(PracticeRevision.class);
        lenient().when(revision.getId()).thenReturn(11L);
        lenient().when(revision.getSlug()).thenReturn("pr-description-quality");
        lenient().when(revision.getPractice()).thenReturn(testPractice);
        lenient().when(revision.getAutomatedReviewPolicy()).thenReturn(testPractice.getAutomatedReviewPolicy());
        // Bindings decide what this practice may assert an ABSENCE over; every source that applies to the
        // artifact is staged for citation regardless.
        lenient().when(revision.getBindings()).thenReturn(testPractice.getBindings());
        lenient().when(practiceRevisionRepository.findById(11L)).thenReturn(Optional.of(revision));
        lenient()
                .when(cas.get("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(Optional.of(
                        "diff --git a/src/Auth.java b/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10 @@\n[L10] + insecure();\n"
                                .getBytes(StandardCharsets.UTF_8)));

        lenient()
                .when(reviewTargets.findPullRequest(456L))
                .thenReturn(Optional.of(new ReviewTargetQuery.Target(123L, "owner/repo", 42, 789L, false)));
        lenient()
                .when(observationRepository.insertIfAbsent(
                        any(),
                        anyString(),
                        any(),
                        anyLong(),
                        anyLong(),
                        any(), // practiceRevisionId
                        anyString(),
                        anyLong(),
                        anyLong(),
                        any(),
                        anyString(),
                        any(),
                        any(), // assessment — null for NOT_APPLICABLE, so any() (anyString() would not match null)
                        any(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        anyString()))
                .thenReturn(1);
    }

    private ValidatedObservation validObservation(String slug, @Nullable Presence presence) {
        Assessment assessment =
                switch (presence) {
                    case PRESENT -> Assessment.GOOD;
                    case ABSENT -> Assessment.BAD;
                    case null -> null;
                };
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.putArray("citations")
                .addObject()
                .put("sourceKind", "scm.pull-request.diff")
                .put("artifactPath", "inputs/context/diff.patch")
                .put("path", "src/Auth.java")
                .put("side", "NEW")
                .put("startLine", 10)
                .put("endLine", 10)
                .put("quote", "+ insecure();");
        // An ABSENT observation asserts a universal, so delivery requires it to say where it looked.
        if (presence == Presence.ABSENT) {
            ObjectNode search = evidence.putObject("search");
            search.putArray("consulted").add("scm.pull-request.diff");
            search.put("lookedFor", "a described rationale for the change");
            search.put("boundary", "the diff of this pull request only");
        }
        // A NOT_APPLICABLE observation asserts something about the work too — that this practice has no
        // subject in it — so delivery requires it to name what the practice looks for and what rules it out.
        if (presence == null) {
            ObjectNode inapplicability = evidence.putObject("inapplicability");
            inapplicability.putArray("consulted").add("scm.pull-request.diff");
            inapplicability.put("subject", "a described rationale for the change");
            inapplicability.put("ruledOutBy", "the change is a generated lockfile update with no prose to judge");
        }
        return new ValidatedObservation(
                slug,
                "Test observation",
                presence == null ? AssessmentStatus.NOT_APPLICABLE : AssessmentStatus.ASSESSED,
                presence,
                assessment,
                assessment == Assessment.BAD ? Severity.MINOR : null,
                evidence,
                null);
    }

    @Test
    void shouldRequireAnUnresolvedQuestionForUndeterminedObservations() {
        var base = validObservation("pr-description-quality", null);
        ObjectNode evidence = (ObjectNode) evidenceOf(base);
        evidence.remove("inapplicability");
        var observation = new ValidatedObservation(
                base.practiceSlug(),
                base.summary(),
                AssessmentStatus.UNDETERMINED,
                null,
                null,
                null,
                evidence,
                "The captured evidence does not settle the criterion.");
        assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessageContaining("open question");
        evidence.putObject("undecidability")
                .put("openQuestion", "Does the criterion include this compatibility-only change?")
                .put("wouldSettleIt", "Clarification of the practice's scope.");
        assertThat(service.deliver(testJob, List.of(observation)).hasNegative()).isFalse();
    }

    private static JsonNode evidenceOf(ValidatedObservation observation) {
        JsonNode evidence = observation.evidence();
        assertThat(evidence).isNotNull();
        return evidence;
    }

    private void admit(Practice practice, long revisionId) {
        practice.setWorkspace(testPractice.getWorkspace());
        ((ObjectNode) testJob.getEvidenceSnapshot())
                .withArray("practices")
                .addObject()
                .put("slug", practice.getSlug())
                .put("revisionId", revisionId);
        PracticeRevision revision = org.mockito.Mockito.mock(PracticeRevision.class);
        lenient().when(revision.getId()).thenReturn(revisionId);
        lenient().when(revision.getSlug()).thenReturn(practice.getSlug());
        lenient().when(revision.getPractice()).thenReturn(practice);
        lenient().when(revision.getAutomatedReviewPolicy()).thenReturn(practice.getAutomatedReviewPolicy());
        lenient().when(revision.getBindings()).thenReturn(practice.getBindings());
        lenient().when(practiceRevisionRepository.findById(revisionId)).thenReturn(Optional.of(revision));
    }

    @Nested
    class EvidenceBoundary {

        @Test
        void rejectsMissingSourceAttribution() {
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.PRESENT);
            ((ObjectNode) evidenceOf(observation)).remove("citations");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("no source-bound evidence citation");
            verifyNoInteractions(observationRepository);
        }

        @Test
        void rejectsDiffCitationWithoutSide() {
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.PRESENT);
            ((ObjectNode) evidenceOf(observation).withArray("citations").get(0)).remove("side");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("invalid evidence citation");
            verifyNoInteractions(observationRepository);
        }

        @Test
        void rejectsNonDiffCitationWithSide() {
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.PRESENT);
            ObjectNode citation =
                    (ObjectNode) evidenceOf(observation).withArray("citations").get(0);
            citation.put("sourceKind", "scm.pull-request.core");
            citation.put("artifactPath", "inputs/context/pull_request.json");
            citation.put("path", "pull_request.json");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("invalid evidence citation");
            verifyNoInteractions(observationRepository);
        }

        @Test
        @DisplayName("a citation to a source this run did not stage is refused")
        void rejectsSourcesTheRunNeverStaged() {
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.PRESENT);
            ObjectNode citation =
                    (ObjectNode) evidenceOf(observation).withArray("citations").get(0);
            citation.put("sourceKind", "scm.repository.tree");
            citation.remove("side");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("misattributed evidence source");
            verifyNoInteractions(observationRepository);
        }

        /**
         * Every source that applies to the artifact is staged for every review, so a quote from one this
         * practice's bindings never named is still a quote from bytes that were really there — refusing it
         * would throw away an observation for being observant.
         */
        @Test
        @DisplayName("a citation to a staged source the practice's bindings did not name is accepted")
        void acceptsAStagedSourceOutsideThePracticeDeclaration() {
            var inventory = ((ObjectNode) testJob.getEvidenceSnapshot().path("manifest"))
                    .withArray("sources")
                    .addObject()
                    .put("kind", "workspace.project-inventory");
            inventory.putObject("state").put("availability", "AVAILABLE").put("content", "NON_EMPTY");
            inventory
                    .putArray("artifacts")
                    .addObject()
                    .put("path", "inputs/context/project_inventory.json")
                    .put("sha256", "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
            when(cas.get("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"))
                    .thenReturn(Optional.of("{\"issues\":[{\"number\":7,\"title\":\"Same migration\"}]}"
                            .getBytes(StandardCharsets.UTF_8)));
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.PRESENT);
            ObjectNode citation =
                    (ObjectNode) evidenceOf(observation).withArray("citations").get(0);
            citation.put("sourceKind", "workspace.project-inventory");
            citation.put("artifactPath", "inputs/context/project_inventory.json");
            citation.put("path", "project_inventory.json");
            citation.put("quote", "\"title\":\"Same migration\"");
            citation.remove("side");

            assertThat(service.deliver(testJob, List.of(observation)).inserted())
                    .isEqualTo(1);
        }

        @Test
        void rejectsASourceWithdrawnAfterCapture() {
            when(sourceCatalogs.isSourceUsePermitted(any(), any(), eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.deliver(
                            testJob, List.of(validObservation("pr-description-quality", Presence.PRESENT))))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("authorization was withdrawn");
            verifyNoInteractions(observationRepository);
            verify(sourceCatalogs).isSourceUsePermitted(any(), any(), eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY));
        }

        @Test
        void rejectsAnUncitedSourceWithdrawnAfterCapture() {
            when(sourceCatalogs.isSourceUsePermitted(any(), eq(new SourceKind("scm.pull-request.core")), any()))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.deliver(
                            testJob, List.of(validObservation("pr-description-quality", Presence.PRESENT))))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("scm.pull-request.core");
            verifyNoInteractions(observationRepository);
        }

        @Test
        void rejectsAQuoteThatIsNotInTheCitedArtifact() {
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.PRESENT);
            ((ObjectNode) ((ObjectNode) evidenceOf(observation))
                            .withArray("citations")
                            .get(0))
                    .put("quote", "fabricated quote");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("does not match the cited diff location");
            verifyNoInteractions(observationRepository);
        }

        @Test
        @DisplayName("only the claim whose quote does not verify is withheld; the other is delivered")
        void withholdsOnlyTheObservationWhoseQuoteDoesNotVerify() {
            Practice second = new Practice();
            ReflectionTestUtils.setField(second, "id", 20L);
            second.setSlug("pr-scope");
            second.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST));
            second.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
            admit(second, 21L);

            ValidatedObservation sound = validObservation("pr-description-quality", Presence.PRESENT);
            ValidatedObservation misquoted = validObservation("pr-scope", Presence.PRESENT);
            ((ObjectNode) evidenceOf(misquoted).withArray("citations").get(0)).put("quote", "+ insecure();,");

            var result = service.deliver(testJob, List.of(sound, misquoted));

            assertThat(result.delivered())
                    .as("the claim that verified is the one persisted, and it is the only one")
                    .extracting(ValidatedObservation::practiceSlug)
                    .containsExactly("pr-description-quality");
            assertThat(result.inserted()).isEqualTo(1);
        }

        @Test
        @DisplayName("a citation to an unstaged source still fails the whole delivery, even beside a sound claim")
        void anEvidenceFailureThatIsNotAQuoteMismatchStillFailsEverything() {
            Practice second = new Practice();
            ReflectionTestUtils.setField(second, "id", 20L);
            second.setSlug("pr-scope");
            second.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST));
            second.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
            admit(second, 21L);

            ValidatedObservation sound = validObservation("pr-description-quality", Presence.PRESENT);
            ValidatedObservation unstaged = validObservation("pr-scope", Presence.PRESENT);
            ObjectNode citation =
                    (ObjectNode) evidenceOf(unstaged).withArray("citations").get(0);
            citation.put("sourceKind", "scm.repository.tree");
            citation.remove("side");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(sound, unstaged)))
                    .as("an unstaged source impugns the run, not just the claim that cited it")
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("misattributed evidence source");
            verifyNoInteractions(observationRepository);
        }

        @Test
        @DisplayName("a batch in which no quote verifies is refused rather than retried as an unavailable server")
        void shouldRefuseObservationsWhenNoQuotedEvidenceVerifies() {
            ValidatedObservation misquoted = validObservation("pr-description-quality", Presence.PRESENT);
            ((ObjectNode) evidenceOf(misquoted).withArray("citations").get(0)).put("quote", "fabricated quote");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(misquoted)))
                    .isInstanceOfSatisfying(
                            ObservationsRefusedException.class,
                            refusal -> assertThat(refusal.reasonCode()).isEqualTo("no_valid_observations"))
                    .hasMessageContaining("No observation survived the evidence check");
            verifyNoInteractions(observationRepository);
        }

        @Test
        void acceptsASecretScannerCitationWithoutPersistingTheSecret() {
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.PRESENT);
            ObjectNode evidence = (ObjectNode) evidenceOf(observation);
            evidence.put("detector", "secret-diff-scanner");
            ObjectNode citation = (ObjectNode) evidence.withArray("citations").get(0);
            citation.remove("quote");
            citation.put("quoteSha256", "cbbe06955840924d2ccb449029560ae1eb92f5ec9866804f1a34be23b61dc488");

            assertThat(service.deliver(testJob, List.of(observation)).inserted())
                    .isEqualTo(1);
            ArgumentCaptor<String> persistedEvidence = ArgumentCaptor.forClass(String.class);
            verify(observationRepository)
                    .insertIfAbsent(
                            any(),
                            anyString(),
                            any(),
                            anyLong(),
                            anyLong(),
                            any(),
                            anyString(),
                            anyLong(),
                            anyLong(),
                            any(),
                            anyString(),
                            any(),
                            any(),
                            any(),
                            persistedEvidence.capture(),
                            any(),
                            anyString(),
                            any(),
                            anyString());
            assertThat(persistedEvidence.getValue()).doesNotContain("quoteSha256", "cbbe06955840924d");
        }

        @Test
        void rejectsAFabricatedSecretScannerDigest() {
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.PRESENT);
            ObjectNode evidence = (ObjectNode) evidenceOf(observation);
            evidence.put("detector", "secret-diff-scanner");
            ObjectNode citation = (ObjectNode) evidence.withArray("citations").get(0);
            citation.remove("quote");
            citation.put("quoteSha256", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("does not match the cited diff location");
            verifyNoInteractions(observationRepository);
        }

        @Test
        void rejectsARealQuoteAtTheWrongDiffLine() {
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.PRESENT);
            ((ObjectNode) evidenceOf(observation).withArray("citations").get(0)).put("startLine", 11);
            ((ObjectNode) evidenceOf(observation).withArray("citations").get(0)).put("endLine", 11);

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("does not match the cited diff location");
            verifyNoInteractions(observationRepository);
        }

        @Test
        void rejectsARealQuoteInTheWrongDiffFile() {
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.PRESENT);
            ((ObjectNode) evidenceOf(observation).withArray("citations").get(0)).put("path", "src/Other.java");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("does not match the cited diff location");
            verifyNoInteractions(observationRepository);
        }

        @Test
        void rejectsARealQuoteWithAnInvalidDiffRange() {
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.PRESENT);
            ((ObjectNode) evidenceOf(observation).withArray("citations").get(0)).put("endLine", 11);

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("does not match the cited diff location");
            verifyNoInteractions(observationRepository);
        }

        @Test
        void acceptsRemovedLineEvidenceOnTheOldSide() {
            when(cas.get("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                    .thenReturn(Optional.of(("diff --git a/src/Auth.java b/src/Auth.java\n" + "--- a/src/Auth.java\n"
                                    + "+++ b/src/Auth.java\n"
                                    + "@@ -8 +8 @@\n"
                                    + "[L8] - requireAdmin();\n"
                                    + "[L8] + allowAll();\n")
                            .getBytes(StandardCharsets.UTF_8)));
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.PRESENT);
            ObjectNode citation =
                    (ObjectNode) evidenceOf(observation).withArray("citations").get(0);
            citation.put("side", "OLD");
            citation.put("startLine", 8);
            citation.put("endLine", 8);
            citation.put("quote", "- requireAdmin();");

            assertThat(service.deliver(testJob, List.of(observation)).inserted())
                    .isEqualTo(1);
        }

        @Test
        void rejectsACitationToAnUnavailableSource() {
            ((ObjectNode) testJob.getEvidenceSnapshot()
                            .path("manifest")
                            .path("sources")
                            .get(0)
                            .path("state"))
                    .put("availability", "UNAVAILABLE");

            assertThatThrownBy(() -> service.deliver(
                            testJob, List.of(validObservation("pr-description-quality", Presence.PRESENT))))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("misattributed evidence source");
            verifyNoInteractions(observationRepository);
        }
    }

    /**
     * An ABSENT observation is a universal claim, and the delivery boundary has to earn it too — not just
     * the in-sandbox normalizer, which a crashed runner or a rescued text payload can bypass.
     */
    @Nested
    class RecordedSearch {

        @Test
        @DisplayName("an ABSENT observation with no recorded search is refused")
        void rejectsAbsentWithoutASearch() {
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.ABSENT);
            ((ObjectNode) evidenceOf(observation)).remove("search");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("must record where it searched");
            verifyNoInteractions(observationRepository);
        }

        @Test
        @DisplayName("a recorded search missing any of its three parts is refused")
        void rejectsAnIncompleteSearch() {
            for (String field : new String[] {"consulted", "lookedFor", "boundary"}) {
                ValidatedObservation observation = validObservation("pr-description-quality", Presence.ABSENT);
                ((ObjectNode) evidenceOf(observation).get("search")).remove(field);

                assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                        .as("an ABSENT observation missing search.%s", field)
                        .isInstanceOf(JobDeliveryException.class)
                        .hasMessageContaining("must record where it searched");
            }
            verifyNoInteractions(observationRepository);
        }

        @Test
        @DisplayName("a search claiming a source this run never staged is refused")
        void rejectsASearchOutsideTheBoundary() {
            // The absence-shaped twin of citing evidence we never had: the source was not staged, so it
            // cannot have been searched, and the claim of having searched it is unfalsifiable otherwise.
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.ABSENT);
            ObjectNode search = (ObjectNode) evidenceOf(observation).get("search");
            search.putArray("consulted").add("scm.repository.tree");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("claims a source this run did not stage");
            verifyNoInteractions(observationRepository);
        }

        @Test
        @DisplayName("an ABSENT observation that recorded its search is delivered")
        void acceptsAnAbsentWithARecordedSearch() {
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.ABSENT);

            var result = service.deliver(testJob, List.of(observation));

            assertThat(result.inserted()).isEqualTo(1);
        }

        @Test
        @DisplayName("ABSENT + GOOD is refused for a practice that bounded no corpus, and points at INCONCLUSIVE")
        void rejectsACleanStrengthFromAnUnboundedPractice() {
            // The asymmetry the whole rule rests on. An ABSENT + BAD is anchored to the locus its citation
            // points at, so it holds over that locus. An ABSENT + GOOD says the harmful behaviour is NOWHERE in
            // the work — a universal over the whole corpus, which a practice that declared nothing EXHAUSTIVE
            // has not closed and therefore cannot assert. The default bindings here are all REQUIRED.
            ValidatedObservation observation = cleanStrength("pr-description-quality");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("declares no EXHAUSTIVE evidence source");
            verifyNoInteractions(observationRepository);
        }

        @Test
        @DisplayName("ABSENT + GOOD is delivered once the practice declares the corpus it searched exhaustive")
        void acceptsACleanStrengthOverABoundedCorpus() {
            // This is the verdict the eight defect detectors could not reach, and the reason they could not was
            // never the practice — it was that nothing had bounded the corpus. Bound it and the negative is
            // provable on exactly the evidence an ABSENT already owes.
            exhaustiveOverTheDiff(testPractice);
            ValidatedObservation observation = cleanStrength("pr-description-quality");

            var result = service.deliver(testJob, List.of(observation));

            assertThat(result.inserted()).isEqualTo(1);
        }

        @Test
        @DisplayName("a bounded corpus still has to have been searched whole")
        void stillRejectsAPartialSearchBehindACleanStrength() {
            // Declaring the stance is what makes the claim admissible, not what makes it true: the search must
            // still cover every source held exhaustive, or the strength is a universal over unread bytes.
            exhaustiveOverTheDiff(testPractice);
            ValidatedObservation observation = cleanStrength("pr-description-quality");
            ((ObjectNode) evidenceOf(observation).get("search"))
                    .putArray("consulted")
                    .add("scm.pull-request.core");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("did not search the sources its practice asserts absence over");
            verifyNoInteractions(observationRepository);
        }

        /** An ABSENT + GOOD: the practice's defect was looked for over the diff and is not there. */
        private ValidatedObservation cleanStrength(String slug) {
            ValidatedObservation gap = validObservation(slug, Presence.ABSENT);
            return new ValidatedObservation(
                    gap.practiceSlug(),
                    gap.summary(),
                    AssessmentStatus.ASSESSED,
                    Presence.ABSENT,
                    Assessment.GOOD,
                    null,
                    gap.evidence(),
                    gap.evidenceRationale());
        }

        private void exhaustiveOverTheDiff(Practice practice) {
            List<PracticeBinding> bindings = practice.getBindings().stream()
                    .map(binding -> new PracticeBinding(
                            binding.signals(),
                            binding.needs().stream()
                                    .map(need -> need.sourceKind().value().equals("scm.pull-request.diff")
                                            ? new PracticeEvidenceRequirement(
                                                    need.sourceKind(), EvidenceStance.EXHAUSTIVE)
                                            : need)
                                    .toList(),
                            binding.onDrafts(),
                            binding.subject()))
                    .toList();
            practice.setBindings(bindings);
            PracticeRevision revision = practiceRevisionRepository.findById(11L).orElseThrow();
            lenient().when(revision.getBindings()).thenReturn(bindings);
        }

        @Test
        @DisplayName("a NOT_APPLICABLE observation with no stated ground is refused")
        void rejectsAnUnjustifiedNotApplicable() {
            // Sandbox output is untrusted even when its normalizer enforces the same rule.
            ValidatedObservation observation = validObservation("pr-description-quality", null);
            ((ObjectNode) evidenceOf(observation)).remove("inapplicability");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("must name what the practice looks for")
                    // Direct the model to uncertainty rather than an invented justification.
                    .hasMessageContaining("UNDETERMINED");
            verifyNoInteractions(observationRepository);
        }

        @Test
        @DisplayName("a stated inapplicability missing any of its three parts is refused")
        void rejectsAnIncompleteInapplicability() {
            for (String field : new String[] {"consulted", "subject", "ruledOutBy"}) {
                ValidatedObservation observation = validObservation("pr-description-quality", null);
                ((ObjectNode) evidenceOf(observation).get("inapplicability")).remove(field);

                assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                        .as("a NOT_APPLICABLE observation missing inapplicability.%s", field)
                        .isInstanceOf(JobDeliveryException.class)
                        .hasMessageContaining("must name what the practice looks for");
            }
            verifyNoInteractions(observationRepository);
        }

        @Test
        @DisplayName("a stated inapplicability claiming a source this run never staged is refused")
        void rejectsAnInapplicabilityOutsideTheBoundary() {
            ValidatedObservation observation = validObservation("pr-description-quality", null);
            ObjectNode inapplicability = (ObjectNode) evidenceOf(observation).get("inapplicability");
            inapplicability.putArray("consulted").add("scm.repository.tree");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("claims a source this run did not stage");
            verifyNoInteractions(observationRepository);
        }

        @Test
        @DisplayName("a ground is asked of NOT_APPLICABLE alone — INCONCLUSIVE claims nothing about the work")
        void doesNotAskForAGroundOnOtherPresences() {
            for (Presence presence : Presence.values()) {
                if (presence == null) {
                    continue;
                }
                ValidatedObservation observation = validObservation("pr-description-quality", presence);
                assertThat(evidenceOf(observation).get("inapplicability"))
                        .as("%s carries no stated inapplicability", presence)
                        .isNull();

                assertThatCode(() -> service.deliver(testJob, List.of(observation)))
                        .as("%s is delivered without a stated inapplicability", presence)
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("a search is asked of ABSENT alone — the other presences assert no universal")
        void doesNotAskForASearchOnOtherPresences() {
            for (Presence presence : Presence.values()) {
                if (presence == Presence.ABSENT) {
                    continue;
                }
                ValidatedObservation observation = validObservation("pr-description-quality", presence);
                assertThat(evidenceOf(observation).get("search"))
                        .as("%s carries no search", presence)
                        .isNull();

                assertThatCode(() -> service.deliver(testJob, List.of(observation)))
                        .as("%s is delivered without a recorded search", presence)
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("a citation to the review history is in bounds although no binding declared it")
        void acceptsACitationToTheStagedHistory() {
            stageHistory("we raised this in the last review");
            ValidatedObservation observation = historyCiting("we raised this in the last review");

            var result = service.deliver(testJob, List.of(observation));

            assertThat(result.inserted()).isEqualTo(1);
        }

        @Test
        @DisplayName("a fabricated quote from the review history is refused like any other")
        void rejectsAnInventedPastObservation() {
            stageHistory("we raised this in the last review");
            ValidatedObservation observation = historyCiting("we raised this three times before");

            assertThatThrownBy(() -> service.deliver(testJob, List.of(observation)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("quote");
            verifyNoInteractions(observationRepository);
        }

        private static final String HISTORY_SHA = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

        private void stageHistory(String body) {
            var source = ((ObjectNode) testJob.getEvidenceSnapshot().path("manifest"))
                    .withArray("sources")
                    .addObject()
                    .put("kind", "hephaestus.observation-history");
            source.putObject("state").put("availability", "AVAILABLE").put("content", "NON_EMPTY");
            source.putArray("artifacts")
                    .addObject()
                    .put("path", "inputs/history/observations.json")
                    .put("sha256", HISTORY_SHA);
            when(cas.get(HISTORY_SHA)).thenReturn(Optional.of(body.getBytes(StandardCharsets.UTF_8)));
        }

        private ValidatedObservation historyCiting(String quote) {
            ValidatedObservation observation = validObservation("pr-description-quality", Presence.PRESENT);
            ObjectNode citation =
                    (ObjectNode) evidenceOf(observation).withArray("citations").get(0);
            citation.put("sourceKind", "hephaestus.observation-history");
            citation.put("artifactPath", "inputs/history/observations.json");
            citation.put("path", "inputs/history/observations.json");
            citation.remove("side");
            citation.put("quote", quote);
            return observation;
        }
    }

    @Nested
    class HappyPath {

        @Test
        void persistsValidObservation() {
            var observations = List.of(validObservation("pr-description-quality", Presence.PRESENT));

            var result = service.deliver(testJob, observations);

            assertThat(result.inserted()).isEqualTo(1);
            assertThat(result.discardedDuplicate()).isZero();

            ArgumentCaptor<String> fingerprintCaptor = ArgumentCaptor.forClass(String.class);
            verify(observationRepository)
                    .insertIfAbsent(
                            any(UUID.class),
                            eq("pr-description-quality:0:scm.pull_request:456:" + testJob.getId()),
                            eq(testJob.getId()),
                            anyLong(),
                            eq(10L),
                            eq(11L),
                            eq("scm.pull_request"),
                            eq(456L),
                            eq(789L), // aboutUserId
                            eq("Test observation"),
                            anyString(),
                            eq("PRESENT"), // presence
                            eq("GOOD"), // assessment
                            isNull(), // severity
                            anyString(),
                            isNull(),
                            fingerprintCaptor.capture(), // recurrence key
                            any(),
                            eq("LIVE") // an event-triggered review is the unbiased population
                            );

            // The recurrence_key written to the row MUST equal the fingerprint the result map returns —
            // they are the single supersession identity, so any drift between them silently breaks re-review.
            var keys = result.delivered().get(0).keys();
            assertThat(keys).isNotNull();
            assertThat(fingerprintCaptor.getValue())
                    .as("persisted recurrence_key matches the returned findingFingerprint")
                    .matches("[0-9a-f]{64}")
                    .isEqualTo(keys.recurrenceKey());

            verify(eventPublisher).publishEvent(eventCaptor.capture());
            PracticeDetectionCompletedEvent event = eventCaptor.getValue();
            assertThat(event.agentJobId()).isEqualTo(testJob.getId());
            assertThat(event.workspaceId()).isEqualTo(1L);
            assertThat(event.observationsInserted()).isEqualTo(1);
            assertThat(event.observationsDiscarded()).isZero();
            assertThat(event.hasNegative()).isFalse();
        }
    }

    @Nested
    class PracticeResolution {

        @Test
        void unknownSlug() {
            var observations = List.of(validObservation("unknown-practice", Presence.PRESENT));

            assertThatThrownBy(() -> service.deliver(testJob, observations))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("not admitted");
            verifyNoInteractions(observationRepository);
        }
    }

    @Nested
    class TargetResolution {

        @Test
        void shouldResolveReviewerWhenSubmittedReviewMatchesArtifactAndSubject() {
            when(reviewTargets.reviewMatchesTarget(77L, 456L, 999L)).thenReturn(true);
            ObjectNode metadata =
                    org.junit.jupiter.api.Assertions.assertInstanceOf(ObjectNode.class, testJob.getMetadata());
            metadata.put("review_id", 77L);
            metadata.put("about_user_id", 999L);
            metadata.put("subject_role", "REVIEWER");

            service.deliver(testJob, List.of());

            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().developerId()).isEqualTo(999L);
            assertThat(eventCaptor.getValue().artifactId()).isEqualTo(456L);
        }

        @Test
        void shouldRejectReviewerWhenSubmittedReviewDoesNotMatchSubject() {
            ObjectNode metadata =
                    org.junit.jupiter.api.Assertions.assertInstanceOf(ObjectNode.class, testJob.getMetadata());
            metadata.put("review_id", 77L);
            metadata.put("about_user_id", 999L);
            metadata.put("subject_role", "REVIEWER");

            assertThatThrownBy(() -> service.deliver(testJob, List.of()))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("no longer matches");
            verifyNoInteractions(observationRepository, eventPublisher);
        }

        @Test
        @DisplayName("throws when pull request not found")
        void prNotFound() {
            when(reviewTargets.findPullRequest(456L)).thenReturn(Optional.empty());
            var observations = List.of(validObservation("pr-description-quality", Presence.PRESENT));

            assertThatThrownBy(() -> service.deliver(testJob, observations))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("Pull request not found");
        }

        @Test
        @DisplayName("throws when pull request has no author")
        void prNoAuthor() {
            when(reviewTargets.findPullRequest(456L))
                    .thenReturn(Optional.of(new ReviewTargetQuery.Target(123L, "owner/repo", 42, null, false)));
            var observations = List.of(validObservation("pr-description-quality", Presence.PRESENT));

            assertThatThrownBy(() -> service.deliver(testJob, observations))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("no author");
        }

        @Test
        void mismatchedArtifactMetadataIsRejectedBeforePersistence() {
            ObjectNode metadata =
                    org.junit.jupiter.api.Assertions.assertInstanceOf(ObjectNode.class, testJob.getMetadata());
            metadata.put("repository_id", 999L);
            var observations = List.of(validObservation("pr-description-quality", Presence.PRESENT));

            assertThatThrownBy(() -> service.deliver(testJob, observations))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("does not match the live target");
            verifyNoInteractions(observationRepository, eventPublisher);
        }

        @Test
        void conversationTargetMustMatchTheLiveWorkspaceThreadAndParticipant() {
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("artifact_kind", ArtifactKinds.CONVERSATION_THREAD.value());
            metadata.put("slack_thread_id", 77L);
            metadata.put("slack_channel_id", "C123");
            metadata.put("slack_thread_ts", "1700000000.100000");
            metadata.put("about_user_id", 789L);
            testJob.setMetadata(metadata);

            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "resolveTarget", testJob, metadata))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("no longer authorized");
            verify(conversationSourceLiveness).isDeliverableThread(1L, 77L, "C123", "1700000000.100000", 789L);
        }
    }

    @Nested
    class MetadataValidation {

        @Test
        void nullMetadata() {
            testJob.setMetadata(null);
            var observations = List.of(validObservation("pr-description-quality", Presence.PRESENT));

            assertThatThrownBy(() -> service.deliver(testJob, observations))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("Missing job metadata");
        }

        @Test
        void missingPullRequestId() {
            testJob.setMetadata(objectMapper.createObjectNode());
            var observations = List.of(validObservation("pr-description-quality", Presence.PRESENT));

            assertThatThrownBy(() -> service.deliver(testJob, observations))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("Missing pull_request_id");
        }
    }

    @Nested
    class MultipleNegatives {

        @Test
        void persistsAllNegativesForPractice() {
            var observations = new java.util.ArrayList<ValidatedObservation>();
            for (int i = 0; i < 7; i++) {
                observations.add(validObservation("pr-description-quality", Presence.ABSENT));
            }

            var result = service.deliver(testJob, observations);

            assertThat(result.inserted()).isEqualTo(7);
            assertThat(result.discardedDuplicate()).isZero();
        }

        @Test
        void persistsManyPositiveObservations() {
            var observations = new java.util.ArrayList<ValidatedObservation>();
            for (int i = 0; i < 10; i++) {
                observations.add(validObservation("pr-description-quality", Presence.PRESENT));
            }

            var result = service.deliver(testJob, observations);

            assertThat(result.inserted()).isEqualTo(10);
            assertThat(result.discardedDuplicate()).isZero();
        }

        @Test
        void persistsNegativesIndependentlyPerPractice() {
            Practice otherPractice = new Practice();
            ReflectionTestUtils.setField(otherPractice, "id", 20L);
            otherPractice.setSlug("error-handling");
            otherPractice.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST));
            otherPractice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
            admit(otherPractice, 22L);

            var observations = new java.util.ArrayList<ValidatedObservation>();
            for (int i = 0; i < 5; i++) {
                observations.add(validObservation("pr-description-quality", Presence.ABSENT));
                observations.add(validObservation("error-handling", Presence.ABSENT));
            }

            var result = service.deliver(testJob, observations);

            assertThat(result.inserted()).isEqualTo(10);
        }
    }

    @Nested
    class NotApplicableObservation {

        @Test
        @DisplayName("persists NOT_APPLICABLE observation without counting as negative")
        void notApplicablePersisted() {
            var observations = List.of(validObservation("pr-description-quality", null));

            var result = service.deliver(testJob, observations);

            assertThat(result.inserted()).isEqualTo(1);
            assertThat(result.hasNegative()).isFalse();
        }

        @Test
        void persistsManyNotApplicableObservations() {
            var observations = new java.util.ArrayList<ValidatedObservation>();
            for (int i = 0; i < 10; i++) {
                observations.add(validObservation("pr-description-quality", null));
            }

            var result = service.deliver(testJob, observations);

            assertThat(result.inserted()).isEqualTo(10);
        }
    }

    @Nested
    class SeverityCoherence {

        private String capturedSeverityFor(ValidatedObservation observation) {
            service.deliver(testJob, List.of(observation));
            ArgumentCaptor<String> severityCaptor = ArgumentCaptor.forClass(String.class);
            verify(observationRepository)
                    .insertIfAbsent(
                            any(),
                            anyString(),
                            any(),
                            anyLong(),
                            anyLong(),
                            any(), // practiceRevisionId
                            anyString(),
                            anyLong(),
                            anyLong(),
                            any(),
                            anyString(),
                            any(),
                            any(), // assessment (null for NOT_APPLICABLE)
                            severityCaptor.capture(),
                            any(),
                            any(),
                            anyString(),
                            any(),
                            anyString());
            return severityCaptor.getValue();
        }

        @Test
        @DisplayName("a BAD observation keeps its severity")
        void badFindingKeepsSeverity() {
            // ABSENT → BAD with Severity.INFO from the fixture helper.
            assertThat(capturedSeverityFor(validObservation("pr-description-quality", Presence.ABSENT)))
                    .isEqualTo("MINOR");
        }

        @Test
        @DisplayName("a GOOD observation's severity is coerced to null (ADR 0022: severity is BAD-only)")
        void goodFindingSeverityCoercedToNull() {
            // PRESENT → GOOD, yet the fixture helper still carries Severity.INFO; it must not be persisted.
            assertThat(capturedSeverityFor(validObservation("pr-description-quality", Presence.PRESENT)))
                    .isNull();
        }

        @Test
        @DisplayName("a NOT_APPLICABLE observation's severity is coerced to null")
        void notApplicableFindingSeverityCoercedToNull() {
            assertThat(capturedSeverityFor(validObservation("pr-description-quality", null)))
                    .isNull();
        }
    }

    @Nested
    class Idempotency {

        @Test
        void duplicateKey() {
            when(observationRepository.insertIfAbsent(
                            any(),
                            anyString(),
                            any(),
                            anyLong(),
                            anyLong(),
                            any(), // practiceRevisionId
                            anyString(),
                            anyLong(),
                            anyLong(),
                            any(),
                            eq("ASSESSED"),
                            any(),
                            anyString(),
                            any(),
                            any(),
                            any(),
                            anyString(),
                            any(),
                            anyString()))
                    .thenReturn(0);

            var observations = List.of(validObservation("pr-description-quality", Presence.PRESENT));

            var result = service.deliver(testJob, observations);

            assertThat(result.inserted()).isZero();
            assertThat(result.discardedDuplicate()).isEqualTo(1);
        }

        @Test
        void keyFormat() {
            var observations = List.of(validObservation("pr-description-quality", Presence.PRESENT));

            service.deliver(testJob, observations);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(observationRepository)
                    .insertIfAbsent(
                            any(),
                            keyCaptor.capture(),
                            any(),
                            anyLong(),
                            anyLong(),
                            any(), // practiceRevisionId
                            anyString(),
                            anyLong(),
                            anyLong(),
                            any(),
                            anyString(),
                            any(),
                            anyString(),
                            isNull(),
                            any(),
                            any(),
                            anyString(),
                            any(),
                            anyString());

            String key = keyCaptor.getValue();
            assertThat(key).isEqualTo("pr-description-quality:0:scm.pull_request:456:" + testJob.getId());
        }
    }

    @Nested
    class EventPublication {

        @Test
        void correctCounts() {
            Practice otherPractice = new Practice();
            ReflectionTestUtils.setField(otherPractice, "id", 20L);
            otherPractice.setSlug("error-handling");
            otherPractice.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST));
            otherPractice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
            admit(otherPractice, 22L);

            var observations = List.of(
                    validObservation("pr-description-quality", Presence.PRESENT),
                    validObservation("error-handling", Presence.ABSENT));

            service.deliver(testJob, observations);

            verify(eventPublisher).publishEvent(eventCaptor.capture());
            PracticeDetectionCompletedEvent event = eventCaptor.getValue();
            assertThat(event.observationsInserted()).isEqualTo(2);
            assertThat(event.observationsDiscarded()).isZero();
            assertThat(event.hasNegative()).isTrue();
            assertThat(event.developerId()).isEqualTo(789L);
            assertThat(event.artifactKind()).isEqualTo(ArtifactKinds.PULL_REQUEST);
            assertThat(event.artifactId()).isEqualTo(456L);
        }
    }

    @Nested
    class IssueRouting {

        @Test
        void routesToIssueTargetAndAuthorWhenArtifactKindIsIssue() {
            when(reviewTargets.findIssue(999L))
                    .thenReturn(Optional.of(new ReviewTargetQuery.Target(123L, "owner/repo", 12, 789L, false)));

            ObjectNode meta = new ObjectMapper().createObjectNode();
            meta.put("artifact_kind", ArtifactKinds.ISSUE.value());
            meta.put("issue_id", 999L);
            meta.put("repository_id", 123L);
            meta.put("repository_full_name", "owner/repo");
            meta.put("issue_number", 12);
            testJob.setMetadata(meta);

            var observations = List.of(validObservation("pr-description-quality", Presence.ABSENT));
            var result = service.deliver(testJob, observations);

            assertThat(result.inserted()).isEqualTo(1);
            verify(observationRepository)
                    .insertIfAbsent(
                            any(),
                            eq("pr-description-quality:0:scm.issue:999:" + testJob.getId()),
                            eq(testJob.getId()),
                            anyLong(),
                            anyLong(),
                            eq(11L),
                            eq("scm.issue"),
                            eq(999L),
                            eq(789L), // aboutUserId
                            anyString(),
                            anyString(), // title
                            eq("ABSENT"), // presence
                            eq("BAD"), // assessment
                            anyString(),
                            any(),
                            any(),
                            anyString(),
                            any(),
                            anyString());
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().artifactKind()).isEqualTo(ArtifactKinds.ISSUE);
            assertThat(eventCaptor.getValue().artifactId()).isEqualTo(999L);
        }

        @Test
        void refusesAKindWithNoDeliveryRoute() {
            ObjectNode meta = new ObjectMapper().createObjectNode();
            meta.put("artifact_kind", "wiki.page");
            testJob.setMetadata(meta);

            var observations = List.of(validObservation("pr-description-quality", Presence.ABSENT));

            assertThatThrownBy(() -> service.deliver(testJob, observations))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("No delivery route for artifact kind: kind=wiki.page");
        }
    }
}
