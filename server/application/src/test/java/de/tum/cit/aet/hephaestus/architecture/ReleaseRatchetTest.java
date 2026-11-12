package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReleaseRatchetTest extends BaseUnitTest {
    @Test
    void shouldDefaultToTheRootManifestVersion(@TempDir Path directory) throws IOException {
        var manifest = directory.resolve("package.json");
        Files.writeString(manifest, "{\"version\":\"1.0.0-rc.5\"}");
        var version = ReleaseRatchet.targetVersion(manifest, null);
        assertThat(version).isEqualTo("1.0.0-rc.5");
        assertThatCode(() ->
                        ReleaseRatchet.assertEmptyForRelease(version, "Lifecycle debt", List.of("Workspace: INBOX")))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldGateThePlannedStableVersionWhileTheSourceIsAPrerelease(@TempDir Path directory) throws IOException {
        var manifest = directory.resolve("package.json");
        Files.writeString(manifest, "{\"version\":\"1.0.0-rc.5\"}");
        var version = ReleaseRatchet.targetVersion(manifest, "1.0.0");
        assertThat(version).isEqualTo("1.0.0");
        assertThatThrownBy(() ->
                        ReleaseRatchet.assertEmptyForRelease(version, "Lifecycle debt", List.of("Workspace: INBOX")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("release 1.0.0")
                .hasMessageContaining("Workspace: INBOX");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0.0-rc.5", "0.0.0-development", "1.1.0-beta.1+build.7"})
    void shouldReportDebtWithoutBlockingAPrerelease(String version) {
        assertThatCode(() ->
                        ReleaseRatchet.assertEmptyForRelease(version, "Lifecycle debt", List.of("Workspace: INBOX")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0.0", "1.1.0", "2.0.0+build.7"})
    void shouldBlockAReleaseAndNameItsRemainingOwners(String version) {
        assertThatThrownBy(() ->
                        ReleaseRatchet.assertEmptyForRelease(version, "Lifecycle debt", List.of("Workspace: INBOX")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(version)
                .hasMessageContaining("Workspace: INBOX");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0.0", "1.0.0-rc.5", "2.0.0+build.7"})
    void shouldPermitAnEmptyRatchet(String version) {
        assertThatCode(() -> ReleaseRatchet.assertEmptyForRelease(version, "Lifecycle debt", List.of()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "1.0", "v1.0.0", "01.0.0", "development", "1.0.0-.", "1.0.0-01", "1.0.0-rc..1", "1.0.0+"})
    void shouldRefuseAnUnreadableVersionInsteadOfBypassingTheGate(String version) {
        assertThatThrownBy(() -> ReleaseRatchet.assertEmptyForRelease(version, "Lifecycle debt", List.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("semantic version");
    }
}
