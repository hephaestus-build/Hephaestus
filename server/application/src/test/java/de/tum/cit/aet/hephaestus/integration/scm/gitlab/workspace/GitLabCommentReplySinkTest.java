package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

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
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
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

class GitLabCommentReplySinkTest extends BaseUnitTest {
    private static final String ADDRESS = "gid://gitlab/MergeRequest/7 gid://gitlab/Discussion/abc123";

    @Mock
    private GitLabGraphQlClientProvider provider;

    @Mock
    private OutboundEgressGuard egressGuard;

    private GitLabCommentReplySink sink() {
        return new GitLabCommentReplySink(provider, egressGuard, ScmFeedbackFixtures.formatter());
    }

    @Test
    void shouldAddressTheMergeRequestAndTheCommentsDiscussion() {
        assertThat(GitLabCommentReplySink.address(7L, "abc123")).isEqualTo(ADDRESS);
        assertThat(GitLabCommentReplySink.address(null, "abc123")).isNull();
        assertThat(GitLabCommentReplySink.address(7L, null)).isNull();
        assertThat(GitLabCommentReplySink.address(7L, "not a discussion")).isNull();
    }

    @Test
    void shouldAnswerInTheDiscussionInPlainTextWithALinkAndItsMarker() {
        var client = mock(HttpGraphQlClient.class);
        var spec = mock(GraphQlClient.RequestSpec.class);
        var response = mock(ClientGraphQlResponse.class);
        var field = mock(ClientResponseField.class);
        when(provider.forScope(1L)).thenReturn(client);
        when(client.documentName("CreateMergeRequestDiscussionReply")).thenReturn(spec);
        when(spec.variable(anyString(), any())).thenReturn(spec);
        when(spec.execute()).thenReturn(Mono.just(response));
        when(response.isValid()).thenReturn(true);
        when(response.getErrors()).thenReturn(List.of());
        when(response.field("createNote.note.id")).thenReturn(field);
        when(field.getValue()).thenReturn("gid://gitlab/Note/9");

        sink().reply(1L, 42L, ADDRESS, "Nothing changed. Ask @someone");

        verify(spec).variable("noteableId", "gid://gitlab/MergeRequest/7");
        verify(spec).variable("discussionId", "gid://gitlab/Discussion/abc123");
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
        sink().reply(1L, 42L, "gid://github/Thread/1", "text");

        verifyNoInteractions(provider, egressGuard);
    }

    @Test
    void shouldPostNothingWhileSilentModeIsEngaged() {
        doThrow(new OutboundEgressSuppressedException("test"))
                .when(egressGuard)
                .requireDeliveryAllowed("gitlab.answer-request-comment");

        sink().reply(1L, 42L, ADDRESS, "text");

        verifyNoInteractions(provider);
    }
}
