package de.tum.cit.aet.hephaestus.account.adapter;

import de.tum.cit.aet.hephaestus.account.PersonalGoals;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPersonalGoalExport;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class PersonalGoalExportAdapter implements AccountPersonalGoalExport {
    private final PersonalGoals goals;

    // ExportGenerationWorker binds the owner and holds the source fence before this read.
    @Override
    public @Nullable String words(long accountId) {
        return goals.wordsForExport(accountId);
    }
}
