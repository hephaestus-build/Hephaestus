package de.tum.cit.aet.hephaestus.core.action;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * What an {@link ActionAdapter} can decode of an action row: the values of every discriminator that its code turns into
 * an enum or a type. A newer build may write a value that this build lacks. Such a row is neither claimed, ended nor
 * counted here, and still holds its lane, so the newer build decides it. The adapter declares its variants and has no
 * default: an adapter states either that its payload names no variant ({@link #one}), or where each variant is.
 *
 * <p>A declaration becomes one SQL JSON path over {@code {key: idempotency_key, payload}}, which the claims, the reaper
 * and the backlog gauges apply ({@link ActionAdmission}). The path derives from the declared types, so the values that
 * the adapter decodes have one home.
 */
public final class ActionVariants {
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]*");
    private static final Pattern CONSTANT = Pattern.compile("[A-Z][A-Z0-9_]*");

    private final String path;

    private ActionVariants(String path) {
        this.path = path;
    }

    /** The JSON path that selects the rows that this adapter can decode. */
    String path() {
        return path;
    }

    /**
     * The payload has one shape, the record {@code payload}. A later enum component of that record, or of a record
     * inside it, is guarded on its own: a row whose enum value this build lacks is not admitted.
     */
    public static ActionVariants one(Class<?> payload) {
        String condition = decodable(payload, "@.payload", new HashSet<>());
        return new ActionVariants(condition == null ? "$" : "$ ? (" + condition + ")");
    }

    /**
     * The payload names its variant in the string property {@code tag}, and the variant's data is in {@code body}.
     * Only the tags in {@code bodies} are admitted, each with the enum values of its body type.
     *
     * @param bodies the tag value of each variant that this build decodes, with the record that it decodes
     */
    public static ActionVariants tagged(String tag, String body, Map<String, Class<?>> bodies) {
        name(tag);
        name(body);
        if (bodies.isEmpty()) throw new IllegalArgumentException("A tagged payload has at least one variant");
        List<String> branches = new ArrayList<>();
        bodies.forEach((value, type) -> {
            String guard = decodable(type, "@.payload." + body, new HashSet<>());
            branches.add("(@.payload." + tag + " == \"" + constant(value) + "\"" + (guard == null ? "" : " && " + guard)
                    + ")");
        });
        return new ActionVariants("$ ? (" + String.join(" || ", branches) + ")");
    }

    /** The variant is the last {@code :} segment of the idempotency key; only {@code values} are admitted. */
    public static ActionVariants keySuffix(Collection<? extends Enum<?>> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("A key suffix has at least one variant");
        String alternatives =
                values.stream().map(value -> constant(value.name())).collect(Collectors.joining("|"));
        return new ActionVariants("$ ? (@.key like_regex \":(" + alternatives + ")$\")");
    }

    /**
     * The condition that every enum below {@code type} has a value that this build declares, or {@code null} when the
     * type constrains nothing. A missing or null value passes, as it does in the decoder.
     */
    private static @Nullable String decodable(Class<?> type, String path, Set<Class<?>> walking) {
        if (!walking.add(type)) throw new IllegalArgumentException("A recursive payload type: " + type.getName());
        try {
            rejectJackson(type);
            if (!type.isRecord()) throw new IllegalArgumentException("A payload is a record: " + type.getName());
            List<String> conditions = new ArrayList<>();
            for (RecordComponent component : type.getRecordComponents()) {
                String at = path + "." + name(component.getName());
                rejectJackson(component);
                rejectJackson(component.getAccessor());
                rejectJackson(field(type, component.getName()));
                String condition = component(component.getType(), component.getGenericType(), at, walking);
                if (condition != null) conditions.add(condition);
            }
            return conditions.isEmpty() ? null : String.join(" && ", conditions);
        } finally {
            walking.remove(type);
        }
    }

    private static @Nullable String component(Class<?> type, Type generic, String path, Set<Class<?>> walking) {
        if (type.isEnum()) {
            rejectJackson(type);
            for (Field constant : type.getFields()) rejectJackson(constant);
            for (Method method : type.getDeclaredMethods()) rejectJackson(method);
            // Jackson 3 reads and writes an enum by its toString, so an override renames the stored value.
            if (!toStringOf(type).equals(Enum.class))
                throw new IllegalArgumentException(
                        "An enum that overrides toString renames what Jackson stores: " + type.getName());
            String values = Arrays.stream(type.getEnumConstants())
                    .map(constant -> path + " == \"" + constant(((Enum<?>) constant).name()) + "\"")
                    .collect(Collectors.joining(" || "));
            return "(!exists(" + path + ") || " + path + " == null || " + values + ")";
        }
        if (type.isRecord()) {
            String inner = decodable(type, path, walking);
            return inner == null ? null : "(" + inner + ")";
        }
        if (Collection.class.isAssignableFrom(type)) {
            if (generic instanceof ParameterizedType parameterized
                    && parameterized.getActualTypeArguments()[0] instanceof Class<?> element
                    && opaque(element)) return null;
            throw new IllegalArgumentException("A collection of more than plain values at " + path);
        }
        if (type.isPrimitive() || opaque(type)) return null;
        throw new IllegalArgumentException("A payload component that no discriminator walk covers: " + path);
    }

    private static final Set<Class<?>> PLAIN = Set.of(
            String.class,
            Boolean.class,
            Byte.class,
            Short.class,
            Integer.class,
            Long.class,
            Float.class,
            Double.class,
            Character.class,
            UUID.class,
            JsonNode.class);

    /** A value whose decoding names no variant: a scalar, a time, an id, or a tree the adapter reads itself. */
    private static boolean opaque(Class<?> type) {
        return type.isPrimitive() || PLAIN.contains(type) || type.getName().startsWith("java.time.");
    }

    private static void rejectJackson(AnnotatedElement element) {
        for (var annotation : element.getAnnotations())
            if (annotation.annotationType().getName().contains("jackson"))
                throw new IllegalArgumentException(
                        "A Jackson annotation renames what the path reads: " + element + " " + annotation);
    }

    private static Class<?> toStringOf(Class<?> type) {
        try {
            return type.getMethod("toString").getDeclaringClass();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Every type has toString: " + type.getName(), e);
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("A record has a field for each component: " + name, e);
        }
    }

    private static String name(String name) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Not a plain property name: " + name);
        return name;
    }

    private static String constant(String value) {
        if (!CONSTANT.matcher(value).matches()) throw new IllegalArgumentException("Not a constant name: " + value);
        return value;
    }
}
