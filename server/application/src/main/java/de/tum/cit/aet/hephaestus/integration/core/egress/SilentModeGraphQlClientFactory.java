package de.tum.cit.aet.hephaestus.integration.core.egress;

import org.springframework.graphql.client.GraphQlClientInterceptor;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.graphql.support.DocumentSource;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class SilentModeGraphQlClientFactory {

    private final SilentModeGraphQlInterceptor interceptor;

    public SilentModeGraphQlClientFactory(SilentModeGraphQlInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    public HttpGraphQlClient create(WebClient webClient, DocumentSource documentSource) {
        WebClient guardedWebClient =
                webClient.mutate().filter(interceptor.httpAttemptFilter()).build();
        return HttpGraphQlClient.builder(guardedWebClient)
                .documentSource(documentSource)
                .interceptor(interceptor)
                .build();
    }

    public HttpGraphQlClient withBearerToken(HttpGraphQlClient baseClient, String token) {
        return baseClient
                .mutate()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    public HttpGraphQlClient withBearerToken(HttpGraphQlClient baseClient, String url, String token) {
        return baseClient
                .mutate()
                .url(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    public HttpGraphQlClient withBearerTokenAndAttribute(
            HttpGraphQlClient baseClient, String url, String token, String attributeName, Object attributeValue) {
        return withBearerTokenAndAttribute(
                baseClient, url, token, attributeName, attributeValue, new GraphQlClientInterceptor[0]);
    }

    public HttpGraphQlClient withBearerTokenAndAttribute(
            HttpGraphQlClient baseClient,
            String url,
            String token,
            String attributeName,
            Object attributeValue,
            GraphQlClientInterceptor... responseInterceptors) {
        return baseClient
                .mutate()
                .url(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .webClient(
                        builder -> builder.defaultRequest(request -> request.attribute(attributeName, attributeValue)))
                .interceptor(responseInterceptors)
                .build();
    }
}
