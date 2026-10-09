package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.SlackApiTextResponse;
import com.slack.api.methods.request.chat.ChatPostEphemeralRequest;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.auth.AuthTestResponse;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.conversations.ConversationsInfoResponse;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.model.Conversation;
import com.slack.api.model.ConversationType;
import com.slack.api.model.Message;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.view.View;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.slack.credentials.SlackCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Per-workspace Slack messaging. Bot token resolved at send time via
 * {@link SlackCredentialProvider}; failures throw {@link SlackSendException} carrying
 * the Slack error code so callers can choose the HTTP mapping.
 */
@Service
@OutboundEgressGateway
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true", matchIfMissing = false)
public class SlackMessageService {

    private static final Logger log = LoggerFactory.getLogger(SlackMessageService.class);

    private static final int CONVERSATIONS_LIST_PAGE_SIZE = 200;
    private static final int CONVERSATIONS_LIST_MAX_PAGES = 20;

    /**
     * Total {@code Retry-After} budget for a single non-streaming send; a throttle beyond this gives up rather than
     * blocking a request/worker thread unboundedly.
     */
    private static final long RATE_LIMIT_TOTAL_BUDGET_MS = 30_000L;
    /** Never honor a single {@code Retry-After} longer than this — defends against an absurd/hostile header. */
    private static final long RATE_LIMIT_MAX_WAIT_MS = 20_000L;

    /**
     * Wider bounds for history reads that no one waits on: the nightly history reconciliation and the Heph reply
     * reconciler's {@link #findWorkspaceMessages}. Slack's clamp on {@code conversations.history}/{@code conversations.replies}
     * for distributed non-Marketplace apps (1 request per minute) answers with {@code Retry-After: 60}, which the
     * interactive {@link #RATE_LIMIT_MAX_WAIT_MS} cap would retry too early and waste.
     */
    private static final long SYNC_RATE_LIMIT_MAX_WAIT_MS = 90_000L;

    private static final long SYNC_RATE_LIMIT_TOTAL_BUDGET_MS = 150_000L;

    /**
     * The smallest {@code conversations.history} or {@code conversations.replies} page any install gets: Slack's clamp
     * for distributed non-Marketplace apps. A self-hosted instance's own app gets more. The reconciler reads one page
     * from the question on, which holds its reply unless the developer wrote this many messages after it.
     */
    private static final int HISTORY_MATCH_LIMIT = 15;

    private final Slack slack;
    private final SlackCredentialProvider credentialProvider;
    private final SlackRateLimitTracker rateLimitTracker;
    private final OutboundEgressGuard egressGuard;
    private final ConcurrentMap<Long, BotIdentity> botCache = new ConcurrentHashMap<>();

    public SlackMessageService(
            SlackCredentialProvider credentialProvider,
            SlackRateLimitTracker rateLimitTracker,
            OutboundEgressGuard egressGuard) {
        this.slack = Slack.getInstance();
        this.credentialProvider = credentialProvider;
        this.rateLimitTracker = rateLimitTracker;
        this.egressGuard = egressGuard;
    }

    public void sendForWorkspace(long workspaceId, String channelId, List<LayoutBlock> blocks, String fallback) {
        sendForWorkspace(workspaceId, channelId, null, blocks, fallback);
    }

    public void sendForWorkspace(
            long workspaceId, String channelId, @Nullable String threadTs, List<LayoutBlock> blocks, String fallback) {
        ChatPostMessageRequest.ChatPostMessageRequestBuilder request = ChatPostMessageRequest.builder()
                .channel(channelId)
                .blocks(blocks)
                .text(fallback); // fallback text shown in notifications + accessibility tools
        if (threadTs != null && !threadTs.isBlank()) {
            request.threadTs(threadTs);
        }
        ChatPostMessageRequest built = request.build();
        callWrite(workspaceId, channelId, methods -> methods.chatPostMessage(built));
    }

    /**
     * A posted message: the conversation it landed in and its {@code ts}. A post addressed to a user id lands in the
     * app's DM with that user, whose channel id the answer names.
     */
    public record Posted(String channelId, String ts) {}

