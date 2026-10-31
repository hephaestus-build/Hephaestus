package de.tum.cit.aet.hephaestus.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.spi.JavaTypeRegistry;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

class NativeHibernateJsonFormatMapperTest extends BaseUnitTest {
    @Test
    void shouldHonorConfiguredModulesAndDeclaredGenericTypes() {
        var configured = JsonMapper.builder()
                .findAndAddModules()
                .addModule(new SimpleModule("native-json-fixture")
                        .setMixInAnnotation(ConnectionConfig.GitLabTokenMetadata.class, MetadataMixin.class))
                .build();
        var properties = new HashMap<String, Object>();
        new HibernateJacksonFormatMapperConfig(configured).customize(properties);
        assertThat(properties.get("hibernate.type.json_format_mapper")).isInstanceOf(Jackson3JsonFormatMapper.class);
        var mapper =
                (Jackson3JsonFormatMapper) Objects.requireNonNull(properties.get("hibernate.type.json_format_mapper"));
        var type = new TypeReference<Map<String, List<ConnectionConfig>>>() {}.getType();
        Map<String, List<ConnectionConfig>> original = Map.of(
                "providers",
                List.of(
                        new ConnectionConfig.GitHubPatConfig("acme", null, Set.of("repos")),
                        new ConnectionConfig.GitLabConfig(
                                "https://typed.example",
                                42L,
                                null,
                                Set.of("merge_requests"),
                                new ConnectionConfig.GitLabTokenMetadata(
                                        null, Instant.parse("2026-10-01T12:00:00Z")))));
        String json = mapper.toString(original, type);
        assertThat(configured
                        .readTree(json)
                        .path("providers")
                        .path(0)
                        .path("type")
                        .asString())
                .isEqualTo("GITHUB_PAT");
        var metadata = configured.readTree(json).path("providers").path(1).path("tokenMetadata");
        assertThat(metadata.path("checkedAtFromModule").asString()).isEqualTo("2026-10-01T12:00:00Z");
        assertThat(metadata.has("checkedAt")).isFalse();
        Map<String, List<ConnectionConfig>> reloaded = mapper.fromString(json, type);
        assertThat(reloaded).isEqualTo(original);
    }

    @Test
    void shouldPreserveTheJacksonCauseWhenJsonIsMalformed() {
        var mapper = new Jackson3JsonFormatMapper(JsonMapper.builder().build());
        var type = new JavaTypeRegistry(null).resolveDescriptor(JsonNode.class);
        assertThatThrownBy(() -> mapper.fromString("{not json", type, mock(WrapperOptions.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(JacksonException.class);
    }

    abstract static class MetadataMixin {
        @JsonProperty("checkedAtFromModule")
        abstract Instant checkedAt();
    }
}
