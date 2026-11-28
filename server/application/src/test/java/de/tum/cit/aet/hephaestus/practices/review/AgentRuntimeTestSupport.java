package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.agent.catalog.CreateWorkspaceLlmConnectionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.catalog.CreateWorkspaceLlmModelRequestDTO;
import de.tum.cit.aet.hephaestus.agent.catalog.InstanceLlmSettings;
import de.tum.cit.aet.hephaestus.agent.catalog.InstanceLlmSettingsRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnectionService;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelService;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionClosing;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionStop;
import de.tum.cit.aet.hephaestus.agent.composer.ReviewComposeSandboxKind;
import de.tum.cit.aet.hephaestus.agent.config.ModelAssignmentRequestDTO;
import de.tum.cit.aet.hephaestus.agent.config.ModelAssignmentService;
import de.tum.cit.aet.hephaestus.agent.config.ModelPurpose;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The agent runtime of a review test, short of the sandbox container: a stub model provider behind the real proxy, a
 * priced review model bound in a workspace, and a composer that decides nothing. Import it with {@link Sandbox}, which
 * registers {@link ReviewSandboxStub} as the sandbox claims. The provider lives as long as the context.
 */
@TestComponent
@RequiredArgsConstructor
class AgentRuntimeTestSupport implements InitializingBean, DisposableBean {

    /** The review model's context window and output bound, which a scan's budget reserves per call. */
    static final int CONTEXT_WINDOW = 128_000;

    static final int MAX_OUTPUT_TOKENS = 4_096;

    /** The prompt tokens that each stub answer reports unless a test sets more. */
    static final int PROMPT_TOKENS = 12;

    /** The review model's price per million tokens: input, output, cache read and cache write. */
    static final LlmPriceSnapshot PRICE = new LlmPriceSnapshot(
            UsageFundingSource.WORKSPACE,
            UsagePricingState.PRICED,
            null,
            null,
            new BigDecimal("0.10"),
            new BigDecimal("0.40"),
            new BigDecimal("0.05"),
            new BigDecimal("0.10"));

    @TestConfiguration(proxyBeanMethods = false)
    static class Sandbox {
        @Bean
        @Primary
        ReviewSandboxStub reviewSandboxStub(ObjectMapper mapper) {
            return new ReviewSandboxStub(mapper);
        }
    }

    private final MockWebServer provider = new MockWebServer();
    private volatile int promptTokens = PROMPT_TOKENS;
    private final InstanceLlmSettingsRepository instanceLlmSettings;
    private final WorkspaceLlmConnectionService connections;
    private final WorkspaceLlmModelService models;
    private final ModelAssignmentService bindings;
    private final AgentRuns agentRuns;
    private final ReviewComposeSandboxKind composeKind;
    private final CompositionClosing compositionClosing;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;

    @Override
    public void afterPropertiesSet() throws IOException {
        provider.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body("""
                            {"id":"stub","object":"chat.completion","created":1,"model":"review",
                             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"MET"}}],
                             "usage":{"prompt_tokens":%d,"completion_tokens":3,"total_tokens":%d}}
                            """.formatted(promptTokens, promptTokens + 3))
                        .build();
            }
        });
        provider.start();
    }

    @Override
    public void destroy() {
        provider.close();
    }

    /** Each later stub answer reports {@code tokens} prompt tokens, so each model call costs what they are worth. */
    void reportPromptTokens(int tokens) {
        promptTokens = tokens;
    }

    /** The requests that reached the stub model provider so far. */
    int modelRequests() {
        return provider.getRequestCount();
    }

    /**
     * Binds a priced review model on the stub provider to practice reviews in {@code workspace}, as its admin does. The
     * admin's workspace context stays set for the caller; the test clears it after each test.
     */
    void bindReviewModel(Workspace workspace) {
        InstanceLlmSettings settings = instanceLlmSettings.findById((short) 1).orElseGet(() -> {
            InstanceLlmSettings created = new InstanceLlmSettings();
            created.setId((short) 1);
            return created;
        });
        settings.setAllowWorkspaceConnections(true);
        instanceLlmSettings.save(settings);
        WorkspaceContext context =
                WorkspaceContext.fromWorkspace(workspace, Set.of(Membership.WorkspaceRole.ADMIN), null);
        WorkspaceContextHolder.setContext(context);
        var connection = connections.create(
                context,
                new CreateWorkspaceLlmConnectionRequestDTO(
                        null,
                        "stub-provider",
                        provider.url("/v1").toString(),
                        LlmApiProtocol.OPENAI_COMPLETIONS,
                        LlmAuthMode.BEARER,
                        "stub-key",
                        true,
                        null));
        Long model = models.create(
                        context,
                        connection.getId(),
                        new CreateWorkspaceLlmModelRequestDTO(
                                null,
                                "review",
                                "review",
                                CONTEXT_WINDOW,
                                MAX_OUTPUT_TOKENS,
                                null,
                                null,
                                null,
                                true,
                                PricingMode.PRICED,
                                PRICE.per1mInputUsd(),
                                PRICE.per1mOutputUsd(),
                                PRICE.per1mCacheReadUsd(),
                                PRICE.per1mCacheWriteUsd(),
                                null,
                                null))
                .getId();
        bindings.upsertBinding(
                context,
                ModelPurpose.PRACTICE_REVIEW,
                DataHandlingTier.UNDECLARED,
                new ModelAssignmentRequestDTO(null, model, null, true));
    }

    /**
     * Runs each queued composition of the review as its review sandbox's loop would, with a model that decides nothing:
     * the run withholds what is open and ends, so the review can end.
     */
    void composeQueued(UUID reviewId) {
        var queued = jdbc.queryForList(
                "SELECT r.workspace_id, r.concurrency_key FROM agent_run r JOIN review_job j ON j.run_id = r.id"
                        + " WHERE j.review_id = ? AND r.kind = 'REVIEW_COMPOSE' AND r.state = 'QUEUED'",
                reviewId);
        for (var row : queued) {
            long workspaceId = ((Number) Objects.requireNonNull(row.get("workspace_id"))).longValue();
            String subject = (String) Objects.requireNonNull(row.get("concurrency_key"));
            var sandbox =
                    new AgentSandboxes.LiveSandbox(UUID.randomUUID(), workspaceId, subject, AgentSandboxKind.REVIEW);
            // The lease and the claim commit together, as the gateway's next does.
            var run = transactions.execute(status -> agentRuns
                    .claimSubject(workspaceId, subject, Set.of(AgentRunKind.REVIEW_COMPOSE))
                    .filter(lease -> composeKind.claimed(sandbox, lease))
                    .map(lease -> new SandboxRun(sandbox, lease.fence(), lease.kind(), lease.attempt()))
                    .orElse(null));
            if (run == null) continue;
            var composing = composeKind.prepare(run);
            compositionClosing.withholdOpen(composing.job(), composing.identity(), CompositionStop.NO_DECISION);
            transactions.executeWithoutResult(status -> composeKind.end(run, SandboxRunKind.Outcome.COMPLETED));
        }
    }
}
