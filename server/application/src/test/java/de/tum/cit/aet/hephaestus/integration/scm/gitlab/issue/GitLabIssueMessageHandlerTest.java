package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.dto.GitLabIssueEventDTO;
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
import org.springframework.transaction.support.TransactionTemplate;

@Tag("unit")
class GitLabIssueMessageHandlerTest extends BaseUnitTest {

    private static final String PROJECT_PATH = "hephaestustest/demo-repository";
    private static final String NATS_SUBJECT = "gitlab.hephaestustest.demo-repository.issue";

    @Mock
    private GitLabIssueProcessor issueProcessor;

    @Mock
    private GitLabWebhookContextResolver contextResolver;

    @Mock
    private NatsMessageDeserializer deserializer;

    private TransactionTemplate transactionTemplate;
    private GitLabIssueMessageHandler handler;

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

        handler = new GitLabIssueMessageHandler(issueProcessor, contextResolver, deserializer, transactionTemplate);

        lenient()
                .when(contextResolver.resolve(eq(PROJECT_PATH), any(), any()))
                .thenReturn(ProcessingContext.forWebhook(1L, setupRepository(), "open"));
    }

    @Test
    void key_returnsIssue() {
        assertThat(handler.key().eventType()).isEqualTo("issue");
    }

    @Nested
    class ActionRouting {

        @Test
        void openAction_routesToProcess() throws IOException {
            GitLabIssueEventDTO event = createEvent("open", "opened", false);
            setupRepository();

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(issueProcessor).process(eq(event), any(ProcessingContext.class));
            verify(issueProcessor, never()).processClosed(any(), any());
            verify(issueProcessor, never()).processReopened(any(), any());
        }

        @Test
        void updateAction_routesToProcessUpdated() throws IOException {
            // GitLab has no native "labeled" action — a label add arrives as action=update with a
            // changes.labels diff. UPDATE therefore routes to processUpdated (which derives the added
            // labels and publishes IssueLabeled), not the plain process() path.
            GitLabIssueEventDTO event = createEvent("update", "closed", false);
            setupRepository();

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(issueProcessor).processUpdated(eq(event), any(ProcessingContext.class));
            verify(issueProcessor, never()).process(any(), any());
        }

        @Test
        void closeAction_routesToProcessClosed() throws IOException {
            GitLabIssueEventDTO event = createEvent("close", "closed", false);
            setupRepository();

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(issueProcessor).processClosed(eq(event), any(ProcessingContext.class));
            verify(issueProcessor, never()).process(any(), any());
        }

        @Test
        void reopenAction_routesToProcessReopened() throws IOException {
            GitLabIssueEventDTO event = createEvent("reopen", "opened", false);
            setupRepository();

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(issueProcessor).processReopened(eq(event), any(ProcessingContext.class));
            verify(issueProcessor, never()).process(any(), any());
        }

        @Test
        void unknownAction_skipsProcessing() throws IOException {
            GitLabIssueEventDTO event = createEvent("unknown_action", "opened", false);
            setupRepository();

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(issueProcessor, never()).process(any(), any());
            verify(issueProcessor, never()).processClosed(any(), any());
            verify(issueProcessor, never()).processReopened(any(), any());
        }

        @Test
        void nullAction_skipsProcessing() throws IOException {
            // A genuinely malformed payload omits object_attributes.action entirely (null), not a bogus
            // string. actionType() guards null → UNKNOWN → the default/skip branch, so no processor runs.
            var attrs = new GitLabIssueEventDTO.ObjectAttributes(
                    422296L,
                    5,
                    "Title",
                    "desc",
                    "opened",
                    null,
                    false,
                    18024L,
                    null,
                    null,
                    "2026-01-31 19:03:35 +0100",
                    "2026-01-31 19:03:35 +0100",
                    null,
                    null,
                    null,
                    "https://gitlab.lrz.de/hephaestustest/demo-repository/-/issues/5");
            GitLabIssueEventDTO event = new GitLabIssueEventDTO(
                    "issue",
                    "issue",
                    createUser(),
                    createProject(),
                    attrs,
                    List.of(new GitLabWebhookLabel(85907L, "enhancement", "#a2eeef")),
                    null,
                    null);

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(issueProcessor, never()).process(any(), any());
            verify(issueProcessor, never()).processUpdated(any(), any());
            verify(issueProcessor, never()).processClosed(any(), any());
            verify(issueProcessor, never()).processReopened(any(), any());
        }
    }

    @Nested
    class ConfidentialIssues {

        @Test
        void confidentialIssue_purgesSnapshotInsteadOfProcessing() throws IOException {
            // A public → confidential flip must NOT (re)ingest, but MUST purge any already-mirrored public
            // snapshot — so the handler resolves the context (to know the repo) and delegates to
            // purgeConfidential rather than short-circuiting before context resolution.
            GitLabIssueEventDTO event = createEvent("update", "opened", true);

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(issueProcessor).purgeConfidential(eq(event), any(ProcessingContext.class));
            verify(issueProcessor, never()).process(any(), any());
            verify(issueProcessor, never()).processUpdated(any(), any());
        }

        @Test
        void confidentialIssueEventType_purgesSnapshot() throws IOException {
            var attrs = new GitLabIssueEventDTO.ObjectAttributes(
                    422297L,
                    6,
                    "Security issue",
                    "desc",
                    "opened",
                    "open",
                    true,
                    18024L,
                    null,
                    null,
                    "2026-01-31 19:03:35 +0100",
                    "2026-01-31 19:03:35 +0100",
                    null,
                    null,
                    null,
                    "https://gitlab.lrz.de/hephaestustest/demo-repository/-/issues/6");
            GitLabIssueEventDTO event = new GitLabIssueEventDTO(
                    "issue", "confidential_issue", createUser(), createProject(), attrs, null, null, null);

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(issueProcessor).purgeConfidential(eq(event), any(ProcessingContext.class));
            verify(issueProcessor, never()).process(any(), any());
        }

        @Test
        void confidentialIssueOnUntrackedRepo_doesNotPurge() throws IOException {
            // No context (repo not monitored) → nothing was ever stored → no purge attempt.
            when(contextResolver.resolve(eq(PROJECT_PATH), any(), any())).thenReturn(null);
            GitLabIssueEventDTO event = createEvent("update", "opened", true);

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(issueProcessor, never()).purgeConfidential(any(), any());
            verify(issueProcessor, never()).process(any(), any());
        }
    }

    @Nested
    class PayloadValidation {

        @Test
        void missingObjectAttributes_skipsProcessing() throws IOException {
            GitLabIssueEventDTO event =
                    new GitLabIssueEventDTO("issue", "issue", createUser(), createProject(), null, null, null, null);

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(issueProcessor, never()).process(any(), any());
        }

        @Test
        void missingProject_skipsProcessing() throws IOException {
            var attrs = new GitLabIssueEventDTO.ObjectAttributes(
                    422296L,
                    5,
                    "Title",
                    "desc",
                    "opened",
                    "open",
                    false,
                    18024L,
                    null,
                    null,
                    "2026-01-31 19:03:35 +0100",
                    "2026-01-31 19:03:35 +0100",
                    null,
                    null,
                    null,
                    "https://example.com");
            GitLabIssueEventDTO event =
                    new GitLabIssueEventDTO("issue", "issue", createUser(), null, attrs, null, null, null);

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(issueProcessor, never()).process(any(), any());
        }

        @Test
        void nonIssueSubject_rejected() throws IOException {
            Message msg = mock(Message.class);
            when(msg.getSubject()).thenReturn("gitlab.org.proj.merge_request");

            handler.onMessage(msg);

            verify(deserializer, never()).deserialize(any(), any());
            verify(issueProcessor, never()).process(any(), any());
        }
    }

    @Nested
    class ContextResolution {

        @Test
        void contextResolverReturnsNull_skipsProcessing() throws IOException {
            when(contextResolver.resolve(eq(PROJECT_PATH), any(), any())).thenReturn(null);
            GitLabIssueEventDTO event = createEvent("open", "opened", false);

            Message msg = mockMessage(event);
            handler.onMessage(msg);

            verify(issueProcessor, never()).process(any(), any());
        }
    }

    // Helpers

    private Repository setupRepository() {
        Repository repo = new Repository();
        repo.setId(-246765L);
        repo.setNameWithOwner(PROJECT_PATH);
        return repo;
    }

    private GitLabIssueEventDTO createEvent(String action, String state, boolean confidential) {
        var attrs = new GitLabIssueEventDTO.ObjectAttributes(
                422296L,
                5,
                "Feature: Add user authentication",
                "Implement OAuth2 authentication flow",
                state,
                action,
                confidential,
                18024L,
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

    private GitLabWebhookUser createUser() {
        return new GitLabWebhookUser(
                18024L,
                "ga84xah",
                "Felix Dietrich",
                "https://gitlab.lrz.de/uploads/-/system/user/avatar/18024/avatar.png",
                null);
    }

    private GitLabWebhookProject createProject() {
        return new GitLabWebhookProject(
                246765L, "demo-repository", "https://gitlab.lrz.de/hephaestustest/demo-repository", PROJECT_PATH);
    }

    private Message mockMessage(GitLabIssueEventDTO event) throws IOException {
        Message msg = mock(Message.class);
        when(msg.getSubject()).thenReturn(NATS_SUBJECT);
        when(deserializer.deserialize(msg, GitLabIssueEventDTO.class)).thenReturn(event);
        return msg;
    }
}
