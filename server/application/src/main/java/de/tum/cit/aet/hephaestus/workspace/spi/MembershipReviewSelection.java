package de.tum.cit.aet.hephaestus.workspace.spi;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface MembershipReviewSelection {
    Set<Long> eligibleAccountIds(long workspaceId);

    Set<Long> eligibleAccountIds(long workspaceId, Collection<Long> accountIds);

    /** The listed accounts that are eligible and that the workspace's people scope admits. */
    Set<Long> inScopeAccountIds(long workspaceId, Collection<Long> accountIds);

    List<Long> selectedAccountIds(long workspaceId);

    void replaceSelection(long workspaceId, Collection<Long> accountIds);
}
