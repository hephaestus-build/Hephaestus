package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.auth.AccountService;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commit the hold, request and account status together before the caller can see a pending reply. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class AccountDeletionAdmission {

    private final PersonDataService personData;
    private final AccountService accounts;

    @Transactional
    public boolean needsAdministrator(long accountId) {
        var request = personData.admitAccountErasure(accountId);
        accounts.softDelete(accountId);
        return request != null && request.getState() == PersonDataRequest.State.FAILED;
    }
}
