package de.tum.cit.aet.hephaestus.practices.review.teamwork;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SnapshotIndex;
import de.tum.cit.aet.hephaestus.integration.core.spi.SnapshotIndexer;
import de.tum.cit.aet.hephaestus.integration.core.spi.SnapshotRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.SnapshotRefusedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Stands in for a board tool: a grid of cells, each with its notes. */
final class BoardFixtureIndexer implements SnapshotIndexer {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public Set<ItemKind> kinds() {
        return Set.of(Captures.KIND);
    }

    @Override
    public String normalizer() {
        return "synthetic-board/1";
    }

    @Override
    public SnapshotIndex index(byte[] normalized) {
        JsonNode board = JSON.readTree(normalized);
        if (!"synthetic-board".equals(board.path("format").asString()))
            throw new SnapshotRefusedException(SnapshotRefusal.UNSUPPORTED_VERSION);
        var elements = new ArrayList<SnapshotIndex.Element>();
        for (JsonNode cell : board.path("cells")) {
            var label = cell.path("row").asString() + " × " + cell.path("column").asString();
            var text = new ArrayList<String>();
            for (JsonNode note : cell.path("notes")) text.add(label + ": " + note.asString());
            if (text.isEmpty()) text.add("(empty)");
            elements.add(new SnapshotIndex.Element(cell.path("id").asString(), "board.cell", label, null, text));
        }
        return new SnapshotIndex(elements, List.of());
    }
}
