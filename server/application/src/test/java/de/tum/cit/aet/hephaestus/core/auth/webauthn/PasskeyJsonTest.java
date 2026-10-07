package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.webauthn.api.AttestationConveyancePreference;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;

@Tag("unit")
class PasskeyJsonTest {
    @Test
    void shouldPreserveChallengeAndMandatoryVerificationAcrossStorage() {
        PasskeyJson json = new PasskeyJson();
        var options = PublicKeyCredentialRequestOptions.builder()
                .challenge(Bytes.random())
                .rpId("example.com")
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build();
        var stored = json.readRequest(json.write(options));
        assertThat(stored.getChallenge()).isEqualTo(options.getChallenge());
        assertThat(stored.getUserVerification()).isEqualTo(UserVerificationRequirement.REQUIRED);
        assertThat(stored.getRpId()).isEqualTo("example.com");
    }

    @Test
    void shouldPreserveCredentialRecordAcrossStorage() {
        var json = new PasskeyJson();
        var record = ImmutableCredentialRecord.builder()
                .credentialId(Bytes.random())
                .userEntityUserId(Bytes.random())
                .publicKey(new ImmutablePublicKeyCose(new byte[] {-1, -2, -3}))
                .signatureCount(7)
                .uvInitialized(true)
                .backupEligible(true)
                .backupState(true)
                .transports(Set.of(AuthenticatorTransport.INTERNAL))
                .attestationObject(Bytes.random())
                .attestationClientDataJSON(Bytes.random())
                .created(Instant.parse("2026-10-07T12:00:00Z"))
                .label("Device")
                .build();
        var stored = json.readCredential(json.writeCredential(record));
        assertThat(stored.getCredentialId()).isEqualTo(record.getCredentialId());
        assertThat(stored.getUserEntityUserId()).isEqualTo(record.getUserEntityUserId());
        assertThat(stored.getPublicKey().getBytes())
                .containsExactly(record.getPublicKey().getBytes());
        assertThat(stored.getSignatureCount()).isEqualTo(7);
        assertThat(stored.isUvInitialized()).isTrue();
        assertThat(stored.isBackupEligible()).isTrue();
        assertThat(stored.getCreated()).isEqualTo(record.getCreated());
        assertThat(stored.getLabel()).isEqualTo("Device");
    }

    @Test
    void shouldPreserveEnrollmentAcrossStorage() {
        var json = new PasskeyJson();
        var options = PublicKeyCredentialCreationOptions.builder()
                .rp(PublicKeyCredentialRpEntity.builder()
                        .id("example.com")
                        .name("Test")
                        .build())
                .user(ImmutablePublicKeyCredentialUserEntity.builder()
                        .id(Bytes.random())
                        .name("42")
                        .displayName("Ada")
                        .build())
                .challenge(Bytes.random())
                .pubKeyCredParams(PublicKeyCredentialParameters.ES256)
                .timeout(Duration.ofMinutes(5))
                .excludeCredentials(List.of())
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build())
                .attestation(AttestationConveyancePreference.NONE)
                .build();
        var stored = json.readCreation(json.write(options));
        assertThat(stored.getChallenge()).isEqualTo(options.getChallenge());
        assertThat(stored.getUser().getId()).isEqualTo(options.getUser().getId());
        assertThat(stored.getAuthenticatorSelection().getUserVerification())
                .isEqualTo(UserVerificationRequirement.REQUIRED);
        assertThat(stored.getPubKeyCredParams()).hasSize(1);
    }
}
