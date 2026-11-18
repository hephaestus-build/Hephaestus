package de.tum.cit.aet.hephaestus.practices.review;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Trials of practices (ADR 0118 decisions 8 and 9).
 *
 * @param retention how long a trial keeps its report after the trial ends. The report holds text cited from
 *     someone's work, so it is cleared then.
 * @param maxActive how many trials of one workspace can be queued or running at once. A trial runs in the same batch
 *     band as a requested review, so this bounds how much of the workspace's share trials take.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.practices.trials")
public record PracticeTrialProperties(
        @DurationMin(hours = 1) @DefaultValue("P30D") Duration retention,
        @Min(1) @DefaultValue("1") int maxActive) {}
