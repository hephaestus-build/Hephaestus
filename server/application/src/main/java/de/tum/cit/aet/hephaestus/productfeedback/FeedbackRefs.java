package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSummaryQuery;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackAccountRefDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackWorkspaceRefDTO;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Resolves the account and workspace ids on a page of records to names, one lookup each per page. */
@Component
@RequiredArgsConstructor
class FeedbackRefs {
    private final AccountSummaryQuery accounts;
    private final WorkspaceSummaryQuery workspaces;

    Resolved resolve(
            Collection<? extends @Nullable Long> accountIds, Collection<? extends @Nullable Long> workspaceIds) {
        Map<Long, FeedbackAccountRefDTO> accountRefs = new HashMap<>();
        accounts.findAllByIds(present(accountIds))
                .values()
                .forEach(a -> accountRefs.put(a.id(), new FeedbackAccountRefDTO(a.id(), a.displayName(), a.email())));
        Map<Long, FeedbackWorkspaceRefDTO> workspaceRefs = new HashMap<>();
        workspaces
                .findAllByIds(present(workspaceIds))
                .values()
                .forEach(
                        w -> workspaceRefs.put(w.id(), new FeedbackWorkspaceRefDTO(w.id(), w.slug(), w.displayName())));
        return new Resolved(accountRefs, workspaceRefs);
    }

    private static Set<Long> present(Collection<? extends @Nullable Long> ids) {
        Set<Long> set = new HashSet<>();
        for (Long id : ids) if (id != null) set.add(id);
        return set;
    }

    record Resolved(Map<Long, FeedbackAccountRefDTO> accounts, Map<Long, FeedbackWorkspaceRefDTO> workspaces) {
        @Nullable
        FeedbackAccountRefDTO account(@Nullable Long id) {
            return id == null ? null : accounts.get(id);
        }

        @Nullable
        FeedbackWorkspaceRefDTO workspace(@Nullable Long id) {
            return id == null ? null : workspaces.get(id);
        }
    }
}
