package de.tum.cit.aet.hephaestus.agent.handler.inapp;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only: lets a local seed write in-app feedback with a history ({@link DevInAppFeedbackService}).
 *
 * <p>Exists only while {@code hephaestus.dev.seed-enabled} is set, and only for {@code app_admin}. {@code @Hidden}:
 * a dev lever, not part of the client surface.
 *
 * <pre>
 *   POST /api/dev/in-app-feedback?workspaceId=...   body: [ DevInAppFeedbackService.Card ]
 * </pre>
 */
@ConditionalOnServerRole
@Hidden
@RestController
@ConditionalOnProperty(name = "hephaestus.dev.seed-enabled", havingValue = "true")
@RecentSignInExempt(reason = "development-only, disabled unless hephaestus.dev.seed-enabled is set")
@PreAuthorize("hasAuthority('app_admin')")
@WorkspaceAgnostic("Dev-only endpoint; workspace ID passed as request parameter")
public class DevInAppFeedbackController {

    private final DevInAppFeedbackService devInAppFeedbackService;

    public DevInAppFeedbackController(DevInAppFeedbackService devInAppFeedbackService) {
        this.devInAppFeedbackService = devInAppFeedbackService;
    }

    /** @return the ids of the cards written */
    @PostMapping("/api/dev/in-app-feedback")
    @AuditExempt(reason = "development-only demo data, disabled unless hephaestus.dev.seed-enabled is set")
    public List<UUID> write(
            @RequestParam Long workspaceId, @Valid @RequestBody List<DevInAppFeedbackService.Card> cards) {
        return devInAppFeedbackService.write(workspaceId, cards);
    }
}
