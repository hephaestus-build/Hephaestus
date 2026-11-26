package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
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
import com.slack.api.model.ResponseMetadata;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.view.View;
import de.tum.cit.aet.hephaestus.core.security.MissingCredentialKeyException;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialUnreadableException;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.slack.SlackProperties;
import de.tum.cit.aet.hephaestus.integration.slack.credentials.SlackCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.metrics.SlackMetrics;
import de.tum.cit.aet.hephaestus.integration.slack.metrics.SlackMetrics.MembershipStatus;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import okhttp3.FormBody;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.BooleanNode;

/**
 * Per-workspace Slack messaging. Bot token resolved at send time via
 * {@link SlackCredentialProvider}; failures throw {@link SlackSendException} carrying
 * the Slack error code so callers can choose the HTTP mapping.
 */
@Service
@OutboundEgressGateway
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true", matchIfMissing = false)
public class SlackMessageService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SlackMessageService.class);
    private static final JsonMapper MEMBERSHIP_JSON = JsonMapper.builder().build();
    private static final List<String> REQUIRED_NON_MEMBER_FLAGS =
            List.of("is_restricted", "is_ultra_restricted", "is_bot", "is_app_user");
    // Slack may omit the deleted field for an active user.
    private static final List<String> OPTIONAL_NON_MEMBER_FLAGS = List.of(
            "deleted",
            "is_stranger",
            "is_external",
            "is_invited_user",
            "is_profile_only_user",
            "is_forgotten",
            "is_connector_bot",
            "is_workflow_bot");

    private static final int CONVERSATIONS_LIST_PAGE_SIZE = 200;
    private static final int CONVERSATIONS_LIST_MAX_PAGES = 20;

    /**
     * The deadline of one HTTP call of the Slack client (OkHttp {@code callTimeout}): connecting, writing the request
     * and reading the whole response. The SDK sets none by default.
     */
    static final int NATIVE_CALL_TIMEOUT_MS = 10_000;

    /**
     * The smallest {@code conversations.history} or {@code conversations.replies} page any install gets: Slack's clamp
     * for distributed non-Marketplace apps. A self-hosted instance's own app gets more. The reconciler reads one page
     * from the question on. When Slack holds more than that page, a reply that the page does not show is not proved
     * absent ({@link Matches#more}).
     */
    private static final int HISTORY_MATCH_LIMIT = 15;

    private final Slack slack;
    private final SlackCredentialProvider credentialProvider;
    private final SlackRateLimitTracker rateLimitTracker;
    private final OutboundEgressGuard egressGuard;
    private final SlackMetrics metrics;
    private final SlackTime time;
    private final ConcurrentMap<Long, BotIdentity> botCache = new ConcurrentHashMap<>();

    @Autowired
    public SlackMessageService(
            SlackCredentialProvider credentialProvider,
            SlackRateLimitTracker rateLimitTracker,
            OutboundEgressGuard egressGuard,
            SlackMetrics metrics,
            SlackProperties properties) {
        this(
                SlackSendFence.client(nativeConfig(properties)),
                credentialProvider,
                rateLimitTracker,
                egressGuard,
                metrics,
                SlackTime.SYSTEM);
    }

    /**
     * @param slack the gateway's Slack client with its send fence
     * @param time the time of the throttle budgets; a test passes virtual time, so no throttle wait takes real time
     */
    SlackMessageService(
            Slack slack,
            SlackCredentialProvider credentialProvider,
            SlackRateLimitTracker rateLimitTracker,
            OutboundEgressGuard egressGuard,
            SlackMetrics metrics,
            SlackTime time) {
        this.slack = slack;
        this.credentialProvider = credentialProvider;
        this.rateLimitTracker = rateLimitTracker;
        this.egressGuard = egressGuard;
        this.metrics = metrics;
        this.time = time;
    }

    /**
     * The configuration of this gateway's own Slack client, with a deadline for each HTTP call
     * ({@link #NATIVE_CALL_TIMEOUT_MS}) and the methods under the API origin of {@code properties}. The SDK's shared
     * default client and its configuration stay unchanged.
     */
    static SlackConfig nativeConfig(SlackProperties properties) {
        SlackConfig config = new SlackConfig();
        config.setHttpClientCallTimeoutMillis(NATIVE_CALL_TIMEOUT_MS);
        config.setMethodsEndpointUrlPrefix(properties.apiBase() + "/api/");
        // SDK statistics can issue auth.test before a method call. An admitted post must make only its own request.
        config.getMethodsConfig().setStatsEnabled(false);
        return config;
    }

    /** Closes this gateway's own Slack client: its HTTP client and the executors of its configuration. */
    @PreDestroy
    @Override
    public void close() {
        try {
            slack.close();
        } catch (Exception e) {
            log.warn("Slack client did not close: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * A posted message: the conversation it landed in and its {@code ts}. A post addressed to a user id lands in the
     * app's DM with that user, whose channel id the answer names.
     */
    public record Posted(String channelId, String ts) {}

    /** A message to post: where it goes, what it shows, and the metadata that finds it again. */
    public record Outgoing(
            String channelId,
            @Nullable String threadTs,
            List<LayoutBlock> blocks,
            String text,
            Message.@Nullable Metadata metadata) {}

    /**
     * Admits a post after connection setup, immediately before the write. A refusal fails with
     * {@code not_admitted}. A 429 returns to the outbox for a fresh attempt.
     */
    public Posted postAdmitted(long workspaceId, Outgoing post, BooleanSupplier admitAtSend) {
        Objects.requireNonNull(admitAtSend);
        String channelId = post.channelId();
        String threadTs = post.threadTs();
        ChatPostMessageRequest.ChatPostMessageRequestBuilder request = ChatPostMessageRequest.builder()
                .channel(channelId)
                .blocks(post.blocks())
                .text(post.text())
                .unfurlLinks(false)
                .unfurlMedia(false)
                .metadata(post.metadata());
        if (threadTs != null && !threadTs.isBlank()) {
            request.threadTs(threadTs);
        }
        ChatPostMessageRequest built = request.build();
        ChatPostMessageResponse response = callWrite(
                workspaceId, null, channelId, () -> true, admitAtSend, methods -> methods.chatPostMessage(built));
        String postedTo = response.getChannel();
        String ts = response.getTs();
        if (ts == null || ts.isBlank()) throw new SlackSendException(workspaceId, channelId, "invalid_receipt");
        return new Posted(postedTo == null || postedTo.isBlank() ? channelId : postedTo, ts);
    }

    /**
     * Post an <strong>ephemeral</strong> message via {@code chat.postEphemeral} — visible only to {@code slackUserId}
     * in {@code channelId} and gone on reload. Throws {@link SlackSendException} carrying the Slack error so the
     * caller can log-and-swallow (the notice is best-effort).
     */
    public void sendEphemeralForWorkspace(
            long workspaceId, String channelId, String slackUserId, List<LayoutBlock> blocks, String fallback) {
        sendEphemeral(workspaceId, null, channelId, slackUserId, new EphemeralMessage(blocks, fallback), () -> true);
    }

    public void sendEphemeralForTeam(
            long workspaceId,
            String teamId,
            String channelId,
            String slackUserId,
            EphemeralMessage message,
            BooleanSupplier eligible) {
        sendEphemeral(workspaceId, teamId, channelId, slackUserId, message, eligible);
    }

    private void sendEphemeral(
            long workspaceId,
            @Nullable String teamId,
            String channelId,
            String slackUserId,
            EphemeralMessage message,
            BooleanSupplier eligible) {
        ChatPostEphemeralRequest request = ChatPostEphemeralRequest.builder()
                .channel(channelId)
                .user(slackUserId)
                .blocks(message.blocks())
                .text(message.fallback())
                .build();
        callWrite(workspaceId, teamId, channelId, eligible, null, methods -> methods.chatPostEphemeral(request));
    }

    public record EphemeralMessage(List<LayoutBlock> blocks, String fallback) {}

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
                    SlackBackoff.interactive(time, rateLimitTracker, workspaceId),
                    () -> slack.methods(token.get()).authTest(req -> req));
            if (r.isOk() && r.getUserId() != null && !r.getUserId().isBlank()) {
                BotIdentity bot = new BotIdentity(r.getUserId(), r.getBotId());
                botCache.put(workspaceId, bot);
                return Optional.of(bot);
            }
            log.debug("Slack auth.test not ok for workspaceId={}: error={}", workspaceId, r.getError());
            return Optional.empty();
        } catch (SlackApiException | IOException e) {
            log.debug(
                    "Slack auth.test failed for workspaceId={}: {}",
                    workspaceId,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private interface WriteCall<T extends SlackApiTextResponse> {
        T call(MethodsClient methods) throws SlackApiException, IOException;
    }

    /**
     * Silent Mode refuses writes before credential lookup and after it returns. The outbox also checks under
     * admission after taking the source lock. Other writes check just before each attempt.
     * A recorded throttle or a 429 returns a post to the outbox for a new attempt. Other writes can wait within the
     * interactive budget.
     */
    private <T extends SlackApiTextResponse> T callWrite(
            long workspaceId,
            @Nullable String teamId,
            String channel,
            BooleanSupplier eligible,
            @Nullable BooleanSupplier admitAtSend,
            WriteCall<T> call) {
        if (!egressGuard.deliveryAllowed("slack.write")) {
            throw new SlackSendException(workspaceId, channel, "silent_mode_engaged");
        }
        Optional<String> credential = teamId == null
                ? resolveToken(workspaceId)
                : credentialProvider
                        .resolveActiveTeam(workspaceId, teamId, eligible)
                        .map(BearerToken::token);
        String token = credential.orElseThrow(
                () -> new SlackSendException(workspaceId, channel, "no_active_slack_connection"));
        try {
            MethodsClient methods = slack.methods(token);
            T response;
            if (admitAtSend == null) {
                response = callHonoringRateLimit(SlackBackoff.interactive(time, rateLimitTracker, workspaceId), () -> {
                    egressGuard.requireDeliveryAllowed("slack.write");
                    return call.call(methods);
                });
            } else {
                egressGuard.requireDeliveryAllowed("slack.write");
                Duration remaining = rateLimitTracker.remainingPostThrottle(workspaceId);
                if (remaining.isPositive()) {
                    // The outbox stores retry delays in whole seconds. Round up to preserve the provider deadline.
                    long seconds = remaining.plusSeconds(1).minusNanos(1).toSeconds();
                    throw new SlackSendException(
                            workspaceId, channel, "ratelimited", TimeUnit.SECONDS.toMillis(seconds));
                }
                SlackSendFence.ADMISSION.set(new SlackSendFence.Admission(admitAtSend));
                try {
                    response = call.call(methods);
                } finally {
                    SlackSendFence.ADMISSION.remove();
                }
            }
            if (!response.isOk()) {
                String error = response.getError();
                throw new SlackSendException(workspaceId, channel, error == null ? "unknown" : error);
            }
            return response;
        } catch (OutboundEgressSuppressedException e) {
            throw new SlackSendException(workspaceId, channel, "silent_mode_engaged", e);
        } catch (SlackApiException e) {
            Response refused = e.getResponse();
            long retryAfterMs = refused == null
                    ? SlackSendException.NOT_RATE_LIMITED
                    : SlackBackoff.retryAfterMillis(refused.code(), refused.header("Retry-After"));
            if (retryAfterMs != SlackSendException.NOT_RATE_LIMITED) {
                if (admitAtSend != null) rateLimitTracker.recordPostThrottle(workspaceId, retryAfterMs);
                throw new SlackSendException(workspaceId, channel, "ratelimited", retryAfterMs, e);
            }
            throw new SlackSendException(workspaceId, channel, "transport_failure", e);
        } catch (SlackSendFence.NotAdmitted e) {
            throw new SlackSendException(workspaceId, channel, "not_admitted", e);
        } catch (IOException e) {
            throw new SlackSendException(workspaceId, channel, "transport_failure", e);
        }
    }

    /**
     * The messages that a filter takes from one page of Slack history.
     *
     * @param ts the {@code ts} of each message that the filter takes, oldest first
     * @param more whether Slack holds more messages than the page: a message that the page does not show can still be
     *     in Slack, so an empty {@code ts} does not prove that it is absent
     */
    public record Matches(List<String> ts, boolean more) {

        /** Whether a history or replies page has more after it: Slack tells it by {@code has_more} or a cursor. */
        static boolean holdsMore(boolean hasMore, @Nullable ResponseMetadata metadata) {
            String cursor = metadata == null ? null : metadata.getNextCursor();
            return hasMore || (cursor != null && !cursor.isBlank());
        }
    }

    /**
     * The messages posted at or after {@code oldest} that {@code match} accepts, read with their metadata: from the top
     * level of the channel, or from the replies of {@code threadTs}. Reads one page.
     *
     * @throws SlackSendException when Slack could not be read, so the caller cannot conclude the message is absent
     */
    public Matches readWorkspaceMessages(
            long workspaceId, String channel, @Nullable String threadTs, String oldest, Predicate<Message> match) {
        return readWorkspaceMessages(workspaceId, channel, threadTs, oldest, match, 1);
    }

    /** Reads a bounded sequence of pages; an unfinished search never proves absence. */
    public Matches readWorkspaceMessages(
            long workspaceId,
            String channel,
            @Nullable String threadTs,
            String oldest,
            Predicate<Message> match,
            int maxPages) {
        if (maxPages < 1) throw new IllegalArgumentException("Readback needs at least one page");
        String token = resolveToken(workspaceId)
                .orElseThrow(() -> new SlackSendException(workspaceId, channel, "no_active_slack_connection"));
        long deadline = time.nanoTime() + Duration.ofSeconds(30).toNanos();
        // Consent readback shares one short budget so a throttled later page leaves time for the write fence.
        var budget = maxPages == 1
                ? SlackBackoff.history(time, rateLimitTracker, workspaceId)
                : SlackBackoff.interactive(time, rateLimitTracker, workspaceId);
        String cursor = null;
        try {
            for (int page = 0; page < maxPages; page++) {
                String pageCursor = cursor;
                List<Message> messages;
                String error;
                boolean more;
                String nextCursor;
                if (threadTs == null) {
                    var r = callHonoringRateLimit(
                            budget,
                            () -> slack.methods(token)
                                    .conversationsHistory(req -> req.channel(channel)
                                            .oldest(oldest)
                                            .inclusive(true)
                                            .includeAllMetadata(true)
                                            .cursor(pageCursor)
                                            .limit(HISTORY_MATCH_LIMIT)));
                    messages = r.getMessages();
                    error = r.isOk() ? null : Objects.requireNonNullElse(r.getError(), "unknown");
                    var responseMetadata = r.getResponseMetadata();
                    more = Matches.holdsMore(r.isHasMore(), responseMetadata);
                    nextCursor = responseMetadata == null ? null : responseMetadata.getNextCursor();
                } else {
                    var r = callHonoringRateLimit(
                            budget,
                            () -> slack.methods(token)
                                    .conversationsReplies(req -> req.channel(channel)
                                            .ts(threadTs)
                                            .oldest(oldest)
                                            .inclusive(true)
                                            .includeAllMetadata(true)
                                            .cursor(pageCursor)
                                            .limit(HISTORY_MATCH_LIMIT)));
                    messages = r.getMessages();
                    error = r.isOk() ? null : Objects.requireNonNullElse(r.getError(), "unknown");
                    var responseMetadata = r.getResponseMetadata();
                    more = Matches.holdsMore(r.isHasMore(), responseMetadata);
                    nextCursor = responseMetadata == null ? null : responseMetadata.getNextCursor();
                }
                if (error != null) throw new SlackSendException(workspaceId, channel, error);
                List<String> matched = (messages == null ? List.<Message>of() : messages)
                        .stream()
                                .filter(match)
                                .map(Message::getTs)
                                .filter(ts -> ts != null && !ts.isBlank())
                                .sorted(SlackTs::compare)
                                .toList();
                if (!matched.isEmpty() || !more) return new Matches(matched, more);
                if (nextCursor == null || nextCursor.isBlank() || time.nanoTime() >= deadline)
                    return new Matches(List.of(), true);
                cursor = nextCursor;
            }
            return new Matches(List.of(), true);
        } catch (SlackApiException | IOException e) {
            throw new SlackSendException(workspaceId, channel, "transport_failure", e);
        }
    }

    /**
     * Publish (replace) the App Home tab view for one member via {@code views.publish}. Throws
     * {@link SlackSendException} carrying the Slack error so the caller can log-and-swallow (App Home render is
     * best-effort).
     */
    public void publishHomeView(
            long workspaceId,
            String teamId,
            String slackUserId,
            View view,
            @Nullable String homeViewHash,
            BooleanSupplier eligible) {
        try {
            callWrite(
                    workspaceId,
                    teamId,
                    slackUserId,
                    eligible,
                    null,
                    methods -> methods.viewsPublish(
                            req -> req.userId(slackUserId).view(view).hash(homeViewHash)));
        } catch (SlackSendException e) {
            if (!"silent_mode_engaged".equals(e.slackError())) {
                throw e;
            }
        }
    }

    /**
     * Confirms full membership from Slack for this admission. Positive results are never cached: a workspace member
     * can leave or become a guest between two deliveries, even if no membership event reached us.
     * Throws when Slack cannot confirm membership, so the inbox can try the lookup again.
     */
    public boolean isCurrentFullMember(long workspaceId, String teamId, String subject) {
        MembershipStatus status;
        @Nullable Throwable cause = null;
        try {
            var token = resolveToken(workspaceId);
            status = token.isEmpty()
                    ? MembershipStatus.CREDENTIAL
                    : membershipStatus(
                            callHonoringRateLimit(
                                    SlackBackoff.interactive(time, rateLimitTracker, workspaceId),
                                    () -> readMembership(token.get(), subject)),
                            teamId,
                            subject);
        } catch (CredentialUnreadableException | MissingCredentialKeyException unreadable) {
            status = MembershipStatus.CREDENTIAL;
        } catch (SlackApiException unavailable) {
            var response = unavailable.getResponse();
            status = response != null && response.code() == 429
                    ? MembershipStatus.RATE_LIMITED
                    : MembershipStatus.PROVIDER;
            if (response != null) {
                cause = membershipHttpFailure(HttpStatusCode.valueOf(response.code()));
            }
        } catch (RestClientResponseException unavailable) {
            status = MembershipStatus.PROVIDER;
            cause = unavailable;
        } catch (IOException unavailable) {
            status = MembershipStatus.TRANSPORT;
            cause = unavailable;
        } catch (JacksonException malformed) {
            status = MembershipStatus.PAYLOAD;
        }
        metrics.recordMembershipLookup(status);
        if (status.unconfirmed()) {
            log.warn("Slack DM membership lookup failed: workspace={} reason={}", workspaceId, status.reason());
            throw new SlackMembershipUnavailableException(status, cause);
        }
        return status == MembershipStatus.CONFIRMED;
    }

    private static RestClientResponseException membershipHttpFailure(HttpStatusCode status) {
        // The inbox uses the status to classify the failure. Keep provider response data out of the exception.
        return new RestClientResponseException("Slack membership lookup failed", status, "", null, null, null);
    }

    private static MembershipStatus membershipStatus(JsonNode response, String teamId, String subject) {
        if (BooleanNode.FALSE.equals(response.path("ok"))) {
            String error = response.path("error").asString("");
            if (SlackSendException.isProviderUnavailable(error)) {
                throw membershipHttpFailure(HttpStatus.SERVICE_UNAVAILABLE);
            }
            return "user_not_found".equals(error) ? MembershipStatus.INELIGIBLE : MembershipStatus.PROVIDER;
        }
        var user = response.path("user");
        var userTeam = user.path("team_id").asString("");
        if (!BooleanNode.TRUE.equals(response.path("ok"))
                || !subject.equals(user.path("id").asString(""))
                || userTeam.isBlank()) return MembershipStatus.PAYLOAD;
        if (!teamId.equals(userTeam)
                || REQUIRED_NON_MEMBER_FLAGS.stream().anyMatch(flag -> BooleanNode.TRUE.equals(user.path(flag)))
                || OPTIONAL_NON_MEMBER_FLAGS.stream().anyMatch(flag -> BooleanNode.TRUE.equals(user.path(flag)))) {
            return MembershipStatus.INELIGIBLE;
        }
        boolean incomplete = REQUIRED_NON_MEMBER_FLAGS.stream()
                        .anyMatch(flag -> !user.path(flag).isBoolean())
                || OPTIONAL_NON_MEMBER_FLAGS.stream()
                        .anyMatch(flag -> user.has(flag) && !user.path(flag).isBoolean());
        return incomplete ? MembershipStatus.PAYLOAD : MembershipStatus.CONFIRMED;
    }

    private JsonNode readMembership(String token, String subject) throws IOException, SlackApiException {
        // The SDK's User type maps missing flags to false. Admission needs explicit values for the required flags.
        var form = new FormBody.Builder().add("user", subject);
        try (var response = slack.methods(token).runPostFormWithToken(form, "users.info", token)) {
            if (!response.isSuccessful()) throw new SlackApiException(response, "");
            var body = response.body();
            if (body == null) throw new IOException("Slack membership response has no body");
            return MEMBERSHIP_JSON.readTree(body.byteStream());
        }
    }

    /**
     * The display name of a human Slack user of the team, read from Slack now; empty for a bot, an app, a deleted user
     * or a user of another team.
     */
    public Optional<String> humanDisplayName(long workspaceId, String teamId, String subject) {
        var token = resolveToken(workspaceId);
        if (token.isEmpty()) throw new SlackSendException(workspaceId, subject, "no_active_slack_connection");
        try {
            var response = callHonoringRateLimit(
                    SlackBackoff.interactive(time, rateLimitTracker, workspaceId),
                    () -> slack.methods(token.get()).usersInfo(request -> request.user(subject)));
            if (!response.isOk())
                throw new SlackSendException(
                        workspaceId, subject, response.getError() == null ? "unknown" : response.getError());
            var user = response.getUser();
            if (user == null
                    || !subject.equals(user.getId())
                    || !teamId.equals(user.getTeamId())
                    || user.isBot()
                    || user.isAppUser()
                    || user.isDeleted()) return Optional.empty();
            var profile = user.getProfile();
            String name = profile == null ? null : profile.getDisplayName();
            if (name == null || name.isBlank()) name = profile == null ? null : profile.getRealName();
            if (name == null || name.isBlank()) name = user.getName();
            if (name == null || name.isBlank()) return Optional.empty();
            return Optional.of(name);
        } catch (SlackApiException | IOException e) {
            throw new SlackSendException(workspaceId, subject, "transport_failure", e);
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
                        SlackBackoff.interactive(time, rateLimitTracker, workspaceId),
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
                    e.getClass().getSimpleName());
            return channels;
        }
    }

    public Optional<SlackConversationInfo> lookupConversation(long workspaceId, String channelId) {
        String token = resolveToken(workspaceId)
                .orElseThrow(() -> new SlackSendException(workspaceId, channelId, "no_active_slack_connection"));
        try {
            ConversationsInfoResponse response = callHonoringRateLimit(
                    SlackBackoff.interactive(time, rateLimitTracker, workspaceId),
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
                    e.getClass().getSimpleName());
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
                    SlackBackoff.interactive(time, rateLimitTracker, workspaceId),
                    () -> slack.methods(token.get()).conversationsInfo(req -> req.channel(channelId)));
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
                    e.getClass().getSimpleName());
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
     * One page of {@code conversations.history}, with each message's metadata. {@code oldest}/{@code latest} are
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
                    SlackBackoff.history(time, rateLimitTracker, workspaceId),
                    () -> slack.methods(token)
                            .conversationsHistory(req -> req.channel(channelId)
                                    .oldest(oldest)
                                    .latest(latest)
                                    .inclusive(true)
                                    .cursor(cursor == null || cursor.isBlank() ? null : cursor)
                                    .includeAllMetadata(true)
                                    .limit(limit)));
            if (!response.isOk()) {
                throw new SlackSendException(
                        workspaceId, channelId, response.getError() == null ? "unknown" : response.getError());
            }
            return HistoryPage.of(
                    workspaceId,
                    channelId,
                    response.getMessages(),
                    response.isHasMore(),
                    response.getResponseMetadata());
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
                    SlackBackoff.history(time, rateLimitTracker, workspaceId),
                    () -> slack.methods(token)
                            .conversationsReplies(req -> req.channel(channelId)
                                    .ts(threadTs)
                                    .oldest(oldest)
                                    .cursor(cursor == null || cursor.isBlank() ? null : cursor)
                                    .limit(limit)));
            if (!response.isOk()) {
                throw new SlackSendException(
                        workspaceId, channelId, response.getError() == null ? "unknown" : response.getError());
            }
            return HistoryPage.of(
                    workspaceId,
                    channelId,
                    response.getMessages(),
                    response.isHasMore(),
                    response.getResponseMetadata());
        } catch (SlackApiException | IOException e) {
            throw new SlackSendException(workspaceId, channelId, "transport_failure", e);
        }
    }

    /** One page of channel history/replies: the raw SDK messages plus the pagination cursor (null when exhausted). */
    public record HistoryPage(
            List<Message> messages, @Nullable String nextCursor) {

        /** A page as Slack answered it. More to read without a cursor is a truncated read, never the end. */
        static HistoryPage of(
                long workspaceId,
                String channelId,
                @Nullable List<Message> messages,
                boolean hasMore,
                @Nullable ResponseMetadata metadata) {
            List<Message> read = messages == null ? List.of() : messages;
            String cursor = metadata == null ? null : metadata.getNextCursor();
            if (cursor != null && !cursor.isBlank()) {
                return new HistoryPage(read, cursor);
            }
            if (hasMore) {
                throw new SlackSendException(workspaceId, channelId, "history_truncated");
            }
            return new HistoryPage(read, null);
        }
    }

    public void joinPublicChannel(long workspaceId, String channelId) {
        callWrite(
                workspaceId,
                null,
                channelId,
                () -> true,
                null,
                methods -> methods.conversationsJoin(req -> req.channel(channelId)));
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
     * Runs a synchronous Slack Web API call within {@code budget}. The SDK's synchronous {@code MethodsClient} surfaces
     * a 429 as a {@link SlackApiException} and does <em>not</em> retry it (only the async client throttles), so the
     * call is tried again here when the budget has waited Slack's whole {@code Retry-After}
     * ({@link SlackBackoff#awaitRetry}). A 429 that the budget refuses, any other {@link SlackApiException} and an
     * {@link IOException} propagate unchanged and are never tried again, so the caller's error handling is preserved.
     */
    private static <T> T callHonoringRateLimit(SlackBackoff budget, SlackCall<T> call)
            throws SlackApiException, IOException {
        while (true) {
            try {
                return call.call();
            } catch (SlackApiException e) {
                Response response = e.getResponse();
                long retryAfterMs = response == null
                        ? SlackSendException.NOT_RATE_LIMITED
                        : SlackBackoff.retryAfterMillis(response.code(), response.header("Retry-After"));
                if (retryAfterMs == SlackSendException.NOT_RATE_LIMITED || !budget.awaitRetry(retryAfterMs)) {
                    throw e;
                }
            }
        }
    }

    private Optional<String> resolveToken(long workspaceId) {
        return credentialProvider
                .resolve(new IntegrationRef(IntegrationKind.SLACK, workspaceId, null))
                .flatMap(b -> b instanceof BearerToken bt ? Optional.of(bt.token()) : Optional.empty());
    }
}
