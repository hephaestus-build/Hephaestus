package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

/** Keeps the Bayesian implementation behind the one public trend-producing service. */
class PracticeTrendBoundaryArchitectureTest extends HephaestusArchitectureTest {

    private static final String TREND_PACKAGE = "de.tum.cit.aet.hephaestus.practices.result.trend..";

    @Test
    void trendInternalsMustOnlyBeAccessedInsideTheirPackage() {
        for (String internal : new String[] {
            "OpportunityBundler",
            "BetaPosterior",
            "TrendDirectionRule",
            "PracticeTrendCalculator",
            "GroupTrendAggregator",
        }) {
            noClasses()
                    .that()
                    .resideOutsideOfPackage(TREND_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .haveSimpleName(internal)
                    .check(classes);
        }
    }
}
