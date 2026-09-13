package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceAiAvailability;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class WorkspaceOnboardingService {
    private final WorkspaceRepository workspaces;
    private final WorkspaceOnboardingSettingsRepository settings;
    private final WorkspaceMemberOnboardingRepository members;
    private final AccountWorkspaceMembershipQuery memberships;
    private final WorkspaceOnboardingLinks links;
    private final WorkspaceAiAvailability availability;
    private final ConfigAuditPort audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public WorkspaceOnboardingDTO state(WorkspaceContext context, long accountId) {
        return memberState(context, accountId);
    }

    private WorkspaceOnboardingDTO memberState(WorkspaceContext context, long accountId) {
        requireMember(context.id(), accountId);
        var policy = settings.findByWorkspaceId(context.id()).orElseGet(WorkspaceOnboardingSettings::new);
        var member =
                members.findByWorkspace_IdAndAccountId(context.id(), accountId).orElse(null);
        var linkOptions = links.options(context.id(), accountId, policy.getRequiredConnectionIds());
        boolean completed = member != null
                && member.getCompletedAt() != null
                && linkOptions.stream()
                        .filter(link -> link.required() && link.available())
                        .allMatch(WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO::linked);
        return new WorkspaceOnboardingDTO(
                context.displayName(),
                policy.isEnabled(),
                policy.isEnabled() && (member == null || member.getWelcomedAt() == null),
                policy.getRevision(),
                policy.isAiChoiceRequired() || member != null,
                member == null ? null : member.getAiChoice(),
                completed,
                availability.options(context.id()).stream()
                        .map(option -> new WorkspaceOnboardingDTO.WorkspaceAiOptionDTO(
                                option.choice(),
                                option.practiceReviewsReady(),
                                option.mentorReady(),
                                option.sameModelsAs()))
                        .toList(),
                linkOptions);
    }

    /** The choice is a boundary, not a pick from today's bindings: any of the four values is accepted. */
    @Transactional
    public WorkspaceOnboardingDTO choose(WorkspaceContext context, long accountId, MemberAiChoice choice) {
        requireSelf();
        Workspace workspace = lockWorkspace(context.id());
        requireMember(context.id(), accountId);
        var member = members.findByWorkspace_IdAndAccountId(context.id(), accountId)
                .orElseGet(() -> {
                    var created = new WorkspaceMemberOnboarding();
                    created.setWorkspace(workspace);
                    created.setAccountId(accountId);
                    return created;
                });
        member.setAiChoice(choice);
        member.setUpdatedAt(clock.instant());
        members.save(member);
        return memberState(context, accountId);
    }

    @Transactional
    public WorkspaceOnboardingDTO complete(WorkspaceContext context, long accountId, long revision) {
        requireSelf();
        lockWorkspace(context.id());
        requireMember(context.id(), accountId);
        var policy = settings.findByWorkspaceId(context.id()).orElseGet(WorkspaceOnboardingSettings::new);
        if (policy.getRevision() != revision)
            throw conflict("Workspace onboarding changed. Review the current requirements and try again.");
        var member = members.findByWorkspace_IdAndAccountId(context.id(), accountId)
                .filter(row -> row.getAiChoice() != null)
                .orElseThrow(() -> conflict("Make your AI choice first; No AI is always available."));
        // A required link the workspace cannot offer right now is the owner's to repair; it never
        // holds a member's setup open.
        if (links.options(context.id(), accountId, policy.getRequiredConnectionIds()).stream()
                .anyMatch(link -> link.required() && link.available() && !link.linked()))
            throw conflict("Connect the required workspace accounts before finishing.");
        member.setWelcomedAt(clock.instant());
        member.setCompletedAt(clock.instant());
        member.setUpdatedAt(clock.instant());
        members.save(member);
        return memberState(context, accountId);
    }

    @Transactional
    public WorkspaceOnboardingDTO dismiss(WorkspaceContext context, long accountId) {
        requireSelf();
        var workspace = lockWorkspace(context.id());
        requireMember(context.id(), accountId);
        if (!settings.findByWorkspaceId(context.id())
                .map(WorkspaceOnboardingSettings::isEnabled)
                .orElse(false)) return memberState(context, accountId);
        var member = members.findByWorkspace_IdAndAccountId(context.id(), accountId)
                .orElseGet(() -> {
                    var created = new WorkspaceMemberOnboarding();
                    created.setWorkspace(workspace);
                    created.setAccountId(accountId);
                    return created;
                });
        member.setWelcomedAt(clock.instant());
        member.setUpdatedAt(clock.instant());
        members.save(member);
        return memberState(context, accountId);
    }

    @Transactional(readOnly = true)
    public WorkspaceOnboardingSettingsDTO settings(WorkspaceContext context) {
        var policy = settings.findByWorkspaceId(context.id()).orElseGet(WorkspaceOnboardingSettings::new);
        return toDTO(policy);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO> linkOptions(
            WorkspaceContext context, long accountId) {
        return links.options(
                context.id(),
                accountId,
                settings.findByWorkspaceId(context.id())
                        .map(WorkspaceOnboardingSettings::getRequiredConnectionIds)
                        .orElse(List.of()));
    }

    @Transactional
    public WorkspaceOnboardingSettingsDTO configure(
            WorkspaceContext context, long accountId, WorkspaceOnboardingSettingsDTO request) {
        var workspace = lockWorkspace(context.id());
        var policy = settings.findByWorkspaceId(context.id()).orElseGet(() -> {
            var created = new WorkspaceOnboardingSettings();
            created.setWorkspace(workspace);
            return created;
        });
        if (request.revision() != policy.getRevision())
            throw conflict("Onboarding settings changed. Reload before saving.");
        if (request.requiredConnectionIds().stream().distinct().count()
                != request.requiredConnectionIds().size())
            throw conflict("Select each required integration only once.");
        var available = links.options(context.id(), accountId, List.of());
        for (long id : request.requiredConnectionIds())
            if (available.stream().noneMatch(link -> link.connectionId() == id && link.available()))
                throw conflict(
                        "Required accounts must use an active integration and enabled account-linking provider configured for this workspace.");
        var before = new SettingsSnapshot(toDTO(policy));
        policy.setEnabled(request.enabled());
        policy.setAiChoiceRequired(policy.isAiChoiceRequired() || request.enabled());
        policy.setRequiredConnectionIds(List.copyOf(request.requiredConnectionIds()));
        settings.saveAndFlush(policy);
        var result = toDTO(policy);
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_FEATURES,
                "member-onboarding",
                context.id(),
                before,
                new SettingsSnapshot(result)));
        return result;
    }

    private void requireMember(long workspaceId, long accountId) {
        if (memberships.membershipsForAccount(accountId).stream()
                .noneMatch(member -> member.workspaceId() == workspaceId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Join this workspace before starting onboarding.");
    }

    private static void requireSelf() {
        if (CurrentAccount.impersonatorId() != null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the account owner can make their AI choice.");
    }

    private Workspace lockWorkspace(long id) {
        return workspaces
                .findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));
    }

    private static WorkspaceOnboardingSettingsDTO toDTO(WorkspaceOnboardingSettings policy) {
        return new WorkspaceOnboardingSettingsDTO(
                policy.isEnabled(),
                policy.isAiChoiceRequired(),
                policy.getRevision(),
                policy.getRequiredConnectionIds());
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record SettingsSnapshot(WorkspaceOnboardingSettingsDTO settings) implements ConfigAuditSnapshot {}
}
