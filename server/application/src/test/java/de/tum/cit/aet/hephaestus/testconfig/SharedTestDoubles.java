package de.tum.cit.aet.hephaestus.testconfig;

import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineContentClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineTokenClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineWebhookClient;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

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

    @Bean
    LateFailingWorkspacePurgeContributor lateFailingWorkspacePurgeContributor() {
        return new LateFailingWorkspacePurgeContributor();
    }

    @Bean
    FailingTransactionalOperation failingTransactionalOperation(JdbcTemplate jdbcTemplate) {
        return new FailingTransactionalOperation(jdbcTemplate);
    }

    @Bean
    SucceedingTransactionalOperation succeedingTransactionalOperation(JdbcTemplate jdbcTemplate) {
        return new SucceedingTransactionalOperation(jdbcTemplate);
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

    public static class FailingTransactionalOperation {

        private final JdbcTemplate jdbcTemplate;

        FailingTransactionalOperation(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Transactional
        public void execute() {
            jdbcTemplate.queryForObject("SELECT 1 / 0", Integer.class);
        }
    }

    public static class SucceedingTransactionalOperation {

        private final JdbcTemplate jdbcTemplate;
        private final AtomicInteger calls = new AtomicInteger();

        SucceedingTransactionalOperation(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Transactional
        public void execute() {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            calls.incrementAndGet();
        }

        public int calls() {
            return calls.get();
        }

        public void reset() {
            calls.set(0);
        }
    }
}
