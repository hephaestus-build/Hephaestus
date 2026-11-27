package de.tum.cit.aet.hephaestus.mentor.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.ToIntFunction;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Shapes already admitted items. The caller selects the route, checks each item and binds the returned refs. */
public final class ContextPacket {
    static final int MAX_TOKENS = 16_384;

    private ContextPacket() {}

    public enum Section {
        INPUT(4_096),
        PLACE(6_144),
        COMMITMENTS(2_048),
        EVIDENCE(3_072),
        CAUSE(1_024);

        private final int maxTokens;

        Section(int maxTokens) {
            this.maxTokens = maxTokens;
        }
    }

    public enum Role {
        PERSON,
        MENTOR,
        SOURCE
    }

    /** A kind, ID, revision and source scope from the owning reader. */
    public record SourceRef(String kind, String id, String revision, String scope) {
        @Override
        public String toString() {
            return "ContextPacket.SourceRef";
        }
    }

    public record Item(Section section, Role role, SourceRef ref, boolean pinned, String text) {
        @Override
        public String toString() {
            return "ContextPacket.Item[section=" + section + ", role=" + role + "]";
        }
    }

    public sealed interface Result permits Ready, TooLarge {}

    public record Ready(String payload, List<SourceRef> refs) implements Result {
        public Ready {
            refs = List.copyOf(refs);
        }

        @Override
        public String toString() {
            return "ContextPacket.Ready";
        }
    }

    /** The caller returns the fixed next action to narrow this request. */
    public record TooLarge(Section section) implements Result {}

    /**
     * Takes admitted items in priority order within each section. The window excludes the system, tool and output
     * reservations. Counts serialized provenance and escaping using the admitted model's input counter.
     */
    public static Result assemble(
            ObjectMapper mapper, List<Item> admitted, int inputWindow, ToIntFunction<String> tokens) {
        List<Item> inputs = admitted.stream()
                .filter(item -> item.section() == Section.INPUT)
                .toList();
        if (inputs.size() != 1 || !inputs.getFirst().pinned()) {
            throw new IllegalArgumentException("A packet requires one pinned input");
        }

        var root = mapper.createObjectNode();
        for (Section section : Section.values()) root.putArray(section.name());
        int available = Math.min(inputWindow, MAX_TOKENS);
        var included = new LinkedHashSet<SourceRef>();
        var ordered = admitted.stream()
                .sorted(Comparator.comparing(Item::pinned).reversed().thenComparing(Item::section))
                .toList();
        for (Item item : ordered) {
            if (included.contains(item.ref())) continue;
            Section section = item.section();
            ArrayNode values = root.withArray(section.name());
            append(values, item);
            if (tokens.applyAsInt(mapper.writeValueAsString(values)) > section.maxTokens
                    || tokens.applyAsInt(mapper.writeValueAsString(root)) > available) {
                values.remove(values.size() - 1);
                if (item.pinned()) return new TooLarge(section);
            } else {
                included.add(item.ref());
            }
        }
        return new Ready(mapper.writeValueAsString(root), new ArrayList<>(included));
    }

    private static void append(ArrayNode values, Item item) {
        ObjectNode entry = values.addObject().put("role", item.role().name());
        SourceRef ref = item.ref();
        entry.putObject("source")
                .put("kind", ref.kind())
                .put("id", ref.id())
                .put("revision", ref.revision())
                .put("scope", ref.scope());
        entry.put("text", item.text());
    }
}
