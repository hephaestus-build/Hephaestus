package de.tum.cit.aet.hephaestus.practices.usage;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.settings.spi.UsageFigure;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The disclosure rules of ADR 0153: a figure below a floor, too small or dominated never leaves the release. */
class UsageDisclosureTest extends BaseUnitTest {

    private static UsageCount count(UsageFigure figure, long workspaces, long total, long largest) {
        return new UsageCount("2026-04", figure.name(), workspaces, total, largest);
    }

    private static UsageCount developers(long distinct) {
        return new UsageCount("2026-04", UsageCount.DISTINCT_PEOPLE, 0, distinct, 0);
    }

    private static List<UsageCount> busy() {
        return List.of(
                count(UsageFigure.PRACTICE_REVIEWS, 40, 96_480, 4_000),
                count(UsageFigure.REVIEWED_WORK, 38, 18_437, 900),
                count(UsageFigure.REVIEWED_PEOPLE, 40, 1_260, 80),
                count(UsageFigure.FEEDBACK_DELIVERED, 35, 31_999, 2_000),
                developers(1_254));
    }

    @Test
    void shouldPublishRoundedFiguresWhenBothFloorsHold() {
        var figures = UsageDisclosure.disclose(busy());

        assertThat(figures.status()).isEqualTo(PublicUsageStatus.PUBLISHED);
        assertThat(figures.figures())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        UsageFigure.WORKSPACES, 40L,
                        UsageFigure.REVIEWED_PEOPLE, 1_200L,
                        UsageFigure.REVIEWED_WORK, 18_400L,
                        UsageFigure.PRACTICE_REVIEWS, 96_400L,
                        UsageFigure.FEEDBACK_DELIVERED, 31_900L));
    }

    @Test
    void shouldPublishNothingBelowAFloor() {
        var fourWorkspaces = UsageDisclosure.disclose(List.of(
                count(UsageFigure.PRACTICE_REVIEWS, 4, 5_000, 1_000),
                count(UsageFigure.REVIEWED_PEOPLE, 4, 400, 100),
                developers(400)));
        var fourDevelopers = UsageDisclosure.disclose(List.of(
                count(UsageFigure.PRACTICE_REVIEWS, 9, 5_000, 1_000),
                count(UsageFigure.REVIEWED_PEOPLE, 4, 4, 1),
                developers(4)));

        assertThat(fourWorkspaces.status()).isEqualTo(PublicUsageStatus.NOT_ENOUGH_ACTIVITY);
        assertThat(fourWorkspaces.figures()).isEmpty();
        assertThat(fourDevelopers.status()).isEqualTo(PublicUsageStatus.NOT_ENOUGH_ACTIVITY);
        assertThat(fourDevelopers.figures()).isEmpty();
        assertThat(UsageDisclosure.disclose(List.of()).status()).isEqualTo(PublicUsageStatus.NOT_ENOUGH_ACTIVITY);
    }

    @Test
    void shouldPublishAPeriodThatMeetsBothFloorsExactly() {
        var figures = UsageDisclosure.disclose(List.of(
                count(UsageFigure.PRACTICE_REVIEWS, 5, 500, 100),
                count(UsageFigure.REVIEWED_PEOPLE, 5, 5, 1),
                developers(5)));

        assertThat(figures.status()).isEqualTo(PublicUsageStatus.PUBLISHED);
        assertThat(figures.figure(UsageFigure.PRACTICE_REVIEWS)).isEqualTo(500L);
    }

    @Test
    void shouldHideAFigureThatOneWorkspaceDominates() {
        var figures = UsageDisclosure.disclose(List.of(
                count(UsageFigure.PRACTICE_REVIEWS, 10, 3_000, 1_001),
                count(UsageFigure.REVIEWED_WORK, 10, 3_000, 1_000),
                count(UsageFigure.REVIEWED_PEOPLE, 10, 60, 21),
                count(UsageFigure.FEEDBACK_DELIVERED, 10, 900, 100),
                developers(60)));

        assertThat(figures.figure(UsageFigure.PRACTICE_REVIEWS))
                .as("one workspace adds more than a third")
                .isNull();
        assertThat(figures.figure(UsageFigure.REVIEWED_WORK))
                .as("exactly a third is allowed")
                .isEqualTo(3_000L);
        assertThat(figures.figure(UsageFigure.REVIEWED_PEOPLE)).isNull();
        assertThat(figures.figure(UsageFigure.FEEDBACK_DELIVERED)).isEqualTo(900L);
        assertThat(figures.figure(UsageFigure.WORKSPACES)).isEqualTo(10L);
    }

    @Test
    void shouldHideAFigureThatRoundsToZero() {
        var figures = UsageDisclosure.disclose(List.of(
                count(UsageFigure.PRACTICE_REVIEWS, 6, 60, 10),
                count(UsageFigure.REVIEWED_PEOPLE, 6, 9, 2),
                count(UsageFigure.FEEDBACK_DELIVERED, 3, 3, 1),
                developers(9)));

        assertThat(figures.status()).isEqualTo(PublicUsageStatus.PUBLISHED);
        assertThat(figures.figure(UsageFigure.WORKSPACES))
                .as("6 rounds down to 0")
                .isNull();
        assertThat(figures.figure(UsageFigure.REVIEWED_PEOPLE)).isNull();
        assertThat(figures.figure(UsageFigure.REVIEWED_WORK)).isNull();
        assertThat(figures.figure(UsageFigure.FEEDBACK_DELIVERED)).isNull();
        assertThat(figures.figure(UsageFigure.PRACTICE_REVIEWS)).isEqualTo(60L);
    }

    @Test
    void shouldRoundDownToTensBelowAThousandAndToHundredsFromAThousand() {
        assertThat(UsageDisclosure.round(9)).isZero();
        assertThat(UsageDisclosure.round(10)).isEqualTo(10);
        assertThat(UsageDisclosure.round(999)).isEqualTo(990);
        assertThat(UsageDisclosure.round(1_000)).isEqualTo(1_000);
        assertThat(UsageDisclosure.round(1_099)).isEqualTo(1_000);
        assertThat(UsageDisclosure.round(18_437)).isEqualTo(18_400);
    }

    @Test
    void shouldReadAStoredReleaseBackUnchanged() {
        var figures = UsageDisclosure.disclose(busy());

        assertThat(UsageFigures.read(UsageFigures.VERSION, figures.toJson())).isEqualTo(figures);
        var empty = UsageFigures.notEnoughActivity();
        assertThat(UsageFigures.read(UsageFigures.VERSION, empty.toJson())).isEqualTo(empty);
    }

    @Test
    void shouldPublishNothingFromAStoredRowItCannotTrust() {
        String valid = UsageDisclosure.disclose(busy()).toJson();

        assertThat(UsageFigures.read((short) 2, valid).status()).isEqualTo(PublicUsageStatus.NOT_PUBLISHED);
        for (String stored : List.of(
                "not json",
                "[]",
                "{\"status\":\"PUBLISHED\",\"figures\":{\"REVIEWED_PEOPLE\":4}}",
                "{\"status\":\"PUBLISHED\",\"figures\":{\"REVIEWED_PEOPLE\":1234}}",
                "{\"status\":\"PUBLISHED\",\"figures\":{\"REVIEWED_PEOPLE\":\"1200\"}}",
                "{\"status\":\"PUBLISHED\",\"figures\":{\"PEOPLE_WHO_ASKED\":100}}",
                "{\"status\":\"LIVE\",\"figures\":{}}",
                "{\"status\":\"NOT_ENOUGH_ACTIVITY\",\"figures\":{\"WORKSPACES\":10}}")) {
            var read = UsageFigures.read(UsageFigures.VERSION, stored);
            assertThat(read.status()).as(stored).isEqualTo(PublicUsageStatus.NOT_PUBLISHED);
            assertThat(read.figures()).as(stored).isEmpty();
        }
    }
}
