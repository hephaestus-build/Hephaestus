package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookContextResolver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookLabel;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookProject;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookUser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.dto.GitLabMergeRequestEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Message;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("unit")
class GitLabMergeRequestMessageHandlerTest extends BaseUnitTest {

    private static final String PROJECT_PATH = "gitlab-org/gitlab";
    private static final String NATS_SUBJECT = "gitlab.gitlab-org.gitlab.merge_request";

    @Mock
    private GitLabMergeRequestProcessor mergeRequestProcessor;

    @Mock
    private GitLabWebhookContextResolver contextResolver;

    @Mock
    private NatsMessageDeserializer deserializer;

    @Mock
    private GitLabClosingIssueClient closingIssueClient;

    private TransactionTemplate transactionTemplate;
    private GitLabMergeRequestMessageHandler handler;

    @BeforeEach
    void setUp() {
        transactionTemplate = mock(TransactionTemplate.class);
        lenient()
                .doAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<TransactionStatus> callback = invocation.getArgument(0);
                    callback.accept(null);
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        handler = new GitLabMergeRequestMessageHandler(
                mergeRequestProcessor, contextResolver, closingIssueClient, deserializer, transactionTemplate);

        // Default: context resolver returns a valid context
        lenient()
                .when(contextResolver.resolve(eq(PROJECT_PATH), any(), any()))
                .thenReturn(ProcessingContext.forWebhook(1L, setupRepository(), "open"));
    }

    @Test
    void key_returnsMergeRequest() {
        assertThat(handler.key().eventType()).isEqualTo("merge_request");
    }

    // Action Routing

    @Nested
    class ActionRouting {

        @Test
        void openAction_routesToProcess() throws IOException {
            GitLabMergeRequestEventDTO event = createEvent("open", "opened", false);
            setupRepository();

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(mergeRequestProcessor).process(eq(event), any(ProcessingContext.class));
            verify(mergeRequestProcessor, never()).processClosed(any(), any());
            verify(mergeRequestProcessor, never()).processReopened(any(), any());
            verify(mergeRequestProcessor, never()).processMerged(any(), any());
            verify(mergeRequestProcessor, never()).processApproved(any(), any());
            verify(mergeRequestProcessor, never()).processUnapproved(any(), any());
        }

        @Test
        void updateAction_routesToProcess() throws IOException {
            GitLabMergeRequestEventDTO event = createEvent("update", "opened", false);
            setupRepository();

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(mergeRequestProcessor).process(eq(event), any(ProcessingContext.class));
        }

        @Test
        void shouldReadTheIssuesAnUpdatedMergeRequestClosesAfterStoringTheEvent() throws IOException {
            // The event stores updated_at, the moment the sync later compares against, so the links
            // it would read for this change are read here instead.
            GitLabMergeRequestEventDTO event = createEvent("update", "opened", false);
            setupRepository();
            when(closingIssueClient.closesIssues(1L, 278964L, 5)).thenReturn(List.of(41, 42));

            handler.onMessage(mockMessage(event));

            verify(mergeRequestProcessor).replaceClosingIssues(any(), eq(5), eq(List.of(41, 42)));
        }

        @Test
        void shouldLeaveTheLinksAloneWhenTheRouteCouldNotBeRead() throws IOException {
            GitLabMergeRequestEventDTO event = createEvent("open", "opened", false);
            setupRepository();
            when(closingIssueClient.closesIssues(1L, 278964L, 5)).thenReturn(null);

            handler.onMessage(mockMessage(event));

            verify(mergeRequestProcessor, never()).replaceClosingIssues(any(), anyInt(), any());
        }

        @Test
        void shouldNotReadTheLinksForAnApprovalEvent() throws IOException {
            GitLabMergeRequestEventDTO event = createEvent("approved", "opened", false);
            setupRepository();

            handler.onMessage(mockMessage(event));

            verify(closingIssueClient, never()).closesIssues(any(), anyLong(), anyInt());
        }

        @Test
        void closeAction_routesToProcessClosed() throws IOException {
            GitLabMergeRequestEventDTO event = createEvent("close", "closed", false);
            setupRepository();

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(mergeRequestProcessor).processClosed(eq(event), any(ProcessingContext.class));
            verify(mergeRequestProcessor, never()).process(any(), any());
        }

