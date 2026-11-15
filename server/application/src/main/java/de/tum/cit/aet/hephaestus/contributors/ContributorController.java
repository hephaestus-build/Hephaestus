package de.tum.cit.aet.hephaestus.contributors;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for Hephaestus project contributor information.
 * This is a public endpoint used by the about page.
 */
@RestController
@RequestMapping("/contributors")
@RequiredArgsConstructor
@Tag(name = "Contributors", description = "Global contributor information")
@PreAuthorize("permitAll()")
@WorkspaceAgnostic("Lists the contributors of the Hephaestus project from GitHub; it reads no workspace data")
public class ContributorController {

    private final ContributorService contributorService;

    /**
     * List all Hephaestus project contributors.
     *
     * @return list of contributors sorted by contribution count
     */
    @GetMapping
    @Operation(
            summary = "List global contributors",
            description = "Returns contributors to the Hephaestus project (for the About page)")
    @SecurityRequirements
    public ResponseEntity<List<ContributorDTO>> listGlobalContributors() {
        return ResponseEntity.ok(contributorService.getGlobalContributors());
    }
}
