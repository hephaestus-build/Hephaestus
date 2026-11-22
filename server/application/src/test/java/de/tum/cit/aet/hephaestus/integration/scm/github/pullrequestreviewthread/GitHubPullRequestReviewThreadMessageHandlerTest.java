package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewthread;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewthread.dto.GitHubPullRequestReviewThreadEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class GitHubPullRequestReviewThreadMessageHandlerTest extends BaseUnitTest {

    @Mock
    private ProcessingContextFactory contextFactory;

    @Mock
    private GitHubPullRequestProcessor prProcessor;

    @Mock
    private GitHubPullRequestReviewThreadProcessor threadProcessor;

    @Mock
    private GitHubUserProcessor userProcessor;

    @Mock
    private EventPayloadDeserializer deserializer;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Test
    void shouldDateThePullRequestsReviewRequestsByWhenTheInboxStoredTheEvent() throws Exception {
        GitHubPullRequestReviewThreadEventDTO event = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build()
                .readValue(
                        new ClassPathResource("github/pull_request_review_thread.unresolved.json").getInputStream(),
                        GitHubPullRequestReviewThreadEventDTO.class);
        ZonedDateTime storedAt = ZonedDateTime.parse("2025-11-05T12:14:02Z");
        ProviderEvent msg = mock(ProviderEvent.class);
        when(msg.subject()).thenReturn("github.HephaestusTest.demo-repository.pull_request_review_thread");
        when(msg.receivedAt()).thenReturn(storedAt.toInstant());
        when(deserializer.deserialize(msg, GitHubPullRequestReviewThreadEventDTO.class))
                .thenReturn(event);
        when(contextFactory.forWebhookEvent(event))
                .thenReturn(Optional.of(ProcessingContext.forWebhook(1L, new Repository(), "unresolved")));
        doAnswer(invocation -> {
                    invocation.<Consumer<TransactionStatus>>getArgument(0).accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());

        new GitHubPullRequestReviewThreadMessageHandler(
                        contextFactory, prProcessor, threadProcessor, userProcessor, deserializer, transactionTemplate)
                .onMessage(msg);

        verify(prProcessor)
                .process(
                        eq(event.pullRequest()),
                        argThat(context -> storedAt.toInstant().equals(context.observedAt())));
    }

    @Test
    void shouldTakeAThreadEventAsAPersonsWhenItsContextNamesNoProvider() throws Exception {
        var event = unresolved();
        handle(event, ProcessingContext.forWebhook(1L, new Repository(), "unresolved"));

        verify(userProcessor, never()).ensureExists(any(), any());
        verify(threadProcessor).unresolve(any(), any(), argThat(context -> context.actorUserId() == null));
    }

    @Test
    void shouldRecordTheSenderAsTheActorOfAThreadEvent() throws Exception {
        var event = unresolved();
        var provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com");
        provider.setId(7L);
        var sender = new User();
        sender.setId(99L);
        when(userProcessor.ensureExists(event.sender(), 7L)).thenReturn(sender);

        handle(event, ProcessingContext.forWebhook(1L, provider, "unresolved"));

        verify(threadProcessor)
                .unresolve(any(), any(), argThat(context -> Long.valueOf(99L).equals(context.actorUserId())));
    }

    private static GitHubPullRequestReviewThreadEventDTO unresolved() throws Exception {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build()
                .readValue(
                        new ClassPathResource("github/pull_request_review_thread.unresolved.json").getInputStream(),
                        GitHubPullRequestReviewThreadEventDTO.class);
    }

    private void handle(GitHubPullRequestReviewThreadEventDTO event, ProcessingContext context) throws Exception {
        ProviderEvent msg = mock(ProviderEvent.class);
        when(msg.subject()).thenReturn("github.HephaestusTest.demo-repository.pull_request_review_thread");
        when(msg.receivedAt())
                .thenReturn(ZonedDateTime.parse("2025-11-05T12:14:02Z").toInstant());
        when(deserializer.deserialize(msg, GitHubPullRequestReviewThreadEventDTO.class))
                .thenReturn(event);
        when(contextFactory.forWebhookEvent(event)).thenReturn(Optional.of(context));
        doAnswer(invocation -> {
                    invocation.<Consumer<TransactionStatus>>getArgument(0).accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
        new GitHubPullRequestReviewThreadMessageHandler(
                        contextFactory, prProcessor, threadProcessor, userProcessor, deserializer, transactionTemplate)
                .onMessage(msg);
    }
}
