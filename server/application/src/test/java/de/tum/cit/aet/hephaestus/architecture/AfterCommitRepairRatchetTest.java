package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AfterCommitRepairRatchetTest extends BaseUnitTest {
    private static final Map<String, String> UNREPAIRED = Map.of(
            "GitLabConnectionStateListener.onDeactivated", "owner=INBOX, reason=no repair for suspended connections");

    @ParameterizedTest
    @ValueSource(strings = {"1.0.0-rc.5", "1.0.0-rc.5+build.1"})
    void shouldAllowPreReleaseAndReportTheUnrepairedEntries(String version) {
        var reports = new ArrayList<String>();

        ReleaseRatchet.assertEmptyForRelease(
                version, "After-commit repair ratchet", UNREPAIRED.entrySet(), reports::add);

        assertThat(reports)
                .singleElement()
                .asString()
                .contains(
                        version,
                        "GitLabConnectionStateListener.onDeactivated",
                        "INBOX",
                        "no repair for suspended connections");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0.0", "1.0.0+build-test"})
    void shouldRefuseReleaseWithTheOwnerAndReason(String version) {
        var reports = new ArrayList<String>();

        assertThatThrownBy(() -> ReleaseRatchet.assertEmptyForRelease(
                        version, "After-commit repair ratchet", UNREPAIRED.entrySet(), reports::add))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("INBOX")
                .hasMessageContaining("no repair for suspended connections");
        assertThat(reports).singleElement().asString().contains("INBOX", "no repair for suspended connections");
    }

    @Test
    void shouldAllowReleaseWhenThereAreNoUnrepairedEntries() {
        var reports = new ArrayList<String>();

        ReleaseRatchet.assertEmptyForRelease(
                "1.0.0", "After-commit repair ratchet", Map.of().entrySet(), reports::add);

        assertThat(reports).singleElement().asString().contains("1.0.0", "[]");
    }
}
