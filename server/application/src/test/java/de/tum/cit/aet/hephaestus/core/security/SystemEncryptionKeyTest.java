package de.tum.cit.aet.hephaestus.core.security;

import static de.tum.cit.aet.hephaestus.testconfig.TestSystemEncryptionKeys.systemKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtSigningKeySealer;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SystemEncryptionKeyTest extends BaseUnitTest {

    /**
     * The operator this message has to serve counted 32 characters and still got rejected. Saying only
     * "must be 32 bytes. Got: 64" sends them hunting for a length they already have, so the message
     * must state both counts and say which one is wrong and why.
     */
    @Test
    void rejectsKeyThatIsNot32BytesAndExplainsTheCharacterCountItGotInstead() {
        // 32 CHARS but multibyte ⇒ >32 bytes ⇒ must fail fast at construction, not at first encrypt.
        String multibyte = "ä".repeat(32); // 32 chars, 64 UTF-8 bytes
        assertThatThrownBy(() -> systemKey(multibyte, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32-byte")
                .hasMessageContaining("Got 64 bytes from 32 characters")
                .hasMessageContaining("non-ASCII")
                .hasMessageContaining("openssl rand -base64 24 | cut -c1-32")
                .as("never echo the key material itself")
                .hasMessageNotContaining(multibyte);
    }

    /** An ASCII key of the wrong length is the ordinary case, and "32 characters" is true for it. */
    @Test
    void tellsAnAsciiKeyOfTheWrongLengthTheCountInCharacters() {
        assertThatThrownBy(() -> systemKey("tooshort", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Got 8 bytes from 8 characters")
                .hasMessageContaining("that is 32 characters");
    }

    @Test
    void shouldUseTheSameByteLengthValidationForJwtSealing() {
        var sealer = new JwtSigningKeySealer(systemKey("ä".repeat(16)));
        byte[] secret = {1, 2, 3};
        assertThat(sealer.unseal(sealer.seal(secret))).isEqualTo(secret);
    }

    @ParameterizedTest
    @ValueSource(strings = {"specs", "cds-training"})
    void shouldTreatMissingArtifactKeysAsExpectedWithoutWeakeningProduction(String profile, CapturedOutput output) {
        assertThat(systemKey(null, profile).key()).isNull();
        assertThat(output.getAll()).doesNotContain("WARN");
        assertThatThrownBy(() -> systemKey(null, profile, "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required in production");
    }

    @Test
    void shouldStillWarnWhenRuntimeEncryptionIsUnavailable(CapturedOutput output) {
        assertThat(systemKey(null, "dev").key()).isNull();
        assertThat(output.getAll()).contains("Skipped encryption configuration: reason=missing_key");
    }

    @Test
    void shouldShareValidatedStateWithHibernateCreatedConverters(CapturedOutput output) {
        new ApplicationContextRunner()
                .withBean(
                        SecurityProperties.class,
                        () -> new SecurityProperties(
                                "0123456789abcdef0123456789abcdef", null, 1, null, null, false, 100))
                .withUserConfiguration(
                        SystemEncryptionKey.class, EncryptedStringConverter.class, JwtSigningKeySealer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var springConverter = context.getBean(EncryptedStringConverter.class);
                    // SpringBeanContainer's JPA-compliant path uses createBean rather than getBean.
                    var hibernateConverter =
                            context.getAutowireCapableBeanFactory().createBean(EncryptedStringConverter.class);
                    assertThat(hibernateConverter).isNotSameAs(springConverter);
                    assertThat(hibernateConverter.convertToEntityAttribute(
                                    springConverter.convertToDatabaseColumn("secret")))
                            .isEqualTo("secret");
                    var sealer = context.getBean(JwtSigningKeySealer.class);
                    byte[] secret = {1, 2, 3};
                    assertThat(sealer.unseal(sealer.seal(secret))).isEqualTo(secret);
                    assertThat(output.getAll().split("Enabled system encryption", -1))
                            .hasSize(2);
                });
    }

    @Test
    void shouldKeepStandaloneSchemaInspectionUnencrypted() {
        var converter = new EncryptedStringConverter();
        assertThat(converter.convertToDatabaseColumn("schema-only")).isEqualTo("schema-only");
    }
}
