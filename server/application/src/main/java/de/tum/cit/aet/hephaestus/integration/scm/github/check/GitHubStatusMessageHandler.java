package de.tum.cit.aet.hephaestus.integration.scm.github.check;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.github.check.dto.GitHubStatusEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Handles GitHub {@code status} (commit status) webhook events. */
@Component
public class GitHubStatusMessageHandler extends AbstractIntegrationMessageHandler<GitHubStatusEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubStatusMessageHandler.class);

    private final ProcessingContextFactory contextFactory;
    private final GitHubHeadCheckProcessor headCheckProcessor;

    GitHubStatusMessageHandler(
            ProcessingContextFactory contextFactory,
            GitHubHeadCheckProcessor headCheckProcessor,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "repository." + GitHubEventType.STATUS.getValue(),
                GitHubStatusEventDTO.class,
                deserializer,
                transactionTemplate);
        this.contextFactory = contextFactory;
        this.headCheckProcessor = headCheckProcessor;
    }

    @Override
    protected void handleEvent(GitHubStatusEventDTO event) {
        CheckState state = GitHubHeadCheckProcessor.fromStatus(event.state());
        if (event.sha() == null || state == null) {
            log.warn("Received status event with missing data: state={}", event.state());
            return;
        }
        ProcessingContext context = contextFactory.forWebhookEvent(event).orElse(null);
        if (context == null) {
            return;
        }
        headCheckProcessor.observe(event.sha(), state, context);
    }
}
