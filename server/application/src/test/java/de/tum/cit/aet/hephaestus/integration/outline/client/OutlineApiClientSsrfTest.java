package de.tum.cit.aet.hephaestus.integration.outline.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class OutlineApiClientSsrfTest extends BaseUnitTest {

    private final OutlineApiClient client = new OutlineApiClient(
            CircuitBreaker.ofDefaults("outlineRestApi"),
            Retry.ofDefaults("outlineRestApi"),
            WebClient.builder().build(),
            new OutlineOriginPolicy(Set.of()));

    @Test
    void validateToken_rejectsBlankServerUrl() {
        assertThatThrownBy(() -> client.validateToken("  ", "tok")).isInstanceOf(OutlineApiException.class);
    }

    @Test
    void validateToken_rejectsPublicButUnapprovedOrigin() {
        assertThatThrownBy(() -> client.validateToken("https://wiki.example.com", "tok"))
                .isInstanceOf(OutlineApiException.class);
    }
}
