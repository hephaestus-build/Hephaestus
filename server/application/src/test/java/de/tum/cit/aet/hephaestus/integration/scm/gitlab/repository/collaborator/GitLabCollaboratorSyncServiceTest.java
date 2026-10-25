package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.collaborator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.SilentModeGraphQlClientFactory;
import de.tum.cit.aet.hephaestus.integration.core.egress.SilentModeGraphQlInterceptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaborator;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaboratorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGraphQlConfig;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials.GitLabCredentialHealth;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials.GitLabCredentialHealthFilter;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabAdmissionTestFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceLinkService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class GitLabCollaboratorSyncServiceTest extends BaseUnitTest {

    /** A project member GitLab names, with developer access. */
    private static final String STUDENT = """
            {"user":{"id":"gid://gitlab/User/21","username":"student","name":"Student","avatarUrl":null,
             "webUrl":"https://gitlab.example.test/student","bot":false},
             "accessLevel":{"stringValue":"DEVELOPER","integerValue":30}}
            """;

    private final GitLabProjectAdmission projectAdmission = mock(GitLabProjectAdmission.class);

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private RepositoryCollaboratorRepository collaboratorRepository;

    @Mock
    private GitLabWorkspaceLinkService workspaceLinkService;

    @Test
    void shouldLeaveASharedProjectsCollaboratorsToTheWorkspaceThatOwnsIt() {
        var service = new GitLabCollaboratorSyncService(
                collaboratorRepository,
                graphQlClientProvider,
                mock(GitLabGraphQlResponseHandler.class),
                mock(GitLabUserService.class),
                mock(GitLabProperties.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                workspaceLinkService,
                projectAdmission);
        Repository project = new Repository();
        project.setId(11L);
        project.setNameWithOwner("course/intro/team-1/demo");
        project.setProvider(TestEntities.gitProvider(10L, IdentityProviderType.GITLAB));
        GitLabAdmissionTestFixtures.permit(projectAdmission, 2L, project, 7L);
        when(workspaceLinkService.mayWriteRepository(2L, project)).thenReturn(false);

        var result = service.syncCollaboratorsForRepository(2L, project);

        assertThat(result.isCompleted())
                .as("a reading this scope may not apply is not a complete answer")
                .isFalse();
        verifyNoInteractions(graphQlClientProvider, collaboratorRepository);
    }

    @Test
    void shouldGrantOnlyTheNamedMemberWhenTheProductionCodecsReadAPendingInvitationBesideThem() {
        Repository project = project();
        User student = user(21L, "student");
        List<RepositoryCollaborator> stored = stored(
                project,
                new RepositoryCollaborator(project, student, RepositoryCollaborator.Permission.READ),
                new RepositoryCollaborator(project, user(22L, "former"), RepositoryCollaborator.Permission.WRITE));
        GitLabUserService users = mock(GitLabUserService.class);
        // Only the named member resolves; a user derived from the invitation would stop the sync unresolved.
        when(users.findOrCreateUser(any(GitLabUserLookup.class), eq(10L)))
                .thenAnswer(invocation -> "student"
                                .equals(invocation
                                        .<GitLabUserLookup>getArgument(0)
                                        .username())
                        ? student
                        : null);
        when(graphQlClientProvider.forScope(2L)).thenReturn(productionCodecClient(page("""
                {"user":null,"accessLevel":{"stringValue":"DEVELOPER","integerValue":30}}
                """, STUDENT)));

        var result = service(users).syncCollaboratorsForRepository(2L, project);

        assertThat(result).isEqualTo(SyncResult.completed(1));
        assertThat(stored)
                .extracting(collaborator -> collaborator.getUser().getLogin(), RepositoryCollaborator::getPermission)
                .as("the complete listing grants the member and removes whom it no longer lists")
                .containsExactly(tuple("student", RepositoryCollaborator.Permission.WRITE));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(
            strings = {
                "{\"accessLevel\":{\"stringValue\":\"DEVELOPER\",\"integerValue\":30}}",
                "{\"user\":{\"id\":\"gid://gitlab/User/23\"},"
                        + "\"accessLevel\":{\"stringValue\":\"DEVELOPER\",\"integerValue\":30}}"
            })
    void shouldKeepEveryCollaboratorWhenTheProductionCodecsReadAMemberWithNoReadableUser(String member) {
        Repository project = project();
        List<RepositoryCollaborator> stored = stored(
                project,
                new RepositoryCollaborator(project, user(21L, "student"), RepositoryCollaborator.Permission.READ),
                new RepositoryCollaborator(project, user(22L, "former"), RepositoryCollaborator.Permission.WRITE));
        GitLabUserService users = mock(GitLabUserService.class);
        when(graphQlClientProvider.forScope(2L)).thenReturn(productionCodecClient(page(member, STUDENT)));

        var result = service(users).syncCollaboratorsForRepository(2L, project);

        assertThat(result.isCompleted()).isFalse();
        assertThat(stored)
                .extracting(collaborator -> collaborator.getUser().getLogin(), RepositoryCollaborator::getPermission)
                .containsExactly(
                        tuple("student", RepositoryCollaborator.Permission.READ),
                        tuple("former", RepositoryCollaborator.Permission.WRITE));
        verifyNoInteractions(users);
    }

    private GitLabCollaboratorSyncService service(GitLabUserService users) {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        lenient().when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new GitLabCollaboratorSyncService(
                collaboratorRepository,
                graphQlClientProvider,
                new GitLabGraphQlResponseHandler(mock(GitLabExceptionClassifier.class)),
                users,
                new GitLabProperties(
                        "https://gitlab.example.test",
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60),
                        Duration.ZERO,
                        Duration.ofMinutes(5)),
                new TransactionTemplate(transactions),
                workspaceLinkService,
                projectAdmission);
    }

    private Repository project() {
        Repository project = new Repository();
        project.setId(11L);
        project.setNameWithOwner("course/intro/team-1/demo");
        project.setProvider(TestEntities.gitProvider(10L, IdentityProviderType.GITLAB));
        GitLabAdmissionTestFixtures.permit(projectAdmission, 2L, project, 7L);
        when(workspaceLinkService.mayWriteRepository(2L, project)).thenReturn(true);
        return project;
    }

    private static User user(long id, String login) {
        User user = new User();
        user.setId(id);
        user.setLogin(login);
        return user;
    }

    /** The collaborators stored for {@code project}, changed only through the repository's save and delete. */
    private List<RepositoryCollaborator> stored(Repository project, RepositoryCollaborator... collaborators) {
        List<RepositoryCollaborator> stored = new ArrayList<>(List.of(collaborators));
        lenient()
                .when(collaboratorRepository.findByRepositoryIdAndUserId(eq(project.getId()), anyLong()))
                .thenAnswer(invocation -> stored.stream()
                        .filter(collaborator ->
                                Objects.equals(collaborator.getUser().getId(), invocation.<Long>getArgument(1)))
                        .findFirst());
        lenient()
                .when(collaboratorRepository.save(any(RepositoryCollaborator.class)))
                .thenAnswer(invocation -> {
                    RepositoryCollaborator saved = invocation.getArgument(0);
                    if (!stored.contains(saved)) stored.add(saved);
                    return saved;
                });
        lenient()
                .when(collaboratorRepository.findByRepository_Id(project.getId()))
                .thenAnswer(invocation -> List.copyOf(stored));
        lenient()
                .doAnswer(invocation -> stored.remove(invocation.<RepositoryCollaborator>getArgument(0)))
                .when(collaboratorRepository)
                .delete(any(RepositoryCollaborator.class));
        return stored;
    }

    private static String page(String... members) {
        return """
                {"data":{"project":{"id":"gid://gitlab/Project/11","projectMembers":{
                 "pageInfo":{"hasNextPage":false,"endCursor":null},"nodes":[%s]}}}}
                """.formatted(String.join(",", members));
    }

    /**
     * The GitLab GraphQL client that {@link GitLabGraphQlConfig} builds, answered with {@code body}. Its mapper omits
     * null values and null map entries, as the application's mapper is configured to; this unit setup builds it here
     * and does not load the application context. A last filter returns the body in place of the connector, so nothing
     * leaves the test.
     */
    private static HttpGraphQlClient productionCodecClient(String body) {
        JsonMapper omittingNulls = JsonMapper.builder()
                .changeDefaultPropertyInclusion(inclusion -> inclusion
                        .withValueInclusion(JsonInclude.Include.NON_NULL)
                        .withContentInclusion(JsonInclude.Include.NON_NULL))
                .build();
        var config = new GitLabGraphQlConfig(new GitLabRateLimitTracker(new SimpleMeterRegistry()));
        // The application's builder carries the credential health filter through its customizer.
        var health = new GitLabCredentialHealthFilter(
                new StaticListableBeanFactory().getBeanProvider(GitLabCredentialHealth.class));
        var webClient =
                config.gitLabGraphQlWebClient(omittingNulls, WebClient.builder().filter(health.filter()));
        return config.gitLabGraphQlClient(
                        webClient,
                        new SilentModeGraphQlClientFactory(
                                new SilentModeGraphQlInterceptor(mock(OutboundEgressGuard.class))))
                .mutate()
                .url("https://gitlab.example.test/api/graphql")
                .webClient(builder -> builder.filter((request, next) -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build())))
                .build();
    }
}
