package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueTypeRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookLabel;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookProject;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookUser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.dto.GitLabIssueEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

@Tag("unit")
class GitLabIssueProcessorTest extends BaseUnitTest {

    private static final long REPO_ID = 1L;
    private static final long RAW_ISSUE_ID = 422296L;
    private static final long ENTITY_ISSUE_ID = 100L;
    private static final int ISSUE_IID = 5;
    private static final long RAW_USER_ID = 18024L;
    private static final long ENTITY_USER_ID = 200L;
    private static final Long PROVIDER_ID = 2L;

    @Mock
    private GitLabUserService gitLabUserService;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LabelRepository labelRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private ScopeIdResolver scopeIdResolver;

    @Mock
    private RepositoryScopeFilter repositoryScopeFilter;

    @Mock
    private MilestoneRepository milestoneRepository;

    @Mock
    private IssueTypeRepository issueTypeRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private GitLabIssueProcessor processor;
    private Repository testRepo;
    private IdentityProvider gitLabProvider;

    @BeforeEach
    void setUp() {
        GitLabProperties properties = new GitLabProperties(
                "https://gitlab.lrz.de",
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ofMillis(200),
                Duration.ofMinutes(5));

        processor = new GitLabIssueProcessor(
                gitLabUserService,
                issueRepository,
                milestoneRepository,
                issueTypeRepository,
                userRepository,
                labelRepository,
                repositoryRepository,
                scopeIdResolver,
                repositoryScopeFilter,
                properties,
                eventPublisher);

        gitLabProvider = new IdentityProvider();
        gitLabProvider.setId(PROVIDER_ID);
        gitLabProvider.setType(IdentityProviderType.GITLAB);
        gitLabProvider.setServerUrl("https://gitlab.lrz.de");

        testRepo = new Repository();
        testRepo.setId(REPO_ID);
        testRepo.setNameWithOwner("hephaestustest/demo-repository");
        testRepo.setProvider(gitLabProvider);

        // Default: upsertCore succeeds
        lenient()
                .when(issueRepository.upsertCore(
                        anyLong(), anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // upsertUser is void — no stubbing needed
    }

    @Nested
    class ConfidentialFiltering {

        @Test
        void processSkipsConfidential() {
            GitLabIssueEventDTO event = createEvent("open", "opened", true);
            ProcessingContext ctx = createContext();

            Issue result = processor.process(event, ctx);

            assertThat(result).isNull();
            verify(issueRepository, never())
                    .upsertCore(
                            anyLong(), anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(),
                            any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any());
        }

        @Test
        void purgeConfidentialTombstonesStoredSnapshot() {
            // Public → confidential flip: the previously-mirrored public row must be tombstoned so it
            // drops out of every live query.
            GitLabIssueEventDTO event = createEvent("update", "opened", true);
            when(issueRepository.tombstoneIssuesByRepositoryIdAndNumbers(eq(REPO_ID), eq(List.of(ISSUE_IID)), any()))
                    .thenReturn(1);

            boolean purged = processor.purgeConfidential(event, createContext());

            assertThat(purged).isTrue();
            verify(issueRepository).tombstoneIssuesByRepositoryIdAndNumbers(eq(REPO_ID), eq(List.of(ISSUE_IID)), any());
        }

        @Test
        void purgeConfidentialIsNoopWhenNothingStored() {
            GitLabIssueEventDTO event = createEvent("update", "opened", true);
            when(issueRepository.tombstoneIssuesByRepositoryIdAndNumbers(eq(REPO_ID), eq(List.of(ISSUE_IID)), any()))
                    .thenReturn(0);

            boolean purged = processor.purgeConfidential(event, createContext());

            assertThat(purged).isFalse();
        }

        @Test
        void processFromSyncSkipsConfidential() {
            var syncData = new GitLabIssueProcessor.SyncIssueData(
                    "gid://gitlab/Issue/422296",
                    "5",
                    "Title",
                    "desc",
                    "opened",
                    true,
                    "https://example.com",
                    null,
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
            Issue result = processor.processFromSync(syncData, testRepo, 1L);

            assertThat(result).isNull();
            verify(issueRepository, never())
                    .upsertCore(
                            anyLong(), anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(),
                            any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    class StateMapping {

        @Test
        void openedMapsToOpen() {
            Issue issue = createIssueEntity();
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(issue));

            GitLabIssueEventDTO event = createEvent("open", "opened", false);
            processor.process(event, createContext());

            verify(issueRepository)
                    .upsertCore(
                            eq(RAW_ISSUE_ID),
                            eq(PROVIDER_ID),
                            eq(ISSUE_IID),
                            any(),
                            any(),
                            eq("OPEN"),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void closedMapsToClosed() {
            Issue issue = createIssueEntity();
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(issue));

            GitLabIssueEventDTO event = createEvent("close", "closed", false);
            processor.process(event, createContext());

            verify(issueRepository)
                    .upsertCore(
                            eq(RAW_ISSUE_ID),
                            eq(PROVIDER_ID),
                            eq(ISSUE_IID),
                            any(),
                            any(),
                            eq("CLOSED"),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void nullStateDefaultsToOpen() {
            Issue issue = createIssueEntity();
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(issue));

            GitLabIssueEventDTO event = createEvent("open", null, false);
            processor.process(event, createContext());

            verify(issueRepository)
                    .upsertCore(
                            eq(RAW_ISSUE_ID),
                            eq(PROVIDER_ID),
                            eq(ISSUE_IID),
                            any(),
                            any(),
                            eq("OPEN"),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }
    }

    @Nested
    class WebhookProcessing {

        @Test
        void processCreatesNewIssue() {
            Issue issue = createIssueEntity();
            // First call: check if exists → empty (new issue)
            // Second call: after upsert → find the issue
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(issue));

            User author = createUserEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(author);

            GitLabIssueEventDTO event = createEvent("open", "opened", false);
            Issue result = processor.process(event, createContext());

            assertThat(result).isNotNull();
            assertThat(result.getProvider()).isEqualTo(gitLabProvider);

            ArgumentCaptor<ScmDomainEvent.IssueCreated> eventCaptor =
                    ArgumentCaptor.forClass(ScmDomainEvent.IssueCreated.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
        }

        @Test
        void processMissingIdSkips() {
            var attrs = new GitLabIssueEventDTO.ObjectAttributes(
                    null, null, "Title", "desc", "opened", "open", false, 18024L, null, null, null, null, null, null,
                    null, null);
            GitLabIssueEventDTO event =
                    new GitLabIssueEventDTO("issue", "issue", createUser(), createProject(), attrs, null, null, null);

            Issue result = processor.process(event, createContext());

            assertThat(result).isNull();
        }

        @Test
        void processClosedPublishesEvent() {
            Issue issue = createIssueEntity();
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.of(issue))
                    .thenReturn(Optional.of(issue));

            User author = createUserEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(author);

            GitLabIssueEventDTO event = createEvent("close", "closed", false);
            Issue result = processor.processClosed(event, createContext());

            assertThat(result).isNotNull();

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue()).isInstanceOf(ScmDomainEvent.IssueClosed.class);
        }

        @ParameterizedTest
        @CsvSource(
                nullValues = "null",
                value = {"null, COMPLETED", "422297, DUPLICATE"})
        void shouldStoreTheStateReasonTheSyncStoresWhenWebhookClosesAnIssue(
                @Nullable Long duplicatedToId, String stateReason) {
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.of(createIssueEntity()));
            var closed = createEvent("close", "closed", false);
            var attrs = Objects.requireNonNull(closed.objectAttributes());
            var duplicateAware = new GitLabIssueEventDTO(
                    closed.objectKind(),
                    closed.eventType(),
                    closed.user(),
                    closed.project(),
                    new GitLabIssueEventDTO.ObjectAttributes(
                            attrs.id(),
                            attrs.iid(),
                            attrs.title(),
                            attrs.description(),
                            attrs.state(),
                            attrs.action(),
                            attrs.confidential(),
                            attrs.authorId(),
                            attrs.assigneeId(),
                            attrs.milestoneId(),
                            attrs.createdAt(),
                            attrs.updatedAt(),
                            attrs.closedAt(),
                            duplicatedToId,
                            "Issue",
                            attrs.url()),
                    closed.labels(),
                    closed.assignees(),
                    closed.changes());

            processor.processClosed(duplicateAware, createContext());

            verify(issueRepository)
                    .upsertCore(
                            eq(RAW_ISSUE_ID),
                            eq(PROVIDER_ID),
                            eq(ISSUE_IID),
                            any(),
                            any(),
                            eq("CLOSED"),
                            eq(stateReason),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void processReopenedPublishesEvent() {
            Issue issue = createIssueEntity();
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.of(issue))
                    .thenReturn(Optional.of(issue));

            User author = createUserEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(author);

            GitLabIssueEventDTO event = createEvent("reopen", "opened", false);
            Issue result = processor.processReopened(event, createContext());

            assertThat(result).isNotNull();

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, Mockito.times(2)).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getAllValues())
                    .extracting(Object::getClass)
                    .containsExactlyInAnyOrder(ScmDomainEvent.IssueUpdated.class, ScmDomainEvent.IssueReopened.class);
        }
    }

    @Nested
    class ProcessUpdated {

        @Test
        void publishesIssueLabeledForEachAddedLabel() {
            Issue issue = createIssueEntity();
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.of(issue))
                    .thenReturn(Optional.of(issue));
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(createUserEntity());
            when(issueRepository.save(any(Issue.class))).thenReturn(issue);
            Label bug = new Label();
            bug.setName("bug");
            when(labelRepository.findByRepositoryIdAndName(REPO_ID, "bug")).thenReturn(Optional.of(bug));

            processor.processUpdated(
                    createUpdateEventWithAddedLabel(new GitLabWebhookLabel(99L, "bug", "#ff0000")), createContext());

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, Mockito.atLeastOnce()).publishEvent(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(e -> e instanceof ScmDomainEvent.IssueLabeled);
        }

        @Test
        void noLabelChangePublishesNoIssueLabeled() {
            Issue issue = createIssueEntity();
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.of(issue))
                    .thenReturn(Optional.of(issue));
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(createUserEntity());

            // An ordinary title/description edit (no changes.labels) must not trigger label-based detection.
            processor.processUpdated(createUpdateEventWithChanges(titleChanged()), createContext());

            verify(eventPublisher, never()).publishEvent(any(ScmDomainEvent.IssueLabeled.class));
        }

        @Test
        void publishesIssueUpdatedNamingWhatTheChangesDiffSaysMoved() {
            Issue issue = createIssueEntity();
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.of(issue))
                    .thenReturn(Optional.of(issue));
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(createUserEntity());

            processor.processUpdated(createUpdateEventWithChanges(titleChanged()), createContext());

            ArgumentCaptor<ScmDomainEvent.IssueUpdated> captor =
                    ArgumentCaptor.forClass(ScmDomainEvent.IssueUpdated.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().changedFields()).containsExactly("title");
        }

        /**
         * GitLab has one update action for every field it tracks, most of which the review evidence never
         * reads. Publishing regardless would mint a fresh occasion for a due-date edit — a review nobody
         * asked for, on evidence that did not move.
         */
        @Test
        void publishesNothingWhenTheUpdateMovedNoFieldTheDomainNames() {
            Issue issue = createIssueEntity();
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.of(issue))
                    .thenReturn(Optional.of(issue));
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(createUserEntity());

            // A due-date-only edit: GitLab still sends changes, but none of its keys is a field the
            // shared domain has a name for.
            processor.processUpdated(
                    createUpdateEventWithChanges(new GitLabIssueEventDTO.Changes(null, null, null, null, null, null)),
                    createContext());

            verify(eventPublisher, never()).publishEvent(any(ScmDomainEvent.IssueUpdated.class));
        }

        @Test
        void publishesOneIssueUpdatedForALabelAndAssigneeEditTogether() {
            Issue issue = createIssueEntity();
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.of(issue))
                    .thenReturn(Optional.of(issue));
            when(gitLabUserService.findOrCreateUser(any(GitLabWebhookUser.class), eq(PROVIDER_ID)))
                    .thenReturn(createUserEntity());

            processor.processUpdated(
                    createUpdateEventWithChanges(new GitLabIssueEventDTO.Changes(
                            new GitLabIssueEventDTO.LabelsChange(List.of(), List.of()),
                            null,
                            null,
                            new GitLabIssueEventDTO.AttributeChange(),
                            null,
                            null)),
                    createContext());

            ArgumentCaptor<ScmDomainEvent.IssueUpdated> captor =
                    ArgumentCaptor.forClass(ScmDomainEvent.IssueUpdated.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().changedFields()).containsExactly("relationships");
        }

