package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the orchestrator prompt's workspace inventory against the paths collectors actually write.
 *
 * <p>A wrong path in the prompt is the same class of defect as a missing collector: the model is told to
 * read a file, reads nothing, and reports what it did not find as a fact about a developer. Neither a
 * test of the collectors alone nor of the prompt alone catches this — a path can be named but never
 * staged, or staged but never named.
 */
class OrchestratorPromptWorkspaceTest extends BaseUnitTest {

    /**
     * Every file the review collectors can write into {@code inputs/}, and the one place that list is
     * stated. Hand-maintained on purpose: a new collector output has to be added here, which is the same
     * edit that forces the author to say what it is in the prompt.
     */
    private static final Set<String> STAGED_INPUT_PATHS = new LinkedHashSet<>(java.util.List.of(
            // Pull request
            SandboxLayout.CONTEXT_PREFIX + "metadata.json",
            SandboxLayout.CONTEXT_PREFIX + "comments.json",
            SandboxLayout.CONTEXT_PREFIX + "diff.patch",
            SandboxLayout.CONTEXT_PREFIX + "diff_stat.txt",
            SandboxLayout.CONTEXT_PREFIX + "diff_summary.md",
            SandboxLayout.CONTEXT_PREFIX + "context-map.md",
            SandboxLayout.CONTEXT_PREFIX + ReviewThreadContentSource.FILE_NAME,
            SandboxLayout.CONTEXT_PREFIX + GeneralReviewCommentContentSource.FILE_NAME,
            SandboxLayout.CONTEXT_PREFIX + PullRequestCommitContentSource.FILE_NAME,
            LinkedWorkItemContentSource.OUTPUT_FILE,
            // Issue
            SandboxLayout.CONTEXT_PREFIX + "issue_summary.md",
            // Conversation thread
            ConversationThreadContentSource.OUTPUT_KEY,
            // Document
            DocumentContentSource.OUTPUT_KEY,
            // Workspace-wide, staged for every review whose artifact kind the source applies to
            WorkspaceInventoryContentSource.OUTPUT_FILE,
            OutlineDocumentContentSource.REVIEW_INDEX_KEY,
            OutlineDocumentContentSource.UNRESOLVED_REFERENCES_KEY,
            ReviewHistoryContentSource.OBSERVATIONS_FILE,
            ReviewHistoryContentSource.FEEDBACK_FILE,
            SandboxLayout.MANIFEST_PATH));

    /** Directories and templated paths the prompt names as prefixes rather than as concrete files. */
    private static final Set<String> STAGED_INPUT_PREFIXES = Set.of(
            SandboxLayout.REPO_MOUNT_RELATIVE,
            SandboxLayout.PRACTICES_PREFIX,
            OutlineDocumentContentSource.REVIEW_PREFIX);

    @Test
    @DisplayName("every file a review collector stages is described in the orchestrator prompt")
    void promptDescribesEveryStagedInput() throws IOException {
        String prompt = orchestratorPrompt();

        assertThat(STAGED_INPUT_PATHS)
                .as("a staged file the prompt never mentions is context the model was not told it has")
                .allSatisfy(path ->
                        assertThat(prompt).as("prompt mentions %s", path).contains(path));
    }

    @Test
    @DisplayName("the orchestrator prompt names no sandbox input that nothing stages")
    void promptNamesNoUnstagedInput() throws IOException {
        Set<String> named = new LinkedHashSet<>();
        Matcher matcher =
                Pattern.compile("inputs/[A-Za-z0-9_./<>-]*[A-Za-z0-9_>]").matcher(orchestratorPrompt());
        while (matcher.find()) {
            named.add(matcher.group());
        }

        assertThat(named).isNotEmpty();
        assertThat(named)
                .as("the prompt sends the model to a path nothing writes; it will read nothing and say so")
                .allSatisfy(path -> assertThat(STAGED_INPUT_PATHS.contains(path)
                                || STAGED_INPUT_PREFIXES.stream().anyMatch(prefix -> path.startsWith(prefix))
                                ||
                                // Trailing-slash and templated forms of the prefixes above.
                                STAGED_INPUT_PREFIXES.stream().anyMatch(prefix -> prefix.startsWith(path)))
                        .as("prompt path %s is staged", path)
                        .isTrue());
    }

    private static String orchestratorPrompt() throws IOException {
        Path candidate = Path.of("src/main/resources/agent/pi-orchestrator.md");
        Path resolved = Files.exists(candidate)
                ? candidate
                : Path.of("server/application/src/main/resources/agent/pi-orchestrator.md");
        assertThat(resolved).isRegularFile();
        return Files.readString(resolved, StandardCharsets.UTF_8);
    }
}
