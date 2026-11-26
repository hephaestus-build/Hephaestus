package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.authority.spi.AuthorityGenerations;
import de.tum.cit.aet.hephaestus.notification.WeeklyDigestEmailListener;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.UnchangedAuthority;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import tools.jackson.databind.json.JsonMapper;

/** A workspace email's stamp names its recipient when the email carries words that Hephaestus wrote for them. */
class EmailsStampTest extends BaseUnitTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @SuppressWarnings("unchecked")
    void shouldStampTheRecipientOnlyOfAnEmailThatDependsOnThem(boolean dependsOnRecipient) {
        AuthorityGenerations generations = spy(new UnchangedAuthority());
        EmailNotice<Map<String, String>> notice = mock(EmailNotice.class);
        when(notice.kind()).thenReturn(EmailKind.WEEKLY_DIGEST);
        when(notice.dependsOnRecipient()).thenReturn(dependsOnRecipient);
        when(notice.address(any())).thenReturn(new EmailNotice.Address("digest:7", 7, 1L, Instant.MAX));
        when(notice.prepare(any())).thenReturn(new EmailNotice.Prepared.Send(mock(EmailMessage.class)));
        var emails = new Emails(
                Optional.empty(),
                mock(EmailProperties.class),
                mock(AgentActions.class),
                new EmailDeliveryMetrics(new SimpleMeterRegistry()),
                JsonMapper.builder().build(),
                generations);

        emails.enqueue(notice, Map.of("entry", "e"));

        verify(generations).authorize(eq(1L), eq(dependsOnRecipient ? List.of(7L) : List.<Long>of()), any());
    }

    @Test
    void shouldMakeTheWeeklyDigestEmailDependOnItsReader() {
        var digest = mock(WeeklyDigestEmailListener.class, withSettings().defaultAnswer(Answers.CALLS_REAL_METHODS));

        assertThat(digest.dependsOnRecipient()).isTrue();
    }
}
