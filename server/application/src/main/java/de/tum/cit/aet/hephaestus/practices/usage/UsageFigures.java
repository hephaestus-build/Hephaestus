package de.tum.cit.aet.hephaestus.practices.usage;

import de.tum.cit.aet.hephaestus.core.settings.spi.UsageFigure;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The stored figures of one released period: what {@link UsageDisclosure} let through, and nothing else. The stored
 * form carries a version. A reader that meets a version or a value it does not know publishes nothing, so a stored row
 * can never fail open (ADR 0153 decision 16).
 *
 * @param status whether the period publishes figures
 * @param figures each published figure, or null when it is hidden; empty unless the status is published
 */
record UsageFigures(PublicUsageStatus status, Map<UsageFigure, @Nullable Long> figures) {

    /** The version that {@link #toJson()} writes and {@link #read} understands. */
    static final short VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.shared();

    UsageFigures {
        var copy = new EnumMap<UsageFigure, @Nullable Long>(UsageFigure.class);
        copy.putAll(figures);
        figures = Collections.unmodifiableMap(copy);
    }

    static UsageFigures published(Map<UsageFigure, @Nullable Long> figures) {
        return new UsageFigures(PublicUsageStatus.PUBLISHED, figures);
    }

    static UsageFigures notEnoughActivity() {
        return new UsageFigures(PublicUsageStatus.NOT_ENOUGH_ACTIVITY, Map.of());
    }

    static UsageFigures notPublished() {
        return new UsageFigures(PublicUsageStatus.NOT_PUBLISHED, Map.of());
    }

    /** A figure as a reader may show it: null when it is hidden or the period publishes none. */
    @Nullable
    Long figure(UsageFigure figure) {
        return figures.get(figure);
    }

    String toJson() {
        ObjectNode root = JSON.createObjectNode().put("status", status.name());
        ObjectNode values = root.putObject("figures");
        figures.forEach((figure, value) -> {
            if (value == null) {
                values.putNull(figure.name());
            } else {
                values.put(figure.name(), value);
            }
        });
        return root.toString();
    }

    /** Reads a stored row. Anything unexpected reads as not published. */
    static UsageFigures read(short version, String json) {
        if (version != VERSION) {
            return notPublished();
        }
        try {
            return readVersion1(JSON.readTree(json));
        } catch (RuntimeException unreadable) {
            return notPublished();
        }
    }

    private static UsageFigures readVersion1(JsonNode root) {
        JsonNode values = root.path("figures");
        if (!root.isObject() || !values.isObject() || !root.path("status").isString()) {
            return notPublished();
        }
        return switch (root.path("status").asString()) {
            case "NOT_ENOUGH_ACTIVITY" -> values.isEmpty() ? notEnoughActivity() : notPublished();
            case "PUBLISHED" -> readPublished(values);
            default -> notPublished();
        };
    }

    private static UsageFigures readPublished(JsonNode values) {
        // Version 1 writes every figure, a hidden one as null; a row with fewer or other keys is not one it wrote.
        if (values.size() != UsageFigure.values().length) {
            return notPublished();
        }
        Map<UsageFigure, @Nullable Long> figures = new EnumMap<>(UsageFigure.class);
        for (var figure : UsageFigure.values()) {
            JsonNode value = values.get(figure.name());
            if (value == null) {
                return notPublished();
            }
            if (value.isNull()) {
                figures.put(figure, null);
            } else if (value.isIntegralNumber()
                    && value.canConvertToLong()
                    && UsageDisclosure.isRounded(value.asLong())
                    && (figure != UsageFigure.REVIEWED_PEOPLE || value.asLong() <= Integer.MAX_VALUE)) {
                figures.put(figure, value.asLong());
            } else {
                return notPublished();
            }
        }
        return published(figures);
    }
}
