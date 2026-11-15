package de.tum.cit.aet.hephaestus.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverterContextImpl;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class NamedEnumModelConverterTest {

    record FirstDTO(
            FirstDTO.Kind kind,

            @io.swagger.v3.oas.annotations.media.Schema(defaultValue = "BUG")
            FirstDTO.Kind withDefault,

            @io.swagger.v3.oas.annotations.media.Schema(allowableValues = "BUG")
            FirstDTO.Kind onlyBugs) {
        @io.swagger.v3.oas.annotations.media.Schema(description = "What was reported")
        enum Kind {
            BUG,
            @JsonProperty("idea")
            IDEA
        }
    }

    record Second(Second.Kind kind) {
        enum Kind {
            OPEN,
            CLOSED
        }
    }

    record Workspace() {
        enum WorkspaceStatus {
            ACTIVE
        }
    }

    record ClashDTO() {
        enum Kind {
            OTHER
        }
    }

    record Clash() {
        enum Kind {
            DIFFERENT
        }
    }

    private final NamedEnumModelConverter converter =
            new NamedEnumModelConverter(JsonMapper.builder().build());

    @Test
    void shouldNameANestedEnumAfterItsDeclaringTypeWithTheWireValues() {
        var context = new ModelConverterContextImpl(List.of(converter));

        var first = context.resolve(new AnnotatedType(FirstDTO.Kind.class));
        var second = context.resolve(new AnnotatedType(Second.Kind.class));

        assertThat(first.get$ref()).isEqualTo("#/components/schemas/FirstKind");
        assertThat(second.get$ref()).isEqualTo("#/components/schemas/SecondKind");
        Schema<?> defined = Objects.requireNonNull(context.getDefinedModels().get("FirstKind"));
        List<Object> values = List.copyOf(defined.getEnum());
        assertThat(values).containsExactly("BUG", "idea");
        assertThat(defined.getDescription()).isEqualTo("What was reported");
    }

    @Test
    void shouldNotRepeatTheDeclaringTypeThatTheEnumNameAlreadyStartsWith() {
        assertThat(NamedEnumModelConverter.schemaName(Workspace.WorkspaceStatus.class))
                .isEqualTo("WorkspaceStatus");
    }

    @Test
    void shouldKeepAPropertyDefaultAndNarrowToItsAllowedValues() throws NoSuchFieldException {
        var context = new ModelConverterContextImpl(List.of(converter));

        var withDefault = context.resolve(usage("withDefault"));
        Schema<?> onlyBugs = context.resolve(usage("onlyBugs"));

        assertThat(withDefault.get$ref()).isEqualTo("#/components/schemas/FirstKind");
        assertThat(withDefault.getDefault()).isEqualTo("BUG");
        assertThat(onlyBugs.get$ref()).isNull();
        List<Object> allowed = List.copyOf(onlyBugs.getEnum());
        assertThat(allowed).containsExactly("BUG");
    }

    @Test
    void shouldRefuseTwoEnumsThatWouldShareAName() {
        var context = new ModelConverterContextImpl(List.of(converter));
        context.resolve(new AnnotatedType(ClashDTO.Kind.class));

        assertThatThrownBy(() -> context.resolve(new AnnotatedType(Clash.Kind.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ClashKind");
    }

    private static AnnotatedType usage(String component) throws NoSuchFieldException {
        Annotation[] annotations = FirstDTO.class.getDeclaredField(component).getAnnotations();
        return new AnnotatedType(FirstDTO.Kind.class).ctxAnnotations(annotations);
    }
}
