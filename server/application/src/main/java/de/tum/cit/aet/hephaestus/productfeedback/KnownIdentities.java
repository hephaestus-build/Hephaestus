package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSummaryQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.spi.MembershipPageQuery;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * The names the instance knows around a reporter: their own, their colleagues' in each of their workspaces, and the
 * workspaces' names and slugs. Redaction removes them, and a person's name keeps a report off the public route.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class KnownIdentities {
    /** Members read per workspace; a larger workspace still has its names removed by the model's instructions. */
    private static final int MEMBERS_PER_WORKSPACE = 1000;

    private final AccountSummaryQuery accounts;
    private final AccountMembershipQuery memberships;
    private final MembershipPageQuery members;

    Redaction.Identities of(long accountId) {
        Set<Long> people = new HashSet<>();
        people.add(accountId);
        Set<String> workspaces = new LinkedHashSet<>();
        for (var membership : memberships.membershipsForAccount(accountId)) {
            workspaces.add(membership.workspaceName());
            workspaces.add(membership.workspaceSlug());
            people.addAll(members.viewableAccountIds(membership.workspaceId(), PageRequest.of(0, MEMBERS_PER_WORKSPACE))
                    .getContent());
        }
        Set<String> names = new LinkedHashSet<>();
        for (var summary : accounts.findAllByIds(people).values()) {
            names.add(summary.displayName());
            String email = summary.email();
            if (email != null && email.indexOf('@') > 0) names.add(email.substring(0, email.indexOf('@')));
        }
        return new Redaction.Identities(names, workspaces);
    }
}
