package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("unit")
class RoutingGateTest {
    @Test
    void shouldAllowAnActionableReportWhenTheReporterAllowedPublicAndAllChecksPass() {
        assertThat(RoutingGate.decide(new RoutingGate.Inputs(
                                true, true, true, draft(Draft.Disposition.ISSUE, Draft.Confidence.HIGH), false, false))
                        .route())
                .isEqualTo(FilingRoute.PUBLIC);
    }

    @ParameterizedTest
    @EnumSource(
            value = Draft.Disposition.class,
            names = {"NOT_AN_ISSUE", "SECURITY"})
    void shouldKeepNonIssuesAndSecurityReportsPrivateRegardlessOfTheSelectedKind(Draft.Disposition disposition) {
        assertThat(RoutingGate.decide(new RoutingGate.Inputs(
                                true, true, true, draft(disposition, Draft.Confidence.HIGH), false, false))
                        .route())
                .isEqualTo(FilingRoute.PRIVATE);
    }

    @ParameterizedTest
    @EnumSource(
            value = Draft.Confidence.class,
            names = {"LOW", "MEDIUM"})
    void shouldKeepUncertainDraftsPrivate(Draft.Confidence confidence) {
        assertThat(RoutingGate.decide(new RoutingGate.Inputs(
                                true, true, true, draft(Draft.Disposition.ISSUE, confidence), false, false))
                        .route())
                .isEqualTo(FilingRoute.PRIVATE);
    }

    @Test
    void shouldRequirePermissionAndRespectRedactionAndTheRiskLatch() {
        Draft draft = draft(Draft.Disposition.ISSUE, Draft.Confidence.HIGH);
        assertThat(RoutingGate.decide(new RoutingGate.Inputs(false, true, true, draft, false, false))
                        .route())
                .isEqualTo(FilingRoute.PRIVATE);
        assertThat(RoutingGate.decide(new RoutingGate.Inputs(true, true, true, draft, true, false))
                        .route())
                .isEqualTo(FilingRoute.PRIVATE);
        assertThat(RoutingGate.decide(new RoutingGate.Inputs(true, true, true, draft, false, true))
                        .route())
                .isEqualTo(FilingRoute.PRIVATE);
    }

    private static Draft draft(Draft.Disposition disposition, Draft.Confidence confidence) {
        return new Draft(
                disposition,
                Draft.Type.BUG,
                "Theme does not persist",
                "Dark mode resets",
                "Dark mode should persist",
                List.of("Choose dark mode", "Sign out"),
                "",
                "",
                List.of(),
                "",
                null,
                false,
                false,
                true,
                confidence);
    }
}
