package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import java.util.UUID;

/** A durable wake contains only a repair key, never a credential or provider payload. */
public record ProviderResourceRepairRequested(UUID repairId) {}
