package de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest;

import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAdmission;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorReadinessQuery;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery.WorkspaceMembershipView;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Where a weekly digest can be written: workspaces where the developer is a member, Heph's model is ready, and Heph
 * would answer them, so a reply in the digest's thread can be answered. There is no workspace admin switch: admins
 * already decide its inputs, such as the Heph model.
 */
@Component
@RequiredArgsConstructor
@WorkspaceAgnostic("Availability is read per account, across the workspaces it is a member of")
public class WeeklyDigestAvailability {

    private final AccountWorkspaceMembershipQuery memberships;
    private final MentorReadinessQuery readiness;
    private final MentorAdmission admission;

    /** The account's memberships that name the workspace member who holds its Heph conversation. */
    public List<WorkspaceMembershipView> memberships(long accountId) {
        return memberships.membershipsForAccount(accountId).stream()
                .filter(membership -> membership.memberId() != null)
                .toList();
    }

    /** Whether a digest can be written for the developer in the workspace now. */
    public boolean available(long workspaceId, long developerId) {
        return readiness.isReady(workspaceId)
                && admission.refusal(workspaceId, developerId).isEmpty();
    }

    /** Whether at least one of the account's workspaces can hold a digest: User settings shows the switch then. */
    public boolean availableFor(long accountId) {
        return memberships(accountId).stream()
                .anyMatch(membership ->
                        available(membership.workspaceId(), Objects.requireNonNull(membership.memberId())));
    }
}
