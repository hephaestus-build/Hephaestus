package de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest;

import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationAdmission;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery.MembershipView;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationReadinessQuery;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Where a weekly digest can be written: workspaces where the account is an active member, Hephaestus's model is ready, and
 * Hephaestus would answer the account, so a reply in the digest's thread can be answered. There is no workspace admin
 * switch: admins already decide its inputs, such as the Hephaestus model.
 */
@Component
@RequiredArgsConstructor
@WorkspaceAgnostic("Availability is read per account, across the workspaces it is a member of")
public class WeeklyDigestAvailability {

    private final AccountMembershipQuery memberships;
    private final ConversationReadinessQuery readiness;
    private final ConversationAdmission admission;

    /** The workspaces the account has not left. */
    public List<MembershipView> memberships(long accountId) {
        return memberships.membershipsForAccount(accountId);
    }

    /** Whether a digest can be written for the account in the workspace now. */
    public boolean available(long workspaceId, long accountId) {
        return memberships.isActiveVisibleMember(workspaceId, accountId)
                && readiness.isReady(workspaceId)
                && admission.refusal(workspaceId, accountId).isEmpty();
    }

    /** Whether at least one of the account's workspaces can hold a digest: User settings shows the switch then. */
    public boolean availableFor(long accountId) {
        return memberships(accountId).stream().anyMatch(membership -> available(membership.workspaceId(), accountId));
    }
}
