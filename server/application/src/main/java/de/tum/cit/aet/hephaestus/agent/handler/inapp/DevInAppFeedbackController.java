package de.tum.cit.aet.hephaestus.agent.handler.inapp;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only: lets a local seed write in-app feedback with a history ({@link DevInAppFeedbackService}).
 *
 * <pre>
 *   POST /api/dev/in-app-feedback?workspaceId=...   body: [ DevInAppFeedbackService.Card ]
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
public class DevInAppFeedbackController {

    private final DevInAppFeedbackService devInAppFeedbackService;

    @PostMapping("/api/dev/in-app-feedback")
    @AuditExempt(reason = "development-only demo data, disabled unless hephaestus.dev.seed-enabled is set")
    public List<UUID> write(
            @RequestParam Long workspaceId, @Valid @RequestBody List<DevInAppFeedbackService.Card> cards) {
        return devInAppFeedbackService.write(workspaceId, cards);
    }
}
