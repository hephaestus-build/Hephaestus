package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackDraftBuilder;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobInput;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.framework.RegisteredItemLabels;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.PracticePageFeedbackRouter;
import de.tum.cit.aet.hephaestus.practices.feedback.page.PracticePageFeedbackService;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponse;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackBasisInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedFeedback;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackProgress;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class FeedbackPreparationReceiptIntegrationTest extends AbstractFeedbackDeliveryIntegrationTest {
    @Autowired
    FeedbackContentDigest digests;

    @Autowired
    ReviewRepository reviews;

    @Autowired
    PracticePageFeedbackService page;

    @MockitoBean
    CurrentDeveloperLookup currentDeveloper;

    @MockitoBean
    ResultVisibilityPolicy resultVisibility;

    @MockitoBean
    PracticePageFeedbackRouter pageRouter;

    // This fixture's item has no mirrored work behind it, so the real labels are empty, which is also this
    // mock's default. Only the label mapping test below names one.
    @MockitoBean
    RegisteredItemLabels itemLabels;

    @Test
    void shouldReturnOnePersistedPreparationTimeAndCanonicalOrderedPackageDigest() {
        var receipt = store.prepareAll(workspace.getId(), reviewId, List.of(draft(true), conversation(developerId)));
        var pieceDigests =
                receipt.pieces().stream().map(PreparedFeedback::packageDigest).toList();
        assertThat(receipt.pieces()).hasSize(2);
        assertThat(receipt.pieces())
                .allSatisfy(piece -> assertThat(piece.preparedAt()).isEqualTo(receipt.preparedAt()));
        assertThat(receipt.preparedAt()).isEqualTo(current().getCreatedAt());
        assertThat(receipt.packageDigest()).isEqualTo(digests.orderedPackageDigest(pieceDigests));
        assertThat(receipt.pieces().getFirst().packageDigest())
                .isEqualTo(digests.current(workspace.getId(), feedbackId));
        var reversed = store.prepareAll(workspace.getId(), reviewId, List.of(conversation(developerId), draft(true)));
        assertThat(reversed.packageDigest()).isNotEqualTo(receipt.packageDigest());
        assertThat(reversed.preparedAt()).isAfterOrEqualTo(receipt.preparedAt());
        assertThat(reversed.pieces())
                .allSatisfy(piece -> assertThat(piece.preparedAt()).isEqualTo(reversed.preparedAt()));
        assertThat(store.prepareAll(workspace.getId(), reviewId, List.of(conversation(developerId), draft(true))))
                .isEqualTo(reversed);
        assertThat(reviews.findById(reviewId).orElseThrow().getPublicReviewAt()).isNull();
        assertThat(provider.summaryCreates).isZero();
        assertThat(provider.noteCreates).isZero();
    }

    @Test
    void shouldReturnTheSameRecordedReceiptOnReplayWithoutAnotherCopyOrSend() {
        var first = store.prepareAll(workspace.getId(), reviewId, List.of(draft(false)));
        var second = store.prepareAll(workspace.getId(), reviewId, List.of(draft(false)));
        assertThat(second).isEqualTo(first);
        assertThat(children()).hasSize(1);
        assertThat(sends.findById(feedbackId).orElseThrow().getAttempts()).isZero();
        assertThat(provider.summaryCreates).isZero();
    }

    @Test
    void shouldRollBackEveryPieceAndPreparationTimeWhenALaterPieceIsInvalid() {
        var invalid = conversation(developerId + 1);
        assertThatThrownBy(() -> store.prepareAll(workspace.getId(), reviewId, List.of(draft(false), invalid)))
                .isInstanceOf(RuntimeException.class);
        assertThat(feedback.findById(feedbackId)).isEmpty();
        assertThat(feedback.findById(invalid.id())).isEmpty();
        assertThat(sends.findById(feedbackId)).isEmpty();
        assertThat(children()).isEmpty();
        assertThat(reviews.findByIdAndWorkspaceId(reviewId, workspace.getId())
                        .orElseThrow()
                        .getPublicReviewAt())
                .isNull();
        assertThat(reviews.findById(reviewId)
                        .orElseThrow()
                        .getChecks()
                        .path("feedbackPreparationReceipts")
                        .isMissingNode())
                .isTrue();
        assertThat(provider.summaryCreates).isZero();
    }

    @Test
    void shouldPrepareAnEmptyPackageWithARealRecordedTime() {
        var empty = store.prepareAll(workspace.getId(), reviewId, List.of());
        assertThat(empty.pieces()).isEmpty();
        assertThat(empty.packageDigest()).isEqualTo(digests.orderedPackageDigest(List.of()));
        var review = reviews.findById(reviewId).orElseThrow();
        assertThat(review.getPublicReviewAt()).isNull();
        assertThat(review.getChecks()
                        .path("feedbackPreparationReceipts")
                        .path(empty.packageDigest())
                        .asString())
                .isEqualTo(empty.preparedAt().toString());
        assertThat(store.prepareAll(workspace.getId(), reviewId, List.of())).isEqualTo(empty);
    }

    @Test
    void shouldPreparePrivateFeedbackWithoutClaimingPublicCompletion() {
        var prepared = store.prepareAll(workspace.getId(), reviewId, List.of(conversation(developerId)));
        assertThat(prepared.pieces()).hasSize(1);
        assertThat(reviews.findById(reviewId).orElseThrow().getPublicReviewAt()).isNull();
        assertThat(store.prepareAll(workspace.getId(), reviewId, List.of(conversation(developerId))))
                .isEqualTo(prepared);
    }

    @Test
    void shouldPersistTheExactBasisAndCopyBeforeQueueingThePreparedFeedback() {
        var proposal = draft(true);
        store.prepareAll(workspace.getId(), reviewId, List.of(proposal));
        assertThat(current().getChecks()).containsKeys(FeedbackCheckStage.COMPOSITION, FeedbackCheckStage.AUTOMATIC);
        assertThat(jdbc.queryForList(
                        "SELECT body FROM feedback WHERE workspace_id=? AND id=?",
                        String.class,
                        workspace.getId(),
                        feedbackId))
                .containsExactly("Add a focused regression test.");
        assertThat(jdbc.queryForList(
                        "SELECT result_id FROM feedback_basis WHERE workspace_id=? AND" + " feedback_id=?",
                        UUID.class,
                        workspace.getId(),
                        feedbackId))
                .containsExactly(supportingResult.getId());
        assertThat(children())
                .extracting(copy -> copy.getId())
                .containsExactlyElementsOf(
                        proposal.copies().stream().map(FeedbackCopyInput::id).toList());
        assertThat(jdbc.queryForList(
                        "SELECT action || '|' || state FROM feedback_send WHERE" + " workspace_id=? AND feedback_id=?",
                        String.class,
                        workspace.getId(),
                        feedbackId))
                .containsExactly("POST|PENDING");
    }

    @Test
    void shouldRetainThePublicCompletionAndOtherChecksWhenRecordingPreparation() {
        var publicAt = AT.plusSeconds(60);
        jdbc.update(
                "UPDATE review SET"
                        + " public_review_at=?,checks=jsonb_build_object('cancellationRequested',false)"
                        + " WHERE workspace_id=? AND id=?",
                Timestamp.from(publicAt),
                workspace.getId(),
                reviewId);
        var receipt = store.prepareAll(workspace.getId(), reviewId, List.of(draft(false)));
        var retained = reviews.findById(reviewId).orElseThrow();
        assertThat(retained.getPublicReviewAt()).isEqualTo(publicAt);
        assertThat(retained.getChecks().path("cancellationRequested").asBoolean())
                .isFalse();
        assertThat(retained.getChecks()
                        .path("feedbackPreparationReceipts")
                        .path(receipt.packageDigest())
                        .asString())
                .isEqualTo(receipt.preparedAt().toString());
    }

    @Test
    void shouldRequireApprovalWithoutQueueingAPostWhenTheCurrentPolicyRequiresIt() {
        when(gate.requiresApproval(any())).thenReturn(true);
        store.prepareAll(workspace.getId(), reviewId, List.of(draft(false)));
        assertThat(current().getState()).isEqualTo(FeedbackState.AWAITING_APPROVAL);
        assertThat(children()).hasSize(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM feedback_send WHERE workspace_id=? AND" + " feedback_id=?",
                        Long.class,
                        workspace.getId(),
                        feedbackId))
                .isZero();
    }

    @Test
    void shouldWithholdOnlyTheCopyWhoseExactProblemWasSuppressed() {
        var first = problems.findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(
                        workspace.getId(), List.of(supportingResult.getId()))
                .getFirst();
        jdbc.update("UPDATE problem SET key='concern-a' WHERE id=?", first.getId());
        var second = problems.saveAndFlush(Problem.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .resultId(supportingResult.getId())
                .position(1)
                .key("concern-b")
                .severity(first.getSeverity())
                .summary("A separate concern")
                .explanation("This independent concern has its own support.")
                .citations(first.getCitations())
                .basis(first.getBasis())
                .build());
        var prior = piece(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, AT);
        basis.saveAndFlush(FeedbackBasis.builder()
                .id(FeedbackIds.basis(prior.getId(), supportingResult.getId(), first.getId()))
                .workspaceId(workspace.getId())
                .feedbackId(prior.getId())
                .resultId(supportingResult.getId())
                .problemId(first.getId())
                .developerId(developerId)
                .ordinal(0)
                .build());
        responses.saveAndFlush(FeedbackResponse.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .feedbackId(prior.getId())
                .resolution(FeedbackResolution.DISAGREE)
                .comment("This concern does not apply to the captured behavior.")
                .createdAt(AT)
                .build());
        var supportA = new FeedbackCompositionTrace.Support(supportingResult.getId(), first.getId());
        var supportB = new FeedbackCompositionTrace.Support(supportingResult.getId(), second.getId());
        var trace = new FeedbackCompositionTrace(
                List.of(
                        new FeedbackCompositionTrace.CopySupport(FeedbackCopyKind.LINE_NOTE, 0, List.of(supportA)),
                        new FeedbackCompositionTrace.CopySupport(FeedbackCopyKind.LINE_NOTE, 1, List.of(supportB))),
                List.of(),
                null,
                null);
        var notes = IntStream.range(0, 2)
                .mapToObj(position -> {
                    UUID id = FeedbackIds.copy(feedbackId, FeedbackCopyKind.LINE_NOTE, position);
                    return new FeedbackCopyInput(
                            id,
                            FeedbackCopyKind.LINE_NOTE,
                            position,
                            "Independent note " + position,
                            "src/A.java",
                            10 + position,
                            10 + position,
                            CitationSide.NEW,
                            id.toString(),
                            FeedbackIds.writeMarker(id));
                })
                .toList();
        var draft = new FeedbackDraft(
                feedbackId,
                workspace.getId(),
                reviewId,
                itemId,
                developerId,
                FeedbackDestination.ON_WORK,
                FeedbackState.READY,
                null,
                null,
                List.of(
                        new FeedbackBasisInput(supportingResult.getId(), first.getId(), 0),
                        new FeedbackBasisInput(supportingResult.getId(), second.getId(), 1)),
                notes,
                Map.of(),
                trace,
                null);
        var receipt = store.prepareAll(workspace.getId(), reviewId, List.of(draft));
        assertThat(current().getState()).isEqualTo(FeedbackState.READY);
        var prepared = children();
        assertThat(prepared).hasSize(2);
        assertThat(prepared.get(0).getState()).isEqualTo(FeedbackCopyState.WITHHELD);
        assertThat(prepared.get(0).getStopReason()).isEqualTo(FeedbackStopReason.REACTED_DISAGREE);
        assertThat(prepared.get(1).getState()).isEqualTo(FeedbackCopyState.PLANNED);
        assertThat(prepared.get(1).getStopReason()).isNull();
        assertThat(store.prepareAll(workspace.getId(), reviewId, List.of(draft)))
                .isEqualTo(receipt);
        assertThat(provider.noteCreates).isZero();
    }

    @Test
    void shouldReplaceQueuedConversationFeedbackAndPreventItsSelection() {
        var previous = conversation(developerId);
        store.prepareAll(workspace.getId(), reviewId, List.of(previous));
        UUID nextId = UUID.randomUUID();
        UUID copyId = FeedbackIds.copy(nextId, FeedbackCopyKind.CHAT_TURN, 0);
        var trace = new FeedbackCompositionTrace(
                List.of(new FeedbackCompositionTrace.CopySupport(
                        FeedbackCopyKind.CHAT_TURN,
                        0,
                        List.of(new FeedbackCompositionTrace.Support(supportingResult.getId(), null)))),
                List.of(),
                previous.id(),
                supportingResult.getId());
        var next = new FeedbackDraft(
                nextId,
                workspace.getId(),
                reviewId,
                itemId,
                developerId,
                FeedbackDestination.CONVERSATION,
                FeedbackState.READY,
                null,
                "Discuss the token boundary.",
                previous.basis(),
                List.of(new FeedbackCopyInput(
                        copyId, FeedbackCopyKind.CHAT_TURN, 0, null, null, null, null, null, copyId.toString(), null)),
                Map.of(),
                trace,
                null);
        store.prepareAll(workspace.getId(), reviewId, List.of(next));
        assertThat(feedback.findById(previous.id()).orElseThrow().getState()).isEqualTo(FeedbackState.REPLACED);
        assertThat(feedback.findById(nextId).orElseThrow().getReplacesId()).isEqualTo(previous.id());
        assertThat(store.claimConversationCopy(workspace.getId(), previous.id(), runningTurn(developerId), 1))
                .isEmpty();
        var retained = reviews.findById(reviewId)
                .orElseThrow()
                .getChecks()
                .path("feedbackComposition")
                .path(nextId.toString());
        assertThat(retained.path("replacesId").asString())
                .isEqualTo(previous.id().toString());
        assertThat(store.prepareAll(workspace.getId(), reviewId, List.of(next)).pieces())
                .hasSize(1);
    }

    @Test
    void shouldKeepAnAcceptedPublicPackageWhenAPrivatePackageIsRefused() {
        var publicReceipt = store.prepareAll(workspace.getId(), reviewId, List.of(draft(false)));
        assertThatThrownBy(() -> store.prepareAll(workspace.getId(), reviewId, List.of(conversation(developerId + 1))))
                .isInstanceOf(RuntimeException.class);
        assertThat(feedback.findById(feedbackId)).isPresent();
        assertThat(store.prepareAll(workspace.getId(), reviewId, List.of(draft(false))))
                .isEqualTo(publicReceipt);
    }

    @Test
    void shouldRetainQuietWitnessesBesideRaisedFeedbackAndRejectChangedDecisionsOnReplay() {
        var quiet = negativeResult();
        var original = draft(false);
        var exact = new FeedbackCompositionTrace(
                List.of(new FeedbackCompositionTrace.CopySupport(
                        FeedbackCopyKind.SUMMARY,
                        0,
                        List.of(new FeedbackCompositionTrace.Support(supportingResult.getId(), null)))),
                List.of(new FeedbackCompositionTrace.QuietDecision(
                        List.of(new FeedbackCompositionTrace.Support(quiet.getId(), null)),
                        "ALREADY_SAID",
                        List.of("github:review-comment:7"))),
                null,
                null);
        var proposal = new FeedbackDraft(
                original.id(),
                original.workspaceId(),
                original.reviewId(),
                original.itemId(),
                original.developerId(),
                original.destination(),
                original.state(),
                original.stopReason(),
                original.body(),
                original.basis(),
                original.copies(),
                original.checks(),
                exact,
                null);
        store.prepareAll(workspace.getId(), reviewId, List.of(proposal));
        var retained = reviews.findById(reviewId)
                .orElseThrow()
                .getChecks()
                .path("feedbackComposition")
                .path(proposal.id().toString());
        assertThat(retained.path("withheld")
                        .required(0)
                        .path("witnessIds")
                        .required(0)
                        .asString())
                .isEqualTo("github:review-comment:7");
        var changed = new FeedbackDraft(
                original.id(),
                original.workspaceId(),
                original.reviewId(),
                original.itemId(),
                original.developerId(),
                original.destination(),
                original.state(),
                original.stopReason(),
                original.body(),
                original.basis(),
                original.copies(),
                original.checks(),
                new FeedbackCompositionTrace(exact.copies(), List.of(), null, null),
                null);
        assertThatThrownBy(() -> store.prepareAll(workspace.getId(), reviewId, List.of(changed)))
                .isInstanceOf(RuntimeException.class);
        assertThat(feedback.findById(proposal.id()).orElseThrow().getState()).isEqualTo(FeedbackState.READY);
    }

    @Test
    void shouldProjectTheReportedCardFieldsThroughPreparationIntoThePracticePageDto() throws IOException {
        String source;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/agent/composer-card.json"))) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace(
                            "30000000-0000-4000-8000-000000000001",
                            supportingResult.getId().toString());
        }
        var fixture = mapper.readTree(source);
        var composed = mapper.treeToValue(fixture.path("prepared"), ComposedFeedback.class);
        var candidate = mock(ResultCandidate.class);
        when(candidate.developerId()).thenReturn(developerId);
        when(candidate.practiceId()).thenReturn(supportingResult.getPracticeId());
        var job = new ReviewJob();
        job.setId(UUID.randomUUID());
        job.setWorkspaceId(workspace.getId());
        job.setReviewId(reviewId);
        job.setInput(new ReviewJobInput(itemId, List.of()));
        var proposal = FeedbackDraftBuilder.build(
                        job,
                        new PrepareFeedbackRequestDTO(
                                job.getReviewId(),
                                job.getId(),
                                job.getAttempt(),
                                List.copyOf(Set.of(supportingResult.getId())),
                                List.of(composed),
                                false,
                                null),
                        List.of(new AdmittedResultSnapshot(supportingResult.getId(), candidate, List.of())),
                        List.of(),
                        Map.of())
                .getFirst();
        // Pattern eligibility has separate coverage. This seam exercises preparation and the visible card fields.
        when(pageRouter.supportsPattern(any(), anyList())).thenReturn(true);
        store.prepareAll(workspace.getId(), reviewId, List.of(proposal));
        when(currentDeveloper.currentDeveloperId()).thenReturn(Optional.of(developerId));
        when(resultVisibility.permitsShown(
                        eq(workspace.getId()), anyList(), eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                .thenReturn(Set.of(supportingResult.getId()));
        var card = page.getOwnPracticePageFeedback(workspace.getId()).stream()
                .filter(row -> row.id().equals(proposal.id()))
                .findFirst()
                .orElseThrow();
        assertThat(card.headline())
                .isEqualTo(fixture.path("reported").path("title").asString());
        assertThat(card.body()).isEqualTo(fixture.path("reported").path("body").asString());
        assertThat(card.nextStep())
                .isEqualTo(fixture.path("reported").path("nextStep").asString());
    }

    @Test
    void shouldProjectWhatPreparedPrivatePiecesDecideAndTheRoomTheirComposedIntentUses() {
        when(pageRouter.supportsPattern(any(), anyList())).thenReturn(true);
        var named = supportingResult;
        var grouped = negativeResult();
        var carded = negativeResult();
        var job = new ReviewJob();
        job.setId(UUID.randomUUID());
        job.setWorkspaceId(workspace.getId());
        job.setReviewId(reviewId);
        job.setInput(new ReviewJobInput(itemId, List.of()));
        var admitted = List.of(snapshot(named), snapshot(grouped), snapshot(carded));
        // One conversation rests on two practices; a composer's page withholding and a card follow.
        var conversation = new ComposedFeedback(
                FeedbackDestination.CONVERSATION,
                List.of(named.getId(), grouped.getId()),
                "Discuss the shared failure handling.",
                List.of(),
                null,
                new FeedbackCompositionTrace(
                        List.of(new FeedbackCompositionTrace.CopySupport(
                                FeedbackCopyKind.CHAT_TURN, 0, support(named, grouped))),
                        List.of(),
                        null,
                        null),
                null);
        var withheld = new ComposedFeedback(
                FeedbackDestination.PRACTICE_PAGE,
                List.of(grouped.getId()),
                null,
                List.of(),
                FeedbackStopReason.COMPOSER_WITHHELD,
                new FeedbackCompositionTrace(
                        List.of(),
                        List.of(new FeedbackCompositionTrace.QuietDecision(support(grouped), "BELOW_BAR", List.of())),
                        null,
                        null),
                null);
        var card = new ComposedFeedback(
                FeedbackDestination.PRACTICE_PAGE,
                List.of(carded.getId()),
                null,
                List.of(),
                null,
                new FeedbackCompositionTrace(
                        List.of(new FeedbackCompositionTrace.CopySupport(FeedbackCopyKind.SUMMARY, 0, support(carded))),
                        List.of(),
                        null,
                        null),
                new ComposedFeedback.Page(
                        "Keep failure context",
                        "Failures lose their context across these changes.",
                        "Check that the context reaches the caller."));
        var drafts = FeedbackDraftBuilder.build(
                job,
                new PrepareFeedbackRequestDTO(
                        reviewId,
                        job.getId(),
                        0,
                        List.of(named.getId(), grouped.getId(), carded.getId()),
                        List.of(conversation, withheld, card),
                        false,
                        null),
                admitted,
                List.of(),
                Map.of());
        for (var draft : drafts) store.prepareAll(workspace.getId(), reviewId, List.of(draft));
        // The server withholds the card after it was composed to be delivered; its room stays used.
        var cardId = drafts.getLast().id();
        store.withhold(feedback.findById(cardId).orElseThrow(), FeedbackStopReason.RESULT_MARKED_INCORRECT);
        assertThat(feedback.findById(cardId).orElseThrow().getState()).isEqualTo(FeedbackState.WITHHELD);
        var everything = List.of(named.getId(), grouped.getId(), carded.getId());

        var progress = store.privateProgress(workspace.getId(), reviewId, developerId, everything);
        assertThat(progress.decidedResultIds()).containsExactlyInAnyOrderElementsOf(everything);
        assertThat(progress.practicePageDeliveries()).isEqualTo(1);
        assertThat(progress.conversationDeliveries()).isEqualTo(1);

        // An exact replay changes nothing; a changed piece under the same identity is refused.
        store.prepareAll(workspace.getId(), reviewId, List.of(drafts.getFirst()));
        var changed = drafts.getFirst();
        assertThatThrownBy(() -> store.prepareAll(
                        workspace.getId(),
                        reviewId,
                        List.of(new FeedbackDraft(
                                changed.id(),
                                changed.workspaceId(),
                                changed.reviewId(),
                                changed.itemId(),
                                changed.developerId(),
                                changed.destination(),
                                changed.state(),
                                changed.stopReason(),
                                "Different words for the same piece.",
                                changed.basis(),
                                changed.copies(),
                                changed.checks(),
                                changed.composition(),
                                changed.page()))))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.privateProgress(workspace.getId(), reviewId, developerId, everything))
                .isEqualTo(progress);

        // Only the admitted results asked about count, and only for this review's developer.
        var withoutCard = store.privateProgress(
                workspace.getId(), reviewId, developerId, List.of(named.getId(), grouped.getId()));
        assertThat(withoutCard.decidedResultIds()).containsExactlyInAnyOrder(named.getId(), grouped.getId());
        assertThat(withoutCard.practicePageDeliveries()).isZero();
        assertThat(withoutCard.conversationDeliveries()).isEqualTo(1);
        assertThat(store.privateProgress(workspace.getId(), reviewId, developerId + 1, everything))
                .isEqualTo(PrivateFeedbackProgress.NONE);
        assertThat(store.privateProgress(workspace.getId(), UUID.randomUUID(), developerId, everything))
                .isEqualTo(PrivateFeedbackProgress.NONE);
    }

    /**
     * Label mapping only: visibility, the current developer and pattern support are controlled as in the card
     * projection test above, so this proves what the page names the work, not who may read it.
     */
    @Test
    void shouldNameTheCardEvidenceByTheProvidersOwnLabelAndFallBackToTheKindsDisplayName() {
        when(pageRouter.supportsPattern(any(), anyList())).thenReturn(true);
        var job = new ReviewJob();
        job.setId(UUID.randomUUID());
        job.setWorkspaceId(workspace.getId());
        job.setReviewId(reviewId);
        job.setInput(new ReviewJobInput(itemId, List.of()));
        var composed = new ComposedFeedback(
                FeedbackDestination.PRACTICE_PAGE,
                List.of(supportingResult.getId()),
                null,
                List.of(),
                null,
                new FeedbackCompositionTrace(
                        List.of(new FeedbackCompositionTrace.CopySupport(
                                FeedbackCopyKind.SUMMARY, 0, support(supportingResult))),
                        List.of(),
                        null,
                        null),
                new ComposedFeedback.Page(
                        "Cover the failure case",
                        "The failure case has no test.",
                        "Add a test that reaches the failure case."));
        var draft = FeedbackDraftBuilder.build(
                        job,
                        new PrepareFeedbackRequestDTO(
                                reviewId,
                                job.getId(),
                                0,
                                List.of(supportingResult.getId()),
                                List.of(composed),
                                false,
                                null),
                        List.of(snapshot(supportingResult)),
                        List.of(),
                        Map.of())
                .getFirst();
        store.prepareAll(workspace.getId(), reviewId, List.of(draft));
        when(currentDeveloper.currentDeveloperId()).thenReturn(Optional.of(developerId));
        when(resultVisibility.permitsShown(
                        eq(workspace.getId()), anyList(), eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                .thenReturn(Set.of(supportingResult.getId()));

        // The provider's own name for the work, never the kind's noun shared by every pull request.
        String url = "https://github.com/ls1intum/hephaestus/pull/21";
        when(itemLabels.labels(eq(workspace.getId()), anyCollection()))
                .thenReturn(Map.of(
                        itemId,
                        new ItemLabel(
                                itemId,
                                "#21",
                                "Explain the change",
                                "pull request",
                                21,
                                "ls1intum/hephaestus",
                                url,
                                IntegrationKind.GITHUB)));
        var named = evidenceItem(draft.id());
        assertThat(named.label()).isEqualTo("#21");
        assertThat(named.title()).isEqualTo("Explain the change");
        assertThat(named.url()).isEqualTo(url);
        assertThat(named.provider()).isEqualTo(IntegrationKind.GITHUB);
        assertThat(named.removedAt()).isNull();

        // Removed work may keep its name while its address is gone; the removal stays on the reference.
        jdbc.update(
                "UPDATE item SET removed_at=? WHERE workspace_id=? AND id=?",
                Timestamp.from(AT.plusSeconds(60)),
                workspace.getId(),
                itemId);
        when(itemLabels.labels(eq(workspace.getId()), anyCollection()))
                .thenReturn(Map.of(
                        itemId,
                        new ItemLabel(
                                itemId,
                                "#21",
                                "Explain the change",
                                "pull request",
                                21,
                                "ls1intum/hephaestus",
                                null,
                                IntegrationKind.GITHUB)));
        var removed = evidenceItem(draft.id());
        assertThat(removed.label()).isEqualTo("#21");
        assertThat(removed.url()).isNull();
        assertThat(removed.removedAt()).isEqualTo(AT.plusSeconds(60));

        // With no label at all, the kind's human name stands in, and nothing about the provider is invented.
        when(itemLabels.labels(eq(workspace.getId()), anyCollection())).thenReturn(Map.of());
        var unnamed = evidenceItem(draft.id());
        assertThat(unnamed.label()).isEqualTo("Pull or merge request");
        assertThat(unnamed.kind()).isEqualTo("scm.pull_request");
        assertThat(unnamed.provider()).isNull();
        assertThat(unnamed.title()).isNull();
        assertThat(unnamed.url()).isNull();
    }

    private ItemRefDTO evidenceItem(UUID feedbackId) {
        var card = page.getOwnPracticePageFeedback(workspace.getId()).stream()
                .filter(row -> row.id().equals(feedbackId))
                .findFirst()
                .orElseThrow();
        assertThat(card.evidence()).hasSize(1);
        return card.evidence().getFirst().item();
    }

    private AdmittedResultSnapshot snapshot(Result result) {
        var candidate = mock(ResultCandidate.class);
        when(candidate.developerId()).thenReturn(developerId);
        when(candidate.practiceId()).thenReturn(result.getPracticeId());
        return new AdmittedResultSnapshot(result.getId(), candidate, List.of());
    }

    private static List<FeedbackCompositionTrace.Support> support(Result... results) {
        return Arrays.stream(results)
                .map(result -> new FeedbackCompositionTrace.Support(result.getId(), null))
                .toList();
    }

    /** A running first attempt of a Heph turn in the developer's main conversation. */
    private UUID runningTurn(long developer) {
        UUID conversationId = UUID.randomUUID(), turnId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)"
                        + " VALUES (?,?,?,now(),now())",
                conversationId,
                workspace.getId(),
                developer);
        jdbc.update(
                "INSERT INTO mentor_turn(id,workspace_id,conversation_id,request_id,status,surface,attempt,started_at,"
                        + "created_at) VALUES (?,?,?,?,'RUNNING','APP',1,now(),now())",
                turnId,
                workspace.getId(),
                conversationId,
                turnId.toString());
        return turnId;
    }

    private FeedbackDraft conversation(long developer) {
        UUID id = FeedbackIds.conversation(workspace.getId(), reviewId, developer, List.of(supportingResult.getId()));
        UUID copyId = FeedbackIds.copy(id, FeedbackCopyKind.CHAT_TURN, 0);
        return new FeedbackDraft(
                id,
                workspace.getId(),
                reviewId,
                itemId,
                developer,
                FeedbackDestination.CONVERSATION,
                FeedbackState.READY,
                null,
                "Discuss a focused regression test.",
                List.of(new FeedbackBasisInput(supportingResult.getId(), null, 0)),
                List.of(new FeedbackCopyInput(
                        copyId, FeedbackCopyKind.CHAT_TURN, 0, null, null, null, null, null, copyId.toString(), null)),
                Map.of(),
                null,
                null);
    }
}
