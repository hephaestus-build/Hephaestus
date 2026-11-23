package de.tum.cit.aet.hephaestus.config;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes each application enum as one named schema, so a type that crosses operations has one name in every
 * generated client. swagger-core's own {@code enumsAsRef} names a nested enum by its simple name, so {@code A.Kind}
 * and {@code B.Kind} would share one schema with the values of whichever came first. Here a nested enum also takes
 * the name of the type that declares it ({@code FeedbackDTO.Kind} becomes {@code FeedbackKind}), and a name that two
 * enums with different values would share fails the build of the spec.
 */
public class NamedEnumModelConverter implements ModelConverter {

    public static final String OPEN_ENUM_NOTE =
            "Extensible enum: a client accepts a value that the examples do not name.";

    /** The compatibility policy keeps both enums closed because a new value changes what an observation means. */
    public static final Set<String> CLOSED_PUBLIC_ENUMS = Set.of("ResultOutcome", "Severity");

    private static final String BASE_PACKAGE = "de.tum.cit.aet.hephaestus";

    /** Container suffixes that name no domain concept. */
    private static final List<String> CONTAINER_SUFFIXES = List.of("DTOs", "DTO", "Controller");

    private final JsonMapper mapper;
    private final Predicate<Class<?>> openWireCode;

    public NamedEnumModelConverter(JsonMapper mapper, Predicate<Class<?>> openWireCode) {
        this.mapper = mapper;
        this.openWireCode = openWireCode;
    }

    @Override
    public @Nullable Schema<?> resolve(
            AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Class<?> raw = JSpecifyRequiredModelConverter.rawClass(type.getType());
        if (raw == null || !raw.isEnum() || !raw.getName().startsWith(BASE_PACKAGE)) {
            return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        }
        var usage = schemaAnnotation(type.getCtxAnnotations());
        boolean open = openWireCode.test(raw);
        String name = schemaName(raw);
        if (!open && usage != null && usage.allowableValues().length > 0) {
            if (CLOSED_PUBLIC_ENUMS.contains(name)) {
                throw new IllegalStateException("The schema " + name + " must use its named closed enum.");
            }
            // The property accepts only some of the enum's values: those, not the whole type.
            StringSchema subset = new StringSchema();
            Stream.of(usage.allowableValues()).forEach(subset::addEnumItem);
            return subset;
        }
        // The wire names, as the application's mapper writes them (it honours @JsonValue and @JsonProperty).
        List<String> values = Stream.of(raw.getEnumConstants())
                .map(constant -> mapper.convertValue(constant, String.class))
                .toList();
        Schema<?> defined = context.getDefinedModels().get(name);
        if (defined != null) {
            var existingValues = open ? defined.getExamples() : defined.getEnum();
            if (!values.equals(existingValues)) {
                throw new IllegalStateException("Two types publish the schema name " + name + ": " + raw.getName()
                        + " with " + values + " and another with " + existingValues
                        + ". Give one @Schema(name = ...).");
            }
        }
        if (defined == null) {
            StringSchema schema = new StringSchema();
            var own = raw.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
            if (own != null && !own.description().isBlank()) {
                schema.setDescription(own.description());
            }
            if (open) {
                openWireSchema(schema, values);
            } else {
                values.forEach(schema::addEnumItem);
            }
            context.defineModel(name, schema);
        }
        Schema<?> reference = new Schema<>().$ref(Components.COMPONENTS_SCHEMAS_REF + name);
        if (usage != null && !usage.defaultValue().isBlank()) {
            reference.setDefault(usage.defaultValue());
        }
        return reference;
    }

    public static <T> void openWireSchema(Schema<T> schema, List<T> values) {
        schema.setEnum(null);
        schema.setExamples(values);
        String description = schema.getDescription();
        schema.setDescription(
                description == null || description.isBlank() ? OPEN_ENUM_NOTE : OPEN_ENUM_NOTE + " " + description);
    }

    /**
     * The enum's own {@code @Schema(name)}, else its simple name. A nested enum is prefixed with its declaring type's
     * name, without a container suffix, unless the simple name already starts with it ({@code Workspace.WorkspaceStatus}
     * stays {@code WorkspaceStatus}).
     */
    static String schemaName(Class<?> type) {
        var named = type.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
        if (named != null && !named.name().isBlank()) {
            return named.name();
        }
        Class<?> declaring = type.getEnclosingClass();
        if (declaring == null) {
            return type.getSimpleName();
        }
        String prefix = declaring.getSimpleName();
        for (String suffix : CONTAINER_SUFFIXES) {
            if (prefix.endsWith(suffix) && prefix.length() > suffix.length()) {
                prefix = prefix.substring(0, prefix.length() - suffix.length());
                break;
            }
        }
        return type.getSimpleName().startsWith(prefix) ? type.getSimpleName() : prefix + type.getSimpleName();
    }

    private static io.swagger.v3.oas.annotations.media.@Nullable Schema schemaAnnotation(
            Annotation @Nullable [] annotations) {
        if (annotations == null) {
            return null;
        }
        for (Annotation annotation : annotations) {
            if (annotation instanceof io.swagger.v3.oas.annotations.media.Schema schema) {
                return schema;
            }
        }
        return null;
    }
}
