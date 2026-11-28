package de.tum.cit.aet.hephaestus.practices.spi;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ReviewRequestTest {

    @Test
    void shouldNameATeamProjectForATeamAudienceAndForNoOther() {
        assertThatCode(() -> request(ReviewAudience.TEAM, UUID.randomUUID())).doesNotThrowAnyException();
        assertThatCode(() -> request(ReviewAudience.OPERATIONAL, null)).doesNotThrowAnyException();
        assertThatCode(() -> request(null, null)).doesNotThrowAnyException();

        assertThatThrownBy(() -> request(ReviewAudience.TEAM, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(ReviewAudience.PERSONAL, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(null, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }

    private static ReviewRequest request(@Nullable ReviewAudience audience, @Nullable UUID team) {
        return ReviewRequest.builder()
                .workspaceId(1)
                .itemId(2)
                .kind(ReviewKind.SCAN)
                .origin(ReviewOrigin.REQUEST)
                .practiceIds(List.of())
                .audience(audience)
                .audienceTeamId(team)
                .build();
    }
}
