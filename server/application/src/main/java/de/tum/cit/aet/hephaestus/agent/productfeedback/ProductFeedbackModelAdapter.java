package de.tum.cit.aet.hephaestus.agent.productfeedback;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.composer.ModelSession;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmCallPermits;
import de.tum.cit.aet.hephaestus.agent.proxy.UpstreamHeaders;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafting;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafting.DraftWork;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafting.Failure;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drafts product feedback with the operator's instance model, in the background band, after every other waiting call.
 * It holds a permit for every physical call and closes it only after the response body is read or the transport is
 * closed. Product feedback validates and routes the answer; this adapter only carries it.
 */
@Component
@ConditionalOnAgentRuntime
@WorkspaceAgnostic("Product feedback drafting uses the instance's model; it belongs to no workspace")
class ProductFeedbackModelAdapter {
    private static final Logger log = LoggerFactory.getLogger(ProductFeedbackModelAdapter.class);
    /** Bounds the one call that a higher-class request may wait behind. */
    static final Duration CALL_TIMEOUT = Duration.ofMinutes(2);

    private final ProductFeedbackDrafting drafting;
    private final LlmModelResolver models;
    private final LlmCallPermits permits;
    private final WebClient client;
    private final ObjectMapper mapper;
    private final String ownerId = UUID.randomUUID().toString();

    ProductFeedbackModelAdapter(
            ProductFeedbackDrafting drafting,
            LlmModelResolver models,
            LlmCallPermits permits,
            WebClient.Builder clients,
            ObjectMapper mapper) {
        this.drafting = drafting;
        this.models = models;
        this.permits = permits;
        this.client = clients.build();
        this.mapper = mapper;
    }

    /**
     * Its own single thread, apart from annotation scheduling. One thread also means one drafting call per server at a
     * time. Tests draft by calling {@link #draftNext}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnAgentRuntime
    @Profile("!test & !specs & !cds-training")
    static class ScheduleConfiguration {
        @Bean
        ScheduledTaskRegistrar productFeedbackDraftingTasks(ProductFeedbackModelAdapter adapter) {
            var tasks = new ScheduledTaskRegistrar();
            tasks.addFixedDelayTask(
                    new FixedDelayTask(adapter::draftNext, Duration.ofSeconds(5), Duration.ofSeconds(30)));
            return tasks;
        }
    }

    void draftNext() {
        try {
            DraftWork work = drafting.claim(ownerId).orElse(null);
            if (work != null) draft(work);
        } catch (RuntimeException failed) {
            // Logged here with what failed; the scheduler would log it without the drafting context.
            log.warn(
                    "Product feedback drafting failed: error={}",
                    failed.getClass().getSimpleName());
        }
    }

    void draft(DraftWork work) {
        LlmModelResolver.InstanceModel model = models.resolveInstanceModel(work.llmModelId());
        if (model == null) {
            drafting.fail(work.feedbackId(), work.attempt(), Failure.MODEL_UNAVAILABLE, 0, 0);
            return;
        }
        BooleanSupplier ended = () -> !drafting.held(work.feedbackId(), work.attempt());
        var usage = new long[2];
        try (var permit = permits.acquire(
                permits.poolFor(model.ref()),
                AgentRunBand.BACKGROUND,
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
                return send(model, body, usage, permit);
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

    private ModelSession.Answer send(
            LlmModelResolver.InstanceModel model, byte[] body, long[] usage, LlmCallPermits.Permit permit) {
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
                    // A permit that a server can no longer renew must end its transport, or the capacity counts twice.
                    .takeUntilOther(permit.revoked())
                    .block(CALL_TIMEOUT);
            if (response == null && permit.isRevoked()) return new ModelSession.Answer(0, new byte[0], false, null);
            byte[] answer = response == null ? new byte[0] : response;
            count(answer, usage);
            permit.served(true);
            return new ModelSession.Answer(200, answer, true, null);
        } catch (WebClientResponseException refused) {
            int status = refused.getStatusCode().value();
            if (LlmCallPermits.throttles(status) && !LlmCallPermits.spentAccount(refused.getResponseBodyAsByteArray()))
                permits.throttled(permit, status, refused.getHeaders(), true);
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
