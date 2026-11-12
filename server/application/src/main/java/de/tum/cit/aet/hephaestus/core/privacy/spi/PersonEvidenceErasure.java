package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.Set;
import java.util.UUID;

/**
 * Server-owned evidence copies must be removed before their PostgreSQL job rows. The job-folder
 * implementation supplies a contributor for its own export/selection and implements this hook.
 * Implementations must await deletion of every attempt, including failed attempts, and prevent a
 * running attempt from recreating evidence. A failed acknowledgement stops erasure.
 */
public interface PersonEvidenceErasure extends PersonDataCatalog {
    /**
     * Every job whose inputs contain the exact subject, including developerless proof and copied
     * inputs. Selection uses retained custody even after the job row expires. Receipts remain until
     * physical cleanup is acknowledged.
     */
    Set<UUID> jobsContaining(PersonScope person);

    void eraseJobEvidence(Set<UUID> jobIds);
}
