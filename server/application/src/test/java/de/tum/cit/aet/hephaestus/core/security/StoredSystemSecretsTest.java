package de.tum.cit.aet.hephaestus.core.security;

import static de.tum.cit.aet.hephaestus.testconfig.TestSystemSecrets.systemSecrets;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class StoredSystemSecretsTest extends BaseUnitTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_KEY = "fedcba9876543210fedcba9876543210";

    private final StoredSystemSecretRepository repository = mock(StoredSystemSecretRepository.class);

    @Test
    void shouldNameTheSecretAndBothKeyIdsWhenTheSystemKeyDoesNotOpenIt() {
        SystemSecrets sealedWith = systemSecrets(KEY, "test");
        when(repository.findAll()).thenReturn(List.of(stored("namespace-reservation", sealedWith)));
        SystemSecrets restored = systemSecrets(OTHER_KEY, "test");

        assertThatThrownBy(() -> new StoredSystemSecrets(repository, restored).verifyAllOpen())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("namespace-reservation")
                .hasMessageContaining(sealedWith.activeKeyId())
                .hasMessageContaining(restored.activeKeyId())
                .hasMessageNotContaining(KEY)
                .hasMessageNotContaining(OTHER_KEY);
    }

    @Test
    void shouldOpenEveryStoredSecretWithTheKeyThatSealedIt() {
        SystemSecrets secrets = systemSecrets(KEY, "test");
        when(repository.findAll()).thenReturn(List.of(stored("namespace-reservation", secrets)));

        assertThatCode(() -> new StoredSystemSecrets(repository, secrets).verifyAllOpen())
                .doesNotThrowAnyException();
    }

    /** The row as the repository loads it: no constructor writes a sealed value. */
    private static StoredSystemSecret stored(String name, SystemSecrets secrets) {
        var secret = new StoredSystemSecret();
        ReflectionTestUtils.setField(secret, "name", name);
        ReflectionTestUtils.setField(secret, "sealedValue", secrets.seal(new byte[32], StoredSystemSecret.binding(name)));
        return secret;
    }
}
