package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keystone anti-drift guard for the practice catalogue's references to materialised agent context.
 *
 * <p>Providers materialise their output under {@code inputs/context/}. If the criteria cite a path that
 * does not exist (e.g. the prefix {@code context/target/}), the agent is told to read files that are not
 * there and silently returns NOT_APPLICABLE. Criteria prose is not type-checked, so this test is the
 * contract: every context path the catalogue names MUST be a path the providers actually emit, and the
 * {@code context/target/} prefix must never appear.
 */
class CatalogContextPathConsistencyTest extends BaseUnitTest {

    /** Workspace-relative files the ContentSources actually write under {@code inputs/context/}. */
    private static final Set<String> REAL_CONTEXT_FILES = Set.of(
            "metadata.json",
            "comments.json",
            "diff.patch",
            "diff_summary.md",
            "diff_stat.txt",
            "issue_summary.md",
            // The two raw SQL-only graph projections (the agent cannot get these from the mounted worktree):
            "linked_work_items.json", // LinkedWorkItemContentSource.OUTPUT_FILE — resolved linked-issue rows
            "review_threads.json", // ReviewThreadContentSource — review-decision/thread rows
            "general_comments.json", // GeneralReviewCommentContentSource — conversation-tab (non-inline) MR review
            // notes
            "commits.json", // PullRequestCommitContentSource — the pull request's commits through commit_pull_request
            "project_inventory.json", // WorkspaceInventoryContentSource.OUTPUT_FILE — whole-project issue/PR index
            "conversation_thread.json", // ConversationThreadContentSource — the ordered human turns of one settled
            // Slack thread
            "document.md" // DocumentContentSource.OUTPUT_KEY — the one mirrored wiki document a review is about
            // These must never appear: test_presence.json + branch_graph.json are worktree-derived Transforms,
            // not content; acceptance_criteria.json is emitted by no provider.
            );

    private static final Pattern CONTEXT_PATH = Pattern.compile("inputs/context/([a-z_]+\\.[a-z]+)");

    @Test
    @DisplayName("default-catalog.json names no fictional context/target/ paths and every inputs/context/ path is real")
    void catalogueContextPathsResolveToRealProviderOutputs() throws IOException {
        String catalogue = readCatalogue();

        assertThat(catalogue)
                .as("the dead pre-rename prefix 'context/target/' must never reappear in the catalogue")
                .doesNotContain("context/target/");

        Set<String> cited = new TreeSet<>();
        Matcher m = CONTEXT_PATH.matcher(catalogue);
        while (m.find()) {
            cited.add(m.group(1));
        }
        assertThat(cited)
                .as("catalogue should cite at least the enrichment context files")
                .isNotEmpty();
        assertThat(REAL_CONTEXT_FILES)
                .as(
                        "every inputs/context/<file> the catalogue cites must be a file a ContentSource emits — cited=%s",
                        cited)
                .containsAll(cited);
    }

    private static String readCatalogue() throws IOException {
        try (InputStream in = CatalogContextPathConsistencyTest.class
                .getClassLoader()
                .getResourceAsStream("practices/default-catalog.json")) {
            assertThat(in)
                    .as("practices/default-catalog.json must be on the classpath")
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
