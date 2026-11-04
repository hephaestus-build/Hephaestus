package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@DisplayName("HTTP requests in transactions")
class HttpRequestsInTransactionsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    private WebClient.Builder webClientBuilder;

    private MockWebServer provider;

    @BeforeEach
    void startProvider() throws IOException {
        provider = new MockWebServer();
        provider.start();
    }

    @AfterEach
    void stopProvider() {
        provider.close();
    }

    @Test
    void shouldCountARequestByTheBoundaryWhenItStartsInATransaction() {
        String blocking = boundary();
        String reactive = boundary();

        named(blocking).executeWithoutResult(status -> restGet());
        named(reactive).executeWithoutResult(status -> webGet());

        assertThat(count(blocking)).isEqualTo(1);
        assertThat(count(reactive)).isEqualTo(1);
    }

    @Test
    void shouldNotCountARequestWhenTheTransactionIsSuspendedForIt() {
        String outer = boundary();
        TransactionTemplate noTransaction = new TransactionTemplate(transactionManager);
        noTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

        named(outer)
                .executeWithoutResult(status -> noTransaction.executeWithoutResult(suspended -> {
                    restGet();
                    webGet();
                }));

        assertThat(count(outer)).isZero();
    }

    private void restGet() {
        provider.enqueue(new MockResponse.Builder().body("ok").build());
        restClientBuilder
                .build()
                .get()
                .uri(provider.url("/probe").uri())
                .retrieve()
                .body(String.class);
    }

    private void webGet() {
        provider.enqueue(new MockResponse.Builder().body("ok").build());
        webClientBuilder
                .build()
                .get()
                .uri(provider.url("/probe").uri())
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));
    }

    private static String boundary() {
        return "Probe" + UUID.randomUUID().toString().replace("-", "") + ".call";
    }

    private TransactionTemplate named(String boundary) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setName("de.tum.cit.aet.hephaestus.probe." + boundary);
        return template;
    }

    private double count(String boundary) {
        @Nullable
        Counter counter = registry.find(CoreMetrics.DATABASE_TRANSACTION_HTTP_REQUESTS)
                .tags("boundary", boundary)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
