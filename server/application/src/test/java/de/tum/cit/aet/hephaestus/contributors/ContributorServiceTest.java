package de.tum.cit.aet.hephaestus.contributors;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.URI;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class ContributorServiceTest extends BaseUnitTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void shouldNotRequestContributorsWithoutToken(@Nullable String token) {
        var builder = WebClient.builder().exchangeFunction(request -> {
            throw new AssertionError("Contributor fetching must be disabled without a token");
        });
        var service = new ContributorService(builder, new ContributorProperties(token));

        assertThat(service.getGlobalContributors()).isEmpty();
    }

    @Test
    void shouldFetchContributorsFromCurrentRepositoryWhenTokenConfigured() {
        var requests = new ArrayList<URI>();
        var builder = WebClient.builder().exchangeFunction(request -> {
            requests.add(request.url());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("[]")
                    .build());
        });
        var service = new ContributorService(builder, new ContributorProperties("test-token"));

        assertThat(service.getGlobalContributors()).isEmpty();
        assertThat(requests)
                .containsExactly(URI.create(
                        "https://api.github.com/repos/hephaestus-build/Hephaestus/contributors?per_page=100"));
    }
}
