package de.tum.cit.aet.hephaestus.agent.runtime;

import de.tum.cit.aet.hephaestus.agent.sandbox.AgentImageReferenceGuard;
import de.tum.cit.aet.hephaestus.agent.sandbox.ImagePullPolicy;
import de.tum.cit.aet.hephaestus.core.runtime.ReceiverComponent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The agent image this server runs its runners in.
 *
 * <p>{@code reference} carries no {@code @DefaultValue} on purpose. A compiled-in reference cannot
 * know which build the deployment is running, so any value here is a guess — and a guess that names
 * a release channel resolves to some other release's image. {@code application.yml} derives the
 * fallback from {@code spring.application.version}, the tag the deploy already chose for the server
 * image, and {@link AgentImageReferenceGuard} refuses what
 * cannot name a matched build. See ADR 0031.
 */
@ConfigurationProperties(prefix = "hephaestus.agent.image")
@ReceiverComponent
public record AgentImageProperties(
        String reference, @DefaultValue("IF_NOT_PRESENT") ImagePullPolicy pullPolicy) {}
