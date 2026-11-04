package de.tum.cit.aet.hephaestus.core.auth.export;

/** An export row committed as PENDING; its generation is owed until the publication completes. */
public record AccountExportRequested(long exportId, long accountId) {}
