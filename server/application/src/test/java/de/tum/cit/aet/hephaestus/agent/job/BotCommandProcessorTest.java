package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.events.BotCommandReceivedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmCommentReactionSink;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

@Tag("unit")
class BotCommandProcessorTest extends BaseUnitTest {

    private static final long REPO_ID = 100L;
    private static final int MR_NUMBER = 42;
    private static final String AUTHOR = "student1";
    private static final long PROVIDER_ID = 3L;
    private static final long AUTHOR_NATIVE_ID = 77L;
    private static final String REPO_NAME = "hephaestustest/demo-repository";

    @Mock
    private ManualReviewRequests manualReviewRequests;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private WorkspaceResolver workspaceResolver;

    private BotCommandProcessor processor;

    private boolean silentModeEngaged;

    @BeforeEach
    void setUp() {
        silentModeEngaged = false;
        processor = new BotCommandProcessor(
                manualReviewRequests,
                pullRequestRepository,
                userRepository,
                workspaceResolver,
                List.of(),
                () -> silentModeEngaged);
    }

    @Nested
    class CommandMatching {

        @ParameterizedTest(name = "{0} triggers a review")
        @ValueSource(strings = {"/hephaestus review", "/hephaestus review please", "/Hephaestus Review"})
        void anAcceptedReviewCommand_asksForAReviewOfThatMergeRequest(String command) {
            PullRequest pr = createOpenPr();
            mockPrLookup(pr);
            Workspace workspace = mockWorkspace();
            User commenter = mockCommenter();
            when(manualReviewRequests.requestPullRequestReview(any(), any(), any()))
                    .thenReturn(ManualReviewOutcome.submitted(UUID.randomUUID()));

            processor.onBotCommandReceived(event(command));

            verify(manualReviewRequests).requestPullRequestReview(eq(workspace), eq(pr), eq(List.of(commenter)));
        }

        @ParameterizedTest(name = "{0} is ignored")
        @ValueSource(strings = {"/hephaestus review-all", "/hephaestus reviewcode", "/hephaestus deploy"})
        void aCommandThatIsNotReview_isSilentlyIgnored(String command) {
            processor.onBotCommandReceived(event(command));

            verify(pullRequestRepository, never()).findByRepositoryIdAndNumber(anyLong(), anyInt());
            verify(manualReviewRequests, never()).requestPullRequestReview(any(), any(), any());
        }
    }

    /** These tests pin only that the right identity reaches {@link ReviewRequestAuthority}. */
    @Nested
    class CommenterIdentity {

        @Test
        void theCommenterIsResolvedByProviderAndNativeId_notByLogin() {
            PullRequest pr = createOpenPr();
            mockPrLookup(pr);
            mockWorkspace();
            User commenter = mockCommenter();
            when(manualReviewRequests.requestPullRequestReview(any(), any(), any()))
                    .thenReturn(ManualReviewOutcome.submitted(UUID.randomUUID()));

            processor.onBotCommandReceived(event("/hephaestus review"));

            verify(userRepository).findByNativeIdAndProviderId(AUTHOR_NATIVE_ID, PROVIDER_ID);
            verify(userRepository, never()).findByLogin(any());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Collection<User>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
            verify(manualReviewRequests).requestPullRequestReview(any(), any(), captor.capture());
            assertThat(captor.getValue()).containsExactly(commenter);
        }

        @Test
        void anUnknownCommenterIsHandedOnAsNobody_ratherThanSkippingTheCheck() {
            PullRequest pr = createOpenPr();
            mockPrLookup(pr);
            mockWorkspace();
            when(userRepository.findByNativeIdAndProviderId(AUTHOR_NATIVE_ID, PROVIDER_ID))
                    .thenReturn(Optional.empty());
            when(manualReviewRequests.requestPullRequestReview(any(), any(), any()))
                    .thenReturn(ManualReviewOutcome.forbidden());

            processor.onBotCommandReceived(event("/hephaestus review"));

            verify(manualReviewRequests).requestPullRequestReview(any(), any(), eq(List.of()));
        }

        @Test
        void aRepositoryNoWorkspaceMonitors_asksForNothing() {
            PullRequest pr = createOpenPr();
            mockPrLookup(pr);
            when(workspaceResolver.resolveForRepository(REPO_NAME)).thenReturn(Optional.empty());

            processor.onBotCommandReceived(event("/hephaestus review"));

            verify(manualReviewRequests, never()).requestPullRequestReview(any(), any(), any());
        }
    }

    @Nested
    class PrValidation {

        @Test
        void prNotFound_skipsProcessing() {
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_NUMBER))
                    .thenReturn(Optional.empty());

            processor.onBotCommandReceived(event("/hephaestus review"));

