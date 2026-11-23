package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Stores no privacy fact. After a database restore, the operator lists each erasure in the erasure recovery manifest
 * by hand ({@link PersonDataRecovery}), as before the journal port existed.
 */
@Component
class UnstoredPrivacyJournal implements PrivacyJournal {

    @Override
    public void record(PrivacyJournalKind kind, Map<String, Object> refs) {}
}
