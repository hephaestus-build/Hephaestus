package de.tum.cit.aet.hephaestus.core.release;

import java.time.Instant;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

final class ReleaseFixtures {
    static final String COMMIT = "a".repeat(40);
    static final String IMAGE = "ghcr.io/hephaestus-build/server@sha256:" + "b".repeat(64);
    static final String ENVIRONMENT = "staging";
    static final String DEPLOYMENT = "0f6f1e0e-2a4b-4c5d-8e9f-0a1b2c3d4e5f";

    private ReleaseFixtures() {}

    static RunningRelease running(String version) {
        return running(version, ENVIRONMENT);
    }

    static RunningRelease running(String version, String environment) {
        return new RunningRelease(
                version, new ReleaseProperties(COMMIT, IMAGE, true, environment, ""), new MockEnvironment());
    }

    /** A stored rollout of {@link #DEPLOYMENT}: its revision is also its row. */
    static ReleaseActivation activation(String version, long revision, Instant at) {
        var entry = new ReleaseActivation(
                ENVIRONMENT, DEPLOYMENT, revision, version, ReleaseChannel.RELEASE, COMMIT, IMAGE, at);
        ReflectionTestUtils.setField(entry, "id", revision);
        return entry;
    }
}
