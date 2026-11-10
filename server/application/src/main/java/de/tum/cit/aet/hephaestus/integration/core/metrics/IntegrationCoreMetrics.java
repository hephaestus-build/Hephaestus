package de.tum.cit.aet.hephaestus.integration.core.metrics;

public final class IntegrationCoreMetrics {

    public static final String CREDENTIAL_ROTATION_FAILURES = "integration.credential.rotation.failures";
    public static final String INTEGRATION_CONSUMER_BACKLOG = "integration.consumer.backlog";
    public static final String INTEGRATION_CONSUMER_BACKLOG_SAMPLER_FAILURES =
            "integration.consumer.backlog.sampler.failures";
    public static final String INTEGRATION_CONSUMER_OLDEST_DUE_SECONDS = "integration.consumer.oldest.due.seconds";
    public static final String INTEGRATION_CONSUMER_RETRY = "integration.consumer.retry";
    public static final String INTEGRATION_CONSUMER_POISON = "integration.consumer.poison";
    public static final String INBOX_RETENTION_FAILURES = "integration.consumer.inbox.retention.failures";
    public static final String INTEGRATION_MESSAGE_LAG = "integration.message.lag";
    public static final String INTEGRATION_MESSAGE_PROCESS_DURATION = "integration.message.process.duration";
    public static final String INTEGRATION_PROVIDER_TEARDOWN = "integration.provider.teardown";
    public static final String INTEGRATION_SYNC_PUSH_MESSAGES = "integration.sync.push.messages";
    public static final String INTEGRATION_SYNC_SSE_EVENTS = "integration.sync.sse.events";
    public static final String INTEGRATION_SYNC_SSE_SUBSCRIBERS = "integration.sync.sse.subscribers";
    public static final String INTEGRATION_SYNC_SSE_SUBSCRIPTIONS = "integration.sync.sse.subscriptions";
    public static final String PRACTICE_REVIEW_REFUSED = "practice.review.refused";
    public static final String OAUTH_STATE_NONCE_PRUNED = "oauth.state.nonce.pruned";
    public static final String WEBHOOK_PUBLISH = "webhook.publish";
    public static final String WEBHOOK_REJECTED = "webhook.rejected";

    private IntegrationCoreMetrics() {}
}
