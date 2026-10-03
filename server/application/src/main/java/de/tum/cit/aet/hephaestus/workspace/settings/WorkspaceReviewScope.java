package de.tum.cit.aet.hephaestus.workspace.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** An empty repository selection admits no repository work; an empty person selection admits nobody. A selected repository without branches admits all its branches. */
public record WorkspaceReviewScope(
        @NonNull @NotNull ReviewRepositoryMode repositoryMode,
        @NonNull @NotNull ReviewPersonMode personMode,
        @NonNull @NotNull @Valid List<ReviewRepositoryTarget> repositories,
        @NonNull @NotNull List<Long> personUserIds) {
    public static final WorkspaceReviewScope ALL = new WorkspaceReviewScope(
            ReviewRepositoryMode.ALL_MONITORED, ReviewPersonMode.ALL_ELIGIBLE, List.of(), List.of());

    public WorkspaceReviewScope {
        Objects.requireNonNull(repositoryMode, "repositoryMode");
        Objects.requireNonNull(personMode, "personMode");
        repositories = List.copyOf(Objects.requireNonNull(repositories, "repositories"));
        long repositoryNames = repositories.stream()
                .map(ReviewRepositoryTarget::nameWithOwner)
                .distinct()
                .count();
        if (repositoryNames != repositories.size()) {
            throw new IllegalArgumentException("A repository may appear only once in review coverage");
        }
        personUserIds = Objects.requireNonNull(personUserIds, "personUserIds").stream()
                .distinct()
                .sorted()
                .toList();
    }

    public boolean admitsRepository(@Nullable String nameWithOwner, @Nullable String baseBranch) {
        return admitsRepository(nameWithOwner, baseBranch, true);
    }

    public boolean admitsRepository(
            @Nullable String nameWithOwner, @Nullable String baseBranch, boolean branchRestrictionsApply) {
        if (repositoryMode == ReviewRepositoryMode.ALL_MONITORED) {
            return true;
        }
        return repositories.stream()
                .filter(selection -> selection.nameWithOwner().equals(nameWithOwner))
                .anyMatch(selection -> !branchRestrictionsApply
                        || selection.baseBranches().isEmpty()
                        || (baseBranch != null && selection.baseBranches().contains(baseBranch)));
    }

    public boolean admitsPerson(@Nullable Long userId) {
        return userId != null && (personMode == ReviewPersonMode.ALL_ELIGIBLE || personUserIds.contains(userId));
    }

    public boolean admits(@Nullable String nameWithOwner, @Nullable String baseBranch, @Nullable Long userId) {
        return admits(nameWithOwner, baseBranch, userId, true);
    }

    public boolean admits(
            @Nullable String nameWithOwner,
            @Nullable String baseBranch,
            @Nullable Long userId,
            boolean branchRestrictionsApply) {
        return admitsRepository(nameWithOwner, baseBranch, branchRestrictionsApply) && admitsPerson(userId);
    }
}
