package de.tum.cit.aet.hephaestus.core.webapp;

import java.util.List;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The requests that read the web app: a GET or HEAD outside the roots where the server keeps its API, its webhooks,
 * its sandbox connector and its health endpoints. They are public, because the page and its files hold no data:
 * every value the page shows comes from the API, which checks each request.
 */
public final class WebAppRequests {

    private static final List<String> SERVER_ROOTS = List.of("/api", "/webhooks", "/internal", "/actuator");

    public static final RequestMatcher MATCHER = request -> {
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return SERVER_ROOTS.stream().noneMatch(root -> path.equals(root) || path.startsWith(root + "/"));
    };

    private WebAppRequests() {}
}