        private GitLabIssueEventDTO.Changes titleChanged() {
            return new GitLabIssueEventDTO.Changes(
                    null, new GitLabIssueEventDTO.AttributeChange(), null, null, null, null);
        }
    }

    @Nested
    class SyncProcessing {

        @Test
        void processFromSyncCreatesIssue() {
            Issue issue = createIssueEntity();
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(issue));

            User author = createUserEntity();
            when(gitLabUserService.findOrCreateUser(any(GitLabUserLookup.class), eq(PROVIDER_ID)))
                    .thenReturn(author);

            var syncData = new GitLabIssueProcessor.SyncIssueData(
                    "gid://gitlab/Issue/422296",
                    "5",
                    "Feature: Add user authentication",
                    "Implement OAuth2 authentication flow",
                    "opened",
                    false,
                    "https://gitlab.lrz.de/hephaestustest/demo-repository/-/issues/5",
                    "2026-01-31T18:03:35Z",
                    "2026-01-31T18:03:35Z",
                    null,
                    "gid://gitlab/User/18024",
                    "ga84xah",
                    "Felix Dietrich",
                    "https://gitlab.lrz.de/uploads/avatar.png",
                    "https://gitlab.lrz.de/ga84xah",
                    0,
                    null,
                    null,
                    null,
                    null,
                    null);
            Issue result = processor.processFromSync(syncData, testRepo, 1L);

            assertThat(result).isNotNull();
            assertThat(result.getProvider()).isEqualTo(gitLabProvider);

            verify(issueRepository)
                    .upsertCore(
                            eq(RAW_ISSUE_ID),
                            eq(PROVIDER_ID),
                            eq(ISSUE_IID),
                            any(),
                            any(),
                            eq("OPEN"),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void processFromSyncInvalidGlobalId() {
            var syncData = new GitLabIssueProcessor.SyncIssueData(
                    "invalid-id",
                    "5",
                    "Title",
                    null,
                    "opened",
                    false,
                    "https://example.com",
                    null,
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
            Issue result = processor.processFromSync(syncData, testRepo, 1L);

            assertThat(result).isNull();
        }

        @Test
        void processFromSyncInvalidIid() {
            var syncData = new GitLabIssueProcessor.SyncIssueData(
                    "gid://gitlab/Issue/422296",
                    "not-a-number",
                    "Title",
                    null,
                    "opened",
                    false,
                    "https://example.com",
                    null,
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
            Issue result = processor.processFromSync(syncData, testRepo, 1L);

            assertThat(result).isNull();
        }

        @Test
        void processFromSyncPublishesCreatedEvent() {
            Issue issue = createIssueEntity();
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(issue));

            var syncData = new GitLabIssueProcessor.SyncIssueData(
                    "gid://gitlab/Issue/422296",
                    "5",
                    "Title",
                    null,
                    "opened",
                    false,
                    "https://example.com",
                    null,
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
            processor.processFromSync(syncData, testRepo, 1L);

            ArgumentCaptor<ScmDomainEvent.IssueCreated> eventCaptor =
                    ArgumentCaptor.forClass(ScmDomainEvent.IssueCreated.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
        }

        @Test
        void processFromSyncLinksMilestone() {
            Issue issue = createIssueEntity();
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(issue));

            Milestone milestone = new Milestone();
            milestone.setId(42L);
            milestone.setNumber(3);
            when(milestoneRepository.findByNumberAndRepositoryId(3, REPO_ID)).thenReturn(Optional.of(milestone));

            var syncData = new GitLabIssueProcessor.SyncIssueData(
                    "gid://gitlab/Issue/422296",
                    "5",
                    "Title",
                    null,
                    "opened",
                    false,
                    "https://example.com",
                    null,
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
                    3,
                    null,
                    null);
            processor.processFromSync(syncData, testRepo, 1L);

            verify(issueRepository)
                    .upsertCore(
                            eq(RAW_ISSUE_ID),
                            eq(PROVIDER_ID),
                            eq(ISSUE_IID),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            eq(42L),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void processFromSyncMilestoneNotFound() {
            Issue issue = createIssueEntity();
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(issue));

            when(milestoneRepository.findByNumberAndRepositoryId(99, REPO_ID)).thenReturn(Optional.empty());

            var syncData = new GitLabIssueProcessor.SyncIssueData(
                    "gid://gitlab/Issue/422296",
                    "5",
                    "Title",
                    null,
                    "opened",
                    false,
                    "https://example.com",
                    null,
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
                    99,
                    null,
                    null);
            processor.processFromSync(syncData, testRepo, 1L);

            verify(issueRepository)
                    .upsertCore(
                            eq(RAW_ISSUE_ID),
                            eq(PROVIDER_ID),
                            eq(ISSUE_IID),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            eq((Long) null),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void processFromSyncNullMilestoneIid() {
            Issue issue = createIssueEntity();
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(issue));

            var syncData = new GitLabIssueProcessor.SyncIssueData(
                    "gid://gitlab/Issue/422296",
                    "5",
                    "Title",
                    null,
                    "opened",
                    false,
                    "https://example.com",
                    null,
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
            processor.processFromSync(syncData, testRepo, 1L);

            verify(milestoneRepository, never()).findByNumberAndRepositoryId(anyInt(), anyLong());
        }

        @Test
        void processFromSyncNoEventForExisting() {
            Issue issue = createIssueEntity();
            // Issue already exists
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, ISSUE_IID))
                    .thenReturn(Optional.of(issue))
                    .thenReturn(Optional.of(issue));

            var syncData = new GitLabIssueProcessor.SyncIssueData(
                    "gid://gitlab/Issue/422296",
                    "5",
                    "Title",
                    null,
                    "opened",
                    false,
                    "https://example.com",
                    null,
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
            processor.processFromSync(syncData, testRepo, 1L);

            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        void shouldMapCompletedStateReasonWhenClosedWithNoOtherSignal() {
            var syncData = new GitLabIssueProcessor.SyncIssueData(
                    "gid://gitlab/Issue/422296",
                    "5",
                    "Title",
                    null,
                    "closed",
                    false,
                    "https://example.com",
                    null,
                    null,
                    "2026-02-01T10:00:00Z",
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

            processor.processFromSync(syncData, testRepo, 1L);

            verify(issueRepository)
                    .upsertCore(
                            eq(RAW_ISSUE_ID),
                            eq(PROVIDER_ID),
                            eq(ISSUE_IID),
                            any(),
                            any(),
                            eq("CLOSED"),
                            eq("COMPLETED"),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void shouldMapDuplicateStateReasonWhenClosedAsDuplicateOfPresent() {
            var syncData = new GitLabIssueProcessor.SyncIssueData(
                    "gid://gitlab/Issue/422296",
                    "5",
                    "Title",
                    null,
                    "closed",
                    false,
                    "https://example.com",
                    null,
                    null,
                    "2026-02-01T10:00:00Z",
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
                    "gid://gitlab/Issue/999");

            processor.processFromSync(syncData, testRepo, 1L);

            verify(issueRepository)
                    .upsertCore(
                            eq(RAW_ISSUE_ID),
                            eq(PROVIDER_ID),
                            eq(ISSUE_IID),
                            any(),
                            any(),
                            eq("CLOSED"),
                            eq("DUPLICATE"),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void shouldResolveIssueTypeIdWhenTypeNameMatches() {
            Organization organization = new Organization();
            organization.setId(42L);
            testRepo.setOrganization(organization);

            IssueType taskType = new IssueType();
            taskType.setId("gid://gitlab/IssueType/1");

            when(issueTypeRepository.findByOrganizationIdAndNameIgnoreCase(42L, "Task"))
                    .thenReturn(Optional.of(taskType));

            var syncData = new GitLabIssueProcessor.SyncIssueData(
                    "gid://gitlab/Issue/422296",
                    "5",
                    "Title",
                    null,
                    "opened",
                    false,
                    "https://example.com",
                    null,
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
                    "TASK",
                    null);

            processor.processFromSync(syncData, testRepo, 1L);

            verify(issueRepository)
                    .upsertCore(
                            eq(RAW_ISSUE_ID),
                            eq(PROVIDER_ID),
                            eq(ISSUE_IID),
                            any(),
                            any(),
                            eq("OPEN"),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            any(),
                            eq("gid://gitlab/IssueType/1"),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void shouldHumaniseMultiWordTypeNameBeforeLookup() {
            // The lookup depends on humaniseTypeName: TEST_CASE must become "Test Case" (not "TEST_CASE"
            // or "test case") before the case-insensitive query — the single-word "TASK" case can't catch
            // a regression in the multi-word/lowercasing path.
            var organization = new Organization();
            organization.setId(42L);
            testRepo.setOrganization(organization);

            IssueType testCaseType = new IssueType();
            testCaseType.setId("gid://gitlab/IssueType/7");
            when(issueTypeRepository.findByOrganizationIdAndNameIgnoreCase(42L, "Test Case"))
                    .thenReturn(Optional.of(testCaseType));

            processor.processFromSync(syncDataWithType("TEST_CASE"), testRepo, 1L);

            verify(issueTypeRepository).findByOrganizationIdAndNameIgnoreCase(42L, "Test Case");
            verify(issueRepository)
                    .upsertCore(
                            eq(RAW_ISSUE_ID),
                            eq(PROVIDER_ID),
                            eq(ISSUE_IID),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            any(),
                            eq("gid://gitlab/IssueType/7"),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void shouldFallBackToProviderScopedLookupForSubgroupOrg() {
            // Subgroup orgs have no own issue_type seed rows: the org-scoped lookup misses and the
            // provider-scoped fallback (the documented LazyInitializationException workaround) resolves
            // the shared provider-global row.
            var organization = new Organization();
            organization.setId(99L);
            testRepo.setOrganization(organization);

            when(issueTypeRepository.findByOrganizationIdAndNameIgnoreCase(99L, "Task"))
                    .thenReturn(Optional.empty());
            IssueType providerScoped = new IssueType();
            providerScoped.setId("gid://gitlab/IssueType/global-1");
            when(issueTypeRepository.findFirstByOrganizationProviderAndNameIgnoreCase(99L, "Task"))
                    .thenReturn(Optional.of(providerScoped));

            processor.processFromSync(syncDataWithType("TASK"), testRepo, 1L);

            verify(issueTypeRepository).findFirstByOrganizationProviderAndNameIgnoreCase(99L, "Task");
            verify(issueRepository)
                    .upsertCore(
                            eq(RAW_ISSUE_ID),
                            eq(PROVIDER_ID),
                            eq(ISSUE_IID),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            any(),
                            eq("gid://gitlab/IssueType/global-1"),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void shouldResolveNullIssueTypeWhenOrganizationMissing() {
            // No organization on the repository → early return null; the issue_type FK passed to upsert is
            // null and the COALESCE on the DB side preserves any previously set value.
            testRepo.setOrganization(null);

            processor.processFromSync(syncDataWithType("TASK"), testRepo, 1L);

            verify(issueRepository)
                    .upsertCore(
                            eq(RAW_ISSUE_ID),
                            eq(PROVIDER_ID),
                            eq(ISSUE_IID),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(REPO_ID),
                            any(),
                            eq((String) null),
                            any(),
                            any(),
                            any(),
                            any());
        }

        /** A minimal sync payload carrying the given GraphQL issue-type enum (e.g. {@code TASK}). */
        private GitLabIssueProcessor.SyncIssueData syncDataWithType(String typeName) {
            return new GitLabIssueProcessor.SyncIssueData(
                    "gid://gitlab/Issue/422296",
                    "5",
                    "Title",
                    null,
                    "opened",
                    false,
                    "https://example.com",
                    null,
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
                    typeName,
                    null);
        }
    }

    private ProcessingContext createContext() {
        return ProcessingContext.forWebhook(1L, testRepo, "open");
    }

    private Issue createIssueEntity() {
        Issue issue = new Issue();
        issue.setId(ENTITY_ISSUE_ID);
        issue.setNumber(ISSUE_IID);
        issue.setTitle("Feature: Add user authentication");
        issue.setState(Issue.State.OPEN);
        issue.setRepository(testRepo);
        issue.setLabels(new HashSet<>());
        issue.setAssignees(new HashSet<>());
        return issue;
    }

    private User createUserEntity() {
        User user = new User();
        user.setId(ENTITY_USER_ID);
        user.setLogin("ga84xah");
        user.setName("Felix Dietrich");
        return user;
    }

    private GitLabIssueEventDTO createEvent(String action, @Nullable String state, boolean confidential) {
        var attrs = new GitLabIssueEventDTO.ObjectAttributes(
                RAW_ISSUE_ID,
                ISSUE_IID,
                "Feature: Add user authentication",
                "Implement OAuth2 authentication flow",
                state,
                action,
                confidential,
                RAW_USER_ID,
                null,
                null,
                "2026-01-31 19:03:35 +0100",
                "2026-01-31 19:03:35 +0100",
                null,
                null,
                null,
                "https://gitlab.lrz.de/hephaestustest/demo-repository/-/issues/5");
        return new GitLabIssueEventDTO(
                "issue",
                confidential ? "confidential_issue" : "issue",
                createUser(),
                createProject(),
                attrs,
                List.of(new GitLabWebhookLabel(85907L, "enhancement", "#a2eeef")),
                null,
                null);
    }

    /** An {@code action=update} event carrying the given {@code changes} diff and no label delta. */
    private GitLabIssueEventDTO createUpdateEventWithChanges(GitLabIssueEventDTO.Changes changes) {
        return withChanges(null, changes);
    }

    private GitLabIssueEventDTO createUpdateEventWithAddedLabel(GitLabWebhookLabel addedLabel) {
        return withChanges(
                addedLabel,
                new GitLabIssueEventDTO.Changes(
                        new GitLabIssueEventDTO.LabelsChange(List.of(), List.of(addedLabel)),
                        null,
                        null,
                        null,
                        null,
                        null));
    }

    private GitLabIssueEventDTO withChanges(
            @Nullable GitLabWebhookLabel addedLabel, GitLabIssueEventDTO.Changes changes) {
        var attrs = new GitLabIssueEventDTO.ObjectAttributes(
                RAW_ISSUE_ID,
                ISSUE_IID,
                "Feature: Add user authentication",
                "Implement OAuth2 authentication flow",
                "opened",
                "update",
                false,
                RAW_USER_ID,
                null,
                null,
                "2026-01-31 19:03:35 +0100",
                "2026-01-31 19:03:35 +0100",
                null,
                null,
                null,
                "https://gitlab.lrz.de/hephaestustest/demo-repository/-/issues/5");
        return new GitLabIssueEventDTO(
                "issue",
                "issue",
                createUser(),
                createProject(),
                attrs,
                addedLabel == null ? List.of() : List.of(addedLabel),
                null,
                changes);
    }

    private GitLabWebhookUser createUser() {
        return new GitLabWebhookUser(
                RAW_USER_ID,
                "ga84xah",
                "Felix Dietrich",
                "https://gitlab.lrz.de/uploads/-/system/user/avatar/18024/avatar.png",
                null);
    }

    private GitLabWebhookProject createProject() {
        return new GitLabWebhookProject(
                246765L,
                "demo-repository",
                "https://gitlab.lrz.de/hephaestustest/demo-repository",
                "hephaestustest/demo-repository");
    }

    /**
     * DTO-level coverage for {@code GitLabIssueEventDTO.addedLabels()} — the current-minus-previous
     * delta. Independent of processor mocking so the filter logic is pinned directly.
     */
    @Nested
    class AddedLabelsDelta {

        private GitLabIssueEventDTO eventWithLabelChange(
                @Nullable List<GitLabWebhookLabel> previous, List<GitLabWebhookLabel> current) {
            var changes = new GitLabIssueEventDTO.Changes(
                    new GitLabIssueEventDTO.LabelsChange(previous, current), null, null, null, null, null);
            return new GitLabIssueEventDTO("issue", "issue", null, null, null, List.of(), null, changes);
        }

        @Test
        void onlyEmitsLabelsAbsentFromPrevious() {
            // current = {1,2}, previous = {1}: only id=2 is newly added; the re-sent id=1 must NOT re-emit.
            var label1 = new GitLabWebhookLabel(1L, "bug", "#ff0000");
            var label2 = new GitLabWebhookLabel(2L, "feature", "#00ff00");

            var added = eventWithLabelChange(List.of(label1), List.of(label1, label2))
                    .addedLabels();

            assertThat(added).extracting(GitLabWebhookLabel::id).containsExactly(2L);
        }

        @Test
        void emitsNothingWhenLabelAlreadyPresent() {
            // An unrelated edit that re-sends an existing label (previous == current) must not trigger
            // IssueLabeled.
            var label1 = new GitLabWebhookLabel(1L, "bug", "#ff0000");

            var added = eventWithLabelChange(List.of(label1), List.of(label1)).addedLabels();

            assertThat(added).isEmpty();
        }

        @Test
        void treatsNullPreviousAsAllAdded() {
            var label1 = new GitLabWebhookLabel(1L, "bug", "#ff0000");

            var added = eventWithLabelChange(null, List.of(label1)).addedLabels();

            assertThat(added).extracting(GitLabWebhookLabel::id).containsExactly(1L);
        }

        @Test
        void emptyWhenNoLabelChangeSection() {
            // No changes diff at all → an ordinary title/description edit never spuriously adds labels.
            var event = new GitLabIssueEventDTO("issue", "issue", null, null, null, List.of(), null, null);

            assertThat(event.addedLabels()).isEmpty();
        }
    }
}
