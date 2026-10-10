package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.dto.PublicActivityDTO;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Public activity resolves only active, opted-in workspaces; SQL scopes every activity read")
@PreAuthorize("permitAll()")
public class PublicActivityController {
    private final PublicActivityService activity;

    @GetMapping("/public/workspaces/{slug}/activity")
    @SecurityRequirements
    @Operation(operationId = "getPublicActivity", summary = "Read human contributions to public repositories")
    public ResponseEntity<PublicActivityDTO> getPublicActivity(
            @PathVariable String slug, @ParameterObject ActivityPeopleRangeParams range) {
        var publication = activity.page(slug, range);
        var response = ResponseEntity.ok()
                .varyBy("Cookie", "Authorization")
                .cacheControl(
                        CurrentAccount.isAuthenticated()
                                ? CacheControl.noStore()
                                : CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic());
        if (!publication.allowSearchEngines()) {
            response.header("X-Robots-Tag", "noindex");
        }
        return response.body(publication.activity());
    }
}
