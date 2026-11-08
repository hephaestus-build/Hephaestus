package de.tum.cit.aet.hephaestus.practices.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class DefinitionSlugsTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();
    private static final List<Class<?>> PRACTICES =
            List.of(CreatePracticeRequestDTO.class, CatalogPracticeInputDTO.class);
    private static final List<Class<?>> GROUPS =
            List.of(CreatePracticeGroupRequestDTO.class, CatalogGroupInputDTO.class);

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    static Stream<String> reservedNames() {
        return DefinitionSlugs.reservedPracticeNames().stream();
    }

    @ParameterizedTest
    @MethodSource("reservedNames")
    void shouldReservePracticeRoutesWithoutReservingGroupNames(String slug) {
        for (var type : PRACTICES) {
            assertThat(VALIDATOR.validateValue(type, "slug", slug)).isNotEmpty();
        }
        for (var type : GROUPS) {
            assertThat(VALIDATOR.validateValue(type, "slug", slug)).isEmpty();
        }
        assertThat(VALIDATOR.validateValue(ReorderRequestDTO.class, "slugs", List.of(slug)))
                .isEmpty();
    }

    @Test
    void shouldKeepExistingFormatAndLengthBoundsIncludingShortIdentifiers() {
        for (String slug : List.of("p", "ab", "scans-summary", "a".repeat(64))) {
            for (var type : PRACTICES) {
                assertThat(VALIDATOR.validateValue(type, "slug", slug)).isEmpty();
            }
        }
        for (String slug : List.of("", "Uppercase", "-p", "p-", "p--q", "p\n", "a".repeat(65))) {
            for (var type : PRACTICES) {
                assertThat(VALIDATOR.validateValue(type, "slug", slug)).isNotEmpty();
            }
        }
    }

    @Test
    void shouldPublishTheSamePatternsThatTheEditorReadsFromGeneratedValidators() throws Exception {
        Object yaml = new Yaml().load(Files.readString(Path.of("..", "openapi.yaml")));
        var schemas = JsonMapper.builder()
                .build()
                .valueToTree(yaml)
                .path("components")
                .path("schemas");
        for (String name : List.of("CreatePracticeRequest", "CatalogPracticeInput")) {
            assertThat(schemas.path(name)
                            .path("properties")
                            .path("slug")
                            .path("pattern")
                            .asString())
                    .isEqualTo(DefinitionSlugs.PRACTICE_PATTERN);
        }
        for (String name : List.of("CreatePracticeGroupRequest", "CatalogGroupInput")) {
            assertThat(schemas.path(name)
                            .path("properties")
                            .path("slug")
                            .path("pattern")
                            .asString())
                    .isEqualTo(DefinitionSlugs.DEFINITION_PATTERN);
        }
    }

    @Test
    void shouldKeepTheBundledCatalogOnRoutablePracticeIdentifiers() throws Exception {
        try (var input = new ClassPathResource("practices/default-catalog.json").getInputStream()) {
            var catalog = JsonMapper.builder().build().readTree(input);
            int practices = 0;
            for (var group : catalog.path("groups")) {
                for (var practice : group.path("practices")) {
                    String slug = practice.path("slug").asString();
                    assertThat(Pattern.matches(DefinitionSlugs.PRACTICE_PATTERN, slug))
                            .as(slug)
                            .isTrue();
                    practices++;
                }
            }
            assertThat(practices).isPositive();
        }
    }

    @Test
    void shouldKeepLaterStaticRoutesFromTakingAcceptedPracticeIdentifiers() throws Exception {
        Object yaml = new Yaml().load(Files.readString(Path.of("..", "openapi.yaml")));
        if (!(yaml instanceof Map<?, ?> api) || !(api.get("paths") instanceof Map<?, ?> paths)) {
            throw new IllegalStateException("The generated API must have paths");
        }
        String prefix = "/workspaces/{workspaceSlug}/practices/";
        int routes = 0;
        for (Object key : paths.keySet()) {
            if (!(key instanceof String path) || !path.startsWith(prefix)) continue;
            String first = path.substring(prefix.length()).split("/", 2)[0];
            if (!first.startsWith("{") && Pattern.matches(DefinitionSlugs.DEFINITION_PATTERN, first)) {
                assertThat(DefinitionSlugs.reservedPracticeNames())
                        .as("Use a separate namespace for new static practice routes: %s", path)
                        .contains(first);
                routes++;
            }
        }
        assertThat(routes).isPositive();
    }
}
