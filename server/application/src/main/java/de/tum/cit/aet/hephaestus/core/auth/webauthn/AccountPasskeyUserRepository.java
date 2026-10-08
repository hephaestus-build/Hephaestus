package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;

/** User handles belong to local accounts, never to provider identities. */
public final class AccountPasskeyUserRepository implements PublicKeyCredentialUserEntityRepository {
    private final AccountRepository accounts;

    public AccountPasskeyUserRepository(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public @Nullable PublicKeyCredentialUserEntity findByUsername(String username) {
        return accounts.findById(Long.valueOf(username))
                .filter(a -> a.getPasskeyUserHandle() != null)
                .map(this::user)
                .orElse(null);
    }

    @Override
    public @Nullable PublicKeyCredentialUserEntity findById(Bytes id) {
        return accounts.findByPasskeyUserHandle(id.toBase64UrlString())
                .map(this::user)
                .orElse(null);
    }

    @Override
    public void save(PublicKeyCredentialUserEntity user) {
        Account account = accounts.findById(Long.valueOf(user.getName())).orElseThrow();
        String existing = account.getPasskeyUserHandle();
        if (existing != null && !existing.equals(user.getId().toBase64UrlString())) {
            throw new IllegalArgumentException("Account user handle cannot change");
        }
        account.setPasskeyUserHandle(user.getId().toBase64UrlString());
        accounts.save(account);
    }

    @Override
    public void delete(Bytes id) {
        throw new UnsupportedOperationException("Account erasure owns user-handle deletion");
    }

    private PublicKeyCredentialUserEntity user(Account account) {
        return ImmutablePublicKeyCredentialUserEntity.builder()
                .name(Objects.requireNonNull(account.getId()).toString())
                .displayName(account.getDisplayName())
                .id(Bytes.fromBase64(Objects.requireNonNull(account.getPasskeyUserHandle())))
                .build();
    }
}
