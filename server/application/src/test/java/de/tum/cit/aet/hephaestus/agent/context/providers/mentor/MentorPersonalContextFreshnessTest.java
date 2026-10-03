package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

class MentorPersonalContextFreshnessTest extends BaseUnitTest {
    enum Source {
        USER,
        WORKSPACE,
        AUTHORED_WORK
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void shouldReadChangedProfileForTheNextTurnWithoutReusingFormattedPersonalContent(Source kind) throws Exception {
        var users = mock(UserRepository.class);
        var queries = mock(MentorContextQueryRepository.class);
        var mapper = new ObjectMapper();
        ContentSource source =
                switch (kind) {
                    case USER -> {
                        when(queries.fetchUserCounts(
                                        eq(1L), eq(2L), any(Instant.class), any(Instant.class), any(Instant.class)))
                                .thenReturn(new MentorUserCounts(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L));
                        yield new UserContentSource(users, queries, mapper);
                    }
                    case WORKSPACE -> {
                        var workspaces = mock(WorkspaceRepository.class);
                        var workspace = new Workspace();
                        workspace.setWorkspaceSlug("context-freshness");
                        when(workspaces.findById(1L)).thenReturn(Optional.of(workspace));
                        yield new WorkspaceContentSource(users, workspaces, queries, mapper);
                    }
                    case AUTHORED_WORK -> new RecentAuthoredWorkContentSource(users, queries, mapper);
                };
        var original = new User();
        original.setLogin("PROFILE-BEFORE-ERASURE");
        when(users.findById(2L)).thenReturn(Optional.of(original));
        var request = new ContextRequest.MentorChatRequest(1L, 2L, UUID.randomUUID());
        var first = new HashMap<String, byte[]>();
        source.contribute(request, first);
        assertThat(first).hasSize(1);
        var before = mapper.readTree(first.values().iterator().next());
        assertThat(before.path("user").path("login").asString()).isEqualTo("PROFILE-BEFORE-ERASURE");

        var erased = new User();
        erased.setLogin("erased-2");
        when(users.findById(2L)).thenReturn(Optional.of(erased));
        var second = new HashMap<String, byte[]>();
        source.contribute(request, second);
        assertThat(second).hasSize(1);
        var after = mapper.readTree(second.values().iterator().next());
        assertThat(after.path("user").path("login").asString()).isEqualTo("erased-2");
        assertThat(after.toString()).doesNotContain("PROFILE-BEFORE-ERASURE");
    }
}
