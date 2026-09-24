package de.tum.cit.aet.hephaestus.integration.core.connection.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.security.EncryptionException;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.EncryptionContext;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the legacy-{@code EncryptedStringConverter} → {@link CredentialBundleConverter}
 * compatibility surface that {@link WorkspaceConnectionBackfillChange}'s package-private
 * helpers expose. Drives ONLY the crypto helpers — the customChange's
 * {@code execute(Database)} path is exercised end-to-end against a real Testcontainer
 * PostgreSQL by {@code WorkspaceConnectionBackfillChangeIntegrationTest}.
 */
class CredentialBundleCryptoCompatTest extends BaseUnitTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final byte[] KEY_BYTES = KEY.getBytes(StandardCharsets.UTF_8);

    @Nested
    class DecryptLegacy {

        @Test
        void roundTripsAValueProducedByTheLegacyEncryptedStringConverter() throws Exception {
            String plaintext = "ghp_secret-PAT-value-with-symbols_+/=";
            String encoded = encryptLegacy(plaintext, KEY_BYTES);

            assertThat(WorkspaceConnectionBackfillChange.decryptLegacy(encoded, KEY_BYTES))
                    .isEqualTo(plaintext);
        }

        @Test
        void unprefixedValuesPassThroughUnchanged() throws Exception {
            // EncryptedStringConverter writes ENC:<base64> for encrypted values but tolerates
            // legacy unencrypted plaintext for pre-encryption rows. Match that behaviour so
            // a workspace with a plaintext PAT (dev-mode, no key set) still migrates.
            assertThat(WorkspaceConnectionBackfillChange.decryptLegacy("plain-token", KEY_BYTES))
                    .isEqualTo("plain-token");
        }

        @Test
        void rejectsCiphertextShorterThanTheIv() {
            assertThatThrownBy(() -> WorkspaceConnectionBackfillChange.decryptLegacy(
                            "ENC:" + Base64.getEncoder().encodeToString(new byte[5]), KEY_BYTES))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class EncryptV2 {

        @Test
        void producesBlobsThatDecryptBackWithTheRealConverter() {
            CredentialBundleConverter converter = new CredentialBundleConverter(KEY, false);
            EncryptionContext ctx =
                    new EncryptionContext(7L, IntegrationKind.GITHUB, "pat", "connection.credentials_encrypted");

            // Build the bundle JSON via Jackson (matches what the customChange does internally)
            // — hardcoding a literal would drift from the discriminator on the sealed type.
            String bundleJson;
            try {
                bundleJson = tools.jackson.databind.json.JsonMapper.builder()
                        .findAndAddModules()
                        .build()
                        .writeValueAsString(new ApiCredentialProvider.BearerToken("ghp_xyz", null));
            } catch (Exception e) {
                throw new AssertionError(e);
            }

            byte[] blob;
            try {
                blob = WorkspaceConnectionBackfillChange.encryptV2(
                        bundleJson.getBytes(StandardCharsets.UTF_8), KEY_BYTES, ctx.toAad());
            } catch (Exception e) {
                throw new AssertionError(e);
            }

            assertThat(blob[0]).as("v2 format version byte").isEqualTo(CredentialBundleConverter.FORMAT_VERSION_V2);

            // The real converter's decrypt path is the runtime contract; if our customChange
            // writes blobs the converter cannot read, every PAT-mode workspace breaks at
            // first request post-deploy.
            var decrypted = converter.decrypt(blob, ctx);
            assertThat(decrypted).isInstanceOf(ApiCredentialProvider.BearerToken.class);
            assertThat(((ApiCredentialProvider.BearerToken) decrypted).token()).isEqualTo("ghp_xyz");
        }

        @Test
        void aadIsBoundToTheRow_wrongContextFailsAuthentication() {
            CredentialBundleConverter converter = new CredentialBundleConverter(KEY, false);
            EncryptionContext writeCtx =
                    new EncryptionContext(7L, IntegrationKind.GITHUB, "pat", "connection.credentials_encrypted");
            EncryptionContext readCtx = new EncryptionContext(
                    7L, IntegrationKind.GITHUB, "different-instance", "connection.credentials_encrypted");

            String bundleJson;
            byte[] blob;
            try {
                bundleJson = tools.jackson.databind.json.JsonMapper.builder()
                        .findAndAddModules()
                        .build()
                        .writeValueAsString(new ApiCredentialProvider.BearerToken("x", null));
                blob = WorkspaceConnectionBackfillChange.encryptV2(
                        bundleJson.getBytes(StandardCharsets.UTF_8), KEY_BYTES, writeCtx.toAad());
            } catch (Exception e) {
                throw new AssertionError(e);
            }

            // Cross-row ciphertext substitution must fail authentication.
            assertThatThrownBy(() -> converter.decrypt(blob, readCtx)).isInstanceOf(EncryptionException.class);
        }
    }

    /** Mirrors EncryptedStringConverter#convertToDatabaseColumn — used only by tests. */
    private static String encryptLegacy(String plaintext, byte[] keyBytes) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, iv));
        byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
        return "ENC:" + Base64.getEncoder().encodeToString(combined);
    }
}
