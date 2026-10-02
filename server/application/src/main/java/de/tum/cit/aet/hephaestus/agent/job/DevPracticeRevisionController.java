package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only: gives practices the revision a review would give them before it asks, so a local seed that writes
 * observations directly pins them to a revision the server fingerprinted. {@link PracticeRevisionService#forReview(Long, java.util.Collection)}
 * keeps a revision already under the current fingerprint scheme and appends one otherwise, exactly as a review's
 * preparation does; the definition never changes.
 *
 * <p>Exists only while dev sign-in is enabled, which is how its one caller signs in, and only for {@code app_admin}.
 * {@code @Hidden}: a dev lever, not part of the client surface.
 *
 * <pre>
 *   POST /api/dev/practice-revisions?workspaceId=...&amp;slug=...&amp;slug=...
 * </pre>
 */
@ConditionalOnServerRole
@Hidden
@RestController
@ConditionalOnProperty(name = "hephaestus.auth.dev-login-enabled", havingValue = "true")
@RecentSignInExempt(reason = "development-only, disabled unless hephaestus.auth.dev-login-enabled is set")
@PreAuthorize("hasAuthority('app_admin')")
@WorkspaceAgnostic("Dev-only endpoint; workspace ID passed as request parameter")
public class DevPracticeRevisionController {

    private final PracticeRevisionService practiceRevisionService;

    public DevPracticeRevisionController(PracticeRevisionService practiceRevisionService) {
        this.practiceRevisionService = practiceRevisionService;
    }

    /** @return each practice found, as {@code slug@revisionNumber} of the revision a review would now pin */
    @PostMapping("/api/dev/practice-revisions")
    @AuditExempt(reason = "appends the revision a review appends before it asks; the definition does not change")
    public String forReview(@RequestParam Long workspaceId, @RequestParam("slug") List<String> slugs) {
        return practiceRevisionService.forReview(workspaceId, slugs).stream()
                .map(revision -> revision.getSlug() + "@" + revision.getRevisionNumber())
                .sorted()
                .collect(Collectors.joining(" "));
    }
}
