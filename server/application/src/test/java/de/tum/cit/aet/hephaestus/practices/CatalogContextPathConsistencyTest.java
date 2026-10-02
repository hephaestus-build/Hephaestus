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

/** Guards static context filenames and retired directory prefixes in authored criteria. */
class CatalogContextPathConsistencyTest extends BaseUnitTest {

    /** Workspace-relative files the ContentSources actually write under {@code context/}. */
    private static final Set<String> REAL_CONTEXT_FILES = Set.of(
            "metadata.json",
            "description.md", // PullRequestContentSource.DESCRIPTION_FILE — the description as written, for quoting
            "change.json", // PullRequestContentSource.CHANGE_FILE — the pinned base and head of the change
            "commits.json", // PullRequestContentSource.COMMITS_FILE — the commits of the change, quotable and citable
            "comments.json",
            "linked_work_items.json", // LinkedWorkItemContentSource.OUTPUT_FILE — resolved linked-issue rows
            "review_threads.json", // ReviewThreadContentSource — review-decision/thread rows
            "general_comments.json", // GeneralReviewCommentContentSource — conversation-tab (non-inline) MR review
            // notes
            "project_inventory.json", // WorkspaceInventoryContentSource.OUTPUT_FILE — whole-project issue/PR index
            "conversation_thread.json", // ConversationThreadContentSource — the ordered human turns of one settled
            // Slack thread
            "document.json", // DocumentContentSource.METADATA_KEY — where the reviewed document lives and who wrote it
            "document.md" // DocumentContentSource.BODY_KEY — the one mirrored wiki document a review is about
            // The patch, its statistics and the changed files are derived in the container under
            // work/change/, never staged under inputs/; the commits are staged, since a quote of a
            // commit message must cite an artifact admission can verify.
            );

    /** Workspace-relative files pi-change.ts derives under {@code work/change/} inside the container. */
    private static final Set<String> REAL_CHANGE_FILES =
            Set.of("diff.patch", "diff_stat.txt", "files.json", "description.authored.md");

    private static final Pattern CONTEXT_PATH = Pattern.compile("context/([a-z_]+\\.[a-z]+)");

    private static final Pattern CHANGE_PATH = Pattern.compile("work/change/([a-z_.]+\\.[a-z]+)");

    private static final Pattern PRECOMPUTE_PATH = Pattern.compile("work/precompute-out/([a-z0-9_.-]+)");

    private static final Pattern SLUG = Pattern.compile("\"slug\"\\s*:\\s*\"([a-z0-9-]+)\"");

    @Test
    @DisplayName("default-catalog.json names no fictional context/target/ paths and every context/ path is real")
    void catalogueContextPathsResolveToRealProviderOutputs() throws IOException {
        String catalogue = readCatalogue();

        assertThat(catalogue)
                .as("criteria must use captured context directories, not retired or fictional wiki prefixes")
                .doesNotContain("context/target/", "context/outline/", "context/wiki/");

        Set<String> cited = new TreeSet<>();
        Matcher m = CONTEXT_PATH.matcher(catalogue);
        while (m.find()) {
            cited.add(m.group(1));
        }
        assertThat(cited)
                .as("catalogue should cite at least the enrichment context files")
                .isNotEmpty();
        assertThat(REAL_CONTEXT_FILES)
                .as("every context/<file> the catalogue cites must be a file a ContentSource emits — cited=%s", cited)
                .containsAll(cited);
        Set<String> citedChange = new TreeSet<>();
        Matcher change = CHANGE_PATH.matcher(catalogue);
        while (change.find()) {
            citedChange.add(change.group(1));
        }
        assertThat(REAL_CHANGE_FILES)
                .as(
                        "every work/change/<file> the catalogue cites must be a file pi-change.ts derives — cited=%s",
                        citedChange)
                .containsAll(citedChange);
    }

    @Test
    @DisplayName("every work/precompute-out/ file the catalogue names is one practice's own precompute output")
    void cataloguePrecomputePathsNameOnePracticesOutput() throws IOException {
        String catalogue = readCatalogue();
        Set<String> outputs = new TreeSet<>();
        Matcher slug = SLUG.matcher(catalogue);
        while (slug.find()) {
            outputs.add(slug.group(1) + ".json");
            outputs.add(slug.group(1) + ".md");
        }
        // The precompute runner writes one JSON and one section per practice and nothing else: a criterion
        // that names another file there sends the model looking for a file that is not written.
        Set<String> cited = new TreeSet<>();
        Matcher precompute = PRECOMPUTE_PATH.matcher(catalogue);
        while (precompute.find()) {
            cited.add(precompute.group(1));
        }
        assertThat(outputs)
                .as(
                        "every work/precompute-out/<file> the catalogue cites must be a practice's output — cited=%s",
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
