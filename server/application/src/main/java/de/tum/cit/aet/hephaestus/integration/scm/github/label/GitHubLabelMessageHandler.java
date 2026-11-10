package de.tum.cit.aet.hephaestus.integration.scm.github.label;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelEventDTO;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub label webhook events.
 */
@Component
public class GitHubLabelMessageHandler extends AbstractIntegrationMessageHandler<GitHubLabelEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubLabelMessageHandler.class);

    private final ProcessingContextFactory contextFactory;
    private final GitHubLabelProcessor labelProcessor;

    GitHubLabelMessageHandler(
            ProcessingContextFactory contextFactory,
            GitHubLabelProcessor labelProcessor,
            EventPayloadDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "repository." + GitHubEventType.LABEL.getValue(),
                GitHubLabelEventDTO.class,
                deserializer,
                transactionTemplate);
        this.contextFactory = contextFactory;
        this.labelProcessor = labelProcessor;
    }

    @Override
    protected void handleEvent(GitHubLabelEventDTO event) {
        @Nullable GitHubLabelDTO labelDto = event.label();

        if (labelDto == null) {
            log.warn("Received label event with missing data: action={}", event.action());
            return;
        }

        log.debug(
                "Received label event: action={}, labelName={}, repoName={}",
                event.action(),
                sanitizeForLog(labelDto.name()),
                event.repository() != null ? sanitizeForLog(event.repository().fullName()) : "unknown");

        ProcessingContext context = contextFactory.forWebhookEvent(event).orElse(null);
        if (context == null) {
            return;
        }

        if (event.actionType() == GitHubEventAction.Label.DELETED) {
            labelProcessor.deleteByNativeId(Objects.requireNonNull(labelDto.id()), context);
        } else {
            labelProcessor.process(labelDto, Objects.requireNonNull(context.repository()), context);
        }
    }
}
