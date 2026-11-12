package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR 0099 release gates use the planned version from {@code hephaestus.releaseVersion}, or root
 * {@code package.json} by default. Gradle forwards {@code -Phephaestus.releaseVersion=1.0.0} to the test JVM.
 */
final class ReleaseRatchet {
    private static final Logger log = LoggerFactory.getLogger(ReleaseRatchet.class);
    private static final String NUMBER = "(?:0|[1-9][0-9]*)";
    private static final String PRERELEASE_ID = "(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)";
    private static final Pattern VERSION = Pattern.compile(NUMBER + "\\." + NUMBER + "\\." + NUMBER
            + "(?<prerelease>-" + PRERELEASE_ID + "(?:\\." + PRERELEASE_ID + ")*)?"
            + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");

    private ReleaseRatchet() {}

    static void assertEmptyForRelease(String name, Collection<?> entries) {
        var version = targetVersion(Path.of("../../package.json"), System.getProperty("hephaestus.releaseVersion"));
        assertEmptyForRelease(version, name, entries);
    }

    static String targetVersion(Path manifest, @Nullable String explicitTarget) {
        if (explicitTarget != null) return explicitTarget;
        try {
            return JsonMapper.builder()
                    .build()
                    .readTree(Files.readString(manifest))
                    .path("version")
                    .asString();
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read the release version from root package.json", failure);
        }
    }

    static void assertEmptyForRelease(String version, String name, Collection<?> entries) {
        var parsed = VERSION.matcher(version);
        assertThat(parsed.matches())
                .as("Release target must be a semantic version: %s", version)
                .isTrue();
        if (parsed.group("prerelease") == null) {
            assertThat(entries)
                    .as("%s must be empty for release %s; remaining owners and reasons: %s", name, version, entries)
                    .isEmpty();
        } else {
            log.info("Release ratchet: name={}, version={}, remaining={}", name, version, entries);
        }
    }
}
