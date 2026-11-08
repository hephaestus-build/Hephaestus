package de.tum.cit.aet.hephaestus.integration.slack.item;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.chat.item.ConversationThreadProjection;
import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadEvents;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSourceAccess;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.RoleHolder;
import de.tum.cit.aet.hephaestus.integration.core.spi.SourceReadAudience;
import de.tum.cit.aet.hephaestus.integration.slack.capture.ThreadCaptureSource;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationProjector;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnCaptureRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class ThreadItemSource implements ItemSource, ItemSourceAccess {
    private final SlackThreadRepository threads;
    private final SlackMessageRepository messages;
    private final SlackConversationProjector projection;
    private final ThreadCaptureSource capture;
    private final GitProviderRegistry providers;
    private final ObjectMapper mapper;
    private final ConnectionService connections;

    public ThreadItemSource(
            SlackThreadRepository threads,
            SlackMessageRepository messages,
            SlackConversationProjector projection,
            ThreadCaptureSource capture,
            GitProviderRegistry providers,
            ObjectMapper mapper,
            ConnectionService connections) {
        this.threads = threads;
        this.messages = messages;
        this.projection = projection;
        this.capture = capture;
        this.providers = providers;
        this.mapper = mapper;
        this.connections = connections;
    }

    @Override
    public Set<ItemKind> kinds() {
        return Set.of(ThreadEvents.THREAD);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean permits(ItemRef item, SourceUsePurpose purpose) {
        return permitted(item, SourceReadAudience.PROCESSING);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean permits(ItemRef item, SourceUsePurpose purpose, SourceReadAudience audience) {
        return permitted(item, Objects.requireNonNull(audience));
    }

    /**
     * A consented thread on an active connection, every live turn of it readable. Processing reads its readability
     * through the AI read guard; a human recorded standing counts the same turns without the authors' AI choice and
     * reads no message body.
     */
    private boolean permitted(ItemRef item, SourceReadAudience audience) {
        if (!supports(item)) return false;
        Long sourceId = item.sourceId();
        if (sourceId == null) return false;
        return threads.findConsentedCandidateRow(item.workspaceId(), sourceId)
                .filter(thread -> connectionFor(item.workspaceId(), thread).isPresent())
                .filter(thread -> audience == SourceReadAudience.PROCESSING
                        ? projection.threadReadability(
                                        item.workspaceId(), thread.getSlackChannelId(), thread.getSlackThreadTs())
                                == ConversationThreadProjection.ThreadReadability.READABLE
                        : humanReadable(item.workspaceId(), thread.getSlackChannelId(), thread.getSlackThreadTs()))
                .isPresent();
    }

    private boolean humanReadable(long workspaceId, String channelId, String threadTs) {
        long live = messages.countLiveTurns(workspaceId, channelId, threadTs);
        return live > 0 && messages.countHumanStandingReadableTurns(workspaceId, channelId, threadTs) == live;
    }

    @Override
    public boolean supports(ItemRef item) {
        return ThreadEvents.THREAD.equals(item.kind()) && item.sourceId() != null;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ItemLabel> labels(long workspaceId, Collection<ItemRef> items) {
        Map<Long, ItemLabel> labels = new HashMap<>();
        for (ItemRef item : items) {
            if (item.workspaceId() != workspaceId || !supports(item)) continue;
            Long sourceId = item.sourceId();
            if (sourceId == null) continue;
            threads.findByWorkspaceIdAndId(workspaceId, sourceId)
                    .ifPresent(thread -> labels.put(
                            item.itemId(),
                            new ItemLabel(
                                    item.itemId(),
                                    "Conversation thread",
                                    "Conversation thread",
                                    "thread",
                                    null,
                                    threads.findConsentedCandidateRow(workspaceId, sourceId)
                                            .map(SlackThreadRepository.SettledCandidateRow::getSlackChannelName)
                                            .orElse(null),
                                    null,
                                    IntegrationKind.SLACK)));
        }
        return Map.copyOf(labels);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ItemSnapshot> current(ItemRef item) {
        return currentSnapshot(item);
    }

    private Optional<ItemSnapshot> currentSnapshot(ItemRef item) {
        if (!supports(item)) return Optional.empty();
        Long sourceId = item.sourceId();
        if (sourceId == null) return Optional.empty();
        return threads.findConsentedCandidateRow(item.workspaceId(), sourceId)
                .flatMap(thread -> connectionFor(item.workspaceId(), thread)
                        .filter(active -> projection.threadReadability(
                                        item.workspaceId(), thread.getSlackChannelId(), thread.getSlackThreadTs())
                                == ConversationThreadProjection.ThreadReadability.READABLE)
                        .flatMap(active -> providers
                                .findProviderId("SLACK", "https://slack.com")
                                .map(providerId -> {
                                    var payload = projection.threadPayload(
                                            item.workspaceId(), thread.getSlackChannelId(), thread.getSlackThreadTs());
                                    var roles = messages
                                            .findThreadMessages(
                                                    item.workspaceId(),
                                                    thread.getSlackChannelId(),
                                                    thread.getSlackThreadTs(),
                                                    Pageable.unpaged())
                                            .stream()
                                            .filter(row ->
                                                    row.authorAccountId() != null && row.authorSlackUserId() != null)
                                            .map(row -> new RoleHolder(
                                                    AboutRole.AUTHOR,
                                                    new PersonIdentity(
                                                            providerId,
                                                            Objects.requireNonNull(row.authorSlackUserId()),
                                                            row.slackTeamId()),
                                                    true,
                                                    false))
                                            .distinct()
                                            .toList();
                                    return new ItemSnapshot(
                                            item,
                                            ThreadEvents.version(mapper.writeValueAsBytes(payload), thread.getLastTs()),
                                            new IntegrationRef(
                                                    IntegrationKind.SLACK,
                                                    item.workspaceId(),
                                                    active.getInstanceKey(),
                                                    active.getId()),
                                            Map.of(),
                                            roles,
                                            new ItemLabel(
                                                    item.itemId(),
                                                    "Conversation thread",
                                                    "Conversation thread",
                                                    "thread",
                                                    null,
                                                    thread.getSlackChannelName(),
                                                    null,
                                                    IntegrationKind.SLACK));
                                })));
    }

    private Optional<Connection> connectionFor(long workspaceId, SlackThreadRepository.SettledCandidateRow thread) {
        return connections.findActiveConnections(workspaceId, IntegrationKind.SLACK).stream()
                .filter(connection -> Objects.equals(thread.getSlackTeamId(), connection.getInstanceKey()))
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public ItemSnapshot pin(ItemRef item, @Nullable ItemVersion requested) {
        ItemSnapshot snapshot = currentSnapshot(item)
                .orElseThrow(
                        () -> new ItemCaptureException(ItemCaptureCode.ITEM_NOT_VISIBLE, "Thread is not available"));
        if (requested != null && !requested.equals(snapshot.version()))
            throw new ItemCaptureException(ItemCaptureCode.VERSION_UNAVAILABLE, "Thread version changed");
        return snapshot;
    }

    @Override
    public JsonNode record(ItemSnapshot pinned, Map<String, byte[]> capturedFiles) {
        if (!supports(pinned.item())
                || pinned.integration().kind() != IntegrationKind.SLACK
                || pinned.item().workspaceId() != pinned.integration().workspaceId())
            throw new ItemCaptureException(
                    ItemCaptureCode.ITEM_NOT_VISIBLE, "Captured thread source does not match the pinned item");
        byte[] bytes = capturedFiles.get(ThreadCaptureSource.OUTPUT_KEY);
        if (bytes == null)
            throw new ItemCaptureException(ItemCaptureCode.SOURCE_NOT_READY, "Captured thread record is unavailable");
        try {
            JsonNode record = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(bytes);
            if (!record.isObject())
                throw new ItemCaptureException(
                        ItemCaptureCode.SOURCE_NOT_READY, "Captured thread record must be an object");
            return record;
        } catch (JacksonException failure) {
            throw new ItemCaptureException(
                    ItemCaptureCode.SOURCE_NOT_READY, "Captured thread record is invalid", failure);
        }
    }

    @Override
    public CaptureContribution capture(ItemSnapshot pinned, CaptureRequest request) {
        return capture.capture(pinned, request);
    }
}
