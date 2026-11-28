package de.tum.cit.aet.hephaestus.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NumberWordsTest extends BaseUnitTest {

    @Test
    void shouldReadLowercaseWordsAndBreakAtEveryCharacterThatIsNotALetterOrADigit() {
        assertThat(NumberWords.words("13 of 17 **Commit** subjects—v2.4, #5\nok"))
                .containsExactly("13", "of", "17", "commit", "subjects", "v2", "4", "5", "ok");
    }

    @ParameterizedTest
    @ValueSource(strings = {"13", "2nd", "3prs", "zero", "one", "fourteen", "ninety", "hundred", "dozens", "millions"})
    void shouldReadACountFromANumeralOrAWordForAnExactCount(String word) {
        assertThat(NumberWords.isCount(word)).isTrue();
        assertThat(NumberWords.isNumber(word)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"first", "second", "both", "several", "once", "twice", "thirtieth", "handful"})
    void shouldReadOtherNumbersThatStateNoExactCount(String word) {
        assertThat(NumberWords.isCount(word)).isFalse();
        assertThat(NumberWords.isNumber(word)).isTrue();
    }

    @Test
    void shouldCompareAWordWithOneValueAsItsDigits() {
        assertThat(NumberWords.value("five")).isEqualTo("5");
        assertThat(NumberWords.value("ninety")).isEqualTo("90");
        assertThat(NumberWords.value("5")).isEqualTo("5");
        assertThat(NumberWords.value("dozen")).isEqualTo("dozen");
    }

    @Test
    void shouldReadATensWordWithAFollowingUnitAsOneCount() {
        assertThat(NumberWords.counts(NumberWords.words("Twenty-one handlers, twenty one tests and twenty 3 retries")))
                .containsExactly("21", "21", "twenty", "3");
        assertThat(NumberWords.counts(NumberWords.words("one of 17 and thirty")))
                .containsExactly("one", "17", "thirty");
    }

    @ParameterizedTest
    @ValueSource(strings = {"v2", "commit", "none", "all", "only", "someone"})
    void shouldReadNoNumberFromOtherWords(String word) {
        assertThat(NumberWords.isNumber(word)).isFalse();
    }
}
