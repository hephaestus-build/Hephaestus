package de.tum.cit.aet.hephaestus.practices.review;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration properties for practice-aware PR review.
 *
 * <p>Binds to the {@code hephaestus.practice-review} prefix in application configuration.
 *
 * @param deliverToMerged     whether to deliver feedback to already-merged PRs
 * @param cooldownMinutes     minimum minutes between reviews for the same PR. 0 disables cooldown.
 * @param maxRequestsPerRequesterPerHour
 *                            how many reviews one person may ask for by hand, per workspace, per hour.
 *                            The only limit here keyed on a person rather than on a piece of work, and
 *                            therefore the only one that catches somebody asking for one review each of
 *                            twenty colleagues' merge requests. 0 disables it.
 * @param reactionSuppression avoid redelivering an observation the developer disputed or marked not applicable.
 *                            Off by default; responses apply to the exact observations bound to feedback.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.practice-review")
public record PracticeReviewProperties(
        @DefaultValue("false") boolean deliverToMerged,
        @Min(0) @DefaultValue("15") int cooldownMinutes,
        @Min(0) @DefaultValue("5") int maxRequestsPerRequesterPerHour,
        @DefaultValue("false") boolean reactionSuppression) {}
