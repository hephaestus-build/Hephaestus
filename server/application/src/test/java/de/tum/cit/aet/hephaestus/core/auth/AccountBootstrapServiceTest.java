package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventData;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventWriter;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pins the break-glass {@code POST /auth/bootstrap-admin} contract: disabled when the token is unset
 * (404), forbidden on token mismatch (403), self-disabled when an admin already exists (409), and a
 * single audited promotion on success.
 */
class AccountBootstrapServiceTest extends BaseUnitTest {

    private static final String TOKEN = "break-glass-token-0123456789abcdef";

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final AuthEventWriter auditWriter = mock(AuthEventWriter.class);

    private AccountBootstrapService serviceWithToken(String configuredToken) {
        AuthProperties props = mock(AuthProperties.class);
        when(props.bootstrapToken()).thenReturn(configuredToken);
        return new AccountBootstrapService(
                accountRepository,
                new AuthEventLogger(auditWriter),
                props,
                java.time.Clock.systemUTC(),
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    @Test
    void disabledWhenTokenBlank_returns404() {
        AccountBootstrapService service = serviceWithToken("");
        assertThatThrownBy(() -> service.bootstrapFirstAdmin(1L, "anything"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(accountRepository, never()).promoteToFirstAdminIfNoneExists(anyLong());
        verifyNoInteractions(auditWriter);
    }

    @Test
    void wrongToken_returns403_andDoesNotTouchTheDb() {
        AccountBootstrapService service = serviceWithToken(TOKEN);
        assertThatThrownBy(() -> service.bootstrapFirstAdmin(1L, "wrong"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(accountRepository, never()).promoteToFirstAdminIfNoneExists(anyLong());
        verifyNoInteractions(auditWriter);
    }

    @Test
    void correctTokenButAdminExists_returns409() {
        AccountBootstrapService service = serviceWithToken(TOKEN);
        when(accountRepository.promoteToFirstAdminIfNoneExists(1L)).thenReturn(0); // self-disabled

        assertThatThrownBy(() -> service.bootstrapFirstAdmin(1L, TOKEN))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(auditWriter);
    }

    @Test
    void correctTokenAndNoAdmin_promotesAndAudits() {
        AccountBootstrapService service = serviceWithToken(TOKEN);
        when(accountRepository.promoteToFirstAdminIfNoneExists(7L)).thenReturn(1);

        service.bootstrapFirstAdmin(7L, TOKEN);

        ArgumentCaptor<AuthEventData> event = ArgumentCaptor.forClass(AuthEventData.class);
        verify(auditWriter).write(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AuthEvent.EventType.APP_ROLE_CHANGED);
        assertThat(event.getValue().details()).contains("bootstrap-token");
    }
}
