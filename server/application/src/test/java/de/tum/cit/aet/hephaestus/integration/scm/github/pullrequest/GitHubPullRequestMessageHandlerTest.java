package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubPullRequestEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.nats.client.Message;
import io.nats.client.impl.NatsJetStreamMetaData;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class GitHubPullRequestMessageHandlerTest extends BaseUnitTest {

    @Mock
    private ProcessingContextFactory contextFactory;

    @Mock
    private GitHubPullRequestProcessor prProcessor;

    @Mock
    private GitHubPullRequestSyncService syncService;

    @Mock
    private NatsMessageDeserializer deserializer;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Test
    void shouldDateTheReviewRequestsByWhenJetStreamStoredTheEvent() throws Exception {
        GitHubPullRequestEventDTO event = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build()
                .readValue(
                        new ClassPathResource("github/pull_request.review_requested.json").getInputStream(),
                        GitHubPullRequestEventDTO.class);
        ZonedDateTime storedAt = ZonedDateTime.parse("2025-11-01T23:31:41Z");
        Message msg = mock(Message.class);
        NatsJetStreamMetaData metaData = mock(NatsJetStreamMetaData.class);
        when(msg.getSubject()).thenReturn("github.HephaestusTest.demo-repository.pull_request");
        when(msg.isJetStream()).thenReturn(true);
        when(msg.metaData()).thenReturn(metaData);
        when(metaData.timestamp()).thenReturn(storedAt);
        when(deserializer.deserialize(msg, GitHubPullRequestEventDTO.class)).thenReturn(event);
        when(contextFactory.forWebhookEvent(event))
                .thenReturn(Optional.of(ProcessingContext.forWebhook(1L, new Repository(), "review_requested")));
        when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation
                        .<TransactionCallback<?>>getArgument(0)
                        .doInTransaction(mock(TransactionStatus.class)));

        new GitHubPullRequestMessageHandler(contextFactory, prProcessor, syncService, deserializer, transactionTemplate)
                .onMessage(msg);

        verify(prProcessor)
                .process(
                        eq(event.pullRequest()),
                        argThat(context -> storedAt.toInstant().equals(context.observedAt())));
    }
}
