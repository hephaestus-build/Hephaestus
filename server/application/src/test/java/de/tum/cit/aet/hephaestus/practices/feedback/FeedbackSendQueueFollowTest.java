package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.action.LaneAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.authority.AuthorizationStamp;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.UnchangedAuthority;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceMemberships;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** The correction that follows a send keeps the authority that the send was queued under (ADR 0134). */
class FeedbackSendQueueFollowTest extends BaseUnitTest {

    @Test
    void shouldQueueTheFollowingCorrectionUnderTheStampOfTheSendItFollows() {
        var actions = mock(AgentActions.class);
        var queue = new FeedbackSendQueue(
                actions,
                new UnchangedAuthority(),
                mock(WorkspaceMemberships.class),
                mock(AccountDirectory.class),
                JsonMapper.builder().build(),
                mock(FeedbackCopyRepository.class),
                mock(FeedbackVisibilityService.class),
                mock(ConfigAuditPort.class));
        UUID id = UUID.randomUUID();
        var row = Feedback.builder().id(id).workspaceId(1).developerId(2).build();
        // The ended send was approved by admin 3 under older generations than the current ones.
        var approved = new AuthorizationStamp(4, Map.of(2L, 1L, 3L, 5L));
        when(actions.newestInLane(1L, "feedback:" + id))
                .thenReturn(Optional.of(new LaneAction(
                        UUID.randomUUID(),
                        ActionKind.DELIVER_FEEDBACK,
                        "feedback:" + id + ":1:POST",
                        ActionState.CANCELLED,
                        1,
                        false,
                        Instant.EPOCH,
                        null,
                        Instant.EPOCH,
                        Instant.EPOCH,
                        approved)));

        queue.follow(row, FeedbackSendAction.RETRACT);

        var request = ArgumentCaptor.forClass(ActionRequest.class);
        verify(actions).enqueue(request.capture());
        assertThat(request.getValue().authority())
                .isNotNull()
                .satisfies(authority -> assertThat(authority.stamp()).isEqualTo(approved));
    }
}
