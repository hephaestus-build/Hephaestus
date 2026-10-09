package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GraphQlResponses;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

/** What one read of a merge request tells about its readiness, from GitLab's real answer shapes and errors. */
class GitLabMergeRequestReadinessReaderTest extends BaseUnitTest {

    private static final String HEAD = "a".repeat(40);

    @Test
    void shouldReadTheMergeRequestItsProjectHeadAndApprovers() {
        GitLabMergeRequestReadinessReader.Facts facts = decode(mergeRequest(), List.of());

        assertThat(facts.projectNativeId()).isEqualTo(246765L);
        assertThat(facts.mergeRequestNativeId()).isEqualTo(334054L);
        assertThat(facts.state()).isEqualTo("opened");
        assertThat(facts.updatedAt()).isEqualTo(Instant.parse("2026-09-30T10:00:00Z"));
        assertThat(facts.headSha()).isEqualTo(HEAD);
        assertThat(facts.mergeable()).isTrue();
        assertThat(facts.detailedMergeStatus()).isEqualTo("MERGEABLE");
        assertThat(facts.approved()).isTrue();
        assertThat(facts.headPipeline()).isEqualTo(GitLabHeadPipeline.reported("SUCCESS", HEAD));
        assertThat(facts.approvers())
                .extracting(GitLabMergeRequestProcessor.SyncUserData::username)
                .containsExactly("tutor");
        assertThat(facts.reviewers())
                .singleElement()
                .satisfies(reviewer -> assertThat(reviewer.reviewState()).isEqualTo("APPROVED"));
    }

    @Test
    void shouldReadOneCompleteDiffPairWithoutInferringABaseWhenGitLabIsStillPreparingIt() {
        var ready = mergeRequest();
        ready.put("diffRefs", Map.of("headSha", HEAD, "baseSha", "b".repeat(40)));
        assertThat(decode(ready, List.of()).diffRefs())
                .isEqualTo(new GitLabMergeRequestReadinessReader.DiffRefs(HEAD, "b".repeat(40)));
        var pending = mergeRequest();
        pending.put("diffRefs", null);
        assertThat(decode(pending, List.of()).diffRefs()).isNull();
        var unpaired = mergeRequest();
        unpaired.put("diffRefs", Map.of("headSha", "c".repeat(40), "baseSha", "b".repeat(40)));
        assertThat(decode(unpaired, List.of()).diffRefs()).isNull();
        var failed = mergeRequest();
        failed.put("diffRefs", Map.of("headSha", HEAD, "baseSha", "b".repeat(40)));
        assertThat(decode(failed, List.of(error("diffRefs", "baseSha"))).diffRefs())
                .isNull();
    }

    @Test
    void shouldCarryGitLabsBotFlagOfEachApproverAndLeaveItUnknownWhereTheReadLacksIt() {
        Map<String, @Nullable Object> node = mergeRequest();
        Map<String, @Nullable Object> tutor = user(90393, "heph_introcourse_tutor_e2e");
        tutor.put("bot", true);
        Map<String, @Nullable Object> student = user(18024, "ga84xah");
        student.put("bot", false);
        node.put("approvedBy", connection(3, false, List.of(tutor, student, user(7, "tutor"))));

        assertThat(decode(node, List.of()).approvers())
                .extracting(GitLabMergeRequestProcessor.SyncUserData::bot)
                .containsExactly(true, false, null);
    }

    @Test
    void shouldTellNoPipelineFromASkippedOne() {
        Map<String, @Nullable Object> none = mergeRequest();
        none.put("headPipeline", null);
        Map<String, @Nullable Object> skipped = mergeRequest();
        skipped.put("headPipeline", Map.of("status", "SKIPPED", "sha", HEAD));

        assertThat(decode(none, List.of()).headPipeline()).isEqualTo(GitLabHeadPipeline.NO_PIPELINE);
        assertThat(decode(skipped, List.of()).headPipeline()).isEqualTo(GitLabHeadPipeline.reported("SKIPPED", HEAD));
    }

