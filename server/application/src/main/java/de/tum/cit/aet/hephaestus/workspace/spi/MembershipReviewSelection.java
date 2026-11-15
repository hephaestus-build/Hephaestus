package de.tum.cit.aet.hephaestus.workspace.spi;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface MembershipReviewSelection {
    Set<Long> eligibleAccountIds(long workspaceId);

    Set<Long> eligibleAccountIds(long workspaceId, Collection<Long> accountIds);

    /**
     * The accounts of {@code eligibleAccountIds}, a result of {@link #eligibleAccountIds}, that the workspace's people
     * scope admits.
     */
    Set<Long> peopleScopeAdmits(long workspaceId, Set<Long> eligibleAccountIds);

    List<Long> selectedAccountIds(long workspaceId);

    void replaceSelection(long workspaceId, Collection<Long> accountIds);
}
