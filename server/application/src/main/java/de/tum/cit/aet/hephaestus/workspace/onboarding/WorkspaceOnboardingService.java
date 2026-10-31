package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiReadiness;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceAiAvailability;
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
    private final MembershipRepository members;
    private final AccountDirectory choices;
    private final AccountMembershipQuery memberships;
    private final WorkspaceOnboardingLinks links;
    private final WorkspaceAiAvailability availability;
    private final MemberAiReadiness aiReadiness;
    private final ConfigAuditPort audit;

    @Transactional(readOnly = true)
    public WorkspaceOnboardingDTO state(WorkspaceContext context, long accountId) {
        return memberState(context, accountId);
    }

    private WorkspaceOnboardingDTO memberState(WorkspaceContext context, long accountId) {
        requireMember(context.id(), accountId);
        var policy = settings.findByWorkspaceId(context.id()).orElseGet(WorkspaceOnboardingSettings::new);
        var choice = choices.find(accountId)
                .map(AccountDirectory.AccountView::aiChoice)
                .orElse(null);
        var linkOptions = links.options(context.id(), accountId, policy.getRequiredConnectionIds());
        @Nullable
        Long seenRevision = members.findByWorkspace_IdAndId_AccountId(context.id(), accountId)
                .map(Membership::getOnboardingSeenRevision)
                .orElse(null);
        // A member who has already answered, and whom this workspace asks nothing else of, never
        // sees the page; a member who skipped it sees it again only once the owner changes the
        // setup.
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
                                option.choice(),
                                option.practiceReviewsReady(),
                                option.mentorReady(),
                                option.models().stream()
                                        .map(WorkspaceOnboardingService::toDTO)
                                        .toList()))
                        .toList(),
                linkOptions,
                aiReadiness.conversationRefusal(context.id(), accountId).orElse(null));
    }

    private static boolean hasOpenRequiredLink(List<WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO> linkOptions) {
        // A required link the workspace cannot offer right now is the owner's to repair; it never
        // holds a member's setup open.
        return linkOptions.stream().anyMatch(link -> link.required() && link.available() && !link.linked());
    }

    /**
     * The choice is a boundary, not a pick from today's bindings: any of the three values is
     * accepted.
     */
    @Transactional
    public WorkspaceOnboardingDTO choose(WorkspaceContext context, long accountId, AiChoice choice) {
        requireMember(context.id(), accountId);
        writeChoice(accountId, choice);
        return memberState(context, accountId);
    }

    @Transactional(readOnly = true)
    public AccountAiChoiceDTO accountChoice(long accountId) {
        var row = choices.find(accountId).orElse(null);
        return new AccountAiChoiceDTO(row == null ? null : row.aiChoice(), row == null ? null : row.aiChoiceAt());
    }

    @Transactional
    public AccountAiChoiceDTO chooseForAccount(long accountId, AiChoice choice) {
        var row = writeChoice(accountId, choice);
        return new AccountAiChoiceDTO(row.aiChoice(), row.aiChoiceAt());
    }

    private static WorkspaceOnboardingDTO.WorkspaceAiModelDTO toDTO(WorkspaceAiAvailability.Model model) {
        return new WorkspaceOnboardingDTO.WorkspaceAiModelDTO(
                model.name(), model.brand(), model.connectionPlatform(), model.dataHandlingTier());
    }

    private AccountDirectory.AccountView writeChoice(long accountId, AiChoice choice) {
        choices.setAiChoice(accountId, choice);
        return choices.find(accountId).orElseThrow();
    }

    @Transactional
    public WorkspaceOnboardingDTO dismiss(WorkspaceContext context, long accountId) {
        var workspace = lockWorkspace(context.id());
        requireMember(context.id(), accountId);
        var policy = settings.findByWorkspaceId(context.id()).orElse(null);
        if (policy == null || !policy.isEnabled()) return memberState(context, accountId);
        markSeen(workspace, accountId, policy.getRevision());
        return memberState(context, accountId);
    }

    private void markSeen(Workspace workspace, long accountId, long revision) {
        var member = members.findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId)
                .orElseThrow();
        member.setOnboardingSeenRevision(revision);
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
            throw conflict("Onboarding settings changed. Reload the settings before you save.");
        if (request.requiredConnectionIds().stream().distinct().count()
                != request.requiredConnectionIds().size())
            throw conflict("Select each required integration only once.");
        var available = links.options(context.id(), accountId, List.of());
        for (long id : request.requiredConnectionIds())
            if (available.stream().noneMatch(link -> link.connectionId() == id && link.available()))
                throw conflict("Required accounts must use an active integration and enabled"
                        + " account-linking provider configured for this workspace.");
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
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Join this workspace before you start onboarding.");
    }

    private Workspace lockWorkspace(long id) {
        return workspaces
                .findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "We could not find that workspace. It may have been"
                                + " deleted. Reload the page to see what is current."));
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
