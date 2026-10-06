package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.WithheldObservation;
import de.tum.cit.aet.hephaestus.agent.handler.conversation.PracticeFeedbackPreparationRequestedEvent;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository.ProviderPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.feedback.ProposedPlacement;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

/** The delivered-feedback ledger writer (ADR 0021). */
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackLedgerRecorderTest extends BaseUnitTest {

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private FeedbackRepository feedbackRepository;

    @Mock
    private FeedbackObservationRepository feedbackObservationRepository;

    @Mock
    private FeedbackPlacementRepository feedbackPlacementRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private OutboundEgressGuard egressGuard;

    @Mock
    private PracticeFeedbackCommentFormatter commentFormatter;

    private FeedbackLedgerRecorder recorder() {
        when(egressGuard.deliveryAllowed(any())).thenReturn(true);
        when(feedbackRepository.existsByAgentJobIdAndPosition(any(), anyInt())).thenReturn(false);
        when(feedbackRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(feedbackObservationRepository.findObservationIdsSuppressedForJob(any()))
                .thenReturn(List.of());
        lenient()
                .when(feedbackObservationRepository.insertIfAbsent(any(), any(), any(), anyInt()))
                .thenReturn(1);
        when(feedbackPlacementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(feedbackPlacementRepository.findLatestDeliveredSummary(any())).thenReturn(Optional.empty());
        return new FeedbackLedgerRecorder(
                observationRepository,
                feedbackRepository,
                feedbackObservationRepository,
                feedbackPlacementRepository,
                eventPublisher,
                egressGuard,
                commentFormatter);
    }

    @Test
    void recordsProviderHandlesForAnApprovedReviewPackage() {
        Feedback feedback =
                Feedback.builder().id(UUID.randomUUID()).workspaceId(7L).build();
        var signal = new InlineFeedbackChannel.DeliveredSignal(
                "recurrence",
                new FeedbackAnchor.DiffAnchor("src/Review.java", 12, 9),
                InlineFeedbackChannel.Disposition.POSTED,
                "inline-ref",
                "thread-ref",
                "https://github.com/owner/repo/pull/42#discussion_r123");

        recorder()
                .recordApprovedPlacements(
                        feedback,
                        "summary-ref",
                        "https://github.com/owner/repo/pull/42#issuecomment-987654",
                        List.of(signal));

        verify(feedbackPlacementRepository)
                .insertProviderPlacementIfAbsent(
                        argThat(placement -> placement.feedbackId().equals(feedback.getId())
                                && placement.placementType().equals("SUMMARY")
                                && "summary-ref".equals(placement.postedCommentRef())
                                && "https://github.com/owner/repo/pull/42#issuecomment-987654"
                                        .equals(placement.postedCommentUrl())));
        verify(feedbackPlacementRepository)
                .insertProviderPlacementIfAbsent(
                        argThat(placement -> placement.feedbackId().equals(feedback.getId())
                                && placement.placementType().equals("INLINE")
                                && "RANGE".equals(placement.anchorKind())
                                && "src/Review.java".equals(placement.anchorPath())
                                && Integer.valueOf(9).equals(placement.anchorStartLine())
                                && Integer.valueOf(12).equals(placement.anchorEndLine())
                                && "NEW".equals(placement.anchorSide())
                                && "inline-ref".equals(placement.postedCommentRef())
                                && "https://github.com/owner/repo/pull/42#discussion_r123"
                                        .equals(placement.postedCommentUrl())));
    }

    @Test
    void shouldRecordWhereApprovedLineNotesActuallyAppearedKeepingTheirProposedAnchor() {
        Feedback feedback =
                Feedback.builder().id(UUID.randomUUID()).workspaceId(7L).build();
        var located = new InlineFeedbackChannel.DeliveredSignal(
                "approved:0",
                FeedbackAnchor.DiffAnchor.range("src/Review.java", 9, 12),
                InlineFeedbackChannel.Disposition.POSTED,
                "gid://gitlab/Note/1",
                null,
                "https://gitlab.example.com/acme/api/-/merge_requests/42#note_1",
                true,
                InlineFeedbackChannel.Placement.LOCATION_COMMENT);
        // Receipts stored before placement was: a fallback was an ordinary comment and a new copy a line comment,
        // while a kept copy proves neither, so no placement is asserted for it.
        var fellBack = new InlineFeedbackChannel.DeliveredSignal(
                "approved:1",
                FeedbackAnchor.DiffAnchor.singleLine("src/Review.java", 20),
                InlineFeedbackChannel.Disposition.FELL_BACK,
                "gid://gitlab/Note/2",
                null);
        var kept = new InlineFeedbackChannel.DeliveredSignal(
                "approved:2",
                FeedbackAnchor.DiffAnchor.singleLine("src/Review.java", 30),
                InlineFeedbackChannel.Disposition.PRESERVED_EXISTING,
                "gid://gitlab/Note/3",
                null);

        var posted = new InlineFeedbackChannel.DeliveredSignal(
                "approved:3",
                FeedbackAnchor.DiffAnchor.singleLine("src/Review.java", 40),
                InlineFeedbackChannel.Disposition.POSTED,
                "gid://gitlab/Note/4",
                null);

        recorder().recordApprovedPlacements(feedback, null, null, List.of(located, fellBack, kept, posted));

        var placements = ArgumentCaptor.forClass(ProviderPlacement.class);
        verify(feedbackPlacementRepository, times(3)).insertProviderPlacementIfAbsent(placements.capture());
        assertThat(placements.getAllValues())
                .extracting(
                        ProviderPlacement::postedCommentRef,
                        ProviderPlacement::placementType,
                        ProviderPlacement::anchorKind,
                        ProviderPlacement::anchorPath,
                        ProviderPlacement::anchorStartLine,
                        ProviderPlacement::anchorEndLine)
                .containsExactly(
                        tuple("gid://gitlab/Note/1", "LOCATION_COMMENT", "RANGE", "src/Review.java", 9, 12),
                        tuple("gid://gitlab/Note/2", "LOCATION_COMMENT", "LINE", "src/Review.java", 20, 20),
                        tuple("gid://gitlab/Note/4", "INLINE", "LINE", "src/Review.java", 40, 40));
    }

    @Test
    void shouldAssertNoPlacementForAKeptCopyWhosePlacementWasNeverRecordedYetStillDeliverItsUnit() {
        var observation = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(observation));
        String key = "observation:" + observation.getOccurrenceKey();
        var note = new DiffNote("src/Foo.java", 10, null, "Fix this", key, null);
        var kept = new InlineFeedbackChannel.DeliveredSignal(
                key,
                FeedbackAnchor.DiffAnchor.singleLine("src/Foo.java", 10),
                InlineFeedbackChannel.Disposition.PRESERVED_EXISTING,
                "gid://gitlab/Note/5",
                "gid://gitlab/Discussion/5");

        recorder()
                .record(
                        job(),
                        new DeliveryContent(null, List.of(note), List.of(), null),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(kept),
                        null,
                        null);

        verify(feedbackPlacementRepository, never()).insertProviderPlacementIfAbsent(any());
        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryState()).isEqualTo(FeedbackDeliveryState.DELIVERED);
        UUID observationId = observation.getId();
        verify(feedbackObservationRepository).insertIfAbsent(any(), eq(observationId), any(), anyInt());
    }

    @Test
    void shouldRecordAnAutomaticLineNotePostedAsALocationCommentWithItsProposedAnchor() {
        var observation = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(observation));
        var note = new DiffNote("src/Foo.java", 10, 14, "Fix this", "ck-foo", null);
        var signal = new InlineFeedbackChannel.DeliveredSignal(
                "ck-foo",
                FeedbackAnchor.DiffAnchor.range("src/Foo.java", 10, 14),
                InlineFeedbackChannel.Disposition.PRESERVED_EXISTING,
                "gid://gitlab/Note/9",
                "gid://gitlab/Discussion/9",
                "https://gitlab.example.com/acme/api/-/merge_requests/42#note_9",
                null,
                InlineFeedbackChannel.Placement.LOCATION_COMMENT);

        recorder()
                .record(
                        job(),
                        new DeliveryContent(null, List.of(note), List.of(), null),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(signal),
                        null,
                        null);

        var placement = ArgumentCaptor.forClass(ProviderPlacement.class);
        verify(feedbackPlacementRepository).insertProviderPlacementIfAbsent(placement.capture());
        assertThat(placement.getValue())
                .extracting(
                        ProviderPlacement::placementType,
                        ProviderPlacement::anchorKind,
                        ProviderPlacement::anchorPath,
                        ProviderPlacement::anchorStartLine,
                        ProviderPlacement::anchorEndLine,
                        ProviderPlacement::postedCommentRef,
                        ProviderPlacement::postedCommentUrl)
                .containsExactly(
                        "LOCATION_COMMENT",
                        "RANGE",
                        "src/Foo.java",
                        10,
                        14,
                        "gid://gitlab/Note/9",
                        "https://gitlab.example.com/acme/api/-/merge_requests/42#note_9");
    }

    @Test
    void composerWithheld_bindsEachObservationExactlyOnce_keptToDeliveredDroppedToSuppressed() {
        List<Observation> observations = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            observations.add(problem());
        }
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(observations);
        var delivery = new DeliveryContent(
                "body",
                List.of(),
                List.of(
                        new WithheldObservation(
                                observations.get(3).getOccurrenceKey(), FeedbackSuppressionReason.VOLUME_CAPPED),
                        new WithheldObservation(
                                observations.get(4).getOccurrenceKey(), FeedbackSuppressionReason.VOLUME_CAPPED)),
                null);

        recorder().record(job(), delivery, ArtifactKinds.PULL_REQUEST, List.of(), "summary-ref", null);

        // Every observation bound exactly once across ALL units (3 to DELIVERED + 1 each to the 2 SUPPRESSED units).
        var boundObservationIds = ArgumentCaptor.forClass(UUID.class);
        verify(feedbackObservationRepository, times(5))
                .insertIfAbsent(any(), boundObservationIds.capture(), any(), anyInt());
        assertThat(boundObservationIds.getAllValues()).doesNotHaveDuplicates().hasSize(5);

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository, atLeast(3)).save(saved.capture());
        long suppressed = saved.getAllValues().stream()
                .filter(f -> f.getDeliveryState() == FeedbackDeliveryState.SUPPRESSED)
                .filter(f -> f.getSuppressionReason() == FeedbackSuppressionReason.VOLUME_CAPPED)
                .count();
        assertThat(suppressed).isEqualTo(2);
    }

    @Test
    void noWithheld_bindsAllProblems_noSuppressedUnits() {
        List<Observation> observations = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            observations.add(problem());
        }
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(observations);

        recorder()
                .record(
                        job(),
                        new DeliveryContent("body", List.of(), List.of(), null),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(),
                        "summary-ref",
                        null);

        verify(feedbackObservationRepository, times(5)).insertIfAbsent(any(), any(), any(), anyInt());
        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryState()).isEqualTo(FeedbackDeliveryState.DELIVERED);
    }

    @Test
    void inlinePlacement_persistsExternalRefFromMatchingSignal() {
        // A3: the INLINE placement must carry the durable vendor handle the channel reported, not a hardcoded
        // null. The note and its DeliveredSignal share a recurrence key, so the signal's externalRef lands
        // on the saved FeedbackPlacement.
        var observation = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(observation));

        var note = new DiffNote("src/Foo.java", 10, null, "Fix this", "ck-foo-10", null);
        var signal = new InlineFeedbackChannel.DeliveredSignal(
                "ck-foo-10",
                new FeedbackAnchor.DiffAnchor("src/Foo.java", 10, null),
                InlineFeedbackChannel.Disposition.POSTED,
                "note-gid-42",
                "discussion-gid-7");

        recorder()
                .record(
                        job(),
                        new DeliveryContent("body", List.of(note), List.of(), null),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(signal),
                        "summary-ref",
                        null);

        var placements = ArgumentCaptor.forClass(ProviderPlacement.class);
        verify(feedbackPlacementRepository, atLeastOnce()).insertProviderPlacementIfAbsent(placements.capture());
        ProviderPlacement inline = placements.getAllValues().stream()
                .filter(p -> p.placementType().equals(PlacementType.INLINE.name()))
                .findFirst()
                .orElseThrow();
        assertThat(inline.postedCommentRef()).isEqualTo("note-gid-42");
    }

    @Test
    void shouldNotCreatePlacementWhenInlineSignalFailed() {
        var observation = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(observation));

        var note = new DiffNote("src/Bar.java", 5, 8, "Range note");
        var signal = new InlineFeedbackChannel.DeliveredSignal(
                null,
                new FeedbackAnchor.DiffAnchor("src/Bar.java", 8, 5),
                InlineFeedbackChannel.Disposition.FAILED,
                null,
                null);

        recorder()
                .record(
                        job(),
                        new DeliveryContent("body", List.of(note), List.of(), null),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(signal),
                        null,
                        null);

        verify(feedbackPlacementRepository, never()).insertProviderPlacementIfAbsent(any());
    }

    @Test
    void b2AndComposerWithheldOverlap_aSuppressedObservationIsNeverBoundTwice() {
        // An observation reaction suppression already withheld must NOT also be written as a composer-withheld
        // unit even when the composer reports its key — it is bound exactly once across all units.
        List<Observation> observations = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            observations.add(problem());
        }
        UUID b2Id = observations.get(5).getId(); // also reported withheld by the composer
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(observations);
        var recorder = recorder();
        when(feedbackObservationRepository.findObservationIdsSuppressedForJob(any()))
                .thenReturn(List.of(b2Id));
        var delivery = new DeliveryContent(
                "body",
                List.of(),
                List.of(new WithheldObservation(
                        observations.get(5).getOccurrenceKey(), FeedbackSuppressionReason.VOLUME_CAPPED)),
                null);

        recorder.record(job(), delivery, ArtifactKinds.PULL_REQUEST, List.of(), "summary-ref", null);

        var bound = ArgumentCaptor.forClass(UUID.class);
        verify(feedbackObservationRepository, atLeastOnce()).insertIfAbsent(any(), bound.capture(), any(), anyInt());
        assertThat(bound.getAllValues()).doesNotHaveDuplicates().doesNotContain(b2Id);
    }

    @Test
    void alreadySuppressedObservation_isExcludedFromDeliveredUnit() {
        // An observation withheld earlier in the flow (reaction suppression wrote a SUPPRESSED unit for it) must
        // NOT also be bound to the DELIVERED unit — else it is double-counted as delivered.
        var kept = problem();
        var b2Suppressed = problem();
        UUID keptId = kept.getId();
        UUID b2Id = b2Suppressed.getId();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(kept, b2Suppressed));
        var recorder = recorder();
        when(feedbackObservationRepository.findObservationIdsSuppressedForJob(any()))
                .thenReturn(List.of(b2Id));

        recorder.record(
                job(),
                new DeliveryContent("body", List.of(), List.of(), null),
                ArtifactKinds.PULL_REQUEST,
                List.of(),
                "summary-ref",
                null);

        var bound = ArgumentCaptor.forClass(UUID.class);
        verify(feedbackObservationRepository).insertIfAbsent(any(), bound.capture(), any(), anyInt());
        assertThat(bound.getAllValues()).containsExactly(keptId);
    }

    @Test
    void everySavedFeedback_isReSourcedToTheObservationSubject_recipientEqualsAbout() {
        // The delivery firewall: the recorder must re-source both recipient AND subject from the
        // observation's about_user_id (7L here), never from some other field. This pins that the saved
        // Feedback always satisfies recipientUserId == aboutUserId == observation.aboutUserId.
        List<Observation> observations = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            observations.add(problem());
        }
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(observations);

        recorder()
                .record(
                        job(),
                        new DeliveryContent("body", List.of(), List.of(), null),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(),
                        "summary-ref",
                        null);

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).isNotEmpty().allSatisfy(f -> {
            assertThat(f.getRecipientUserId()).isEqualTo(7L);
            assertThat(f.getAboutUserId()).isEqualTo(f.getRecipientUserId());
        });
    }

    @Test
    void reReview_proposal_carriesItsThreadAndRetiresTheUndecidedOneBeforeIt() {
        Observation observation = problem();
        var practice = mock(Practice.class);
        when(practice.getSlug()).thenReturn("practice");
        when(observation.getPractice()).thenReturn(practice);
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(observation));
        when(feedbackRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AgentJob job = job();
        when(commentFormatter.appendDisclosure("proposed body", job)).thenReturn("proposed body\n\nAI disclosure");
        when(commentFormatter.appendInlineFeedbackPrompt(eq("inline body"), any()))
                .thenReturn("inline body\n\nAI disclosure");
        var metadata = JsonMapper.builder().build().createObjectNode();
        metadata.put("commit_sha", "abc123");
        job.setMetadata(metadata);

        recorder()
                .recordProposal(
                        job,
                        new DeliveryContent(
                                "proposed body",
                                List.of(new DiffNote("src/Example.java", 12, 14, "inline body", "rk", null)),
                                List.of(),
                                List.of(observation.getOccurrenceKey())));

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getThreadKey()).isNotBlank();
        assertThat(saved.getValue().getReviewedRevision()).isEqualTo("abc123");
        assertThat(saved.getValue().getDeliveryState()).isEqualTo(FeedbackDeliveryState.AWAITING_APPROVAL);
        verify(eventPublisher)
                .publishEvent(new PracticeFeedbackPreparationRequestedEvent(
                        job.getId(), job.getWorkspace().getId()));
        assertThat(saved.getValue().getProposedPracticeSlugs()).containsExactly("practice");
        assertThat(saved.getValue().getProposedPlacements())
                .extracting(placement -> placement.type().name())
                .containsExactly("SUMMARY", "INLINE");
        assertThat(saved.getValue().getBody()).isEqualTo("proposed body\n\nAI disclosure");
        assertThat(saved.getValue().getProposedPlacements().get(0).body())
                .isEqualTo(saved.getValue().getBody());
        assertThat(saved.getValue().getProposedPlacements().get(1).body()).isEqualTo("inline body\n\nAI disclosure");
        verify(feedbackRepository)
                .supersedeUndecidedProposals(any(), eq(saved.getValue().getThreadKey()), any());
    }

    @Test
    void shouldProposeOnlyTheInlineNoteWhenTheReviewHasNoSummary() {
        Observation bad = problem();
        Observation good = strength();
        lenient().when(good.getOccurrenceKey()).thenReturn("occ-good");
        withPractice(bad, "practice-a");
        withPractice(good, "practice-b");
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(bad, good));
        AgentJob job = job();
        String deliveryKey = "observation:" + bad.getOccurrenceKey() + "#0";
        when(commentFormatter.appendInlineFeedbackPrompt(eq("Name the test after the behaviour."), any()))
                .thenReturn("Name the test after the behaviour.\n\nAI disclosure");

        recorder().recordProposal(job, inlineOnly(deliveryKey, bad, good));

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        Feedback proposal = saved.getValue();
        assertThat(proposal.getDeliveryState()).isEqualTo(FeedbackDeliveryState.AWAITING_APPROVAL);
        assertThat(proposal.getBody()).isNull();
        assertThat(proposal.getProposedPlacements()).singleElement().satisfies(placement -> {
            assertThat(placement.type()).isEqualTo(PlacementType.INLINE);
            assertThat(placement.deliveryKey()).isEqualTo(deliveryKey);
        });
        UUID badId = bad.getId();
        UUID goodId = good.getId();
        verify(feedbackObservationRepository).insertIfAbsent(any(), eq(badId), eq("PRIMARY"), anyInt());
        verify(feedbackObservationRepository).insertIfAbsent(any(), eq(goodId), eq("SUPPORTING"), anyInt());
    }

    @Test
    void shouldRecordAFailedUnitWhenAnInlineOnlyReviewIsUndelivered() {
        Observation bad = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(bad));
        String deliveryKey = "observation:" + bad.getOccurrenceKey() + "#0";

        recorder().recordUndelivered(job(), inlineOnly(deliveryKey, bad));

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        Feedback unit = saved.getValue();
        assertThat(unit.getDeliveryState()).isEqualTo(FeedbackDeliveryState.FAILED);
        assertThat(unit.getBody()).isNull();
        assertThat(unit.getProposedPlacements())
                .containsExactly(ProposedPlacement.inline(
                        "Name the test after the behaviour.", "src/Foo.java", 10, null, deliveryKey));
        UUID badId = bad.getId();
        verify(feedbackObservationRepository).insertIfAbsent(any(), eq(badId), eq("PRIMARY"), anyInt());
    }

    @Test
    void shouldRefuseToAddressAProposalWhenItRestsOnObservationsAboutTwoPeople() {
        Observation aboutOne = problem();
        Observation aboutAnother = problem();
        lenient().when(aboutAnother.getAboutUserId()).thenReturn(8L);
        withPractice(aboutOne, "practice-a");
        withPractice(aboutAnother, "practice-a");
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(aboutOne, aboutAnother));
        String deliveryKey = "observation:" + aboutOne.getOccurrenceKey() + "#0";
        when(commentFormatter.appendInlineFeedbackPrompt(any(), any())).thenReturn("note");
        FeedbackLedgerRecorder recorder = recorder();
        DeliveryContent delivery = inlineOnly(deliveryKey, aboutOne, aboutAnother);
        AgentJob job = job();

        assertThatThrownBy(() -> recorder.recordProposal(job, delivery)).isInstanceOf(IllegalStateException.class);
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void reReview_priorDeliveredUnit_isSupersededAndNewRowReplacesIt() {
        // B1: the re-review SUPERSEDED branch (every other test stubs the prior lookup to Optional.empty()).
        // A prior live DELIVERED unit on this continuity line → the new row's replacesId points at it AND the
        // prior is flipped to SUPERSEDED via the native supersedeDelivered, AFTER the new row lands (never zero live).
        var observation = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(observation));
        var recorder = recorder();
        UUID priorId = UUID.randomUUID();
        FeedbackPlacement priorSummary = mock(FeedbackPlacement.class);
        when(priorSummary.getFeedbackId()).thenReturn(priorId);
        when(feedbackPlacementRepository.findLatestDeliveredSummary(any())).thenReturn(Optional.of(priorSummary));

        recorder.record(
                job(),
                new DeliveryContent("body", List.of(), List.of(), null),
                ArtifactKinds.PULL_REQUEST,
                List.of(),
                "summary-ref",
                null);

        // The prior is superseded by id, inside its workspace.
        verify(feedbackRepository).supersedeDelivered(1L, priorId);
        // The freshly saved DELIVERED unit carries replacesId = the prior id.
        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository, atLeastOnce()).save(saved.capture());
        Feedback delivered = saved.getAllValues().stream()
                .filter(f -> f.getDeliveryState() == FeedbackDeliveryState.DELIVERED)
                .findFirst()
                .orElseThrow();
        assertThat(delivered.getReplacesId()).isEqualTo(priorId);
    }

    @Test
    void goodStrengthBoundAsSupporting_afterProblems_naExcluded() {
        // B1: a GOOD strength binds as SUPPORTING and sorts LAST (null severity = least severe); a
        // NOT_APPLICABLE abstention is excluded entirely.
        var problem = problem();
        var strength = strength();
        var na = notApplicable();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(strength, problem, na));

        recorder()
                .record(
                        job(),
                        new DeliveryContent("body", List.of(), List.of(), null),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(),
                        "summary-ref",
                        null);

        // Two bindings (problem PRIMARY + strength SUPPORTING); the NA is never bound.
        var boundId = ArgumentCaptor.forClass(UUID.class);
        var role = ArgumentCaptor.forClass(String.class);
        var ordinal = ArgumentCaptor.forClass(Integer.class);
        verify(feedbackObservationRepository, times(2))
                .insertIfAbsent(any(), boundId.capture(), role.capture(), ordinal.capture());
        assertThat(boundId.getAllValues()).containsExactly(problem.getId(), strength.getId());
        // The problem leads (PRIMARY, ordinal 0); the strength is SUPPORTING and sorts last (ordinal 1).
        assertThat(role.getAllValues()).containsExactly("PRIMARY", "SUPPORTING");
        assertThat(ordinal.getAllValues()).containsExactly(0, 1);
        assertThat(boundId.getAllValues()).doesNotContain(na.getId());
    }

    @Test
    void transientNoop_writesNoPhantomDelivered_andDoesNotSupersedePrior() {
        // A3: a TRANSIENT no-op (summaryDelivered=false) kept the prior run's summary live and posted nothing.
        // The recorder must write NO fresh DELIVERED unit and must NOT supersede the still-live prior — else the
        // mentor coaches against words the student never saw.
        var observation = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(observation));
        var recorder = recorder();
        recorder.record(
                job(),
                new DeliveryContent("body", List.of(), List.of(), null),
                ArtifactKinds.PULL_REQUEST,
                List.of(),
                null,
                null);

        verify(feedbackRepository, never()).save(any());
        verify(feedbackRepository, never()).supersedeDelivered(any(), any());
        verify(feedbackObservationRepository, never()).insertIfAbsent(any(), any(), any(), anyInt());
    }

    @Test
    void inlineOnlyDeliveryRecordsInlinePlacementWithoutSupersedingSummary() {
        var observation = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(observation));
        var note = new DiffNote("src/Foo.java", 10, null, "Fix this", "ck-foo", null);
        var signal = new InlineFeedbackChannel.DeliveredSignal(
                "ck-foo",
                new FeedbackAnchor.DiffAnchor("src/Foo.java", 10, null),
                InlineFeedbackChannel.Disposition.POSTED,
                "note-1",
                "disc-1");

        recorder()
                .record(
                        job(),
                        new DeliveryContent(null, List.of(note), List.of(), null),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(signal),
                        null,
                        null);

        var savedFeedback = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(savedFeedback.capture());
        assertThat(savedFeedback.getValue().getBody()).isNull();
        assertThat(savedFeedback.getValue().getReplacesId()).isNull();
        verify(feedbackRepository, never()).supersedeDelivered(any(), any());

        var savedPlacement = ArgumentCaptor.forClass(ProviderPlacement.class);
        verify(feedbackPlacementRepository).insertProviderPlacementIfAbsent(savedPlacement.capture());
        assertThat(savedPlacement.getValue().placementType()).isEqualTo(PlacementType.INLINE.name());
        assertThat(savedPlacement.getValue().postedCommentRef()).isEqualTo("note-1");
        verify(feedbackPlacementRepository, never()).findLatestDeliveredSummary(any());
    }

    @Test
    void recordUndelivered_persistsFailedBody_bindsObservations_andSignalsConversation() {
        // A direct-delivery failure: the composed body must be persisted as a FAILED IN_CONTEXT unit (auditable +
        // dashboard-visible) AND the conversational channel must be signalled so it can pick up the loci the
        // developer never saw in-context.
        Observation bad = problem();
        Observation good = strength();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(bad, good));

        recorder()
                .recordUndelivered(
                        job(), new DeliveryContent("the advice that never landed", List.of(), List.of(), null));

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        Feedback unit = saved.getValue();
        assertThat(unit.getDeliveryState()).isEqualTo(FeedbackDeliveryState.FAILED);
        assertThat(unit.getBody()).isEqualTo("the advice that never landed");
        // Ordinal 4000 keeps the FAILED unit clear of the DELIVERED(0)/SUPPRESSED(1000)/policy(2000)/conv(3000) bases.
        assertThat(unit.getPosition()).isEqualTo(4000);
        // Both assessed observations are bound (BAD as PRIMARY, GOOD as SUPPORTING); NA would be excluded.
        verify(feedbackObservationRepository, times(2)).insertIfAbsent(any(), any(), any(), anyInt());
        // The chat and in-app lanes are signalled despite the failed direct delivery.
        verify(eventPublisher).publishEvent(any(PracticeFeedbackPreparationRequestedEvent.class));
    }

    @Test
    void recordUndelivered_noOps_whenDeliveredUnitAlreadyExists() {
        // A DELIVERED unit at ordinal 0 already exists (a prior run landed): never write a contradictory FAILED
        // unit and never signal — record() already handled both.
        FeedbackLedgerRecorder rec = recorder();
        // Override AFTER recorder() installed the anyInt()->false default, so eq(0) wins for the IN_CONTEXT unit.
        when(feedbackRepository.existsByAgentJobIdAndPosition(any(), eq(0))).thenReturn(true);

        rec.recordUndelivered(job(), new DeliveryContent("body", List.of(), List.of(), null));

        verify(feedbackRepository, never()).save(any());
        // Typed, not any(): ApplicationEventPublisher.publishEvent is overloaded, and a bare any() binds to
        // the ApplicationEvent overload this code never calls — which passes whatever the code does.
        verify(eventPublisher, never()).publishEvent(any(PracticeFeedbackPreparationRequestedEvent.class));
    }

    @Test
    void recordUndelivered_wakesTheLongitudinalLanes_evenWithNothingToPostOnTheWork() {
        // The composer can decline to say anything on the merge request and still have written feedback
        // about the way of working behind it.
        recorder().recordUndelivered(job(), null);

        verify(eventPublisher).publishEvent(any(PracticeFeedbackPreparationRequestedEvent.class));
        verify(feedbackRepository, never()).save(any());
    }

    /**
     * Silence stops the note on the work, not the developer's own pages: the lanes are woken at once, not when
     * the hourly sweeper next passes.
     */
    @Test
    void shouldRecordOneSuppressionAndWakeTheLanesWhenSilentModeWithholdsTheRun() {
        Observation bad = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(bad));
        FeedbackLedgerRecorder recorder = recorder();
        when(egressGuard.deliveryAllowed(any())).thenReturn(false);

        recorder.recordUndelivered(job(), new DeliveryContent("body", List.of(), List.of(), null));

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryState()).isEqualTo(FeedbackDeliveryState.SUPPRESSED);
        assertThat(saved.getValue().getSuppressionReason()).isEqualTo(FeedbackSuppressionReason.INSTANCE_SILENCED);
        verify(eventPublisher).publishEvent(any(PracticeFeedbackPreparationRequestedEvent.class));
    }

    /**
     * A reason that withholds only the note on the work wakes the lanes at once; closed, gone or opted-out work
     * wakes none. Either way the withheld review is on the ledger.
     */
    @ParameterizedTest
    @CsvSource({
        "INSTANCE_SILENCED,true",
        "REPEATS_DELIVERED_NOTE,true",
        "ARTIFACT_MERGED,true",
        "ARTIFACT_CLOSED,false",
        "ARTIFACT_GONE,false",
        "RECIPIENT_OPTED_OUT,false"
    })
    void shouldWakeTheLanesOnlyWhenTheReasonWithholdsJustTheNoteOnTheWork(
            FeedbackSuppressionReason reason, boolean wakes) {
        Observation bad = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(bad));
        FeedbackLedgerRecorder rec = recorder();

        rec.recordSuppressedUnit(job(), new DeliveryContent("body", List.of(), List.of(), null), reason);

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryState()).isEqualTo(FeedbackDeliveryState.SUPPRESSED);
        assertThat(saved.getValue().getSuppressionReason()).isEqualTo(reason);
        verify(eventPublisher, wakes ? times(1) : never())
                .publishEvent(any(PracticeFeedbackPreparationRequestedEvent.class));
    }

    @Test
    void recordUndelivered_reSignalsButDoesNotRepersist_onFailedRetry() {
        // A failing retry: the FAILED unit (ordinal 4000) was already written. Re-signalling the conversation is
        // harmless (idempotent listener), but the FAILED row must NOT be persisted twice.
        FeedbackLedgerRecorder rec = recorder();
        Observation bad = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(bad));
        // Past the DELIVERED(0) guard (default false), but the FAILED(4000) unit already exists (retry).
        when(feedbackRepository.existsByAgentJobIdAndPosition(any(), eq(4000))).thenReturn(true);

        rec.recordUndelivered(job(), new DeliveryContent("body", List.of(), List.of(), null));

        verify(eventPublisher).publishEvent(any(PracticeFeedbackPreparationRequestedEvent.class));
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void recordUndelivered_noOps_whenJobHasNoWorkspace() {
        // A no-workspace integrity failure (PR path throws before a workspace is resolved) has no recipient or
        // artifact to bind — persist nothing and signal nothing.
        AgentJob noWorkspace = TestEntities.agentJob(); // no setWorkspace

        recorder().recordUndelivered(noWorkspace, new DeliveryContent("body", List.of(), List.of(), null));

        verify(feedbackRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void composerWithheld_recordsDedupReasonFromWithheldReport() {
        // The composer's near-duplicate collapse must land as COMPOSER_DEDUPED, not folded into the volume-cap
        // reason — an evaluation treats "redundant with a delivered lesson" differently from "over the cap".
        var kept = problem();
        var deduped = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(kept, deduped));
        var delivery = new DeliveryContent(
                "body",
                List.of(),
                List.of(new WithheldObservation(
                        deduped.getOccurrenceKey(), FeedbackSuppressionReason.COMPOSER_DEDUPED)),
                null);

        recorder().record(job(), delivery, ArtifactKinds.PULL_REQUEST, List.of(), "summary-ref", null);

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository, atLeast(2)).save(saved.capture());
        assertThat(saved.getAllValues().stream()
                        .filter(f -> f.getDeliveryState() == FeedbackDeliveryState.SUPPRESSED)
                        .map(Feedback::getSuppressionReason))
                .containsExactly(FeedbackSuppressionReason.COMPOSER_DEDUPED);
    }

    @Test
    void recordNothingToPost_recordsTheWithheldStrengthWakesTheLanesAndOpensNoApprovalItem() {
        Observation withheld = strength();
        lenient().when(withheld.getOccurrenceKey()).thenReturn("occ-withheld");
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(withheld));

        recorder()
                .recordNothingToPost(
                        job(),
                        new DeliveryContent(
                                null,
                                List.of(),
                                List.of(new WithheldObservation(
                                        "occ-withheld", FeedbackSuppressionReason.COMPOSER_WITHHELD)),
                                List.of()));

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryState()).isEqualTo(FeedbackDeliveryState.SUPPRESSED);
        assertThat(saved.getValue().getSuppressionReason()).isEqualTo(FeedbackSuppressionReason.COMPOSER_WITHHELD);
        verify(eventPublisher).publishEvent(any(PracticeFeedbackPreparationRequestedEvent.class));
    }

    @Test
    void shouldRetainSuppressedEvidenceWhenAProposalHasNoSafePlacement() {
        Observation bad = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(bad));
        var delivery = new DeliveryContent("<iframe></iframe>", List.of(), List.of(), null);

        recorder().recordProposal(job(), delivery);

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryState()).isEqualTo(FeedbackDeliveryState.SUPPRESSED);
        assertThat(saved.getValue().getSuppressionReason()).isEqualTo(FeedbackSuppressionReason.EMPTY_AFTER_SANITIZE);
        assertThat(saved.getValue().getBody()).isEqualTo(delivery.mrNote());
        UUID observationId = bad.getId();
        verify(feedbackObservationRepository).insertIfAbsent(any(), eq(observationId), eq("PRIMARY"), anyInt());
    }

    @Test
    void recordSuppressedUnit_persistsGateReasonAndBody_bindsObservations_noConversationSignal() {
        // A closed PR withholds more than the note on the work, so the whole review collapses to ONE suppressed
        // unit and no lane is woken to re-raise its loci.
        Observation bad = problem();
        Observation good = strength();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(bad, good));

        recorder()
                .recordSuppressedUnit(
                        job(),
                        new DeliveryContent("the withheld advice", List.of(), List.of(), null),
                        FeedbackSuppressionReason.ARTIFACT_CLOSED);

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        Feedback unit = saved.getValue();
        assertThat(unit.getDeliveryState()).isEqualTo(FeedbackDeliveryState.SUPPRESSED);
        assertThat(unit.getSuppressionReason()).isEqualTo(FeedbackSuppressionReason.ARTIFACT_CLOSED);
        assertThat(unit.getBody()).isEqualTo("the withheld advice");
        assertThat(unit.getPosition()).isEqualTo(5000);
        verify(feedbackObservationRepository, times(2)).insertIfAbsent(any(), any(), any(), anyInt());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void recordSuppressedUnit_noOps_whenDeliveredUnitAlreadyExists() {
        FeedbackLedgerRecorder rec = recorder();
        when(feedbackRepository.existsByAgentJobIdAndPosition(any(), eq(0))).thenReturn(true);

        rec.recordSuppressedUnit(
                job(),
                new DeliveryContent("body", List.of(), List.of(), null),
                FeedbackSuppressionReason.ARTIFACT_MERGED);

        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void shouldReferenceLiveUnitWithoutSupersedingWhenReReviewIsSuppressed() {
        Observation bad = problem();
        UUID liveFeedbackId = UUID.randomUUID();
        FeedbackLedgerRecorder rec = recorder();
        FeedbackPlacement livePlacement = mock(FeedbackPlacement.class);
        when(livePlacement.getFeedbackId()).thenReturn(liveFeedbackId);
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(bad));
        when(feedbackPlacementRepository.findLatestDeliveredSummary(any())).thenReturn(Optional.of(livePlacement));

        rec.recordSuppressedUnit(
                job(),
                new DeliveryContent("would have updated", List.of(), List.of(), null),
                FeedbackSuppressionReason.INSTANCE_SILENCED);

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getReplacesId()).isEqualTo(liveFeedbackId);
        verify(feedbackRepository, never()).supersedeDelivered(1L, liveFeedbackId);
    }

    @Test
    void shouldRecordOnlyLandedPlacementAndObservationWhenInlineDeliveryIsPartiallySuppressed() {
        Observation landed = problem();
        Observation suppressed = problem();
        when(landed.getOccurrenceKey()).thenReturn("key-1");
        lenient().when(landed.getRecurrenceKey()).thenReturn("shared-location");
        when(suppressed.getOccurrenceKey()).thenReturn("key-2");
        lenient().when(suppressed.getRecurrenceKey()).thenReturn("shared-location");
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(landed, suppressed));
        DeliveryContent delivery = new DeliveryContent(
                "summary",
                List.of(
                        new DiffNote("src/Foo.java", 10, null, "landed", "observation:key-1", null),
                        new DiffNote("src/Foo.java", 10, null, "suppressed", "observation:key-2", null)),
                List.of(),
                null);
        InlineFeedbackChannel.DeliveredSignal signal = new InlineFeedbackChannel.DeliveredSignal(
                "observation:key-1",
                new FeedbackAnchor.DiffAnchor("src/Foo.java", 10, null),
                InlineFeedbackChannel.Disposition.POSTED,
                "note-1",
                "discussion-1",
                "https://gitlab.example.com/a/b/-/merge_requests/1#note_123");
        FeedbackLedgerRecorder recorder = recorder();
        AgentJob job = job();

        recorder.record(job, delivery, ArtifactKinds.PULL_REQUEST, List.of(signal), null, null);
        recorder.recordSuppressedRemainder(
                job, delivery, FeedbackSuppressionReason.INSTANCE_SILENCED, List.of("observation:key-2"));

        ArgumentCaptor<ProviderPlacement> placement = ArgumentCaptor.forClass(ProviderPlacement.class);
        verify(feedbackPlacementRepository).insertProviderPlacementIfAbsent(placement.capture());
        assertThat(placement.getValue().anchorPath()).isEqualTo("src/Foo.java");
        assertThat(placement.getValue().postedCommentUrl())
                .isEqualTo("https://gitlab.example.com/a/b/-/merge_requests/1#note_123");

        ArgumentCaptor<Feedback> feedback = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository, times(2)).save(feedback.capture());
        assertThat(feedback.getAllValues())
                .extracting(Feedback::getDeliveryState)
                .containsExactly(FeedbackDeliveryState.DELIVERED, FeedbackDeliveryState.SUPPRESSED);

        ArgumentCaptor<UUID> evidence = ArgumentCaptor.forClass(UUID.class);
        verify(feedbackObservationRepository, times(2)).insertIfAbsent(any(), evidence.capture(), any(), anyInt());
        assertThat(evidence.getAllValues()).containsExactly(landed.getId(), suppressed.getId());
    }

    @Test
    void shouldNotPublishConversationAfterReleaseWhenCycleWasSuppressed() {
        Observation observation = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(observation));

        recorder()
                .recordWithoutConversation(
                        job(),
                        new DeliveryContent("landed summary", List.of(), List.of(), null),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(),
                        "summary-ref",
                        null);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void deliveredUnit_skipsSummaryPlacement_whenNoSummaryCommentExists() {
        // A summary-less delivery (body sanitised to blank but inline notes landed): the DELIVERED unit must
        // not claim a SUMMARY posting that never happened.
        var observation = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(observation));
        AgentJob job = job(); // deliveryCommentId stays null

        recorder()
                .record(
                        job,
                        new DeliveryContent("body", List.of(), List.of(), null),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(),
                        null,
                        null);

        verify(feedbackPlacementRepository, never()).insertProviderPlacementIfAbsent(any());
    }

    @Test
    void shouldPersistTheDispatchSummaryReferenceInsteadOfMutableJobState() {
        var observation = problem();
        when(observationRepository.findByAgentJobId(any(), anyLong())).thenReturn(List.of(observation));
        AgentJob job = job();
        job.setDeliveryCommentId("stale-job-ref");

        recorder()
                .record(
                        job,
                        new DeliveryContent("body", List.of(), List.of(), null),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(),
                        "dispatch-ref",
                        null);

        var placement = ArgumentCaptor.forClass(ProviderPlacement.class);
        verify(feedbackPlacementRepository).insertProviderPlacementIfAbsent(placement.capture());
        assertThat(placement.getValue().placementType()).isEqualTo(PlacementType.SUMMARY.name());
        assertThat(placement.getValue().postedCommentRef()).isEqualTo("dispatch-ref");
    }

    /** A review with no summary and one line note written from the given observations. */
    private static DeliveryContent inlineOnly(String deliveryKey, Observation... writtenFrom) {
        List<String> contributors =
                Arrays.stream(writtenFrom).map(Observation::getOccurrenceKey).toList();
        return new DeliveryContent(
                null,
                List.of(new DiffNote(
                        "src/Foo.java", 10, null, "Name the test after the behaviour.", deliveryKey, contributors)),
                List.of(),
                List.of());
    }

    private static void withPractice(Observation observation, String slug) {
        Practice practice = mock(Practice.class);
        lenient().when(practice.getSlug()).thenReturn(slug);
        lenient().when(observation.getPractice()).thenReturn(practice);
    }

    private AgentJob job() {
        AgentJob job = TestEntities.agentJob();
        job.setWorkspace(TestEntities.workspace(1L));
        return job;
    }

    private Observation strength() {
        Observation pf = mock(Observation.class);

        lenient().when(pf.getId()).thenReturn(UUID.randomUUID());
        lenient().when(pf.getOutcome()).thenReturn(Outcome.MET);
        lenient().when(pf.getSeverity()).thenReturn(null); // GOOD strengths carry no severity (ADR 0022)
        lenient().when(pf.getArtifactKind()).thenReturn(ArtifactKinds.PULL_REQUEST);
        lenient().when(pf.getArtifactId()).thenReturn(100L);
        lenient().when(pf.getAboutUserId()).thenReturn(7L);
        return pf;
    }

    private Observation notApplicable() {
        Observation pf = mock(Observation.class);

        lenient().when(pf.getId()).thenReturn(UUID.randomUUID());
        lenient().when(pf.getOutcome()).thenReturn(Outcome.NOT_APPLICABLE);

        lenient().when(pf.getSeverity()).thenReturn(null);
        lenient().when(pf.getArtifactKind()).thenReturn(ArtifactKinds.PULL_REQUEST);
        lenient().when(pf.getArtifactId()).thenReturn(100L);
        lenient().when(pf.getAboutUserId()).thenReturn(7L);
        return pf;
    }

    private Observation problem() {
        Observation pf = mock(Observation.class);

        UUID id = UUID.randomUUID();
        lenient().when(pf.getId()).thenReturn(id);
        lenient().when(pf.getOccurrenceKey()).thenReturn("occ-" + id);
        lenient().when(pf.getOutcome()).thenReturn(Outcome.NOT_MET);
        lenient().when(pf.getSeverity()).thenReturn(Severity.MINOR);
        lenient().when(pf.getArtifactKind()).thenReturn(ArtifactKinds.PULL_REQUEST);
        lenient().when(pf.getArtifactId()).thenReturn(100L);
        // about_user_id is the recipient the recorder binds feedback to.
        lenient().when(pf.getAboutUserId()).thenReturn(7L);
        return pf;
    }
}
