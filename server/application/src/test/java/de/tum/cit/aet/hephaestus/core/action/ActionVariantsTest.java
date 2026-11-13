package de.tum.cit.aet.hephaestus.core.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActionVariantsTest extends BaseUnitTest {

    enum Level {
        LOW,
        HIGH
    }

    record Inner(Level level, String text) {}

    record Plain(UUID id, int count, Instant at, List<UUID> ids) {}

    record Nested(Inner inner, Level level) {}

    record Renamed(@JsonProperty("lvl") Level level) {}

    enum Style {
        PLAIN,
        FANCY;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum Tagged {
        A,
        B;

        @JsonValue
        String value() {
            return name();
        }
    }

    record Spelled(Style style) {}

    record ValueTagged(Tagged tagged) {}

    record WithMap(Map<String, String> data) {}

    record WithEnums(List<Level> levels) {}

    @Test
    void shouldAdmitAnyPayloadOfARecordWithoutEnums() {
        assertThat(ActionVariants.one(Plain.class).path()).isEqualTo("$");
    }

    @Test
    void shouldGuardEveryEnumOfTheRecordAndOfTheRecordsInsideIt() {
        assertThat(ActionVariants.one(Nested.class).path())
                .contains("@.payload.inner.level == \"LOW\"", "@.payload.inner.level == \"HIGH\"")
                .contains("@.payload.level == \"LOW\"", "@.payload.level == \"HIGH\"")
                .contains("!exists(@.payload.level) || @.payload.level == null");
    }

    @Test
    void shouldAdmitOnlyTheTagsOfTheBuildAndGuardTheirBodies() {
        assertThat(ActionVariants.tagged("kind", "event", Map.of("KNOWN", Inner.class))
                        .path())
                .startsWith("$ ? ((@.payload.kind == \"KNOWN\" && ")
                .contains("@.payload.event.level == \"HIGH\"");
    }

    @Test
    void shouldAdmitOnlyTheSuffixesOfTheBuild() {
        assertThat(ActionVariants.keySuffix(List.of(Level.LOW, Level.HIGH)).path())
                .isEqualTo("$ ? (@.key like_regex \":(LOW|HIGH)$\")");
    }

    @Test
    void shouldRefuseAShapeThatNoWalkCovers() {
        assertThatThrownBy(() -> ActionVariants.one(Renamed.class)).hasMessageContaining("Jackson annotation");
        assertThatThrownBy(() -> ActionVariants.one(WithMap.class)).hasMessageContaining("no discriminator walk");
        assertThatThrownBy(() -> ActionVariants.one(WithEnums.class)).hasMessageContaining("collection");
        assertThatThrownBy(() -> ActionVariants.one(String.class)).hasMessageContaining("record");
        assertThatThrownBy(() -> ActionVariants.one(Spelled.class)).hasMessageContaining("overrides toString");
        assertThatThrownBy(() -> ActionVariants.one(ValueTagged.class)).hasMessageContaining("Jackson annotation");
        assertThatThrownBy(() -> ActionVariants.tagged("kind", "event", Map.of()))
                .hasMessageContaining("at least one");
        assertThatThrownBy(() -> ActionVariants.keySuffix(List.of())).hasMessageContaining("at least one");
    }
}
