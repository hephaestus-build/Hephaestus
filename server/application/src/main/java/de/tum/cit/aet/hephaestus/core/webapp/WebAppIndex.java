package de.tum.cit.aet.hephaestus.core.webapp;

import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.release.ReleaseProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicUsagePolicy;
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
 * <p>Every value of the record but one is fixed for the life of the process. The exception is whether Hephaestus in
 * numbers is on, which an instance admin can change on any replica. So the page is rendered once for each value of that
 * switch, and each request serves the page of the switch's last known value ({@link
 * PublicUsagePolicy#lastKnownAllowed()}), so a page load never waits for the database. The page is never stored
 * ({@code no-store}), so a load after the next background read carries a change. The record is JSON inside a {@code <script>} element, so
 * {@code <}, {@code >} and {@code &} are escaped: no value can end the element.
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

    private final @Nullable String htmlWithPublicUsage;

    private final PublicUsagePolicy publicUsage;

    private final Set<String> files = new HashSet<>();

    WebAppIndex(
            WebAppProperties properties,
            ResourcePatternResolver resources,
            ObjectMapper objectMapper,
            PublicUsagePolicy publicUsage,
            AuthProperties auth,
            WorkspaceSubdomainProperties workspaceSubdomains,
            ReleaseProperties release,
            @Value("${spring.application.version}") String version,
            @Value("${hephaestus.webapp.url}") String clientUrl,
            @Value("${hephaestus.features.flags.browser-fault-reports:false}") boolean browserFaultReports)
            throws IOException {
        this.publicUsage = publicUsage;
        Resource index = resources.getResource(properties.location() + "index.html");
        if (!index.exists()) {
            log.info("No web app at {}: this server serves the API only", properties.location());
            this.html = null;
            this.htmlWithPublicUsage = null;
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
                browserFaultReports,
                false);
        String template = index.getContentAsString(StandardCharsets.UTF_8);
        requireNoncePlaceholder(template);
        int head = template.indexOf("</head>");
        if (head < 0) {
            throw new IllegalStateException("The web app's index.html has no </head>");
        }
        this.html = render(template, head, objectMapper, config);
        this.htmlWithPublicUsage = render(template, head, objectMapper, config.withPublicUsage());
    }

    private static String render(String template, int head, ObjectMapper objectMapper, WebAppConfig config) {
        String record = objectMapper
                .writeValueAsString(config)
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026");
        return template.substring(0, head)
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
        if (html == null || htmlWithPublicUsage == null) {
            throw new IllegalStateException("This server has no web app");
        }
        return (publicUsage.lastKnownAllowed() ? htmlWithPublicUsage : html).replace(NONCE_PLACEHOLDER, nonce);
    }

    /** Whether the web app has a file at this path, such as {@code /favicon.png}. */
    boolean hasFile(String path) {
        return files.contains(path);
    }
}
