package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Locks the consumer surface in place.
 *
 * <ol>
 *   <li>{@code integration/scm/sync/} holds no non-{@code backfill} production code.
 *       The unified consumer fleet lives under {@code integration/consumer/}; per-kind
 *       sync drivers live under {@code integration/<kind>/sync/}. New code in the legacy
 *       package would re-grow the coupling this boundary exists to prevent.</li>
 *   <li>The {@code agent/} runtime role never imports {@code integration.consumer..}.
 *       Consumer beans are server-role-only ({@link de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole#SERVER_PROPERTY});
 *       letting the agent role link against them would re-introduce the bean-cluster
 *       leak that ADR 0008 separates by role.</li>
 * </ol>
 *
 * <p>Both rules are intentionally allow-list shaped: violations name the offending class so
 * accidental drift from a refactor surfaces as a precise failure, not a vague "package
 * boundary breached".
 */
class IntegrationConsumerBoundaryTest extends HephaestusArchitectureTest {

    @Test
    void scmSyncIsEmptyOfNonBackfillCode() {
        List<String> violations = classes.stream()
                .filter(c -> c.getPackageName().equals("de.tum.cit.aet.hephaestus.integration.scm.sync"))
                .map(JavaClass::getFullName)
                .collect(Collectors.toList());

        assertThat(violations)
                .as("integration/scm/sync/ must be empty of production code — the unified "
                        + "consumer lives under integration/consumer/ and the per-kind sync drivers live "
                        + "under integration/<kind>/sync/")
                .isEmpty();
    }

    @Test
    void agentDoesNotDependOnIntegrationConsumer() {
        noClasses()
                .that()
                .resideInAPackage("de.tum.cit.aet.hephaestus.agent..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("de.tum.cit.aet.hephaestus.integration.core.consumer..")
                .because("the agent job queue is Postgres-backed, not NATS-backed; depending on "
                        + "integration.consumer would mix bean clusters across roles and break the "
                        + "runtime-role isolation locked by RuntimeRoleBoundaryTest")
                .check(classes);
    }

    @Test
    void agentDoesNotLinkAgainstJnatsAtAll() {
        noClasses()
                .that()
                .resideInAPackage("de.tum.cit.aet.hephaestus.agent..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.nats..")
                .because("the agent job queue is Postgres-backed (review_job table, polled by "
                        + "ReviewJobExecutor) — the agent package must have zero io.nats dependency")
                .check(classes);
    }
}
