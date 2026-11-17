package de.tum.cit.aet.hephaestus.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class HiddenCharactersTest {

    private static String ch(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    @Test
    void shouldMarkTagBidiAndZeroWidthCharactersVisibly() {
        String hidden = "Fix it" + ch(0xE0049) + ch(0x202E) + "evil" + ch(0x200B) + ".";

        assertThat(HiddenCharacters.mark(hidden)).isEqualTo("Fix it⟦U+E0049⟧⟦U+202E⟧evil⟦U+200B⟧.");
    }

    @Test
    void shouldKeepTextAPersonCanSeeAndTheJoinerThatComposesEmoji() {
        String text = "Line one\n\tLine two " + ch(0x1F469) + ch(0x200D) + ch(0x1F4BB) + " é";

        assertThat(HiddenCharacters.mark(text)).isSameAs(text);
    }

    @Test
    void shouldKeepAJoinerBetweenTwoLettersThatAWordNeeds() {
        String persian = "می" + ch(0x200C) + "خواهم";
        String arabicNumber = ch(0x0600) + "١٢٣";

        assertThat(HiddenCharacters.mark(persian)).isSameAs(persian);
        assertThat(HiddenCharacters.mark(arabicNumber)).isSameAs(arabicNumber);
    }

    @Test
    void shouldMarkJoinersInARunOrAtAnEdgeAndOtherDefaultIgnorableCharacters() {
        String text = "a" + ch(0x200C) + ch(0x200D) + "b " + ch(0x200D) + "c" + ch(0x17B4) + ch(0xE0080);

        assertThat(HiddenCharacters.mark(text)).isEqualTo("a⟦U+200C⟧⟦U+200D⟧b ⟦U+200D⟧c⟦U+17B4⟧⟦U+E0080⟧");
        assertThat(HiddenCharacters.hides(text)).isTrue();
    }

    @Test
    void shouldMarkAJoinerNextToAsciiSoARecordAndItsExtractedStringAgree() {
        String record = "{\"body\":\"خواهم" + ch(0x200C) + "\"}";
        String extracted = "خواهم" + ch(0x200C);

        assertThat(HiddenCharacters.mark(record)).isEqualTo("{\"body\":\"خواهم⟦U+200C⟧\"}");
        assertThat(HiddenCharacters.mark(extracted)).isEqualTo("خواهم⟦U+200C⟧");
        assertThat(HiddenCharacters.mark("a" + ch(0x200C) + "b")).isEqualTo("a⟦U+200C⟧b");
    }

    @Test
    void shouldLeaveBytesThatAreNotUtf8TextAsTheyAre() {
        byte[] binary = {(byte) 0xFF, (byte) 0xFE, 0x00, 0x41};

        assertThat(HiddenCharacters.markUtf8(binary)).isSameAs(binary);
        assertThat(new String(
                        HiddenCharacters.markUtf8(("a" + ch(0x2066) + "b").getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8))
                .isEqualTo("a⟦U+2066⟧b");
    }
}
