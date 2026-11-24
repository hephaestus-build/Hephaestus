package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.security.SecurityProperties;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import org.jspecify.annotations.Nullable;

public final class TestSystemSecrets {

    public static final String DEFAULT_KEY = "test-encryption-key-32-bytes-aes";

    private TestSystemSecrets() {}

    public static SystemSecrets systemSecrets(String key) {
        return systemSecretsWithPrior(key, null);
    }

    public static SystemSecrets systemSecretsWithPrior(String key, @Nullable String priorKey) {
        return new SystemSecrets(properties(key, priorKey));
    }

    public static SecurityProperties properties(@Nullable String key, @Nullable String priorKey) {
        return new SecurityProperties(key, null, 1, null, null, false, 100, priorKey, false);
    }
}
