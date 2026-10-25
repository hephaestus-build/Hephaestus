package de.tum.cit.aet.hephaestus.agent.task;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.util.List;

/** Input locations in the job folder. The materializer owns the layout, not its consumers. */
public record TaskPaths(
        String contextRoot,
        String repositoryRoot,
        String manifest,
        String practiceIndex,
        String preparedFeedback,
        String practiceScripts) {
    public TaskPaths {
        for (String path :
                List.of(contextRoot, repositoryRoot, manifest, practiceIndex, preparedFeedback, practiceScripts)) {
            if (path.matches("\\p{Z}*")
                    || path.startsWith("/")
                    || path.indexOf('\\') >= 0
                    || path.indexOf(':') >= 0
                    || path.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Task paths must be normalized workspace-relative paths");
            }
            for (String component : path.split("/", -1)) {
                if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                    throw new IllegalArgumentException("Task paths must be normalized workspace-relative paths");
                }
            }
        }
    }

    public static TaskPaths capturedInputs() {
        return new TaskPaths(
                SandboxLayout.CONTEXT_PREFIX.replaceFirst("/$", ""),
                SandboxLayout.REPO_MOUNT_RELATIVE.replaceFirst("/$", ""),
                SandboxLayout.MANIFEST_PATH,
                SandboxLayout.PRACTICES_PREFIX + "index.json",
                SandboxLayout.HISTORY_PREFIX + "prepared.json",
                SandboxLayout.PRACTICE_SCRIPT_PRACTICES_PREFIX.replaceFirst("/$", ""));
    }
}
