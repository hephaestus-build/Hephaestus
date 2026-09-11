package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for local repository clones and for the commit trees read out of them.
 *
 * <p>The three tree bounds mirror what the shipped {@code scm.repository.tree} source contract already
 * promises. They are settings rather than constants because the ceiling that keeps one deployment's
 * reviews affordable is not the ceiling another wants, and a bound nobody can raise is one operators route
 * around by turning the source off entirely.
 *
 * <p>Raising them costs disk and the copy into the sandbox on every review; lowering them costs
 * completeness, never correctness — a truncated tree is reported {@code PARTIAL} with the limitation that
 * truncated it, and any practice asserting an absence is refused rather than answered from a fragment.
 *
 * @param treeMaxFiles     files a single tree snapshot may stage before the walk stops
 * @param treeMaxTotalSize bytes a single tree snapshot may stage before the walk stops
 * @param treeMaxFileSize  size at which one file is skipped and the rest of the tree is still read
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.git")
public record GitRepositoryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("20000") @Min(1) int treeMaxFiles,
        @DefaultValue("32MB") @NotNull DataSize treeMaxTotalSize,
        @DefaultValue("10MB") @NotNull DataSize treeMaxFileSize) {
    /**
     * Bean Validation has no comparison constraint between two properties, so the ordering the bounds
     * have to satisfy is checked here — the same place {@code AgentProperties} checks its durations.
     */
    public GitRepositoryProperties {
        if (treeMaxTotalSize == null || treeMaxTotalSize.toBytes() <= 0) {
            throw new IllegalArgumentException(
                    "hephaestus.git.tree-max-total-size (GIT_TREE_MAX_TOTAL_SIZE) must be positive, got: "
                            + treeMaxTotalSize);
        }
        if (treeMaxFileSize == null || treeMaxFileSize.toBytes() <= 0) {
            throw new IllegalArgumentException(
                    "hephaestus.git.tree-max-file-size (GIT_TREE_MAX_FILE_SIZE) must be positive, got: "
                            + treeMaxFileSize);
        }
        if (treeMaxFileSize.toBytes() > treeMaxTotalSize.toBytes()) {
            throw new IllegalArgumentException("hephaestus.git.tree-max-file-size (GIT_TREE_MAX_FILE_SIZE) must be <= "
                    + "hephaestus.git.tree-max-total-size (GIT_TREE_MAX_TOTAL_SIZE), got: "
                    + treeMaxFileSize
                    + " > "
                    + treeMaxTotalSize);
        }
    }
}
