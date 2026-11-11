package de.tum.cit.aet.hephaestus.workspace;

/** A workspace's public activity page settings as an administrator sets them. */
public record PublicActivityChoice(boolean enabled, boolean allowSearchEngines, boolean contributorsWithoutAccount) {}
