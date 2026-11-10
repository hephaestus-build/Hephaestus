package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

class GitHubCommentReplySinkTest extends BaseUnitTest {
    private static final String ADDRESS = GitHubCommentReplySink.address("acme/widgets", 7);

    @Mock
    private GitHubGraphQlClientProvider provider;

    @Mock
    private GitHubPrNodeIdResolver pullRequestNodes;

    @Mock
    private OutboundEgressGuard egressGuard;

    private GitHubCommentReplySink sink() {
        return new GitHubCommentReplySink(provider, pullRequestNodes, egressGuard, ScmFeedbackFixtures.formatter());
    }

    @Test
    void shouldAnswerOnThePullRequestInPlainTextWithALinkAndItsMarker() {
        var client = mock(HttpGraphQlClient.class);
        var spec = mock(GraphQlClient.RequestSpec.class);
        var response = mock(ClientGraphQlResponse.class);
        var field = mock(ClientResponseField.class);
        when(pullRequestNodes.resolve(1L, "acme", "widgets", 7)).thenReturn("PR_node");
        when(provider.forScope(1L)).thenReturn(client);
        when(client.documentName("AddPullRequestComment")).thenReturn(spec);
        when(spec.variable(anyString(), any())).thenReturn(spec);
        when(spec.execute()).thenReturn(Mono.just(response));
        when(response.isValid()).thenReturn(true);
        when(response.getErrors()).thenReturn(List.of());
        when(response.field("addComment.commentEdge.node.id")).thenReturn(field);
        when(field.getValue()).thenReturn("IC_node");

        sink().reply(1L, 42L, ADDRESS, "Nothing changed. Ask @someone");

        verify(spec).variable("subjectId", "PR_node");
        var body = ArgumentCaptor.forClass(Object.class);
        verify(spec).variable(eq("body"), body.capture());
        assertThat((String) body.getValue())
                .startsWith("Nothing changed.")
                // Posted text: a name never notifies anyone.
                .contains("`@someone`")
                .contains("/feedback/42")
                .endsWith(ScmFeedbackFormatter.REQUEST_ANSWER_MARKER);
    }

    @Test
    void shouldPostNothingForAnAddressItCannotRead() {
        sink().reply(1L, 42L, "gid://gitlab/MergeRequest/7 gid://gitlab/Discussion/abc", "text");

        verifyNoInteractions(provider, pullRequestNodes, egressGuard);
    }

    @Test
    void shouldPostNothingWhileSilentModeIsEngaged() {
        doThrow(new OutboundEgressSuppressedException("test"))
                .when(egressGuard)
                .requireDeliveryAllowed("github.answer-request-comment");

        sink().reply(1L, 42L, ADDRESS, "text");

        verifyNoInteractions(provider, pullRequestNodes);
    }
}
