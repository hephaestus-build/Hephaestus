package de.tum.cit.aet.hephaestus.integration.core.spi;
/** A provider resolution or reopening hands an owned thread to the people on its work. */
public record WorkFeedbackThreadChangedEvent(long workspaceId, long itemId, String threadRef) {}
