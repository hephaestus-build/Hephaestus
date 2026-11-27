package de.tum.cit.aet.hephaestus.practices.review.teamwork;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SnapshotIndex;
import de.tum.cit.aet.hephaestus.integration.core.spi.SnapshotIndexer;
import de.tum.cit.aet.hephaestus.integration.core.spi.SnapshotRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.SnapshotRefusedException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Stands in for a modelling tool: classes and the relationships drawn between them. */
final class ModelFixtureIndexer implements SnapshotIndexer {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public Set<ItemKind> kinds() {
        return Set.of(Captures.KIND);
    }

    @Override
    public String normalizer() {
        return "fixture-graph/1";
    }

    @Override
    public SnapshotIndex index(byte[] normalized) {
        JsonNode model = JSON.readTree(normalized);
        if (!"synthetic-model".equals(model.path("format").asString()))
            throw new SnapshotRefusedException(SnapshotRefusal.UNSUPPORTED_VERSION);
        var elements = new ArrayList<SnapshotIndex.Element>();
        var names = new HashMap<String, String>();
        for (JsonNode type : model.path("classes")) {
            var id = type.path("id").asString();
            var name = type.path("name").asString();
            names.put(id, name);
            var text = new ArrayList<String>(List.of("Class " + name));
            for (JsonNode member : type.path("members")) text.add(name + " " + member.asString());
            elements.add(new SnapshotIndex.Element(id, "model.class", name, null, text));
        }
        for (JsonNode edge : model.path("relationships")) {
            var from = names.get(edge.path("from").asString());
            var to = names.get(edge.path("to").asString());
            if (from == null || to == null) throw new SnapshotRefusedException(SnapshotRefusal.MALFORMED);
            var multiplicity = edge.path("multiplicityAtTo").asString();
            elements.add(new SnapshotIndex.Element(
                    edge.path("id").asString(),
                    "model.relationship",
                    from + " to " + to,
                    null,
                    List.of(
                            "Relationship from " + from + " to " + to + ", " + edge.path("drawn").asString(),
                            "Multiplicity at " + to + ": " + (multiplicity.isEmpty() ? "not written" : multiplicity))));
        }
        return new SnapshotIndex(elements, List.of());
    }
}
