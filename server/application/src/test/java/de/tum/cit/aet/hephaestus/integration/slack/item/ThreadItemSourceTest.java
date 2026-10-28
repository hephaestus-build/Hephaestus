package de.tum.cit.aet.hephaestus.integration.slack.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadEvents;
import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SourceReadAudience;
import de.tum.cit.aet.hephaestus.integration.slack.capture.ThreadCaptureSource;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationProjector;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThread;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadMessageRow;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class ThreadItemSourceTest extends BaseUnitTest {
    private final SlackThreadRepository threads = mock(SlackThreadRepository.class);
    private final SlackMessageRepository messages = mock(SlackMessageRepository.class);
    private final ConnectionService connections = mock(ConnectionService.class);
    private final GitProviderRegistry providers = mock(GitProviderRegistry.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ItemRef item = new ItemRef(7L, 41L, ThreadEvents.THREAD, 19L, null, null);
    private ThreadItemSource source;

    @BeforeEach
    void setUp() {
        var projector = new SlackConversationProjector(threads, messages, mapper, mock(PersonDataCopyRecorder.class));
        source = new ThreadItemSource(
                threads,
                messages,
                projector,
                new ThreadCaptureSource(mapper, projector, threads, connections),
                providers,
                mapper,
                connections);
    }

    private static Connection connection(String team, long id) {
        var workspace = new Workspace();
        workspace.setId(7L);
        var connection = new Connection(
                workspace, IntegrationKind.SLACK, team, new ConnectionConfig.SlackConfig(team, null, null, Set.of()));
        ReflectionTestUtils.setField(connection, "id", id);
        connection.setState(IntegrationState.ACTIVE);
        return connection;
    }

    private SlackThreadRepository.SettledCandidateRow readableThread() {
        when(connections.findActiveConnections(7L, IntegrationKind.SLACK)).thenReturn(List.of(connection("T1", 21L)));
        var candidate = mock(SlackThreadRepository.SettledCandidateRow.class);
        when(candidate.getSlackChannelId()).thenReturn("C1");
        when(candidate.getSlackTeamId()).thenReturn("T1");
        when(candidate.getSlackThreadTs()).thenReturn("1700000000.000000");
        when(threads.findConsentedCandidateRow(7L, 19L)).thenReturn(Optional.of(candidate));
        when(messages.countLiveTurns(7L, "C1", "1700000000.000000")).thenReturn(2L);
        when(messages.countReadableTurns(7L, "C1", "1700000000.000000")).thenReturn(2L);
        return candidate;
    }

    private void readyThread() {
        var candidate = readableThread();
        when(candidate.getLastTs()).thenReturn("1700000001.000000");
        when(messages.findThreadMessages(anyLong(), any(), any(), any()))
                .thenReturn(List.of(turn("First", "U1", 55L), turn("Second", "U1", 55L)));
        when(providers.findProviderId("SLACK", "https://slack.com")).thenReturn(Optional.of(3L));
    }

    private void allowCapture() {
        var thread = new SlackThread();
        thread.setWorkspaceId(7L);
        thread.setSlackChannelId("C1");
        thread.setSlackThreadTs("1700000000.000000");
        thread.setLastTs("1700000001.000000");
        when(threads.findByWorkspaceIdAndId(7L, 19L)).thenReturn(Optional.of(thread));
    }

    @Test
    void shouldPinTheWholeMessageSetAndExactTeamWhenOneHumanAuthoredTheThread() {
        readyThread();
        allowCapture();
        var pinned = source.pin(item, null);
        assertThat(pinned.item().itemId()).isEqualTo(41L);
        assertThat(pinned.integration().instanceKey()).isEqualTo("T1");
        assertThat(pinned.integration().connectionId()).isEqualTo(21L);
        assertThat(pinned.roles()).singleElement().satisfies(role -> {
            assertThat(role.identity().subject()).isEqualTo("U1");
            assertThat(role.identity().teamId()).isEqualTo("T1");
        });
        var captured = source.capture(pinned, request());
        assertThat(mapper.readTree(Objects.requireNonNull(captured.files().get(ThreadCaptureSource.OUTPUT_KEY)))
                        .path("messages")
                        .size())
                .isEqualTo(2);
    }

    @Test
    void shouldRefuseTheOldVersionWhenAProviderEditsTextWithoutChangingTheMessageCount() {
        readyThread();
        allowCapture();
        var pinned = source.pin(item, null);
        when(messages.findThreadMessages(anyLong(), any(), any(), any()))
                .thenReturn(List.of(turn("Edited", "U1", 55L), turn("Second", "U1", 55L)));
        assertThatThrownBy(() -> source.capture(pinned, request())).isInstanceOf(ItemCaptureException.class);
    }

    @Test
    void shouldWithholdAllBytesWhenAnyParticipantLosesConsentAfterPinning() {
        readyThread();
        allowCapture();
        var pinned = source.pin(item, null);
        when(messages.countReadableTurns(7L, "C1", "1700000000.000000")).thenReturn(1L);
        var captured = source.capture(pinned, request());
        assertThat(captured.files()).isEmpty();
        assertThat(captured.completeness()).isEmpty();
        assertThat(captured.citableTexts()).isEmpty();
        assertThat(captured.stateOverrides())
                .containsEntry(
                        ThreadCaptureSource.SOURCE,
                        new SourceCaptureState.Redacted(SourceAbsenceReason.CONSENT_NOT_ACTIVE));
    }

    @Test
    void shouldRegisterEveryTurnAndRecordWithTheExactUnescapedCapturedText() throws Exception {
        readyThread();
        allowCapture();
        String first = "Ada said \"review it\".\nZażółć 🚀";
        when(messages.findThreadMessages(anyLong(), any(), any(), any()))
                .thenReturn(List.of(turn(first, "U1", 55L), turn("Second", "U1", 55L)));
        var captured = source.capture(source.pin(item, null), request());
        var record = captured.citableTexts().stream()
                .filter(text -> text.path().equals("context/conversation_thread.json"))
                .findFirst()
                .orElseThrow();
        assertThat(record.stagedPath()).isEqualTo(ThreadCaptureSource.OUTPUT_KEY);
        for (int index = 0; index < 2; index++) {
            String path = "context/conversation_thread.json#/messages/" + index + "/text";
            var message = captured.citableTexts().stream()
                    .filter(text -> text.path().equals(path))
                    .findFirst()
                    .orElseThrow();
            assertThat(captured.files().get(message.stagedPath()))
                    .isEqualTo((index == 0 ? first : "Second").getBytes(StandardCharsets.UTF_8));
        }
        assertThat(captured.citableTexts()).hasSize(captured.files().size());
        assertThat(captured.citableTexts().stream().map(CitableText::path).toList())
                .doesNotHaveDuplicates();
        for (var candidate : captured.citableTexts()) {
            assertThat(candidate.source()).isEqualTo(ThreadCaptureSource.SOURCE.value());
            assertThat(candidate.side()).isEqualTo(CitationSide.NEW);
            assertThat(candidate.commit()).isNull();
            byte[] staged = Objects.requireNonNull(captured.files().get(candidate.stagedPath()));
            assertThat(candidate.sha256())
                    .isEqualTo(HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(staged)));
        }
    }

    @Test
    void shouldPreserveBothAuthorRolesWhenTheThreadHasTwoHumanAccounts() {
        readyThread();
        when(messages.findThreadMessages(anyLong(), any(), any(), any()))
                .thenReturn(List.of(turn("First", "U1", 55L), turn("Second", "U2", 56L)));
        assertThat(source.pin(item, null).roles()).hasSize(2);
    }

    @Test
    void aNativeBotDoesNotReceiveAnAuthorAccountRole() {
        readyThread();
        when(messages.findThreadMessages(anyLong(), any(), any(), any()))
                .thenReturn(List.of(turn("First", "U1", 55L), turn("Bot reply", "B1", null)));
        assertThat(source.pin(item, null).roles())
                .singleElement()
                .satisfies(role -> assertThat(role.identity().subject()).isEqualTo("U1"));
    }

    @Test
    void shouldRefuseAThreadFromAnotherWorkspaceOrDisconnectedTeam() {
        assertThat(source.current(new ItemRef(8L, 41L, ThreadEvents.THREAD, 19L, null, null)))
                .isEmpty();
        assertThat(source.current(item)).isEmpty();
    }

    @Test
    void shouldCaptureTheOwningTeamWhenAnotherActiveTeamConnectionIsNewer() {
        readyThread();
        allowCapture();
        var owning = connection("T1", 21L);
        var other = connection("T2", 22L);
        when(connections.findActiveConnections(7L, IntegrationKind.SLACK)).thenReturn(List.of(other, owning));
        assertThat(source.permits(item, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isTrue();
        var pinned = source.pin(item, null);
        assertThat(pinned.integration().instanceKey()).isEqualTo("T1");
        assertThat(pinned.integration().connectionId()).isEqualTo(21L);
        assertThat(source.capture(pinned, request()).files()).containsKey(ThreadCaptureSource.OUTPUT_KEY);
        verify(connections, never()).findActive(7L, IntegrationKind.SLACK);
    }

    @Test
    void shouldNotCreateAnIdentityNamespaceWhilePinningAThread() {
        readableThread();
        when(providers.findProviderId("SLACK", "https://slack.com")).thenReturn(Optional.empty());
        assertThat(source.current(item)).isEmpty();
        verify(providers, never()).resolveProviderId("SLACK", "https://slack.com");
        verify(messages, never()).findThreadMessages(anyLong(), any(), any(), any());
    }

    @Test
    void shouldAdvertiseOnlyPrivateDestinationsAndNoScanMembers() {
        var descriptor = new ThreadItemDescriptor();
        assertThat(descriptor.copyCapabilities())
                .containsExactlyInAnyOrder(CopyCapability.CONVERSATION, CopyCapability.PRACTICE_PAGE);
        assertThat(descriptor.memberKinds()).isEmpty();
        assertThat(descriptor.reviewCapabilities().scanItemKinds()).isEmpty();
    }

    private static SlackThreadMessageRow turn(String text, String author, @Nullable Long accountId) {
        return new SlackThreadMessageRow(
                "Second".equals(text) ? "1700000001.000000" : "1700000000.000000",
                "T1",
                author,
                accountId,
                null,
                author,
                text,
                null);
    }

    @ParameterizedTest
    @EnumSource(SourceUsePurpose.class)
    void shouldPermitCurrentThreadAccessWithoutLoadingAnyMessageText(SourceUsePurpose purpose) {
        readableThread();
        assertThat(source.kinds()).containsExactly(ThreadEvents.THREAD);
        assertThat(source.permits(item, purpose)).isTrue();
        verify(messages, never()).findThreadMessages(anyLong(), any(), any(), any());
    }

    @Test
    void shouldRefuseThreadAccessWhenAnyNativeParticipantIsNoLongerReadable() {
        readableThread();
        when(messages.countReadableTurns(7L, "C1", "1700000000.000000")).thenReturn(1L);
        assertThat(source.permits(item, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isFalse();
        when(messages.countReadableTurns(7L, "C1", "1700000000.000000")).thenReturn(2L);
        assertThat(source.permits(item, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isTrue();
        when(connections.findActiveConnections(7L, IntegrationKind.SLACK)).thenReturn(List.of());
        assertThat(source.permits(item, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isFalse();
        assertThat(source.permits(
                        new ItemRef(8L, 41L, ThreadEvents.THREAD, 19L, null, null),
                        SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isFalse();
    }

    @Test
    void shouldCountTheHumanStandingWhenOnlyAnAuthorsAiChoiceHidesATurn() {
        readableThread();
        var human = SourceReadAudience.HUMAN_RECORDED_STANDING;
        // One author chose No AI: every AI read sees one of two live turns, the human standing sees both.
        when(messages.countReadableTurns(7L, "C1", "1700000000.000000")).thenReturn(1L);
        when(messages.countHumanStandingReadableTurns(7L, "C1", "1700000000.000000"))
                .thenReturn(2L);
        for (var purpose : SourceUsePurpose.values()) {
            assertThat(source.permits(item, purpose)).isFalse();
            assertThat(source.permits(item, purpose, SourceReadAudience.PROCESSING))
                    .isFalse();
        }
        assertThat(source.permits(item, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, human))
                .isTrue();
        verify(messages, never()).findThreadMessages(anyLong(), any(), any(), any());

        // A turn that visibility hides (opt-out, tombstone, erasure, paused channel) hides the thread here too.
        when(messages.countHumanStandingReadableTurns(7L, "C1", "1700000000.000000"))
                .thenReturn(1L);
        assertThat(source.permits(item, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, human))
                .isFalse();
        when(messages.countHumanStandingReadableTurns(7L, "C1", "1700000000.000000"))
                .thenReturn(2L);
        when(messages.countLiveTurns(7L, "C1", "1700000000.000000")).thenReturn(0L);
        when(messages.countHumanStandingReadableTurns(7L, "C1", "1700000000.000000"))
                .thenReturn(0L);
        assertThat(source.permits(item, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, human))
                .isFalse();
        when(messages.countLiveTurns(7L, "C1", "1700000000.000000")).thenReturn(2L);
        when(messages.countHumanStandingReadableTurns(7L, "C1", "1700000000.000000"))
                .thenReturn(2L);
        when(connections.findActiveConnections(7L, IntegrationKind.SLACK)).thenReturn(List.of());
        assertThat(source.permits(item, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, human))
                .isFalse();
        when(connections.findActiveConnections(7L, IntegrationKind.SLACK)).thenReturn(List.of(connection("T1", 21L)));
        when(threads.findConsentedCandidateRow(7L, 19L)).thenReturn(Optional.empty());
        assertThat(source.permits(item, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, human))
                .isFalse();
        assertThat(source.permits(
                        new ItemRef(8L, 41L, ThreadEvents.THREAD, 19L, null, null),
                        SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY,
                        human))
                .isFalse();
    }

    @Test
    void shouldRefuseThreadAccessWhenTheOwningTeamDiffersFromTheActiveConnection() {
        when(connections.findActiveConnections(7L, IntegrationKind.SLACK)).thenReturn(List.of(connection("T1", 21L)));
        var candidate = mock(SlackThreadRepository.SettledCandidateRow.class);
        when(candidate.getSlackTeamId()).thenReturn("T2");
        when(threads.findConsentedCandidateRow(7L, 19L)).thenReturn(Optional.of(candidate));
        assertThat(source.permits(item, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isFalse();
        verifyNoInteractions(messages);
    }

    @Test
    void shouldReturnTheGovernedThreadRecordWithoutReadingCurrentProviderState() {
        readyThread();
        allowCapture();
        var pinned = source.pin(item, null);
        var captured = source.capture(pinned, request());
        clearInvocations(threads, messages, connections, providers);
        var record = source.record(pinned, captured.files());
        assertThat(record.path("messages").path(0).path("text").asString()).isEqualTo("First");
        assertThat(record)
                .isEqualTo(
                        mapper.readTree(Objects.requireNonNull(captured.files().get(ThreadCaptureSource.OUTPUT_KEY))));
        verifyNoInteractions(threads, messages, connections, providers);
        assertThatThrownBy(() -> source.record(pinned, Map.of()))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.SOURCE_NOT_READY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[]", "null", "not json", "{} {}"})
    void shouldRefuseMissingOrMalformedCapturedThreadRecords(String bytes) {
        readyThread();
        var pinned = source.pin(item, null);
        assertThatThrownBy(() -> source.record(
                        pinned, Map.of(ThreadCaptureSource.OUTPUT_KEY, bytes.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.SOURCE_NOT_READY));
    }

    private static CaptureRequest request() {
        return new CaptureRequest(
                UUID.randomUUID(), UUID.randomUUID(), 1, Set.of(ThreadCaptureSource.SOURCE), "3.0.0", 3);
    }
}
