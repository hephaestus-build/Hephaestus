package de.tum.cit.aet.hephaestus.testconfig;

import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.integration.core.webhook.JetStreamPublisher;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineContentClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineTokenClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineWebhookClient;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class SharedTestDoubles {

    @Bean
    @Primary
    OutlineContentClient outlineContentClient() {
        return mock(OutlineContentClient.class);
    }

    @Bean
    @Primary
    OutlineTokenClient outlineTokenClient() {
        return mock(OutlineTokenClient.class);
    }

    @Bean
    @Primary
    OutlineWebhookClient outlineWebhookClient() {
        return mock(OutlineWebhookClient.class);
    }

    @Bean
    @Primary
    SlackMessageService slackMessageService() {
        return mock(SlackMessageService.class);
    }

    @Bean
    @Primary
    SlackOAuthClient slackOAuthClient() {
        return mock(SlackOAuthClient.class);
    }

    /** NATS is off in tests; this stands in for it so a verified webhook reaches the publish step. */
    @Bean
    JetStreamPublisher jetStreamPublisher() {
        return mock(JetStreamPublisher.class);
    }

    @Bean
    LateFailingWorkspacePurgeContributor lateFailingWorkspacePurgeContributor() {
        return new LateFailingWorkspacePurgeContributor();
    }

    public static final class LateFailingWorkspacePurgeContributor implements WorkspacePurgeContributor {

        private final AtomicBoolean fail = new AtomicBoolean();

        public void setFail(boolean fail) {
            this.fail.set(fail);
        }

        @Override
        public void deleteWorkspaceData(Long workspaceId) {
            if (fail.get()) {
                throw new IllegalStateException("late purge failure");
            }
        }

        @Override
        public int getOrder() {
            return Integer.MAX_VALUE;
        }
    }
}
