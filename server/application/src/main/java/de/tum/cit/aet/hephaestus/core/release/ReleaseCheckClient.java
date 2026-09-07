package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Uses an isolated HTTP client so tenant credentials cannot reach release discovery. */
@Component
@ConditionalOnServerRole
public class ReleaseCheckClient {
    private static final String REPOSITORY = "hephaestus-build/Hephaestus";
    static final String REPOSITORY_URL = "https://github.com/" + REPOSITORY;
    private final RestClient client;
    private final ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired
    public ReleaseCheckClient(ObjectMapper mapper) {
        this(httpBuilder(), mapper);
    }

    ReleaseCheckClient(RestClient.Builder builder, ObjectMapper mapper) {
        this.mapper = mapper;
        client = builder.baseUrl("https://api.github.com/repos/" + REPOSITORY + "/releases/latest")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "Hephaestus-release-check")
                .build();
    }

    private static RestClient.Builder httpBuilder() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory);
    }

    public record Result(
            int status,
            @Nullable String etag,
            @Nullable String retryAfter,
            @Nullable String reset,
            ReleaseStatusDTO.@Nullable AvailableReleaseDTO release) {}

    public Result check(@Nullable String etag) {
        return client.get()
                .headers(headers -> {
                    if (etag != null) headers.setIfNoneMatch(etag);
                })
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    var headers = response.getHeaders();
                    ReleaseStatusDTO.AvailableReleaseDTO release = null;
                    if (status == 200) {
                        byte[] body = response.getBody().readNBytes(262145);
                        if (body.length > 262144) throw new IllegalArgumentException("Oversized release");
                        var node = mapper.readTree(body);
                        String tag = node.path("tag_name").asText("");
                        if (!tag.matches("v(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})")
                                || !node.path("draft").isBoolean()
                                || node.path("draft").asBoolean()
                                || !node.path("prerelease").isBoolean()
                                || node.path("prerelease").asBoolean()
                                || !node.path("body").isTextual()) {
                            throw new IllegalArgumentException("Unsupported release");
                        }
                        Instant.parse(node.path("published_at").asText(""));
                        String notes = node.path("body").asText();
                        String migrations = notes.contains("<!-- hephaestus:schema-migrations=true -->")
                                ? "REQUIRED"
                                : notes.contains("<!-- hephaestus:schema-migrations=false -->") ? "NONE" : "UNKNOWN";
                        release = new ReleaseStatusDTO.AvailableReleaseDTO(
                                tag.substring(1),
                                REPOSITORY_URL + "/releases/tag/" + tag,
                                migrations,
                                "UNKNOWN",
                                "Review this release and all intervening migration notes before upgrading.");
                    }
                    return new Result(
                            status,
                            headers.getETag(),
                            headers.getFirst("Retry-After"),
                            "0".equals(headers.getFirst("X-RateLimit-Remaining"))
                                    ? headers.getFirst("X-RateLimit-Reset")
                                    : null,
                            release);
                });
    }
}
