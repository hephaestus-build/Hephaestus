package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

/** The namespace in which the sandbox owner creates claims. Templates own Pod settings. */
@Validated
@Profile("!specs & !cds-training")
@ConditionalOnAgentRuntime
@ConfigurationProperties(prefix = "hephaestus.sandbox.kubernetes", ignoreUnknownFields = false)
public record KubernetesSandboxProperties(
        @DefaultValue("") @NotBlank @Pattern(regexp = "[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?")
        String namespace) {}
