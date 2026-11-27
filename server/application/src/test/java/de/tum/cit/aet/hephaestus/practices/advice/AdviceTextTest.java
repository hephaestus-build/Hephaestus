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
                    Three of your recent pull request descriptions were empty. | refuse
                    Five of your last review comments went unanswered. | refuse
                    Two changes to config.yml came in pull requests without a description. | refuse
                    Pull requests since v2.4 without tests: 3. | refuse
                    You merged a pull request without review three times. | refuse
                    Five of the last six times you opened a pull request, it had no tests. | refuse
                    Three times now, you opened a PR without a description. | refuse
                    Look at your recent pull requests. Three of them had no tests. | refuse
                    Twice you opened a pull request without tests. | refuse
                    The two changes in this pull request share one test; split it so each failure points at one change. | refuse
                    Split it into two commits so each review reads one change. | refuse
                    This review found two missing tests. | refuse
                    Add two tests before the review. | refuse
                    You had never added tests before. Pull requests without tests: 3. | refuse
                    Nothing changed after. Merge requests with no description: 4. | refuse
                    You opened 3PRs without tests. | refuse
                    You opened 3pull-requests without tests. | refuse
                    You opened 3 \u00e9before PRs. | refuse
                    You opened 3 e\u0301before PRs. | refuse
                    You opened 3 \u0130before PRs. | refuse
                    More than once you merged a pull request without review. | refuse
                    Your thirtieth pull request had no tests. | refuse
                    Add two tests before review. | admit
                    Add two tests before reviews start. | admit
                    Name the risk of the migration, so a reviewer can check it. | admit
                    Add a test for the empty list. | admit
                    Name the two risks of this change in the description. | admit
                    Write the rollback steps in the description before review. | admit
                    Add a test for the empty branch, and name the rollback in the pull request description. | admit
                    """)
    void shouldRefuseEveryCountOfWorkAndAdmitAdviceAboutOneReview(String text, String expected) {
        assertThat(AdviceText.admits(text) ? "admit" : "refuse").isEqualTo(expected);
    }

    /** A line break ends no count, and a "before" or "after" before it exempts no work noun after it. */
    @ParameterizedTest
    @ValueSource(
            strings = {"Three of your\npull requests had no tests.", "- No tests, same as before\n- PRs affected: 3"})
    void shouldRefuseACountThatALineBreakSeparatesFromItsWork(String text) {
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
