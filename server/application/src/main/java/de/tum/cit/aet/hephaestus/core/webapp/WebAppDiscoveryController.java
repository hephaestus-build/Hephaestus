package de.tum.cit.aet.hephaestus.core.webapp;

import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Hidden;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two documents that the deployment publishes at the host root (RFC 8615): the instance metadata, which the web
 * app and the browser extension read, and the security contact (RFC 9116). Neither is part of the API.
 */
@Hidden
@RestController
@ConditionalOnServerRole
class WebAppDiscoveryController {

    /** The version of a build from source ({@code server/build.gradle.kts}), which is no release. */
    private static final String DEVELOPMENT_VERSION = "0.0.0-development";

    /** A single https origin, the only kind of host that a {@code Canonical} line may name (RFC 9116 § 2.5.2). */
    private static final Pattern SINGLE_HTTPS_ORIGIN = Pattern.compile("https://[^\\s/]+/?");

    private final WebAppProperties properties;
    private final WorkspaceSubdomainProperties workspaceSubdomains;
    private final String version;
    private final String clientUrl;
    private final Clock clock;

    WebAppDiscoveryController(
            WebAppProperties properties,
            WorkspaceSubdomainProperties workspaceSubdomains,
            @Value("${spring.application.version}") String version,
            @Value("${hephaestus.webapp.url}") String clientUrl,
            Clock clock) {
        this.properties = properties;
        this.workspaceSubdomains = workspaceSubdomains;
        // The document names the release that this server runs, and none for a build from source. The browser extension
        // refuses a server whose release is older than it supports, and 0.0.0 would read as one.
        this.version = DEVELOPMENT_VERSION.equals(version) ? "" : version;
        this.clientUrl = clientUrl;
        this.clock = clock;
    }

    record InstanceMetadata(String apexOrigin, String baseDomain, boolean workspaceSubdomainsEnabled, String version) {}

    @GetMapping(value = "/.well-known/hephaestus.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    ResponseEntity<InstanceMetadata> instanceMetadata() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new InstanceMetadata(
                        clientUrl, workspaceSubdomains.baseDomain(), workspaceSubdomains.enabled(), version));
    }

    /**
     * The contact names the operator of this deployment, so without one there is no file: a report about an
     * installation goes to whoever runs it. Workspace hosts serve the file on many hosts, so only a single https
     * origin gets a {@code Canonical} line. The expiry stays under a year, as RFC 9116 § 2.5.5 recommends.
     */
    @GetMapping(value = "/.well-known/security.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("permitAll()")
    ResponseEntity<String> securityTxt() {
        if (properties.securityContact().isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var body = new StringBuilder("Contact: ")
                .append(properties.securityContact())
                .append('\n');
        if (!workspaceSubdomains.enabled()
                && SINGLE_HTTPS_ORIGIN.matcher(clientUrl).matches()) {
            body.append("Canonical: ").append(clientUrl.replaceAll("/$", "")).append("/.well-known/security.txt\n");
        }
        Instant expires = Instant.now(clock).plus(Duration.ofDays(364)).truncatedTo(ChronoUnit.SECONDS);
        body.append("Expires: ").append(expires).append('\n');
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(body.toString());
    }
}
