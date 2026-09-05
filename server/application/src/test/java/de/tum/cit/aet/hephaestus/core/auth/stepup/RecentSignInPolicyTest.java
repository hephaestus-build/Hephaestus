package de.tum.cit.aet.hephaestus.core.auth.stepup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventData;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventWriter;
import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** The decision the recent-sign-in gate makes, and what it leaves on the audit trail when it refuses. */
class RecentSignInPolicyTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-06-02T10:00:00Z");
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private static final long ACTING_ACCOUNT_ID = 42L;

    @Mock
    private AuthEventWriter authEventWriter;

    private SimpleMeterRegistry meterRegistry;
    private RecentSignInPolicy policy;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        policy = policyWith(AuthPropertiesFixture.withStepUpMaxAge(MAX_AGE));
    }

    private RecentSignInPolicy policyWith(AuthProperties properties) {
        return new RecentSignInPolicy(
                properties,
                new AuthEventLogger(authEventWriter),
                new AuthMetrics(meterRegistry),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Authentication signedInAt(@Nullable Instant authTime) {
        List<GrantedAuthority> authorities =
                new java.util.ArrayList<>(List.of(new SimpleGrantedAuthority("app_admin")));
        if (authTime != null) {
            authorities.add(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY)
                    .issuedAt(authTime)
                    .build());
        }
        var authentication = new TestingAuthenticationToken("42", "n/a", authorities);
        authentication.setAuthenticated(true);
        return authentication;
    }

    @Test
    void aSignInInsideTheWindowIsLetThrough() {
        policy.require(signedInAt(NOW.minus(MAX_AGE).plusSeconds(1)), AuthEvent.EventType.APP_ROLE_CHANGED, 42L);

        Mockito.verifyNoInteractions(authEventWriter);
    }

    @Test
    void aSignInOlderThanTheWindowIsRefused() {
        assertThatThrownBy(() -> policy.require(
                        signedInAt(NOW.minus(MAX_AGE)), AuthEvent.EventType.APP_ROLE_CHANGED, ACTING_ACCOUNT_ID))
                .isInstanceOf(StepUpRequiredException.class);
    }

    /**
     * The session was stamped by whichever pod completed the OAuth dance; that pod's clock can lead the
     * one enforcing the gate. Treating a leading stamp as invalid would tell an administrator who just
     * signed in to sign in again, with no setting that helps them.
     */
    @Test
    void aSignInStampedAheadOfThisPodsClockIsStillFresh() {
        policy.require(signedInAt(NOW.plusSeconds(30)), AuthEvent.EventType.APP_ROLE_CHANGED, ACTING_ACCOUNT_ID);

        Mockito.verifyNoInteractions(authEventWriter);
    }

    @Test
    void aSessionWithNoRecordedSignInIsRefused() {
        assertThatThrownBy(() ->
                        policy.require(signedInAt(null), AuthEvent.EventType.IMPERSONATION_BEGIN, ACTING_ACCOUNT_ID))
                .isInstanceOf(StepUpRequiredException.class);
    }

    @Test
    void anUnauthenticatedCallerIsRefused() {
        assertThatThrownBy(() -> policy.require(null, AuthEvent.EventType.APP_ROLE_CHANGED, null))
                .isInstanceOf(StepUpRequiredException.class);
    }

    @Test
    void aRefusalIsRecordedOnTheAttemptedActionAndCounted() {
        assertThatThrownBy(() -> policy.require(
                        signedInAt(NOW.minus(Duration.ofHours(1))),
                        AuthEvent.EventType.LOGIN_PROVIDER_DELETED,
                        ACTING_ACCOUNT_ID))
                .isInstanceOfSatisfying(
                        StepUpRequiredException.class,
                        e -> assertThat(e.getBody().getProperties())
                                .containsEntry("code", StepUpRequiredException.CODE)
                                .containsEntry("maxAgeSeconds", MAX_AGE.toSeconds()));

        ArgumentCaptor<AuthEventData> captor = ArgumentCaptor.forClass(AuthEventData.class);
        Mockito.verify(authEventWriter).write(captor.capture());
        AuthEventData event = captor.getValue();
        assertThat(event.type()).isEqualTo(AuthEvent.EventType.LOGIN_PROVIDER_DELETED);
        assertThat(event.result()).isEqualTo(AuthEvent.Result.FAILURE);
        assertThat(event.accountId()).isEqualTo(ACTING_ACCOUNT_ID);
        assertThat(event.actingAccountId()).isEqualTo(ACTING_ACCOUNT_ID);
        assertThat(event.failureReason()).isEqualTo(StepUpRequiredException.CODE);

        assertThat(meterRegistry
                        .get("auth.step_up.denied")
                        .tag("action", "login_provider_deleted")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void aWindowThatIsNotAPositiveDurationIsRefusedAtStartup() {
        assertThatThrownBy(() -> policyWith(AuthPropertiesFixture.withStepUpMaxAge(Duration.ZERO)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step-up-max-age");
    }
}
