package de.tum.cit.aet.hephaestus.core.auth.jwt;

import static de.tum.cit.aet.hephaestus.testconfig.TestSystemSecrets.systemSecrets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** A new signing key replaces the old one while tokens of the old key keep verifying (ADR 0104). */
class JwtSigningKeyRotationTest extends BaseUnitTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private final SystemSecrets secrets = systemSecrets(KEY, "test");
    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-14T10:00:00Z"));
    private final JwtSigningKeyRepository repository = mock(JwtSigningKeyRepository.class);
    private final List<JwtSigningKey> saved = new ArrayList<>();

    JwtSigningKeyRotationTest() {
        ArgumentCaptor<JwtSigningKey> row = ArgumentCaptor.forClass(JwtSigningKey.class);
        lenient().when(repository.save(row.capture())).thenAnswer(call -> {
            saved.addFirst(call.getArgument(0));
            return call.getArgument(0);
        });
    }

    @Test
    void shouldVerifyATokenOfTheOldKeyAfterANewKeySigns() {
        JwtSigningKeyService before = service(null);
        before.ensureActiveKey();
        when(repository.findActive()).thenAnswer(call -> List.copyOf(saved));
        String oldKid = before.currentSigningKey().getKeyID();
        String oldToken = sign(before, oldKid);

        clock.advance(Duration.ofHours(1));
        JwtSigningKeyService after = service(clock.instant().minusSeconds(60));
        after.ensureActiveKey();

        String newKid = after.currentSigningKey().getKeyID();
        assertThat(newKid).isNotEqualTo(oldKid);
        var decoder = NimbusJwtDecoder.withJwkSource(after)
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        assertThat(decoder.decode(oldToken).getHeaders()).containsEntry("kid", oldKid);
        assertThat(decoder.decode(sign(after, newKid)).getHeaders()).containsEntry("kid", newKid);
    }

    @Test
    void shouldNotMakeAnotherKeyWhenAKeyIsNewerThanTheRequestedInstant() {
        when(repository.existsByActiveTrueAndCreatedAtGreaterThanEqual(any())).thenReturn(true);

        service(clock.instant().minusSeconds(60)).ensureActiveKey();

        verify(repository, never()).save(any());
    }

    @Test
    void shouldIgnoreARequestedInstantInTheFuture() {
        when(repository.countByActiveTrue()).thenReturn(1L);

        service(clock.instant().plusSeconds(60)).ensureActiveKey();

        verify(repository, never()).save(any());
    }

    @Test
    void shouldAcceptATokenOfAKeyThatAnotherReplicaMadeAfterThisOneLoadedItsKeys() {
        JwtSigningKeyService other = service(null);
        other.ensureActiveKey();
        when(repository.findActive()).thenAnswer(call -> List.copyOf(saved));
        JwtSigningKeyService stale = service(null);
        String firstKid = stale.currentSigningKey().getKeyID();

        clock.advance(Duration.ofSeconds(10));
        service(clock.instant().minusSeconds(1)).ensureActiveKey();
        String newKid = saved.getFirst().getKid();
        assertThat(newKid).isNotEqualTo(firstKid);

        var decoder = NimbusJwtDecoder.withJwkSource(stale)
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        assertThat(decoder.decode(sign(service(null), newKid)).getHeaders()).containsEntry("kid", newKid);
    }

    private JwtSigningKeyService service(@Nullable Instant notBefore) {
        var properties = notBefore == null
                ? AuthPropertiesFixture.defaults()
                : AuthPropertiesFixture.withSigningKeyNotBefore(notBefore);
        return new JwtSigningKeyService(repository, secrets, properties, clock);
    }

    private String sign(JwtSigningKeyService keys, String kid) {
        Instant now = clock.instant();
        var claims = JwtClaimsSet.builder()
                .subject("account")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofDays(3650)))
                .build();
        var header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(kid).build();
        return new NimbusJwtEncoder(keys)
                .encode(JwtEncoderParameters.from(header, claims))
                .getTokenValue();
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
