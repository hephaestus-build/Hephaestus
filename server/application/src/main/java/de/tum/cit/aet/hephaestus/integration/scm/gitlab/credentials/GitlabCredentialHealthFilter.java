package de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials;

import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** One HTTP observation hook for authenticated GitLab REST and GraphQL calls. */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitlabCredentialHealthFilter implements WebClientCustomizer {
    private final ObjectProvider<GitlabCredentialHealth> health;

    @Override
    public void customize(WebClient.Builder builder) {
        builder.filter(filter());
    }

    public ExchangeFilterFunction filter() {
        return (request, next) -> next.exchange(request).flatMap(response -> {
            Object scope = request.attributes().get(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE);
            String authorization = request.headers().getFirst(HttpHeaders.AUTHORIZATION);
            int status = response.statusCode().value();
            if (!(scope instanceof Long workspaceId)
                    || authorization == null
                    || !authorization.startsWith("Bearer ")
                    || (status != 401
                            && (!response.statusCode().is2xxSuccessful()
                                    || request.url().getPath().equals("/api/graphql")))) {
                return Mono.just(response);
            }
            String token = authorization.substring(7);
            // GitLab also answers 401 when a merge request author tries to approve their own work.
            // That is an action refusal, not a revoked token. Confirm that case against identity.
            if (status == 401 && request.url().getPath().endsWith("/approve")) {
                var probe = ClientRequest.from(request)
                        .method(HttpMethod.GET)
                        .url(request.url().resolve("/api/v4/user"))
                        .body(BodyInserters.empty())
                        .build();
                // Consume the original response before another exchange needs a pooled connection.
                // Rebuild its body so the caller still receives GitLab's original refusal.
                return response.bodyToMono(byte[].class)
                        .defaultIfEmpty(new byte[0])
                        .flatMap(body -> next.exchange(probe)
                                .flatMap(identity -> {
                                    int identityStatus = identity.statusCode().value();
                                    Mono<Void> observation = Mono.empty();
                                    if (identityStatus == 401
                                            || identity.statusCode().is2xxSuccessful()) {
                                        observation = observe(workspaceId, token, identityStatus == 401);
                                    }
                                    return identity.releaseBody().then(observation);
                                })
                                .onErrorResume(error -> Mono.empty())
                                .thenReturn(response.mutate()
                                        .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(body)))
                                        .build()));
            }
            return observe(workspaceId, token, status == 401).thenReturn(response);
        });
    }

    public Mono<Void> observe(long workspaceId, String token, boolean refused) {
        return Mono.<Void>fromRunnable(() -> health.getObject().observe(workspaceId, token, refused))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    log.warn("Could not record GitLab credential health: workspaceId={}", workspaceId);
                    return Mono.empty();
                });
    }
}
