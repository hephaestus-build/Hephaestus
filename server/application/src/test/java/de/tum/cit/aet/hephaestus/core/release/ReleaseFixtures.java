package de.tum.cit.aet.hephaestus.core.release;

import org.springframework.mock.env.MockEnvironment;

final class ReleaseFixtures {
    static final String COMMIT = "a".repeat(40);
    static final String IMAGE = "ghcr.io/hephaestus-build/application-server@sha256:" + "b".repeat(64);

    private ReleaseFixtures() {}

    static RunningRelease running(String version) {
        return new RunningRelease(version, new ReleaseProperties(COMMIT, IMAGE, true), new MockEnvironment());
    }
}
