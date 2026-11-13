package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Workspace-owned snapshots; no returned value is a managed entity. */
public interface WorkspaceQueries {
    Optional<ReviewWorkspace> findById(long workspaceId);

    Optional<ReviewWorkspace> findByIdForUpdate(long workspaceId);

    List<RepositorySettings> repositorySettings(long workspaceId);

    List<RepositoryTarget> reviewRepositoryTargets(long workspaceId);

    record ReviewWorkspace(
            WorkspaceContext context,
            boolean active,
            ReviewFeatures features,
            PracticeReviewPolicy reviewSettings,
            @Nullable Instant practiceCatalogInstalledAt) {
        public long id() {
            return context.id();
        }

        public String slug() {
            return context.slug();
        }

        public ReviewWorkspace withReviewSettings(PracticeReviewPolicy settings) {
            return new ReviewWorkspace(context, active, features, settings, practiceCatalogInstalledAt);
        }
    }

    record ReviewFeatures(boolean practicesEnabled, boolean autoTriggerEnabled, boolean manualTriggerEnabled) {}

    record RepositorySettings(long id, List<String> generatedPaths) {
        public RepositorySettings {
            generatedPaths = List.copyOf(generatedPaths);
        }
    }

    record RepositoryTarget(long repositoryMonitorId, List<String> baseBranches) {
        public RepositoryTarget {
            baseBranches = List.copyOf(baseBranches);
        }
    }
}
