package de.tum.cit.aet.hephaestus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelWorkspaceGrantRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;

class AgentWorkspacePurgeAdapterTest extends BaseUnitTest {

    @Mock
    private ReviewJobRepository jobRepository;

    @Mock
    private WorkspaceAgentBindingRepository bindingRepository;

    @Mock
    private WorkspaceLlmModelRepository modelRepository;

    @Mock
    private WorkspaceLlmConnectionRepository connectionRepository;

    @Mock
    private LlmModelWorkspaceGrantRepository grantRepository;

    @Mock
    private AgentRuns runs;

    private AgentWorkspacePurgeAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AgentWorkspacePurgeAdapter(
                jobRepository, runs, bindingRepository, modelRepository, connectionRepository, grantRepository);
    }

    @Test
    void shouldDeleteWorkspaceOwnedAgentDataInDependencyOrder() {
        adapter.deleteWorkspaceData(42L);

        InOrder order =
                inOrder(jobRepository, runs, bindingRepository, modelRepository, connectionRepository, grantRepository);
        order.verify(jobRepository).deleteAllByWorkspaceId(42L);
        order.verify(runs).deleteWorkspace(42L);
        order.verify(bindingRepository).deleteAllByWorkspaceId(42L);
        order.verify(modelRepository).deleteAllByWorkspaceId(42L);
        order.verify(connectionRepository).deleteAllByWorkspaceId(42L);
        order.verify(grantRepository).deleteAllByWorkspaceId(42L);
    }

    @Test
    void shouldRunAfterPracticesCleanup() {
        assertThat(adapter.getOrder()).isGreaterThan(-100);
    }
}
