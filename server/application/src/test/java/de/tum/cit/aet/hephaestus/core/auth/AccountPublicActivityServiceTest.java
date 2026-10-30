package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class AccountPublicActivityServiceTest extends BaseUnitTest {
    @Mock
    private AccountRepository accounts;

    @Test
    void shouldDefaultToHiddenUntilAnsweredAndAllowEitherChoice() {
        var account = new Account("Person");
        when(accounts.findById(1L)).thenReturn(Optional.of(account));
        when(accounts.findByIdForUpdate(1L)).thenReturn(Optional.of(account));
        var service = new AccountPublicActivityService(accounts);
        assertThat(service.visible(1L)).isFalse();
        assertThat(service.setVisible(1L, true)).isTrue();
        assertThat(account.isPublicActivityVisible()).isTrue();
        verify(accounts).clearLinkedPublicActivityObjections(1L);
        assertThat(service.setVisible(1L, false)).isFalse();
        assertThat(service.visible(1L)).isFalse();
        verify(accounts).retainPublicActivityObjections(1L);
        // An explicit choice is the account's own: a rollback of the upgrade default must leave it.
        verify(accounts, times(2)).forgetPublicActivityHiddenByDefault(1L);
    }

    @Test
    void shouldRejectUnknownAccounts() {
        var service = new AccountPublicActivityService(accounts);
        assertThatThrownBy(() -> service.visible(1L)).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.setVisible(1L, false)).isInstanceOf(EntityNotFoundException.class);
    }
}
