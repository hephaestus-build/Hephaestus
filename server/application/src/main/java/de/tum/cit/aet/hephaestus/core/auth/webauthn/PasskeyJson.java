package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.webauthn.api.AttestationConveyancePreference;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Framework codecs handle browser input. Typed snapshots handle database storage without polymorphic typing. */
public final class PasskeyJson {
    private final JsonMapper mapper =
            JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T read(String value, TypeReference<T> type) {
        return mapper.readValue(value, type);
    }

    private record Rp(String id, String name) {}

    private record User(String id, String name, String displayName) {}

    private record Parameter(String type, long alg) {}

    private record Descriptor(
            String id, String type, @Nullable Set<String> transports) {
        PublicKeyCredentialDescriptor restore() {
            return PublicKeyCredentialDescriptor.builder()
                    .id(Bytes.fromBase64(id))
                    .transports(
                            transports == null
                                    ? Set.of()
                                    : transports.stream()
                                            .map(AuthenticatorTransport::valueOf)
                                            .collect(Collectors.toSet()))
                    .build();
        }
    }

    private record Selection(String residentKey, String userVerification) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Creation(
            Rp rp,
            User user,
            String challenge,
            List<Parameter> pubKeyCredParams,
            long timeout,
            List<Descriptor> excludeCredentials,
            Selection authenticatorSelection,
            String attestation) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Request(
            String challenge, String rpId, long timeout, List<Descriptor> allowCredentials, String userVerification) {}

    public PublicKeyCredentialCreationOptions readCreation(String value) {
        return restored(() -> {
            Creation stored = mapper.readValue(value, Creation.class);
            if (!"required".equals(stored.authenticatorSelection().userVerification())
                    || !"required".equals(stored.authenticatorSelection().residentKey()))
                throw new IllegalStateException(
                        "Stored enrollment must require user verification and discoverable credentials");
            List<PublicKeyCredentialParameters> supported = List.of(
                    PublicKeyCredentialParameters.EdDSA,
                    PublicKeyCredentialParameters.ES256,
                    PublicKeyCredentialParameters.RS256);
            var parameters = stored.pubKeyCredParams().stream()
                    .map(p -> supported.stream()
                            .filter(s -> s.getAlg().getValue() == p.alg())
                            .findFirst()
                            .orElseThrow())
                    .toList();
            return PublicKeyCredentialCreationOptions.builder()
                    .rp(PublicKeyCredentialRpEntity.builder()
                            .id(stored.rp().id())
                            .name(stored.rp().name())
                            .build())
                    .user(ImmutablePublicKeyCredentialUserEntity.builder()
                            .id(Bytes.fromBase64(stored.user().id()))
                            .name(stored.user().name())
                            .displayName(stored.user().displayName())
                            .build())
                    .challenge(Bytes.fromBase64(stored.challenge()))
                    .pubKeyCredParams(parameters)
                    .timeout(Duration.ofMillis(stored.timeout()))
                    .excludeCredentials(stored.excludeCredentials().stream()
                            .map(Descriptor::restore)
                            .toList())
                    .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                            .residentKey(ResidentKeyRequirement.REQUIRED)
                            .userVerification(UserVerificationRequirement.REQUIRED)
                            .build())
                    .attestation(AttestationConveyancePreference.valueOf(stored.attestation()))
                    .build();
        });
    }

    public PublicKeyCredentialRequestOptions readRequest(String value) {
        return restored(() -> {
            Request stored = mapper.readValue(value, Request.class);
            if (!"required".equals(stored.userVerification()))
                throw new IllegalStateException("Stored assertion must require user verification");
            return PublicKeyCredentialRequestOptions.builder()
                    .challenge(Bytes.fromBase64(stored.challenge()))
                    .rpId(stored.rpId())
                    .timeout(Duration.ofMillis(stored.timeout()))
                    .allowCredentials(stored.allowCredentials().stream()
                            .map(Descriptor::restore)
                            .toList())
                    .userVerification(UserVerificationRequirement.REQUIRED)
                    .build();
        });
    }

    private record StoredCredential(
            String credentialId,
            String userId,
            String publicKey,
            long signatureCount,
            boolean uvInitialized,
            Set<String> transports,
            boolean backupEligible,
            boolean backupState,
            @Nullable String attestationObject,
            @Nullable String attestationClientDataJSON,
            @Nullable Instant created,
            @Nullable Instant lastUsed,
            String label) {}

    public String writeCredential(CredentialRecord record) {
        return write(new StoredCredential(
                record.getCredentialId().toBase64UrlString(),
                record.getUserEntityUserId().toBase64UrlString(),
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(record.getPublicKey().getBytes()),
                record.getSignatureCount(),
                record.isUvInitialized(),
                record.getTransports().stream()
                        .map(AuthenticatorTransport::getValue)
                        .collect(Collectors.toSet()),
                record.isBackupEligible(),
                record.isBackupState(),
                record.getAttestationObject() == null
                        ? null
                        : record.getAttestationObject().toBase64UrlString(),
                record.getAttestationClientDataJSON() == null
                        ? null
                        : record.getAttestationClientDataJSON().toBase64UrlString(),
                record.getCreated(),
                record.getLastUsed(),
                record.getLabel()));
    }

    public ImmutableCredentialRecord readCredential(String value) {
        return restored(() -> {
            StoredCredential stored = mapper.readValue(value, StoredCredential.class);
            var builder = ImmutableCredentialRecord.builder()
                    .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                    .credentialId(Bytes.fromBase64(stored.credentialId()))
                    .userEntityUserId(Bytes.fromBase64(stored.userId()))
                    .publicKey(ImmutablePublicKeyCose.fromBase64(stored.publicKey()))
                    .signatureCount(stored.signatureCount())
                    .uvInitialized(stored.uvInitialized())
                    .transports(stored.transports().stream()
                            .map(AuthenticatorTransport::valueOf)
                            .collect(Collectors.toSet()))
                    .backupEligible(stored.backupEligible())
                    .backupState(stored.backupState())
                    .attestationObject(
                            stored.attestationObject() == null ? null : Bytes.fromBase64(stored.attestationObject()))
                    .attestationClientDataJSON(
                            stored.attestationClientDataJSON() == null
                                    ? null
                                    : Bytes.fromBase64(stored.attestationClientDataJSON()))
                    .label(stored.label());
            if (stored.created() != null) {
                builder.created(stored.created());
            }
            if (stored.lastUsed() != null) {
                builder.lastUsed(stored.lastUsed());
            }
            return builder.build();
        });
    }

    private static <T> T restored(Supplier<T> restore) {
        try {
            return restore.get();
        } catch (JacksonException | IllegalArgumentException | IllegalStateException failure) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Stored passkey data could not be read.", failure);
        }
    }
}
