package de.tum.cit.aet.hephaestus.core.provenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.jwk.OctetKeyPair;
import de.tum.cit.aet.hephaestus.core.release.ReleaseProperties;
import de.tum.cit.aet.hephaestus.core.release.RunningRelease;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestSystemSecrets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProvenanceSignerTest extends BaseUnitTest {

    private static final String ISSUER = "https://hephaestus.example";
    private static final Instant AT = Instant.parse("2026-11-15T10:00:00Z");
    private static final String TEXT = "Add a **How to verify** section to the description.";

    private final SystemSecrets secrets = TestSystemSecrets.systemSecrets("test-encryption-key-32-bytes-aes");
    private final ProvenanceSigningKeyRepository repository = mock(ProvenanceSigningKeyRepository.class);
    private final ProvenanceKeys keys = new ProvenanceKeys(repository, secrets, Clock.fixed(AT, ZoneOffset.UTC));
    private final RunningRelease release =
            new RunningRelease("1.0.0", new ReleaseProperties("", "", true, "local", ""), new MockEnvironment());
    private ProvenanceSigningKey stored;

    @BeforeEach
    void storeOneKey() throws Exception {
        stored = key("kid-1");
        when(repository.findFirstByOrderByCreatedAtDescKidDesc()).thenReturn(Optional.of(stored));
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.findById("kid-1")).thenReturn(Optional.of(stored));
        when(repository.findAll()).thenReturn(List.of(stored));
    }

    @Test
    void shouldVerifyTheRecordItSignedAndMatchOnlyItsText() {
        var signed = signer().sign(ProvenanceRecord.Kind.FEEDBACK, TEXT, "gpt-6-luna", AT);

        var record = verifier().verify(signed.jws()).orElseThrow();

        assertThat(record).isEqualTo(signed.record());
        assertThat(record.issuer()).isEqualTo(ISSUER);
        assertThat(record.version()).isEqualTo("1.0.0");
        assertThat(record.model()).isEqualTo("gpt-6-luna");
        assertThat(record.generatedAt()).isEqualTo(AT);
        assertThat(record.marks(TEXT)).isTrue();
        assertThat(record.marks(TEXT + "\r\n")).isTrue();
        assertThat(record.marks("Add a **How to test** section to the description."))
                .isFalse();
    }

    @Test
    void shouldRefuseToSignWhenNoKeyExistsInsteadOfMakingOne() {
        when(repository.findFirstByOrderByCreatedAtDescKidDesc()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> signer().sign(ProvenanceRecord.Kind.FEEDBACK, TEXT, null, AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No provenance signing key exists");
        verify(repository, never()).save(any());
    }

    @Test
    void shouldLeaveTheModelOutWhenNoModelWasInvolved() throws Exception {
        var signed = signer().sign(ProvenanceRecord.Kind.ISSUE, TEXT, null, AT);

        assertThat(JWSObject.parse(signed.jws()).getPayload().toJSONObject()).doesNotContainKey("model");
        assertThat(verifier().verify(signed.jws()).orElseThrow().model()).isNull();
    }

    @Test
    void shouldRefuseARecordWhoseClaimsChangedAfterSigning() throws Exception {
        var signed = signer().sign(ProvenanceRecord.Kind.FEEDBACK, TEXT, "gpt-6-luna", AT);
        var parsed = JWSObject.parse(signed.jws());
        var parts = parsed.getParsedParts();
        Map<String, Object> claims = parsed.getPayload().toJSONObject();
        claims.put("model", "another-model");
        String changed = parts[0] + "." + new Payload(claims).toBase64URL() + "." + parts[2];

        assertThat(verifier().verify(changed)).isEmpty();
    }

    @Test
    void shouldRefuseARecordSignedWithAKeyThatThisInstanceDoesNotHold() throws Exception {
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var forged = new JWSObject(
                new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                        .type(ProvenanceSigner.TYPE)
                        .keyID("kid-1")
                        .build(),
                JWSObject.parse(signer().sign(ProvenanceRecord.Kind.FEEDBACK, TEXT, null, AT)
                                .jws())
                        .getPayload());
        forged.sign(Ed25519Jws.signer(other.getPrivate()));

        assertThat(verifier().verify(forged.serialize())).isEmpty();
    }

    @Test
    void shouldRefuseAnUnknownKeyAnotherIssuerAnotherTypeAndText() throws Exception {
        String jws =
                signer().sign(ProvenanceRecord.Kind.FEEDBACK, TEXT, null, AT).jws();
        var otherInstance = new ProvenanceVerifier(keys, "https://other.example");
        var payload = JWSObject.parse(jws).getPayload();
        var wrongType = new JWSObject(
                new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                        .type(JOSEObjectType.JWT)
                        .keyID("kid-1")
                        .build(),
                payload);
        wrongType.sign(Ed25519Jws.signer(keys.signingKey().privateKey()));
        var unknownKey = new JWSObject(
                new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                        .type(ProvenanceSigner.TYPE)
                        .keyID("kid-unknown")
                        .build(),
                payload);
        unknownKey.sign(Ed25519Jws.signer(keys.signingKey().privateKey()));

        assertThat(otherInstance.verify(jws)).isEmpty();
        assertThat(verifier().verify(wrongType.serialize())).isEmpty();
        assertThat(verifier().verify(unknownKey.serialize())).isEmpty();
        assertThat(verifier().verify("not a record")).isEmpty();
    }

    @Test
    void shouldPublishOnlyThePublicHalfOfEachKey() throws Exception {
        var set = keys.publicJwkSet();

        assertThat(set.getKeys()).singleElement().satisfies(jwk -> {
            var okp = (OctetKeyPair) jwk;
            assertThat(okp.getKeyID()).isEqualTo("kid-1");
            assertThat(okp.isPrivate()).isFalse();
            assertThat(okp.getX().decode()).hasSize(32);
        });
        assertThat(set.toJSONObject().toString()).doesNotContain("\"d\"");
        // The published x is the stored key that verifies: the last 32 bytes of its X.509 encoding.
        byte[] encoded = stored.getPublicKey();
        assertThat(((OctetKeyPair) set.getKeys().getFirst()).getX().decode())
                .isEqualTo(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }

    @Test
    void shouldVerifyOldAndNewRecordsWhenTheSystemKeyRotates() {
        var before = signer().sign(ProvenanceRecord.Kind.FEEDBACK, TEXT, null, AT);
        String rotatedKey = "rotated-encryption-key-32-bytes!";
        SystemSecrets rotation =
                TestSystemSecrets.systemSecretsWithPrior(rotatedKey, "test-encryption-key-32-bytes-aes");
        when(repository.lockAllForKeyRotation()).thenReturn(List.of(stored));
        var rotating = new ProvenanceKeys(repository, rotation, Clock.fixed(AT, ZoneOffset.UTC));

        assertThat(rotating.resealWithActiveKey(rotation)).isEqualTo(1);
        assertThat(rotating.resealWithActiveKey(rotation)).isZero();

        // After the re-seal, the new system key alone opens the signing key.
        var rotated = new ProvenanceKeys(
                repository, TestSystemSecrets.systemSecrets(rotatedKey), Clock.fixed(AT, ZoneOffset.UTC));
        rotated.verifyAllOpen();
        var after = new ProvenanceSigner(rotated, release, ISSUER).sign(ProvenanceRecord.Kind.FEEDBACK, TEXT, null, AT);
        var verifier = new ProvenanceVerifier(rotated, ISSUER);
        assertThat(verifier.verify(before.jws())).contains(before.record());
        assertThat(verifier.verify(after.jws())).contains(after.record());
    }

    private ProvenanceSigner signer() {
        return new ProvenanceSigner(keys, release, ISSUER);
    }

    private ProvenanceVerifier verifier() {
        return new ProvenanceVerifier(keys, ISSUER);
    }

    private ProvenanceSigningKey key(String kid) throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return new ProvenanceSigningKey(
                kid, pair.getPublic().getEncoded(), pair.getPrivate().getEncoded(), AT, secrets);
    }
}
