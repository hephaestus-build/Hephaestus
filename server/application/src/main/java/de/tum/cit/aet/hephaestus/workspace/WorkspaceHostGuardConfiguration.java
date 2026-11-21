package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.validation.WorkspaceSlugValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * The host rules of workspace subdomains (ADR 0053), decided before any other filter and without a lookup of a
 * workspace or its membership:
 *
 * <ul>
 *   <li>A host under the base domain whose label no workspace can have is not found.</li>
 *   <li>A workspace host serves only the web app: the API, sign-in, OAuth discovery and the server's other roots stay
 *       on the apex.</li>
 *   <li>On the apex, {@code /w/<slug>/…} moves permanently to the workspace's own host.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.workspace.subdomains.enabled")
class WorkspaceHostGuardConfiguration {

    private static final Pattern APEX_ONLY = Pattern.compile(
            "/(?:api|auth|oauth|oauth2|login|consent|webhooks|actuator|\\.well-known/oauth-[^/]+)(?:/.*)?");

    private static final Pattern WORKSPACE_PATH = Pattern.compile("/w/([^/]+)(/.*)?");

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> workspaceHostGuardFilter(WorkspaceSubdomainProperties properties) {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(new Filter(properties.baseDomain()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    static final class Filter extends OncePerRequestFilter {

        private final String baseDomain;

        Filter(String baseDomain) {
            this.baseDomain = baseDomain;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String host = request.getServerName().toLowerCase(Locale.ROOT);
            // The raw path keeps its encoding for the redirect; the rules read the decoded path, as the controllers do.
            String path =
                    request.getRequestURI().substring(request.getContextPath().length());
            String decoded = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
            if (host.endsWith("." + baseDomain)) {
                String label = host.substring(0, host.length() - baseDomain.length() - 1);
                if (!WorkspaceSlugValidator.isAssignable(label)
                        || APEX_ONLY.matcher(decoded).matches()) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
            } else if (host.equals(baseDomain)
                    && ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))) {
                var workspacePath = WORKSPACE_PATH.matcher(path);
                if (workspacePath.matches() && WorkspaceSlugValidator.isAssignable(workspacePath.group(1))) {
                    String tail = workspacePath.group(2) == null ? "/" : workspacePath.group(2);
                    String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
                    response.setStatus(HttpServletResponse.SC_PERMANENT_REDIRECT);
                    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                    response.setHeader(
                            HttpHeaders.LOCATION,
                            "https://" + workspacePath.group(1) + "." + baseDomain + tail + query);
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }
}
