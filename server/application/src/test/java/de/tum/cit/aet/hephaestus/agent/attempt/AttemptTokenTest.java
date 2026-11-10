package de.tum.cit.aet.hephaestus.agent.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AttemptTokenTest extends BaseUnitTest {

    private static final UUID LEASE = UUID.fromString("ed34a2dc-7803-43e5-82c4-d2865087c41f");
    private AttemptSigningKey key;
    private AttemptTokenIssuer issuer;
    private AttemptTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        key = AttemptSigningKey.generate();
        issuer = new AttemptTokenIssuer(AttemptKeyFixtures.keyring(key));
        verifier = new AttemptTokenVerifier(AttemptKeyFixtures.keyring(key), new SimpleMeterRegistry());
    }

    @Test
    void issuedJobTokenVerifiesWithRequiredClaims() {
        UUID jobId = UUID.randomUUID();
        Instant work = Instant.now().plusSeconds(300);

        AttemptJwt jwt = verifier.verify(issuer.issueForReview(jobId, 42L, 1, LEASE, work, work.plusSeconds(600)));

        assertThat(jwt.id()).isEqualTo(jobId);
        assertThat(jwt.runId()).isEqualTo(LEASE);
        assertThat(jwt.epoch()).isOne();
        assertThat(jwt.workspaceId()).isEqualTo(42L);
        assertThat(jwt.scopes()).containsExactly("llm_proxy");
        assertThat(jwt.issuedAt()).isNotNull();
        assertThat(jwt.jti()).isNotBlank();
    }

    @Test
    void shouldIssueATurnTokenThatNamesOnlyItsTurnWorkspaceAndClaim() {
        UUID turnId = UUID.randomUUID();

        Instant work = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS);

        AttemptJwt jwt =
                verifier.verify(issuer.issueForTurn(turnId, 42L, 2, UUID.randomUUID(), work, work.plusSeconds(60)));

        assertThat(jwt.kind()).isEqualTo(AttemptJwt.Kind.TURN);
        assertThat(jwt.id()).isEqualTo(turnId);
        assertThat(jwt.workspaceId()).isEqualTo(42L);
        assertThat(jwt.epoch()).isEqualTo(2);
        assertThat(jwt.workDeadline()).isEqualTo(work);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedAttemptClaims")
    void shouldRefuseAnAttemptTokenWithMalformedClaims(String label, AttemptJwt.Kind kind, Map<String, Object> claims) {
        Instant issued = Instant.now().minusSeconds(60);
        Map<String, Object> withDeadline = new HashMap<>(claims);
        withDeadline.putIfAbsent(AttemptTokenIssuer.WORK_DEADLINE_CLAIM, issued.plusSeconds(300));
        String token = attemptToken(kind, issued, issued.plusSeconds(900), withDeadline);

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(AttemptTokenInvalidException.class);
    }

    static Stream<Arguments> malformedAttemptClaims() {
        return Stream.of(
                Arguments.of("a worker subject", AttemptJwt.Kind.REVIEW, Map.<String, Object>of("sub", "worker-1")),
                Arguments.of(
                        "an unknown kind",
                        AttemptJwt.Kind.REVIEW,
                        Map.<String, Object>of(AttemptTokenIssuer.KIND_CLAIM, "SCAN")),
                Arguments.of(
                        "an epoch before the first claim",
                        AttemptJwt.Kind.TURN,
                        Map.<String, Object>of(AttemptTokenIssuer.EPOCH_CLAIM, 0)),
                Arguments.of(
                        "an id that is no UUID",
                        AttemptJwt.Kind.REVIEW,
                        Map.<String, Object>of(AttemptTokenIssuer.ID_CLAIM, "job-1")),
                Arguments.of(
                        "no id", AttemptJwt.Kind.REVIEW, Map.<String, Object>of(AttemptTokenIssuer.ID_CLAIM, ABSENT)),
                Arguments.of("no workspace", AttemptJwt.Kind.REVIEW, Map.<String, Object>of("workspace_id", ABSENT)),
                Arguments.of(
                        "a workspace that is no number",
                        AttemptJwt.Kind.REVIEW,
                        Map.<String, Object>of("workspace_id", "acme")),
                Arguments.of(
                        "no run", AttemptJwt.Kind.REVIEW, Map.<String, Object>of(AttemptTokenIssuer.RUN_CLAIM, ABSENT)),
                Arguments.of(
                        "an invalid run",
                        AttemptJwt.Kind.TURN,
                        Map.<String, Object>of(AttemptTokenIssuer.RUN_CLAIM, "worker-1")),
                Arguments.of(
                        "no epoch",
                        AttemptJwt.Kind.REVIEW,
                        Map.<String, Object>of(AttemptTokenIssuer.EPOCH_CLAIM, ABSENT)),
                Arguments.of(
                        "an epoch that is no number",
                        AttemptJwt.Kind.REVIEW,
                        Map.<String, Object>of(AttemptTokenIssuer.EPOCH_CLAIM, "first")),
                Arguments.of("another issuer", AttemptJwt.Kind.REVIEW, Map.<String, Object>of("iss", "elsewhere")),
                Arguments.of("another audience", AttemptJwt.Kind.REVIEW, Map.<String, Object>of("aud", "elsewhere")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherAlgorithms")
    void shouldRefuseAnAttemptTokenThatIsNotSignedWithTheWorkersKey(String label, Algorithm algorithm) {
        Instant issued = Instant.now().minusSeconds(60);
        String token = JWT.create()
                .withHeader(Map.of("kid", key.kid(), "typ", AttemptTokenIssuer.ATTEMPT_TOKEN_TYPE))
                .withIssuer(AttemptTokenIssuer.ISSUER)
                .withAudience(AttemptTokenIssuer.AUDIENCE)
                .withClaim(AttemptTokenIssuer.KIND_CLAIM, AttemptJwt.Kind.REVIEW.name())
                .withClaim(AttemptTokenIssuer.ID_CLAIM, UUID.randomUUID().toString())
                .withClaim("workspace_id", 42L)
                .withClaim(AttemptTokenIssuer.EPOCH_CLAIM, 1)
                .withClaim(AttemptTokenIssuer.WORK_DEADLINE_CLAIM, issued.plusSeconds(300))
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(issued)
                .withExpiresAt(issued.plusSeconds(900))
                .sign(algorithm);

        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(AttemptTokenInvalidException.class);
    }

    static Stream<Arguments> otherAlgorithms() {
        return Stream.of(
                Arguments.of("alg none", Algorithm.none()),
                Arguments.of("HS256", Algorithm.HMAC256("a shared secret of thirty-two bytes!")));
    }

    @Test
    void shouldRefuseATurnTokenWithoutAWorkDeadline() {
        Instant issued = Instant.now().minusSeconds(60);
        String token = attemptToken(AttemptJwt.Kind.TURN, issued, issued.plusSeconds(900), Map.<String, Object>of());

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(AttemptTokenInvalidException.class)
                .hasMessageMatching("(?s).*work[_ ]deadline.*");
    }

    @Test
    void shouldCarryTheWorkDeadlineAndTheLaterExpiryAsTwoSignedFacts() {
        Instant work = Instant.now().plusSeconds(900).truncatedTo(ChronoUnit.SECONDS);
        Instant expiry = work.plusSeconds(600);

        AttemptJwt jwt =
                verifier.verify(issuer.issueForReview(UUID.randomUUID(), 42L, 1, UUID.randomUUID(), work, expiry));

        assertThat(jwt.workDeadline()).isEqualTo(work);
        assertThat(jwt.expiresAt()).isEqualTo(expiry);
        assertThat(jwt.scopes()).containsExactly(AttemptTokenIssuer.LLM_PROXY_SCOPE);
    }

    @Test
    void shouldNotIssueAJobTokenWhoseWorkOutlastsItOrHasAlreadyEnded() {
        Instant expiry = Instant.now().plusSeconds(600);

        assertThatThrownBy(() -> issuer.issueForReview(
                        UUID.randomUUID(), 42L, 1, UUID.randomUUID(), expiry.plusSeconds(1), expiry))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issuer.issueForReview(
                        UUID.randomUUID(),
                        42L,
                        0,
                        UUID.randomUUID(),
                        Instant.now().minusSeconds(1),
                        expiry))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldStillAuthenticateAJobWhoseWorkTimeEndedUntilItsTokenExpires() {
        // The attempt still returns its result in the grace after its work; only model calls refuse it.
        Instant issued = Instant.now().minusSeconds(120);
        Instant work = Instant.now().minusSeconds(60);
        String token = jobToken(
                issued, work.plusSeconds(600), Map.<String, Object>of(AttemptTokenIssuer.WORK_DEADLINE_CLAIM, work));

        AttemptJwt jwt = verifier.verify(token);

        assertThat(jwt.workDeadline()).isEqualTo(work.truncatedTo(ChronoUnit.SECONDS));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidWorkDeadlines")
    void shouldRefuseAJobTokenWithoutAWellOrderedWorkDeadline(
            String label, Instant issued, Map<String, Object> claims) {
        String token = jobToken(issued, issued.plusSeconds(900), claims);

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(AttemptTokenInvalidException.class)
                .hasMessageMatching("(?s).*work[_ ]deadline.*");
    }

    static Stream<Arguments> invalidWorkDeadlines() {
        Instant issued = Instant.now().minusSeconds(60);
        return Stream.of(
                Arguments.of("missing", issued, Map.<String, Object>of()),
                Arguments.of(
                        "a string",
                        issued,
                        Map.<String, Object>of(
                                AttemptTokenIssuer.WORK_DEADLINE_CLAIM, Long.toString(issued.getEpochSecond() + 300))),
                Arguments.of(
                        "before issue",
                        issued,
                        Map.<String, Object>of(AttemptTokenIssuer.WORK_DEADLINE_CLAIM, issued.minusSeconds(30))),
                Arguments.of(
                        "after expiry",
                        issued,
                        Map.<String, Object>of(AttemptTokenIssuer.WORK_DEADLINE_CLAIM, issued.plusSeconds(901))));
    }

    @Test
    void shouldRefuseAJobTokenWhoseWorkDeadlineWasChangedAfterSigning() {
        Instant work = Instant.now().plusSeconds(300);
        String[] parts = issuer.issueForReview(
                        UUID.randomUUID(), 42L, 1, UUID.randomUUID(), work, work.plusSeconds(600))
                .split("\\.", -1);
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String later = payload.replace(
                "\"work_deadline\":" + work.getEpochSecond(), "\"work_deadline\":" + (work.getEpochSecond() + 3600));
        assertThat(later).isNotEqualTo(payload);
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(later.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(AttemptTokenInvalidException.class)
                .hasMessageContaining("verify failed");
    }

    @Test
    void expiredJobTokenRejected() {
        Instant past = Instant.now().minusSeconds(120);
        String token = jobToken(
                past,
                past.plusSeconds(30),
                Map.<String, Object>of(AttemptTokenIssuer.WORK_DEADLINE_CLAIM, past.plusSeconds(10)));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(AttemptTokenInvalidException.class)
                .hasMessageContaining("verify failed");
    }

    // An attempt token signed with the active key, carrying exactly the given extra claims.
    /** A claim value that the token carries as JSON null, so the claim has no value. */
    private enum Absent {
        CLAIM
    }

    private static final Absent ABSENT = Absent.CLAIM;

    private String jobToken(Instant issued, Instant expires, Map<String, Object> claims) {
        return attemptToken(AttemptJwt.Kind.REVIEW, issued, expires, claims);
    }

    private String attemptToken(AttemptJwt.Kind kind, Instant issued, Instant expires, Map<String, Object> claims) {
        AttemptSigningKey active = key;
        var builder = JWT.create()
                .withHeader(Map.of("kid", active.kid(), "typ", AttemptTokenIssuer.ATTEMPT_TOKEN_TYPE))
                .withIssuer(AttemptTokenIssuer.ISSUER)
                .withAudience(AttemptTokenIssuer.AUDIENCE)
                .withClaim(AttemptTokenIssuer.KIND_CLAIM, kind.name())
                .withClaim(AttemptTokenIssuer.ID_CLAIM, UUID.randomUUID().toString())
                .withClaim("workspace_id", 42L)
                .withClaim(AttemptTokenIssuer.EPOCH_CLAIM, 1)
                .withClaim("scope", List.of("llm_proxy"))
                .withJWTId(UUID.randomUUID().toString())
                .withClaim(AttemptTokenIssuer.RUN_CLAIM, LEASE.toString())
                .withIssuedAt(issued)
                .withExpiresAt(expires);
        claims.forEach((name, value) -> {
            if (value instanceof Absent) builder.withNullClaim(name);
            else if (value instanceof Instant instant) builder.withClaim(name, instant);
            else if (value instanceof Integer number) builder.withClaim(name, number);
            else builder.withClaim(name, value.toString());
        });
        return builder.sign(Algorithm.RSA256(active.publicKey(), active.privateKey()));
    }

    @Test
    void legacyTokenWithoutExplicitTypeRejected() {
        // Tokens minted before the claim-profile migration carry the library default "JWT" header
        // type; the one-way cut rejects them even when the signature and claims would verify.
        AttemptSigningKey active = key;
        String token = JWT.create()
                .withKeyId(active.kid())
                .withIssuer(AttemptTokenIssuer.ISSUER)
                .withAudience(AttemptTokenIssuer.AUDIENCE)
                .withSubject("worker-1")
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(Instant.now())
                .withExpiresAt(Instant.now().plusSeconds(60))
                .sign(Algorithm.RSA256(active.publicKey(), active.privateKey()));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(AttemptTokenInvalidException.class)
                .hasMessageContaining("token type");
    }

    @Test
    void unknownKidRejected() {
        AttemptSigningKey other = AttemptSigningKey.generate();
        String token = JWT.create()
                .withKeyId("foreign-kid")
                .withIssuer(AttemptTokenIssuer.ISSUER)
                .withSubject("worker-1")
                .withJWTId("j")
                .withIssuedAt(Instant.now())
                .withExpiresAt(Instant.now().plusSeconds(60))
                .sign(Algorithm.RSA256(other.publicKey(), other.privateKey()));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(AttemptTokenInvalidException.class)
                .hasMessageContaining("unknown kid");
    }

    @Test
    void missingKidRejected() {
        String token = JWT.create()
                .withIssuer(AttemptTokenIssuer.ISSUER)
                .withSubject("worker-1")
                .withJWTId("j")
                .withIssuedAt(Instant.now())
                .withExpiresAt(Instant.now().plusSeconds(60))
                .sign(Algorithm.RSA256(key.publicKey(), key.privateKey()));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(AttemptTokenInvalidException.class)
                .hasMessageContaining("missing kid");
    }

    @Test
    void shouldIssueASandboxTokenThatNamesItsSandboxWorkspaceAndSubjectAndEndsWithTheSandbox() {
        UUID sandbox = UUID.randomUUID();
        Instant shutdown = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS);

        SandboxJwt jwt = verifier.verifySandbox(issuer.issueForSandbox(sandbox, 42L, "review:7", shutdown));

        assertThat(jwt.sandboxId()).isEqualTo(sandbox);
        assertThat(jwt.workspaceId()).isEqualTo(42L);
        assertThat(jwt.subjectKey()).isEqualTo("review:7");
        assertThat(jwt.expiresAt()).isEqualTo(shutdown);
    }

    @Test
    void shouldNotTakeAnAttemptTokenForASandboxTokenNorTheOtherWayRound() {
        Instant work = Instant.now().plusSeconds(300);
        String attempt = issuer.issueForReview(UUID.randomUUID(), 42L, 1, LEASE, work, work.plusSeconds(600));
        String sandbox = issuer.issueForSandbox(UUID.randomUUID(), 42L, "review:7", work);

        assertThatThrownBy(() -> verifier.verifySandbox(attempt))
                .isInstanceOf(AttemptTokenInvalidException.class)
                .hasMessageContaining("token type");
        assertThatThrownBy(() -> verifier.verify(sandbox))
                .isInstanceOf(AttemptTokenInvalidException.class)
                .hasMessageContaining("token type");
    }

    @Test
    void shouldRefuseASandboxTokenWhoseSubjectWasChangedAfterSigning() {
        String token = issuer.issueForSandbox(
                UUID.randomUUID(), 42L, "review:7", Instant.now().plusSeconds(300));
        String[] parts = token.split("\\.", -1);
        var decoder = Base64.getUrlDecoder();
        String payload = new String(decoder.decode(parts[1]), StandardCharsets.UTF_8).replace("review:7", "review:8");
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThatThrownBy(() -> verifier.verifySandbox(forged))
                .isInstanceOf(AttemptTokenInvalidException.class)
                .hasMessageContaining("verify failed");
    }

    @Test
    void shouldNotIssueASandboxTokenThatEndsBeforeItStartsOrNamesNoSubject() {
        assertThatThrownBy(() -> issuer.issueForSandbox(UUID.randomUUID(), 42L, "review:7", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issuer.issueForSandbox(
                        UUID.randomUUID(), 42L, " ", Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
