package de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.BotCommandReceivedEvent;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmPullRequestItems;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.dto.GitHubIssueCommentEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** {@code /hephaestus review} on a new pull request comment asks for a review, as it does on a GitLab merge request. */
class GitHubReviewCommandTest extends BaseUnitTest {
    private static final long WORKSPACE_ID = 5L;
    private static final long PROVIDER_ID = 3L;
    private static final long PULL_REQUEST_ID = 70L;
    private static final long ITEM_ID = 80L;

    private final ProcessingContextFactory contexts = mock(ProcessingContextFactory.class);
    private final GitHubIssueCommentProcessor comments = mock(GitHubIssueCommentProcessor.class);
    private final PullRequestRepository pullRequests = mock(PullRequestRepository.class);
    private final ScmPullRequestItems items = mock(ScmPullRequestItems.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final GitHubIssueCommentMessageHandler handler = new GitHubIssueCommentMessageHandler(
            contexts,
            mock(GitHubIssueProcessor.class),
            comments,
            pullRequests,
            items,
            events,
            mock(EventPayloadDeserializer.class),
            mock(TransactionTemplate.class));

    @BeforeEach
    void workspaceItem() {
        Repository repository = TestEntities.repository(60L, "HephaestusTest/TestRepository");
        IdentityProvider provider = TestEntities.gitProvider(PROVIDER_ID, IdentityProviderType.GITHUB);
        lenient()
                .when(contexts.forWebhookEvent(any()))
                .thenReturn(Optional.of(new ProcessingContext(
                        WORKSPACE_ID, repository, provider, Instant.now(), "test", "created", DataSource.WEBHOOK)));
        var pullRequest = TestEntities.pullRequest(PULL_REQUEST_ID, 1, "A pull request");
        lenient().when(pullRequests.findByRepositoryIdAndNumber(60L, 1)).thenReturn(Optional.of(pullRequest));
        lenient()
                .when(items.find(WORKSPACE_ID, PULL_REQUEST_ID))
                .thenReturn(Optional.of(new ItemRef(
                        WORKSPACE_ID, ITEM_ID, new ItemKind("scm.pull_request"), PULL_REQUEST_ID, null, null)));
    }

    @Test
    void shouldAskForAReviewWhenANewCommentIsExactlyTheCommand() throws IOException {
        handler.handleEvent(event("created", " /Hephaestus Review \n", "User"));

        var published = ArgumentCaptor.forClass(BotCommandReceivedEvent.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue())
                .isEqualTo(new BotCommandReceivedEvent(
                        IntegrationKind.GITHUB,
                        WORKSPACE_ID,
                        ITEM_ID,
                        " /Hephaestus Review \n",
                        "hephaestususer",
                        PROVIDER_ID,
                        231938035L,
                        3477004001L,
                        "HephaestusTest/TestRepository#1"));
    }

    @Test
    void shouldNotAskWhenTheCommentWasEdited() throws IOException {
        handler.handleEvent(event("edited", "/hephaestus review", "User"));

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void shouldNotAskWhenABotWroteTheComment() throws IOException {
        handler.handleEvent(event("created", "/hephaestus review", "Bot"));

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void shouldNotAskWhenTheCommentSaysMoreThanTheCommand() throws IOException {
        handler.handleEvent(event("created", "/hephaestus review please", "User"));

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void shouldNotAskWhenTheWorkspaceHasNoItemForThePullRequest() throws IOException {
        when(items.find(WORKSPACE_ID, PULL_REQUEST_ID)).thenReturn(Optional.empty());

        handler.handleEvent(event("created", "/hephaestus review", "User"));

        verify(events, never()).publishEvent(any(Object.class));
    }

    /** The recorded pull request comment delivery, with its action, body and author type replaced. */
    private static GitHubIssueCommentEventDTO event(String action, String body, String authorType) throws IOException {
        var mapper = JsonMapper.builder().build();
        String json = new ClassPathResource("github/issue_comment.created.pull-request.json")
                .getContentAsString(StandardCharsets.UTF_8);
        var payload = (ObjectNode) mapper.readTree(json);
        payload.put("action", action);
        var comment = (ObjectNode) payload.get("comment");
        comment.put("body", body);
        ((ObjectNode) comment.get("user")).put("type", authorType);
        return mapper.treeToValue(payload, GitHubIssueCommentEventDTO.class);
    }
}
