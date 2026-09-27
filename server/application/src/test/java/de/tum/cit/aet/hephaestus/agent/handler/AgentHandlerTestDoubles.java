package de.tum.cit.aet.hephaestus.agent.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class AgentHandlerTestDoubles {

    @Bean
    @Primary
    PullRequestCommentPoster testPullRequestCommentPoster() {
        return mock(PullRequestCommentPoster.class);
    }

    @Bean
    @Primary
    DiffNotePoster testDiffNotePoster() {
        return mock(DiffNotePoster.class);
    }

    @Bean
    @Primary
    AccountPreferencesQuery testAccountPreferencesQuery() {
        return mock(AccountPreferencesQuery.class);
    }

    static void resolveSummaryWrites(PullRequestCommentPoster poster) {
        SummaryChannel channel = mock(SummaryChannel.class);
        when(poster.summaryWrite(any(), anyBoolean(), anyString(), anyString())).thenAnswer(invocation -> {
            AgentJob job = invocation.getArgument(0);
            return new PullRequestCommentPoster.SummaryWrite(
                    job,
                    channel,
                    new SummaryChannel.FeedbackTarget(
                            new IntegrationRef(
                                    IntegrationKind.GITLAB, job.getWorkspace().getId(), null),
                            "test-subject",
                            null),
                    new SummaryChannel.FeedbackContent(invocation.getArgument(2), invocation.getArgument(3)));
        });
    }
}
