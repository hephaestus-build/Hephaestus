package de.tum.cit.aet.hephaestus.agent.sandbox;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import java.time.Duration;

/** Valid sandbox limits for tests that assert nothing about their values. */
public final class SandboxTestLimits {
    public static final SandboxProperties.DefaultResourceLimits REVIEW =
            new SandboxProperties.DefaultResourceLimits(2.0);

    public static final ResourceLimits ATTEMPT = REVIEW.forAttempt(Duration.ofMinutes(10));

    public static final SandboxProperties PROPERTIES = new SandboxProperties(5, 10, 60, REVIEW);

    private SandboxTestLimits() {}
}
