package de.tum.cit.aet.hephaestus.practices.review.teamwork;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SnapshotIndexer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/**
 * The two synthetic captures of the team review journey. Each observation cites two places of its version 1. Only the
 * capture differs between them: the item kind, the practice and what the review concludes are the same.
 */
final class Captures {
    /**
     * One kind for both, because a practice runs on one kind. The two captures prove the seam between a tool and the
     * review, not that one practice reviews several kinds: a real indexer declares the kinds of its own tool.
     */
    static final ItemKind KIND = new ItemKind("team.board");

    /** A place the observation rests on: an element of version 1 and the exact line it quotes. */
    record Cited(String elementId, String quote) {}

    record Capture(String name, SnapshotIndexer indexer, String version1, String version2, List<Cited> cited) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Capture board() {
        return new Capture(
                "board",
                new BoardFixtureIndexer(),
                fixture("board-v1.json"),
                fixture("board-v2.json"),
                List.of(
                        new Cited("c-use-gains", "Gains × Use: Refunds arrive the same day"),
                        new Cited("c-use-impact", "(empty)")));
    }

    static Capture model() {
        return new Capture(
                "model",
                new ModelFixtureIndexer(),
                fixture("model-v1.json"),
                fixture("model-v2.json"),
                List.of(
                        new Cited("e-lines", "Multiplicity at Order: not written"),
                        new Cited("c-order", "Class Order")));
    }

    private static String fixture(String name) {
        try {
            return new ClassPathResource("team-review/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Captures() {}
}
