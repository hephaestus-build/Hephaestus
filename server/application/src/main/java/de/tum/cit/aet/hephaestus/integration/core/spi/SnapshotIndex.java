package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.HashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What an indexer read: the elements of one snapshot, in the order their text is written for a review, and what the
 * capture left out.
 */
public record SnapshotIndex(List<Element> elements, List<Limitation> limitations) {
    public SnapshotIndex {
        elements = List.copyOf(elements);
        limitations = List.copyOf(limitations);
        var ids = new HashSet<String>();
        for (var element : elements) {
            if (!ids.add(element.id())) throw new IllegalArgumentException("Two elements share an id");
        }
        for (var element : elements) {
            if (element.parentId() != null && !ids.contains(element.parentId()))
                throw new IllegalArgumentException("An element names a parent that the snapshot does not hold");
        }
    }

    /**
     * One thing a review can cite.
     *
     * @param id unique within the snapshot; it means nothing in another version
     * @param kind the indexer's own word for it, which only its view reads
     * @param label what a person calls it
     * @param text the citable lines, one string each. An empty position whose capture is complete is the single line
     *     {@code (empty)}, so a gap can be cited.
     */
    public record Element(
            String id, String kind, String label, @Nullable String parentId, List<String> text) {
        public static final int MAX_ID_LENGTH = 128;

        public Element {
            if (id.isEmpty() || id.length() > MAX_ID_LENGTH) throw new IllegalArgumentException("Invalid element id");
            if (kind.isBlank()) throw new IllegalArgumentException("An element needs a kind");
            text = List.copyOf(text);
            // A citation names line numbers, so one string must stay one line.
            if (text.isEmpty() || text.stream().anyMatch(line -> line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0))
                throw new IllegalArgumentException("An element needs text, one line for each string");
        }
    }

    /** Something inside the selection that the capture did not hold. A review cannot then claim it is absent. */
    public record Limitation(String code, int count) {
        public Limitation {
            if (code.isBlank() || count < 1) throw new IllegalArgumentException("Invalid limitation");
        }
    }
}
