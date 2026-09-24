package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventData;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventWriter;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.LinkedAccountRow;
import de.tum.cit.aet.hephaestus.core.auth.spi.UserViewAccess;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class UserViewAccessServiceTest extends BaseUnitTest {
    private final IdentityLinkRepository identityLinks = mock(IdentityLinkRepository.class);
    private final AuthEventWriter writer = mock(AuthEventWriter.class);
    private final UserViewAccessService service =
            new UserViewAccessService(identityLinks, new AuthEventLogger(writer), new JsonMapper());

    @BeforeEach
    void signInAsAdministrator() {
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("test")
                        .header("alg", "none")
                        .subject("42")
                        .build()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldRecordTheAdministratorAsActingAndTheViewedUserWhenTheUserHasNoAccount() {
        when(writer.write(any())).thenReturn(true);

        service.record(
                7L, 99L, null, "Investigate%20missing%20feedback", "/workspaces/acme/user-view/users/99/practices");

        AuthEventData event = written();
        assertThat(event.actingAccountId()).isEqualTo(42L);
        assertThat(event.accountId()).isNull();
        assertThat(event.viewedUserId()).isEqualTo(99L);
        assertThat(event.workspaceId()).isEqualTo(7L);
        assertThat(event.details())
                .contains("\"reason\":\"Investigate missing feedback\"")
                .contains("\"read\":\"/workspaces/acme/user-view/users/99/practices\"");
    }

    @Test
    void shouldRecordTheLinkedAccountAsTheEventAccountWhenTheUserHasOne() {
        when(writer.write(any())).thenReturn(true);

        service.record(7L, 99L, 3L, "Support", "/read");

        assertThat(written().accountId()).isEqualTo(3L);
    }

    @Test
    void shouldDenyDisclosureWhenTheAuditWriteDoesNotCommit() {
        when(writer.write(any())).thenReturn(false);

        assertThatThrownBy(() -> service.record(7L, 99L, null, "Support", "/read"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%20", "%broken", "reason%0Aforged", "support%E2%80%AEdesrever", "a%E2%80%8Bb"})
    void shouldRejectAReasonThatCannotBeReadBackAsWritten(String encodedReason) {
        assertThatThrownBy(() -> service.record(7L, 99L, null, encodedReason, "/read"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(writer);
    }

    @Test
    void shouldRejectAReasonLongerThanFiveHundredCharacters() {
        assertThatThrownBy(() -> service.record(7L, 99L, null, "x".repeat(501), "/read"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(writer);
    }

    @Test
    void shouldMapEachLinkedUserToItsAccountAndStatus() {
        when(identityLinks.findLinkedAccountsByExternalActorIds(List.of(99L, 100L)))
                .thenReturn(List.of(new LinkedAccountRow(99L, 3L, Account.Status.DELETING)));

        assertThat(service.linkedAccounts(List.of(99L, 100L)))
                .containsExactly(Map.entry(99L, new UserViewAccess.LinkedAccount(3L, "DELETING")));
    }

    private AuthEventData written() {
        var event = ArgumentCaptor.forClass(AuthEventData.class);
        verify(writer).write(event.capture());
        return event.getValue();
    }
}
