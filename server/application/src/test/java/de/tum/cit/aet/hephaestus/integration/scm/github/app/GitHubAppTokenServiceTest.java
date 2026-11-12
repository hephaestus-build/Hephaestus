package de.tum.cit.aet.hephaestus.integration.scm.github.app;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.github.GitHubProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.PinnedParks;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class GitHubAppTokenServiceTest extends BaseUnitTest {

    private final AtomicInteger mints = new AtomicInteger();

    /** GitHub answers each mint after a moment, as a real call waits on the network. */
    private GitHubAppTokenService service() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder()
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        var github = WebClient.builder().exchangeFunction(request -> {
            mints.incrementAndGet();
            return Mono.delay(Duration.ofMillis(50))
                    .map(unused -> ClientResponse.create(HttpStatus.CREATED)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body("{\"token\":\"ghs_test\",\"expires_at\":\""
                                    + Instant.now().plus(Duration.ofHours(1)) + "\"}")
                            .build());
        });
        return new GitHubAppTokenService(
                new GitHubProperties(new GitHubProperties.App(7, null, pem, null, null, null)), github);
    }

    /** Callers on virtual threads mint a token; a GitHub call under the cache's lock would pin each one that waits. */
    @Test
    void shouldMintATokenWithoutPinningTheVirtualThreadThatAsks() throws Exception {
        var tokens = service();

        assertThat(PinnedParks.during(
                        () -> assertThat(tokens.getInstallationToken(11)).isEqualTo("ghs_test")))
                .as("pinned parks while the token was minted")
                .isEmpty();
        assertThat(tokens.getInstallationToken(11)).as("the cached token").isEqualTo("ghs_test");
        assertThat(mints).hasValue(1);
    }
}
