package de.tum.cit.aet.hephaestus.core.webapp;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
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
import org.springframework.web.util.UriUtils;

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

    /** Roots of the web app's own files, which a missing file under them does not turn into the page. */
    private static final List<String> FILE_ROOTS = List.of("/assets", "/legal", "/legal-overrides");

    /**
     * Images and fonts, which nginx answered with a 404 when missing, rather than with the page. Other dotted paths stay
     * client routes: a login can contain a dot.
     */
    private static final Pattern STATIC_FILE =
            Pattern.compile(".*\\.(?:ico|png|jpe?g|gif|webp|svg|woff2?|ttf|otf|eot)");

    private final WebAppProperties properties;

    WebAppConfiguration(WebAppProperties properties) {
        this.properties = properties;
    }

    @Bean
    RouterFunction<ServerResponse> webAppIndexRoute(WebAppIndex index) {
        RequestPredicate clientRoute = RequestPredicates.method(HttpMethod.GET)
                .or(RequestPredicates.method(HttpMethod.HEAD))
                .and(request -> index.present() && isClientRoute(path(request), index));
        return RouterFunctions.route(clientRoute, request -> {
            // The header writer reads the nonce when the response commits, after this handler chose it.
            String nonce = WebAppIndex.newNonce();
            request.servletRequest().setAttribute(WebAppRequests.SCRIPT_NONCE, nonce);
            return ServerResponse.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .cacheControl(CacheControl.noStore())
                    .body(index.html(nonce));
        });
    }

    static boolean isClientRoute(String path, WebAppIndex index) {
        if (path.equals("/index.html")) {
            return true;
        }
        if (Stream.concat(WebAppRequests.SERVER_ROOTS.stream(), FILE_ROOTS.stream())
                .anyMatch(root -> path.equals(root) || path.startsWith(root + "/"))) {
            return false;
        }
        return !index.hasFile(path) && !STATIC_FILE.matcher(path).matches();
    }

    /** The decoded path, as the controllers and the security matchers see it. */
    private static String path(ServerRequest request) {
        return UriUtils.decode(request.requestPath().pathWithinApplication().value(), StandardCharsets.UTF_8);
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
                    .addResourceLocations(legalOverridesLocation(properties.legalOverrides()))
                    .setCacheControl(CacheControl.noCache());
        }
        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new EncodedResourceResolver());
    }

    /**
     * The operator's directory, as a resource location. The chart mounts it from a ConfigMap, where each file is a
     * symbolic link into a hidden versioned directory.
     */
    static String legalOverridesLocation(String directory) {
        return "file:" + directory.replaceAll("/*$", "/");
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
