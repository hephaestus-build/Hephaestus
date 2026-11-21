package de.tum.cit.aet.hephaestus.core.webapp;

import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.release.ReleaseProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The built web app: its files, and {@code index.html} with the config record of this deployment.
 *
 * <p>Every value of the record is fixed for the life of the process, so the page is rendered once. The record is
 * JSON inside a {@code <script>} element, so {@code <}, {@code >} and {@code &} are escaped: no value can end the
 * element.
 *
 * <p>Only the script nonce changes per request. Vite's {@code html.cspNonce} writes {@link #NONCE_PLACEHOLDER} on
 * every script, style and preload tag of the build, and each request replaces it with a new nonce that the page's
 * content security policy names ({@code SecurityHeaders}).
 */
@Component
@ConditionalOnServerRole
public class WebAppIndex {

    private static final Logger log = LoggerFactory.getLogger(WebAppIndex.class);

    /** The nonce that {@code webapp/vite.config.ts} builds into the page, which no request ever sends. */
    static final String NONCE_PLACEHOLDER = "HEPHAESTUS_CSP_NONCE";

    private static final SecureRandom NONCES = new SecureRandom();

    private final @Nullable String html;

    private final Set<String> files = new HashSet<>();

    WebAppIndex(
            WebAppProperties properties,
            ResourcePatternResolver resources,
            ObjectMapper objectMapper,
            AuthProperties auth,
            WorkspaceSubdomainProperties workspaceSubdomains,
            ReleaseProperties release,
            @Value("${spring.application.version}") String version,
            @Value("${hephaestus.webapp.url}") String clientUrl,
            @Value("${hephaestus.features.flags.browser-fault-reports:false}") boolean browserFaultReports)
            throws IOException {
        Resource index = resources.getResource(properties.location() + "index.html");
        if (!index.exists()) {
            log.info("No web app at {}: this server serves the API only", properties.location());
            this.html = null;
            return;
        }
        String root = index.getURL().toString();
        root = root.substring(0, root.length() - "index.html".length());
        for (Resource file : resources.getResources(properties.location() + "**/*")) {
            if (file.isReadable()) {
                files.add("/" + file.getURL().toString().substring(root.length()));
            }
        }
        var config = new WebAppConfig(
                version,
                clientUrl,
                auth.apiBaseUrl(),
                new WebAppConfig.WorkspaceSubdomains(workspaceSubdomains.enabled(), workspaceSubdomains.baseDomain()),
                auth.csrfCookieName(),
                release.environment(),
                release.commit(),
                browserFaultReports);
        String record = objectMapper
                .writeValueAsString(config)
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026");
        String template = index.getContentAsString(StandardCharsets.UTF_8);
        requireNoncePlaceholder(template);
        int head = template.indexOf("</head>");
        if (head < 0) {
            throw new IllegalStateException("The web app's index.html has no </head>");
        }
        this.html = template.substring(0, head)
                + "<script type=\"application/json\" id=\"hephaestus-config\">"
                + record
                + "</script>"
                + template.substring(head);
    }

    /**
     * A page built without the placeholder would carry no nonce, and the policy would block every script on it. A
     * {@code webapp/dist} built before the placeholder existed is the usual cause.
     */
    static void requireNoncePlaceholder(String template) {
        if (!template.contains("nonce=\"" + NONCE_PLACEHOLDER + "\"")) {
            throw new IllegalStateException(
                    "The web app's index.html carries no script nonce placeholder; rebuild it with `vp run build:webapp`");
        }
    }

    /** Whether this server has a web app to serve. */
    boolean present() {
        return html != null;
    }

    /** A new script nonce: 128 random bits, as the CSP specification asks of a nonce. */
    static String newNonce() {
        byte[] bytes = new byte[16];
        NONCES.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** The page whose scripts carry this nonce. */
    String html(String nonce) {
        if (html == null) {
            throw new IllegalStateException("This server has no web app");
        }
        return html.replace(NONCE_PLACEHOLDER, nonce);
    }

    /** Whether the web app has a file at this path, such as {@code /favicon.png}. */
    boolean hasFile(String path) {
        return files.contains(path);
    }
}
