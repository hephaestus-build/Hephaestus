package de.tum.cit.aet.hephaestus.core.release;

import static de.tum.cit.aet.hephaestus.core.release.ReleaseFixtures.DEPLOYMENT;
import static de.tum.cit.aet.hephaestus.core.release.ReleaseFixtures.ENVIRONMENT;
import static de.tum.cit.aet.hephaestus.core.release.ReleaseFixtures.activation;
import static de.tum.cit.aet.hephaestus.core.release.ReleaseFixtures.running;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("unit")
class ReleaseHistoryTest {
    private static final Instant NOW = Instant.parse("2026-11-25T18:00:00Z");

    private final ReleaseActivationRepository repository = mock(ReleaseActivationRepository.class);
    private final TransactionTemplate transaction = new TransactionTemplate(mock(PlatformTransactionManager.class));

    private ReleaseHistory history(@Nullable RolloutReader reader) {
        var beans = new StaticListableBeanFactory(reader == null ? Map.of() : Map.of("rolloutReader", reader));
        return new ReleaseHistory(
                running("1.3.0"),
                repository,
                transaction,
                new MockEnvironment(),
                beans.getBeanProvider(RolloutReader.class));
    }

    private static List<String> eventNames(Runnable during) {
        Logger logger = (Logger) LoggerFactory.getLogger(ReleaseHistory.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            during.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .flatMap(event -> event.getKeyValuePairs().stream())
                .filter(pair -> pair.key.equals("event.name"))
                .map(pair -> String.valueOf(pair.value))
                .toList();
    }

    @Test
    void shouldRecordTheRolloutEveryMinuteAndClaimOnlyItsOwnRevisionAsRunning() {
        var history = history(() -> new Rollout(DEPLOYMENT, 2));

        history.onApplicationReady();
        history.report();

        // The insert is idempotent: a rollout undo that starts no server changes the revision that the next read sees.
        verify(repository, times(2)).recordRollout(any(), eq(new Rollout(DEPLOYMENT, 2)));
        assertThat(history.runningSince(List.of(activation("1.3.0", 2, NOW), activation("1.2.3", 1, NOW))))
                .isEqualTo(NOW);
        // A newer rollout is the newest entry: this old server does not claim it.
        assertThat(history.runningSince(List.of(activation("1.4.0", 3, NOW), activation("1.3.0", 2, NOW))))
                .isNull();
    }

    @Test
    void shouldReportNothingBeforeTheServerIsReady() {
        RolloutReader reader = mock(RolloutReader.class);
        var history = history(reader);

        // The timer starts with the context, before the server is ready.
        history.report();

        verifyNoInteractions(reader, repository);
    }

    @Test
    void shouldRecordNothingAndTryAgainWhenTheRolloutCannotBeRead() {
        RolloutReader reader = mock(RolloutReader.class);
        when(reader.read())
                .thenThrow(new IllegalStateException("ReplicaSet has no revision"))
                .thenReturn(new Rollout(DEPLOYMENT, 2));
        var history = history(reader);

        var events = eventNames(history::onApplicationReady);

        assertThat(events).containsExactly("release.history.failed");
        verify(repository, never()).recordRollout(any(), any());
        assertThat(history.runningSince(List.of(activation("1.3.0", 2, NOW)))).isNull();

        history.report();

        verify(repository).recordRollout(any(), eq(new Rollout(DEPLOYMENT, 2)));
    }

    @Test
    void shouldClaimNoRunningSinceOnceAReadFailsAfterASuccessfulOne() {
        RolloutReader reader = mock(RolloutReader.class);
        when(reader.read())
                .thenReturn(new Rollout(DEPLOYMENT, 7))
                .thenThrow(new IllegalStateException("the API refused the read"));
        var history = history(reader);
        var recent = List.of(activation("1.3.0", 7, NOW));

        history.onApplicationReady();
        assertThat(history.runningSince(recent)).isEqualTo(NOW);

        // An undo can have revised the ReplicaSet since: the date of revision 7 is no longer known to be current.
        history.report();
        assertThat(history.runningSince(recent)).isNull();
    }

    @Test
    void shouldRecordAServerStartOutsideKubernetesOnce() {
        when(repository.recordStart(any())).thenReturn(7L);
        var history = history(null);

        history.onApplicationReady();
        history.report();

        verify(repository, times(1)).recordStart(any());
        var start = new ReleaseActivation(
                ENVIRONMENT,
                null,
                null,
                "1.3.0",
                ReleaseChannel.RELEASE,
                ReleaseFixtures.COMMIT,
                ReleaseFixtures.IMAGE,
                NOW);
        ReflectionTestUtils.setField(start, "id", 7L);
        assertThat(history.runningSince(List.of(start))).isEqualTo(NOW);
    }
}
