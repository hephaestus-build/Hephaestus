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
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The AI choice is the account's and is written here whichever page asks for it; the setup page is
 * the workspace's and returns only while it still has something to ask: the choice itself, or a
 * required account link the workspace can offer right now. Setup is done when nothing is owed, so
 * there is no completion to record; a skip is recorded so the page stays away until the owner
 * changes what it asks for.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class WorkspaceOnboardingService {
    private final WorkspaceRepository workspaces;
    private final WorkspaceOnboardingSettingsRepository settings;
    private final WorkspaceMemberOnboardingRepository members;
    private final AccountAiChoiceRepository choices;
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
        var choice =
                choices.findById(accountId).map(AccountAiChoice::getAiChoice).orElse(null);
        var linkOptions = links.options(context.id(), accountId, policy.getRequiredConnectionIds());
        @Nullable
        Long seenRevision = members.findByWorkspace_IdAndAccountId(context.id(), accountId)
                .map(WorkspaceMemberOnboarding::getSeenRevision)
                .orElse(null);
        // A member who has already answered, and whom this workspace asks nothing else of, never
        // sees the page; a member who skipped it sees it again only once the owner changes the setup.
        boolean needsSetup = policy.isEnabled()
                && (seenRevision == null || seenRevision != policy.getRevision())
                && (choice == null || hasOpenRequiredLink(linkOptions));
        return new WorkspaceOnboardingDTO(
                context.displayName(),
                policy.isEnabled(),
                needsSetup,
                policy.isAiChoiceRequired() || choice != null,
                choice,
                availability.options(context.id()).stream()
                        .map(option -> new WorkspaceOnboardingDTO.WorkspaceAiOptionDTO(
                                option.choice(), option.practiceReviewsReady(), option.mentorReady()))
                        .toList(),
                linkOptions);
    }

    private static boolean hasOpenRequiredLink(List<WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO> linkOptions) {
        // A required link the workspace cannot offer right now is the owner's to repair; it never
        // holds a member's setup open.
        return linkOptions.stream().anyMatch(link -> link.required() && link.available() && !link.linked());
    }

    /** The choice is a boundary, not a pick from today's bindings: any of the four values is accepted. */
    @Transactional
    public WorkspaceOnboardingDTO choose(WorkspaceContext context, long accountId, MemberAiChoice choice) {
        requireSelf();
        requireMember(context.id(), accountId);
        writeChoice(accountId, choice);
        return memberState(context, accountId);
    }

    @Transactional(readOnly = true)
    public AccountAiChoiceDTO accountChoice(long accountId) {
        return choices.findById(accountId)
                .map(WorkspaceOnboardingService::toDTO)
                .orElseGet(() -> new AccountAiChoiceDTO(null, null));
    }

    @Transactional
    public AccountAiChoiceDTO chooseForAccount(long accountId, MemberAiChoice choice) {
        requireSelf();
        return toDTO(writeChoice(accountId, choice));
    }

    private AccountAiChoice writeChoice(long accountId, MemberAiChoice choice) {
        var row = choices.findById(accountId).orElseGet(() -> {
            var created = new AccountAiChoice();
            created.setAccountId(accountId);
            return created;
        });
        row.setAiChoice(choice);
        row.setUpdatedAt(clock.instant());
        return choices.save(row);
    }

    @Transactional
    public WorkspaceOnboardingDTO dismiss(WorkspaceContext context, long accountId) {
        requireSelf();
        var workspace = lockWorkspace(context.id());
        requireMember(context.id(), accountId);
        var policy = settings.findByWorkspaceId(context.id()).orElse(null);
        if (policy == null || !policy.isEnabled()) return memberState(context, accountId);
        markSeen(workspace, accountId, policy.getRevision());
        return memberState(context, accountId);
    }

    private void markSeen(Workspace workspace, long accountId, long revision) {
        var member = members.findByWorkspace_IdAndAccountId(workspace.getId(), accountId)
                .orElseGet(() -> {
                    var created = new WorkspaceMemberOnboarding();
                    created.setWorkspace(workspace);
                    created.setAccountId(accountId);
                    return created;
                });
        member.setSeenRevision(revision);
        member.setUpdatedAt(clock.instant());
        members.save(member);
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

    private static AccountAiChoiceDTO toDTO(AccountAiChoice row) {
        return new AccountAiChoiceDTO(row.getAiChoice(), row.getUpdatedAt());
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
