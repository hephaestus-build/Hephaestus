package de.tum.cit.aet.hephaestus.workspace.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * An empty repository selection admits no repository work; an empty person selection admits nobody.
 * A selected repository without branches admits all its branches.
 */
public record WorkspaceReviewScope(
        @NonNull @NotNull ReviewScopeMode repositoryMode,
        @NonNull @NotNull ReviewScopeMode personMode,
        @NonNull @NotNull @Valid List<ReviewRepositoryTarget> repositories,
        @NonNull @NotNull List<Long> accountIds) {
    public static final WorkspaceReviewScope ALL =
            new WorkspaceReviewScope(ReviewScopeMode.ALL, ReviewScopeMode.ALL, List.of(), List.of());

    public WorkspaceReviewScope {
        Objects.requireNonNull(repositoryMode, "repositoryMode");
        Objects.requireNonNull(personMode, "personMode");
        repositories = List.copyOf(Objects.requireNonNull(repositories, "repositories"));
        long repositoryIds = repositories.stream()
                .mapToLong(ReviewRepositoryTarget::repositoryItemId)
                .distinct()
                .count();
        if (repositoryIds != repositories.size()) {
            throw new IllegalArgumentException("A repository may appear only once in review coverage");
        }
        accountIds = Objects.requireNonNull(accountIds, "accountIds").stream()
                .distinct()
                .sorted()
                .toList();
    }

    public boolean admitsRepository(@Nullable Long repositoryItemId, @Nullable String baseBranch) {
        return admitsRepository(repositoryItemId, baseBranch, true);
    }

    public boolean admitsRepository(
            @Nullable Long repositoryItemId, @Nullable String baseBranch, boolean branchRestrictionsApply) {
        if (repositoryMode == ReviewScopeMode.ALL) {
            return true;
        }
        return repositories.stream()
                .filter(selection -> repositoryItemId != null && selection.repositoryItemId() == repositoryItemId)
                .anyMatch(selection -> !branchRestrictionsApply
                        || selection.baseBranches().isEmpty()
                        || (baseBranch != null && selection.baseBranches().contains(baseBranch)));
    }

    public boolean admitsPerson(@Nullable Long accountId) {
        return accountId != null && admitsPerson(personMode, accountIds.contains(accountId));
    }

    /**
     * Whether the people scope admits an eligible member: all of them under ALL, only the selected ones under
     * SELECTED. The member selection flag means nothing under ALL. Review settings counts with this rule, and
     * Members shows it.
     */
    public static boolean admitsPerson(ReviewScopeMode personMode, boolean selected) {
        return personMode == ReviewScopeMode.ALL || selected;
    }

    public boolean admits(@Nullable Long repositoryItemId, @Nullable String baseBranch, @Nullable Long accountId) {
        return admits(repositoryItemId, baseBranch, accountId, true);
    }

    public boolean admits(
            @Nullable Long repositoryItemId,
            @Nullable String baseBranch,
            @Nullable Long accountId,
            boolean branchRestrictionsApply) {
        return admitsRepository(repositoryItemId, baseBranch, branchRestrictionsApply) && admitsPerson(accountId);
    }
}
