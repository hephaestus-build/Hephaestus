package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Pins the persisted AAD wire format and field separation that round trips cannot detect. */
class EncryptionContextTest extends BaseUnitTest {

    private static byte[] aad(long ws, IntegrationKind kind, @Nullable String instanceKey, String column) {
        return new EncryptionContext(ws, kind, instanceKey, column).toAad();
    }

    @Test
    void shouldUseVersionedLengthPrefixedFraming() {
        byte[] actual = aad(1L, IntegrationKind.GITHUB, "a", "bc");

        assertThat(HexFormat.of().formatHex(actual))
                .isEqualTo("686570686165737475732d63726564656e7469616c2d62756e646c651f02"
                        + "000131000647495448554200016100026263");
    }

    @Test
    void aadDiffersForEachDistinguishingField() {
        List<byte[]> aads = List.of(
                aad(1L, IntegrationKind.GITHUB, "inst", "col"),
                aad(2L, IntegrationKind.GITHUB, "inst", "col"), // workspaceId differs
                aad(1L, IntegrationKind.GITLAB, "inst", "col"), // kind differs
                aad(1L, IntegrationKind.GITHUB, "other", "col"), // instanceKey differs
                aad(1L, IntegrationKind.GITHUB, "inst", "other") // columnFqn differs
                );
        Set<String> distinct = new HashSet<>();
        for (byte[] a : aads) {
            distinct.add(Arrays.toString(a));
        }
        assertThat(distinct)
                .as("every distinguishing field must change the AAD")
                .hasSize(aads.size());
    }

    @Test
    void lengthPrefixingPreventsFieldBoundaryConfusion() {
        // Naive concatenation would make these collide; length-prefix framing must keep them distinct.
        byte[] a = aad(1L, IntegrationKind.GITHUB, "a", "bc");
        byte[] b = aad(1L, IntegrationKind.GITHUB, "ab", "c");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void rejectsBlankInstanceKey() {
        assertThatThrownBy(() -> aad(1L, IntegrationKind.GITHUB, "", "col"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instanceKey must not be blank");
    }

    @Test
    void rejectsFieldExceedingU16LengthLimit() {
        EncryptionContext oversized = new EncryptionContext(1L, IntegrationKind.GITHUB, "inst", "c".repeat(70_000));
        assertThatThrownBy(oversized::toAad)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("u16 length limit");
    }

    @Test
    void shouldAcceptMaximumU16FieldLength() {
        String maximumColumn = "c".repeat(0xFFFF);
        byte[] actual = aad(1L, IntegrationKind.GITHUB, "inst", maximumColumn);

        int columnLengthOffset = actual.length - 0xFFFF - 2;
        assertThat(actual[columnLengthOffset]).isEqualTo((byte) 0xFF);
        assertThat(actual[columnLengthOffset + 1]).isEqualTo((byte) 0xFF);
        assertThat(actual[columnLengthOffset + 2]).isEqualTo((byte) 'c');
        assertThat(actual[actual.length - 1]).isEqualTo((byte) 'c');
    }
}
