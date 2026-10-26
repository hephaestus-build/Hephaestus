package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.DeliveredFeedbackContentSource;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorLinkDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorTimeline;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.OwnFeedbackRefDTO;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultPresentation;
import de.tum.cit.aet.hephaestus.practices.result.ResultSourceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackHistory;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.node.JsonNodeFactory;

class FeedbackHistoryQueryIntegrationTest extends AbstractFeedbackIntegrationTest {
    @Autowired
    FeedbackHistoryQueryService history;

    @MockitoBean
    ResultHistoryQuery permission;

    @MockitoBean
    ResultSourceAuthorization publicPermission;

    @Autowired
    MentorConversationService conversations;

    @Autowired
    MentorTurnService turns;

    @Autowired
    MentorTimeline timeline;

    @MockitoBean
    FeedbackVisibilityService visibility;

    @Autowired
    DeliveredFeedbackContentSource contextFile;

    @Autowired
    ResultPresentation presentation;

    @Test
    void shouldReadOneDevelopersDeliveredWitnessAndQueuedTargetOnTheReadPracticesOnly() {
        var proof = negativeResult();
        authorize(proof);
        var witness = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, AT);
        bind(witness, proof);
        var target = piece(FeedbackDestination.CONVERSATION, FeedbackState.READY, null);
        bind(target, proof);
        bind(piece(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, AT), proof);
        var sibling = negativeResult();
        authorize(sibling);
        bind(piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, AT), sibling);
        var practices = routes(proof);

        var read = history.forPrivateComposition(workspace.getId(), developerId, practices, 10)
                .orElseThrow();

        assertThat(read.entries())
                .extracting(entry -> entry.feedback().feedbackId())
                .containsExactlyInAnyOrder(witness.getId(), target.getId());
        assertThat(read.hasMore()).isFalse();
        assertThat(read.withoutCopyAuthority()).isZero();
        var said = read.entries().stream()
                .filter(entry -> entry.feedback().feedbackId().equals(witness.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(said.feedback().body()).isEqualTo(witness.getBody());
        assertThat(said.currentness()).isEqualTo(Currentness.CURRENT);
        assertThat(said.withdrawn()).isFalse();
        assertThat(said.replaceablePracticeSlug()).isNull();
        assertThat(said.basedOn())
                .containsExactly(new PrivateFeedbackHistory.Basis(
                        proof.getId(), "regression-tests", proof.getPracticeVersionId(), ResultOutcome.NOT_MET));
        var queued = read.entries().stream()
                .filter(entry -> entry.feedback().feedbackId().equals(target.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(queued.replaceablePracticeSlug()).isEqualTo("regression-tests");
        assertThat(queued.feedback().deliveredAt()).isNull();
        verify(permission, never())
                .find(
                        eq(workspace.getId()),
                        eq(proof.getId()),
                        eq(developerId),
                        eq(SourceUsePurpose.CONVERSATIONAL_MENTORING));

        long otherId = Objects.requireNonNull(
                persistAccount("Another composed developer").getId());
        jdbc.update(
                "INSERT INTO"
                        + " membership(workspace_id,account_id,role,joined_at,review_selected,hidden,public_activity_seen)"
                        + " VALUES (?,?,'MEMBER',now(),false,false,false)",
                workspace.getId(),
                otherId);
        assertThat(history.forPrivateComposition(workspace.getId(), otherId, practices, 10)
                        .orElseThrow()
                        .entries())
                .isEmpty();
        assertThat(history.forPrivateComposition(workspace.getId() + 1, developerId, practices, 10))
                .isEmpty();
    }

    @Test
    void shouldWithholdWordsTargetsAndRowsThePrivateHistoryMayNotCarry() {
        var proof = negativeResult();
        authorize(proof);
        var withdrawn = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, AT);
        bind(withdrawn, proof);
        when(visibility.withdrawn(workspace.getId(), withdrawn.getId())).thenReturn(true);
        var withdrawnTarget = piece(FeedbackDestination.CONVERSATION, FeedbackState.READY, null);
        bind(withdrawnTarget, proof);
        when(visibility.withdrawn(workspace.getId(), withdrawnTarget.getId())).thenReturn(true);
        // No copy authority for this support: the permission mock finds nothing for it.
        var lost = negativeResult();
        bind(piece(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, AT), lost);
        var practices = routes(proof, lost);

        var read = history.forPrivateComposition(workspace.getId(), developerId, practices, 10)
                .orElseThrow();

        assertThat(read.entries())
                .extracting(entry -> entry.feedback().feedbackId())
                .containsExactly(withdrawn.getId());
        assertThat(read.withoutCopyAuthority()).isEqualTo(1);
        var entry = read.entries().getFirst();
        assertThat(entry.withdrawn()).isTrue();
        assertThat(entry.feedback().body()).isNull();

        proof.end(ResultEndReason.REPLACED, AT);
        results.saveAndFlush(proof);
        assertThat(history.forPrivateComposition(workspace.getId(), developerId, practices, 10)
                        .orElseThrow()
                        .entries()
                        .getFirst()
                        .currentness())
                .isEqualTo(Currentness.STALE);
    }

    @Test
    void shouldReportOlderMatchingFeedbackBeyondTheReadLimit() {
        var proof = negativeResult();
        authorize(proof);
        bind(piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, AT), proof);
        bind(piece(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, AT), proof);

        var read = history.forPrivateComposition(workspace.getId(), developerId, routes(proof), 1)
                .orElseThrow();

        assertThat(read.entries()).hasSize(1);
        assertThat(read.maxEntries()).isEqualTo(1);
        assertThat(read.hasMore()).isTrue();
    }

    @Test
    void shouldReadEachPracticeOnlyOnTheDestinationRoutedToIt() {
        var pageOnly = negativeResult();
        authorize(pageOnly);
        var conversationOnly = negativeResult();
        authorize(conversationOnly);
        var card = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, AT);
        bind(card, pageOnly);
        bind(piece(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, AT), pageOnly);
        bind(piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, AT), conversationOnly);
        var said = piece(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, AT);
        bind(said, conversationOnly);

        var read = history.forPrivateComposition(
                        workspace.getId(),
                        developerId,
                        List.of(
                                new PrivateFeedbackHistory.Route(
                                        pageOnly.getPracticeId(), FeedbackDestination.PRACTICE_PAGE),
                                new PrivateFeedbackHistory.Route(
                                        conversationOnly.getPracticeId(), FeedbackDestination.CONVERSATION)),
                        10)
                .orElseThrow();

        // Two open routes are not four: neither practice is read on the other's destination.
        assertThat(read.entries())
                .extracting(entry -> entry.feedback().feedbackId())
                .containsExactlyInAnyOrder(card.getId(), said.getId());
        assertThat(history.forPrivateComposition(workspace.getId(), developerId, List.of(), 10))
                .isEmpty();
    }

    @Test
    void shouldKeepTheWordsOfAStaleClaimAsWhatWasSaid() {
        var proof = negativeResult();
        authorize(proof);
        var card = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, AT);
        bind(card, proof);
        // A later review of the same work replaced the claim the card rested on.
        proof.end(ResultEndReason.REPLACED, AT);
        results.saveAndFlush(proof);

        var entry = history.forPrivateComposition(workspace.getId(), developerId, routes(proof), 10)
                .orElseThrow()
                .entries()
                .getFirst();

        assertThat(entry.currentness()).isEqualTo(Currentness.STALE);
        assertThat(entry.feedback().body()).isEqualTo(card.getBody());
        assertThat(entry.withdrawn()).isFalse();
    }

    private static List<PrivateFeedbackHistory.Route> routes(Result... proofs) {
        return Arrays.stream(proofs)
                .flatMap(proof -> Stream.of(FeedbackDestination.PRACTICE_PAGE, FeedbackDestination.CONVERSATION)
                        .map(destination -> new PrivateFeedbackHistory.Route(proof.getPracticeId(), destination)))
                .toList();
    }

    @Test
    void shouldKeepPrivateHistoryWithItsDeveloperAndWorkspace() {
        var privatePiece = piece(FeedbackDestination.CONVERSATION, FeedbackState.READY, null);
        var publicPiece = piece(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, AT);
        long otherId =
                Objects.requireNonNull(persistAccount("Another history viewer").getId());
        jdbc.update(
                "INSERT INTO"
                        + " membership(workspace_id,account_id,role,joined_at,review_selected,hidden,public_activity_seen)"
                        + " VALUES (?,?,'MEMBER',now(),false,false,false)",
                workspace.getId(),
                otherId);
        assertThat(history.forItem(workspace.getId(), itemId, otherId, 10))
                .extracting(entry -> entry.feedbackId())
                .containsExactly(publicPiece.getId());
        assertThat(history.forDeveloper(workspace.getId(), otherId, AT.minusSeconds(1), 10))
                .isEmpty();
        assertThat(history.forDeveloper(workspace.getId() + 1, developerId, AT.minusSeconds(1), 10))
                .isEmpty();
        assertThat(history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 10))
                .extracting(entry -> entry.feedbackId())
                .containsExactlyInAnyOrder(privatePiece.getId(), publicPiece.getId());
    }

    @Test
    void shouldExcludeHiddenSuspendedAndDepartedMembersFromHistory() {
        var row = piece(FeedbackDestination.CONVERSATION, FeedbackState.READY, null);
        assertThat(history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 10))
                .extracting(entry -> entry.feedbackId())
                .containsExactly(row.getId());
        jdbc.update(
                "UPDATE membership SET hidden=true WHERE workspace_id=? AND account_id=?",
                workspace.getId(),
                developerId);
        assertThat(history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 10))
                .isEmpty();
        assertThat(history.forItem(workspace.getId(), itemId, developerId, 10)).isEmpty();
        jdbc.update(
                "UPDATE membership SET hidden=false WHERE workspace_id=? AND account_id=?",
                workspace.getId(),
                developerId);
        try {
            jdbc.update("UPDATE account SET status='SUSPENDED' WHERE id=?", developerId);
            assertThat(history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 10))
                    .isEmpty();
            assertThat(history.forItem(workspace.getId(), itemId, developerId, 10))
                    .isEmpty();
        } finally {
            jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?", developerId);
        }
        assertThat(history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 10))
                .extracting(entry -> entry.feedbackId())
                .containsExactly(row.getId());
        jdbc.update(
                "UPDATE membership SET left_at=now() WHERE workspace_id=? AND account_id=?",
                workspace.getId(),
                developerId);
        assertThat(history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 10))
                .isEmpty();
        assertThat(history.forItem(workspace.getId(), itemId, developerId, 10)).isEmpty();
    }

    @Test
    void shouldRedactUnavailableEvidenceWhileRetainingTheRecordedDeliveryState() {
        var row = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, AT);
        var proof = result();
        bind(row, proof);
        when(permission.find(workspace.getId(), proof.getId(), developerId, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.empty());
        var unavailable = history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 1)
                .getFirst();
        assertThat(unavailable.body()).isNull();
        assertThat(unavailable.state()).isEqualTo(FeedbackState.DELIVERED);
        assertThat(unavailable.deliveredAt()).isEqualTo(AT);
        var retained = mock(ResultHistoryEntry.class);
        when(retained.copyAuthorityComplete()).thenReturn(true);
        when(retained.resultId()).thenReturn(proof.getId());
        when(retained.practiceSlug()).thenReturn("regression-tests");
        when(retained.sourceJobIds()).thenReturn(List.of());
        when(retained.copiedIdentities()).thenReturn(List.of());
        when(retained.repositoryIds()).thenReturn(List.of());
        when(permission.find(workspace.getId(), proof.getId(), developerId, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(retained));
        assertThat(history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 1)
                        .getFirst()
                        .body())
                .isEqualTo(row.getBody());
    }

    /**
     * Heph's context and the developer's own pages read the same states: never a proposal awaiting approval, a
     * rejection, a withholding or a replacement, whose state alone would tell the developer about it.
     */
    @Test
    void shouldShowHephAndTheProfileOnlyTheFeedbackStatesTheDeveloperMayLearnOf() {
        var proof = result();
        authorize(proof);
        var visible = new ArrayList<UUID>();
        var hidden = new ArrayList<UUID>();
        for (var state : FeedbackState.values()) {
            var row = state == FeedbackState.WITHHELD
                    ? withheld(FeedbackDestination.ON_WORK)
                    : piece(
                            FeedbackDestination.ON_WORK,
                            state,
                            state == FeedbackState.DELIVERED || state == FeedbackState.REPLACED ? AT : null);
            bind(row, proof);
            (List.of(FeedbackState.READY, FeedbackState.DELIVERED, FeedbackState.UNCONFIRMED, FeedbackState.FAILED)
                                    .contains(state)
                            ? visible
                            : hidden)
                    .add(row.getId());
        }
        for (var destination : List.of(FeedbackDestination.PRACTICE_PAGE, FeedbackDestination.CONVERSATION)) {
            var ready = piece(destination, FeedbackState.READY, null);
            bind(ready, proof);
            visible.add(ready.getId());
            var withheld = withheld(destination);
            bind(withheld, proof);
            hidden.add(withheld.getId());
        }

        var states = contextFile.buildPayload(workspace.getId(), developerId).get("feedbackStates");
        assertThat(states.valueStream()
                        .map(row -> UUID.fromString(row.get("feedbackId").asString())))
                .containsExactlyInAnyOrderElementsOf(visible);
        assertThat(presentation.ownFeedback(workspace.getId(), List.of(proof)).get(proof.getId()))
                .extracting(OwnFeedbackRefDTO::id)
                .containsExactlyInAnyOrderElementsOf(visible);
        for (var id : visible)
            assertThat(history.find(workspace.getId(), id, developerId)).isPresent();
        for (var id : hidden)
            assertThat(history.find(workspace.getId(), id, developerId)).isEmpty();
    }

    /** The timeline names the work and practice that a problem and shown feedback are about, as the developer reads them. */
    @Test
    void shouldNameTheWorkAndPracticeOfAProblemAndOfShownFeedbackInTheTimeline() {
        var shown = piece(FeedbackDestination.CONVERSATION, FeedbackState.READY, null);
        var proof = result();
        bind(shown, proof);
        authorize(proof);
        UUID problemId = UUID.randomUUID();
        var result = mock(ResultHistoryEntry.class);
        when(result.practiceSlug()).thenReturn("describe-why");
        when(result.itemId()).thenReturn(itemId);
        when(permission.findOfProblem(
                        workspace.getId(), problemId, developerId, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(result));
        var main = conversations.findOrCreateMain(workspace.getId(), developerId);
        var asked = turns.submit(
                workspace.getId(),
                main.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP,
                        "What did the review find?",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        MentorTurn running = running(asked.turn().getId());
        turns.end(
                running.getId(),
                running.getAttempt(),
                "worker-history",
                MentorTurn.Status.DONE,
                List.of(
                        MentorLedgerFixtures.toolCall(
                                MentorLedgerFixtures.piId(),
                                "call-problem",
                                "link_problem",
                                JsonNodeFactory.instance
                                        .objectNode()
                                        .put("problemId", problemId.toString())
                                        .put("text", "The failure case has no test.")),
                        MentorLedgerFixtures.toolResult(
                                MentorLedgerFixtures.piId(), "call-problem", "link_problem", "Linked", false),
                        MentorLedgerFixtures.toolCall(
                                MentorLedgerFixtures.piId(),
                                "call-feedback",
                                "show_feedback",
                                JsonNodeFactory.instance
                                        .objectNode()
                                        .put("feedbackId", shown.getId().toString())
                                        .put("text", "Say why.")),
                        MentorLedgerFixtures.toolResult(
                                MentorLedgerFixtures.piId(), "call-feedback", "show_feedback", "Shown", false)),
                null);

        List<MentorLinkDTO> links = timeline.entries(workspace.getId(), developerId, null, 10).entries().stream()
                .flatMap(entry -> entry.links().stream())
                .toList();

        assertThat(links)
                .extracting(MentorLinkDTO::kind, MentorLinkDTO::practiceSlug, MentorLinkDTO::itemId)
                .containsExactly(
                        tuple(MentorLinkDTO.Kind.PROBLEM, "describe-why", itemId),
                        tuple(MentorLinkDTO.Kind.FEEDBACK, "regression-tests", itemId));
    }

    /** Claims turns until this one starts. A turn of another test that starts first is taken along. */
    private MentorTurn running(UUID turnId) {
        while (true) {
            MentorTurn claimed =
                    turns.claimNext("worker-history", Duration.ofMinutes(5)).orElseThrow();
            if (claimed.getId().equals(turnId)) {
                return claimed;
            }
        }
    }

    @Test
    void shouldExposeAnAuthorizedReadyBriefWithoutInventingAThreadReceipt() {
        var row = piece(FeedbackDestination.CONVERSATION, FeedbackState.READY, null);
        var proof = result();
        bind(row, proof);
        authorize(proof);
        var entry = history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 1)
                .getFirst();
        assertThat(entry.body()).isEqualTo(row.getBody());
        assertThat(entry.threadId()).isNull();
        assertThat(entry.deliveredAt()).isNull();
        assertThat(entry.copyAuthorityComplete()).isTrue();
        assertThat(entry.practiceSlugs()).containsExactly("regression-tests");
    }

    @Test
    void shouldReturnOnlyConfirmedWordsWhenASiblingRemainsUnknown() {
        var row = piece(FeedbackDestination.ON_WORK, FeedbackState.UNCONFIRMED, AT);
        var proof = result();
        bind(row, proof);
        authorize(proof);
        summary(row, FeedbackCopyState.WRITE_STARTED);
        var copyId = FeedbackIds.copy(row.getId(), FeedbackCopyKind.FILE_NOTE, 1);
        copies.saveAndFlush(FeedbackCopy.builder()
                .id(copyId)
                .workspaceId(workspace.getId())
                .feedbackId(row.getId())
                .kind(FeedbackCopyKind.FILE_NOTE)
                .position(1)
                .state(FeedbackCopyState.POSTED)
                .body("Cover the failure case.")
                .path("src/A.java")
                .writeKey(copyId.toString())
                .writeMarker(FeedbackIds.writeMarker(copyId))
                .writeStartedAt(AT)
                .postedAt(AT)
                .externalRef("confirmed-note")
                .createdAt(AT)
                .build());
        var entry = history.forItem(workspace.getId(), itemId, developerId, 1).getFirst();
        assertThat(entry.state()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(entry.body()).isEqualTo("Cover the failure case.");
        var publicHistory = Objects.requireNonNull(entry.publicHistory());
        assertThat(publicHistory.path("body").isNull()).isTrue();
        assertThat(publicHistory.path("copies").size()).isEqualTo(1);
        assertThat(publicHistory.path("copies").get(0).path("externalRef").asString())
                .isEqualTo("confirmed-note");
    }

    @Test
    void shouldReturnTheActualShownConversationWordsAndThread() {
        var row = piece(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, AT);
        var proof = result();
        bind(row, proof);
        authorize(proof);
        UUID conversation = UUID.randomUUID(), answer = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)"
                        + " VALUES (?,?,?,now(),now())",
                conversation,
                workspace.getId(),
                developerId);
        jdbc.update(
                "INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,kind,surface,text,created_at)"
                        + " VALUES (?,?,?,1,'HEPH','APP','Try a focused test.',now())",
                answer,
                workspace.getId(),
                conversation);
        UUID id = FeedbackIds.copy(row.getId(), FeedbackCopyKind.CHAT_TURN, 0);
        copies.saveAndFlush(FeedbackCopy.builder()
                .id(id)
                .workspaceId(workspace.getId())
                .feedbackId(row.getId())
                .kind(FeedbackCopyKind.CHAT_TURN)
                .state(FeedbackCopyState.POSTED)
                .body("Try a focused test.")
                .writeKey(id.toString())
                .writeStartedAt(AT)
                .postedAt(AT)
                .mentorEntryId(answer)
                .createdAt(AT)
                .build());
        var entry = history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 1)
                .getFirst();
        assertThat(entry.body()).isEqualTo("Try a focused test.").isNotEqualTo(row.getBody());
        assertThat(entry.threadId()).isEqualTo(conversation);
        assertThat(entry.publicHistory()).isNull();
    }

    @Test
    void shouldExposeConfirmedPublicCopyWordsToAPeerWithoutExposingPrivateResultWords() {
        var row = piece(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, AT);
        var proof = result();
        bind(row, proof);
        summary(row, FeedbackCopyState.POSTED);
        authorize(proof);
        long peerId =
                Objects.requireNonNull(persistAccount("Public feedback viewer").getId());
        jdbc.update(
                "INSERT INTO"
                        + " membership(workspace_id,account_id,role,joined_at,review_selected,hidden,public_activity_seen)"
                        + " VALUES (?,?,'MEMBER',now(),false,false,false)",
                workspace.getId(),
                peerId);
        var entry = history.forItem(workspace.getId(), itemId, peerId, 1).getFirst();
        assertThat(entry.body()).isEqualTo(row.getBody());
        assertThat(entry.copyAuthorityComplete()).isTrue();
        assertThat(Objects.requireNonNull(entry.publicHistory())
                        .path("copies")
                        .get(0)
                        .path("body")
                        .asString())
                .isEqualTo(row.getBody());
        verify(permission, never())
                .find(workspace.getId(), proof.getId(), peerId, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        when(publicPermission.authorized(any(), any(), eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                .thenReturn(false);
        assertThat(history.forItem(workspace.getId(), itemId, peerId, 1)
                        .getFirst()
                        .body())
                .isNull();
    }

    @Test
    void shouldUseMentoringPermissionForPrivateFeedbackHistory() {
        var row = piece(FeedbackDestination.CONVERSATION, FeedbackState.READY, null);
        var proof = result();
        bind(row, proof);
        authorize(proof);
        when(permission.find(workspace.getId(), proof.getId(), developerId, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.empty());
        var entry = history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 1)
                .getFirst();
        assertThat(entry.body()).isNull();
        assertThat(entry.state()).isEqualTo(FeedbackState.READY);
        assertThat(entry.practiceSlugs())
                .as("an unauthorized result names no practice")
                .isEmpty();
        verify(permission, never())
                .find(workspace.getId(), proof.getId(), developerId, SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW);
    }

    private Feedback withheld(FeedbackDestination destination) {
        return feedback.saveAndFlush(Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .reviewId(reviewId)
                .itemId(itemId)
                .developerId(developerId)
                .destination(destination)
                .state(FeedbackState.WITHHELD)
                .stopReason(FeedbackStopReason.COMPOSER_WITHHELD)
                .body("Add a focused regression test.")
                .createdAt(AT)
                .build());
    }

    private void authorize(Result proof) {
        var custody = new HistoryCustody(true, false, List.of(), List.of(), List.of());
        when(publicPermission.custody(argThat(row -> row != null && row.getId().equals(proof.getId()))))
                .thenReturn(custody);
        for (var purpose :
                List.of(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, SourceUsePurpose.CONVERSATIONAL_MENTORING))
            when(publicPermission.authorized(
                            argThat(row -> row != null && row.getId().equals(proof.getId())), eq(custody), eq(purpose)))
                    .thenReturn(true);
        var retained = mock(ResultHistoryEntry.class);
        when(retained.copyAuthorityComplete()).thenReturn(true);
        when(retained.resultId()).thenReturn(proof.getId());
        when(retained.practiceSlug()).thenReturn("regression-tests");
        when(permission.find(workspace.getId(), proof.getId(), developerId, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(retained));
        when(permission.find(
                        workspace.getId(), proof.getId(), developerId, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(Optional.of(retained));
    }
}
