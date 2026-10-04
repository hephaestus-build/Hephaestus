package de.tum.cit.aet.hephaestus.agent.handler;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaults;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Optional;

final class InContextDeliveryGateFixtures {

    private InContextDeliveryGateFixtures() {}

    static InContextDeliveryGate gate(
            PracticeRepository practices, ObservationRepository observations, FeedbackLedgerRecorder ledger) {
        return new InContextDeliveryGate(
                practices, observations, ledger, workspaceDefaults(), workspacesAtTheDefaultJobRevision());
    }

    static WorkspaceReviewDefaultsProvider workspaceDefaults() {
        WorkspaceReviewDefaultsProvider provider = mock(WorkspaceReviewDefaultsProvider.class);
        lenient().when(provider.forWorkspace(anyLong())).thenReturn(WorkspaceReviewDefaults.UNSET);
        return provider;
    }

    static WorkspaceRepository workspacesAtTheDefaultJobRevision() {
        return workspacesAtRevision(new AgentJob().getPracticeRolloutRevision());
    }

    static WorkspaceRepository workspacesAtRevision(long rolloutRevision) {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        lenient().when(repository.findById(anyLong())).thenAnswer(invocation -> {
            Workspace workspace = new Workspace();
            workspace.setId(invocation.getArgument(0));
            workspace.getReviewSettings().setRolloutRevision(rolloutRevision);
            return Optional.of(workspace);
        });
        return repository;
    }

    static WorkspaceRepository noWorkspaces() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        lenient().when(repository.findById(anyLong())).thenReturn(Optional.empty());
        return repository;
    }
}