    /**
     * Posts a message without link or media unfurls. {@code text} is what notifications and screen readers show.
     */
    public Posted postMessage(
            long workspaceId,
            String channelId,
            @Nullable String threadTs,
            List<LayoutBlock> blocks,
            String text,
            Message.@Nullable Metadata metadata) {
        ChatPostMessageRequest.ChatPostMessageRequestBuilder request = ChatPostMessageRequest.builder()
                .channel(channelId)
                .blocks(blocks)
                .text(text)
                .unfurlLinks(false)
                .unfurlMedia(false)
                .metadata(metadata);
        if (threadTs != null && !threadTs.isBlank()) {
            request.threadTs(threadTs);
        }
        ChatPostMessageRequest built = request.build();
        ChatPostMessageResponse response = callWrite(workspaceId, channelId, methods -> methods.chatPostMessage(built));
        String postedTo = response.getChannel();
        return new Posted(postedTo == null || postedTo.isBlank() ? channelId : postedTo, response.getTs());
    }

    /** Replaces the blocks, text and metadata of a message the app wrote, such as a stream that Slack already ended. */
    public void updateMessage(
            long workspaceId,
            String channelId,
            String ts,
            List<LayoutBlock> blocks,
            String text,
            Message.@Nullable Metadata metadata) {
        callWrite(
                workspaceId,
                channelId,
                methods -> methods.chatUpdate(req ->
                        req.channel(channelId).ts(ts).blocks(blocks).text(text).metadata(metadata)));
    }

    /**
     * Post an <strong>ephemeral</strong> message via {@code chat.postEphemeral} — visible only to {@code slackUserId}
     * in {@code channelId} and gone on reload. Throws {@link SlackSendException} carrying the Slack error so the
     * caller can log-and-swallow (the notice is best-effort).
     */
    public void sendEphemeralForWorkspace(
            long workspaceId, String channelId, String slackUserId, List<LayoutBlock> blocks, String fallback) {
        ChatPostEphemeralRequest request = ChatPostEphemeralRequest.builder()
                .channel(channelId)
                .user(slackUserId)
                .blocks(blocks)
                .text(fallback)
                .build();
        callWrite(workspaceId, channelId, methods -> methods.chatPostEphemeral(request));
    }

    /** Whether the workspace has an active Slack connection whose bot token Hephaestus can use. */
    public boolean isConnected(long workspaceId) {
        return resolveToken(workspaceId).isPresent();
    }

    /** Evict the cached bot identity, e.g. on uninstall — a later reconnect may install a different app. */
    public void evictBot(long workspaceId) {
        botCache.remove(workspaceId);
    }

    /**
     * The app's bot in a workspace, as {@code auth.test} names it.
     *
     * @param userId the bot user ({@code U…}), which a member event names
     * @param botId the bot ({@code B…}), which every message the app posts carries as {@code bot_id}
     */
    public record BotIdentity(String userId, @Nullable String botId) {}

