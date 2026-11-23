package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingPrivacyJournal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;

/** The purge of an account's rows records the account erasure in the purge's transaction. */
class AccountPurgerTest extends BaseUnitTest {
    @Mock
    private ObjectProvider<PersonDataService> personErasure;

    @Test
    void shouldRecordTheAccountErasureWhenThePurgeDeletesTheAccountRows() {
        var erasures = mock(AccountErasureRepository.class);
        var journal = new RecordingPrivacyJournal();
        var purger = new AccountPurger(mock(AccountRepository.class), erasures, List.of(), personErasure, journal);

        purger.purgeInCurrentTransaction(5L);

        verify(erasures).deleteIdentities(5L);
        assertThat(journal.facts())
                .containsExactly(
                        new RecordingPrivacyJournal.Fact(PrivacyJournalKind.ACCOUNT_ERASED, Map.of("accountId", 5L)));
    }
}
