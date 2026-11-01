package de.tum.cit.aet.hephaestus.agent.productfeedback;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallClass;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.composer.ModelSession;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmCallPermits;
import de.tum.cit.aet.hephaestus.agent.proxy.UpstreamHeaders;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafting;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafting.DraftWork;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafting.Failure;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drafts product feedback with the operator's instance model, in the lowest model-call class. It holds a permit for
 * every physical call and closes it only after the response body is read or the transport is closed. Product
 * feedback validates and routes the answer; this adapter only carries it.
 */
@Component
@ConditionalOnWorkerRole
@WorkspaceAgnostic("Product feedback drafting uses the instance's model; it belongs to no workspace")
class ProductFeedbackModelAdapter {
    private static final Logger log = LoggerFactory.getLogger(ProductFeedbackModelAdapter.class);
    /** Bounds the one call that a higher-class request may wait behind. */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    private final ProductFeedbackDrafting drafting;
    private final LlmModelResolver models;
    private final LlmCallPermits permits;
    private final WebClient client;
    private final ObjectMapper mapper;
    private final String workerId;
    private volatile @Nullable ScheduledExecutorService scheduler;
    private volatile @Nullable ScheduledFuture<?> task;

    ProductFeedbackModelAdapter(
            ProductFeedbackDrafting drafting,
            LlmModelResolver models,
            LlmCallPermits permits,
            WebClient.Builder clients,
            ObjectMapper mapper,
            WorkerProperties worker) {
        this.drafting = drafting;
        this.models = models;
        this.permits = permits;
        this.client = clients.build();
        this.mapper = mapper;
        this.workerId = worker.resolvedWorkerId();
    }

    /**
     * Annotation scheduling runs only in the server role, so a worker-only process starts its own single thread. One
     * thread also means one drafting call per worker at a time.
     */
    @EventListener(ApplicationReadyEvent.class)
    void start() {
        var executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name("product-feedback-drafting")
                .daemon(true)
                .factory());
        task = executor.scheduleWithFixedDelay(this::draftNext, 30, 5, TimeUnit.SECONDS);
        scheduler = executor;
    }

    @PreDestroy
    void stop() {
        ScheduledFuture<?> running = task;
        if (running != null) running.cancel(true);
        ScheduledExecutorService executor = scheduler;
        if (executor != null) executor.shutdownNow();
    }

    void draftNext() {
        try {
            DraftWork work = drafting.claim(workerId).orElse(null);
            if (work != null) draft(work);
        } catch (RuntimeException failed) {
            // An exception that left this method would end the scheduled task for good.
            log.warn(
                    "Product feedback drafting failed: error={}",
                    failed.getClass().getSimpleName());
        }
    }

    void draft(DraftWork work) {
        LlmModelResolver.InstanceModel model = models.resolveInstanceModel(work.llmModelId());
        Long pool = model == null ? null : permits.poolFor(model.ref());
        if (model == null || pool == null) {
            // The strict class needs a pool; a model outside one is unavailable, never a bypass of admission.
            drafting.fail(work.feedbackId(), work.attempt(), Failure.MODEL_UNAVAILABLE, 0, 0);
            return;
        }
        BooleanSupplier ended = () -> !drafting.held(work.feedbackId(), work.attempt());
        var usage = new long[2];
        try (var permit = permits.acquire(
                pool,
                LlmCallClass.PRODUCT_FEEDBACK,
                new LlmCallPermits.Caller(null, work.feedbackId(), work.attempt(), model.ref()),
                ended,
                work.deadline())) {
            if (permit == null) {
                drafting.fail(work.feedbackId(), work.attempt(), Failure.TIMEOUT, 0, 0);
                return;
            }
            var settings = new ModelSession.Settings(
                    model.protocol(),
                    model.credential().upstreamModelId(),
                    model.reasoningEffort(),
                    work.maxOutputTokens(),
                    null);
            var calls = new int[1];
            ModelSession.Transport transport = body -> {
                if (calls[0]++ > 0 && !permits.beforeRetry(permit, ended, work.deadline()))
                    return new ModelSession.Answer(0, new byte[0], false, null);
                return send(model, body, usage);
            };
            var session = new ModelSession(transport, mapper, settings, work.system(), List.of());
            ModelSession.Reply reply = session.send(work.user());
            if (reply.failure() == ModelSession.Failure.PROVIDER) reply = session.retry();
            String text = reply.text();
            if (reply.failure() != null || text == null) {
                drafting.fail(work.feedbackId(), work.attempt(), Failure.PROVIDER_ERROR, usage[0], usage[1]);
                return;
            }
            drafting.complete(work.feedbackId(), work.attempt(), text, usage[0], usage[1]);
        } catch (RuntimeException failed) {
            log.warn(
                    "Product feedback draft failed: feedbackId={}, error={}",
                    work.feedbackId(),
                    failed.getClass().getSimpleName());
            drafting.fail(work.feedbackId(), work.attempt(), Failure.PROVIDER_ERROR, usage[0], usage[1]);
        }
    }

    private ModelSession.Answer send(LlmModelResolver.InstanceModel model, byte[] body, long[] usage) {
        var credential = model.credential();
        URI uri = URI.create(credential.baseUrl().strip().replaceAll("/+$", "")
                + model.protocol().upstreamPath());
        HttpHeaders headers = UpstreamHeaders.of(new HttpHeaders(), credential);
        try {
            byte[] response = client.method(HttpMethod.POST)
                    .uri(uri)
                    .headers(outgoing -> outgoing.addAll(headers))
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(CALL_TIMEOUT);
            byte[] answer = response == null ? new byte[0] : response;
            count(answer, usage);
            return new ModelSession.Answer(200, answer, true, null);
        } catch (WebClientResponseException refused) {
            return new ModelSession.Answer(
                    refused.getStatusCode().value(), refused.getResponseBodyAsByteArray(), true, null);
        }
    }

    /** Reads the token counts the provider reported, in either protocol's field names. */
    private void count(byte[] response, long[] usage) {
        try {
            JsonNode node = mapper.readTree(response).path("usage");
            usage[0] += first(node, "prompt_tokens", "input_tokens");
            usage[1] += first(node, "completion_tokens", "output_tokens");
        } catch (RuntimeException unreadable) {
            // A body without usage still counts as a call; the permit already debited it.
        }
    }

    private static long first(JsonNode node, String a, String b) {
        JsonNode value = node.path(a).isMissingNode() ? node.path(b) : node.path(a);
        return value.isNumber() ? value.asLong() : 0;
    }
}
