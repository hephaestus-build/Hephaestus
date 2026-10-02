package de.tum.cit.aet.hephaestus.core.auth.clientsession;

/**
 * One registered installed client.
 *
 * @param kind        what the client is
 * @param clientId    the client's stable identity; a Chrome extension id for {@link InstalledClientKind#BROWSER_EXTENSION}
 * @param redirectUri the one callback a sign-in may return to, compared by exact string equality
 * @param origin      the one browser origin its requests come from, appended to the CORS allowlist
 */
public record InstalledClient(InstalledClientKind kind, String clientId, String redirectUri, String origin) {}
