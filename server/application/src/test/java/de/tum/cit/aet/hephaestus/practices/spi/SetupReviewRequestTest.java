package de.tum.cit.aet.hephaestus.practices.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SetupReviewRequestTest {

    private static final ReviewedItem ITEM = new ReviewedItem.RepositoryCommit(1, null, null);

    @Test
    void shouldMatchTheRfcExampleWhenItNamesAVersion5Uuid() {
        // RFC 9562 Appendix A.4: the DNS namespace and the name "www.example.com".
        UUID dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

        assertThat(SetupReviewRequest.nameBased(dns, "www.example.com"))
                .isEqualTo(UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"));
    }

    @Test
    void shouldGiveOneKeyToEachDueTimeOfASetupWhenTwoWritersScheduleIt() {
        UUID setup = UUID.randomUUID();
        Instant due = Instant.parse("2026-12-07T06:00:00Z");

        UUID key = SetupReviewRequest.scheduleKey(setup, due);

        assertThat(SetupReviewRequest.scheduleKey(setup, Instant.parse("2026-12-07T06:00:00Z")))
                .isEqualTo(key);
        assertThat(key.version()).isEqualTo(5);
        assertThat(key.variant()).isEqualTo(2);
        assertThat(SetupReviewRequest.scheduleKey(setup, due.plusSeconds(7 * 24 * 3600)))
                .isNotEqualTo(key);
        assertThat(SetupReviewRequest.scheduleKey(UUID.randomUUID(), due)).isNotEqualTo(key);
    }

    @Test
    void shouldRefuseTheRequestWhenItsOriginAndRequesterDisagree() {
        assertThatCode(() -> request(ReviewOrigin.REQUEST, 7L)).doesNotThrowAnyException();
        assertThatCode(() -> request(ReviewOrigin.SCHEDULE, null)).doesNotThrowAnyException();

        assertThatThrownBy(() -> request(ReviewOrigin.REQUEST, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(ReviewOrigin.SCHEDULE, 7L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(ReviewOrigin.EVENT, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static SetupReviewRequest request(ReviewOrigin origin, @Nullable Long requestedBy) {
        return new SetupReviewRequest(1, UUID.randomUUID(), ITEM, origin, UUID.randomUUID(), requestedBy);
    }
}
