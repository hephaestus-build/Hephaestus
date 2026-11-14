package de.tum.cit.aet.hephaestus.integration.slack.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerNotifications;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackMessages;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackReplies;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor;

class SlackConversationProjectorSubscriptionTest extends BaseUnitTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SlackConversationProjector.SubscriptionConfiguration.class)
            .withBean(SlackConversationProjector.class, () -> mock(SlackConversationProjector.class))
            .withBean(ConversationLedgerNotifications.class, () -> mock(ConversationLedgerNotifications.class))
            .withPropertyValues("hephaestus.integration.slack.enabled=true");

    @Test
    void shouldSubscribeTheProjectorOnAServerWithSlack() {
        runner.run(context -> assertThat(context).hasBean("slackConversationProjectorSubscription"));
    }

    @Test
    void shouldReturnToTheLedgerListenerWhileTheOutboxPostIsBlocked() throws Exception {
        var messages = mock(ConversationSlackMessages.class);
        var replies = mock(ConversationSlackReplies.class);
        var executor = mock(ActionExecutor.class);
        var notifications = mock(ConversationLedgerNotifications.class);
        var turnId = UUID.randomUUID();
        var actionId = UUID.randomUUID();
        var question = mock(ConversationEntry.class);
        var questionId = UUID.randomUUID();
        when(question.getId()).thenReturn(questionId);
        when(messages.slackTurn(1L, turnId))
                .thenReturn(Optional.of(new ConversationSlackMessages.SlackTurn(
                        mock(ConversationTurn.class), 2L, "D1", null, question, List.of(), false)));
        when(replies.forQuestion(1L, questionId)).thenReturn(Optional.of(actionId));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    started.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Post was not released");
                    return Optional.empty();
                })
                .when(executor)
                .runNow(actionId);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            new ApplicationContextRunner()
                    .withUserConfiguration(SlackConversationProjector.SubscriptionConfiguration.class)
                    .withPropertyValues("hephaestus.integration.slack.enabled=true")
                    .withBean(ConversationLedgerNotifications.class, () -> notifications)
                    .withBean(
                            SlackConversationProjector.class,
                            () -> new SlackConversationProjector(messages, replies, executor))
                    .withBean(AsyncAnnotationBeanPostProcessor.class, () -> {
                        var advice = new AsyncAnnotationBeanPostProcessor();
                        advice.setProxyTargetClass(true);
                        advice.setExecutor(workers);
                        return advice;
                    })
                    .run(context -> {
                        context.publishEvent(new ApplicationReadyEvent(
                                new SpringApplication(),
                                new String[0],
                                context.getSourceApplicationContext(),
                                Duration.ZERO));
                        var listener = ArgumentCaptor.forClass(ConversationLedgerNotifications.Listener.class);
                        verify(notifications).subscribe(listener.capture());
                        var returned = CompletableFuture.runAsync(() -> listener.getValue()
                                .turnChanged(new ConversationLedgerEvents.TurnChanged(
                                        1L,
                                        UUID.randomUUID(),
                                        turnId,
                                        ConversationTurn.Status.DONE,
                                        ConversationTurn.Surface.SLACK,
                                        1)));
                        try {
                            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                            // The ledger callback returns before provider work is allowed to complete.
                            returned.get(5, TimeUnit.SECONDS);
                            assertThat(release.getCount()).isEqualTo(1);
                        } finally {
                            release.countDown();
                            returned.get(5, TimeUnit.SECONDS);
                        }
                    });
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "hephaestus.background-work.enabled=false",
                "hephaestus.integration.slack.enabled=false",
                "hephaestus.runtime.server.enabled=false"
            })
    void shouldSubscribeNoProjectorWithoutBackgroundWorkWithoutSlackOrWithoutTheServerRole(String setting) {
        runner.withPropertyValues(setting)
                .run(context -> assertThat(context).doesNotHaveBean("slackConversationProjectorSubscription"));
    }
}
