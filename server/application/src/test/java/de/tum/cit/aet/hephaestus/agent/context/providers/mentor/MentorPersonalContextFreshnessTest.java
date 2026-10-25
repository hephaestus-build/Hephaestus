package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSummaryQuery;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.MentorUserCounts;
import de.tum.cit.aet.hephaestus.integration.core.spi.MentorWorkQuery;
import de.tum.cit.aet.hephaestus.integration.scm.context.MentorContextQueryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.context.RecentAuthoredWorkContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.context.ScmMentorWorkQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        var queries = mock(MentorWorkQuery.class);
        var accounts = mock(AccountSummaryQuery.class);
        var mapper = new ObjectMapper();
        var actorSelector = mock(WorkspaceActorSelector.class);
        lenient().when(actorSelector.connectedProviderId(1L)).thenReturn(Optional.of(5L));
        ContentSource source = switch (kind) {
            case USER -> {
                when(queries.fetchUserCounts(
                                eq(1L), eq(2L), any(Instant.class), any(Instant.class), any(Instant.class)))
                        .thenReturn(new MentorUserCounts(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L));
                yield new UserContentSource(accounts, queries, actorSelector, mapper);
            }
            case WORKSPACE -> {
                var workspaces = mock(WorkspaceRepository.class);
                var workspace = new Workspace();
                workspace.setWorkspaceSlug("context-freshness");
                when(workspaces.findById(1L)).thenReturn(Optional.of(workspace));
                yield new WorkspaceContentSource(accounts, workspaces, queries, actorSelector, mapper);
            }
            case AUTHORED_WORK ->
                new RecentAuthoredWorkContentSource(
                        accounts, mock(MentorContextQueryRepository.class), mock(ScmMentorWorkQuery.class), mapper);
        };
        when(accounts.findAllByIds(List.of(2L)))
                .thenReturn(Map.of(2L, new AccountSummaryQuery.AccountSummary(2, "PROFILE-BEFORE-ERASURE", null)));
        var request = new MentorContextRequest(1L, 2L, UUID.randomUUID(), null);
        var first = new HashMap<String, byte[]>();
        source.contribute(request, first);
        assertThat(first).hasSize(1);
        var before = mapper.readTree(first.values().iterator().next());
        assertThat((kind == Source.AUTHORED_WORK
                                ? before.path("account").path("displayName")
                                : before.path("user").path("name"))
                        .asString())
                .isEqualTo("PROFILE-BEFORE-ERASURE");

        when(accounts.findAllByIds(List.of(2L)))
                .thenReturn(Map.of(2L, new AccountSummaryQuery.AccountSummary(2, "erased-2", null)));
        var second = new HashMap<String, byte[]>();
        source.contribute(request, second);
        assertThat(second).hasSize(1);
        var after = mapper.readTree(second.values().iterator().next());
        assertThat((kind == Source.AUTHORED_WORK
                                ? after.path("account").path("displayName")
                                : after.path("user").path("name"))
                        .asString())
                .isEqualTo("erased-2");
        assertThat(after.toString()).doesNotContain("PROFILE-BEFORE-ERASURE");
    }
}
