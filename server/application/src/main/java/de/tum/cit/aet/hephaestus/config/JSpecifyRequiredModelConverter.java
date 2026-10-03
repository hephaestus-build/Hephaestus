package de.tum.cit.aet.hephaestus.config;

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
import java.util.Iterator;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Bridges JSpecify nullness onto the OpenAPI {@code required} contract for springdoc / swagger-core.
 *
 * <p><b>Why this is needed.</b> swagger-core decides {@code required} by matching the <em>simple
 * name</em> of <em>declaration-site</em> annotations against a hardcoded set
 * ({@code NotNull, NonNull, NotBlank, NotEmpty}; see {@code io.swagger.v3.core.jackson.ModelResolver}).
 * The previously used {@code org.springframework.lang.@NonNull} satisfied that because it targeted
 * {@code FIELD}/{@code METHOD} with runtime retention. JSpecify {@code @NonNull} is
 * {@code @Target(TYPE_USE)} only, so it never reaches swagger's declaration-site inspection — and
 * every {@code required:} block silently disappears once the codebase migrates to JSpecify.
 * swagger-core's own fix for this is unmerged as of 2026-05 (swagger-api/swagger-core#4848, PR
 * swagger-api/swagger-core#4985); springdoc maintainers recommend a custom {@link ModelConverter} in
 * the meantime (springdoc/springdoc-openapi#3110, springdoc/springdoc-openapi#2991). springdoc
 * auto-registers every {@code ModelConverter} bean.
 *
 * <p><b>How.</b> After the default chain resolves a schema, this converter re-derives
 * {@code required} for our own types by reading the JSpecify {@code @NonNull} that sits at the
 * type-use position of each record component / field ({@link java.lang.reflect.AnnotatedType}). A
 * property is {@code required} iff its member is <em>explicitly</em> {@code @NonNull} — exactly the
 * contract swagger used to derive from the Spring annotation. This is deliberately an explicit-
 * annotation read rather than {@code org.springframework.core.Nullness}: {@code Nullness} reports
 * every primitive as non-null, which would mark bare primitives (e.g. a counter) {@code required}
 * and diverge from the historical spec. A primitive cannot be null, so a nullness annotation on it
 * says nothing; a primitive that must be {@code required} (e.g. {@code GitLabPreflightResponseDTO#valid})
 * declares {@code @Schema(requiredMode = REQUIRED)}, which swagger-core reads itself and this
 * converter leaves in place.
 *
 * <p><b>Scope.</b> Matching is by member name; a {@code @JsonProperty}/{@code @Schema(name=...)}
 * rename on an <em>exposed</em> schema would need its renamed key handled here. None exist today —
 * webhook DTOs that rename are deserialization-only and never surface as OpenAPI schemas — and
 * {@code JSpecifyRequiredModelConverterTest} locks the supported shapes.
 *
 * <p><b>Removal condition.</b> Delete this class once swagger-core ships native JSpecify support
 * (track swagger-api/swagger-core#4848) and springdoc pins that version; regenerating
 * {@code openapi.yaml} with this removed should then produce zero drift.
 */
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
            for (RecordComponent rc : raw.getRecordComponents()) {
                if (isNonNull(rc.getAnnotatedType())) {
                    markRequired(model, rc.getName());
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

    private static @Nullable Schema<?> referencedModel(Schema<?> resolved, ModelConverterContext context) {
        if (resolved.get$ref() == null || context.getDefinedModels() == null) {
            return null;
        }
        String ref = resolved.get$ref();
        return context.getDefinedModels().get(ref.substring(ref.lastIndexOf('/') + 1));
    }

    private static @Nullable Class<?> rawClass(Type type) {
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