    @Test
    void shouldCaptureNoPipelineWhereTheFieldOrItsStatusOrShaFailedToLoad() {
        Map<String, @Nullable Object> failed = mergeRequest();
        failed.put("headPipeline", null);
        Map<String, @Nullable Object> statusFailed = mergeRequest();
        Map<String, @Nullable Object> pipeline = new HashMap<>();
        pipeline.put("status", null);
        pipeline.put("sha", HEAD);
        statusFailed.put("headPipeline", pipeline);
        Map<String, @Nullable Object> shaFailed = mergeRequest();
        Map<String, @Nullable Object> withoutSha = new HashMap<>();
        withoutSha.put("status", "FAILED");
        withoutSha.put("sha", null);
        shaFailed.put("headPipeline", withoutSha);

        assertThat(decode(failed, List.of(error("headPipeline"))).headPipeline())
                .isEqualTo(GitLabHeadPipeline.NOT_CAPTURED);
        assertThat(decode(statusFailed, List.of(error("headPipeline", "status")))
                        .headPipeline())
                .isEqualTo(GitLabHeadPipeline.NOT_CAPTURED);
        assertThat(decode(shaFailed, List.of(error("headPipeline", "sha"))).headPipeline())
                .isEqualTo(GitLabHeadPipeline.NOT_CAPTURED);
    }

    @Test
    void shouldLeaveApprovalsUnknownWhereTheyFailedToLoadOrRunPastOnePage() {
        Map<String, @Nullable Object> failed = mergeRequest();
        failed.put("approvedBy", null);
        Map<String, @Nullable Object> longer = mergeRequest();
        longer.put("approvedBy", connection(21, true, List.of(user(7, "tutor"))));
        Map<String, @Nullable Object> empty = mergeRequest();
        empty.put("approvedBy", connection(0, false, List.of()));

        assertThat(decode(failed, List.of(error("approvedBy"))).approvers()).isNull();
        assertThat(decode(longer, List.of()).approvers()).isNull();
        assertThat(decode(empty, List.of()).approvers())
                .as("GitLab listing nobody is an answer")
                .isEmpty();
    }

    @Test
    void shouldLeaveTheMergeStatusUnknownWhereItFailedToLoad() {
        Map<String, @Nullable Object> failed = mergeRequest();
        failed.put("detailedMergeStatus", null);

        assertThat(decode(failed, List.of(error("detailedMergeStatus"))).detailedMergeStatus())
                .isNull();
    }

    @Test
    void shouldReadWhoMergedWhenAndTheCommitAndLeaveAFailedOrMissingOneUnknown() {
        Map<String, @Nullable Object> merged = mergeRequest();
        merged.put("state", "merged");
        merged.put("mergeUser", user(7, "tutor"));
        merged.put("mergedAt", "2026-09-30T10:05:00Z");
        merged.put("mergeCommitSha", "b".repeat(40));
        Map<String, @Nullable Object> failedMerger = mergeRequest();
        failedMerger.put("state", "merged");
        failedMerger.put("mergeUser", null);
        failedMerger.put("mergedAt", "2026-09-30T10:05:00Z");
        // A fast-forward merge leaves no merge commit.
        failedMerger.put("mergeCommitSha", null);

        GitLabMergeRequestReadinessReader.Merge merge =
                decode(merged, List.of()).merge();
        assertThat(Objects.requireNonNull(merge.user()).username()).isEqualTo("tutor");
        assertThat(merge.mergedAt()).isEqualTo(Instant.parse("2026-09-30T10:05:00Z"));
        assertThat(merge.commitSha()).isEqualTo("b".repeat(40));
        GitLabMergeRequestReadinessReader.Merge unknown =
                decode(failedMerger, List.of(error("mergeUser"))).merge();
        assertThat(unknown.user()).isNull();
        assertThat(unknown.commitSha()).isNull();
        assertThat(unknown.mergedAt()).isEqualTo(Instant.parse("2026-09-30T10:05:00Z"));
    }

