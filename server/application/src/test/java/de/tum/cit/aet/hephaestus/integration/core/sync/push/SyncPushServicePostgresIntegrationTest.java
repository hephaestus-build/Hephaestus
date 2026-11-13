package de.tum.cit.aet.hephaestus.integration.core.sync.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobStatus;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncStateChangedEvent;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextArgumentResolver;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

class SyncPushServicePostgresIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private DataSourceProperties dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private SyncJobRepository jobs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private SyncEventHub originHub;

    @Autowired
    private SyncPushService origin;

    private SyncPushService sibling;
    private SyncEventHub siblingHub;
    private MvcResult originStream;
    private MvcResult siblingStream;
    private Workspace workspace;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        long installationId = Math.max(1, UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.installationWorkspace(installationId, "sync-replicas"),
                installationId);
        connection = connections
                .findActive(workspace.getId(), IntegrationKind.GITHUB)
                .orElseThrow();
        var mapper = new ObjectMapper();
        siblingHub = new SyncEventHub(mapper, new SimpleMeterRegistry(), Duration.ZERO, () -> new SseEmitter(0L));
        sibling = new SyncPushService(siblingHub, mapper, dataSource, jobs, new SimpleMeterRegistry());
        sibling.startListening();
        originStream = subscribe(originHub);
        siblingStream = subscribe(siblingHub);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(listening(origin.listenerName())).isTrue();
            assertThat(listening(sibling.listenerName())).isTrue();
        });
    }

    @AfterEach
    void tearDown() {
        cancel(originStream);
        cancel(siblingStream);
        if (sibling != null) sibling.shutdown();
        if (siblingHub != null) siblingHub.shutdown();
    }

    @Test
    void shouldSendOneCommittedSyncHintToEachReplicaAndNoneForARolledBackChange() {
        transactions.executeWithoutResult(tx -> {
            events.publishEvent(new SyncStateChangedEvent(
                    workspace.getId(),
                    connection.getId(),
                    IntegrationKind.GITHUB,
                    SyncStateChangedEvent.Scope.RESOURCES));
            tx.setRollbackOnly();
        });
        var job = new SyncJob(
                workspace, connection, IntegrationKind.GITHUB, SyncJobType.INITIAL, SyncJobTrigger.MANUAL, null);
        transactions.executeWithoutResult(tx -> {
            jobs.saveAndFlush(job);
            events.publishEvent(new SyncStateChangedEvent(
                    workspace.getId(), connection.getId(), IntegrationKind.GITHUB, SyncStateChangedEvent.Scope.JOB));
        });
        assertThat(jobs.findByIdAndWorkspace_Id(job.getId(), workspace.getId()))
                .get()
                .extracting(SyncJob::getStatus)
                .isEqualTo(SyncJobStatus.PENDING);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            for (var stream : new MvcResult[] {originStream, siblingStream}) {
                assertThat(stream.getResponse().getContentAsString().lines().filter(frame -> frame.startsWith("data:")))
                        .singleElement()
                        .satisfies(frame -> assertThat(frame)
                                .contains("\"scope\":\"job\"")
                                .contains("\"connectionId\":" + connection.getId()));
            }
        });
    }

    private boolean listening(String name) {
        return Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE application_name = ? "
                                + "AND query IN ('LISTEN sync_status', 'SELECT 1') AND state = 'idle'",
                        Integer.class,
                        name))
                == 1;
    }

    private MvcResult subscribe(SyncEventHub hub) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new SyncEventsController(hub))
                .setCustomArgumentResolvers(new WorkspaceContextArgumentResolver())
                .build();
        WorkspaceContextHolder.setContext(new WorkspaceContext(
                workspace.getId(), workspace.getWorkspaceSlug(), workspace.getDisplayName(), null, null, Set.of()));
        try {
            return mvc.perform(get("/sync/events").accept("text/event-stream"))
                    .andExpect(status().isOk())
                    .andExpect(request().asyncStarted())
                    .andReturn();
        } finally {
            WorkspaceContextHolder.clearContext();
        }
    }

    private static void cancel(MvcResult stream) {
        if (stream != null && stream.getRequest().isAsyncStarted())
            Objects.requireNonNull(stream.getRequest().getAsyncContext()).complete();
    }
}
