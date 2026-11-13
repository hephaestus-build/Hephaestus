/**
 * Provider inbox consumer wiring for the unified integration framework.
 *
 * <p>Routes provider inbox messages from per-kind subject namespaces ({@code github.*},
 * {@code gitlab.*}, {@code slack.*}) to the right
 * {@link de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler} via the
 * vendor-agnostic registry. Subject parsing is delegated to the per-kind
 * {@link de.tum.cit.aet.hephaestus.integration.core.spi.SubjectParser} implementations.
 */
@org.springframework.modulith.NamedInterface("consumer")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.core.consumer;
