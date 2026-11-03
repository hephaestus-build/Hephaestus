package de.tum.cit.aet.hephaestus.core.auth.web;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.http.HttpMethod;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Exact public reads share anonymous authorization, cookie-free CSRF handling and rate limiting. */
@NamedInterface("public-activity-reads")
public final class PublicActivityReadRequestMatcher {
    public static final RequestMatcher MATCHER = new OrRequestMatcher(List.of(
                    "/public/workspaces/*/activity",
                    "/public/workspaces/*/activity/people/*",
                    "/public/workspaces/*/activity/people/*/work")
            .stream()
            .flatMap(path -> Stream.of(HttpMethod.GET, HttpMethod.HEAD)
                    .<RequestMatcher>map(
                            method -> PathPatternRequestMatcher.withDefaults().matcher(method, path)))
            .toList());

    private PublicActivityReadRequestMatcher() {}
}
