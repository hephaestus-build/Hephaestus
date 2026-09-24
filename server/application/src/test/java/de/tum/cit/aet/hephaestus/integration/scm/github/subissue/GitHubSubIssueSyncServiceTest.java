package de.tum.cit.aet.hephaestus.integration.scm.github.subissue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHSubIssuesSummary;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The GraphQL sub-issue page against rows whose ids are not GitHub's: a row is keyed by (provider,
 * native id) and found by repository and number, never by GitHub's database id as a JPA id.
 */
class GitHubSubIssueSyncServiceTest extends BaseUnitTest {

    private static final long REPO_ID = 5L;
    private static final long PARENT_ROW_ID = 7L;
    private static final long CHILD_ROW_ID = 8L;
    private static final BigInteger PARENT_GITHUB_ID = BigInteger.valueOf(3_578_400_000L);
    private static final BigInteger CHILD_GITHUB_ID = BigInteger.valueOf(3_578_496_080L);

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private SyncTargetProvider syncTargetProvider;

    @Mock
    private GitHubGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitHubSyncProperties syncProperties;

    @Mock
    private GitHubExceptionClassifier exceptionClassifier;

    @Mock
    private SyncSchedulerProperties syncSchedulerProperties;

    @Mock
    private GitHubIssueProcessor issueProcessor;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private GitHubGraphQlSyncCoordinator graphQlSyncHelper;

    private GitHubSubIssueSyncService service;
    private Repository repository;
    private Issue parent;
    private Issue child;

    @BeforeEach
    void setUp() {
        service = new GitHubSubIssueSyncService(
                issueRepository,
                repositoryRepository,
                syncTargetProvider,
                graphQlClientProvider,
                syncProperties,
                exceptionClassifier,
                syncSchedulerProperties,
                issueProcessor,
                transactionManager,
                graphQlSyncHelper);
        repository = new Repository();
        repository.setId(REPO_ID);
        repository.setNameWithOwner("acme/web");
        parent = new Issue();
        parent.setId(PARENT_ROW_ID);
        parent.setNativeId(PARENT_GITHUB_ID.longValue());
        parent.setNumber(19);
        child = new Issue();
        child.setId(CHILD_ROW_ID);
        child.setNativeId(CHILD_GITHUB_ID.longValue());
        child.setNumber(20);
    }

    @Test
    void shouldLinkAChildToItsParentByRepositoryAndNumberWhenRowIdsAreNotGitHubIds() {
        when(repositoryRepository.findByNameWithOwnerWithOrganization("acme/web"))
                .thenReturn(Optional.of(repository));
        when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 20)).thenReturn(Optional.of(child));
        when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 19)).thenReturn(Optional.of(parent));
        GHIssue parentNode = issueNode(PARENT_GITHUB_ID, 19);
        GHIssue childNode = issueNode(CHILD_GITHUB_ID, 20);
        childNode.setParent(parentNode);
        GHSubIssuesSummary summary = new GHSubIssuesSummary();
        summary.setTotal(1);
        summary.setCompleted(0);
        summary.setPercentCompleted(0);
        parentNode.setSubIssuesSummary(summary);
        GHIssueConnection page = new GHIssueConnection();
        page.setNodes(List.of(childNode, parentNode));

        int linked = service.processIssueNodes(page, repository, 1L);

        assertThat(linked).isEqualTo(1);
        assertThat(child.getParentIssue()).isSameAs(parent);
        assertThat(parent.getSubIssuesTotal()).isEqualTo(1);
        verify(issueRepository).save(child);
        verify(issueRepository, never()).findById(anyLong());
        verify(issueProcessor, never()).processStub(any(), any());
    }

    private static GHIssue issueNode(BigInteger fullDatabaseId, int number) {
        GHIssue node = new GHIssue();
        node.setFullDatabaseId(fullDatabaseId);
        node.setNumber(number);
        GHRepository ghRepository = new GHRepository();
        ghRepository.setNameWithOwner("acme/web");
        node.setRepository(ghRepository);
        return node;
    }
}
