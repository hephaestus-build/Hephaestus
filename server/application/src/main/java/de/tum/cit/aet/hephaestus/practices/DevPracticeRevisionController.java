package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only: pins practices to the revision a review would pin, so a local seed that writes observations directly
 * cites a revision the server fingerprinted. The definition never changes.
 *
 * <pre>
 *   POST /api/dev/practice-revisions?workspaceId=...&amp;slug=...&amp;slug=...
 * </pre>
 */
@ConditionalOnServerRole
@Hidden
@RestController
@ConditionalOnBooleanProperty("hephaestus.dev.seed-enabled")
@RecentSignInExempt(reason = "development-only, disabled unless hephaestus.dev.seed-enabled is set")
@RequireInstanceAdmin
@WorkspaceAgnostic("Dev-only endpoint; workspace ID passed as request parameter")
@RequiredArgsConstructor
public class DevPracticeRevisionController {

    private final PracticeRevisionService practiceRevisionService;

    /** The revision a review pins for one practice, and whether this request appended it. */
    public record PinnedRevisionDTO(String slug, @Nullable Long revisionId, Integer revisionNumber, boolean appended) {}

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
