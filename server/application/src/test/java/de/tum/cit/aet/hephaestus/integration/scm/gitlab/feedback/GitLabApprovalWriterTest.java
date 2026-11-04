package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.web.reactive.function.client.WebClient;

class GitLabApprovalWriterTest extends BaseUnitTest {

    @Mock
    private GitLabGraphQlClientProvider gitLabProvider;

    @Mock
    private GitLabTokenService tokenService;

    @Mock
    private GitLabSummaryWriter feedbackChannel;

    @Mock
    private OutboundEgressGuard egressGuard;

    private MockWebServer gitlab;
    private GitLabApprovalWriter channel;

    @BeforeEach
    void setUp() throws IOException {
        gitlab = new MockWebServer();
        gitlab.start();
        channel = new GitLabApprovalWriter(
                gitLabProvider,
                tokenService,
                feedbackChannel,
                () -> WebClient.builder(),
                egressGuard,
                ScmFeedbackFixtures.resolver());
    }

    @AfterEach
    void tearDown() throws IOException {
        gitlab.close();
    }

    private void stubScope() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(false);
        // Trailing slash on purpose: the URL builder must not emit "//api/v4".
        when(tokenService.resolveServerUrl(1L)).thenReturn(gitlab.url("/").toString());
        when(tokenService.getAccessToken(1L)).thenReturn("glpat-secret");
    }

    // GitLab project paths need one percent-encoding pass; a bare or double-encoded slash addresses another route.
    @Test
    void approvePostsToThePercentEncodedProjectPath() throws InterruptedException {
        stubScope();
        gitlab.enqueue(new MockResponse.Builder().code(201).body("{\"id\":1}").build());

        assertThatCode(() -> channel.approve(gitlabTarget(), null)).doesNotThrowAnyException();

        RecordedRequest request = gitlab.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getTarget()).isEqualTo("/api/v4/projects/group%2Fproject/merge_requests/42/approve");
        assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer glpat-secret");
    }

    // GitLab uses 401 when a token owner cannot approve, including the author's permanent restriction.
    @Test
    void approveSurfacesTheReasonOnUnauthorized() {
        stubScope();
        gitlab.enqueue(new MockResponse.Builder()
                .code(401)
                .body("{\"message\":\"401 Unauthorized\"}")
                .build());

        assertThatThrownBy(() -> channel.approve(gitlabTarget(), null))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("group/project!42")
                .hasMessageContaining("may not approve");
    }

    @Test
    void approveThrowsOnServerError() {
        stubScope();
        gitlab.enqueue(new MockResponse.Builder().code(500).build());

        assertThatThrownBy(() -> channel.approve(gitlabTarget(), null)).isInstanceOf(FeedbackDeliveryException.class);
    }

    @Test
    void approveDoesNotPostTheMessageWhenTheApprovalFailed() {
        stubScope();
        gitlab.enqueue(new MockResponse.Builder().code(401).build());

        assertThatThrownBy(() -> channel.approve(gitlabTarget(), "looks good"))
                .isInstanceOf(FeedbackDeliveryException.class);

        verify(feedbackChannel, never()).postSummary(any(), any());
    }

    // GitLab approval accepts no body; explanatory text requires a separate note.
    @Test
    void approvePostsTheMessageAsASeparateNote() {
        stubScope();
        gitlab.enqueue(new MockResponse.Builder().code(201).body("{\"id\":1}").build());

        channel.approve(gitlabTarget(), "looks good");

        ArgumentCaptor<SummaryContent> content = ArgumentCaptor.forClass(SummaryContent.class);
        verify(feedbackChannel).postSummary(any(), content.capture());
        assertThat(content.getValue().body()).isEqualTo("looks good");
    }

    @Test
    void approveThrowsOnRateLimit() {
        when(gitLabProvider.isRateLimitCritical(1L)).thenReturn(true);

        assertThatThrownBy(() -> channel.approve(gitlabTarget(), null))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("rate limit is critical");
    }

    @Test
    void shouldBlockBeforeTokenOrHttpCallsWhenSilentModeIsEngaged() {
        doThrow(new OutboundEgressSuppressedException("test"))
                .when(egressGuard)
                .requireDeliveryAllowed("gitlab.approve");

        assertThatThrownBy(() -> channel.approve(gitlabTarget(), null))
                .isInstanceOf(OutboundEgressSuppressedException.class);
        verifyNoInteractions(tokenService, feedbackChannel);
        assertThat(gitlab.getRequestCount()).isZero();
    }

    private static FeedbackTarget gitlabTarget() {
        return ScmFeedbackFixtures.target(
                new IntegrationRef(IntegrationKind.GITLAB, 1L, null), "group/project!42", null);
    }
}
