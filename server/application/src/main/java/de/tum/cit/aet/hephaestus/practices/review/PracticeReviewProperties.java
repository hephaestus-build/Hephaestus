package de.tum.cit.aet.hephaestus.practices.review;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration properties for practice-aware PR review.
 *
 * <p>Binds to the {@code hephaestus.practice-review} prefix in application configuration.
 *
 * @param deliverToMerged     whether to post feedback on already-merged PRs; other channels are unaffected
 * @param cooldownMinutes     minimum minutes between reviews for the same PR. 0 disables cooldown.
 * @param maxRequestsPerRequesterPerHour
 *                            manual review requests per person, workspace and hour. 0 disables the limit.
 * @param reactionSuppression avoid redelivering an observation the developer disputed or marked not applicable.
 *                            Off by default; responses apply to the exact observations bound to feedback.
 * @param samplingTemperature requested model sampling temperature, or null for the provider default.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.practice-review")
public record PracticeReviewProperties(
        @DefaultValue("false") boolean deliverToMerged,
        @Min(0) @DefaultValue("15") int cooldownMinutes,
        @Min(0) @DefaultValue("5") int maxRequestsPerRequesterPerHour,
        @DefaultValue("false") boolean reactionSuppression,
        @Nullable @DecimalMin("0.0") @DecimalMax("2.0") Double samplingTemperature) {}
