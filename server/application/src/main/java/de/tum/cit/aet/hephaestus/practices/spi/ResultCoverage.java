package de.tum.cit.aet.hephaestus.practices.spi;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "source")
@JsonSubTypes({@JsonSubTypes.Type(value = HostCoverage.class, name = "host")})
public sealed interface ResultCoverage permits HostCoverage {
    /**
     * The coverage of a stored result. An earlier install can hold coverage that a script claimed, which
     * admission no longer accepts and nothing measured, so it reads as no coverage.
     */
    static @Nullable ResultCoverage stored(ObjectMapper json, @Nullable JsonNode coverage) {
        return coverage == null || !"host".equals(coverage.path("source").asString(""))
                ? null
                : json.convertValue(coverage, ResultCoverage.class);
    }
}
