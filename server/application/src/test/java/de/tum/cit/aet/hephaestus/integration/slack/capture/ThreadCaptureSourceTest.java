package de.tum.cit.aet.hephaestus.integration.slack.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndexBuilder;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.chat.item.ConversationThreadProjection;
import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadEvents;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCaptureFixtures;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationProjector;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThread;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ThreadCaptureSourceTest extends BaseUnitTest {

    private static final String THREAD_TS = "1700000000.000000";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SlackThreadRepository threads = mock(SlackThreadRepository.class);
    private final SlackConversationProjector projection = mock(SlackConversationProjector.class);
    private final ConnectionService connections = mock(ConnectionService.class);
    private final SlackThread thread = new SlackThread();

    ThreadCaptureSourceTest() {
        var workspace = new Workspace();
        workspace.setId(7L);
        var connection = new Connection(
                workspace, IntegrationKind.SLACK, "T1", new ConnectionConfig.SlackConfig("T1", null, null, Set.of()));
        connection.setState(IntegrationState.ACTIVE);
        when(connections.findActiveConnections(7L, IntegrationKind.SLACK)).thenReturn(List.of(connection));
        thread.setWorkspaceId(7L);
        thread.setSlackChannelId("C1");
        thread.setSlackThreadTs(THREAD_TS);
        when(threads.findByWorkspaceIdAndId(7L, 19L)).thenReturn(Optional.of(thread));
    }

    @Test
    void shouldWithholdSourceBytesWhenChannelConsentIsPausedAfterPinning() {
        when(projection.threadReadability(7L, "C1", THREAD_TS))
                .thenReturn(ConversationThreadProjection.ThreadReadability.CONSENT_NOT_ACTIVE);
        var request = new CaptureRequest(
                UUID.randomUUID(), UUID.randomUUID(), 1, Set.of(ThreadCaptureSource.SOURCE), "3.0.0", 3);
        var captured = captureSource().capture(snapshot(new ItemVersion("frozen", null)), request);
        assertThat(captured.files()).isEmpty();
        assertThat(captured.citableTexts()).isEmpty();
        assertThat(captured.completeness()).isEmpty();
        assertThat(captured.stateOverrides())
                .containsEntry(
                        ThreadCaptureSource.SOURCE,
                        new SourceCaptureState.Redacted(SourceAbsenceReason.CONSENT_NOT_ACTIVE));
    }

    @Test
    void shouldGiveEachBundledChatPracticeTheWholeThreadItReads() {
        String question =
                "How do I run the migrations locally? I tried ./gradlew update and it stops at a missing driver.";
        var payload = mapper.createObjectNode().put("channel", "C1").put("threadTs", THREAD_TS);
        payload.putArray("messages")
                .addObject()
                .put("ts", THREAD_TS)
                .put("author", "U1")
                .put("text", question);
        payload.put("messageCount", 1).put("truncated", false);
        thread.setLastTs(THREAD_TS);
        when(projection.threadReadability(7L, "C1", THREAD_TS))
                .thenReturn(ConversationThreadProjection.ThreadReadability.READABLE);
        when(projection.threadPayload(7L, "C1", THREAD_TS)).thenReturn(payload);
        var pinned = snapshot(ThreadEvents.version(mapper.writeValueAsBytes(payload), THREAD_TS));

        var chatPractices = ScmCaptureFixtures.bundledPractices().entrySet().stream()
                .filter(entry -> entry.getValue().kind().equals(ThreadEvents.THREAD))
                .toList();

        assertThat(chatPractices)
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "asks-answerable-questions",
                        "gives-actionable-answers",
                        "posts-clear-status-and-blocker-updates");
        var exporter = new JobFolderIndexBuilder(
                new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC()), mapper, Clock.systemUTC());
        for (var entry : chatPractices) {
            var reads = entry.getValue().definition().reads().sources();
            assertThat(reads).as(entry.getKey()).anySatisfy(read -> {
                assertThat(read.source()).isEqualTo(ThreadCaptureSource.SOURCE);
                assertThat(read.need()).isEqualTo(ReadNeed.COMPLETE);
            });
            Set<SourceKind> selected = reads.stream().map(read -> read.source()).collect(Collectors.toSet());
            var captured = captureSource()
                    .capture(pinned, new CaptureRequest(UUID.randomUUID(), UUID.randomUUID(), 1, selected, "3.0.0", 3));

            var record = mapper.readTree(Objects.requireNonNull(captured.files().get(ThreadCaptureSource.OUTPUT_KEY)));
            assertThat(record.path("messages"))
                    .as(entry.getKey())
                    .singleElement()
                    .satisfies(message ->
                            assertThat(message.path("text").asString()).isEqualTo(question));
            assertThat(captured.completeness())
                    .as(entry.getKey())
                    .containsEntry(ThreadCaptureSource.SOURCE, SourceCompleteness.COMPLETE);
            assertThat(captured.contentStates())
                    .as(entry.getKey())
                    .containsEntry(ThreadCaptureSource.SOURCE, SourceContentState.NON_EMPTY);

            // The thread is the practice's only read, so this capture is the one preparation exports under it.
            assertThat(selected).as(entry.getKey()).containsExactly(ThreadCaptureSource.SOURCE);
            try (var prepared = exporter.build(pinned.item().kind(), Map.of(ThreadCaptureSource.SOURCE, captured))) {
                JsonNode exported =
                        mapper.readTree(Objects.requireNonNull(prepared.files().get(ThreadCaptureSource.OUTPUT_KEY)));
                assertThat(exported.path("messages"))
                        .as(entry.getKey())
                        .singleElement()
                        .satisfies(message -> {
                            assertThat(message.path("author").asString()).isEqualTo("U1");
                            assertThat(message.path("text").asString()).isEqualTo(question);
                        });
                assertExported(
                        mapper,
                        prepared,
                        pinned.item().kind(),
                        ThreadCaptureSource.SOURCE,
                        ThreadCaptureSource.OUTPUT_KEY,
                        "context/conversation_thread.json#/messages/0/text",
                        question);
            }
        }
    }

    /** Reads the exported manifest back and binds one source's file and quoted words to their staged bytes. */
    private static void assertExported(
            JsonMapper mapper,
            PreparedEvidence prepared,
            ItemKind item,
            SourceKind source,
            String file,
            String citation,
            String words) {
        JsonNode manifest =
                mapper.readTree(Objects.requireNonNull(prepared.files().get(SandboxLayout.MANIFEST_PATH)));
        assertThat(manifest.path("itemKind").asString()).isEqualTo(item.value());
        assertThat(manifest.path("sources")).singleElement().satisfies(captured -> {
            assertThat(captured.path("kind").asString()).isEqualTo(source.value());
            assertThat(captured.path("state").path("availability").asString()).isEqualTo("AVAILABLE");
            assertThat(captured.path("state").path("completeness").asString()).isEqualTo("COMPLETE");
            assertThat(captured.path("state").path("content").asString()).isEqualTo("NON_EMPTY");
        });
        byte[] exported = Objects.requireNonNull(prepared.files().get(file));
        assertThat(manifest.path("files")).anySatisfy(entry -> {
            assertThat(entry.path("kind").asString()).isEqualTo(source.value());
            assertThat(entry.path("file").path("path").asString()).isEqualTo(file);
            assertThat(entry.path("file").path("sha256").asString()).isEqualTo(ProvenanceDigest.sha256Hex(exported));
        });
        assertThat(manifest.path("citableTexts")).anySatisfy(text -> {
            assertThat(text.path("source").asString()).isEqualTo(source.value());
            assertThat(text.path("path").asString()).isEqualTo(citation);
            String staged = text.path("stagedPath").asString();
            assertThat(prepared.files()).containsKey(staged);
            byte[] quoted = Objects.requireNonNull(prepared.files().get(staged));
            assertThat(new String(quoted, StandardCharsets.UTF_8)).isEqualTo(words);
            assertThat(text.path("sha256").asString()).isEqualTo(ProvenanceDigest.sha256Hex(quoted));
        });
    }

    private ThreadCaptureSource captureSource() {
        return new ThreadCaptureSource(mapper, projection, threads, connections);
    }

    private static ItemSnapshot snapshot(ItemVersion version) {
        var item = new ItemRef(7L, 41L, ThreadEvents.THREAD, 19L, null, null);
        return ItemFixtures.snapshot(item, Map.of())
                .version(version)
                .integration(new IntegrationRef(IntegrationKind.SLACK, 7L, "T1"))
                .label(ItemFixtures.label()
                        .itemId(41L)
                        .title("Thread")
                        .noun("thread")
                        .integration(IntegrationKind.SLACK)
                        .build())
                .build();
    }
}
