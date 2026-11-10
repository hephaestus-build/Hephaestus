package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.UUID;

public record ReviewCancellationRequested(UUID reviewId, long workspaceId) {}
