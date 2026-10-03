package de.tum.cit.aet.hephaestus.practices.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;

import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;

class PracticesWorkspacePurgeAdapterTest extends BaseUnitTest {

    @Mock
    private FeedbackRepository feedbackRepository;

    @Mock
    private DeliveryPolicyEvaluationRepository evaluationRepository;

    @Mock
    private FeedbackDispatchRepository dispatchRepository;

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private PracticeRepository practiceRepository;

    @Mock
    private PracticeGroupRepository practiceGroupRepository;

    private PracticesWorkspacePurgeAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PracticesWorkspacePurgeAdapter(
                evaluationRepository,
                dispatchRepository,
                feedbackRepository,
                observationRepository,
                practiceRepository,
                practiceGroupRepository);
    }

    @Test
    void deleteWorkspaceData_deletesAllPracticesData() {
        Long workspaceId = 789L;

        adapter.deleteWorkspaceData(workspaceId);

        // The FK-driven dependency order is load-bearing (feedback has a RESTRICT FK; practices clear the
        // practice -> practice_group references before groups are removed). Assert the ORDER, not just the
        // calls, so a reordering refactor fails the unit test instead of only failing on a real DB.
        InOrder inOrder = inOrder(
                evaluationRepository,
                dispatchRepository,
                feedbackRepository,
                observationRepository,
                practiceRepository,
                practiceGroupRepository);
        inOrder.verify(evaluationRepository).deleteAllByWorkspaceId(workspaceId);
        inOrder.verify(dispatchRepository).deleteAllByWorkspaceId(workspaceId);
        inOrder.verify(feedbackRepository).deleteAllByWorkspaceId(workspaceId);
        inOrder.verify(observationRepository).deleteAllByPracticeWorkspaceId(workspaceId);
        inOrder.verify(practiceRepository).deleteAllByWorkspaceId(workspaceId);
        inOrder.verify(practiceGroupRepository).deleteAllByWorkspaceId(workspaceId);
    }

    @Test
    @DisplayName("runs before default-order purge contributors")
    void getOrder_returnsNegativeValue() {
        assertThat(adapter.getOrder()).isLessThan(0);
    }
}
