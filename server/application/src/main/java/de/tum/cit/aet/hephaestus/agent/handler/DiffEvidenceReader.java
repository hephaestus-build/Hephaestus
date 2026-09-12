package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Retains only the prefix a caller can compare, while consuming and hashing each complete line. */
final class DiffEvidenceReader {
    private DiffEvidenceReader() {}

    record Line(String prefix, boolean complete, String contentSha256) {}

    static void scan(Reader reader, int prefixLength, boolean hashContent, Consumer<Line> consumer) throws IOException {
        var prefix = new StringBuilder();
        var digest = ProvenanceDigest.sha256();
        boolean contentStarted = false;
        boolean annotated = false;
        boolean annotationDone = false;
        boolean skipSign = false;
        long length = 0;
        int previous = -1;
        int highSurrogate = -1;
        char[] buffer = new char[8192];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            for (int i = 0; i < count; i++) {
                char value = buffer[i];
                if (value == '\n') {
                    consumer.accept(new Line(prefix.toString(), length <= prefixLength, ProvenanceDigest.hex(digest)));
                    prefix.setLength(0);
                    digest = ProvenanceDigest.sha256();
                    contentStarted = false;
                    annotated = false;
                    annotationDone = false;
                    skipSign = false;
                    length = 0;
                    previous = -1;
                    highSurrogate = -1;
                    continue;
                }
                if (length < prefixLength) prefix.append(value);
                length++;
                if (length == 1) annotated = value == '[';
                if (!hashContent) continue;
                if (!contentStarted) {
                    if (annotated && !annotationDone) {
                        if (previous == ']' && value == ' ') {
                            annotationDone = true;
                            skipSign = true;
                        }
                        previous = value;
                        continue;
                    }
                    contentStarted = true;
                    if (skipSign && (value == '+' || value == '-')) continue;
                }
                if (Character.isHighSurrogate(value)) {
                    highSurrogate = value;
                    continue;
                }
                int codePoint = highSurrogate < 0 ? value : Character.toCodePoint((char) highSurrogate, value);
                highSurrogate = -1;
                digest.update(new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8));
            }
        }
        if (length > 0)
            consumer.accept(new Line(prefix.toString(), length <= prefixLength, ProvenanceDigest.hex(digest)));
    }
}
