package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.WithheldObservation;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/** The delivered-feedback ledger writer (ADR 0021). */
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
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
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

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
                "thread-ref");

        recorder().recordApprovedPlacements(feedback, "summary-ref", List.of(signal));

        verify(feedbackPlacementRepository)
                .insertProviderPlacementIfAbsent(
                        argThat(placement -> placement.feedbackId().equals(feedback.getId())
                                && placement.placementType().equals("SUMMARY")
                                && placement.postedCommentRef().equals("summary-ref")));
        verify(feedbackPlacementRepository)
                .insertProviderPlacementIfAbsent(
                        argThat(placement -> placement.feedbackId().equals(feedback.getId())
                                && placement.placementType().equals("INLINE")
                                && "RANGE".equals(placement.anchorKind())
                                && "src/Review.java".equals(placement.anchorPath())
                                && Integer.valueOf(9).equals(placement.anchorStartLine())
                                && Integer.valueOf(12).equals(placement.anchorEndLine())
                                && "NEW".equals(placement.anchorSide())
                                && placement.postedCommentRef().equals("inline-ref")));
    }

    @Test
    void composerWithheld_bindsEachFindingExactlyOnce_keptToDeliveredDroppedToSuppressed() {
        List<Observation> observations = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            observations.add(problem());
        }
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(observations);
        var delivery = new DeliveryContent(
                "body",
                List.of(),
                List.of(
                        new WithheldObservation(
                                observations.get(3).getOccurrenceKey(), FeedbackSuppressionReason.VOLUME_CAPPED),
                        new WithheldObservation(
                                observations.get(4).getOccurrenceKey(), FeedbackSuppressionReason.VOLUME_CAPPED)));

        recorder().record(job(), delivery, ArtifactKinds.PULL_REQUEST, List.of(), "summary-ref", false);

        // Every observation bound exactly once across ALL units (3 to DELIVERED + 1 each to the 2 SUPPRESSED units).
        var boundFindingIds = ArgumentCaptor.forClass(UUID.class);
        verify(feedbackObservationRepository, org.mockito.Mockito.times(5))
                .insertIfAbsent(any(), boundFindingIds.capture(), any(), anyInt());
        assertThat(boundFindingIds.getAllValues()).doesNotHaveDuplicates().hasSize(5);

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository, org.mockito.Mockito.atLeast(3)).save(saved.capture());
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
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(observations);

        recorder()
                .record(
                        job(),
                        new DeliveryContent("body", List.of(), List.of()),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(),
                        "summary-ref",
                        false);

        verify(feedbackObservationRepository, org.mockito.Mockito.times(5))
                .insertIfAbsent(any(), any(), any(), anyInt());
        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryState()).isEqualTo(FeedbackDeliveryState.DELIVERED);
    }

    @Test
    void inlinePlacement_persistsExternalRefFromMatchingSignal() {
        // A3: the INLINE placement must carry the durable vendor handle the channel reported, not a hardcoded
        // null. The note and its DeliveredSignal share a findingFingerprint, so the signal's externalRef lands
        // on the saved FeedbackPlacement.
        var observation = problem();
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(observation));

        var note = new DiffNote("src/Foo.java", 10, null, "Fix this", "ck-foo-10");
        var signal = new InlineFeedbackChannel.DeliveredSignal(
                "ck-foo-10",
                new FeedbackAnchor.DiffAnchor("src/Foo.java", 10, null),
                InlineFeedbackChannel.Disposition.POSTED,
                "note-gid-42",
                "discussion-gid-7");

        recorder()
                .record(
                        job(),
                        new DeliveryContent("body", List.of(note), List.of()),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(signal),
                        "summary-ref",
                        true);

        var placements = ArgumentCaptor.forClass(FeedbackPlacement.class);
        verify(feedbackPlacementRepository, org.mockito.Mockito.atLeastOnce()).save(placements.capture());
        FeedbackPlacement inline = placements.getAllValues().stream()
                .filter(p -> p.getPlacementType() == PlacementType.INLINE)
                .findFirst()
                .orElseThrow();
        assertThat(inline.getPostedCommentRef()).isEqualTo("note-gid-42");
    }

    @Test
    void shouldNotCreatePlacementWhenInlineSignalFailed() {
        var observation = problem();
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(observation));

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
                        new DeliveryContent("body", List.of(note), List.of()),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(signal),
                        null,
                        false);

        verify(feedbackPlacementRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void b2AndComposerWithheldOverlap_aSuppressedFindingIsNeverBoundTwice() {
        // An observation reaction suppression already withheld must NOT also be written as a composer-withheld
        // unit even when the composer reports its key — it is bound exactly once across all units.
        List<Observation> observations = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            observations.add(problem());
        }
        UUID b2Id = observations.get(5).getId(); // also reported withheld by the composer
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(observations);
        var recorder = recorder();
        when(feedbackObservationRepository.findObservationIdsSuppressedForJob(any()))
                .thenReturn(List.of(b2Id));
        var delivery = new DeliveryContent(
                "body",
                List.of(),
                List.of(new WithheldObservation(
                        observations.get(5).getOccurrenceKey(), FeedbackSuppressionReason.VOLUME_CAPPED)));

        recorder.record(job(), delivery, ArtifactKinds.PULL_REQUEST, List.of(), "summary-ref", false);

        var bound = ArgumentCaptor.forClass(UUID.class);
        verify(feedbackObservationRepository, org.mockito.Mockito.atLeastOnce())
                .insertIfAbsent(any(), bound.capture(), any(), anyInt());
        assertThat(bound.getAllValues()).doesNotHaveDuplicates().doesNotContain(b2Id);
    }

    @Test
    void alreadySuppressedFinding_isExcludedFromDeliveredUnit() {
        // An observation withheld earlier in the flow (reaction suppression wrote a SUPPRESSED unit for it) must
        // NOT also be bound to the DELIVERED unit — else it is double-counted as delivered.
        var kept = problem();
        var b2Suppressed = problem();
        UUID keptId = kept.getId();
        UUID b2Id = b2Suppressed.getId();
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(kept, b2Suppressed));
        var recorder = recorder();
        when(feedbackObservationRepository.findObservationIdsSuppressedForJob(any()))
                .thenReturn(List.of(b2Id));

        recorder.record(
                job(),
                new DeliveryContent("body", List.of(), List.of()),
                ArtifactKinds.PULL_REQUEST,
                List.of(),
                "summary-ref",
                false);

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
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(observations);

        recorder()
                .record(
                        job(),
                        new DeliveryContent("body", List.of(), List.of()),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(),
                        "summary-ref",
                        false);

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).isNotEmpty().allSatisfy(f -> {
            assertThat(f.getRecipientUserId()).isEqualTo(7L);
            assertThat(f.getAboutUserId()).isEqualTo(f.getRecipientUserId());
        });
    }

    @Test
    void reReview_proposal_carriesItsThreadAndRetiresTheUndecidedOneBeforeIt() {
        Observation observation = problem();
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(observation));
        when(feedbackRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AgentJob job = job();
        when(commentFormatter.appendDisclosure("proposed body", job)).thenReturn("proposed body\n\nAI disclosure");
        when(commentFormatter.appendInlineFeedbackPrompt("inline body")).thenReturn("inline body\n\nAI disclosure");
        var metadata = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
        metadata.put("commit_sha", "abc123");
        job.setMetadata(metadata);

        recorder()
                .recordProposal(
                        job,
                        new DeliveryContent(
                                "proposed body",
                                List.of(new DiffNote("src/Example.java", 12, 14, "inline body", "rk")),
                                List.of()),
                        List.of(new PracticeDetectionResultParser.ValidatedObservation(
                                "practice",
                                "summary",
                                AssessmentStatus.ASSESSED,
                                Presence.ABSENT,
                                Assessment.GOOD,
                                Severity.MAJOR,
                                null,
                                "reasoning",
                                new ObservationKeys(observation.getOccurrenceKey(), "rk"))));

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getThreadKey()).isNotBlank();
        assertThat(saved.getValue().getReviewedRevision()).isEqualTo("abc123");
        assertThat(saved.getValue().getDeliveryState()).isEqualTo(FeedbackDeliveryState.AWAITING_APPROVAL);
        verify(eventPublisher)
                .publishEvent(new de.tum.cit.aet.hephaestus.agent.handler.conversation.PracticeDetectionDeliveredEvent(
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
    void reReview_priorDeliveredUnit_isSupersededAndNewRowReplacesIt() {
        // B1: the re-review SUPERSEDED branch (every other test stubs the prior lookup to Optional.empty()).
        // A prior live DELIVERED unit on this continuity line → the new row's replacesId points at it AND the
        // prior is flipped to SUPERSEDED via the native updateState, AFTER the new row lands (never zero live).
        var observation = problem();
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(observation));
        var recorder = recorder();
        UUID priorId = UUID.randomUUID();
        FeedbackPlacement priorSummary = mock(FeedbackPlacement.class);
        when(priorSummary.getFeedbackId()).thenReturn(priorId);
        when(feedbackPlacementRepository.findLatestDeliveredSummary(any())).thenReturn(Optional.of(priorSummary));

        recorder.record(
                job(),
                new DeliveryContent("body", List.of(), List.of()),
                ArtifactKinds.PULL_REQUEST,
                List.of(),
                "summary-ref",
                false);

        // The prior is superseded by id+name.
        verify(feedbackRepository).updateState(priorId, FeedbackDeliveryState.SUPERSEDED.name());
        // The freshly saved DELIVERED unit carries replacesId = the prior id.
        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
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
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(strength, problem, na));

        recorder()
                .record(
                        job(),
                        new DeliveryContent("body", List.of(), List.of()),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(),
                        "summary-ref",
                        false);

        // Two bindings (problem PRIMARY + strength SUPPORTING); the NA is never bound.
        var boundId = ArgumentCaptor.forClass(UUID.class);
        var role = ArgumentCaptor.forClass(String.class);
        var ordinal = ArgumentCaptor.forClass(Integer.class);
        verify(feedbackObservationRepository, org.mockito.Mockito.times(2))
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
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(observation));
        var recorder = recorder();
        recorder.record(
                job(),
                new DeliveryContent("body", List.of(), List.of()),
                ArtifactKinds.PULL_REQUEST,
                List.of(),
                null,
                false);

        verify(feedbackRepository, org.mockito.Mockito.never()).save(any());
        verify(feedbackRepository, org.mockito.Mockito.never()).updateState(any(), any());
        verify(feedbackObservationRepository, org.mockito.Mockito.never())
                .insertIfAbsent(any(), any(), any(), anyInt());
    }

    @Test
    void inlineOnlyDeliveryRecordsInlinePlacementWithoutSupersedingSummary() {
        var observation = problem();
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(observation));
        var note = new DiffNote("src/Foo.java", 10, null, "Fix this", "ck-foo");
        var signal = new InlineFeedbackChannel.DeliveredSignal(
                "ck-foo",
                new FeedbackAnchor.DiffAnchor("src/Foo.java", 10, null),
                InlineFeedbackChannel.Disposition.POSTED,
                "note-1",
                "disc-1");

        recorder()
                .record(
                        job(),
                        new DeliveryContent(null, List.of(note), List.of()),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(signal),
                        null,
                        true);

        var savedFeedback = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(savedFeedback.capture());
        assertThat(savedFeedback.getValue().getBody()).isNull();
        assertThat(savedFeedback.getValue().getReplacesId()).isNull();
        verify(feedbackRepository, org.mockito.Mockito.never()).updateState(any(), any());

        var savedPlacement = ArgumentCaptor.forClass(FeedbackPlacement.class);
        verify(feedbackPlacementRepository).save(savedPlacement.capture());
        assertThat(savedPlacement.getValue().getPlacementType()).isEqualTo(PlacementType.INLINE);
        assertThat(savedPlacement.getValue().getPostedCommentRef()).isEqualTo("note-1");
        verify(feedbackPlacementRepository, org.mockito.Mockito.never()).findLatestDeliveredSummary(any());
    }

    @Test
    void recordUndelivered_persistsFailedBody_bindsFindings_andSignalsConversation() {
        // A direct-delivery failure: the composed body must be persisted as a FAILED IN_CONTEXT unit (auditable +
        // dashboard-visible) AND the conversational channel must be signalled so it can pick up the loci the
        // developer never saw in-context.
        Observation bad = problem();
        Observation good = strength();
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(bad, good));

        recorder().recordUndelivered(job(), new DeliveryContent("the advice that never landed", List.of(), List.of()));

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        Feedback unit = saved.getValue();
        assertThat(unit.getDeliveryState()).isEqualTo(FeedbackDeliveryState.FAILED);
        assertThat(unit.getBody()).isEqualTo("the advice that never landed");
        // Ordinal 4000 keeps the FAILED unit clear of the DELIVERED(0)/SUPPRESSED(1000)/policy(2000)/conv(3000) bases.
        assertThat(unit.getPosition()).isEqualTo(4000);
        // Both assessed observations are bound (BAD as PRIMARY, GOOD as SUPPORTING); NA would be excluded.
        verify(feedbackObservationRepository, org.mockito.Mockito.times(2))
                .insertIfAbsent(any(), any(), any(), anyInt());
        // The chat and in-app lanes are signalled despite the failed direct delivery.
        verify(eventPublisher)
                .publishEvent(any(
                        de.tum.cit.aet.hephaestus.agent.handler.conversation.PracticeDetectionDeliveredEvent.class));
    }

    @Test
    void recordUndelivered_noOps_whenDeliveredUnitAlreadyExists() {
        // A DELIVERED unit at ordinal 0 already exists (a prior run landed): never write a contradictory FAILED
        // unit and never signal — record() already handled both.
        FeedbackLedgerRecorder rec = recorder();
        // Override AFTER recorder() installed the anyInt()->false default, so eq(0) wins for the IN_CONTEXT unit.
        when(feedbackRepository.existsByAgentJobIdAndPosition(any(), eq(0))).thenReturn(true);

        rec.recordUndelivered(job(), new DeliveryContent("body", List.of(), List.of()));

        verify(feedbackRepository, org.mockito.Mockito.never()).save(any());
        // Typed, not any(): ApplicationEventPublisher.publishEvent is overloaded, and a bare any() binds to
        // the ApplicationEvent overload this code never calls — which passes whatever the code does.
        verify(eventPublisher, org.mockito.Mockito.never())
                .publishEvent(any(
                        de.tum.cit.aet.hephaestus.agent.handler.conversation.PracticeDetectionDeliveredEvent.class));
    }

    @Test
    void recordUndelivered_wakesTheLongitudinalLanes_evenWithNothingToPostOnTheWork() {
        // The composer can decline to say anything on the merge request and still have written a message
        // about the habit behind it. Waking the private lanes used to be gated on there being a note, so
        // those messages were composed and then dropped until the hourly sweeper found them.
        recorder().recordUndelivered(job(), null);

        verify(eventPublisher)
                .publishEvent(any(
                        de.tum.cit.aet.hephaestus.agent.handler.conversation.PracticeDetectionDeliveredEvent.class));
        verify(feedbackRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void shouldRecordOneSuppressionWithoutConversationWhenUndeliveredDuringSilentMode() {
        Observation bad = problem();
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(bad));
        FeedbackLedgerRecorder recorder = recorder();
        when(egressGuard.deliveryAllowed(any())).thenReturn(false);

        recorder.recordUndelivered(job(), new DeliveryContent("body", List.of(), List.of()));

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryState()).isEqualTo(FeedbackDeliveryState.SUPPRESSED);
        assertThat(saved.getValue().getSuppressionReason()).isEqualTo(FeedbackSuppressionReason.INSTANCE_SILENCED);
        verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any());
    }

    @Test
    void recordUndelivered_reSignalsButDoesNotRepersist_onFailedRetry() {
        // A failing retry: the FAILED unit (ordinal 4000) was already written. Re-signalling the conversation is
        // harmless (idempotent listener), but the FAILED row must NOT be persisted twice.
        FeedbackLedgerRecorder rec = recorder();
        Observation bad = problem();
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(bad));
        // Past the DELIVERED(0) guard (default false), but the FAILED(4000) unit already exists (retry).
        when(feedbackRepository.existsByAgentJobIdAndPosition(any(), eq(4000))).thenReturn(true);

        rec.recordUndelivered(job(), new DeliveryContent("body", List.of(), List.of()));

        verify(eventPublisher)
                .publishEvent(any(
                        de.tum.cit.aet.hephaestus.agent.handler.conversation.PracticeDetectionDeliveredEvent.class));
        verify(feedbackRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void recordUndelivered_noOps_whenJobHasNoWorkspace() {
        // A no-workspace integrity failure (PR path throws before a workspace is resolved) has no recipient or
        // artifact to bind — persist nothing and signal nothing.
        AgentJob noWorkspace = TestEntities.agentJob(); // no setWorkspace

        recorder().recordUndelivered(noWorkspace, new DeliveryContent("body", List.of(), List.of()));

        verify(feedbackRepository, org.mockito.Mockito.never()).save(any());
        verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any());
    }

    @Test
    void composerWithheld_recordsDedupReasonFromWithheldReport() {
        // The composer's near-duplicate collapse must land as COMPOSER_DEDUPED, not folded into the volume-cap
        // reason — an evaluation treats "redundant with a delivered lesson" differently from "over the cap".
        var kept = problem();
        var deduped = problem();
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(kept, deduped));
        var delivery = new DeliveryContent(
                "body",
                List.of(),
                List.of(new WithheldObservation(
                        deduped.getOccurrenceKey(), FeedbackSuppressionReason.COMPOSER_DEDUPED)));

        recorder().record(job(), delivery, ArtifactKinds.PULL_REQUEST, List.of(), "summary-ref", false);

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository, org.mockito.Mockito.atLeast(2)).save(saved.capture());
        assertThat(saved.getAllValues().stream()
                        .filter(f -> f.getDeliveryState() == FeedbackDeliveryState.SUPPRESSED)
                        .map(Feedback::getSuppressionReason))
                .containsExactly(FeedbackSuppressionReason.COMPOSER_DEDUPED);
    }

    @Test
    void recordSuppressedUnit_persistsGateReasonAndBody_bindsFindings_noConversationSignal() {
        // A gate decision applies to every channel, so the whole review collapses to ONE suppressed unit
        // and the loci must not be re-raised as a conversational signal in a mentor turn.
        Observation bad = problem();
        Observation good = strength();
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(bad, good));

        recorder()
                .recordSuppressedUnit(
                        job(),
                        new DeliveryContent("the withheld advice", List.of(), List.of()),
                        FeedbackSuppressionReason.ARTIFACT_CLOSED);

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        Feedback unit = saved.getValue();
        assertThat(unit.getDeliveryState()).isEqualTo(FeedbackDeliveryState.SUPPRESSED);
        assertThat(unit.getSuppressionReason()).isEqualTo(FeedbackSuppressionReason.ARTIFACT_CLOSED);
        assertThat(unit.getBody()).isEqualTo("the withheld advice");
        assertThat(unit.getPosition()).isEqualTo(5000);
        verify(feedbackObservationRepository, org.mockito.Mockito.times(2))
                .insertIfAbsent(any(), any(), any(), anyInt());
        verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any());
    }

    @Test
    void recordSuppressedUnit_noOps_whenDeliveredUnitAlreadyExists() {
        FeedbackLedgerRecorder rec = recorder();
        when(feedbackRepository.existsByAgentJobIdAndPosition(any(), eq(0))).thenReturn(true);

        rec.recordSuppressedUnit(
                job(), new DeliveryContent("body", List.of(), List.of()), FeedbackSuppressionReason.ARTIFACT_MERGED);

        verify(feedbackRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void shouldReferenceLiveUnitWithoutSupersedingWhenReReviewIsSuppressed() {
        Observation bad = problem();
        UUID liveFeedbackId = UUID.randomUUID();
        FeedbackLedgerRecorder rec = recorder();
        FeedbackPlacement livePlacement = mock(FeedbackPlacement.class);
        when(livePlacement.getFeedbackId()).thenReturn(liveFeedbackId);
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(bad));
        when(feedbackPlacementRepository.findLatestDeliveredSummary(any())).thenReturn(Optional.of(livePlacement));

        rec.recordSuppressedUnit(
                job(),
                new DeliveryContent("would have updated", List.of(), List.of()),
                FeedbackSuppressionReason.INSTANCE_SILENCED);

        var saved = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getReplacesId()).isEqualTo(liveFeedbackId);
        verify(feedbackRepository, org.mockito.Mockito.never())
                .updateState(liveFeedbackId, FeedbackDeliveryState.SUPERSEDED.name());
    }

    @Test
    void shouldRecordOnlyLandedPlacementAndFindingWhenInlineDeliveryIsPartiallySuppressed() {
        Observation landed = problem();
        Observation suppressed = problem();
        when(landed.getRecurrenceKey()).thenReturn("key-1");
        when(suppressed.getRecurrenceKey()).thenReturn("key-2");
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(landed, suppressed));
        DeliveryContent delivery = new DeliveryContent(
                "summary",
                List.of(
                        new DiffNote("src/Foo.java", 10, null, "landed", "key-1"),
                        new DiffNote("src/Bar.java", 20, null, "suppressed", "key-2")),
                List.of());
        InlineFeedbackChannel.DeliveredSignal signal = new InlineFeedbackChannel.DeliveredSignal(
                "key-1",
                new FeedbackAnchor.DiffAnchor("src/Foo.java", 10, null),
                InlineFeedbackChannel.Disposition.POSTED,
                "note-1",
                "discussion-1");
        FeedbackLedgerRecorder recorder = recorder();
        AgentJob job = job();

        recorder.record(job, delivery, ArtifactKinds.PULL_REQUEST, List.of(signal), null, true);
        recorder.recordSuppressedRemainder(
                job, delivery, FeedbackSuppressionReason.INSTANCE_SILENCED, List.of("key-2"));

        ArgumentCaptor<FeedbackPlacement> placement = ArgumentCaptor.forClass(FeedbackPlacement.class);
        verify(feedbackPlacementRepository).save(placement.capture());
        assertThat(placement.getValue().getAnchorPath()).isEqualTo("src/Foo.java");

        ArgumentCaptor<Feedback> feedback = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository, org.mockito.Mockito.times(2)).save(feedback.capture());
        assertThat(feedback.getAllValues())
                .extracting(Feedback::getDeliveryState)
                .containsExactly(FeedbackDeliveryState.DELIVERED, FeedbackDeliveryState.SUPPRESSED);

        ArgumentCaptor<UUID> evidence = ArgumentCaptor.forClass(UUID.class);
        verify(feedbackObservationRepository, org.mockito.Mockito.times(2))
                .insertIfAbsent(any(), evidence.capture(), any(), anyInt());
        assertThat(evidence.getAllValues()).containsExactly(landed.getId(), suppressed.getId());
    }

    @Test
    void shouldNotPublishConversationAfterReleaseWhenCycleWasSuppressed() {
        Observation observation = problem();
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(observation));

        recorder()
                .recordWithoutConversation(
                        job(),
                        new DeliveryContent("landed summary", List.of(), List.of()),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(),
                        "summary-ref",
                        false);

        verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any());
    }

    @Test
    void deliveredUnit_skipsSummaryPlacement_whenNoSummaryCommentExists() {
        // A summary-less delivery (body sanitised to blank but inline notes landed): the DELIVERED unit must
        // not claim a SUMMARY posting that never happened.
        var observation = problem();
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(observation));
        AgentJob job = job(); // deliveryCommentId stays null

        recorder()
                .record(
                        job,
                        new DeliveryContent("body", List.of(), List.of()),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(),
                        null,
                        false);

        verify(feedbackPlacementRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void shouldPersistTheDispatchSummaryReferenceInsteadOfMutableJobState() {
        var observation = problem();
        when(observationRepository.findByAgentJobId(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(observation));
        AgentJob job = job();
        job.setDeliveryCommentId("stale-job-ref");

        recorder()
                .record(
                        job,
                        new DeliveryContent("body", List.of(), List.of()),
                        ArtifactKinds.PULL_REQUEST,
                        List.of(),
                        "dispatch-ref",
                        false);

        var placement = ArgumentCaptor.forClass(FeedbackPlacement.class);
        verify(feedbackPlacementRepository).save(placement.capture());
        assertThat(placement.getValue().getPlacementType()).isEqualTo(PlacementType.SUMMARY);
        assertThat(placement.getValue().getPostedCommentRef()).isEqualTo("dispatch-ref");
    }

    private AgentJob job() {
        AgentJob job = TestEntities.agentJob();
        job.setWorkspace(TestEntities.workspace(1L));
        return job;
    }

    private Observation strength() {
        Observation pf = mock(Observation.class);
        lenient()
                .when(pf.getOutcome())
                .thenAnswer(invocation ->
                        de.tum.cit.aet.hephaestus.practices.model.Outcome.of(pf.getPresence(), pf.getAssessment()));
        lenient().when(pf.getId()).thenReturn(UUID.randomUUID());
        lenient().when(pf.getPresence()).thenReturn(Presence.PRESENT);
        org.mockito.Mockito.lenient().when(pf.getAssessmentStatus()).thenReturn(AssessmentStatus.ASSESSED);
        lenient().when(pf.getAssessment()).thenReturn(Assessment.GOOD);
        lenient().when(pf.getSeverity()).thenReturn(null); // GOOD strengths carry no severity (ADR 0022)
        lenient().when(pf.getArtifactKind()).thenReturn(ArtifactKinds.PULL_REQUEST);
        lenient().when(pf.getArtifactId()).thenReturn(100L);
        lenient().when(pf.getAboutUserId()).thenReturn(7L);
        return pf;
    }

    private Observation notApplicable() {
        Observation pf = mock(Observation.class);
        lenient()
                .when(pf.getOutcome())
                .thenAnswer(invocation ->
                        de.tum.cit.aet.hephaestus.practices.model.Outcome.of(pf.getPresence(), pf.getAssessment()));
        lenient().when(pf.getId()).thenReturn(UUID.randomUUID());
        lenient().when(pf.getAssessmentStatus()).thenReturn(AssessmentStatus.NOT_APPLICABLE);
        lenient().when(pf.getAssessment()).thenReturn(null); // NA carries no valence (ADR 0022)
        lenient().when(pf.getSeverity()).thenReturn(null);
        lenient().when(pf.getArtifactKind()).thenReturn(ArtifactKinds.PULL_REQUEST);
        lenient().when(pf.getArtifactId()).thenReturn(100L);
        lenient().when(pf.getAboutUserId()).thenReturn(7L);
        return pf;
    }

    private Observation problem() {
        Observation pf = mock(Observation.class);
        lenient()
                .when(pf.getOutcome())
                .thenAnswer(invocation ->
                        de.tum.cit.aet.hephaestus.practices.model.Outcome.of(pf.getPresence(), pf.getAssessment()));
        UUID id = UUID.randomUUID();
        lenient().when(pf.getId()).thenReturn(id);
        lenient().when(pf.getOccurrenceKey()).thenReturn("occ-" + id);
        lenient().when(pf.getPresence()).thenReturn(Presence.ABSENT);
        org.mockito.Mockito.lenient().when(pf.getAssessmentStatus()).thenReturn(AssessmentStatus.ASSESSED);
        lenient().when(pf.getAssessment()).thenReturn(Assessment.GOOD);
        lenient().when(pf.getSeverity()).thenReturn(Severity.MINOR);
        lenient().when(pf.getArtifactKind()).thenReturn(ArtifactKinds.PULL_REQUEST);
        lenient().when(pf.getArtifactId()).thenReturn(100L);
        // about_user_id is the recipient the recorder binds feedback to.
        lenient().when(pf.getAboutUserId()).thenReturn(7L);
        return pf;
    }
}
