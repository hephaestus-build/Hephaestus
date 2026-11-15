package de.tum.cit.aet.hephaestus.core.auth.clientsession;

/** What kind of installed client a registered client id belongs to. */
public enum InstalledClientKind {
    /** A Chrome extension, identified by its extension id. */
    BROWSER_EXTENSION,
    /** A third-party MCP client, identified by the URL of its client ID metadata document (ADR 0110). */
    MCP_CLIENT,
}
