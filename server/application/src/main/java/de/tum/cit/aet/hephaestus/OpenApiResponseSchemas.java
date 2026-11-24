package de.tum.cit.aet.hephaestus;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

final class OpenApiResponseSchemas {

    private OpenApiResponseSchemas() {}

    /** Use the same roots for retention, name normalization and the open-code rule. */
    @SuppressWarnings("rawtypes")
    static void visit(OpenAPI openApi, Operation operation, Consumer<Schema> visit) {
        if (operation.getResponses() == null) return;
        var components = openApi.getComponents();
        var responses = components == null ? null : components.getResponses();
        var headers = components == null ? null : components.getHeaders();
        Set<ApiResponse> seenResponses = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Header> seenHeaders = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ApiResponse response : operation.getResponses().values()) {
            for (var current = response;
                    current != null && seenResponses.add(current);
                    current = localComponent(current.get$ref(), "#/components/responses/", responses)) {
                visitContentSchemas(current.getContent(), visit);
                if (current.getHeaders() == null) continue;
                for (Header header : current.getHeaders().values()) {
                    for (var resolved = header;
                            resolved != null && seenHeaders.add(resolved);
                            resolved = localComponent(resolved.get$ref(), "#/components/headers/", headers)) {
                        if (resolved.getSchema() != null) visit.accept(resolved.getSchema());
                        visitContentSchemas(resolved.getContent(), visit);
                    }
                }
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private static void visitContentSchemas(@Nullable Content content, Consumer<Schema> visit) {
        if (content != null)
            content.values().stream()
                    .map(MediaType::getSchema)
                    .filter(Objects::nonNull)
                    .forEach(visit);
    }

    private static <T> @Nullable T localComponent(
            @Nullable String ref, String prefix, @Nullable Map<String, T> components) {
        return ref != null && components != null && ref.startsWith(prefix)
                ? components.get(ref.substring(prefix.length()))
                : null;
    }
}