            verify(manualReviewRequests, never()).requestPullRequestReview(any(), any(), any());
        }

        @Test
        void closedPr_skipsProcessing() {
            mockPrLookup(createPrWithState(PullRequest.State.CLOSED));

            processor.onBotCommandReceived(event("/hephaestus review"));

            verify(manualReviewRequests, never()).requestPullRequestReview(any(), any(), any());
        }

        @Test
        void mergedPr_skipsProcessing() {
            mockPrLookup(createPrWithState(PullRequest.State.MERGED));

            processor.onBotCommandReceived(event("/hephaestus review"));

            verify(manualReviewRequests, never()).requestPullRequestReview(any(), any(), any());
        }

        @Test
        void missingBranchInfo_skipsProcessing() {
            PullRequest pr = createOpenPr();
            org.springframework.test.util.ReflectionTestUtils.setField(pr, "headRefOid", null);
            mockPrLookup(pr);

            processor.onBotCommandReceived(event("/hephaestus review"));

            verify(manualReviewRequests, never()).requestPullRequestReview(any(), any(), any());
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void exceptionDuringProcessing_doesNotPropagate() {
            when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_NUMBER))
                    .thenThrow(new RuntimeException("DB connection failed"));

            processor.onBotCommandReceived(event("/hephaestus review"));

            verify(manualReviewRequests, never()).requestPullRequestReview(any(), any(), any());
        }
    }

    @Nested
    class EyesReaction {

        private final ScmCommentReactionSink sink = mock(ScmCommentReactionSink.class);

        private BotCommandProcessor processorWithSink() {
            when(sink.kind()).thenReturn(IntegrationKind.GITLAB);
            return new BotCommandProcessor(
                    manualReviewRequests,
                    pullRequestRepository,
                    userRepository,
                    workspaceResolver,
                    List.of(sink),
                    () -> silentModeEngaged);
        }

        @Test
        void acknowledgesCommandWhenNotSilenced() {
            processorWithSink().onBotCommandReceived(eventWithReactionTarget("/hephaestus deploy"));
            verify(sink).react(9L, 7L, "eyes");
        }

        @Test
        void silentModeSuppressesTheAcknowledgementReaction() {
            silentModeEngaged = true;
            processorWithSink().onBotCommandReceived(eventWithReactionTarget("/hephaestus deploy"));
            verify(sink, never()).react(anyLong(), anyLong(), any());
        }

        private BotCommandReceivedEvent eventWithReactionTarget(String noteBody) {
            // commentId = 7, scopeId = 9; react() takes (scopeId, commentNativeId, name).
            return new BotCommandReceivedEvent(
                    IntegrationKind.GITLAB,
                    REPO_ID,
                    MR_NUMBER,
                    noteBody,
                    AUTHOR,
                    PROVIDER_ID,
                    AUTHOR_NATIVE_ID,
                    7L,
                    9L);
        }
    }

    // Test helpers

    private BotCommandReceivedEvent event(String noteBody) {
        return new BotCommandReceivedEvent(
                IntegrationKind.GITLAB,
                REPO_ID,
                MR_NUMBER,
                noteBody,
                AUTHOR,
                PROVIDER_ID,
                AUTHOR_NATIVE_ID,
                null,
                null);
    }

    private PullRequest createOpenPr() {
        return createPrWithState(PullRequest.State.OPEN);
    }

    private PullRequest createPrWithState(PullRequest.State state) {
        Repository repo = new Repository();
        repo.setId(REPO_ID);
        repo.setNameWithOwner(REPO_NAME);
        repo.setHtmlUrl("https://gitlab.example.com/" + REPO_NAME);

        PullRequest pr = new PullRequest();
        pr.setId(500L);
        pr.setNumber(MR_NUMBER);
        pr.setState(state);
        pr.setTitle("Test MR");
        pr.setHtmlUrl("https://gitlab.example.com/" + REPO_NAME + "/-/merge_requests/" + MR_NUMBER);
        pr.setHeadRefOid("abc123");
        pr.setHeadRefName("feature/branch");
        pr.setBaseRefName("main");
        pr.setBaseRefOid("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        pr.setRepository(repo);
        return pr;
    }

    private void mockPrLookup(PullRequest pr) {
        when(pullRequestRepository.findByRepositoryIdAndNumber(REPO_ID, MR_NUMBER))
                .thenReturn(Optional.of(pr));
        when(pullRequestRepository.findByIdWithAllForGate(pr.getId())).thenReturn(Optional.of(pr));
    }

    private Workspace mockWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(1L);
        when(workspaceResolver.resolveForRepository(REPO_NAME)).thenReturn(Optional.of(workspace));
        return workspace;
    }

    private User mockCommenter() {
        User commenter = new User();
        commenter.setId(4242L);
        commenter.setLogin(AUTHOR);
        when(userRepository.findByNativeIdAndProviderId(AUTHOR_NATIVE_ID, PROVIDER_ID))
                .thenReturn(Optional.of(commenter));
        return commenter;
    }
}
