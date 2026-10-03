package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import de.tum.cit.aet.hephaestus.agent.LlmProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

@Timeout(15)
class LlmProxyWebClientConfigTest extends BaseUnitTest {

    private static final String LOOPBACK_URL = "http://localhost:9/";
    private static final Duration BLOCK = Duration.ofSeconds(5);

    @Test
    void proxyWebClientRejectsLoopbackResolvingHostWhenLoopbackNotAllowed() {
        LlmProxyWebClientConfig config = new LlmProxyWebClientConfig();
        ConnectionProvider provider = config.llmProxyConnectionProvider();
        LoopResources loop = config.llmProxyLoopResources();
        try {
            WebClient client = config.llmProxyWebClient(
                    provider,
                    loop,
                    new LlmProperties(
                            "", new LlmProperties.Egress(false), new LlmProperties.Fx(LlmProperties.ECB_DAILY_URL)),
                    WebClient.builder());

            Throwable thrown = catchThrowable(() -> client.get()
                    .uri(LOOPBACK_URL)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(BLOCK));

            assertThat(thrown).isNotNull();

            Throwable root = NestedExceptionUtils.getMostSpecificCause(thrown);
            assertThat(root)
                    .as("guard must reject at DNS resolution, not surface a plain connect failure")
                    .isInstanceOf(UnknownHostException.class)
                    .isNotInstanceOf(ConnectException.class);
            assertThat(root.getMessage()).contains("SSRF guard");
        } finally {
            provider.dispose();
            loop.disposeLater().block(BLOCK);
        }
    }
}
