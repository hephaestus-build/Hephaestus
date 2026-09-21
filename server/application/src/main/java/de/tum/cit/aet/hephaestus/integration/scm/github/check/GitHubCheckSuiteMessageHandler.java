package de.tum.cit.aet.hephaestus.integration.scm.github.check;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.github.check.dto.GitHubCheckSuiteEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Handles GitHub {@code check_suite} webhook events. */
@Component
public class GitHubCheckSuiteMessageHandler extends AbstractIntegrationMessageHandler<GitHubCheckSuiteEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubCheckSuiteMessageHandler.class);

    private final ProcessingContextFactory contextFactory;
    private final GitHubHeadCheckProcessor headCheckProcessor;

    GitHubCheckSuiteMessageHandler(
            ProcessingContextFactory contextFactory,
            GitHubHeadCheckProcessor headCheckProcessor,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "repository." + GitHubEventType.CHECK_SUITE.getValue(),
                GitHubCheckSuiteEventDTO.class,
                deserializer,
                transactionTemplate);
        this.contextFactory = contextFactory;
        this.headCheckProcessor = headCheckProcessor;
    }

    @Override
    protected void handleEvent(GitHubCheckSuiteEventDTO event) {
        var suite = event.checkSuite();
        if (suite == null || suite.headSha() == null) {
            log.warn("Received check_suite event with missing data: action={}", event.action());
            return;
        }
        CheckState state = GitHubHeadCheckProcessor.fromCheckSuite(suite.status(), suite.conclusion());
        if (state == null) {
            log.debug(
                    "Skipped check_suite event: reason=saysNothingAboutTheHead, action={}, conclusion={}",
                    event.action(),
                    suite.conclusion());
            return;
        }
        ProcessingContext context = contextFactory.forWebhookEvent(event).orElse(null);
        if (context == null) {
            return;
        }
        headCheckProcessor.observe(suite.headSha(), state, context);
    }
}
