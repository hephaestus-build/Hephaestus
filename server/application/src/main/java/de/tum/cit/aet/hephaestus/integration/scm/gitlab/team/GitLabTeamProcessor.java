package de.tum.cit.aet.hephaestus.integration.scm.gitlab.team;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabDescendantGroupResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupResponse;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processor for GitLab teams (subgroups).
 * <p>
 * Maps GitLab descendant group data to {@link Team} entities. Each subgroup
 * becomes a team with:
 * <ul>
 *   <li>{@code name} = display name (e.g., "alpha")</li>
 *   <li>{@code slug} = relative path from root group (e.g., "group1/alpha")</li>
 *   <li>{@code organization} = root group full path (e.g., "ase/introcourse")</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabTeamProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitLabTeamProcessor.class);

    private final TeamRepository teamRepository;

    public GitLabTeamProcessor(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    /**
     * Processes a GitLab descendant group into a Team entity (upsert).
     *
     * @param group         the GraphQL descendant group response
     * @param rootFullPath  the root group full path (workspace account login)
     * @param provider      the git provider entity
     * @return the persisted Team, or null if input is invalid
     */
    @Transactional
    @Nullable
    public Team process(GitLabDescendantGroupResponse group, String rootFullPath, IdentityProvider provider) {
        if (group == null
                || group.id() == null
                || group.fullPath() == null
                || group.name() == null
                || group.webUrl() == null
                || group.name().isBlank()) {
            log.warn("Skipped team processing: reason=nullOrMissingId");
            return null;
        }

        long nativeId;
        try {
            nativeId = GitLabSyncConstants.extractNumericId(group.id());
        } catch (IllegalArgumentException e) {
            log.warn("Skipped team processing: reason=invalidGlobalId, gid={}", group.id());
            return null;
        }

        Long providerId = Objects.requireNonNull(provider.getId());
        String slug = computeRelativePath(group.fullPath(), rootFullPath);

        // Upsert: try nativeId first, then natural key (organization + slug + provider)
        Team team = teamRepository
                .findByNativeIdAndProviderId(nativeId, providerId)
                .orElseGet(() -> teamRepository
                        .findByOrganizationIgnoreCaseAndSlugAndProviderId(rootFullPath, slug, providerId)
                        .orElseGet(() -> {
                            Team t = new Team();
                            t.setNativeId(nativeId);
                            t.setProvider(provider);
                            t.setOrganization(rootFullPath);
                            return t;
                        }));

        boolean isNew = team.getId() == null;

        // Update fields
        team.setName(group.name());
        team.setSlug(slug);
        team.setHtmlUrl(group.webUrl());
        team.setPrivacy(mapVisibility(group.visibility()));
        team.setLastSyncAt(Instant.now());

        if (group.description() != null) {
            team.setDescription(group.description());
        }

        Team saved = teamRepository.save(team);

        if (isNew) {
            log.debug("Created GitLab team: teamId={}, slug={}, name={}", saved.getId(), slug, group.name());
        } else {
            log.debug("Updated GitLab team: teamId={}, slug={}, name={}", saved.getId(), slug, group.name());
        }

        return saved;
    }

    /**
     * Processes the root group as a Team entity (upsert).
     * <p>
     * Mirrors {@link #process(GitLabDescendantGroupResponse, String, IdentityProvider)} but
     * sources data from {@link GitLabGroupResponse} (which is what the {@code GetGroup}
     * query returns for a single group by fullPath). The root team anchors the
     * parent chain for descendants and carries the staff-wide direct members
     * (instructors, TAs, group access tokens) that GitLab inherits down to every
     * subgroup — they belong on the root, not replicated into every child.
     *
     * @param group         the root group payload
     * @param rootFullPath  the root group full path (also the Team.organization)
     * @param provider      the git provider entity
     * @return the persisted Team, or null if input is invalid
     */
    @Transactional
    @Nullable
    public Team processRoot(GitLabGroupResponse group, String rootFullPath, IdentityProvider provider) {
        if (group == null
                || group.id() == null
                || group.fullPath() == null
                || group.name() == null
                || group.webUrl() == null) {
            log.warn("Skipped root team processing: reason=nullOrMissingId");
            return null;
        }

        long nativeId;
        try {
            nativeId = GitLabSyncConstants.extractNumericId(group.id());
        } catch (IllegalArgumentException e) {
            log.warn("Skipped root team processing: reason=invalidGlobalId, gid={}", group.id());
            return null;
        }

        Long providerId = Objects.requireNonNull(provider.getId());
        String slug = rootSlug(group.fullPath());

        Team team = teamRepository
                .findByNativeIdAndProviderId(nativeId, providerId)
                .orElseGet(() -> teamRepository
                        .findByOrganizationIgnoreCaseAndSlugAndProviderId(rootFullPath, slug, providerId)
                        .orElseGet(() -> {
                            Team t = new Team();
                            t.setNativeId(nativeId);
                            t.setProvider(provider);
                            t.setOrganization(rootFullPath);
                            return t;
                        }));

        boolean isNew = team.getId() == null;

        team.setName(group.name());
        team.setSlug(slug);
        team.setHtmlUrl(group.webUrl());
        team.setPrivacy(mapVisibility(group.visibility()));
        team.setLastSyncAt(Instant.now());

        if (group.description() != null) {
            team.setDescription(group.description());
        }

        Team saved = teamRepository.save(team);

        if (isNew) {
            log.debug("Created GitLab root team: teamId={}, slug={}, name={}", saved.getId(), slug, group.name());
        } else {
            log.debug("Updated GitLab root team: teamId={}, slug={}, name={}", saved.getId(), slug, group.name());
        }

        return saved;
    }

    /**
     * Deletes a team by native ID and provider ID.
     */
    @Transactional
    public void delete(Long nativeId, Long providerId) {
        if (nativeId == null) {
            return;
        }

        teamRepository.findByNativeIdAndProviderId(nativeId, providerId).ifPresent(team -> {
            Long teamId = team.getId();
            String teamName = team.getName();

            team.clearMemberships();
            team.clearRepoPermissions();

            teamRepository.delete(team);
            log.info("Deleted GitLab team: teamId={}, name={}", teamId, teamName);
        });
    }

    /**
     * Maps GitLab visibility to Team privacy.
     * Private groups → SECRET, public/internal → VISIBLE.
     */
    static Team.Privacy mapVisibility(@Nullable String visibility) {
        if (visibility == null) {
            return Team.Privacy.VISIBLE;
        }
        return switch (visibility.toLowerCase(Locale.ROOT)) {
            case "private" -> Team.Privacy.SECRET;
            case "public", "internal" -> Team.Privacy.VISIBLE;
            default -> Team.Privacy.VISIBLE;
        };
    }

    /**
     * Computes the relative path from the root group.
     * <p>
     * Example: fullPath = "ase/introcourse/group1/alpha", rootFullPath = "ase/introcourse"
     * → returns "group1/alpha"
     */
    static String computeRelativePath(String fullPath, String rootFullPath) {
        if (fullPath.startsWith(rootFullPath + "/")) {
            return fullPath.substring(rootFullPath.length() + 1);
        }
        // Fallback: return the full path if it doesn't start with root
        return fullPath;
    }

    /**
     * Slug for the root Team: the last segment of its fullPath. Chosen so the
     * {@code (provider_id, organization, slug)} unique constraint still holds
     * when descendant teams (whose slugs are the relative path below the root)
     * share the same organization.
     */
    static String rootSlug(@Nullable String fullPath) {
        if (fullPath == null || fullPath.isBlank()) {
            return "";
        }
        int idx = fullPath.lastIndexOf('/');
        return idx < 0 ? fullPath : fullPath.substring(idx + 1);
    }
}
