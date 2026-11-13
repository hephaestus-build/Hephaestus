package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewPolicy;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Mutations join the caller's workspace transaction and retain its workspace row lock. */
public interface WorkspaceCommands {
    void markPracticeCatalogInstalled(long workspaceId, Instant installedAt);

    PracticeReviewPolicy saveReviewPolicy(long workspaceId, PracticeReviewPolicy policy, boolean rolloutChanged);

    void replaceReviewScope(
            long workspaceId, ReviewScopeMode repositories, ReviewScopeMode people, Map<Long, List<String>> targets);

    void patchGeneratedPaths(long workspaceId, Map<Long, List<String>> paths);

    BudgetChange updateInstanceLlmBudget(String workspaceSlug, @Nullable BigDecimal monthlyBudgetUsd);

    BudgetChange updateOwnProviderLlmBudget(long workspaceId, @Nullable BigDecimal monthlyBudgetUsd);

    record BudgetChange(long workspaceId, @Nullable BigDecimal previousLimit) {}
}
