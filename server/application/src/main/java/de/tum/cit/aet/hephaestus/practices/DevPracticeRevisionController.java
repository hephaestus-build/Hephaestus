package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only: gives practices the revision a review would give them before it asks, so a local seed that writes
 * observations directly pins them to a revision the server fingerprinted. {@link PracticeRevisionService#forReview(
 * Long, java.util.Collection)} keeps a revision already under the current fingerprint scheme and appends one
 * otherwise, exactly as a review's preparation does; the definition never changes, and the revision it appends is
 * the one the next review of the practice would append.
 *
 * <p>Exists only while {@code hephaestus.dev.seed-enabled} is set, and only for {@code app_admin}. {@code @Hidden}:
 * a dev lever, not part of the client surface.
 *
 * <pre>
 *   POST /api/dev/practice-revisions?workspaceId=...&amp;slug=...&amp;slug=...
 * </pre>
 */
@ConditionalOnServerRole
@Hidden
@RestController
@ConditionalOnProperty(name = "hephaestus.dev.seed-enabled", havingValue = "true")
@RecentSignInExempt(reason = "development-only, disabled unless hephaestus.dev.seed-enabled is set")
@PreAuthorize("hasAuthority('app_admin')")
@WorkspaceAgnostic("Dev-only endpoint; workspace ID passed as request parameter")
public class DevPracticeRevisionController {

    private final PracticeRevisionService practiceRevisionService;

    public DevPracticeRevisionController(PracticeRevisionService practiceRevisionService) {
        this.practiceRevisionService = practiceRevisionService;
    }

    /** The revision a review now pins for one practice, and whether this request appended it. */
    public record PinnedRevisionDTO(String slug, @Nullable Long revisionId, Integer revisionNumber, boolean appended) {}

    /** @return each practice found, by slug */
    @PostMapping("/api/dev/practice-revisions")
    @AuditExempt(reason = "appends the revision a review appends before it asks; the definition does not change")
    public List<PinnedRevisionDTO> forReview(@RequestParam Long workspaceId, @RequestParam("slug") List<String> slugs) {
        return practiceRevisionService.forReview(workspaceId, slugs).stream()
                .map(pinned -> new PinnedRevisionDTO(
                        pinned.revision().getSlug(),
                        pinned.revision().getId(),
                        pinned.revision().getRevisionNumber(),
                        pinned.appended()))
                .sorted(Comparator.comparing(PinnedRevisionDTO::slug))
                .toList();
    }
}