    /**
     * The app's own bot in this workspace via {@code auth.test}, or empty when it cannot be resolved (no active
     * connection / Slack failure). Best-effort and never throws: an unresolved bot degrades to "cannot confirm that it is
     * the bot" rather than blocking the caller.
     *
     * <p>Positive results are cached per workspace: the bot is stable for an installation, and this runs on
     * the serial event-consumer thread for every {@code member_joined_channel}, so an uncached remote call per join
     * would stall unrelated workspaces' events. Failures are not cached (retried on the next event).
     */
    public Optional<BotIdentity> resolveBot(long workspaceId) {
        BotIdentity cached = botCache.get(workspaceId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<String> token = resolveToken(workspaceId);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        try {
            AuthTestResponse r = callHonoringRateLimit(
                    workspaceId, () -> slack.methods(token.get()).authTest(req -> req));
            if (r.isOk() && r.getUserId() != null && !r.getUserId().isBlank()) {
                BotIdentity bot = new BotIdentity(r.getUserId(), r.getBotId());
                botCache.put(workspaceId, bot);
                return Optional.of(bot);
            }
            log.debug("Slack auth.test not ok for workspaceId={}: error={}", workspaceId, r.getError());
            return Optional.empty();
        } catch (SlackApiException | IOException e) {
            log.debug("Slack auth.test failed for workspaceId={}: {}", workspaceId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Begin a streamed assistant reply in {@code threadTs}; returns the streaming message {@code ts} to append
     * to. {@code markdownText} is standard Markdown (Slack renders it, incl. tables) — not Slack mrkdwn.
     */
    public String startStream(long workspaceId, String channel, @Nullable String threadTs, String markdownText) {
        return callWrite(
                        workspaceId,
                        channel,
                        methods -> methods.chatStartStream(
                                req -> req.channel(channel).threadTs(threadTs).markdownText(markdownText)))
                .getTs();
    }

    /** Append a Markdown delta to an in-progress stream. Throws with the Slack error so callers can detect a gone recipient. */
    public void appendStream(long workspaceId, String channel, String ts, String markdownText) {
        callWrite(
                workspaceId,
                channel,
                methods -> methods.chatAppendStream(
                        req -> req.channel(channel).ts(ts).markdownText(markdownText)));
    }

    /**
     * How {@link #stopStream} ends a stream.
     *
     * @param markdownText a last piece of Markdown
     * @param blocks blocks under the text, such as feedback buttons
     * @param metadata metadata that tags the message for the reconciler
     */
    public record StreamStop(
            @Nullable String markdownText, List<LayoutBlock> blocks, Message.@Nullable Metadata metadata) {}

    public void stopStream(long workspaceId, String channel, String ts, StreamStop stop) {
        callWrite(
                workspaceId,
                channel,
                methods -> methods.chatStopStream(req -> req.channel(channel)
                        .ts(ts)
                        .markdownText(stop.markdownText())
                        .blocks(stop.blocks().isEmpty() ? null : stop.blocks())
                        .metadata(stop.metadata())));
    }

    private interface WriteCall<T extends SlackApiTextResponse> {
        T call(MethodsClient methods) throws SlackApiException, IOException;
    }

    /**
     * One Slack write; every write of this gateway goes through it. Silent Mode refuses it before a token is read. A
     * 429 is retried after its {@code Retry-After}; one that outlives the retry budget is {@code ratelimited} and
     * carries its wait. A failed transport is {@code transport_failure}.
     */
    private <T extends SlackApiTextResponse> T callWrite(long workspaceId, String channel, WriteCall<T> call) {
        if (!egressGuard.deliveryAllowed("slack.write")) {
            throw new SlackSendException(workspaceId, channel, "silent_mode_engaged");
        }
        String token = resolveToken(workspaceId)
                .orElseThrow(() -> new SlackSendException(workspaceId, channel, "no_active_slack_connection"));
        try {
            T response = callOutboundHonoringRateLimit(workspaceId, channel, () -> call.call(slack.methods(token)));
            if (!response.isOk()) {
                String error = response.getError();
                throw new SlackSendException(workspaceId, channel, error == null ? "unknown" : error);
            }
            return response;
        } catch (SlackApiException e) {
            long retryAfterMs = rateLimitRetryAfterMillis(e);
            if (retryAfterMs != SlackSendException.NOT_RATE_LIMITED) {
                throw new SlackSendException(workspaceId, channel, "ratelimited", retryAfterMs, e);
            }
            throw new SlackSendException(workspaceId, channel, "transport_failure", e);
        } catch (IOException e) {
            throw new SlackSendException(workspaceId, channel, "transport_failure", e);
        }
    }

    /**
     * Adds ({@code present}) or removes the app's emoji reaction on a message. A reaction that is already in the
     * wanted state counts as done.
     *
     * @throws SlackSendException with the Slack error when Slack refused it
     */
    public void setReaction(long workspaceId, String channel, String ts, String emoji, boolean present) {
        try {
            if (present) {
                callWrite(
                        workspaceId,
                        channel,
                        methods -> methods.reactionsAdd(
                                req -> req.channel(channel).timestamp(ts).name(emoji)));
            } else {
                callWrite(
                        workspaceId,
                        channel,
                        methods -> methods.reactionsRemove(
                                req -> req.channel(channel).timestamp(ts).name(emoji)));
            }
        } catch (SlackSendException e) {
            if (!(present ? "already_reacted" : "no_reaction").equals(e.slackError())) {
                throw e;
            }
        }
    }

    /**
     * The {@code ts} of each message posted at or after {@code oldest} that {@code match} accepts, oldest first, read
     * with its metadata: from the top level of the channel, or from the replies of {@code threadTs}. Reads one page.
     *
     * @throws SlackSendException when Slack could not be read, so the caller cannot conclude the message is absent
     */
    public List<String> findWorkspaceMessages(
            long workspaceId, String channel, @Nullable String threadTs, String oldest, Predicate<Message> match) {
        String token = resolveToken(workspaceId)
                .orElseThrow(() -> new SlackSendException(workspaceId, channel, "no_active_slack_connection"));
        try {
            List<Message> messages;
            String error;
            if (threadTs == null) {
                var r = callHonoringRateLimit(
                        workspaceId,
                        () -> slack.methods(token)
                                .conversationsHistory(req -> req.channel(channel)
                                        .oldest(oldest)
                                        .inclusive(true)
                                        .includeAllMetadata(true)
                                        .limit(HISTORY_MATCH_LIMIT)),
                        SYNC_RATE_LIMIT_MAX_WAIT_MS,
                        SYNC_RATE_LIMIT_TOTAL_BUDGET_MS);
                messages = r.getMessages();
                error = r.isOk() ? null : r.getError();
            } else {
                var r = callHonoringRateLimit(
                        workspaceId,
                        () -> slack.methods(token)
                                .conversationsReplies(req -> req.channel(channel)
                                        .ts(threadTs)
                                        .oldest(oldest)
                                        .inclusive(true)
                                        .includeAllMetadata(true)
                                        .limit(HISTORY_MATCH_LIMIT)),
                        SYNC_RATE_LIMIT_MAX_WAIT_MS,
                        SYNC_RATE_LIMIT_TOTAL_BUDGET_MS);
                messages = r.getMessages();
                error = r.isOk() ? null : r.getError();
            }
            if (error != null) {
                throw new SlackSendException(workspaceId, channel, error);
            }
            return (messages == null ? List.<Message>of() : messages)
                    .stream()
                            .filter(match)
                            .map(Message::getTs)
                            .filter(ts -> ts != null && !ts.isBlank())
                            .sorted(SlackTs::compare)
                            .toList();
        } catch (SlackApiException | IOException e) {
            throw new SlackSendException(workspaceId, channel, "transport_failure", e);
        }
    }

    /**
     * Publish (replace) the App Home tab view for one member via {@code views.publish}. Throws
     * {@link SlackSendException} carrying the Slack error so the caller can log-and-swallow (App Home render is
     * best-effort).
     */
    public void publishHomeView(long workspaceId, String slackUserId, View view) {
        try {
            callWrite(
                    workspaceId,
                    slackUserId,
                    methods ->
                            methods.viewsPublish(req -> req.userId(slackUserId).view(view)));
        } catch (SlackSendException e) {
            if (!"silent_mode_engaged".equals(e.slackError())) {
                throw e;
            }
        }
    }

    public List<SlackConversationInfo> listConversations(long workspaceId) {
        Optional<String> token = resolveToken(workspaceId);
        if (token.isEmpty()) {
            log.debug("Slack conversations.list skipped: no token for workspaceId={}", workspaceId);
            return List.of();
        }

        MethodsClient methods = slack.methods(token.get());
        List<SlackConversationInfo> channels = new ArrayList<>();
        String cursor = "";
        int pages = 0;
        try {
            do {
                String pageCursor = cursor;
                ConversationsListResponse response = callHonoringRateLimit(
                        workspaceId,
                        () -> methods.conversationsList(req -> req.types(
                                        List.of(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
                                .excludeArchived(false)
                                .limit(CONVERSATIONS_LIST_PAGE_SIZE)
                                .cursor(pageCursor)));
                if (!response.isOk()) {
                    log.warn(
                            "Slack conversations.list returned ok=false: workspaceId={}, error={}",
                            workspaceId,
                            response.getError());
                    return channels;
                }
                if (response.getChannels() != null) {
                    response.getChannels().stream()
                            .map(SlackMessageService::toConversationInfo)
                            .forEach(channels::add);
                }
                cursor = response.getResponseMetadata() == null
                        ? null
                        : response.getResponseMetadata().getNextCursor();
                pages++;
            } while (cursor != null && !cursor.isBlank() && pages < CONVERSATIONS_LIST_MAX_PAGES);
            if (pages >= CONVERSATIONS_LIST_MAX_PAGES) {
                log.warn(
                        "Slack conversations.list pagination hit cap: workspaceId={}, pages={}, channels={}",
                        workspaceId,
                        pages,
                        channels.size());
            }
            return channels;
        } catch (SlackApiException | IOException e) {
            log.warn(
                    "Slack conversations.list transport failure: workspaceId={}, collected={}, error={}",
                    workspaceId,
                    channels.size(),
                    e.getMessage());
            return channels;
        }
    }

    public Optional<SlackConversationInfo> lookupConversation(long workspaceId, String channelId) {
        String token = resolveToken(workspaceId)
                .orElseThrow(() -> new SlackSendException(workspaceId, channelId, "no_active_slack_connection"));
        try {
            ConversationsInfoResponse response = callHonoringRateLimit(
                    workspaceId,
                    () -> slack.methods(token)
                            .conversationsInfo(req -> req.channel(channelId).includeNumMembers(true)));
            if (!response.isOk()) {
                log.debug(
                        "Slack conversations.info returned ok=false: workspaceId={}, channelId={}, error={}",
                        workspaceId,
                        channelId,
                        response.getError());
                return Optional.empty();
            }
            return Optional.ofNullable(response.getChannel()).map(SlackMessageService::toConversationInfo);
        } catch (SlackApiException | IOException e) {
            log.warn(
                    "Slack conversations.info transport failure: workspaceId={}, channelId={}, error={}",
                    workspaceId,
                    channelId,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * {@code conversations.info} with the failure mode preserved: the metadata refresher must distinguish a
     * DEFINITIVE "this channel is gone" ({@code channel_not_found}) from a transient transport problem — pausing a
     * healthy channel on a network blip would be a consent-state lie in the other direction.
     */
    public ConversationLookup lookupConversationDetailed(long workspaceId, String channelId) {
        Optional<String> token = resolveToken(workspaceId);
        if (token.isEmpty()) {
            return new ConversationLookup.Unavailable("no_active_slack_connection");
        }
        try {
            ConversationsInfoResponse response = callHonoringRateLimit(
                    workspaceId, () -> slack.methods(token.get()).conversationsInfo(req -> req.channel(channelId)));
            if (!response.isOk()) {
                String error = response.getError() == null ? "unknown" : response.getError();
                return "channel_not_found".equals(error)
                        ? new ConversationLookup.NotFound(error)
                        : new ConversationLookup.Unavailable(error);
            }
            Conversation channel = response.getChannel();
            if (channel == null) {
                return new ConversationLookup.Unavailable("empty_response");
            }
            return new ConversationLookup.Found(toConversationInfo(channel));
        } catch (SlackApiException | IOException e) {
            log.debug(
                    "Slack conversations.info transport failure: workspaceId={}, channelId={}, error={}",
                    workspaceId,
                    channelId,
                    e.getMessage());
            return new ConversationLookup.Unavailable("transport_failure");
        }
    }

    /** The three distinguishable outcomes of {@link #lookupConversationDetailed}. */
    public sealed interface ConversationLookup {
        record Found(SlackConversationInfo info) implements ConversationLookup {}

        /** Slack answered definitively: the channel no longer exists. */
        record NotFound(String slackError) implements ConversationLookup {}

        /** No definitive answer (transport failure, missing scope, no token) — callers must not act on this. */
        record Unavailable(String slackError) implements ConversationLookup {}
    }

    /**
     * One page of {@code conversations.history} for the nightly reconciliation. {@code oldest}/{@code latest} are
     * EXCLUSIVE Slack ts bounds (matching the forward-only "strictly after" invariant); results are newest-first and
     * contain thread parents only (replies live behind {@code conversations.replies}). Throws
     * {@link SlackSendException} on any failure so the sync can abandon the channel without advancing its watermark.
     */
    public HistoryPage fetchHistoryPage(
            long workspaceId, String channelId, String oldest, String latest, @Nullable String cursor, int limit) {
        String token = resolveToken(workspaceId)
                .orElseThrow(() -> new SlackSendException(workspaceId, channelId, "no_active_slack_connection"));
        try {
            var response = callHonoringRateLimit(
                    workspaceId,
                    () -> slack.methods(token)
                            .conversationsHistory(req -> req.channel(channelId)
                                    .oldest(oldest)
                                    .latest(latest)
                                    .cursor(cursor == null || cursor.isBlank() ? null : cursor)
                                    .limit(limit)),
                    SYNC_RATE_LIMIT_MAX_WAIT_MS,
                    SYNC_RATE_LIMIT_TOTAL_BUDGET_MS);
            if (!response.isOk()) {
                throw new SlackSendException(
                        workspaceId, channelId, response.getError() == null ? "unknown" : response.getError());
            }
            List<Message> messages = response.getMessages() == null ? List.of() : response.getMessages();
            String nextCursor = response.getResponseMetadata() == null
                    ? null
                    : response.getResponseMetadata().getNextCursor();
            return new HistoryPage(messages, nextCursor == null || nextCursor.isBlank() ? null : nextCursor);
        } catch (SlackApiException | IOException e) {
            throw new SlackSendException(workspaceId, channelId, "transport_failure", e);
        }
    }

    /**
     * One page of {@code conversations.replies} for a thread with a detected reply gap. Same contract as
     * {@link #fetchHistoryPage}; the parent message is included by Slack and must be filtered by the caller.
     */
    public HistoryPage fetchRepliesPage(
            long workspaceId, String channelId, String threadTs, String oldest, @Nullable String cursor, int limit) {
        String token = resolveToken(workspaceId)
                .orElseThrow(() -> new SlackSendException(workspaceId, channelId, "no_active_slack_connection"));
        try {
            var response = callHonoringRateLimit(
                    workspaceId,
                    () -> slack.methods(token)
                            .conversationsReplies(req -> req.channel(channelId)
                                    .ts(threadTs)
                                    .oldest(oldest)
                                    .cursor(cursor == null || cursor.isBlank() ? null : cursor)
                                    .limit(limit)),
                    SYNC_RATE_LIMIT_MAX_WAIT_MS,
                    SYNC_RATE_LIMIT_TOTAL_BUDGET_MS);
            if (!response.isOk()) {
                throw new SlackSendException(
                        workspaceId, channelId, response.getError() == null ? "unknown" : response.getError());
            }
            List<Message> messages = response.getMessages() == null ? List.of() : response.getMessages();
            String nextCursor = response.getResponseMetadata() == null
                    ? null
                    : response.getResponseMetadata().getNextCursor();
            return new HistoryPage(messages, nextCursor == null || nextCursor.isBlank() ? null : nextCursor);
        } catch (SlackApiException | IOException e) {
            throw new SlackSendException(workspaceId, channelId, "transport_failure", e);
        }
    }

    /** One page of channel history/replies: the raw SDK messages plus the pagination cursor (null when exhausted). */
    public record HistoryPage(
            List<Message> messages, @Nullable String nextCursor) {}

    public void joinPublicChannel(long workspaceId, String channelId) {
        try {
            callWrite(workspaceId, channelId, methods -> methods.conversationsJoin(req -> req.channel(channelId)));
        } catch (SlackSendException e) {
            if (!"silent_mode_engaged".equals(e.slackError())) {
                throw e;
            }
        }
    }

    private static SlackConversationInfo toConversationInfo(Conversation conversation) {
        return new SlackConversationInfo(
                conversation.getId(),
                conversation.getName() == null || conversation.getName().isBlank()
                        ? conversation.getId()
                        : conversation.getName(),
                conversation.isPrivate(),
                conversation.isMember(),
                conversation.isArchived());
    }

    public record SlackConversationInfo(
            String channelId, String channelName, boolean privateChannel, boolean member, boolean archived) {}

    /** A synchronous Slack Web API call that may throw the SDK's checked failures. */
    @FunctionalInterface
    private interface SlackCall<T> {
        T call() throws SlackApiException, IOException;
    }

    /**
     * Run a synchronous Slack Web API call, transparently honoring a 429 {@code Retry-After} (jittered, bounded by
     * {@link #RATE_LIMIT_TOTAL_BUDGET_MS}). The SDK's synchronous {@code MethodsClient} surfaces a 429 as a
     * {@link SlackApiException} and does <em>not</em> retry it (only the async client throttles), so the bounded
     * wait-and-retry happens here. Non-429 {@link SlackApiException} and {@link IOException} propagate unchanged so
     * the caller's existing error handling is preserved.
     */
    private <T> T callHonoringRateLimit(long workspaceId, SlackCall<T> call) throws SlackApiException, IOException {
        return callHonoringRateLimit(workspaceId, call, RATE_LIMIT_MAX_WAIT_MS, RATE_LIMIT_TOTAL_BUDGET_MS);
    }

    private <T> T callOutboundHonoringRateLimit(long workspaceId, String target, SlackCall<T> call)
            throws SlackApiException, IOException {
        return callHonoringRateLimit(workspaceId, () -> {
            try {
                egressGuard.requireDeliveryAllowed("slack.write");
                return call.call();
            } catch (OutboundEgressSuppressedException e) {
                throw new SlackSendException(workspaceId, target, "silent_mode_engaged", e);
            }
        });
    }

    /**
     * Variant with explicit wait/budget bounds: interactive callers keep the tight defaults (a user is waiting), and
     * history reads that no one waits on pass the wider {@code SYNC_*} bounds (see {@link #SYNC_RATE_LIMIT_MAX_WAIT_MS}).
     */
    private <T> T callHonoringRateLimit(long workspaceId, SlackCall<T> call, long maxWaitMs, long totalBudgetMs)
            throws SlackApiException, IOException {
        long budgetLeftMs = totalBudgetMs;
        int attempt = 0;
        while (true) {
            try {
                return call.call();
            } catch (SlackApiException e) {
                long retryAfterMs = rateLimitRetryAfterMillis(e);
                if (retryAfterMs == SlackSendException.NOT_RATE_LIMITED) {
                    throw e; // not a rate-limit — let the caller map it
                }
                // Report the throttle before deciding whether to retry: an admin needs to see a 429 even
                // (especially) when the retry budget is spent and the call is about to fail.
                rateLimitTracker.recordThrottle(workspaceId, retryAfterMs);
                if (budgetLeftMs <= 0) {
                    throw e; // retry budget spent — let the caller map it
                }
                long waitMs =
                        Math.min(Math.min(SlackBackoff.withJitter(retryAfterMs, ++attempt), maxWaitMs), budgetLeftMs);
                log.warn(
                        "Slack rate-limited (429); backing off {} ms before retry (attempt {}, budget left {} ms)",
                        waitMs,
                        attempt,
                        budgetLeftMs);
                budgetLeftMs -= waitMs;
                SlackBackoff.sleep(waitMs);
                if (Thread.currentThread().isInterrupted()) {
                    // Interrupted mid-backoff (e.g. a shutting-down worker thread): stop retrying immediately rather
                    // than spinning the rest of the budget in a tight loop; surface the last 429 unchanged (the
                    // interrupt flag stays set for the caller) so its error handling maps it.
                    throw e;
                }
            }
        }
    }

    /**
     * The {@code Retry-After} wait in ms for a 429, else {@link SlackSendException#NOT_RATE_LIMITED}. Slack always
     * sends {@code Retry-After} on a 429; a missing/garbled header defaults to 1s.
     */
    static long rateLimitRetryAfterMillis(SlackApiException e) {
        Response resp = e.getResponse();
        if (resp == null || resp.code() != 429) {
            return SlackSendException.NOT_RATE_LIMITED;
        }
        String header = resp.header("Retry-After");
        long seconds = 1L;
        if (header != null) {
            try {
                seconds = Long.parseLong(header.trim());
            } catch (NumberFormatException ignored) {
                // keep the 1s default
            }
        }
        return Math.max(0L, seconds) * 1000L;
    }

    private Optional<String> resolveToken(long workspaceId) {
        return credentialProvider
                .resolve(new IntegrationRef(IntegrationKind.SLACK, workspaceId, null))
                .flatMap(b -> b instanceof BearerToken bt ? Optional.of(bt.token()) : Optional.empty());
    }
}
