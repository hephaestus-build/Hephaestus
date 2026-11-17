package de.tum.cit.aet.hephaestus.core.webapp;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The requests that read the web app: a GET or HEAD outside every root that the server answers itself, and the two
 * documents the web app publishes under {@code /.well-known}. They are public, because the page and its files hold no
 * data: every value the page shows comes from the API, which checks each request.
 *
 * <p>The patterns match the decoded path, as Spring MVC does, so an encoded root such as {@code /%61pi} is still the
 * API.
 */
public final class WebAppRequests {

    /** The roots that the server answers itself. None of them is the web app, and none falls back to its page. */
    public static final List<String> SERVER_ROOTS = List.of(
            "/api",
            "/webhooks",
            "/internal",
            "/actuator",
            "/v3",
            "/swagger-ui",
            "/error",
            "/livez",
            "/readyz",
            "/.well-known");

    /** The documents under {@code /.well-known} that belong to the web app ({@link WebAppDiscoveryController}). */
    static final List<String> DOCUMENTS = List.of("/.well-known/hephaestus.json", "/.well-known/security.txt");

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    private static final RequestMatcher READ =
            new OrRequestMatcher(PATHS.matcher(HttpMethod.GET, "/**"), PATHS.matcher(HttpMethod.HEAD, "/**"));

    private static final RequestMatcher SERVER = new OrRequestMatcher(SERVER_ROOTS.stream()
            .flatMap(root -> Stream.of(PATHS.matcher(root), PATHS.matcher(root + "/**")))
            .toList());

    public static final RequestMatcher MATCHER = new AndRequestMatcher(
            READ,
            new OrRequestMatcher(
                    new NegatedRequestMatcher(SERVER),
                    new OrRequestMatcher(DOCUMENTS.stream()
                            .<RequestMatcher>map(PATHS::matcher)
                            .toList())));

    private WebAppRequests() {}
}
