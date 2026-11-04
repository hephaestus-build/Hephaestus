package de.tum.cit.aet.hephaestus.core.auth.audit;

import de.tum.cit.aet.hephaestus.core.auth.spi.ProductFeedbackAudit;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@ConditionalOnServerRole
@Component
public class ProductFeedbackAuditAdapter implements ProductFeedbackAudit {

    private final AuthEventLogger authEventLogger;
    private final ObjectMapper json;

    public ProductFeedbackAuditAdapter(AuthEventLogger authEventLogger, ObjectMapper json) {
        this.authEventLogger = authEventLogger;
        this.json = json;
    }

    @Override
    public boolean recordDestinationsChanged(
            long actingAccountId,
            @Nullable String publicRepository,
            @Nullable String privateRepository,
            boolean paused,
            int noticeVersion) {
        return authEventLogger
                .event(AuthEvent.EventType.PRODUCT_FEEDBACK_DESTINATIONS_CHANGED, AuthEvent.Result.SUCCESS)
                .actingAccount(actingAccountId)
                .details(json.createObjectNode()
                        .put("publicRepository", publicRepository)
                        .put("privateRepository", privateRepository)
                        .put("paused", paused)
                        .put("noticeVersion", noticeVersion)
                        .toString())
                .recordInCurrentTransaction();
    }

    @Override
    public boolean recordCleanupConfirmed(long actingAccountId, UUID cleanupId, String step, String repository) {
        return authEventLogger
                .event(AuthEvent.EventType.PRODUCT_FEEDBACK_CLEANUP_CONFIRMED, AuthEvent.Result.SUCCESS)
                .actingAccount(actingAccountId)
                .details(json.createObjectNode()
                        .put("cleanupId", cleanupId.toString())
                        .put("step", step)
                        .put("repository", repository)
                        .toString())
                .recordInCurrentTransaction();
    }
}
