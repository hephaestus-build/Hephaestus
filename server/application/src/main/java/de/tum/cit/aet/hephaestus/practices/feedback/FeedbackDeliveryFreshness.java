package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackSendService;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The age of the oldest feedback on the work that may be sent and has no posted copy, as the gauge
 * {@code practice.feedback.oldest.waiting}, from one read each 30 seconds, never one read per scrape. It sees the
 * feedback that never arrives, which the first-delivery histogram cannot. Feedback that waits for a person's approval,
 * or for the developer to open a conversation, does not count: it waits for a person, not for the pipeline.
 *
 * <p>No reading, no claim: the gauge is NaN before the first read and after a read that failed.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("The oldest waiting feedback is one instance-wide age; it reads no content")
class FeedbackDeliveryFreshness {

    private static final Logger log = LoggerFactory.getLogger(FeedbackDeliveryFreshness.class);

    private final FeedbackRepository feedback;
    private final FeedbackSendRepository sends;
    private final AtomicReference<@Nullable Integer> sendAttempts = new AtomicReference<>();
    private final AtomicReference<@Nullable Double> oldest = new AtomicReference<>();

    FeedbackDeliveryFreshness(FeedbackRepository feedback, FeedbackSendRepository sends, MeterRegistry registry) {
        this.feedback = feedback;
        this.sends = sends;
        Gauge.builder(PracticeMetrics.FEEDBACK_OLDEST_WAITING, oldest, FeedbackDeliveryFreshness::seconds)
                .description("Seconds since the oldest feedback on the work that may be sent and has no posted copy;"
                        + " 0 when none waits, NaN before the first read and after a failed read")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(PracticeMetrics.FEEDBACK_SEND_ATTEMPTS, sendAttempts, value -> {
                    Integer current = value.get();
                    return current == null ? Double.NaN : current.doubleValue();
                })
                .description("Most lifetime attempts of feedback sends not DONE; NaN without a successful read")
                .register(registry);
        Gauge.builder(PracticeMetrics.FEEDBACK_SEND_READBACK_LIMIT, () -> FeedbackSendService.MAX_READBACK_ATTEMPTS)
                .description("Claim count at which unresolved feedback readback stops retrying")
                .register(registry);
    }

    @Scheduled(fixedDelay = 30, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    void sample() {
        try {
            Double seconds = feedback.oldestWaitingOnWorkSeconds();
            oldest.set(seconds == null ? 0.0 : seconds);
        } catch (RuntimeException e) {
            oldest.set(null);
            log.warn(
                    "Feedback delivery freshness read failed, reporting no value: errorType={}",
                    e.getClass().getName());
        }
        try {
            sendAttempts.set(sends.maxAttemptsBeforeDone());
        } catch (RuntimeException e) {
            sendAttempts.set(null);
            log.warn(
                    "Feedback send attempts read failed: errorType={}",
                    e.getClass().getName());
        }
    }

    private static double seconds(AtomicReference<@Nullable Double> oldest) {
        Double value = oldest.get();
        return value == null ? Double.NaN : value;
    }
}
