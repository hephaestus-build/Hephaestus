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
                                true,
                                true,
                                true,
                                draft(Draft.Disposition.ISSUE, Draft.Confidence.HIGH, true),
                                false,
                                false))
                        .route())
                .isEqualTo(FilingRoute.PUBLIC);
    }

    @ParameterizedTest
    @EnumSource(
            value = Draft.Disposition.class,
            names = {"NOT_AN_ISSUE", "SECURITY"})
    void shouldKeepNonIssuesAndSecurityReportsPrivateRegardlessOfTheSelectedKind(Draft.Disposition disposition) {
        assertThat(RoutingGate.decide(new RoutingGate.Inputs(
                                true, true, true, draft(disposition, Draft.Confidence.HIGH, true), false, false))
                        .route())
                .isEqualTo(FilingRoute.PRIVATE);
    }

    @ParameterizedTest
    @EnumSource(
            value = Draft.Confidence.class,
            names = {"LOW", "MEDIUM"})
    void shouldKeepUncertainDraftsPrivate(Draft.Confidence confidence) {
        assertThat(RoutingGate.decide(new RoutingGate.Inputs(
                                true, true, true, draft(Draft.Disposition.ISSUE, confidence, true), false, false))
                        .route())
                .isEqualTo(FilingRoute.PRIVATE);
    }

    @Test
    void shouldRequirePermissionAndRespectRedactionAndTheRiskLatch() {
        Draft draft = draft(Draft.Disposition.ISSUE, Draft.Confidence.HIGH, true);
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

    @Test
    void shouldRefusePromotionWhenTheDraftIsNotAnIssueOrIsIncoherent() {
        assertThat(RoutingGate.refusePromotion(
                        true, draft(Draft.Disposition.NOT_AN_ISSUE, Draft.Confidence.HIGH, true), false))
                .isEqualTo(FilingReason.LOW_CONFIDENCE);
        assertThat(RoutingGate.refusePromotion(
                        true, draft(Draft.Disposition.ISSUE, Draft.Confidence.HIGH, false), false))
                .isEqualTo(FilingReason.LOW_CONFIDENCE);
    }

    @ParameterizedTest
    @EnumSource(Draft.Confidence.class)
    void shouldAllowMaintainerPromotionOfACoherentIssueRegardlessOfConfidence(Draft.Confidence confidence) {
        assertThat(RoutingGate.refusePromotion(true, draft(Draft.Disposition.ISSUE, confidence, true), false))
                .isNull();
    }

    @Test
    void shouldNeverOverridePermissionSecurityOrTheRiskLatchDuringPromotion() {
        Draft issue = draft(Draft.Disposition.ISSUE, Draft.Confidence.HIGH, true);
        assertThat(RoutingGate.refusePromotion(false, issue, false)).isEqualTo(FilingReason.OPTED_OUT);
        assertThat(RoutingGate.refusePromotion(true, null, false)).isEqualTo(FilingReason.NO_DRAFT);
        assertThat(RoutingGate.refusePromotion(
                        true, draft(Draft.Disposition.SECURITY, Draft.Confidence.HIGH, true), false))
                .isEqualTo(FilingReason.SECURITY);
        assertThat(RoutingGate.refusePromotion(true, issue, true)).isEqualTo(FilingReason.REDACTION);
    }

    private static Draft draft(Draft.Disposition disposition, Draft.Confidence confidence, boolean coherent) {
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
                coherent,
                confidence);
    }
}