        @Test
        void reopenAction_routesToProcessReopened() throws IOException {
            GitLabMergeRequestEventDTO event = createEvent("reopen", "opened", false);
            setupRepository();

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(mergeRequestProcessor).processReopened(eq(event), any(ProcessingContext.class));
            verify(mergeRequestProcessor, never()).process(any(), any());
        }

        @Test
        void mergeAction_routesToProcessMerged() throws IOException {
            GitLabMergeRequestEventDTO event = createEvent("merge", "merged", false);
            setupRepository();

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(mergeRequestProcessor).processMerged(eq(event), any(ProcessingContext.class));
            verify(mergeRequestProcessor, never()).process(any(), any());
        }

        @Test
        void approvedAction_routesToProcessApproved() throws IOException {
            GitLabMergeRequestEventDTO event = createEvent("approved", "opened", false);
            setupRepository();

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(mergeRequestProcessor).processApproved(eq(event), any(ProcessingContext.class));
            verify(mergeRequestProcessor, never()).process(any(), any());
        }

        @Test
        void unapprovedAction_routesToProcessUnapproved() throws IOException {
            GitLabMergeRequestEventDTO event = createEvent("unapproved", "opened", false);
            setupRepository();

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(mergeRequestProcessor).processUnapproved(eq(event), any(ProcessingContext.class));
            verify(mergeRequestProcessor, never()).process(any(), any());
        }

        @Test
        void approvalAction_skipsProcessing() throws IOException {
            GitLabMergeRequestEventDTO event = createEvent("approval", "opened", false);
            setupRepository();

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(mergeRequestProcessor, never()).process(any(), any());
            verify(mergeRequestProcessor, never()).processApproved(any(), any());
            verify(mergeRequestProcessor, never()).processUnapproved(any(), any());
            verify(mergeRequestProcessor, never()).processClosed(any(), any());
            verify(mergeRequestProcessor, never()).processReopened(any(), any());
            verify(mergeRequestProcessor, never()).processMerged(any(), any());
        }

        @Test
        void unapprovalAction_skipsProcessing() throws IOException {
            GitLabMergeRequestEventDTO event = createEvent("unapproval", "opened", false);
            setupRepository();

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(mergeRequestProcessor, never()).process(any(), any());
            verify(mergeRequestProcessor, never()).processApproved(any(), any());
            verify(mergeRequestProcessor, never()).processUnapproved(any(), any());
            verify(mergeRequestProcessor, never()).processClosed(any(), any());
            verify(mergeRequestProcessor, never()).processReopened(any(), any());
            verify(mergeRequestProcessor, never()).processMerged(any(), any());
        }

        @Test
        void unknownAction_skipsProcessing() throws IOException {
            GitLabMergeRequestEventDTO event = createEvent("unknown_action", "opened", false);
            setupRepository();

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(mergeRequestProcessor, never()).process(any(), any());
            verify(mergeRequestProcessor, never()).processClosed(any(), any());
            verify(mergeRequestProcessor, never()).processReopened(any(), any());
            verify(mergeRequestProcessor, never()).processMerged(any(), any());
            verify(mergeRequestProcessor, never()).processApproved(any(), any());
            verify(mergeRequestProcessor, never()).processUnapproved(any(), any());
        }
    }

    // Confidential Merge Request Handling

    @Nested
    class ConfidentialMergeRequests {

        @Test
        void confidentialMergeRequest_skipsProcessing() throws IOException {
            var attrs = new GitLabMergeRequestEventDTO.ObjectAttributes(
                    999555L,
                    5,
                    "Secret MR",
                    "Confidential desc",
                    "opened",
                    "open",
                    "feature/secret",
                    "main",
                    false,
                    12345L,
                    null,
                    null,
                    "2024-01-15T10:00:00Z",
                    "2024-01-15T10:00:00Z",
                    null,
                    null,
                    "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                    null,
                    null,
                    null);
            GitLabMergeRequestEventDTO event = new GitLabMergeRequestEventDTO(
                    "merge_request",
                    "confidential_merge_request",
                    createUser(),
                    createProject(),
                    attrs,
                    null,
                    null,
                    null);

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(mergeRequestProcessor, never()).process(any(), any());
            verify(mergeRequestProcessor, never()).processClosed(any(), any());
            verify(mergeRequestProcessor, never()).processReopened(any(), any());
            verify(mergeRequestProcessor, never()).processMerged(any(), any());
            verify(mergeRequestProcessor, never()).processApproved(any(), any());
            verify(mergeRequestProcessor, never()).processUnapproved(any(), any());
        }
    }

