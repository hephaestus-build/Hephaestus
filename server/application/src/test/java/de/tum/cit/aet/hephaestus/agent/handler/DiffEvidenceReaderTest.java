package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class DiffEvidenceReaderTest extends BaseUnitTest {
    @Test
    void shouldConsumeLongLinesWithoutRetainingThem() throws Exception {
        var lines = new ArrayList<DiffEvidenceReader.Line>();
        try (var reader = new Reader() {
            private long remaining = 16L * 1024 * 1024;

            @Override
            public int read(char[] buffer, int offset, int length) {
                if (remaining == 0) return -1;
                int count = (int) Math.min(remaining, length);
                java.util.Arrays.fill(buffer, offset, offset + count, 'x');
                remaining -= count;
                return count;
            }

            @Override
            public void close() {}
        }) {
            DiffEvidenceReader.scan(reader, 32, false, lines::add);
        }
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().prefix()).isEqualTo("x".repeat(32));
        assertThat(lines.getFirst().complete()).isFalse();
    }

    @Test
    void shouldHashExactSourceBytesIncludingWhitespaceAndCarriageReturn() throws Exception {
        String content = "  λ secret  \r";
        var lines = new ArrayList<DiffEvidenceReader.Line>();
        DiffEvidenceReader.scan(new StringReader("[L42] +" + content + "\n"), 8, true, lines::add);
        assertThat(lines.getFirst().contentSha256())
                .isEqualTo(ProvenanceDigest.sha256Hex(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldDecodeQuotedGitPathsWithoutLosingUtf8OrWhitespace() {
        assertThat(PracticeDetectionDeliveryService.parseDiffPath("\"b/\\303\\251\\tfile.java\""))
                .isEqualTo("é\tfile.java");
        assertThat(PracticeDetectionDeliveryService.parseDiffPath("b/ leading trailing "))
                .isEqualTo(" leading trailing ");
    }
}
