package de.tum.cit.aet.hephaestus.integration.scm.github.credentials;

import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Records a stored GitHub token that GitHub refused (HTTP 401) on a workspace's GraphQL call. The workspace's sync then
 * pauses until an admin replaces the token. An installation token has no stored copy, so its refusal records nothing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitHubCredentialRefusalFilter implements ExchangeFilterFunction {
    public static final String SCOPE_ID_ATTRIBUTE = "hephaestus.integration.github.scopeId";

    private final IntegrationAttentionService attention;

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return next.exchange(request).flatMap(response -> {
            String authorization = request.headers().getFirst(HttpHeaders.AUTHORIZATION);
            if (response.statusCode().value() != 401
                    || !(request.attributes().get(SCOPE_ID_ATTRIBUTE) instanceof Long workspaceId)
                    || authorization == null
                    || !authorization.startsWith("Bearer ")) {
                return Mono.just(response);
            }
            String token = authorization.substring("Bearer ".length());
            return Mono.fromRunnable(
                            () -> attention.observeCredential(IntegrationKind.GITHUB, workspaceId, token, true))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(error -> {
                        log.warn("Could not record GitHub credential refusal: workspaceId={}", workspaceId);
                        return Mono.empty();
                    })
                    .thenReturn(response);
        });
    }
}