    // Validation

    @Nested
    class PayloadValidation {

        @Test
        void missingObjectAttributes_skipsProcessing() throws IOException {
            GitLabMergeRequestEventDTO event = new GitLabMergeRequestEventDTO(
                    "merge_request", "merge_request", createUser(), createProject(), null, null, null, null);

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(mergeRequestProcessor, never()).process(any(), any());
        }

        @Test
        void missingProject_skipsProcessing() throws IOException {
            var attrs = new GitLabMergeRequestEventDTO.ObjectAttributes(
                    999555L,
                    5,
                    "Title",
                    "desc",
                    "opened",
                    "open",
                    "feature/branch",
                    "main",
                    false,
                    12345L,
                    null,
                    null,
                    "2024-01-15T10:00:00Z",
                    "2024-01-15T10:00:00Z",
                    null,
                    null,
                    "https://example.com",
                    null,
                    null,
                    null);
            GitLabMergeRequestEventDTO event = new GitLabMergeRequestEventDTO(
                    "merge_request", "merge_request", createUser(), null, attrs, null, null, null);

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(mergeRequestProcessor, never()).process(any(), any());
        }

        @Test
        void nonMergeRequestSubject_rejected() throws IOException {
            Message msg = mock(Message.class);
            when(msg.getSubject()).thenReturn("gitlab.org.proj.issue");

            handler.onMessage(msg);

            verify(deserializer, never()).deserialize(any(), any());
            verify(mergeRequestProcessor, never()).process(any(), any());
        }
    }

    // Context Resolution

    @Nested
    class ContextResolution {

        @Test
        void contextResolverReturnsNull_skipsProcessing() throws IOException {
            when(contextResolver.resolve(eq(PROJECT_PATH), any(), any())).thenReturn(null);
            GitLabMergeRequestEventDTO event = createEvent("open", "opened", false);

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(mergeRequestProcessor, never()).process(any(), any());
        }
    }

    // Helpers

    private Repository setupRepository() {
        Repository repo = new Repository();
        repo.setId(-278964L);
        repo.setNameWithOwner(PROJECT_PATH);
        return repo;
    }

    private GitLabMergeRequestEventDTO createEvent(String action, String state, boolean confidential) {
        var attrs = new GitLabMergeRequestEventDTO.ObjectAttributes(
                999555L,
                5,
                "Add awesome feature",
                "This MR adds an awesome feature",
                state,
                action,
                "feature/awesome-feature",
                "main",
                false,
                12345L,
                null,
                null,
                "2024-01-15T10:00:00Z",
                "2024-01-15T10:00:00Z",
                null,
                null,
                "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/5",
                null,
                null,
                null);
        return new GitLabMergeRequestEventDTO(
                "merge_request",
                confidential ? "confidential_merge_request" : "merge_request",
                createUser(),
                createProject(),
                attrs,
                List.of(new GitLabWebhookLabel(101L, "feature", "#0075ca")),
                null,
                null);
    }

    private GitLabWebhookUser createUser() {
        return new GitLabWebhookUser(
                12345L,
                "testuser",
                "Test User",
                "https://gitlab.com/uploads/-/system/user/avatar/12345/avatar.png",
                null);
    }

    private GitLabWebhookProject createProject() {
        return new GitLabWebhookProject(278964L, "gitlab", "https://gitlab.com/gitlab-org/gitlab", PROJECT_PATH);
    }

    private Message mockMessage(GitLabMergeRequestEventDTO event) throws IOException {
        Message msg = mock(Message.class);
        when(msg.getSubject()).thenReturn(NATS_SUBJECT);
        when(deserializer.deserialize(msg, GitLabMergeRequestEventDTO.class)).thenReturn(event);
        return msg;
    }
}
