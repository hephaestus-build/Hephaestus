package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.web.ApiPath;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.http.HttpMethod;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** The anonymous reads of the public usage page: they share public access, cookie-free CSRF and one rate limit. */
@NamedInterface("public-usage-reads")
public final class PublicUsageReadRequestMatcher {
    public static final RequestMatcher MATCHER =
            new OrRequestMatcher(List.of("/public/usage", "/public/usage/*").stream()
                    .flatMap(path -> Stream.of(HttpMethod.GET, HttpMethod.HEAD)
                            .<RequestMatcher>map(method -> ApiPath.matchers().matcher(method, path)))
                    .toList());

    private PublicUsageReadRequestMatcher() {}
}
