package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.core.LauncherFactory;

@Tag("architecture")
class TestTierTaggingArchTest {

    private static final Set<String> TIERS = Set.of("unit", "architecture", "database", "integration", "live");

    @Test
    void everyDiscoveredTestHasATier() {
        var plan = LauncherFactory.create()
                .discover(request()
                        .selectors(selectPackage("de.tum.cit.aet.hephaestus"))
                        .build());
        var tests = plan.getRoots().stream()
                .flatMap(root -> plan.getDescendants(root).stream())
                // Parameterized tests and dynamic-test factories are method containers at discovery.
                .filter(test -> test.isTest()
                        || test.getSource()
                                .filter(MethodSource.class::isInstance)
                                .isPresent())
                .toList();

        assertThat(tests).as("JUnit must discover the server tests").isNotEmpty();
        assertThat(tests.stream()
                        .filter(test ->
                                test.getTags().stream().map(TestTag::getName).noneMatch(TIERS::contains))
                        .map(test -> test.getUniqueId())
                        .toList())
                .as("Tests without a tier run in no required suite; assign the tag their behaviour needs")
                .isEmpty();
    }
}
