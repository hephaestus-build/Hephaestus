package de.tum.cit.aet.hephaestus.integration.scm.github.team;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.team.dto.GitHubTeamEventDTO;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processor for GitHub teams.
 */
@Service
public class GitHubTeamProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitHubTeamProcessor.class);

    private final TeamRepository teamRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GitHubTeamProcessor(TeamRepository teamRepository, ApplicationEventPublisher eventPublisher) {
        this.teamRepository = teamRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public @Nullable Team process(
            GitHubTeamEventDTO.GitHubTeamDTO dto, String orgLogin, @NonNull ProcessingContext context) {
        if (dto == null || dto.id() == null) {
            log.warn("Skipped team processing: reason=nullOrMissingId");
            return null;
        }

        // First try natural key lookup (organization + name), scoped to this provider so a same-named
        // team in a same-named org on another provider is never adopted and overwritten.
        Optional<Team> existingTeam = Optional.empty();
        if (orgLogin != null && dto.name() != null) {
            existingTeam = teamRepository.findByOrganizationIgnoreCaseAndNameAndProviderId(
                    orgLogin, dto.name(), Objects.requireNonNull(context.providerId()));
        }

        // Fall back to nativeId lookup if natural key not found (handles renames)
        if (existingTeam.isEmpty()) {
            existingTeam = teamRepository.findByNativeIdAndProviderId(
                    Objects.requireNonNull(dto.id()), Objects.requireNonNull(context.providerId()));
        }

        Team team = existingTeam.orElseGet(() -> {
            Team t = new Team();
            t.setNativeId(Objects.requireNonNull(dto.id()));
            t.setProvider(Objects.requireNonNull(context.provider()));
            return t;
        });

        boolean isNew = team.getName() == null;

        // Update fields
        if (dto.name() != null) {
            team.setName(dto.name());
        }
        // Persist slug (URL-safe identifier); fall back to name if not provided
        String slug = dto.slug() != null ? dto.slug() : dto.name();
        if (slug != null) {
            team.setSlug(slug);
        }
        if (dto.description() != null) {
            team.setDescription(dto.description());
        }
        // htmlUrl is required - use DTO value or construct from org and team slug/name
        if (dto.htmlUrl() != null) {
            team.setHtmlUrl(dto.htmlUrl());
        } else if (team.getHtmlUrl() == null && orgLogin != null && dto.slug() != null) {
            // Construct htmlUrl from org and slug as fallback
            team.setHtmlUrl("https://github.com/orgs/" + orgLogin + "/teams/" + dto.slug());
            log.debug("Constructed htmlUrl from org and slug for team: teamSlug={}", dto.slug());
        } else if (team.getHtmlUrl() == null && orgLogin != null && dto.name() != null) {
            // Fallback: use name if slug is not available
            team.setHtmlUrl("https://github.com/orgs/" + orgLogin + "/teams/" + dto.name());
            log.debug("Constructed htmlUrl from org and name for team: teamName={}", dto.name());
        }
        if (dto.privacy() != null) {
            team.setPrivacy(mapPrivacy(dto.privacy()));
        }
        if (dto.createdAt() != null) {
            team.setCreatedAt(dto.createdAt());
        }
        if (dto.updatedAt() != null) {
            team.setUpdatedAt(dto.updatedAt());
        }
        if (isNew && orgLogin != null) {
            team.setOrganization(orgLogin);
        }

        Team saved;
        try {
            saved = teamRepository.save(team);
        } catch (DataIntegrityViolationException e) {
            // Unique constraint violation on (provider_id, organization, slug)
            // This can happen when:
            // 1. Team A had slug "core" in org "myorg", renamed to "platform"
            // 2. Team B (new) was created with slug "core" in org "myorg"
            // 3. We're processing Team B but Team A still has "core" in our DB
            //
            // Solution: Find and update the conflicting team's slug first
            if (isSlugConflict(e)) {
                saved = handleSlugConflict(team, orgLogin, dto, context);
                if (saved == null) {
                    return null;
                }
            } else {
                throw e;
            }
        }

        if (isNew) {
            eventPublisher.publishEvent(
                    new ScmDomainEvent.TeamCreated(ScmEventPayload.TeamData.from(saved), EventContext.from(context)));
            log.debug("Created team: teamId={}, teamSlug={}", saved.getId(), saved.getName());
        } else {
            eventPublisher.publishEvent(new ScmDomainEvent.TeamUpdated(
                    ScmEventPayload.TeamData.from(saved), Set.of("name", "description"), EventContext.from(context)));
            log.debug("Updated team: teamId={}, teamSlug={}", saved.getId(), saved.getName());
        }

        return saved;
    }

    /**
     * Delete a team by its ID.
     * <p>
     * IMPORTANT: Clears all associated memberships and repository permissions
     * before deletion. While cascade REMOVE handles the deletion automatically,
     * clearing the collections ensures no stale references remain in the
     * persistence context.
     *
     * @param nativeId the team's native provider ID
     * @param context processing context with scope information
     */
    @Transactional
    public void delete(Long nativeId, @NonNull ProcessingContext context) {
        if (nativeId == null) {
            return;
        }

        teamRepository
                .findByNativeIdAndProviderId(nativeId, Objects.requireNonNull(context.providerId()))
                .ifPresent(team -> {
                    Long teamId = team.getId();
                    String teamName = team.getName();

                    // Clear collections to avoid stale references in persistence context
                    team.getMemberships().clear();
                    team.getRepoPermissions().clear();

                    teamRepository.delete(team);
                    eventPublisher.publishEvent(
                            new ScmDomainEvent.TeamDeleted(teamId, teamName, EventContext.from(context)));
                    log.info("Deleted team: teamId={}, teamSlug={}", teamId, teamName);
                });
    }

    /**
     * Check if the exception is a team slug unique constraint violation.
     */
    private boolean isSlugConflict(DataIntegrityViolationException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return (message.contains("uk_team_provider_organization_slug")
                || (message.contains("team") && message.contains("slug")));
    }

    /**
     * Handle a slug conflict by finding the conflicting team and resolving it.
     * <p>
     * When a team is renamed, another team can take the old slug. This method:
     * 1. Finds the team with the conflicting slug
     * 2. Updates that team's slug and name to a placeholder
     * 3. Retries the save for the new team
     *
     * @return the saved team, or null if unable to resolve
     */
    private @Nullable Team handleSlugConflict(
            Team team, String orgLogin, GitHubTeamEventDTO.GitHubTeamDTO dto, ProcessingContext context) {
        String teamSlug = dto.slug() != null ? dto.slug() : dto.name();

        // Find the team that has the conflicting slug. Provider-scoped: the rename below is destructive,
        // and only a team on this provider can actually contend for the slug.
        Optional<Team> conflicting = teamRepository.findByOrganizationIgnoreCaseAndSlugAndProviderId(
                orgLogin, teamSlug, Objects.requireNonNull(context.providerId()));
        if (conflicting.isEmpty()) {
            // Conflict resolved concurrently
            log.debug("Team slug conflict resolved concurrently: org={}, slug={}", orgLogin, teamSlug);
            return teamRepository.save(team);
        }

        Team oldTeam = conflicting.get();
        if (oldTeam.getId().equals(team.getId())) {
            // Same team - shouldn't happen but handle gracefully
            log.debug("Team slug conflict was for same team: teamId={}", team.getId());
            return teamRepository.save(team);
        }

        // Rename the old team to free up the slug
        String renamedSlug = "RENAMED_" + oldTeam.getId();
        log.info(
                "Freeing up team slug for renamed team: oldTeamId={}, oldSlug={}, newSlug={}, newTeamId={}",
                oldTeam.getId(),
                teamSlug,
                renamedSlug,
                team.getId());
        oldTeam.setSlug(renamedSlug);
        oldTeam.setName("RENAMED_" + oldTeam.getId());
        teamRepository.save(oldTeam);

        // Retry the save
        try {
            return teamRepository.save(team);
        } catch (DataIntegrityViolationException e) {
            log.warn("Team save still failed after freeing slug: teamId={}, slug={}", team.getId(), teamSlug, e);
            return null;
        }
    }

    private Team.Privacy mapPrivacy(String privacy) {
        if (privacy == null) {
            log.debug("Team privacy is null, using VISIBLE as default");
            return Team.Privacy.VISIBLE;
        }
        return switch (privacy.toLowerCase(Locale.ROOT)) {
            case "secret" -> Team.Privacy.SECRET;
            // "visible" from GraphQL, "closed" from REST API - both map to VISIBLE
            case "visible", "closed" -> Team.Privacy.VISIBLE;
            default -> {
                log.warn("Unknown team privacy '{}', using VISIBLE as default", privacy);
                yield Team.Privacy.VISIBLE;
            }
        };
    }
}
