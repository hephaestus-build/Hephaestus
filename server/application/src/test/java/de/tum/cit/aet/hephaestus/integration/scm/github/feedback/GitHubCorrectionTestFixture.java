package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNoteWriter;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import org.springframework.graphql.client.HttpGraphQlClient;

public final class GitHubCorrectionTestFixture {
    private GitHubCorrectionTestFixture() {}

    public static LineNoteWriter writer(HttpGraphQlClient client) {
        var provider = mock(GitHubGraphQlClientProvider.class);
        when(provider.forScope(1L)).thenReturn(client);
        return new GitHubLineNoteWriter(
                provider,
                mock(GitHubPrNodeIdResolver.class),
                mock(OutboundEgressGuard.class),
                ScmFeedbackFixtures.resolver(),
                mock(GitHubSummaryWriter.class),
                ScmFeedbackFixtures.formatter());
    }
}
