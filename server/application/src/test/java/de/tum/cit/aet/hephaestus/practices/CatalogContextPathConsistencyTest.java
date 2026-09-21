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
            "description.md", // PullRequestContentSource.DESCRIPTION_FILE — the description as written, for quoting
            "change.json", // PullRequestContentSource.CHANGE_FILE — the pinned base and head of the change
            "comments.json",
            // The raw SQL-only projections (the agent cannot get these from the checkout):
            "linked_work_items.json", // LinkedWorkItemContentSource.OUTPUT_FILE — resolved linked-issue rows
            "review_threads.json", // ReviewThreadContentSource — review-decision/thread rows
            "general_comments.json", // GeneralReviewCommentContentSource — conversation-tab (non-inline) MR review
            // notes
            "project_inventory.json", // WorkspaceInventoryContentSource.OUTPUT_FILE — whole-project issue/PR index
            "conversation_thread.json", // ConversationThreadContentSource — the ordered human turns of one settled
            // Slack thread
            "document.json", // DocumentContentSource.METADATA_KEY — where the reviewed document lives and who wrote it
            "document.md" // DocumentContentSource.BODY_KEY — the one mirrored wiki document a review is about
            // Everything about the change itself — the patch, its statistics, the changed files, the
            // commits — is derived in the container under work/change/, never staged under inputs/.
            );

    /** Workspace-relative files pi-change.ts derives under {@code work/change/} inside the container. */
    private static final Set<String> REAL_CHANGE_FILES =
            Set.of("diff.patch", "diff_stat.txt", "files.json", "commits.json", "description.authored.md");

    private static final Pattern CONTEXT_PATH = Pattern.compile("inputs/context/([a-z_]+\\.[a-z]+)");

    private static final Pattern CHANGE_PATH = Pattern.compile("work/change/([a-z_.]+\\.[a-z]+)");

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