    @Test
    void shouldDescribeNothingWithoutTheHeadOrTheMergeRequest() {
        Map<String, @Nullable Object> headless = mergeRequest();
        headless.put("diffHeadSha", null);

        assertThat(GitLabMergeRequestReadinessReader.decode(response(headless, List.of(error("diffHeadSha")))))
                .isNull();
        assertThat(GitLabMergeRequestReadinessReader.decode(response(null, List.of())))
                .isNull();
    }

    @Test
    void shouldReadNothingWhenGitLabCannotBeReached() {
        GitLabGraphQlClientProvider provider = mock(GitLabGraphQlClientProvider.class);
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec request = mock(GraphQlClient.RequestSpec.class);
        when(provider.forScope(1L)).thenReturn(client);
        when(client.documentName(anyString())).thenReturn(request);
        when(request.variable(anyString(), any())).thenReturn(request);
        IllegalStateException unreachable = new IllegalStateException("connection refused");
        when(request.execute()).thenReturn(Mono.error(unreachable));
        GitLabMergeRequestReadinessReader reader = new GitLabMergeRequestReadinessReader(
                provider,
                new GitLabGraphQlResponseHandler(mock(GitLabExceptionClassifier.class)),
                new GitLabProperties(
                        "https://gitlab.lrz.de",
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60),
                        Duration.ZERO,
                        Duration.ofMinutes(5)),
                mock(GitLabApprovalClient.class));

        assertThat(reader.read(1L, "hephaestustest/demo-repository", 4)).isNull();
        verify(provider).recordFailure(unreachable);
    }

    private static GitLabMergeRequestReadinessReader.Facts decode(
            Map<String, @Nullable Object> mergeRequest, List<Map<String, ?>> errors) {
        return Objects.requireNonNull(GitLabMergeRequestReadinessReader.decode(response(mergeRequest, errors)));
    }

    private static ClientGraphQlResponse response(
            @Nullable Map<String, @Nullable Object> mergeRequest, List<Map<String, ?>> errors) {
        Map<String, @Nullable Object> project = new HashMap<>();
        project.put("id", "gid://gitlab/Project/246765");
        project.put("mergeRequest", mergeRequest);
        assertVendorCouldReturn(GITLAB, GitLabMergeRequestReadinessReader.DOCUMENT, "project", project);
        return GraphQlResponses.of(Map.of("project", project), errors);
    }

    /** An error GitLab reports at {@code field} of the merge request. */
    private static Map<String, ?> error(Object... field) {
        Object[] path = new Object[field.length + 2];
        path[0] = "project";
        path[1] = "mergeRequest";
        System.arraycopy(field, 0, path, 2, field.length);
        return GraphQlResponses.error("Internal server error", path);
    }

    private static Map<String, @Nullable Object> mergeRequest() {
        Map<String, @Nullable Object> node = new HashMap<>();
        node.put("id", "gid://gitlab/MergeRequest/334054");
        node.put("iid", "4");
        node.put("state", "opened");
        node.put("updatedAt", "2026-09-30T10:00:00Z");
        node.put("diffHeadSha", HEAD);
        node.put("mergeable", true);
        node.put("detailedMergeStatus", "MERGEABLE");
        node.put("approved", true);
        node.put("headPipeline", Map.of("status", "SUCCESS", "sha", HEAD));
        Map<String, @Nullable Object> reviewer = user(7, "tutor");
        reviewer.put("mergeRequestInteraction", Map.of("reviewState", "APPROVED"));
        node.put("reviewers", connection(1, false, List.of(reviewer)));
        node.put("approvedBy", connection(1, false, List.of(user(7, "tutor"))));
        return node;
    }

    private static Map<String, @Nullable Object> user(long id, String username) {
        Map<String, @Nullable Object> user = new HashMap<>();
        user.put("id", "gid://gitlab/User/" + id);
        user.put("username", username);
        return user;
    }

    private static Map<String, Object> connection(int count, boolean hasNextPage, List<?> nodes) {
        Map<String, @Nullable Object> pageInfo = new HashMap<>();
        pageInfo.put("hasNextPage", hasNextPage);
        pageInfo.put("endCursor", hasNextPage ? "next" : null);
        return Map.of("count", count, "pageInfo", pageInfo, "nodes", nodes);
    }
}
