package de.tum.cit.aet.hephaestus.practices.review;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration properties for practice-aware PR review.
 *
 * <p>Binds to the {@code hephaestus.practice-review} prefix in application configuration.
 *
 * @param deliverToMerged     Posts feedback on work that is already merged or closed. Without it, that feedback stops
 *                            as ITEM_MERGED or ITEM_CLOSED. The practice page and the conversation do not change.
 * @param cooldownMinutes     minimum minutes between reviews for the same PR. 0 disables cooldown.
 * @param maxRequestsPerRequesterPerHour
 *                            manual review requests per person, workspace and hour. 0 disables the limit.
 * @param samplingTemperature requested model sampling temperature, or null for the provider default.
 * @param practiceModelCalls  The model calls that one practice gets in a review.
 * @param practiceOutputTokens The output tokens that one practice gets in a review.
 * @param compositionTimeout  the longest a review's composition run composes its feedback after the review is
 *                            measured. A destination still open then is withheld as not composed.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.practice-review")
public record PracticeReviewProperties(
        @DefaultValue("false") boolean deliverToMerged,
        @Min(0) @DefaultValue("15") int cooldownMinutes,
        @Min(0) @DefaultValue("5") int maxRequestsPerRequesterPerHour,
        @Nullable @DecimalMin("0.0") @DecimalMax("2.0") Double samplingTemperature,
        @Min(1) @DefaultValue("36") int practiceModelCalls,
        @Min(1000) @DefaultValue("48000") int practiceOutputTokens,
        @DurationMin(minutes = 1) @DefaultValue("PT10M") Duration compositionTimeout) {}
