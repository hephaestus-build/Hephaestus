package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Binds the owner to the database transaction that will use the person rows. */
@Component
@RequiredArgsConstructor
public class OwnerScope {
    /** Marks a job that owns its stored account, or authorized maintenance that resolves the account it operates on. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface StoredOwnerOperation {}

    private final JdbcTemplate jdbc;

    public long fromPrincipal(PersonAct act) {
        long account = act.requireSelf();
        bind(account);
        return account;
    }

    /** The owner comes from the stored job or an admitted maintenance operation. */
    public void ofJob(long account) {
        bind(account);
    }

    private void bind(long account) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Owner scope requires a database transaction.");
        }
        String scope = jdbc.queryForObject("""
            SELECT CASE WHEN COALESCE(public.current_owner_account(), ?) = ?
                        THEN pg_catalog.set_config('hephaestus.owner_account', ?::text, true) END
            """, String.class, account, account, account);
        if (scope == null) {
            throw new IllegalStateException("Owner scope could not be set.");
        }
    }
}
