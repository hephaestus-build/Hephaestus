package de.tum.cit.aet.hephaestus.core.webapp;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.resource.EncodedResourceResolver;

/**
 * Serves the built web app (ADR 0100). Controllers come first, then {@code index.html} for each client route, then
 * the files. A path under a root that the server owns is never a client route, so an unknown API path stays a 404.
 *
 * <p>Vite names each asset by its content, so assets are cached for a year and never revalidated. Every other file
 * is revalidated, and {@code index.html} is never stored: a page that the back button brings back from the
 * back-forward cache would keep the cookies of a session that has ended. Each file is served from its Brotli or gzip
 * copy when the browser accepts one.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
class WebAppConfiguration implements WebMvcConfigurer {

    /** Path roots that the server answers itself, so none of them falls back to {@code index.html}. */
    private static final List<String> SERVER_ROOTS = List.of(
            "/api",
            "/webhooks",
            "/internal",
            "/actuator",
            "/.well-known",
            "/v3",
            "/swagger-ui",
            "/error",
            "/assets",
            "/legal",
            "/legal-overrides");

    private static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final WebAppProperties properties;

    WebAppConfiguration(WebAppProperties properties) {
        this.properties = properties;
    }

    @Bean
    RouterFunction<ServerResponse> webAppIndexRoute(WebAppIndex index) {
        RequestPredicate clientRoute = RequestPredicates.method(HttpMethod.GET, HttpMethod.HEAD)
                .and(request -> index.html().isPresent() && isClientRoute(path(request), index));
        return RouterFunctions.route(
                clientRoute,
                request -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .cacheControl(CacheControl.noStore())
                        .body(index.html().orElseThrow()));
    }

    static boolean isClientRoute(String path, WebAppIndex index) {
        if (path.equals("/index.html")) {
            return true;
        }
        if (SERVER_ROOTS.stream().anyMatch(root -> path.equals(root) || path.startsWith(root + "/"))) {
            return false;
        }
        return !index.hasFile(path);
    }

    private static String path(ServerRequest request) {
        return request.requestPath().pathWithinApplication().value();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = properties.location();
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(location + "assets/")
                .setCacheControl(
                        CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .resourceChain(true)
                .addResolver(new EncodedResourceResolver());
        registry.addResourceHandler("/legal/_disclaimer/*.md")
                .addResourceLocations(location + "legal/_disclaimer/")
                .setCacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).mustRevalidate());
        if (!properties.legalOverrides().isBlank()) {
            registry.addResourceHandler("/legal-overrides/*.md")
                    .addResourceLocations("file:" + properties.legalOverrides().replaceAll("/*$", "/"))
                    .setCacheControl(CacheControl.noCache());
        }
        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new EncodedResourceResolver());
    }

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.mediaType("md", MARKDOWN);
    }

    /** Legal pages are for readers of this deployment, not for search engines or other origins. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        response.setHeader("X-Robots-Tag", "noindex, nofollow");
                        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
                        return true;
                    }
                })
                .addPathPatterns("/legal/**", "/legal-overrides/**");
    }
}
