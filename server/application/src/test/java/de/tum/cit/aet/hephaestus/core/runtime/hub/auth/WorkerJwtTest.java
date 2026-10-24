package de.tum.cit.aet.hephaestus.core.runtime.hub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class WorkerJwtTest extends BaseUnitTest {

    private WorkerKeyRing keyRing;
    private WorkerTokenProperties properties;
    private WorkerJwtIssuer issuer;
    private WorkerTokenDenylistService denylist;
    private JavaJwtWorkerJwtVerifier verifier;

    @BeforeEach
    void setUp() {
        properties = new WorkerTokenProperties(
                "hephaestus-test", "hephaestus-worker", Duration.ofMinutes(15), "register-me", List.of(), null);
        keyRing = WorkerKeyRing.fromConfig(properties);
        issuer = new WorkerJwtIssuer(keyRing, properties);
        denylist = mock(WorkerTokenDenylistService.class);
        lenient().when(denylist.isRevoked(anyString())).thenReturn(false);
        verifier = new JavaJwtWorkerJwtVerifier(keyRing, properties, denylist, new SimpleMeterRegistry());
    }

    @Test
    void issuedTokenVerifiesWithExpectedClaims() {
        WorkerJwtIssuer.IssuedWorkerJwt issued = issuer.issue("worker-1");
        WorkerSessionJwt jwt = (WorkerSessionJwt) verifier.verify(issued.token());

        assertThat(jwt.workerId()).isEqualTo("worker-1");
        assertThat(jwt.jti()).isEqualTo(issued.jti());
        assertThat(jwt.expiresAt()).isCloseTo(issued.expiresAt(), within(1, ChronoUnit.SECONDS));
    }

    @Test
    void issuedJobTokenVerifiesWithRequiredClaims() {
        UUID jobId = UUID.randomUUID();
        Instant work = Instant.now().plusSeconds(300);

        JobJwt jwt = (JobJwt) verifier.verify(
                issuer.issueForJobUntil(jobId, 42L, 0, work, work.plusSeconds(600), JobCredentialScope.REVIEW));

        assertThat(jwt.jobId()).isEqualTo(jobId);
        assertThat(jwt.workspaceId()).isEqualTo(42L);
        assertThat(jwt.scopes()).containsExactlyInAnyOrder("llm_proxy", "review");
        assertThat(jwt.issuedAt()).isNotNull();
        assertThat(jwt.jti()).isNotBlank();
    }

    @Test
    void shouldCarryTheWorkDeadlineAndTheLaterExpiryAsTwoSignedFacts() {
        Instant work = Instant.now().plusSeconds(900).truncatedTo(ChronoUnit.SECONDS);
        Instant expiry = work.plusSeconds(600);

        JobJwt jwt = (JobJwt) verifier.verify(
                issuer.issueForJobUntil(UUID.randomUUID(), 42L, 0, work, expiry, JobCredentialScope.REVIEW));

        assertThat(jwt.workDeadline()).isEqualTo(work);
        assertThat(jwt.expiresAt()).isEqualTo(expiry);
        assertThat(jwt.scopes()).containsExactlyInAnyOrder(WorkerJwtIssuer.LLM_PROXY_SCOPE, "review");
    }

    @Test
    void shouldNotIssueAJobTokenWhoseWorkOutlastsItOrHasAlreadyEnded() {
        Instant expiry = Instant.now().plusSeconds(600);

        assertThatThrownBy(() -> issuer.issueForJobUntil(
                        UUID.randomUUID(), 42L, 0, expiry.plusSeconds(1), expiry, JobCredentialScope.REVIEW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issuer.issueForJobUntil(
                        UUID.randomUUID(), 42L, 0, Instant.now().minusSeconds(1), expiry, JobCredentialScope.REVIEW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldStillAuthenticateAJobWhoseWorkTimeEndedUntilItsTokenExpires() {
        // The attempt still returns its result in the grace after its work; only model calls refuse it.
        Instant issued = Instant.now().minusSeconds(120);
        Instant work = Instant.now().minusSeconds(60);
        String token = jobToken(
                issued, work.plusSeconds(600), Map.<String, Object>of(WorkerJwtIssuer.WORK_DEADLINE_CLAIM, work));

        JobJwt jwt = (JobJwt) verifier.verify(token);

        assertThat(jwt.workDeadline()).isEqualTo(work.truncatedTo(ChronoUnit.SECONDS));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidWorkDeadlines")
    void shouldRefuseAJobTokenWithoutAWellOrderedWorkDeadline(
            String label, Instant issued, Map<String, Object> claims) {
        String token = jobToken(issued, issued.plusSeconds(900), claims);

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(WorkerJwtInvalidException.class)
                .hasMessageContaining("work_deadline");
    }

    static Stream<Arguments> invalidWorkDeadlines() {
        Instant issued = Instant.now().minusSeconds(60);
        return Stream.of(
                Arguments.of("missing", issued, Map.<String, Object>of()),
                Arguments.of(
                        "a string",
                        issued,
                        Map.<String, Object>of(
                                WorkerJwtIssuer.WORK_DEADLINE_CLAIM, Long.toString(issued.getEpochSecond() + 300))),
                Arguments.of(
                        "before issue",
                        issued,
                        Map.<String, Object>of(WorkerJwtIssuer.WORK_DEADLINE_CLAIM, issued.minusSeconds(30))),
                Arguments.of(
                        "after expiry",
                        issued,
                        Map.<String, Object>of(WorkerJwtIssuer.WORK_DEADLINE_CLAIM, issued.plusSeconds(901))));
    }

    @Test
    void shouldRefuseAJobTokenWhoseWorkDeadlineWasChangedAfterSigning() {
        Instant work = Instant.now().plusSeconds(300);
        String[] parts = issuer.issueForJobUntil(
                        UUID.randomUUID(), 42L, 0, work, work.plusSeconds(600), JobCredentialScope.REVIEW)
                .split("\\.", -1);
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String later = payload.replace(
                "\"work_deadline\":" + work.getEpochSecond(), "\"work_deadline\":" + (work.getEpochSecond() + 3600));
        assertThat(later).isNotEqualTo(payload);
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(later.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(WorkerJwtInvalidException.class)
                .hasMessageContaining("verify failed");
    }

    @Test
    void expiredJobTokenRejected() {
        Instant past = Instant.now().minusSeconds(120);
        String token = jobToken(
                past,
                past.plusSeconds(30),
                Map.<String, Object>of(WorkerJwtIssuer.WORK_DEADLINE_CLAIM, past.plusSeconds(10)));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(WorkerJwtInvalidException.class)
                .hasMessageContaining("verify failed");
    }

    @Test
    void workerSessionTokenCarryingAWorkDeadlineRejected() {
        WorkerSigningKey active = keyRing.active();
        String token = JWT.create()
                .withHeader(Map.of("kid", active.kid(), "typ", "worker-session+jwt"))
                .withIssuer("hephaestus-test")
                .withAudience("hephaestus-worker")
                .withSubject("worker-1")
                .withClaim(WorkerJwtIssuer.WORK_DEADLINE_CLAIM, Instant.now().plusSeconds(30))
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(Instant.now())
                .withExpiresAt(Instant.now().plusSeconds(60))
                .sign(Algorithm.RSA256(active.publicKey(), active.privateKey()));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(WorkerJwtInvalidException.class)
                .hasMessageContaining("job claims");
    }

    // A job token signed with the active key, carrying exactly the given extra claims.
    private String jobToken(Instant issued, Instant expires, Map<String, Object> claims) {
        WorkerSigningKey active = keyRing.active();
        var builder = JWT.create()
                .withHeader(Map.of("kid", active.kid(), "typ", "job+jwt"))
                .withIssuer("hephaestus-test")
                .withAudience("hephaestus-worker")
                .withClaim("job_id", UUID.randomUUID().toString())
                .withClaim("workspace_id", 42L)
                .withClaim("attempt", 0)
                .withClaim("scope", List.of("llm_proxy"))
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(issued)
                .withExpiresAt(expires);
        claims.forEach((name, value) -> {
            if (value instanceof Instant instant) builder.withClaim(name, instant);
            else builder.withClaim(name, value.toString());
        });
        return builder.sign(Algorithm.RSA256(active.publicKey(), active.privateKey()));
    }

    @Test
    void legacyTokenWithoutExplicitTypeRejected() {
        // Tokens minted before the claim-profile migration carry the library default "JWT" header
        // type; the one-way cut rejects them even when the signature and claims would verify.
        WorkerSigningKey active = keyRing.active();
        String token = JWT.create()
                .withKeyId(active.kid())
                .withIssuer("hephaestus-test")
                .withAudience("hephaestus-worker")
                .withSubject("worker-1")
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(Instant.now())
                .withExpiresAt(Instant.now().plusSeconds(60))
                .sign(Algorithm.RSA256(active.publicKey(), active.privateKey()));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(WorkerJwtInvalidException.class)
                .hasMessageContaining("token type");
    }

    @Test
    void workerSessionTokenCarryingJobClaimsRejected() {
        WorkerSigningKey active = keyRing.active();
        String token = JWT.create()
                .withHeader(Map.of("kid", active.kid(), "typ", "worker-session+jwt"))
                .withIssuer("hephaestus-test")
                .withAudience("hephaestus-worker")
                .withSubject("worker-1")
                .withClaim("job_id", UUID.randomUUID().toString())
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(Instant.now())
                .withExpiresAt(Instant.now().plusSeconds(60))
                .sign(Algorithm.RSA256(active.publicKey(), active.privateKey()));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(WorkerJwtInvalidException.class)
                .hasMessageContaining("job claims");
    }

    @Test
    void revokedTokenRejected() {
        WorkerJwtIssuer.IssuedWorkerJwt issued = issuer.issue("worker-1");
        when(denylist.isRevoked(issued.jti())).thenReturn(true);

        assertThatThrownBy(() -> verifier.verify(issued.token()))
                .isInstanceOf(WorkerJwtInvalidException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void unknownKidRejected() {
        WorkerSigningKey other = WorkerSigningKey.generateEphemeral("foreign-kid");
        String token = JWT.create()
                .withKeyId("foreign-kid")
                .withIssuer("hephaestus-test")
                .withSubject("worker-1")
                .withJWTId("j")
                .withIssuedAt(Instant.now())
                .withExpiresAt(Instant.now().plusSeconds(60))
                .sign(Algorithm.RSA256(other.publicKey(), other.privateKey()));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(WorkerJwtInvalidException.class)
                .hasMessageContaining("unknown kid");
    }

    @Test
    void missingKidRejected() {
        String token = JWT.create()
                .withIssuer("hephaestus-test")
                .withSubject("worker-1")
                .withJWTId("j")
                .withIssuedAt(Instant.now())
                .withExpiresAt(Instant.now().plusSeconds(60))
                .sign(Algorithm.RSA256(
                        keyRing.active().publicKey(), keyRing.active().privateKey()));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(WorkerJwtInvalidException.class)
                .hasMessageContaining("missing kid");
    }

    @Test
    void rotation_oldTokenStillVerifiesAfterAddingNewActiveKey() {
        String issuerName = "hephaestus-rot-test";
        WorkerSigningKey keyA = WorkerSigningKey.generateEphemeral("kid-A");
        WorkerSigningKey keyB = WorkerSigningKey.generateEphemeral("kid-B");

        WorkerTokenProperties propsBefore = new WorkerTokenProperties(
                issuerName, "hephaestus-worker", Duration.ofMinutes(15), "register", List.of(toEntry(keyA)), "kid-A");
        String tokenSignedByA = new WorkerJwtIssuer(WorkerKeyRing.fromConfig(propsBefore), propsBefore)
                .issue("worker-1")
                .token();

        WorkerTokenProperties propsAfter = new WorkerTokenProperties(
                issuerName,
                "hephaestus-worker",
                Duration.ofMinutes(15),
                "register",
                List.of(toEntry(keyA), toEntry(keyB)),
                "kid-B");
        WorkerJwtVerifier verifierAfter = new JavaJwtWorkerJwtVerifier(
                WorkerKeyRing.fromConfig(propsAfter), propsAfter, denylist, new SimpleMeterRegistry());
        String tokenSignedByB = new WorkerJwtIssuer(WorkerKeyRing.fromConfig(propsAfter), propsAfter)
                .issue("worker-2")
                .token();

        assertThatNoException().isThrownBy(() -> verifierAfter.verify(tokenSignedByA));
        assertThatNoException().isThrownBy(() -> verifierAfter.verify(tokenSignedByB));

        WorkerTokenProperties propsDropA = new WorkerTokenProperties(
                issuerName, "hephaestus-worker", Duration.ofMinutes(15), "register", List.of(toEntry(keyB)), "kid-B");
        WorkerJwtVerifier verifierDropA = new JavaJwtWorkerJwtVerifier(
                WorkerKeyRing.fromConfig(propsDropA), propsDropA, denylist, new SimpleMeterRegistry());
        assertThatThrownBy(() -> verifierDropA.verify(tokenSignedByA))
                .isInstanceOf(WorkerJwtInvalidException.class)
                .hasMessageContaining("unknown kid");
        assertThatNoException().isThrownBy(() -> verifierDropA.verify(tokenSignedByB));
    }

    @ParameterizedTest(name = "fromConfig rejects {0}")
    @MethodSource("invalidRings")
    void fromConfigRejectsInvalidRings(String label, WorkerTokenProperties props, String expectedMessage) {
        assertThatThrownBy(() -> WorkerKeyRing.fromConfig(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage);
    }

    static Stream<Arguments> invalidRings() {
        WorkerSigningKey k = WorkerSigningKey.generateEphemeral("only-kid");
        return Stream.of(
                Arguments.of(
                        "duplicate kid",
                        new WorkerTokenProperties(
                                "iss", "aud", Duration.ofMinutes(15), "r", List.of(toEntry(k), toEntry(k)), null),
                        "Duplicate kid"),
                Arguments.of(
                        "active-kid not in ring",
                        new WorkerTokenProperties(
                                "iss", "aud", Duration.ofMinutes(15), "r", List.of(toEntry(k)), "missing-kid"),
                        "active-kid"));
    }

    @Test
    void propertiesToStringRedactsRegistrationToken() {
        WorkerTokenProperties props =
                new WorkerTokenProperties("iss", "aud", Duration.ofMinutes(5), "the-secret", List.of(), null);
        String dumped = props.toString();
        assertThat(dumped).doesNotContain("the-secret");
        assertThat(dumped).contains("<redacted>");
    }

    private static WorkerTokenProperties.KeyEntry toEntry(WorkerSigningKey key) {
        return new WorkerTokenProperties.KeyEntry(key.kid(), toPemPkcs8(key.privateKey()));
    }

    private static String toPemPkcs8(RSAPrivateKey key) {
        return ("-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
    }
}
