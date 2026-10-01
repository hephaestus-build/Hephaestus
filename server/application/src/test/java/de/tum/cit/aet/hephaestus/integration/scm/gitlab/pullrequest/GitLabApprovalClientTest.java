package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class GitLabApprovalClientTest extends BaseUnitTest {
    private static final Instant APPROVED_AT = Instant.parse("2026-10-01T14:11:19.087Z");
    private static final List<GitLabMergeRequestProcessor.SyncUserData> APPROVERS =
            List.of(new GitLabMergeRequestProcessor.SyncUserData(
                    "gid://gitlab/User/90393", "tutor", null, null, null, null, true));

    @Test
    void shouldDecodeTheNativeStandingRowDateThroughTheAuthenticatedProjectRoute() {
        var tokens = mock(GitLabTokenService.class);
        when(tokens.resolveServerUrl(9L)).thenReturn("https://gitlab.example");
        when(tokens.getAccessToken(9L)).thenReturn("test-token");
        var client = new GitLabApprovalClient(tokens, WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().toString())
                    .isEqualTo("https://gitlab.example/api/v4/projects/273327/merge_requests/11/approvals");
            assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-token");
            assertThat(request.attribute(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE))
                    .contains(9L);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("""
                            {"id":384516,"iid":11,"project_id":273327,"state":"merged",
                             "updated_at":"2026-10-01T14:43:39.777Z","title":"Checklist","description":"Why",
                             "approved_by":[
                             {"user":{"id":90393,"username":"tutor"},"approved_at":"2026-10-01T14:11:19.087Z"}]}
                            """)
                    .build());
        }));

        var snapshot = Objects.requireNonNull(client.read(9L, 273327, 11));
        assertThat(snapshot.updatedAt()).isEqualTo(Instant.parse("2026-10-01T14:43:39.777Z"));
        assertThat(snapshot.title()).isEqualTo("Checklist");
        assertThat(snapshot.description()).isEqualTo("Why");
        assertThat(snapshot.datesFor(273327, 384516, 11, APPROVERS))
                .containsExactlyEntriesOf(Map.of(90393L, APPROVED_AT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"approved_by\":[{\"user\":{\"id\":90393},\"approved_at\":\"not-a-date\"}]}", "not-json"})
    void shouldLeaveAResponseThatCannotBeDecodedUnread(String body) {
        var tokens = mock(GitLabTokenService.class);
        when(tokens.resolveServerUrl(9L)).thenReturn("https://gitlab.example");
        when(tokens.getAccessToken(9L)).thenReturn("test-token");
        var client = new GitLabApprovalClient(
                tokens,
                WebClient.builder()
                        .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                                .body(body)
                                .build())));

        assertThat(client.read(9L, 273327, 11)).isNull();
    }

    @Test
    void shouldAcceptAbsentCommunityEditionMetadataButNotContraryIdentityOrAnUnmatchedApproverSet() {
        var row = new GitLabApprovalClient.Approval(new GitLabApprovalClient.Approver(90393L), APPROVED_AT);
        assertThat(new GitLabApprovalClient.Snapshot(null, null, null, null, null, null, null, List.of(row))
                        .datesFor(273327, 384516, 11, APPROVERS))
                .containsEntry(90393L, APPROVED_AT);
        for (var snapshot : List.of(
                new GitLabApprovalClient.Snapshot(384517L, 11, 273327L, "merged", null, null, null, List.of(row)),
                new GitLabApprovalClient.Snapshot(384516L, 12, 273327L, "merged", null, null, null, List.of(row)),
                new GitLabApprovalClient.Snapshot(384516L, 11, 273328L, "merged", null, null, null, List.of(row)),
                new GitLabApprovalClient.Snapshot(384516L, 11, 273327L, "opened", null, null, null, List.of(row)),
                new GitLabApprovalClient.Snapshot(384516L, 11, 273327L, "merged", null, null, null, List.of()))) {
            assertThat(snapshot.datesFor(273327, 384516, 11, APPROVERS)).isNull();
        }
    }

    @Test
    void shouldTreatAbsentAndConflictingDatesAsUnknown() {
        var known = new GitLabApprovalClient.Approval(new GitLabApprovalClient.Approver(90393L), APPROVED_AT);
        var unknown = new GitLabApprovalClient.Approval(new GitLabApprovalClient.Approver(90393L), null);
        assertThat(new GitLabApprovalClient.Snapshot(null, null, null, null, null, null, null, List.of(unknown))
                        .datesFor(273327, 384516, 11, APPROVERS))
                .isEmpty();
        assertThat(new GitLabApprovalClient.Snapshot(null, null, null, null, null, null, null, List.of(known, unknown))
                        .datesFor(273327, 384516, 11, APPROVERS))
                .isEmpty();
        assertThat(new GitLabApprovalClient.Snapshot(null, null, null, null, null, null, null, List.of(known, known))
                        .datesFor(273327, 384516, 11, APPROVERS))
                .containsEntry(90393L, APPROVED_AT);
    }
}
