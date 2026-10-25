package de.tum.cit.aet.hephaestus.practices.review;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
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
 * @param deliverToMerged     Posts feedback on pull requests that are already merged. Other destinations do not
 *                            change.
 * @param cooldownMinutes     minimum minutes between reviews for the same PR. 0 disables cooldown.
 * @param maxRequestsPerRequesterPerHour
 *                            manual review requests per person, workspace and hour. 0 disables the limit.
 * @param samplingTemperature requested model sampling temperature, or null for the provider default.
 * @param practiceModelCalls  The model calls that one practice gets in a review turn. A turn gets this number for
 *                            each practice, and at least the number for three practices.
 * @param practiceOutputTokens
 *                            the output tokens one practice is owed in a review turn, counted the same way.
 * @param practiceConcurrency maximum concurrent practice sessions in an item review. Scans remain serial.
 * @param directModelTokensPerAttempt The input and output tokens that the direct model calls of the practice programs
 *                                    of one review attempt may spend together. The calls of the review session itself
 *                                    do not count. The runner holds the programs to it, and the proxy refuses further
 *                                    direct calls.
 * @param directModelSearchUnitsPerAttempt The search units that the direct reranking calls of one review attempt may
 *                                         bill, for providers such as Cohere that bill search units. The proxy counts
 *                                         them and refuses further direct calls.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.practice-review")
public record PracticeReviewProperties(
        @DefaultValue("false") boolean deliverToMerged,
        @Min(0) @DefaultValue("15") int cooldownMinutes,
        @Min(0) @DefaultValue("5") int maxRequestsPerRequesterPerHour,
        @Nullable @DecimalMin("0.0") @DecimalMax("2.0") Double samplingTemperature,
        @Min(1) @DefaultValue("12") int practiceModelCalls,
        @Min(1000) @DefaultValue("16000") int practiceOutputTokens,
        @Min(1) @Max(32) @DefaultValue("3") int practiceConcurrency,
        @Min(1) @DefaultValue("200000") long directModelTokensPerAttempt,
        @Min(1) @DefaultValue("1000") long directModelSearchUnitsPerAttempt) {}
