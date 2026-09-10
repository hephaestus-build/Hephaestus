package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessClient;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessFailure;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessFailure.Reason;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.InstallationCredential;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persist intent, then execute under current policy and identity locks. A crash leaves a readable intent, not success. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class GitHubAccessReconciliation {
    private final GitHubAccessTargetRepository targets;
    private final GitHubAccessMembershipRepository members;
    private final GitHubAccessActionRepository actions;
    private final GitHubAccessPolicyService policies;
    private final GitHubAccessEligibilityService evidence;
    private final AccountIdentityQuery identities;
    private final GitProviderRegistry providers;
    private final WorkspaceAccountMembershipRepository workspaceMembers;
    private final ConnectionRepository connections;
    private final CredentialReader credentials;
    private final GitHubAccessClient github;
    private final ConfigAuditPort audit;
    private final Clock clock;

    public record Person(long githubUserId, @Nullable Long accountId) {}

    public record Input(
            long workspaceId,
            long targetId,
            long configurationVersion,
            @Nullable Long organizationId,
            @Nullable Long scopeId,
            GitHubAccessEvidence.@Nullable Authorization authorization,
            GitHubAccessEvidence.@Nullable Eligibility eligibility,
            @Nullable String eligibilityBlocker,
            List<Person> people,
            boolean preview,
            boolean sourceEnded) {}

    /** Immutable addresses cross transaction boundaries; no pre-lock JPA entity is reused by a write. */
    @Transactional
    public Input snapshot(long workspaceId, long connectionId, boolean preview) {
        var target = targets.findByConnectionIdAndWorkspace_Id(connectionId, workspaceId)
                .orElseThrow();
        GitHubAccessEvidence.Eligibility eligibility = null;
        String blocker = null;
        try {
            eligibility = evidence.read(target, preview);
        } catch (IllegalArgumentException unavailable) {
            blocker = unavailable.getMessage();
        }
        List<Person> people = new ArrayList<>();
        for (var member : members.findByWorkspace_IdAndTarget_IdOrderById(workspaceId, target.getId()))
            people.add(new Person(member.getGithubUserId(), member.getAccountId()));
        if (eligibility != null) {
            for (var candidate : eligibility.candidates()) {
                Long id = candidate.githubUserId();
                if (id != null && people.stream().noneMatch(person -> person.githubUserId() == id))
                    people.add(new Person(id, candidate.accountId()));
            }
        }
        return new Input(
                workspaceId,
                target.getId(),
                target.getConfigurationVersion(),
                target.getOrganizationId(),
                target.getScopeId(),
                target.getAuthorization(),
                eligibility,
                blocker,
                List.copyOf(people),
                preview,
                evidence.sourceEnded(target));
    }

    /** Known local exits are latched even when the next GitHub request fails. */
    @Transactional
    public void retainDepartures(Input input) {
        policies.lockActive(input.workspaceId());
        lockEligibilitySource(input);
        input.people().stream()
                .map(Person::accountId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(identities::accountForUpdate);
        var target = policies.required(input.workspaceId(), input.targetId());
        requireVersion(input, target);
        for (var member : members.findByWorkspace_IdAndTarget_IdOrderById(input.workspaceId(), input.targetId())) {
            if ((member.isManaged() || unresolved(member) != null) && mustRevoke(input, target, member))
                member.setRevocationRequested(true);
        }
        target.setLastAttemptAt(clock.instant());
    }

    @Transactional(readOnly = true)
    public InstallationCredential installation(Input input) {
        var target = targets.findByIdAndWorkspace_Id(input.targetId(), input.workspaceId())
                .orElseThrow();
        requireVersion(input, target);
        var authorization = target.getAuthorization();
        if (!target.isAuthorityHeld() || authorization == null)
            throw failure(
                    Reason.AUTHORITY_LOST,
                    "Obtain fresh GitHub organization-owner approval; existing access has not been revoked");
        var connection = connections
                .findByIdAndWorkspaceId(target.getConnectionId(), input.workspaceId())
                .filter(value ->
                        value.getKind() == IntegrationKind.GITHUB_ACCESS && value.getState() == IntegrationState.ACTIVE)
                .orElseThrow(() ->
                        failure(Reason.CREDENTIALS, "Restore the Access App connection to inspect pending access"));
        var bundle = credentials
                .credentialsOf(connection)
                .orElseThrow(() -> failure(Reason.CREDENTIALS, "Restore the Access App installation credentials"));
        if (!(bundle instanceof InstallationCredential installation)
                || installation.installationId() != authorization.installationId()
                || !installation.issuer().equals("https://github.com"))
            throw failure(Reason.CREDENTIALS, "The stored installation does not match the owner-approved Access App");
        return installation;
    }

    @Transactional
    public void publishPreview(Input input, GitHubAccessClient.Inventory inventory) {
        policies.lockActive(input.workspaceId());
        var target = policies.required(input.workspaceId(), input.targetId());
        requireVersion(input, target);
        var eligibility = input.eligibility();
        if (eligibility == null || !evidence.sameCapture(eligibility, evidence.read(target, true)))
            throw failure(Reason.INCOMPLETE, "Refresh the eligibility evidence before previewing GitHub access");
        target.setPreview(new GitHubAccessEvidence.Preview(input.configurationVersion(), eligibility, inventory));
        target.setLastConfirmedAt(clock.instant());
        target.setFailureCode(null);
        target.setFailureReason(null);
        target.setRetryAt(null);
    }

    /** Commits a durable action before any membership mutation can reach GitHub. */
    @Transactional
    public @Nullable Long prepare(Input input, Person person, GitHubAccessClient.Membership observed) {
        var target = lock(input, person);
        var member = members.findByWorkspace_IdAndTarget_IdAndGithubUserId(
                        input.workspaceId(), input.targetId(), person.githubUserId())
                .orElseGet(() -> newMember(input, person, target));
        var before = GitHubAccessAudit.Membership.of(member);
        String previousLogin = member.getGithubLogin();
        observed(member, observed);
        var pending = unresolved(member);
        if (pending != null) {
            if (pending.getStatus() == GitHubAccessAction.Status.PENDING
                    && previousLogin != null
                    && !previousLogin.equalsIgnoreCase(observed.login())) {
                pending.setStatus(GitHubAccessAction.Status.MANUAL_RECOVERY);
                pending.setFailureCode(Reason.IDENTITY_CHANGED);
                pending.setFailureReason(
                        "An unconfirmed request used GitHub login " + previousLogin
                                + ", but the linked native identity now uses " + observed.login()
                                + ". Inspect both logins in the approved scope before resolving this action; automatic retries are stopped.");
                member.setBlocker(pending.getFailureReason());
            }
            return pending.getId();
        }
        if (!member.isManaged()
                && !member.isRevocationRequested()
                && observed.state() == GitHubAccessClient.State.ABSENT) rebind(input, member);
        if (member.isManaged() && mustRevoke(input, target, member)) member.setRevocationRequested(true);
        if (observed.state() == GitHubAccessClient.State.ABSENT && member.isRevocationRequested()) {
            member.setManaged(false);
            member.setRevocationRequested(false);
            member.setBlocker(null);
            auditMember(member, before);
            return null;
        }
        if (member.isManualException()) {
            member.setBlocker("External access is an owner-approved manual exception, not managed access");
            return null;
        }
        if (target.isPaused()) {
            member.setBlocker("GitHub writes are paused; existing access remains");
            return null;
        }
        GitHubAccessAction.Type type;
        if (member.isRevocationRequested()) {
            if (!member.isManaged()) {
                member.setBlocker(
                        "Unconfirmed grant needs an owner decision before this access can be adopted or removed");
                return null;
            }
            type = GitHubAccessAction.Type.REVOKE;
        } else if (eligible(input, target, member)) {
            if (observed.state() == GitHubAccessClient.State.ACTIVE
                    || observed.state() == GitHubAccessClient.State.PENDING) {
                member.setBlocker(member.isManaged() ? null : "Existing GitHub access needs explicit owner adoption");
                return null;
            }
            if (observed.state() != GitHubAccessClient.State.ABSENT) {
                member.setBlocker(observed.explanation());
                return null;
            }
            member.setManaged(false);
            type = GitHubAccessAction.Type.GRANT;
        } else {
            member.setBlocker(
                    !member.isEnrolled() && observed.state() == GitHubAccessClient.State.ABSENT
                            ? null
                            : input.eligibilityBlocker() == null
                                    ? "No current approved eligibility for a new grant"
                                    : input.eligibilityBlocker());
            return null;
        }
        if (observed.state() == GitHubAccessClient.State.PROTECTED) {
            member.setBlocker(observed.explanation());
            return null;
        }
        var action = new GitHubAccessAction();
        action.setWorkspace(target.getWorkspace());
        action.setTarget(target);
        action.setMembership(member);
        action.setOrganizationId(Objects.requireNonNull(target.getOrganizationId()));
        action.setScopeId(Objects.requireNonNull(target.getScopeId()));
        action.setGithubUserId(member.getGithubUserId());
        action.setConfigurationVersion(target.getConfigurationVersion());
        var eligibility = input.eligibility();
        if (eligibility != null) {
            action.setEligibilityConfigurationVersion(eligibility.configurationVersion());
            action.setEligibilityCapturedAt(eligibility.captureStartedAt());
            action.setEligibilitySourceVersion(eligibility.sourceVersion());
        }
        action.setType(type);
        action.setCreatedAt(clock.instant());
        action.setProviderState(observed.state());
        actions.saveAndFlush(action);
        auditMember(member, before);
        return action.getId();
    }

    /** The account locks serialize with unlink/erasure; the workspace lock serializes with policy/role changes. */
    @Transactional
    public void execute(Input input, Person person, long actionId, GitHubAccessClient.Session session) {
        var target = lock(input, person);
        var action = actions.findByIdAndWorkspace_IdAndTarget_Id(actionId, input.workspaceId(), input.targetId())
                .orElseThrow();
        if (action.getStatus() != GitHubAccessAction.Status.PENDING) return;
        var member = action.getMembership();
        var before = GitHubAccessAudit.Membership.of(member);
        if (target.isPaused()) {
            member.setBlocker("GitHub writes are paused; the pending action is retained");
            return;
        }
        requireAuthorization(target, session);
        if (action.getOrganizationId() != session.organizationId()
                || action.getScopeId() != session.scopeId()
                || action.getGithubUserId() != person.githubUserId())
            throw failure(Reason.TARGET_CHANGED, "The persisted action does not match this immutable target");
        var current = github.inspect(session, member.getGithubUserId());
        observed(member, current);
        if (action.getType() == GitHubAccessAction.Type.GRANT) {
            if (current.state() == GitHubAccessClient.State.WAITING_ORGANIZATION) {
                action.setStatus(GitHubAccessAction.Status.INVALIDATED);
                member.setManaged(false);
                member.setRevocationRequested(false);
                member.setBlocker(current.explanation());
                auditMember(member, before);
                return;
            }
            if (current.state() != GitHubAccessClient.State.ABSENT) {
                action.setStatus(GitHubAccessAction.Status.MANUAL_RECOVERY);
                action.setFailureCode(Reason.WRITE_UNCONFIRMED);
                action.setFailureReason(
                        "Access exists after an unconfirmed grant; explicitly adopt it or retain it as unmanaged rather than repeating the request");
                member.setBlocker(action.getFailureReason());
                return;
            }
            if (!eligible(input, target, member)
                    || action.getConfigurationVersion() != target.getConfigurationVersion()) {
                action.setStatus(GitHubAccessAction.Status.INVALIDATED);
                member.setBlocker("The grant became stale; refresh current eligibility before retrying");
                return;
            }
        } else {
            if (current.state() == GitHubAccessClient.State.ABSENT) {
                confirm(action, member, current);
                auditMember(member, before);
                return;
            }
            if (!member.isManaged() || !member.isRevocationRequested()) {
                action.setStatus(GitHubAccessAction.Status.INVALIDATED);
                member.setBlocker("Removal no longer has managed authority; review the current policy");
                return;
            }
        }
        action.setLastAttemptAt(clock.instant());
        action.setAttempts(action.getAttempts() + 1);
        // The intent was committed by prepare. If this transaction rolls back after provider success,
        // its next execution inspects external state before doing anything else.
        var result = action.getType() == GitHubAccessAction.Type.GRANT
                ? github.grant(session, member.getGithubUserId())
                : github.revoke(session, member.getGithubUserId());
        confirm(action, member, result);
        auditMember(member, before);
    }

    public enum Decision {
        ADOPT,
        MANUAL_EXCEPTION,
        RESET_EXCEPTION
    }

    /** Adoption never sends a provider write. It assigns responsibility for freshly inspected existing access. */
    @Transactional
    public void decide(
            Input input,
            Person person,
            Decision decision,
            @Nullable String reason,
            GitHubAccessClient.Session session) {
        var target = lock(input, person);
        policies.requirePermission(input.workspaceId(), true);
        requireAuthorization(target, session);
        var member = members.findByWorkspace_IdAndTarget_IdAndGithubUserId(
                        input.workspaceId(), input.targetId(), person.githubUserId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Refresh the target before deciding how to manage this membership"));
        var before = GitHubAccessAudit.Membership.of(member);
        var current = github.inspect(session, member.getGithubUserId());
        observed(member, current);
        if (decision == Decision.ADOPT) {
            if (current.state() != GitHubAccessClient.State.ACTIVE
                    && current.state() != GitHubAccessClient.State.PENDING)
                throw new IllegalArgumentException(
                        "Only existing ordinary membership or a pending invitation can be adopted; protected access stays unmanaged");
            boolean removalOnly =
                    member.isRevocationRequested() || target.getStatus() == GitHubAccessTarget.Status.ENDING;
            if (!removalOnly && !eligible(input, target, member))
                throw new IllegalArgumentException("Approve current eligibility before adopting this membership");
            member.setManaged(true);
            member.setManualException(false);
            member.setExceptionReason(null);
            member.setRevocationRequested(removalOnly);
            member.setBlocker(removalOnly ? "Existing access was adopted for confirmed removal" : null);
        } else if (decision == Decision.MANUAL_EXCEPTION) {
            if (reason == null || reason.isBlank() || reason.length() > 512)
                throw new IllegalArgumentException("Explain why this access will remain unmanaged");
            member.setManaged(false);
            member.setManualException(true);
            member.setExceptionReason(reason);
            member.setRevocationRequested(false);
            member.setBlocker("The workspace owner retained this external access as an unmanaged exception");
        } else {
            member.setManualException(false);
            member.setExceptionReason(null);
            member.setBlocker("Exception reset; existing access still needs fresh explicit adoption");
        }
        if (decision != Decision.RESET_EXCEPTION) {
            var pending = unresolved(member);
            if (pending != null) {
                pending.setStatus(GitHubAccessAction.Status.INVALIDATED);
                pending.setFailureReason(
                        "The workspace owner resolved the unconfirmed intent through a fresh membership decision");
            }
        }
        auditMember(member, before);
    }

    @Transactional
    public void failedAction(Input input, long actionId, GitHubAccessFailure failure) {
        policies.lockActive(input.workspaceId());
        var target = policies.required(input.workspaceId(), input.targetId());
        var action = actions.findByIdAndWorkspace_IdAndTarget_Id(actionId, input.workspaceId(), input.targetId())
                .orElseThrow();
        if (action.getStatus() != GitHubAccessAction.Status.PENDING) return;
        if (failure.reason() == Reason.IDENTITY_CHANGED) action.setStatus(GitHubAccessAction.Status.MANUAL_RECOVERY);
        action.setFailureCode(failure.reason());
        action.setFailureReason(failure.getMessage());
        action.setRetryAt(failure.retryAt());
        action.setLastAttemptAt(clock.instant());
        action.setAttempts(action.getAttempts() + 1);
        action.getMembership().setBlocker(failure.getMessage());
        target.setFailureCode(failure.reason());
        target.setFailureReason(failure.getMessage());
        target.setRetryAt(failure.retryAt());
    }

    @Transactional
    public void failed(long workspaceId, long connectionId, GitHubAccessFailure failure) {
        var target = targets.findByConnectionIdAndWorkspace_Id(connectionId, workspaceId)
                .orElse(null);
        if (target == null) return;
        target.setLastAttemptAt(clock.instant());
        target.setFailureCode(failure.reason());
        target.setFailureReason(failure.getMessage());
        target.setRetryAt(failure.retryAt());
    }

    @Transactional
    public boolean finish(Input input) {
        policies.lockActive(input.workspaceId());
        var target = policies.required(input.workspaceId(), input.targetId());
        requireVersion(input, target);
        var all = members.findByWorkspace_IdAndTarget_IdOrderById(input.workspaceId(), input.targetId());
        boolean pending = !actions.findByWorkspace_IdAndTarget_IdAndStatusInOrderById(
                        input.workspaceId(),
                        input.targetId(),
                        Set.of(GitHubAccessAction.Status.PENDING, GitHubAccessAction.Status.MANUAL_RECOVERY))
                .isEmpty();
        boolean obligations =
                pending || all.stream().anyMatch(member -> member.isManaged() || member.isRevocationRequested());
        if (target.getStatus() == GitHubAccessTarget.Status.ENDING && !obligations) {
            var before = GitHubAccessAudit.Policy.of(target);
            target.setStatus(GitHubAccessTarget.Status.ENDED);
            target.setAuthorityHeld(false);
            target.setAuthorization(null);
            target.setPreview(null);
            audit.record(ConfigAuditEntry.updated(
                    ConfigAuditEntityType.GITHUB_ACCESS_POLICY,
                    target.getId(),
                    input.workspaceId(),
                    before,
                    GitHubAccessAudit.Policy.of(target)));
        }
        boolean unlinked = input.eligibility() != null
                && input.eligibility().candidates().stream().anyMatch(candidate -> candidate.githubUserId() == null);
        boolean unresolved = pending
                || unlinked
                || input.eligibilityBlocker() != null
                || all.stream().anyMatch(member -> member.getBlocker() != null && !member.isManualException());
        target.setLastConfirmedAt(clock.instant());
        if (!unresolved) {
            target.setFailureCode(null);
            target.setFailureReason(null);
            target.setRetryAt(null);
        } else {
            target.setFailureCode(Reason.INCOMPLETE);
            target.setRetryAt(null);
            target.setFailureReason(
                    unlinked
                            ? "Eligible developers must link their GitHub.com identities before access can be granted"
                            : input.eligibilityBlocker() != null
                                    ? input.eligibilityBlocker()
                                    : "Some memberships need attention; inspect the per-person blockers and pending actions");
        }
        return unresolved;
    }

    private void lockEligibilitySource(Input input) {
        evidence.lockSource(targets.findByIdAndWorkspace_Id(input.targetId(), input.workspaceId())
                .orElseThrow());
    }

    private GitHubAccessTarget lock(Input input, Person person) {
        policies.lockActive(input.workspaceId());
        lockEligibilitySource(input);
        var authorization = input.authorization();
        var eligibility = input.eligibility();
        Stream.concat(
                        Stream.of(person.accountId(), authorization == null ? null : authorization.accountId()),
                        eligibility == null
                                ? Stream.empty()
                                : eligibility.candidates().stream()
                                        .filter(candidate ->
                                                Objects.equals(candidate.githubUserId(), person.githubUserId()))
                                        .map(GitHubAccessEvidence.Candidate::accountId))
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(identities::accountForUpdate);
        var target = policies.required(input.workspaceId(), input.targetId());
        requireVersion(input, target);
        return target;
    }

    private void requireVersion(Input input, GitHubAccessTarget target) {
        if (target.getConfigurationVersion() != input.configurationVersion()
                || !Objects.equals(target.getAuthorization(), input.authorization()))
            throw failure(
                    Reason.TARGET_CHANGED,
                    "The target or authorizing identity changed; retry with a fresh preview and evidence");
    }

    private void requireAuthorization(GitHubAccessTarget target, GitHubAccessClient.Session session) {
        var authorization = target.getAuthorization();
        if (!target.isAuthorityHeld()
                || authorization == null
                || !Objects.equals(target.getOrganizationId(), session.organizationId())
                || !Objects.equals(target.getScopeId(), session.scopeId())
                || authorization.installationId() != session.installationId()
                || !authorization.permissions().equals(session.permissions())
                || identities
                        .account(authorization.accountId())
                        .filter(AccountIdentityQuery.AccountView::active)
                        .isEmpty()
                || !hasGithubLink(
                        authorization.accountId(), authorization.identityLinkId(), authorization.githubUserId()))
            throw failure(
                    Reason.AUTHORITY_LOST,
                    "Current linked organization-owner authority is missing; obtain a fresh approval");
        github.requireOrganizationOwner(session, authorization.githubUserId());
    }

    private boolean mustRevoke(Input input, GitHubAccessTarget target, GitHubAccessMembership member) {
        if (member.isRevocationRequested()
                || !member.isEnrolled()
                || target.getStatus() == GitHubAccessTarget.Status.ENDING
                || input.sourceEnded()) return true;
        Long accountId = member.getAccountId();
        Long githubLink = member.getGithubIdentityLinkId();
        Long directoryLink = member.getDirectoryIdentityLinkId();
        if (accountId == null
                || githubLink == null
                || identities
                        .account(accountId)
                        .filter(AccountIdentityQuery.AccountView::active)
                        .isEmpty()
                || workspaceMembers
                        .findByWorkspace_IdAndAccountId(input.workspaceId(), accountId)
                        .filter(value -> value.isActiveAt(clock.instant()))
                        .isEmpty()
                || !hasGithubLink(accountId, githubLink, member.getGithubUserId())) return true;
        if (target.getSource() == GitHubAccessTarget.Source.REQUEST) {
            try {
                return evidence.read(target, false).candidates().stream()
                        .noneMatch(candidate -> candidate.accountId() == accountId
                                && Objects.equals(candidate.githubUserId(), member.getGithubUserId())
                                && Objects.equals(candidate.githubIdentityLinkId(), githubLink));
            } catch (IllegalArgumentException unavailable) {
                return false;
            }
        }
        if (directoryLink == null
                || identities.activeLinksForAccount(accountId).stream()
                        .noneMatch(link -> link.identityLinkId().equals(directoryLink)
                                && link.gitProviderId().equals(target.getDirectoryProviderId())
                                && link.subject().equals(member.getDirectorySubject()))) return true;
        var eligibility = input.eligibility();
        if (eligibility == null || !eligibility.confirmedDepartures().contains(member.getDirectorySubject()))
            return false;
        try {
            return evidence.sameCapture(eligibility, evidence.read(target, false));
        } catch (IllegalArgumentException unavailable) {
            return false;
        }
    }

    private boolean eligible(Input input, GitHubAccessTarget target, GitHubAccessMembership member) {
        var eligibility = input.eligibility();
        if (eligibility == null
                || target.getStatus() != GitHubAccessTarget.Status.ACTIVE
                || member.isManualException()
                || member.isRevocationRequested()
                || !member.isEnrolled()
                || !policies.isCurrentOwner(input.workspaceId(), target.getApprovedByAccountId())
                || target.getAuthorization() == null
                || target.getAuthorization().configurationVersion() != target.getConfigurationVersion()
                || mustRevoke(input, target, member)) return false;
        try {
            if (!evidence.sameCapture(eligibility, evidence.read(target, false))) return false;
        } catch (IllegalArgumentException unavailable) {
            return false;
        }
        boolean candidate = eligibility.candidates().stream()
                .anyMatch(value -> Objects.equals(value.accountId(), member.getAccountId())
                        && Objects.equals(value.githubUserId(), member.getGithubUserId())
                        && Objects.equals(value.githubIdentityLinkId(), member.getGithubIdentityLinkId())
                        && Objects.equals(value.directoryIdentityLinkId(), member.getDirectoryIdentityLinkId()));
        if (!candidate) return false;
        Long accountId = member.getAccountId();
        return accountId != null
                && members.findByAccountId(accountId).stream()
                        .noneMatch(other -> other.getTarget().getId().equals(target.getId())
                                && !other.getId().equals(member.getId())
                                && (other.isManaged() || other.isRevocationRequested() || unresolved(other) != null));
    }

    private boolean hasGithubLink(long accountId, long linkId, long userId) {
        Long providerId =
                providers.findProviderId("GITHUB", "https://github.com").orElse(null);
        return providerId != null
                && identities.activeLinksForAccount(accountId).stream()
                        .anyMatch(link -> link.identityLinkId() == linkId
                                && link.gitProviderId().equals(providerId)
                                && link.subject().equals(Long.toString(userId)));
    }

    private void rebind(Input input, GitHubAccessMembership member) {
        var eligibility = input.eligibility();
        if (eligibility == null) return;
        eligibility.candidates().stream()
                .filter(candidate -> Objects.equals(candidate.githubUserId(), member.getGithubUserId()))
                .findFirst()
                .ifPresent(candidate -> {
                    if (!Objects.equals(member.getAccountId(), candidate.accountId())) member.setEnrolled(true);
                    member.setAccountId(candidate.accountId());
                    member.setGithubIdentityLinkId(candidate.githubIdentityLinkId());
                    member.setDirectoryIdentityLinkId(candidate.directoryIdentityLinkId());
                    member.setDirectorySubject(candidate.directorySubject());
                });
    }

    private GitHubAccessMembership newMember(Input input, Person person, GitHubAccessTarget target) {
        var eligibility = input.eligibility();
        var candidate = eligibility == null
                ? null
                : eligibility.candidates().stream()
                        .filter(value -> Objects.equals(value.githubUserId(), person.githubUserId()))
                        .findFirst()
                        .orElse(null);
        if (candidate == null)
            throw failure(Reason.IDENTITY_CHANGED, "The linked identity no longer has captured eligibility");
        var member = new GitHubAccessMembership();
        member.setWorkspace(target.getWorkspace());
        member.setTarget(target);
        member.setAccountId(candidate.accountId());
        member.setGithubUserId(person.githubUserId());
        member.setGithubIdentityLinkId(candidate.githubIdentityLinkId());
        member.setDirectoryIdentityLinkId(candidate.directoryIdentityLinkId());
        member.setDirectorySubject(candidate.directorySubject());
        return members.saveAndFlush(member);
    }

    private @Nullable GitHubAccessAction unresolved(GitHubAccessMembership member) {
        return actions
                .findByWorkspace_IdAndTarget_IdAndStatusInOrderById(
                        member.getWorkspace().getId(),
                        member.getTarget().getId(),
                        Set.of(GitHubAccessAction.Status.PENDING, GitHubAccessAction.Status.MANUAL_RECOVERY))
                .stream()
                .filter(action -> action.getMembership().getId().equals(member.getId()))
                .findFirst()
                .orElse(null);
    }

    private void observed(GitHubAccessMembership member, GitHubAccessClient.Membership observed) {
        if (member.getGithubUserId() != observed.userId())
            throw failure(Reason.IDENTITY_CHANGED, "GitHub returned a different immutable identity");
        member.setExternalState(observed.state());
        member.setInvitationId(observed.invitationId());
        member.setGithubLogin(observed.login());
        member.setConfirmedAt(clock.instant());
    }

    private void confirm(
            GitHubAccessAction action, GitHubAccessMembership member, GitHubAccessClient.Membership result) {
        boolean grant = action.getType() == GitHubAccessAction.Type.GRANT;
        if ((grant
                        && result.state() != GitHubAccessClient.State.PENDING
                        && result.state() != GitHubAccessClient.State.ACTIVE)
                || (!grant && result.state() != GitHubAccessClient.State.ABSENT))
            throw failure(Reason.WRITE_UNCONFIRMED, "GitHub has not confirmed the intended membership change");
        observed(member, result);
        member.setManaged(grant);
        member.setRevocationRequested(false);
        member.setBlocker(null);
        action.setStatus(GitHubAccessAction.Status.CONFIRMED);
        action.setProviderState(result.state());
        action.setConfirmedAt(clock.instant());
        action.setFailureCode(null);
        action.setFailureReason(null);
        action.setRetryAt(null);
    }

    private void auditMember(GitHubAccessMembership member, GitHubAccessAudit.Membership before) {
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.GITHUB_ACCESS_MEMBERSHIP,
                member.getId(),
                member.getWorkspace().getId(),
                before,
                GitHubAccessAudit.Membership.of(member)));
    }

    private static GitHubAccessFailure failure(Reason reason, String message) {
        return new GitHubAccessFailure(reason, message);
    }
}
