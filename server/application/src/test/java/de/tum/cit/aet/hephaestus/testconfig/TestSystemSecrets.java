package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.security.SecurityProperties;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import org.jspecify.annotations.Nullable;
import org.springframework.mock.env.MockEnvironment;

public final class TestSystemSecrets {

    private TestSystemSecrets() {}

    public static SystemSecrets systemSecrets(@Nullable String key, String... profiles) {
        return systemSecretsWithPrior(key, null, profiles);
    }

    public static SystemSecrets systemSecretsWithPrior(
            @Nullable String key, @Nullable String priorKey, String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new SystemSecrets(properties(key, priorKey), environment);
    }

    public static SecurityProperties properties(@Nullable String key, @Nullable String priorKey) {
        return new SecurityProperties(key, null, 1, null, null, false, 100, priorKey, false);
    }
}
