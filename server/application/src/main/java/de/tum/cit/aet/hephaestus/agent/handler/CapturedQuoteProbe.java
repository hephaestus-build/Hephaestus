package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceFileReads;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.practices.spi.CapturedTextProbe;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Whether the code that an earlier problem quoted is still in the work that this attempt captured. Only file versions
 * count: a text that a capture stages at a commit. A record of the change, such as its unified diff, holds removed lines
 * too, and a record of the item holds no code.
 *
 * <p>The answer is {@link Presence#GONE} only for a quote of the reviewed side of a file, when the capture holds the
 * cited source whole and holds the cited path, and no file version of the reviewed side contains the quote:
 *
 * <ul>
 *   <li>A file still at the cited path is read first.
 *   <li>A source that captures a change, with an old and a new side, is searched in every new file version: code that
 *       moved to another file, as in a rename, is still present. A deleted file keeps its old side in the capture, so
 *       its path is held and its code is gone.
 *   <li>A path that the capture does not hold, such as a file that the change no longer touches, shows nothing, and so
 *       does a quote of the old side, which the change removes by design.
 * </ul>
 *
 * Leading and trailing blanks of each line are ignored, so a reindented line is still present. The search of one citation
 * reads at most {@link #SEARCH_BUDGET_CHARS} characters; past that it cannot say.
 */
final class CapturedQuoteProbe implements CapturedTextProbe {
    static final long SEARCH_BUDGET_CHARS = 16L * 1024 * 1024;

    private final ReviewJob job;
    private final CapturedEvidence captured;
    private final EvidenceFileReads files;
    private long read;

    CapturedQuoteProbe(ReviewJob job, CapturedEvidence captured, EvidenceFileReads files) {
        this.job = job;
        this.captured = captured;
        this.files = files;
    }

    @Override
    public Presence presence(EvidenceCitation cited) {
        read = 0;
        var kind = cited.sourceKind();
        if (cited.side() == CitationSide.OLD || !captured.capturedWhole(kind) || captured.leftPartOut(kind))
            return Presence.UNKNOWN;
        List<CitableText> versions = captured.texts(kind).stream()
                .filter(text -> text.commit() != null)
                .toList();
        if (versions.stream().noneMatch(text -> text.path().equals(cited.path()))) return Presence.UNKNOWN;
        String quote = blanksTrimmed(cited.quote());
        if (quote.isBlank()) return Presence.UNKNOWN;
        var reviewed = versions.stream()
                .filter(text -> text.side() == CitationSide.NEW)
                .toList();
        var atPath = reviewed.stream()
                .filter(text -> text.path().equals(cited.path()))
                .toList();
        var found = search(atPath, quote);
        if (found != Presence.GONE) return found;
        boolean change = versions.stream().anyMatch(text -> text.side() == CitationSide.OLD);
        if (!change) return atPath.isEmpty() ? Presence.UNKNOWN : Presence.GONE;
        return search(
                reviewed.stream()
                        .filter(text -> !text.path().equals(cited.path()))
                        .toList(),
                quote);
    }

    /** PRESENT if a text holds the quote, GONE if none does, UNKNOWN if one could not be read within the budget. */
    private Presence search(List<CitableText> texts, String quote) {
        for (var text : texts) {
            var body = read(text);
            if (body.isEmpty()) return Presence.UNKNOWN;
            if (body.orElseThrow().contains(quote)) return Presence.PRESENT;
        }
        return Presence.GONE;
    }

    /** A text past the budget, one the attempt no longer holds, or one that fails its digest is no text. */
    private Optional<String> read(CitableText text) {
        try {
            var body = files.readText(job, text.stagedPath(), text.sha256());
            if (body.isEmpty()) return Optional.empty();
            read += body.orElseThrow().length();
            if (read > SEARCH_BUDGET_CHARS) return Optional.empty();
            return Optional.of(blanksTrimmed(body.orElseThrow()));
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
