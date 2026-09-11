package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.agent.config.AgentBindingLimits;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.ChatMessage;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Interrupts and accounts mentor turns abandoned by a crashed process.
 *
 * <p>Each turn is accounted independently so one failure cannot roll back previously accounted turns
 * or leave their in-flight rows blocking the thread.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Sweeps stuck rows by created_at; not a tenant data accessor")
public class MentorInFlightReaper {

    private static final Logger log = LoggerFactory.getLogger(MentorInFlightReaper.class);
    /** Sandbox startup, streaming finalisation and this sweep's own cron/lock delay. */
    private static final Duration OVERHEAD_ALLOWANCE = Duration.ofMinutes(10);

    /**
     * A turn reaped while still running is billed as abandoned and loses its thread to the in-flight
     * index, so the window must clear the longest turn a binding can produce. Derived from the ceiling
     * the binding API enforces, so raising that ceiling raises this floor with it.
     */
    private static final Duration MINIMUM_SAFE_WINDOW =
            Duration.ofSeconds(AgentBindingLimits.MAX_TIMEOUT_SECONDS).plus(OVERHEAD_ALLOWANCE);

    private final ChatMessageRepository chatMessageRepository;
    private final MentorInFlightAccounting accounting;
    private final MeterRegistry meterRegistry;

    private final Duration window;

    public MentorInFlightReaper(
            ChatMessageRepository chatMessageRepository,
            MentorInFlightAccounting accounting,
            MeterRegistry meterRegistry,
            @Value("${hephaestus.mentor.in-flight-reaper.window:#{null}}") @Nullable Duration window) {
        this.chatMessageRepository = chatMessageRepository;
        this.accounting = accounting;
        this.meterRegistry = meterRegistry;
        this.window = safeWindow(window);
    }

    @Scheduled(cron = "${hephaestus.mentor.in-flight-reaper.cron:0 */2 * * * *}")
    @SchedulerLock(name = "mentor-in-flight-reaper", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void reap() {
        List<UUID> stale = chatMessageRepository
                .findStaleInFlightForAccounting(Instant.now().minus(window))
                .stream()
                .map(ChatMessage::getId)
                .toList();
        int billed = 0;
        int failed = 0;
        for (UUID messageId : stale) {
            try {
                if (accounting.account(messageId)) {
                    billed++;
                }
            } catch (RuntimeException e) {
                failed++;
                // Never rethrown: one turn losing its write must not stop the turns behind it.
                log.warn(
                        "Mentor in-flight reaper could not account turn {}; leaving it for the next tick",
                        messageId,
                        e);
                meterRegistry
                        .counter(AgentMetrics.MENTOR_IN_FLIGHT_REAPER_FAILURE)
                        .increment();
            }
        }
        if (!stale.isEmpty())
            log.info(
                    "Mentor in-flight reaper accounted {} stuck row(s); {} billed from proxy-recorded usage, {} failed",
                    stale.size(),
                    billed,
                    failed);
    }

    private static Duration safeWindow(@Nullable Duration configuredWindow) {
        if (configuredWindow == null) {
            return MINIMUM_SAFE_WINDOW;
        }
        if (configuredWindow.compareTo(MINIMUM_SAFE_WINDOW) < 0) {
            log.warn(
                    "Mentor in-flight reaper window {} is unsafe for configured turns; using {}",
                    configuredWindow,
                    MINIMUM_SAFE_WINDOW);
            return MINIMUM_SAFE_WINDOW;
        }
        return configuredWindow;
    }

    Duration window() {
        return window;
    }
}
