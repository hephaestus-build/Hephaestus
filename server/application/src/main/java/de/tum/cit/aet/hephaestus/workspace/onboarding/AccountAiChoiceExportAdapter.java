package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountAiChoiceExport;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class AccountAiChoiceExportAdapter implements AccountAiChoiceExport {
    private final AccountAiChoiceRepository choices;

    @Override
    @Transactional(readOnly = true)
    public @Nullable Choice choice(long accountId) {
        return choices.findById(accountId)
                .map(row -> new Choice(row.getAiChoice().name(), row.getUpdatedAt()))
                .orElse(null);
    }
}
