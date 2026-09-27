package de.tum.cit.aet.hephaestus.integration.scm.domain.workurl;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workurl.ReviewedWorkUrls.Page;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workurl.ReviewedWorkUrls.WorkAddress;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The address grammar is the first fence between a browser tab and a workspace's mirrored work: anything
 * it lets through ambiguously could name another project's work, so every rejection here is deliberate.
 */
class ReviewedWorkUrlsTest extends BaseUnitTest {

    @Test
    void shouldReadNumericCommentIdentityOnlyForTheMatchingProvider() {
        assertThat(ReviewedWorkUrls.commentNativeId(IntegrationKind.GITLAB, "gid://gitlab/Note/123"))
                .contains(123L);
        assertThat(ReviewedWorkUrls.commentNativeId(IntegrationKind.GITHUB, "gid://gitlab/Note/123"))
                .isEmpty();
        assertThat(ReviewedWorkUrls.commentNativeId(null, "gid://gitlab/Note/123"))
                .isEmpty();
        assertThat(ReviewedWorkUrls.commentNativeId(IntegrationKind.GITLAB, null))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "123",
                "opaque-node-id",
                "gid://gitlab/Note/0",
                "gid://gitlab/Note/01",
                "gid://gitlab/Note/-1",
                "gid://gitlab/Note/9223372036854775808",
                "gid://gitlab/Note/123/456"
            })
    void shouldRejectMalformedOrUnrepresentableCommentIdentities(String ref) {
        assertThat(ReviewedWorkUrls.commentNativeId(IntegrationKind.GITLAB, ref))
                .isEmpty();
    }

    @Nested
    @DisplayName("Structure and origin")
    class Structure {

        @Test
        void shouldNormalizeSchemeHostAndDefaultPortWhenReadingAPage() {
            Page page = ReviewedWorkUrls.page("HTTPS://GitHub.COM:443/octo/repo/pull/5")
                    .orElseThrow();

            assertThat(page.origin()).isEqualTo("https://github.com");
            assertThat(page.segments()).containsExactly("octo", "repo", "pull", "5");
        }

        @Test
        void shouldKeepANonDefaultPortWhenReadingAPage() {
            assertThat(ReviewedWorkUrls.page("https://git.example.test:8443/g/p/-/issues/1")
                            .map(Page::origin))
                    .contains("https://git.example.test:8443");
        }

        @Test
        void shouldIgnoreQueryAndFragmentWhenReadingAPage() {
            Page page = ReviewedWorkUrls.page("https://github.com/octo/repo/pull/5?diff=split&x={y}|z#discussion_r1")
                    .orElseThrow();

            assertThat(page.segments()).containsExactly("octo", "repo", "pull", "5");
        }

        @Test
        void shouldDecodeEachSegmentOnceWithoutTurningPlusIntoSpaceWhenReadingAPage() {
            Page page = ReviewedWorkUrls.page("https://gitlab.example.test/my%2Dgroup/c++/-/issues/2")
                    .orElseThrow();

            assertThat(page.segments()).containsExactly("my-group", "c++", "-", "issues", "2");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "",
                    "github.com/octo/repo/pull/5",
                    "/octo/repo/pull/5",
                    "http://github.com/octo/repo/pull/5",
                    "ftp://github.com/octo/repo/pull/5",
                    "https:octo/repo/pull/5",
                    "https://user:secret@github.com/octo/repo/pull/5",
                    "https://user@github.com/octo/repo/pull/5",
                    "https://github.com",
                    "https://github.com/",
                    "https://github.com:0/octo/repo/pull/5",
                    "https://github.com:99999/octo/repo/pull/5",
                    "https://exa_mple.test/octo/repo/pull/5",
                    "https://github.com/octo\\repo/pull/5",
                    "https://github.com/octo/repo/pull/5\n",
                    "https://github.com/octo/repo/pull/5\u0000",
                    "https://github.com/octo//repo/pull/5",
                    "https://github.com/octo/repo/pull/5//",
                    "https://github.com/octo/./repo/pull/5",
                    "https://github.com/octo/../repo/pull/5",
                    "https://github.com/octo/%2E%2E/repo/pull/5",
                    "https://github.com/octo%2Frepo/x/pull/5",
                    "https://github.com/octo%2frepo/x/pull/5",
                    "https://github.com/octo%5Crepo/x/pull/5",
                    "https://github.com/octo%00/repo/pull/5",
                    "https://github.com/octo%0A/repo/pull/5",
                    "https://github.com/octo%252Frepo/x/pull/5",
                    "https://github.com/octo%2/repo/pull/5",
                    "https://github.com/octo%zz/repo/pull/5",
                    "https://github.com/octo%C3/repo/pull/5",
                    "https://github.com/oc to/repo/pull/5",
                })
        void shouldRefuseAStructurallyUnsoundAddressWhenReadingAPage(String url) {
            assertThat(ReviewedWorkUrls.page(url)).isEmpty();
        }

        @Test
        void shouldReduceAConfiguredServerToTheSameOriginAPageHasWhenBothNameIt() {
            assertThat(ReviewedWorkUrls.configuredOrigin("https://GitLab.Example.test/"))
                    .contains("https://gitlab.example.test");
            assertThat(ReviewedWorkUrls.configuredOrigin("https://gitlab.example.test:443"))
                    .contains("https://gitlab.example.test");
            assertThat(ReviewedWorkUrls.configuredOrigin("https://gitlab.example.test:8443"))
                    .contains("https://gitlab.example.test:8443");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "http://gitlab.example.test",
                    "https://gitlab.example.test/gitlab",
                    "https://token@gitlab.example.test",
                    "https://gitlab.example.test?x=1",
                    "gitlab.example.test",
                    "not a url",
                })
        void shouldHaveNoOriginWhenTheConfiguredServerIsNotABareHttpsOrigin(String serverUrl) {
            assertThat(ReviewedWorkUrls.configuredOrigin(serverUrl)).isEmpty();
        }

        /** A host is compared whole: a lookalike that merely ends in the configured host is another server. */
        @Test
        void shouldNotMatchAHostThatOnlySharesASuffixWhenComparingOrigins() {
            String page = ReviewedWorkUrls.page("https://evil-github.com/octo/repo/pull/5")
                    .orElseThrow()
                    .origin();

            assertThat(ReviewedWorkUrls.configuredOrigin("https://github.com")).isNotEqualTo(Optional.of(page));
        }
    }

    @Nested
    @DisplayName("GitHub")
    class GitHub {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "https://github.com/octo/repo/pull/5",
                    "https://github.com/octo/repo/pull/5/",
                    "https://github.com/octo/repo/pull/5/files",
                    "https://github.com/octo/repo/pull/5/changes",
                    "https://github.com/octo/repo/pull/5/commits",
                    "https://github.com/octo/repo/pull/5/checks",
                    "https://github.com/octo/repo/pull/5/commits/0123abc",
                    "https://github.com/octo/repo/pull/5/commits/0123456789abcdef0123456789abcdef01234567",
                    "https://github.com/octo/repo/pull/5#issuecomment-1",
                })
        void shouldReadAPullRequestWhenThePageIsOneOfItsTabs(String url) {
            assertThat(gitHub(url)).contains(new WorkAddress("octo/repo", ScmSignals.PULL_REQUEST, 5));
        }

        @Test
        void shouldReadAnIssueWhenThePageIsTheIssue() {
            assertThat(gitHub("https://github.com/octo/repo/issues/12/"))
                    .contains(new WorkAddress("octo/repo", ScmSignals.ISSUE, 12));
        }

        @Test
        void shouldKeepDotsAndHyphensInsideNamesWhenReadingAGitHubPage() {
            assertThat(gitHub("https://ghe.example.test/my-org/repo.name.js/pull/3"))
                    .contains(new WorkAddress("my-org/repo.name.js", ScmSignals.PULL_REQUEST, 3));
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "https://github.com/octo/repo",
                    "https://github.com/octo/repo/pulls",
                    "https://github.com/octo/repo/pulls/5",
                    "https://github.com/octo/repo/compare/main...feature",
                    "https://github.com/octo/repo/pull/new/feature",
                    "https://github.com/octo/repo/issues/new",
                    "https://github.com/octo/repo/pull/5.diff",
                    "https://github.com/octo/repo/pull/5.patch",
                    "https://github.com/octo/repo/pull/5abc",
                    "https://github.com/octo/repo/pull/0",
                    "https://github.com/octo/repo/pull/-5",
                    "https://github.com/octo/repo/pull/05",
                    "https://github.com/octo/repo/pull/2147483648",
                    "https://github.com/octo/repo/pull/99999999999",
                    "https://github.com/octo/repo/pull/5/unknown",
                    "https://github.com/octo/repo/pull/5/files/extra",
                    "https://github.com/octo/repo/pull/5/commits/not-a-sha",
                    "https://github.com/octo/repo/issues/5/files",
                    "https://github.com/octo/repo/discussions/5",
                    "https://github.com/octo/group/repo/pull/5",
                    "https://github.com/octo/repo/-/merge_requests/5",
                })
        void shouldRefuseARouteThatIsNotAPullRequestOrIssueWhenReadingAGitHubPage(String url) {
            assertThat(gitHub(url)).isEmpty();
        }

        @Test
        void shouldReadTheLargestIntNumberWhenItFits() {
            assertThat(gitHub("https://github.com/octo/repo/issues/2147483647"))
                    .contains(new WorkAddress("octo/repo", ScmSignals.ISSUE, Integer.MAX_VALUE));
        }

        private Optional<WorkAddress> gitHub(String url) {
            return ReviewedWorkUrls.workAddress(
                    IntegrationKind.GITHUB, ReviewedWorkUrls.page(url).orElseThrow());
        }
    }

    @Nested
    @DisplayName("GitLab")
    class GitLab {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "https://gitlab.example.test/group/project/-/merge_requests/5",
                    "https://gitlab.example.test/group/project/-/merge_requests/5/",
                    "https://gitlab.example.test/group/project/-/merge_requests/5/diffs",
                    "https://gitlab.example.test/group/project/-/merge_requests/5/commits",
                    "https://gitlab.example.test/group/project/-/merge_requests/5/pipelines",
                    "https://gitlab.example.test/group/project/-/merge_requests/5/reports",
                    "https://gitlab.example.test/group/project/-/merge_requests/5?tab=diffs#note_9",
                })
        void shouldReadAMergeRequestWhenThePageIsOneOfItsTabs(String url) {
            assertThat(gitLab(url)).contains(new WorkAddress("group/project", ScmSignals.PULL_REQUEST, 5));
        }

        /** Issues and work items share one project IID namespace; the same number is the same issue. */
        @ParameterizedTest
        @ValueSource(
                strings = {
                    "https://gitlab.example.test/group/project/-/issues/5",
                    "https://gitlab.example.test/group/project/-/work_items/5",
                    "https://gitlab.example.test/group/project/-/work_items/5/",
                })
        void shouldReadAnIssueWhenThePageIsAnIssueOrAProjectWorkItem(String url) {
            assertThat(gitLab(url)).contains(new WorkAddress("group/project", ScmSignals.ISSUE, 5));
        }

        @Test
        void shouldKeepEveryNestedSubgroupWhenReadingAGitLabPage() {
            assertThat(gitLab("https://gitlab.example.test/top/sub/deeper/my.project-x/-/merge_requests/7"))
                    .contains(new WorkAddress("top/sub/deeper/my.project-x", ScmSignals.PULL_REQUEST, 7));
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "https://gitlab.example.test/project/-/issues/5",
                    "https://gitlab.example.test/-/issues/5",
                    "https://gitlab.example.test/groups/group/-/work_items/5",
                    "https://gitlab.example.test/groups/group/sub/-/work_items/5",
                    "https://gitlab.example.test/groups/group/-/epics/5",
                    "https://gitlab.example.test/group/project/-/epics/5",
                    "https://gitlab.example.test/group/project/merge_requests/5",
                    "https://gitlab.example.test/group/project/-/merge_requests",
                    "https://gitlab.example.test/group/project/-/merge_requests/new",
                    "https://gitlab.example.test/group/project/-/merge_requests/5/unknown",
                    "https://gitlab.example.test/group/project/-/merge_requests/5/diffs/extra",
                    "https://gitlab.example.test/group/project/-/issues/5/designs",
                    "https://gitlab.example.test/group/project/-/work_items/5/diffs",
                    "https://gitlab.example.test/group/project/-/issues/0",
                    "https://gitlab.example.test/group/project/-/issues/5x",
                    "https://gitlab.example.test/group/project/-/issues/3000000000",
                    "https://gitlab.example.test/group/project/-/merge_requests/5.diff",
                    "https://gitlab.example.test/group/project/-/merge_requests/5.patch",
                    "https://gitlab.example.test/group/project/pull/5",
                })
        void shouldRefuseARouteThatIsNotAProjectMergeRequestOrIssueWhenReadingAGitLabPage(String url) {
            assertThat(gitLab(url)).isEmpty();
        }

        private Optional<WorkAddress> gitLab(String url) {
            return ReviewedWorkUrls.workAddress(
                    IntegrationKind.GITLAB, ReviewedWorkUrls.page(url).orElseThrow());
        }
    }

    @Test
    void shouldReadNothingWhenTheProviderHasNoPageGrammar() {
        Page page = new Page("https://slack.com", List.of("octo", "repo", "pull", "5"));

        assertThat(ReviewedWorkUrls.workAddress(IntegrationKind.SLACK, page)).isEmpty();
    }
}
