package de.tum.cit.aet.hephaestus.integration.scm.github.graphql;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A mapper configured like the one the GitHub GraphQL codecs decode with, for tests without a Spring context: the
 * production {@link GitHubGraphQlConfig#gitHubGraphQlObjectMapper} factory over a base mapper that has the two
 * {@code spring.jackson.deserialization.*} settings {@code application.yml} sets. The application-wide mapper's other
 * settings are not reproduced.
 */
public final class GitHubGraphQlTestMapper {

    private GitHubGraphQlTestMapper() {}

    public static JsonMapper create() {
        JsonMapper base = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
        return GitHubGraphQlConfig.gitHubGraphQlObjectMapper(base);
    }
}
