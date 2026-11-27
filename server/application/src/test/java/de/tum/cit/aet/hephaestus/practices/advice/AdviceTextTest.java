package de.tum.cit.aet.hephaestus.practices.advice;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AdviceTextTest extends BaseUnitTest {

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

    /**
     * Every string from the review rounds of #5420, each marked refuse or admit. When in doubt the rule refuses, so some
     * rows refuse acceptable advice on purpose.
     */
    @ParameterizedTest(name = "{1}: {0}")
    @CsvSource(delimiter = '|', textBlock = """
                    In 3 pull requests the tests were missing. | refuse
                    Three merge requests had no description. | refuse
                    Several of your PRs lack tests. | refuse
                    Across multiple reviews the rollback was missing. | refuse
                    Two issues had no acceptance criteria. | refuse
                    You skipped tests in 2 recent pull requests. | refuse
                    Eleven pull requests had no tests. | refuse
                    Two in-flight pull requests had no tests. | refuse
                    Four of your merge requests skipped the checklist. | refuse
                    Only 1 pull request included rollback instructions. | refuse
                    One pull request is enough for this change. | refuse
                    Two recent, unrelated pull requests lacked rollback instructions. | refuse
                    Three high\u2011risk merge requests lacked rollback instructions. | refuse
                    You left acceptance criteria out of 2+ issues. | refuse
                    Two **reviews** missed the rollback risk. | refuse
                    Both pull requests lacked rollback instructions. | refuse
                    Twenty-one pull requests had no tests. | refuse
                    A few merge requests skipped the checklist. | refuse
                    Three of your most recent pull requests had no tests. | refuse
                    Two of your recently merged PRs had no tests. | refuse
                    Three of this week's pull requests had no tests. | refuse
                    Two after-hours pull requests had no tests. | refuse
                    Two end-to-end test pull requests had no description. | refuse
                    Zero pull requests had rollback notes. | refuse
                    A handful of your pull requests had no tests. | refuse
                    In your third pull request the tests were missing. | refuse
                    Pull requests without tests: 3. | refuse
                    Add tests to one more pull request. | refuse
                    Add one test per review comment. | refuse
                    The two changes in this pull request share one test; split it so each failure points at one change. | admit
                    Split it into two commits so each review reads one change. | admit
                    Add two tests before review. | admit
                    Add two tests before reviews start. | admit
                    This review found two missing tests. | admit
                    Name the risk of the migration, so a reviewer can check it. | admit
                    Add a test for the empty list. | admit
                    Name the two risks of this change in the description. | admit
                    Write the rollback steps in the description before review. | admit
                    """)
    void shouldRefuseEveryCountOfWorkAndAdmitAdviceAboutOneReview(String text, String expected) {
        assertThat(AdviceText.admits(text) ? "admit" : "refuse").isEqualTo(expected);
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
