package de.tum.cit.aet.hephaestus.notification.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.notification.ProductFeedbackDigestRequested;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductFeedbackDigestJobTest extends BaseUnitTest {
    @Test
    void shouldBoundCatchUpAndNeverIncludeReportsBeforeOptIn() {
        var repository = mock(NotificationSubscriptionRepository.class);
        var first = subscription(1, "2026-09-12T12:30:00Z");
        var old = subscription(2, "2026-08-01T00:00:00Z");
        old.setLastDigestDate(LocalDate.parse("2026-08-31"));
        var today = subscription(3, "2026-09-13T07:00:00Z");
        when(repository.pendingDigests(any(), any())).thenReturn(List.of(first, old, today));
        List<Object> events = new ArrayList<>();
        var clock = Clock.fixed(Instant.parse("2026-09-13T08:00:00Z"), ZoneOffset.UTC);
        new ProductFeedbackDigestJob(repository, events::add, clock).queue();
        assertThat(events)
                .containsExactly(
                        new ProductFeedbackDigestRequested(
                                1, Instant.parse("2026-09-12T12:30:00Z"), Instant.parse("2026-09-13T00:00:00Z")),
                        new ProductFeedbackDigestRequested(
                                2, Instant.parse("2026-09-06T00:00:00Z"), Instant.parse("2026-09-13T00:00:00Z")));
        assertThat(first.getLastDigestDate()).isEqualTo(LocalDate.parse("2026-09-12"));
        assertThat(today.getLastDigestDate()).isEqualTo(LocalDate.parse("2026-09-12"));
    }

    @Test
    void shouldWaitUntilEightUtc() {
        var repository = mock(NotificationSubscriptionRepository.class);
        List<Object> events = new ArrayList<>();
        new ProductFeedbackDigestJob(
                        repository, events::add, Clock.fixed(Instant.parse("2026-09-13T07:59:59Z"), ZoneOffset.UTC))
                .queue();
        assertThat(events).isEmpty();
    }

    private NotificationSubscription subscription(long accountId, String since) {
        var row = new NotificationSubscription(accountId, NotificationSubscriptionKind.PRODUCT_FEEDBACK);
        row.setEnabled(true, Instant.parse(since));
        row.setFrequency(NotificationEmailFrequency.DAILY, Instant.parse(since));
        return row;
    }
}
