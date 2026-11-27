package de.tum.cit.aet.hephaestus.practices.advice;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdviceTextTest extends BaseUnitTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Name the risk of the migration in the description, so a reviewer can check the rollback.",
                "The two changes in this pull request share one test; split it so each failure points at one change.",
                "Add a test for the empty list.",
                "Add two tests before review.",
                "Split it into two commits so each review reads one change.",
                "One pull request is enough for this change."
            })
    void shouldAdmitAdviceAboutWhatOneReviewObserved(String text) {
        assertThat(AdviceText.admits(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "You forgot the tests again.",
                "Every time you change the schema, the rollback is missing.",
                "Each time the description is empty.",
                "You repeatedly skip the tests.",
                "This is a recurring gap.",
                "A pattern in your work: no tests.",
                "Your patterns show missing tests.",
                "You often skip the description.",
                "You usually merge without a review.",
                "You consistently leave the description empty.",
                "You keep skipping the tests.",
                "The description keeps missing the risk.",
                "REPEATEDLY missing tests."
            })
    void shouldRefuseAClaimOfAPattern(String text) {
        assertThat(AdviceText.admits(text)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "In 3 pull requests the tests were missing.",
                "Three merge requests had no description.",
                "Several of your PRs lack tests.",
                "Across multiple reviews the rollback was missing.",
                "Two issues had no acceptance criteria.",
                "You skipped tests in 2 recent pull requests.",
                "Eleven pull requests had no tests.",
                "Two in-flight pull requests had no tests.",
                "Four of your merge requests skipped the checklist."
            })
    void shouldRefuseACountOfWork(String text) {
        assertThat(AdviceText.admits(text)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "Add a test\u200b for the empty list."})
    void shouldRefuseEmptyOrHiddenText(String text) {
        assertThat(AdviceText.admits(text)).isFalse();
    }

    @Test
    void shouldRefuseTextOverTheLimit() {
        assertThat(AdviceText.admits("a".repeat(AdviceText.MAX_LENGTH))).isTrue();
        assertThat(AdviceText.admits("a".repeat(AdviceText.MAX_LENGTH + 1))).isFalse();
    }
}
