package de.tum.cit.aet.hephaestus.practices.feedback;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.facts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.providers.conversation.DeliveredFeedbackContentSource;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationLinkDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTimeline;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnClaims;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.MirroredThreadStateQuery;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.OwnFeedbackRefDTO;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.model.ResultFacts;
import de.tum.cit.aet.hephaestus.practices.result.ResultPresentation;
import de.tum.cit.aet.hephaestus.practices.result.ResultSourceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepair;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepairStatus;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.NoteThreadState;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackHistory;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class FeedbackHistoryQueryIntegrationTest extends AbstractFeedbackIntegrationTest {

    @Autowired
    private ConversationTurnClaims claims;

    @MockitoBean
    MirroredThreadStateQuery mirroredThreads;

    @Autowired
    FeedbackHistoryQueryService history;

    @Autowired
    FeedbackRepair repairs;

    @MockitoBean
    ResultHistoryQuery permission;

    @MockitoBean
    ResultSourceAuthorization publicPermission;

    @Autowired
    ConversationService conversations;

    @Autowired
    ConversationTurnService turns;

    @Autowired
    ConversationTimeline timeline;

    @MockitoBean
    FeedbackVisibilityService visibility;

    @Autowired
    DeliveredFeedbackContentSource contextFile;

    @Autowired
    ResultPresentation presentation;

    /**
     * The history reads a page of pieces at once. Each batched read answers, for each row, what this test stubs for that
     * row alone, so the answers stay those of the single reads.
     */
    @BeforeEach
    void answerBatchedReadsByRow() {
        when(permission.findAll(anyLong(), any(), anyLong(), any())).thenAnswer(call -> {
            long workspaceId = call.getArgument(0);
            Collection<UUID> ids = call.getArgument(1);
            long viewerId = call.getArgument(2);
            SourceUsePurpose purpose = call.getArgument(3);
            Map<UUID, ResultHistoryEntry> found = new HashMap<>();
            if (ids != null)
                ids.forEach(id ->
                        permission.find(workspaceId, id, viewerId, purpose).ifPresent(entry -> found.put(id, entry)));
            return found;
        });
        when(publicPermission.checkAll(anyLong(), any(), any())).thenAnswer(call -> {
            Collection<ResultFacts> rows = call.getArgument(1);
            SourceUsePurpose purpose = call.getArgument(2);
            Map<UUID, ResultSourceAuthorization.Check> checks = new HashMap<>();
            if (rows != null)
                for (var row : rows) {
                    @Nullable HistoryCustody custody = publicPermission.custody(row);
                    checks.put(
                            row.id(),
                            custody == null
                                    ? new ResultSourceAuthorization.Check(
                                            new HistoryCustody(false, false, List.of(), List.of(), List.of()), false)
                                    : new ResultSourceAuthorization.Check(
                                            custody, publicPermission.authorized(row, custody, purpose)));
                }
            return checks;
        });
        when(visibility.withdrawnAt(anyLong(), any())).thenAnswer(call -> {
            long workspaceId = call.getArgument(0);
            Collection<UUID> ids = call.getArgument(1);
            Map<UUID, Instant> withdrawn = new HashMap<>();
            if (ids != null)
                ids.stream().filter(id -> visibility.withdrawn(workspaceId, id)).forEach(id -> withdrawn.put(id, AT));
            return withdrawn;
        });
    }

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
            jdbc.update("UPDATE account SET status='BLOCKED' WHERE id=?", developerId);
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
     * Hephaestus's context and the developer's own pages read the same states: never a proposal awaiting approval, a
     * rejection, a withholding or a replacement, whose state alone would tell the developer about it.
     */
    @Test
    void shouldShowHephaestusAndTheProfileOnlyTheFeedbackStatesTheDeveloperMayLearnOf() {
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
        assertThat(presentation
                        .ownFeedback(workspace.getId(), facts(List.of(proof)))
                        .get(proof.getId()))
                .extracting(OwnFeedbackRefDTO::id)
                .containsExactlyInAnyOrderElementsOf(visible);
        for (var id : visible)
            assertThat(history.find(workspace.getId(), id, developerId)).isPresent();
        for (var id : hidden)
            assertThat(history.find(workspace.getId(), id, developerId)).isEmpty();
    }

    /** A withdrawn piece is gone for the developer: Hephaestus reads it as their Practice profile does. */
    @Test
    void shouldLeaveOutAWithdrawnPieceAsTheProfileDoes() {
        var proof = result();
        authorize(proof);
        var shown = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, AT);
        var withdrawn = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, AT);
        bind(shown, proof);
        bind(withdrawn, proof);
        // The withdrawn piece is the newest, so a read with a limit of 1 must page past it.
        jdbc.update(
                "UPDATE feedback SET created_at=? WHERE id=?", Timestamp.from(AT.plusSeconds(60)), withdrawn.getId());
        when(visibility.withdrawn(workspace.getId(), withdrawn.getId())).thenReturn(true);
        when(visibility.withdrawnAt(eq(workspace.getId()), argThat(ids -> ids.contains(withdrawn.getId()))))
                .thenReturn(Map.of(withdrawn.getId(), AT));

        assertThat(history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 10))
                .extracting(FeedbackHistoryEntry::feedbackId)
                .contains(shown.getId())
                .doesNotContain(withdrawn.getId());
        assertThat(history.find(workspace.getId(), withdrawn.getId(), developerId))
                .isEmpty();
        // A withdrawn piece does not use up the limit.
        assertThat(history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 1))
                .extracting(FeedbackHistoryEntry::feedbackId)
                .containsExactly(shown.getId());
        // A caller that lists one piece asks for two: behind the withdrawn piece, both kept pieces show that more
        // exist.
        var also = piece(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, AT);
        bind(also, proof);
        assertThat(history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 2))
                .extracting(FeedbackHistoryEntry::feedbackId)
                .containsExactlyInAnyOrder(shown.getId(), also.getId());
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
        var item = new ItemRef(workspace.getId(), itemId, new ItemKind("scm.pull_request"), 42L, null, null);
        when(result.copyAuthorityComplete()).thenReturn(true);
        when(result.developerAccountId()).thenReturn(developerId);
        when(result.item()).thenReturn(item);
        when(result.origin()).thenReturn(ReviewOrigin.REQUEST);
        when(result.practiceSlug()).thenReturn("describe-why");
        when(result.itemId()).thenReturn(itemId);
        when(result.storedWorkVersion()).thenReturn(ResultHistoryEntry.StoredWorkVersion.UNKNOWN);
        when(permission.findOfProblem(
                        workspace.getId(), problemId, developerId, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(result));
        var main = conversations.findOrCreateMain(workspace.getId(), developerId);
        var asked = turns.submit(
                workspace.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        "What did the review find?",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        ConversationTurn running = running(asked.turn().getId());
        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.DONE,
                        List.of(
                                ConversationLedgerFixtures.toolCall(
                                        ConversationLedgerFixtures.piId(),
                                        "call-problem",
                                        "link_problem",
                                        JsonNodeFactory.instance
                                                .objectNode()
                                                .put("problemId", problemId.toString())
                                                .put("text", "The failure case has no test.")),
                                ConversationLedgerFixtures.toolResult(
                                        ConversationLedgerFixtures.piId(),
                                        "call-problem",
                                        "link_problem",
                                        "Linked",
                                        false),
                                ConversationLedgerFixtures.toolCall(
                                        ConversationLedgerFixtures.piId(),
                                        "call-feedback",
                                        "show_feedback",
                                        JsonNodeFactory.instance
                                                .objectNode()
                                                .put("feedbackId", shown.getId().toString())
                                                .put("text", "Say why.")),
                                ConversationLedgerFixtures.toolResult(
                                        ConversationLedgerFixtures.piId(),
                                        "call-feedback",
                                        "show_feedback",
                                        "Shown",
                                        false)),
                        null,
                        null));

        List<ConversationLinkDTO> links = timeline.entries(workspace.getId(), developerId, null, 10).content().stream()
                .flatMap(entry -> entry.links().stream())
                .toList();

        assertThat(links)
                .extracting(ConversationLinkDTO::kind, ConversationLinkDTO::practiceSlug, ConversationLinkDTO::itemId)
                .containsExactly(
                        tuple(ConversationLinkDTO.Kind.PROBLEM, "describe-why", itemId),
                        tuple(ConversationLinkDTO.Kind.FEEDBACK, "regression-tests", itemId));
    }

    /** Claims turns until this one starts. A turn of another test that starts first is taken along. */
    private ConversationTurn running(UUID turnId) {
        while (true) {
            ConversationTurn claimed = claims.claimNext().orElseThrow();
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
    void shouldCloseAPracticeInThePublicHistoryOnlyWhenEveryNoteOnItIsClosedAsFixed() {
        var row = piece(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, AT);
        var closed = result();
        var summarized = result();
        var mixed = result();
        var proofs = List.of(closed, summarized, mixed);
        for (int ordinal = 0; ordinal < proofs.size(); ordinal++) {
            var proof = proofs.get(ordinal);
            basis.saveAndFlush(FeedbackBasis.builder()
                    .id(FeedbackIds.basis(row.getId(), proof.getId(), null))
                    .workspaceId(workspace.getId())
                    .feedbackId(row.getId())
                    .resultId(proof.getId())
                    .developerId(developerId)
                    .ordinal(ordinal)
                    .build());
            authorize(proof);
        }
        summary(row, FeedbackCopyState.POSTED);
        // The summary rests on all three practices; the notes on one each, and the mixed practice has two notes.
        var support = new ArrayList<FeedbackCompositionTrace.CopySupport>();
        support.add(new FeedbackCompositionTrace.CopySupport(
                FeedbackCopyKind.SUMMARY,
                0,
                proofs.stream()
                        .map(proof -> new FeedbackCompositionTrace.Support(proof.getId(), null))
                        .toList()));
        var notes = List.of(closed, mixed, mixed);
        for (int index = 0; index < notes.size(); index++) {
            int position = index + 1;
            var copyId = FeedbackIds.copy(row.getId(), FeedbackCopyKind.LINE_NOTE, position);
            String thread = "gid://gitlab/Discussion/" + copyId;
            copies.saveAndFlush(FeedbackCopy.builder()
                    .id(copyId)
                    .workspaceId(workspace.getId())
                    .feedbackId(row.getId())
                    .kind(FeedbackCopyKind.LINE_NOTE)
                    .position(position)
                    .state(FeedbackCopyState.POSTED)
                    .body("Cover the failure case here.")
                    .path("src/A.java")
                    .startLine(position)
                    .endLine(position)
                    .side(CitationSide.NEW)
                    .writeKey(copyId.toString())
                    .writeMarker(FeedbackIds.writeMarker(copyId))
                    .writeStartedAt(AT)
                    .postedAt(AT)
                    .externalRef("note-" + position)
                    .threadRef(thread)
                    .createdAt(AT)
                    .build());
            support.add(new FeedbackCompositionTrace.CopySupport(
                    FeedbackCopyKind.LINE_NOTE,
                    position,
                    List.of(new FeedbackCompositionTrace.Support(
                            notes.get(index).getId(), null))));
            // Hephaestus resolved the first two notes' threads on GitLab, where it never reopens one.
            if (position < 3) jdbc.update("""
                        INSERT INTO feedback_thread_resolution (id, workspace_id, item_id, thread_ref, copy_id,
                          generation, resolved, resolve_written, reopens, provider, state, changed_at)
                        VALUES (?, ?, ?, ?, ?, 1, true, true, false, 'GITLAB', 'DONE', now())
                        """, UUID.randomUUID(), workspace.getId(), itemId, thread, copyId);
        }
        jdbc.update(
                """
                UPDATE review SET checks = jsonb_set(COALESCE(checks, '{}'::jsonb), '{feedbackComposition}',
                  COALESCE(checks->'feedbackComposition', '{}'::jsonb) || jsonb_build_object(?::text, ?::jsonb))
                WHERE id = ?
                """,
                row.getId().toString(),
                mapper.writeValueAsString(new FeedbackCompositionTrace(support, List.of(), null, null)),
                reviewId);

        var publicHistory = Objects.requireNonNull(history.forItem(workspace.getId(), itemId, developerId, 1)
                .getFirst()
                .publicHistory());

        // Only the practice whose every note is closed: not the one that only the summary carries, nor the mixed one.
        assertThat(publicHistory.path("closedPractices").valueStream().map(JsonNode::asString))
                .containsExactly(slug(closed));
    }

    @Test
    void shouldGiveEachNoteThePracticeAndIdentityOfTheProblemsItCites() {
        var row = piece(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, AT);
        var identified = negativeResult();
        var unidentified = negativeResult();
        var proofs = List.of(identified, unidentified);
        for (int ordinal = 0; ordinal < proofs.size(); ordinal++) {
            var proof = proofs.get(ordinal);
            // The basis of a piece on the work is the support of its copies, problems included.
            basis.saveAndFlush(FeedbackBasis.builder()
                    .id(FeedbackIds.basis(row.getId(), proof.getId(), problemOf(proof)))
                    .workspaceId(workspace.getId())
                    .feedbackId(row.getId())
                    .resultId(proof.getId())
                    .problemId(problemOf(proof))
                    .developerId(developerId)
                    .ordinal(ordinal)
                    .build());
            authorize(proof);
        }
        jdbc.update(
                "UPDATE problem SET identity = 'key:missing-test' WHERE workspace_id = ? AND result_id = ?",
                workspace.getId(),
                identified.getId());
        summary(row, FeedbackCopyState.POSTED);
        var support = new ArrayList<FeedbackCompositionTrace.CopySupport>();
        support.add(new FeedbackCompositionTrace.CopySupport(
                FeedbackCopyKind.SUMMARY,
                0,
                List.of(new FeedbackCompositionTrace.Support(identified.getId(), problemOf(identified)))));
        // The first note cites a problem with an identity; the second one cites a problem without one.
        for (int position = 1; position <= proofs.size(); position++) {
            var proof = proofs.get(position - 1);
            var copyId = FeedbackIds.copy(row.getId(), FeedbackCopyKind.LINE_NOTE, position);
            copies.saveAndFlush(FeedbackCopy.builder()
                    .id(copyId)
                    .workspaceId(workspace.getId())
                    .feedbackId(row.getId())
                    .kind(FeedbackCopyKind.LINE_NOTE)
                    .position(position)
                    .state(FeedbackCopyState.POSTED)
                    .body("Cover the failure case here.")
                    .path("src/A.java")
                    .startLine(position)
                    .endLine(position)
                    .side(CitationSide.NEW)
                    .writeKey(copyId.toString())
                    .writeMarker(FeedbackIds.writeMarker(copyId))
                    .writeStartedAt(AT)
                    .postedAt(AT)
                    .externalRef("note-" + position)
                    .createdAt(AT)
                    .build());
            support.add(new FeedbackCompositionTrace.CopySupport(
                    FeedbackCopyKind.LINE_NOTE,
                    position,
                    List.of(new FeedbackCompositionTrace.Support(proof.getId(), problemOf(proof)))));
        }
        jdbc.update(
                """
                UPDATE review SET checks = jsonb_set(COALESCE(checks, '{}'::jsonb), '{feedbackComposition}',
                  COALESCE(checks->'feedbackComposition', '{}'::jsonb) || jsonb_build_object(?::text, ?::jsonb))
                WHERE id = ?
                """,
                row.getId().toString(),
                mapper.writeValueAsString(new FeedbackCompositionTrace(support, List.of(), null, null)),
                reviewId);

        var publicHistory = Objects.requireNonNull(history.forItem(workspace.getId(), itemId, developerId, 1)
                .getFirst()
                .publicHistory());

        var cites = new HashMap<String, JsonNode>();
        publicHistory
                .path("copies")
                .forEach(copy -> cites.put(
                        copy.path("kind").asString() + copy.path("externalRef").asString(""), copy.path("cites")));
        assertThat(cites.get("LINE_NOTEnote-1"))
                .isEqualTo(mapper.createArrayNode()
                        .add(mapper.createObjectNode()
                                .put("practiceSlug", slug(identified))
                                .put("identity", "key:missing-test")));
        assertThat(cites.get("LINE_NOTEnote-2")).isEqualTo(mapper.createArrayNode());
        assertThat(cites.get("SUMMARY" + "copy-" + FeedbackIds.copy(row.getId(), FeedbackCopyKind.SUMMARY, 0)))
                .as("a summary may point to its notes, so it cites nothing")
                .isEqualTo(mapper.createArrayNode());
    }

    /**
     * The history reads the mirrored state of every note thread of a page in one read; the mirror's own query is
     * {@code ScmMirroredThreadStateQueryIntegrationTest}'s.
     */
    @Test
    void shouldGiveEachNoteTheStateOfItsThreadAsTheMirrorOfTheProviderHoldsIt() {
        var row = piece(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, AT);
        var proof = result();
        basis.saveAndFlush(FeedbackBasis.builder()
                .id(FeedbackIds.basis(row.getId(), proof.getId(), null))
                .workspaceId(workspace.getId())
                .feedbackId(row.getId())
                .resultId(proof.getId())
                .developerId(developerId)
                .ordinal(0)
                .build());
        authorize(proof);
        summary(row, FeedbackCopyState.POSTED);
        var threads = new ArrayList<String>();
        for (int position = 1; position <= 3; position++) {
            var copyId = FeedbackIds.copy(row.getId(), FeedbackCopyKind.LINE_NOTE, position);
            threads.add("PRRT_" + copyId);
            copies.saveAndFlush(FeedbackCopy.builder()
                    .id(copyId)
                    .workspaceId(workspace.getId())
                    .feedbackId(row.getId())
                    .kind(FeedbackCopyKind.LINE_NOTE)
                    .position(position)
                    .state(FeedbackCopyState.POSTED)
                    .body("Cover the failure case here.")
                    .path("src/A.java")
                    .startLine(position)
                    .endLine(position)
                    .side(CitationSide.NEW)
                    .writeKey(copyId.toString())
                    .writeMarker(FeedbackIds.writeMarker(copyId))
                    .writeStartedAt(AT)
                    .postedAt(AT)
                    .externalRef("note-" + position)
                    .threadRef(threads.getLast())
                    .createdAt(AT)
                    .build());
        }
        // A person resolved the first thread, the second is open, and the mirror holds no thread for the third.
        when(mirroredThreads.resolved(
                        eq(workspace.getId()),
                        eq(List.of(itemId)),
                        argThat(refs -> refs.containsAll(threads) && refs.size() == threads.size())))
                .thenReturn(Map.of(threads.get(0), true, threads.get(1), false));

        var publicHistory = Objects.requireNonNull(history.forItem(workspace.getId(), itemId, developerId, 1)
                .getFirst()
                .publicHistory());

        var threadStates = new HashMap<String, JsonNode>();
        publicHistory
                .path("copies")
                .forEach(copy -> threadStates.put(copy.path("externalRef").asString(""), copy.path("threadState")));
        assertThat(Objects.requireNonNull(threadStates.get("note-1")).asString())
                .isEqualTo("RESOLVED");
        assertThat(Objects.requireNonNull(threadStates.get("note-2")).asString())
                .isEqualTo("OPEN");
        assertThat(Objects.requireNonNull(threadStates.get("note-3")).asString())
                .isEqualTo("UNKNOWN");
        assertThat(Objects.requireNonNull(
                                threadStates.get("copy-" + FeedbackIds.copy(row.getId(), FeedbackCopyKind.SUMMARY, 0)))
                        .isNull())
                .as("a summary has no thread")
                .isTrue();
    }

    @Test
    void shouldFlagInTheReadbackANoteWhoseProblemANoteOfAnotherReviewOnTheWorkCites() {
        // Another review's note reached the work after this piece was prepared, at AT: it raced this review.
        var race = race(AT.plusSeconds(120));
        var later = piece(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, AT.plusSeconds(60));
        var laterNote = postedNote(later, race.result(), "https://github.com/acme/app/pull/7#discussion_r2", AT);
        readBack(later, laterNote);

        var status = repairs.statusWithRepeats(workspace.getId(), reviewId);

        assertThat(status.state()).isEqualTo(FeedbackRepairStatus.State.READBACK);
        assertThat(status.repeats()).containsExactly(race.repeatOf(laterNote));
    }

    @Test
    void shouldFlagAtAQuietEditsReadbackANoteThatAnotherReviewPostedBetweenTheFirstPublicationAndTheEdit() {
        // The other note arrived after this composition first published, at AT, and before its quiet edit.
        var race = race(AT.plusSeconds(120));
        var root = piece(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, AT);
        postedNote(root, race.result(), "https://github.com/acme/app/pull/7#discussion_r2", AT);
        var edit = piece(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, AT.plusSeconds(300));
        jdbc.update(
                "UPDATE feedback SET created_at = ? WHERE id = ?", Timestamp.from(AT.plusSeconds(300)), edit.getId());
        var editNote = postedNote(edit, race.result(), "https://github.com/acme/app/pull/7#discussion_r2", AT);
        feedback.updateComposition(
                workspace.getId(),
                root.getId(),
                mapper.createObjectNode().put("latest", edit.getId().toString()));
        readBack(edit, editNote);
        jdbc.update(
                """
                UPDATE feedback SET composition = composition || jsonb_build_object('root', ?::text, 'editOf', ?::text,
                  'latest', ?::text) WHERE id = ?
                """,
                root.getId().toString(),
                root.getId().toString(),
                edit.getId().toString(),
                edit.getId());

        var status = repairs.statusWithRepeats(workspace.getId(), reviewId);

        assertThat(status.feedbackId()).isEqualTo(edit.getId());
        assertThat(status.repeats()).containsExactly(race.repeatOf(editNote));
    }

    /** Another review's note on the work, and this review's result of the same problem for another developer. */
    private record Race(Result result, FeedbackCopy other, UUID problem) {
        FeedbackRepairStatus.Repeat repeatOf(FeedbackCopy mine) {
            return new FeedbackRepairStatus.Repeat(
                    mine.getId(),
                    List.of(problem),
                    new FeedbackRepairStatus.OtherNote(
                            "src/A.java",
                            1,
                            1,
                            "https://github.com/acme/app/pull/7#discussion_r1",
                            other.getBody(),
                            NoteThreadState.UNKNOWN));
        }
    }

    private Race race(Instant otherPostedAt) {
        var earlierResult = negativeResult();
        authorize(earlierResult);
        var earlier = piece(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, AT);
        var earlierNote =
                postedNote(earlier, earlierResult, "https://github.com/acme/app/pull/7#discussion_r1", otherPostedAt);
        var earlierReview = reviewId;
        // This review measures the same practice on the same work for another developer, and finds the same problem.
        reviewId = UUID.randomUUID();
        developerId = Objects.requireNonNull(
                ensureMembership(workspace, persistUser("second-developer"), Membership.WorkspaceRole.MEMBER)
                        .getAccountId());
        jdbc.update("""
                INSERT INTO review SELECT (jsonb_populate_record(NULL::review, to_jsonb(v)
                  || jsonb_build_object('id', ?::text))).* FROM review v WHERE v.id = ?
                """, reviewId.toString(), earlierReview);
        jdbc.update("""
                INSERT INTO review_practice SELECT (jsonb_populate_record(NULL::review_practice, to_jsonb(p)
                  || jsonb_build_object('review_id', ?::text))).* FROM review_practice p WHERE p.review_id = ?
                """, reviewId.toString(), earlierReview);
        jdbc.update("""
                INSERT INTO review_item SELECT (jsonb_populate_record(NULL::review_item, to_jsonb(m)
                  || jsonb_build_object('review_id', ?::text))).* FROM review_item m WHERE m.review_id = ?
                """, reviewId.toString(), earlierReview);
        UUID laterResultId = UUID.randomUUID();
        // One statement: a NOT_MET result must hold its problem when the statement ends.
        jdbc.update(
                """
                WITH copied AS (
                  INSERT INTO result SELECT (jsonb_populate_record(NULL::result, to_jsonb(r)
                    || jsonb_build_object('id', ?::text, 'review_id', ?::text, 'person_account_id', ?::bigint,
                      'ended_at', r.recorded_at, 'end_reason', 'REPLACED'))).*
                  FROM result r WHERE r.id = ? RETURNING id)
                INSERT INTO problem SELECT (jsonb_populate_record(NULL::problem, to_jsonb(p)
                  || jsonb_build_object('id', gen_random_uuid()::text, 'result_id', (SELECT id FROM copied)::text))).*
                FROM problem p WHERE p.result_id = ?
                """,
                laterResultId.toString(),
                reviewId.toString(),
                developerId,
                earlierResult.getId(),
                earlierResult.getId());
        jdbc.update(
                "UPDATE problem SET identity = 'key:missing-test' WHERE workspace_id = ? AND result_id IN (?, ?)",
                workspace.getId(),
                earlierResult.getId(),
                laterResultId);
        var laterResult =
                results.findByIdAndWorkspaceId(laterResultId, workspace.getId()).orElseThrow();
        authorize(laterResult);
        return new Race(laterResult, earlierNote, problemOf(laterResult));
    }

    /** The provider found the note and it is not resolved. */
    private void readBack(Feedback row, FeedbackCopy note) {
        var readback = mapper.createArrayNode();
        readback.addObject()
                .put("copyId", note.getId().toString())
                .put("status", "FOUND")
                .put("body", "Cover the failure case here.")
                .put("renderedBody", "Cover the failure case here.")
                .putNull("anchor")
                .put("resolved", false)
                .put("outdated", false);
        var state = mapper.createObjectNode();
        state.set("readback", readback);
        feedback.updateComposition(workspace.getId(), row.getId(), state);
    }

    /** A posted line note at position 1 of the piece, resting on the result's problem, with its composition support. */
    private FeedbackCopy postedNote(Feedback row, Result proof, String url, Instant postedAt) {
        var problem = problemOf(proof);
        basis.saveAndFlush(FeedbackBasis.builder()
                .id(FeedbackIds.basis(row.getId(), proof.getId(), problem))
                .workspaceId(workspace.getId())
                .feedbackId(row.getId())
                .resultId(proof.getId())
                .problemId(problem)
                .developerId(developerId)
                .ordinal(0)
                .build());
        var copyId = FeedbackIds.copy(row.getId(), FeedbackCopyKind.LINE_NOTE, 1);
        var note = copies.saveAndFlush(FeedbackCopy.builder()
                .id(copyId)
                .workspaceId(workspace.getId())
                .feedbackId(row.getId())
                .kind(FeedbackCopyKind.LINE_NOTE)
                .position(1)
                .state(FeedbackCopyState.POSTED)
                .body("Cover the failure case here.")
                .path("src/A.java")
                .startLine(1)
                .endLine(1)
                .side(CitationSide.NEW)
                .writeKey(copyId.toString())
                .writeMarker(FeedbackIds.writeMarker(copyId))
                .writeStartedAt(postedAt)
                .postedAt(postedAt)
                .externalRef("note-" + copyId)
                .externalUrl(url)
                .createdAt(AT)
                .build());
        var support = List.of(new FeedbackCompositionTrace.CopySupport(
                FeedbackCopyKind.LINE_NOTE, 1, List.of(new FeedbackCompositionTrace.Support(proof.getId(), problem))));
        jdbc.update(
                """
                UPDATE review SET checks = jsonb_set(COALESCE(checks, '{}'::jsonb), '{feedbackComposition}',
                  COALESCE(checks->'feedbackComposition', '{}'::jsonb) || jsonb_build_object(?::text, ?::jsonb))
                WHERE id = ?
                """,
                row.getId().toString(),
                mapper.writeValueAsString(new FeedbackCompositionTrace(support, List.of(), null, null)),
                reviewId);
        return note;
    }

    private UUID problemOf(Result proof) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM problem WHERE workspace_id = ? AND result_id = ?",
                UUID.class,
                workspace.getId(),
                proof.getId()));
    }

    private String slug(Result proof) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT slug FROM practice WHERE id = ?", String.class, proof.getPracticeId()));
    }

    @Test
    void shouldReturnTheActualShownConversationWordsAndThread() {
        var row = piece(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, AT);
        var proof = result();
        bind(row, proof);
        authorize(proof);
        UUID conversation = UUID.randomUUID(), answer = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO conversation(id,workspace_id,account_id,created_at,last_activity_at)"
                        + " VALUES (?,?,?,now(),now())",
                conversation,
                workspace.getId(),
                developerId);
        jdbc.update(
                "INSERT INTO conversation_entry(id,workspace_id,conversation_id,account_id,seq,kind,surface,text,"
                        + "created_at) VALUES (?,?,?,?,1,'ANSWER','APP','Try a focused test.',now())",
                answer,
                workspace.getId(),
                conversation,
                developerId);
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
                .conversationEntryId(answer)
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
    void shouldUseConversationPermissionForPrivateFeedbackHistory() {
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
        when(publicPermission.custody(argThat(row -> row != null && row.id().equals(proof.getId()))))
                .thenReturn(custody);
        for (var purpose :
                List.of(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, SourceUsePurpose.CONVERSATIONAL_MENTORING))
            when(publicPermission.authorized(
                            argThat(row -> row != null && row.id().equals(proof.getId())), eq(custody), eq(purpose)))
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
