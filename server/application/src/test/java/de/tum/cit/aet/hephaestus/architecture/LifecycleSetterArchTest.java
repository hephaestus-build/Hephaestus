package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaTypeVariable;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.tum.cit.aet.hephaestus.core.Lifecycle;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import jakarta.persistence.Entity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class LifecycleSetterArchTest extends HephaestusArchitectureTest {
    @Test
    void entitiesChangeLifecyclesThroughNamedTransitions() {
        classes()
                .that()
                .areAnnotatedWith(Entity.class)
                .should(new ArchCondition<JavaClass>("not set a lifecycle field") {
                    @Override
                    public void check(JavaClass aggregate, ConditionEvents events) {
                        for (var method : lifecycleSetters(aggregate)) {
                            events.add(SimpleConditionEvent.violated(
                                    method, method.getFullName() + " must use a named aggregate transition"));
                        }
                    }
                })
                .check(classes);
    }

    /**
     * Stored status and state fields whose enum does not implement {@link Lifecycle} yet. A field leaves this list when
     * its enum declares its transitions; a new entity field never joins it. The value is the owner and the reason.
     */
    private static final Map<String, String> UNMODELED = Map.ofEntries(
            Map.entry("AccountExport.status", "core: export worker writes it directly"),
            Map.entry("AgentSandbox.state", "agent: sandbox claims write it directly"),
            Map.entry("CommitIdentityResolution.state", "integration: resolver writes it directly"),
            Map.entry("Feedback.state", "practices: feedback store writes it directly"),
            Map.entry("FeedbackCopy.state", "practices: send service writes it directly"),
            Map.entry("GitHubInstallationRequest.status", "integration: installation flow"),
            Map.entry("ItemEvent.state", "practices: item event decisions"),
            Map.entry("McpClientGrant.status", "core: MCP client grants"),
            Map.entry("ConversationTurn.status", "conversation: turn service writes it directly"),
            Map.entry("OutlineCollection.state", "integration: collection selection"),
            Map.entry("PersonEvidenceCopy.state", "core: evidence custody"),
            Map.entry("ProductFeedbackFiling.state", "productfeedback: filing service"),
            Map.entry("ProviderResourceRepair.state", "integration: repair worker"),
            Map.entry("ScriptRun.status", "agent: script runs"),
            Map.entry("SyncJob.status", "integration: sync jobs"));

    /** The provider owns these values; the mirror copies what it reports, so they have no transitions of ours. */
    private static final Set<String> MIRRORED =
            Set.of("Issue.state", "Milestone.state", "PullRequestReview.state", "PullRequestReviewThread.state");

    /**
     * Covers enum fields named exactly {@code status} or {@code state}, declared or inherited. A lifecycle under another
     * name, such as {@code ReviewJob.processingStatus}, or stored as a string, such as {@code ProviderEventRow.state},
     * is not seen.
     */
    @Test
    void storedStatusesDeclareTheirTransitions() {
        var unmodeled = classes.stream()
                .filter(type -> type.isAnnotatedWith(Entity.class))
                .flatMap(type -> type.getAllFields().stream())
                .filter(field ->
                        field.getName().equals("status") || field.getName().equals("state"))
                .filter(field ->
                        field.getRawType().isEnum() && !field.getRawType().isAssignableTo(Lifecycle.class))
                .map(field -> field.getOwner().getSimpleName() + "." + field.getName())
                .filter(name -> !MIRRORED.contains(name))
                .collect(Collectors.toSet());
        assertThat(unmodeled)
                .as("A stored status enum implements Lifecycle; remove a field from UNMODELED when its enum does")
                .containsExactlyInAnyOrderElementsOf(UNMODELED.keySet());
    }

    private static List<JavaMethod> lifecycleSetters(JavaClass aggregate) {
        return aggregate.getAllMethods().stream()
                .filter(method -> method.getName().startsWith("set"))
                .filter(method -> method.getParameterTypes().stream().anyMatch(LifecycleSetterArchTest::isLifecycle))
                .toList();
    }

    @Test
    void shouldFindALifecycleSetterInheritedFromAGenericBase() {
        assertThat(lifecycleSetters(classesWithTests.get(InheritsLifecycleSetter.class)))
                .extracting(JavaMethod::getName)
                .containsExactly("setState");
    }

    private static boolean isLifecycle(JavaType type) {
        return type.toErasure().isAssignableTo(Lifecycle.class)
                || (type instanceof JavaTypeVariable<?> variable
                        && variable.getUpperBounds().stream().anyMatch(LifecycleSetterArchTest::isLifecycle));
    }

    @SuppressWarnings("UnusedMethod") // ArchUnit reads these fixture signatures without invoking them.
    private abstract static class GenericLifecycle<S extends Enum<S> & Lifecycle<S>> {
        abstract void setState(S state);

        abstract void transitionTo(S state);
    }

    private abstract static class InheritsLifecycleSetter extends GenericLifecycle<ReviewStatus> {}
}
