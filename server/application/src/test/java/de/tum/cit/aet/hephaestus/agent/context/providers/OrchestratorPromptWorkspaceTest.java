package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.task.TaskPaths;
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

/** Checks the prompt's documented workspace inventory against known collector paths. */
class OrchestratorPromptWorkspaceTest extends BaseUnitTest {

    private static final Set<String> STAGED_INPUT_PATHS = new LinkedHashSet<>(java.util.List.of(
            // Pull request
            SandboxLayout.CONTEXT_PREFIX + "metadata.json",
            PullRequestContentSource.DESCRIPTION_FILE,
            SandboxLayout.CONTEXT_PREFIX + "comments.json",
            PullRequestContentSource.CHANGE_FILE,
            SandboxLayout.CONTEXT_PREFIX + ReviewThreadContentSource.FILE_NAME,
            SandboxLayout.CONTEXT_PREFIX + GeneralReviewCommentContentSource.FILE_NAME,
            LinkedWorkItemContentSource.OUTPUT_FILE,
            LinkedWorkItemContentSource.ITEMS_PREFIX + "<n>.md",
            // Conversation thread
            ConversationThreadContentSource.OUTPUT_KEY,
            // Document
            DocumentContentSource.BODY_KEY,
            DocumentContentSource.METADATA_KEY,
            // Workspace-wide, staged for every review whose artifact kind the source applies to
            WorkspaceInventoryContentSource.OUTPUT_FILE,
            OutlineDocumentContentSource.REVIEW_INDEX_KEY,
            ReviewHistoryContentSource.OBSERVATIONS_FILE,
            ReviewHistoryContentSource.FEEDBACK_FILE,
            SandboxLayout.MANIFEST_PATH));

    /** What pi-change.ts derives in the container from the checkout; the prompt must send the model there. */
    private static final Set<String> DERIVED_CHANGE_PATHS = Set.of(
            "work/change/diff.patch",
            "work/change/diff_stat.txt",
            "work/change/files.json",
            "work/change/commits.json");

    /** Directories and templated paths the prompt names as prefixes rather than as concrete files. */
    private static final Set<String> STAGED_INPUT_PREFIXES = Set.of(
            SandboxLayout.REPO_MOUNT_RELATIVE,
            SandboxLayout.PRACTICES_PREFIX,
            OutlineDocumentContentSource.REVIEW_PREFIX);

    @Test
    @DisplayName("the prompt states the observation contract the runtime and the server enforce")
    void promptStatesTheObservationContract() throws IOException {
        String prompt = resolvedDocumentedPrompt();
        assertThat(prompt)
                .contains("| ASSESSED | PRESENT | GOOD | POSITIVE | null |")
                .contains("| ASSESSED | ABSENT | GOOD | NEGATIVE | required |")
                .contains("| ASSESSED | PRESENT | BAD | NEGATIVE | required |")
                .contains("| ASSESSED | ABSENT | BAD | POSITIVE | null |")
                .contains("| NOT_APPLICABLE | null | null | none | null |")
                .contains("| UNDETERMINED | null | null | none | null |")
                .contains("`evidence.inapplicability`", "`evidence.search`", "`evidence.undecidability`")
                .contains("a mention alone does not establish guidance supplied or adopted by the author");
        // The criteria decide the outcome; the prompt itself pushes toward neither outcome.
        assertThat(prompt)
                .contains("a positive outcome is as ordinary as a negative one")
                .doesNotContain("Report all justified negative observations", "genuinely exemplary");
    }

    @Test
    @DisplayName("the workspace section documents the known collector outputs")
    void workspaceSectionDocumentsCollectorOutputs() throws IOException {
        String prompt = documentedWorkspaceSection();

        assertThat(STAGED_INPUT_PATHS)
                .as("the workspace section must describe each known collector output")
                .allSatisfy(path ->
                        assertThat(prompt).as("prompt mentions %s", path).contains(path));
        assertThat(DERIVED_CHANGE_PATHS)
                .as("the workspace section must describe each file of the derived change view")
                .allSatisfy(path ->
                        assertThat(prompt).as("prompt mentions %s", path).contains(path));
        assertThat(prompt)
                .doesNotContain("inputs/context/diff", "inputs/context/commits", "context-map", "diff_summary");
    }

    @Test
    @DisplayName("prompt paths belong to known files or content directories")
    void promptPathsBelongToKnownInputs() throws IOException {
        Set<String> named = new LinkedHashSet<>();
        Matcher matcher =
                Pattern.compile("inputs/[A-Za-z0-9_./<>-]*[A-Za-z0-9_>]").matcher(resolvedDocumentedPrompt());
        while (matcher.find()) {
            named.add(matcher.group());
        }

        TaskPaths paths = TaskPaths.capturedInputs();
        assertThat(named).isNotEmpty();
        assertThat(named)
                .as("documented paths must belong to known files or content directories")
                .allSatisfy(path -> assertThat(STAGED_INPUT_PATHS.contains(path)
                                || path.equals(paths.compositionRequest())
                                || path.equals(paths.preparedFeedback())
                                || STAGED_INPUT_PATHS.stream().anyMatch(staged -> staged.startsWith(path + "/"))
                                || STAGED_INPUT_PREFIXES.stream().anyMatch(prefix -> path.startsWith(prefix))
                                ||
                                // Trailing-slash and templated forms of the prefixes above.
                                STAGED_INPUT_PREFIXES.stream().anyMatch(prefix -> prefix.startsWith(path)))
                        .as("prompt path %s is staged", path)
                        .isTrue());
    }

    private static String resolvedDocumentedPrompt() throws IOException {
        Path candidate = Path.of("src/main/resources/agent/pi-orchestrator.md");
        Path resolved = Files.exists(candidate)
                ? candidate
                : Path.of("server/application/src/main/resources/agent/pi-orchestrator.md");
        assertThat(resolved).isRegularFile();
        String prompt = Files.readString(resolved, StandardCharsets.UTF_8);
        TaskPaths paths = TaskPaths.capturedInputs();
        String resolvedPrompt = prompt.replace("<contextRoot>", paths.contextRoot())
                .replace("<repositoryRoot>", paths.repositoryRoot())
                .replace("<manifest>", paths.manifest())
                .replace("<practiceIndex>", paths.practiceIndex())
                .replace("<compositionRequest>", paths.compositionRequest())
                .replace("<preparedFeedback>", paths.preparedFeedback())
                .replace("<practiceRoot>", paths.practiceIndex().replaceFirst("/[^/]+$", ""))
                .replace("<historyRoot>", paths.preparedFeedback().replaceFirst("/[^/]+$", ""));
        assertThat(Pattern.compile("<[A-Za-z][A-Za-z0-9]*>")
                        .matcher(resolvedPrompt)
                        .results()
                        .map(result -> result.group())
                        .toList())
                .as("remaining notation must be a documented content placeholder, not an unresolved task path")
                .allMatch(Set.of("<collection>", "<doc>", "<n>", "<sha>", "<slug>", "<verb>")::contains);
        return resolvedPrompt;
    }

    private static String documentedWorkspaceSection() throws IOException {
        String resolvedPrompt = resolvedDocumentedPrompt();
        int workspaceStart = resolvedPrompt.indexOf("## Workspace");
        int workspaceEnd = resolvedPrompt.indexOf("## Tools", workspaceStart);
        assertThat(workspaceStart).isNotNegative();
        assertThat(workspaceEnd).isGreaterThan(workspaceStart);
        return resolvedPrompt.substring(workspaceStart, workspaceEnd);
    }
}
