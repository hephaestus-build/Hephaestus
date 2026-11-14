package de.tum.cit.aet.hephaestus.integration.slack.capture;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadEvents;
import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadProjection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.thread.SlackThreadProjector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class ThreadCaptureSource {
    public static final SourceKind SOURCE = ThreadItemDescriptor.RECORD_SOURCE;
    public static final String OUTPUT_KEY = "inputs/context/conversation_thread.json";
    private final ObjectMapper mapper;
    private final SlackThreadProjector projection;
    private final SlackThreadRepository threads;
    private final ConnectionService connections;

    public ThreadCaptureSource(
            ObjectMapper mapper,
            SlackThreadProjector projection,
            SlackThreadRepository threads,
            ConnectionService connections) {
        this.mapper = mapper;
        this.projection = projection;
        this.threads = threads;
        this.connections = connections;
    }

    @Transactional(readOnly = true, noRollbackFor = ItemCaptureException.class)
    public CaptureContribution capture(ItemSnapshot pinned, CaptureRequest request) {
        if (!request.sources().contains(SOURCE)) {
            return contribution(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
        if (!ThreadEvents.THREAD.equals(pinned.item().kind())
                || pinned.integration().kind() != IntegrationKind.SLACK
                || pinned.integration().workspaceId() != pinned.item().workspaceId())
            throw new ItemCaptureException(
                    ItemCaptureCode.ITEM_NOT_VISIBLE, "Thread source does not match the pinned item");
        boolean connected =
                connections.findActiveConnections(pinned.item().workspaceId(), IntegrationKind.SLACK).stream()
                        .anyMatch(connection -> Objects.equals(
                                connection.getInstanceKey(),
                                pinned.integration().instanceKey()));
        if (!connected) {
            return contribution(
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(SOURCE, new SourceCaptureState.Unavailable(SourceAbsenceReason.NO_PROVIDER)));
        }
        Long sourceId = pinned.item().sourceId();
        if (sourceId == null) throw new IllegalArgumentException("Thread source id is required");
        var thread = threads.findByWorkspaceIdAndId(pinned.item().workspaceId(), sourceId);
        if (thread.isEmpty()) {
            return contribution(
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(SOURCE, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND)));
        }
        var row = thread.get();
        var readability =
                projection.threadReadability(row.getWorkspaceId(), row.getSlackChannelId(), row.getSlackThreadTs());
        switch (readability) {
            case CONSENT_NOT_ACTIVE -> {
                return contribution(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(SOURCE, new SourceCaptureState.Redacted(SourceAbsenceReason.CONSENT_NOT_ACTIVE)));
            }
            case NOT_FOUND -> {
                return contribution(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(SOURCE, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND)));
            }
            case READABLE -> {}
        }
        var payload = projection.threadPayload(row.getWorkspaceId(), row.getSlackChannelId(), row.getSlackThreadTs());
        String lastTs = row.getLastTs();
        byte[] record = mapper.writeValueAsBytes(payload);
        if (lastTs == null || !ThreadEvents.version(record, lastTs).equals(pinned.version())) {
            throw new ItemCaptureException(ItemCaptureCode.VERSION_UNAVAILABLE, "Thread version changed");
        }
        if (projection.threadReadability(row.getWorkspaceId(), row.getSlackChannelId(), row.getSlackThreadTs())
                != ThreadProjection.ThreadReadability.READABLE)
            return contribution(
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(SOURCE, new SourceCaptureState.Redacted(SourceAbsenceReason.CONSENT_NOT_ACTIVE)));
        Instant effectiveAt = projection.sourceEffectiveAt(lastTs);
        var files = new LinkedHashMap<String, byte[]>();
        var texts = new ArrayList<CitableText>();
        register(files, texts, OUTPUT_KEY, "context/conversation_thread.json", record);
        registerRecordStrings(payload, "", files, texts);
        return new CaptureContribution(
                files,
                Map.of(
                        SOURCE,
                        payload.path("truncated").asBoolean()
                                ? SourceCompleteness.PARTIAL
                                : SourceCompleteness.COMPLETE),
                Map.of(SOURCE, pinned.version().value()),
                Map.of(),
                effectiveAt == null ? Map.of() : Map.of(SOURCE, effectiveAt),
                Map.of(
                        SOURCE,
                        payload.path("messageCount").asInt() == 0
                                ? SourceContentState.EMPTY
                                : SourceContentState.NON_EMPTY),
                Map.of(),
                Map.of(),
                null,
                Map.of(),
                List.of(),
                List.of(),
                texts);
    }

    private static CaptureContribution contribution(
            Map<String, byte[]> files,
            Map<SourceKind, SourceCompleteness> completeness,
            Map<SourceKind, String> identities,
            Map<SourceKind, Instant> effectiveAt,
            Map<SourceKind, SourceContentState> contentStates,
            Map<SourceKind, SourceCaptureState> states) {
        return new CaptureContribution(
                files,
                completeness,
                identities,
                Map.of(),
                effectiveAt,
                contentStates,
                states,
                Map.of(),
                null,
                Map.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static void registerRecordStrings(
            JsonNode node, String pointer, Map<String, byte[]> files, List<CitableText> texts) {
        if (node.isString()) {
            register(
                    files,
                    texts,
                    "inputs/context/conversation_thread_texts/" + texts.size() + ".txt",
                    "context/conversation_thread.json#" + pointer,
                    node.asString().getBytes(StandardCharsets.UTF_8));
        } else if (node.isObject()) {
            node.properties()
                    .forEach(field -> registerRecordStrings(
                            field.getValue(),
                            pointer + "/" + field.getKey().replace("~", "~0").replace("/", "~1"),
                            files,
                            texts));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++)
                registerRecordStrings(Objects.requireNonNull(node.get(index)), pointer + "/" + index, files, texts);
        }
    }

    private static void register(
            Map<String, byte[]> files, List<CitableText> texts, String stagedPath, String logicalPath, byte[] bytes) {
        files.put(stagedPath, bytes);
        try {
            String digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            texts.add(new CitableText(SOURCE.value(), logicalPath, CitationSide.NEW, null, stagedPath, digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
