package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Private persisted evidence, not HTTP views. Provider tokens and directory client secrets never enter it. */
public final class GitHubAccessEvidence {
    private GitHubAccessEvidence() {}

    public record Authorization(
            long configurationVersion,
            long accountId,
            long identityLinkId,
            long githubUserId,
            long installationId,
            Map<String, String> permissions,
            Instant authorizedAt) {
        public Authorization {
            permissions = Map.copyOf(permissions);
        }
    }

    public record Candidate(
            long accountId,
            String displayName,
            long directoryIdentityLinkId,
            String directorySubject,
            @Nullable Long githubIdentityLinkId,
            @Nullable Long githubUserId) {}

    public record Directory(
            long configurationVersion,
            Instant captureStartedAt,
            Instant sourceVersion,
            Set<String> groupIds,
            Map<String, String> groupNames,
            List<Candidate> candidates,
            Set<String> confirmedDepartures) {
        public Directory {
            groupIds = Set.copyOf(groupIds);
            groupNames = Map.copyOf(groupNames);
            candidates = List.copyOf(candidates);
            confirmedDepartures = Set.copyOf(confirmedDepartures);
        }
    }

    public record Preview(long configurationVersion, Directory directory, GitHubAccessClient.Inventory github) {}
}
