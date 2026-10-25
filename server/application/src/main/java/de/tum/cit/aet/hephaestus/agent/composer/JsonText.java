package de.tum.cit.aet.hephaestus.agent.composer;

import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * JSON text as JavaScript's {@code JSON.stringify(value, null, indent)} writes it. The prompts embed such blocks in
 * this layout, which the golden fixtures record; Jackson's pretty printer spaces and escapes them differently.
 */
final class JsonText {
    private JsonText() {}

    static String stringify(JsonNode node, int indent) {
        var out = new StringBuilder();
        write(out, node, indent, 0);
        return out.toString();
    }

    /** {@code JSON.stringify(text)}. */
    static String quote(String text) {
        var out = new StringBuilder(text.length() + 2);
        writeString(out, text);
        return out.toString();
    }

    private static void write(StringBuilder out, JsonNode node, int indent, int depth) {
        if (node.isObject()) {
            if (node.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (property.getValue().isMissingNode()) continue;
                if (!first) out.append(',');
                first = false;
                newline(out, indent, depth + 1);
                writeString(out, property.getKey());
                out.append(indent > 0 ? ": " : ":");
                write(out, property.getValue(), indent, depth + 1);
            }
            newline(out, indent, depth);
            out.append('}');
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[');
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) out.append(',');
                newline(out, indent, depth + 1);
                JsonNode element = node.get(index);
                if (element.isMissingNode()) out.append("null");
                else write(out, element, indent, depth + 1);
            }
            newline(out, indent, depth);
            out.append(']');
        } else if (node.isString()) {
            writeString(out, node.asString());
        } else if (node.isNumber()) {
            out.append(number(node));
        } else if (node.isBoolean()) {
            out.append(node.asBoolean());
        } else {
            out.append("null");
        }
    }

    private static void newline(StringBuilder out, int indent, int depth) {
        if (indent <= 0) return;
        out.append('\n');
        out.append(" ".repeat(indent * depth));
    }

    /** A JavaScript number prints its shortest round-trip form; integral values print without a fraction. */
    private static String number(JsonNode node) {
        if (node.isIntegralNumber()) return node.bigIntegerValue().toString();
        double value = node.doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) return "null";
        if (value == Math.rint(value) && Math.abs(value) < 1e21) return new BigDecimal(value).toPlainString();
        String shortest = Double.toString(value);
        if (!shortest.contains("E")) return shortest;
        // Java writes 1.0E-7; JavaScript writes 1e-7.
        var exponent = shortest.split("E", 2);
        String mantissa = exponent[0].endsWith(".0") ? exponent[0].substring(0, exponent[0].length() - 2) : exponent[0];
        int power = Integer.parseInt(exponent[1]);
        return mantissa + "e" + (power > 0 ? "+" : "") + power;
    }

    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    boolean loneSurrogate = Character.isHighSurrogate(c)
                            ? index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))
                            : Character.isLowSurrogate(c)
                                    && (index == 0 || !Character.isHighSurrogate(text.charAt(index - 1)));
                    if (c < 0x20 || loneSurrogate)
                        out.append("\\u").append(HexFormat.of().toHexDigits(c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
