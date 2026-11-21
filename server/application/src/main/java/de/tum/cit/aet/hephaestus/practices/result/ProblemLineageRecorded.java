package de.tum.cit.aet.hephaestus.practices.result;

import java.util.UUID;

/** The seal wrote problem lineage rows for a result. Listeners run in the seal's transaction. */
public record ProblemLineageRecorded(long workspaceId, UUID resultId) {}
