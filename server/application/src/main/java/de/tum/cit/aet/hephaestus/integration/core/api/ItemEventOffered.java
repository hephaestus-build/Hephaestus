package de.tum.cit.aet.hephaestus.integration.core.api;

import java.util.UUID;

/** A wake for a recorded occasion. The ledger stores when to offer it again if the wake is lost. */
public record ItemEventOffered(long workspaceId, UUID eventId, long itemId) {}
