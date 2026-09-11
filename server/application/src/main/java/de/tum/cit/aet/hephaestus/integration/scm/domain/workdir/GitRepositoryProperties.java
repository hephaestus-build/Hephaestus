package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Local repository mirrors and the trusted image that operates on them.
 *
 * @param image the git-preparation image every native Git operation runs in
 */
@ConfigurationProperties(prefix = "hephaestus.git")
@Validated
public record GitRepositoryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("2") @Min(1) int maxConcurrentIngestions,
        @NotBlank String image) {}
