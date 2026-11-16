package de.tum.cit.aet.hephaestus.practices.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** A practice with review history cannot be removed, and the refusal names the tool that stops its reviews instead. */
@Tag("unit")
class RemovePracticeToolTest {

    @AfterEach
    void clearTheWorkspace() {
        WorkspaceContextHolder.clearContext();
    }

    @Test
    void shouldNameUpdatePracticeWhenThePracticeHasReviewHistory() {
        WorkspaceContextHolder.setContext(new WorkspaceContext(10L, "workspace", "Workspace", null, null, Set.of()));
        PracticeService practices = mock(PracticeService.class);
        doThrow(new ResponseStatusException(
                        HttpStatus.CONFLICT, "This practice has recorded review history and cannot be deleted."))
                .when(practices)
                .deletePractice(any(), eq("reviewed-before"));
        var tool = new RemovePracticeTool(practices, JsonMapper.builder().build());

        assertThatThrownBy(() -> tool.call(Map.of("slug", "reviewed-before")))
                .isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refused.getReason()).contains("review history").contains("update_practice");
                });
    }
}
