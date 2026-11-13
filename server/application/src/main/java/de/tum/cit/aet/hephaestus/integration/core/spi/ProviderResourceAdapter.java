package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/** Exact provider targets, independent of a connection's subsequent configuration (ADR 0101). */
public interface ProviderResourceAdapter {
    IntegrationKind kind();

    List<Resource> resources(IntegrationRef ref, ConnectionConfig config, @Nullable CredentialBundle credential);

    /** Reads ownership before removal. A successful DELETE alone is not terminal proof. */
    Removal remove(Resource resource, @Nullable CredentialBundle credential, BooleanSupplier beforeDispatch);

    /** A complete listing discovers only the immutable ownership marker of this creation attempt. */
    default List<Resource> discoverRegistration(Resource registration, @Nullable CredentialBundle credential) {
        return List.of();
    }

    enum Removal {
        VERIFIED_ABSENT,
        DIFFERENT_OWNER,
        AWAITING_CONFIRMATION,
        NEEDS_AUTHORIZATION,
        TERMINAL_SUCCESS,
        ADMIN_CONFIRMATION,
        CANCELLED
    }

    enum ResourceType {
        INSTALLATION,
        GROUP_HOOK,
        BOT_TOKEN,
        BOT_USER,
        SUBSCRIPTION,
        REGISTRATION
    }

    record Resource(
            IntegrationKind kind,
            String origin,
            ResourceType type,
            String parentId,
            String resourceId,
            @Nullable String ownershipHash) {
        public Resource {
            origin = ScmOrigin.of(origin)
                    .orElseThrow(() -> new IllegalArgumentException("A provider resource requires an origin"));
            if (parentId.contains("\n") || resourceId.contains("\n"))
                throw new IllegalArgumentException("A provider identity cannot contain a line separator");
        }

        public String identity() {
            return fingerprint(kind.name() + "\n" + origin + "\n" + type.name() + "\n" + parentId + "\n" + resourceId);
        }
    }

    static String fingerprint(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    static String slackTokenFingerprint(String token) {
        return fingerprint("hephaestus/slack-bot-token/v1" + token);
    }
}
