package de.tum.cit.aet.hephaestus.core.runtime;

import org.springframework.context.annotation.Profile;
import org.springframework.core.SpringProperties;
import org.springframework.stereotype.Component;

/**
 * The cds-training profile exists for the image build's training run, which the buildpack starts with the JVM system
 * property {@code spring.context.exit=onRefresh}, so the process exits once the context refreshes. A container started
 * with that profile would otherwise serve with the build's fixture keys. Read through {@link SpringProperties}, as Spring's exit check does: an environment variable or a
 * command-line argument of the same name does not make the process exit, so it does not pass this guard either.
 */
@Component
@Profile("cds-training")
public final class CdsTrainingRunGuard {

    public CdsTrainingRunGuard() {
        if (!"onRefresh".equalsIgnoreCase(SpringProperties.getProperty("spring.context.exit"))) {
            throw new IllegalStateException("The cds-training profile runs only in the image build's training run."
                    + " Set SPRING_PROFILES_ACTIVE for the server, such as prod.");
        }
    }
}
