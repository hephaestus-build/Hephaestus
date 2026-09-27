package de.tum.cit.aet.hephaestus.integration.scm.gitlab.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaboratorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler.HandleResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceLinkService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * The provider lookup a member event on a connection route is applied from: only a readable empty listing proves no
 * membership.
 */
class GitLabTeamSyncServiceMembershipLookupTest extends BaseUnitTest {

    private static final long GROUP_ID = 501L;
    private static final long USER_ID = 42L;
    private static final String GROUP_PATH = "course/team-1";

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitLabGraphQlResponseHandler responseHandler;

    private GitLabTeamSyncService service;
    private ClientResponseField nodes;
    private HttpGraphQlClient.RequestSpec request;

    @BeforeEach
    void setUp() {
        service = new GitLabTeamSyncService(
                mock(TeamRepository.class),
                mock(TeamMembershipRepository.class),
                mock(RepositoryRepository.class),
                graphQlClientProvider,
                responseHandler,
                mock(GitLabTeamProcessor.class),
                mock(GitLabUserService.class),
                mock(GitLabWorkspaceLinkService.class),
                new GitLabProperties(
                        "https://gitlab.com",
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60),
                        Duration.ofMillis(1),
                        Duration.ofMinutes(5)),
                mock(RepositoryCollaboratorRepository.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                null);
        when(responseHandler.handle(any(), anyString(), any()))
                .thenReturn(new HandleResult(HandleResult.Action.CONTINUE, null));
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField groupId = mock(ClientResponseField.class);
        doReturn("gid://gitlab/Group/" + GROUP_ID).when(groupId).getValue();
        when(response.field("group.id")).thenReturn(groupId);
        nodes = mock(ClientResponseField.class);
        doReturn(List.of()).when(nodes).getValue();
        when(response.field("group.groupMembers.nodes")).thenReturn(nodes);
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        request = mock(HttpGraphQlClient.RequestSpec.class);
        when(graphQlClientProvider.forScope(1L)).thenReturn(client);
        when(client.documentName("GetGroupMember")).thenReturn(request);
        when(request.variable(anyString(), any())).thenReturn(request);
        when(request.execute()).thenReturn(Mono.just(response));
    }

    @Test
    void shouldReportNoMembershipWhenGitLabListsNobody() {
        when(nodes.toEntityList(GitLabGroupMemberResponse.class)).thenReturn(List.of());

        assertThat(service.fetchMembership(1L, GROUP_PATH, GROUP_ID, USER_ID, false))
                .contains(List.of());
        verify(request).variable("relations", List.of("DIRECT", "SHARED_FROM_GROUPS"));
    }

    @Test
    void shouldReportAGrantTheGroupHoldsOnlyThroughAnInvitedGroup() {
        var invitedMaintainer = new GitLabGroupMemberResponse(
                new GitLabGroupMemberResponse.GitLabMemberUser(
                        "gid://gitlab/User/" + USER_ID, "tutor", null, null, null),
                new GitLabGroupMemberResponse.GitLabAccessLevel("MAINTAINER", 40));
        when(nodes.toEntityList(GitLabGroupMemberResponse.class)).thenReturn(List.of(invitedMaintainer));

        assertThat(service.fetchMembership(1L, GROUP_PATH, GROUP_ID, USER_ID, true))
                .as("losing a direct grant keeps the access an invited group still gives")
                .contains(List.of(invitedMaintainer));
        verify(request).variable("relations", List.of("DIRECT", "INHERITED", "SHARED_FROM_GROUPS"));
    }

    @Test
    void shouldReportNothingWhenAListedEntryCannotBeRead() {
        var accessOnly =
                new GitLabGroupMemberResponse(null, new GitLabGroupMemberResponse.GitLabAccessLevel("DEVELOPER", 30));
        when(nodes.toEntityList(GitLabGroupMemberResponse.class)).thenReturn(List.of(accessOnly));

        assertThatThrownBy(() -> service.fetchMembership(1L, GROUP_PATH, GROUP_ID, USER_ID, false))
                .as("an unreadable entry may be this user's, so it must not read as their removal")
                .isInstanceOf(GitLabSyncException.class);
    }
}
