package de.tum.cit.aet.hephaestus.integration.submission;

/** How the bytes of a snapshot reached the server. */
public enum TeamEntryRoute {
    PLUGIN_CAPTURE,
    EDITOR_CAPTURE,
    LINK_IMPORT,
    FILE_UPLOAD,
    PINNED_COMMIT
}
