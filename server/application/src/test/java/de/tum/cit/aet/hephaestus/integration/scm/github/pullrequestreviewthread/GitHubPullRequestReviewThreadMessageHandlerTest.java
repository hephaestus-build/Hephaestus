package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewthread;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewthread.dto.GitHubPullRequestReviewThreadEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Message;
import io.nats.client.impl.NatsJetStreamMetaData;
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
    private NatsMessageDeserializer deserializer;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Test
    void shouldDateThePullRequestsReviewRequestsByWhenJetStreamStoredTheEvent() throws Exception {
        GitHubPullRequestReviewThreadEventDTO event = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build()
                .readValue(
                        new ClassPathResource("github/pull_request_review_thread.unresolved.json").getInputStream(),
                        GitHubPullRequestReviewThreadEventDTO.class);
        ZonedDateTime storedAt = ZonedDateTime.parse("2025-11-05T12:14:02Z");
        Message msg = mock(Message.class);
        NatsJetStreamMetaData metaData = mock(NatsJetStreamMetaData.class);
        when(msg.getSubject()).thenReturn("github.HephaestusTest.demo-repository.pull_request_review_thread");
        when(msg.isJetStream()).thenReturn(true);
        when(msg.metaData()).thenReturn(metaData);
        when(metaData.timestamp()).thenReturn(storedAt);
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
}
