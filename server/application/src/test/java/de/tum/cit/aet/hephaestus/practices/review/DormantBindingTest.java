package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactCatalog;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptionsFixture;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DormantBindingTest extends BaseUnitTest {

    private static final ArtifactCatalog ARTIFACTS = PracticeSignalOptionsFixture.catalog();

    @Test
    void shouldNameTheMomentsAndTheIntegrationsThatReportThemInTheirOwnWords() {
        var dormant = new DormantBinding(
                1L,
                Set.of(ScmSignals.ISSUE_OPENED, ScmSignals.ISSUE_CLOSED),
                Set.of(IntegrationKind.GITHUB, IntegrationKind.GITLAB));

        String reason = dormant.reason(ARTIFACTS::signalDisplayName, DormantBindingTest::integration);

        assertThat(reason)
                .isEqualTo("Nothing connected to this workspace reports the moments this practice watches for "
                        + "(Closed, Opened); GitHub or GitLab would.")
                // A developer reads this on their own work's trace and cannot connect anything.
                .doesNotContain("Connect", "scm.", "GITHUB", "GITLAB");
    }

    @Test
    void shouldSayWhenNothingCanEverReportTheMoments() {
        var dormant = new DormantBinding(1L, Set.of(ScmSignals.PULL_REQUEST_MERGED), Set.of());

        assertThat(dormant.reason(ARTIFACTS::signalDisplayName, DormantBindingTest::integration))
                .isEqualTo("Nothing Hephaestus can connect reports the moments this practice watches for (Merged), "
                        + "so it is never reviewed.");
    }

    @Test
    void shouldNameASignalNoDescriptorDeclaresGenerically() {
        var dormant = new DormantBinding(
                1L, Set.of(SignalName.of("scm.pull_request.retired_moment")), Set.of(IntegrationKind.GITHUB));

        assertThat(dormant.reason(ARTIFACTS::signalDisplayName, DormantBindingTest::integration))
                .contains("(A moment this version no longer offers)")
                .doesNotContain("scm.", "retired_moment");
    }

    private static String integration(IntegrationKind kind) {
        return kind == IntegrationKind.GITLAB ? "GitLab" : "GitHub";
    }
}
