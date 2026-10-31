package de.tum.cit.aet.hephaestus.productfeedback;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The model's draft of one report, after code validated every field. The model's answer is an input to {@link
 * RoutingGate}: no single field can make a report public on its own.
 */
record Draft(
        Disposition disposition,
        Type type,
        String title,
        String observed,
        String expected,
        List<String> steps,
        String problem,
        String outcome,
        List<String> criteria,
        String context,
        @Nullable Long related,
        boolean aboutNamedPerson,
        boolean abuse,
        boolean coherent,
        Confidence confidence) {

    enum Disposition {
        ISSUE,
        NOT_AN_ISSUE,
        SECURITY
    }

    enum Type {
        BUG,
        FEATURE
    }

    enum Confidence {
        HIGH,
        MEDIUM,
        LOW
    }

    static final int TITLE_MAX = 80;
    static final int FIELD_MAX = 2000;
    static final int ITEM_MAX = 300;
    static final int ITEMS_MAX = 12;

    /** The answer could not be read as a draft. */
    static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message);
        }
    }

    /**
     * Reads the model's answer. The JSON object may sit in a code fence or among prose; anything outside the first
     * object is ignored. {@code related} must name an open issue of the snapshot, or it is dropped.
     */
    static Draft parse(String answer, ObjectMapper mapper, Set<Long> openIssues) throws Invalid {
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) throw new Invalid("The answer holds no JSON object.");
        JsonNode node;
        try {
            node = mapper.readTree(answer.substring(start, end + 1));
        } catch (JacksonException failed) {
            throw new Invalid("The JSON object does not parse.");
        }
        Disposition disposition = enumOf(Disposition.class, node.path("disposition"), "disposition");
        Type type = node.path("type").isMissingNode() || node.path("type").isNull()
                ? Type.BUG
                : enumOf(Type.class, node.path("type"), "type");
        String title = text(node, "title", TITLE_MAX);
        if (disposition == Disposition.ISSUE && title.isBlank()) throw new Invalid("An issue needs a title.");
        @Nullable Long related = null;
        JsonNode relatedNode = node.path("related");
        if (relatedNode.isIntegralNumber() && openIssues.contains(relatedNode.asLong())) related = relatedNode.asLong();
        return new Draft(
                disposition,
                type,
                title,
                text(node, "observed", FIELD_MAX),
                text(node, "expected", FIELD_MAX),
                items(node, "steps"),
                text(node, "problem", FIELD_MAX),
                text(node, "outcome", FIELD_MAX),
                items(node, "criteria"),
                text(node, "context", FIELD_MAX),
                related,
                node.path("aboutNamedPerson").asBoolean(false),
                node.path("abuse").asBoolean(false),
                node.path("coherent").asBoolean(false),
                enumOf(Confidence.class, node.path("confidence"), "confidence"));
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, JsonNode node, String field) throws Invalid {
        if (!node.isString()) throw new Invalid("The field " + field + " is missing.");
        try {
            return Enum.valueOf(type, node.asString().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new Invalid("The field " + field + " has an unknown value.");
        }
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        if (!value.isString()) return "";
        String text = value.asString().strip();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static List<String> items(JsonNode node, String field) {
        JsonNode value = node.path(field);
        var out = new ArrayList<String>();
        if (!value.isArray()) return out;
        for (JsonNode item : value) {
            if (out.size() == ITEMS_MAX) break;
            if (!item.isString() || item.asString().isBlank()) continue;
            String text = item.asString().strip();
            out.add(text.length() <= ITEM_MAX ? text : text.substring(0, ITEM_MAX));
        }
        return List.copyOf(out);
    }

    /** The same draft with every text field passed through {@code redact}, which also counts what it removed. */
    Draft map(UnaryOperator<String> redact) {
        return new Draft(
                disposition,
                type,
                redact.apply(title),
                redact.apply(observed),
                redact.apply(expected),
                steps.stream().map(redact).toList(),
                redact.apply(problem),
                redact.apply(outcome),
                criteria.stream().map(redact).toList(),
                redact.apply(context),
                related,
                aboutNamedPerson,
                abuse,
                coherent,
                confidence);
    }
}
