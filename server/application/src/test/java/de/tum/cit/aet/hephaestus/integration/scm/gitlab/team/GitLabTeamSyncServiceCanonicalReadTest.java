package de.tum.cit.aet.hephaestus.integration.scm.gitlab.team;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler.HandleResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

@Tag("unit")
class GitLabTeamSyncServiceCanonicalReadTest {

    private static final String GROUP = "hephaestustest/introcourse";

    private final GitLabGraphQlClientProvider clients = mock(GitLabGraphQlClientProvider.class);
    private final GitLabGraphQlResponseHandler handler = mock(GitLabGraphQlResponseHandler.class);
    private final ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
    private GitLabTeamSyncService service;

    @BeforeEach
    void setUp() {
        GitLabProperties properties = mock(GitLabProperties.class);
        when(properties.graphqlTimeout()).thenReturn(Duration.ofSeconds(1));
        service = new GitLabTeamSyncService(
                mock(), mock(), mock(), clients, handler, mock(), mock(), mock(), properties, mock(), mock(), null);
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec request = mock(HttpGraphQlClient.RequestSpec.class);
        when(clients.forScope(3L)).thenReturn(client);
        when(client.documentName(anyString())).thenReturn(request);
        when(request.variable(anyString(), any())).thenReturn(request);
        when(request.execute()).thenReturn(Mono.just(response));
        when(handler.handle(any(), anyString(), any()))
                .thenReturn(new HandleResult(HandleResult.Action.CONTINUE, null));
        ClientResponseField groupId = mock(ClientResponseField.class);
        when(groupId.getValue()).thenReturn("gid://gitlab/Group/42");
        when(response.field("group.id")).thenReturn(groupId);
        when(response.field("group.groupMembers.nodes")).thenReturn(mock(ClientResponseField.class));
    }

    @Test
    void shouldRetryRatherThanReadAPartialMembershipAnswerAsNoMembership() {
        when(response.getErrors()).thenReturn(List.of(mock(ResponseError.class)));

        assertThatThrownBy(() -> service.fetchMembership(3L, GROUP, 42L, 9L, true))
                .isInstanceOf(GitLabSyncException.class);
    }

    @Test
    void shouldRetryWhenGitLabListsNoMembersFieldAtAll() {
        when(response.getErrors()).thenReturn(List.of());

        assertThatThrownBy(() -> service.fetchMembership(3L, GROUP, 42L, 9L, true))
                .isInstanceOf(GitLabSyncException.class);
    }
}
