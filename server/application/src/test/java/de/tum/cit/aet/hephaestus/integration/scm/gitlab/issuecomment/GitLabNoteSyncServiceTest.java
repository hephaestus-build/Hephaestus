package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabAdmissionTestFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GraphQlResponses;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/** The page loop of the issue note sync, driven through GitLab's note pages and the page's write transaction. */
class GitLabNoteSyncServiceTest extends BaseUnitTest {
    private static final Long SCOPE_ID = 100L;
    private static final String FULL_PATH = "hephaestustest/demo-repository";
    private static final String DOCUMENT = "GetIssueNotes";

    private final GitLabProjectAdmission projectAdmission = mock(GitLabProjectAdmission.class);

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitLabIssueCommentProcessor issueCommentProcessor;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private HttpGraphQlClient client;

    @Mock
    private GraphQlClient.RequestSpec requestSpec;

    private final Deque<ClientGraphQlResponse> scriptedResponses = new ArrayDeque<>();
    private GitLabNoteSyncService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new GitLabNoteSyncService(
                graphQlClientProvider,
                new GitLabGraphQlResponseHandler(mock(GitLabExceptionClassifier.class)),
                issueCommentProcessor,
                new GitLabProperties(
                        "https://gitlab.lrz.de",
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60),
                        Duration.ZERO,
                        Duration.ofMinutes(5)),
                new TransactionTemplate(transactionManager),
                projectAdmission);
        lenient().when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(client);
        lenient().when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);
        lenient().when(client.documentName(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
        lenient()
                .when(requestSpec.execute())
                .thenAnswer(
                        invocation -> scriptedResponses.isEmpty() ? Mono.empty() : Mono.just(scriptedResponses.poll()));
    }

    @Test
    void shouldHandTheTypedConflictToTheParentSyncWithoutReadingTheNextPageWhenANotePageWriteConflicts() {
        Repository repository = repository();
        scriptedResponses.add(page(true));
        scriptedResponses.add(page(false));
        var conflict = new ObjectOptimisticLockingFailureException("private-repository-canary", "private-row-canary");
        when(issueCommentProcessor.processFromSync(any(), any(), any(), any())).thenThrow(conflict);

        assertThatThrownBy(() -> service.syncNotesForIssue(SCOPE_ID, repository, 3, new Issue()))
                .isSameAs(conflict);
        assertThat(scriptedResponses).hasSize(1);
        verify(graphQlClientProvider, never()).recordFailure(any());
    }

    @Test
    void shouldRecordTheProviderFailureAndKeepThePartialCountWhenANotePageFailsForAnotherReason() {
        Repository repository = repository();
        scriptedResponses.add(page(true));
        scriptedResponses.add(page(false));
        var failure = new IllegalStateException("ordinary failure");
        when(issueCommentProcessor.processFromSync(any(), any(), any(), any())).thenThrow(failure);

        assertThat(service.syncNotesForIssue(SCOPE_ID, repository, 3, new Issue()))
                .isZero();
        assertThat(scriptedResponses).hasSize(1);
        verify(graphQlClientProvider).recordFailure(failure);
    }

    private Repository repository() {
        IdentityProvider provider = new IdentityProvider();
        provider.setId(2L);
        Repository repository = new Repository();
        repository.setId(7L);
        repository.setNameWithOwner(FULL_PATH);
        repository.setProvider(provider);
        GitLabAdmissionTestFixtures.permit(projectAdmission, SCOPE_ID, repository, 7L);
        return repository;
    }

    /** One page of issue #3's notes, holding one user comment. */
    private static ClientGraphQlResponse page(boolean hasNextPage) {
        List<Map<String, Object>> nodes = List.of(Map.of(
                "id", "gid://gitlab/Note/501",
                "body", "Please add a regression test.",
                "system", false,
                "internal", false,
                "createdAt", "2026-04-15T10:23:58.766+02:00",
                "updatedAt", "2026-04-15T10:23:58.766+02:00",
                "author", Map.of("id", "gid://gitlab/User/90002", "username", "erika.maria.sample")));
        assertVendorCouldReturn(GITLAB, DOCUMENT, "project.issue.notes.nodes", nodes);
        Map<String, @Nullable Object> pageInfo = new HashMap<>();
        pageInfo.put("hasNextPage", hasNextPage);
        pageInfo.put("endCursor", hasNextPage ? "next-page" : null);
        return GraphQlResponses.of(
                Map.of(
                        "project",
                        Map.of("issue", Map.of("notes", Map.of("count", 2, "nodes", nodes, "pageInfo", pageInfo)))),
                List.of());
    }
}
