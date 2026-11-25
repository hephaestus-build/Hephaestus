package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.authority.spi.AuthorityGenerations;
import de.tum.cit.aet.hephaestus.notification.metrics.NotificationMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EmailsTest extends BaseUnitTest {

    @Test
    void shouldExpireTheEmailOfAPurgedWorkspaceWithoutAskingItsNotice() {
        var actions = mock(AgentActions.class);
        var meters = new SimpleMeterRegistry();
        var asked = new AtomicBoolean();
        // The workspace was purged after its durable event was published.
        var gone = new AuthorityGenerations() {
            @Override
            public OptionalLong workspace(long workspaceId) {
                return OptionalLong.empty();
            }

            @Override
            public OptionalLong workspaceHeld(long workspaceId) {
                return OptionalLong.empty();
            }
        };
        var emails = new Emails(
                Optional.empty(),
                new EmailProperties(null, "Hephaestus", null),
                actions,
                new EmailDeliveryMetrics(meters),
                JsonMapper.builder().build(),
                gone);
        EmailNotice<String> notice = new EmailNotice<>() {
            @Override
            public EmailKind kind() {
                return EmailKind.WORKSPACE_ALERT;
            }

            @Override
            public Class<String> eventType() {
                return String.class;
            }

            @Override
            public Address address(String event) {
                return new Address("alert:" + event, 5L, 7L, Instant.now().plusSeconds(60));
            }

            @Override
            public Prepared prepare(String event) {
                asked.set(true);
                return Prepared.withhold(EmailDeliveryResult.Outcome.SENT);
            }
        };

        emails.enqueue(notice, "budget");

        assertThat(asked).isFalse();
        assertThat(meters.get(NotificationMetrics.EMAIL_DELIVERY)
                        .tag("kind", EmailKind.WORKSPACE_ALERT.tag())
                        .tag("outcome", "expired")
                        .counter()
                        .count())
                .isEqualTo(1);
        verify(actions, never()).enqueue(any());
    }
}
