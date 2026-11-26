package de.tum.cit.aet.hephaestus.mentor.spi;

/** The canonical provider message an event is about, at one revision. */
public record MessageRef(String nativeMessageId, String revision) {}
