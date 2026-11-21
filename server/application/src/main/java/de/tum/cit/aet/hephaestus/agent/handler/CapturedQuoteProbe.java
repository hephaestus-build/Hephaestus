package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.practices.spi.CapturedTextProbe;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Whether the text that an earlier problem quoted is still in the work that this attempt captured. The answer is
 * {@link Presence#GONE} only when the capture holds the cited source whole, holds the cited path, and no text of the
 * source at the reviewed version contains the quote. The quote is searched in every text of the source, so code that
 * moved to another file, as in a rename, is still present. A deleted file keeps its old text in the capture of a change,
 * so its path is still there, and its code is gone. A path that the capture does not hold, such as a file that the
 * change no longer touches, shows nothing. Leading and trailing blanks of each line are ignored, so a reindented line
 * is still present.
 */
final class CapturedQuoteProbe implements CapturedTextProbe {
    private final ReviewJob job;
    private final CapturedEvidence captured;
    private final JobEvidenceFiles files;
    private final Map<String, Optional<String>> read = new HashMap<>();

    CapturedQuoteProbe(ReviewJob job, CapturedEvidence captured, JobEvidenceFiles files) {
        this.job = job;
        this.captured = captured;
        this.files = files;
    }

    @Override
    public Presence presence(EvidenceCitation cited) {
        var kind = cited.sourceKind();
        if (!captured.capturedWhole(kind) || captured.leftPartOut(kind)) return Presence.UNKNOWN;
        List<CitableText> texts = captured.texts(kind);
        if (texts.stream().noneMatch(text -> text.path().equals(cited.path()))) return Presence.UNKNOWN;
        String quote = blanksTrimmed(cited.quote());
        if (quote.isBlank()) return Presence.UNKNOWN;
        for (var text : texts) {
            if (text.side() != CitationSide.NEW) continue;
            var body = read.computeIfAbsent(text.stagedPath(), path -> read(path, text.sha256()));
            if (body.isEmpty()) return Presence.UNKNOWN;
            if (body.orElseThrow().contains(quote)) return Presence.PRESENT;
        }
        return Presence.GONE;
    }

    /** A text the attempt no longer holds, or one that fails its digest, is no text: nothing then shows it is gone. */
    private Optional<String> read(String path, String sha256) {
        try {
            return files.readText(job, path, sha256).map(CapturedQuoteProbe::blanksTrimmed);
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static String blanksTrimmed(String text) {
        return Arrays.stream(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1))
                .map(String::strip)
                .collect(Collectors.joining("\n"));
    }
}
