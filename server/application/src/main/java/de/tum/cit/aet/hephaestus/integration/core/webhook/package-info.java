/**
 * Inbound webhook reception — shared receive-side substrate. A single
 * {@code POST /webhooks/{kind}} entry point selects the per-kind
 * {@link de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier} and
 * {@link de.tum.cit.aet.hephaestus.integration.core.spi.SubjectKeyDeriver} via
 * {@link de.tum.cit.aet.hephaestus.integration.core.webhook.IntegrationKindRouting};
 * verified envelopes are published to provider inbox provider inbox.
 */
@org.springframework.modulith.NamedInterface("webhook")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.integration.core.webhook;
