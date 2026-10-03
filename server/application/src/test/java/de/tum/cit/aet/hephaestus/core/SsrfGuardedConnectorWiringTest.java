package de.tum.cit.aet.hephaestus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Proves the real WebClient → Reactor Netty → {@link SsrfGuardedResolverGroup} wiring engages: a client
 * on {@link WebClientConnectors#ssrfGuarded()} fails to reach a loopback-resolving host (deterministic
 * because {@code localhost} resolves offline via the hosts file; a hostname routes through our resolver).
 */
@Timeout(15) // safety net: the unguarded control's connect must not hang the suite
class SsrfGuardedConnectorWiringTest extends BaseUnitTest {

    private static final String LOOPBACK_URL = "http://localhost:9/";
    private static final Duration BLOCK = Duration.ofSeconds(5);

    @Test
    void ssrfGuardedClientRefusesToConnectToLoopbackResolvingHost() {
        WebClient client = WebClient.builder()
                .clientConnector(WebClientConnectors.ssrfGuarded())
                .build();

        Throwable thrown = catchThrowable(() -> client.get()
                .uri(LOOPBACK_URL)
                .retrieve()
                .bodyToMono(String.class)
                .block(BLOCK));

        // The guard fires at RESOLUTION, before any socket is opened: UnknownHostException with our message.
        assertThat(thrown).isNotNull();
        Throwable root = NestedExceptionUtils.getMostSpecificCause(thrown);
        assertThat(root)
                .as("guard must reject at DNS resolution, not surface a plain connect failure")
                .isInstanceOf(UnknownHostException.class)
                .isNotInstanceOf(ConnectException.class);
        assertThat(root.getMessage())
                .as("failure must identify the SSRF guard, distinguishing it from 'nothing listening'")
                .contains("SSRF guard");
    }

    @Test
    void systemDnsClientDoesNotApplyTheGuard() {
        // Negative control: the same loopback host through the UNGUARDED connector must NOT carry our guard
        // message — proving it is the guard, not Reactor Netty, that rejects loopback above.
        WebClient client = WebClient.builder()
                .clientConnector(WebClientConnectors.systemDns())
                .build();

        Throwable thrown = catchThrowable(() -> client.get()
                .uri(LOOPBACK_URL)
                .retrieve()
                .bodyToMono(String.class)
                .block(BLOCK));

        assertThat(thrown).isNotNull();

        Throwable root = NestedExceptionUtils.getMostSpecificCause(thrown);
        assertThat(root).isNotInstanceOf(UnknownHostException.class);
        assertThat(String.valueOf(root.getMessage())).doesNotContain("SSRF guard");
    }
}
