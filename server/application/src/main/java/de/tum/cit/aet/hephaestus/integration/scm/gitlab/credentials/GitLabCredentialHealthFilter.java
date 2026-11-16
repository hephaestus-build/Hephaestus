package de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials;

import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** One HTTP observation hook for authenticated GitLab REST and GraphQL calls. */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitLabCredentialHealthFilter implements WebClientCustomizer {
    private final ObjectProvider<GitLabCredentialHealth> health;

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
