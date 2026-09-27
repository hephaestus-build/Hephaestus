package de.tum.cit.aet.hephaestus.integration.scm.gitlab.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaborator;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaboratorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler.HandleResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceLinkService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

class GitLabTeamSyncServiceTest extends BaseUnitTest {

    private static final long PROVIDER_ID = 100L;
    private static final String ROOT = "course";
    private static final String TEAM_PATH = "course/team-1";

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitLabGraphQlResponseHandler responseHandler;

    @Mock
    private GitLabWorkspaceLinkService workspaceLinkService;

    @Mock
    private RepositoryCollaboratorRepository collaboratorRepository;

    private GitLabTeamSyncService service;
    private HttpGraphQlClient.RequestSpec request;
    private HttpGraphQlClient client;
    private Team team;

    @BeforeEach
    void setUp() {
        service = new GitLabTeamSyncService(
                teamRepository,
                mock(TeamMembershipRepository.class),
                mock(RepositoryRepository.class),
                graphQlClientProvider,
                responseHandler,
                mock(GitLabTeamProcessor.class),
                mock(GitLabUserService.class),
                workspaceLinkService,
                new GitLabProperties(
                        "https://gitlab.com",
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60),
                        Duration.ofMillis(1),
                        Duration.ofMinutes(5)),
                collaboratorRepository,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                null);
        when(responseHandler.handle(any(), anyString(), any()))
                .thenReturn(new HandleResult(HandleResult.Action.CONTINUE, null));
        when(responseHandler.isWholePage(any(), anyString())).thenReturn(true);
        lenient()
                .when(workspaceLinkService.groupWriteProvider(anyLong(), anyString()))
                .thenReturn(Optional.of(TestEntities.gitProvider(PROVIDER_ID, IdentityProviderType.GITLAB)));
        team = new Team();
        team.setId(7L);
        lenient().when(teamRepository.findById(7L)).thenReturn(Optional.of(team));

        // The team's direct listing is complete and empty.
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField nodes = mock(ClientResponseField.class);
        when(nodes.toEntityList(GitLabGroupMemberResponse.class)).thenReturn(List.of());
        when(response.field("group.groupMembers.nodes")).thenReturn(nodes);
        ClientResponseField pageInfo = mock(ClientResponseField.class);
        when(pageInfo.toEntity(GitLabPageInfo.class)).thenReturn(new GitLabPageInfo(false, null));
        when(response.field("group.groupMembers.pageInfo")).thenReturn(pageInfo);
        client = mock(HttpGraphQlClient.class);
        request = mock(HttpGraphQlClient.RequestSpec.class);
        when(client.documentName("GetGroupMembers")).thenReturn(request);
        when(request.variable(anyString(), any())).thenReturn(request);
        when(request.execute()).thenReturn(Mono.just(response));
    }

    @Test
    void shouldListATeamsOwnAndInvitedGroupsMembersButNotInheritedOnes() {
        service.syncTeamMembers(client, 1L, ROOT, 7L, TEAM_PATH, PROVIDER_ID);

        verify(request).variable("relations", List.of("DIRECT", "SHARED_FROM_GROUPS"));
    }

    @Test
    void shouldKeepAProjectOnlyStudentAndDropOthersOnceBothSourcesAreRead() {
        User student = user(1L);
        User formerMember = user(2L);
        team.addMembership(new TeamMembership(team, student, TeamMembership.Role.MEMBER));
        team.addMembership(new TeamMembership(team, formerMember, TeamMembership.Role.MEMBER));
        when(collaboratorRepository.findByOrgLoginAndPermissions(
                        TEAM_PATH,
                        PROVIDER_ID,
                        List.of(RepositoryCollaborator.Permission.WRITE, RepositoryCollaborator.Permission.TRIAGE)))
                .thenReturn(List.of(new RepositoryCollaborator(
                        new Repository(), student, RepositoryCollaborator.Permission.WRITE)));

        var result = service.syncTeamMembers(client, 1L, ROOT, 7L, TEAM_PATH, PROVIDER_ID);

        assertThat(result.complete()).isTrue();
        assertThat(team.getMemberships())
                .extracting(membership -> membership.getUser().getId())
                .containsExactly(1L);
    }

    @Test
    void shouldRemoveNobodyWhenTheCollaboratorSourceCannotBeRead() {
        User student = user(1L);
        team.addMembership(new TeamMembership(team, student, TeamMembership.Role.MEMBER));
        when(collaboratorRepository.findByOrgLoginAndPermissions(anyString(), anyLong(), anyList()))
                .thenThrow(new DataAccessResourceFailureException("collaborators unavailable"));

        assertThatThrownBy(() -> service.syncTeamMembers(client, 1L, ROOT, 7L, TEAM_PATH, PROVIDER_ID))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(team.getMemberships())
                .extracting(membership -> membership.getUser().getId())
                .containsExactly(1L);
    }

    @Test
    void shouldChangeNothingOnceThisScopeNoLongerOwnsTheGroup() {
        team.addMembership(new TeamMembership(team, user(1L), TeamMembership.Role.MEMBER));
        when(workspaceLinkService.groupWriteProvider(anyLong(), anyString())).thenReturn(Optional.empty());

        var result = service.syncTeamMembers(client, 1L, ROOT, 7L, TEAM_PATH, PROVIDER_ID);

        assertThat(result.complete()).isFalse();
        assertThat(team.getMemberships()).hasSize(1);
    }

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
