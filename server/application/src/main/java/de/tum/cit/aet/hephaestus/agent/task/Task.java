package de.tum.cit.aet.hephaestus.agent.task;

import java.util.Objects;

/** Instructions for a practice review. Interactive mentor sessions do not use task.json. */
public record Task(String prompt, int pullRequestNumber, String repositoryFullName) {
    public Task {
        Objects.requireNonNull(prompt, "prompt");
        if (prompt.isBlank()) throw new IllegalArgumentException("prompt must not be blank");
        Objects.requireNonNull(repositoryFullName, "repositoryFullName");
        if (repositoryFullName.isBlank()) throw new IllegalArgumentException("repositoryFullName must not be blank");
        if (pullRequestNumber <= 0) {
            throw new IllegalArgumentException("pullRequestNumber must be positive, got " + pullRequestNumber);
        }
    }
}
