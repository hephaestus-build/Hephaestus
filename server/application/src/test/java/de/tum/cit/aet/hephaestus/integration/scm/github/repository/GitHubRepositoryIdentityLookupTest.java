package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialReader;
import de.tum.cit.aet.hephaestus.integration.core.graphql.FragmentMergingDocumentSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryIdentityLookup.RepositoryIdentity;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlFragments;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class GitHubRepositoryIdentityLookupTest extends BaseUnitTest {
    private static final long WORKSPACE_ID = 41L;
    private static final String TOKEN = "synthetic-token-stored-with-the-connection";

    private final GitHubGraphQlClientProvider clients = mock(GitHubGraphQlClientProvider.class);
    private final ConnectionRepository connections = mock(ConnectionRepository.class);
    private final CredentialReader credentials = mock(CredentialReader.class);
    private final GitHubSyncProperties properties = mock(GitHubSyncProperties.class);
    private final Connection connection = mock(Connection.class);
    private final AtomicInteger requests = new AtomicInteger();

    private GitHubRepositoryIdentityLookup lookup(@Nullable String serverUrl, String answer) {
        // Shared by every case; a refused case never reaches some of them.
        lenient()
                .when(connection.getConfig())
                .thenReturn(new ConnectionConfig.GitHubPatConfig("shared-org", serverUrl, Set.of()));
        lenient()
                .when(connections.findActive(WORKSPACE_ID, IntegrationKind.GITHUB))
                .thenReturn(Optional.of(connection));
        lenient().when(credentials.credentialsOf(connection)).thenReturn(Optional.of(new BearerToken(TOKEN, null)));
        lenient().when(properties.graphqlTimeout()).thenReturn(Duration.ofSeconds(5));
        lenient().when(clients.withToken(TOKEN)).thenReturn(client(answer));
        return new GitHubRepositoryIdentityLookup(clients, connections, credentials, properties);
    }

    @Test
    void shouldConfirmTheIdAndCurrentNameGitHubReportsToTheTokenItsConnectionStores() {
        var lookup = lookup(null, """
                {"data":{"repository":{"id":"R_kgDO","databaseId":2147483647,
                  "nameWithOwner":"Shared-Org/Private-Repository","isPrivate":true}}}
                """);

        assertThat(lookup.resolve(WORKSPACE_ID, "shared-org/private-repository"))
                .contains(new RepositoryIdentity(2_147_483_647L, "Shared-Org/Private-Repository"));
        assertThat(requests).hasValue(1);
        // One read of the connection supplies the token; the scope is not resolved again, and no REST seeding runs.
        verify(connections, times(1)).findActive(WORKSPACE_ID, IntegrationKind.GITHUB);
        verify(credentials, times(1)).credentialsOf(connection);
        verify(clients).withToken(TOKEN);
        verify(clients, never()).forScope(anyLong());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // The token cannot see the repository.
                """
                {"data":{"repository":null},"errors":[{"type":"NOT_FOUND","path":["repository"],
                  "message":"Could not resolve to a Repository with the name 'shared-org/private-repository'."}]}
                """,
                // A refusal with nothing else.
                """
                {"data":null,"errors":[{"type":"FORBIDDEN","message":"Resource not accessible by personal access token"}]}
                """,
                // A partial answer: a repository alongside an error.
                """
                {"data":{"repository":{"databaseId":9101,"nameWithOwner":"shared-org/private-repository"}},
                 "errors":[{"type":"RATE_LIMITED","message":"partial"}]}
                """,
                // No id, or no name, confirms nothing.
                """
                {"data":{"repository":{"nameWithOwner":"shared-org/private-repository"}}}
                """,
                """
                {"data":{"repository":{"databaseId":9101}}}
                """
            })
    void shouldConfirmNothingFromARefusedPartialOrIncompleteAnswer(String answer) {
        assertThat(lookup(null, answer).resolve(WORKSPACE_ID, "shared-org/private-repository"))
                .isEmpty();
        assertThat(requests).hasValue(1);
    }

    @Test
    void shouldNeitherReadNorSendATokenStoredForAnotherServer() {
        var lookup = lookup("https://github.enterprise.example.test", """
                {"data":{"repository":{"databaseId":9101,"nameWithOwner":"shared-org/private-repository"}}}
                """);

        assertThat(lookup.resolve(WORKSPACE_ID, "shared-org/private-repository"))
                .isEmpty();
        verify(credentials, never()).credentialsOf(any());
        verify(clients, never()).withToken(anyString());
        verify(clients, never()).forScope(anyLong());
        assertThat(requests).hasValue(0);
    }

    @Test
    void shouldAskNothingWithoutAStoredToken() {
        var lookup = lookup(null, """
                {"data":{"repository":{"databaseId":9101,"nameWithOwner":"shared-org/private-repository"}}}
                """);
        lenient().when(credentials.credentialsOf(connection)).thenReturn(Optional.empty());

        assertThat(lookup.resolve(WORKSPACE_ID, "shared-org/private-repository"))
                .isEmpty();
        verify(clients, never()).withToken(anyString());
        assertThat(requests).hasValue(0);
    }

    @Test
    void shouldNotAskForAPathThatNamesNoRepository() {
        var lookup = lookup(null, """
                {"data":{"repository":{"databaseId":9101,"nameWithOwner":"shared-org/private-repository"}}}
                """);

        assertThat(lookup.resolve(WORKSPACE_ID, "shared-org")).isEmpty();
        verify(connections, never()).findActive(anyLong(), any());
        verify(clients, never()).withToken(anyString());
    }

    private HttpGraphQlClient client(String answer) {
        return HttpGraphQlClient.builder(WebClient.builder().exchangeFunction(request -> {
                    requests.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(answer)
                            .build());
                }))
                .url("https://api.github.com/graphql")
                .documentSource(new FragmentMergingDocumentSource(
                        List.of(
                                new ClassPathResource("graphql/github/operations/"),
                                new ClassPathResource("graphql/github/fragments/")),
                        List.of(".graphql", ".gql"),
                        List.of(
                                new ClassPathResource(GitHubGraphQlFragments.ACTOR_FRAGMENTS_RESOURCE),
                                new ClassPathResource(GitHubGraphQlFragments.COMMIT_ENRICHMENT_FIELDS_RESOURCE))))
                .build();
    }
}
