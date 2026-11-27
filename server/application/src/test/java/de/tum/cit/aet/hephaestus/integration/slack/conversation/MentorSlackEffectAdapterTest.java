package de.tum.cit.aet.hephaestus.integration.slack.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.api.model.block.MarkdownBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionEnded;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.action.ActionVariants;
import de.tum.cit.aet.hephaestus.integration.slack.SlackHephaestusUiLinks;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.mentor.spi.Audience;
import de.tum.cit.aet.hephaestus.mentor.spi.AuthorityScope;
import de.tum.cit.aet.hephaestus.mentor.spi.EffectIntent;
import de.tum.cit.aet.hephaestus.mentor.spi.EffectKind;
import de.tum.cit.aet.hephaestus.mentor.spi.EffectOutcome;
import de.tum.cit.aet.hephaestus.mentor.spi.EffectRef;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorEffectProof;
import de.tum.cit.aet.hephaestus.mentor.spi.ObservationTarget;
import de.tum.cit.aet.hephaestus.mentor.spi.Proof;
import de.tum.cit.aet.hephaestus.mentor.spi.ProofRefusal;
import de.tum.cit.aet.hephaestus.mentor.spi.ProviderObservation;
import de.tum.cit.aet.hephaestus.mentor.spi.RenderProjection;
import de.tum.cit.aet.hephaestus.mentor.spi.Route;
import de.tum.cit.aet.hephaestus.mentor.spi.ScopeStamp;
import de.tum.cit.aet.hephaestus.mentor.spi.Transport;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class MentorSlackEffectAdapterTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");
    private static final Route ROUTE = new Route(Transport.SLACK, "T1", "D1", null, "100.000001", 7L, 42L, 9L, "U1");
    private static final EffectRef REF = new EffectRef(UUID.randomUUID());
    private static final String KEY = "notice:9:42";
    private static final String MARKER = "opaque";
    private static final Proof.Allowed ALLOWED = new Proof.Allowed(
            new EffectIntent(
                    REF,
                    KEY,
                    EffectKind.RETENTION_NOTICE,
                    null,
                    new Audience(Transport.SLACK, 42L, "D1", null),
                    ROUTE,
                    new ScopeStamp(9L, 0L, 5L, 0L, new AuthorityScope.Slack(7L, 0L, 42L, 0L, "U1")),
                    null,
                    NOW.plusSeconds(120),
                    MARKER),
            new RenderProjection.FixedText(EffectKind.RETENTION_NOTICE));
    private static final ProviderObservation OBSERVED =
            new ProviderObservation(REF, ROUTE.fingerprint(), "U1", Set.of("U1"), 5L, NOW);
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Mock
    private ObjectProvider<SlackMessageService> messages;

    @Mock
    private ObjectProvider<SlackHephaestusUiLinks> links;

    @Mock
    private ObjectProvider<MentorEffectProof> proof;

    @Mock
    private SlackMessageService slack;

    @Mock
    private SlackMessageService.BoundRoute bound;

    @Mock
    private SlackHephaestusUiLinks app;

    @Mock
    private MentorEffectProof runtime;

    @Mock
    private PlatformTransactionManager transactions;

    @Test
    void shouldPostFixedCopyAndTheExactBindingSettingsLinkAfterTheSecondProof() {
        var adapter = ready();
        when(runtime.check(REF, OBSERVED)).thenReturn(ALLOWED);
        readyToPost();
        when(bound.post(anyList(), anyString(), eq(MARKER), any())).thenAnswer(call -> {
            var blocks = call.<List<?>>getArgument(0);
            assertThat(blocks).hasSize(2);
            assertThat(blocks.getFirst())
                    .isInstanceOfSatisfying(
                            MarkdownBlock.class,
                            block -> assertThat(block.getText())
                                    .isEqualTo("Slack may retain and export this DM, including Hephaestus replies."));
            assertThat(blocks.getLast())
                    .isInstanceOfSatisfying(
                            SectionBlock.class,
                            block -> assertThat(block.getText())
                                    .isInstanceOfSatisfying(
                                            MarkdownTextObject.class,
                                            link -> assertThat(link.getText())
                                                    .isEqualTo(
                                                            "<https://app.example.test/settings#slack-dm-binding-42|Use my personal context in Slack DMs>")));
            assertThat(call.<BooleanSupplier>getArgument(3).getAsBoolean()).isTrue();
            return new SlackMessageService.Posted("D1", "101.000001");
        });

        assertThat(adapter.execute(attempt(false))).isEqualTo(new ActionResult.Done("101.000001"));
        verify(runtime, times(2)).check(REF, OBSERVED);
    }

    @Test
    void shouldCarryTheWireProofRefusalOutOfTheSlackCallback() {
        var adapter = ready();
        when(runtime.check(REF, OBSERVED)).thenReturn(ALLOWED, new Proof.Refused(ProofRefusal.AUTHORIZATION_CHANGED));
        readyToPost();
        when(bound.post(anyList(), anyString(), eq(MARKER), any())).thenAnswer(call -> {
            assertThat(call.<BooleanSupplier>getArgument(3).getAsBoolean()).isFalse();
            throw new SlackSendException(7L, "D1", "not_admitted");
        });

        assertThat(adapter.execute(attempt(false)))
                .isEqualTo(new ActionResult.Cancelled(ActionStopReason.WITHDRAWN, ProofRefusal.AUTHORIZATION_CHANGED));
    }

    @Test
    void shouldRecoverTheNoticeByItsMarkerEvenWithoutARestoredWriteMarker() {
        var adapter = ready();
        when(runtime.check(REF, OBSERVED)).thenReturn(ALLOWED);
        when(bound.readPost(MARKER, 3)).thenReturn(new SlackMessageService.Matches(List.of("101.000001"), false));

        assertThat(adapter.execute(attempt(false))).isEqualTo(new ActionResult.Done("101.000001"));
        verify(bound, never()).post(anyList(), anyString(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldLeaveIncompleteReadbackUnconfirmed(boolean possibleReceipt) {
        var adapter = ready();
        when(runtime.check(REF, OBSERVED)).thenReturn(ALLOWED);
        var receipts = possibleReceipt ? List.of("101.000001") : List.<String>of();
        when(bound.readPost(MARKER, 3)).thenReturn(new SlackMessageService.Matches(receipts, true));

        assertThat(adapter.execute(attempt(true)))
                .isEqualTo(new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED));
        verify(bound, never()).post(anyList(), anyString(), anyString(), any());
    }

    @Test
    void shouldRoundUpTheKnownPost429AndSpendTheAttemptOnTheSameRoute() {
        var adapter = ready();
        when(runtime.check(REF, OBSERVED)).thenReturn(ALLOWED);
        readyToPost();
        when(bound.post(anyList(), anyString(), eq(MARKER), any())).thenAnswer(call -> {
            assertThat(call.<BooleanSupplier>getArgument(3).getAsBoolean()).isTrue();
            throw new SlackSendException(7L, "D1", "ratelimited", 12_345);
        });

        assertThat(adapter.execute(attempt(false))).isEqualTo(new ActionResult.Retry(Duration.ofSeconds(13)));
    }

    @Test
    void shouldRefuseTheOriginalRouteWhenTheAuthorizationIsStaleOnRetry() {
        var adapter = ready();
        when(runtime.check(REF, OBSERVED)).thenReturn(new Proof.Refused(ProofRefusal.AUTHORIZATION_CHANGED));

        assertThat(adapter.execute(attempt(false)))
                .isEqualTo(new ActionResult.Cancelled(ActionStopReason.WITHDRAWN, ProofRefusal.AUTHORIZATION_CHANGED));
        verify(slack).bind(ROUTE);
        verify(bound, never()).post(anyList(), anyString(), anyString(), any());
    }

    @Test
    void shouldDeferAfterTheWriteProofRollsBackOnAuthorityContention() {
        var adapter = ready();
        when(runtime.check(REF, OBSERVED))
                .thenReturn(ALLOWED)
                .thenThrow(new CannotAcquireLockException("Workspace lifecycle holds the guard"));
        readyToPost();
        when(bound.post(anyList(), anyString(), eq(MARKER), any())).thenAnswer(call -> {
            assertThat(call.<BooleanSupplier>getArgument(3).getAsBoolean()).isFalse();
            throw new SlackSendException(7L, "D1", "not_admitted");
        });

        assertThat(adapter.execute(attempt(false))).isEqualTo(new ActionResult.Defer(Duration.ofSeconds(2)));
    }

    @Test
    void shouldSettleReaperExpiryByTheOutboxActionId() {
        when(proof.getIfAvailable()).thenReturn(runtime);
        var id = UUID.randomUUID();

        adapter()
                .ended(new ActionEnded(
                        id,
                        7L,
                        ActionKind.MENTOR_EFFECT,
                        KEY,
                        ActionState.CANCELLED,
                        ActionStopReason.EXPIRED,
                        null,
                        null));

        verify(runtime).settleAction(id, new EffectOutcome.Expired());
    }

    @Test
    void shouldDeclineAllClaimsWithoutTheMentorRuntimeProof() {
        assertThat(adapter().variants()).isSameAs(ActionVariants.none());
    }

    @Test
    void shouldRejectEndingTheOutboxActionWhenSettlementIsUnavailable() {
        var ended = new ActionEnded(
                UUID.randomUUID(),
                7L,
                ActionKind.MENTOR_EFFECT,
                KEY,
                ActionState.DONE,
                ActionStopReason.COMPLETED,
                "101.000001",
                null);

        assertThatIllegalStateException().isThrownBy(() -> adapter().ended(ended));
    }

    /** Review probe: a read that Slack refused proves nothing, so it must not end the once-only notice. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void probeAFailedReadbackIsReadAgainWithinTheDeadline(boolean writeStarted) {
        var adapter = ready();
        when(runtime.check(REF, OBSERVED)).thenReturn(ALLOWED);
        // What the gateway throws for a 429 beyond its budget, an HTTP 5xx or a timeout.
        when(bound.readPost(MARKER, 3)).thenThrow(new SlackSendException(7L, "D1", "transport_failure"));

        // A possible earlier write keeps its marker (Reread). A fresh attempt wrote nothing.
        assertThat(adapter.execute(attempt(writeStarted)))
                .isInstanceOfAny(
                        writeStarted
                                ? new Class<?>[] {ActionResult.Reread.class}
                                : new Class<?>[] {
                                    ActionResult.Defer.class, ActionResult.Retry.class, ActionResult.Reread.class
                                });
    }

    /** Review probe: a post that failed before its send boundary wrote nothing and was refused by nobody. */
    @Test
    void probeAConnectionFailureBeforeAdmissionIsNotAPermanentRefusal() {
        var adapter = ready();
        when(runtime.check(REF, OBSERVED)).thenReturn(ALLOWED);
        readyToPost();
        // The send fence is a network interceptor: a connect failure never asks it for admission.
        when(bound.post(anyList(), anyString(), eq(MARKER), any()))
                .thenThrow(new SlackSendException(7L, "D1", "transport_failure"));

        assertThat(adapter.execute(attempt(false)))
                .isInstanceOfAny(ActionResult.Defer.class, ActionResult.Retry.class);
    }

    private MentorSlackEffectAdapter ready() {
        when(proof.getIfAvailable()).thenReturn(runtime);
        when(runtime.observationTarget(REF)).thenReturn(Optional.of(new ObservationTarget(REF, ROUTE, 3L, 5L)));
        when(messages.getIfAvailable()).thenReturn(slack);
        when(slack.bind(ROUTE)).thenReturn(Optional.of(bound));
        when(bound.observesAskerInDm()).thenReturn(true);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return adapter();
    }

    private MentorSlackEffectAdapter adapter() {
        return new MentorSlackEffectAdapter(
                messages, links, proof, mapper, Clock.fixed(NOW, ZoneOffset.UTC), transactions);
    }

    private void readyToPost() {
        when(bound.readPost(MARKER, 3)).thenReturn(new SlackMessageService.Matches(List.of(), false));
        when(links.getIfAvailable()).thenReturn(app);
        when(app.userSettingsUrl()).thenReturn("https://app.example.test/settings");
    }

    private ActionAttempt attempt(boolean started) {
        return new ActionAttempt(
                UUID.randomUUID(),
                7L,
                ActionKind.MENTOR_EFFECT,
                KEY,
                mapper.valueToTree(new MentorSlackEffectAdapter.Payload(REF.effectId())),
                1,
                3L,
                started,
                null,
                BooleanSupplier::getAsBoolean,
                null);
    }
}
