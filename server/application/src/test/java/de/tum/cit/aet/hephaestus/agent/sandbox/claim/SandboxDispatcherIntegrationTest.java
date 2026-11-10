package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxDispatcher;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAttempt;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@DisplayName("Sandbox dispatch over PostgreSQL Integration")
class SandboxDispatcherIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AgentSandboxRepository repository;

    @Autowired
    private AgentRuns runs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final FakeSandboxClaims claims = new FakeSandboxClaims();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private AgentSandboxes sandboxes;
    private long workspace;

    @BeforeEach
    void setUp() {
        // The dispatch offers the due runs of every workspace, so no run of another test may wait.
        databaseTestUtils.cleanDatabase();
        sandboxes = new AgentSandboxes(
                repository,
                runs,
                claims,
                new AttemptTokenIssuer(AttemptKeyFixtures.keyring(AttemptSigningKey.generate())),
                transactions,
                "http://hephaestus-sandbox-gateway.hephaestus.svc:8081",
                host -> "10.43.0.12",
                meters);
        workspace = workspaces
                .save(TestEntities.activeWorkspace("dispatch-" + UUID.randomUUID()))
                .getId();
    }

    private SandboxDispatcher dispatcher(int reviewSandboxes) {
        return new SandboxDispatcher(
                sandboxes,
                runs,
                List.of(new Review()),
                Map.of(AgentSandboxKind.REVIEW, reviewSandboxes),
                new ReviewAttemptCustody(runs),
                meters);
    }

    private String queue() {
        String subject = "review:" + UUID.randomUUID();
        transactions.executeWithoutResult(
                status -> runs.enqueue(workspace, AgentRunKind.REVIEW, AgentRunBand.BATCH, subject, null));
        return subject;
    }

    private int sandboxesOf(String subject) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM agent_sandbox WHERE subject_key = ? AND state IN ('REQUESTED', 'READY')",
                Integer.class,
                subject));
    }

    @Test
    void shouldRequestTheSandboxesOfDueRunsInClaimOrderWithinTheKindsLimit() {
        String first = queue();
        String second = queue();
        String third = queue();

        dispatcher(2).dispatch();

        assertThat(sandboxesOf(first)).isOne();
        assertThat(sandboxesOf(second)).isOne();
        assertThat(sandboxesOf(third)).as("past the limit").isZero();
        assertThat(claims.creates).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE state = 'LEASED'", Integer.class))
                .as("the dispatch leases nothing")
                .isZero();
    }

    @Test
    void shouldLeaveASubjectThatHasItsSandboxToThatSandbox() {
        String subject = queue();
        sandboxes.ensure(workspace, subject, AgentSandboxKind.REVIEW);

        dispatcher(5).dispatch();

        assertThat(sandboxesOf(subject)).isOne();
        assertThat(claims.creates).hasSize(1);
    }

    @Test
    void shouldGiveOneRunOneSandboxWhenTwoDispatchersReadItAtOnce() throws Exception {
        String subject = queue();
        var start = new CountDownLatch(1);
        try (var replicas = Executors.newFixedThreadPool(2)) {
            var passes = List.of(dispatcher(5), dispatcher(5)).stream()
                    .map(replica -> replicas.submit(() -> {
                        start.await();
                        replica.dispatch();
                        return null;
                    }))
                    .toList();
            start.countDown();
            for (var pass : passes) pass.get();
        }

        assertThat(sandboxesOf(subject)).isOne();
        assertThat(claims.creates).hasSize(1);
    }

    @Test
    void shouldCountAFailedPassAndRequestTheSandboxAtTheNextOne() {
        String subject = queue();
        claims.failCreates = true;
        var dispatcher = dispatcher(5);

        dispatcher.dispatch();

        assertThat(meters.counter("agent.sandbox.dispatch.failure").count()).isEqualTo(1.0);
        claims.failCreates = false;
        dispatcher.dispatch();
        assertThat(sandboxesOf(subject)).isOne();
    }

    /** A run kind that review sandboxes drive; the dispatch reads only its kind and its sandbox. */
    private static final class Review implements SandboxRunKind {
        @Override
        public AgentRunKind kind() {
            return AgentRunKind.REVIEW;
        }

        @Override
        public AgentSandboxKind sandbox() {
            return AgentSandboxKind.REVIEW;
        }

        @Override
        public boolean claimed(SandboxRun run) {
            return true;
        }

        @Override
        public Optional<ProxyAttempt> attempt(SandboxRun run) {
            return Optional.empty();
        }

        @Override
        public JsonNode context(SandboxRun run) {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public boolean end(SandboxRun run, Outcome outcome) {
            return true;
        }
    }
}
