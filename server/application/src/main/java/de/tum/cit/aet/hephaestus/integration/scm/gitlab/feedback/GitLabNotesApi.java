package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.GRAPHQL_TIMEOUT;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebClients;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.MrCoordinates;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The REST calls on a merge request's notes and discussions that both the line note writer and the thread resolution
 * writer make: one authorized exchange that records the rate limit and holds every write to Silent Mode, and the
 * token's own account.
 */
@Component
@ConditionalOnServerRole
@OutboundEgressGateway
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
class GitLabNotesApi {

    static final JsonMapper MAPPER = new JsonMapper();

    private final GitLabGraphQlClientProvider gitLabProvider;
    private final WebClient rest;
    private final GitLabTokenService tokenService;
    private final OutboundEgressGuard egressGuard;

    GitLabNotesApi(
            GitLabGraphQlClientProvider gitLabProvider,
            GitLabWebClients rest,
            GitLabTokenService tokenService,
            OutboundEgressGuard egressGuard) {
        this.gitLabProvider = gitLabProvider;
        this.rest = rest.builder().build();
        this.tokenService = tokenService;
        this.egressGuard = egressGuard;
    }

    record Answer(int status, @Nullable JsonNode json, String text) {}

    // A status is an answer; only transport failures and timeouts leave the outcome unknown to the caller.
    Answer exchange(long scopeId, HttpMethod method, Request target, @Nullable Map<String, Object> body) {
        // Every REST write consults Silent Mode here too, as every GraphQL mutation does in
        // SilentModeGraphQlInterceptor.
        if (!HttpMethod.GET.equals(method))
            egressGuard.requireDeliveryAllowed(
                    "scm.rest.gitlab." + method.name().toLowerCase(Locale.ROOT));
        var request = rest.method(method)
                .uri(target.uri())
                .header(HttpHeaders.AUTHORIZATION, target.authorization())
                .attribute(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE, scopeId);
        WebClient.RequestHeadersSpec<?> spec = body == null
                ? request
                : request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        Answer answer = spec.exchangeToMono(response -> {
                    gitLabProvider.updateRateLimit(scopeId, response.headers().asHttpHeaders());
                    int status = response.statusCode().value();
                    // A 204 such as bulk_publish's carries no body, so only a non-empty success body is JSON.
                    boolean success = response.statusCode().is2xxSuccessful();
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(text -> new Answer(
                                    status, success && !text.isBlank() ? MAPPER.readTree(text) : null, text));
                })
                .block(GRAPHQL_TIMEOUT);
        if (answer == null) throw new IllegalStateException("GitLab answered without a response");
        return answer;
    }

    record Request(URI uri, String authorization) {}

    // The address and credential are local facts; resolving them is never part of a provider request.
    Request request(long scopeId, MrCoordinates mr, @Nullable String discussion) {
        return authorized(scopeId, discussions(scopeId, mr, discussion));
    }

    Request authorized(long scopeId, URI uri) {
        return new Request(uri, "Bearer " + tokenService.getAccessToken(scopeId));
    }

    private URI discussions(long scopeId, MrCoordinates mr, @Nullable String discussion) {
        return URI.create(GitLabDiffVersionReader.mergeRequestApi(tokenService.resolveServerUrl(scopeId), mr)
                + "/discussions"
                + (discussion == null ? "" : "/" + discussion));
    }

    // The token's own account is the only author whose copy a correction may edit.
    long currentUser(long scopeId, Request user) {
        Answer answer = exchange(scopeId, HttpMethod.GET, user, null);
        JsonNode json = answer.json();
        if (answer.status() != 200
                || json == null
                || !json.path("id").isIntegralNumber()
                || json.path("id").longValue() <= 0)
            throw new FeedbackDeliveryException("The GitLab account could not be read");
        return json.path("id").longValue();
    }
}
