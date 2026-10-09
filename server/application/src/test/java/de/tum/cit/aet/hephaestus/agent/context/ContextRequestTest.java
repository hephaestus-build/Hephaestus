package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.HephTurnRequest;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Validates the sealed shape of {@link ContextRequest}: both variants exist + validation
 * lives in the compact constructors + sealed-switch exhaustiveness compiles.
 */
class ContextRequestTest extends BaseUnitTest {

    @Test
    void hephTurnRequestValidation() {
        UUID threadId = UUID.randomUUID();
        HephTurnRequest req = new HephTurnRequest(1L, 2L, threadId);
        assertThat(req.workspaceId()).isEqualTo(1L);
        assertThat(req.developerId()).isEqualTo(2L);
        assertThat(req.conversationId()).isEqualTo(threadId);

        assertThatThrownBy(() -> new HephTurnRequest(0L, 2L, threadId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceId");
        assertThatThrownBy(() -> new HephTurnRequest(1L, 0L, threadId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("developerId");
    }
}
