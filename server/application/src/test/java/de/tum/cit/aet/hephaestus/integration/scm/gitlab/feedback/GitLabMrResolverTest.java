package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.MrCoordinates;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.MrInfo;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

/**
 * Pins {@link GitLabMrResolver}'s own branching: the pure external-id parsers (every malformed shape →
 * {@link FeedbackDeliveryException}) and the resolve / resolveIssueGid round-trips (null response, MR/issue
 * not found, and the documented null-diffRefs case). Every caller test mocks this resolver, so without this
 * suite its error and null-tolerance paths are unexercised.
 */
class GitLabMrResolverTest extends BaseUnitTest {

    @Mock
    private GitLabGraphQlClientProvider gitLabProvider;

    private GitLabMrResolver resolver() {
        return new GitLabMrResolver(gitLabProvider);
    }

    @Test
    void parseSubjectExternalId_splitsPathAndIid() {
        MrCoordinates coords = GitLabMrResolver.parseSubjectExternalId("group/sub/project!42");
        assertThat(coords.projectPath()).isEqualTo("group/sub/project");
        assertThat(coords.iid()).isEqualTo(42);
    }

    @ParameterizedTest
    @ValueSource(strings = {"  ", "no-separator", "!42", "path!", "path!x", "path#42"})
    void parseSubjectExternalId_rejectsMalformed(String raw) {
        assertThatThrownBy(() -> GitLabMrResolver.parseSubjectExternalId(raw))
                .isInstanceOf(FeedbackDeliveryException.class);
    }

    @Test
    void parseSubjectExternalId_rejectsNull() {
        assertThatThrownBy(() -> GitLabMrResolver.parseSubjectExternalId(null))
                .isInstanceOf(FeedbackDeliveryException.class);
    }

    @Test
    void parseIssueSubjectExternalId_splitsPathAndIid() {
        MrCoordinates coords = GitLabMrResolver.parseIssueSubjectExternalId("group/project#7");
        assertThat(coords.projectPath()).isEqualTo("group/project");
        assertThat(coords.iid()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"  ", "no-separator", "#7", "path#", "path#x", "path!7"})
    void parseIssueSubjectExternalId_rejectsMalformed(String raw) {
        assertThatThrownBy(() -> GitLabMrResolver.parseIssueSubjectExternalId(raw))
                .isInstanceOf(FeedbackDeliveryException.class);
    }

    @Test
    void resolve_nullResponseThrows() {
        stubExecute(Mono.empty());
        assertThatThrownBy(() -> resolver().resolve(1L, "group/project", 42))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("Null response");
    }

    @Test
    void resolve_missingGlobalIdThrowsWithErrors() {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        stubField(response, "project.mergeRequest.id", null);
        when(response.getErrors()).thenReturn(List.of(mock(ResponseError.class)));
        stubExecute(Mono.just(response));

        assertThatThrownBy(() -> resolver().resolve(1L, "group/project", 42))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("MR not found")
                .hasMessageContaining("errors=");
    }

    @Test
    void resolve_returnsMrInfoWithNullDiffRefsWhenAbsent() {
        // Documented case: an MR with no diffs yet returns null base/head/start SHAs into MrInfo.
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        stubField(response, "project.mergeRequest.id", "gid://gitlab/MergeRequest/9");
        stubField(response, "project.mergeRequest.diffRefs.baseSha", null);
        stubField(response, "project.mergeRequest.diffRefs.headSha", null);
        stubField(response, "project.mergeRequest.diffRefs.startSha", null);
        stubExecute(Mono.just(response));

        MrInfo info = resolver().resolve(1L, "group/project", 42);

        assertThat(info.globalId()).isEqualTo("gid://gitlab/MergeRequest/9");
        assertThat(info.baseSha()).isNull();
        assertThat(info.headSha()).isNull();
        assertThat(info.startSha()).isNull();
    }

    @Test
    void resolveIssueGid_nullResponseThrows() {
        stubExecute(Mono.empty());
        assertThatThrownBy(() -> resolver().resolveIssueGid(1L, "group/project", 7))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("Null response");
    }

    @Test
    void resolveIssueGid_missingGidThrows() {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        stubField(response, "project.issue.id", null);
        when(response.getErrors()).thenReturn(List.of());
        stubExecute(Mono.just(response));

        assertThatThrownBy(() -> resolver().resolveIssueGid(1L, "group/project", 7))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("Issue not found");
    }

    @Test
    void resolveIssueGid_returnsGid() {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        stubField(response, "project.issue.id", "gid://gitlab/Issue/55");
        stubExecute(Mono.just(response));

        assertThat(resolver().resolveIssueGid(1L, "group/project", 7)).isEqualTo("gid://gitlab/Issue/55");
    }

    private void stubExecute(Mono<ClientGraphQlResponse> result) {
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitLabProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        when(spec.execute()).thenReturn(result);
    }

    private static void stubField(ClientGraphQlResponse response, String path, @Nullable String value) {
        ClientResponseField field = mock(ClientResponseField.class);
        when(field.<String>getValue()).thenReturn(value);
        when(response.field(path)).thenReturn(field);
    }
}
