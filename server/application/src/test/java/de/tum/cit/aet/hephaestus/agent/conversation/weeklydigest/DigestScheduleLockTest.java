package de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestFacts;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The scheduler calls the digest pass through ShedLock's method proxy, which the integration tests do not: they build
 * the schedule directly, and the test profile runs no agent beans. This context has the proxy in the mode that the
 * application uses (ShedLockConfig), so a locked entry that the proxy refuses fails here.
 */
class DigestScheduleLockTest extends BaseUnitTest {

    private final WeeklyDigestSubscriptions subscriptions = mock(WeeklyDigestSubscriptions.class);

    @Test
    void theScheduledPassRunsThroughTheLockProxy() {
        when(subscriptions.subscribedAccountIds()).thenReturn(List.of());

        new ApplicationContextRunner()
                .withBean(WeeklyDigestSubscriptions.class, () -> subscriptions)
                .withUserConfiguration(Locked.class)
                .run(context -> {
                    DigestSchedule schedule = context.getBean(DigestSchedule.class);
                    assertThat(AopUtils.isAopProxy(schedule)).isTrue();

                    assertThatCode(schedule::enqueueDueDigests).doesNotThrowAnyException();
                    verify(subscriptions).subscribedAccountIds();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
    static class Locked {

        @Bean
        LockProvider lockProvider() {
            return configuration -> Optional.of(() -> {});
        }

        @Bean
        DigestSchedule digestSchedule(WeeklyDigestSubscriptions subscriptions) {
            return new DigestSchedule(
                    subscriptions,
                    mock(WeeklyDigestAvailability.class),
                    mock(AccountDirectory.class),
                    mock(WeeklyDigestFacts.class),
                    mock(ConversationService.class),
                    mock(ConversationEntryService.class),
                    mock(ConversationTurnService.class),
                    mock(PersonDataWriteFence.class),
                    mock(ObjectMapper.class),
                    mock(TransactionTemplate.class),
                    Clock.systemUTC());
        }
    }
}
