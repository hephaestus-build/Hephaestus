package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.event.AccountDeletionRequestedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Records each committed self-service account deletion outside the database. A restore to an earlier point brings the
 * account back, so the operator's erasure recovery manifest needs this record (docs/admin/backup-restore.mdx § Reapply
 * erasure decisions before processing resumes). The registry writes it only after the deletion commits, and writes it
 * again after a failure.
 */
@Component
@ConditionalOnServerRole
public class AccountDeletionRecord {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionRecord.class);

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(AccountDeletionRequestedEvent event) {
        // core cannot depend on observability, so the key is a literal; it holds an id, never personal data.
        log.atInfo()
                .addKeyValue("event.name", "account.deleted")
                .addKeyValue("account.id", event.accountId())
                .log("A person deleted their account");
    }
}
