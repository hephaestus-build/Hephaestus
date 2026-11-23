package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.tum.cit.aet.hephaestus.core.MaintenanceBarrier;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every claim path refuses while a database migration runs. A claim path is a query that takes a new lease: it moves a
 * row's lease epoch on, or it inserts a row that is already leased. A renewal, a requeue or an end keeps the epoch, so
 * the work in flight finishes under the barrier.
 */
class MaintenanceBarrierArchTest extends HephaestusArchitectureTest {

    private static final String QUERY = "org.springframework.data.jpa.repository.Query";

    private static final Pattern NEW_EPOCH =
            Pattern.compile("lease_epoch\\s*=\\s*(\\w+\\.)?lease_epoch\\s*\\+\\s*1", Pattern.CASE_INSENSITIVE);

    private static final Pattern LEASED_INSERT =
            Pattern.compile("INSERT\\s+INTO\\s+\\w+\\s*\\([^)]*\\bleased_until\\b", Pattern.CASE_INSENSITIVE);

    private static final DescribedPredicate<JavaMethod> CLAIMS = DescribedPredicate.describe(
            "take a new lease",
            method -> query(method)
                    .filter(sql -> NEW_EPOCH.matcher(sql).find()
                            || LEASED_INSERT.matcher(sql).find())
                    .isPresent());

    @Test
    void everyClaimPathChecksTheMaintenanceBarrier() {
        methods()
                .that(CLAIMS)
                .should(new ArchCondition<JavaMethod>("refuse while a database migration runs") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        if (query(method).orElseThrow().contains(MaintenanceBarrier.LIFTED)) return;
                        events.add(SimpleConditionEvent.violated(
                                method,
                                method.getFullName() + " takes a new lease without MaintenanceBarrier.LIFTED: a claim"
                                        + " during a migration can lease a row that the migration converts"));
                    }
                })
                .check(classes);
    }

    /** The rule finds the claims of runs, actions and provider events, so it cannot pass by matching nothing. */
    @Test
    void theRuleFindsTheClaimsOfRunsActionsAndProviderEvents() {
        assertThat(classes.stream()
                        .flatMap(type -> type.getMethods().stream())
                        .filter(CLAIMS)
                        .map(method -> method.getOwner().getSimpleName() + "#" + method.getName()))
                .contains(
                        "AgentRunRepository#claim",
                        "AgentRunRepository#claimSubject",
                        "AgentActionRepository#claim",
                        "AgentActionRepository#claimById",
                        "AgentActionRepository#insertExecuting",
                        "ProviderEventRepository#claim");
    }

    private static Optional<String> query(JavaMethod method) {
        return method.getAnnotations().stream()
                .filter(annotation -> annotation.getRawType().getName().equals(QUERY))
                .flatMap(annotation -> annotation.get("value").stream())
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .findFirst();
    }
}
