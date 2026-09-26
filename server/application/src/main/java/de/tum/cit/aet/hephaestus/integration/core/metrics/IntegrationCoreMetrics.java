package de.tum.cit.aet.hephaestus.integration.core.metrics;

public final class IntegrationCoreMetrics {

    public static final String CREDENTIAL_ROTATION_FAILURES = "integration.credential.rotation.failures";
    public static final String INTEGRATION_CONSUMER_NAK = "integration.consumer.nak";
    public static final String INTEGRATION_CONSUMER_POISON = "integration.consumer.poison";
    public static final String INTEGRATION_SYNC_PUSH_MESSAGES = "integration.sync.push.messages";
    public static final String INTEGRATION_SYNC_SSE_EVENTS = "integration.sync.sse.events";
    public static final String INTEGRATION_SYNC_SSE_SUBSCRIBERS = "integration.sync.sse.subscribers";
    public static final String INTEGRATION_SYNC_SSE_SUBSCRIPTIONS = "integration.sync.sse.subscriptions";
    public static final String OAUTH_STATE_NONCE_PRUNED = "oauth.state.nonce.pruned";
    public static final String PRACTICE_REVIEW_REFUSED = "practice.review.refused";
    public static final String WEBHOOK_PUBLISH = "webhook.publish";
    public static final String WEBHOOK_PUBLISH_RETRY = "webhook.publish.retry";
    public static final String WEBHOOK_REJECTED = "webhook.rejected";
    public static final String WEBHOOK_STREAM_BYTES = "webhook.stream.bytes";
    public static final String WEBHOOK_STREAM_BYTES_LIMIT = "webhook.stream.bytes.limit";
    public static final String WEBHOOK_STREAM_BYTES_UTILIZATION = "webhook.stream.bytes.utilization";
    public static final String WEBHOOK_STREAM_CONSUMER_ACK_PENDING = "webhook.stream.consumer.ack.pending";
    public static final String WEBHOOK_STREAM_CONSUMER_PENDING = "webhook.stream.consumer.pending";
    public static final String WEBHOOK_STREAM_CONSUMERS = "webhook.stream.consumers";
    public static final String WEBHOOK_STREAM_CONSUMERS_WITHOUT_PULL_REQUESTS =
            "webhook.stream.consumers.without.pull.requests";
    public static final String WEBHOOK_STREAM_MESSAGES = "webhook.stream.messages";
    public static final String WEBHOOK_STREAM_OLDEST_MESSAGE_AGE = "webhook.stream.oldest.message.age";
    public static final String WEBHOOK_STREAM_POLL_AGE = "webhook.stream.poll.age";

    public static final String REVIEW_OCCASIONS = "practice.review.occasions";

    private IntegrationCoreMetrics() {}
}
