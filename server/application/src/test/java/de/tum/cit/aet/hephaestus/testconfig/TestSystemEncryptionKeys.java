package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.security.SecurityProperties;
import de.tum.cit.aet.hephaestus.core.security.SystemEncryptionKey;
import org.jspecify.annotations.Nullable;
import org.springframework.mock.env.MockEnvironment;

public final class TestSystemEncryptionKeys {

    private TestSystemEncryptionKeys() {}

    public static SystemEncryptionKey systemKey(@Nullable String key, String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new SystemEncryptionKey(new SecurityProperties(key, null, 1, null, null, false, 100), environment);
    }
}
