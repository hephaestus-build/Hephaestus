package de.tum.cit.aet.hephaestus.integration.core.events;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;

/** The stored bearer credential changed; contains no credential material. */
public record ConnectionCredentialsReplacedEvent(long connectionId, long workspaceId, IntegrationKind kind) {}
