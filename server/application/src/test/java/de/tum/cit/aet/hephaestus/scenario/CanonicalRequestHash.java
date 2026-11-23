package de.tum.cit.aet.hephaestus.scenario;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keys a model request by its meaning, not its bytes: object keys sorted at every depth, no insignificant whitespace,
 * array order kept. Two bodies that differ only in key order or spacing get the same hash.
 */
public final class CanonicalRequestHash {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CanonicalRequestHash() {}

    /** The request body in canonical form. */
    public static String canonical(String body) {
        return JSON.writeValueAsString(sorted(JSON.readTree(body)));
    }

    /** The SHA-256 of the canonical form, in hex. */
    public static String of(String body) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(canonical(body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform has SHA-256", e);
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Map<String, JsonNode> fields = new TreeMap<>();
            object.properties().forEach(field -> fields.put(field.getKey(), sorted(field.getValue())));
            ObjectNode copy = JSON.createObjectNode();
            fields.forEach(copy::set);
            return copy;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = JSON.createArrayNode();
            array.forEach(element -> copy.add(sorted(element)));
            return copy;
        }
        return node;
    }
}
