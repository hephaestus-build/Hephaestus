package de.tum.cit.aet.hephaestus.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

// swagger-core inspects declaration annotations; JSpecify nullness is a type-use annotation.
// Only explicit @NonNull marks a property required; primitives use @Schema(requiredMode = REQUIRED).
// JsonInclude.ALWAYS records require every key, including nullable values.
@Component
public class JSpecifyRequiredModelConverter implements ModelConverter {

    private static final String BASE_PACKAGE = "de.tum.cit.aet.hephaestus";

    @Override
    public @Nullable Schema<?> resolve(
            AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Schema<?> resolved = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        if (resolved == null) {
            return null;
        }
        Class<?> raw = rawClass(type.getType());
        if (raw == null || !raw.getName().startsWith(BASE_PACKAGE)) {
            return resolved;
        }
        // The resolved schema is either the object model (properties present) or a $ref to a model
        // swagger-core just defined; in the latter case resolve the referenced model and edit that.
        Schema<?> model = resolved.getProperties() != null ? resolved : referencedModel(resolved, context);
        if (model == null || model.getProperties() == null) {
            return resolved;
        }
        if (raw.isRecord()) {
            JsonInclude inclusion = raw.getAnnotation(JsonInclude.class);
            boolean alwaysPresent = inclusion != null && inclusion.value() == JsonInclude.Include.ALWAYS;
            for (RecordComponent rc : raw.getRecordComponents()) {
                if (alwaysPresent || isNonNull(rc.getAnnotatedType())) {
                    markRequired(model, rc.getName());
                }
                if (alwaysPresent && rc.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                    markNullable(model, rc.getName());
                }
            }
        } else {
            for (Class<?> c = raw; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && isNonNull(f.getAnnotatedType())) {
                        markRequired(model, f.getName());
                    }
                }
            }
        }
        return resolved;
    }

    /** Whether a JSpecify {@code @NonNull} sits at the top level of a type-use position. */
    private static boolean isNonNull(AnnotatedElement annotatedType) {
        return annotatedType.isAnnotationPresent(NonNull.class);
    }

    /** Adds an existing, not-yet-required property to the model's {@code required} list. */
    private static void markRequired(Schema<?> model, String property) {
        if (model.getProperties().containsKey(property)
                && (model.getRequired() == null || !model.getRequired().contains(property))) {
            model.addRequiredItem(property);
        }
    }

    // ALWAYS records require nullable keys too; OpenAPI 3.1 represents null in the value type.
    private static void markNullable(Schema<?> model, String name) {
        Schema<?> property = model.getProperties().get(name);
        if (property == null) return;
        if (property.get$ref() != null
                || property.getOneOf() != null
                || property.getAnyOf() != null
                || property.getAllOf() != null) {
            if (property.getAnyOf() != null
                    && property.getAnyOf().stream().anyMatch(branch -> {
                        Schema<?> value = branch;
                        return value.get$ref() == null
                                && value.getTypes() != null
                                && value.getTypes().contains("null");
                    })) return;
            if (property.getTypes() != null) {
                var nonNullTypes = new HashSet<>(property.getTypes());
                nonNullTypes.remove("null");
                property.setTypes(nonNullTypes.isEmpty() ? null : nonNullTypes);
            }
            if ("null".equals(property.getType())) property.setType(null);
            Schema<Object> nullable = new Schema<>();
            nullable.setDescription(property.getDescription());
            nullable.addAnyOfItem(property);
            Schema<Object> nullValue = new Schema<>();
            nullValue.setTypes(Set.of("null"));
            nullable.addAnyOfItem(nullValue);
            model.addProperty(name, nullable);
            return;
        }
        var types = new HashSet<String>();
        if (property.getTypes() != null) types.addAll(property.getTypes());
        if (property.getType() != null) types.add(property.getType());
        types.add("null");
        property.setType(null);
        property.setTypes(types);
        allowNullEnumValue(property);
    }

    private static <T> void allowNullEnumValue(Schema<T> property) {
        if (property.getEnum() != null && !property.getEnum().contains(null)) {
            property.setEnum(new ArrayList<>(property.getEnum()));
            property.addEnumItemObject(null);
        }
    }

    private static @Nullable Schema<?> referencedModel(Schema<?> resolved, ModelConverterContext context) {
        if (resolved.get$ref() == null || context.getDefinedModels() == null) {
            return null;
        }
        String ref = resolved.get$ref();
        return context.getDefinedModels().get(ref.substring(ref.lastIndexOf('/') + 1));
    }

    /** The class behind a type that swagger-core passes, which can be a Jackson 2 {@code JavaType}. */
    static @Nullable Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return c;
        }
        // swagger-core hands nested property types through as a Jackson JavaType, not a Class.
        if (type instanceof JavaType jt) {
            return jt.getRawClass();
        }
        return null;
    }
}
