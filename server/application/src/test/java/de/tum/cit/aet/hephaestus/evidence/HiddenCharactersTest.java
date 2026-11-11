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
    void shouldLeaveBytesThatAreNotUtf8TextAsTheyAre() {
        byte[] binary = {(byte) 0xFF, (byte) 0xFE, 0x00, 0x41};

        assertThat(HiddenCharacters.markUtf8(binary)).isSameAs(binary);
        assertThat(new String(
                        HiddenCharacters.markUtf8(("a" + ch(0x2066) + "b").getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8))
                .isEqualTo("a⟦U+2066⟧b");
    }
}
