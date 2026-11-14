package de.tum.cit.aet.hephaestus.core.security;

import static de.tum.cit.aet.hephaestus.core.TransactionCallbacks.afterCommit;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seals each stored system secret again with the active system key, while {@code encryption-rotation-enabled} is on.
 * The tables are small, so one transaction covers every row. A value that does not open rolls the pass back; the next
 * pass retries it after the operator corrects the prior key.
 */
@Service
@ConditionalOnServerRole
@ConditionalOnProperty(prefix = "hephaestus.security", name = "encryption-rotation-enabled", havingValue = "true")
public class SystemKeyRotation {

    private static final Logger log = LoggerFactory.getLogger(SystemKeyRotation.class);

    private final List<SystemSecretOwner> owners;
    private final SystemSecrets secrets;

    public SystemKeyRotation(List<SystemSecretOwner> owners, SystemSecrets secrets) {
        this.owners = owners;
        this.secrets = secrets;
    }

    @Scheduled(
            initialDelayString = "${hephaestus.security.encryption-rotation-delay:PT1M}",
            fixedDelayString = "${hephaestus.security.encryption-rotation-delay:PT1M}")
    @SchedulerLock(name = "system-key-rotation", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    @Transactional
    @WorkspaceAgnostic("Seals the system secrets of every workspace again")
    public void rotate() {
        int resealed = 0;
        for (SystemSecretOwner owner : owners) {
            resealed += owner.resealWithActiveKey(secrets);
        }
        int count = resealed;
        // A pass that found nothing to seal again is the completion signal. A pass that resealed values is not: a
        // server still on the earlier configuration can seal a value under the earlier key while its rollout runs.
        afterCommit(() -> log.info(
                "System key rotation pass committed: resealed={}, activeKeyId={}, complete={}",
                count,
                secrets.activeKeyId(),
                count == 0));
    }
}
