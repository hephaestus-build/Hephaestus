package de.tum.cit.aet.hephaestus.agent.composer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The value semantics the renderers and checks rely on: a {@code MissingNode} is an absent value and a
 * {@code NullNode} an explicit null, as the JSON the prompts show keeps them apart. Read fields with {@code path},
 * never {@code get}.
 */
final class Js {
    private Js() {}

    static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

    static boolean isString(JsonNode value) {
        return value.isString();
    }

    static boolean isRecord(JsonNode value) {
        return value.isObject();
    }

    /** The string, or null when the value is not one. */
    static @Nullable String string(JsonNode value) {
        return value.isString() ? value.asString() : null;
    }

    /** {@code Array.isArray(value) ? value : []}. */
    static List<JsonNode> listOf(JsonNode value) {
        var list = new ArrayList<JsonNode>();
        if (value.isArray()) value.forEach(list::add);
        return list;
    }

    /** {@code String.prototype.trim}: Unicode white space, the byte order mark and line terminators. */
    static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && whitespace(text.charAt(start))) start++;
        while (end > start && whitespace(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    static boolean blank(String text) {
        return trim(text).isEmpty();
    }

    private static boolean whitespace(char c) {
        return c == '\t'
                || c == '\n'
                || c == 0x0B
                || c == '\f'
                || c == '\r'
                || c == ' '
                || c == 0x00A0
                || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028
                || c == 0x2029
                || c == 0x202F
                || c == 0x205F
                || c == 0x3000
                || c == 0xFEFF;
    }

    /** A non-blank string, or null. */
    static @Nullable String textOf(JsonNode value) {
        return value.isString() && !blank(value.asString()) ? value.asString() : null;
    }

    static boolean isInteger(JsonNode number) {
        return number.isNumber()
                && (number.isIntegralNumber() || number.doubleValue() == Math.rint(number.doubleValue()))
                && !Double.isInfinite(number.doubleValue());
    }

    static boolean isSafeInteger(JsonNode number) {
        return isInteger(number) && Math.abs(number.doubleValue()) <= MAX_SAFE_INTEGER;
    }

    /** {@code Date.parse} as a finite instant, for the ISO forms the capture and the server write; null otherwise. */
    static @Nullable Instant instant(JsonNode value) {
        return value.isString() ? instant(value.asString()) : null;
    }

    static @Nullable Instant instant(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException notOffset) {
            try {
                return LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException notDate) {
                return null;
            }
        }
    }

    /** {@code JSON.stringify} of a string. */
    static String quote(String text) {
        return JsonText.quote(text);
    }
}
