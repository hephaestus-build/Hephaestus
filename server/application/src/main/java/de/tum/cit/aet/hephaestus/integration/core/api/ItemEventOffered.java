package de.tum.cit.aet.hephaestus.integration.core.api;

/** A wake for a recorded occasion. The ledger stores when to offer it again if the wake is lost. */
public record ItemEventOffered(ItemEventRecorded event) {}
