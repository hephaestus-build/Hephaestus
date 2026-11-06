package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.GitLabNoteSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.backfill.BackfillBatchResult;
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
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import reactor.core.publisher.Mono;

class GitLabIssueSyncServiceTest extends BaseUnitTest {
    private static final Long SCOPE_ID = 100L;
    private static final String FULL_PATH = "hephaestustest/demo-repository";
    private final Logger logger = (Logger) LoggerFactory.getLogger(GitLabIssueSyncService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitLabIssueProcessor issueProcessor;

    @Mock
    private GitLabNoteSyncService noteSyncService;

    @Mock
    private GitLabProjectAdmission projectAdmission;

    @Mock
    private HttpGraphQlClient client;

    @Mock
    private GraphQlClient.RequestSpec requestSpec;

    private final Deque<ClientGraphQlResponse> scriptedResponses = new ArrayDeque<>();
    private GitLabIssueSyncService service;

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
        service = new GitLabIssueSyncService(
                graphQlClientProvider,
                new GitLabGraphQlResponseHandler(mock(GitLabExceptionClassifier.class)),
                issueProcessor,
                noteSyncService,
                new GitLabProperties(
                        "https://gitlab.lrz.de",
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60),
                        Duration.ZERO,
                        Duration.ofMinutes(5)),
                projectAdmission);
        when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(client);
        when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);
        when(client.documentName(anyString())).thenReturn(requestSpec);
        when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
        when(requestSpec.execute())
                .thenAnswer(invocation ->
                        scriptedResponses.isEmpty() ? Mono.empty() : Mono.just(scriptedResponses.removeFirst()));
    }

    @AfterEach
    void releaseEvents() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void shouldAbortAConcurrentWriteWithoutReadingTheNextPageAndAllowAFreshSync() {
        Repository repository = repository();
        scriptedResponses.add(page("GetProjectIssues", true));
        scriptedResponses.add(page("GetProjectIssues", false));
        when(issueProcessor.processFromSync(any(), any()))
                .thenThrow(
                        new ObjectOptimisticLockingFailureException("private-repository-canary", "private-row-canary"))
                .thenReturn(new Issue());

        assertThat(service.syncIssues(SCOPE_ID, repository, null)).isEqualTo(SyncResult.abortedError(0));
        assertThat(scriptedResponses).hasSize(1);
        assertConcurrentWriteStayedLocal();

        assertThat(service.syncIssues(SCOPE_ID, repository, null)).isEqualTo(SyncResult.completed(1));
        assertThat(scriptedResponses).isEmpty();
    }

    @Test
    void shouldAbortAConcurrentHistoricalWriteWithoutAdvancingItsCheckpointAndAllowAFreshBatch() {
        Repository repository = repository();
        scriptedResponses.add(page("GetProjectIssuesHistorical", true));
        scriptedResponses.add(page("GetProjectIssuesHistorical", false));
        when(issueProcessor.processFromSync(any(), any()))
                .thenThrow(
                        new ObjectOptimisticLockingFailureException("private-repository-canary", "private-row-canary"))
                .thenReturn(new Issue());

        assertThat(service.backfillIssues(SCOPE_ID, repository, null, 1))
                .isEqualTo(BackfillBatchResult.abortedWithError());
        assertThat(scriptedResponses).hasSize(1);
        assertConcurrentWriteStayedLocal();

        assertThat(service.backfillIssues(SCOPE_ID, repository, null, 1))
                .isEqualTo(new BackfillBatchResult(1, 3, 3, null, true, false));
        assertThat(scriptedResponses).isEmpty();
    }

    @Test
    void shouldAbortWithoutReadingTheNextPageAndAllowAFreshSyncWhenANoteWriteConflicts() {
        Repository repository = repository();
        scriptedResponses.add(page("GetProjectIssues", true, 1));
        scriptedResponses.add(page("GetProjectIssues", false, 1));
        when(issueProcessor.processFromSync(any(), any())).thenReturn(new Issue());
        when(noteSyncService.syncNotesForIssue(any(), any(), anyInt(), any(), any()))
                .thenThrow(
                        new ObjectOptimisticLockingFailureException("private-repository-canary", "private-row-canary"))
                .thenReturn(1);

        assertThat(service.syncIssues(SCOPE_ID, repository, null)).isEqualTo(SyncResult.abortedError(0));
        assertThat(scriptedResponses).hasSize(1);
        assertConcurrentWriteStayedLocal();

        assertThat(service.syncIssues(SCOPE_ID, repository, null)).isEqualTo(SyncResult.completed(1));
        assertThat(scriptedResponses).isEmpty();
    }

    @Test
    void shouldAbortWithoutAdvancingTheCheckpointAndAllowAFreshBatchWhenAHistoricalNoteWriteConflicts() {
        Repository repository = repository();
        scriptedResponses.add(page("GetProjectIssuesHistorical", true, 1));
        scriptedResponses.add(page("GetProjectIssuesHistorical", false, 1));
        when(issueProcessor.processFromSync(any(), any())).thenReturn(new Issue());
        when(noteSyncService.syncNotesForIssue(any(), any(), anyInt(), any(), any()))
                .thenThrow(
                        new ObjectOptimisticLockingFailureException("private-repository-canary", "private-row-canary"))
                .thenReturn(1);

        assertThat(service.backfillIssues(SCOPE_ID, repository, null, 1))
                .isEqualTo(BackfillBatchResult.abortedWithError());
        assertThat(scriptedResponses).hasSize(1);
        assertConcurrentWriteStayedLocal();

        assertThat(service.backfillIssues(SCOPE_ID, repository, null, 1))
                .isEqualTo(new BackfillBatchResult(1, 3, 3, null, true, false));
        assertThat(scriptedResponses).isEmpty();
    }

    @Test
    void shouldKeepTheIssueAndReadTheNextPageWhenItsNoteSyncFailsForAnotherReason() {
        Repository repository = repository();
        scriptedResponses.add(page("GetProjectIssues", true, 1));
        scriptedResponses.add(page("GetProjectIssues", false, 1));
        when(issueProcessor.processFromSync(any(), any())).thenReturn(new Issue());
        when(noteSyncService.syncNotesForIssue(any(), any(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("ordinary failure"))
                .thenReturn(1);

        assertThat(service.syncIssues(SCOPE_ID, repository, null)).isEqualTo(SyncResult.completed(2));
        assertThat(scriptedResponses).isEmpty();
        verify(graphQlClientProvider, never()).recordFailure(any());
        assertThat(appender.list)
                .noneMatch(
                        event -> event.getFormattedMessage().equals("Mirror write conflicts with another transaction"));
    }

    private void assertConcurrentWriteStayedLocal() {
        verify(graphQlClientProvider, never()).recordFailure(any());
        assertThat(appender.list)
                .filteredOn(
                        event -> event.getFormattedMessage().equals("Mirror write conflicts with another transaction"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getKeyValuePairs()).isNotNull();
                    assertThat(event.getKeyValuePairs().stream()
                                    .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value)))
                            .containsExactlyInAnyOrderEntriesOf(Map.of(
                                    "event.name",
                                    "integration.mirror.write.conflict",
                                    "error.type",
                                    ObjectOptimisticLockingFailureException.class.getName(),
                                    "integration.scope.id",
                                    SCOPE_ID));
                    assertThat(event.getArgumentArray()).isNullOrEmpty();
                });
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("canary");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private Repository repository() {
        Repository repository = new Repository();
        repository.setId(7L);
        repository.setNameWithOwner(FULL_PATH);
        GitLabAdmissionTestFixtures.permit(projectAdmission, SCOPE_ID, repository, 7L);
        return repository;
    }

    private static ClientGraphQlResponse page(String document, boolean hasNextPage) {
        return page(document, hasNextPage, 0);
    }

    /** One page holding issue #3; a nonzero {@code userNotesCount} makes the sync read its notes. */
    private static ClientGraphQlResponse page(String document, boolean hasNextPage, int userNotesCount) {
        List<Map<String, Object>> nodes = List.of(Map.of(
                "id", "gid://gitlab/Issue/3003",
                "iid", "3",
                "title", "Issue #3",
                "state", "opened",
                "confidential", false,
                "userNotesCount", userNotesCount));
        assertVendorCouldReturn(GITLAB, document, "project.issues.nodes", nodes);
        Map<String, @Nullable Object> pageInfo = new HashMap<>();
        pageInfo.put("hasNextPage", hasNextPage);
        pageInfo.put("endCursor", hasNextPage ? "next-page" : null);
        return GraphQlResponses.of(
                Map.of("project", Map.of("issues", Map.of("nodes", nodes, "pageInfo", pageInfo))), List.of());
    }
}
