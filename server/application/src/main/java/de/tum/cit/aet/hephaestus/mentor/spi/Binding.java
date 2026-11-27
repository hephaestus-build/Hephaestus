package de.tum.cit.aet.hephaestus.mentor.spi;

/** A workspace's verified provider binding. */
public record Binding(long workspaceId, long connectionId, String teamId) {}
