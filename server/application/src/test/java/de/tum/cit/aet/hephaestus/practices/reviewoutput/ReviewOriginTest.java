package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewOriginTest extends BaseUnitTest {
    @Test
    void scheduleAndBackfillAreOriginsRatherThanSyntheticEvents() {
        assertThat(ReviewOrigin.values())
                .containsExactly(
                        ReviewOrigin.EVENT, ReviewOrigin.REQUEST, ReviewOrigin.SCHEDULE, ReviewOrigin.BACKFILL);
        var request = new ReviewRequest(
                1,
                3,
                ReviewKind.ITEM,
                null,
                ReviewOrigin.SCHEDULE,
                null,
                null,
                UUID.randomUUID(),
                null,
                null,
                null,
                List.of(),
                true,
                Set.of());
        assertThat(request.eventId()).isNull();
        assertThat(request.origin()).isEqualTo(ReviewOrigin.SCHEDULE);
    }
}
