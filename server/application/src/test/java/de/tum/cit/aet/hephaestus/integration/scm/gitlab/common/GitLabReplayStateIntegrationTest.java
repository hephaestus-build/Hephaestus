package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.GitLabIssueProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.dto.GitLabIssueEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.dto.GitLabMergeRequestEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class GitLabReplayStateIntegrationTest extends BaseIntegrationTest {
    private static final Instant REPLAY_VERSION = Instant.parse("2026-01-31T18:03:35Z");

    @Autowired
    private GitLabIssueProcessor issues;

    @Autowired
    private GitLabMergeRequestProcessor mergeRequests;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private RecordingScmEventListener eventListener;

    private Repository repository;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        IdentityProvider provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.lrz.de")
                .orElseGet(() ->
                        providers.save(new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.lrz.de")));
        repository = new Repository();
        repository.setNativeId(246765L);
        repository.setName("demo-repository");
        repository.setNameWithOwner("hephaestustest/demo-repository");
        repository.setHtmlUrl("https://gitlab.lrz.de/hephaestustest/demo-repository");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setPushedAt(REPLAY_VERSION);
        repository.setCreatedAt(REPLAY_VERSION);
        repository.setUpdatedAt(REPLAY_VERSION);
        repository.setProvider(provider);
        repository = repositories.save(repository);
        workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("gitlab-replay"));
    }

    @ParameterizedTest
    @CsvSource({"update,0", "update,60", "close,0", "close,60", "reopen,0", "reopen,60"})
    void shouldKeepNewerIssueWhenWebhookIsReplayed(String action, long secondsNewer) throws Exception {
        GitLabIssueEventDTO replay;
        try (var input = new ClassPathResource("gitlab/issue.update.json").getInputStream()) {
            ObjectNode root = (ObjectNode) mapper.readTree(input);
            root.withObject("object_attributes").put("updated_at", REPLAY_VERSION.toString());
            replay = mapper.treeToValue(root, GitLabIssueEventDTO.class);
        }
        var attrs = Objects.requireNonNull(replay.objectAttributes());
        Issue stored = issue(Objects.requireNonNull(attrs.id()), Objects.requireNonNull(attrs.iid()), secondsNewer);

        if (action.equals("close")) {
            stored.setState(Issue.State.CLOSED);
            stored = issueRepository.saveAndFlush(stored);
        }
        var context = ProcessingContext.forWebhook(workspace.getId(), repository, action);
        switch (action) {
            case "update" -> issues.processUpdated(replay, context);
            case "close" -> issues.processClosed(replay, context);
            case "reopen" -> issues.processReopened(replay, context);
            default -> throw new IllegalArgumentException(action);
        }

        assertStored(stored, secondsNewer);
        assertThat(eventListener.ofType(ScmDomainEvent.IssueUpdated.class)).isEmpty();
        assertThat(eventListener.ofType(ScmDomainEvent.IssueClosed.class)).isEmpty();
        assertThat(eventListener.ofType(ScmDomainEvent.IssueReopened.class)).isEmpty();
        assertThat(eventListener.ofType(ScmDomainEvent.IssueLabeled.class)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 60})
    void shouldKeepNewerIssueWhenSyncPageIsReplayed(long secondsNewer) {
        Issue stored = issue(422296L, 5, secondsNewer);
        var replay = new GitLabIssueProcessor.SyncIssueData(
                "gid://gitlab/Issue/422296",
                "5",
                "Old title",
                "Old body",
                "opened",
                false,
                "https://gitlab.lrz.de/hephaestustest/demo-repository/-/issues/5",
                null,
                REPLAY_VERSION.toString(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null);

        issues.processFromSync(
                replay,
                ProcessingContext.forSync(workspace.getId(), repository)
                        .withObservedAt(REPLAY_VERSION.minusSeconds(1)));

        assertStored(stored, secondsNewer);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 60})
    void shouldKeepNewerMergeRequestWhenWebhookIsReplayed(long secondsNewer) throws Exception {
        GitLabMergeRequestEventDTO replay;
        try (var input = new ClassPathResource("gitlab/merge_request.update.json").getInputStream()) {
            ObjectNode root = (ObjectNode) mapper.readTree(input);
            root.withObject("object_attributes").put("updated_at", REPLAY_VERSION.toString());
            replay = mapper.treeToValue(root, GitLabMergeRequestEventDTO.class);
        }
        var attrs = Objects.requireNonNull(replay.objectAttributes());
        PullRequest stored = new PullRequest();
        stored.setNativeId(Objects.requireNonNull(attrs.id()));
        stored.setNumber(Objects.requireNonNull(attrs.iid()));
        stored.setTitle("Newer title");
        stored.setBody("Newer body");
        stored.setState(Issue.State.OPEN);
        stored.setHtmlUrl(repository.getHtmlUrl() + "/-/merge_requests/" + attrs.iid());
        stored.setRepository(repository);
        stored.setProvider(repository.getProvider());
        stored.setUpdatedAt(REPLAY_VERSION.plusSeconds(secondsNewer));
        stored = pullRequests.saveAndFlush(stored);

        mergeRequests.process(replay, ProcessingContext.forWebhook(workspace.getId(), repository, "update"));

        PullRequest actual = pullRequests.findById(stored.getId()).orElseThrow();
        assertThat(actual.getTitle()).isEqualTo("Newer title");
        assertThat(actual.getBody()).isEqualTo("Newer body");
        assertThat(actual.getUpdatedAt()).isEqualTo(REPLAY_VERSION.plusSeconds(secondsNewer));
    }

    @ParameterizedTest
    @CsvSource({"-1,false", "0,false", "1,true"})
    void shouldRefreshATiedIssueOnlyWhenTheAuthoritativeReadBeganAfterTheStoredWrite(
            long nanosAfterWrite, boolean accepted) {
        Issue stored = issue(422296L, 5, 0);
        Instant writtenAt = Objects.requireNonNull(stored.getLastSyncAt());
        var currentProviderState = new GitLabIssueProcessor.SyncIssueData(
                "gid://gitlab/Issue/422296",
                "5",
                "Current title",
                "Current body",
                "opened",
                false,
                stored.getHtmlUrl(),
                null,
                REPLAY_VERSION.toString(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null);

        issues.processFromSync(
                currentProviderState,
                ProcessingContext.forSync(workspace.getId(), repository)
                        .withObservedAt(writtenAt.plusNanos(nanosAfterWrite)));

        Issue actual = issueRepository.findById(stored.getId()).orElseThrow();
        assertThat(actual.getTitle()).isEqualTo(accepted ? "Current title" : "Newer title");
    }

    private Issue issue(long nativeId, int number, long secondsNewer) {
        Issue stored = new Issue();
        stored.setNativeId(nativeId);
        stored.setNumber(number);
        stored.setTitle("Newer title");
        stored.setBody("Newer body");
        stored.setState(Issue.State.OPEN);
        stored.setHtmlUrl(repository.getHtmlUrl() + "/-/issues/" + number);
        stored.setRepository(repository);
        stored.setProvider(repository.getProvider());
        stored.setUpdatedAt(REPLAY_VERSION.plusSeconds(secondsNewer));
        stored.setLastSyncAt(REPLAY_VERSION.plusSeconds(120));
        return issueRepository.saveAndFlush(stored);
    }

    private void assertStored(Issue stored, long secondsNewer) {
        Issue actual = issueRepository.findById(stored.getId()).orElseThrow();
        assertThat(actual.getTitle()).isEqualTo("Newer title");
        assertThat(actual.getBody()).isEqualTo("Newer body");
        assertThat(actual.getUpdatedAt()).isEqualTo(REPLAY_VERSION.plusSeconds(secondsNewer));
    }
}
