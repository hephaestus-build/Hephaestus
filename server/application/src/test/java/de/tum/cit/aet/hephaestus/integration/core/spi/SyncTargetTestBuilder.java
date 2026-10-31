package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public final class SyncTargetTestBuilder {

    private Long id = 1L;
    private Long scopeId = 1L;
    private @Nullable Long installationId;
    private @Nullable String personalAccessToken;
    private AuthMode authMode = AuthMode.INSTALLATION_APP;
    private String repositoryNameWithOwner = "owner/repository";
    private @Nullable Instant lastLabelsSyncedAt;
    private @Nullable Instant lastMilestonesSyncedAt;
    private @Nullable Instant lastIssuesSyncedAt;
    private @Nullable Instant lastPullRequestsSyncedAt;
    private @Nullable Instant lastCollaboratorsSyncedAt;
    private @Nullable Instant lastFullSyncAt;
    private @Nullable Integer issueBackfillHighWaterMark;
    private @Nullable Integer issueBackfillCheckpoint;
    private @Nullable Integer pullRequestBackfillHighWaterMark;
    private @Nullable Integer pullRequestBackfillCheckpoint;
    private @Nullable Instant backfillLastRunAt;
    private @Nullable String issueSyncCursor;
    private @Nullable String pullRequestSyncCursor;
    private @Nullable Long nativeId;

    private SyncTargetTestBuilder() {}

    public static SyncTargetTestBuilder syncTarget() {
        return new SyncTargetTestBuilder();
    }

    public SyncTargetTestBuilder id(Long value) {
        this.id = value;
        return this;
    }

    public SyncTargetTestBuilder scopeId(Long value) {
        this.scopeId = value;
        return this;
    }

    public SyncTargetTestBuilder installationId(@Nullable Long value) {
        this.installationId = value;
        return this;
    }

    public SyncTargetTestBuilder personalAccessToken(@Nullable String value) {
        this.personalAccessToken = value;
        return this;
    }

    public SyncTargetTestBuilder authMode(AuthMode value) {
        this.authMode = value;
        return this;
    }

    public SyncTargetTestBuilder repositoryNameWithOwner(String value) {
        this.repositoryNameWithOwner = value;
        return this;
    }

    public SyncTargetTestBuilder lastLabelsSyncedAt(@Nullable Instant value) {
        this.lastLabelsSyncedAt = value;
        return this;
    }

    public SyncTargetTestBuilder lastMilestonesSyncedAt(@Nullable Instant value) {
        this.lastMilestonesSyncedAt = value;
        return this;
    }

    public SyncTargetTestBuilder lastIssuesSyncedAt(@Nullable Instant value) {
        this.lastIssuesSyncedAt = value;
        return this;
    }

    public SyncTargetTestBuilder lastPullRequestsSyncedAt(@Nullable Instant value) {
        this.lastPullRequestsSyncedAt = value;
        return this;
    }

    public SyncTargetTestBuilder lastCollaboratorsSyncedAt(@Nullable Instant value) {
        this.lastCollaboratorsSyncedAt = value;
        return this;
    }

    public SyncTargetTestBuilder lastFullSyncAt(@Nullable Instant value) {
        this.lastFullSyncAt = value;
        return this;
    }

    public SyncTargetTestBuilder issueBackfillHighWaterMark(@Nullable Integer value) {
        this.issueBackfillHighWaterMark = value;
        return this;
    }

    public SyncTargetTestBuilder issueBackfillCheckpoint(@Nullable Integer value) {
        this.issueBackfillCheckpoint = value;
        return this;
    }

    public SyncTargetTestBuilder pullRequestBackfillHighWaterMark(@Nullable Integer value) {
        this.pullRequestBackfillHighWaterMark = value;
        return this;
    }

    public SyncTargetTestBuilder pullRequestBackfillCheckpoint(@Nullable Integer value) {
        this.pullRequestBackfillCheckpoint = value;
        return this;
    }

    public SyncTargetTestBuilder backfillLastRunAt(@Nullable Instant value) {
        this.backfillLastRunAt = value;
        return this;
    }

    public SyncTargetTestBuilder issueSyncCursor(@Nullable String value) {
        this.issueSyncCursor = value;
        return this;
    }

    public SyncTargetTestBuilder pullRequestSyncCursor(@Nullable String value) {
        this.pullRequestSyncCursor = value;
        return this;
    }

    public SyncTargetTestBuilder nativeId(@Nullable Long value) {
        this.nativeId = value;
        return this;
    }

    public SyncTarget build() {
        return new SyncTarget(
                id,
                scopeId,
                installationId,
                personalAccessToken,
                authMode,
                repositoryNameWithOwner,
                lastLabelsSyncedAt,
                lastMilestonesSyncedAt,
                lastIssuesSyncedAt,
                lastPullRequestsSyncedAt,
                lastCollaboratorsSyncedAt,
                lastFullSyncAt,
                issueBackfillHighWaterMark,
                issueBackfillCheckpoint,
                pullRequestBackfillHighWaterMark,
                pullRequestBackfillCheckpoint,
                backfillLastRunAt,
                issueSyncCursor,
                pullRequestSyncCursor,
                nativeId);
    }
}
