package de.tum.cit.aet.hephaestus.productfeedback.adapter;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.productfeedback.DestinationSettings;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRepositoryExclusion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The configured private repository holds reports for its maintainers, so it stays out of every workspace. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class PrivateRepositoryExclusion implements WorkspaceRepositoryExclusion {
    private final DestinationSettings settings;

    @Override
    public boolean excludes(String nameWithOwner) {
        return settings.excludesRepository(nameWithOwner);
    }
}
