package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class GitLabWebhookCleanupClientTest extends BaseUnitTest {
    private static final String OWN = "https://app.example/webhooks/gitlab/connections/7/";
    private final Map<Long, String> hooks = new LinkedHashMap<>();
    private final List<Long> deleted = new ArrayList<>();
    private boolean denyReads;
    private boolean losePermissionDuringDelete;
    private boolean misleadingDelete404;

    private GitLabWebhookClient client() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            if (request.method().equals(HttpMethod.DELETE)) {
                String path = request.url().getPath();
                long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
                if (losePermissionDuringDelete) {
                    denyReads = true;
                    return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
                }
                if (misleadingDelete404)
                    return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
                hooks.remove(id);
                deleted.add(id);
                return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
            }
            if (denyReads)
                return Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).build());
            int page = request.url().getQuery().endsWith("page=2") ? 2 : 1;
            List<Map<String, Object>> all = hooks.entrySet().stream()
                    .map(hook -> Map.<String, Object>of("id", hook.getKey(), "url", hook.getValue()))
                    .toList();
            int from = Math.min((page - 1) * 2, all.size());
            int to = Math.min(from + 2, all.size());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .header("X-Next-Page", to < all.size() ? "2" : "")
                    .body(JsonMapper.builder().build().writeValueAsString(all.subList(from, to)))
                    .build());
        });
        return new GitLabWebhookClient(
                mock(GitLabGraphQlClientProvider.class), mock(GitLabTokenService.class), () -> builder);
    }

    @Test
    void shouldFindOwnedHooksOnLaterPagesAndPreserveOtherConnections() {
        hooks.put(1L, "https://other.example/webhooks/gitlab/connections/7/key/route");
        hooks.put(2L, "https://app.example/webhooks/gitlab/connections/70/key/route");
        hooks.put(3L, OWN + "unknown-key/unrecorded-route");

        client().removeGroupWebhooksWithCredentials(
                        "https://gitlab.example",
                        "test-token",
                        42L,
                        hook -> hook.url().startsWith(OWN));

        assertThat(hooks.keySet()).containsExactly(1L, 2L);
        assertThat(deleted).containsExactly(3L);
    }

    @Test
    void shouldNotConfirmDeletionWhenTheTokenLosesGroupAccess() {
        hooks.put(3L, OWN + "key/route");
        losePermissionDuringDelete = true;

        assertThatThrownBy(() -> client().removeGroupWebhooksWithCredentials(
                                "https://gitlab.example",
                                "test-token",
                                42L,
                                hook -> hook.url().startsWith(OWN)))
                .isInstanceOf(WebClientResponseException.Forbidden.class);
        assertThat(hooks).containsKey(3L);
    }

    @Test
    void shouldNotConfirmAMisleading404WhileTheHookRemainsListed() {
        hooks.put(3L, OWN + "key/route");
        misleadingDelete404 = true;

        assertThatThrownBy(() -> client().removeGroupWebhooksWithCredentials(
                                "https://gitlab.example",
                                "test-token",
                                42L,
                                hook -> hook.url().startsWith(OWN)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(hooks).containsKey(3L);
    }
}
