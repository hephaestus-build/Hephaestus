package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityPersonDetailDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityWorkDTO;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Public activity resolves only active, opted-in workspaces; SQL scopes every activity read")
@PreAuthorize("permitAll()")
public class PublicActivityController {
    private final PublicActivityService activity;

    @GetMapping("/public/workspaces/{slug}/activity")
    @SecurityRequirements
    @Operation(operationId = "getPublicActivity", summary = "Read human contributions to public repositories")
    @ApiResponse(responseCode = "200", description = "Public contributions counted")
    public ResponseEntity<PublicActivityDTO> getPublicActivity(
            @PathVariable String slug,
            @ParameterObject ActivityPeopleRangeParams range,
            @RequestParam(required = false) @Nullable Set<String> repo) {
        var page = activity.page(slug, range, repo == null ? Set.of() : repo);
        return response(page, page.allowSearchEngines());
    }

    @GetMapping("/public/workspaces/{slug}/activity/people/{login}")
    @SecurityRequirements
    @Operation(operationId = "getPublicActivityPerson", summary = "Read a person's published repository contributions")
    @ApiResponse(responseCode = "200", description = "Public contributions counted")
    public ResponseEntity<PublicActivityPersonDetailDTO> getPublicActivityPerson(
            @PathVariable String slug,
            @PathVariable String login,
            @ParameterObject ActivityPeopleRangeParams range,
            @RequestParam(required = false) @Nullable Set<String> repo) {
        var page = activity.person(slug, login, range, repo == null ? Set.of() : repo);
        return response(page.body(), page.allowSearchEngines());
    }

    @GetMapping("/public/workspaces/{slug}/activity/people/{login}/work")
    @SecurityRequirements
    @Operation(operationId = "getPublicActivityPersonWork", summary = "Read a person's published work timeline")
    @ApiResponse(responseCode = "200", description = "Public work listed")
    public ResponseEntity<CursorPageDTO<PublicActivityWorkDTO>> getPublicActivityPersonWork(
            @PathVariable String slug,
            @PathVariable String login,
            @ParameterObject ActivityPeopleRangeParams range,
            @RequestParam(required = false) @Nullable Set<String> repo,
            @Valid @ParameterObject ActivityWorkFilterParams page) {
        var work = activity.work(slug, login, range, repo == null ? Set.of() : repo, page);
        return response(work.body(), work.allowSearchEngines());
    }

    private static <T> ResponseEntity<T> response(T body, boolean allowSearchEngines) {
        var response = ResponseEntity.ok()
                .varyBy("Cookie", "Authorization")
                .cacheControl(
                        CurrentAccount.isAuthenticated()
                                ? CacheControl.noStore()
                                : CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic());
        if (!allowSearchEngines) {
            response.header("X-Robots-Tag", "noindex");
        }
        return response.body(body);
    }
}
