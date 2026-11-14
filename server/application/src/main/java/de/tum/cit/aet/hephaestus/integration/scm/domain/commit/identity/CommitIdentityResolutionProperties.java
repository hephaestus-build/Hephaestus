package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the job that corrects commit links from the provider's answer.
 *
 * @param enabled whether the job runs
 * @param batchSize how many emails one run asks the provider about
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.commit-identity-resolution")
public record CommitIdentityResolutionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("50") @Min(1) @Max(500) int batchSize) {}
