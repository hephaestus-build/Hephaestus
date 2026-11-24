package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.UUID;

/** The private check has ended. Its requester can read its status and any outcomes through the port. */
public record PrivateCheckSettled(UUID reviewId, long workspaceId, long accountId) {}
