package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.ResultProofAccess;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Current result visibility is owned by practices; a captured quote alone grants no access. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class CitedSourceAccess {
    private final ResultProofAccess results;

    public Set<UUID> permitsAllHistory(long workspaceId, Collection<Result> rows, SourceUsePurpose purpose) {
        return results.permitsAllHistory(workspaceId, rows, purpose);
    }

    public Set<UUID> permitsAll(long workspaceId, Collection<Result> rows, SourceUsePurpose purpose) {
        return results.permitsAll(workspaceId, rows, purpose);
    }

    public <T> T asOneRead(long workspaceId, Supplier<T> read) {
        return results.asOneRead(workspaceId, read);
    }
}
