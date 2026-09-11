package de.tum.cit.aet.hephaestus.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditActorKind;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventData;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventWriter;
import de.tum.cit.aet.hephaestus.core.security.WorkspaceElevationContext;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Both ledgers must read the same elevation decision, and must scope it to one workspace. */
class ConfigAuditElevationTest extends BaseUnitTest {

    @AfterEach
    void clearRequestState() {
        WorkspaceElevationContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldTagOnlyTheElevatedWorkspaceInBothLedgers() {
        authenticate(false);
        WorkspaceElevationContext.set(7L);

        assertThat(ConfigAuditActor.fromSecurityContext(7L).elevatedViaInstanceAdmin())
                .isTrue();
        assertThat(ConfigAuditActor.fromSecurityContext(8L).elevatedViaInstanceAdmin())
                .as("a second workspace touched in the same request is not elevated")
                .isFalse();
        assertThat(ConfigAuditActor.fromSecurityContext(null).elevatedViaInstanceAdmin())
                .as("an instance-scoped change has no workspace to be elevated into")
                .isFalse();

        AuthEventWriter writer = mock(AuthEventWriter.class);
        when(writer.write(any())).thenReturn(true);
        new AuthEventLogger(writer)
                .event(AuthEvent.EventType.WORKSPACE_ELEVATION, AuthEvent.Result.SUCCESS)
                .account(42L)
                .workspace(7L)
                .record();

        ArgumentCaptor<AuthEventData> written = ArgumentCaptor.forClass(AuthEventData.class);
        verify(writer).write(written.capture());
        assertThat(written.getValue().elevatedViaInstanceAdmin()).isTrue();
    }

    @Test
    void shouldNotTagAnythingOnceTheRequestHasCleared() {
        authenticate(false);
        WorkspaceElevationContext.set(7L);
        WorkspaceElevationContext.clear();

        assertThat(ConfigAuditActor.fromSecurityContext(7L).elevatedViaInstanceAdmin())
                .isFalse();
    }

    @Test
    void shouldNotConfuseImpersonationWithElevation() {
        authenticate(true);

        ConfigAuditActor actor = ConfigAuditActor.fromSecurityContext(7L);

        assertThat(actor.kind()).isEqualTo(ConfigAuditActorKind.IMPERSONATED);
        assertThat(actor.actingAccountId()).isEqualTo(99L);
        assertThat(actor.elevatedViaInstanceAdmin())
                .as("impersonation is attributable through the actor pair, not through elevation")
                .isFalse();
    }

    @Test
    void shouldKeepUnauthenticatedBackgroundWorkAsSystemAndUnelevated() {
        WorkspaceElevationContext.set(7L);

        ConfigAuditActor actor = ConfigAuditActor.fromSecurityContext(7L);

        assertThat(actor.kind()).isEqualTo(ConfigAuditActorKind.SYSTEM);
        assertThat(actor.elevatedViaInstanceAdmin()).isFalse();
    }

    private static void authenticate(boolean impersonating) {
        Jwt.Builder builder = Jwt.withTokenValue("test").header("alg", "none").subject("42");
        if (impersonating) {
            builder.claim("act", Map.of("sub", "99"));
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(builder.build()));
    }
}
