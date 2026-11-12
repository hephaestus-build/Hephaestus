package de.tum.cit.aet.hephaestus.agent.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmCallPermits;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafting;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class ProductFeedbackModelAdapterTest extends BaseUnitTest {
    private static final long MODEL = 5;

    @Mock
    private ProductFeedbackDrafting drafting;

    @Mock
    private LlmModelResolver models;

    @Mock
    private LlmCallPermits permits;

    private final MockWebServer provider = new MockWebServer();
    private final LlmCallPermits.Permit permit = mock(LlmCallPermits.Permit.class);
    private ProductFeedbackModelAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        provider.start();
        adapter = new ProductFeedbackModelAdapter(drafting, models, permits, WebClient.builder(), new ObjectMapper());
        var ref = new LlmModelResolver.ConnectionRef(UsageFundingSource.INSTANCE, 1L, MODEL, null);
        var credential = new LlmModelResolver.ProxyCredential(
                provider.url("/v1").toString(),
                LlmApiProtocol.OPENAI_COMPLETIONS.wire(),
                LlmAuthMode.BEARER,
                "model",
                "secret",
                9);
        when(models.resolveInstanceModel(MODEL))
                .thenReturn(
                        new LlmModelResolver.InstanceModel(ref, credential, LlmApiProtocol.OPENAI_COMPLETIONS, null));
        when(permits.poolFor(ref)).thenReturn(9L);
        when(permits.acquire(anyLong(), any(), any(), any(), any())).thenReturn(permit);
    }

    @AfterEach
    void stopProvider() {
        provider.close();
    }

    private static ProductFeedbackDrafting.DraftWork work() {
        return new ProductFeedbackDrafting.DraftWork(
                UUID.randomUUID(),
                1,
                MODEL,
                "system",
                "user",
                100,
                Instant.now().plusSeconds(120));
    }

    @Test
    void shouldEndACallWhenItsPermitIsRevoked() {
        when(permit.revoked()).thenReturn(Mono.delay(Duration.ofMillis(200)).then());
        when(permit.isRevoked()).thenReturn(true);
        provider.enqueue(new MockResponse.Builder()
                .headersDelay(50, TimeUnit.SECONDS)
                .code(200)
                .build());
        var work = work();

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> adapter.draft(work));

        verify(permit, never()).served(true);
        verify(drafting).fail(work.feedbackId(), work.attempt(), ProductFeedbackDrafting.Failure.PROVIDER_ERROR, 0, 0);
    }

    @Test
    void shouldStillCountAnEmptyAnswerAsServed() {
        when(permit.revoked()).thenReturn(Mono.never());
        when(permit.isRevoked()).thenReturn(false);
        provider.enqueue(new MockResponse.Builder().code(200).build());

        adapter.draft(work());

        verify(permit).served(true);
        assertThat(provider.getRequestCount()).isEqualTo(1);
    }
}
