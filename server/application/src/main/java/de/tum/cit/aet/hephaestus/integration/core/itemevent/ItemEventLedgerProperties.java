package de.tum.cit.aet.hephaestus.integration.core.itemevent;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// Pending events depend on operator changes, so fast retries only repeat the refusal.
// Retirement bounds the queue lifetime; batch size bounds each sweep's queued work.
/**
 * Bounds of the item event ledger.
 *
 * @param pendingRetryAfter How long the ledger waits before it offers a recorded or waiting item event for review
 *     again.
 * @param pendingLapseAfter Age at which an item event that is still recorded or waiting expires with the reason
 *     PENDING_DEADLINE_EXCEEDED. The age counts from when the event occurred.
 * @param sweepBatchSize Most item events that each step of the ledger sweep handles. The sweep runs every 15 minutes.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.item-event-ledger")
public record ItemEventLedgerProperties(
        @DurationMin(nanos = 1) @DefaultValue("PT1H") Duration pendingRetryAfter,
        @DurationMin(nanos = 1) @DefaultValue("P7D") Duration pendingLapseAfter,
        @Min(1) @DefaultValue("200") int sweepBatchSize,
        @DurationMin(nanos = 1) @DefaultValue("P30D") Duration retainFor) {}
