package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.web.ApiPath;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.http.server.LocalTestWebServer;
import org.springframework.boot.webtestclient.autoconfigure.WebTestClientBuilderCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Request tests address API routes relative to {@link ApiPath#PREFIX}, as the generated clients do with the OpenAPI
 * {@code servers} entry. A route outside the API, such as {@code /webhooks/github}, uses {@link #root}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ApiWebTestClient {

    @Bean
    WebTestClientBuilderCustomizer apiBaseUri(ApplicationContext context) {
        return builder -> builder.uriBuilderFactory(
                LocalTestWebServer.obtain(context).withPath(ApiPath.PREFIX).uriBuilderFactory());
    }

    /** The server's origin, for a request to a route outside the API. */
    public static String root(ApplicationContext context, String path) {
        return LocalTestWebServer.obtain(context).uri(path);
    }
}
