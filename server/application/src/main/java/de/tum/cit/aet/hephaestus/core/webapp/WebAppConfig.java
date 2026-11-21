package de.tum.cit.aet.hephaestus.core.webapp;

/**
 * The config record that {@code index.html} carries as {@code <script type="application/json"
 * id="hephaestus-config">}. The web app reads it before it renders, so it needs no request to learn where it runs.
 *
 * @param version               The release version without a leading {@code v}.
 * @param clientUrl             The public origin of the web app, the apex when workspace subdomains are on.
 * @param serverUrl             The base URL of the API.
 * @param workspaceSubdomains   Whether workspaces have their own hosts, and under which domain.
 * @param xsrfCookieName        The name of the CSRF double-submit cookie.
 * @param deploymentEnvironment The name of this deployment, such as {@code production}.
 * @param commit                The source commit of the release, or empty.
 * @param browserFaultReports   Whether the web app reports the faults that readers meet.
 */
record WebAppConfig(
        String version,
        String clientUrl,
        String serverUrl,
        WorkspaceSubdomains workspaceSubdomains,
        String xsrfCookieName,
        String deploymentEnvironment,
        String commit,
        boolean browserFaultReports) {

    record WorkspaceSubdomains(boolean enabled, String baseDomain) {}
}
