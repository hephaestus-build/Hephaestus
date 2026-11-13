/**
 * Unified message-handler registry.
 *
 * <p>One {@link de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey} → one
 * {@link de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler}, across
 * every {@link de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind}. Vendor-specific
 * handlers live under {@code integration/<kind>/handler/...} and are auto-wired into the
 * registry via Spring DI.
 *
 * <p>This package only ships the contract and the registry. The dispatcher that maps provider inbox
 * subjects to keys lives in {@code integration/consumer/}.
 */
@org.springframework.modulith.NamedInterface("handler")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.core.handler;
