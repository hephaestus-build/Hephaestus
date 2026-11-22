package de.tum.cit.aet.hephaestus.practices.result;

import java.util.UUID;

/** The seal wrote problem lineage rows for a result. The event publication registry delivers it after the commit. */
public record ProblemLineageRecorded(long workspaceId, UUID resultId) {}
