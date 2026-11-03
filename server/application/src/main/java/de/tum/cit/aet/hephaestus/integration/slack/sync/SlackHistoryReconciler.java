package de.tum.cit.aet.hephaestus.integration.slack.sync;

import com.slack.api.model.Message;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackLiveMessage;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackIngestService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService.HistoryPage;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Removes stored messages that Slack no longer holds, as a {@code message_deleted} event would. The event is lost when
 * Slack gives up on delivery or when it ages out of the stream during an outage, and the text would otherwise stay until
 * the retention sweep.
 *
 * <p>A removal is irreversible, so it needs proof:
 * <ul>
 *   <li>The stored rows are read before Slack is. A row that exists at that moment was posted before the read, so a
 *       message that arrives during the read is never taken for a deleted one. The newest {@link #SETTLING} stay out, so
 *       a clock difference or a lagging Slack read cannot make a new message look deleted.</li>
 *   <li>A range is compared only after every page of it was read. Any error, an exhausted budget, a consent change or a
 *       cancellation removes nothing from it.</li>
 *   <li>History holds thread roots only, so a reply is compared only with a complete read of its own thread.</li>
 *   <li>Only the last {@link #VISIBLE_HISTORY} are compared, measured when the rows are read: Slack's free plan hides
 *       history older than 90 days from the API, so its absence there proves nothing. The day short of 90 absorbs a read
 *       that ends later than it starts and a cut that Slack does not specify to the hour.</li>
 * </ul>
 *
 * <p>The pass over older messages never ingests. It reads ranges below the watermark, which can hold paused gaps and
 * messages of people who later opted out, so only a row that is already stored can change.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackHistoryReconciler {

    private static final Logger log = LoggerFactory.getLogger(SlackHistoryReconciler.class);

    static final Duration SETTLING = Duration.ofMinutes(10);
    static final Duration VISIBLE_HISTORY = Duration.ofDays(89);
    private static final long SECONDS_PER_DAY = Duration.ofDays(1).toSeconds();
    private static final String DELETED_ROOT_WITH_REPLIES = "tombstone";

    private final SlackMessageRepository messageRepository;
    private final SlackMonitoredChannelRepository monitoredChannelRepository;
    private final SlackMessageService slackMessageService;
    private final SlackIngestService ingestService;
    private final SlackSyncProperties properties;
    private final Clock clock;

    public SlackHistoryReconciler(
            SlackMessageRepository messageRepository,
            SlackMonitoredChannelRepository monitoredChannelRepository,
            SlackMessageService slackMessageService,
            SlackIngestService ingestService,
            SlackSyncProperties properties,
            Clock clock) {
        this.messageRepository = messageRepository;
        this.monitoredChannelRepository = monitoredChannelRepository;
        this.slackMessageService = slackMessageService;
        this.ingestService = ingestService;
        this.properties = properties;
        this.clock = clock;
    }

    /** The live rows whose thread root lies in the range. Read this before Slack is read for the same range. */
    List<SlackLiveMessage> stored(long workspaceId, String channelId, String oldest, String latest) {
        Instant now = clock.instant();
        String visible = SlackTs.ofInstant(now.minus(VISIBLE_HISTORY));
        String settled = SlackTs.ofInstant(now.minus(SETTLING));
        return messageRepository.findLiveInThreadRange(
                workspaceId,
                channelId,
                SlackTs.compare(oldest, visible) >= 0 ? oldest : visible,
                SlackTs.compare(latest, settled) <= 0 ? latest : settled);
    }

    /**
     * Compare stored rows with a complete read of their range.
     *
     * @param returned everything Slack returned for the range, by {@code ts}, before any filter
     * @return whether every stored reply was compared too
     */
    boolean removeAbsent(
            long workspaceId,
            SlackMonitoredChannel channel,
            List<SlackLiveMessage> stored,
            Map<String, Message> returned,
            SlackSyncBudget repliesBudget,
            BooleanSupplier cancelled) {
        Map<String, List<SlackLiveMessage>> threads =
                stored.stream().collect(Collectors.groupingBy(SlackLiveMessage::rootTs));
        boolean complete = true;
        for (Map.Entry<String, List<SlackLiveMessage>> thread : threads.entrySet()) {
            Message root = returned.get(thread.getKey());
            if (root == null || DELETED_ROOT_WITH_REPLIES.equals(root.getSubtype())) {
                thread.getValue().stream().filter(row -> !row.isReply()).forEach(row -> remove(channel, row));
            }
            List<SlackLiveMessage> replies =
                    thread.getValue().stream().filter(SlackLiveMessage::isReply).toList();
            int repliesInSlack = root == null || root.getReplyCount() == null ? 0 : root.getReplyCount();
            if (properties.repliesEnabled() && !replies.isEmpty() && replies.size() != repliesInSlack) {
                complete &=
                        removeAbsentReplies(workspaceId, channel, thread.getKey(), replies, repliesBudget, cancelled);
            }
        }
        return complete;
    }

    /**
     * Compare the stored messages below each channel's watermark with Slack, one UTC day of thread roots at a time,
     * newest first. Each channel keeps its place across nights, and the channel that finished a full pass longest ago
     * goes first. Its history and replies reads share one budget, so the window sync cannot starve it of replies.
     *
     * @return how many channels failed
     */
    int reconcileOlder(
            long workspaceId, List<SlackMonitoredChannel> channels, String retentionFloor, BooleanSupplier cancelled) {
        if (properties.reconcileRequestBudget() == 0) {
            return 0;
        }
        SlackSyncBudget budget =
                new SlackSyncBudget(properties.reconcileRequestBudget(), properties.historyRequestInterval());
        List<SlackMonitoredChannel> stalestFirst = channels.stream()
                .sorted(Comparator.comparing(
                        SlackMonitoredChannel::getHistoryCheckedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        int failed = 0;
        for (SlackMonitoredChannel channel : stalestFirst) {
            if (cancelled.getAsBoolean() || !budget.available()) {
                break;
            }
            try {
                reconcileChannel(workspaceId, channel, retentionFloor, budget, cancelled);
            } catch (RuntimeException e) {
                failed++;
                // The cursor stays for a retry; the channel goes to the back so a day that keeps failing holds no one.
                monitoredChannelRepository.deferHistoryCheck(workspaceId, channel.getSlackChannelId(), clock.instant());
                String reason = SlackChannelHistorySyncService.reasonOf(e);
                monitoredChannelRepository.recordHistorySyncError(
                        workspaceId, channel.getSlackChannelId(), "Deletion check failed (" + reason + ")");
                log.warn(
                        "slack.sync: deletion check failed for workspaceId={} channelId={}: {}",
                        workspaceId,
                        channel.getSlackChannelId(),
                        reason);
            }
        }
        return failed;
    }

    /** Continue one channel's pass from where it stopped, until it ends or a read cannot finish. */
    private void reconcileChannel(
            long workspaceId,
            SlackMonitoredChannel channel,
            String retentionFloor,
            SlackSyncBudget budget,
            BooleanSupplier cancelled) {
        String channelId = channel.getSlackChannelId();
        Instant announcedAt = channel.getConsentAnnouncedAt();
        String watermark = channel.getLastHistorySyncedTs();
        if (announcedAt == null || watermark == null) {
            return;
        }
        String announced = SlackTs.ofInstant(announcedAt);
        String oldest = SlackTs.compare(announced, retentionFloor) >= 0 ? announced : retentionFloor;
        String cursor = channel.getHistoryCheckCursorTs();
        NavigableMap<Long, List<SlackLiveMessage>> days = new TreeMap<>(Collections.reverseOrder());
        if (SlackTs.compare(oldest, watermark) < 0) {
            for (SlackLiveMessage row : stored(workspaceId, channelId, oldest, watermark)) {
                Long micros = SlackTs.toEpochMicros(row.rootTs());
                if (micros != null && (cursor == null || SlackTs.compare(row.rootTs(), cursor) < 0)) {
                    days.computeIfAbsent(micros / 1_000_000L / SECONDS_PER_DAY, day -> new ArrayList<>())
                            .add(row);
                }
            }
        }
        if (days.isEmpty()) {
            if (cursor != null || channel.getHistoryCheckedAt() == null) {
                monitoredChannelRepository.completeHistoryCheck(workspaceId, channelId, clock.instant());
            }
            return;
        }
        for (Map.Entry<Long, List<SlackLiveMessage>> day : days.entrySet()) {
            boolean wholeBudgetLeft = budget.used() == 0;
            Map<String, Message> returned = readRange(workspaceId, channelId, day.getValue(), budget, cancelled);
            String dayStart = SlackTs.ofInstant(Instant.ofEpochSecond(day.getKey() * SECONDS_PER_DAY));
            if (returned == null || !removeAbsent(workspaceId, channel, day.getValue(), returned, budget, cancelled)) {
                if (wholeBudgetLeft && !budget.available() && !cancelled.getAsBoolean()) {
                    // A day that one night's whole budget cannot read would hold the channel forever: pass it.
                    log.warn(
                            "slack.sync: one day of workspaceId={} channelId={} needs more than the deletion check budget",
                            workspaceId,
                            channelId);
                    monitoredChannelRepository.recordHistoryCheckCursor(workspaceId, channelId, dayStart);
                }
                return;
            }
            monitoredChannelRepository.recordHistoryCheckCursor(workspaceId, channelId, dayStart);
        }
        monitoredChannelRepository.completeHistoryCheck(workspaceId, channelId, clock.instant());
    }

    /** Every message Slack returns for the range of these rows by {@code ts}, or {@code null} when the read stopped. */
    private @Nullable Map<String, Message> readRange(
            long workspaceId,
            String channelId,
            List<SlackLiveMessage> rows,
            SlackSyncBudget budget,
            BooleanSupplier cancelled) {
        Comparator<String> byInstant = SlackTs::compare;
        List<String> roots = rows.stream().map(SlackLiveMessage::rootTs).toList();
        String oldest = Collections.min(roots, byInstant);
        String latest = Collections.max(roots, byInstant);
        Map<String, Message> returned = new HashMap<>();
        String cursor = null;
        do {
            if (!active(workspaceId, channelId) || !budget.acquire(cancelled)) {
                return null;
            }
            HistoryPage page = slackMessageService.fetchHistoryPage(
                    workspaceId, channelId, oldest, latest, cursor, properties.historyPageLimit());
            for (Message message : page.messages()) {
                returned.put(message.getTs(), message);
                refreshEdit(workspaceId, channelId, message);
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        return returned;
    }

    /** @return whether the whole thread was read and compared */
    private boolean removeAbsentReplies(
            long workspaceId,
            SlackMonitoredChannel channel,
            String rootTs,
            List<SlackLiveMessage> replies,
            SlackSyncBudget repliesBudget,
            BooleanSupplier cancelled) {
        String channelId = channel.getSlackChannelId();
        Set<String> present = new HashSet<>();
        String cursor = null;
        do {
            if (!active(workspaceId, channelId) || !repliesBudget.acquire(cancelled)) {
                return false;
            }
            HistoryPage page;
            try {
                page = slackMessageService.fetchRepliesPage(
                        workspaceId, channelId, rootTs, rootTs, cursor, properties.historyPageLimit());
            } catch (SlackSendException e) {
                if (!"thread_not_found".equals(e.slackError())) {
                    throw e;
                }
                page = new HistoryPage(List.of(), null);
            }
            for (Message reply : page.messages()) {
                present.add(reply.getTs());
                refreshEdit(workspaceId, channelId, reply);
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        replies.stream().filter(reply -> !present.contains(reply.slackTs())).forEach(reply -> remove(channel, reply));
        return true;
    }

    private boolean active(long workspaceId, String channelId) {
        return monitoredChannelRepository
                        .findConsentState(workspaceId, channelId)
                        .orElse(null)
                == ConsentState.ACTIVE;
    }

    private void refreshEdit(long workspaceId, String channelId, Message message) {
        if (message.getEdited() != null && !DELETED_ROOT_WITH_REPLIES.equals(message.getSubtype())) {
            ingestService.refreshEdit(
                    workspaceId,
                    channelId,
                    message.getTs(),
                    Objects.requireNonNullElse(message.getText(), ""),
                    message.getEdited().getTs());
        }
    }

    private void remove(SlackMonitoredChannel channel, SlackLiveMessage message) {
        ingestService.tombstoneMessage(channel.getSlackTeamId(), channel.getSlackChannelId(), message.slackTs());
    }
}
