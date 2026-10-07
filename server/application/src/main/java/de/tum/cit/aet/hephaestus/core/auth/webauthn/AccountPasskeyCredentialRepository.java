package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

/** Adapts Spring's credential repositories to immutable local account IDs. */
public final class AccountPasskeyCredentialRepository implements UserCredentialRepository {
    private final AccountRepository accounts;
    private final PasskeyCredentialRepository credentials;
    private final PasskeyJson json;

    public AccountPasskeyCredentialRepository(
            AccountRepository accounts, PasskeyCredentialRepository credentials, PasskeyJson json) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.json = json;
    }

    @Override
    public void delete(Bytes id) {
        credentials.deleteById(id.toBase64UrlString());
    }

    @Override
    public void save(CredentialRecord record) {
        Account account = accounts.findByPasskeyUserHandle(
                        record.getUserEntityUserId().toBase64UrlString())
                .orElseThrow();
        String id = record.getCredentialId().toBase64UrlString();
        PasskeyCredential existing = credentials.findById(id).orElse(null);
        if (existing != null && !Objects.equals(existing.getAccount().getId(), account.getId())) {
            throw new IllegalArgumentException("Credential belongs to another account");
        }
        credentials.save(new PasskeyCredential(id, account, json.writeCredential(record)));
    }

    @Override
    public @Nullable CredentialRecord findByCredentialId(Bytes id) {
        return credentials
                .findById(id.toBase64UrlString())
                .map(c -> json.readCredential(c.getRecordJson()))
                .orElse(null);
    }

    @Override
    public List<CredentialRecord> findByUserId(Bytes id) {
        return accounts.findByPasskeyUserHandle(id.toBase64UrlString())
                .map(a -> credentials.findByAccountId(Objects.requireNonNull(a.getId())).stream()
                        .<CredentialRecord>map(c -> json.readCredential(c.getRecordJson()))
                        .toList())
                .orElse(List.of());
    }
}
